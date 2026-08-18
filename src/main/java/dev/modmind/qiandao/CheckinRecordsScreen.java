package dev.modmind.qiandao;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.Inventory;

public final class CheckinRecordsScreen extends AbstractContainerScreen<CheckinRecordsScreenHandler> {
    private static final Identifier CONTAINER_BACKGROUND =
            Identifier.withDefaultNamespace("textures/gui/container/generic_54.png");
    private final int containerRows;

    public CheckinRecordsScreen(CheckinRecordsScreenHandler menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
        this.containerRows = menu.getRowCount();
        this.imageHeight = 114 + containerRows * 18;
        this.inventoryLabelY = imageHeight - 94;
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float delta) {
        super.render(graphics, mouseX, mouseY, delta);
        renderTooltip(graphics, mouseX, mouseY);
    }

    @Override
    protected void renderBg(GuiGraphics graphics, float delta, int mouseX, int mouseY) {
        int left = (width - imageWidth) / 2;
        int top = (height - imageHeight) / 2;
        RenderPipeline pipeline = RenderPipelines.GUI_TEXTURED;
        graphics.blit(pipeline, CONTAINER_BACKGROUND, left, top, 0.0F, 0.0F,
                imageWidth, containerRows * 18 + 17, 256, 256);
        graphics.blit(pipeline, CONTAINER_BACKGROUND, left, top + containerRows * 18 + 17,
                0.0F, 126.0F, imageWidth, 96, 256, 256);
    }
}
