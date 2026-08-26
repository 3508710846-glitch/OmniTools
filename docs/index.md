# OmniTools 文档

[统一配置平台](config-platform.md) 说明根快照、公共模板、JSONC 教学文件和 Schema。

本目录面向第一次管理 Fabric 服务端的服主。所有可复制的 `json` 代码块都符合严格 JSON；带注释的 `jsonc` 仅用于讲解，不能直接放进 `.json` 文件。

## 快速入口

- [第一次配置](getting-started/first-setup.md)
- [JSON、物品 ID 与颜色](getting-started/configuration-basics.md)
- [排错](getting-started/troubleshooting.md)
- [根配置参考](reference/root-config.md)
- [统一奖励格式](reference/rewards.md)
- [22 个 OmniTools 占位符](reference/placeholders.md)
- [可选 Text Placeholder API](reference/placeholder-api.md)

## 模块

- [每日签到](modules/daily-checkin.md)
- [CDK 与补签卡](modules/cdk.md)
- [在线奖励](modules/online-reward.md)
- [商店与货币](modules/shop-and-currency.md)
- [称号](modules/titles.md)
- [称号效果](modules/title-effects.md)
- [成就](modules/achievements.md)
- [云存储](modules/cloud-storage.md)
- [权限](modules/permissions.md)
- [命令菜单](modules/command-menu.md)
- [侧边栏](modules/sidebar.md)

## 跨模块指南

- [模块管理与热重载](guides/module-management.md)
- [配置升级](guides/upgrade-guide.md)
- [备份与恢复](guides/backup-and-recovery.md)
- [奖励一致性与奖励箱](guides/reward-consistency.md)

历史设计位于 `archive/`，不作为配置真源。`last-ai-change.json` 和 `last-ai-response.txt` 是内部记录，未纳入导航。
