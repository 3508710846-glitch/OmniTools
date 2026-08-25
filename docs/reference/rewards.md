# 统一奖励格式

每日签到、在线奖励和成就使用同一份奖励定义。每项 `id` 必须在同一事件内唯一，且只允许小写字母、数字、`_`、`.`、`-`，长度 1--64。

教学版，不能直接复制：

```jsonc
[
  { "id": "coins", "type": "currency", "amount": 100 }, // 货币
  { "id": "bread", "type": "item", "item": "minecraft:bread", "count": 8 }, // 物品
  { "id": "starter_title", "type": "title", "title": "geologist" }, // 已定义的称号
  {
    "id": "announce", "type": "command",
    "run_as": "console", // 奖励命令只能由控制台运行
    "command": "say {player_name} completed a milestone"
  }
]
```

可直接复制版：

```json
[
  { "id": "coins", "type": "currency", "amount": 100 },
  { "id": "bread", "type": "item", "item": "minecraft:bread", "count": 8 },
  { "id": "starter_title", "type": "title", "title": "geologist" },
  {
    "id": "announce",
    "type": "command",
    "run_as": "console",
    "command": "say {player_name} completed a milestone"
  }
]
```

| `type` | 必填字段 | 限制 |
| --- | --- | --- |
| `currency` | `id`、`amount` | `amount` 为非负整数。 |
| `item` | `id`、`item`、`count` | `count` 为 1--64；可加 `components` 原版组件字符串。`nbt` 不支持。 |
| `title` | `id`、`title` | `title` 必须存在于称号模块，且称号模块已启用。 |
| `command` | `id`、`run_as`、`command` | `run_as` 必须是 `console`；还受根配置总开关、长度与白名单限制。 |

安全红线：命令中只允许 `{player_name}`、`{player_uuid}`、`{player_x}`、`{player_y}`、`{player_z}`、`{player_world}`。不允许 `%omnitools:...%` 或任何第三方文本占位符进入命令内容。

物品奖励在背包空间不足时进入玩家奖励箱；使用 `/omnitools rewards open` 重试。奖励账本状态和人工结案见[奖励一致性](../guides/reward-consistency.md)。
