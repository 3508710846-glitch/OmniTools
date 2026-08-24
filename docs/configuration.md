# 配置指南

OmniTools 的管理员配置统一位于 `config/omnitools/`。首次启动会按需创建缺失的默认文件。修改任意配置后，使用具有 `config.reload` 权限的命令源执行 `/omnitools reload`。

重载会先读取模块配置、称号效果引用、成就奖励引用和指令权限，并在全部校验成功后一次性发布新快照。任一配置无效时，服务器继续使用上一份有效配置。

## 目录结构

```text
config/omnitools/
├── config.json
├── daily_checkin/config.json
├── online_reward/config.json
├── shop/config.json
├── titles/config.json
├── title_effects/config.json
├── achievements/config.json
├── cloud_storage/config.json
├── permissions/config.json
└── legacy/
```

| 文件 | 用途 |
| --- | --- |
| `config.json` | 全局选项、可选集成与模块开关 |
| `daily_checkin/config.json` | 签到货币和月度奖励 |
| `online_reward/config.json` | 每日在线时长奖励 |
| `shop/config.json` | 商店商品与价格 |
| `titles/config.json` | 称号定义 |
| `title_effects/config.json` | 称号关联的效果定义 |
| `achievements/config.json` | 成就条件和奖励；详见[成就配置指南](achievements.md) |
| `cloud_storage/config.json` | 云端存储的容量和页面配置 |
| `permissions/config.json` | 指令动作的最低角色与安全开关 |
| `legacy/` | 识别到的历史根目录配置副本与迁移清单 |

世界中的签到、货币、称号、成就和存储内容由 `SavedData` 保存，不在这些 JSON 配置中。不要手动编辑世界 `SavedData`；迁移或升级前应备份完整世界目录和 `config/omnitools/`。

## 根配置

`config/omnitools/config.json` 的新生成版本为 `format_version: 2`：

```json
{
  "format_version": 2,
  "global": {
    "debug": false,
    "timezone": "Asia/Shanghai"
  },
  "integrations": {
    "placeholder_api": {
      "enabled": true
    }
  },
  "modules": {
    "daily_checkin": { "enabled": true },
    "online_reward": { "enabled": true },
    "shop": { "enabled": true },
    "titles": { "enabled": true },
    "title_effects": { "enabled": true },
    "achievements": { "enabled": true },
    "cloud_storage": { "enabled": true },
    "permissions": { "enabled": false }
  }
}
```

| 字段 | 说明 |
| --- | --- |
| `global.debug` | 全局调试标记，默认 `false` |
| `global.timezone` | 有效的 Java `ZoneId`；默认 `Asia/Shanghai`，用于签到日期与在线时长的跨日切分 |
| `integrations.placeholder_api.enabled` | Placeholder API 集成总开关，默认 `true` |
| `modules.*.enabled` | 对应功能模块是否启用 |

关闭功能模块不会删除玩家已有数据。依赖其他模块的功能仍需满足其引用关系；例如 `title_effects` 已启用且其中定义了效果时，`titles` 也必须启用。`permissions` 保留在模块状态中；指令入口始终按照 `permissions/config.json` 的当前动作配置进行权限检查。

旧根配置中 `format_version: 1` 且没有 `integrations.placeholder_api` 节点时，占位符集成默认视为启用。加载旧配置不会强制写回新字段；新建根配置才会写入 v2 格式。

## 指令权限

`permissions/config.json` 的 `commands` 对象以“规范动作 ID -> 最低角色”的方式配置。角色与原版权限等级对应为：

| 角色 | Minecraft 权限等级 |
| --- | --- |
| `PLAYER` | `0` |
| `MODERATOR` | `1` |
| `ADMIN` | `2` |
| `OWNER` | `4` |

默认动作与角色如下：

| 动作 ID | 默认角色 | 覆盖的操作 |
| --- | --- | --- |
| `checkin.open` | `PLAYER` | 打开签到界面 |
| `online.open` | `PLAYER` | 打开在线奖励界面 |
| `shop.open` | `PLAYER` | 打开商店 |
| `title.open` | `PLAYER` | 打开称号界面 |
| `achievements.open` | `PLAYER` | 打开成就界面与领取奖励 |
| `storage.open` | `ADMIN` | 打开云端存储 |
| `currency.balance.self` | `PLAYER` | 查询自己的余额 |
| `currency.balance.other` | `ADMIN` | 查询其他玩家余额 |
| `currency.add` | `ADMIN` | 增加货币 |
| `currency.remove` | `ADMIN` | 扣除货币 |
| `checkin.clear` | `ADMIN` | 清除当日签到数据 |
| `title.grant` | `ADMIN` | 授予称号 |
| `title.revoke` | `ADMIN` | 回收称号 |
| `config.reload` | `ADMIN` | 重载配置 |

一个可编辑的最小示例：

```json
{
  "format_version": 1,
  "allow_title_command_grants": false,
  "commands": {
    "storage.open": {
      "role": "ADMIN",
      "allow_native_node": true
    },
    "config.reload": "ADMIN"
  }
}
```

未列出的动作保持默认角色。`commands` 本身必须存在，且不得包含未知动作 ID。控制台或没有实体的命令源始终允许执行命令。

`storage.open.allow_native_node` 默认 `true`：拥有原生权限节点 `omnitools:cloud_storage` 的玩家可打开云端存储，即使其 Minecraft 权限等级未达到该动作角色。设为 `false` 可只使用角色门槛。

`allow_title_command_grants` 默认 `false`。称号效果配置中的 `omnitools:command.*` 权限效果在此开关关闭时不会被授予；`omnitools:cloud_storage` 是允许的独立原生节点。

## 模块配置的使用方式

各模块 JSON 的具体字段由其功能决定。建议先让服务器生成默认文件，在副本中修改并进行 JSON 校验，再替换生产配置。以下是常见用途：

- 每日签到：调整每日货币、连续签到与月度奖励。
- 在线奖励：按每日累计在线时长定义奖励条目。
- 商店：定义可购买物品、数量和货币价格。
- 称号与称号效果：先定义称号，再将有效效果 ID 关联给称号。
- 成就：配置原版统计条件树和一次性奖励，详见[成就配置指南](achievements.md)。
- 云端存储：调整个人存储的容量和页面设置。

模块关闭后不会继续执行该模块的定时服务或开放相应入口。重新启用时，原有的世界数据仍会保留。关闭成就模块时不进行成就检查和领奖；关闭称号效果模块时，服务端会移除其管理的效果。

## 重载、迁移与备份

`/omnitools reload` 会触发历史配置迁移检查，然后尝试构造并校验完整的新配置快照。成功时所有相关服务一起切换；失败时日志会记录原因，旧快照继续运行。

历史根目录配置文件可能使用 `omnitools-*.json` 或更早的 `qiandao-*.json` 命名。迁移只会在目标模块配置尚不存在时复制内容；原文件不会被删除，副本会归档到 `config/omnitools/legacy/`，并在 `legacy/manifest.json` 记录来源和时间。

在生产服务器上执行以下操作前应完整备份：修改配置、启用或关闭模块、升级 OmniTools、手动处理历史配置。备份至少应包含世界目录和 `config/omnitools/`，以便在外部依赖或配置错误时恢复。
