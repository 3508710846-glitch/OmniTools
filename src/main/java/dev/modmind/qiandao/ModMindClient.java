package dev.modmind.qiandao;

import net.fabricmc.api.ClientModInitializer;
import net.minecraft.client.gui.screens.MenuScreens;

public final class ModMindClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        MenuScreens.register(CheckinScreenHandler.TYPE, CheckinScreen::new);
    }
}
