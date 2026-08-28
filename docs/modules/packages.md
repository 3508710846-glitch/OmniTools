# 礼包

礼包模块提供服务端持有的虚拟礼包实例。礼包不是可丢弃、复制或篡改的物品；实例、物品快照和投递进度保存在世界 SavedData 中，玩家通过服务端 GUI 打开。适用于新手礼包、活动奖励、成就奖励、签到奖励和 CDK 奖励。

## 启用、路径与重载

根配置 `modules.packages.enabled` 默认是 `false`。首次启用并成功加载后使用：

```text
config/omnitools/packages/config.json
```

修改礼包配置后执行 `/omnitools reload packages`。修改根配置、公共奖励或其他跨模块引用时执行完整 `/omnitools reload`。重载先解析和校验候选快照，失败时保留旧快照；已经发放的实例不受配置重载影响。关闭模块不会删除已有实例，重新启用后可以继续处理。

教学示例：[packages.jsonc](../examples/config-platform/packages.jsonc)；Schema：[packages.schema.json](../schemas/packages.schema.json)。

## V2 配置示例

当前配置格式为 `format_version: 2`。V1 文件会在加载时迁移并补齐 V2 安全字段；迁移失败会拒绝重载。

```json
{
  "format_version": 2,
  "settings": {
    "max_pending_packages_per_player": 256,
    "max_quantity_per_entry": 2304,
    "max_total_quantity": 589824,
    "max_delivery_stacks_per_package": 216,
    "delivery_stacks_per_tick": 4,
    "history_retention_days": 90,
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
        { "id": "iron", "item": "minecraft:iron_ingot", "count": 1, "quantity": 32 }
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
        { "id": "gold", "item": "minecraft:gold_ingot", "count": 1, "quantity": 32 }
      ]
    }
  ]
}
```

## 字段与安全限制

| 字段 | 类型与范围 | 说明 |
| --- | --- | --- |
| `format_version` | `1` 或 `2` | 推荐 V2；V1 会迁移。 |
| `settings.max_pending_packages_per_player` | `1`--`4096`，默认 `256` | 玩家未打开实例上限。旧字段 `max_packages_per_player` 仍兼容。 |
| `settings.max_quantity_per_entry` | `1`--`1,000,000`，默认 `2304` | 单条业务数量上限。 |
| `settings.max_total_quantity` | `1`--`589824` | 单个礼包总数量上限，不能超过服务端固定上限。 |
| `settings.max_delivery_stacks_per_package` | `1`--`216`，默认 `216` | 一个礼包允许的逻辑投递条目数。 |
| `settings.delivery_stacks_per_tick` | `1`--`64`，默认 `4` | 服务端主线程每 tick 最多处理的物理堆数。 |
| `settings.history_retention_days` | `1`--`3650`，默认 `90` | 已完成历史实例的保留时间。 |
| `settings.delivery_policy` | `inventory_then_inbox` | 先放入背包，无法完整放入时进入奖励箱投递。 |
| `settings.random_strategy` | `uniform` | `random_one` 在条目间等概率选择。 |
| `packages` | 最多 128 个定义 | 定义 ID 不得重复。 |
| `packages[].id` | `[a-z0-9_.-]{1,64}` | 稳定业务 ID，不要复用为其他含义。 |
| `display` | 字符串，最长 128 | 创建实例时写入快照，并支持现有文本模板和 `&` 颜色代码。 |
| `description` | 字符串数组，最多 32 行 | 创建实例时写入快照，在列表和预览中作为 Lore。 |
| `icon` | 已注册物品 ID | 无效物品会阻止重载。 |
| `mode` | `all` 或 `random_one` | 缺省为 `all`。 |
| `version` | 正整数，缺省 `1` | 定义版本写入实例快照，仅用于审计和迁移识别。 |
| `items` | 1--256 条 | 条目数量还必须不超过 `max_delivery_stacks_per_package`。 |
| `items[].id` | `[a-z0-9_.-]{1,64}` | 礼包内稳定条目 ID，不得重复。 |
| `items[].item`、`count`、`components` | 简单物品写法 | 与 `nbt` 二选一；原型堆叠数为 1--64，最终会规范化为 1。 |
| `items[].nbt` | 完整 ItemStack SNBT，最多 32 KiB | 与 `item`、`count`、`components` 二选一。 |
| `items[].quantity` | 正整数，不超过单条上限 | 业务数量可大于 64，投递时按最大堆叠数拆分。 |

不支持礼包嵌套。无效物品、组件、SNBT、数量、重复 ID 或超限条目会使整次重载失败并保留旧配置。

## 物品快照与打开模式

简单写法：

```json
{ "id": "bread", "item": "minecraft:bread", "count": 1, "quantity": 100 }
```

`quantity: 100` 会按物品最大堆叠数投递为 `64 + 36`。需要名称、Lore、附魔或复杂组件时使用完整 SNBT：

```json
{
  "id": "named_sword",
  "nbt": "{id:'minecraft:iron_sword',count:1,components:{'minecraft:custom_name':'{\\\"text\\\":\\\"新手铁剑\\\"}'}}",
  "quantity": 1
}
```

`all` 发放所有条目；`random_one` 只选择一个条目并发放其完整数量。随机选择由服务端生成并立即持久化，重启不会重新随机。

实例会保存实例 UUID、所有者 UUID、礼包 ID 和版本、名称、描述、图标、模式、物品原型、数量、来源事件、`grantKey`、状态、授予时间和随机选择索引。配置修改不会改变既有快照。

