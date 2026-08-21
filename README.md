# qiandao

`qiandao` 是一个面向 Minecraft Java Edition 的 Fabric 每日签到模组。玩家可以在游戏内打开签到日历，完成当天签到，查看个人统计和全服签到顺序；签到记录、连续天数、虚拟货币余额、月度里程碑奖励和可配置的称号均由服务器保存和校验。

## 快速开始

1. 在服务器与每位玩家的客户端安装相同版本的 `qiandao`、Fabric Loader 与 Fabric API。
2. 启动服务器一次，让模组生成 `config/qiandao-rewards.json`、`config/qiandao-shop.json`、`config/qiandao-titles.json` 和 `config/qiandao-title-effects.json`。
3. 按需编辑这些服务器端配置文件，并使用 `/qiandao reload` 重新加载，无须重启服务器。
4. 玩家使用 `/qiandao` 打开签到日历，使用 `/qiandao online` 领取在线时长奖励，使用 `/qiandao shop` 消费货币。

## 功能

- 称号系统：管理员可授予或回收称号；玩家可在称号界面选择佩戴。普通、稀有、传说称号分别在聊天、玩家列表和头顶显示，显示范围逐级增加。
- 称号效果：称号可关联药水效果、属性修正、移动粒子和自定义权限；玩家可以在称号界面单独开关效果，关闭效果不会隐藏称号显示。

- 5×9 签到界面：按月份显示日期，最多显示 31 个日期槽位。
- 只有服务器当前日期可以签到；过去、未来日期和空白槽位不会修改数据。
- 已签到日期显示附魔书，未签到日期显示普通书，并区分过去、今天和未来状态。
- 显示下一次签到倒计时、当天签到名次、累计签到天数、连续签到天数、本月签到天数和虚拟货币余额。
- “今日签到记录”界面按签到时间排序，支持分页，每页最多 45 条记录，并显示玩家头像、名次和签到时间。
- 玩家加入服务器后，如果当天尚未签到，会收到可点击的签到提醒。
- 首次签到播放音效并向全服广播名次；每日签到和月度里程碑可发放虚拟货币。
- 提供每日在线时长奖励与六行商店界面；商店前五行提供 45 个商品槽，商品、价格、物品组件及完整物品堆叠均由服务器配置并支持分页。
- 管理员可以清除当天签到、查询或调整余额、重新加载奖励、商店、称号和称号效果配置。
- 使用世界 `SavedData` 持久化，服务器重启或切换维度后数据仍然保留。

## 运行环境

- Minecraft Java Edition `1.21.11`
- Fabric Loader `0.19.3` 或更高版本
- Fabric API `0.141.6+1.21.11`（或与目标版本兼容的更新版本）
- Java `21` 或更高版本

这是一个双端模组。服务器和所有连接的客户端都需要安装相同版本的 `qiandao` 与兼容的 Fabric API；服务端与客户端版本不匹配时，无法保证正常连接或打开界面。

## 安装

