package dev.modmind.omnitools;

import dev.modmind.omnitools.config.ModuleId;
import dev.modmind.omnitools.config.OmniToolsConfigManager;
import dev.modmind.omnitools.config.OmniToolsConfigSnapshot;
import dev.modmind.omnitools.permissions.CommandAction;
import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
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
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.flag.FeatureFlags;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Server-authoritative module switchboard backed by transactional root configuration updates. */
public final class ModuleManagerScreenHandler extends ChestMenu {
    public static final int ROWS = 3;
    public static final int CONTAINER_SIZE = ROWS * 9;
    public static final int RELOAD_SLOT = 22;
    public static final MenuType<ModuleManagerScreenHandler> TYPE = net.minecraft.core.Registry.register(
            BuiltInRegistries.MENU,
            Identifier.fromNamespaceAndPath(ModMindEntry.MOD_ID, "module_manager"),
            new MenuType<>(ModuleManagerScreenHandler::new, FeatureFlags.DEFAULT_FLAGS));

    private static final Map<ModuleId, Integer> MODULE_SLOTS = moduleSlots();
    private static final Map<ModuleId, Item> MODULE_ICONS = Map.of(
            ModuleId.DAILY_CHECKIN, Items.CLOCK,
            ModuleId.ONLINE_REWARD, Items.CLOCK,
            ModuleId.SHOP, Items.EMERALD,
            ModuleId.TITLES, Items.NAME_TAG,
            ModuleId.TITLE_EFFECTS, Items.BLAZE_POWDER,
            ModuleId.ACHIEVEMENTS, Items.KNOWLEDGE_BOOK,
            ModuleId.CLOUD_STORAGE, Items.ENDER_CHEST,
            ModuleId.PERMISSIONS, Items.TRIPWIRE_HOOK);

    private final SimpleContainer moduleContainer;
    private final UUID ownerId;
    private final ServerPlayer owner;
    private long lastConfigRevision = Long.MIN_VALUE;

    public static void register() {
        // Loading this class registers TYPE before the client creates its screen.
    }

    public ModuleManagerScreenHandler(int syncId, Inventory inventory) {
        this(syncId, inventory, new SimpleContainer(CONTAINER_SIZE), null);
    }

    private ModuleManagerScreenHandler(int syncId, Inventory inventory, SimpleContainer container, ServerPlayer owner) {
        super(TYPE, syncId, inventory, container, ROWS);
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

        if (slotId == RELOAD_SLOT) {
            reloadFromDisk(serverPlayer);
            return;
        }
        ModuleId module = moduleAt(slotId);
        if (module == null) {
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
            player.displayClientMessage(Component.translatable(block.get().translationKey()).withStyle(ChatFormatting.YELLOW),
                    true);
            refreshContents();
            return;
        }

        OmniToolsConfigManager.ModuleUpdateResult result = ModMindEntry.moduleControlService()
                .updateModuleEnabled(player.level().getServer(), module, targetState);
        if (result.success()) {
            player.displayClientMessage(Component.translatable("message.omnitools.modules.updated",
                    moduleName(module), stateName(targetState)).withStyle(ChatFormatting.GREEN), true);
        } else {
            player.displayClientMessage(failureMessage(result.message()), true);
        }
        if (player.containerMenu == this) {
            refreshContents();
        }
    }

    private void reloadFromDisk(ServerPlayer player) {
        OmniToolsConfigManager.ReloadResult result = ModMindEntry.moduleControlService().reload(player.level().getServer());
        if (result.success()) {
            player.displayClientMessage(Component.translatable("message.omnitools.modules.reloaded")
                    .withStyle(ChatFormatting.GREEN), true);
        } else {
            player.displayClientMessage(failureMessage(result.message()), true);
        }
        if (player.containerMenu == this) {
            refreshContents();
        }
    }

    private void refreshContents() {
        OmniToolsConfigSnapshot snapshot = ModMindEntry.configSnapshot();
        for (int slot = 0; slot < CONTAINER_SIZE; slot++) {
            moduleContainer.setItem(slot, filler());
        }
        for (Map.Entry<ModuleId, Integer> entry : MODULE_SLOTS.entrySet()) {
            ModuleId module = entry.getKey();
            boolean enabled = snapshot.enabled(module);
            Optional<ModuleControlService.DependencyBlock> block = ModMindEntry.moduleControlService()
                    .dependencyBlock(snapshot, module, !enabled);
            moduleContainer.setItem(entry.getValue(), moduleItem(module, enabled, block));
        }
        moduleContainer.setItem(RELOAD_SLOT, reloadItem());
        lastConfigRevision = snapshot.revision();
    }

