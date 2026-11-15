/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package dev.cobalmoon.core.mixin;

import dev.cobalmoon.core.CobalmoonClient;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Observes the local player's attacks (for reach, combo, crits, hitsounds). Never changes them. */
@Mixin(value = MultiPlayerGameMode.class, priority = 1100)
abstract class MultiPlayerGameModeMixin {
    @Inject(method = "attack", at = @At("HEAD"))
    private void cobalmoon$onAttack(Player player, Entity target, CallbackInfo ci) {
        var tracker = CobalmoonClient.combat();
        if (tracker != null) tracker.onAttack(player, target);
    }
}
