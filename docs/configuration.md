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
├── command_menu/config.json
├── command_menu/menus/
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
| `command_menu/config.json` | 命令菜单注册表；菜单内容位于同目录的 `menus/` |
| `permissions/config.json` | 指令动作的最低角色与安全开关；仅在权限模块启用时生效 |
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
    "permissions": { "enabled": false },
    "command_menu": { "enabled": true }
  }
}
```

| 字段 | 说明 |
| --- | --- |
| `global.debug` | 全局调试标记，默认 `false` |
| `global.timezone` | 有效的 Java `ZoneId`；默认 `Asia/Shanghai`，用于签到日期与在线时长的跨日切分 |
| `integrations.placeholder_api.enabled` | Placeholder API 集成总开关，默认 `true` |
| `modules.*.enabled` | 对应功能模块是否启用 |

关闭功能模块不会删除玩家已有数据。依赖其他模块的功能仍需满足其引用关系；例如 `title_effects` 已启用且其中定义了效果时，`titles` 也必须启用。`permissions` 关闭时，所有指令动作回退到源码默认角色；启用后才读取并应用 `permissions/config.json` 的角色覆盖项，不会变成无条件放行。

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
| `config.reload` | `ADMIN` | 重载配置，并打开模块管理界面 |
| `command_menu.open` | `PLAYER` | 打开命令菜单 |
| `command_menu.close` | `PLAYER` | 关闭当前命令菜单 |

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

## 模块管理

管理员可使用 `/omnitools modules` 打开模块管理界面。该入口复用 `config.reload` 动作权限，默认角色为 `ADMIN`，并且只能由游戏内玩家执行；控制台仍可使用 `/omnitools reload`，但不能打开箱子 GUI。菜单打开和每次点击时都会再次验证该权限。

界面展示根配置 `config/omnitools/config.json` 中的九个 `modules.*.enabled` 开关：

| 模块 ID | 管理的功能 |
| --- | --- |
| `daily_checkin` | 每日签到与签到记录 |
| `online_reward` | 在线时长累计与在线奖励 |
| `shop` | 商店入口与购买 |
| `titles` | 称号界面、聊天、Tab 和头顶显示 |
| `title_effects` | 称号关联的效果 |
| `achievements` | 成就检查、界面与领奖 |
| `cloud_storage` | 云端存储入口 |
| `permissions` | `permissions/config.json` 中的动作角色覆盖项 |
| `command_menu` | 自定义命令菜单与点击动作 |

左键点击图标可切换对应模块。成功后服务器会刷新在线玩家的命令树、关闭已失效的模块菜单，并应用对应的运行时处理：停止在线奖励前会先保存在线时长；禁用称号效果会移除其管理的效果；重新启用成就会立即进行一次检查。模块关闭不会删除已有 `SavedData`。

每次切换都按事务处理：服务端以当前有效快照为基础构造候选根配置，加载相关子配置并完成校验，校验成功后才原子写入根配置并发布新快照。因此启用模块时若子配置无效，或配置引用不合法，菜单会显示失败原因，磁盘与运行状态都会保留在切换前。菜单中的“重新读取磁盘配置”按钮与 `/omnitools reload` 完全等价。

依赖关系不会静默级联切换：启用 `title_effects` 时 `titles` 必须已启用；当 `title_effects` 已启用且存在效果定义时，不能关闭 `titles`，应先关闭称号效果或清空其效果配置。`permissions` 禁用时所有动作回退到源码默认角色，不会变成无条件放行；启用时才读取并应用 `permissions/config.json`。`integrations.placeholder_api.enabled` 是独立集成选项，不属于模块开关，也不会出现在此菜单中。

## 模块配置的使用方式

各模块 JSON 的具体字段由其功能决定。建议先让服务器生成默认文件，在副本中修改并进行 JSON 校验，再替换生产配置。以下是常见用途：

- 每日签到：调整每日货币、连续签到与月度奖励。
- 在线奖励：按每日累计在线时长定义奖励条目。
- 商店：定义可购买物品、数量和货币价格。
- 称号与称号效果：先定义称号，再将有效效果 ID 关联给称号。
- 成就：配置原版统计条件树和一次性奖励，详见[成就配置指南](achievements.md)。
- 云端存储：调整个人存储的容量和页面设置。
- 命令菜单：使用独立注册表和 `menus/` 文件配置原版箱子菜单，详见[命令菜单配置指南](command-menu.md)。

模块关闭后不会继续执行该模块的定时服务或开放相应入口。重新启用时，原有的世界数据仍会保留。关闭成就模块时不进行成就检查和领奖；关闭称号效果模块时，服务端会移除其管理的效果。

## 重载、迁移与备份

`/omnitools reload` 会触发历史配置迁移检查，然后尝试构造并校验完整的新配置快照。成功时所有相关服务一起切换；失败时日志会记录原因，旧快照继续运行。管理员还可在游戏内使用 `/omnitools modules` 打开模块管理界面；单项切换会先校验候选配置，再原子写入根配置并应用同一套运行时补偿逻辑。

历史根目录配置文件可能使用 `omnitools-*.json` 或更早的 `qiandao-*.json` 命名。迁移只会在目标模块配置尚不存在时复制内容；原文件不会被删除，副本会归档到 `config/omnitools/legacy/`，并在 `legacy/manifest.json` 记录来源和时间。

在生产服务器上执行以下操作前应完整备份：修改配置、启用或关闭模块、升级 OmniTools、手动处理历史配置。备份至少应包含世界目录和 `config/omnitools/`，以便在外部依赖或配置错误时恢复。
