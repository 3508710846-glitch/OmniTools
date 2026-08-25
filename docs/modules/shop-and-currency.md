# 商店与货币

## 1. 模块用途和适用场景

商店让玩家用 OmniTools 共享货币购买配置物品。所有扣费与发物都在服务端校验，商店界面是原版箱子 GUI。

## 2. 模块依赖与关联模块

模块 ID 为 `shop`。货币余额保存在共享 `CheckinData`，不依赖签到模块持续启用；签到、在线奖励与成就可向同一余额发放货币。

## 3. 模块开关配置

```json
{ "modules": { "shop": { "enabled": true } } }
```

禁用会关闭商店 GUI 和拒绝商店入口；不会删除商品配置或货币余额。

## 4. 初始配置文件位置

首次启动生成 `config/omnitools/shop/config.json`。修改后需要 `/omnitools reload`。

## 5. 最小可用配置

```json
{
  "format_version": 1,
  "products": [{ "index": 0, "item": "minecraft:diamond", "count": 1, "price": 20 }]
}
```

## 6. 完整配置示例

```json
{
  "format_version": 1,
  "products": [
    { "index": 0, "item": "minecraft:bread", "count": 16, "price": 10 },
    { "index": 1, "item": "minecraft:diamond", "count": 3, "price": 100, "components": {} }
  ]
}
```

## 7. 配置字段表

| 字段 | 类型 | 必填 | 默认值或范围 | 重载方式 |
| --- | --- | --- | --- | --- |
| `format_version` | integer | 否 | 首次生成 `1` | reload |
| `products` | array | 是 | 商品列表；每页 45 个物品格 | reload |
| `products[].index` | integer | 是 | 非负且唯一 | reload |
| `products[].price` | integer | 是 | 非负 | reload |
| `products[].item` | string | 与 `nbt` 二选一 | 有效物品 ID | reload |
| `products[].count` | integer | item 时 | 至少 1 | reload |
| `products[].components` | object 或组件文本 | 否 | 原版物品组件 | reload |
| `products[].nbt` | string | 与 `item` 二选一 | 有效、非空 ItemStack 的 SNBT | reload |

## 8. 指令、别名和权限节点

| 指令 | 别名 | 权限节点 | 默认角色 |
| --- | --- | --- | --- |
| `/omnitools shop [open]` | `/checkin shop [open]` | `shop.open` | PLAYER |
| `/omnitools currency` | `/checkin currency`、`/money` | `currency.balance.self` | PLAYER |
| `/omnitools currency balance|get [player]` | `/balance [player]` | 自己为 `currency.balance.self`，他人为 `currency.balance.other` | PLAYER / ADMIN |
| `/omnitools currency add <player> <amount>` | `/money add ...` | `currency.add` | ADMIN |
| `/omnitools currency remove <player> <amount>` | `/money remove ...` | `currency.remove` | ADMIN |

## 9. GUI 操作说明

每页显示最多 45 件商品，可用翻页控制切换。点击商品时服务端同时验证价格、余额和背包空间；玩家不能取走 GUI 物品。余额不足或背包无法容纳时不会扣费。

## 10. 占位符列表及用途

`%omnitools:balance%` 是整数余额，`%omnitools:balance_formatted%` 是格式化余额，可用于侧边栏、菜单名称和 Lore。

## 11. 数据保存位置和升级影响

商品规则在模块 JSON，余额在世界 `CheckinData`。升级或禁用商店不会重置余额；迁移前同时备份世界和配置目录。

## 12. 与其他模块的联动

签到、在线奖励和成就可发共享货币；云存储扩容也扣除同一余额。权限模块可覆盖所有货币指令的默认角色。

## 13. 常见错误及解决方法

| 现象 | 处理 |
| --- | --- |
| 商品未出现 | 检查商品 `index` 是否唯一、物品 ID 与组件是否有效，然后 reload。 |
| 点击后未购买 | 检查余额与背包完整空间；失败不会扣费。 |
| 管理命令无权限 | 检查 `permissions` 模块中对应动作角色或原生权限等级。 |

## 14. 可复制的验收清单

- [ ] 商品按 `index` 显示并可翻页。
- [ ] 余额不足、背包满与成功购买三种情况均正确。
- [ ] 货币查询、增加和扣除权限符合配置。
- [ ] 关闭商店模块不影响已有余额。
