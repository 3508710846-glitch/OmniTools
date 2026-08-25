package dev.modmind.omnitools;

import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
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
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.item.component.ResolvableProfile;

import java.util.List;
import java.util.UUID;

/** Server-authoritative title selection menu with room for up to 45 titles per page. */
public final class TitleScreenHandler extends ChestMenu {
    public static final int ROWS = 6;
    public static final int CONTAINER_SIZE = ROWS * 9;
    public static final int TITLE_SLOTS = 45;
    public static final int PREVIOUS_PAGE_SLOT = 45;
    public static final int EFFECTS_SLOT = 47;
    public static final int UNEQUIP_SLOT = 48;
    public static final int PROFILE_SLOT = 49;
    public static final int NEXT_PAGE_SLOT = 53;
    private final SimpleContainer titleContainer;
    private final UUID ownerId;
    private final ServerPlayer owner;
    private final TitleConfig config;
    private int page;
    private int stateHash = Integer.MIN_VALUE;
    private long lastStateCheckTick = Long.MIN_VALUE;

    public TitleScreenHandler(int syncId, Inventory inventory) {
        this(syncId, inventory, new SimpleContainer(CONTAINER_SIZE), null, TitleConfig.empty());
    }

    private TitleScreenHandler(int syncId, Inventory inventory, SimpleContainer container, ServerPlayer owner,
                               TitleConfig config) {
        super(MenuType.GENERIC_9x6, syncId, inventory, container, ROWS);
        this.titleContainer = container;
        this.ownerId = owner == null ? null : owner.getUUID();
        this.owner = owner;
        this.config = config;
        if (owner != null) {
            refreshContents();
        }
    }

    public static TitleScreenHandler createServer(int syncId, Inventory inventory, ServerPlayer owner,
                                                   TitleConfig config) {
        return new TitleScreenHandler(syncId, inventory, new SimpleContainer(CONTAINER_SIZE), owner, config);
    }

    @Override
    public void clicked(int slotId, int button, ClickType clickType, Player player) {
        if (!ModMindEntry.isModuleEnabled(dev.modmind.omnitools.config.ModuleId.TITLES)
                || (player instanceof ServerPlayer serverPlayerForPermission
                && !ModMindEntry.hasCommandPermission(serverPlayerForPermission,
                dev.modmind.omnitools.permissions.CommandAction.TITLE_OPEN))) {
            if (player instanceof ServerPlayer serverPlayer) {
                serverPlayer.closeContainer();
            }
            return;
        }
        if (slotId < 0 || slotId >= CONTAINER_SIZE) {
            super.clicked(slotId, button, clickType, player);
            return;
        }
        if (!(player instanceof ServerPlayer serverPlayer) || ownerId == null
                || !ownerId.equals(serverPlayer.getUUID()) || clickType != ClickType.PICKUP) {
            return;
        }

        List<TitleConfig.TitleDefinition> unlockedTitles = config.unlockedTitles(ownerId);
        int pageCount = pageCount(unlockedTitles);
        if (slotId == PREVIOUS_PAGE_SLOT && page > 0) {
            page--;
            refreshContents();
            return;
        }
        if (slotId == NEXT_PAGE_SLOT && page + 1 < pageCount) {
            page++;
            refreshContents();
            return;
        }
        if (slotId == UNEQUIP_SLOT) {
            if (config.clearSelection(ownerId, serverPlayer.getGameProfile().name())) {
                serverPlayer.displayClientMessage(ServerText.translatable("message.omnitools.title.unequipped"), true);
                TitleDisplayService.refreshPlayer(serverPlayer);
                TitleEffectService.refresh(serverPlayer);
            }
            refreshContents();
            return;
        }
        if (slotId == EFFECTS_SLOT) {
            boolean enabled = config.toggleEffects(ownerId, serverPlayer.getGameProfile().name());
            serverPlayer.displayClientMessage(ServerText.translatable(
                    enabled ? "message.omnitools.title.effects_enabled" : "message.omnitools.title.effects_disabled"), true);
            TitleEffectService.refresh(serverPlayer);
            refreshContents();
            return;
        }
        if (slotId >= TITLE_SLOTS) {
            return;
        }

        int titleIndex = page * TITLE_SLOTS + slotId;
        if (titleIndex >= unlockedTitles.size()) {
            return;
        }
        TitleConfig.TitleDefinition title = unlockedTitles.get(titleIndex);
        if (title.id().equals(config.selectedTitleId(ownerId))) {
            config.clearSelection(ownerId, serverPlayer.getGameProfile().name());
            serverPlayer.displayClientMessage(ServerText.translatable("message.omnitools.title.unequipped"), true);
        } else if (config.select(ownerId, serverPlayer.getGameProfile().name(), title.id())
                == TitleConfig.SelectionResult.SELECTED) {
            serverPlayer.displayClientMessage(ServerText.translatable("message.omnitools.title.equipped", title.displayComponent()), true);
        }
        TitleDisplayService.refreshPlayer(serverPlayer);
        TitleEffectService.refresh(serverPlayer);
        refreshContents();
    }

