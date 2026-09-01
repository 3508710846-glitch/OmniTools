# OmniTools 文档

这是唯一的文档导航入口。所有可复制的 `json` 代码块都符合严格 JSON；带注释的 `jsonc` 仅用于教学，不能直接放进 `config/`。旧根目录页面仅保留外部链接兼容，当前规则以本页链接的主说明为准。

## 新手开始

- [第一次配置](getting-started/first-setup.md)：从首次启动到第一次成功重载。
- [JSON、物品 ID 与颜色](getting-started/configuration-basics.md)：编辑配置所需基础。
- [排错](getting-started/troubleshooting.md)：配置加载与功能异常排查。

## 模块配置

- [每日签到](modules/daily-checkin.md)
- [CDK 与补签卡](modules/cdk.md)
- [在线奖励](modules/online-reward.md)
- [商店与货币](modules/shop-and-currency.md)
- [称号](modules/titles.md)
- [称号效果](modules/title-effects.md)
- [成就](modules/achievements.md)
- [排行榜](modules/leaderboards.md)
- [礼包](modules/packages.md)
- [技能树](modules/skills.md)
- [云存储](modules/cloud-storage.md)
- [权限](modules/permissions.md)
- [命令菜单](modules/command-menu.md)
- [侧边栏](modules/sidebar.md)

## 固定语法与配置平台

- [统一配置平台](config-platform.md)：根快照、公共模板、全量与单模块重载。
- [根配置参考](reference/root-config.md)：全局开关、命令安全与集成。
- [统一奖励格式](reference/rewards.md)：奖励类型、物品 SNBT、限时称号和安全边界。
- [内置占位符](reference/placeholders.md)
- [可选 Text Placeholder API](reference/placeholder-api.md)
- [配置教学示例](examples/config-platform/README.md) 与 [Schema](schemas/)

## 运维与升级

- [模块管理与热重载](guides/module-management.md)
- [配置升级](guides/upgrade-guide.md)
- [备份与恢复](guides/backup-and-recovery.md)
- [奖励一致性与奖励箱](guides/reward-consistency.md)

## 示例与预设

- [最小服务器配置](examples/minimal-server/README.md)
- [成就条件示例](examples/achievement-examples/README.md)
- [奖励示例](examples/reward-examples/README.md)
- [成就预设](presets/achievements/README.md)

历史方案和旧工作请求位于 `archive/`，不描述为当前功能，也不纳入主导航。文档维护规则与发布检查见[维护者文档](maintainers/document-map.md)。
