package dev.modmind.omnitools;

import dev.modmind.omnitools.config.ModuleId;
import dev.modmind.omnitools.permissions.CommandAction;
import dev.modmind.omnitools.skills.SkillTreeConfig;
import dev.modmind.omnitools.skills.SkillTreeData;
import dev.modmind.omnitools.skills.SkillTreeService;
import net.minecraft.ChatFormatting;
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

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Server-authoritative skill-tree menu. No status is inferred from display text or lore. */
public final class SkillTreeScreenHandler extends ChestMenu {
    private static final int ROWS = 6;
    private static final int SIZE = ROWS * 9;
    private static final int CLOSE_SLOT = GuiSlots.HEADER_CLOSE_54;
    private static final int BACK_SLOT = GuiSlots.HEADER_LEFT_54;
    private static final int ATTRIBUTE_SLOT = 31;
    private static final int[] SKILL_SLOTS = {11, 13, 15, 17};
    private final SimpleContainer container;
    private final ServerPlayer owner;
    private final UUID ownerId;
    private final SkillTreeService service;
    private String selectedTreeId = "";
    private long lastRefreshTick = Long.MIN_VALUE;
    private long lastRevision = Long.MIN_VALUE;

    public SkillTreeScreenHandler(int syncId, Inventory inventory) {
        this(syncId, inventory, new SimpleContainer(SIZE), null, null);
    }

    private SkillTreeScreenHandler(int syncId, Inventory inventory, SimpleContainer container,
                                   ServerPlayer owner, SkillTreeService service) {
        super(MenuType.GENERIC_9x6, syncId, inventory, container, ROWS);
        this.container = container;
        this.owner = owner;
        this.ownerId = owner == null ? null : owner.getUUID();
        this.service = service;
        if (owner != null && service != null) refreshContents();
    }

    public static SkillTreeScreenHandler createServer(int syncId, Inventory inventory, ServerPlayer owner,
                                                       SkillTreeService service) {
        return new SkillTreeScreenHandler(syncId, inventory, new SimpleContainer(SIZE), owner, service);
    }

    @Override
    public void clicked(int slotId, int button, ClickType clickType, Player player) {
        if (!ModMindEntry.isModuleEnabled(ModuleId.SKILLS) || (player instanceof ServerPlayer serverPlayer
                && !ModMindEntry.hasCommandPermission(serverPlayer, CommandAction.SKILLS_OPEN))) {
            if (player instanceof ServerPlayer serverPlayer) serverPlayer.closeContainer();
            return;
        }
        if (slotId < 0 || slotId >= SIZE) {
            super.clicked(slotId, button, clickType, player);
            return;
        }
        if (!(player instanceof ServerPlayer serverPlayer) || !serverPlayer.getUUID().equals(ownerId)
                || clickType != ClickType.PICKUP) return;
        if (slotId == CLOSE_SLOT) {
            serverPlayer.closeContainer();
            return;
        }
        if (selectedTreeId.isBlank()) {
            int index = GuiSlots.contentIndex54(slotId);
            List<SkillTreeConfig.TreeDefinition> trees = service.config().trees();
            if (index >= 0 && index < trees.size()) {
                selectedTreeId = trees.get(index).id();
                refreshContents();
            }
            return;
        }
        if (slotId == BACK_SLOT) {
            selectedTreeId = "";
            refreshContents();
            return;
        }
        if (slotId == ATTRIBUTE_SLOT) {
            service.investAttribute(serverPlayer, selectedTreeId);
            refreshContents();
            return;
        }
        SkillTreeConfig.TreeDefinition tree = service.config().tree(selectedTreeId).orElse(null);
        if (tree == null) {
            selectedTreeId = "";
            refreshContents();
            return;
        }
        for (int index = 0; index < SKILL_SLOTS.length; index++) {
            if (slotId == SKILL_SLOTS[index]) {
                service.unlockSkill(serverPlayer, tree.id(), tree.skills().get(index).id());
                refreshContents();
                return;
            }
        }
    }

