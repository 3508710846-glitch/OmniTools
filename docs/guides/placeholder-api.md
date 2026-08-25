# Placeholder API

## 可选依赖

Text Placeholder API 是可选依赖，不属于模块开关。未安装时 OmniTools 仍可启动，内置文本照常渲染；第三方 `%namespace:id%` 占位符安全回退为 `-`。已安装且根配置允许时，OmniTools 注册自己的 17 个占位符并可解析第三方文本占位符。

根配置开关：

```json
{
  "integrations": { "placeholder_api": { "enabled": true } }
}
```

关闭该开关不会删除任何数据，但不会解析第三方文本占位符。注册在服务器进程中完成；修改后重启服务器可保证注册状态与开关一致。

## 内置占位符

使用 `%omnitools:<id>%`。侧边栏也接受 `%<id>%` 形式。

`balance`、`balance_formatted`、`checkin_today`、`checkin_today_rank`、`checkin_total_days`、`checkin_streak_days`、`checkin_month_days`、`online_today_seconds`、`online_today_minutes`、`online_today_hms`、`title_id`、`title`、`title_plain`、`title_effects_enabled`、`achievements_unlocked`、`achievements_claimed`、`achievements_total`。

模块关闭或无玩家上下文时使用稳定回退：数值为 `0`，布尔值为 `false`，称号文本为空，在线时长格式为 `00:00:00`。

## 文本与命令边界

可配置的玩家可见文本按“内置占位符 → 可选 Placeholder API → 颜色格式”渲染。未知占位符只记录一次警告并安全回退。

第三方文本占位符绝不能用于控制台命令内容。命令菜单和 `command` 奖励只接受受控变量 `{player_name}`、`{player_uuid}`、`{player_x}`、`{player_y}`、`{player_z}`、`{player_world}`，还必须通过命令根白名单。

## 验收清单

- [ ] 不安装 Placeholder API 时服务器可启动且侧边栏正常显示。
- [ ] 安装 API 后，第三方模组可读取 `%omnitools:balance%`。
- [ ] 侧边栏中的未知第三方占位符显示 `-`，不会中断刷新。
