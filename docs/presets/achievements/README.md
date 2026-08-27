# 成就预设

这些严格 JSON 文件均可作为完整 `config/omnitools/achievements/config.json` 的起点，适用于 `format_version: 2`。启用 `achievements` 后复制其中一个文件，按本服经济和称号定义调整奖励，再执行 `/omnitools reload achievements`。

| 预设 | 场景 | 前置注意事项 |
| --- | --- | --- |
| `collection.json` | 采集与收集 | 检查目标物品、奖励物品和称号 ID。 |
| `combat.json` | 战斗与实体击杀 | 检查实体目标及伤害/击杀统计。 |
| `container-interaction.json` | 容器交互 | 验证原版统计是否符合服务器玩法。 |
| `distance.json` | 移动距离 | 确认距离单位和目标阈值。 |
| `exploration.json` | 探索 | 确认维度、目标组与统计目标。 |

预设不绕过成就条件校验、奖励账本或命令白名单。字段解释、完整示例索引和数据升级规则以[成就模块主说明](../../modules/achievements.md)为准。
