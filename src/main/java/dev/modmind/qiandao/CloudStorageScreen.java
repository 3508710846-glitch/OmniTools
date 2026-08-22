package dev.modmind.qiandao;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.Inventory;

/** Client presentation for the six-row cloud storage chest. */
public final class CloudStorageScreen extends AbstractContainerScreen<CloudStorageScreenHandler> {
    private static final Identifier CONTAINER_BACKGROUND =
            Identifier.withDefaultNamespace("textures/gui/container/generic_54.png");
    private static final int HEADER_TEXT_COLOR = 0xFF9CE7E0;
    private static final int SECONDARY_TEXT_COLOR = 0xFFC7D2D6;

    public CloudStorageScreen(CloudStorageScreenHandler menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
        this.imageHeight = 114 + CloudStorageScreenHandler.ROWS * 18;
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
        graphics.fill(left - 1, top - 1, left + imageWidth + 1, top + imageHeight + 1, 0xFF273942);
        graphics.blit(pipeline, CONTAINER_BACKGROUND, left, top, 0.0F, 0.0F,
                imageWidth, CloudStorageScreenHandler.ROWS * 18 + 17, 256, 256);
        graphics.blit(pipeline, CONTAINER_BACKGROUND, left,
                top + CloudStorageScreenHandler.ROWS * 18 + 17, 0.0F, 126.0F, imageWidth, 96, 256, 256);
        graphics.fill(left, top, left + imageWidth, top + 18, 0xE01D3440);
        graphics.fill(left, top + 17, left + imageWidth, top + 18, 0xFF71D1C6);
        int actionRowTop = top + 17 + CloudStorageData.SLOTS_PER_PAGE / 9 * 18;
        graphics.fill(left + 7, actionRowTop, left + imageWidth - 7, actionRowTop + 1, 0xFF52717B);
    }

    @Override
    protected void renderLabels(GuiGraphics graphics, int mouseX, int mouseY) {
        graphics.drawString(font, title, titleLabelX, titleLabelY, HEADER_TEXT_COLOR, true);
        graphics.drawString(font, playerInventoryTitle, inventoryLabelX, inventoryLabelY, SECONDARY_TEXT_COLOR, true);
    }
}
