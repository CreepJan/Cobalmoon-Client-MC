/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package dev.cobalmoon.core.script;

import com.google.gson.Gson;
import org.luaj.vm2.Globals;
import org.luaj.vm2.LoadState;
import org.luaj.vm2.LuaError;
import org.luaj.vm2.LuaString;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.Varargs;
import org.luaj.vm2.compiler.LuaC;
import org.luaj.vm2.lib.Bit32Lib;
import org.luaj.vm2.lib.DebugLib;
import org.luaj.vm2.lib.OneArgFunction;
import org.luaj.vm2.lib.PackageLib;
import org.luaj.vm2.lib.StringLib;
import org.luaj.vm2.lib.TableLib;
import org.luaj.vm2.lib.TwoArgFunction;
import org.luaj.vm2.lib.VarArgFunction;
import org.luaj.vm2.lib.ZeroArgFunction;
import org.luaj.vm2.lib.jse.JseBaseLib;
import org.luaj.vm2.lib.jse.JseMathLib;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * One sandboxed Lua script. What it gets:
 * <ul>
 *   <li>basic Lua (no {@code load}, {@code dofile}, {@code require}, {@code io}, {@code debug}, {@code luajava});</li>
 *   <li>{@code string}, {@code table}, {@code math}, {@code bit32}, {@code os.time}/{@code os.clock};</li>
 *   <li>the {@code cobalmoon} table, whose functions check the permissions the player granted.</li>
 * </ul>
 * Every call into the script has an instruction budget, so an endless loop is stopped with an error
 * instead of freezing the game. This class has no Minecraft dependency (unit-tested on its own).
 */
public final class ScriptRuntime {
    /** What the game provides to scripts. Implemented by the script module. */
    public interface Host {
        void log(String message);

        /** Called only when the script has {@link Permission#CHAT_SEND}; plain chat only, never commands. */
        void sendChat(String message);

        /** Called only with {@link Permission#PLAYER_READ}. Keys: x, y, z, yaw, health, fps, cps. */
        Map<String, Object> player();

        Map<String, Object> loadStore();

        void saveStore(Map<String, Object> data);
    }

    public static final class ScriptException extends Exception {
        public ScriptException(String message) {
            super(message);
        }
    }

    public static final long INIT_BUDGET = 5_000_000;
    public static final long CALL_BUDGET = 300_000;
    static final int HOOK_STEP = 1_000;
    public static final int MAX_SOURCE_CHARS = 256 * 1024;
    static final int MAX_STRING = 1 << 20;
    static final int MAX_STORE_BYTES = 64 * 1024;
    static final long CHAT_INTERVAL_NANOS = 1_000_000_000L;
    private static final Pattern FORMAT_WIDTH = Pattern.compile("%[-+ #0]*\\d{4,}");
    private static final Gson GSON = new Gson();

    private final Globals g = new Globals();
    private final Set<Permission> granted;
    private final Host host;
    private long budget;
    private long used;
    private long lastChat;
    private Map<String, Object> store;

    public ScriptRuntime(String chunkName, String source, Set<Permission> granted, Host host) throws ScriptException {
        this.granted = granted.isEmpty() ? EnumSet.noneOf(Permission.class) : EnumSet.copyOf(granted);
        this.host = host;
        if (source.length() > MAX_SOURCE_CHARS) throw new ScriptException("script is too large (max 256 KB)");
        buildGlobals();
        LuaValue chunk;
        try {
            chunk = g.load(source, "@" + chunkName);
        } catch (LuaError e) {
            throw new ScriptException(clean(e));
        }
        run(INIT_BUDGET, () -> chunk.call());
    }

    // ---- sandbox ------------------------------------------------------------------------------

