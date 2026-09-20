# 命令与权限操作手册

本页是管理员的命令索引。命令由服务端注册，原版客户端可以使用；但对应模块关闭、权限动作被拒绝，或命令要求玩家在线时，命令不会执行。

完整权限字段和默认角色见[权限模块](../modules/permissions.md)，各模块的参数、界面和配置说明见对应模块页面。

## 先理解权限模型

OmniTools 不使用独立的第三方权限插件节点。每项命令映射为一个“权限动作”，默认角色与原版权限等级对应：

| 角色 | 原版权限等级 | 常见用途 |
| --- | ---: | --- |
| `PLAYER` | 0 | 打开玩家界面、查询自己的余额、领取奖励 |
| `MODERATOR` | 1 | 适合由管理员在配置中分配有限运营动作 |
| `ADMIN` | 2 | 重载、发奖、查看账本、管理货币 |
| `OWNER` | 4 | 适合保留给服主的高风险操作 |

在 `config/omnitools/permissions/config.json` 的 `commands` 中覆写动作即可。例如，以下配置让普通玩家可以打开云仓，但仍将恢复操作保留给服主：

```json
{
  "format_version": 1,
  "allow_title_command_grants": false,
  "commands": {
    "storage.open": "PLAYER",
    "storage.recovery": "OWNER"
  }
}
```

未写出的动作使用内置默认角色。修改权限配置后执行完整重载：

```text
/omnitools reload
```

在调整权限前，至少保留一个可使用 `config.reload` 的管理员账号；否则配置写错后无法在游戏内恢复。

## 玩家入口

下表中的“根命令”是最短常用写法。除特别说明外，也可以在前面加上 `/omnitools`，例如 `/omnitools shop`。

| 根命令 | 权限动作 | 条件与作用 |
| --- | --- | --- |
| `/checkin` 或 `/omnitools` | `checkin.open` | 打开每日签到。 |
| `/online` | `online.open` | 打开在线奖励。需要 `online_reward` 模块。 |
| `/shop` | `shop.open` | 打开商店。需要 `shop` 模块。 |
| `/titles` | `title.open` | 打开称号界面。需要 `titles` 模块。 |
| `/achievements` | `achievements.open` | 打开成就界面。 |
| `/leaderboard` | `leaderboards.open` | 打开排行榜。需要 `leaderboards` 模块。 |
| `/top <id> [page]` | `leaderboards.chat` | 将指定排行榜页发送到自己的聊天栏。需要 `leaderboards` 模块。 |
| `/packages` | `package.open` | 打开自己的礼包列表。需要 `packages` 模块。 |
| `/skills` | `skills.open` | 打开技能界面；`stats` 查看详情，`ability <skill>` 查看能力。 |
| `/divination`、`/fortune` | `divination.open` | 打开每日占卜。 |
| `/menu [open <menu_id>]` | `command_menu.open` | 打开命令菜单；省略参数时打开 `main`。 |
| `/sidebar on|off|toggle|status` | `sidebar.toggle` / `sidebar.status` | 控制或查询自己的侧边栏。 |
| `/money`、`/balance` | `currency.balance.self` | 查询自己的共享钱包余额。 |
| `/omnitools rewards open` | `rewards.retry` | 打开自己的待投递奖励箱。 |
| `/omnitools cdk redeem <code>` | `cdk.redeem` | 兑换 CDK。 |

`/cloudstorage`、`/cstorage` 与 `/omnitools storage` 是云仓入口的兼容别名。云仓默认要求 `storage.open` 的 `ADMIN` 角色；向玩家开放前，应先在测试服验证存取、关闭和断线恢复。

## 管理员日常命令

