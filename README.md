# omnitools

## 成就系统（`achievements`）

成就系统 v2 使用服务端条件树描述解锁条件。模组直接读取 Minecraft 原版统计，不在 `SavedData` 中维护第二份统计计数；配置在启动或 `/omnitools reload` 时解析为不可变模型。运行时只读取已经解析的注册对象，配置错误不会替换当前有效快照。

本模块先说明工作原理、统计适配和目标解析，再按“如何使用 → 玩家命令 → 管理员命令 → 默认配置 → 示例配置与字段解析”说明实际操作；旧配置迁移、持久化、GUI 和性能限制放在后续小节。成就模块由主配置中的 `modules.achievements.enabled` 控制，关闭后不执行周期检查、不解锁成就、不发放奖励，但不会删除已有的世界数据。

### 模块快速导航

| 小节 | 你可以在这里找到 |
| --- | --- |
| 工作原理 | 条件树、统计读取、解锁和进度计算流程。 |
| 如何使用 | 配置文件位置、重载流程和一份可直接改写的 v2 配置。 |
| 玩家命令 | 打开成就菜单、查看进度和领取奖励。 |
| 管理员命令 | 重载配置、权限要求和失败回退。 |
| 默认配置 | 首次启动生成的最小可用配置。 |
| 示例配置与字段解析 | `sum`、`each`、`any`、逻辑嵌套、单位和字段约束。 |

### 工作原理

一次玩家检查的流程是：

1. 读取当前有效的成就配置快照；配置文件不会在检查期间被重新读取。
2. 为该玩家创建一个 `StatisticEvaluationContext`，按“统计类型 + 目标对象”缓存原版统计值。
3. 从根节点向下求值，得到统一的 `completed`、`current`、`target` 结果。
4. 条件满足且成就尚未解锁时，写入世界 `SavedData`；已解锁成就跳过后续周期求值。
5. 领奖和 GUI 刷新继续使用同一棵条件树，服务端重新校验，客户端不参与判定。

因此，成就系统不复制 Minecraft 统计，也不会因为原版统计后来被重置而撤销已经写入的解锁状态。

条件节点的语义如下：

| 节点 | 作用 |
| --- | --- |
| `stat` | 读取一个原版统计源和一组目标。叶子条件达到 `at_least` 后完成。 |
| `sum` | 把多个统计源的当前值相加后与一个阈值比较。只允许相同单位类别。 |
| `all` | 所有子条件都完成才完成。 |
| `any` | 任一子条件完成即完成。 |
| `not` | 子条件未完成时为 `true`，子条件完成后为 `false`。可以嵌套在 `all` 或 `any` 中。 |

`stat` 的 `match` 决定多个目标之间的关系：`sum` 为目标合计，`each` 为每个目标分别达标，`any` 为任一目标达标。所有节点都输出 `completed`、`current` 和 `target`，GUI 只展示服务端生成的结果。顶层成就不能只有 `not`，必须包含至少一个正向统计条件。

#### 支持的原版统计

| `stat` | 目标注册表 | 示例 |
| --- | --- | --- |
| `block_mined` | 方块 | 挖掘石头、深板岩 |
| `item_crafted` | 物品 | 合成石头 100 个 |
| `item_used` | 物品 | 使用钻石剑 100 次 |
| `item_broken` | 物品 | 损坏工具 |
| `item_picked_up` | 物品 | 拾取物品 |
| `item_dropped` | 物品 | 丢弃物品 |
| `entity_killed` | 实体类型 | 击杀凋灵或末影龙 |
| `entity_killed_by` | 实体类型 | 被指定实体击杀 |
| `custom` | `Stats.CUSTOM` | 移动、游戏时长、伤害、死亡 |

`custom` 必须提供 `custom_stat`，例如 `minecraft:walk_one_cm`、`minecraft:play_time`、`minecraft:damage_dealt`、`minecraft:damage_taken`、`minecraft:deaths`。物品、方块和实体目标只接受显式注册 ID、标签、目标组或通配符展开后的对象；不会把任意字符串当作统计目标。

常用 `custom_stat` 可以按单位类别理解：`walk_one_cm`、`sprint_one_cm`、`swim_one_cm` 和 `fly_one_cm` 属于距离；`play_time` 和 `total_world_time` 属于时长；`damage_dealt`、`damage_taken` 和 `damage_blocked_by_shield` 属于伤害；`deaths`、`mob_kills`、`player_kills`、`jump`、`animals_bred`、`fish_caught`、`traded_with_villager` 和 `raid_win` 按次数计算。具体统计 ID 必须能在当前服务端的 `Stats.CUSTOM` 注册表中找到。

#### 目标解析与单位

目标写法如下：

| 写法 | 含义 |
| --- | --- |
| `minecraft:stone` | 显式注册对象 ID。 |
| `$stone_family` | 当前文件 `target_groups` 中定义的目标组。 |
| `#minecraft:logs` | 当前统计域对应的 Minecraft 标签。 |
| `*` | 当前统计类型注册表中的全部目标；不能与其他目标混用。 |

