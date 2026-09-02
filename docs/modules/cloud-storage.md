# 云存储

## 1. 用途与场景

云存储为每位玩家提供原版 6 行箱子。默认可用第一页，玩家可花货币扩容到第二页。

## 2. 前置条件、关联模块与开关

根开关为 `modules.cloud_storage.enabled`。权限来自权限模块配置；禁用时已打开的存储界面关闭，物品数据保留。

## 3. 配置路径与重载

文件为 `config/omnitools/cloud_storage/config.json`，修改后执行 `/omnitools reload`。

## 4. 最小可用配置

下方以默认一页和受限扩容构成最小可用配置。

## 5. 注释教学版 `jsonc`

解析器的实际字段只有扩容价格与最大页数；第一页固定存在，不能通过配置改为 0 页或超过 2 页。

教学版，不能直接复制：

```jsonc
{
  "format_version": 1, // 云存储配置格式版本。
  "expansionCost": 100, // 解锁第二页需要的货币。
  "maxPages": 2 // 最大总页数；只能为 1 或 2。
}
```

## 6. 可直接复制版 `json`

```json
{ "format_version": 1, "expansionCost": 100, "maxPages": 2 }
```

## 7. 字段表

| 字段 | 类型 | 必填 | 默认/范围 | 常见错误 |
| --- | --- | --- | --- | --- |
| `format_version` | 整数 | 否 | 1 | 非整数。 |
| `expansionCost` | 非负整数 | 是 | 默认 100 | 使用负数。 |
| `maxPages` | 整数 | 是 | 1--2，默认 2 | 写 3 或 0。 |

## 8. 全部配置场景

`maxPages: 1` 禁止扩容，`maxPages: 2` 开启一页付费扩容。没有名为 `default_pages` 的配置字段；默认页数由实现固定为 1。

## 9. 指令、权限与默认角色

`/omnitools storage` 默认 `ADMIN`。`/omnitools storage recovery list|inspect <操作UUID>|resolve <操作UUID> commit|rollback` 使用独立的 `storage.recovery` 管理员权限；即使模块临时关闭，该恢复入口仍可使用。权限配置的 `storage.open` 可用字符串角色或对象完整写法；见[权限](permissions.md)。

## 10. 占位符

没有独立占位符；可在侧边栏使用 `%balance%` 显示扩容货币。

## 11. 数据与升级

物品与已解锁页数保存在世界 SavedData，并且每个改变页面的存入、取出或移动都保留操作前后快照于独立账本。提交前会对整页逐项做 `ItemStack.CODEC` 编码、解码和组件一致性校验；任何一项失败都会拒绝整页提交并恢复界面和背包。

启动时若账本与页面快照无法证明结果，操作会进入 `QUARANTINED`，对应玩家存储将保持关闭，直到管理员检查账本后显式选择 `commit`（恢复操作后页面）或 `rollback`（恢复操作前页面）。恢复命令只替换云存储页面，管理员应先核对玩家背包，避免跨存档边界的重复发放。

## 12. 验收与排错

打开存储，放入物品，扩容后翻页并重启服务器验证。不能打开时检查模块开关和 `storage.open` 角色。