    @Override
    public ItemStack quickMoveStack(Player player, int slot) { return ItemStack.EMPTY; }

    @Override
    public void broadcastChanges() {
        if (owner != null && service != null) {
            long tick = owner.level().getServer().getTickCount();
            if (tick - lastRefreshTick >= 20L || lastRevision != service.revision()) refreshContents();
        }
        super.broadcastChanges();
    }

    private void refreshContents() {
        if (owner == null || service == null) return;
        GuiTheme.clear(container);
        lastRefreshTick = owner.level().getServer().getTickCount();
        lastRevision = service.revision();
        if (selectedTreeId.isBlank()) renderOverview();
        else renderDetail();
        container.setItem(CLOSE_SLOT, GuiTheme.navigation(Items.BARRIER, Component.literal("关闭"), null));
    }

    private void renderOverview() {
        List<SkillTreeConfig.TreeDefinition> trees = service.config().trees();
        int completed = 0;
        int availablePoints = 0;
        for (SkillTreeConfig.TreeDefinition tree : trees) {
            SkillTreeData.Progress progress = service.progress(owner, tree.id());
            if (progress.level() >= service.config().settings().maxLevel()) completed++;
            availablePoints += progress.availablePoints();
        }
        container.setItem(GuiSlots.HEADER_LEFT_54, GuiTheme.status(Items.EXPERIENCE_BOTTLE,
                Component.literal("技能树").withStyle(ChatFormatting.AQUA), ChatFormatting.AQUA,
                List.of(Component.literal("已满级：" + completed + " / " + trees.size()).withStyle(ChatFormatting.GRAY),
                        Component.literal("可用技能点：" + availablePoints).withStyle(ChatFormatting.GREEN)), false));
        container.setItem(GuiSlots.HEADER_CENTER_54, GuiTheme.status(Items.NETHER_STAR,
                Component.literal("MMO 成长").withStyle(ChatFormatting.GOLD), ChatFormatting.GOLD,
                List.of(Component.literal("每棵技能树独立积累经验与属性。 ").withStyle(ChatFormatting.GRAY)), false));
        if (trees.isEmpty()) {
            container.setItem(GuiSlots.CENTER_54, GuiTheme.named(Items.BOOK, Component.literal("暂无技能树").withStyle(ChatFormatting.GRAY),
                    List.of(Component.literal("请由管理员在 skills/config.json 配置。 ").withStyle(ChatFormatting.DARK_GRAY))));
            return;
        }
        for (int index = 0; index < trees.size() && index < GuiSlots.CONTENT_SLOT_COUNT_54; index++) {
            SkillTreeConfig.TreeDefinition tree = trees.get(index);
            SkillTreeData.Progress progress = service.progress(owner, tree.id());
            long required = progress.level() >= service.config().settings().maxLevel() ? 0L : service.xpRequired(tree, progress.level());
            List<Component> lore = new ArrayList<>();
            lore.add(Component.literal("等级：" + progress.level() + " / " + service.config().settings().maxLevel()).withStyle(ChatFormatting.WHITE));
            lore.add(Component.literal("经验：" + progress.currentXp() + " / " + required).withStyle(ChatFormatting.GRAY));
            lore.add(Component.literal("属性加成：" + percent(service.attributeBonus(progress))).withStyle(ChatFormatting.GOLD));
            lore.add(Component.literal("可用技能点：" + progress.availablePoints()).withStyle(ChatFormatting.GREEN));
            lore.add(Component.literal("点击查看技能与强化").withStyle(ChatFormatting.AQUA));
            container.setItem(GuiSlots.contentSlot54(index), GuiTheme.status(tree.icon(),
                    Component.literal(tree.display()).withStyle(ChatFormatting.YELLOW), ChatFormatting.YELLOW, lore,
                    progress.availablePoints() > 0));
        }
    }

