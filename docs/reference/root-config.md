# 根配置 `config/omnitools/config.json`

格式版本固定为 `4`。新安装请使用下面的推荐格式；旧服迁移时保留原文件备份并阅读[升级指南](../guides/upgrade-guide.md)。

教学版，不能直接复制：

```jsonc
{
  "format_version": 4, // 根配置格式版本，当前必须为 4。
  "global": { // 全局运行参数。
    "debug": false, // 是否输出额外诊断日志。
    "timezone": "Asia/Shanghai", // IANA 时区
    "language": "zh_cn", // 仅 zh_cn 或 en_us
    "data_retention": "full", // full、monthly_summary、archive
    "command_security": { // 命令菜单和受控命令的安全限制。
      "allowed_roots": ["spawn", "home", "warp"], // 新服不能写 *
      "max_command_length": 1024, // 单条菜单命令的最大字符数。
      "cooldown_ticks": 10 // 同一玩家执行菜单命令的冷却 tick。
    },
    "reward_security": { // 指令奖励的独立安全开关。
      "allow_command_rewards": false, // 默认禁止执行奖励命令。
      "max_command_length": 1024 // 单条奖励命令的最大字符数。
    }
  },
  "integrations": { // 可选外部模组集成。
    "placeholder_api": { "enabled": true } // 安装 API 时才会实际解析第三方占位符。
  },
  "modules": { // 模块运行时开关。
    "daily_checkin": { "enabled": true } // 开启每日签到模块。
  }
}
```

可直接复制版。下面十一个模块开关均存在，按需改为 `false`：

```json
{
  "format_version": 4,
  "global": {
    "debug": false,
    "timezone": "Asia/Shanghai",
    "language": "zh_cn",
    "data_retention": "full",
    "command_security": {
      "allowed_roots": ["spawn", "home", "warp"],
      "max_command_length": 1024,
      "cooldown_ticks": 10
    },
    "reward_security": {
      "allow_command_rewards": false,
      "max_command_length": 1024
    }
  },
  "integrations": { "placeholder_api": { "enabled": true } },
  "modules": {
    "daily_checkin": { "enabled": true },
    "cdk": { "enabled": true },
    "online_reward": { "enabled": true },
    "shop": { "enabled": true },
    "titles": { "enabled": true },
    "title_effects": { "enabled": true },
    "achievements": { "enabled": true },
    "cloud_storage": { "enabled": true },
    "permissions": { "enabled": false },
    "command_menu": { "enabled": true },
    "sidebar": { "enabled": true }
  }
}
```

| 字段 | 类型与范围 | 默认值 | 说明 |
| --- | --- | --- | --- |
| `format_version` | 整数，必须为 4 | 4 | 根格式版本。 |
| `global.timezone` | IANA ZoneId | `Asia/Shanghai` | 签到日界线。 |
| `language` | `zh_cn` / `en_us` | `zh_cn` | 内置文本语言。 |
| `data_retention` | `full` / `monthly_summary` / `archive` | `full` | `full` 不改变旧服行为。 |
| `allowed_roots` | 命令根数组 | `[]` | 新服默认拒绝；`*` 仅为旧服兼容宽松模式。 |
| `max_command_length` | 1--16384 | 1024 | 菜单命令上限。 |
| `cooldown_ticks` | 0--72000 | 10 | 每玩家可配置命令冷却。 |
| `allow_command_rewards` | 布尔 | `false` | 指令奖励总开关。 |
| `integrations.placeholder_api.enabled` | 布尔 | `true` | API 未安装时仍可启动。 |
| `modules.<id>.enabled` | 布尔 | 权限模块为 false，其余 true | 禁用会关闭相关界面并停止模块处理。 |

旧格式兼容：升级器会将旧根配置迁移到 v4 并创建备份。旧服已迁移出的 `allowed_roots: ["*"]` 保留可用性但会记录宽松模式警告；新服不要复制它。v3 及更早配置升级时，新增的 CDK 模块保持关闭，需管理员在 v4 根配置中显式启用。

v4 新增 `config/omnitools/common/` 公共配置目录：

- `rewards.json`：可在模块奖励数组中通过 `"template": "模板 ID"` 复用奖励定义。
- `conditions.json`：成就条件可通过 `"template": "模板 ID"` 复用条件树节点。
- `texts.json`：模块可逐步复用稳定文本键；未知模板、循环引用和超过 4 层引用会阻止重载。

公共模板只展开数据，不允许执行代码；奖励账本、权限、命令白名单、NBT 限制和存档迁移仍由代码固定控制。
