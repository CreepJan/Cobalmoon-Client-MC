/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package dev.cobalmoon.core.mixin;

import dev.cobalmoon.core.modules.ZoomModule;
import net.minecraft.client.MouseHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** While zooming, the mouse wheel changes the zoom level instead of the hotbar slot. */
@Mixin(value = MouseHandler.class, priority = 1100)
abstract class MouseScrollMixin {
    @Inject(method = "onScroll", at = @At("HEAD"), cancellable = true)
    private void cobalmoon$zoomScroll(long window, double dx, double dy, CallbackInfo ci) {
        if (ZoomModule.consumeScroll(dy)) ci.cancel();
    }
}
