package dev.modmind.omnitools;

import dev.modmind.omnitools.diagnostics.ModuleFaultBoundary;
import dev.modmind.omnitools.text.TextTemplateRenderer;
import dev.modmind.omnitools.entitlement.TimedEntitlement;
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
    public static final int TITLE_SLOTS = GuiSlots.CONTENT_SLOT_COUNT_54;
    public static final int PREVIOUS_PAGE_SLOT = GuiSlots.FIRST_ACTION_SLOT_54;
    public static final int EFFECTS_SLOT = 47;
    public static final int UNEQUIP_SLOT = 48;
    public static final int PROFILE_SLOT = GuiSlots.CENTER_54;
    public static final int NEXT_PAGE_SLOT = GuiSlots.LAST_SLOT_54;
    public static final int HEADER_PROFILE_SLOT = GuiSlots.HEADER_LEFT_54;
    public static final int HEADER_TITLE_SLOT = GuiSlots.HEADER_CENTER_54;
    public static final int CLOSE_SLOT = GuiSlots.HEADER_CLOSE_54;
    private final SimpleContainer titleContainer;
    private final UUID ownerId;
    private final ServerPlayer owner;
    private TitleConfig config;
    private int page;
    private int stateHash = Integer.MIN_VALUE;
    private long lastStateCheckTick = Long.MIN_VALUE;
    private long lastConfigRevision = Long.MIN_VALUE;

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
        if (player instanceof ServerPlayer serverPlayer) {
            ModuleFaultBoundary.runPlayerAction(dev.modmind.omnitools.config.ModuleId.TITLES, "menu_click",
                    serverPlayer, "title_selection_retained", () -> handleClick(slotId, button, clickType, player));
            return;
        }
        handleClick(slotId, button, clickType, player);
    }

    private void handleClick(int slotId, int button, ClickType clickType, Player player) {
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
        refreshConfigIfChanged();

        List<TitleConfig.TitleDefinition> unlockedTitles = config.unlockedTitles(ownerId);
        int pageCount = pageCount(unlockedTitles);
        if (slotId == CLOSE_SLOT) {
            serverPlayer.closeContainer();
            GuiFeedbackService.click(serverPlayer);
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
        if (slotId == UNEQUIP_SLOT) {
            if (config.clearSelection(ownerId, serverPlayer.getGameProfile().name())) {
                GuiFeedbackService.success(serverPlayer);
                serverPlayer.displayClientMessage(ServerText.translatable("message.omnitools.title.unequipped"), true);
                TitleDisplayService.refreshPlayer(serverPlayer);
                TitleEffectService.refresh(serverPlayer);
            }
            refreshContents();
            return;
        }
        if (slotId == EFFECTS_SLOT) {
            boolean enabled = config.toggleEffects(ownerId, serverPlayer.getGameProfile().name());
            GuiFeedbackService.click(serverPlayer);
            serverPlayer.displayClientMessage(ServerText.translatable(
                    enabled ? "message.omnitools.title.effects_enabled" : "message.omnitools.title.effects_disabled"), true);
            TitleEffectService.refresh(serverPlayer);
            refreshContents();
            return;
        }
        int localIndex = GuiSlots.contentIndex54(slotId);
        if (localIndex < 0) {
            return;
        }

        int titleIndex = page * TITLE_SLOTS + localIndex;
        if (titleIndex >= unlockedTitles.size()) {
            return;
        }
        TitleConfig.TitleDefinition title = unlockedTitles.get(titleIndex);
        if (title.id().equals(config.selectedTitleId(ownerId))) {
            config.clearSelection(ownerId, serverPlayer.getGameProfile().name());
            GuiFeedbackService.success(serverPlayer);
            serverPlayer.displayClientMessage(ServerText.translatable("message.omnitools.title.unequipped"), true);
        } else if (config.select(ownerId, serverPlayer.getGameProfile().name(), title.id())
                == TitleConfig.SelectionResult.SELECTED) {
            GuiFeedbackService.success(serverPlayer);
            serverPlayer.displayClientMessage(ServerText.translatable("message.omnitools.title.equipped",
                    TextTemplateRenderer.render(serverPlayer, title.display())), true);
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
                if (!refreshConfigIfChanged() && currentStateHash() != stateHash) {
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

        GuiTheme.clear(titleContainer);

        if (unlockedTitles.isEmpty()) {
            titleContainer.setItem(22, GuiTheme.named(Items.BOOK,
                    ServerText.translatable("gui.omnitools.title.empty_title").withStyle(ChatFormatting.GRAY),
                    List.of(ServerText.translatable("gui.omnitools.title.empty_hint").withStyle(ChatFormatting.DARK_GRAY))));
        }

        titleContainer.setItem(HEADER_PROFILE_SLOT, profileItem(unlockedTitles.size(), selectedId));
        titleContainer.setItem(HEADER_TITLE_SLOT, GuiTheme.status(Items.NAME_TAG,
                ServerText.translatable("gui.omnitools.title.menu_title"), ChatFormatting.AQUA,
                List.of(ServerText.translatable("gui.omnitools.title.unlocked_count", unlockedTitles.size())
                        .withStyle(ChatFormatting.GRAY)), false));
        titleContainer.setItem(CLOSE_SLOT, GuiNavigationService.close());

        int firstTitle = page * TITLE_SLOTS;
        for (int index = 0; index < TITLE_SLOTS && firstTitle + index < unlockedTitles.size(); index++) {
            TitleConfig.TitleDefinition title = unlockedTitles.get(firstTitle + index);
            boolean selected = title.id().equals(selectedId);
            boolean temporary = config.entitlement(ownerId, title.id()).map(entitlement -> !entitlement.isPermanent())
                    .orElse(false);
            ItemStack titleItem = new ItemStack(selected ? Items.LIME_DYE
                    : temporary ? Items.CLOCK : Items.NAME_TAG);
            List<Component> lore = new java.util.ArrayList<>();
            lore.add(ServerText.translatable("gui.omnitools.title.rarity." + title.rarity().serializedName())
                    .withStyle(rarityColor(title.rarity())));
            lore.add(entitlementComponent(title.id()));
            if (!title.tooltip().isEmpty()) {
                lore.addAll(title.tooltip().stream().map(text -> TextTemplateRenderer.render(owner, text)).toList());
            }
            List<TitleEffectConfig.EffectDefinition> effects = config.effectsFor(title,
                    ModMindEntry.titleEffectConfig());
            if (effects.isEmpty()) {
                lore.add(ServerText.translatable("gui.omnitools.title.no_effects").withStyle(ChatFormatting.DARK_GRAY));
            } else {
                lore.addAll(effects.stream().map(this::effectComponent).toList());
            }
            GuiStatusItem.State visualState = selected ? GuiStatusItem.State.ACTIONABLE
                    : temporary ? GuiStatusItem.State.TEMPORARY : GuiStatusItem.State.OWNED;
            titleContainer.setItem(GuiSlots.contentSlot54(index), GuiStatusItem.create(titleItem,
                    TextTemplateRenderer.render(owner, title.display()), visualState,
                    GuiTextService.cardLore(lore, ServerText.translatable(
                            selected ? "gui.omnitools.title.selected" : "gui.omnitools.title.select_hint")
                            .withStyle(selected ? ChatFormatting.GREEN : ChatFormatting.GRAY))));
        }

        if (page > 0) {
            titleContainer.setItem(PREVIOUS_PAGE_SLOT, GuiNavigationService.previous());
        }
        titleContainer.setItem(UNEQUIP_SLOT, GuiStatusItem.create(new ItemStack(Items.BARRIER),
                ServerText.translatable("gui.omnitools.title.unequip"), GuiStatusItem.State.BLOCKED,
                List.of(ServerText.translatable("gui.omnitools.title.unequip_hint").withStyle(ChatFormatting.GRAY))));
        titleContainer.setItem(EFFECTS_SLOT, effectsItem(selectedId));
        titleContainer.setItem(PROFILE_SLOT, GuiNavigationService.page(page + 1, pageCount, unlockedTitles.size()));
        if (page + 1 < pageCount) {
            titleContainer.setItem(NEXT_PAGE_SLOT, GuiNavigationService.next());
        }
        stateHash = currentStateHash();
        lastConfigRevision = ModMindEntry.configSnapshot().revision();
    }

    private boolean refreshConfigIfChanged() {
        if (ModMindEntry.configSnapshot().revision() == lastConfigRevision) {
            return false;
        }
        config = ModMindEntry.titleConfig();
        refreshContents();
        return true;
    }

    private ItemStack profileItem(int unlockedCount, String selectedId) {
        ItemStack profile = new ItemStack(Items.PLAYER_HEAD);
        profile.set(DataComponents.PROFILE, ResolvableProfile.createResolved(owner.getGameProfile()));
        profile.set(DataComponents.CUSTOM_NAME, ServerText.translatable("gui.omnitools.title.profile")
                .withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD));
        Component equipped = selectedId.isEmpty()
                ? ServerText.translatable("gui.omnitools.title.no_selection")
                : config.definition(selectedId)
                        .<Component>map(title -> TextTemplateRenderer.render(owner, title.display()))
                        .orElse(Component.literal(selectedId).withStyle(ChatFormatting.RED));
        profile.set(DataComponents.LORE, new ItemLore(List.of(
                ServerText.translatable("gui.omnitools.title.unlocked_count", unlockedCount).withStyle(ChatFormatting.AQUA),
                ServerText.translatable("gui.omnitools.title.current").append(equipped).withStyle(ChatFormatting.GRAY),
                selectedId.isEmpty() ? ServerText.translatable("gui.omnitools.title.no_selection")
                        .withStyle(ChatFormatting.DARK_GRAY) : entitlementComponent(selectedId))));
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
            hash = 31 * hash + config.entitlement(ownerId, title.id()).hashCode();
        }
        return hash;
    }

    private Component entitlementComponent(String titleId) {
        return config.entitlement(ownerId, titleId).<Component>map(entitlement -> entitlement.isPermanent()
                ? ServerText.translatable("gui.omnitools.title.permanent").withStyle(ChatFormatting.GREEN)
                : ServerText.translatable("gui.omnitools.title.remaining", remainingText(entitlement))
                        .withStyle(ChatFormatting.GOLD)).orElseGet(() ->
                ServerText.translatable("gui.omnitools.title.expired").withStyle(ChatFormatting.RED));
    }

    private static String remainingText(TimedEntitlement entitlement) {
        long seconds = Math.max(0L, entitlement.remainingActiveTicks() / 20L);
        long days = seconds / 86_400L;
        long hours = (seconds % 86_400L) / 3_600L;
        long minutes = (seconds % 3_600L) / 60L;
        long remainingSeconds = seconds % 60L;
        return days + "d " + String.format(java.util.Locale.ROOT, "%02d:%02d:%02d", hours, minutes, remainingSeconds);
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

    private ItemStack effectsItem(String selectedId) {
        boolean enabled = config.effectsEnabled(ownerId);
        List<Component> lore = new java.util.ArrayList<>();
        lore.add(ServerText.translatable("gui.omnitools.title.effects_hint").withStyle(ChatFormatting.GRAY));
        ItemStack item = GuiTheme.status(enabled ? Items.LIME_DYE : Items.GRAY_DYE,
                ServerText.translatable(enabled ? "gui.omnitools.title.effects_on" : "gui.omnitools.title.effects_off"),
                enabled ? ChatFormatting.GREEN : ChatFormatting.GRAY, lore, false);
        if (!selectedId.isEmpty()) {
            config.definition(selectedId).ifPresent(title -> {
                List<TitleEffectConfig.EffectDefinition> effects = config.effectsFor(title,
                        ModMindEntry.titleEffectConfig());
                if (effects.isEmpty()) {
                    lore.add(ServerText.translatable("gui.omnitools.title.no_effects").withStyle(ChatFormatting.DARK_GRAY));
                }
                for (TitleEffectConfig.EffectDefinition effect : effects) {
                    lore.add(effectComponent(effect));
                }
                item.set(DataComponents.LORE, new ItemLore(lore));
            });
        }
        return item;
    }

    private Component effectComponent(TitleEffectConfig.EffectDefinition effect) {
        return TextTemplateRenderer.render(owner, effect.display().isBlank() ? effect.name() : effect.display());
    }

}
