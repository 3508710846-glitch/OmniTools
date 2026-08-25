# 奖励一致性与奖励箱

奖励账本按事件和奖励 ID 记录 `PENDING -> APPLYING -> GRANTED`，异常时会进入 `BLOCKED` 或 `FAILED`。物品投递失败不会丢失，会保留在玩家奖励箱；指令和崩溃边界的未知派发结果不会自动危险重放。

玩家使用 `/omnitools rewards open` 查看自己的待投递物品。管理员使用 `/omnitools rewards inspect <player> [event]` 定位事件，`retry` 重试安全项，`resolve <player> <event> grant|fail` 人工结案。操作会写入审计信息。

不要删除账本记录来处理异常，否则可能造成重复发奖。先备份世界数据，再按事件核对来源状态。
