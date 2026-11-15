/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package dev.cobalmoon.core.mixin;

import dev.cobalmoon.core.modules.VisualModules;
import net.minecraft.client.renderer.fog.FogData;
import net.minecraft.client.renderer.fog.FogRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** No fog: adjusts the fog data right before it is uploaded (26.x: public updateBuffer(FogData)). */
@Mixin(value = FogRenderer.class, priority = 1100)
abstract class FogMixin {
    @Inject(method = "updateBuffer(Lnet/minecraft/client/renderer/fog/FogData;)V", at = @At("HEAD"))
    private void cobalmoon$noFog(FogData fog, CallbackInfo ci) {
        VisualModules.NoFog.apply(fog);
    }
}
