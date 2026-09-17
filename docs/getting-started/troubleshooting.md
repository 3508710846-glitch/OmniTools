# 排错

| 现象 | 检查 |
| --- | --- |
| 修改不生效 | 执行 `/omnitools reload`；检查 JSON 是否有注释、尾逗号或错误字段。 |
| 指令奖励或菜单命令不执行 | 根配置必须启用 `allow_command_rewards`（仅奖励）并在 `allowed_roots` 列出命令根。 |
| 占位符显示 `-` | 检查拼写；第三方占位符还需安装 Text Placeholder API 并启用集成。 |
| 模块菜单打不开 | 检查根配置模块开关、对应权限角色和控制台日志。 |
| 物品奖励未入背包 | 用 `/omnitools rewards open` 打开奖励箱；不要重复签到。 |

管理员可执行 `/omnitools diagnose` 查看配置版本、模块状态、Placeholder API、命令白名单、未处理奖励、统一操作账本、资源预算和审计队列。使用 `/omnitools diagnose operation <操作ID>` 可跨云存储、技能经验、商店和奖励账本定位一笔操作；此命令只读，不会自动重试、提交或回滚。异常奖励使用 `/omnitools rewards inspect <player> [event]` 排查；云存储的人工恢复仍必须使用 `/omnitools storage recovery inspect|resolve` 并核对玩家背包。
