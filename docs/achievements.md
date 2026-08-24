# 成就配置指南

OmniTools 的成就配置文件位于 `config/omnitools/achievements/config.json`。成就由服务端根据原版统计判定，满足条件后解锁，奖励只能领取一次。

修改配置后执行 `/omnitools reload`。重载会先校验完整配置；新配置有错误时会保留当前有效快照，不会替换正在运行的成就列表。

## 格式版本与兼容

新配置使用 `format_version: 2`。每个成就的 `requirements` 是一个条件对象：

```json
{
  "format_version": 2,
  "achievements": [
    {
      "id": "stone_worker",
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
      "rewards": {
        "coins": 500,
        "titles": ["geologist"]
      }
    }
  ]
}
```

旧版 `format_version: 1` 的 `requirements` 数组继续可用，不会被自动写回或覆盖。每一条旧需求会转换为一个 `stat` 条件，并由顶层 `all` 连接，因此旧版“全部需求满足”的语义不变：

```json
{
  "format_version": 1,
  "achievements": [
    {
      "id": "old_stone_worker",
      "display": "旧版石匠",
      "description": "兼容旧配置",
      "icon": "minecraft:stone",
      "requirements": [
        {
          "type": "block_mined",
          "target": "minecraft:stone",
          "count": 1000
        }
      ],
      "rewards": {
        "coins": 500
      }
    }
  ]
}
```

建议新增或维护的配置统一使用 v2 的条件对象。成就 `id` 必须唯一；`icon` 必须是有效且非空气的物品 ID。奖励中的称号 ID 必须能在启用的称号配置中找到。

## 条件类型

每个条件都必须声明 `type`。条件树支持嵌套，配置中的数字阈值均为正整数。

| 类型 | 字段 | 含义 |
| --- | --- | --- |
| `stat` | `stat`、目标、`at_least` | 一个原版统计来源的条件 |
| `sum` | `sources`、`at_least` | 多个统计来源的累计条件 |
| `all` | `children` | 所有子条件都满足 |
| `any` | `children` | 任一子条件满足 |
| `not` | `child` | 子条件尚未满足 |

`all` 和 `any` 的 `children` 必须是非空数组；`not` 只接受一个 `child` 对象。顶层条件树必须含有至少一个未被 `not` 包裹的正向 `stat` 或 `sum` 条件，不能创建只由 `not` 组成的成就。

例如，挖石头后还需要击杀凋灵或末影龙之一：

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
      "type": "any",
      "children": [
        {
          "type": "stat",
          "stat": "entity_killed",
          "targets": ["minecraft:wither"],
          "at_least": 1
        },
        {
          "type": "stat",
          "stat": "entity_killed",
          "targets": ["minecraft:ender_dragon"],
          "at_least": 1
        }
      ]
    }
  ]
}
```

`not` 适合表达累计统计尚未达到某阈值。例如下面条件表示“死亡次数仍少于 1 次”；它必须与正向统计条件组合使用，不能单独作为一个成就：

```json
{
  "type": "not",
  "child": {
    "type": "stat",
    "stat": "custom",
    "custom_stat": "minecraft:deaths",
    "at_least": 1
  }
}
```

## `stat` 条件

非 `custom` 统计使用 `targets` 数组，`match` 默认为 `sum`：

```json
{
  "type": "stat",
  "stat": "block_mined",
  "targets": ["minecraft:stone", "minecraft:deepslate"],
  "match": "sum",
  "at_least": 1000
}
```

| `match` | 完成条件 | 适用场景 |
| --- | --- | --- |
| `sum` | 所有目标的统计值总和达到阈值 | 石头与深板岩合计挖掘 1000 个 |
| `each` | 每个目标都分别达到阈值 | 石头和深板岩各自挖掘 1000 个 |
| `any` | 任意一个目标达到阈值 | 石头或深板岩任意一种挖掘 1000 个 |

可用的 `stat` 值及其目标域如下：

| `stat` | 原版统计 | `targets` 的对象类型 |
| --- | --- | --- |
| `block_mined` | `Stats.BLOCK_MINED` | 方块 |
| `item_crafted` | `Stats.ITEM_CRAFTED` | 物品 |
| `item_used` | `Stats.ITEM_USED` | 物品 |
| `item_broken` | `Stats.ITEM_BROKEN` | 物品 |
| `item_picked_up` | `Stats.ITEM_PICKED_UP` | 物品 |
| `item_dropped` | `Stats.ITEM_DROPPED` | 物品 |
| `entity_killed` | `Stats.ENTITY_KILLED` | 实体类型 |
| `entity_killed_by` | `Stats.ENTITY_KILLED_BY` | 实体类型 |
| `custom` | `Stats.CUSTOM` | 使用 `custom_stat`，不使用 `targets` |

配置目标时必须使用与统计域相符的注册表 ID。例如 `block_mined` 不能引用物品，`item_used` 不能引用方块，`entity_killed` 不能引用物品。

### 自定义原版统计与单位

`custom` 条件使用 `custom_stat`，并且禁止出现 `targets`：

```json
{
  "type": "stat",
  "stat": "custom",
  "custom_stat": "minecraft:walk_one_cm",
  "unit": "kilometers",
  "at_least": 10
}
```

统计值内部始终使用原版原始值，配置加载时才换算阈值。支持的单位取决于 `custom_stat` 的类别：

| 统计类别 | 常见 `custom_stat` | 可用 `unit` |
| --- | --- | --- |
| 计数 | `jump`、`mob_kills`、`player_kills`、`deaths`、`animals_bred` | `count`，默认 |
| 距离 | `walk_one_cm`、`sprint_one_cm`、`swim_one_cm`、`fly_one_cm`、`aviate_one_cm`、`boat_one_cm`、`horse_one_cm` | `cm`、`meters`、`blocks`、`kilometers` |
| 时长 | `play_time`、`total_world_time` | `ticks`、`seconds`、`minutes`、`hours` |
| 伤害 | `damage_dealt`、`damage_taken`、`damage_blocked_by_shield` | `damage`、`hearts` |

`custom_stat` 必须是当前 Minecraft 注册的原版自定义统计。时长和伤害的界面进度按配置单位显示；距离统一以米显示，例如“行走 10 公里”会显示为 `当前值/10000 meters`。普通方块、物品和实体统计使用计数。

## 目标组、标签与通配符

根对象可选的 `target_groups` 用于复用目标列表。组名只能使用小写字母、数字、`.`、`_` 和 `-`；以 `$` 引用组：

```json
{
  "format_version": 2,
  "target_groups": {
    "stone_family": [
      "minecraft:stone",
      "minecraft:deepslate"
    ],
    "bosses": [
      "minecraft:ender_dragon",
      "minecraft:wither"
    ]
  },
  "achievements": []
}
```

`targets` 或 `sum.sources[].targets` 可使用以下形式：

| 写法 | 含义 |
| --- | --- |
| `minecraft:stone` | 一个显式注册表 ID |
| `$stone_family` | 引用同一文件的目标组；目标组可以嵌套引用目标组 |
| `#minecraft:logs` | 当前统计域中的 Minecraft 标签 |
| `*` | 当前统计域中全部注册对象 |

