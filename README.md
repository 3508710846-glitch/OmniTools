# omnitools

`omnitools` 是面向 Minecraft Java Edition Fabric 服务器的玩家服务模组，提供每日签到、在线时长奖励、虚拟货币、配置化商店、称号与称号效果、原版统计驱动的自定义成就，以及玩家独立的云端存储。

奖励发放、余额扣除、物品交易、成就判定和权限校验全部在服务端完成。客户端只负责显示服务端生成的箱子 GUI，因此不能通过修改客户端状态绕过领取或购买校验。

## 环境与安装

- Minecraft Java Edition `1.21.11`
- Fabric Loader `0.19.3` 或更高版本
- Fabric API（版本以项目 `gradle.properties` 为准）
- Java `21`
- 服务端和需要打开 GUI 的客户端安装兼容版本的模组

将构建产物 `build/libs/omnitools-<版本>.jar` 放入服务器和客户端的 `mods/` 目录。首次启动后停止服务器，再编辑配置文件；也可以编辑后使用 `/omnitools reload` 热重载。

## 配置目录与模块总览

所有管理员可编辑的定义都位于 `config/omnitools/`：

```text
config/omnitools/
├── config.json
├── daily_checkin/config.json
├── online_reward/config.json
├── shop/config.json
├── titles/config.json
├── title_effects/config.json
├── achievements/config.json
├── cloud_storage/config.json
└── legacy/
```

`legacy/` 保存迁移后的旧配置副本和 `manifest.json`。签到记录、余额、在线时长、称号拥有状态、成就状态和云存储物品不写入配置文件，而是写入世界 `SavedData`。

### 升级兼容

从旧版 `qiandao` 品牌升级时，启动迁移器会同时识别 `omnitools-*` 和 `qiandao-*` 两套根目录配置文件名，优先使用当前品牌文件；仅当目标模块文件不存在时才生成迁移文件。源文件会保留，并复制到 `legacy/`，迁移记录写入 `legacy/manifest.json`。旧的 `/checkin` 命令别名也会继续保留。

世界数据同样会从旧的 `qiandao_data`、`qiandao_titles`、`qiandao_achievements` 和 `qiandao_cloud_storage` 数据 ID 导入到当前 `omnitools_*` 数据文件；迁移不会删除旧文件。升级前仍应完整备份世界目录和 `config/omnitools/`。

## 主配置与模块开关

### 工作原理

启动或重载时，模组先读取主配置，再按模块开关加载对应文件，校验注册表对象和跨模块引用，最后一次性替换不可变配置快照。启用模块配置损坏时拒绝新快照并继续使用上一份有效配置；缺失文件会生成默认文件。禁用模块会停止其命令、GUI 点击处理、Tick、加入/断开处理和显示逻辑，但不会删除已有 SavedData。

### 如何使用

编辑 `config/omnitools/config.json` 后执行 `/omnitools reload`。`global.timezone` 使用 Java `ZoneId`，影响签到日期、在线时长跨日切分和相关时间显示，例如 `Asia/Shanghai` 或 `UTC`。`global.debug` 目前只作为全局调试标记保留。

### 玩家命令

主配置没有玩家专用命令。玩家使用的功能命令会在执行时检查相应模块是否启用；模块关闭时命令不可用或 GUI 会被关闭。

### 管理员命令

```text
/omnitools reload
```

需要 Minecraft 权限等级 `2`（`Game Master`）。成功后在线玩家的成就会重新检查，称号显示和称号效果会刷新，已关闭模块的 GUI 会被关闭；失败时保留旧快照。

### 默认配置

```json
{
  "format_version": 1,
  "global": {
    "debug": false,
    "timezone": "Asia/Shanghai"
  },
  "modules": {
    "daily_checkin": { "enabled": true },
    "online_reward": { "enabled": true },
    "shop": { "enabled": true },
    "titles": { "enabled": true },
    "title_effects": { "enabled": true },
    "achievements": { "enabled": true },
    "cloud_storage": { "enabled": true },
    "permissions": { "enabled": false }
  }
}
```

