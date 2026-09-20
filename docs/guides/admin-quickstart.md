# OmniTools 新手管理员快速手册

本手册面向第一次维护 Fabric 服务端的管理员。按照本文顺序操作，可以完成 OmniTools 的安装、首次启动、基础配置、功能验收、备份和故障处理。

本文只描述当前项目已经实现的功能。完整字段说明请从[文档首页](../index.md)进入对应模块页面。

## 1. 环境要求

当前构建固定使用以下版本：

| 项目 | 要求 |
| --- | --- |
| Minecraft | `1.21.11` |
| Fabric Loader | `0.19.3` 或更高兼容版本 |
| Fabric API | `0.141.6+1.21.11`，必需 |
| Java | Java 21 或更高兼容版本 |
| OmniTools | `0.1.0` |
| Text Placeholder API | 可选；仅第三方文本占位符需要 |

OmniTools 是服务端模组。玩家可以使用原版客户端连接，不需要在客户端安装 OmniTools。

先在终端检查 Java：

```text
java -version
```

输出中应出现版本 `21`。如果系统中安装了多个 Java，请确保启动脚本实际调用的是 Java 21。

内存没有适用于所有整合包的固定值。空白测试服可从 `2G` 开始，正式服应根据模组数量、在线人数、视距和世界生成负载调整。不要把增加内存当作解决配置错误或模组冲突的方法。

## 2. 安装与首次启动

### 2.1 准备服务端

1. 创建一个新的服务器目录。
2. 安装 Minecraft 1.21.11 对应的 Fabric 服务端，并选择 Fabric Loader 0.19.3。
3. 将 OmniTools JAR 放入服务器的 `mods/`。
4. 将 Fabric API `0.141.6+1.21.11` 放入 `mods/`。
5. 启动一次服务端。

Windows 示例：

```bat
java -Xms2G -Xmx4G -jar fabric-server-launch.jar nogui
```

Linux 示例：

```sh
java -Xms2G -Xmx4G -jar fabric-server-launch.jar nogui
```

实际 Fabric 启动器文件名可能不同，请把示例中的 `fabric-server-launch.jar` 替换成真实文件名。

### 2.2 同意 EULA

首次启动通常会生成 `eula.txt` 并停止。确认你同意 Minecraft EULA 后，将其中的 `eula=false` 改为 `eula=true`，再启动服务器。

### 2.3 判断首次启动是否成功

成功时应满足：

- 服务器进入可接受玩家连接的状态。
- `config/omnitools/config.json` 已生成。
- `config/omnitools/<module_id>/config.json` 等模块配置已生成。
- 日志中没有 OmniTools 的未处理 `ERROR`、`Exception` 或配置加载失败。

