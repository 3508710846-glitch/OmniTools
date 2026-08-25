# 云存储

## 1. 模块用途和适用场景

云存储提供每名玩家独立的箱子式仓库。每页固定 45 格，第一页免费，后续页面可消耗共享货币扩容；存取物品均由服务端保存。

## 2. 模块依赖与关联模块

模块 ID 为 `cloud_storage`。扩容扣除共享货币，但不要求签到模块持续启用。可选原生权限节点 `omnitools:cloud_storage` 由权限配置与称号权限效果共同控制。

## 3. 模块开关配置

```json
{ "modules": { "cloud_storage": { "enabled": true } } }
```

禁用会关闭已打开的存储 GUI 并拒绝新入口，不删除玩家仓库、已解锁页数或货币。

## 4. 初始配置文件位置

首次启动生成 `config/omnitools/cloud_storage/config.json`。修改后执行 `/omnitools reload`。

## 5. 最小可用配置

```json
{ "format_version": 1, "expansionCost": 100, "maxPages": 1 }
```

## 6. 完整配置示例

```json
{
  "format_version": 1,
  "expansionCost": 250,
  "maxPages": 2
}
```

## 7. 配置字段表

| 字段 | 类型 | 必填 | 默认值或范围 | 重载方式 |
| --- | --- | --- | --- | --- |
| `format_version` | integer | 否 | 首次生成 `1` | reload |
| `expansionCost` | integer | 是 | 默认 `100`，非负 | reload |
| `maxPages` | integer | 是 | 默认 `2`，范围 `1-2` | reload |

## 8. 指令、别名和权限节点

| 指令 | 别名 | 权限节点 | 默认角色 |
| --- | --- | --- | --- |
| `/omnitools storage [open]` | `/cloudstorage [open]`、`/cstorage [open]`、`/checkin storage [open]` | `storage.open` | ADMIN |

`permissions/config.json` 中 `storage.open.allow_native_node: true` 时，原生节点 `omnitools:cloud_storage` 也可以授权访问。

## 9. GUI 操作说明

仓库使用原版 6 行箱子。玩家可在物品区正常存取，使用导航格翻页，并在尚未解锁的下一页点击扩容。扩容会先校验余额，再永久保存页数；GUI 装饰与导航物品不可取走。

## 10. 占位符列表及用途

当前没有专属云存储占位符。可在侧边栏或菜单文本中使用通用 `%omnitools:balance%` 和称号占位符；完整列表见[Placeholder API](../guides/placeholder-api.md)。

## 11. 数据保存位置和升级影响

每名玩家的已解锁页数和每页 45 个 `ItemStack` 保存在世界 `CloudStorageData`。配置 JSON 只保存价格和页数上限；降低 `maxPages` 不会主动删除历史存储，应先备份再调整。

## 12. 与其他模块的联动

扩容使用与签到、商店和奖励相同的货币余额。权限模块可改写访问角色，称号效果可通过受限权限节点授予访问能力。

## 13. 常见错误及解决方法

| 现象 | 处理 |
| --- | --- |
| 无法打开仓库 | 检查模块开关、`storage.open` 角色或原生节点。 |
| 无法扩容 | 检查 `maxPages`、余额与 `expansionCost`。 |
| reload 后价格未变 | 查看服务端日志中的配置校验错误；旧快照会继续生效。 |

## 14. 可复制的验收清单

- [ ] 玩家可存取第一页物品，重连后内容仍在。
- [ ] 余额足够时可扩容，余额不足时不扣费。
- [ ] 翻页与最大页数限制正确。
- [ ] 禁用模块关闭 GUI 但不丢失仓库内容。
