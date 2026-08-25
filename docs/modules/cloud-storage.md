# 云端存储

## 1. 功能简介

云端存储提供每名玩家独立的箱子式物品空间。每页固定 45 格，首页免费，第二页可花货币扩容。存储界面使用 OmniTools 的自定义菜单类型及客户端界面。

## 2. 模块开关

根配置为 `config/omnitools/config.json`：

```json
{
  "modules": {
    "cloud_storage": { "enabled": true }
  }
}
```

禁用后存储菜单关闭且入口拒绝；已存物品和已解锁页数保留，重新启用后恢复。

## 3. 初始配置

首次加载生成 `config/omnitools/cloud_storage/config.json`：

```json
{
  "format_version": 1,
  "expansionCost": 100,
  "maxPages": 2
}
```

文件缺失时写入默认值；损坏时不覆盖原文件，旧快照继续运行。

## 4. 指令与权限

| 指令 | 别名 | 用途 | 默认权限 | 仅玩家 |
| --- | --- | --- | --- | --- |
| `/omnitools storage [open]` | `/cloudstorage [open]`、`/cstorage [open]`、`/checkin storage [open]` | 打开自己的存储 | `storage.open` (`ADMIN`) | 是 |

当 `permissions/config.json` 中 `storage.open.allow_native_node` 为 `true`（默认）时，拥有原生节点 `omnitools:cloud_storage` 的玩家也可打开。

## 5. 配置字段

| 字段 | JSON 类型 | 必填 | 默认值/范围 | 作用与错误行为 |
| --- | --- | --- | --- | --- |
| `format_version` | 任意 JSON 值（当前未读取） | 否 | 首次生成写入 `1` | 当前读取器忽略该字段；它仅作为生成文件的格式标记。 |
| `expansionCost` | integer | 是 | `100`，`>= 0` | 解锁下一页消耗的货币。 |
| `maxPages` | integer | 是 | `2`，仅 `1-2` | 每玩家最多页数；固定上限为 2。 |

## 6. 使用示例

```json
{
  "format_version": 1,
  "expansionCost": 250,
  "maxPages": 2
}
```

玩家从菜单操作扩容。修改后执行 `/omnitools reload`；页数或价格字段非法时根据日志修正，旧配置不会丢失。

## 7. 数据保存

世界 `SavedData` 的 `CloudStorageData` 保存每位玩家已解锁页数和每页 45 个 `ItemStack`。JSON 不保存物品。升级前必须备份世界和 `config/omnitools/`。

## 8. 热重载与依赖

成功重载后新开菜单读取新成本与页数；模块禁用或玩家失去 `storage.open` 权限时已打开菜单关闭。扩容费用使用共享货币余额。统一候选快照失败时旧存储配置继续运行。
