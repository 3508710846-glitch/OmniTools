# 礼包

礼包模块提供服务端持有的虚拟礼包实例。礼包不是可丢弃、复制或篡改的物品；实例和其中的物品快照保存在世界存档中，玩家通过服务端 GUI 打开。该设计适合新手礼包、活动奖励、成就奖励和 CDK 奖励。

## 启用、路径与重载

根配置中的 `modules.packages.enabled` 默认是 `false`。模块首次启用并成功加载时会创建：

```text
config/omnitools/packages/config.json
```

将根开关设为 `true`，填写礼包定义后执行：

```text
/omnitools reload packages
```

修改根配置或公共模板时使用完整 `/omnitools reload`。重载会先解析、校验整份候选配置，失败时保留旧配置；已发放实例不会被重载改写。模块关闭后不能创建或打开新礼包，已有实例仍保留在存档中，重新启用后可继续处理。

可复制的教学副本见[`packages.jsonc`](../examples/config-platform/packages.jsonc)，编辑器约束见[`packages.schema.json`](../schemas/packages.schema.json)。

## 完整配置示例

下面是严格 JSON，可直接写入 `config/omnitools/packages/config.json`。示例中的 `nbt` 是完整 ItemStack SNBT 字符串；也可以使用 `item`、`count`、`components` 的简单写法。

```json
{
  "format_version": 1,
  "settings": {
    "max_packages_per_player": 256,
    "max_quantity_per_entry": 2304,
    "max_total_quantity": 589824,
    "delivery_policy": "inventory_then_inbox",
    "random_strategy": "uniform"
  },
  "packages": [
    {
      "id": "starter",
      "display": "&a新手礼包",
      "description": ["&7打开后获得全部新手物资"],
      "icon": "minecraft:chest",
      "mode": "all",
      "version": 1,
      "items": [
        { "id": "bread", "item": "minecraft:bread", "count": 1, "quantity": 16 },
        { "id": "iron", "item": "minecraft:iron_ingot", "count": 1, "quantity": 32 },
        {
          "id": "starter_sword",
          "nbt": "{id:'minecraft:iron_sword',count:1,components:{'minecraft:custom_name':'{\\\"text\\\":\\\"新手铁剑\\\"}'}}",
          "quantity": 1
        }
      ]
    },
    {
      "id": "random_material",
      "display": "&b随机材料礼包",
      "description": ["&7随机获得以下材料中的一种"],
      "icon": "minecraft:barrel",
      "mode": "random_one",
      "items": [
        { "id": "coal", "item": "minecraft:coal", "count": 1, "quantity": 128 },
        { "id": "iron", "item": "minecraft:iron_ingot", "count": 1, "quantity": 64 },
        { "id": "gold", "item": "minecraft:gold_ingot", "count": 1, "quantity": 32 }
      ]
    }
  ]
}
```

## 字段与限制

| 字段 | 类型与范围 | 说明 |
| --- | --- | --- |
| `format_version` | 必须为 `1` | 礼包配置格式版本。 |
| `settings.max_packages_per_player` | `1`--`4096`，默认 `256` | 每名玩家未打开实例的上限；达到上限时创建失败。 |
| `settings.max_quantity_per_entry` | `1`--`1,000,000`，默认 `2304` | 单个条目的业务数量上限。 |
| `settings.max_total_quantity` | `1`--`589824`，默认 `589824` | 单个礼包所有条目的数量总和上限。 |
| `settings.delivery_policy` | 目前仅 `inventory_then_inbox` | 先尝试背包，无法完整放入时保留待投递物品。 |
| `settings.random_strategy` | 目前仅 `uniform` | `random_one` 按条目等概率选择。 |
| `packages` | 最多 `128` 个定义 | 定义 ID 不得重复。 |
| `packages[].id` | `[a-z0-9_.-]{1,64}` | 发布后应视为稳定 ID，不要复用。 |
| `display` | 最长 `128` 个字符 | 缺省时使用 ID；创建实例时写入快照，礼包列表、预览和确认界面会通过文本模板渲染 `&` 颜色代码与现有占位符。 |
| `description` | 字符串数组，最多 `32` 行 | 创建实例时写入展示快照；礼包列表会作为 Lore 显示，后续配置重载不会改写已发放礼包。 |
| `icon` | 原版或已注册物品 ID | 无效物品会拒绝重载。 |
| `mode` | `all` / `random_one` | 缺省为 `all`。 |
| `version` | 正整数，缺省 `1` | 写入实例快照，用于审计和迁移识别。 |
| `items` | `1`--`256` 个条目 | 条目 ID 在礼包内不得重复。 |
| `items[].id` | `[a-z0-9_.-]{1,64}` | 礼包内稳定的条目标识；仅用于快照和审计。 |
| `items[].item` | 物品 ID | 与 `nbt` 二选一；可配合 `count`、`components`。 |
| `items[].count` | `1`--`64` | 物品原型的初始堆叠数，最终会规范化为 1。 |
| `items[].components` | 组件字符串或空对象 | 简单组件写法；不能与 `nbt` 同时使用。 |
| `items[].nbt` | 最长 32 KiB 的完整 SNBT | 完整 ItemStack 写法；不能与 `item`、`count`、`components` 同时使用。 |
| `items[].quantity` | `1`--`1,000,000`，且不超过 `settings.max_quantity_per_entry` | 业务数量可超过 64，发放时自动拆堆。 |

