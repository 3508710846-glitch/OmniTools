package dev.modmind.omnitools;

import dev.modmind.omnitools.config.ModuleId;
import dev.modmind.omnitools.diagnostics.ModuleFaultBoundary;
import dev.modmind.omnitools.config.ModuleStatus;
import dev.modmind.omnitools.config.ConfigPaths;
import dev.modmind.omnitools.config.OmniToolsConfigManager;
import dev.modmind.omnitools.config.OmniToolsConfigSnapshot;
import dev.modmind.omnitools.permissions.CommandAction;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/** Server-authoritative module switchboard backed by transactional root configuration updates. */
public final class ModuleManagerScreenHandler extends ChestMenu {
    public static final int ROWS = 3;
    public static final int CONTAINER_SIZE = ROWS * 9;
    public static final int RELOAD_SLOT = GuiSlots.CENTER_27;
    private static final int PREVIOUS_PAGE_SLOT = GuiSlots.FIRST_ACTION_SLOT_27;
    private static final int NEXT_PAGE_SLOT = GuiSlots.LAST_SLOT_27;
    private static final int CLOSE_SLOT = GuiSlots.HEADER_CLOSE_27;
    private static final List<ModuleId> MODULES = List.copyOf(Arrays.asList(ModuleId.values()));
    private static final Map<ModuleId, Supplier<Item>> MODULE_ICONS = Map.ofEntries(
            Map.entry(ModuleId.DAILY_CHECKIN, () -> Items.CLOCK),
            Map.entry(ModuleId.CDK, () -> Items.TRIPWIRE_HOOK),
            Map.entry(ModuleId.ONLINE_REWARD, () -> Items.CLOCK),
            Map.entry(ModuleId.SHOP, () -> Items.EMERALD),
            Map.entry(ModuleId.TITLES, () -> Items.NAME_TAG),
            Map.entry(ModuleId.TITLE_EFFECTS, () -> Items.BLAZE_POWDER),
            Map.entry(ModuleId.ACHIEVEMENTS, () -> Items.KNOWLEDGE_BOOK),
            Map.entry(ModuleId.CLOUD_STORAGE, () -> Items.ENDER_CHEST),
            Map.entry(ModuleId.PERMISSIONS, () -> Items.TRIPWIRE_HOOK),
            Map.entry(ModuleId.COMMAND_MENU, () -> Items.CHEST),
            Map.entry(ModuleId.SIDEBAR, () -> Items.PAPER),
            Map.entry(ModuleId.LEADERBOARDS, () -> Items.GOLD_INGOT),
            Map.entry(ModuleId.PACKAGES, () -> Items.CHEST),
            Map.entry(ModuleId.SKILLS, () -> Items.EXPERIENCE_BOTTLE));

    private final SimpleContainer moduleContainer;
    private final UUID ownerId;
    private final ServerPlayer owner;
    private int page;
    private int pageCount = 1;
    private ModuleId pendingConfirmation;
    private long lastConfigRevision = Long.MIN_VALUE;

    public ModuleManagerScreenHandler(int syncId, Inventory inventory) {
        this(syncId, inventory, new SimpleContainer(CONTAINER_SIZE), null);
    }

    private ModuleManagerScreenHandler(int syncId, Inventory inventory, SimpleContainer container, ServerPlayer owner) {
        super(MenuType.GENERIC_9x3, syncId, inventory, container, ROWS);
        this.moduleContainer = container;
        this.owner = owner;
        this.ownerId = owner == null ? null : owner.getUUID();
        if (owner != null) {
            refreshContents();
        }
    }

    public static ModuleManagerScreenHandler createServer(int syncId, Inventory inventory, ServerPlayer owner) {
        return new ModuleManagerScreenHandler(syncId, inventory, new SimpleContainer(CONTAINER_SIZE), owner);
    }

    @Override
    public void clicked(int slotId, int button, ClickType clickType, Player player) {
        if (player instanceof ServerPlayer serverPlayer) {
            ModuleFaultBoundary.runPlayerAction(ModuleId.PERMISSIONS, "module_manager_click", serverPlayer,
                    "previous_configuration_snapshot_retained", () -> handleClick(slotId, button, clickType, player));
            return;
        }
        handleClick(slotId, button, clickType, player);
    }

