# OmniTools

OmniTools 是适用于 Fabric `1.21.11` 的纯服务端工具模组，提供每日签到与补签卡、在线奖励、CDK、商店与货币、称号与称号效果、成就、云存储、权限、命令菜单和侧边栏。玩家不需要安装 OmniTools；所有菜单均为原版箱子界面。

| 要求 | 版本 |
| --- | --- |
| Minecraft | `1.21.11` |
| Fabric Loader | `0.19.3` 或更高 |
| Fabric API | 与服务端版本匹配 |
| Java | `21` |

## 安装与最短上手

1. 将 OmniTools 和 Fabric API 放入服务端 `mods/`。
2. 用 Java 21 启动一次服务端。配置会生成在 `config/omnitools/`。
3. 按[第一次配置](docs/getting-started/first-setup.md)启用所需模块并调整根配置，再执行 `/omnitools reload`。

新安装默认禁止可配置命令执行。只有在根配置中明确列出命令根后，命令菜单和指令奖励才会执行。

## 文档入口

从[文档首页](docs/index.md)进入新手配置、模块主说明、配置语法参考、示例与运维指南。生产服升级、调整奖励 ID 或数据保留策略前，请先阅读[升级指南](docs/guides/upgrade-guide.md)和[备份与恢复](docs/guides/backup-and-recovery.md)。

根配置、公共模板和重载语义以[统一配置平台](docs/config-platform.md)为准；奖励类型、NBT 物品、限时称号与指令安全以[奖励参考](docs/reference/rewards.md)为准。

变更记录见 [CHANGELOG.md](CHANGELOG.md)。
