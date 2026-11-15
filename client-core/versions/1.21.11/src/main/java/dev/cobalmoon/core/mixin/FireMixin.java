/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package dev.cobalmoon.core.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.cobalmoon.core.modules.VisualModules;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.ScreenEffectRenderer;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Low fire: moves the first-person fire overlay down (1.21.11). */
@Mixin(value = ScreenEffectRenderer.class, priority = 1100)
abstract class FireMixin {
    @Inject(method = "renderFire", at = @At("HEAD"))
    private static void cobalmoon$lower(PoseStack pose, MultiBufferSource buffers, TextureAtlasSprite sprite, CallbackInfo ci) {
        pose.pushPose();
        pose.translate(0f, -VisualModules.LowFire.offset(), 0f);
    }

    @Inject(method = "renderFire", at = @At("RETURN"))
    private static void cobalmoon$restore(PoseStack pose, MultiBufferSource buffers, TextureAtlasSprite sprite, CallbackInfo ci) {
        pose.popPose();
    }
}
