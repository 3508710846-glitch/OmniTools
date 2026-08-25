# 每日签到

## 1. 功能简介

每日签到为玩家记录当天签到、连续签到、当月签到和签到历史。每日和月度奖励都使用统一奖励定义，可发放货币、物品、称号和受控的服务器指令。它不依赖商店模块；货币余额与签到记录共同保存在 `CheckinData`。签到和记录界面均为原版箱子 GUI，原版客户端无需安装 OmniTools。

## 2. 模块开关

根配置为 `config/omnitools/config.json`：

```json
{
  "modules": {
    "daily_checkin": { "enabled": true }
  }
}
```

禁用后不能打开签到和记录菜单，也不会发送未签到提醒；已有签到与货币数据不会删除，重新启用后继续使用。

## 3. 初始配置

首次加载时生成 `config/omnitools/daily_checkin/config.json`：

```json
{
  "format_version": 2,
  "daily": {
    "rewards": [
      { "id": "daily_currency", "type": "currency", "amount": 100 }
    ]
  },
  "monthly": {
    "5": [{ "id": "month_5_currency", "type": "currency", "amount": 500 }],
    "10": [{ "id": "month_10_currency", "type": "currency", "amount": 1000 }],
    "15": [{ "id": "month_15_currency", "type": "currency", "amount": 2000 }],
    "25": [{ "id": "month_25_currency", "type": "currency", "amount": 5000 }]
  }
}
```

文件缺失时生成上述 v2 默认值。配置文件损坏时不会覆盖原文件，完整重载失败且旧快照继续运行。`onlineTimeRewards` 是旧版兼容字段；当前在线奖励应配置在 `online_reward/config.json`。

旧格式仍可读取，不需要先迁移文件：`dailyCoins`（也兼容 `dailyReward`、`daily`）转换为 ID 为 `legacy_daily_currency` 的每日货币奖励；`monthlyRewards`（也兼容 `monthlyCoins`）的各里程碑转换为 `legacy_monthly_<天数>_currency`。旧格式不会自动覆写为 v2。

## 4. 指令与权限

| 指令 | 别名 | 用途 | 默认权限 | 仅玩家 |
| --- | --- | --- | --- | --- |
| `/omnitools` | `/checkin` | 打开签到界面 | `checkin.open` (`PLAYER`) | 是 |
| `/omnitools open` | 无 | 打开签到界面 | `checkin.open` (`PLAYER`) | 是 |
| `/omnitools clear [today]` | `/checkin clear [today]` | 清除当天所有玩家的签到状态 | `checkin.clear` (`ADMIN`) | 否 |
| `/omnitools rewards retry` | 无 | 重试自己账本中待处理的签到和成就奖励 | `rewards.retry` (`PLAYER`) | 是 |

签到界面中的操作由服务端验证；日期使用根配置的 `global.timezone`。

## 5. 配置字段

| 字段 | JSON 类型 | 必填 | 默认值/范围 | 作用与错误行为 |
| --- | --- | --- | --- | --- |
| `format_version` | integer | v2 是 | `2` | v2 奖励数组格式；不是 `2` 时按旧格式读取。 |
| `daily` | object | v2 是 | 无 | 每日奖励容器；缺失或不是对象会拒绝 v2 候选配置。 |
| `daily.rewards` | array | v2 是 | 可为空 | 每日首次签到创建的奖励事件；数组顺序是发放顺序。 |
| `monthly` | object | v2 是 | 可为空 | 月度里程碑映射；键为正整数天数，值为奖励数组。 |
| `monthly.<天数>` | array | 是 | 可为空 | 当月签到天数达到该里程碑后发放一次。全部奖励成功后才记录该里程碑已领取。 |
| `rewards[].id` | string | 是 | `[a-z0-9_.-]{1,64}`，同一数组唯一 | 稳定账本 ID；调整数组顺序不会重复发放。非法或重复会拒绝配置。 |
| `rewards[].type` | string | 是 | `currency`、`item`、`title`、`command` | 奖励类型；未知值拒绝配置。 |
| `rewards[].amount` | integer | `currency` 是 | `>= 0` | 货币金额，使用 `long` 保存；负数或溢出拒绝配置。 |
| `rewards[].item`、`count`、`components` | string、integer、string/空 object | `item` 是 | `count` 为 `1-64` | 物品奖励。`components` 复用商店的原版组件文本；`{}` 表示没有组件。无效物品、组件或单事件物品数超过 2304 会拒绝配置。 |
| `rewards[].title` | string | `title` 是 | 有效称号 ID | 称号奖励；称号模块关闭或 ID 不存在时拒绝候选配置。已拥有视为成功。 |
| `rewards[].run_as`、`command` | string、string | `command` 是 | 仅 `console`；命令长度不超过根配置限制 | 命令奖励；只允许白名单占位符，禁止换行和未知占位符。根配置未显式开启时拒绝候选配置。 |
| `dailyCoins`、`monthlyRewards` | integer、object | 否 | 旧格式默认 `100` 与 `5/10/15/25` | 仅旧格式兼容字段，分别转换为稳定的遗留货币奖励。 |

## 6. 使用示例

最小 v2 配置只调整每日奖励：

```json
{
  "format_version": 2,
  "daily": {
    "rewards": [
      { "id": "daily_currency", "type": "currency", "amount": 200 }
    ]
  },
  "monthly": {
    "5": [],
    "10": [],
    "15": [],
    "25": []
  }
}
```

每日奖励可混合四种类型：

```json
{
  "format_version": 2,
  "daily": {
    "rewards": [
      { "id": "daily_currency", "type": "currency", "amount": 100 },
      { "id": "daily_bread", "type": "item", "item": "minecraft:bread", "count": 3, "components": {} },
      { "id": "daily_title", "type": "title", "title": "loyal_player" }
    ]
  },
  "monthly": {}
}
```

指令奖励还必须在根配置中显式开启 `global.reward_security.allow_command_rewards`，并使用 `run_as: "console"`。修改后执行 `/omnitools reload`；失败时查看日志的 `daily_checkin/config.json` 错误并恢复为合法 JSON。

## 7. 数据保存

世界 `SavedData` 中的 `CheckinData` 保存玩家签到日期、连续和月度统计、在线时长、在线奖励领取记录与货币余额。独立的 `RewardClaimLedger` 按“事件 ID + 奖励 ID”保存 `PENDING`、`GRANTED`、`BLOCKED`、`FAILED` 与失败原因；JSON 只保存奖励规则。

物品只会完整放入玩家主背包，空间不足不会掉落或部分发放，账本保持 `PENDING`。玩家登录、打开签到界面和执行 `/omnitools rewards retry` 都会重试历史待处理签到事件。迁移或升级前同时备份世界目录与 `config/omnitools/`。

## 8. 热重载与依赖

重载按统一快照流程处理：读取所有已启用模块，构造候选快照，完整校验后一次发布并执行运行时补偿；任一配置错误时旧快照继续运行。禁用签到会关闭已打开的签到/记录菜单。称号奖励依赖启用的 `titles` 模块和已定义的称号；模块管理 GUI 会拒绝在仍有签到或成就称号奖励时关闭称号模块。签到奖励使用共享货币存储，但货币指令没有独立 `ModuleId`，不要把禁用签到解释为必然禁用所有货币管理指令。
