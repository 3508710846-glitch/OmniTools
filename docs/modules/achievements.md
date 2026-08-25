# 成就

## 1. 模块用途和适用场景

成就模块基于原版玩家统计构建条件树，满足后永久解锁，并让玩家从原版箱子 GUI 领取一次性统一奖励。适用于采集、战斗、探索、距离和容器交互目标。

## 2. 模块依赖与关联模块

模块 ID 为 `achievements`。称号奖励依赖 `titles`；命令奖励受根命令安全规则限制；奖励状态与物品奖励箱见[奖励一致性](../guides/reward-consistency.md)。

## 3. 模块开关配置

```json
{ "modules": { "achievements": { "enabled": true } } }
```

禁用会停止分批检查、清空调度队列并关闭成就 GUI；已解锁、已领取和账本记录保留。重新启用后会对在线玩家重新建立检查队列。

## 4. 初始配置文件位置

首次启动生成 `config/omnitools/achievements/config.json`。修改后执行 `/omnitools reload`。

## 5. 最小可用配置

```json
{
  "format_version": 2,
  "achievements": [
    {
      "id": "stone_breaker",
      "display": "石匠",
      "description": "挖掘石头 1000 个",
      "icon": "minecraft:stone",
      "requirements": { "type": "stat", "stat": "block_mined", "targets": ["minecraft:stone"], "at_least": 1000 },
      "rewards": []
    }
  ]
}
```

## 6. 完整配置示例

```json
{
  "format_version": 2,
  "target_groups": { "ores": ["#minecraft:coal_ores", "minecraft:iron_ore"] },
  "achievements": [
    {
      "id": "ore_miner",
      "display": "矿工",
      "description": "累计挖掘 100 块矿石",
      "icon": "minecraft:iron_pickaxe",
      "requirements": {
        "type": "all",
        "children": [
          { "type": "stat", "stat": "block_mined", "targets": ["$ores"], "match": "sum", "at_least": 100 },
          { "type": "not", "child": { "type": "stat", "stat": "custom", "custom_stat": "minecraft:time_since_rest", "unit": "ticks", "at_least": 144000 } }
        ]
      },
      "rewards": [{ "id": "coins", "type": "currency", "amount": 500 }]
    }
  ]
}
```

## 7. 配置字段表

| 字段 | 类型 | 必填 | 默认值或范围 | 重载方式 |
| --- | --- | --- | --- | --- |
| `format_version` | integer | 否 | 新配置为 `2`，兼容 v1 | reload |
| `target_groups` | object | 否 | 组名与目标列表；可引用 `$group`、标签或通配符 | reload |
| `achievements` | array | 是 | 成就 ID 唯一 | reload |
| `id`、`display`、`description`、`icon` | string | 是 | 有效 ID、文本和非空气物品 | reload |
| `requirements` | object | 是 | `stat`、`sum`、`all`、`any`、`not` 条件树 | reload |
| `stat.targets` | string array | 非 custom 时 | 有效目标；支持组、标签、通配符 | reload |
| `stat.custom_stat`、`unit` | string、string | custom 时 | 距离、时间、伤害或计数的有效单位 | reload |
| `rewards` | array | 否 | 四种统一奖励类型 | reload |

`all` 与 `any` 使用非空 `children`；`not` 使用单个 `child`；`sum` 使用非空 `sources`。调度检查受服务端预算限制，打开菜单和领取时会针对当前玩家实时复核。

## 8. 指令、别名和权限节点

| 指令 | 别名 | 权限节点 | 默认角色 |
| --- | --- | --- | --- |
| `/omnitools achievements [open]` | `/checkin achievements [open]` | `achievements.open` | PLAYER |
| `/omnitools rewards open` | 无 | `rewards.retry` | PLAYER |
| `/omnitools rewards retry` | 无 | `rewards.retry` | PLAYER |

## 9. GUI 操作说明

原版箱子界面显示锁定、已解锁、可领取、已领取和待处理状态，并支持分页。点击可领取成就触发账本发放；物品空间不足时进入奖励箱。菜单不允许取走 UI 物品。

## 10. 占位符列表及用途

`%omnitools:achievements_unlocked%`、`achievements_claimed` 和 `achievements_total` 可显示进度。见[Placeholder API](../guides/placeholder-api.md)。

## 11. 数据保存位置和升级影响

解锁与领取状态位于世界 `AchievementData`，奖励状态位于 `RewardClaimLedger`。保留成就 ID 与奖励 ID 可避免升级后重复发奖；配置 JSON 不保存玩家进度。

## 12. 与其他模块的联动

成就可发共享货币、物品、称号与受控命令。侧边栏使用成就占位符；模块管理在 reload 时校验称号奖励和命令安全。

## 13. 常见错误及解决方法

| 现象 | 处理 |
| --- | --- |
| 成就不解锁 | 检查统计域、目标注册表 ID、单位和阈值；打开 GUI 可触发当前玩家复核。 |
| reload 失败 | 检查组循环、标签为空、通配符混用、树深度或目标数量。 |
| 奖励无法领取 | 检查奖励箱、称号模块或命令安全配置。 |

## 14. 可复制的验收清单

- [ ] `all`、`any`、`not`、`sum` 条件按预期计算。
- [ ] 距离、时间和伤害单位能正确换算。
- [ ] 热重载后调度队列重建，已解锁成就不重复领取。
- [ ] 逐个验证下列预设可通过配置校验：采集、战斗、探索、距离、容器交互。

## 预设

- [采集](../presets/achievements/collection.json)：方块、物品采集类目标。
- [战斗](../presets/achievements/combat.json)：击杀与战斗统计。
- [探索](../presets/achievements/exploration.json)：探索和自定义统计。
- [距离](../presets/achievements/distance.json)：行走、飞行等距离统计。
- [容器交互](../presets/achievements/container-interaction.json)：容器开启等交互统计。

合并预设时必须确保成就 ID 与奖励 ID 保持唯一。
