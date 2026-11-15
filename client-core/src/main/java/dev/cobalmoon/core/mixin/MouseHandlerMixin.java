/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package dev.cobalmoon.core.mixin;

import dev.cobalmoon.api.Cobalmoon;
import dev.cobalmoon.api.event.Events;
import dev.cobalmoon.core.compat.Platform;
import net.minecraft.client.MouseHandler;
import net.minecraft.client.input.MouseButtonInfo;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Posts {@link Events.MouseButton} for every physical button press/release. Observes only. */
@Mixin(value = MouseHandler.class, priority = 1100)
abstract class MouseHandlerMixin {
    private static final int GLFW_PRESS = 1, GLFW_RELEASE = 0;

    @Inject(method = "onButton", at = @At("HEAD"))
    private void cobalmoon$onButton(long window, MouseButtonInfo info, int action, CallbackInfo ci) {
        if (action != GLFW_PRESS && action != GLFW_RELEASE) return;
        Cobalmoon.find().ifPresent(api -> api.events().post(
                new Events.MouseButton(info.button(), action == GLFW_PRESS, System.nanoTime(), !Platform.isScreenOpen())));
    }
}
