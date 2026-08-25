# 奖励一致性

每条奖励由稳定的 `id` 标识，账本以“事件 ID + 奖励 ID”记录状态。

```text
PENDING -> APPLYING -> GRANTED
                    -> BLOCKED / FAILED
```

货币和称号在各自的世界持久化数据中同时记录奖励事件键。服务器在写入后崩溃时，玩家下次上线会对账并完成账本，不会重复增加货币或重复授予称号。

物品奖励会先将物品快照写入账本的持久待投递记录。背包不能完整容纳时保持 `PENDING`，不会掉落或吞掉物品。物品写入背包与世界账本属于不同存档，无法获得跨存档的严格事务：若进程恰好在投递中断，启动扫描会将该项标记为 `BLOCKED (item_delivery_outcome_unknown)`，管理员先检查背包再用 `resolve` 确认，系统不会冒险自动重放并复制物品。

指令奖励只允许控制台身份。执行前会记录派发时间和解析后的命令；进程中断或异常后会进入 `BLOCKED`，不会自动重放。该策略保证最多派发一次，避免重复执行 `give`、权限修改等外部副作用。管理员可根据审计信息手动补偿后执行 `resolve grant`，或确认失败后执行 `resolve fail`。

指令奖励默认关闭。启用时既要设置 `global.reward_security.allow_command_rewards`，又要在 `global.command_security.allowed_roots` 中列出允许的命令根。未知占位符、换行、空命令、过长命令和不在白名单内的命令会使配置热重载失败。