    private void buildGlobals() {
        g.load(new JseBaseLib());
        g.load(new PackageLib());
        g.load(new Bit32Lib());
        g.load(new TableLib());
        g.load(new StringLib());
        g.load(new JseMathLib());
        g.load(new DebugLib());
        LoadState.install(g);
        LuaC.install(g);

        // Instruction budget through a debug hook on this script's main thread; then hide debug.
        LuaValue sethook = g.get("debug").get("sethook");
        sethook.call(new VarArgFunction() {
            @Override
            public Varargs invoke(Varargs args) {
                used += HOOK_STEP;
                if (used > budget) throw new LuaError("script used too much CPU time (limit " + budget + " instructions)");
                return NONE;
            }
        }, LuaValue.valueOf(""), LuaValue.valueOf(HOOK_STEP));

        for (String name : new String[]{"debug", "dofile", "loadfile", "load", "loadstring", "require", "package",
                "collectgarbage", "module", "luajava", "io", "coroutine"}) {
            g.set(name, LuaValue.NIL);
        }

        // Strings: a private copy for this script, with size-limited rep/format and no dump. Method calls
        // on strings ("x"):rep() go through a shared metatable that scripts cannot reach (see getmetatable).
        LuaTable safeString = safeStringTable((LuaTable) g.get("string"));
        g.set("string", copy(safeString));
        installStringMetatable(safeString);
        LuaValue rawGetmetatable = g.get("getmetatable");
        g.set("getmetatable", new OneArgFunction() {
            @Override
            public LuaValue call(LuaValue v) {
                return v.istable() ? rawGetmetatable.call(v) : NIL;
            }
        });

        LuaTable os = new LuaTable();
        long start = System.nanoTime();
        os.set("time", new ZeroArgFunction() {
            @Override public LuaValue call() { return LuaValue.valueOf(System.currentTimeMillis() / 1000.0); }
        });
        os.set("clock", new ZeroArgFunction() {
            @Override public LuaValue call() { return LuaValue.valueOf((System.nanoTime() - start) / 1e9); }
        });
        g.set("os", os);
        g.set("print", new VarArgFunction() {
            @Override
            public Varargs invoke(Varargs args) {
                StringBuilder sb = new StringBuilder();
                for (int i = 1; i <= args.narg(); i++) {
                    if (i > 1) sb.append('\t');
                    sb.append(args.arg(i).tojstring());
                }
                host.log(limit(sb.toString(), 500));
                return NONE;
            }
        });
        g.set("cobalmoon", api());
    }

    private static volatile boolean stringMetatableInstalled;

    private static synchronized void installStringMetatable(LuaTable safeString) {
        if (stringMetatableInstalled) return;
        LuaTable meta = new LuaTable();
        meta.set(LuaValue.INDEX, copy(safeString));
        LuaString.s_metatable = meta;
        stringMetatableInstalled = true;
    }

    private static LuaTable copy(LuaTable t) {
        LuaTable out = new LuaTable();
        for (LuaValue k = t.next(LuaValue.NIL).arg1(); !k.isnil(); k = t.next(k).arg1()) out.set(k, t.get(k));
        return out;
    }

    private static LuaTable safeStringTable(LuaTable original) {
        LuaTable s = copy(original);
        s.set("dump", LuaValue.NIL);
        LuaValue rep = original.get("rep");
        s.set("rep", new VarArgFunction() {
            @Override
            public Varargs invoke(Varargs a) {
                long len = (long) a.checkjstring(1).length() * Math.max(0, a.checkint(2))
                        + (long) (a.isnil(3) ? 0 : a.checkjstring(3).length()) * Math.max(0, a.checkint(2) - 1);
                if (len > MAX_STRING) throw new LuaError("string.rep result too large (max 1 MB)");
                return rep.invoke(a);
            }
        });
        LuaValue format = original.get("format");
        s.set("format", new VarArgFunction() {
            @Override
            public Varargs invoke(Varargs a) {
                if (FORMAT_WIDTH.matcher(a.checkjstring(1)).find()) throw new LuaError("string.format width too large");
                return format.invoke(a);
            }
        });
        return s;
    }

    private void require(Permission p) {
        if (!granted.contains(p)) throw new LuaError("permission '" + p.id + "' not granted");
    }

