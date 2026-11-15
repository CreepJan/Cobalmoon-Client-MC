/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package dev.cobalmoon.core.mixin;

import com.llamalad7.mixinextras.sugar.Local;
import dev.cobalmoon.core.modules.VisualModules;
import net.minecraft.client.renderer.fog.FogData;
import net.minecraft.client.renderer.fog.FogRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** No fog: adjusts the computed fog right before it is uploaded (1.21.11). */
@Mixin(value = FogRenderer.class, priority = 1100)
abstract class FogMixin {
    @Inject(method = "setupFog", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/fog/FogRenderer;updateBuffer(Ljava/nio/ByteBuffer;ILorg/joml/Vector4f;FFFFFF)V"))
    private void cobalmoon$noFog(CallbackInfoReturnable<?> cir, @Local FogData fog) {
        VisualModules.NoFog.apply(fog);
    }
}
