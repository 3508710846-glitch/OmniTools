# OmniTools

OmniTools 是面向 Fabric 服务器的模块化管理工具，提供每日签到、在线奖励、商店与货币、称号、成就、云存储、命令菜单、侧边栏和权限控制。

| 要求 | 版本 |
| --- | --- |
| Minecraft | `1.21.11` |
| Fabric Loader | `0.19.3` 或更高版本 |
| Fabric API | 与服务端 Minecraft 版本匹配 |
| Java | `21` |

## 纯服务端

OmniTools 只安装在服务器。玩家可用未安装 OmniTools 的原版或 Fabric 客户端进入，无需资源包、客户端模组或自定义网络包。所有业务菜单均使用原版箱子容器，侧边栏使用原版计分板。

## 安装

1. 使用 Java 21 启动 Fabric `1.21.11` 服务端。
2. 将 OmniTools 和 Fabric API 放入服务器的 `mods/` 目录。
3. 启动一次服务器；根配置与已启用模块的配置会生成在 `config/omnitools/`。
4. 修改配置后，以管理员身份执行 `/omnitools reload`。配置校验失败时，服务器继续使用上一份有效快照。

Placeholder API 是可选依赖。只有需要向其他模组暴露占位符，或在配置文本中解析第三方占位符时才安装它。详情见[Placeholder API 指南](docs/guides/placeholder-api.md)。

## 快速开始

检查 `config/omnitools/config.json` 中的时区、语言、命令白名单和模块开关，再按模块文档修改各自的 `config.json`。新安装默认禁止可配置命令执行；仅在明确列出允许的命令根后才会执行命令菜单或奖励指令。

玩家可使用 `/omnitools` 或 `/checkin` 打开签到界面。管理员可使用 `/omnitools modules` 管理模块，使用 `/omnitools diagnose` 查看当前配置、集成和运行状态。

## 文档

从[文档总索引](docs/index.md)进入模块参考、配置示例、升级和排障指南。升级生产服务器、变更奖励 ID 或修改数据保留策略前，请先阅读[升级指南](docs/guides/upgrade-guide.md)和[备份与恢复](docs/guides/backup-and-recovery.md)。

## 常见问题

- 原版客户端打开菜单：所有 OmniTools 菜单都是原版容器；若收到网络协议错误，请保留服务端日志、客户端报错和触发命令。
- 配置没有生效：执行 `/omnitools reload` 并检查日志。失败不会替换运行中的旧配置。
- 奖励未到账：先打开 `/omnitools rewards open` 检查奖励箱；管理员再使用奖励账本工具排查，见[奖励一致性](docs/guides/reward-consistency.md)。

发布版本与变更记录以仓库的 `CHANGELOG.md` 和构建产物元数据为准。
