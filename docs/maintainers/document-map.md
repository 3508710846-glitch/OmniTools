# 文档地图

本页定义文档的真源和维护边界。`canonical` 页面可维护事实；`redirect` 仅保留旧链接；`archive` 是历史记录；`internal` 不面向用户；`example` 与 `schema` 是可复制数据或编辑器约束，不取代说明页面。

| 路径 | 标记 | 负责人 | 唯一职责 / 真源关系 |
| --- | --- | --- | --- |
| `README.md` | canonical | 项目维护者 | 项目介绍、安装、最短上手和文档入口；不维护模块字段或命令表。 |
| `docs/index.md` | canonical | 文档维护者 | 唯一文档导航页；只链接真源，不重复配置规则。 |
| `docs/config-platform.md` | canonical | 配置平台维护者 | 根快照、公共模板与重载语义；根字段细节委托给 `reference/root-config.md`。 |
| `docs/getting-started/*.md` | canonical | 文档维护者 | 新服主上手和通用排错；不维护第二份模块命令表。 |
| `docs/modules/<module>.md` | canonical | 对应模块维护者 | 模块用途、开关、配置、命令、数据与模块级重载行为的唯一说明。 |
| `docs/reference/root-config.md` | canonical | 配置平台维护者 | 根配置字段、默认值和安全边界。 |
| `docs/reference/rewards.md` | canonical | 奖励系统维护者 | 奖励类型、NBT 物品、限时称号、技能经验、补签卡和指令安全。 |
| `docs/reference/placeholders.md` | canonical | 文本集成维护者 | 内置占位符、回退值和支持模块。 |
| `docs/reference/placeholder-api.md` | canonical | 文本集成维护者 | 可选 Text Placeholder API 的安装、开关与文本边界。 |
| `docs/guides/module-management.md`、`upgrade-guide.md`、`backup-and-recovery.md`、`reward-consistency.md` | canonical | 运维维护者 | 模块管理、升级、备份和奖励账本处理流程。 |
| `docs/examples/**/*.json`、`docs/examples/**/*.jsonc` | example | 对应模块维护者 | 可复制配置或教学配置；用途、目标路径和重载方式由同目录 README 声明。 |
| `docs/presets/**/*.json` | example | 成就维护者 | 可加载的成就预设；不替代成就模块说明。 |
| `docs/schemas/*.json` | schema | 配置平台维护者 | 编辑器补全与格式约束；运行时解析器仍是最终执行校验。 |
| `docs/configuration.md`、`docs/achievements.md`、`docs/backup-and-recovery.md`、`docs/reward-consistency.md`、`docs/upgrade-guide.md`、`docs/modules/module-management.md`、`docs/modules/placeholder-api.md`、`docs/guides/placeholder-api.md` | redirect | 文档维护者 | 兼容旧链接，只包含到真源的跳转，不增加内容。 |
| `docs/archive/**` | archive | 项目维护者 | 过期方案、旧需求和迁移记录；不得在 `index.md` 主导航中出现，也不得描述为已实现。 |
| `docs/idea.md` | redirect | 项目维护者 | 旧工作请求的兼容入口，跳转到归档记录。 |
| `docs/last-ai-change.json`、`docs/last-ai-response.txt` | internal | 自动化工具 | 内部记录，不加入导航，也不作为功能事实来源。 |

变更事实时先修改唯一真源，再只在其他页面增加链接。页面所有者不是个人姓名，而是应审阅该类变更的模块维护角色；提交者负责在合并前完成[发布前检查清单](validation-checklist.md)。