### 示例配置与字段解析

```json
{
  "format_version": 1,
  "global": {
    "debug": true,
    "timezone": "UTC"
  },
  "modules": {
    "daily_checkin": { "enabled": true },
    "online_reward": { "enabled": false },
    "shop": { "enabled": true },
    "titles": { "enabled": true },
    "title_effects": { "enabled": true },
    "achievements": { "enabled": true },
    "cloud_storage": { "enabled": false },
    "permissions": { "enabled": false }
  }
}
```

- `format_version`：正整数格式版本，当前为 `1`。
- `global.debug`：全局调试开关；不改变奖励规则。
- `global.timezone`：服务端计算“今天”和在线时长归属日的时区。
- `modules.<id>.enabled`：模块开关。模块 ID 必须使用 `daily_checkin`、`online_reward`、`shop`、`titles`、`title_effects`、`achievements`、`cloud_storage` 或 `permissions`。
- `title_effects` 依赖 `titles`；成就称号奖励需要 `titles` 启用；`permissions` 当前没有独立后端，默认关闭。

## 每日签到模块（`daily_checkin`）

### 工作原理

玩家打开签到 GUI 后，服务端按照配置时区取得当天日期。点击当天格子时，`CheckinData` 原子记录 UUID、签到日、签到时间、当天名次、累计天数和连续天数；只有首次成功签到才会发放 `dailyCoins`。签到成功后，服务端按本月已签到天数检查 `monthlyRewards`，每个里程碑通过 SavedData 的领取集合只发放一次。重复点击不会重复领取。

### 如何使用

玩家执行 `/omnitools` 或 `/omnitools open`，点击当天日期完成签到。加入服务器且当天尚未签到时会收到提醒。GUI 还可以查看当天签到记录、名次和时间。管理员清除当天记录只会移除签到状态和排名，不会回滚已经发放的货币或月度奖励；玩家再次签到前应确认这符合服务器运营规则。

### 玩家命令

```text
/omnitools
/omnitools open
/checkin
```

三个命令都会打开同一个签到 GUI。余额查询也属于共享货币功能，见文末“共享货币命令”。

### 管理员命令

```text
/omnitools balance <玩家>
/omnitools add <玩家> <数量>
/omnitools remove <玩家> <数量>
/omnitools clear
/omnitools clear today
```

这些命令需要权限等级 `2`。`add` 增加余额，`remove` 按实际可扣除数量减少余额；`clear` 和 `clear today` 等价，只清除当前配置时区的当天签到记录。

### 默认配置

文件：`config/omnitools/daily_checkin/config.json`

```json
{
  "format_version": 1,
  "dailyCoins": 100,
  "monthlyRewards": {
    "5": 500,
    "10": 1000,
    "15": 2000,
    "25": 5000
  }
}
```

### 示例配置与字段解析

```json
{
  "format_version": 1,
  "dailyCoins": 80,
  "monthlyRewards": {
    "5": 300,
    "10": 800,
    "15": 1500,
    "25": 4000
  }
}
```

- `dailyCoins`：每次成功签到发放的货币，必须是非负整数。
- `monthlyRewards`：固定里程碑对象，键为本月签到天数 `5`、`10`、`15`、`25`，值为该里程碑一次性货币奖励；缺少键时使用内置默认值。
- `format_version`：当前为 `1`，仅用于格式识别。
- 旧配置中的 `dailyReward`、`daily`、`monthlyCoins` 可被兼容读取；新配置应使用上面的字段名。

## 在线时长奖励模块（`online_reward`）

### 工作原理

玩家加入服务器时创建在线会话，服务端 Tick 定期把会话时间写入 `CheckinData`，并在跨过配置时区的午夜时拆分到不同日期。奖励只在达到配置的分钟数后显示为可领取，玩家点击 GUI 格子时服务端重新刷新时间、验证是否达标和是否已经领取，然后一次性增加货币。在线时间本身不写入配置，也不会因为 GUI 关闭而丢失。

