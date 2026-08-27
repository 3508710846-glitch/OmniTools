# 模块管理与热重载

根配置 `modules.<id>.enabled` 是所有模块的唯一开关。修改后执行 `/omnitools reload`；模块管理原版箱子界面与该运行状态一致。

禁用模块会关闭关联 GUI、停止关联任务，并清理侧边栏或称号显示效果；玩家 SavedData、货币、签到、成就、称号和奖励账本仍会保留。重新启用后模块从当前配置快照恢复。若新配置任一部分无效，重载失败且旧快照继续运行。

`title_effects` 依赖 `titles`；使用 `type: "package"` 的奖励依赖 `packages`。礼包模块默认关闭，启用后才会处理 `config/omnitools/packages/config.json`。管理员使用 `/omnitools modules` 打开管理界面，使用 `/omnitools diagnose` 读取当前模块状态。
