# 统一奖励

每日签到、在线奖励和成就共用同一套 `rewards` 数组。每个奖励的 `id` 必须在同一事件内唯一，且为 1-64 位小写字母、数字、`_`、`.` 或 `-`。

下面的 `jsonc` 仅用于教学，包含注释，不能直接复制到真实 JSON 文件。

```jsonc
[
  { "id": "coins", "type": "currency", "amount": 100 }, // 发放货币
  {
    "id": "bread",
    "type": "item",
    "item": "minecraft:bread", // 简单物品 ID
    "count": 8,
    "components": {} // 可选的原版或已注册模组数据组件
  },
  {
    "id": "vip_week",
    "type": "title",
    "title": "vip", // 必须是 titles 配置中已有的 ID
    "duration": {
      "mode": "active_days", // permanent 或 active_days
      "days": 7
    },
    "renewal": "extend" // extend、replace 或 max
  },
  {
    "id": "announce",
    "type": "command",
    "run_as": "console",
    "command": "say {player_name} completed a milestone"
  }
]
```

可直接复制的 JSON：

```json
[
  { "id": "coins", "type": "currency", "amount": 100 },
  {
    "id": "bread",
    "type": "item",
    "item": "minecraft:bread",
    "count": 8,
    "components": {}
  },
  {
    "id": "vip_week",
    "type": "title",
    "title": "vip",
    "duration": {
      "mode": "active_days",
      "days": 7
    },
    "renewal": "extend"
  },
  {
    "id": "announce",
    "type": "command",
    "run_as": "console",
    "command": "say {player_name} completed a milestone"
  }
]
```

| `type` | 必填字段 | 规则 |
| --- | --- | --- |
| `currency` | `id`、`amount` | `amount` 为非负整数。 |
| `item` | `id`，以及简单写法或完整 SNBT 写法之一 | 物品数量必须为 1-64。 |
| `title` | `id`、`title` | `title` 必须在称号模块中存在。可选 `duration` 与 `renewal`。 |
| `command` | `id`、`run_as`、`command` | `run_as` 只能为 `console`，并受根配置的命令奖励开关与白名单限制。 |

## 称号的有效佩戴时长

没有 `duration` 的 `title` 奖励为永久称号，兼容旧配置：

```json
{ "id": "legend_forever", "type": "title", "title": "legend" }
```

临时称号必须使用推荐的新格式：

```json
{
  "id": "vip_7_days",
  "type": "title",
  "title": "vip",
  "duration": { "mode": "active_days", "days": 7 },
  "renewal": "extend"
}
```

- `duration.mode`：`permanent` 或 `active_days`。
- `active_days` 必须有正整数 `days`；1 天等于 1,728,000 个在线且佩戴时的服务器 tick。
- `permanent` 不能有 `days`。
- `renewal`：`extend`（默认，追加新时长）、`replace`（替换为新时长）、`max`（取新旧较大值）。
- 已经是永久的称号不会被临时奖励缩短或覆盖。
- 同一个奖励事件和奖励 ID 只处理一次；不同事件再次奖励同一称号时才按 `renewal` 续期。
- 重载后已发放的剩余时长保持不变；修改奖励定义只影响新创建的奖励事件。

## 物品：简单组件与完整 SNBT

普通改名、Lore 和附魔优先使用 `item`、`count`、`components`。需要完整物品堆、复杂容器内容或兼容既有商店 SNBT 时，使用完整 `nbt` 写法。两种写法二选一，不能把 `nbt` 与 `item`、`count`、`components` 同时出现。

```jsonc
{
  "id": "named_bread", // 奖励稳定 ID
  "type": "item",
  "nbt": "{id:'minecraft:bread',count:8,components:{'minecraft:custom_name':'{\"text\":\"每日面包\"}'}}" // 完整 ItemStack SNBT
}
```

```json
{
  "id": "named_bread",
  "type": "item",
  "nbt": "{id:'minecraft:bread',count:8,components:{'minecraft:custom_name':'{\"text\":\"每日面包\"}'}}"
}
```

`nbt` 是完整 ItemStack SNBT，至少要有 `id`，通常也应有 `count` 与 `components`；它不是局部 NBT 片段。JSON 字符串中的双引号必须写为 `\"`。服务器会限制 SNBT 源文本和可持久化物品快照为 32 KiB，并验证物品、数量、组件和账本编码/解码；无效配置会使重载失败并保留旧快照。

背包空间不足时，物品快照进入玩家奖励箱。之后修改同一奖励 ID 的物品或 SNBT 不会改写已入箱的快照；崩溃边界不会自动危险重放。

## 命令奖励安全边界

命令中只能使用以下受控变量：

```text
{player_name}
{player_uuid}
{player_x}
{player_y}
{player_z}
{player_world}
```

不能在控制台命令中使用 `%omnitools:...%` 或任何第三方文本占位符。命令奖励还必须通过根配置的总开关、长度限制、冷却和 `allowed_roots` 白名单。

奖励状态、重试和人工结案见[奖励一致性](../guides/reward-consistency.md)。
