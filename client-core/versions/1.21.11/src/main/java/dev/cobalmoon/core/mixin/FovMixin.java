/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package dev.cobalmoon.core.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import dev.cobalmoon.core.modules.ZoomModule;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Zoom: scales the world field of view (1.21.11: GameRenderer#getFov). */
@Mixin(value = GameRenderer.class, priority = 1100)
abstract class FovMixin {
    @ModifyReturnValue(method = "getFov", at = @At("RETURN"))
    private float cobalmoon$zoom(float fov) {
        return ZoomModule.fov(fov);
    }
}
