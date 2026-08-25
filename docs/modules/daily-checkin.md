# 每日签到

## 1. 功能简介

每日签到为玩家记录当天签到、连续签到、当月签到和签到历史，并把奖励货币写入共享货币余额。它不依赖商店模块；货币余额与签到记录共同保存在 `CheckinData`。签到和记录界面使用 OmniTools 的自定义菜单类型及客户端界面。

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
  "format_version": 1,
  "dailyCoins": 100,
  "monthlyRewards": {
    "5": 500,
    "10": 1000,
    "15": 2000,
    "25": 5000
  }
}
```

文件缺失时生成上述默认值。`dailyCoins`、`monthlyRewards` 缺失的旧配置会按默认值补全并写回；文件格式错误时不覆盖原文件，完整重载失败且旧快照继续运行。`onlineTimeRewards` 是旧版兼容字段；当前在线奖励应配置在 `online_reward/config.json`。

## 4. 指令与权限

| 指令 | 别名 | 用途 | 默认权限 | 仅玩家 |
| --- | --- | --- | --- | --- |
| `/omnitools` | `/checkin` | 打开签到界面 | `checkin.open` (`PLAYER`) | 是 |
| `/omnitools open` | 无 | 打开签到界面 | `checkin.open` (`PLAYER`) | 是 |
| `/omnitools clear [today]` | `/checkin clear [today]` | 清除当天所有玩家的签到状态 | `checkin.clear` (`ADMIN`) | 否 |

签到界面中的操作由服务端验证；日期使用根配置的 `global.timezone`。

## 5. 配置字段

| 字段 | JSON 类型 | 必填 | 默认值/范围 | 作用与错误行为 |
| --- | --- | --- | --- | --- |
| `format_version` | 任意 JSON 值（当前未读取） | 否 | 首次生成写入 `1` | 当前读取器忽略该字段；它仅作为生成文件的格式标记。 |
| `dailyCoins` | integer | 否 | `100`，`>= 0` | 当日首次签到获得的货币。也兼容旧字段 `dailyReward`、`daily`。非法值拒绝配置。 |
| `monthlyRewards` | object | 否 | `5/10/15/25` 天：`500/1000/2000/5000` | 月内达到里程碑时的货币奖励；键固定为这四个里程碑，值必须为非负整数。也兼容 `monthlyCoins`。 |

## 6. 使用示例

最小配置只调整每日奖励：

```json
{
  "dailyCoins": 200,
  "monthlyRewards": {
    "5": 0,
    "10": 0,
    "15": 0,
    "25": 0
  }
}
```

修改后执行 `/omnitools reload`；失败时查看日志的 `daily_checkin/config.json` 错误并恢复为合法 JSON。省略某个里程碑时会使用该里程碑的默认值；填写 `0` 可禁用该里程碑的货币奖励。

## 7. 数据保存

世界 `SavedData` 中的 `CheckinData` 保存玩家签到日期、连续和月度统计、在线时长、在线奖励领取记录与货币余额。JSON 只保存奖励规则。迁移或升级前同时备份世界目录与 `config/omnitools/`。

## 8. 热重载与依赖

重载按统一快照流程处理：读取所有已启用模块，构造候选快照，完整校验后一次发布并执行运行时补偿；任一配置错误时旧快照继续运行。禁用签到会关闭已打开的签到/记录菜单。签到奖励使用共享货币存储，但货币指令没有独立 `ModuleId`，不要把禁用签到解释为必然禁用所有货币管理指令。