    private LuaTable api() {
        LuaTable api = new LuaTable();
        api.set("log", new OneArgFunction() {
            @Override public LuaValue call(LuaValue v) {
                host.log(limit(v.tojstring(), 500));
                return NIL;
            }
        });
        api.set("has_permission", new OneArgFunction() {
            @Override public LuaValue call(LuaValue v) {
                return LuaValue.valueOf(Permission.of(v.checkjstring()).map(granted::contains).orElse(false));
            }
        });
        api.set("send_chat", new OneArgFunction() {
            @Override public LuaValue call(LuaValue v) {
                require(Permission.CHAT_SEND);
                String msg = v.checkjstring().replaceAll("\\p{Cntrl}", "").trim();
                if (msg.isEmpty()) return NIL;
                if (msg.startsWith("/")) throw new LuaError("scripts cannot send commands");
                if (msg.length() > 256) throw new LuaError("message too long (max 256)");
                long now = System.nanoTime();
                if (lastChat != 0 && now - lastChat < CHAT_INTERVAL_NANOS) throw new LuaError("send_chat is limited to 1 message per second");
                lastChat = now;
                host.sendChat(msg);
                return NIL;
            }
        });
        api.set("player", new ZeroArgFunction() {
            @Override public LuaValue call() {
                require(Permission.PLAYER_READ);
                return toLua(host.player());
            }
        });
        LuaTable store = new LuaTable();
        store.set("get", new OneArgFunction() {
            @Override public LuaValue call(LuaValue key) {
                require(Permission.STORAGE);
                Object v = store().get(key.checkjstring());
                return v == null ? NIL : toLua(v);
            }
        });
        store.set("set", new TwoArgFunction() {
            @Override public LuaValue call(LuaValue key, LuaValue value) {
                require(Permission.STORAGE);
                String k = key.checkjstring();
                if (k.length() > 64) throw new LuaError("store key too long (max 64)");
                Map<String, Object> s = store();
                Object old = s.get(k);
                if (value.isnil()) s.remove(k);
                else if (value.isboolean()) s.put(k, value.toboolean());
                else if (value.isnumber()) s.put(k, value.todouble());
                else if (value.isstring()) s.put(k, value.tojstring());
                else throw new LuaError("store values must be strings, numbers or booleans");
                if (GSON.toJson(s).length() > MAX_STORE_BYTES) {
                    if (old == null) s.remove(k);
                    else s.put(k, old);
                    throw new LuaError("store is full (max 64 KB)");
                }
                host.saveStore(s);
                return NIL;
            }
        });
        api.set("store", store);
        return api;
    }

    private Map<String, Object> store() {
        if (store == null) {
            Map<String, Object> loaded = host.loadStore();
            store = loaded == null ? new LinkedHashMap<>() : new LinkedHashMap<>(loaded);
        }
        return store;
    }

    private static LuaValue toLua(Object v) {
        if (v == null) return LuaValue.NIL;
        if (v instanceof Boolean b) return LuaValue.valueOf(b);
        if (v instanceof Number n) return LuaValue.valueOf(n.doubleValue());
        if (v instanceof Map<?, ?> m) {
            LuaTable t = new LuaTable();
            m.forEach((k, val) -> t.set(String.valueOf(k), toLua(val)));
            return t;
        }
        return LuaValue.valueOf(String.valueOf(v));
    }

    private static String limit(String s, int max) {
        return s.length() > max ? s.substring(0, max) + "…" : s;
    }

    // ---- calls --------------------------------------------------------------------------------

    private interface LuaCall {
        Object run();
    }

    private Object run(long limit, LuaCall call) throws ScriptException {
        budget = limit;
        used = 0;
        try {
            return call.run();
        } catch (LuaError e) {
            throw new ScriptException(clean(e));
        } catch (StackOverflowError e) {
            throw new ScriptException("stack overflow (endless recursion?)");
        }
    }

    private static String clean(LuaError e) {
        String m = e.getMessage() == null ? "error" : e.getMessage();
        int nl = m.indexOf('\n');
        return limit(nl > 0 ? m.substring(0, nl) : m, 300);
    }

    public boolean has(String function) {
        return g.get(function).isfunction();
    }

    /** Calls a global function if the script defines it. */
    public Varargs call(String function, LuaValue... args) throws ScriptException {
        LuaValue f = g.get(function);
        if (!f.isfunction()) return LuaValue.NONE;
        return (Varargs) run(CALL_BUDGET, () -> f.invoke(LuaValue.varargsOf(args)));
    }

    public void onTick() throws ScriptException {
        call("on_tick");
    }

    public void onChat(String text) throws ScriptException {
        if (granted.contains(Permission.CHAT_READ)) call("on_chat", LuaValue.valueOf(limit(text, 1000)));
    }

    public void onHit(double distance, boolean critical, boolean player) throws ScriptException {
        if (!granted.contains(Permission.COMBAT_READ)) return;
        LuaTable t = new LuaTable();
        t.set("distance", distance);
        t.set("critical", LuaValue.valueOf(critical));
        t.set("player", LuaValue.valueOf(player));
        call("on_hit", t);
    }

    /** Lines from {@code on_hud()} (strings; at most 20 lines of 200 characters). */
    public List<String> hudLines() throws ScriptException {
        if (!granted.contains(Permission.HUD_DRAW)) return List.of();
        Varargs r = call("on_hud");
        LuaValue v = r.arg1();
        List<String> out = new ArrayList<>();
        if (v.istable()) {
            for (int i = 1; i <= Math.min(20, v.length()); i++) out.add(limit(v.get(i).tojstring(), 200));
        } else if (v.isstring()) {
            out.add(limit(v.tojstring(), 200));
        }
        return out;
    }
}
