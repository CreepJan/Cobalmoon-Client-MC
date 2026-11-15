package dev.cobalmoon.core;

import dev.cobalmoon.core.script.Permission;
import dev.cobalmoon.core.script.ScriptManifest;
import dev.cobalmoon.core.script.ScriptRuntime;
import dev.cobalmoon.core.script.ScriptRuntime.ScriptException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class ScriptSandboxTest {

    /** Records what the script asked the game to do. */
    static final class FakeHost implements ScriptRuntime.Host {
        final List<String> logs = new ArrayList<>();
        final List<String> sent = new ArrayList<>();
        Map<String, Object> saved;

        @Override public void log(String m) { logs.add(m); }
        @Override public void sendChat(String m) { sent.add(m); }
        @Override public Map<String, Object> player() {
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("x", 1.5); p.put("y", 64.0); p.put("z", -3.0); p.put("health", 20.0); p.put("fps", 144); p.put("cps", 7);
            return p;
        }
        @Override public Map<String, Object> loadStore() { return saved; }
        @Override public void saveStore(Map<String, Object> d) { saved = new LinkedHashMap<>(d); }
    }

    static ScriptRuntime run(String src, Permission... perms) throws ScriptException {
        Set<Permission> p = perms.length == 0 ? EnumSet.noneOf(Permission.class) : EnumSet.of(perms[0], perms);
        return new ScriptRuntime("test.lua", src, p, new FakeHost());
    }

    // ---- escapes ------------------------------------------------------------------------------

    @ParameterizedTest
    @ValueSource(strings = {"io", "debug", "luajava", "require", "package", "dofile", "loadfile", "load", "loadstring", "collectgarbage", "coroutine"})
    void dangerousGlobalsAreAbsent(String name) throws Exception {
        FakeHost h = new FakeHost();
        new ScriptRuntime("t.lua", "cobalmoon.log(tostring(" + name + "))", Set.of(), h);
        assertEquals(List.of("nil"), h.logs, name + " must not exist");
    }

    @Test
    void osOnlyHasTimeAndClock() throws Exception {
        FakeHost h = new FakeHost();
        new ScriptRuntime("t.lua", "cobalmoon.log(tostring(os.execute)..' '..tostring(os.exit)..' '..tostring(os.getenv)..' '..type(os.time()))", Set.of(), h);
        assertEquals(List.of("nil nil nil number"), h.logs);
    }

    @Test
    void noWayToJavaOrFiles() {
        String[] attempts = {
                "luajava.bindClass('java.lang.Runtime')",
                "io.open('C:/Windows/win.ini')",
                "require('os')",
                "string.dump(print)",
                "load('return 1')()",
                "getmetatable('').__index.rep = nil",
        };
        for (String a : attempts) assertThrows(ScriptException.class, () -> run(a), a);
    }

    @Test
    void scriptsCannotTamperWithStringsSharedByOtherScripts() throws Exception {
        run("string.upper = function() return 'pwned' end");
        FakeHost h = new FakeHost();
        new ScriptRuntime("b.lua", "cobalmoon.log(('ok'):upper()) cobalmoon.log(string.upper('ok'))", Set.of(), h);
        assertEquals(List.of("OK", "OK"), h.logs);
    }

    // ---- resource limits ----------------------------------------------------------------------

    @Test
    void endlessLoopsAreStoppedInsteadOfFreezingTheGame() {
        ScriptException e = assertTimeoutPreemptively(Duration.ofSeconds(5), () -> assertThrows(ScriptException.class, () -> run("while true do end")));
        assertTrue(e.getMessage().contains("CPU"), e.getMessage());
    }

    @Test
    void endlessLoopInACallbackIsStoppedAndTheScriptStaysUsable() throws Exception {
        ScriptRuntime r = run("n = 0 function on_tick() n = n + 1 if n == 2 then while true do end end end function on_hud() return {tostring(n)} end", Permission.HUD_DRAW);
        r.onTick();
        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> assertThrows(ScriptException.class, r::onTick));
        assertEquals(List.of("2"), r.hudLines(), "budget resets for the next call");
    }

    @Test
    void endlessRecursionIsStopped() {
        assertThrows(ScriptException.class, () -> run("local function f() return f() + 1 end f()"));
    }

    @Test
    void memoryBombsAreRejected() {
        assertThrows(ScriptException.class, () -> run("local s = string.rep('x', 100000000)"));
        assertThrows(ScriptException.class, () -> run("local s = ('x'):rep(100000000)"));
        assertThrows(ScriptException.class, () -> run("local s = string.format('%99999999d', 1)"));
        assertThrows(ScriptException.class, () -> run("local s = 'x' .. string.rep('y', 2000000)"));
    }

    @Test
    void hugeSourcesAreRejected() {
        assertThrows(ScriptException.class, () -> run("-- " + "x".repeat(ScriptRuntime.MAX_SOURCE_CHARS)));
    }

    // ---- permissions & API --------------------------------------------------------------------

    @Test
    void permissionsAreEnforced() {
        var e = assertThrows(ScriptException.class, () -> run("cobalmoon.send_chat('hi')"));
        assertTrue(e.getMessage().contains("chat.send"), e.getMessage());
        assertThrows(ScriptException.class, () -> run("cobalmoon.player()"));
        assertThrows(ScriptException.class, () -> run("cobalmoon.store.set('a', 1)"));
    }

    @Test
    void chatIsPlainTextRateLimitedAndNeverACommand() throws Exception {
        FakeHost h = new FakeHost();
        ScriptRuntime r = new ScriptRuntime("t.lua", "function on_tick() cobalmoon.send_chat('gg') end", EnumSet.of(Permission.CHAT_SEND), h);
        r.onTick();
        assertThrows(ScriptException.class, r::onTick, "second message within a second");
        assertEquals(List.of("gg"), h.sent);
        assertThrows(ScriptException.class, () -> new ScriptRuntime("c.lua", "cobalmoon.send_chat('/op Steve')", EnumSet.of(Permission.CHAT_SEND), new FakeHost()));
    }

    @Test
    void hudPlayerChatAndStorageWork() throws Exception {
        FakeHost h = new FakeHost();
        ScriptRuntime r = new ScriptRuntime("t.lua", """
                hits = 0
                last = ''
                function on_hit(e) hits = hits + 1 end
                function on_chat(t) last = t end
                function on_hud()
                  local p = cobalmoon.player()
                  return { 'Hits ' .. hits, 'Y ' .. p.y, last }
                end
                cobalmoon.store.set('runs', (cobalmoon.store.get('runs') or 0) + 1)
                """, EnumSet.of(Permission.HUD_DRAW, Permission.PLAYER_READ, Permission.COMBAT_READ, Permission.CHAT_READ, Permission.STORAGE), h);
        r.onHit(2.9, true, true);
        r.onChat("Winner: Steve");
        assertEquals(List.of("Hits 1", "Y 64", "Winner: Steve"), r.hudLines());
        assertEquals(1.0, h.saved.get("runs"));
    }

    @Test
    void callbacksWithoutPermissionAreNotCalled() throws Exception {
        FakeHost h = new FakeHost();
        ScriptRuntime r = new ScriptRuntime("t.lua", "function on_chat(t) cobalmoon.log('saw ' .. t) end function on_hud() return {'x'} end", Set.of(), h);
        r.onChat("secret");
        assertTrue(h.logs.isEmpty(), "chat.read not granted");
        assertTrue(r.hudLines().isEmpty(), "hud.draw not granted");
    }

    @Test
    void storageIsSizeLimited() throws Exception {
        ScriptRuntime r = run("function fill() for i = 1, 100 do cobalmoon.store.set('k' .. i, string.rep('x', 1000)) end end", Permission.STORAGE);
        var e = assertThrows(ScriptException.class, () -> r.call("fill"));
        assertTrue(e.getMessage().contains("full"), e.getMessage());
    }

    @ParameterizedTest
    @ValueSource(strings = {"session-timer", "best-combo"})
    void bundledExamplesRunWithTheirOwnPermissions(String name) throws Exception {
        String base = "/assets/cobalmoon/examples/scripts/" + name + "/";
        String manifestJson, source;
        try (var m = getClass().getResourceAsStream(base + "script.json"); var s = getClass().getResourceAsStream(base + "main.lua")) {
            manifestJson = new String(m.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            source = new String(s.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
        ScriptManifest manifest = ScriptManifest.parse(manifestJson);
        assertEquals(name, manifest.id());
        assertTrue(manifest.unknownPermissions().isEmpty());
        ScriptRuntime r = new ScriptRuntime(name, source, manifest.permissions(), new FakeHost());
        r.onHit(2.8, false, true);
        r.onHit(3.0, true, true);
        List<String> lines = r.hudLines();
        assertFalse(lines.isEmpty(), "example draws something");
        assertTrue(String.join(" ", lines).matches(".*\\d.*"), lines.toString());
    }

    // ---- manifest -----------------------------------------------------------------------------

    @Test
    void manifestValidation() {
        ScriptManifest m = ScriptManifest.parse("{\"id\":\"timer\",\"name\":\"Timer\",\"permissions\":[\"hud.draw\",\"files.write\"]}");
        assertEquals("main.lua", m.main());
        assertEquals(Set.of(Permission.HUD_DRAW), m.permissions());
        assertEquals(List.of("files.write"), m.unknownPermissions(), "unknown permissions are reported, never granted");
        assertThrows(IllegalArgumentException.class, () -> ScriptManifest.parse("{\"id\":\"../evil\"}"));
        assertThrows(IllegalArgumentException.class, () -> ScriptManifest.parse("{\"id\":\"a\",\"main\":\"../x.lua\"}"));
        assertThrows(IllegalArgumentException.class, () -> ScriptManifest.parse("not json"));
    }
}