1. 为目标实例安装 Java 21、Fabric Loader 和 Fabric API。
2. 从 `build/libs/` 获取 `qiandao-<版本>.jar`，或按照[构建](#构建)章节生成 JAR。
3. 将 JAR 同时放入服务器和客户端的 `mods/` 目录。
4. 启动服务器和客户端，并确认日志中出现 `qiandao initialized` 且没有依赖错误。

首次正常启动服务器后，模组会在服务器的 `config/qiandao-rewards.json` 创建默认奖励配置，在 `config/qiandao-shop.json` 创建默认商店配置，在 `config/qiandao-titles.json` 创建默认称号配置，并在 `config/qiandao-title-effects.json` 创建默认称号效果定义。

## 玩家使用

### 打开签到界面

以下命令都可以打开签到界面，命令必须由玩家执行：

```text
/qiandao
/qiandao open
/checkin
```

控制台不能直接打开玩家 GUI，但可以执行管理命令。

### 在线时长奖励

玩家可使用下列命令打开每日在线时长奖励界面：

```text
/qiandao online
/qiandao online rewards
/checkin online
```

在线时长以服务器实际连接时间累计，并按服务器时区在每日零点重置。奖励格会显示今日已累计分钟数；未达到档位时显示红色未附魔时钟，达到后显示绿色可领取状态，领取后时钟会附魔并向全服广播。每个档位每天只能领取一次。

### 完成签到

打开界面后，在当前月份找到今天的日期槽位并点击。签到成功后，槽位会变为附魔书，界面显示当天名次，玩家收到奖励并触发广播。重复点击不会重复签到或重复领取奖励。

日期和倒计时使用服务器 Java 进程的系统时区，客户端本地时区不会影响日期切换。跨过服务器午夜后，重新打开界面或点击旧日期即可刷新状态。

### 查看今日记录

点击签到界面底部的时钟图标打开记录界面。记录按签到时间从早到晚排列；在线玩家显示实时头像，离线玩家使用已保存的 UUID 头像。将鼠标悬停在头像上可查看玩家名称、签到名次和 `HH:mm:ss` 格式的签到时间。底部按钮可返回签到界面或切换分页。

## 奖励配置

配置文件位于服务器目录：

```text
config/qiandao-rewards.json
```

默认内容如下：

```json
{
  "dailyCoins": 100,
  "monthlyRewards": {
    "5": 500,
    "10": 1000,
    "15": 2000,
    "25": 5000
  },
  "onlineTimeRewards": [
    { "minutes": 30, "coins": 50 },
    { "minutes": 60, "coins": 100 },
    { "minutes": 120, "coins": 250 }
  ]
}
```

`dailyCoins` 是每次签到奖励；`monthlyRewards` 的键是本月累计签到天数，奖励在每个自然月中每个里程碑只领取一次。`onlineTimeRewards` 必须恰好包含三个按 `minutes` 升序排列的档位，`minutes` 为正整数，`coins` 为非负整数。所有奖励都是模组内部的数字货币，不会生成物品，也不会自动调用其他经济模组。

修改后重启服务器，或由有权限的管理员执行：

```text
/qiandao reload
```

配置解析失败时，本次运行会记录错误并使用内置默认值。读取旧版本配置时仍兼容 `dailyReward`、`daily` 和 `monthlyCoins` 字段，缺少标准字段的配置会被补写为标准格式。

## 商店配置

商店使用签到和在线时长奖励共享的货币余额。首次启动服务器时会创建
`config/qiandao-shop.json`，默认只有一个钻石商品：

```json
[
  {
    "index": 0,
    "item": "minecraft:diamond",
    "count": 1,
    "price": 20
  }
]
```

配置文件的根节点是商品数组。下方是包含第二页商品的完整示例：

```json
[
  {
    "index": 0,
    "item": "minecraft:diamond",
    "count": 1,
    "price": 20
  },
  {
    "index": 45,
    "item": "minecraft:golden_apple",
    "count": 3,
    "price": 100
  }
]
```

| 字段 | 必填 | 说明 |
| --- | --- | --- |
| `index` | 是 | 从 `0` 开始的全局商品槽位编号；`0` 至 `44` 为第一页，`45` 至 `89` 为第二页，以此类推。每个编号只能出现一次。 |
| `item` | 使用普通写法时必填 | Minecraft 物品注册 ID，例如 `minecraft:diamond`。 |
| `count` | 使用普通写法时必填 | 单次购买得到的物品数量，必须是正整数。 |
| `price` | 是 | 购买所需货币，必须是非负整数；`0` 表示免费。 |
| `components` | 否 | Minecraft 1.21.11 物品组件字符串，直接附在物品 ID 后解析。 |
| `nbt` | 否 | 完整物品堆叠的 SNBT 字符串。设置后优先使用此字段，不再读取 `item`、`count` 或 `components`。 |

未配置商品的槽位会以灰色玻璃板填充。`index` 大于 `44` 时会自动显示对应的后续页面；底栏左右两侧的箭头可在存在相邻页面时切换。

普通写法的 `components` 应使用游戏接受的组件语法。对于较长或复杂的物品数据，可使用 `nbt`
写入完整物品堆叠 SNBT（包含 `id`、`count`，可选 `components`），例如：

```json
[
  {
    "index": 0,
    "price": 500,
    "nbt": "{id:\"minecraft:diamond\",count:64}"
  }
]
```

编辑后执行 `/qiandao reload` 即可重新加载。若任意商品的 JSON、物品 ID、物品组件、SNBT、槽位或数值不合法，整个商店会暂时禁用；修正配置并重新加载后恢复。玩家使用 `/qiandao shop`、`/qiandao shop open`、`/checkin shop` 或 `/checkin shop open` 打开商店，底栏中央头颅的悬停提示会显示当前余额与当前页码。

购买时由服务器再次校验余额与商品配置。扣款成功后，商品优先放入玩家背包；背包无法容纳的剩余部分会掉落在玩家位置。

## 称号

称号独立于签到货币和奖励发放。管理员负责授予与回收，玩家只能在自己已解锁的称号中选择佩戴。所有选择和所有权由服务器保存，重新登录后仍会保留。

### 使用称号

玩家可使用以下任一命令打开“我的称号”界面：

```text
/qiandao title
/qiandao title open
/checkin title
/checkin title open
/title
/title open
```

界面每页显示最多 45 个已解锁称号。点击称号即可佩戴；再次点击当前已佩戴的称号，或点击底栏的屏障图标，即可卸下。未解锁的称号不会显示在玩家界面中。

| 稀有度 | 聊天 | 玩家列表（Tab） | 头顶名称 |
| --- | --- | --- | --- |
| `common`（普通） | 显示 | 不显示 | 不显示 |
| `rare`（稀有） | 显示 | 显示 | 不显示 |
| `legendary`（传说） | 显示 | 显示 | 显示 |

佩戴后，聊天消息会带上称号前缀；稀有和传说称号会同步到在线玩家的玩家列表，传说称号还会显示在角色头顶。

#### 佩戴效果的生效方式

称号的显示与称号效果是两个独立状态。玩家点击一个已解锁称号时，服务器会先移除旧称号的效果，再按新称号的 `effects` 数组应用效果；没有佩戴称号时不会应用任何称号效果。一个称号可以同时引用多个效果，效果 ID 会在 `qiandao-title-effects.json` 中逐一查找，找不到的 ID 只会被忽略。

底栏的染料图标是玩家自己的总开关：绿色染料表示已开启，灰色染料表示已关闭。关闭后称号仍会在聊天、Tab 和头顶正常显示，但药水、属性、粒子和权限效果会立即移除；重新开启、切换称号、重新登录、重生或管理员执行 `/qiandao reload` 后会再次刷新。这个开关会保存在 `players.<UUID>.effects_enabled`，未填写时默认为 `true`。

配置称号效果时需要同时修改两个文件：

1. 在 `qiandao-title-effects.json` 中定义效果，例如定义 `speed_1`。
2. 在 `qiandao-titles.json` 对应称号的 `effects` 数组中填写 `speed_1`。
3. 可选地在称号的 `tooltip` 数组中写入悬停说明；`tooltip` 只负责界面展示，不会创建或修改实际效果。
4. 执行 `/qiandao reload`，然后在称号界面佩戴称号并确认效果开关已开启。

### 称号配置

称号配置位于服务端：

```text
config/qiandao-titles.json
```

首次启动会生成以下默认定义；`players` 节由模组自动维护，用于保存玩家名、已解锁称号、当前佩戴项和效果开关。管理员编辑称号定义时应保留该节，避免丢失所有权记录。

```json
{
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
  ],
  "players": {}
}
```

| 字段 | 说明 |
| --- | --- |
| `id` | 唯一称号 ID。会转为小写，推荐使用 `[a-z0-9_.-]`，长度为 1 至 64 个字符。 |
| `display` | 游戏中显示的称号文本；必须有可见文字，最长 128 个字符。支持 Minecraft 传统 `§` 格式代码，也支持 `§x§R§R§G§G§B§B` 十六进制颜色。 |
| `rarity` | 显示范围，使用 `common`、`rare` 或 `legendary`。 |
| `effects` | 可选。称号效果 ID 数组；每个 ID 必须符合 `[a-z0-9_.-]`，并在 `qiandao-title-effects.json` 中定义。重复 ID 会被拒绝。 |
| `tooltip` | 可选。称号物品悬停提示的文本数组，每行最长 256 个字符；它只负责展示，不会自动从效果定义生成。 |
| `players` | 模组维护的玩家数据；不应手动覆盖或删除。每名玩家会保存 `unlocked`、`selected` 和默认值为 `true` 的 `effects_enabled`。 |

称号只有在玩家已佩戴且效果开关开启时才会应用关联的效果。效果 ID 不存在时不会阻止称号佩戴或显示，但该 ID 不会产生实际效果。

旧版称号配置可以省略 `effects`、`tooltip` 和玩家记录中的 `effects_enabled` 字段：省略时分别按空数组和 `true` 处理。升级时请保留 `players` 节；只需要给称号定义增加效果 ID，不要删除已保存的 `unlocked`、`selected` 或 `effects_enabled` 数据。

### 称号效果配置

称号效果定义位于服务端：

```text
config/qiandao-title-effects.json
```

配置根节点是“效果 ID -> 定义”的对象；也可以将这些定义包在根节点的 `effects` 对象中。首次启动会生成速度 I/II、抗性提升 I、生命上限 II、夜视、防火、红石粒子和游戏管理员权限等默认定义。称号通过其 `effects` 数组引用这些 ID。

以下示例覆盖四种支持的效果类型：

```json
{
  "speed_1": {
    "name": "速度 I",
    "type": "POTION",
    "effect": "minecraft:speed",
    "amplifier": 0,
    "duration": -1,
    "display": "§a移动速度提升 20%"
  },
  "health_2": {
    "name": "生命提升 II",
    "type": "ATTRIBUTE",
    "attribute": "minecraft:generic.max_health",
    "operation": "ADDITION",
    "amount": 4.0,
    "display": "§c♥ 生命上限 +4"
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
    "permission": "qiandao:command.gamemaster",
    "display": "§d解锁游戏管理员命令"
  }
}
```

| 类型 | 必填字段 | 可选字段与行为 |
| --- | --- | --- |
| `POTION` | `effect`：有效的药水效果 ID | `amplifier` 默认为 `0`，对应游戏内 I 级；`duration` 默认为 `-1`（永久），其他值必须为正游戏刻。 |
| `ATTRIBUTE` | `attribute`：有效的属性 ID；`amount`：有限数值 | `operation` 默认为 `ADDITION`，还可使用 `ADD_MULTIPLIED_BASE` 或 `ADD_MULTIPLIED_TOTAL`。属性修正会在卸下称号、关闭效果、断开连接或重载后移除。 |
| `PARTICLE` | `particle`：有效的粒子 ID | `frequency` 默认为 `10`，必须为正整数。玩家在地面移动时，按该间隔发出粒子。 |
| `PERMISSION` | `permission`：有效的权限 ID | 将权限加入玩家当前的原生权限集合，仅在称号效果启用且称号处于佩戴状态时有效。 |

所有类型都可使用可选的 `name` 和 `display` 字段；`display` 会显示在称号界面的效果开关提示中，未填写时使用 `name`。效果 ID 会转为小写，且必须符合 `[a-z0-9_.-]`、长度为 1 至 64 个字符。

药水效果的 `duration` 使用游戏刻：`-1` 表示永久，正数表示从佩戴或刷新时开始计时的持续时间，持续时间结束后会自然消失，并会在下一次效果刷新（例如重新佩戴、重新登录、重生或重新加载配置）时再次应用。属性修正使用模组生成的临时修正，不会写入玩家的永久属性；卸下称号、关闭开关、断开连接或刷新配置时会移除。粒子只在玩家在地面移动时按 `frequency` 间隔生成。权限效果只在称号处于佩戴且开关开启时加入玩家的原生权限检查。

在称号界面底栏点击染料图标可切换个人效果开关。关闭后称号的聊天、Tab 与头顶显示保持不变，但药水、属性、粒子和权限效果会立即移除；重新开启或更换称号后会重新应用。玩家重新登录、重生，以及管理员重载配置时也会刷新效果。

`PERMISSION` 效果可影响服务器权限，应仅授予可信玩家。`qiandao:command.moderator`、`qiandao:command.gamemaster`、`qiandao:command.admin` 和 `qiandao:command.owner` 分别映射为原生权限级别 `MODERATORS`、`GAMEMASTERS`、`ADMINS` 和 `OWNERS`；其中 `qiandao:command.gamemaster` 可以满足本模组的管理命令权限要求。其他权限 ID 可供使用 Minecraft 原生权限 API 的内容检查。

修改奖励、商店、称号或称号效果配置后，执行 `/qiandao reload` 即可重新加载四类配置；称号显示和在线玩家的效果会立刻刷新。称号配置无效时当前会话不会提供称号；称号效果配置无效时称号仍可佩戴和显示，但不会提供任何称号效果。修正配置后重新加载即可恢复。

### 称号命令

以下管理命令需要 Minecraft 权限等级 `2`，控制台也可以执行。`<玩家>` 使用 Minecraft 的玩家参数，`<称号ID>` 必须是配置中存在的 ID。

| 用途 | 命令 |
| --- | --- |
| 授予称号 | `/qiandao title give <玩家> <称号ID>` 或 `/qiandao title add <玩家> <称号ID>` |
| 回收称号 | `/qiandao title remove <玩家> <称号ID>` 或 `/qiandao title take <玩家> <称号ID>` |

`/checkin title ...` 与 `/title ...` 也支持同样的 `give`、`add`、`remove`、`take` 子命令。回收当前佩戴的称号时，模组会自动卸下它，并刷新在线玩家的显示。

## 命令

### 玩家命令

| 用途 | 命令 |
| --- | --- |
| 打开签到界面 | `/qiandao`、`/qiandao open`、`/checkin` |
| 打开在线时长奖励界面 | `/qiandao online`、`/qiandao online rewards`、`/checkin online` |
| 打开商店 | `/qiandao shop`、`/qiandao shop open`、`/checkin shop`、`/checkin shop open` |
| 打开称号界面 | `/qiandao title`、`/qiandao title open`、`/checkin title`、`/checkin title open`、`/title`、`/title open` |
| 查询自己的余额 | `/qiandao balance`、`/checkin balance`、`/qiandao currency`、`/qiandao currency balance`、`/qiandao currency get`、`/checkin currency`、`/checkin currency balance`、`/checkin currency get`、`/money`、`/money balance`、`/money get`、`/balance` |

### 管理员命令

以下命令需要 Minecraft `2` 级权限（控制台也可以执行）：

| 用途 | 主命令 | 可用别名 |
| --- | --- | --- |
| 查询指定玩家余额 | `/qiandao balance <玩家>` | `/checkin balance <玩家>`、`/qiandao currency <查询操作> <玩家>`、`/checkin currency <查询操作> <玩家>`、`/money <查询操作> <玩家>`、`/balance <玩家>` |
| 增加余额 | `/qiandao add <玩家> <数量>` | `/qiandao currency add <玩家> <数量>`、`/checkin currency add <玩家> <数量>`、`/money add <玩家> <数量>` |
| 扣除余额 | `/qiandao remove <玩家> <数量>` | `/qiandao currency <操作> <玩家> <数量>`、`/checkin currency <操作> <玩家> <数量>`、`/money <操作> <玩家> <数量>` |
| 清除今日签到 | `/qiandao clear` 或 `/qiandao clear today` | `/checkin clear`、`/checkin clear today` |
| 重新加载奖励、商店、称号和称号效果配置 | `/qiandao reload` | 无 |

`<玩家>` 使用 Minecraft 的玩家选择器参数，可以一次指定多个已知玩家。数量必须是大于 0 的整数；扣除数量不会超过目标玩家当前余额。清除操作只影响服务器当前日期的签到状态、名次和时间，并重新计算连续签到，不会回滚已经发放的货币或月度奖励。

上表中的 `<查询操作>` 可替换为 `balance` 或 `get`；扣除命令中的 `<操作>` 可替换为 `remove`、`deduct` 或 `take`。这些子命令的参数顺序均为玩家选择器后跟正整数数量。

## 数据与备份

称号定义、玩家已解锁称号、已佩戴项和个人效果开关保存在 `config/qiandao-titles.json`；称号效果定义保存在 `config/qiandao-title-effects.json`。备份或迁移时，请将这两个文件与世界数据、奖励配置和商店配置一并保留。

签到数据保存在主世界的 Minecraft `SavedData` 中，数据 ID 为 `qiandao_data`，通常对应：

```text
<世界目录>/data/qiandao_data.dat
```

数据按玩家 UUID 保存每日签到日期、名次、签到时间、累计天数、连续天数、余额、月度奖励领取记录、当日在线时长和在线奖励领取记录。所有维度共享同一份数据。迁移或升级前应先完全停止服务器，并备份整个世界目录，尤其是 `data/qiandao_data.dat`、`config/qiandao-rewards.json`、`config/qiandao-shop.json`、`config/qiandao-titles.json` 和 `config/qiandao-title-effects.json`。只备份配置文件不会包含签到历史、在线时长或余额。

服务器日期以 Java 进程系统时区为准；漏签后再次签到会从 1 天重新计算连续签到。模组不会向外部服务上传玩家数据。

## 常见问题

### 命令没有打开界面

确认命令由玩家而不是控制台执行，并检查服务器和客户端是否都安装了本模组及兼容版本的 Fabric API。

### 奖励配置没有生效

确认编辑的是服务器端 `config/qiandao-rewards.json`，JSON 格式有效且数值为非负整数，然后执行 `/qiandao reload`。查看服务器日志中的配置解析错误；出现错误时当前运行会使用默认奖励。

### 商店打开后没有商品

确认编辑的是服务器端 `config/qiandao-shop.json`，根节点为 JSON 数组，商品的 `index` 没有重复，且物品 ID、组件或 SNBT 能被 Minecraft 1.21.11 解析。任一条商品不合法都会让商店临时禁用；修正后执行 `/qiandao reload`，并查看服务器日志中的具体错误。

### 称号显示正常但效果没有生效

确认玩家已经佩戴该称号，并在称号界面开启了效果开关。然后检查 `config/qiandao-titles.json` 中称号的 `effects` ID 是否与 `config/qiandao-title-effects.json` 的定义一致。两个文件任一格式无效都会在服务器日志中记录解析错误；修正后执行 `/qiandao reload`。权限类型还应确认该权限不会授予超出预期的命令级别。

### 日期或倒计时不正确

检查服务器操作系统的日期、时间和时区。客户端时区不参与签到判断。跨过午夜后重新打开界面即可刷新。

### 重启或换图后记录消失

确认启动时使用的是原来的世界目录。若有备份，从对应世界恢复 `data/qiandao_data.dat`；仅恢复奖励配置不会恢复历史记录和余额。

## 构建

项目使用 Gradle Wrapper，无需单独安装 Gradle：

```bash
# Linux/macOS
./gradlew build

# Windows PowerShell
.\gradlew.bat build
```

产物位于 `build/libs/`：

- `qiandao-<版本>.jar`：可安装的模组 JAR
- `qiandao-<版本>-sources.jar`：源代码 JAR

版本号由 `gradle.properties` 中的 `mod_version` 定义。需要完全清理时执行 `clean build`。

提交改动前建议至少运行一次 `build`，确认 Java 21、资源 JSON 和 Fabric 元数据均能正常打包。构建不需要单独安装 Gradle；Wrapper 会使用项目声明的 Minecraft、Fabric Loader 和 Fabric API 版本。

## 项目结构

### 模块说明

| 模块 | 主要文件 | 职责 |
| --- | --- | --- |
| 初始化与命令 | `ModMindEntry` | 注册菜单、生命周期事件、签到提醒、全部指令，以及称号聊天前缀。 |
| 客户端菜单 | `ModMindClient`、各 `*Screen` | 注册并渲染签到、记录、在线奖励、商店和称号 GUI。 |
| 签到与记录 | `CheckinData`、`CheckinScreenHandler`、`CheckinRecordsScreenHandler` | 保存签到数据，提供日历、连续天数、名次和今日记录。 |
| 货币与奖励 | `CheckinRewardConfig`、`CheckinRewardService`、`OnlineTimeRewardService` | 加载每日、月度和在线时长奖励，并以服务端货币余额结算。 |
| 商店 | `ShopConfig`、`ShopScreenHandler`、`ShopScreen` | 解析商品配置、处理分页和服务端购买校验。 |
| 称号数据 | `TitleConfig`、`TitleRarity`、`LegacyTitleText` | 读取称号定义，保存玩家所有权、选择和效果开关，并解析颜色格式文本。 |
| 称号效果 | `TitleEffectConfig`、`TitleEffectService`、`ServerPlayerPermissionMixin` | 读取效果定义，应用或移除药水、属性、粒子和权限效果。 |
| 称号显示 | `TitleDisplayService`、`ServerPlayerTabListMixin`、`PlayerNameTagMixin` | 将已佩戴称号同步到聊天、Tab 列表和传说称号的头顶名称。 |
| 称号界面 | `TitleScreenHandler`、`TitleScreen` | 展示已解锁称号、效果提示、效果开关、分页、佩戴与卸下操作。 |

```text
src/main/java/dev/modmind/qiandao/
├── ModMindEntry.java                 # 初始化、提醒、命令和奖励重载
├── ModMindClient.java                # 客户端 GUI 注册
├── CheckinData.java                  # SavedData、签到统计、余额和月度记录
├── CheckinRewardConfig.java          # 奖励配置读取、校验和生成
├── CheckinRewardService.java         # 每日及月度奖励发放
├── OnlineTimeRewardService.java      # 在线时长累计与奖励领取校验
├── OnlineTimeRewardScreenHandler.java # 在线时长奖励菜单和服务端校验
├── OnlineTimeRewardScreen.java       # 在线时长奖励界面渲染
├── ShopConfig.java                   # 商店配置与完整物品堆叠 SNBT 读取
├── ShopScreenHandler.java            # 商店菜单、分页和服务端购买校验
├── ShopScreen.java                   # 商店界面渲染
├── CheckinScreenHandler.java         # 签到菜单、日期槽位和服务端校验
├── CheckinScreen.java                # 签到界面渲染
├── CheckinRecordsScreenHandler.java  # 今日记录排序、分页和头像物品
├── CheckinRecordsScreen.java         # 记录界面渲染
├── LegacyTitleText.java               # 传统颜色代码与格式文本解析
├── TitleConfig.java                   # 称号定义、玩家所有权、佩戴项和效果开关
├── TitleDisplayService.java           # 聊天、Tab 和头顶称号显示
├── TitleEffectConfig.java             # 称号效果定义读取、校验和生成
├── TitleEffectService.java            # 药水、属性、粒子和权限效果的应用与移除
├── TitleRarity.java                   # 称号稀有度与显示范围
├── TitleScreenHandler.java            # 称号菜单、分页和服务端交互校验
├── TitleScreen.java                   # 称号界面渲染
└── mixin/
    ├── ServerPlayerPermissionMixin.java # 将活动称号权限加入玩家权限集合
    ├── ServerPlayerTabListMixin.java    # 在 Tab 列表显示稀有及传说称号
    └── client/PlayerNameTagMixin.java   # 在头顶显示传说称号

src/main/resources/
├── fabric.mod.json                   # 模组元数据和依赖声明
└── assets/qiandao/lang/
    ├── zh_cn.json                    # 简体中文文本
    └── en_us.json                    # English text
```

## 许可证

本项目使用 [MIT License](LICENSE) 发布。