    private void handleClick(int slotId, int button, ClickType clickType, Player player) {
        if (!(player instanceof ServerPlayer serverPlayer) || ownerId == null || !ownerId.equals(serverPlayer.getUUID())) {
            return;
        }
        if (!ModMindEntry.hasCommandPermission(serverPlayer, CommandAction.CONFIG_RELOAD)) {
            serverPlayer.closeContainer();
            return;
        }
        if (clickType != ClickType.PICKUP || button != 0 || slotId < 0 || slotId >= CONTAINER_SIZE) {
            return;
        }

        if (slotId == CLOSE_SLOT) {
            serverPlayer.closeContainer();
            GuiFeedbackService.click(serverPlayer);
            return;
        }
        if (pendingConfirmation != null) {
            handleConfirmation(serverPlayer, slotId);
            return;
        }
        if (slotId == RELOAD_SLOT) {
            reloadFromDisk(serverPlayer);
            return;
        }
        if (slotId == PREVIOUS_PAGE_SLOT && page > 0) {
            page--;
            refreshContents();
            GuiFeedbackService.click(serverPlayer);
            return;
        }
        if (slotId == NEXT_PAGE_SLOT && page + 1 < pageCount) {
            page++;
            refreshContents();
            GuiFeedbackService.click(serverPlayer);
            return;
        }
        ModuleId module = moduleAt(GuiSlots.contentIndex27(slotId));
        if (module == null) {
            return;
        }
        if (requiresConfirmation(module)) {
            pendingConfirmation = module;
            refreshContents();
            GuiFeedbackService.click(serverPlayer);
            return;
        }
        updateModule(serverPlayer, module);
    }

    @Override
    public ItemStack quickMoveStack(Player player, int slotIndex) {
        return ItemStack.EMPTY;
    }

    @Override
    public void broadcastChanges() {
        if (owner != null) {
            if (!ModMindEntry.hasCommandPermission(owner, CommandAction.CONFIG_RELOAD)) {
                owner.closeContainer();
            } else if (ModMindEntry.configSnapshot().revision() != lastConfigRevision) {
                refreshContents();
            }
        }
        super.broadcastChanges();
    }

    private void updateModule(ServerPlayer player, ModuleId module) {
        OmniToolsConfigSnapshot snapshot = ModMindEntry.configSnapshot();
        boolean targetState = !snapshot.enabled(module);
        Optional<ModuleControlService.DependencyBlock> block = ModMindEntry.moduleControlService()
                .dependencyBlock(snapshot, module, targetState);
        if (block.isPresent()) {
            GuiFeedbackService.failure(player);
            player.displayClientMessage(ServerText.translatable(block.get().translationKey()).withStyle(ChatFormatting.YELLOW),
                    true);
            refreshContents();
            return;
        }

        OmniToolsConfigManager.ModuleUpdateResult result = ModMindEntry.moduleControlService()
                .updateModuleEnabled(player.level().getServer(), module, targetState);
        if (result.success()) {
            GuiFeedbackService.success(player);
            player.displayClientMessage(ServerText.translatable(targetState
                    ? "message.omnitools.modules.enabled_result"
                    : "message.omnitools.modules.disabled_result", moduleName(module))
                    .withStyle(ChatFormatting.GREEN), true);
        } else {
            GuiFeedbackService.failure(player);
            player.displayClientMessage(failureMessage(result.message()), true);
        }
        if (player.containerMenu == this) {
            refreshContents();
        }
    }

    private void reloadFromDisk(ServerPlayer player) {
        OmniToolsConfigManager.ReloadResult result = ModMindEntry.moduleControlService().reload(player.level().getServer());
        if (result.success()) {
            GuiFeedbackService.success(player);
            player.displayClientMessage(ServerText.translatable("message.omnitools.modules.reloaded")
                    .withStyle(ChatFormatting.GREEN), true);
        } else {
            GuiFeedbackService.failure(player);
            player.displayClientMessage(failureMessage(result.message()), true);
        }
        if (player.containerMenu == this) {
            refreshContents();
        }
    }