    private void renderDetail() {
        SkillTreeConfig.TreeDefinition tree = service.config().tree(selectedTreeId).orElse(null);
        if (tree == null) {
            selectedTreeId = "";
            renderOverview();
            return;
        }
        SkillTreeData.Progress progress = service.progress(owner, tree.id());
        long required = progress.level() >= service.config().settings().maxLevel() ? 0L : service.xpRequired(tree, progress.level());
        container.setItem(BACK_SLOT, GuiTheme.navigation(Items.ARROW, Component.literal("返回总览"), null));
        container.setItem(GuiSlots.HEADER_CENTER_54, GuiTheme.status(tree.icon(), Component.literal(tree.display()),
                ChatFormatting.YELLOW, List.of(Component.literal("等级：" + progress.level() + " / " + service.config().settings().maxLevel()),
                        Component.literal("经验：" + progress.currentXp() + " / " + required),
                        Component.literal("溢出经验：" + progress.overflowXp()).withStyle(ChatFormatting.DARK_GRAY)), false));
        for (int index = 0; index < tree.skills().size(); index++) {
            SkillTreeConfig.SkillDefinition skill = tree.skills().get(index);
            boolean unlocked = progress.unlockedSkills().contains(skill.id());
            boolean levelReady = progress.level() >= skill.unlockLevel();
            ItemStack item;
            List<Component> lore = new ArrayList<>();
            lore.add(Component.literal(skill.description()).withStyle(ChatFormatting.GRAY));
            lore.add(Component.literal("解锁等级：" + skill.unlockLevel()).withStyle(levelReady ? ChatFormatting.GREEN : ChatFormatting.RED));
            lore.add(Component.literal("技能点消耗：" + skill.pointCost()).withStyle(ChatFormatting.GOLD));
            if (unlocked) {
                lore.add(Component.literal("状态：已解锁").withStyle(ChatFormatting.GREEN));
                item = GuiTheme.status(Items.GREEN_DYE, Component.literal(skill.display()), ChatFormatting.GREEN, lore, false);
            } else if (levelReady && progress.availablePoints() >= skill.pointCost()) {
                lore.add(Component.literal("状态：可解锁，点击确认").withStyle(ChatFormatting.AQUA));
                item = GuiTheme.status(Items.LIME_DYE, Component.literal(skill.display()), ChatFormatting.GREEN, lore, true);
            } else {
                lore.add(Component.literal("状态：尚未满足条件").withStyle(ChatFormatting.RED));
                item = GuiTheme.status(Items.GRAY_DYE, Component.literal(skill.display()), ChatFormatting.GRAY, lore, false);
            }
            container.setItem(SKILL_SLOTS[index], item);
        }
        int maxAttributePoints = (int) Math.floor((service.config().settings().pointAttributeCap() + 0.000_000_1D)
                / service.config().settings().pointAttributeBonus());
        boolean canInvest = progress.availablePoints() > 0 && progress.attributePoints() < maxAttributePoints;
        container.setItem(ATTRIBUTE_SLOT, GuiTheme.status(canInvest ? Items.LIME_DYE : Items.GRAY_DYE,
                Component.literal("属性强化").withStyle(canInvest ? ChatFormatting.GREEN : ChatFormatting.GRAY),
                canInvest ? ChatFormatting.GREEN : ChatFormatting.GRAY,
                List.of(Component.literal("当前加成：" + percent(service.attributeBonus(progress))).withStyle(ChatFormatting.GOLD),
                        Component.literal("属性点：" + progress.attributePoints() + " / " + maxAttributePoints),
                        Component.literal("每点增加：" + percent(service.config().settings().pointAttributeBonus())),
                        Component.literal(canInvest ? "点击消耗 1 点强化" : "需要可用技能点或未达到上限")
                                .withStyle(canInvest ? ChatFormatting.AQUA : ChatFormatting.RED)), canInvest));
    }

    private static String percent(double value) { return String.format(java.util.Locale.ROOT, "%.1f%%", value * 100.0D); }
}
