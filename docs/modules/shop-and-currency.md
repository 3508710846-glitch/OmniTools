# 商店与货币

商店使用服务端箱子 GUI 展示商品，购买扣除 OmniTools 货币。余额由签到、在线奖励、成就、CDK 和管理员命令共享。商店和礼包均为服务端功能，不需要客户端模组。

## 启用、路径与重载

根开关为 `modules.shop.enabled`，礼包商品还要求 `modules.packages.enabled`。商店配置路径：

```text
config/omnitools/shop/config.json
```

修改后执行 `/omnitools reload shop`；修改根开关、礼包定义或跨模块引用时执行完整 `/omnitools reload`。校验失败会保留旧配置。余额和购买事务保存在世界 SavedData 中。

教学示例：[shop.jsonc](../examples/config-platform/shop.jsonc)；Schema：[shop.schema.json](../schemas/shop.schema.json)。

## 配置示例

```json
{
  "format_version": 1,
  "products": [
    { "index": 0, "item": "minecraft:diamond", "count": 1, "price": 20 },
    {
      "index": 1,
      "nbt": "{id:'minecraft:diamond_sword',count:1,components:{}}",
      "price": 500
    },
    {
      "index": 2,
      "type": "package",
      "package": "starter",
      "price": 100
    }
  ]
}
```

## 商品字段

| 字段 | 类型与范围 | 说明 |
| --- | --- | --- |
| `format_version` | `1` | 当前商店格式版本。 |
| `products` | 商品数组 | 每页最多 45 个槽位，索引可稀疏但不能重复。 |
| `index` | 非负整数 | 商品 GUI 槽位。 |
| `type` | `item` 或 `package` | 缺省为 `item`。 |
| `item`、`count`、`components` | 物品商品字段 | 与 `nbt` 二选一；数量为正整数，按物品最大堆叠数交付。 |
| `nbt` | 完整 ItemStack SNBT | 不能与 `item`、`count`、`components` 混用。 |
| `package` | 礼包定义 ID | 仅 `type: "package"` 使用，且不能同时出现物品字段。 |
| `price` | 非负整数 | 购买所需货币，可为 0。 |

`type: "package"` 商品显示为箱子图标，购买后创建礼包实例；`package` 必须引用当前已加载的礼包定义。物品商品沿用统一物品解析和 32 KiB SNBT 限制。

## 指令、权限与占位符

| 命令 | 默认权限 | 说明 |
| --- | --- | --- |
| `/omnitools shop` 或 `/omnitools shop open` | `shop.open`（PLAYER） | 打开商店。 |
| `/omnitools balance` | 查询自己为 PLAYER；查询他人和调整余额为 ADMIN | 查看或管理货币余额。 |
| `/omnitools shop audit` | `shop.audit`（ADMIN） | 列出购买总数和阻塞事务。 |
| `/omnitools shop audit <transaction_uuid>` | `shop.audit`（ADMIN） | 查看单笔购买的状态、玩家、商品、价格、`grantKey`、时间戳和审计原因。 |

可用货币占位符为 `%balance%` 和 `%balance_formatted%`。商店商品本身不接受客户端提交的价格或礼包内容。

## 礼包购买事务

礼包商品不直接调用普通物品发放，而是使用独立的持久化购买事务：

```text
PREPARED -> CHARGED -> PACKAGE_CREATED -> COMPLETED
                    \-> BLOCKED
```

购买流程：

1. 预检商店和礼包模块、礼包定义、玩家容量、余额及点击冷却。
2. 创建并持久化 `PREPARED`，其中包含商品索引、价格和完整礼包快照。
3. 以交易 UUID 为幂等键扣币，并持久化 `CHARGED`。重复执行不会再次扣币。
4. 以稳定键 `shop:<transactionId>#package` 创建礼包实例；礼包 SavedData 已存在时复用同一快照，并持久化 `PACKAGE_CREATED`。
5. 确认礼包实例存在后持久化 `COMPLETED`。

每个不可逆边界都会同步保存。服务器在扣币或创建礼包结果不确定时不会自动退款或重发，而是将事务置为 `BLOCKED` 并写入 `config/omnitools/shop-purchase-audit.log`。管理员先使用 `shop audit` 核对货币 SavedData、礼包实例和交易日志，再决定人工处置；当前没有自动退款命令。

服务器启动时只恢复有持久化证明的步骤：已有扣币标记的 `PREPARED` 会推进为 `CHARGED`，`CHARGED` 会按稳定 `grantKey` 尝试创建/复用礼包，`PACKAGE_CREATED` 只有在实例确实存在时才完成。缺少证明或发生异常的事务保持 `BLOCKED`，不会猜测性补发。

普通物品购买仍在背包空间和余额检查后交付；礼包购买的投递由礼包模块按 tick 分批处理，背包不足时进入礼包的奖励箱流程。

## 数据、升级与排错

余额、购买事务和礼包 SavedData 应随世界 `data/` 一起备份。修改商品价格或礼包定义不会改写已保存的购买快照。排错时先确认两个模块均启用，再检查商品索引、价格、礼包 ID、玩家余额和容量；对于 `BLOCKED` 事务保留原始记录并使用审计命令，不要删除交易或货币事件来“重试”。

建议在测试服验证：普通商品购买、组件/SNBT 商品、礼包商品、重复点击冷却、余额不足、礼包容量不足、扣币后重启、创建礼包后重启、`BLOCKED` 审计，以及最终只创建一个礼包实例且余额只扣一次。

## 当前未实现与规划

当前商店不支持自动退款、自动重发或玩家自行解决阻塞交易；管理员审计是故障闭环。购买事务的稳定键和礼包快照模型已为后续更多商品类型预留，但任何新类型都必须沿用持久化状态机和明确的恢复证明。