    @Override
    public ItemStack quickMoveStack(Player player, int slot) {
        return ItemStack.EMPTY;
    }

    @Override
    public void broadcastChanges() {
        if (owner != null) {
            long tick = owner.level().getServer().getTickCount();
            if (lastStateCheckTick == Long.MIN_VALUE || tick - lastStateCheckTick >= 10L) {
                lastStateCheckTick = tick;
                if (currentStateHash() != stateHash) {
                    refreshContents();
                }
            }
        }
        super.broadcastChanges();
    }

    private void refreshContents() {
        List<TitleConfig.TitleDefinition> unlockedTitles = config.unlockedTitles(ownerId);
        int pageCount = pageCount(unlockedTitles);
        page = Math.max(0, Math.min(page, pageCount - 1));
        String selectedId = config.selectedTitleId(ownerId);

        for (int slot = 0; slot < CONTAINER_SIZE; slot++) {
            titleContainer.setItem(slot, filler());
        }

        if (unlockedTitles.isEmpty()) {
            titleContainer.setItem(22, namedItem(Items.BOOK,
                    ServerText.translatable("gui.omnitools.title.empty_title").withStyle(ChatFormatting.GRAY),
                    List.of(ServerText.translatable("gui.omnitools.title.empty_hint").withStyle(ChatFormatting.DARK_GRAY))));
        }

        int firstTitle = page * TITLE_SLOTS;
        for (int slot = 0; slot < TITLE_SLOTS && firstTitle + slot < unlockedTitles.size(); slot++) {
            TitleConfig.TitleDefinition title = unlockedTitles.get(firstTitle + slot);
            boolean selected = title.id().equals(selectedId);
            ItemStack titleItem = new ItemStack(Items.NAME_TAG);
            titleItem.set(DataComponents.CUSTOM_NAME, title.displayComponent());
            if (selected) {
                titleItem.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
            }
            List<Component> lore = new java.util.ArrayList<>();
            lore.add(ServerText.translatable("gui.omnitools.title.rarity." + title.rarity().serializedName())
                    .withStyle(rarityColor(title.rarity())));
            if (!title.tooltip().isEmpty()) {
                lore.addAll(title.tooltip().stream().map(TitleScreenHandler::legacyComponent).toList());
            } else if (title.effects().isEmpty()) {
                lore.add(ServerText.translatable("gui.omnitools.title.no_effects").withStyle(ChatFormatting.DARK_GRAY));
            } else {
                lore.addAll(title.effects().stream().map(TitleScreenHandler::effectComponent).toList());
            }
            lore.add(ServerText.translatable(selected ? "gui.omnitools.title.selected" : "gui.omnitools.title.select_hint")
                    .withStyle(selected ? ChatFormatting.GOLD : ChatFormatting.GRAY));
            lore.add(Component.literal(title.id()).withStyle(ChatFormatting.DARK_GRAY));
            titleItem.set(DataComponents.LORE, new ItemLore(lore));
            titleContainer.setItem(slot, titleItem);
        }

        if (page > 0) {
            titleContainer.setItem(PREVIOUS_PAGE_SLOT, namedItem(Items.ARROW,
                    ServerText.translatable("gui.omnitools.title.previous").withStyle(ChatFormatting.AQUA),
                    List.of(ServerText.translatable("gui.omnitools.title.previous_hint").withStyle(ChatFormatting.GRAY))));
        }
        titleContainer.setItem(UNEQUIP_SLOT, namedItem(Items.BARRIER,
                ServerText.translatable("gui.omnitools.title.unequip").withStyle(ChatFormatting.RED),
                    List.of(ServerText.translatable("gui.omnitools.title.unequip_hint").withStyle(ChatFormatting.GRAY))));
        titleContainer.setItem(EFFECTS_SLOT, effectsItem(selectedId));
        titleContainer.setItem(PROFILE_SLOT, profileItem(unlockedTitles.size(), selectedId, page + 1, pageCount));
        if (page + 1 < pageCount) {
            titleContainer.setItem(NEXT_PAGE_SLOT, namedItem(Items.ARROW,
                    ServerText.translatable("gui.omnitools.title.next").withStyle(ChatFormatting.AQUA),
                    List.of(ServerText.translatable("gui.omnitools.title.next_hint").withStyle(ChatFormatting.GRAY))));
        }
        stateHash = currentStateHash();
    }

