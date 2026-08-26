package dev.modmind.omnitools;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;

/** Small, consistent server-authoritative feedback for menu actions. */
public final class GuiFeedbackService {
    private GuiFeedbackService() {
    }

    public static void click(ServerPlayer player) {
        player.playSound(SoundEvents.UI_BUTTON_CLICK.value(), 0.7f, 1.0f);
    }

    public static void success(ServerPlayer player) {
        player.playSound(SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, 1.0f, 1.0f);
    }

    public static void failure(ServerPlayer player) {
        player.playSound(SoundEvents.UI_BUTTON_CLICK.value(), 0.5f, 0.6f);
    }
}