### 如何使用

在 `config/omnitools/online_reward/config.json` 中按分钟递增配置奖励。玩家使用 `/omnitools online` 打开 GUI，达到条件后手动点击对应奖励。服务器停止、玩家断开或定期刷新时都会把当前会话落盘。奖励数组可以增删或重排，但已发布的 `id` 不要复用，否则历史领取记录可能指向新的奖励。

### 玩家命令

```text
/omnitools online
/omnitools online rewards
/checkin online
/checkin online rewards
```

命令只负责打开 GUI；领取动作在 GUI 内完成。`online_reward` 关闭时这些命令不可用，已保存的余额和历史领取记录仍保留。

### 管理员命令

没有独立的在线奖励编辑命令。修改配置后执行：

```text
/omnitools reload
```

若重载把在线奖励关闭，服务端会先保存在线会话并清理计时；重新启用后玩家重新加入或下一次 Tick 会继续累计。

### 默认配置

文件：`config/omnitools/online_reward/config.json`

```json
{
  "format_version": 1,
  "rewards": [
    { "id": "online_30m", "minutes": 30, "coins": 50 },
    { "id": "online_60m", "minutes": 60, "coins": 100 },
    { "id": "online_120m", "minutes": 120, "coins": 250 }
  ]
}
```

### 示例配置与字段解析

```json
{
  "format_version": 1,
  "rewards": [
    { "id": "online_15m", "minutes": 15, "coins": 20 },
    { "id": "online_45m", "minutes": 45, "coins": 80 },
    { "id": "online_180m", "minutes": 180, "coins": 500 }
  ]
}
```

- `id`：奖励稳定标识，必须唯一，匹配 `[a-z0-9_.-]{1,64}`；发布后不要复用。
- `minutes`：达到奖励所需的当天在线分钟数，必须为正整数，且数组严格递增。
- `coins`：领取时增加的货币，必须为非负整数。
- `format_version`：当前为 `1`。

领取记录新格式是 `日期纪元日:奖励ID`。旧版本的 `日期:槽位` 记录会按旧数组顺序兼容读取并转换为稳定 ID；因此调整数组顺序时必须保持每个奖励的 ID 不变。

## 商店模块（`shop`）

### 工作原理

商店配置在服务端注册表可用后解析为完整 `ItemStack`。GUI 每页使用 45 个商品槽位，剩余一行放置翻页、余额和页码按钮。玩家点击商品时，服务端重新读取商品和余额，只有成功扣除完整价格后才把物品放入背包；背包无法容纳时按 Minecraft 常规规则掉落。客户端不能修改价格或商品内容。

### 如何使用

编辑 `config/omnitools/shop/config.json`，每个商品通过 `index` 指定全局槽位；`index` 为 `0` 到 `44` 时显示在第一页，`45` 到 `89` 时显示在第二页，以此类推。配置可以是推荐的对象格式，也兼容旧版根数组格式。普通商品使用 `item`、`count` 和可选 `components`，需要完整物品堆时使用 `nbt`；提供 `nbt` 时服务端优先按完整物品堆解析，建议不要同时填写普通格式字段。

### 玩家命令

```text
/omnitools shop
/omnitools shop open
/checkin shop
/checkin shop open
```

打开后点击商品即可购买；价格、余额和物品数量会显示在提示信息中。

### 管理员命令

没有独立的商品编辑命令。修改配置后使用：

```text
/omnitools reload
```

商品配置解析失败时不会清空正在运行的商店，而是保留上一份有效快照。

### 默认配置

文件：`config/omnitools/shop/config.json`

```json
{
  "format_version": 1,
  "products": [
    {
      "index": 0,
      "item": "minecraft:diamond",
      "count": 1,
      "price": 20
    }
  ]
}
```

### 示例配置与字段解析

