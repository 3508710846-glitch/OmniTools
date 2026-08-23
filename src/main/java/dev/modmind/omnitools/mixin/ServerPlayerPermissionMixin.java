package dev.modmind.omnitools.mixin;

import dev.modmind.omnitools.TitleEffectService;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.PermissionSet;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ServerPlayer.class)
abstract class ServerPlayerPermissionMixin {
    @Inject(method = "permissions", at = @At("RETURN"), cancellable = true)
    private void omnitools$addActiveTitlePermissions(CallbackInfoReturnable<PermissionSet> callback) {
        callback.setReturnValue(TitleEffectService.permissionSet((ServerPlayer) (Object) this,
                callback.getReturnValue()));
    }
}