    private ItemStack profileItem(int unlockedCount, String selectedId, int pageNumber, int pageCount) {
        ItemStack profile = new ItemStack(Items.PLAYER_HEAD);
        profile.set(DataComponents.PROFILE, ResolvableProfile.createResolved(owner.getGameProfile()));
        profile.set(DataComponents.CUSTOM_NAME, ServerText.translatable("gui.omnitools.title.profile")
                .withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD));
        Component equipped = selectedId.isEmpty()
                ? ServerText.translatable("gui.omnitools.title.no_selection")
                : config.definition(selectedId)
                        .<Component>map(TitleConfig.TitleDefinition::displayComponent)
                        .orElse(Component.literal(selectedId).withStyle(ChatFormatting.RED));
        profile.set(DataComponents.LORE, new ItemLore(List.of(
                ServerText.translatable("gui.omnitools.title.unlocked_count", unlockedCount).withStyle(ChatFormatting.AQUA),
                ServerText.translatable("gui.omnitools.title.current").append(equipped).withStyle(ChatFormatting.GRAY),
                ServerText.translatable("gui.omnitools.title.page", pageNumber, pageCount).withStyle(ChatFormatting.DARK_GRAY))));
        return profile;
    }

    private int currentStateHash() {
        if (ownerId == null) {
            return 0;
        }
        int hash = 31 * page + config.selectedTitleId(ownerId).hashCode();
        hash = 31 * hash + Boolean.hashCode(config.effectsEnabled(ownerId));
        for (TitleConfig.TitleDefinition title : config.unlockedTitles(ownerId)) {
            hash = 31 * hash + title.id().hashCode();
        }
        return hash;
    }

    private static int pageCount(List<TitleConfig.TitleDefinition> titles) {
        return Math.max(1, (titles.size() + TITLE_SLOTS - 1) / TITLE_SLOTS);
    }

    private static ChatFormatting rarityColor(TitleRarity rarity) {
        return switch (rarity) {
            case COMMON -> ChatFormatting.GRAY;
            case RARE -> ChatFormatting.AQUA;
            case LEGENDARY -> ChatFormatting.GOLD;
        };
    }

    private static ItemStack filler() {
        return namedItem(Items.GRAY_STAINED_GLASS_PANE, ServerText.translatable("gui.omnitools.empty"), List.of());
    }

    private ItemStack effectsItem(String selectedId) {
        boolean enabled = config.effectsEnabled(ownerId);
        List<Component> lore = new java.util.ArrayList<>();
        lore.add(ServerText.translatable("gui.omnitools.title.effects_hint").withStyle(ChatFormatting.GRAY));
        ItemStack item = namedItem(enabled ? Items.LIME_DYE : Items.GRAY_DYE,
                ServerText.translatable(enabled ? "gui.omnitools.title.effects_on" : "gui.omnitools.title.effects_off")
                        .withStyle(enabled ? ChatFormatting.GREEN : ChatFormatting.GRAY),
                lore);
        if (!selectedId.isEmpty()) {
            config.definition(selectedId).ifPresent(title -> {
                for (String effectId : title.effects()) {
                    lore.add(effectComponent(effectId));
                }
                item.set(DataComponents.LORE, new ItemLore(lore));
            });
        }
        return item;
    }

    private static Component effectComponent(String effectId) {
        return ModMindEntry.titleEffectConfig().definition(effectId)
                .<Component>map(effect -> legacyComponent(effect.display()))
                .orElse(Component.literal(effectId).withStyle(ChatFormatting.RED));
    }

    private static Component legacyComponent(String text) {
        return LegacyTitleText.parse(text);
    }

    private static ItemStack namedItem(Item item, Component name, List<Component> lore) {
        ItemStack stack = new ItemStack(item);
        stack.set(DataComponents.CUSTOM_NAME, name);
        if (!lore.isEmpty()) {
            stack.set(DataComponents.LORE, new ItemLore(lore));
        }
        return stack;
    }
}