```json
{
  "format_version": 1,
  "products": [
    {
      "index": 0,
      "item": "minecraft:diamond",
      "count": 1,
      "price": 20
    },
    {
      "index": 1,
      "item": "minecraft:golden_apple",
      "count": 2,
      "price": 250,
      "components": "[minecraft:custom_name='{\"text\":\"高级苹果\"}']"
    },
    {
      "index": 45,
      "nbt": "{id:\"minecraft:netherite_sword\",count:1,components:{\"minecraft:unbreakable\":{}}}",
      "price": 1000
    }
  ]
}
```

- `index`：商品的全局槽位，必须为非负整数且不可重复；每 45 个槽位组成一页。
- `item`：带命名空间的物品 ID，例如 `minecraft:diamond`。
- `count`：普通格式的堆叠数量，必须为正整数。
- `price`：购买价格，必须为非负整数。
- `components`：Minecraft 1.21.11 物品组件命令语法字符串，必须能被服务端 `ItemParser` 解析。
- `nbt`：完整物品堆 SNBT，包含 `id`、`count` 和可选组件；提供该字段时会优先使用它，建议不要再写 `item`/`count`，避免配置含义混淆。
- `format_version`：当前为 `1`。复杂组件或 SNBT 语法错误会拒绝整份新配置快照。

## 称号模块（`titles`）

### 工作原理

`titles/config.json` 只保存管理员定义的称号；玩家拥有的称号、当前佩戴称号和效果开关保存到世界 `SavedData`。称号显示服务根据稀有度把文本注入聊天、Tab 列表和头顶名称：普通称号显示在聊天，稀有称号显示在聊天和 Tab，传说称号三处都显示。配置重载不会覆盖玩家状态。

### 如何使用

管理员先在配置中定义称号，再使用管理员命令授予玩家。玩家执行 `/omnitools title` 打开 GUI，在已解锁列表中点击称号进行佩戴或卸下，并可单独切换称号效果。旧版 `omnitools-titles.json` 的 `players` 数据会在首次启动时导入 `TitleData`，定义和状态之后完全分离。

### 玩家命令

```text
/omnitools title
/omnitools title open
/checkin title
/checkin title open
/title
/title open
```

### 管理员命令

```text
/omnitools title give <玩家> <称号ID>
/omnitools title add <玩家> <称号ID>
/omnitools title remove <玩家> <称号ID>
/omnitools title take <玩家> <称号ID>
```

均需要权限等级 `2`。`give`/`add` 授予称号但不会自动佩戴；`remove`/`take` 回收称号，若玩家正在佩戴则同时卸下。修改称号定义后使用 `/omnitools reload`。

### 默认配置

文件：`config/omnitools/titles/config.json`

```json
{
  "format_version": 1,
  "titles": [
    {
      "id": "geologist",
      "display": "§7[§r地质学家§7] §r",
      "rarity": "common",
      "effects": ["health_2"],
      "tooltip": ["§7佩戴效果：", "§c♥ 生命上限 +4"]
    },
    {
      "id": "architect",
      "display": "§b[§r建筑师§b] §r",
      "rarity": "rare",
      "effects": ["speed_1"],
      "tooltip": ["§7佩戴效果：", "§a✔ 移动速度提升"]
    },
    {
      "id": "legend",
      "display": "§6[§r传说§6] §r",
      "rarity": "legendary",
      "effects": ["resistance_1", "night_vision"],
      "tooltip": ["§7佩戴效果：", "§a✔ 抗性提升 I", "§a✔ 永久夜视"]
    }
  ]
}
```

### 示例配置与字段解析

```json
{
  "format_version": 1,
  "titles": [
    {
      "id": "explorer",
      "display": "§2[§r探险家§2] §r",
      "rarity": "rare",
      "effects": ["night_vision"],
      "tooltip": ["§7探索黑暗区域时更加方便"]
    }
  ]
}
```

