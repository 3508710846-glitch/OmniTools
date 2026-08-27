# 升级指南

升级前备份 `config/omnitools/` 和世界的 `data/`。根配置会迁移到格式版本 `4` 并为旧文件创建备份。为避免旧服升级后静默开启新功能，迁移缺失模块会保持关闭策略；请在 `/omnitools diagnose` 中检查实际状态。v4 新增 `common/` 公共模板目录，并要求 CDK 模块由管理员在根配置中显式启用。

推荐签到奖励为 `daily.rewards` 与 `monthly`，在线奖励为独立 `online_reward/config.json` 的 `rewards`。旧 `dailyCoins`、`monthlyRewards`、`onlineTimeRewards` 和 `coins` 仍有兼容读取，迁移后请改写为新格式，奖励 ID 不要随意更改。

旧侧边栏值 `warn`、`disabled` 会兼容为安全的 `skip`；新配置只使用 `skip`、`replace`、`restore`。
