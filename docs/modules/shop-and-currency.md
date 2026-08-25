# 商店与货币

## 1. 用途与场景

商店以原版箱子展示商品，购买会扣除 OmniTools 货币。余额由签到、在线奖励、成就和管理员命令共享。

## 2. 前置条件、关联模块与开关

根开关是 `modules.shop.enabled`；货币数据始终保留。无需客户端模组。

## 3. 配置路径与重载

文件为 `config/omnitools/shop/config.json`。修改后执行 `/omnitools reload`。

## 4--6. 最小配置、教学版与可复制版

教学版，不能直接复制：

```jsonc
{
  "format_version": 1,
  "products": [{
    "index": 0, // 0--44 是一页商品格
    "item": "minecraft:diamond",
    "count": 1,
    "price": 20
  }]
}
```

可直接复制版：

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
| `count` | 整数 | 否 | 1--64 | 写 0 或 65。 |
| `price` | 非负整数 | 是 | 可为 0 | 负价格。 |
| `components` | 原版组件字符串 | 否 | 解析为物品组件 | 使用 JSON 对象。 |

## 8. 全部配置场景

推荐 `components`，例如上例的自定义名称。解析器仍兼容旧 `nbt` 完整 ItemStack SNBT，但这是旧格式，不推荐新配置使用；统一奖励中的 `nbt` 更是明确不支持。购买失败通常是余额不足或背包无空间。

## 9. 指令、权限与默认角色

`/omnitools shop` 默认 `PLAYER`。`/omnitools balance` 查询自己的余额为 `PLAYER`；查看他人、加减货币为 `ADMIN`。

## 10. 占位符

`%balance%` 和 `%balance_formatted%`。

## 11. 数据与升级

余额保存在 SavedData。更换商品不会改变已有余额；先备份再批量调价。

## 12. 验收与排错

打开商店，购买普通商品和带组件商品；分别验证余额不足、背包满和成功购买的消息。
