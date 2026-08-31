package dev.modmind.omnitools;

import dev.modmind.omnitools.config.ModuleId;
import dev.modmind.omnitools.packages.PackageData;
import dev.modmind.omnitools.packages.PackageInstance;
import dev.modmind.omnitools.packages.PackageService;
import dev.modmind.omnitools.permissions.CommandAction;
import dev.modmind.omnitools.text.TextTemplateRenderer;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.List;
import java.util.UUID;

/** Final server-authoritative confirmation before a virtual package starts delivery. */
public final class PackageConfirmScreenHandler extends ChestMenu {
    private static final int SIZE = 27;
    private static final int DETAIL_SLOT = 13;
    private static final int CONFIRM_SLOT = 11;
    private static final int CANCEL_SLOT = 15;

    private final SimpleContainer container;
    private final ServerPlayer owner;
    private final UUID ownerId;
    private final UUID instanceId;
    private final int listPage;
    private final int previewPage;

    private PackageConfirmScreenHandler(int syncId, Inventory inventory, SimpleContainer container, ServerPlayer owner,
                                        UUID instanceId, int listPage, int previewPage) {
        super(MenuType.GENERIC_9x3, syncId, inventory, container, 3);
        this.container = container;
        this.owner = owner;
        this.ownerId = owner == null ? null : owner.getUUID();
        this.instanceId = instanceId;
        this.listPage = Math.max(0, listPage);
        this.previewPage = Math.max(0, previewPage);
        if (owner != null) {
            render();
        }
    }

    static void open(ServerPlayer player, UUID instanceId, int listPage, int previewPage) {
        player.openMenu(new SimpleMenuProvider(
                (syncId, inventory, ignored) -> new PackageConfirmScreenHandler(syncId, inventory,
                        new SimpleContainer(SIZE), player, instanceId, listPage, previewPage),
                Component.literal("确认打开礼包")));
    }

    @Override
    public void clicked(int slotId, int button, ClickType clickType, Player player) {
        if (!(player instanceof ServerPlayer serverPlayer) || ownerId == null || !ownerId.equals(serverPlayer.getUUID())
                || clickType != ClickType.PICKUP || button != 0) {
            return;
        }
        if (!ModMindEntry.isModuleEnabled(ModuleId.PACKAGES)
                || !ModMindEntry.hasCommandPermission(serverPlayer, CommandAction.PACKAGE_OPEN)) {
            serverPlayer.closeContainer();
            return;
        }
        if (slotId == CANCEL_SLOT) {
            PackagePreviewScreenHandler.open(serverPlayer, instanceId, listPage, previewPage);
            return;
        }
        if (slotId != CONFIRM_SLOT) {
            return;
        }
        PackageInstance instance = findInstance(serverPlayer);
        if (!PackageScreenHandler.canOpen(instance)) {
            render();
            broadcastChanges();
            return;
        }
        PackageService.OpenResult result = ModMindEntry.packageService().open(serverPlayer, instanceId);
        if (result.result() == PackageService.Result.SELECTION_REQUIRED) {
            PackageSkillXpSelectionScreenHandler.open(serverPlayer, instanceId, listPage, previewPage);
            serverPlayer.playSound(SoundEvents.UI_BUTTON_CLICK.value(), 0.7f, 1.0f);
            return;
        }
        if (result.result() == PackageService.Result.OPENED) {
            serverPlayer.playSound(SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, 0.8f, 1.0f);
        } else if (result.result() == PackageService.Result.WAITING_INBOX) {
            serverPlayer.playSound(SoundEvents.UI_BUTTON_CLICK.value(), 0.7f, 0.8f);
        } else {
            serverPlayer.playSound(SoundEvents.UI_BUTTON_CLICK.value(), 0.7f, 0.5f);
        }
        serverPlayer.displayClientMessage(Component.literal(openResultMessage(result.result()))
                .withStyle(result.result() == PackageService.Result.OPENED ? ChatFormatting.GREEN : ChatFormatting.YELLOW), true);
        PackageScreenHandler.open(serverPlayer, listPage);
    }

    @Override
    public ItemStack quickMoveStack(Player player, int slot) {
        return ItemStack.EMPTY;
    }

    private void render() {
        GuiTheme.clear(container);
        PackageInstance instance = findInstance(owner);
        if (instance == null) {
            container.setItem(DETAIL_SLOT, GuiTheme.status(Items.BARRIER, Component.literal("礼包不存在"),
                    ChatFormatting.RED, List.of(Component.literal("该礼包已被移除")), false));
            container.setItem(CANCEL_SLOT, GuiTheme.navigation(Items.ARROW, Component.literal("返回"), null));
            return;
        }
        ItemStack detail = new ItemStack(Items.CHEST);
        detail.set(net.minecraft.core.component.DataComponents.CUSTOM_NAME,
                TextTemplateRenderer.render(owner, instance.displayName()).copy().withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD));
        detail.set(net.minecraft.core.component.DataComponents.LORE, new net.minecraft.world.item.component.ItemLore(List.of(
                Component.literal(instance.mode() == dev.modmind.omnitools.packages.PackageDefinition.Mode.ALL
                        ? "将获得全部物品" : "将随机获得一种物品").withStyle(ChatFormatting.GRAY),
                Component.literal("物品条目：" + instance.items().size()).withStyle(ChatFormatting.GRAY),
                Component.literal("技能经验：" + instance.skillXpGrants().size() + " 项").withStyle(ChatFormatting.GRAY))));
        container.setItem(DETAIL_SLOT, detail);
        if (PackageScreenHandler.canOpen(instance)) {
            container.setItem(CONFIRM_SLOT, GuiTheme.navigation(Items.LIME_DYE, Component.literal("确认打开"),
                    Component.literal("此操作将开始投递")));
        } else {
            container.setItem(CONFIRM_SLOT, GuiTheme.navigation(Items.BARRIER,
                    Component.literal(PackageScreenHandler.statusLabel(instance.status())),
                    Component.literal("当前状态不可打开")));
        }
        container.setItem(CANCEL_SLOT, GuiTheme.navigation(Items.BARRIER, Component.literal("取消"),
                Component.literal("返回预览")));
    }

    private PackageInstance findInstance(ServerPlayer player) {
        if (player == null || instanceId == null) {
            return null;
        }
        return PackageData.get(player.level().getServer()).find(player.getUUID(), instanceId).orElse(null);
    }

    private static String openResultMessage(PackageService.Result result) {
        return switch (result) {
            case OPENED -> "礼包已打开";
            case WAITING_INBOX -> "背包空间不足，剩余物品待投递";
            case SELECTION_REQUIRED -> "请选择技能树";
            case BLOCKED -> "礼包投递已阻塞，请联系管理员";
            case NOT_FOUND -> "礼包不存在";
            case INVALID -> "礼包当前不可打开";
            case CREATED, LIMIT_REACHED -> "礼包状态已更新";
        };
    }
}