- `id`：称号稳定 ID，建议使用小写字母、数字、下划线、点或连字符。
- `display`：聊天、Tab 或头顶显示文本，支持传统 `§` 颜色代码。
- `rarity`：`common`、`rare` 或 `legendary`，决定显示范围。
- `effects`：引用 `title_effects/config.json` 的效果 ID；引用不存在时重载失败并保留旧快照。
- `tooltip`：称号 GUI 的提示文本数组。
- 玩家状态文件：`<世界>/data/omnitools_titles.dat`，保存拥有、佩戴和 `effects_enabled`，不应手工编辑。

## 称号效果模块（`title_effects`）

### 工作原理

效果定义使用效果 ID 作为 JSON 根对象键。玩家佩戴称号、加入服务器、重生或配置重载时，服务端根据当前称号引用重新应用药水、属性、粒子和受限权限效果；卸下称号、关闭开关、断开连接或模块关闭时移除由模组添加的效果。权限效果通过白名单校验，不会直接提升 Minecraft 管理员等级。

### 如何使用

在 `config/omnitools/title_effects/config.json` 定义效果，再在称号的 `effects` 数组中引用。玩家在称号 GUI 底部切换“称号效果”开关；称号仍会显示，但关闭后不再应用效果。`title_effects` 依赖 `titles`，不能在没有称号模块时单独启用。

### 玩家命令

没有独立的称号效果命令。玩家通过以下命令打开称号 GUI，再点击效果开关：

```text
/omnitools title
/title
```

### 管理员命令

```text
/omnitools reload
```

需要权限等级 `2`。重载时会刷新在线玩家效果；如果关闭 `title_effects`，会先移除在线玩家的称号效果，但保留称号文字显示。

### 默认配置

文件：`config/omnitools/title_effects/config.json`

```json
{
  "format_version": 1,
  "speed_1": {
    "name": "速度 I",
    "type": "POTION",
    "effect": "minecraft:speed",
    "amplifier": 0,
    "duration": -1,
    "display": "§a移动速度提升 20%"
  },
  "speed_2": {
    "name": "速度 II",
    "type": "POTION",
    "effect": "minecraft:speed",
    "amplifier": 1,
    "duration": -1,
    "display": "§a移动速度提升 40%"
  },
  "resistance_1": {
    "name": "抗性提升 I",
    "type": "POTION",
    "effect": "minecraft:resistance",
    "amplifier": 0,
    "duration": -1,
    "display": "§a抗性提升 I"
  },
  "health_2": {
    "name": "生命提升 II",
    "type": "ATTRIBUTE",
    "attribute": "minecraft:generic.max_health",
    "operation": "ADDITION",
    "amount": 4.0,
    "display": "§c♥ 生命上限 +4"
  },
  "night_vision": {
    "name": "夜视",
    "type": "POTION",
    "effect": "minecraft:night_vision",
    "amplifier": 0,
    "duration": -1,
    "display": "§f永久夜视"
  },
  "fire_resistance": {
    "name": "防火",
    "type": "POTION",
    "effect": "minecraft:fire_resistance",
    "amplifier": 0,
    "duration": -1,
    "display": "§6免疫火焰伤害"
  },
  "particle_redstone": {
    "name": "红石粒子",
    "type": "PARTICLE",
    "particle": "minecraft:redstone",
    "frequency": 10,
    "display": "§c行走时飘落红石粒子"
  },
  "command_gamemaster": {
    "name": "游戏管理员命令权限",
    "type": "PERMISSION",
    "permission": "omnitools:command.gamemaster",
    "display": "§d解锁游戏管理员命令"
  }
}
```

### 示例配置与字段解析

```json
{
  "format_version": 1,
  "jump_boost": {
    "name": "跳跃提升 I",
    "type": "POTION",
    "effect": "minecraft:jump_boost",
    "amplifier": 0,
    "duration": -1,
    "display": "§b跳跃提升 I"
  },
  "sparkle": {
    "name": "闪烁粒子",
    "type": "PARTICLE",
    "particle": "minecraft:enchant",
    "frequency": 5,
    "display": "§d周围出现附魔粒子"
  }
}
```

