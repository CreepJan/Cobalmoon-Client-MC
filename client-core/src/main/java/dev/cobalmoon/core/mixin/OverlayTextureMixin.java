/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package dev.cobalmoon.core.mixin;

import com.mojang.blaze3d.platform.NativeImage;
import dev.cobalmoon.core.modules.HitColorModule;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.renderer.texture.OverlayTexture;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/**
 * Lets the hit-colour module repaint the "hurt" half of the entity overlay texture (vanilla: translucent
 * red). The other half (white flash) is left as vanilla. Implements {@link HitColorModule.Target}.
 */
@Mixin(value = OverlayTexture.class, priority = 1100)
abstract class OverlayTextureMixin implements HitColorModule.Target {
    @Shadow @Final private DynamicTexture texture;

    @Override
    public void cobalmoon$paintHurt(int argb) {
        NativeImage pixels = texture.getPixels();
        if (pixels == null) return;
        // Rows 0-7 hold the hurt colour (same layout as the vanilla constructor).
        for (int y = 0; y < 8; y++) {
            for (int x = 0; x < 16; x++) pixels.setPixel(x, y, argb);
        }
        texture.upload();
    }
}
