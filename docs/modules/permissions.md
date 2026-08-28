# 权限

## 1. 用途与场景

权限模块为 OmniTools 命令动作指定 Minecraft 原生权限等级。角色不是第三方权限插件节点：`PLAYER`、`MODERATOR`、`ADMIN`、`OWNER` 分别对应原生命令等级 0、1、2、4。

## 2. 前置条件、关联模块与开关

根开关为 `modules.permissions.enabled`。关闭后模块保留内置管理员绕过和每个动作的默认角色；不要把它理解为删除原生命令权限。

## 3. 配置路径与重载

文件为 `config/omnitools/permissions/config.json`，修改后执行 `/omnitools reload`。

## 4. 最小可用配置

下方以 `PLAYER` 与 `ADMIN` 两个角色构成最小可用配置。

## 5. 注释教学版 `jsonc`

教学版，不能直接复制：

```jsonc
{
  "format_version": 1, // 权限配置格式版本。
  "allow_title_command_grants": false, // 是否允许原生命令授予称号
  "commands": { // 动作 ID 到内置角色的映射。
    "checkin.open": "PLAYER", // 字符串简写
    "storage.open": { "role": "ADMIN", "allow_native_node": true } // 完整对象只用于 storage.open
  }
}
```

## 6. 可直接复制版 `json`

```json
{
  "format_version": 1,
  "allow_title_command_grants": false,
  "commands": {
    "checkin.open": "PLAYER",
    "checkin.makeup": "PLAYER",
    "checkin.cards.buy": "PLAYER",
    "checkin.cards.admin": "ADMIN",
    "online.open": "PLAYER",
    "shop.open": "PLAYER",
    "title.open": "PLAYER",
    "achievements.open": "PLAYER",
    "storage.open": { "role": "ADMIN", "allow_native_node": true },
    "currency.balance.self": "PLAYER",
    "currency.balance.other": "ADMIN",
    "currency.add": "ADMIN",
    "currency.remove": "ADMIN",
    "checkin.clear": "ADMIN",
    "title.grant": "ADMIN",
    "title.revoke": "ADMIN",
    "config.reload": "ADMIN",
    "diagnose": "ADMIN",
    "command_menu.open": "PLAYER",
    "command_menu.close": "PLAYER",
    "sidebar.toggle": "PLAYER",
    "sidebar.status": "PLAYER",
    "rewards.retry": "PLAYER",
    "rewards.admin": "ADMIN",
    "cdk.redeem": "PLAYER",
    "cdk.admin": "ADMIN",
    "leaderboards.open": "PLAYER",
    "leaderboards.chat": "PLAYER",
    "package.open": "PLAYER",
    "package.give": "ADMIN",
    "package.inspect": "ADMIN",
    "package.remove": "ADMIN",
    "package.resolve": "ADMIN",
    "package.cancel": "ADMIN"
  }
}
```

## 7. 字段表

| 字段 | 类型 | 必填 | 默认/范围 | 常见错误 |
| --- | --- | --- | --- | --- |
| `format_version` | 整数 | 否 | 1 | 重复出现。 |
| `allow_title_command_grants` | 布尔 | 否 | false | 写成字符串。 |
| `commands` | 对象 | 是 | 动作 ID -> 角色 | 写未知动作 ID。 |
| 角色简写 | 字符串 | 是 | PLAYER/MODERATOR/ADMIN/OWNER | 写 `OP`。 |
| `storage.open` 完整写法 | 对象 | 可选 | `role` 必填，`allow_native_node` 可选 | 在其他动作使用对象。 |

## 8. 全部配置场景

可只写需要覆盖的动作，未写的动作使用代码默认角色。`storage.open` 的对象写法用于控制原生命令节点授权；其他动作仅接受角色字符串。

## 9. 指令、权限与默认角色

可复制版列出了全部当前动作和默认角色。`/omnitools reload`、`diagnose`、奖励账本管理默认 `ADMIN`，普通 GUI 默认 `PLAYER`。

## 10. 占位符

没有权限专属占位符。

## 11. 数据与升级

配置重载即时生效；它不重置货币、签到或奖励账本。修改管理员权限前保留一个 Owner 账号。

## 12. 验收与排错

用不同权限等级测试 `/checkin`、`/omnitools reload` 和 `/omnitools storage`。拒绝访问先检查动作 ID，而不是尝试客户端权限模组。
