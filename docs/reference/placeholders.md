# OmniTools 内置占位符

标准写法为 `%omnitools:balance%`。侧边栏额外接受简写 `%balance%`。渲染顺序为：OmniTools 内置占位符、可选 Placeholder API、颜色格式。

| 占位符 | 说明 | 依赖模块 | 模块关闭或无玩家时回退值 | 配置示例 |
| --- | --- | --- | --- | --- |
| `balance` | 当前货币 | 无 | `0` | `%omnitools:balance%` |
| `balance_formatted` | 千分位货币 | 无 | `0` | `%balance_formatted%` |
| `checkin_today` | 今天是否签到 | 每日签到 | `false` | `%checkin_today%` |
| `checkin_today_rank` | 今天签到序号 | 每日签到 | `0` | `%checkin_today_rank%` |
| `checkin_total_days` | 累计签到天数 | 每日签到 | `0` | `%checkin_total_days%` |
| `checkin_streak_days` | 连续签到天数 | 每日签到 | `0` | `%checkin_streak_days%` |
| `checkin_month_days` | 本月签到天数 | 每日签到 | `0` | `%checkin_month_days%` |
| `online_today_seconds` | 今天在线秒数 | 在线奖励 | `0` | `%online_today_seconds%` |
| `online_today_minutes` | 今天在线分钟数 | 在线奖励 | `0` | `%online_today_minutes%` |
| `online_today_hms` | 今天在线时分秒 | 在线奖励 | `00:00:00` | `%online_today_hms%` |
| `title_id` | 已佩戴称号 ID | 称号 | 空文本 | `%title_id%` |
| `title` | 带格式的已佩戴称号 | 称号 | 空文本 | `%title%` |
| `title_plain` | 无格式的已佩戴称号 | 称号 | 空文本 | `%title_plain%` |
| `title_effects_enabled` | 称号效果是否开启 | 称号、称号效果 | `false` | `%title_effects_enabled%` |
| `title_remaining_days` | 已佩戴临时称号的剩余有效天数 | 称号 | `0` | `%title_remaining_days%` |
| `title_remaining_hours` | 已佩戴临时称号的剩余有效小时数 | 称号 | `0` | `%title_remaining_hours%` |
| `title_remaining_hms` | 已佩戴临时称号的剩余有效时分秒 | 称号 | `00:00:00` | `%title_remaining_hms%` |
| `title_is_temporary` | 已佩戴称号是否为临时称号 | 称号 | `false` | `%title_is_temporary%` |
| `title_is_equipped` | 是否有可显示的已佩戴称号 | 称号 | `false` | `%title_is_equipped%` |
| `achievements_unlocked` | 已解锁成就数 | 成就 | `0` | `%achievements_unlocked%` |
| `achievements_claimed` | 已领取成就数 | 成就 | `0` | `%achievements_claimed%` |
| `achievements_total` | 配置中的成就总数 | 成就 | `0` | `%achievements_total%` |

共 22 个内置占位符。未知文本占位符显示 `-`，并且每个未知名称只写一次警告日志。可选 API 的安装、开关和第三方变量排查见[Placeholder API](placeholder-api.md)。