- `type`：`POTION`、`ATTRIBUTE`、`PARTICLE` 或 `PERMISSION`。
- 药水效果使用 `effect`、`amplifier` 和 `duration`；`duration: -1` 表示无限时长，`amplifier` 从 `0` 开始。
- 属性效果使用 `attribute`、`operation` 和 `amount`；操作支持 `ADDITION`、`ADD_MULTIPLIED_BASE`、`ADD_MULTIPLIED_TOTAL`。
- 粒子效果使用 `particle` 和正整数 `frequency`，表示每多少 Tick 生成一次。
- 权限效果使用 `permission`。只允许 `omnitools:cloud_storage` 或 `omnitools:command.*`，其他节点会被配置校验拒绝。
- `name` 和 `display` 用于 GUI 提示；效果 ID就是称号配置中引用的键。

## 自定义成就模块（`achievements`）

### 工作原理

模组不重复记录挖掘和击杀次数，直接读取 Minecraft 原版统计：

```java
player.getStats().getValue(Stats.BLOCK_MINED.get(block));
player.getStats().getValue(Stats.ENTITY_KILLED.get(entityType));
```

支持 `block_mined` 和 `entity_killed`。方块、生物和图标在配置加载时解析并缓存，服务端每 `10` Tick 检查一次，玩家加入时也检查一次。所有 requirement 必须同时满足（AND）；达成后把成就 ID 写入 `AchievementData`，解锁永久有效，即使原版统计后来被重置也不会回退。奖励需要在 GUI 中手动领取一次，领取状态单独持久化。

### 如何使用

管理员编辑成就定义并执行 `/omnitools reload`。玩家执行成就命令查看分页 GUI：0-44 格为成就，45 为上一页，49 为玩家头像/完成数量/页码，53 为下一页。进行中显示当前值/目标值；已达成未领取显示绿色；已领取显示金色和附魔光效。点击时服务端会重新验证成就 ID、完成状态和领取状态。

### 玩家命令

```text
/omnitools achievements
/omnitools achievements open
/checkin achievements
/checkin achievements open
```

### 管理员命令

没有独立的授予、清除或伪造成就命令。修改定义后使用：

```text
/omnitools reload
```

称号奖励只有在 `titles` 模块启用且引用的称号存在时才会发放；成就解锁和领取状态文件是独立的。

### 默认配置

文件：`config/omnitools/achievements/config.json`

```json
{
  "format_version": 1,
  "achievements": [
    {
      "id": "stone_breaker",
      "display": "石匠",
      "description": "挖掘石头 1000 个",
      "icon": "minecraft:stone",
      "requirements": [
        {
          "type": "block_mined",
          "target": "minecraft:stone",
          "count": 1000
        }
      ],
      "rewards": {
        "coins": 500,
        "titles": ["geologist"]
      }
    }
  ]
}
```

### 示例配置与字段解析

```json
{
  "format_version": 1,
  "achievements": [
    {
      "id": "zombie_hunter",
      "display": "僵尸猎人",
      "description": "击杀僵尸 100 个",
      "icon": "minecraft:rotten_flesh",
      "requirements": [
        {
          "type": "entity_killed",
          "target": "minecraft:zombie",
          "count": 100
        }
      ],
      "rewards": {
        "coins": 300,
        "titles": []
      }
    }
  ]
}
```

- `id`：唯一成就 ID，匹配 `[a-z0-9_.-]{1,64}`。
- `display`：GUI 中的名称，不能为空且长度有限制。
- `description`：GUI 中的说明文本。
- `icon`：物品 ID，用作 GUI 图标，不能是不存在的物品或 `minecraft:air`。
- `requirements`：至少一个目标对象；`type` 只能是 `block_mined` 或 `entity_killed`，`target` 必须是有效方块/生物 ID，`count` 必须为正整数。
- `rewards.coins`：领取时增加的货币，可省略，默认 `0`。
- `rewards.titles`：领取时授予的称号 ID 数组，可省略；称号模块启用时必须引用已定义称号。
- 当前值始终从原版统计读取，不会把进度写回配置文件。
- 状态文件：`<世界>/data/omnitools_achievements.dat`，保存解锁和已领取 ID。

