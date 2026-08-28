# OmniTools 完整使用手册

本手册面向服主和配置人员，按“安装、启用、配置、验证、运维”的顺序组织内容。它是导航和快速总结，不替代原始模块文档；涉及字段边界时，以文末链接的 canonical 文档和运行时校验为准。

OmniTools 是 Fabric `1.21.11` 的纯服务端模组，玩家使用原版客户端即可连接，所有菜单均为原版箱子界面。

## 目录

1. [项目简介与版本要求](#1-项目简介与版本要求)
2. [五分钟完成首次配置](#2-五分钟完成首次配置)
3. [配置文件结构](#3-配置文件结构)
4. [模块总览](#4-模块总览)
5. [统一配置平台](#5-统一配置平台)
6. [每日签到模块](#6-每日签到模块)
7. [在线奖励模块](#7-在线奖励模块)
8. [成就模块](#8-成就模块)
9. [CDK 与补签卡](#9-cdk-与补签卡)
10. [奖励系统](#10-奖励系统)
11. [商店与货币](#11-商店与货币)
12. [称号与称号效果](#12-称号与称号效果)
13. [礼包模块](#13-礼包模块)
14. [排行榜模块](#14-排行榜模块)
15. [侧边栏模块](#15-侧边栏模块)
16. [命令菜单模块](#16-命令菜单模块)
17. [权限配置](#17-权限配置)
18. [云存储](#18-云存储)
19. [占位符](#19-占位符)
20. [GUI 使用规范](#20-gui-使用规范)
21. [数据保存、备份与恢复](#21-数据保存备份与恢复)
22. [热重载与模块管理](#22-热重载与模块管理)
23. [故障排查](#23-故障排查)
24. [配置示例索引](#24-配置示例索引)
25. [Schema 与编辑器支持](#25-schema-与编辑器支持)
26. [版本升级说明](#26-版本升级说明)
27. [已实现功能、缺口与规划](#27-已实现功能缺口与规划)
28. [原始文档对照表](#28-原始文档对照表)

## 1. 项目简介与版本要求

| 项目 | 要求 |
| --- | --- |
| Minecraft | `1.21.11` |
| Fabric Loader | `0.19.3` 或更高 |
| Fabric API | 与服务端版本匹配 |
| Java | `21` |
| 客户端 | 不需要安装 OmniTools，原版客户端即可 |

功能包括签到、在线奖励、成就、CDK、商店与货币、称号、礼包、排行榜、侧边栏、命令菜单、权限和云存储。安装入口见 [README.md](../README.md)。

## 2. 五分钟完成首次配置

1. 将 OmniTools 与 Fabric API 放入服务端 `mods/`。
2. 用 Java 21 启动一次服务端，等待 `config/omnitools/` 生成。
3. 备份 `config/omnitools/` 和世界 `data/`。
4. 编辑根配置 `config/omnitools/config.json`。新服建议保留指令奖励关闭，并只填写需要允许的命令根。
5. 编辑要使用的模块文件。礼包、排行榜、权限默认关闭，其余模块默认开启；按需调整。
6. 以管理员身份执行 `/omnitools reload`。
7. 用 `/omnitools diagnose` 检查模块状态、Placeholder API、命令白名单、未处理奖励和侧边栏状态。

最小根配置（严格 JSON，可直接使用）如下：

```json
{
  "format_version": 4,
  "global": {
    "debug": false,
    "timezone": "Asia/Shanghai",
    "language": "zh_cn",
    "data_retention": "full",
    "command_security": {
      "allowed_roots": ["spawn"],
      "max_command_length": 1024,
      "cooldown_ticks": 10
    },
    "reward_security": {
      "allow_command_rewards": false,
      "max_command_length": 1024
    }
  },
  "integrations": { "placeholder_api": { "enabled": true } },
  "modules": {
    "daily_checkin": { "enabled": true },
    "cdk": { "enabled": false },
    "online_reward": { "enabled": true },
    "shop": { "enabled": true },
    "titles": { "enabled": true },
    "title_effects": { "enabled": true },
    "achievements": { "enabled": true },
    "cloud_storage": { "enabled": true },
    "permissions": { "enabled": false },
    "command_menu": { "enabled": true },
    "sidebar": { "enabled": true },
    "leaderboards": { "enabled": false },
    "packages": { "enabled": false }
  }
}
```

严格 JSON 不能含注释、尾逗号或单引号；教学文件的 `.jsonc` 只能作为参考。完整字段见 [根配置参考](reference/root-config.md) 和 [第一次配置](getting-started/first-setup.md)。

## 3. 配置文件结构

```text
config/omnitools/
  config.json
  common/rewards.json
  common/conditions.json
  common/texts.json
  daily_checkin/config.json
  online_reward/config.json
  achievements/config.json
  cdk/config.json
  shop/config.json
  titles/config.json
  title_effects/config.json
  packages/config.json
  leaderboards/config.json
  sidebar/config.json
  command_menu/config.json
  command_menu/menus/*.json
  permissions/config.json
  cloud_storage/config.json
```

根配置决定开关和安全边界；模块文件决定业务定义；世界 `data/` 保存玩家数据、奖励账本、货币、礼包实例等。公共文件变更必须使用完整重载。

## 4. 模块总览

| 模块 | 根开关 | 默认 | 配置文件 |
| --- | --- | --- | --- |
| 每日签到 | `daily_checkin` | 开 | `daily_checkin/config.json` |
| 在线奖励 | `online_reward` | 开 | `online_reward/config.json` |
| 成就 | `achievements` | 开 | `achievements/config.json` |
| CDK | `cdk` | 开 | `cdk/config.json` |
| 商店与货币 | `shop` | 开 | `shop/config.json` |
| 称号 | `titles` | 开 | `titles/config.json` |
| 称号效果 | `title_effects` | 开 | `title_effects/config.json` |
| 礼包 | `packages` | 关 | `packages/config.json` |
| 排行榜 | `leaderboards` | 关 | `leaderboards/config.json` |
| 侧边栏 | `sidebar` | 开 | `sidebar/config.json` |
| 命令菜单 | `command_menu` | 开 | `command_menu/config.json` 和 `menus/` |
| 权限 | `permissions` | 关 | `permissions/config.json` |
| 云存储 | `cloud_storage` | 开 | `cloud_storage/config.json` |

`title_effects` 依赖 `titles`；奖励中的 `title`、`package`、`makeup_card` 还必须满足对应模块的跨模块校验。

## 5. 统一配置平台

所有完整重载都先读取根配置和 `common/`，再加载已启用模块，最后执行跨模块校验；任一错误都会保留上一份有效快照。单模块重载只替换目标模块，但仍执行完整校验。

- 完整：`/omnitools reload`
- 单模块：`/omnitools reload <module-id>`
- 模块管理 GUI：`/omnitools modules`
- 诊断：`/omnitools diagnose`

公共文件位于 `common/rewards.json`、`common/conditions.json` 和 `common/texts.json`。奖励库 V2 使用 `rewards` 和 `sets`，支持奖励/集合引用、集合嵌套、未知引用和循环检测；展开重复 ID 或超过 16 层会拒绝重载。V1 的 `templates`、`template` 与 `$ref` 仍兼容，模板引用的旧字段覆盖行为保持不变。详情见[统一奖励](reference/rewards.md)。

## 6. 每日签到模块

**用途与场景**：`/checkin` 或 `/omnitools` 打开 6 行日历手账 GUI，支持每日奖励、月度里程碑、连续签到、补签卡和奖励箱。

**默认开关与依赖**：`modules.daily_checkin.enabled` 默认开启。称号奖励要求 `titles` 和对应称号存在；指令奖励要求根安全开关与白名单。

**路径与重载**：`config/omnitools/daily_checkin/config.json`，格式版本 `3`；修改后执行 `/omnitools reload`。

**最小配置**：

```json
{
  "format_version": 3,
  "daily": { "rewards": [{ "id": "daily_coins", "type": "currency", "amount": 100 }] },
  "monthly": { "7": [{ "id": "week_item", "type": "item", "item": "minecraft:bread", "count": 4 }] },
  "makeup": { "enabled": true, "max_cards": 99, "max_backfill_days": 7, "max_uses_per_calendar_month": 3 }
}
```

**字段重点**：`daily.rewards`、`monthly.<天数>`、`makeup.enabled`、`max_cards`、`max_backfill_days`、`max_uses_per_calendar_month`、`daily_reward_policy`、`counts_for_monthly_milestones`；可选 `ui` 负责手账主题、图标和声音。奖励 ID 在同一事件中必须唯一。

**指令与权限**：`/checkin`、`/omnitools`（`PLAYER`）；`/omnitools checkin clear`（`ADMIN`）。

**占位符**：`checkin_today`、`checkin_today_rank`、`checkin_total_days`、`checkin_streak_days`、`checkin_month_days`。

**联动、数据与重载**：奖励、签到记录、月度进度和补签卡保存在世界 SavedData；配置重载不重置数据。补签卡是虚拟权益，不生成实体物品。

**常见错误**：日期键必须为正整数；称号 ID 必须已定义；指令奖励需要 `allow_command_rewards` 和 `allowed_roots`；奖励未进背包时打开 `/omnitools rewards open`。

**高级示例与原始文档**：[daily-checkin.jsonc](examples/config-platform/daily-checkin.jsonc)、[daily-checkin.schema.json](schemas/daily-checkin.schema.json)、[每日签到原文](modules/daily-checkin.md)。

## 7. 在线奖励模块

**用途与场景**：按服务器时区累计自然日在线分钟数，玩家在奖励箱领取达成的里程碑。

**默认开关与依赖**：`modules.online_reward.enabled` 默认开启；奖励类型的依赖遵循[统一奖励](reference/rewards.md)。

**路径与重载**：`config/omnitools/online_reward/config.json`，格式版本 `1`；执行 `/omnitools reload online_reward` 或完整重载。

**最小配置**：

```json
{
  "format_version": 1,
  "rewards": [{ "id": "online_30m", "minutes": 30, "rewards": [{ "id": "coins", "type": "currency", "amount": 50 }] }]
}
```

**字段重点**：`rewards[]` 按 `minutes` 升序排列；里程碑 `id` 稳定且不可随意复用，内部 `rewards` 为统一奖励数组。

**指令与权限**：`/omnitools online`（`PLAYER`）；`/omnitools rewards open`（`PLAYER`）。

**占位符**：`online_today_seconds`、`online_today_minutes`、`online_today_hms`。

**联动、数据与重载**：在线秒数和里程碑账本保存在 SavedData；同一里程碑事件只处理一次，失败投递进入奖励箱。

**常见错误**：分钟未升序、奖励 ID 重复、临时称号未启用 `titles`。修改配置后必须重载，失败会保留旧快照。

**高级示例与原始文档**：[online-reward.jsonc](examples/config-platform/online-reward.jsonc)、[online-reward.schema.json](schemas/online-reward.schema.json)、[在线奖励原文](modules/online-reward.md)。

## 8. 成就模块

**用途与场景**：根据原版统计和 `custom` 统计追踪进度，玩家在 GUI 领取解锁奖励。

**默认开关与依赖**：`modules.achievements.enabled` 默认开启；称号奖励依赖 `titles`，指令奖励依赖根命令安全。

**路径与重载**：`config/omnitools/achievements/config.json`，格式版本 `2`（兼容 v1）；执行 `/omnitools reload achievements`。

**最小配置**：

```json
{
  "format_version": 2,
  "achievements": [{
    "id": "starter_stone",
    "display": "初次挖掘",
    "description": "挖掘 1 个石头",
    "icon": "minecraft:stone",
    "requirements": { "type": "stat", "stat": "block_mined", "targets": ["minecraft:stone"], "match": "sum", "at_least": 1 },
    "rewards": [{ "id": "starter_coins", "type": "currency", "amount": 10 }]
  }]
}
```

**字段重点**：`id`、`display`、`description`、`icon`、`requirements`、`rewards`；条件支持 `stat`、`sum`、`all`、`any`、`not`，最多 8 层，必须有正向统计来源。

**指令与权限**：`/omnitools achievements`（`PLAYER`）；管理员发放/结算动作使用 `rewards.admin`（`ADMIN`）。

**占位符**：`achievements_unlocked`、`achievements_claimed`、`achievements_total`。

**联动、数据与重载**：解锁状态和奖励账本保存在 SavedData；排行榜可选关联成就 ID，但不读取成就进度生成统计。

**常见错误**：统计单位、目标 ID、条件深度或称号/命令依赖不满足会阻止重载；原版没有精确“方块放置数”，`item_used` 只能近似。

**高级示例与原始文档**：[成就示例索引](examples/achievement-examples/README.md)、[成就预设](presets/achievements/README.md)、[achievements.schema.json](schemas/achievements.schema.json)、[成就原文](modules/achievements.md)。

## 9. CDK 与补签卡

**用途与场景**：活动码、开服礼、补偿和虚拟补签卡发放；玩家通过命令兑换，不使用客户端 GUI。

**默认开关与依赖**：`modules.cdk.enabled` 默认开启；关闭只停止新兑换，既有兑换记录和待投递奖励保留。

**路径与重载**：`config/omnitools/cdk/config.json`，格式版本 `1`；CDK 文件可单独 `/omnitools reload cdk`，公共模板或根配置变更必须完整重载。

**最小配置**：

```json
{
  "format_version": 1,
  "security": { "max_code_length": 64, "cooldown_ticks": 20, "max_failed_attempts": 5, "lockout_seconds": 60 },
  "campaigns": [{ "id": "welcome_2026", "code": "OMNI-2026-WELCOME", "max_uses": 0, "rewards": [{ "id": "welcome_cards", "type": "makeup_card", "amount": 2 }] }]
}
```

**字段重点**：活动 `id`、`code`、`starts_at`、`expires_at`、`max_uses`、`rewards`；安全项限制码长度、冷却、失败次数和锁定时间。活动 ID、兑换码、奖励和有效期在已有兑换后受指纹保护。

**指令与权限**：`/omnitools cdk redeem <code>`、`status`（`cdk.redeem`，`PLAYER`）；`/omnitools cdk admin list|audit <id>`（`cdk.admin`，`ADMIN`）。

**占位符**：使用统一文本占位符，无 CDK 专属占位符。

**联动、数据与重载**：兑换事件、次数和奖励账本保存在世界数据；`makeup_card` 写入签到模块虚拟权益。每名玩家按 UUID 对同一活动只能兑换一次。

**常见错误**：活动时间、次数或奖励引用错误；不要在日志中寻找原始码，系统只保存规范化码的 SHA-256 哈希。

**高级示例与原始文档**：[cdk.jsonc](examples/config-platform/cdk.jsonc)、[cdk.schema.json](schemas/cdk.schema.json)、[CDK 原文](modules/cdk.md)。

## 10. 奖励系统

**用途与范围**：每日签到、在线奖励、成就和 CDK 共用同一 `RewardDefinition` 与奖励账本。当前支持：

| 类型 | 必填字段 | 已实现行为 |
| --- | --- | --- |
| `currency` | `id`、`amount` | 增加共享货币 |
| `item` | `id`、`item`/`nbt` | 物品进入背包或奖励箱 |
| `title` | `id`、`title` | 永久或限时称号 |
| `command` | `id`、`run_as`、`command` | 仅受控控制台命令 |
| `makeup_card` | `id`、`amount` | 增加虚拟补签卡 |
| `package` | `id`、`package` | 创建虚拟礼包实例 |

奖励 ID 必须在同一事件内唯一，格式为 1--64 位小写字母、数字、`_`、`.` 或 `-`。已上线 ID 是永久业务键，不要改作另一种类型或含义；应新增 ID。

**物品写法**：普通物品使用 `item`、`count`、`components`；复杂组件使用完整 ItemStack `nbt`，二者不能混写。SNBT 至少有 `id`，源文本和持久化快照受 32 KiB 限制。

```json
{ "id": "named_bread", "type": "item", "nbt": "{id:'minecraft:bread',count:8,components:{'minecraft:custom_name':'{\"text\":\"每日面包\"}'}}" }
```

**称号**：没有 `duration` 为永久；`duration.mode: "active_days"` 只在在线且佩戴时消耗时间，`renewal` 可为 `extend`、`replace` 或 `max`。

**礼包奖励**：服务端按 `eventId + rewardId` 生成 `grantKey`，奖励账本在 `APPLYING` 恢复时查询并复用既有实例。礼包模式、快照、预览、投递和恢复见[礼包模块](modules/packages.md)。

**幂等与故障**：账本状态为 `PENDING -> APPLYING -> GRANTED`；不确定的物品投递进入奖励箱或保守隔离，不通过删除账本记录解决问题。玩家使用 `/omnitools rewards open`，管理员使用 `/omnitools rewards inspect <player> [event]`、`retry`、`resolve <player> <event> grant|fail`。

**奖励库**：`common/rewards.json` 的 V2 `rewards` 键是稳定奖励 ID，`sets` 可嵌套组合；业务奖励数组使用 `{ "reward": "..." }` 或 `{ "set": "..." }`。调用处不能覆盖目录定义。V1 `templates`、`template`、`$ref` 继续可用。

原始文档与约束：[统一奖励](reference/rewards.md)、[奖励一致性与奖励箱](guides/reward-consistency.md)、[奖励示例](examples/reward-examples/README.md)、[common-rewards.schema.json](schemas/common-rewards.schema.json)。

## 11. 商店与货币

**用途与场景**：原版箱子商店出售物品或礼包，货币由签到、在线奖励、成就和管理员命令共享。

**默认开关与依赖**：`modules.shop.enabled` 默认开启；货币数据不因模块关闭删除。

**路径与重载**：`config/omnitools/shop/config.json`，格式版本 `1`；执行 `/omnitools reload`。

**最小配置**：

```json
{ "format_version": 1, "products": [{ "index": 0, "item": "minecraft:diamond", "count": 1, "price": 20 }] }
```

**字段重点**：`products[].index`（0 起且不重复）、`type`、`item`/`package`、`count`、`components`/`nbt`、`price`。`type: "package"` 只能填写礼包 ID 和价格，不能混用物品字段。

**指令与权限**：`/omnitools shop`（`shop.open`，`PLAYER`）；`/omnitools balance` 查自己（`PLAYER`），查他人及加减货币（`ADMIN`）。

**占位符**：`balance`、`balance_formatted`。

**联动、数据与重载**：余额、购买事务和礼包快照保存在 SavedData。礼包商品必须同时启用 `shop` 与 `packages`。购买使用 `PREPARED -> CHARGED -> PACKAGE_CREATED -> COMPLETED` 状态机和 `shop:<transactionId>#package` 稳定键；启动只恢复有证明的步骤，不确定结果进入 `BLOCKED`。

**常见错误**：商品索引越界、价格为负、组件/SNBT 混写、礼包 ID 不存在或余额/礼包容量不足；阻塞购买使用 `/omnitools shop audit`，不会自动退款或重发。

**高级示例与原始文档**：[shop.jsonc](examples/config-platform/shop.jsonc)、[shop.schema.json](schemas/shop.schema.json)、[商店与货币原文](modules/shop-and-currency.md)。

## 12. 称号与称号效果

### 称号

**用途与场景**：定义可佩戴的展示称号，可关联称号效果。

**默认开关与依赖**：`modules.titles.enabled` 默认开启；`title_effects` 关闭时称号仍可佩戴但无效果。

**路径与重载**：`config/omnitools/titles/config.json`，格式版本 `1`；执行 `/omnitools reload`。

**最小配置**：

```json
{ "format_version": 1, "nameplate_mode": "scoreboard_team", "team_conflict_policy": "preserve_external_team", "titles": [{ "id": "vip", "display": "&6[VIP]&r ", "rarity": "legendary", "effects": [], "tooltip": ["&eVIP 称号"] }] }
```

**字段重点**：`id`、`display`、`rarity`、`effects`、`tooltip`，以及计分板队伍冲突策略。

**指令与权限**：`/omnitools title|titles`、`/titles`、`titles time|select|clear`（`omnitools.title.open`，`PLAYER`）；管理员 `titles admin grant|revoke`（对应 `ADMIN`）。

**占位符**：`title_id`、`title`、`title_plain`、`title_effects_enabled`、`title_remaining_days`、`title_remaining_hours`、`title_remaining_hms`、`title_is_temporary`、`title_is_equipped`。

**联动、数据与重载**：称号授权、佩戴状态和剩余时长保存于 SavedData；限时称号只在佩戴时计时。奖励中的 `title` 必须引用已存在称号。

**常见错误**：效果 ID 不存在、队伍策略与外部队伍冲突、临时称号时长格式错误。

**原始文档**：[titles.jsonc](examples/config-platform/titles.jsonc)、[titles.schema.json](schemas/titles.schema.json)、[称号原文](modules/titles.md)。

### 称号效果

**用途与场景**：为称号提供药水、属性、粒子或权限效果。

**默认开关与依赖**：`modules.title_effects.enabled` 默认开启且依赖 `titles`。

**路径与重载**：`config/omnitools/title_effects/config.json`，格式版本 `1`；执行 `/omnitools reload title_effects`。

**最小配置**：

```json
{ "format_version": 1, "effects": { "speed_1": { "name": "速度 I", "type": "POTION", "effect": "minecraft:speed", "amplifier": 0, "duration": -1, "display": "&a速度 I" } } }
```

**字段重点**：效果 ID、`type`（`POTION`/`ATTRIBUTE`/`PARTICLE`/`PERMISSION`）及类型专属字段；权限效果仍受安全白名单限制。

**指令与权限、占位符**：无专属玩家指令或占位符；称号选择使用称号模块权限。

**联动、数据与重载、排错**：效果定义不保存玩家数据，佩戴状态由称号模块保存；未知效果 ID、药水/粒子/属性 ID 会拒绝重载。

**原始文档**：[title-effects.jsonc](examples/config-platform/title-effects.jsonc)、[title-effects.schema.json](schemas/title-effects.schema.json)、[称号效果原文](modules/title-effects.md)。

## 13. 礼包模块

**用途与场景**：服务端持有的虚拟礼包实例，适合新手、活动、成就和 CDK 奖励；不会生成可丢弃的实体礼包物品。

**默认开关与依赖**：`modules.packages.enabled` 默认关闭；使用 `type: "package"` 的奖励或商店联动时必须开启。

**路径与重载**：`config/omnitools/packages/config.json`，当前格式版本 `2`（兼容并迁移 V1）；启用后执行 `/omnitools reload packages`，根配置或公共文件变更使用完整重载。

**最小配置**：

```json
{
  "format_version": 2,
  "settings": { "max_pending_packages_per_player": 256, "max_quantity_per_entry": 2304, "max_total_quantity": 589824, "max_delivery_stacks_per_package": 216, "delivery_stacks_per_tick": 4, "history_retention_days": 90, "delivery_policy": "inventory_then_inbox", "random_strategy": "uniform" },
  "packages": [{ "id": "starter", "display": "&a新手礼包", "description": ["&7打开后获得面包"], "icon": "minecraft:chest", "mode": "all", "version": 1, "items": [{ "id": "bread", "item": "minecraft:bread", "count": 1, "quantity": 16 }] }]
}
```

**字段重点**：定义 ID、`display`、`description`、`icon`、`mode`（`all`/`random_one`）、`version`、条目 `item`/`nbt` 和 `quantity`。单条数量、礼包总量、条目数和 SNBT 大小均有限制；礼包禁止嵌套。

**指令与权限**：玩家使用 `/omnitools packages` 或 `/omnitools package open`（`package.open`，`PLAYER`）；管理员使用 `give`、`list`、`inspect`、`resolve`、`cancel`、`remove`（分别需要对应 `package.*` 的 `ADMIN` 权限）。`resolve` 必须明确指定堆 UUID 和 `delivered|pending confirm`，不存在一键重试；`resolve`、`cancel`、`remove` 会写入 `config/omnitools/package-audit.log`。

**占位符**：当前没有礼包专属占位符，可使用通用文本占位符渲染名称和描述。

**联动、数据与重载**：实例、逻辑投递批次和物品快照保存在世界 SavedData；配置重载不会修改旧实例。奖励通过 `grantKey = eventId + "#" + rewardId` 幂等创建；`all` 全部投递，`random_one` 服务端均匀选择一项并持久化。每 tick 按预算投递，背包不足进入奖励箱，不确定状态的堆保守隔离为 `BLOCKED`。

**常见错误**：模块未启用、礼包 ID/物品 ID 无效、数量超限、NBT 与简单写法混用、玩家试图打开他人实例。超大礼包应先在测试服验证拆堆和恢复。

**高级示例与原始文档**：[packages.jsonc](examples/config-platform/packages.jsonc)、[packages.schema.json](schemas/packages.schema.json)、[礼包原文](modules/packages.md)。

## 14. 排行榜模块

**用途与场景**：按原版统计生成可缓存榜单，支持挖掘、物品使用、实体击杀和 `custom` 统计；不修改原版统计。

**默认开关与依赖**：`modules.leaderboards.enabled` 默认关闭；侧边栏排行榜页依赖它。

**路径与重载**：`config/omnitools/leaderboards/config.json`，格式版本 `1`；执行 `/omnitools reload leaderboards`。

**最小配置**：

```json
{ "format_version": 1, "leaderboards": [{ "id": "mine_stone", "display": "&b石材矿工", "icon": "minecraft:stone", "stat": { "type": "block_mined", "targets": ["minecraft:stone"], "aggregation": "sum", "unit": "count" } }] }
```

**字段重点**：刷新间隔、是否扫描离线玩家、零分过滤、每 tick 扫描文件数、目标组、榜单统计类型/目标/聚合方式；最多 128 个榜单。

**指令与权限**：`/omnitools leaderboard`、`open <id>`、`list`（`leaderboards.open`，`PLAYER`）；`chat <id> [page]`、`/top`（`leaderboards.chat`，`PLAYER`）。

**占位符**：无专属占位符；侧边栏榜单行使用其自身格式字段。

**联动、数据与重载**：榜单快照在内存，玩家统计在世界 `stats/`；重载成功后后台重新扫描，失败保留旧快照。

**常见错误**：统计目标、目标组或关联成就 ID 不存在；侧边栏页面需同时启用排行榜模块。

**原始文档**：[leaderboards.jsonc](examples/config-platform/leaderboards.jsonc)、[leaderboards.schema.json](schemas/leaderboards.schema.json)、[排行榜原文](modules/leaderboards.md)。

## 15. 侧边栏模块

**用途与场景**：使用原版计分板展示文本页或排行榜快照。

**默认开关与依赖**：`modules.sidebar.enabled` 默认开启；Placeholder API 可选，排行榜页依赖排行榜模块。

**路径与重载**：`config/omnitools/sidebar/config.json`，当前格式版本 `3`（v1/v2 自动兼容）；执行 `/omnitools reload sidebar`。

**最小配置**：

```json
{ "format_version": 3, "default_visible": true, "refresh_interval_ticks": 20, "conflict_policy": "skip", "presentation": { "mode": "fixed", "fixed_page": "main", "rotation_ticks": 200, "page_ids": ["main"] }, "pages": [{ "id": "main", "type": "text", "title": "&b&lOmniTools", "lines": [{ "id": "money", "text": "&e货币: &f%balance_formatted%" }] }] }
```

**字段重点**：刷新间隔、冲突策略（`skip`/`replace`/`restore`）、固定/轮播页面、页面类型、最多 15 行和排行榜行格式。

**指令与权限**：`/omnitools sidebar toggle|status`（`PLAYER`）；单模块重载需管理员。

**占位符**：可使用内置和可选第三方文本占位符。

**联动、数据与重载**：显示偏好保存在 SavedData；排行榜页只读内存快照，排行榜关闭时静态页仍显示。

**常见错误**：页面 ID 重复或未知、行数超过 15、排行榜模块关闭、第三方侧边栏冲突策略不符合预期。

**原始文档**：[sidebar.jsonc](examples/config-platform/sidebar.jsonc)、[sidebar.schema.json](schemas/sidebar.schema.json)、[侧边栏原文](modules/sidebar.md)。

## 16. 命令菜单模块

**用途与场景**：纯服务端 27/54 格箱子菜单，支持子菜单、消息、玩家命令和受控控制台命令。

**默认开关与依赖**：`modules.command_menu.enabled` 默认开启；命令动作还受根白名单、长度和冷却限制。

**路径与重载**：注册表 `config/omnitools/command_menu/config.json`，页面 `command_menu/menus/*.json`；修改后执行 `/omnitools reload`。

**最小配置**：注册表和页面必须同时存在。

```json
{ "format_version": 1, "allow_console_commands": false, "menus": [{ "id": "main", "file": "main.json", "permission": "PLAYER" }] }
```

```json
{ "format_version": 1, "title": "&b服务菜单", "size": 27, "items": [{ "slot": 13, "item": "minecraft:compass", "name": "&a回主城", "left_click": [{ "type": "command", "run_as": "player", "command": "spawn" }] }] }
```

**字段重点**：菜单注册 `id`/`file`/`permission`；页面 `size`、`slot`、显示物品、`left_click`/`right_click`。动作类型为 `open_menu`、`close_menu`、`command`、`message`，每侧最多 8 个动作。

**指令与权限**：`/omnitools menu <id>`（`command_menu.open`，`PLAYER`）。

**占位符**：标题、名称、Lore、消息可用文本占位符；命令只能用 `{player_name}`、`{player_uuid}`、`{player_x}`、`{player_y}`、`{player_z}`、`{player_world}`。

**联动、数据与重载**：菜单配置不保存玩家数据；热重载会关闭失效菜单。

**常见错误**：页面文件路径穿越、槽位越界、控制台动作未显式允许、命令根未列入白名单。

**原始文档**：[command-menu.jsonc](examples/config-platform/command-menu.jsonc)、[command-menu.schema.json](schemas/command-menu.schema.json)、[命令菜单原文](modules/command-menu.md)。

## 17. 权限配置

**用途与场景**：将 OmniTools 动作映射到原生命令等级：`PLAYER`=0、`MODERATOR`=1、`ADMIN`=2、`OWNER`=4。

**默认开关与依赖**：`modules.permissions.enabled` 默认关闭；关闭不会删除内置管理员绕过和默认角色。

**路径与重载**：`config/omnitools/permissions/config.json`，格式版本 `1`；执行 `/omnitools reload`。

**最小配置**：

```json
{ "format_version": 1, "allow_title_command_grants": false, "commands": { "checkin.open": "PLAYER", "shop.open": "PLAYER", "config.reload": "ADMIN" } }
```

**字段重点**：`allow_title_command_grants` 和 `commands` 动作映射；除 `storage.open` 外动作值使用角色字符串，未写动作使用代码默认角色。

**指令与权限**：配置本身无额外玩家指令；`/omnitools reload`、`diagnose`、奖励账本管理默认 `ADMIN`。

**占位符**：无专属占位符。

**联动、数据与重载**：即时影响命令授权，不重置任何 SavedData。修改管理员权限前保留 Owner 账号。

**常见错误**：使用 `OP` 等非支持角色、把对象写法用于非 `storage.open` 动作、误以为关闭模块会移除原生命令权限。

**原始文档**：[permissions.jsonc](examples/config-platform/permissions.jsonc)、[permissions.schema.json](schemas/permissions.schema.json)、[权限原文](modules/permissions.md)。

## 18. 云存储

**用途与场景**：每名玩家拥有原版 6 行箱子，默认一页，可用货币扩容至第二页。

**默认开关与依赖**：`modules.cloud_storage.enabled` 默认开启；权限受 `storage.open` 控制。

**路径与重载**：`config/omnitools/cloud_storage/config.json`，格式版本 `1`；执行 `/omnitools reload`。

**最小配置**：

```json
{ "format_version": 1, "expansionCost": 100, "maxPages": 2 }
```

**字段重点**：`expansionCost` 非负；`maxPages` 只能为 `1` 或 `2`。第一页固定存在，没有 `default_pages` 字段。

**指令与权限**：`/omnitools storage` 默认 `ADMIN`；权限文件可将 `storage.open` 授予其他角色。

**占位符**：无独立占位符；侧边栏可显示 `%balance%`。

**联动、数据与重载**：物品与解锁页数保存在世界 SavedData；禁用模块不删除物品，重启后继续保留。

**常见错误**：扩容价格为负、页数超范围、权限不足；修改后检查 `/omnitools diagnose`。

**原始文档**：[cloud-storage.jsonc](examples/config-platform/cloud-storage.jsonc)、[cloud-storage.schema.json](schemas/cloud-storage.schema.json)、[云存储原文](modules/cloud-storage.md)。

## 19. 占位符

渲染顺序是 OmniTools 内置占位符、可选 Placeholder API、颜色格式。标准写法为 `%omnitools:balance%`；侧边栏兼容 `%balance%`。

| 占位符 | 用途 | 支持位置 | 依赖 |
| --- | --- | --- | --- |
| 玩家名称 | 显示当前玩家名 | GUI、侧边栏、文本 | 可选 Text Placeholder API（如 `%player:name%`） |
| 玩家坐标 | 显示当前坐标 | 侧边栏、文本 | 可选 Text Placeholder API（如 `%player:pos_x%`、`%player:pos_y%`、`%player:pos_z%`） |
| `balance`、`balance_formatted` | 货币余额 | GUI、侧边栏、文本 | 内置/商店数据 |
| `checkin_today`、`checkin_today_rank` | 今日签到状态和序号 | 签到 GUI、文本 | 每日签到 |
| `checkin_total_days`、`checkin_streak_days`、`checkin_month_days` | 累计、连续、本月签到 | 签到 GUI、侧边栏、文本 | 每日签到 |
| `online_today_seconds`、`online_today_minutes`、`online_today_hms` | 当日在线时长 | 在线 GUI、侧边栏、文本 | 在线奖励 |
| `title_id`、`title`、`title_plain` | 当前称号 | GUI、侧边栏、文本 | 称号 |
| `title_effects_enabled` | 称号效果开关状态 | GUI、文本 | 称号/效果 |
| `title_remaining_days`、`title_remaining_hours`、`title_remaining_hms` | 临时称号剩余时间 | GUI、文本 | 称号 |
| `title_is_temporary`、`title_is_equipped` | 临时/佩戴布尔值 | GUI、文本 | 称号 |
| `achievements_unlocked`、`achievements_claimed`、`achievements_total` | 成就统计 | 成就 GUI、文本 | 成就 |
| Text Placeholder API 占位符 | 第三方文本变量 | 支持的玩家可见文本位置 | 可选依赖 |

完整内置列表、回退值和一次性警告见[内置占位符参考](reference/placeholders.md)。可选 Text Placeholder API `2.8.2+1.21.10` 支持 `%namespace:path%` 语法，安装、开关和第三方变量见[Placeholder API 参考](reference/placeholder-api.md)。未知占位符显示 `-`。

文本占位符只用于玩家可见文本；菜单或奖励控制台命令只能使用受控 `{player_*}` 变量。

## 20. GUI 使用规范

- 所有 GUI 由服务端创建和校验，客户端不能提交奖励内容、随机结果或他人实例 UUID。
- 箱子 GUI 的装饰槽和玩家物品槽由服务端拦截；只在明确的奖励详情、确认或奖励箱入口领取物品。
- 礼包预览不产生随机结果，确认后才由服务端选择并持久化。
- 奖励箱用于背包不足或暂时不可投递的物品；不要通过重复点击尝试“补发”。
- 关闭、分页和刷新按钮均应按当前模块文档使用；热重载失败时旧快照继续服务。

## 21. 数据保存、备份与恢复

备份至少包括：

- `config/omnitools/` 全目录；
- 世界目录 `data/`（SavedData、奖励账本、货币、礼包、称号等）；
- 世界 `stats/`（离线排行榜需要）；
- 生产服升级前的完整世界目录。

恢复流程：停止服务端，恢复同一批配置与世界数据，再启动并执行 `/omnitools diagnose`。不要删除 SavedData 或账本来“重置”奖励；先用 `/omnitools rewards inspect` 定位事件，再按[奖励一致性指南](guides/reward-consistency.md)重试或人工结案。礼包实例删除属于不可逆操作，先备份并核对 UUID。

## 22. 热重载与模块管理

修改根配置或 `common/`：

```text
/omnitools reload
```

只修改模块文件：

```text
/omnitools reload <module-id>
```

重载采用候选快照，失败时不发布半份配置。禁用模块会关闭相关 GUI、任务、侧边栏或称号效果，但保留玩家数据；重新启用后从当前配置恢复。完整行为见[模块管理与热重载](guides/module-management.md)和[统一配置平台](config-platform.md)。

## 23. 故障排查

| 现象 | 处理 |
| --- | --- |
| 修改不生效 | 确认写入正确路径，删除注释和尾逗号，执行对应 reload。 |
| 重载失败 | 查看控制台第一条 `[omnitools]` 错误；旧快照会继续运行。 |
| 指令奖励/菜单命令不执行 | 检查 `allow_command_rewards`、`allowed_roots`、菜单控制台开关和命令冷却。 |
| 占位符显示 `-` | 检查拼写、模块开关；第三方变量需安装并启用 Placeholder API。 |
| GUI 打不开 | 检查根开关、权限动作和模块依赖，使用 `/omnitools diagnose`。 |
| 物品未进背包 | 执行 `/omnitools rewards open`；不要重复领取或删除账本。 |
| 礼包被隔离 | 备份世界 `data/`，用礼包 inspect 和日志核对快照，勿自动重试不确定堆。 |
| CDK 无效 | 检查活动时间、次数和玩家兑换记录；原始码不会写入日志。 |

## 24. 配置示例索引

所有 `docs/examples/config-platform/*.jsonc` 是教学副本，删除注释后才能写入 `config/`。目标路径、格式版本、前置开关和重载命令见[配置平台示例目录](examples/config-platform/README.md)。

- [最小服务器](examples/minimal-server/README.md)
- [奖励示例](examples/reward-examples/README.md)
- [成就条件示例](examples/achievement-examples/README.md)
- [成就预设](presets/achievements/README.md)

## 25. Schema 与编辑器支持

`docs/schemas/` 提供根配置、每个模块、公共文件和通用模块配置的 JSON Schema，可用于编辑器补全和静态校验。Schema 不是运行时最终校验；运行时仍会检查跨模块依赖、权限、命令安全、物品 Codec、统计目标和数据边界。

常用 Schema： [root-config](schemas/root-config.schema.json)、[common-rewards](schemas/common-rewards.schema.json)、[daily-checkin](schemas/daily-checkin.schema.json)、[online-reward](schemas/online-reward.schema.json)、[achievements](schemas/achievements.schema.json)、[cdk](schemas/cdk.schema.json)、[shop](schemas/shop.schema.json)、[packages](schemas/packages.schema.json)。完整列表见[文档首页](index.md)。

## 26. 版本升级说明

升级前先备份配置和世界数据。根配置会迁移到 `format_version: 4` 并创建备份；缺失的 CDK、排行榜和礼包模块不会因升级自动开启。每日签到推荐 v3，成就推荐 v2，侧边栏推荐 v3。

旧签到字段、旧在线奖励字段和旧公共模板保留兼容读取；迁移后应改写为推荐格式。不要修改已产生账本的奖励 ID、CDK 活动 ID、礼包 ID、成就 ID、称号 ID 或榜单 ID 的语义。详细迁移规则见[升级指南](guides/upgrade-guide.md)。

## 27. 已实现功能、缺口与规划

**已实现**：服务端原版箱子 GUI；签到、在线奖励、成就、CDK、商店物品与礼包商品、称号、称号效果、虚拟礼包、排行榜、侧边栏、命令菜单、权限和云存储；统一奖励账本、V2 奖励库、奖励箱、限时称号、礼包快照、NBT/组件物品、礼包逐堆审计和商店购买事务恢复。

**明确缺口**：按权重随机和保底、礼包专用占位符、实体礼包物品交易、商店自动退款/自动重发，以及更丰富的购买人工结案界面。当前版本不能把这些当作可用功能。

**规划中**：权重与保底随机、礼包钥匙、赛季礼包、任务/公会/世界活动礼包等。规划记录只在[archive](archive/)中维护，不作为当前行为依据。

## 28. 原始文档对照表

| 原始路径 | 用途 |
| --- | --- |
| [README.md](../README.md) | 项目简介、安装入口和版本要求 |
| [docs/index.md](index.md) | 现有文档导航 |
| [docs/config-platform.md](config-platform.md) | 统一配置、公共文件和重载语义 |
| [docs/modules/*.md](modules/) | 各模块详细说明、字段、命令和验收 |
| [docs/reference/*.md](reference/) | 根配置、奖励、占位符和 Placeholder API |
| [docs/guides/*.md](guides/) | 模块管理、升级、备份、奖励一致性和第三方集成 |
| [docs/examples/**/*.json(c)](examples/) | 可复制配置与教学副本 |
| [docs/schemas/*.json](schemas/) | 编辑器补全与格式约束 |
| [docs/presets/**/*.json](presets/) | 成就预设 |
| [docs/archive/**](archive/) | 历史方案、旧需求和规划，不代表当前功能 |
| [CHANGELOG.md](../CHANGELOG.md) | 版本变更记录 |

`docs/last-ai-change.json`、`docs/last-ai-response.txt` 等内部记录不属于用户教程，也不作为功能事实来源。