目标组、标签和 `*` 只在配置加载或重载时展开、去重和缓存。未知 ID、未知标签、空标签、目标组循环、`*` 混用都会拒绝新配置。每个条件最多 2048 个展开目标，每个成就最多 128 个统计叶子，条件树最多 8 层。

原版统计值内部保持原始单位，配置加载时把阈值换算为原始单位：

| 数据类别 | 可用 `unit` | 说明 |
| --- | --- | --- |
| 方块、物品、实体、跳跃、击杀 | `count`（默认） | 直接按次数比较。 |
| 距离 | `cm`、`meters`、`blocks`、`kilometers` | 例如 `10 kilometers` 转换为厘米阈值。 |
| 伤害 | `damage`、`hearts` | `hearts` 按 Minecraft 伤害单位换算。 |
| 时长 | `ticks`、`seconds`、`minutes`、`hours` | 例如 `play_time` 以 tick 为原始值。 |

`sum` 只能合计同一单位类别；数量不能与距离、伤害或时长相加。配置校验阶段发现单位不兼容时，重载会失败并保留旧快照。

### 如何使用

编辑 `config/omnitools/achievements/config.json`，保存为 UTF-8 后执行 `/omnitools reload`。推荐按以下步骤操作：

1. 先复制并备份当前配置，尤其是已经在线上使用的成就 ID。
2. 在 `achievements` 数组中新增或修改成就，确保每个 ID 唯一且至少有一个正向统计条件。
3. 在测试世界执行 `/omnitools reload`，确认服务器提示加载成功后再发布到正式世界。

一个完整的 v2 配置如下：

```json
{
  "format_version": 2,
  "target_groups": {
    "stone_family": ["minecraft:stone", "minecraft:deepslate"],
    "bosses": ["minecraft:wither", "minecraft:ender_dragon"]
  },
  "achievements": [
    {
      "id": "stone_worker",
      "display": "石匠",
      "description": "挖掘石头系方块累计 1000 个",
      "icon": "minecraft:stone",
      "requirements": {
        "type": "stat",
        "stat": "block_mined",
        "targets": ["$stone_family"],
        "match": "sum",
        "at_least": 1000
      },
      "rewards": {"coins": 500, "titles": []}
    }
  ]
}
```

`requirements` 在 v2 中是一个条件对象；旧版数组仍会自动转换，见“旧配置兼容”。`id` 必须唯一，`display`、`description` 不能为空，`icon` 必须是有效物品。奖励中的 `coins` 为领取时增加的货币，`titles` 为领取时授予的称号 ID。

配置修改只有在整份文件校验成功后才会生效；不要直接编辑世界 `SavedData` 或把玩家统计复制到成就文件中。服务器首次启动会自动创建默认文件，首次使用前应先确认该文件位于当前服务器的 `config/omnitools/achievements/` 目录。

### 示例配置与字段解析

石头和深板岩分别达到 1000：

```json
{
  "type": "stat",
  "stat": "block_mined",
  "targets": ["$stone_family"],
  "match": "each",
  "at_least": 1000
}
```

石头和深板岩任意一个达到 1000：把 `match` 改为 `any`。挖掘或使用石头系累计 1000：

```json
{
  "type": "sum",
  "at_least": 1000,
  "sources": [
    {"stat": "block_mined", "targets": ["$stone_family"]},
    {"stat": "item_used", "targets": ["$stone_family"]}
  ]
}
```

组合条件可以任意嵌套：

```json
{
  "type": "all",
  "children": [
    {
      "type": "stat",
      "stat": "block_mined",
      "targets": ["$stone_family"],
      "match": "sum",
      "at_least": 1000
    },
    {
      "type": "any",
      "children": [
        {"type": "stat", "stat": "entity_killed", "targets": ["minecraft:wither"], "at_least": 1},
        {"type": "stat", "stat": "entity_killed", "targets": ["minecraft:ender_dragon"], "at_least": 1}
      ]
    }
  ]
}
```

`not` 表示“子条件尚未完成”。它可以嵌套在 `all` 或 `any` 中，但不能单独作为顶层成就：

```json
{
  "type": "all",
  "children": [
    {
      "type": "stat",
      "stat": "block_mined",
      "targets": ["minecraft:stone"],
      "at_least": 1000
    },
    {
      "type": "not",
      "child": {
        "type": "stat",
        "stat": "custom",
        "custom_stat": "minecraft:deaths",
        "at_least": 1
      }
    }
  ]
}
```

上例要求玩家累计挖掘 1000 个石头，并且累计死亡统计仍为 0；它不能表达“从接取任务后从未死亡”，因为原版统计是累计值。

移动 10 公里或造成 10000 点伤害的叶子条件分别可以写成：

```json
{"type": "stat", "stat": "custom", "custom_stat": "minecraft:walk_one_cm", "unit": "kilometers", "at_least": 10}
```

