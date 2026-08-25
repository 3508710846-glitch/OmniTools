# 升级指南

升级前先备份世界目录和 `config/omnitools/`。OmniTools 会在加载配置前迁移根配置；迁移过程会创建类似 `config.json.v2.bak-<时间戳>` 的原文件备份，并在日志中记录结果。

根配置现为版本 3。迁移会补齐以下字段：

```json
{
  "global": {
    "language": "zh_cn",
    "data_retention": "full",
    "reward_security": {
      "allow_command_rewards": false,
      "max_command_length": 1024
    },
    "command_security": {
      "allowed_roots": ["say", "trigger"],
      "max_command_length": 1024,
      "cooldown_ticks": 20
    }
  },
  "integrations": {
    "placeholder_api": { "enabled": true }
  }
}
```

从旧根配置升级时，未出现过的 `command_menu` 和 `sidebar` 模块会保持关闭，避免升级后自动向玩家开放新功能。迁移会为旧的可配置命令显式写入 `allowed_roots: ["*"]` 以保持兼容；管理员应尽快替换为实际需要的命令根。

`data_retention` 默认是 `full`，不删减签到明细。`monthly_summary` 和 `archive` 会先在 `config/omnitools/archives/` 写出当前签到数据的 SNBT 归档，再把当月以前的逐日签到、排名和时间聚合为月度摘要。账本不会按时间盲删；只有全部奖励已完成且签到或成就来源状态仍可证明完成的事件才会清理。

升级后执行 `/omnitools reload`。任何模块配置、交叉依赖或命令安全校验失败时，服务器继续使用旧的完整配置快照。
