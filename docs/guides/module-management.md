# 模块管理与热重载

## 安全启用或禁用模块

1. 先备份 `config/omnitools/` 与世界目录的 `data/`。
2. 检查目标模块的配置文件和依赖模块是否齐全。
3. 修改 `config/omnitools/config.json` 中的 `modules.<id>.enabled`。
4. 执行 `/omnitools reload`，不要只重载目标模块；根配置的模块开关属于整套配置快照。
5. 用 `/omnitools diagnose` 确认模块状态，再由测试账号打开对应界面或执行一个无损操作。

模块关闭不会删除该模块的 SavedData、奖励账本或历史记录。重新启用后会继续使用已有数据；这并不意味着配置中的跨模块引用仍然有效，重载会重新校验它们。

## 当前依赖与阻止规则

| 操作 | 运行时处理 | 管理员应做什么 |
| --- | --- | --- |
| 启用 `title_effects`，但 `titles` 已关闭 | 拒绝启用 | 先启用并重载 `titles`。 |
| 关闭 `titles`，但 `title_effects` 有定义 | 拒绝关闭 | 先清空或停用称号效果，再关闭称号。 |
| 关闭 `titles`，但签到、在线奖励、成就或 CDK 仍投放称号 | 拒绝关闭 | 先移除或替换这些 `title` 奖励。 |
| 关闭 `packages`，但奖励仍引用 `type: "package"` | 拒绝关闭 | 先移除或替换礼包奖励。 |
| 使用商店礼包商品 | 必须同时开启 `shop` 与 `packages` | 同时校验礼包 ID 已定义。 |
| 礼包包含 `skill_xp` | 需要开启 `skills` | 使用当前 canonical 技能 ID。 |

这类拒绝不会修改当前已生效快照。不要为了绕过校验而删除模块的世界数据；应先让配置中的依赖关系一致。

## 选择正确的重载范围

| 改动内容 | 使用命令 | 原因 |
| --- | --- | --- |
| 一个模块自己的 `config.json` | `/omnitools reload <module-id>` | 仅发布该模块的已校验配置。 |
| 根配置、模块开关、`common/rewards.json`、`common/conditions.json` 或 `common/texts.json` | `/omnitools reload` | 这些文件可能同时影响多个模块。 |
| 配置语法、SNBT、引用或跨模块检查失败 | 不继续重试 | 旧快照仍在运行；先修复控制台首条 `[omnitools]` 错误，再重载。 |

“重载成功”只表示配置被校验并发布，不等于玩家玩法已经完成验收。启用商店、礼包、云仓、技能或占卜后，仍应在测试账号上验证一次对应流程。

## 降级与健康状态

模块回调发生异常时，故障边界会记录结构化错误，并尽可能把异常隔离在对应模块内；其他模块不应因此停止。`/omnitools diagnose` 会显示模块健康、资源预算和审计队列摘要。

出现 `DEGRADED`、配置拒绝或频繁预算超限时：

1. 停止对该功能的玩家运营操作，保存控制台日志。
2. 使用 `/omnitools diagnose` 与 `/omnitools diagnose operation <操作 ID>` 收集只读证据。
3. 对涉及物品、货币或云仓的异常，先备份世界 `data/`。
4. 修复配置后执行完整重载；若仍无法确认数据结果，按[备份与恢复](backup-and-recovery.md)走人工恢复流程。

云仓例外：即使模块随后关闭或降级，已经打开的会话仍会在关闭、断线或停服时尝试提交，或保留恢复证据。不要以关闭模块作为“撤销某次存取”的手段。

根配置 `modules.<id>.enabled` 是所有模块的唯一开关。修改后执行 `/omnitools reload`；模块管理原版箱子界面与该运行状态一致。

禁用模块会关闭关联 GUI、停止关联任务，并清理侧边栏或称号显示效果；玩家 SavedData、货币、签到、成就、称号和奖励账本仍会保留。重新启用后模块从当前配置快照恢复。若新配置任一部分无效，重载失败且旧快照继续运行。

`title_effects` 依赖 `titles`；使用 `type: "package"` 的奖励依赖 `packages`。礼包模块默认关闭，启用后才会处理 `config/omnitools/packages/config.json`。管理员使用 `/omnitools modules` 打开管理界面，使用 `/omnitools diagnose` 读取当前模块状态。
