# 每日签到

## 1. 用途与场景

玩家用 `/checkin` 或 `/omnitools` 打开 6 行原版箱子：左侧为周一开始的真实月历，右侧显示今日状态、奖励详情、记录、成就入口、连签与余额。奖励详情页分页显示每日和月度奖励。

## 2. 前置条件、关联模块与开关

根开关为 `modules.daily_checkin.enabled`。货币奖励无需额外模块；称号奖励要求 `titles` 开启且称号存在；指令奖励还要求根配置允许。关联规则见[统一奖励](../reference/rewards.md)。

## 3. 配置路径与重载

推荐配置路径：`config/omnitools/daily_checkin/config.json`。修改后执行 `/omnitools reload`。

## 4--6. 最小配置、教学版与可复制版

推荐新格式的教学版，不能直接复制：

```jsonc
{
  "format_version": 2,
  "daily": {
    "rewards": [
      { "id": "daily_coins", "type": "currency", "amount": 100 } // 每日 100 货币
    ]
  },
  "monthly": {
    "7": [
      { "id": "week_item", "type": "item", "item": "minecraft:bread", "count": 4 }
    ]
  }
}
```

可直接复制版：

```json
{
  "format_version": 2,
  "daily": {
    "rewards": [
      { "id": "daily_coins", "type": "currency", "amount": 100 }
    ]
  },
  "monthly": {
    "7": [
      { "id": "week_item", "type": "item", "item": "minecraft:bread", "count": 4 }
    ]
  }
}
```

## 7. 字段表

| 字段 | 类型 | 必填 | 默认/范围 | 常见错误 |
| --- | --- | --- | --- | --- |
| `format_version` | 整数 | 是 | 必须 `2` | 使用 `1` 新格式会失败。 |
| `daily.rewards` | 奖励数组 | 是 | 可为空 | 同一数组的奖励 ID 重复。 |
| `monthly` | 对象 | 是 | 键为正整数天数 | 写成数组或用 `0`。 |
| `monthly.<天数>` | 奖励数组 | 是 | 每个里程碑独立 | 用旧货币数字代替数组。 |

## 8. 全部配置场景

四类奖励均使用[统一奖励格式](../reference/rewards.md)。月度 10 天发称号时，先在称号配置定义 `geologist`；指令奖励先在根配置开启 `allow_command_rewards` 并允许命令根。不要把全部奖励堆进日期 Lore，玩家可在“奖励详情”页查看。

旧格式兼容（只用于升级，不推荐新服）：

```jsonc
{ "dailyCoins": 100, "monthlyRewards": { "7": 500 } }
```

同一旧格式的严格 JSON 版（仅供核对旧服，不要作为新服模板）：

```json
{ "dailyCoins": 100, "monthlyRewards": { "7": 500 } }
```

该旧格式读取为货币奖励，迁移后改用上方新格式并保持奖励 ID 稳定。

## 9. 指令、权限与默认角色

`/checkin`、`/omnitools` 打开签到，默认 `PLAYER`；签到记录和成就入口会按各自模块权限显示。`/omnitools checkin clear` 默认 `ADMIN`。

## 10. 占位符

`%checkin_today%`、`%checkin_today_rank%`、`%checkin_total_days%`、`%checkin_streak_days%`、`%checkin_month_days%`。完整回退规则见[占位符表](../reference/placeholders.md)。

## 11. 数据与升级

签到、排名、货币和月度奖励账本保存在世界 SavedData；配置重载不重置。升级前备份，详见[升级指南](../guides/upgrade-guide.md)。

## 12. 验收与排错

执行 `/checkin`，确认真实月份空位、今天可点击、过去/未来日期不可点击；再打开奖励详情与签到记录。若奖励未到背包，用 `/omnitools rewards open` 检查奖励箱。
