# 排错

## 按现象排查

### 服务器无法启动或配置加载被拒绝

1. 保留完整控制台日志；不要只截取最后一行。
2. 找到第一条 `[omnitools] Configuration reload rejected` 或其后的 `Caused by`。
3. 检查修改过的文件是否为严格 JSON：双引号、无注释、无尾逗号，数值不要写成字符串。
4. 还原该文件的上一份备份，或按错误中的字段路径修正后执行 `/omnitools reload`。

配置重载采用“全量校验、成功才发布”的快照机制。失败时旧快照会继续运行；不能因为游戏还在运行就认为新配置已生效。

### 某个界面或命令无法打开

按以下顺序检查：

1. `config/omnitools/config.json` 中对应 `modules.<id>.enabled` 是否为 `true`。
2. 权限配置是否允许该命令动作；默认角色见[命令与权限操作手册](../guides/commands-and-permissions.md)。
3. 是否从控制台执行了必须由在线玩家执行的 GUI 命令。
4. `/omnitools diagnose` 是否显示模块降级或最近错误。
5. 修改根开关或权限后是否执行了完整 `/omnitools reload`。

### 商店、礼包或奖励看似没有发放

先不要重新购买、重复兑换或手动补发。

1. 检查背包和 `/omnitools rewards open`；物品可能处于待投递奖励箱。
2. 对商店礼包使用 `/omnitools shop audit [transactionUuid]`。
3. 对礼包使用 `/omnitools package inspect <player> <instanceUuid>`。
4. 对统一奖励使用 `/omnitools rewards inspect <player> [event]`。
5. 用 `/omnitools diagnose operation <operationId>` 汇总查找相关账本。

只有当记录明确显示某项未发出时，才按对应模块文档执行人工恢复。`BLOCKED` 是“需要证据”，不是“可以立刻重发”。

### 云仓出现存取争议

1. 立即让玩家停止云仓操作；无法判断物品位置时先停服。
2. 备份世界 `data/`、配置和日志。
3. 执行 `/omnitools storage recovery list` 与 `inspect <operationUuid>`。
4. 核对玩家背包、云仓页面快照和操作时间。
5. 仅在结论明确后使用 `commit` 或 `rollback`。

不要删除 journal、打开第二个窗口“试一下”，或把同类物品直接补给玩家。详见[备份与恢复](../guides/backup-and-recovery.md)。

### 技能经验、称号或占卜效果不符合预期

- 技能新配置应使用 `mining`、`swords`、`repair` 等 canonical ID；旧专业树 ID 仅为兼容读取。
- 使用 `/omnitools skills health` 检查经验拒绝原因与事务状态。
- 称号只改变允许的技能经验倍率，不提高技能能力上限。
- 占卜只修正正常玩法技能经验；礼包、统一奖励和管理员命令经验不受签文修正。
- 模块配置更新后，通过 `/omnitools reload skills` 或完整重载发布，再用测试账号触发一次对应行为。

### 仍无法定位问题时

提交问题报告时附上：

```text
服务器与 OmniTools 版本：
发生时间和时区：
玩家 UUID：
执行的命令或界面操作：
模块开关与相关配置片段：
完整错误前后的日志：
操作 ID / 交易 UUID / 礼包实例 UUID：
是否重启后仍能复现：
已采取的恢复操作：
```

不要贴出不相关的整个世界数据或密钥；优先提供能够复现和定位当前问题的最小证据。

| 现象 | 检查 |
| --- | --- |
| 修改不生效 | 执行 `/omnitools reload`；检查 JSON 是否有注释、尾逗号或错误字段。 |
| 指令奖励或菜单命令不执行 | 根配置必须启用 `allow_command_rewards`（仅奖励）并在 `allowed_roots` 列出命令根。 |
| 占位符显示 `-` | 检查拼写；第三方占位符还需安装 Text Placeholder API 并启用集成。 |
| 模块菜单打不开 | 检查根配置模块开关、对应权限角色和控制台日志。 |
| 物品奖励未入背包 | 用 `/omnitools rewards open` 打开奖励箱；不要重复签到。 |

管理员可执行 `/omnitools diagnose` 查看配置版本、模块状态、Placeholder API、命令白名单、未处理奖励、统一操作账本、资源预算和审计队列。使用 `/omnitools diagnose operation <操作ID>` 可跨云存储、技能经验、商店和奖励账本定位一笔操作；此命令只读，不会自动重试、提交或回滚。异常奖励使用 `/omnitools rewards inspect <player> [event]` 排查；云存储的人工恢复仍必须使用 `/omnitools storage recovery inspect|resolve` 并核对玩家背包。
