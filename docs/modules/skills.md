# 技能树模块

技能树配置文件位于 `config/omnitools/skills/config.json`。首次启用时会生成 6 棵基础树：`gathering`、`combat`、`defense`、`hunting`、`crafting`、`survival`。

每棵树独立保存等级、当前经验、总经验、可用技能点、属性投资、已解锁技能、每日有效经验和满级后的溢出经验。所有经验必须经过 `SkillTreeService.addSkillXp`，不会从物品名称或 Lore 推断状态。

## 核心配置

```json
{
  "format_version": 1,
  "settings": {
    "max_level": 2000,
    "points_every_levels": 500,
    "max_daily_xp": 250000,
    "min_interval_ticks": 4,
    "base_attribute_cap": 0.30,
    "point_attribute_cap": 0.20,
    "point_attribute_bonus": 0.05,
    "max_title_xp_bonus": 0.50,
    "xp_base": 100,
    "xp_linear": 25,
    "xp_quadratic": 0.015
  }
}
```

升级所需经验为 `(xp_base + level * xp_linear + level^2 * xp_quadratic) * 阶段倍率`。默认阶段倍率分别从 1、501、1001、1501 级开始为 `1.0`、`1.25`、`1.6`、`2.0`。

基础等级属性最多 30%，每个投入属性的技能点提供 5%，最多再投入 4 点，总上限为 50%。技能点按 `floor(level / points_every_levels)` 计算，重载与重登不会重复发放。

## 技能树定义

每棵树固定包含 4 个定义，配置阶段按如下结构编辑：

```json
{
  "id": "combat",
  "display": "战斗",
  "icon": "minecraft:iron_sword",
  "attribute": "attack_damage",
  "sources": ["entity_kill", "reward", "command"],
  "level_multipliers": [
    { "from_level": 1, "multiplier": 1.0 },
    { "from_level": 501, "multiplier": 1.25 }
  ],
  "skills": [
    {
      "id": "foundation",
      "display": "战斗本能",
      "description": "基础技能，达到等级后可解锁。",
      "unlock_level": 1,
      "point_cost": 0
    }
  ]
}
```

可用经验来源：`block_break`、`entity_kill`、`craft`、`survival`、`reward`、`command`。默认实现使用方块破坏、击杀、合成统计增量和有效位移；每个来源会经过树的白名单、最小事件间隔和每日上限。`reward` 与 `command` 同样会校验树 ID 和每日上限。

原生属性目标限定为 `block_break_speed`、`attack_damage`、`armor`、`luck`、`movement_speed`、`max_health` 的安全映射。四个技能的解锁条件与点数扣除已持久化；特殊掉落、暴击、制造品质和主动冷却效果应作为后续明确数值后的独立效果类型接入，避免仅凭 Lore 声称已改变玩法。

## 称号联动

称号效果可使用服务端专用的 `skill_xp` 类型，它只影响技能经验，不叠加原生属性：

```json
{
  "id": "combat_xp_15",
  "name": "战斗经验",
  "type": "skill_xp",
  "amount": 0.15,
  "display": "战斗技能经验 +15%"
}
```

多个已选称号效果按加法累计，实际结算时由 `max_title_xp_bonus` 封顶，默认最多 `+50%`。

## 奖励与礼包投放

统一奖励定义支持直接发放技能经验：

```json
{
  "id": "combat_xp_1000",
  "type": "skill_xp",
  "tree": "combat",
  "amount": 1000
}
```

该奖励会由既有奖励账本记录，并在进程中断时保守地进入人工处理状态，不会自动重放而重复增加经验。礼包模块仍保持“物品投递批次”职责；把礼包、成就、在线奖励或活动奖励配置为上述统一 `skill_xp` 奖励即可投放经验，不会把非物品状态伪装成 `ItemStack`。

玩家入口：`/omnitools skills` 或 `/skills`。管理员可对自己使用 `/omnitools skills add <tree> <amount>`，需要 `skills.admin` 权限。
