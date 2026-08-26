# 商店与货币

## 1. 用途与场景

商店以原版箱子展示商品，购买会扣除 OmniTools 货币。余额由签到、在线奖励、成就和管理员命令共享。

## 2. 前置条件、关联模块与开关

根开关是 `modules.shop.enabled`；货币数据始终保留。无需客户端模组。

## 3. 配置路径与重载

文件为 `config/omnitools/shop/config.json`。修改后执行 `/omnitools reload`。

## 4. 最小可用配置

下方以一个可购买物品和初始余额构成最小可用配置。

## 5. 注释教学版 `jsonc`

教学版，不能直接复制：

```jsonc
{
  "format_version": 1, // 商店配置格式版本。
  "products": [{ // 商品定义数组。
    "index": 0, // 0--44 是一页商品格
    "item": "minecraft:diamond", // 要出售的已注册物品 ID。
    "count": 1, // 一次购买获得的数量。
    "price": 20 // 购买一次需要的货币。
  }]
}
```

## 6. 可直接复制版 `json`

```json
{
  "format_version": 1,
  "products": [
    { "index": 0, "item": "minecraft:diamond", "count": 1, "price": 20 },
    {
      "index": 1,
      "item": "minecraft:paper",
      "count": 1,
      "components": "[minecraft:custom_name='{\"text\":\"传送券\"}']",
      "price": 100
    }
  ]
}
```

## 7. 字段表

| 字段 | 类型 | 必填 | 默认/范围 | 常见错误 |
| --- | --- | --- | --- | --- |
| `products` | 数组 | 是 | 每页 45 商品格 | 写成对象。 |
| `index` | 正整数 | 是 | 0 起，不能重复 | 超出可用商品格。 |
| `item` | 原版/已注册物品 ID | 是 | 非 `minecraft:air` | 拼错命名空间。 |
| `count` | 整数 | 是（不用 `nbt` 时） | 1 至 Java 整数上限 | 写 0、负数或非整数。商店沿用历史行为，单次购买会按物品最大堆叠数拆分后交付。 |
| `price` | 非负整数 | 是 | 可为 0 | 负价格。 |
| `components` | 原版组件字符串 | 否 | 解析为物品组件 | 使用 JSON 对象。 |

## 8. 全部配置场景

推荐 `components`，例如上例的自定义名称。需要完整 ItemStack、复杂容器内容或兼容既有商店 SNBT 时可使用完整 `nbt`；它不能与 `item`、`count`、`components` 混写，规则与[统一奖励](../reference/rewards.md)一致。购买失败通常是余额不足或背包无空间。

## 9. 指令、权限与默认角色

`/omnitools shop` 默认 `PLAYER`。`/omnitools balance` 查询自己的余额为 `PLAYER`；查看他人、加减货币为 `ADMIN`。

## 10. 占位符

`%balance%` 和 `%balance_formatted%`。

## 11. 数据与升级

余额保存在 SavedData。更换商品不会改变已有余额；先备份再批量调价。

## 12. 验收与排错

打开商店，购买普通商品和带组件商品；分别验证余额不足、背包满和成功购买的消息。
