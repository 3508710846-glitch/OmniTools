# 模块管理与热重载

## 用途

根配置的 `modules.<id>.enabled` 控制十个模块：`daily_checkin`、`online_reward`、`shop`、`titles`、`title_effects`、`achievements`、`cloud_storage`、`permissions`、`command_menu`、`sidebar`。新安装默认启用除 `permissions` 外的模块。

## 操作

| 入口 | 权限 | 作用 |
| --- | --- | --- |
| `/omnitools reload` | `config.reload`，默认 `ADMIN` | 校验所有已启用模块并原子发布新快照 |
| `/omnitools modules` | `config.reload`，默认 `ADMIN` | 打开原版箱子模块管理界面 |
| `/omnitools diagnose` | `diagnose`，默认 `ADMIN` | 只读显示配置、模块、集成与风险状态 |

模块管理界面与根配置写入同一份配置。直接编辑根配置后也必须执行 `reload`。

## 热重载语义

加载器先读取根配置与所有已启用模块配置，执行跨模块校验，再一次性发布快照。JSON、引用、命令安全或依赖校验失败时，不替换旧快照、不删除数据，并在日志中报告文件和原因。

禁用模块会停止对应任务并关闭相关已打开界面：签到会关闭签到、记录和奖励详情；在线奖励、商店、称号、成就、云存储、命令菜单、奖励箱与管理员账本界面也会按权限或开关失效关闭。侧边栏会清理自身显示，称号效果会清理已施加的运行时效果。启用后会从保留的 SavedData 恢复运行。

## 依赖

- `title_effects` 依赖 `titles`。效果定义非空时，不能关闭称号模块。
- 签到、在线奖励与成就的 `title` 奖励需要已启用且包含对应 ID 的 `titles`。
- 命令菜单和 `command` 奖励受根配置的命令安全规则共同限制。

## 验收清单

- [ ] 修改一个模块配置后，`/omnitools reload` 成功且新开菜单显示新内容。
- [ ] 故意写入无效 JSON 后，reload 失败且旧行为仍在。
- [ ] 关闭并重新启用一个模块后，玩家数据与配置仍在。
- [ ] 关闭带有已打开 GUI 的模块时，该 GUI 被关闭。
