# 每日签到

## 1. 用途与场景

玩家用 `/checkin` 或 `/omnitools` 打开 6 行原版箱子。左侧是周一开始的真实月历，右侧固定显示玩家信息、月份、奖励、进度、记录、成就入口、连续签到、余额和奖励箱。奖励详情页会分页显示每日和月度奖励。

## 2. 前置条件、关联模块与开关

根开关为 `modules.daily_checkin.enabled`。货币奖励无需额外模块；称号奖励要求 `titles` 开启且称号存在；指令奖励还要求根配置允许。关联规则见[统一奖励](../reference/rewards.md)。

## 3. 配置路径与重载

推荐配置路径：`config/omnitools/daily_checkin/config.json`。首次启动生成默认文件。修改后执行 `/omnitools reload`。

## 4. 最小可用配置

下方以每日货币和一个月度物品里程碑构成最小可用配置。

## 5. 注释教学版 `jsonc`

推荐新格式的教学版，`jsonc` 中的注释不能直接复制到真实 `config.json`：

```jsonc
{
  "format_version": 2, // 每日签到的新格式版本，必须为 2。
  "daily": { // 每天签到时发放的奖励。
    "rewards": [
      { "id": "daily_coins", "type": "currency", "amount": 100 }, // 稳定奖励 ID、类型和数量。
      {
        "id": "daily_vip_7d", "type": "title", "title": "vip",
        "duration": { "mode": "active_days", "days": 7 }, "renewal": "extend"
      } // 仅在在线佩戴时扣除的临时称号。
    ]
  },
  "monthly": { // 按本月累计签到天数发放的里程碑奖励。
    "7": [
      { "id": "week_item", "type": "item", "item": "minecraft:bread", "count": 4 }
    ]
  }
}
```

## 6. 可直接复制版 `json`

```json
{
  "format_version": 2,
  "daily": {
    "rewards": [
      { "id": "daily_coins", "type": "currency", "amount": 100 },
      {
        "id": "daily_vip_7d",
        "type": "title",
        "title": "vip",
        "duration": { "mode": "active_days", "days": 7 },
        "renewal": "extend"
      }
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
| `format_version` | 整数 | 是 | 必须 `2` | 在新格式中使用 `1`。 |
| `daily.rewards` | 奖励数组 | 是 | 可为空 | 同一数组的奖励 ID 重复。 |
| `monthly` | 对象 | 是 | 键为正整数天数 | 写成数组或使用 `0`。 |
| `monthly.<天数>` | 奖励数组 | 是 | 每个里程碑独立 | 用旧货币数字代替数组。 |
| `ui.style` | 字符串 | 否 | `journal` | 使用未支持的主题名。 |
| `ui.icons.*` | 原版物品 ID | 否 | 见第 13 节 | 使用 `minecraft:air` 或不存在的物品。 |
| `ui.sounds.*` | 布尔值 | 否 | 全部为 `true` | 写成字符串 `"true"`。 |

## 8. 全部配置场景

四类奖励均使用[统一奖励格式](../reference/rewards.md)。每日 `daily.rewards` 和月度 `monthly.<天数>` 都可使用该页的完整 ItemStack SNBT `nbt` 写法；月度 10 天发称号时，先在称号配置定义 `geologist`；指令奖励先在根配置开启 `allow_command_rewards` 并允许命令根。不要把全部奖励堆进日期 Lore，玩家可在“奖励详情”页查看。

旧格式兼容（只用于升级，不推荐新服）：

```jsonc
{ "dailyCoins": 100, "monthlyRewards": { "7": 500 } } // 旧服兼容：每日货币和第 7 天月度货币。
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

## 13. 日历手账 UI 配置

新配置使用推荐的 `journal` 主题：月历占左侧 7 列，右侧固定显示玩家、月份、奖励、连签、余额、奖励箱和操作按钮。缺少 `ui` 时自动使用内置默认值，旧服务器不必修改配置。

教学版 `jsonc` 中的 `//` 注释仅用于学习，不能直接复制到真实 `config.json`：

```jsonc
{
  "format_version": 2,
  "daily": { "rewards": [{ "id": "daily_coins", "type": "currency", "amount": 100 }] },
  "monthly": { "7": [{ "id": "week_item", "type": "item", "item": "minecraft:bread", "count": 4 }] },
  "ui": {
    "style": "journal", // 日历手账主题。
    "show_weekday": true, // 日期名显示星期一至星期日。
    "show_progress_bar": true, // 显示 10 格文本进度条。
    "show_action_hints": true, // 今日可签到日期显示点击提示。
    "show_reward_preview": true, // 奖励面板优先使用真实物品图标。
    "icons": {
      "available": "minecraft:clock", // 今日可签到。
      "signed": "minecraft:book", // 今日已签到。
      "past_signed": "minecraft:lime_dye", // 过去已签到。
      "missed": "minecraft:red_dye", // 已漏签的过去日期。
      "future": "minecraft:paper", // 未来日期。
      "milestone": "minecraft:chest", // 月度奖励日。
      "empty": "minecraft:map" // 月历中的无效日期格。
    },
    "sounds": {
      "open": true, // 打开菜单。
      "click": true, // 普通按钮操作。
      "success": true, // 签到成功。
      "failure": true // 重复签到或不可用操作。
    }
  }
}
```

可直接复制的严格 JSON：

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
  },
  "ui": {
    "style": "journal",
    "show_weekday": true,
    "show_progress_bar": true,
    "show_action_hints": true,
    "show_reward_preview": true,
    "icons": {
      "available": "minecraft:clock",
      "signed": "minecraft:book",
      "past_signed": "minecraft:lime_dye",
      "missed": "minecraft:red_dye",
      "future": "minecraft:paper",
      "milestone": "minecraft:chest",
      "empty": "minecraft:map"
    },
    "sounds": {
      "open": true,
      "click": true,
      "success": true,
      "failure": true
    }
  }
}
```

槽位固定为：日期 `0-6、9-15、18-24、27-33、36-42、45-51`；头像 `7`、月份 `8`、奖励 `16`、进度 `17`、记录 `25`、成就 `26`、连续签到 `34`、余额 `35`、奖励箱 `43`、说明 `44`、刷新 `52`、关闭 `53`。日期以图标和文字双重表达：今日可签到、今日已签到、过去已签到、漏签、未来和无效日期分别为金色、绿色、绿色、红色、灰色和深灰色。

修改后执行 `/omnitools reload`。已打开的菜单会按新快照刷新；若校验失败，旧快照继续运行。所有装饰槽都由服务端拦截，奖励物品只能从奖励详情页或奖励箱领取。
