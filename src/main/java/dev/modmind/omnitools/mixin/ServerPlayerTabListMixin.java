package dev.modmind.omnitools.mixin;

import dev.modmind.omnitools.TitleDisplayService;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ServerPlayer.class)
abstract class ServerPlayerTabListMixin {
    @Inject(method = "getTabListDisplayName", at = @At("HEAD"), cancellable = true)
    private void omnitools$addTitleToTabList(CallbackInfoReturnable<Component> callback) {
        Component displayName = TitleDisplayService.tabListDisplayName((ServerPlayer) (Object) this);
        if (displayName != null) {
            callback.setReturnValue(displayName);
        }
    }
}
