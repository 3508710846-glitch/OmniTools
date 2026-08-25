# 每日签到

## 1. 模块用途和适用场景

每日签到按根配置的 `global.timezone` 记录每日一次签到，并支持每日奖励、月度里程碑、签到记录和奖励详情。它适合需要共享货币与可恢复奖励的服务器；界面为原版 6 行箱子，原版客户端可直接使用。

## 2. 模块依赖与关联模块

模块 ID 为 `daily_checkin`。货币使用共享 `CheckinData`；`title` 奖励要求 `titles` 已启用并有对应称号；`command` 奖励受根配置命令安全限制。奖励状态与异常处理见[奖励一致性](../guides/reward-consistency.md)。

## 3. 模块开关配置

在 `config/omnitools/config.json`：

```json
{ "modules": { "daily_checkin": { "enabled": true } } }
```

禁用会关闭签到、记录与奖励详情 GUI，并停止未签到提醒；不会删除签到、货币或奖励账本数据。

## 4. 初始配置文件位置

首次启动时生成 `config/omnitools/daily_checkin/config.json`。修改后执行 `/omnitools reload`；无效配置不会覆盖运行中的旧快照。

## 5. 最小可用配置

```json
{
  "format_version": 2,
  "daily": { "rewards": [] },
  "monthly": {}
}
```

## 6. 完整配置示例

```json
{
  "format_version": 2,
  "daily": {
    "rewards": [
      { "id": "daily_currency", "type": "currency", "amount": 100 },
      { "id": "daily_bread", "type": "item", "item": "minecraft:bread", "count": 3, "components": {} }
    ]
  },
  "monthly": {
    "5": [{ "id": "month_5_currency", "type": "currency", "amount": 500 }],
    "10": [{ "id": "month_10_title", "type": "title", "title": "loyal_player" }]
  }
}
```

命令奖励必须额外启用根配置的 `global.reward_security.allow_command_rewards`，并将命令根加入 `global.command_security.allowed_roots`。

## 7. 配置字段表

| 字段 | 类型 | 必填 | 默认值或范围 | 重载方式 |
| --- | --- | --- | --- | --- |
| `format_version` | integer | 是（v2） | `2` | reload |
| `daily.rewards` | array | 是 | 可为空 | reload |
| `monthly` | object | 是 | 键为正整数天数 | reload |
| `monthly.<days>` | array | 是 | 对应里程碑奖励 | reload |
| `rewards[].id` | string | 是 | `[a-z0-9_.-]{1,64}`，同数组唯一 | reload |
| `rewards[].type` | string | 是 | `currency`、`item`、`title`、`command` | reload |
| `amount` | integer | currency 时 | 非负 | reload |
| `item`、`count`、`components` | string、integer、object | item 时 | 有效物品，`count` 为 1-64 | reload |
| `title` | string | title 时 | 已定义的称号 ID | reload |
| `run_as`、`command` | string | command 时 | 仅 `console`；受全局长度与白名单限制 | reload |

旧 `dailyCoins`、`dailyReward`、`monthlyRewards` 和 `monthlyCoins` 仍能读取，但新配置应使用 v2。不要改名已发放奖励的 `id`。

## 8. 指令、别名和权限节点

| 指令 | 别名 | 权限节点 | 默认角色 |
| --- | --- | --- | --- |
| `/omnitools`、`/omnitools open` | `/checkin` | `checkin.open` | PLAYER |
| `/omnitools clear [today]` | `/checkin clear [today]` | `checkin.clear` | ADMIN |
| `/omnitools rewards open` | 无 | `rewards.retry` | PLAYER |

## 9. GUI 操作说明

主界面是 6 行周历：周一至周日在前七列，右侧两列固定显示玩家摘要、今日签到、奖励详情、本月进度、记录、成就、连续签到和货币。只有今天日期格与“今日签到”按钮可签到，二者调用同一服务端校验。奖励详情进入只读分页页；记录和成就入口保留原有跳转。界面物品不可取走。

## 10. 占位符列表及用途

`%omnitools:checkin_today%`、`checkin_today_rank`、`checkin_total_days`、`checkin_streak_days`、`checkin_month_days` 用于侧边栏和可配置文本。详情见[Placeholder API](../guides/placeholder-api.md)。

## 11. 数据保存位置和升级影响

签到日期、排名、连续天数、月度状态与货币保存在世界 `SavedData` 的 `CheckinData`；奖励状态在 `RewardClaimLedger`。JSON 只保存规则。升级时保留 SavedData 与奖励 ID，并同时备份世界和 `config/omnitools/`。

## 12. 与其他模块的联动

签到货币可在商店、云存储扩容与其他奖励中使用；称号奖励与称号模块联动；成就入口仅在成就模块可用时打开。奖励箱和管理员账本是跨模块入口。

## 13. 常见错误及解决方法

| 现象 | 处理 |
| --- | --- |
| 今天无法签到 | 检查时区、当日状态与 `checkin.open` 权限；同一天不能重复签到。 |
| 称号奖励阻塞 | 启用 `titles` 并定义相同 ID，随后 reload 或在奖励箱重试。 |
| 物品未进入背包 | 腾出完整空间后打开奖励箱再次点击；不要用重连复制奖励。 |
| 配置未生效 | 修正日志中的 JSON 错误后执行 `/omnitools reload`。 |

## 14. 可复制的验收清单

- [ ] 月历按周一开始，任意月份的日期位置正确。
- [ ] 今天只能签到一次，重复点击不会重复发奖。
- [ ] 奖励详情、记录和成就入口可用，奖励项可分页查看。
- [ ] 禁用模块时相关 GUI 关闭，重新启用后历史数据保留。
