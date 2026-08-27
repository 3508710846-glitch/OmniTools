# 成就条件示例

每个 JSON 文件都是完整的 `config/omnitools/achievements/config.json`，适用于 `format_version: 2`。启用 `achievements` 模块后复制其中**一个**文件作为起点，再执行 `/omnitools reload achievements`。称号或指令奖励还需先满足[奖励参考](../../reference/rewards.md)中的称号定义和命令安全要求。

| 场景 | 示例文件 | 适用条件 |
| --- | --- | --- |
| 挖掘单一方块 | `01-mine-one-block.json` | 最简单的 `stat` 与单目标。 |
| 多目标累计 | `02-sum-multiple-targets.json` | 多个目标的 `match: sum`。 |
| 每个目标都达标 | `03-each-target-must-pass.json` | `match: each`。 |
| 任一分支达成 | `04-any-condition.json` | 条件树 `any`。 |
| 所有分支达成 | `05-all-conditions.json` | 条件树 `all`。 |
| 排除条件 | `06-not-condition.json` | 条件树 `not`。 |
| 移动距离 | `07-distance-statistics.json` | `custom` 距离统计与单位转换。 |
| 在线或使用时长 | `08-time-statistics.json` | `custom` 时间统计与单位转换。 |
| 伤害统计 | `09-damage-statistics.json` | `custom` 伤害统计与单位转换。 |
| 实体与 Boss | `10-entity-and-boss.json` | `entity_killed`、实体目标。 |
| 标签、分组与通配符 | `11-target-groups-tags-wildcards.json` | `$目标组`、标签和 `*`。 |
| 奖励组合 | `12-four-reward-types.json` | 货币、物品、称号和受控指令奖励。 |

预设位于 `docs/presets/achievements/`，适合按玩法组合加载；字段解释、数据保存和验收流程以[成就模块主说明](../../modules/achievements.md)为准。
