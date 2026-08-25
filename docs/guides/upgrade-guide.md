# 升级指南

## 升级前

停止服务端并同时备份世界目录和 `config/omnitools/`。玩家进度、货币、奖励账本、称号、成就和云存储保存在世界 `SavedData`；JSON 只保存规则和开关。不要为了升级删除 SavedData 或变更既有奖励 ID。

## 根配置迁移

当前根配置格式为 `3`。迁移会先在同目录创建 `config.json.v<旧版本>.bak-<时间戳>`，再原子写入新文件并在日志中记录结果。

| 来源 | 迁移行为 |
| --- | --- |
| v1 或缺少版本 | 补齐语言、数据保留、奖励安全、命令安全和 Placeholder API；旧版本没有的命令菜单与侧边栏保持关闭 |
| v2 | 补齐 v3 的数据保留和命令安全字段；既有可配置命令保持兼容 |
| v3 | 不改写根配置 |

旧服为保持行为可能得到 `allowed_roots: ["*"]` 和 `cooldown_ticks: 0`。这是宽松兼容模式，启动与 reload 会产生警告；请尽快改成实际允许的命令根，例如 `spawn`、`home`、`warp`，并设置 `cooldown_ticks: 10` 或更高。

```json
{
  "format_version": 3,
  "global": {
    "timezone": "Asia/Shanghai",
    "language": "zh_cn",
    "data_retention": "full",
    "command_security": {
      "allowed_roots": ["spawn", "home", "warp"],
      "max_command_length": 1024,
      "cooldown_ticks": 10
    },
    "reward_security": { "allow_command_rewards": false, "max_command_length": 1024 }
  },
  "integrations": { "placeholder_api": { "enabled": true } }
}
```

在线奖励的旧 `coins` 字段仍可读取为单条货币奖励。签到、成就和在线奖励的稳定 ID 是去重依据，升级时不要重命名已发放的奖励或里程碑 ID。

## 升级后

启动服务器，检查迁移备份与日志，然后执行 `/omnitools diagnose` 和 `/omnitools reload`。若 reload 失败，旧快照会继续使用；修正 JSON 后再次 reload。详见[备份与恢复](backup-and-recovery.md)。
