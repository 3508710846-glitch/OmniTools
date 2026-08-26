# 成就

## 1. 用途与场景

成就以原版统计数据判断，可组合 `stat`、`sum`、`all`、`any`、`not` 条件。检查器按玩家数与条件数预算分批运行；打开菜单和领奖时会即时校验当前玩家。

## 2. 前置条件、关联模块与开关

根开关为 `modules.achievements.enabled`。称号或指令奖励还分别依赖称号模块、根命令安全配置。

## 3. 配置路径与重载

文件为 `config/omnitools/achievements/config.json`，修改后执行 `/omnitools reload`，队列会重新建立。

## 4. 最小可用配置

下方以一个挖掘方块的成就构成最小可用配置。

## 5. 注释教学版 `jsonc`

教学版，不能直接复制：

```jsonc
{
  "format_version": 2, // 成就新格式版本。
  "achievements": [{ // 成就定义列表。
    "id": "mine_stone", // 稳定成就 ID，发奖后不要修改。
    "display": "石匠", // 玩家看到的成就名称。
    "description": "挖掘 1 个石头", // 玩家看到的完成条件说明。
    "icon": "minecraft:stone", // GUI 中显示的物品图标。
    "requirements": { // v2 推荐：一个条件对象
      "type": "stat", // 单个统计条件节点。
      "stat": "block_mined", // 使用原版挖掘方块统计域。
      "targets": ["minecraft:stone"], // 要统计的方块 ID。
      "match": "sum", // 多目标时将统计值相加。
      "at_least": 1 // 达成阈值。
    },
    "rewards": [{ "id": "coins", "type": "currency", "amount": 10 }] // 成就完成后发放的奖励。
  }]
}
```

## 6. 可直接复制版 `json`

```json
{
  "format_version": 2,
  "achievements": [
    {
      "id": "mine_one_stone",
      "display": "石匠起步",
      "description": "挖掘 1 个石头",
      "icon": "minecraft:stone",
      "requirements": {
        "type": "stat",
        "stat": "block_mined",
        "targets": ["minecraft:stone"],
        "match": "sum",
        "at_least": 1
      },
      "rewards": [{ "id": "coins", "type": "currency", "amount": 10 }]
    }
  ]
}
```

同一文件也作为 [01-mine-one-block.json](../examples/achievement-examples/01-mine-one-block.json) 独立保存。

## 7. 字段表

| 字段 | 类型 | 必填 | 默认/范围 | 常见错误 |
| --- | --- | --- | --- | --- |
| `format_version` | 整数 | 是 | 1--2；推荐 2 | 用 v2 对象却标 v1。 |
| `check_scheduler` | 对象 | 否 | 间隔 1--1200；玩家 1--1000；条件 1--16384；全量秒数 1--86400 | 设为 0。 |
| `id` | 小写 ID | 是 | 1--64、唯一 | 修改已发奖 ID。 |
| `display`、`description`、`icon` | 文本、文本、物品 ID | 是 | 图标不能 air | 图标拼错。 |
| `requirements` | 条件对象 | 是 | 最大嵌套 8 | 没有正向 `stat`/`sum`。 |
| `rewards` | 奖励数组 | 是 | 可为空 | 指令奖励未开白名单。 |

## 8. 全部配置场景

每个文件都是可复制的完整 `achievements/config.json`：

- [01 挖掘一个方块](../examples/achievement-examples/01-mine-one-block.json)
- [02 多目标总和](../examples/achievement-examples/02-sum-multiple-targets.json)
- [03 每个目标都达标](../examples/achievement-examples/03-each-target-must-pass.json)
- [04 任一条件](../examples/achievement-examples/04-any-condition.json)
- [05 全部条件](../examples/achievement-examples/05-all-conditions.json)
- [06 非条件](../examples/achievement-examples/06-not-condition.json)
- [07 距离](../examples/achievement-examples/07-distance-statistics.json)
- [08 时间](../examples/achievement-examples/08-time-statistics.json)
- [09 伤害](../examples/achievement-examples/09-damage-statistics.json)
- [10 实体与 Boss](../examples/achievement-examples/10-entity-and-boss.json)
- [11 目标组、标签、通配符](../examples/achievement-examples/11-target-groups-tags-wildcards.json)
- [12 四类奖励](../examples/achievement-examples/12-four-reward-types.json)

仓库附带的可加载预设位于 `docs/presets/achievements/`：

- [collection.json](../presets/achievements/collection.json)：采集类目标。
- [combat.json](../presets/achievements/combat.json)：战斗和实体击杀。
- [container-interaction.json](../presets/achievements/container-interaction.json)：容器交互统计。
- [distance.json](../presets/achievements/distance.json)：移动距离统计。
- [exploration.json](../presets/achievements/exploration.json)：探索相关统计。

预设可能采用为兼容旧服保留的格式；新服改造时以本页 v2 条件对象和各独立示例为准。

`stat` 支持 `block_mined`、`item_crafted`、`item_used`、`item_broken`、`item_picked_up`、`item_dropped`、`entity_killed`、`entity_killed_by`、`custom`。多个目标用 `match: sum`、`each` 或 `any`。目标可写普通 ID、`$目标组`、`#namespace:tag`、`*` 通配符。`achievements[].rewards` 的物品也可使用[统一奖励](../reference/rewards.md)的完整 ItemStack SNBT `nbt` 写法。

`custom` 的距离单位为 `cm`/`meters`/`blocks`/`kilometers`，时间为 `ticks`/`seconds`/`minutes`/`hours`，伤害为 `damage`/`hearts`，其他统计为 `count`。原版没有严格独立的“方块放置数”统计；`item_used` 只能近似物品使用或放置，不能作为精确放置数宣传。

## 9. 指令、权限与默认角色

`/omnitools achievements` 默认 `PLAYER`。玩家在 GUI 领取已解锁奖励；管理员奖本操作由 `rewards.admin` 默认 `ADMIN` 控制。

## 10. 占位符

`%achievements_unlocked%`、`%achievements_claimed%`、`%achievements_total%`。

## 11. 数据与升级

解锁和奖励账本保存在 SavedData。不要更换已上线成就 ID，新的 JSON 先在备份服务器重载验证。

## 12. 验收与排错

复制一个例子、重载、完成目标并打开成就 GUI。配置失败时先检查条件单位、目标类型和引用的称号/命令安全配置。
