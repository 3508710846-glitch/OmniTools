# OmniTools

OmniTools 是面向 Fabric 服务器的模块化实用工具模组。它提供每日签到、在线奖励、商店与货币、称号、成就、云端存储、命令菜单、侧边栏和可配置的指令权限。

| 项目 | 版本或要求 |
| --- | --- |
| Minecraft | `1.21.11` |
| Fabric Loader | `>= 0.19.3` |
| Fabric API | `0.141.6+1.21.11` |
| Java | `21` |
| OmniTools | `2.0.1` |

OmniTools 是纯服务端模组：配置、玩家数据、命令校验和全部业务 GUI 都在服务端执行。签到、在线奖励、商店、称号、成就、云端存储、命令菜单和模块管理均使用原版箱子界面；侧边栏使用原版 scoreboard 数据包。玩家可使用完全未安装 OmniTools 的原版或 Fabric 客户端连接，无需资源包、客户端模组或自定义网络包。

自定义文本由服务端按 `config/omnitools/config.json` 的 `global.language`（`zh_cn` 或 `en_us`）解析后发送。头顶称号使用原版计分板队伍前缀；一个玩家同时只能属于一个计分板队伍，和其他队伍系统发生冲突时由称号配置中的策略决定处理方式。

## 安装

1. 使用 Java 21 启动 Fabric 1.21.11 服务端。
2. 将 OmniTools 和 Fabric API 放入服务器的 `mods/` 目录。
3. 首次启动服务器。OmniTools 会在 `config/omnitools/` 创建根配置和已启用模块的初始配置。
4. 修改配置后，由管理员执行 `/omnitools reload`。该命令只会在全部已启用模块配置校验通过时发布新配置。

Fabric Placeholder API 是可选依赖。只有需要向其他模组提供占位符或在侧边栏使用第三方占位符时才安装它，详见[Placeholder API](docs/modules/placeholder-api.md)。

## 快速开始

首次启动后，玩家可使用 `/omnitools` 或 `/checkin` 打开签到界面。服主先检查 `config/omnitools/config.json` 中的时区与模块开关，再按模块文档修改相应配置，最后执行 `/omnitools reload`。配置校验失败时，服务器会继续使用上一份有效配置。

生产服升级模组、修改配置或切换模块前，请备份世界目录和 `config/omnitools/`。配置 JSON 只保存规则；玩家进度由世界 `SavedData` 保存。

## 模块文档

| 模块 | 文档 |
| --- | --- |
| 每日签到 | [daily-checkin.md](docs/modules/daily-checkin.md) |
| 在线奖励 | [online-reward.md](docs/modules/online-reward.md) |
| 商店与货币 | [shop-and-currency.md](docs/modules/shop-and-currency.md) |
| 称号 | [titles.md](docs/modules/titles.md) |
| 称号效果 | [title-effects.md](docs/modules/title-effects.md) |
| 成就 | [achievements.md](docs/modules/achievements.md) |
| 云端存储 | [cloud-storage.md](docs/modules/cloud-storage.md) |
| 指令权限 | [permissions.md](docs/modules/permissions.md) |
| 命令菜单 | [command-menu.md](docs/modules/command-menu.md) |
| 侧边栏 | [sidebar.md](docs/modules/sidebar.md) |
| 模块管理与重载 | [module-management.md](docs/modules/module-management.md) |
| Placeholder API | [placeholder-api.md](docs/modules/placeholder-api.md) |

排查问题时请保留服务器日志、相关配置文件和复现步骤。规划记录已归档在 `docs/archive/`，不代表已实现功能。
