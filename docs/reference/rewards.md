# 统一奖励格式

每日签到、在线奖励和成就使用同一份奖励定义。每项 `id` 必须在同一事件内唯一，且只允许小写字母、数字、`_`、`.`、`-`，长度 1--64。

`item` 奖励有两种互斥写法。普通物品、改名、Lore 和附魔优先使用 `item`、`count` 与 `components`；需要完整物品堆、复杂容器内容或兼容既有商店 SNBT 时，使用完整 `nbt`。`nbt` 不是附加片段，必须是含 `id` 的完整 ItemStack SNBT。

教学版，不能直接复制：

```jsonc
[
  { "id": "coins", "type": "currency", "amount": 100 }, // 稳定奖励 ID、货币类型和发放数量。
  { "id": "bread", "type": "item", "item": "minecraft:bread", "count": 8 }, // 简单物品：稳定 ID、物品 ID 和数量。
  {
    "id": "named_sword", // NBT 奖励的稳定 ID。
    "type": "item", // 仍是现有 item 类型，不会新增第五种奖励。
    "nbt": "{id:'minecraft:diamond_sword',count:1,components:{'minecraft:custom_name':{text:'签到宝剑',color:'gold',italic:false},'minecraft:enchantments':{'minecraft:sharpness':5,'minecraft:unbreaking':3},'minecraft:unbreakable':{}}}" // 完整 ItemStack SNBT；示例使用单引号，若在 JSON 字符串内写双引号必须转义为 \"。
  },
  { "id": "starter_title", "type": "title", "title": "geologist" }, // 稳定 ID、称号类型和已定义的称号 ID。
  {
    "id": "announce", // 稳定奖励 ID。
    "type": "command", // 指令奖励类型。
    "run_as": "console", // 奖励命令只能由控制台运行
    "command": "say {player_name} completed a milestone" // 只能使用允许的 {player_*} 变量。
  }
]
```

可直接复制版：

```json
[
  { "id": "coins", "type": "currency", "amount": 100 },
  { "id": "bread", "type": "item", "item": "minecraft:bread", "count": 8 },
  {
    "id": "named_sword",
    "type": "item",
    "nbt": "{id:'minecraft:diamond_sword',count:1,components:{'minecraft:custom_name':{text:'签到宝剑',color:'gold',italic:false},'minecraft:enchantments':{'minecraft:sharpness':5,'minecraft:unbreaking':3},'minecraft:unbreakable':{}}}"
  },
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
| `item` | `id` 加 `item`、`count`，或 `id` 加 `nbt` | 两种写法二选一。`count` 或 NBT 内数量必须为 1--64；可加 `components` 原版组件字符串。`nbt` 必须是完整 ItemStack SNBT，不能同时出现 `item`、`count`、`components`。 |
| `title` | `id`、`title` | `title` 必须存在于称号模块，且称号模块已启用。 |
| `command` | `id`、`run_as`、`command` | `run_as` 必须是 `console`；还受根配置总开关、长度与白名单限制。 |

安全红线：命令中只允许 `{player_name}`、`{player_uuid}`、`{player_x}`、`{player_y}`、`{player_z}`、`{player_world}`。不允许 `%omnitools:...%` 或任何第三方文本占位符进入命令内容。

完整 SNBT 的源文本和账本序列化快照均限制为 32 KiB。重载时会用服务器注册表解析、再按奖励账本的 `ItemStack.CODEC + RegistryOps(NbtOps, 服务器注册表)` 路径编码解码；未知原版/模组组件、空气、数量越界或无法持久化的物品会令重载失败并保留旧快照。不会额外屏蔽容器、药水、附魔或已注册模组组件。

旧 `dailyCoins`、`monthlyRewards`、在线奖励 `coins` 和成就旧奖励对象只能表示货币/称号，不能加入 NBT。迁移到统一 `rewards` 数组后才可使用本页格式，迁移器不会自动改写已有奖励配置。

物品奖励在背包空间不足时进入玩家奖励箱；使用 `/omnitools rewards open` 重试。账本在首次投递前保存完整物品快照，因此之后修改同一奖励 ID 的 NBT 不会改写已排队的物品；异常边界继续使用人工结算保护，不会自动重复投递。奖励账本状态和人工结案见[奖励一致性](../guides/reward-consistency.md)。
