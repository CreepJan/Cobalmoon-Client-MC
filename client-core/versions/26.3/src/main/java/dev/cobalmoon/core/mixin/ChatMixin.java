/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package dev.cobalmoon.core.mixin;

import dev.cobalmoon.api.Cobalmoon;
import dev.cobalmoon.api.event.Events;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Posts every chat line shown to the player as plain text (26.x: private 4-argument addMessage). Observes only. */
@Mixin(value = ChatComponent.class, priority = 1100)
abstract class ChatMixin {
    @Inject(method = "addMessage", at = @At("HEAD"))
    private void cobalmoon$chat(CallbackInfo ci, @com.llamalad7.mixinextras.sugar.Local(argsOnly = true) Component message) {
        Cobalmoon.find().ifPresent(api -> api.events().post(new Events.ChatMessage(message.getString())));
    }
}
