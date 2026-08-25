# 在线奖励

## 1. 功能简介

在线奖励按玩家当天累计在线时长发放一次性货币奖励。服务端每 20 tick 刷盘在线时长，断开连接、停服和禁用模块前也会保存未写入的时长。奖励界面为原版箱子 GUI，原版客户端无需安装 OmniTools。

## 2. 模块开关

`config/omnitools/config.json`：

```json
{
  "modules": {
    "online_reward": { "enabled": true }
  }
}
```

禁用时周期计时停止、在线奖励菜单关闭，并先执行 `flushAll` 保存在线玩家时长。历史时长与已领取状态保留，重新启用后继续使用。

## 3. 初始配置

首次加载生成 `config/omnitools/online_reward/config.json`：

```json
{
  "format_version": 1,
  "rewards": [
    { "id": "online_30m", "minutes": 30, "coins": 50 },
    { "id": "online_60m", "minutes": 60, "coins": 100 },
    { "id": "online_120m", "minutes": 120, "coins": 250 }
  ]
}
```

文件缺失时创建该配置。格式错误时不会覆盖文件，完整重载保留旧快照。旧字段 `onlineTimeRewards` 仅作为读取兼容入口。

## 4. 指令与权限

| 指令 | 别名 | 用途 | 默认权限 | 仅玩家 |
| --- | --- | --- | --- | --- |
| `/omnitools online` | `/checkin online` | 打开在线奖励界面 | `online.open` (`PLAYER`) | 是 |
| `/omnitools online rewards` | `/checkin online rewards` | 打开在线奖励界面 | `online.open` (`PLAYER`) | 是 |

## 5. 配置字段

| 字段 | JSON 类型 | 必填 | 默认值/范围 | 作用与错误行为 |
| --- | --- | --- | --- | --- |
| `format_version` | 任意 JSON 值（当前未读取） | 否 | 首次生成写入 `1` | 当前读取器忽略该字段；它仅作为生成文件的格式标记。 |
| `rewards` | array | 是 | 默认 3 项 | 奖励列表；必须按 `minutes` 严格递增。缺失或非数组拒绝配置。 |
| `rewards[].id` | string | 否 | `online_<minutes>m` | 稳定领取 ID，匹配 `[a-z0-9_.-]{1,64}` 且不能重复。 |
| `rewards[].minutes` | integer | 是 | 正整数 | 达到的分钟数；必须递增。 |
| `rewards[].coins` | integer | 是 | `>= 0` | 领取时增加的货币。 |

## 6. 使用示例

```json
{
  "format_version": 1,
  "rewards": [
    { "id": "online_15m", "minutes": 15, "coins": 20 },
    { "id": "online_90m", "minutes": 90, "coins": 150 }
  ]
}
```

保存后执行 `/omnitools reload`。如日志提示 ID 重复或分钟未排序，修正后重载；旧配置在失败期间仍有效。

## 7. 数据保存

在线时长、按日期的领取记录和货币余额保存在世界 `SavedData` 的 `CheckinData`，不在 JSON 中。跨日时服务端依 `global.timezone` 将一次会话切分到各日期。

升级或迁移前必须同时备份世界目录和 `config/omnitools/`。

## 8. 热重载与依赖

成功重载会更新菜单定义；禁用模块先刷盘再停止计时，已打开菜单关闭。没有对其他模块的必需依赖，奖励货币使用共享余额。统一重载的候选快照失败时，旧配置与计时行为保持不变。
