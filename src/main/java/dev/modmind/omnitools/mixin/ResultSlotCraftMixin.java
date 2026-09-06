package dev.modmind.omnitools.mixin;

import dev.modmind.omnitools.ModMindEntry;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ResultSlot;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Reports only successful server-side crafting result takes; no registry-wide statistic polling. */
@Mixin(ResultSlot.class)
abstract class ResultSlotCraftMixin {
    @Inject(method = "onTake(Lnet/minecraft/world/entity/player/Player;Lnet/minecraft/world/item/ItemStack;)V", at = @At("TAIL"))
    private void omnitools$recordCraftedResult(Player player, ItemStack crafted, CallbackInfo callback) {
        ModMindEntry.recordCraftedItem(player, crafted);
    }
}
