# 成就

## 1. 功能简介

成就模块使用原版玩家统计构建条件树，满足后永久解锁，并由玩家从成就界面领取一次性货币、物品、称号和受控服务器指令奖励。条件检查每 10 tick 执行；已解锁成就不会再次计算原始统计。成就界面使用 OmniTools 的自定义菜单类型及客户端界面。

## 2. 模块开关

根配置为 `config/omnitools/config.json`：

```json
{
  "modules": {
    "achievements": { "enabled": true }
  }
}
```

禁用后周期检查停止，成就菜单关闭并拒绝领取；解锁和领取记录保留，重新启用时立即检查在线玩家。

## 3. 初始配置

首次加载生成 `config/omnitools/achievements/config.json`，默认成就是挖掘 1000 个石头并奖励 500 货币和 `geologist` 称号：

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
      "rewards": [
        { "id": "stone_coins", "type": "currency", "amount": 500 },
        { "id": "stone_title", "type": "title", "title": "geologist" }
      ]
    }
  ]
}
```

`format_version: 1` 的需求数组仍可读取；新配置应使用 v2 条件对象。旧的奖励对象 `{ "coins": 500, "titles": ["geologist"] }` 也可读取，会转换为稳定 ID `legacy_<成就ID>_currency` 与 `legacy_<成就ID>_title_<称号ID>`。格式错误时不会覆盖原文件，完整重载保留旧快照。

## 4. 指令与权限

| 指令 | 别名 | 用途 | 默认权限 | 仅玩家 |
| --- | --- | --- | --- | --- |
| `/omnitools achievements [open]` | `/checkin achievements [open]` | 打开成就并领取奖励 | `achievements.open` (`PLAYER`) | 是 |
| `/omnitools rewards retry` | 无 | 重试自己的待处理签到和成就奖励 | `rewards.retry` (`PLAYER`) | 是 |

## 5. 配置字段

根字段：

| 字段 | JSON 类型 | 必填 | 默认值/范围 | 作用与错误行为 |
| --- | --- | --- | --- | --- |
| `format_version` | number | 否 | `1` 或 `2` | 使用 v2 条件树；其他版本拒绝配置。 |
| `target_groups` | object | 否 | `{}` | 复用目标列表；组名为小写 ID，值为非空字符串数组，可用 `$组名` 嵌套。未知组、空组、循环引用或非法组名会拒绝候选配置。 |
| `achievements` | array | 是 | 默认一项 | 成就定义列表。 |
| `achievements[].id` | string | 是 | 小写 ID，唯一 | 稳定成就 ID。 |
| `display` | string | 是 | 最多 128 字符 | 成就名称，支持 `&` 颜色。 |
| `description` | string | 是 | 最多 512 字符 | 成就说明。 |
| `icon` | string | 是 | 有效、非空气物品 ID | 界面图标。 |
| `requirements` | object（v2）或 array（v1/迁移形式） | 是 | 条件树 | 条件树；v1 使用旧数组，v2 数组按 `all` 解释。缺失、类型错误、空数组或混合 v1/v2 节点会拒绝候选配置。 |
| `rewards` | array（旧 object 兼容） | 否 | `[]` | 一次性奖励数组，按配置顺序发放；旧 `{ coins, titles }` 对象会转换为稳定遗留 ID。数组中重复 ID 会拒绝候选配置。 |
| `rewards[].id` | string | 是 | `[a-z0-9_.-]{1,64}`，同一成就唯一 | 稳定账本 ID；排序和重载不会重复发放。 |
| `rewards[].type` | string | 是 | `currency`、`item`、`title`、`command` | 奖励类型；未知值拒绝配置。 |
| `rewards[].amount` | integer | `currency` 是 | `>= 0` | 货币金额，使用 `long` 保存。 |
| `rewards[].item`、`count`、`components` | string、integer、string/空 object | `item` 是 | `count` 为 `1-64` | 物品奖励；组件语法复用商店。无效物品、组件或单事件物品总数超过 2304 会拒绝配置。 |
| `rewards[].title` | string | `title` 是 | 现有称号 ID | 称号奖励；称号模块关闭或 ID 不存在时拒绝候选配置。已拥有称号视为成功。 |
| `rewards[].run_as`、`command` | string、string | `command` 是 | 仅 `console`；长度受根配置限制 | 受控指令奖励；只接受白名单占位符，禁止换行和未知占位符。根配置未开启指令奖励时拒绝候选配置。 |

条件节点必须有 `type`。支持 `stat`、`sum`、`all`、`any`、`not`；未知类型、无效单位、无效注册表 ID 或违反下列约束都会拒绝整个候选配置。

`stat` 节点：

| 字段 | JSON 类型 | 必填 | 默认值/范围 | 作用与错误行为 |
| --- | --- | --- | --- | --- |
| `type` | string | 是 | `stat` | 单一统计条件。 |
| `stat` | string | 是 | `block_mined`、`item_crafted`、`item_used`、`item_broken`、`item_picked_up`、`item_dropped`、`entity_killed`、`entity_killed_by`、`custom` | 选择原版统计域；未知值会拒绝配置。 |
| `targets` | string array | 非 `custom` 时是 | 非空；展开后最多 2048 个 | 目标方块、物品或实体。`custom` 节点禁止此字段。非法、空或不匹配统计域的目标会拒绝配置。 |
| `custom_stat` | string | `stat=custom` 时是 | 有效原版自定义统计 ID | 自定义统计的 ID；非 `custom` 时不读取。缺失或不存在会拒绝配置。 |
| `match` | string | 否 | `sum`；可为 `sum`、`each`、`any` | 多个目标的比较方式。未知值会拒绝配置。 |
| `at_least` | integer | 是 | 正整数 | 达成阈值；小于 1、非整数或缺失会拒绝配置。 |
| `unit` | string | `custom` 时否 | `count` | 自定义统计的阈值单位；无效或不适用的单位会拒绝配置。 |

`sum` 节点：

| 字段 | JSON 类型 | 必填 | 默认值/范围 | 作用与错误行为 |
| --- | --- | --- | --- | --- |
| `type` | string | 是 | `sum` | 累加多个统计来源。 |
| `sources` | array | 是 | 非空；展开后最多 2048 个目标 | 参与累加的来源；空数组、非对象来源或过多目标会拒绝配置。 |
| `sources[].stat` | string | 是 | 与 `stat.stat` 相同 | 来源统计域。 |
| `sources[].targets` | string array | 非 `custom` 来源时是 | 非空 | 来源目标；`custom` 来源禁止此字段。 |
| `sources[].custom_stat` | string | `stat=custom` 时是 | 有效原版自定义统计 ID | 自定义来源统计。 |
| `sources[].unit` | string | 否 | `count` | 仅校验该自定义来源使用的单位是否合法；总阈值的换算由节点 `unit` 决定。 |
| `at_least` | integer | 是 | 正整数 | 所有来源相加后的阈值。 |
| `unit` | string | 非计数类自定义来源时是 | `count` | 距离、时长或伤害的总阈值单位；非自定义来源只能为 `count`。 |

一个 `sum` 只能组合相同的统计单位类别（计数、距离、时长或伤害）。距离自定义统计支持 `cm`、`meters`、`blocks`、`kilometers`；`play_time`、`total_world_time` 支持 `ticks`、`seconds`、`minutes`、`hours`；名称以 `damage_` 开头的统计支持 `damage`、`hearts`；其他自定义统计只能使用 `count`。

逻辑节点：

| 节点 | 字段 | JSON 类型 | 必填 | 默认值/范围 | 作用与错误行为 |
| --- | --- | --- | --- | --- | --- |
| `all` | `children` | object array | 是 | 非空 | 所有子条件完成；任何子项不是对象会拒绝配置。 |
| `any` | `children` | object array | 是 | 非空 | 任一子条件完成；任何子项不是对象会拒绝配置。 |
| `not` | `child` | object | 是 | 一个条件节点 | 子条件未完成；缺失或不是对象会拒绝配置。整个成就树仍必须含至少一个正向 `stat` 或 `sum` 叶子。 |

目标组使用 `target_groups.<group_id>` 定义。每个值是非空字符串数组，可包含显式 ID、`$其他组`、标签 `#namespace:tag` 或通配符 `*`。组 ID 与成就 ID 同样匹配 `[a-z0-9_.-]{1,64}`。通配符不能与其他目标混用，标签必须存在且非空；目标解析后最多 2048 个。条件最多嵌套 8 层，每个成就最多 128 个 `stat`/`sum` 叶子。

