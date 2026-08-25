# 备份与恢复

玩家货币、签到、在线时长、称号、成就、云储存和奖励账本均保存在世界的 `SavedData` 中；`config/omnitools/` 只保存规则与模块开关。升级、修改奖励、启用数据保留或更换服务端前，应同时备份世界目录和 `config/omnitools/`。

恢复时关闭服务端，恢复同一时间点的世界与配置备份，再启动服务端。不要只恢复配置或只恢复世界：奖励事件与配置奖励 ID 必须对应，才能正确判断已发放状态。

启用 `monthly_summary` 或 `archive` 前，系统会在 `config/omnitools/archives/` 写出签到数据的 SNBT 快照。该归档用于审计与人工恢复；恢复整份历史数据时应恢复完整世界备份，而不是直接编辑 SavedData 文件。

奖励账本中的 `BLOCKED`、`FAILED` 或启动时遗留的 `APPLYING` 项可由管理员排查：

```text
/omnitools rewards inspect <player> [event]
/omnitools rewards retry <player> <event>
/omnitools rewards resolve <player> <event> grant
/omnitools rewards resolve <player> <event> fail
```

`inspect` 可用于离线玩家。`retry` 要求目标在线，因为物品、称号显示和命令执行需要实际玩家上下文。`resolve grant` 只确认管理员已经完成补偿，不会再次执行奖励副作用；成就和月度签到的来源状态会同步为已确认。