    private void refreshContents() {
        OmniToolsConfigSnapshot snapshot = ModMindEntry.configSnapshot();
        GuiTheme.clear(moduleContainer);
        if (pendingConfirmation != null) {
            renderConfirmation(snapshot);
            lastConfigRevision = snapshot.revision();
            return;
        }
        pageCount = Math.max(1, (MODULES.size() + GuiSlots.CONTENT_SLOT_COUNT_27 - 1)
                / GuiSlots.CONTENT_SLOT_COUNT_27);
        page = Math.max(0, Math.min(page, pageCount - 1));
        moduleContainer.setItem(GuiSlots.HEADER_LEFT_27, GuiTheme.status(Items.COMPASS,
                ServerText.translatable("gui.omnitools.modules.title"), ChatFormatting.AQUA,
                List.of(ServerText.translatable("gui.omnitools.modules.revision", snapshot.revision())
                        .withStyle(ChatFormatting.GRAY)), false));
        moduleContainer.setItem(GuiSlots.HEADER_CENTER_27, GuiTheme.status(Items.WRITABLE_BOOK,
                ServerText.translatable("gui.omnitools.modules.title"), ChatFormatting.AQUA,
                List.of(ServerText.translatable("gui.omnitools.modules.reload_hint")
                        .withStyle(ChatFormatting.GRAY)), false));
        moduleContainer.setItem(CLOSE_SLOT, GuiNavigationService.close());
        int first = page * GuiSlots.CONTENT_SLOT_COUNT_27;
        for (int index = 0; index < GuiSlots.CONTENT_SLOT_COUNT_27 && first + index < MODULES.size(); index++) {
            ModuleId module = MODULES.get(first + index);
            ModuleStatus status = ModMindEntry.isModuleRuntimeDegraded(module)
                    ? ModuleStatus.DEGRADED : snapshot.status(module);
            boolean enabled = status == ModuleStatus.ENABLED;
            Optional<ModuleControlService.DependencyBlock> block = ModMindEntry.moduleControlService()
                    .dependencyBlock(snapshot, module, !enabled);
            moduleContainer.setItem(GuiSlots.contentSlot27(index), moduleItem(module, status, block));
        }
        if (page > 0) {
            moduleContainer.setItem(PREVIOUS_PAGE_SLOT, GuiNavigationService.previous());
        }
        moduleContainer.setItem(RELOAD_SLOT, reloadItem());
        if (page + 1 < pageCount) {
            moduleContainer.setItem(NEXT_PAGE_SLOT, GuiNavigationService.next());
        }
        lastConfigRevision = snapshot.revision();
    }

    static ItemStack moduleItem(ModuleId module, boolean enabled,
                                Optional<ModuleControlService.DependencyBlock> block) {
        return moduleItem(module, enabled ? ModuleStatus.ENABLED : ModuleStatus.DISABLED, block);
    }

    static ItemStack moduleItem(ModuleId module, ModuleStatus status,
                                Optional<ModuleControlService.DependencyBlock> block) {
        boolean enabled = status == ModuleStatus.ENABLED;
        boolean degraded = status == ModuleStatus.DEGRADED;
        ChatFormatting color = block.isPresent() ? ChatFormatting.YELLOW
                : degraded ? ChatFormatting.RED : enabled ? ChatFormatting.GREEN : ChatFormatting.GRAY;
        List<Component> lore = new java.util.ArrayList<>();
        Component statusName = degraded ? Component.literal("DEGRADED").withStyle(ChatFormatting.RED) : stateName(enabled);
        lore.add(ServerText.translatable("gui.omnitools.modules.status", statusName).withStyle(color));
        lore.add(ServerText.translatable("gui.omnitools.modules.id", module.id()).withStyle(ChatFormatting.DARK_GRAY));
        lore.add(ServerText.translatable("gui.omnitools.modules.path", ConfigPaths.moduleConfig(module).toString())
                .withStyle(ChatFormatting.DARK_GRAY));
        if (block.isPresent()) {
            lore.add(ServerText.translatable("gui.omnitools.modules.blocked").withStyle(ChatFormatting.YELLOW));
        }
        GuiStatusItem.State state = block.isPresent() ? GuiStatusItem.State.PENDING
                : degraded ? GuiStatusItem.State.BLOCKED
                : enabled ? GuiStatusItem.State.ACTIONABLE : GuiStatusItem.State.INACTIVE;
        return GuiStatusItem.create(new ItemStack(moduleIcon(module)), moduleName(module), state,
                GuiTextService.cardLore(lore, block.isPresent()
                        ? ServerText.translatable(block.get().translationKey()).withStyle(ChatFormatting.YELLOW)
                        : ServerText.translatable("gui.omnitools.modules.toggle_hint").withStyle(ChatFormatting.GRAY)));
    }

