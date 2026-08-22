package dev.modmind.omnitools;

import net.fabricmc.api.ClientModInitializer;
import net.minecraft.client.gui.screens.MenuScreens;

public final class ModMindClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        MenuScreens.register(CheckinScreenHandler.TYPE, CheckinScreen::new);
        MenuScreens.register(CheckinRecordsScreenHandler.TYPE, CheckinRecordsScreen::new);
        MenuScreens.register(OnlineTimeRewardScreenHandler.TYPE, OnlineTimeRewardScreen::new);
        MenuScreens.register(ShopScreenHandler.TYPE, ShopScreen::new);
        MenuScreens.register(TitleScreenHandler.TYPE, TitleScreen::new);
        MenuScreens.register(CloudStorageScreenHandler.TYPE, CloudStorageScreen::new);
        MenuScreens.register(AchievementScreenHandler.TYPE, AchievementScreen::new);
    }
}
