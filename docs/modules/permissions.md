# 指令权限

## 1. 功能简介

权限模块为每个 `CommandAction` 配置最低角色，并提供云端存储原生权限节点和称号命令权限效果的安全开关。它是覆盖层，不会替代源码中的默认权限。

## 2. 模块开关

根配置为 `config/omnitools/config.json`：

```json
{
  "modules": {
    "permissions": { "enabled": false }
  }
}
```

根配置默认关闭权限模块。关闭时所有动作回退到 `CommandAction` 的源码默认角色，不会无条件放行；重新启用后读取权限配置覆盖项。已有配置和玩家数据保留。

## 3. 初始配置

启用后首次加载生成 `config/omnitools/permissions/config.json`。以下是完整的首次生成配置，所有角色均来自 `CommandAction` 的源码默认值：

```json
{
  "format_version": 1,
  "allow_title_command_grants": false,
  "commands": {
    "checkin.open": "PLAYER",
    "online.open": "PLAYER",
    "shop.open": "PLAYER",
    "title.open": "PLAYER",
    "achievements.open": "PLAYER",
    "storage.open": {
      "role": "ADMIN",
      "allow_native_node": true
    },
    "currency.balance.self": "PLAYER",
    "currency.balance.other": "ADMIN",
    "currency.add": "ADMIN",
    "currency.remove": "ADMIN",
    "checkin.clear": "ADMIN",
    "title.grant": "ADMIN",
    "title.revoke": "ADMIN",
    "config.reload": "ADMIN",
    "command_menu.open": "PLAYER",
    "command_menu.close": "PLAYER",
    "sidebar.toggle": "PLAYER",
    "sidebar.status": "PLAYER",
    "rewards.retry": "PLAYER"
  }
}
```

文件缺失时生成默认配置；错误时不采用新配置，旧快照继续运行。

## 4. 指令与权限

角色对应原版权限等级：`PLAYER`=0、`MODERATOR`=1、`ADMIN`=2、`OWNER`=4。以下是源码中的全部动作：

| 指令入口 | 别名 | 用途 | 默认权限 | 仅玩家 |
| --- | --- | --- | --- | --- |
| `/omnitools`、`/omnitools open` | `/checkin` | 签到菜单 | `checkin.open` (`PLAYER`) | 是 |
| `/omnitools online [rewards]` | `/checkin online [rewards]` | 在线奖励菜单 | `online.open` (`PLAYER`) | 是 |
| `/omnitools shop [open]` | `/checkin shop [open]` | 商店 | `shop.open` (`PLAYER`) | 是 |
| `/omnitools title [open]` | `/checkin title [open]`、`/title [open]` | 称号菜单 | `title.open` (`PLAYER`) | 是 |
| `/omnitools achievements [open]` | `/checkin achievements [open]` | 成就菜单和领取 | `achievements.open` (`PLAYER`) | 是 |
| `/omnitools storage [open]` | `/checkin storage [open]`、`/cloudstorage [open]`、`/cstorage [open]` | 云端存储 | `storage.open` (`ADMIN`) | 是 |
| `/omnitools currency`、`/money` | `/checkin currency` | 查询自己的余额 | `currency.balance.self` (`PLAYER`) | 是 |
| `/omnitools currency balance|get [玩家]` | `/checkin currency balance|get [玩家]`、`/money balance|get [玩家]`、`/omnitools balance [玩家]`、`/checkin balance [玩家]`、`/balance [玩家]` | 查询余额 | 自己：`currency.balance.self`；他人：`currency.balance.other` (`ADMIN`) | 自己查询是 |
| `/omnitools currency add <玩家> <数量>`、`/omnitools add ...` | `/checkin currency add ...`、`/money add ...` | 增加货币 | `currency.add` (`ADMIN`) | 否 |
| `/omnitools currency remove|deduct|take <玩家> <数量>`、`/omnitools remove ...` | `/checkin currency remove|deduct|take ...`、`/money remove|deduct|take ...` | 扣除货币 | `currency.remove` (`ADMIN`) | 否 |
| `/omnitools clear [today]` | `/checkin clear [today]` | 清除当天签到 | `checkin.clear` (`ADMIN`) | 否 |
| `/omnitools title give|add ...` | `/checkin title give|add ...`、`/title give|add ...` | 授予称号 | `title.grant` (`ADMIN`) | 否 |
| `/omnitools title remove|take ...` | `/checkin title remove|take ...`、`/title remove|take ...` | 回收称号 | `title.revoke` (`ADMIN`) | 否 |
| `/omnitools reload` | 无 | 完整配置重载 | `config.reload` (`ADMIN`) | 否 |
| `/omnitools modules` | 无 | 打开模块管理 GUI | `config.reload` (`ADMIN`) | 是 |
| `/omnitools menu open` | `/omnitools menu`、`main` | 打开命令菜单 | `command_menu.open` (`PLAYER`) | 是 |
| `/omnitools menu close` | 无 | 关闭命令菜单 | `command_menu.close` (`PLAYER`) | 是 |
| `/omnitools sidebar on/off/toggle` | 无 | 修改个人侧边栏 | `sidebar.toggle` (`PLAYER`) | 是 |
| `/omnitools sidebar status` | 无 | 查看个人侧边栏状态 | `sidebar.status` (`PLAYER`) | 是 |
| `/omnitools rewards retry` | 无 | 重试自己的待处理签到和成就奖励 | `rewards.retry` (`PLAYER`) | 是 |

## 5. 配置字段

| 字段 | JSON 类型 | 必填 | 默认值/范围 | 作用与错误行为 |
| --- | --- | --- | --- | --- |
| `format_version` | number | 否 | `1` | 正整数。 |
| `allow_title_command_grants` | boolean | 否 | `false` | 是否允许称号效果授予 `omnitools:command.*` 节点。 |
| `commands` | object | 是 | 每个动作使用源码默认角色 | 根字段缺失或未知动作 ID 会拒绝配置。单个已知动作可省略，省略后回退该动作的源码默认角色。 |
| `commands.<action>` | string/object | 否 | 对应默认角色 | 覆盖单个动作最低角色。 |
| `commands.storage.open.allow_native_node` | boolean | 否 | `true` | 是否允许 `omnitools:cloud_storage` 原生节点绕过角色门槛。 |

## 6. 使用示例

将在线奖励交给管理员、保留玩家自查余额：

```json
{
  "format_version": 1,
  "allow_title_command_grants": false,
  "commands": {
    "online.open": "ADMIN",
    "currency.balance.self": "PLAYER"
  }
}
```

修改后执行 `/omnitools reload`。非法动作 ID、角色或字段会被拒绝；失败时旧权限快照继续生效。

## 7. 数据保存

权限 JSON 只保存角色覆盖和安全开关，不保存玩家进度。模块关闭不会删除权限文件或世界 `SavedData`。

升级或迁移前必须同时备份世界目录和 `config/omnitools/`。

## 8. 热重载与依赖

重载成功后在线玩家命令树刷新，已打开但失去权限的菜单关闭；模块 GUI 每次打开和点击都会再次检查 `config.reload`。关闭权限模块后即时回退源码默认角色。权限配置错误时不会变成无条件放行。
