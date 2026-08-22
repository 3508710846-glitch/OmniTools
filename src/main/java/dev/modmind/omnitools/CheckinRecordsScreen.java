package dev.modmind.omnitools;

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
    private static final int HEADER_TEXT_COLOR = 0xFFF6D58A;
    private static final int SECONDARY_TEXT_COLOR = 0xFFCCC4D7;
    private final int containerRows;

    public CheckinRecordsScreen(CheckinRecordsScreenHandler menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
        this.containerRows = menu.getRowCount();
        this.imageHeight = 114 + containerRows * 18;
        this.inventoryLabelY = imageHeight - 94;
        this.titleLabelX = 8;
        this.titleLabelY = 6;
        this.inventoryLabelX = 8;
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
        graphics.fill(left - 3, top - 3, left + imageWidth + 3, top + imageHeight + 3, 0xD0000000);
        graphics.fill(left - 1, top - 1, left + imageWidth + 1, top + imageHeight + 1, 0xFF3A3246);
        graphics.blit(pipeline, CONTAINER_BACKGROUND, left, top, 0.0F, 0.0F,
                imageWidth, containerRows * 18 + 17, 256, 256);
        graphics.blit(pipeline, CONTAINER_BACKGROUND, left, top + containerRows * 18 + 17,
                0.0F, 126.0F, imageWidth, 96, 256, 256);
        graphics.fill(left, top, left + imageWidth, top + 18, 0xE02A2634);
        graphics.fill(left, top + 17, left + imageWidth, top + 18, 0xFF6FB8B0);
        int dividerY = top + containerRows * 18 + 17;
        graphics.fill(left + 7, dividerY, left + imageWidth - 7, dividerY + 1, 0xFF6E617E);
        graphics.fill(left + 7, top + imageHeight - 1, left + imageWidth - 7, top + imageHeight,
                0xFF211D29);

        for (int row = 0; row < containerRows; row++) {
            if ((row & 1) == 0) {
                int rowTop = top + 17 + row * 18;
                graphics.fill(left + 7, rowTop, left + imageWidth - 7, rowTop + 18, 0x0AFFFFFF);
            }
        }
    }

    @Override
    protected void renderLabels(GuiGraphics graphics, int mouseX, int mouseY) {
        graphics.drawString(font, title, titleLabelX, titleLabelY, HEADER_TEXT_COLOR, true);
        graphics.drawString(font, playerInventoryTitle, inventoryLabelX, inventoryLabelY, SECONDARY_TEXT_COLOR, true);
        Component subtitle = Component.translatable("gui.omnitools.records.subtitle");
        int subtitleWidth = font.width(subtitle);
        if (font.width(title) + subtitleWidth <= imageWidth - 24) {
            graphics.drawString(font, subtitle, imageWidth - subtitleWidth - 8, titleLabelY, 0xFFB9D8D1, true);
        }
    }
}
