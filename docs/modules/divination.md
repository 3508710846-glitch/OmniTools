# 每日占卜

> 当前实现为 v2 五级签文。旧版配置仍可兼容读取，但不作为新配置或新交互的依据。

## 当前规则（v2）

- 每名玩家每天免费抽取一次签文；签等只有下下签、下签、中签、上签、上上签五级。
- 抽签后先点击“解签”。解签才会揭示四句签诗、解读、今日宜忌、开运建议，并让当天效果生效。
- 已解签后可消耗 reroll_cost 货币重新抽签，最多次数由 max_daily_draws 控制；旧签会进入历史，不能恢复。
- 每支签可配置一个与主题对应的短期技能经验修正（允许轻微负值）和一次性货币奖励。礼包、命令与奖励发放的技能经验不受签文影响。
- 上上签与下下签在解签时可全服广播，开关为 broadcast_extremes。
- 重抽扣款使用持久化操作 ID 去重；解签货币奖励复用钱包奖励事件去重，断线重试不会重复扣款或重复发奖。
- 钱包由签到与经济模块共享；称号经验倍率仍由技能模块先计算，再叠加当天签文。成就模块可继续用现有奖励体系投放货币或称号，签文不会改变成就领取账本。

### v2 配置

~~~json
{
  "format_version": 2,
  "settings": {
    "enabled": true,
    "daily_free_draws": 1,
    "max_daily_draws": 3,
    "reroll_cost": 250,
    "omen_buff_cap": 0.15,
    "history_retention_days": 30,
    "draw_cooldown_seconds": 3,
    "broadcast_extremes": true
  },
  "signs": [
    {
      "id": "fortune",
      "rank": "upper",
      "weight": 22,
      "theme": "mining",
      "poem": ["晨光铺古道", "新叶满前川", "勤行逢好景", "顺势得心安"],
      "interpretation": "今日有小吉相伴，持续投入更容易得到回报。",
      "favorable": "宜采掘与推进计划内目标。",
      "avoid": "忌分散精力、半途而废。",
      "advice": "优先完成今天最重要的目标。",
      "skill_xp_modifier": 0.08,
      "currency_reward": 80
    }
  ]
}
~~~

rank 只能是 lower_lower、lower、middle、upper、upper_upper。每种签等至少需要一支签文；权重由所有签文共同参与抽取。

旧版 `format_version: 1` 在运行时仍可读取，并会将旧签等归并到当前五级签；不会自动改写管理员已有配置。Schema 位于 [divination.schema.json](../schemas/divination.schema.json)。

## 旧版兼容说明

占卜提供每日一次的短期玩法指引。玩家先求签，再免费解签；已解开的签会按主题给常规技能经验提供加法加成。它不修改永久属性，也不会影响礼包、奖励或管理命令发放的经验。

## 启用与配置

根开关为 `modules.divination.enabled`。首次启用会创建：

```text
config/omnitools/divination/config.json
```

修改后执行 `/omnitools reload divination`。加载或校验失败会保留上一次有效快照。Schema 位于 [divination.schema.json](../schemas/divination.schema.json)。

当前配置必须至少包含五支签文，并且五个 `rank` 各至少出现一次。默认文件提供完整五级签池；上方示例仅展示一条结构。

## 当前交互

- `/divination`、`/fortune` 或 `/omnitools divination` 打开求签台，默认权限为 `divination.open`。
- 玩家直接求签；不再提供主题选择、深度解签或化解任务。
- 免费解签展示四句签诗、解读、今日宜忌、开运建议与当天效果。
- 每天仅保留一支当前有效签；跨日自动失效并归档最近历史。已解签后可按 `reroll_cost` 消耗货币重抽，`max_daily_draws` 限制当天总次数。

## 技能经验联动

可作用的主题如下：

| 主题 | 技能 |
| --- | --- |
| `mining` | Mining、Excavation、Woodcutting、Herbalism 及其旧 ID |
| `combat` | Swords、Axes、Archery 及其旧 ID |
| `exploration` | Acrobatics、Fishing 及其旧 ID |
| `trade`、`social` | 当前只作为指引展示，等待相应的服务端结算事件接入 |

加成位于技能 XP 事务内，在称号倍率之后、每日上限之前计算，并由 `omen_buff_cap` 统一封顶。只有 `BLOCK_BREAK`、`ENTITY_KILL`、`CRAFT` 与 `SURVIVAL` 等正常玩法来源可以得到加成；`REWARD`、`COMMAND` 和礼包经验不会触发签文效果。

## 数据、诊断与占位符

玩家数据保存于世界 `SavedData`：当前签、每日次数、解签状态、最近历史和操作证据均一起持久化。启动时只会协调未完成的操作；诊断不会更改任何状态。

`/omnitools diagnose operation <操作ID>` 能查找占卜抽签和解签记录。可用占位符：

- `divination_rank`、`divination_theme`
- `divination_remaining_hms`
- `divination_resolved`、`divination_bonus_percent`

建议测试服验证：同日重复求签、重抽扣费、解签后重启、跨日失效、技能经验实际加成与礼包经验不受影响。