## 云端存储模块（`cloud_storage`）

### 工作原理

云存储是每个玩家独立的服务端物品仓库。每页 GUI 为 `6 x 9`，前五行共 45 个存储槽，最后一行是上一页、余额、状态、扩展和下一页按钮。玩家首次使用时拥有 1 页；点击扩展按钮时服务端检查权限、余额和页数上限，再原子扣除货币并解锁下一页，任一步失败都不会丢失货币。物品和已解锁页保存到 `CloudStorageData`。

### 如何使用

玩家必须拥有 `omnitools:cloud_storage` 权限，或拥有 Minecraft 权限等级 `2` 及以上。打开 GUI 后可像普通箱子一样放入和取出物品；快捷移动也受到服务端校验。达到余额要求后点击绿宝石扩展按钮购买下一页，页数上限由配置决定。当前实现的硬上限是 2 页，每页 45 格。

### 玩家命令

```text
/omnitools storage
/omnitools storage open
/checkin storage
/checkin storage open
/cloudstorage
/cloudstorage open
/cstorage
/cstorage open
```

没有权限或模块关闭时命令不会打开界面；管理员默认绕过权限节点。

### 管理员命令

没有独立的强制扩展、清空或转移物品命令。管理员可以直接使用 GUI，也可以修改配置后执行：

```text
/omnitools reload
```

降低 `maxPages` 不会删除 SavedData 中已有物品，但 GUI 只允许访问当前配置允许的页数；操作前请备份世界数据。

### 默认配置

文件：`config/omnitools/cloud_storage/config.json`

```json
{
  "format_version": 1,
  "expansionCost": 100,
  "maxPages": 2
}
```

### 示例配置与字段解析

```json
{
  "format_version": 1,
  "expansionCost": 250,
  "maxPages": 2
}
```

- `expansionCost`：每次解锁下一页所需货币，必须为非负整数。
- `maxPages`：玩家最多可解锁的页数，当前只能是 `1` 或 `2`；每个玩家初始为 `1` 页。
- `format_version`：当前为 `1`。
- 状态文件：`<世界>/data/omnitools_cloud_storage.dat`，保存每个 UUID 的页数和物品堆。
- 权限节点：`omnitools:cloud_storage`。称号权限效果也只能授予这一节点或 `omnitools:command.*`。

## 权限预留模块（`permissions`）

### 工作原理

`permissions` 目前只有主配置中的预留开关，没有独立权限数据服务、权限配置文件或权限管理命令。当前实际权限来自 Minecraft 原生权限等级、云存储权限节点，以及称号效果的白名单权限注入。

### 如何使用

不要把该模块当作完整权限插件使用。普通权限后端需要自行授予 `omnitools:cloud_storage`；管理员等级 `2` 及以上自动拥有云存储访问权。称号效果配置中的 `PERMISSION` 节点会在重载时校验白名单。

### 玩家命令

没有权限管理命令。玩家只需使用各功能模块的命令；最终权限始终由服务端再次判断。

### 管理员命令

当前没有独立命令。模块开关可通过以下命令重载：

```text
/omnitools reload
```

该命令本身需要权限等级 `2`。

### 默认配置

主配置中的默认值为：

```json
{
  "permissions": { "enabled": false }
}
```

### 示例配置与字段解析

```json
{
  "format_version": 1,
  "global": {
    "debug": false,
    "timezone": "Asia/Shanghai"
  },
  "modules": {
    "permissions": { "enabled": false }
  }
}
```

启用该开关不会凭空创建权限后端；请保持关闭，除非未来版本增加独立实现。配置文件禁止授予任意管理员级别权限。

## 共享货币命令