如果服务器无法启动，先不要删除世界数据。跳到本文的[故障排查](#12-故障排查)章节。

## 3. 目录说明

以下路径都相对于正式服务器根目录。开发环境中的服务器根目录通常是 `run/`，正式服不要额外添加 `run/`。

| 路径 | 用途 | 备份优先级 |
| --- | --- | --- |
| `config/omnitools/` | OmniTools 权威配置目录 | 必须 |
| `config/omnitools/config.json` | 根配置、模块开关和全局设置 | 必须 |
| `config/omnitools/<module_id>/config.json` | 各模块配置 | 必须 |
| `config/omnitools/legacy/` | 旧配置迁移输入或归档 | 不作为日常编辑目标 |
| `world/` | 世界、玩家和模组持久化数据 | 必须 |
| `world/data/` | OmniTools SavedData、操作账本和云仓数据 | 必须 |
| `logs/` | 当前日志和历史日志 | 故障时必须保留 |
| `crash-reports/` | 崩溃报告 | 崩溃时必须保留 |

唯一权威配置是 `config/omnitools/`。不要继续编辑已经迁移到 `legacy/` 的旧文件，也不要通过删除 `world/data/` 来重置功能。

## 4. 正确的配置顺序

1. 停止服务器，备份 `config/omnitools/` 和整个 `world/`。
2. 修改根配置 `config/omnitools/config.json` 中的模块开关。
3. 修改对应的 `config/omnitools/<module_id>/config.json`。
4. 启动服务器，或在服务器运行时执行 `/omnitools reload`。
5. 查看命令返回和控制台中第一条 `[omnitools]` 错误。
6. 用普通玩家账号实际验证入口、权限和奖励。

### 4.1 JSON 基础规则

- 真实 `.json` 文件不支持 `//` 或 `/* ... */` 注释。
- 键名和字符串必须使用英文双引号 `"`。
- 数字和布尔值不能写成字符串，例如使用 `250` 和 `true`，不要写成 `"250"` 和 `"true"`。
- 最后一项后不能有多余逗号。
- 文件应保存为 UTF-8。
- 文档中的 `jsonc` 是带注释的教学示例，不能原样放入配置目录。

配置重载失败时，OmniTools 会保留上一份有效快照。不要看到失败后立即反复重载；先根据日志修正第一个错误。

### 4.2 根配置与模块依赖

模块开关位于 `config/omnitools/config.json`。以下内容只是片段，不应覆盖首次启动生成的完整根配置：

```json
{
  "modules": {
    "shop": { "enabled": true },
    "packages": { "enabled": true },
    "skills": { "enabled": true },
    "divination": { "enabled": true },
    "cloud_storage": { "enabled": true }
  }
}
```

特别注意：

- 商店中存在 `type: "package"` 商品时，必须启用 `packages`。
- 礼包包含 `skill_xp` 奖励时，必须启用 `skills`。
- 礼包配置中已有定义但 `packages` 被关闭时，全量重载会被拒绝。
- 禁用模块不会删除玩家数据；重新启用后会继续读取原有 SavedData。

## 5. 重载、诊断与权限

### 5.1 常用管理员命令

| 命令 | 默认权限动作 | 作用 |
| --- | --- | --- |
| `/omnitools reload` | `config.reload` | 重载全部配置 |
| `/omnitools reload <module>` | `config.reload` | 仅重载指定模块 |
| `/omnitools diagnose` | `diagnose` | 查看配置、模块健康、资源预算和账本摘要 |
| `/omnitools diagnose operation <operationId>` | `diagnose` | 跨账本只读查询一笔操作 |
| `/omnitools modules` | 管理员入口 | 打开模块管理界面 |

`diagnose` 和 `diagnose operation` 都是只读命令。它们不会自动发奖、重试、提交、回滚或修改账本状态。

### 5.2 权限角色

OmniTools 使用以下内置角色：

| 角色 | 原生命令等级 | 典型用途 |
| --- | ---: | --- |
| `PLAYER` | 0 | 普通玩家入口 |
| `MODERATOR` | 1 | 协管功能 |
| `ADMIN` | 2 | 配置、诊断和发放 |
| `OWNER` | 4 | 最高管理权限 |

权限配置位于 `config/omnitools/permissions/config.json`。常用动作包括：

| 权限动作 | 默认角色 |
| --- | --- |
| `storage.open` | `ADMIN` |
| `storage.recovery` | `ADMIN` |
| `diagnose` | `ADMIN` |
| `skills.open` | `PLAYER` |
| `skills.admin` | `ADMIN` |
| `divination.open` | `PLAYER` |
| `divination.admin` | `ADMIN` |

修改权限前应保留至少一个可用的 Owner 账号。完整动作表见[权限模块](../modules/permissions.md)。

## 6. 云存储安全操作

云存储直接保存玩家物品，是最需要谨慎运维的模块。

### 6.1 启用前备份

1. 正常停止服务器。
2. 备份整个 `world/`。
3. 单独备份 `world/data/`，方便快速定位 SavedData。
4. 备份 `config/omnitools/`。
5. 保留最近一次正常启动的 `logs/latest.log`。

不要在服务器仍在写入世界时复制一半数据，也不要混用不同时间点的配置与世界备份。

### 6.2 玩家入口和操作

管理员授权 `storage.open` 后，玩家可使用：

```text
/omnitools storage
/omnitools storage open
```

界面按照原版容器规则处理左键、右键、Shift 点击、拖拽分配、双击收集、数字键交换、`Q` 丢弃和窗口外丢弃。控制栏不属于存储槽位，不能通过 Shift 点击转入云仓。

云仓使用独立会话镜像。页面变化会在检查点、翻页、关闭、断线和停服路径提交；提交失败时会冻结会话并保留 journal 证据，不应继续反复操作。

### 6.3 journal 状态

| 状态 | 含义 | 管理员动作 |
| --- | --- | --- |
| `PREPARED` | 已记录操作前后快照，结果尚待确认 | 先检查当前页面与日志 |
| `COMMITTED` | 操作已完成 | 不要重复恢复 |
| `ROLLED_BACK` | 已回到操作前快照 | 不要再次转换 |
| `QUARANTINED` | 无法自动证明结果，已隔离 | 人工核对后选择 commit 或 rollback |

这些状态是受约束的终态转换。不要直接编辑或删除 journal；人工处理结果会连同操作 ID 和恢复证据保存。

### 6.4 云仓恢复命令

```text
/omnitools storage recovery list
/omnitools storage recovery inspect <operationId>
/omnitools storage recovery resolve <operationId> commit
/omnitools storage recovery resolve <operationId> rollback
```

建议流程：

1. 停止玩家继续使用云仓；严重问题先停服。
2. 备份当前 `world/`、`config/omnitools/` 和日志。
3. 用 `/omnitools diagnose operation <operationId>` 只读定位跨模块记录。
4. 用 `storage recovery inspect` 核对玩家 UUID、页面、操作时间、前后物品数量和会话信息。
5. 同时核对玩家背包，判断应该保留操作后页面还是恢复操作前页面。
6. 只有证据明确时才执行 `commit` 或 `rollback`。

`commit` 表示采用操作后的云仓页面，`rollback` 表示采用操作前的云仓页面。恢复命令只处理云仓页面，不能代替对玩家背包的核对。

### 6.5 云仓故障报告模板

```text
玩家名称：
玩家 UUID：
发生时间与时区：
操作类型：存入 / 取出 / Shift / 拖拽 / 双击 / 翻页 / 关闭 / 断线
云仓页面：
物品名称、组件与数量：
是否在重启或断线后发生：
operation ID：
相关日志文件：
截图或视频：
```

更多细节见[云存储模块](../modules/cloud-storage.md)和[备份与恢复](backup-and-recovery.md)。

## 7. 技能模块当前边界

当前技能模块是独立实现的 mcMMO 风格行为兼容层，不是旧版“每树 2000 级、四技能、50% 属性”的专业树方案，也不包含 Bukkit/Spigot mcMMO 本体。

### 7.1 已实现

当前十项 canonical 技能 ID：

```text
mining
woodcutting
herbalism
excavation
swords
axes
archery
acrobatics
repair
alchemy
```

已实现的共同规则：

- 单项等级范围 `0-1000`。
- 服务端校验经验来源、频率、每日上限和操作 ID。
- XP 事务具备 `PREPARED`、`COMMITTED`、`ROLLED_BACK` 状态与重启恢复。
- 相同操作 ID 不会重复结算经验。
- BossBar 合并显示近期经验和升级进度。
- ActionBar、标题和音效用于能力状态与升级反馈，并带限频。
- 方块、击杀、制作和药剂等行为按技能配置路由。
- 额外掉落与范围能力具有创造模式、精准采集、数量、碰撞和重入限制。

玩家与管理员命令：

| 命令 | 权限 | 作用 |
| --- | --- | --- |
| `/skills` 或 `/omnitools skills` | `skills.open` | 打开技能界面 |
| `/skills stats` | `skills.open` | 查看引擎、总战力和技能经验 |
| `/skills ability <skill>` | `skills.open` | 查看能力等级、冷却和剩余时间 |
| `/skills health` | `skills.admin` | 查看技能事件与 XP 事务健康状态 |
| `/skills add <tree> <amount>` | `skills.admin` | 给命令执行者增加指定技能经验 |

### 7.2 尚未实现或未完成

以下内容不能写成已上线功能：

- `/skills top` 技能排行榜。
- `/party` 与 Party 经验共享。
- Fishing、Taming、Salvage、Smelting。
- 对真实 mcMMO 的双向数据导入。
- 自动化客户端 GUI 画面验收。

完整能力、配置和迁移边界见[技能模块](../modules/skills.md)。

## 8. 每日占卜

占卜配置位于 `config/omnitools/divination/config.json`。当前流程已经收紧为五级签文：

```text
下下签 -> 下签 -> 中签 -> 上签 -> 上上签
```

玩家每天免费抽取一次。抽签后点击“解签”，才会显示签诗、解读、今日宜忌和开运建议，并使当天效果生效。已解签后可消耗共享钱包货币重新抽签，当天总次数受 `max_daily_draws` 限制。

入口：

```text
/divination
/fortune
/omnitools divination
/omnitools divination open
```

核心设置：

```json
{
  "format_version": 2,
  "settings": {
    "enabled": true,
    "daily_free_draws": 1,
    "max_daily_draws": 3,
    "reroll_cost": 250,
    "omen_buff_cap": 0.15,
    "history_retention_days": 30,
    "draw_cooldown_seconds": 3,
    "broadcast_extremes": true
  }
}
```

上面的 `signs` 签文数组被省略，因此是结构说明，不应覆盖完整生成文件。关键边界：

- `daily_free_draws` 当前必须为 `1`。
- `max_daily_draws` 可配置为 `1-9`。
- 上上签和下下签可在解签时全服广播。
- 签文可提供一次性货币奖励和当天技能经验修正，负面修正最低为 `-15%`。
- `omen_buff_cap` 限制正向修正上限，不能超过 `50%`。
- 占卜只修正正常玩法产生的技能经验；礼包、奖励系统和管理员命令经验不受影响。
- 重抽扣款与解签货币奖励使用持久化操作 ID 去重，失败重试不会重复扣款或重复发奖。
- 当前没有主题选择、深度解签或化解任务。

完整签文结构见[每日占卜模块](../modules/divination.md)。

## 9. 商店、礼包与技能经验联动

在上线商店礼包前，按这个顺序检查：

1. 根配置同时启用 `shop` 和 `packages`。
2. 如果礼包包含 `skill_xp`，同时启用 `skills`。
3. 确认商店引用的 `package_id` 在礼包配置中存在。
4. 先执行 `/omnitools reload packages`，再执行 `/omnitools reload shop`，最后执行一次全量 `/omnitools reload`。
5. 用测试账号购买一次并检查钱包、礼包实例和奖励账本。

技能经验礼包支持定向、随机和玩家自选。随机目标在发奖前持久化；重试不会重新随机或重复发放。是否享受称号经验加成由礼包条目配置控制，占卜修正不作用于礼包经验。

## 10. 备份、升级与回滚

### 10.1 日常备份

至少备份同一时间点的：

```text
config/omnitools/
world/
logs/latest.log
```

其中 `world/data/` 保存货币、签到、称号、成就、云仓和操作账本等关键数据。只备份配置不能恢复玩家状态。

### 10.2 升级前

1. 正常停止服务器。
2. 记录当前 Minecraft、Fabric Loader、Fabric API、Java 和 OmniTools 版本。
3. 备份配置、完整世界、旧 JAR 和最近日志。
4. 替换 JAR 后首次启动，观察配置迁移与备份提示。
5. 执行 `/omnitools diagnose`。
6. 用测试账号依次验证签到、钱包、商店、礼包、技能、占卜和云仓。

不要在没有备份的情况下同时升级 Minecraft、Loader、Fabric API、Java 和 OmniTools；否则发生问题时很难确定原因。

### 10.3 回滚

回滚必须恢复同一批次的 OmniTools JAR、`config/omnitools/` 和 `world/`。只换回旧 JAR、却继续使用新版本迁移后的配置或 SavedData，可能造成字段不兼容或错误恢复。

## 11. 模块验收清单

- [ ] `java -version` 显示 Java 21。
- [ ] Minecraft、Loader 和 Fabric API 版本与本文一致。
- [ ] 第二次启动后服务器正常接受连接。
- [ ] `config/omnitools/` 已生成且已备份。
- [ ] `/omnitools reload` 成功，没有替换失败提示。
- [ ] `/omnitools diagnose` 未报告意外降级模块或异常账本。
- [ ] 普通玩家只能访问授予的玩家命令。
- [ ] 管理员可执行重载、诊断和恢复查询。
- [ ] 商店礼包和技能经验的依赖模块同时启用。
- [ ] `/skills stats` 只记录一次实际行为经验。
- [ ] 占卜每天免费一次，重抽正确扣款，极端签按配置广播。
- [ ] 云仓完成存入、取出、Shift、拖拽、双击、满背包、关闭和断线测试。
- [ ] 重启后玩家数据、云仓页面和账本状态保持一致。
- [ ] 已完成一份可恢复的完整世界与配置备份。

## 12. 故障排查

### 12.1 服务器无法启动

```text
服务器无法启动
├─ 日志提示 Java 版本错误
│  └─ 确认启动脚本使用 Java 21
├─ 日志提示缺少 Fabric API
│  └─ 安装 0.141.6+1.21.11 并检查是否误放到客户端目录
├─ 日志提示模组版本不兼容
│  └─ 核对 Minecraft 1.21.11 与 Loader 0.19.3
├─ 日志提示配置解析错误
│  └─ 修正第一条 JSON/字段错误，或恢复上一份配置
└─ 出现崩溃报告
   └─ 保留 logs/latest.log 与 crash-reports/ 对应文件
```

### 12.2 配置重载失败

1. 不要连续点击重载。
2. 查找控制台第一条 `Configuration reload rejected` 或 `Could not load configuration module <id>`。
3. 修正该模块 JSON、未知字段、无效 ID 或跨模块依赖。
4. 先重载单模块，再执行全量重载。

不要删除 SavedData 或 journal。重载失败时旧快照仍在运行，玩家数据通常不需要恢复。

### 12.3 模块显示已开启但功能不可用

1. 检查根配置的 `modules.<id>.enabled`。
2. 检查模块自己的 `settings.enabled`（如果存在）。
3. 检查命令对应的权限动作和角色。
4. 查看 `/omnitools diagnose` 中的模块健康状态。
5. 查找控制台中该模块第一次报错的完整堆栈。

### 12.4 商店提示礼包未启用

这通常不是 `shop` 开关本身的问题。确认 `packages` 已启用；若礼包包含技能经验，再确认 `skills` 已启用。全量配置校验会拒绝“商店引用礼包但礼包模块关闭”的快照。

### 12.5 云仓疑似吞物或复制

1. 立即停止相关玩家继续操作；必要时停服。
2. 备份当前世界、配置和日志。
3. 记录玩家 UUID、物品、页面、时间和 operation ID。
4. 使用只读诊断和 `storage recovery inspect` 核对证据。
5. 不要删除 journal，不要直接编辑 SavedData，不要凭感觉执行两次恢复。

### 12.6 提交故障报告时附带

```text
Minecraft / Loader / Fabric API / Java / OmniTools 版本
发生时间与时区
玩家 UUID
执行的命令或操作步骤
期望结果与实际结果
operation ID（如有）
logs/latest.log
对应 crash-reports 文件（如有）
相关配置文件（删除密钥或隐私数据后）
截图或视频
```

不要只发送最后一行异常；第一条 OmniTools 错误和完整 `Caused by` 链通常更重要。

## 13. 后续阅读

- [第一次配置](../getting-started/first-setup.md)
- [配置基础](../getting-started/configuration-basics.md)
- [模块管理与热重载](module-management.md)
- [备份与恢复](backup-and-recovery.md)
- [升级指南](upgrade-guide.md)
- [云存储](../modules/cloud-storage.md)
- [技能模块](../modules/skills.md)
- [每日占卜](../modules/divination.md)
- [商店与货币](../modules/shop-and-currency.md)
- [礼包](../modules/packages.md)
- [权限](../modules/permissions.md)
- [命令与权限操作手册](commands-and-permissions.md)
