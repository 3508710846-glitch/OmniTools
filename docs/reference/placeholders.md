# OmniTools 内置占位符

标准写法为 `%omnitools:balance%`。侧边栏和其他可配置文本也接受简写 `%balance%`。文本渲染顺序是内置占位符、可选 Placeholder API、颜色格式。

| 占位符 | 说明 | 依赖模块 | 模块关闭时 | 配置示例 |
| --- | --- | --- | --- | --- |
| `balance` | 当前货币 | 无 | `0` | `%omnitools:balance%` |
| `balance_formatted` | 千分位货币 | 无 | `0` | `%balance_formatted%` |
| `checkin_today` | 今日是否签到 | 每日签到 | `false` | `%checkin_today%` |
| `checkin_today_rank` | 今日签到序号 | 每日签到 | `0` | `%checkin_today_rank%` |
| `checkin_total_days` | 累计签到 | 每日签到 | `0` | `%checkin_total_days%` |
| `checkin_streak_days` | 连续签到 | 每日签到 | `0` | `%checkin_streak_days%` |
| `checkin_month_days` | 本月签到 | 每日签到 | `0` | `%checkin_month_days%` |
| `online_today_seconds` | 今日在线秒数 | 在线奖励 | `0` | `%online_today_seconds%` |
| `online_today_minutes` | 今日在线分钟 | 在线奖励 | `0` | `%online_today_minutes%` |
| `online_today_hms` | 今日在线时分秒 | 在线奖励 | `00:00:00` | `%online_today_hms%` |
| `title_id` | 已佩戴称号 ID | 称号 | 空文本 | `%title_id%` |
| `title` | 带格式称号 | 称号 | 空文本 | `%title%` |
| `title_plain` | 无格式称号 | 称号 | 空文本 | `%title_plain%` |
| `title_effects_enabled` | 称号效果是否开启 | 称号、称号效果 | `false` | `%title_effects_enabled%` |
| `achievements_unlocked` | 已解锁成就数 | 成就 | `0` | `%achievements_unlocked%` |
| `achievements_claimed` | 已领奖成就数 | 成就 | `0` | `%achievements_claimed%` |
| `achievements_total` | 配置的成就数 | 成就 | `0` | `%achievements_total%` |

未知文本占位符显示 `-`，且每个未知名称只在日志中警告一次。内置占位符被 Text Placeholder API 注册时，第三方模组也使用 `%omnitools:名称%` 读取。