所有模块共用 `CheckinData` 中的余额，关闭 `daily_checkin` 不会删除余额，因为商店、在线奖励、成就和云存储也会使用它。

玩家可查询自己的余额：

```text
/omnitools balance
/omnitools currency
/omnitools currency balance
/omnitools currency get
/money
/money balance
/money get
/balance
```

管理员（权限等级 `2`）可查询或修改指定玩家：

```text
/omnitools balance <玩家>
/balance <玩家>
/omnitools add <玩家> <数量>
/omnitools remove <玩家> <数量>
/omnitools currency add <玩家> <数量>
/omnitools currency remove <玩家> <数量>
/omnitools currency deduct <玩家> <数量>
/omnitools currency take <玩家> <数量>
/money add <玩家> <数量>
/money remove <玩家> <数量>
```

所有数量参数必须为正整数；货币余额为非负值，扣除数量超过余额时只扣除现有余额。

## 持久化、备份与迁移

玩家数据由世界 `SavedData` 保存：

```text
<世界>/data/omnitools_data.dat                 # 签到、余额、月度领取、在线时长
<世界>/data/omnitools_titles.dat               # 称号拥有、佩戴和效果开关
<世界>/data/omnitools_achievements.dat         # 成就解锁和领取状态
<世界>/data/omnitools_cloud_storage.dat        # 云存储物品和页数
```

备份或迁移服务器前应停止服务端，同时备份整个世界目录和 `config/omnitools/`。只备份 JSON 配置不能恢复余额、领取记录或云存储物品。

首次加载时会把旧根目录文件迁移到新目录，仅在目标文件不存在时执行，不删除源文件：

| 旧文件 | 新文件 |
| --- | --- |
| `config/omnitools-rewards.json` | `daily_checkin/config.json` 与 `online_reward/config.json` |
| `config/omnitools-shop.json` | `shop/config.json` |
| `config/omnitools-titles.json` | `titles/config.json`，玩家状态导入 `omnitools_titles.dat` |
| `config/omnitools-title-effects.json` | `title_effects/config.json` |
| `config/omnitools-achievements.json` | `achievements/config.json` |
| `config/omnitools-cloud-storage.json` | `cloud_storage/config.json` |

迁移成功后源文件会复制到 `config/omnitools/legacy/`，并追加 `legacy/manifest.json`。迁移失败会保留旧文件，修复后可再次启动或重载。

## 重载与故障排查

重载流程为：迁移旧配置、读取主配置、在服务端注册表可用后读取模块文件、校验方块/生物/物品/称号/效果/权限引用、构建快照并一次性替换。重载失败时旧快照继续工作，不会把其他模块清空。

- GUI 无法打开：确认服务端和客户端模组版本一致、模块已启用；云存储还要检查 `omnitools:cloud_storage` 权限。
- 配置不生效：确认 JSON 为 UTF-8、字段类型正确、ID 唯一，然后执行 `/omnitools reload`。
- 商店加载失败：检查 `index` 是否重复，物品 ID、组件语法或完整 SNBT 是否有效。
- 成就目标无效：检查 `minecraft:` 方块/生物 ID，模组只读取原版统计，不维护第二份计数。
- 在线奖励错位：不要复用或随意更改已发布的奖励 ID。
- 称号效果不生效：确认 `title_effects` 和 `titles` 都启用，且称号引用的效果 ID 存在。
- 重启后数据缺失：确认使用的是原来的世界目录，并恢复对应的 `omnitools_*.dat` 文件。

## 构建与验证

Windows PowerShell：

```powershell
.\gradlew.bat compileJava
.\gradlew.bat build
```

Linux/macOS：

```bash
./gradlew compileJava
./gradlew build
```

产物位于 `build/libs/`。发布前应同时验证配置内容、服务端启动、GUI 权限、重复领取、重载失败回滚、服务器重启后的 SavedData 持久化。

## 许可证

本项目使用 [MIT License](LICENSE) 发布。