```json
{"type": "stat", "stat": "custom", "custom_stat": "minecraft:damage_dealt", "unit": "damage", "at_least": 10000}
```

### 玩家命令

```text
/omnitools achievements
/omnitools achievements open
/checkin achievements
/checkin achievements open
```

玩家在成就界面查看条件树进度，并领取已经完成但尚未领取的奖励。服务端会在打开界面和点击领取时重新验证条件、解锁状态和领取状态；客户端不能修改统计或绕过校验。

### 管理员命令

```text
/omnitools reload
```

默认需要 `config.reload` 权限动作（Minecraft 权限等级 2）。没有单独的授予、清除或伪造成就命令；管理员通过编辑配置并重载来维护成就定义。称号奖励只有在 `titles` 模块启用且称号存在时才会发放。

### 默认配置

首次启动生成 `config/omnitools/achievements/config.json`，格式版本为 `2`。默认成就挖掘 `minecraft:stone` 1000 个，奖励 500 货币；`geologist` 称号仅在称号模块启用且已定义时发放。

```json
{
  "format_version": 2,
  "achievements": [
    {
      "id": "stone_breaker",
      "display": "石匠",
      "description": "挖掘石头 1000 个",
      "icon": "minecraft:stone",
      "requirements": {
        "type": "stat",
        "stat": "block_mined",
        "targets": ["minecraft:stone"],
        "match": "sum",
        "at_least": 1000
      },
      "rewards": {"coins": 500, "titles": ["geologist"]}
    }
  ]
}
```

### 旧配置兼容

