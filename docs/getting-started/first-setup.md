# 第一次配置

OmniTools 仅安装在服务端。首次启动后会创建 `config/omnitools/config.json` 和各模块的 `config.json`。玩家使用原版客户端即可连接。

1. 先备份 `config/omnitools/` 与世界目录的 `data/`。
2. 编辑根配置。新安装保持命令奖励关闭，并列出允许的命令根。
3. 编辑要启用模块的配置文件。
4. 以管理员身份执行 `/omnitools reload`。失败时服务器保留上一份有效快照。

教学版不能直接复制：

```jsonc
{
  "global": { // 全局运行参数。
    "timezone": "Asia/Shanghai" // IANA 时区；会影响签到日界线。
  },
  "modules": { // 每个模块都由根配置开关控制。
    "daily_checkin": { "enabled": true } // 开启每日签到模块。
  }
}
```

可直接复制的最小根配置请见[根配置参考](../reference/root-config.md)。修改配置后均执行：

```text
/omnitools reload
```

不要删除 SavedData、`world/data/` 或奖励账本来“重置配置”。它们保存货币、签到、成就、称号与待处理奖励；详见[备份与恢复](../guides/backup-and-recovery.md)。
