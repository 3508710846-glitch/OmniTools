package dev.modmind.qiandao.mixin.client;

import dev.modmind.qiandao.TitleDisplayService;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Player.class)
abstract class PlayerNameTagMixin {
    @Inject(method = "getDisplayName", at = @At("HEAD"), cancellable = true)
    private void qiandao$renderManagedLegendaryNameTag(CallbackInfoReturnable<Component> callback) {
        Component titleName = TitleDisplayService.unwrapManagedNameTag(((Player) (Object) this).getCustomName());
        if (titleName != null) {
            callback.setReturnValue(titleName);
        }
    }
}