礼包级总量还受代码固定的服务器上限约束；即使调高配置，也不能超过 `589824`。物品原型解析、组件校验和持久化快照复用现有 32 KiB 限制。礼包不支持嵌套礼包。

## 物品写法与数量拆堆

简单物品写法：

```json
{
  "id": "bread",
  "item": "minecraft:bread",
  "count": 1,
  "quantity": 100
}
```

`quantity: 100` 会按物品最大堆叠数发放为 `64 + 36`。原型本身的 `count` 会被规范化为 1；请不要依赖原型 `count` 表示礼包数量。

需要名称、Lore、附魔或复杂组件时使用完整 `nbt`，并将 JSON 字符串内的双引号转义：

```json
{
  "id": "named_sword",
  "nbt": "{id:'minecraft:iron_sword',count:1,components:{'minecraft:custom_name':'{\\\"text\\\":\\\"新手铁剑\\\"}'}}",
  "quantity": 1
}
```

`nbt` 与 `item`、`count`、`components` 二选一；不能提交局部 NBT 片段。无效 ID、组件、SNBT、数量或超限条目会阻止重载。

## 打开模式与实例快照

`all` 一次发放所有条目及各自数量；`random_one` 只选择一个条目并发放该条目的完整数量。当前随机策略为均匀随机，不支持权重。

创建实例时会保存实例 UUID、所有者 UUID、礼包 ID、礼包版本、展示名称、描述、图标、模式、物品原型快照、数量、来源事件、状态、发放时间和随机选择索引。当前实现没有单独的 `opened_at` 字段，也没有 `EXPIRED` 状态；不要在运维脚本中假定这些字段存在。

奖励来源会使用 `grantKey = eventId + "#" + rewardId`。带有非空 `grantKey` 的实例在创建前会查询同一玩家的已有实例并复用，因此奖励账本在 `APPLYING` 状态恢复时不会无条件再创建礼包。管理员命令使用空授权键，按次发放，不参与奖励去重。

配置重载后，旧实例仍使用原快照；修改礼包定义不会改变玩家尚未打开的礼包。其中 `display`、`description` 和 `icon` 都会随实例快照保存，因此旧实例不会跟随新配置改变外观或 Lore。

## 投递状态与恢复

实例状态枚举包括：

```text
PENDING -> OPENING -> DELIVERING -> OPENED
                              \\-> WAITING_INBOX
OPENING（尚未创建批次）-> 可恢复创建批次
DELIVERING（存在不确定堆叠）/ BLOCKED -> 人工检查
```

打开时，服务端先锁定实例并同步持久化随机选择结果；随后生成持久化 `PackageDeliveryBatch`，将每个已经拆分的物品堆记录为 `PENDING`、`DELIVERING`、`DELIVERED`、`WAITING_INBOX` 或 `BLOCKED`。每一堆都会先写入并同步保存 `DELIVERING`，再写入玩家背包，最后写为 `DELIVERED` 并再次保存。背包不足的堆写为 `WAITING_INBOX`，玩家再次打开列表即可继续投递。已成功投递的堆不会再次发放，已 `OPENED` 的实例不能重复打开。

随机礼包的选择索引在实例中持久化，因此断线或重启后不会重新随机。重启恢复时，已完成的批次会收敛为 `OPENED`；尚未开始投递的批次可以安全继续；任何仍为 `DELIVERING` 的堆都会连同实例隔离为 `BLOCKED`，不会自动重放，以避免重复物品。应先备份世界数据并由管理员检查实例和日志。

`PackageDeliveryBatch` 与实例一同保存在世界 SavedData 中，包含批次 UUID、实例 UUID、每堆的物品快照、数量、状态、游标和时间戳。实例继续保留完整定义快照，批次是实际投递恢复边界。当前管理员命令尚未提供逐堆审计或人工结算界面；损坏或不确定批次会保留数据并标记 `BLOCKED`。

## 玩家与管理员命令

| 命令 | 权限动作 | 说明 |
| --- | --- | --- |
| `/omnitools packages` | `package.open` | 打开自己的礼包列表。 |
| `/omnitools package open` | `package.open` | `/omnitools packages` 的同义入口。 |
| `/omnitools package give <player> <package_id> [amount]` | `package.give` | 向一个或多个玩家创建指定数量的礼包实例。 |
| `/omnitools package inspect <player>` | `package.inspect` | 查看玩家实例总数。 |
| `/omnitools package remove <player> <instance_id>` | `package.remove` | 删除指定实例；执行前应确认实例 UUID。 |

