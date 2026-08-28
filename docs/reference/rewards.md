# 统一奖励

签到、在线奖励、成就和 CDK 使用统一的 `RewardDefinition` 与奖励账本。每个事件内的奖励 `id` 必须唯一，且为 1--64 位小写字母、数字、`_`、`.` 或 `-`。奖励 ID 是账本业务键，发布后不要改作其他含义；需要改变语义时新增 ID。

## 奖励库 V2

`config/omnitools/common/rewards.json` 当前支持 V2 奖励库。`rewards` 对象的键就是稳定奖励 ID，`sets` 是可嵌套的奖励集合。业务模块的 `rewards` 数组可以使用 `{ "reward": "..." }` 或 `{ "set": "..." }` 引用：

```json
{
  "format_version": 2,
  "rewards": {
    "coins_100": { "type": "currency", "amount": 100 },
    "starter_package": { "type": "package", "package": "starter" }
  },
  "sets": {
    "daily_basic": { "rewards": ["coins_100", "starter_package"] }
  }
}
```

模块中：

```json
{
  "rewards": [
    { "set": "daily_basic" },
    { "reward": "coins_100" }
  ]
}
```

奖励库键名自动注入定义的 `id`。调用处只能写引用，不能覆盖类型、数量、物品、NBT、称号或命令字段。集合支持嵌套，但未知奖励/集合、循环引用、超过 16 层深度，以及同一事件展开后重复奖励 ID 都会阻止整次重载。奖励库解析失败时保留上一份配置。

V1 的 `templates`、模块内 `template` 和 `$ref` 仍兼容，且保留旧的调用处字段覆盖行为。V1 示例和迁移说明见[统一配置平台](../config-platform.md)。

## 支持的奖励类型

| `type` | 必填字段 | 行为与限制 |
| --- | --- | --- |
| `currency` | `id`、`amount` | 发放非负整数货币。 |
| `item` | `id`、简单物品写法或完整 `nbt` | 单个物品原型数量 1--64；事件物品总数最多 2304。 |
| `title` | `id`、`title` | 引用称号模块中的 ID；无 `duration` 为永久称号。 |
| `command` | `id`、`run_as`、`command` | 仅允许控制台执行，并受命令奖励开关、长度、冷却和 `allowed_roots` 白名单限制。 |
| `makeup_card` | `id`、`amount` | 发放服务端虚拟补签卡，受 `daily_checkin.makeup.max_cards` 限制。 |
| `package` | `id`、`package` | 创建礼包虚拟实例；礼包模块必须启用且定义存在。 |

## 物品与 SNBT

普通物品优先使用 `item`、`count`、`components`：

```json
{
  "id": "bread",
  "type": "item",
  "item": "minecraft:bread",
  "count": 8,
  "components": {}
}
```

需要自定义名称、Lore、附魔或复杂组件时使用完整 ItemStack SNBT。`nbt` 与 `item`、`count`、`components` 二选一，不能写局部 NBT 片段；JSON 字符串中的双引号必须转义：

```json
{
  "id": "named_bread",
  "type": "item",
  "nbt": "{id:'minecraft:bread',count:8,components:{'minecraft:custom_name':'{\\\"text\\\":\\\"每日面包\\\"}'}}"
}
```

服务器限制 SNBT 源文本和可持久化快照为 32 KiB，并使用带注册表的 Codec 校验和恢复。无效配置会使重载失败；背包不足的物品快照会进入奖励箱，既有快照不会因配置重载改变。

## 称号时长

没有 `duration` 的称号奖励永久有效：

```json
{ "id": "legend", "type": "title", "title": "legend" }
```

限时称号示例：

```json
{
  "id": "architect_7_days",
  "type": "title",
  "title": "architect",
  "duration": { "mode": "active_days", "days": 7 },
  "renewal": "extend"
}
```

`duration.mode` 为 `permanent` 或 `active_days`；后者需要正整数 `days`。有效时间只在玩家在线且佩戴该称号时消耗，`renewal` 可为 `extend`、`replace` 或 `max`。永久称号不会被临时奖励缩短或覆盖。同一事件和奖励 ID 只处理一次，不同事件再次奖励同一称号时才按续期策略处理。

## 礼包奖励

礼包奖励只创建服务端虚拟实例：

```json
{ "id": "daily_package", "type": "package", "package": "starter" }
```

奖励账本的 `grantKey` 为 `eventId + "#" + rewardId`。恢复 `APPLYING` 时先按玩家和 `grantKey` 查询已有实例并复用，保证不会重复创建。礼包的快照、随机选择、分批投递、`WAITING_INBOX`、`BLOCKED` 和管理员处置见[礼包模块](../modules/packages.md)。

## 补签卡奖励

```json
{ "id": "makeup_cards", "type": "makeup_card", "amount": 2 }
```

补签卡是 SavedData 中的虚拟权益，不会生成实体物品。账本和卡余额在同一玩家存档事务中提交；达到上限时保留待处理状态，释放容量后可重试。

## 命令奖励安全边界

命令只能使用以下受控变量：

```text
{player_name} {player_uuid} {player_x} {player_y} {player_z} {player_world}
```

不能在控制台命令中使用 `%omnitools:...%` 或第三方文本占位符。奖励状态、重试和人工结案见[奖励一致性与奖励箱](../guides/reward-consistency.md)。

## 账本与重载语义

奖励账本按事件和奖励 ID 记录 `PENDING -> APPLYING -> GRANTED`，异常会进入 `BLOCKED` 或 `FAILED`。不要删除账本记录来处理异常；先备份世界数据，再按事件、奖励 ID 和 `grantKey` 核对效果。商店礼包购买使用独立购买事务，不属于统一奖励数组，见[商店与货币](../modules/shop-and-currency.md)。