标签、目标组和 `*` 会在配置加载或重载时展开为固定目标列表，统计检查时不会扫描注册表。未知或为空的标签、未知目标组、循环分组、无效 ID 都会拒绝配置。`*` 不能与任何其他目标混用，包括通过目标组间接混用。

## 跨统计累计 `sum`

`sum` 将每个来源解析出的统计值相加后与 `at_least` 比较。每个来源使用 `stat` 和对应的 `targets`，但不使用 `match`：

```json
{
  "type": "sum",
  "at_least": 1000,
  "sources": [
    {
      "stat": "block_mined",
      "targets": ["$stone_family"]
    },
    {
      "stat": "item_used",
      "targets": ["$stone_family"]
    }
  ]
}
```

同一个 `sum` 只能合计相同单位类别：计数可以与计数合计，距离只能与距离合计，时长只能与时长合计，伤害只能与伤害合计。混合距离、时长、伤害和计数会被拒绝。含距离、时长或伤害自定义统计的 `sum` 应在每个自定义来源中声明有效 `unit`，并在条件本身声明用于阈值与显示的同类 `unit`。

## 完整 v2 示例

下面的示例包含目标组、累计挖掘、击杀 Boss 与行走距离。它展示的是一个有效的条件树结构；奖励中的 `geologist` 需要在称号配置中存在。

```json
{
  "format_version": 2,
  "target_groups": {
    "stone_family": [
      "minecraft:stone",
      "minecraft:deepslate"
    ],
    "bosses": [
      "minecraft:ender_dragon",
      "minecraft:wither"
    ]
  },
  "achievements": [
    {
      "id": "experienced_geologist",
      "display": "资深地质学家",
      "description": "挖掘石材、击杀 Boss 并完成长途行走",
      "icon": "minecraft:diamond_pickaxe",
      "requirements": {
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
            "type": "stat",
            "stat": "entity_killed",
            "targets": ["$bosses"],
            "match": "any",
            "at_least": 1
          },
          {
            "type": "stat",
            "stat": "custom",
            "custom_stat": "minecraft:walk_one_cm",
            "unit": "kilometers",
            "at_least": 10
          }
        ]
      },
      "rewards": {
        "coins": 500,
        "titles": ["geologist"]
      }
    }
  ]
}
```

## 配置限制与排错

为避免配置导致高频统计检查失控，单个成就最多嵌套 8 层、最多 128 个 `stat` 或 `sum` 叶子条件；标签、分组和通配符展开后的目标数最多为 2048。阈值和累计值使用非负长整数并做饱和处理，过大的可换算阈值会在加载时拒绝。

常见错误包括：把 v2 的 `stat` 节点写进旧版 `requirements` 数组、给 `custom` 同时写入 `targets`、使用不存在的注册表 ID、混用 `*`、为空条件数组配置 `all` 或 `any`，以及在 `sum` 中混合不同单位类别。请根据日志给出的字段路径修正后再执行 `/omnitools reload`。

成就菜单展示、解锁检查和领取检查都由服务端使用同一条件树计算。配置更新后，已打开的菜单会在下一次刷新时切换到新配置。
