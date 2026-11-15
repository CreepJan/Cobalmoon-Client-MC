/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package dev.cobalmoon.core.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import dev.cobalmoon.api.Cobalmoon;
import dev.cobalmoon.core.Branding;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Prefixes the window title with the Cobalmoon name. Uses MixinExtras' ModifyReturnValue so
 * other mods changing the title still chain correctly (no overwrite).
 */
@Mixin(value = Minecraft.class, priority = 1100)
abstract class MinecraftTitleMixin {

    @ModifyReturnValue(method = "createTitle", at = @At("RETURN"))
    private String cobalmoon$brandTitle(String original) {
        return Cobalmoon.find()
                .map(api -> Branding.windowTitle(original, api.clientVersion()))
                .orElse(original);
    }
}