v1 兼容格式中的 `requirements` 是非空数组，每项都必须为 `{ "type": "...", "target": "...", "count": 1 }`：`type` 为上述原版统计域，`target` 为对应注册表 ID，`count` 是正整数。旧格式按所有项目均需满足处理；新配置请使用 v2 条件对象。

## 6. 使用示例

最小 v2 成就：

```json
{
  "format_version": 2,
  "achievements": [
    {
      "id": "walk_far",
      "display": "远足者",
      "description": "行走 10 公里",
      "icon": "minecraft:diamond_boots",
      "requirements": {
        "type": "stat",
        "stat": "custom",
        "custom_stat": "minecraft:walk_one_cm",
        "unit": "kilometers",
        "at_least": 10
      },
      "rewards": [
        { "id": "walk_currency", "type": "currency", "amount": 100 },
        { "id": "walk_boots", "type": "item", "item": "minecraft:leather_boots", "count": 1, "components": {} }
      ]
    }
  ]
}
```

组合条件：

```json
{
  "type": "all",
  "children": [
    { "type": "stat", "stat": "block_mined", "targets": ["minecraft:stone"], "at_least": 1000 },
    { "type": "stat", "stat": "entity_killed", "targets": ["minecraft:wither"], "at_least": 1 }
  ]
}
```

