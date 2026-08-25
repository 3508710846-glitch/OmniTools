# 奖励一致性与奖励箱

签到、在线奖励和成就共用稳定奖励定义与 `RewardClaimLedger`。账本以“事件 ID + 奖励 ID”记录状态：

```text
PENDING -> APPLYING -> GRANTED
                    -> BLOCKED / FAILED
```

货币和称号与各自持久化数据一起记录事件键，提供可验证的至多一次发放。物品先进入持久化待投递记录；背包空间不足时保持待领取，玩家通过 `/omnitools rewards open` 再次点击投递。指令只以控制台身份执行，采用最多一次派发策略。

服务器在物品投递或命令派发的崩溃边界不会自动重放：相关项会进入 `BLOCKED`，原因可能是 `item_delivery_outcome_unknown` 或 `command_dispatch_outcome_unknown`。管理员应先核对实际结果，再结案。

## 玩家入口

| 命令 | 权限 | 用途 |
| --- | --- | --- |
| `/omnitools rewards open` | `rewards.retry`，默认 `PLAYER` | 打开自己的待领取/待重试物品奖励箱 |
| `/omnitools rewards retry` | `rewards.retry`，默认 `PLAYER` | 重试可安全重试的待处理奖励 |

## 管理员入口

| 命令 | 权限 | 用途 |
| --- | --- | --- |
| `/omnitools rewards inspect <player> [event]` | `rewards.admin`，默认 `ADMIN` | 检查账本条目 |
| `/omnitools rewards retry <player> <event>` | `rewards.admin`，默认 `ADMIN` | 仅对可安全重试项执行重试 |
| `/omnitools rewards resolve <player> <event> grant|fail` | `rewards.admin`，默认 `ADMIN` | 人工确认已补偿或已失败，不重放副作用 |
| `/omnitools rewards admin` | `rewards.admin`，默认 `ADMIN` | 打开按状态筛选的原版账本 GUI |

管理员 GUI 显示事件 ID、玩家、奖励类型、阻塞原因与已解析命令。它只允许标记已处理或失败，并记录操作者、状态变更和时间。

## 命令奖励安全

命令奖励同时需要 `global.reward_security.allow_command_rewards: true` 和 `global.command_security.allowed_roots` 中的显式命令根。新安装的白名单为空；不要使用 `"*"`。命令长度受全局上限限制，禁止换行与未受控变量。

## 验收清单

- [ ] 背包满时，物品奖励在奖励箱中保留。
- [ ] 重连与 reload 不会重复发放已 `GRANTED` 的奖励。
- [ ] 崩溃边界的物品和指令项不会自动重放。
- [ ] 管理员可检查、结案并从审计日志追溯异常项。
