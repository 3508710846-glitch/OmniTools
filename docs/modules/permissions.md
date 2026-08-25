# 权限

## 1. 模块用途和适用场景

权限模块为每个 `CommandAction` 配置最低角色，并可为云存储保留原生权限节点。它是覆盖层：关闭时回退到代码中的安全默认角色，不会无条件放行命令。

## 2. 模块依赖与关联模块

模块 ID 为 `permissions`，默认关闭。它影响签到、在线奖励、商店、称号、成就、云存储、命令菜单、侧边栏、奖励与配置管理命令；不保存玩家业务数据。

## 3. 模块开关配置

```json
{ "modules": { "permissions": { "enabled": true } } }
```

禁用后，所有动作立即回退到内置默认角色，已打开但失去权限的 GUI 会关闭。重新启用时加载本模块配置。

## 4. 初始配置文件位置

启用后首次加载生成 `config/omnitools/permissions/config.json`。修改后需要 `/omnitools reload`。

## 5. 最小可用配置

```json
{
  "format_version": 1,
  "allow_title_command_grants": false,
  "commands": {}
}
```

空 `commands` 使用每个动作的内置默认角色。

## 6. 完整配置示例

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
    "storage.open": { "role": "ADMIN", "allow_native_node": true },
    "currency.add": "ADMIN",
    "config.reload": "ADMIN",
    "diagnose": "ADMIN",
    "rewards.admin": "ADMIN"
  }
}
```

## 7. 配置字段表

| 字段 | 类型 | 必填 | 默认值或范围 | 重载方式 |
| --- | --- | --- | --- | --- |
| `format_version` | integer | 否 | 首次生成 `1` | reload |
| `allow_title_command_grants` | boolean | 否 | `false` | reload |
| `commands` | object | 否 | 空对象使用代码默认角色 | reload |
| `commands.<action>` | string 或 object | 否 | `PLAYER`、`MODERATOR`、`ADMIN`、`OWNER` | reload |
| `commands.storage.open.allow_native_node` | boolean | 否 | `true` | reload |

支持的动作：`checkin.open`、`online.open`、`shop.open`、`title.open`、`achievements.open`、`storage.open`、`currency.balance.self`、`currency.balance.other`、`currency.add`、`currency.remove`、`checkin.clear`、`title.grant`、`title.revoke`、`config.reload`、`diagnose`、`command_menu.open`、`command_menu.close`、`sidebar.toggle`、`sidebar.status`、`rewards.retry`、`rewards.admin`。

## 8. 指令、别名和权限节点

角色对应原版权限等级：`PLAYER=0`、`MODERATOR=1`、`ADMIN=2`、`OWNER=4`。每个模块页列出它使用的动作。原生节点仅为 `omnitools:cloud_storage`，需在 `storage.open` 中允许；称号的 PERMISSION 效果还受 `allow_title_command_grants` 控制。

## 9. GUI 操作说明

权限没有玩家 GUI。管理员通过模块管理 GUI 启用或禁用本模块；各业务 GUI 在权限失效时由服务端关闭，玩家不能通过旧窗口绕过权限。

## 10. 占位符列表及用途

权限模块没有专属占位符。可用所有通用占位符的文本展示不等于获得对应命令权限，见[Placeholder API](../guides/placeholder-api.md)。

## 11. 数据保存位置和升级影响

规则保存在 `permissions/config.json`，不保存玩家业务数据。禁用或升级不会重置任何货币、签到、称号、成就、存储或奖励账本。

## 12. 与其他模块的联动

所有注册命令都会查询本模块；云存储可使用原生节点；称号权限效果受总开关保护。命令菜单和奖励命令的白名单仍由根 `command_security` 管理，不由本模块放宽。

## 13. 常见错误及解决方法

| 现象 | 处理 |
| --- | --- |
| 玩家无法使用命令 | 检查动作名称、角色等级、模块开关和原版 OP 等级。 |
| 设置无效 | 使用大写角色名；修复 JSON 后 reload。 |
| 称号没有授予权限 | 检查 `allow_title_command_grants`、PERMISSION 效果和节点白名单。 |

## 14. 可复制的验收清单

- [ ] PLAYER 无法使用管理员命令，ADMIN 可使用配置管理与账本命令。
- [ ] `storage.open` 的原生节点按开关工作。
- [ ] 禁用权限模块后，命令回退到内置默认角色。
- [ ] 权限改变后已失效的 GUI 被关闭。
