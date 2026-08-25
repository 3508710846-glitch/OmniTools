# 商店与货币

## 1. 功能简介

商店让玩家用 OmniTools 共享货币购买配置物品。货币余额由世界 `CheckinData` 保存，也被签到、在线奖励和成就奖励使用；货币管理命令不是独立模块。商店界面使用 OmniTools 的自定义菜单类型及客户端界面。

## 2. 模块开关

根配置为 `config/omnitools/config.json`：

```json
{
  "modules": {
    "shop": { "enabled": true }
  }
}
```

禁用商店会关闭商店菜单并隐藏/拒绝商店入口，不删除余额或商品配置。货币查询和管理员增减货币只受各自 `CommandAction` 权限控制。

## 3. 初始配置

首次加载生成 `config/omnitools/shop/config.json`：

```json
{
  "format_version": 1,
  "products": [
    { "index": 0, "item": "minecraft:diamond", "count": 1, "price": 20 }
  ]
}
```

文件缺失时创建默认商品；配置错误时不覆盖原文件，旧快照继续运行。

## 4. 指令与权限

| 指令 | 别名 | 用途 | 默认权限 | 仅玩家 |
| --- | --- | --- | --- | --- |
| `/omnitools shop [open]` | `/checkin shop [open]` | 打开商店 | `shop.open` (`PLAYER`) | 是 |
| `/omnitools currency` | `/checkin currency`、`/money` | 查询自己的余额 | `currency.balance.self` (`PLAYER`) | 是 |
| `/omnitools currency balance|get [玩家]` | `/checkin currency balance|get [玩家]`、`/money balance|get [玩家]`、`/omnitools balance [玩家]`、`/checkin balance [玩家]`、`/balance [玩家]` | 查询余额 | 自己：`currency.balance.self`；他人：`currency.balance.other` (`ADMIN`) | 自己查询是 |
| `/omnitools currency add <玩家> <数量>` | `/checkin currency add ...`、`/money add ...`、`/omnitools add ...` | 增加货币 | `currency.add` (`ADMIN`) | 否 |
| `/omnitools currency remove|deduct|take <玩家> <数量>` | `/checkin currency remove|deduct|take ...`、`/money remove|deduct|take ...`、`/omnitools remove ...` | 扣除货币 | `currency.remove` (`ADMIN`) | 否 |

## 5. 配置字段

| 字段 | JSON 类型 | 必填 | 默认值/范围 | 作用与错误行为 |
| --- | --- | --- | --- | --- |
| `format_version` | 任意 JSON 值（当前未读取） | 否 | 首次生成写入 `1` | 当前读取器忽略该字段；它仅作为生成文件的格式标记。 |
| `products` | array | 是 | 默认一件钻石 | 商品列表；根也可直接是数组。非数组拒绝配置。 |
| `products[].index` | integer | 是 | `>= 0`，唯一 | 商品槽位和分页索引；每页 45 个槽位。 |
| `products[].price` | integer | 是 | `>= 0` | 购买价格。 |
| `products[].item` | string | 与 `nbt` 二选一 | 有效物品 ID | 物品 ID。可配合 `components`。 |
| `products[].count` | integer | 使用 `item` 时是 | `>= 1` | 物品数量。 |
| `products[].components` | string | 否 | 无 | 原版物品组件文本；与 `item` 拼接解析。 |
| `products[].nbt` | string | 与 `item` 二选一 | 无 | 完整物品堆 SNBT；必须能解码为有效非空 `ItemStack`。 |

## 6. 使用示例

```json
{
  "format_version": 1,
  "products": [
    { "index": 0, "item": "minecraft:bread", "count": 16, "price": 10 },
    { "index": 1, "item": "minecraft:diamond", "count": 3, "price": 100 }
  ]
}
```

修改后执行 `/omnitools reload`。若物品 ID、组件或 SNBT 无效，查看日志并恢复合法 JSON；旧商品列表不会被替换。

## 7. 数据保存

商品 JSON 只保存货架定义。货币余额保存在世界 `SavedData` 的 `CheckinData`，购买扣费与发物由服务端判定。备份世界与配置目录后再迁移。

## 8. 热重载与依赖

成功重载会使用新商品快照；商店禁用或玩家失去 `shop.open` 权限时已打开商店关闭。商店依赖共享货币数据，但不要求每日签到模块持续启用。配置快照任一处失败时旧商店继续运行。