| 命令 | 权限动作 | 用途 |
| --- | --- | --- |
| `/omnitools reload` | `config.reload` | 校验并发布整套配置快照。修改根配置或 `common/` 后使用。 |
| `/omnitools reload <module-id>` | `config.reload` | 仅重载一个模块配置；不可用于根配置或 `common/`。 |
| `/omnitools modules` | `config.reload` | 打开模块管理界面。 |
| `/omnitools diagnose` | `diagnose` | 只读查看配置版本、模块健康、资源预算和审计队列。 |
| `/omnitools diagnose operation <operationId>` | `diagnose` | 跨云仓、技能经验、奖励与商店账本定位一个操作。 |
| `/omnitools currency add <player> <amount>` | `currency.add` | 增加共享钱包货币。 |
| `/omnitools currency remove <player> <amount>` | `currency.remove` | 扣除共享钱包货币。`deduct`、`take` 是同义写法。 |
| `/omnitools balance <player>` | `currency.balance.other` | 查询其他玩家余额。 |
| `/omnitools shop audit [transactionUuid]` | `shop.audit` | 查看商店购买摘要或单笔交易。 |
| `/omnitools skills health` | `skills.admin` | 查看技能经验成功、拒绝和事务状态。 |
| `/omnitools skills add <skill> <amount>` | `skills.admin` | 向执行命令的在线玩家发放某技能经验；使用 canonical ID。 |
| `/omnitools cdk admin list` | `cdk.admin` | 查看 CDK 活动与兑换摘要。 |
| `/omnitools cdk admin audit <id>` | `cdk.admin` | 查看指定 CDK 活动审计。 |

管理称号、补签卡、签到清理、礼包实例和奖励账本均有独立子命令。它们会修改持久化数据，应先阅读对应模块页面，不要把试错操作直接用于正式玩家数据。

## 故障恢复命令

以下命令属于人工处理闭环，不是“重新发一遍”的快捷键。先停下重复操作、备份世界目录，再用只读命令收集证据。

| 命令 | 权限动作 | 使用规则 |
| --- | --- | --- |
| `/omnitools storage recovery list` | `storage.recovery` | 列出 `PREPARED` 或待处理的 `QUARANTINED` 云仓操作。 |
| `/omnitools storage recovery inspect <operationUuid>` | `storage.recovery` | 查看页面前后快照摘要、会话和哈希；不改数据。 |
| `/omnitools storage recovery resolve <operationUuid> commit` | `storage.recovery` | 明确认可操作后页面为正确结果。 |
| `/omnitools storage recovery resolve <operationUuid> rollback` | `storage.recovery` | 明确认可操作前页面为正确结果。 |
| `/omnitools rewards inspect <player> [event]` | `rewards.admin` | 查看奖励账本事件。 |
| `/omnitools rewards retry [event]` | `rewards.retry` | 对自己的可重试奖励进行安全重试。 |
| `/omnitools rewards resolve <player> <event> grant|fail` | `rewards.admin` | 对已核对的奖励账本进行人工裁决。 |
| `/omnitools package inspect <player> <instanceUuid>` | `package.inspect` | 查看礼包实例、投递批次及堆状态。 |
| `/omnitools package resolve <player> <instanceUuid> <stackUuid> delivered confirm` | `package.resolve` | 仅当已确认该堆已经发出时使用。 |
| `/omnitools package resolve <player> <instanceUuid> <stackUuid> pending confirm` | `package.resolve` | 仅当已确认该堆未发出时使用，之后才允许安全重投。 |

`package.cancel`、`package.remove`、奖励 `resolve` 和云仓 `resolve` 都是高风险操作。操作前必须记录玩家、时间、操作 ID、物品/货币摘要和判定理由，并保存备份。

## 管理员执行顺序

1. 先用 `/omnitools diagnose` 确认模块未被关闭或降级。
2. 使用 `inspect` 或 `diagnose operation` 获取操作 ID 对应的账本状态。
3. 涉及物品、货币或页面快照时，停止玩家继续操作并备份世界 `data/`。
4. 只在证据能确定“已发放”或“未发放”时使用 `resolve`；无法确定时保留记录，收集日志后再处理。

不要通过删除 `world/data/`、奖励账本或 journal 来让命令“重新可用”。这会把原本可追溯的异常变成无法判定的重复发放或物品丢失。
