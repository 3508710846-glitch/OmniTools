# 在线奖励

## 1. 模块用途和适用场景

在线奖励按玩家当天累计在线分钟数解锁里程碑，可发货币、物品、称号和受控控制台命令。它使用原版箱子 GUI 与统一奖励账本，适合需要每日在线激励的服务器。

## 2. 模块依赖与关联模块

模块 ID 为 `online_reward`。货币使用共享数据；称号奖励依赖 `titles`；命令奖励受根配置命令安全限制。统一发放和异常处理见[奖励一致性](../guides/reward-consistency.md)。

## 3. 模块开关配置

```json
{ "modules": { "online_reward": { "enabled": true } } }
```

禁用时先保存在线时长，再停止计时并关闭在线奖励 GUI；历史时长、里程碑和账本不会删除。

## 4. 初始配置文件位置

首次启动生成 `config/omnitools/online_reward/config.json`。修改后执行 `/omnitools reload`。

## 5. 最小可用配置

```json
{
  "format_version": 1,
  "rewards": [
    { "id": "online_30m", "minutes": 30, "rewards": [{ "id": "currency", "type": "currency", "amount": 50 }] }
  ]
}
```

## 6. 完整配置示例

```json
{
  "format_version": 1,
  "rewards": [
    {
      "id": "online_30m",
      "minutes": 30,
      "rewards": [
        { "id": "currency", "type": "currency", "amount": 50 },
        { "id": "bread", "type": "item", "item": "minecraft:bread", "count": 2, "components": {} }
      ]
    },
    {
      "id": "online_60m",
      "minutes": 60,
      "rewards": [{ "id": "title", "type": "title", "title": "loyal_player" }]
    }
  ]
}
```

## 7. 配置字段表

| 字段 | 类型 | 必填 | 默认值或范围 | 重载方式 |
| --- | --- | --- | --- | --- |
| `format_version` | integer | 否 | 首次生成 `1` | reload |
| `rewards` | array | 是 | 非空；按分钟严格递增 | reload |
| `rewards[].id` | string | 否 | `online_<minutes>m`，唯一且匹配 `[a-z0-9_.-]{1,64}` | reload |
| `rewards[].minutes` | integer | 是 | 正整数 | reload |
| `rewards[].rewards` | array | 新配置是 | 非空统一奖励定义 | reload |
| `rewards[].coins` | integer | 旧格式兼容 | 非负；会转换为 `legacy_currency` | reload |
| 奖励条目 | object | 是 | 与签到奖励相同的四种类型 | reload |

稳定里程碑 ID 是事件键的一部分；修改已使用 ID 会使历史领取消重失去对应关系。

## 8. 指令、别名和权限节点

| 指令 | 别名 | 权限节点 | 默认角色 |
| --- | --- | --- | --- |
| `/omnitools online [rewards]` | `/checkin online [rewards]` | `online.open` | PLAYER |
| `/omnitools rewards open` | 无 | `rewards.retry` | PLAYER |
| `/omnitools rewards retry` | 无 | `rewards.retry` | PLAYER |

## 9. GUI 操作说明

原版箱子菜单显示每个里程碑所需时长、奖励预览与状态：未解锁、可领取、已领取、待处理或阻塞。满足时长后点击相应格领取；背包满时物品保留在奖励箱。GUI 物品不可取走。

## 10. 占位符列表及用途

`%omnitools:online_today_seconds%`、`online_today_minutes` 与 `online_today_hms` 可显示今日在线时长。完整规则见[Placeholder API](../guides/placeholder-api.md)。

## 11. 数据保存位置和升级影响

在线时长和历史领取标记在世界 `CheckinData`，奖励事件在 `RewardClaimLedger`。事件 ID 采用 `online:<uuid>:<epoch_day>:<milestone_id>`；重连、跨日与 reload 不会重复发放已完成项目。旧 `coins` 配置仍可读取。

## 12. 与其他模块的联动

在线奖励可增加共享货币、授予称号、进入奖励箱；侧边栏可读取在线时长。称号和命令奖励依赖对应模块和根安全开关。

## 13. 常见错误及解决方法

| 现象 | 处理 |
| --- | --- |
| 里程碑没有解锁 | 确认模块已启用、在线分钟数已达到，并检查奖励配置的分钟顺序。 |
| 物品奖励待处理 | 清理背包后用奖励箱重试。 |
| 命令奖励被拒绝 | 检查总开关、命令根白名单、长度和 `console` 身份。 |
| reload 后仍是旧规则 | 新 JSON 校验失败；查看服务端日志并修正。 |

## 14. 可复制的验收清单

- [ ] 30 分钟和 60 分钟里程碑按顺序解锁。
- [ ] 货币、物品、称号奖励均可安全领取。
- [ ] 背包满时奖励不丢失，且不会因重连重复发放。
- [ ] 禁用模块会保存时长、停止计时并关闭 GUI。
