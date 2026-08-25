# Placeholder API

## 1. 功能简介

Placeholder API 是可选集成，不是 `ModuleId`，用于向下游文本模组注册 OmniTools 占位符，也用于侧边栏解析第三方占位符。

## 2. 模块开关

集成开关位于 `config/omnitools/config.json`，不在模块管理 GUI 中：

```json
{
  "integrations": {
    "placeholder_api": { "enabled": true }
  }
}
```

关闭集成不会关闭任何模块；玩家数据和内置侧边栏占位符不受影响。

## 3. 初始配置

根配置缺失该字段时默认启用。Placeholder API 本身未安装时 OmniTools 仍正常启动，注册动作会跳过。

## 4. 指令与权限

本集成没有独立的玩家命令；配置修改使用以下入口：

| 指令 | 别名 | 用途 | 默认权限 | 仅玩家 |
| --- | --- | --- | --- | --- |
| `/omnitools reload` | 无 | 重新读取集成开关和模块配置 | `config.reload` (`ADMIN`) | 否 |

## 5. 配置字段

| 字段 | JSON 类型 | 必填 | 默认值/范围 | 作用与错误行为 |
| --- | --- | --- | --- | --- |
| `integrations.placeholder_api.enabled` | boolean | 否 | `true` | 控制可选 API 注册和第三方解析；类型错误使完整快照拒绝。 |

## 6. 使用示例

OmniTools 注册的 ID 使用 `omnitools:<id>` 格式：

```text
omnitools:balance
omnitools:balance_formatted
omnitools:checkin_today
omnitools:checkin_today_rank
omnitools:checkin_total_days
omnitools:checkin_streak_days
omnitools:checkin_month_days
omnitools:online_today_seconds
omnitools:online_today_minutes
omnitools:online_today_hms
omnitools:title_id
omnitools:title
omnitools:title_plain
omnitools:title_effects_enabled
omnitools:achievements_unlocked
omnitools:achievements_claimed
omnitools:achievements_total
```

侧边栏内置占位符可写 `%omnitools:balance%` 或 `%balance%`；启用 API 后也可尝试 `%example:rank%` 等第三方 ID。修改根配置后执行 `/omnitools reload`。

内置 ID 的模块关闭或没有玩家上下文时回退如下：

| ID 类别 | 回退文本 |
| --- | --- |
| `balance`、`balance_formatted` | 货币不是独立模块，始终读取共享余额。 |
| `checkin_today`、`title_effects_enabled` | `false` |
| `online_today_hms` | `00:00:00` |
| `title_id`、`title`、`title_plain` | 空文本 |
| 其余签到、在线秒/分钟、成就计数 ID | `0` |

## 7. 数据保存

占位符是实时计算值，不写入 JSON 或世界数据。它读取当前模块数据；模块关闭时对应值使用解析器的回退值。

升级或迁移前仍应同时备份世界目录和 `config/omnitools/`，以保留集成开关及它读取的模块数据。

## 8. 热重载与依赖

成功重载会尝试注册可选集成，并刷新侧边栏文本。API 未安装时注册跳过，内置占位符仍可用于侧边栏。第三方占位符解析失败显示 `-` 并记录警告，不影响其他模块。当前实现只在本进程首次满足开关和依赖条件时注册 OmniTools 的 API ID；热重载将开关改为关闭不会注销已注册 ID，需重启服务器后才会以关闭状态启动。该开关会立即停止侧边栏的第三方占位符解析。