奖励来源的 `grantKey` 为 `eventId + "#" + rewardId`。非空 `grantKey` 创建前会查询同一玩家的已有实例并复用，保证奖励账本从 `APPLYING` 恢复时不会重复创建。管理员 `give` 使用空授权键，按命令批次独立创建。

## 投递批次与恢复

打开礼包只创建持久化投递批次，不会一次点击预生成所有物理堆。批次包含逻辑条目、物品快照、总数量、已投递数量、条目 UUID、游标和时间戳；每 tick 在服务端主线程按 `delivery_stacks_per_tick` 预算生成并投递物理堆。

实例状态：

```text
PENDING -> OPENING -> DELIVERING -> OPENED
                              \\-> WAITING_INBOX
DELIVERING（不确定中断）-> BLOCKED
```

每个条目状态为 `PENDING`、`DELIVERING`、`DELIVERED`、`WAITING_INBOX` 或 `BLOCKED`。写入 `DELIVERING` 后才修改背包；若在该边界中断，重启恢复会保守标记为 `BLOCKED`，绝不自动重发。背包不足的条目进入 `WAITING_INBOX`，空间可用后再次打开列表即可继续。全部条目完成后批次为 `COMPLETED`、实例为 `OPENED`，已完成条目不会重复投递。

物品快照使用带注册表的 Codec 持久化。损坏或无法解码的记录会保留原始数据、记录错误并隔离为 `BLOCKED`，不会静默丢失。

## 玩家与管理员命令

| 命令 | 权限动作 | 说明 |
| --- | --- | --- |
| `/omnitools packages` | `package.open` | 打开自己的礼包列表。 |
| `/omnitools package open` | `package.open` | 同上。 |
| `/omnitools package give <player> <package> [amount]` | `package.give` | 给一个或多个目标玩家发放 1--4096 个实例。先预检所有目标玩家的定义和容量，任一失败则零创建；创建异常会回滚本次批次并返回操作 UUID。 |
| `/omnitools package list <player> [status] [page]` | `package.inspect` | 按状态分页列出实例。 |
| `/omnitools package inspect <player>` | `package.inspect` | 查看实例总数。 |
| `/omnitools package inspect <player> <instance_uuid>` | `package.inspect` | 显示来源、版本、`grantKey`、随机索引、批次和每堆总量/已投递数量/状态。 |
| `/omnitools package resolve <player> <instance_uuid> <stack_uuid> delivered confirm` | `package.resolve` | 明确确认阻塞堆已发，写入审计并转入待投递状态。仅允许处理 `BLOCKED` 实例中的 `BLOCKED` 堆。 |
| `/omnitools package resolve <player> <instance_uuid> <stack_uuid> pending confirm` | `package.resolve` | 明确确认阻塞堆未发，之后允许安全重投。 |
| `/omnitools package cancel <player> <instance_uuid> confirm` | `package.cancel` | 取消 `BLOCKED` 实例；存在不确定投递堆时拒绝。 |
| `/omnitools package remove <player> <instance_uuid>` | `package.remove` | 直接删除指定实例的旧命令，非恢复工具；执行前必须核对 UUID。 |

高风险 `resolve`、`cancel`、`remove` 和相关操作写入 `config/omnitools/package-audit.log`。`remove` 仍是不可逆的直接删除命令，建议仅在确认 UUID 且完成外部备份后使用。没有“BLOCKED 一键重试”，必须先逐堆确认。

## 历史清理、权限与联动

后台只自动清理同时满足以下条件的历史：实例为 `OPENED`、无未完成投递批次、超过 `history_retention_days`，并且奖励账本或商店购买事务已确认完成。`PENDING`、`WAITING_INBOX`、`BLOCKED` 永不自动删除。删除前应备份世界 `data/`。

默认权限角色：`package.open` 为 `PLAYER`；`package.give`、`package.inspect`、`package.resolve`、`package.cancel`、`package.remove` 为 `ADMIN`。

统一奖励可引用礼包：

```json
{ "id": "daily_package", "type": "package", "package": "starter" }
```

签到、在线奖励、成就和 CDK 都可使用该类型；礼包模块必须启用且引用的定义必须存在。奖励账本仍以 `eventId + rewardId` 幂等。商店礼包商品使用独立购买事务，见[商店与货币](shop-and-currency.md)。

## 排错与验收

先确认根开关、权限和模块状态，再检查 V2 字段、定义/条目 ID、物品 ID 或 SNBT、数量及条目上限。使用 `package list` 和 `package inspect` 查看批次及堆状态；不确定状态先备份世界并查阅审计日志，不能删除账本记录来“修复”重复发放。

测试服至少验证：`all` 和 `random_one`、超过 64 的数量拆分、NBT/组件保真、背包满后的 `WAITING_INBOX`、重复点击、奖励 `APPLYING` 恢复、重启后随机索引保持、配置重载不影响旧快照、`give` 全量预检/回滚，以及玩家无法打开其他玩家实例。

## 当前未实现与规划

当前实现不包含权重随机、保底机制、礼包专用占位符、指定实例玩家命令、实体礼包物品交易和商店自动退款/自动重发。商店购买结果不确定时会进入 `BLOCKED`，需管理员查看商店审计记录后处理。后续扩展应继续使用虚拟实例、快照和持久化批次模型。
