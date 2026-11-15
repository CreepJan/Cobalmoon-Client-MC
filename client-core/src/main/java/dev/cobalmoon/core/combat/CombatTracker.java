/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package dev.cobalmoon.core.combat;

import dev.cobalmoon.api.event.EventBus;
import dev.cobalmoon.api.event.Events;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Turns raw game state into combat events: {@link Events.Attack} (fed by the attack mixin),
 * {@link Events.HitConfirmed} (the target's hurt animation started shortly after our attack),
 * {@link Events.PlayerHurt}, and server join/leave. It only observes; it never sends anything.
 */
public final class CombatTracker {
    /** Ticks an attack waits for the target's hurt animation before it counts as a miss. */
    static final int CONFIRM_WINDOW_TICKS = 6;

    private record Pending(int entityId, double distance, boolean critical, boolean player, int hurtTimeBefore, int tick) {}

    private final EventBus bus;
    private final List<Pending> pending = new ArrayList<>();
    private int tick;
    private int lastOwnHurtTime;
    private boolean inWorld;
    private String server;

    public CombatTracker(EventBus bus) {
        this.bus = bus;
    }

    /** Distance from a point to the nearest point of a box (0 if inside). */
    public static double distanceToBox(double px, double py, double pz,
                                       double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
        double dx = Math.max(Math.max(minX - px, 0), px - maxX);
        double dy = Math.max(Math.max(minY - py, 0), py - maxY);
        double dz = Math.max(Math.max(minZ - pz, 0), pz - maxZ);
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    /** Vanilla critical-hit conditions (as of 1.21/26.x). */
    public static boolean isCritical(Player p) {
        return p.fallDistance > 0
                && !p.onGround()
                && !p.onClimbable()
                && !p.isInWater()
                && !p.hasEffect(MobEffects.BLINDNESS)
                && !p.isPassenger()
                && !p.isSprinting()
                && p.getAttackStrengthScale(0.5f) > 0.9f;
    }

    /** Called by the attack mixin before the attack packet is sent. */
    public void onAttack(Player player, Entity target) {
        Vec3 eye = player.getEyePosition();
        AABB box = target.getBoundingBox();
        double distance = distanceToBox(eye.x, eye.y, eye.z, box.minX, box.minY, box.minZ, box.maxX, box.maxY, box.maxZ);
        boolean critical = target instanceof LivingEntity && isCritical(player);
        boolean isPlayer = target instanceof Player;
        int hurtBefore = target instanceof LivingEntity le ? le.hurtTime : 0;
        bus.post(new Events.Attack(target.getId(), distance, critical, isPlayer));
        pending.removeIf(p -> p.entityId == target.getId());
        pending.add(new Pending(target.getId(), distance, critical, isPlayer, hurtBefore, tick));
    }

    /** Called at the end of every client tick. */
    public void tick(Minecraft mc) {
        tick++;
        Player self = mc.player;
        boolean nowInWorld = mc.level != null && self != null;
        if (nowInWorld && !inWorld) {
            ServerData data = mc.getCurrentServer();
            server = data != null && data.ip != null ? data.ip.trim().toLowerCase(Locale.ROOT) : null;
            bus.post(new Events.ServerJoin(server));
        } else if (!nowInWorld && inWorld) {
            pending.clear();
            bus.post(Events.ServerLeave.INSTANCE);
        }
        inWorld = nowInWorld;
        if (!nowInWorld) return;

        // Own damage: hurtTime jumps up when the hurt animation (re)starts.
        if (self.hurtTime > lastOwnHurtTime) bus.post(new Events.PlayerHurt(self.getHealth()));
        lastOwnHurtTime = self.hurtTime;

        for (var it = pending.iterator(); it.hasNext(); ) {
            Pending p = it.next();
            Entity e = mc.level.getEntity(p.entityId);
            if (e instanceof LivingEntity le && le.hurtTime > p.hurtTimeBefore) {
                bus.post(new Events.HitConfirmed(p.entityId, p.distance, p.critical, p.player));
                it.remove();
            } else if (tick - p.tick > CONFIRM_WINDOW_TICKS || e == null) {
                it.remove();
            }
        }
    }

    /** Address of the current server, lower-case, or null in singleplayer / menus. */
    public String server() {
        return inWorld ? server : null;
    }
}