修改配置后执行 `/omnitools reload`。若日志提示未知注册表 ID、错误单位、条件树过深或目标过多，修正对应字段后重载；旧成就列表保持有效。

命令奖励必须同时修改根配置：

```json
{
  "global": {
    "reward_security": {
      "allow_command_rewards": true,
      "max_command_length": 1024
    }
  }
}
```

然后可在奖励数组中使用 `{ "id": "announce", "type": "command", "run_as": "console", "command": "say {player_name} 完成了成就" }`。允许的替换变量只有 `{player_name}`、`{player_uuid}`、`{player_x}`、`{player_y}`、`{player_z}`、`{player_world}`。指令在账本先标记为已派发后执行，采用最多执行一次策略：服务器极端崩溃时可能漏执行，但不会在重试时重复执行。

## 7. 数据保存

世界 `SavedData` 的 `AchievementData` 保存每位玩家已解锁和已领取的成就 ID。独立 `RewardClaimLedger` 以 `achievement:<uuid>:<achievement_id>` 与奖励 ID 保存每条奖励的 `PENDING`、`GRANTED`、`BLOCKED`、`FAILED` 状态和失败原因。配置 JSON 不保存进度。

领取时只要有物品背包空间不足、称号模块关闭等可恢复问题，成就保持“奖励待处理”而不是标记 `claimed`；再次点击、打开成就界面、玩家登录或执行 `/omnitools rewards retry` 会跳过已 `GRANTED` 的奖励并重试其余部分。物品只完整进入主背包，绝不掉落或部分发放。

升级或迁移前必须同时备份世界目录和 `config/omnitools/`。

## 8. 热重载与依赖

成功重载替换条件树和奖励定义；已打开菜单在刷新时使用新配置。模块启用时立即检查在线玩家，禁用后停止周期检查并关闭菜单。奖励称号依赖 `titles` 模块与有效称号定义；模块管理 GUI 在签到或成就仍有称号奖励时拒绝关闭称号模块。候选快照任一配置错误时旧成就继续运行。
