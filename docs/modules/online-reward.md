# 在线奖励

## 1. 用途与场景

在线时长按服务器时区的自然日累计。玩家打开原版奖励箱领取已达成的时长里程碑；同一里程碑事件只处理一次。

## 2. 前置条件、关联模块与开关

根开关为 `modules.online_reward.enabled`。四类奖励的额外要求与安全限制见[统一奖励](../reference/rewards.md)。

## 3. 配置路径与重载

使用 `config/omnitools/online_reward/config.json`，保存后执行 `/omnitools reload`。

## 4--6. 最小配置、教学版与可复制版

推荐新格式教学版，不能直接复制：

```jsonc
{
  "format_version": 1,
  "rewards": [
    {
      "id": "online_30m", // 稳定 ID；不要在发放后随意改名
      "minutes": 30,
      "rewards": [{ "id": "coins", "type": "currency", "amount": 50 }]
    }
  ]
}
```

可直接复制版：

```json
{
  "format_version": 1,
  "rewards": [
    {
      "id": "online_30m",
      "minutes": 30,
      "rewards": [{ "id": "coins", "type": "currency", "amount": 50 }]
    },
    {
      "id": "online_60m",
      "minutes": 60,
      "rewards": [{ "id": "bread", "type": "item", "item": "minecraft:bread", "count": 8 }]
    }
  ]
}
```

## 7. 字段表

| 字段 | 类型 | 必填 | 默认/范围 | 常见错误 |
| --- | --- | --- | --- | --- |
| `format_version` | 整数 | 否 | 1 | 不是正整数。 |
| `rewards` | 数组 | 是 | 按分钟严格升序 | 30 后写 30 或 15。 |
| `id` | 字符串 | 否 | `online_<分钟>m` | 重复或更改已使用 ID。 |
| `minutes` | 正整数 | 是 | 1 以上 | 使用秒数。 |
| `rewards` | 奖励数组 | 是 | 至少一项 | `coins` 写在新格式项中。 |

## 8. 全部配置场景

每个里程碑可放货币、物品、称号和指令奖励；完整数组直接复制自[统一奖励](../reference/rewards.md)。每个自然日事件 ID 包含玩家、日期和里程碑 ID，旧 `coins` 格式兼容读取：

```jsonc
{ "onlineTimeRewards": [{ "minutes": 30, "coins": 50 }] }
```

同一旧格式的严格 JSON 版（仅供核对旧服）：

```json
{ "onlineTimeRewards": [{ "minutes": 30, "coins": 50 }] }
```

`onlineTimeRewards` 是当前加载器保留的兼容数组名，旧签到奖励文件也会读取它；它不是新在线奖励文件的推荐写法，新服使用本页的 `rewards` 格式。

## 9. 指令、权限与默认角色

`/omnitools online` 默认 `PLAYER`。奖励箱 `/omnitools rewards open` 也默认 `PLAYER`。

## 10. 占位符

`%online_today_seconds%`、`%online_today_minutes%`、`%online_today_hms%`。

## 11. 数据与升级

在线时长和领取记录在 SavedData；背包满时物品奖励进奖励箱。升级不要重置数据或修改已领取里程碑 ID。

## 12. 验收与排错

进入服务器达到里程碑，打开在线奖励 GUI 领取；背包满时检查奖励箱。重连后同一事件不会重复发放。