当前没有实现 `/omnitools package open <instance_id>`；玩家只能从自己的列表点击实例。

礼包列表使用 54 格容器和 36 个内容槽，支持分页。列表会显示实例快照的名称、Description、来源、模式与状态，并提供奖励箱入口（受 `rewards.retry` 权限控制）。点击条目只会打开只读预览：`all` 显示全部物品，`random_one` 显示全部候选物品但不会产生随机结果。玩家必须在独立确认界面再次点击确认，服务端才会选择随机条目并开始投递。预览和确认页同样基于实例快照，因此配置重载不影响既有礼包。

`give` 的 `amount` 范围为 `1`--`4096`，按玩家、按数量顺序创建；当前没有容量预检和整体事务，达到实例上限时此前已经创建的实例不会回滚。

## 权限与奖励联动

权限动作及默认角色：

| 动作 | 默认角色 | 用途 |
| --- | --- | --- |
| `package.open` | `PLAYER` | 打开自己的礼包列表。 |
| `package.give` | `ADMIN` | 管理员发放礼包。 |
| `package.inspect` | `ADMIN` | 管理员查看实例数量。 |
| `package.remove` | `ADMIN` | 管理员删除实例。 |

统一奖励支持 `type: "package"`：

```json
{
  "id": "daily_package",
  "type": "package",
  "package": "starter"
}
```

签到、在线奖励、成就和 CDK 的奖励数组都可以使用该类型。礼包模块必须启用，且 `package` 必须引用已加载的定义；否则整次配置校验失败。奖励账本仍以 `eventId + rewardId` 幂等，重复检查不会重复创建实例。商店礼包商品和扣款事务目前尚未实现。

## 安全边界

- 所有礼包操作由服务端根据玩家 UUID 鉴权，客户端不能提交物品内容、随机结果或替他人打开实例。
- ID、条目数量、单条数量、总数量、文本长度和 SNBT 大小均有上限。
- 礼包定义不允许嵌套礼包；不能通过配置递归展开。
- 随机结果由服务端生成并写入实例，不信任客户端随机数。
- 已发放实例使用快照，不受热重载影响。
- 管理员发放和删除应结合服务器日志、世界存档备份进行审计；当前命令只返回数量/成功状态，没有独立审计页面或专项审计日志。
- 删除实例是不可逆的数据操作，执行前应确认玩家和实例 UUID。

## 备份、排错与验收

礼包实例保存在世界 SavedData 中，配置文件位于 `config/omnitools/packages/`。升级或批量修改前同时备份世界 `data/`（含礼包 SavedData）和配置目录。不要通过删除实例或奖励账本记录来“修复”重复发放；奖励重试应先按事件和奖励 ID 核对 `grantKey`，再使用[奖励一致性与奖励箱](../guides/reward-consistency.md)中的流程。

排错顺序：确认根开关和权限；检查 `format_version`、礼包 ID、物品 ID/SNBT、数量上限；查看重载日志是否保留旧快照；最后使用 `package inspect` 和世界备份核对实例状态。若 SavedData 中的物品快照无法使用注册表 Codec 解码，服务端会记录错误、保留原始记录并将该实例隔离为 `BLOCKED`；当前没有单独的管理员恢复命令。

建议在测试服验证：`all` 完整投递、`random_one` 单条选择、超过 64 的拆堆、NBT 组件保真、背包不足后的 `WAITING_INBOX`、重复点击幂等、奖励账本 `APPLYING` 恢复时复用同一 `grantKey`、重启后随机结果保持不变、配置修改不影响旧实例，以及玩家无法打开其他 UUID 的实例。

## 当前未实现与后续规划

### V2 稳定性说明

V2 配置兼容 V1 字段，并新增 `max_pending_packages_per_player`、`max_delivery_stacks_per_package`、`delivery_stacks_per_tick` 和 `history_retention_days`。打开礼包只建立逻辑投递条目，按 tick 限量生成物理堆；不会在一次点击中展开超大礼包。`package give` 会先预检全部目标玩家，失败时不创建任何实例，创建阶段异常会回滚本次批次。

系统只自动清理已 `OPENED`、无未完成投递批次且奖励账本已确认的过期历史实例；`PENDING`、`WAITING_INBOX` 和 `BLOCKED` 永不自动删除。

管理员现在可使用 `package list`、`package inspect <player> <instance_uuid>`、`package resolve ... delivered|pending confirm` 和 `package cancel ... confirm` 进行受控审计与处置；这些操作会写入 `config/omnitools/package-audit.log`。商店礼包购买事务仍未实现。

当前版本明确不提供：按权重随机、商店礼包商品及购买事务恢复、指定实例玩家命令、`retry`/`resolve` 人工处理命令、完整批次审计界面、礼包专用占位符和实体礼包物品交易。后续若增加这些能力，应保持现有虚拟实例和快照模型，避免引入可复制的实体礼包。
