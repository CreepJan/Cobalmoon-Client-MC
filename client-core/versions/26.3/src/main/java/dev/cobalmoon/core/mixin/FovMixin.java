/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package dev.cobalmoon.core.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import dev.cobalmoon.core.modules.ZoomModule;
import net.minecraft.client.Camera;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Zoom: scales the world field of view. In 26.x the camera computes it once per frame in
 * Camera#calculateFov and stores it; the renderer reads that stored value, so this hooks the computation.
 */
@Mixin(value = Camera.class, priority = 1100)
abstract class FovMixin {
    @ModifyReturnValue(method = "calculateFov", at = @At("RETURN"))
    private float cobalmoon$zoom(float fov) {
        return ZoomModule.fov(fov);
    }
}
