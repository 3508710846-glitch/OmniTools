# 排错

| 现象 | 检查 |
| --- | --- |
| 修改不生效 | 执行 `/omnitools reload`；检查 JSON 是否有注释、尾逗号或错误字段。 |
| 指令奖励或菜单命令不执行 | 根配置必须启用 `allow_command_rewards`（仅奖励）并在 `allowed_roots` 列出命令根。 |
| 占位符显示 `-` | 检查拼写；第三方占位符还需安装 Text Placeholder API 并启用集成。 |
| 模块菜单打不开 | 检查根配置模块开关、对应权限角色和控制台日志。 |
| 物品奖励未入背包 | 用 `/omnitools rewards open` 打开奖励箱；不要重复签到。 |

管理员可执行 `/omnitools diagnose` 查看配置版本、模块状态、Placeholder API、命令白名单、未处理奖励和侧边栏状态。异常奖励使用 `/omnitools rewards inspect <player> [event]` 排查。