    private static ItemStack reloadItem() {
        return GuiTheme.navigation(Items.RECOVERY_COMPASS, ServerText.translatable("gui.omnitools.modules.reload"),
                ServerText.translatable("gui.omnitools.modules.reload_hint"));
    }

    static Item moduleIcon(ModuleId module) {
        Supplier<Item> supplier = module == null ? null : MODULE_ICONS.get(module);
        return supplier == null ? Items.COMPASS : supplier.get();
    }

    static boolean hasConfiguredIcon(ModuleId module) {
        return module != null && MODULE_ICONS.containsKey(module);
    }

    private static Component moduleName(ModuleId module) {
        return ServerText.translatable("gui.omnitools.modules.module." + module.id());
    }

    private static Component stateName(boolean enabled) {
        return ServerText.translatable(enabled ? "gui.omnitools.modules.enabled" : "gui.omnitools.modules.disabled");
    }

    private static Component failureMessage(String reason) {
        if (reason == null || reason.isBlank()) {
            return ServerText.translatable("message.omnitools.modules.update_failed_unknown").withStyle(ChatFormatting.RED);
        }
        if (reason.startsWith("gui.omnitools.modules.blocked.")) {
            return ServerText.translatable(reason).withStyle(ChatFormatting.YELLOW);
        }
        String safeReason = reason;
        return ServerText.translatable("message.omnitools.modules.update_failed", Component.literal(safeReason))
                .withStyle(ChatFormatting.RED);
    }

    private ModuleId moduleAt(int contentIndex) {
        int index = page * GuiSlots.CONTENT_SLOT_COUNT_27 + contentIndex;
        return contentIndex < 0 || index >= MODULES.size() ? null : MODULES.get(index);
    }

    private void handleConfirmation(ServerPlayer player, int slotId) {
        if (slotId == PREVIOUS_PAGE_SLOT) {
            pendingConfirmation = null;
            refreshContents();
            GuiFeedbackService.click(player);
            return;
        }
        if (slotId == RELOAD_SLOT) {
            ModuleId confirmed = pendingConfirmation;
            pendingConfirmation = null;
            updateModule(player, confirmed);
        }
    }

    private void renderConfirmation(OmniToolsConfigSnapshot snapshot) {
        ModuleId module = pendingConfirmation;
        boolean enabled = snapshot.enabled(module);
        Optional<ModuleControlService.DependencyBlock> block = ModMindEntry.moduleControlService()
                .dependencyBlock(snapshot, module, !enabled);
        moduleContainer.setItem(GuiSlots.HEADER_LEFT_27, GuiTheme.status(moduleIcon(module), moduleName(module),
                enabled ? ChatFormatting.GREEN : ChatFormatting.GRAY,
                List.of(ServerText.translatable("gui.omnitools.modules.status", stateName(enabled))
                        .withStyle(enabled ? ChatFormatting.GREEN : ChatFormatting.GRAY)), false));
        moduleContainer.setItem(GuiSlots.HEADER_CENTER_27, GuiTheme.status(Items.BARRIER,
                ServerText.translatable("gui.omnitools.modules.confirm_title"), ChatFormatting.RED,
                List.of(ServerText.translatable("gui.omnitools.modules.confirm_detail", moduleName(module))
                        .withStyle(ChatFormatting.GRAY)), false));
        moduleContainer.setItem(CLOSE_SLOT, GuiNavigationService.close());
        moduleContainer.setItem(GuiSlots.contentSlot27(4), moduleItem(module, enabled, block));
        moduleContainer.setItem(PREVIOUS_PAGE_SLOT, GuiTheme.navigation(Items.ARROW,
                ServerText.translatable("gui.omnitools.modules.cancel"),
                ServerText.translatable("gui.omnitools.modules.cancel_hint")));
        moduleContainer.setItem(RELOAD_SLOT, GuiStatusItem.create(new ItemStack(Items.LIME_DYE),
                ServerText.translatable("gui.omnitools.modules.confirm"), GuiStatusItem.State.ACTIONABLE,
                List.of(ServerText.translatable("gui.omnitools.modules.confirm_hint").withStyle(ChatFormatting.GREEN))));
    }

    private static boolean requiresConfirmation(ModuleId module) {
        return switch (module) {
            case DAILY_CHECKIN, CDK, ONLINE_REWARD, TITLES, ACHIEVEMENTS -> true;
            default -> false;
        };
    }
}
