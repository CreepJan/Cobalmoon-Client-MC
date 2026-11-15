/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package dev.cobalmoon.core.mixin;

import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.blaze3d.vertex.PoseStack;
import dev.cobalmoon.core.modules.VisualModules;
import net.minecraft.client.renderer.ItemInHandRenderer;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Low shield: lowers a held shield in first person (1.21.11). */
@Mixin(value = ItemInHandRenderer.class, priority = 1100)
abstract class ShieldMixin {
    @Unique private boolean cobalmoon$shield;

    @Inject(method = "renderArmWithItem", at = @At("HEAD"))
    private void cobalmoon$begin(CallbackInfo ci, @Local(argsOnly = true) ItemStack stack) {
        cobalmoon$shield = stack.is(Items.SHIELD);
    }

    @Inject(method = "renderArmWithItem", at = @At("RETURN"))
    private void cobalmoon$end(CallbackInfo ci) {
        cobalmoon$shield = false;
    }

    @Inject(method = "applyItemArmTransform", at = @At("TAIL"))
    private void cobalmoon$lower(PoseStack pose, HumanoidArm arm, float equip, CallbackInfo ci) {
        if (cobalmoon$shield) pose.translate(0f, -VisualModules.LowShield.offset(), 0f);
    }
}