`format_version: 1` 的旧配置无需修改：

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
        {"type": "block_mined", "target": "minecraft:stone", "count": 1000}
      ],
      "rewards": {"coins": 500, "titles": []}
    }
  ]
}
```

加载时会把每个旧 requirement 转换为 `stat` 叶子，再放入 `all` 节点。不会自动覆盖用户原文件；新配置校验失败时，上一份有效配置、解锁状态和领奖判定继续使用，保证旧版“挖掘石头 1000 个”的行为不变。旧数组中的 `type`、`target`、`count` 分别映射为 `stat`、单项 `targets` 和 `at_least`。

v2 的规范写法是把条件对象直接放在 `requirements` 下，不再额外包一层数组：

```json
"requirements": {
  "type": "stat",
  "stat": "custom",
  "custom_stat": "minecraft:walk_one_cm",
  "unit": "kilometers",
  "at_least": 10
}
```

为兼容实际升级过程中常见的混合写法，当前加载器也接受“数组中的每一项都是 v2 条件节点”，并将该数组按隐式 `all` 处理。因此下面这种写法也等价于上面的单个条件：

```json
"requirements": [
  {
    "type": "stat",
    "stat": "custom",
    "custom_stat": "minecraft:walk_one_cm",
    "unit": "kilometers",
    "at_least": 10
  }
]
```

同一个数组不能混用 v1 的 `{ "type": "block_mined", "target": ..., "count": ... }` 和 v2 的 `{ "type": "stat", ... }` 条目；混用会拒绝新快照并继续使用上一份有效配置。

如果日志出现 `Unknown achievement requirement type: stat`，通常是服务器仍在运行旧 JAR，或配置被写成了旧版数组但使用了 v2 节点。先确认文件顶层包含 `"format_version": 2` 和 `"achievements": [...]`，再优先改用上面的对象写法；若要继续使用数组写法，必须把服务器 `mods/` 中的 OmniTools JAR 更新到包含 v2 条件数组兼容分支的版本，然后重启服务器或执行 `/omnitools reload`。

### 解锁、领奖与持久化

达到条件后，服务端将成就 ID 写入世界 `SavedData`，解锁状态不会因原版统计后来被重置而回退。奖励领取状态单独保存，每个成就只能领取一次。数据文件为 `<世界>/data/omnitools_achievements.dat`；备份或迁移服务器时必须同时备份世界目录和 `config/omnitools/`。

### GUI 展示

成就菜单由服务端生成进度快照，显示条件关系和 `current/target`：

```text
挖掘石头与深板岩：650/1000
石头：1000/1000
深板岩：420/1000
条件关系：全部满足
```

`sum` 显示合计进度，`each` 展开每个目标，`all`、`any`、`not` 显示关系。方块、物品和实体优先使用原版本地化名称，无翻译时回退资源 ID。客户端不新增判定逻辑；`broadcastChanges()` 不会每 tick 重新读取全部统计。

### 重载、性能与边界

重载先读取主配置和所有模块配置，再解析、校验成就条件树；全部成功后一次性替换 `OmniToolsConfigSnapshot`，并刷新在线玩家的成就菜单。JSON 错误、未知目标、循环目标组、空标签、通配符混用、单位不兼容、树深度/叶子/目标数量超限或纯 `not` 顶层成就都会拒绝新快照，旧快照继续工作。

每名玩家每轮检查只创建一个 `StatisticEvaluationContext`，相同“统计类型 + 目标”只读取一次；已解锁成就跳过求值。目标组、标签和 `*` 只在配置加载时展开，不在每 10 tick 的检查中扫描注册表。GUI 使用同一份服务端条件进度，避免重复计算。

### 配置字段速查

| 字段 | 说明 |
| --- | --- |
| `format_version` | 当前新格式为 `2`；`1` 仅用于兼容旧数组。 |
| `target_groups` | 可复用目标组，引用自身或间接循环会被拒绝。 |
| `requirements.type` | `stat`、`sum`、`all`、`any` 或 `not`。 |
| `requirements.stat` | 原版统计类型，见“支持的原版统计”。 |
| `requirements.targets` | 显式 ID、`$` 目标组、`#` 标签或 `*`。自定义统计不使用目标列表。 |
| `requirements.match` | `sum`、`each` 或 `any`，默认按目标合计。 |
| `requirements.at_least` | 正整数阈值，加载时按 `unit` 转换。 |
| `requirements.children` / `child` | 逻辑节点的子条件；不允许空数组，支持嵌套。 |
| `requirements.sources` | `sum` 节点的统计源列表，必须属于同一单位类别。 |
| `requirements.custom_stat` | `stat: "custom"` 时对应的 `Stats.CUSTOM` ID。 |
| `requirements.unit` | 距离、时长或伤害统计的显示单位；普通数量统计使用 `count`。 |
| `achievements[].display` / `description` / `icon` | 成就标题、说明和 GUI 图标；图标必须是有效物品。 |
| `rewards.coins` / `rewards.titles` | 领取时发放的货币和称号。 |

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
├── permissions/config.json
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
  "format_version": 2,
  "global": {
    "debug": false,
    "timezone": "Asia/Shanghai"
  },
  "integrations": {
    "placeholder_api": { "enabled": true }
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
  "format_version": 2,
  "global": {
    "debug": true,
    "timezone": "UTC"
  },
  "integrations": {
    "placeholder_api": { "enabled": true }
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

- `format_version`：正整数格式版本，当前为 `2`。旧版 `1` 且缺少 `integrations` 时，Placeholder API 集成默认视为启用；不会强制改写旧文件，补充节点后执行 `/omnitools reload` 即可。
- `global.debug`：全局调试开关；不改变奖励规则。
- `global.timezone`：服务端计算“今天”和在线时长归属日的时区。
- `modules.<id>.enabled`：模块开关。模块 ID 必须使用 `daily_checkin`、`online_reward`、`shop`、`titles`、`title_effects`、`achievements`、`cloud_storage` 或 `permissions`。
- `title_effects` 依赖 `titles`；成就称号奖励需要 `titles` 启用。`permissions.enabled` 仍是预留开关，但 `permissions/config.json` 始终有效，用于保护命令入口，不会因该开关为 `false` 而关闭安全校验。
- `integrations.placeholder_api.enabled`：是否启用可选 Placeholder API 联动。它不是游戏功能模块，不加入 `ModuleId`；只有安装 `placeholder-api` 且该值为 `true` 时才注册占位符。

## Placeholder API 集成

### 工作原理

OmniTools 使用稳定命名空间 `omnitools` 向 Fabric Placeholder API 注册只读占位符。注册器只在服务端启动或成功执行 `/omnitools reload` 后尝试一次；同一 JVM 内不会重复注册。Placeholder API 未安装时不会加载第三方 API 类，OmniTools 仍正常启动。

每次解析都从当前 `PlaceholderContext` 获取 `ServerPlayer`，再读取当前配置快照、`CheckinData`、在线时长服务、称号状态和成就状态。不缓存玩家对象，不读写配置文件，不触发签到、领奖、扣币、称号授予或其他 SavedData 写入。缺少玩家上下文时直接返回安全默认值。

### 如何使用

安装与 Minecraft `1.21.11` 兼容的 Fabric Placeholder API（当前编译联动版本为 `2.8.2+1.21.10`），保持 `fabric.mod.json` 中的 `suggests`，不需要把它作为必需依赖。然后在主配置中启用：

```json
{
  "format_version": 2,
  "integrations": {
    "placeholder_api": { "enabled": true }
  }
}
```

关闭集成后，已经注册的 ID 不会从 Placeholder API 注册表注销；回调会根据当前快照返回默认值，这是该 API 注册表的正常限制。重新开启并执行 `/omnitools reload` 时，如果此前尚未注册则会完成首次注册。

### 玩家命令

Placeholder API 没有 OmniTools 专用命令。第三方聊天、计分板、Tab 列表或 HUD 模组按照自身语法解析 `omnitools` 命名空间，例如使用本节表格中的 `omnitools:balance`。OmniTools 不接受按玩家名或 UUID 查询他人数据的参数。

### 管理员命令

修改 `integrations.placeholder_api.enabled` 或主配置后执行：

```text
/omnitools reload
```

默认需要 `config.reload` 动作（`ADMIN`，Minecraft 等级 `2`）。重载失败时保留旧快照和旧占位符行为。

### 默认配置

主配置首次生成时包含：

```json
{
  "format_version": 2,
  "integrations": {
    "placeholder_api": {
      "enabled": true
    }
  }
}
```

### 示例配置与字段解析

```json
{
  "format_version": 2,
  "integrations": {
    "placeholder_api": { "enabled": false }
  }
}
```

- `enabled: true`：若 Placeholder API 已安装，则注册全部公开 ID。
- `enabled: false`：不注册新 ID；如果本次启动前已经注册过，后续解析只返回默认值。
- 旧 `format_version: 1` 文件缺少 `integrations` 时按 `true` 处理，确保升级不改变联动行为。

公开 ID 和返回值如下：

| ID | 返回值 | 所属模块关闭、集成关闭或无玩家时 |
| --- | --- | --- |
| `omnitools:balance` | 原始货币余额 | `0` |
| `omnitools:balance_formatted` | 带千分位的余额 | `0` |
| `omnitools:checkin_today` | `true`/`false` | `false` |
| `omnitools:checkin_today_rank` | 已签到后的实际今日名次；未签到不返回预计名次 | `0` |
| `omnitools:checkin_total_days` | 累计签到天数 | `0` |
| `omnitools:checkin_streak_days` | 当前连续签到天数 | `0` |
| `omnitools:checkin_month_days` | 本月签到天数 | `0` |
| `omnitools:online_today_seconds` | 今日在线秒数，向下取整 | `0` |
| `omnitools:online_today_minutes` | 今日在线分钟数，向下取整 | `0` |
| `omnitools:online_today_hms` | `HH:mm:ss`，如 `01:23:45` | `00:00:00` |
| `omnitools:title_id` | 当前佩戴称号 ID | 空文本 |
| `omnitools:title` | 保留颜色与样式的称号 | 空文本 |
| `omnitools:title_plain` | 称号纯文本 | 空文本 |
| `omnitools:title_effects_enabled` | 总模块和玩家开关都有效时为 `true` | `false` |
| `omnitools:achievements_unlocked` | 已解锁成就数量 | `0` |
| `omnitools:achievements_claimed` | 已领取成就数量 | `0` |
| `omnitools:achievements_total` | 当前配置成就总数 | `0` |

余额占位符不受 `daily_checkin` 开关限制，因为余额也被商店、成就奖励和 `/money` 使用。在线时长读取包含当前尚未周期落盘的会话时间；签到日期使用 `global.timezone`，与签到 GUI 保持一致；本月签到天数会遍历该玩家历史记录，适合普通显示场景，不建议在高频计分板中每 tick 重复解析。

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

这些管理动作默认需要 `ADMIN`（Minecraft 等级 `2`），也可在 `permissions/config.json` 中分别调整。`add` 增加余额，`remove` 按实际可扣除数量减少余额；`clear` 和 `clear today` 等价，只清除当前配置时区的当天签到记录。

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

`title.grant` 和 `title.revoke` 默认需要 `ADMIN`（等级 `2`），可在权限配置中单独调整。`give`/`add` 授予称号但不会自动佩戴；`remove`/`take` 回收称号，若玩家正在佩戴则同时卸下。修改称号定义后使用 `/omnitools reload`。

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

默认需要 `ADMIN`（等级 `2`），具体以 `config.reload` 权限动作配置为准。重载时会刷新在线玩家效果；如果关闭 `title_effects`，会先移除在线玩家的称号效果，但保留称号文字显示。

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
- 权限效果使用 `permission`。只允许 `omnitools:cloud_storage` 或 `omnitools:command.*`，其他节点会被配置校验拒绝；`omnitools:command.*` 还必须在权限配置中显式打开 `allow_title_command_grants`。
- `name` 和 `display` 用于 GUI 提示；效果 ID就是称号配置中引用的键。

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
- 权限节点：`omnitools:cloud_storage`。称号权限效果默认只能授予这一节点；只有 `allow_title_command_grants` 为 `true` 时才允许 `omnitools:command.*`。

## 指令权限模块（`permissions`）

### 工作原理

权限规则保存在服务端的 `config/omnitools/permissions/config.json`。模组把命令和所有别名归一为固定动作 ID，再将动作映射到最低角色：`PLAYER`（等级 0）、`MODERATOR`（等级 1）、`ADMIN`（等级 2）或 `OWNER`（等级 4）。控制台命令源始终允许。判断使用 Minecraft 原生 `CommandSourceStack.permissions()` 和权限等级，不维护 UUID 白名单。

Brigadier 的 `.requires(...)` 只负责命令树显示、补全和入口过滤；执行方法、打开 GUI、GUI 点击、云存储快捷移动和称号/货币等状态变更还会再次调用服务端权限服务。权限重载后会重新发送在线玩家命令树，并关闭已无权访问的 GUI。

### 如何使用

服务器首次启动会生成权限文件。编辑文件后执行 `/omnitools reload`，所有规则在一次成功重载后原子替换；JSON 损坏、未知动作、未知角色或错误字段会拒绝新快照并继续使用上一份有效规则。

动作 ID 不按命令别名拆分，以下入口共享同一权限：

| 动作 ID | 覆盖入口 |
| --- | --- |
| `checkin.open` | `/omnitools`、`/omnitools open`、`/checkin` |
| `online.open` | `/omnitools online`、`/checkin online` |
| `shop.open` | `/omnitools shop`、`/checkin shop` |
| `title.open` | `/omnitools title`、`/title`、`/checkin title` |
| `achievements.open` | `/omnitools achievements`、`/checkin achievements` |
| `storage.open` | `/omnitools storage`、`/checkin storage`、`/cloudstorage`、`/cstorage` |
| `currency.balance.self` | `/omnitools balance`、`/omnitools currency`、`/money`、`/balance` |
| `currency.balance.other` | `/omnitools balance <玩家>`、`/balance <玩家>` |
| `currency.add` | `/omnitools add`、`currency add`、`/money add` |
| `currency.remove` | `/omnitools remove`、`currency remove|deduct|take`、`/money remove` |
| `checkin.clear` | `/omnitools clear [today]`、`/checkin clear [today]` |
| `title.grant` | `title give|add` 的所有入口 |
| `title.revoke` | `title remove|take` 的所有入口 |
| `config.reload` | `/omnitools reload` |

### 玩家命令

权限模块没有单独的玩家命令。玩家直接使用每日签到、在线奖励、商店、称号、成就和余额命令；每条命令都会按当前动作规则检查。默认情况下，功能打开命令和查询自己的余额都是 `PLAYER`，因此普通玩家可以使用：

```text
/omnitools
/omnitools online
/omnitools shop
/omnitools title
/omnitools achievements
/omnitools balance
/money
```

云存储默认要求 `ADMIN`，但 `storage.open.allow_native_node: true` 时，拥有 `omnitools:cloud_storage` 原生节点的普通玩家也可以打开和操作云存储。

### 管理员命令

权限模块没有独立的授予/撤销命令，规则通过文件编辑并使用以下命令加载：

```text
/omnitools reload
```

`config.reload` 默认是 `ADMIN`。修改它时建议至少保留一名拥有新角色的管理员，否则无法通过命令重新加载错误配置；启动时读取失败仍会保留上一份有效快照。

### 默认配置

主配置中的 `permissions.enabled` 默认仍为 `false`，它只是未来权限数据后端的预留开关，不会关闭下面这份命令安全配置。首次生成的 `config/omnitools/permissions/config.json` 等价于：

```json
{
  "format_version": 1,
  "allow_title_command_grants": false,
  "commands": {
    "checkin.open": "PLAYER",
    "online.open": "PLAYER",
    "shop.open": "PLAYER",
    "title.open": "PLAYER",
    "achievements.open": "PLAYER",
    "storage.open": {
      "role": "ADMIN",
      "allow_native_node": true
    },
    "currency.balance.self": "PLAYER",
    "currency.balance.other": "ADMIN",
    "currency.add": "ADMIN",
    "currency.remove": "ADMIN",
    "checkin.clear": "ADMIN",
    "title.grant": "ADMIN",
    "title.revoke": "ADMIN",
    "config.reload": "ADMIN"
  }
}
```

这组默认值与旧版本行为一致：等级 0 玩家可以打开功能和查询自己的余额，等级 2 管理员可以修改余额、清除签到、管理称号和重载配置，控制台全部允许。

### 示例配置与字段解析

下面的示例把商店限制为协管以上、称号 GUI 限制为管理员以上，并要求只有服主可以重载配置；余额查询自己的动作仍保持对玩家开放：

```json
{
  "format_version": 1,
  "allow_title_command_grants": false,
  "commands": {
    "checkin.open": "PLAYER",
    "online.open": "PLAYER",
    "shop.open": "MODERATOR",
    "title.open": "ADMIN",
    "achievements.open": "PLAYER",
    "storage.open": {
      "role": "ADMIN",
      "allow_native_node": true
    },
    "currency.balance.self": "PLAYER",
    "currency.balance.other": "ADMIN",
    "currency.add": "ADMIN",
    "currency.remove": "ADMIN",
    "checkin.clear": "ADMIN",
    "title.grant": "ADMIN",
    "title.revoke": "ADMIN",
    "config.reload": "OWNER"
  }
}
```

- `format_version`：当前为整数 `1`。
- `commands`：动作到角色的映射。未填写的动作使用代码内默认值；未知动作或未知角色会使整次重载失败。
- `role`：角色名大小写不敏感，解析后转换为枚举。`PLAYER`、`MODERATOR`、`ADMIN`、`OWNER` 分别对应原生等级 0、1、2、4。
- `storage.open.allow_native_node`：为 `true` 时保留 `omnitools:cloud_storage` 节点绕过最低角色的行为；为 `false` 时只有达到配置角色的玩家可用，控制台仍始终允许。
- `allow_title_command_grants`：默认 `false`。称号效果只能授予 `omnitools:cloud_storage`；若设为 `true`，才允许标题效果配置中的 `omnitools:command.*` 节点参与原生命令权限扩展。该开关不会自动授予任何节点。

称号权限效果默认禁止 `omnitools:command.moderator`、`omnitools:command.gamemaster`、`omnitools:command.admin` 和 `omnitools:command.owner`，避免玩家佩戴称号后意外获得管理命令；云存储原生节点行为不受影响。

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

重载流程为：迁移旧配置、读取主配置、在服务端注册表可用后读取模块文件、校验统计目标/标签/目标组以及称号、效果和权限引用、构建快照并一次性替换。重载失败时旧快照继续工作，不会把其他模块清空。

- GUI 无法打开：确认服务端和客户端模组版本一致、模块已启用；云存储还要检查 `omnitools:cloud_storage` 权限。
- 配置不生效：确认 JSON 为 UTF-8、字段类型正确、ID 唯一，然后执行 `/omnitools reload`。
- 商店加载失败：检查 `index` 是否重复，物品 ID、组件语法或完整 SNBT 是否有效。
- 成就目标无效：检查统计类型对应的方块、物品或实体 ID，以及标签、目标组、`unit` 和 `match` 写法；模组只读取原版统计，不维护第二份计数。
- 在线奖励错位：不要复用或随意更改已发布的奖励 ID。
- 称号效果不生效：确认 `title_effects` 和 `titles` 都启用，且称号引用的效果 ID 存在。
- 重启后数据缺失：确认使用的是原来的世界目录，并恢复对应的 `omnitools_*.dat` 文件。

## 构建与验证

使用 Gradle 编译和打包：

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

## 附录：成就系统 v2 完成度自检报告

本节只记录隔离环境中的验收证据，不重复定义成就配置；实际使用和字段说明以文档前面的“成就系统（`achievements`）”章节为准。

### 自检范围与隔离环境

本报告记录 2026-08-23 对成就系统 v2 的一次完成度自检。测试环境为 Fabric 1.21.11、Fabric Loader 0.19.3、Fabric API 0.141.6+1.21.11、Java 21，构建产物由 `compileJava` 和 ModMind 构建任务生成。

所有配置解析和服务端启动测试都在独立目录 `.modmind/builds/achievement-self-check-20260823-1038/` 中完成，使用独立 `config/`、`run/` 和新建世界；没有写入正式 `run/config` 或正式世界。合法矩阵的隔离服务端就绪日志为 `project/stdout-3.log`，最终稳定启动验证日志为 `.modmind/builds/runServer-1787483277644.log`。错误矩阵的逐项日志为 `project/matrix-*.log`，v1 旧配置复核日志为 `v1-project/v1-gradle-2.log`。

### 总体结论

**PARTIAL**。条件树实现、配置迁移、目标展开、错误回退和服务端启动均有代码与隔离日志证据；未能在本次无认证客户端环境中完成真实玩家统计增长、奖励领取/重登录持久化以及 GUI 截图验证，因此不能把整体验收标为 PASS。未发现会导致服务器启动崩溃的成就配置错误。

### 检查项结果

| 检查项 | 结果 | 证据 |
| --- | --- | --- |
| 静态结构 | PASS | `AchievementCondition`、`StatCondition`、`SumCondition`、`AllCondition`、`AnyCondition`、`NotCondition` 分别位于 `src/main/java/dev/modmind/omnitools/achievement/`；`AchievementConfig.CURRENT_FORMAT_VERSION` 为 2（`AchievementConfig.java:46`）；解锁、领奖和菜单快照都调用 `AchievementDefinition.condition()`（`AchievementService.java:82-210`）。 |
| 配置解析 | PASS | 合法隔离配置覆盖 v1、逻辑嵌套、5 类物品统计、`entity_killed`/`entity_killed_by`、custom 距离/时间/伤害、目标组、标签、通配符和跨源 sum，并在 `project/stdout-3.log` 记录 `Done`；v1 旧配置在 `v1-project/v1-gradle-2.log` 就绪。未知 ID/标签/分组、循环组、通配符混用、错误单位、空子条件和纯 `not` 均在 `project/matrix-*.log` 被拒绝并记录 `Configuration reload rejected; keeping the previous snapshot`。 |
| 逻辑判定 | PASS | `StatCondition` 的 `SUM/EACH/ANY`、`AllCondition`、`AnyCondition`、`NotCondition` 均通过统一 `AchievementCondition.evaluate/progress` 接口；`ConditionProgress` 同时提供 `completed/current/target`。解析器保留 v1 requirements 的 `all + stat` 语义，且 `ConfigValidator` 拒绝没有正向统计叶子的顶层条件。 |
| 运行时功能 | PARTIAL | Fabric 服务端隔离启动、模组加载和 Placeholder API 注册通过，证据为 `project/stdout-3.log` 与 `runServer-1787483277644.log`。本轮没有可用的已认证客户端或 GameTest 脚本，挖掘/合成/使用/损坏/拾取/丢弃、Boss 击杀、奖励领取一次性、重登录持久化和“关闭模块不再检查”未做玩家级实测。 |
| GUI | PARTIAL | `AchievementScreenHandler.refreshContents()` 只消费一次 `AchievementService.MenuSnapshot`，`broadcastChanges()` 按 10 tick 和配置 revision 刷新（`AchievementScreenHandler.java:146-180`）；服务端条件树进度、关系和本地化目标名均有静态证据。没有客户端截图或交互日志，故不能证明三种状态、重载后页面刷新和实际显示文本完全正确。 |
| 性能边界 | PARTIAL | `StatisticEvaluationContext` 按“统计类型 + 目标 ID”缓存（`StatisticEvaluationContext.java:11-35`）；`StatisticTargetResolver` 只在解析时展开目标并限制 2048 个目标；解析器/校验器限制 8 层和 128 个叶子，服务跳过已解锁成就（`AchievementService.java:144-151`）。本轮没有多玩家压力或 tick profile，无法以运行时数据证明无明显延迟。 |

### 配置解析矩阵

| 场景 | 预期结果 | 实际结果 |
| --- | --- | --- |
| v1 `requirements` 数组 | 自动转换为 `all`，旧“挖石头 1000”行为保持不变 | PASS；`v1-project/v1-gradle-2.log` 服务端就绪，无成就配置错误 |
| 单个 `block_mined` | 解析方块注册表并生成一个 stat 叶子 | PASS；合法矩阵启动成功 |
| `item_crafted`、`item_used`、`item_broken` | 使用物品注册表 | PASS；合法矩阵启动成功 |
| `item_picked_up`、`item_dropped` | 使用物品注册表 | PASS；合法矩阵启动成功 |
| `entity_killed`、`entity_killed_by` | 使用实体类型注册表 | PASS；合法矩阵启动成功 |
| custom 移动、时长、伤害 | 映射 `Stats.CUSTOM` 并转换单位阈值 | PASS；`walk_one_cm`、`play_time`、`damage_dealt`、`damage_taken` 配置启动成功 |
| `$target_group` | 加载时展开并去重 | PASS；`stone_family`、`bosses` 启动成功 |
| `#minecraft:logs` | 加载时从对应注册表标签展开 | PASS；合法标签启动成功；未知标签被拒绝 |
| `*` | 加载时展开当前统计域全部注册对象 | PASS；`item_used` 通配符启动成功；与具体 ID 混用被拒绝 |
| `match: sum/each/any` | 分别实现累计、每项达标、任一达标 | PASS；三种配置均进入合法矩阵并通过解析 |
| `sum` 跨统计源 | 只允许相同单位类型 | PASS；数量型 `block_mined + item_used` 通过；距离/数量冲突被拒绝 |
| 空数组、未知 ID、未知标签、未知分组 | 拒绝新快照 | PASS；见 `matrix-unknown-id.log`、`matrix-unknown-tag.log`、`matrix-unknown-group.log`、`matrix-empty-children.log` |
| 循环分组、错误单位、通配符混用、纯 `not` | 拒绝新快照且保留旧快照 | PASS；见 `matrix-cycle-group.log`、`matrix-bad-unit.log`、`matrix-wildcard-mix.log`、`matrix-not-only.log` |

### 逻辑语义核对

- 单个石头阈值只读取 `minecraft:stone`，达到 1000 才完成。
- `sum` 将石头和深板岩统计值相加；`each` 要求两个目标分别达到阈值；`any` 只要求一个目标达到阈值。
- `all` 要求所有子条件完成；`any` 要求任一子条件完成；`not` 在子条件未完成时为 true，子条件完成后为 false。
- `not` 可嵌套在 `all`/`any` 中并按树结构求值；顶层纯 `not` 因没有正向统计条件而被拒绝。
- 解锁周期检查、领奖前即时复核和 GUI 菜单快照都从同一 `AchievementCondition` 树生成结果；GUI 不自行读取原版统计。

### 运行命令与证据索引

```powershell
.\gradlew.bat compileJava
```

```text
ModMind server matrix: PASS
log: .modmind/builds/runServer-1787483277644.log
isolated config/project: .modmind/builds/achievement-self-check-20260823-1038/
artifact: .modmind/minecraft/mods/modmind-current-project.jar
```

`modmind_validate_content` 检查 4 个资源文件无错误，`modmind_build_project` 成功生成 297803 字节产物，`git diff --check` 仅报告现有文件换行符转换提示。正式 `run/config/omnitools/achievements/config.json` 在测试前后未被修改。

### 尚需人工或客户端补验

需要使用隔离客户端/测试玩家继续验证：实际统计增长和单位数值、Boss 击杀方向、解锁通知、奖励只能领取一次、玩家重登录后的 SavedData、成就模块关闭后的行为，以及 GUI 中 `sum/each/any` 的本地化名称、状态颜色和重载后数量刷新。完成这些交互证据后，整体结论才可从 PARTIAL 提升为 PASS。

## 许可证

本项目使用 [MIT License](LICENSE) 发布。
