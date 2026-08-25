# OmniTools 文档

本索引只列出面向服务器管理员和玩家的文档。`docs/archive/` 保存历史设计资料；工作台记录不属于用户文档。

## 开始使用

- [项目首页与安装](../README.md)
- [模块管理与热重载](guides/module-management.md)
- [Placeholder API](guides/placeholder-api.md)
- [升级指南](guides/upgrade-guide.md)
- [备份与恢复](guides/backup-and-recovery.md)
- [奖励一致性与奖励箱](guides/reward-consistency.md)

## 模块

| 模块 | 内容 |
| --- | --- |
| [每日签到](modules/daily-checkin.md) | 周历签到、月度里程碑、记录与奖励详情 |
| [在线奖励](modules/online-reward.md) | 在线时长里程碑与统一奖励 |
| [商店与货币](modules/shop-and-currency.md) | 商品、购买与货币指令 |
| [称号](modules/titles.md) | 称号授予、展示与队伍冲突策略 |
| [称号效果](modules/title-effects.md) | 药水、属性、粒子与权限效果 |
| [成就](modules/achievements.md) | 原版统计条件树、奖励与预设 |
| [云存储](modules/cloud-storage.md) | 玩家个人仓库与扩容 |
| [权限](modules/permissions.md) | 命令角色与原生权限节点 |
| [命令菜单](modules/command-menu.md) | 原版箱子菜单、动作与命令安全 |
| [侧边栏](modules/sidebar.md) | 计分板显示、文本和冲突策略 |

## 约定

- 所有配置均在 `config/omnitools/` 下；根配置为 `config/omnitools/config.json`，当前格式版本为 `3`。
- 修改配置后执行 `/omnitools reload`。任一候选配置无效时，当前运行快照不会被替换。
- 所有业务 GUI 均为原版 `GENERIC_9x3` 或 `GENERIC_9x6` 容器，客户端无需安装 OmniTools。
- 奖励、签到、货币、成就、称号和云存储的玩家数据位于世界的 `SavedData`，不是配置 JSON。