    private static ItemStack moduleItem(ModuleId module, boolean enabled,
                                         Optional<ModuleControlService.DependencyBlock> block) {
        ChatFormatting color = block.isPresent() ? ChatFormatting.YELLOW
                : enabled ? ChatFormatting.GREEN : ChatFormatting.RED;
        ItemStack item = new ItemStack(MODULE_ICONS.get(module));
        if (enabled) {
            item.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
        }
        item.set(DataComponents.CUSTOM_NAME, moduleName(module).copy().withStyle(color, ChatFormatting.BOLD));
        List<Component> lore = new java.util.ArrayList<>();
        lore.add(Component.translatable("gui.omnitools.modules.status", stateName(enabled)).withStyle(color));
        lore.add(Component.translatable("gui.omnitools.modules.id", module.id()).withStyle(ChatFormatting.DARK_GRAY));
        if (block.isPresent()) {
            lore.add(Component.translatable("gui.omnitools.modules.blocked").withStyle(ChatFormatting.YELLOW));
            lore.add(Component.translatable(block.get().translationKey()).withStyle(ChatFormatting.YELLOW));
        } else {
            lore.add(Component.translatable("gui.omnitools.modules.toggle_hint").withStyle(ChatFormatting.GRAY));
        }
        item.set(DataComponents.LORE, new ItemLore(lore));
        return item;
    }

    private static ItemStack reloadItem() {
        ItemStack item = new ItemStack(Items.RECOVERY_COMPASS);
        item.set(DataComponents.CUSTOM_NAME, Component.translatable("gui.omnitools.modules.reload")
                .withStyle(ChatFormatting.AQUA, ChatFormatting.BOLD));
        item.set(DataComponents.LORE, new ItemLore(List.of(
                Component.translatable("gui.omnitools.modules.reload_hint").withStyle(ChatFormatting.GRAY))));
        return item;
    }

    private static ItemStack filler() {
        ItemStack item = new ItemStack(Items.GRAY_STAINED_GLASS_PANE);
        item.set(DataComponents.CUSTOM_NAME, Component.translatable("gui.omnitools.empty"));
        return item;
    }

    private static Component moduleName(ModuleId module) {
        return Component.translatable("gui.omnitools.modules.module." + module.id());
    }

    private static Component stateName(boolean enabled) {
        return Component.translatable(enabled ? "gui.omnitools.modules.enabled" : "gui.omnitools.modules.disabled");
    }

    private static Component failureMessage(String reason) {
        if (reason == null || reason.isBlank()) {
            return Component.translatable("message.omnitools.modules.update_failed_unknown").withStyle(ChatFormatting.RED);
        }
        if (reason.startsWith("gui.omnitools.modules.blocked.")) {
            return Component.translatable(reason).withStyle(ChatFormatting.YELLOW);
        }
        String safeReason = reason;
        return Component.translatable("message.omnitools.modules.update_failed", Component.literal(safeReason))
                .withStyle(ChatFormatting.RED);
    }

    private static ModuleId moduleAt(int slot) {
        for (Map.Entry<ModuleId, Integer> entry : MODULE_SLOTS.entrySet()) {
            if (entry.getValue() == slot) {
                return entry.getKey();
            }
        }
        return null;
    }

    private static Map<ModuleId, Integer> moduleSlots() {
        EnumMap<ModuleId, Integer> slots = new EnumMap<>(ModuleId.class);
        slots.put(ModuleId.DAILY_CHECKIN, 10);
        slots.put(ModuleId.ONLINE_REWARD, 11);
        slots.put(ModuleId.SHOP, 12);
        slots.put(ModuleId.TITLES, 13);
        slots.put(ModuleId.TITLE_EFFECTS, 14);
        slots.put(ModuleId.ACHIEVEMENTS, 15);
        slots.put(ModuleId.CLOUD_STORAGE, 16);
        slots.put(ModuleId.PERMISSIONS, 17);
        return Map.copyOf(slots);
    }
}
