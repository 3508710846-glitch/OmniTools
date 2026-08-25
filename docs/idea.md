# Mod idea

可将签到和成就的奖励统一为一套“奖励定义 + 发放服务 + 持久化账本”机制。这样四种奖励的配置、校验、展示、失败重试都只实现一次，签到与成就只负责定义“何时触发”。

## 目标结构

新增 `reward` 包：

```text
reward/
├── RewardType.java           // CURRENCY / ITEM / TITLE / COMMAND
├── RewardDefinition.java     // 一条配置化奖励
├── RewardEvent.java          // 一次玩家奖励事件
├── RewardGrantService.java   // 统一发奖入口
├── RewardGrantResult.java    // SUCCESS / PENDING / BLOCKED / FAILED
└── RewardClaimLedger.java    // 奖励状态与失败原因的持久化账本
```

改造现有：

- [CheckinRewardConfig.java](D:/mod/qiandao/src/main/java/dev/modmind/omnitools/CheckinRewardConfig.java)
- [CheckinRewardService.java](D:/mod/qiandao/src/main/java/dev/modmind/omnitools/CheckinRewardService.java)
- [AchievementConfig.java](D:/mod/qiandao/src/main/java/dev/modmind/omnitools/AchievementConfig.java)
- [AchievementService.java](D:/mod/qiandao/src/main/java/dev/modmind/omnitools/AchievementService.java)
- [CheckinData.java](D:/mod/qiandao/src/main/java/dev/modmind/omnitools/CheckinData.java)
- [AchievementData.java](D:/mod/qiandao/src/main/java/dev/modmind/omnitools/AchievementData.java)
- [ConfigValidator.java](D:/mod/qiandao/src/main/java/dev/modmind/omnitools/config/ConfigValidator.java)

## 统一奖励配置

每条奖励必须有稳定且唯一的 `id`，不可根据数组下标识别。这样服主调整奖励顺序、重载配置或玩家断线后，系统仍能准确判断哪条奖励已经发放。

```json
{
  "id": "stone_expert_currency",
  "type": "currency",
  "amount": 500
}
```

```json
{
  "id": "stone_expert_item",
  "type": "item",
  "item": "minecraft:diamond",
  "count": 2,
  "components": {}
}
```

```json
{
  "id": "stone_expert_title",
  "type": "title",
  "title": "geologist"
}
```

```json
{
  "id": "stone_expert_announce",
  "type": "command",
  "run_as": "console",
  "command": "say {player_name} 完成了石头专家成就"
}
```

物品的 `components` 复用当前商店模块已有的物品组件解析逻辑，不新增另一套格式。

## 签到配置

将当前仅支持 `dailyCoins`、`monthlyRewards` 的格式升级为：

```json
{
  "format_version": 2,
  "daily": {
    "rewards": [
      { "id": "daily_currency", "type": "currency", "amount": 100 },
      { "id": "daily_bread", "type": "item", "item": "minecraft:bread", "count": 3 }
    ]
  },
  "monthly": {
    "5": [
      { "id": "month_5_currency", "type": "currency", "amount": 500 }
    ],
    "10": [
      { "id": "month_10_title", "type": "title", "title": "loyal_player" }
    ],
    "25": [
      {
        "id": "month_25_command",
        "type": "command",
        "run_as": "console",
        "command": "give {player_name} minecraft:emerald 8"
      }
    ]
  }
}
```

兼容旧配置：读取到 `dailyCoins` 时转换为每日 `currency` 奖励；读取到 `monthlyRewards` 时转换为对应里程碑的 `currency` 奖励。旧文件不应因升级失效。

签到本身仍只能每日一次；奖励未能发放时，签到记录保留，奖励进入“待发放”状态，而不是允许玩家重复签到。

## 成就配置

将当前：

```json
"rewards": {
  "coins": 500,
  "titles": ["geologist"]
}
```

升级为：

```json
"rewards": [
  { "id": "stone_coins", "type": "currency", "amount": 500 },
  { "id": "stone_diamond", "type": "item", "item": "minecraft:diamond", "count": 2 },
  { "id": "stone_title", "type": "title", "title": "geologist" },
  {
    "id": "stone_command",
    "type": "command",
    "run_as": "console",
    "command": "say {player_name} 完成了成就：石头专家"
  }
]
```

保持现有语义：成就达成后由玩家在成就 GUI 点击领取。只有全部奖励成功后，才将该成就标记为 `claimed`；物品奖励待发放时显示“待领取”，可再次点击重试。

## 发奖与账本规则

奖励事件 ID：

```text
checkin:<uuid>:daily:<epoch_day>
checkin:<uuid>:monthly:<year_month>:<milestone>
achievement:<uuid>:<achievement_id>
```

账本按“事件 ID + 奖励 ID”保存：

```text
PENDING    等待发放或等待重试
GRANTED    已完成，永不重复发放
BLOCKED    前置模块关闭、背包不足等可恢复问题
FAILED     配置错误等不可恢复问题
```

处理顺序：

1. 创建或读取该事件的账本。
2. 依配置顺序处理每条未完成奖励。
3. 已是 `GRANTED` 的奖励跳过。
4. 全部完成后，签到月度记录或成就 `claimed` 才最终确认。
5. 玩家登录、打开签到/成就 GUI、点击领取时自动重试待处理奖励。
6. 增加玩家命令，例如 `/omnitools rewards retry`，用于主动重试。

物品默认采用“仅放入背包”策略。背包空间不足时不得掉落在地面、不得吞掉奖励、不得标记完成，应保留为 `PENDING`。这比直接掉落更适合服务端长期运行。

## 四类奖励的约束

- `currency`：调用现有货币数据接口；金额使用 `long`，拒绝负数和溢出。
- `item`：校验物品注册表 ID、数量范围、组件格式；单条数量及单事件总数量设置上限。
- `title`：校验称号 ID 存在；若称号已拥有，视为该条奖励成功，不重复授予。
- `command`：首版只允许 `run_as: "console"`，不允许以玩家身份执行。

指令奖励必须默认关闭。在总配置增加：

```json
"global": {
  "reward_security": {
    "allow_command_rewards": false,
    "max_command_length": 1024
  }
}
```

仅在服主显式开启后接受 `command` 奖励。限制允许的占位符：

```text
{player_name} {player_uuid} {player_x} {player_y} {player_z} {player_world}
```

拒绝换行、未知占位符、空命令和超长命令。指令具有外部副作用，无法做到崩溃场景下的绝对事务回滚；应在执行前将账本记为“已派发”，采用“最多执行一次”策略，宁可极端崩溃时漏执行，也不重复执行 `give`、权限修改等危险指令。

## 模块依赖与热重载

称号奖励依赖 `titles` 模块。`ConfigValidator` 应拒绝以下候选配置：

- 签到或成就引用了不存在的称号 ID。
- 存在称号奖励，但 `titles` 模块被关闭。
- 存在指令奖励，但 `allow_command_rewards` 未开启。
- 奖励 ID 重复、类型未知、物品无效、数量非法。

模块管理 GUI 关闭称号模块时，也必须检查签到和成就是否仍引用称号奖励；存在引用则拒绝关闭并提示原因。配置热重载校验失败时继续使用旧配置快照，不能让运行中的奖励表变为空。

## 实施阶段

1. 新增统一奖励模型、账本和配置校验，先支持货币与称号，保持旧配置兼容。
2. 将每日签到、月度签到接入统一发奖服务，并实现物品背包不足后的重试。
3. 将成就领取接入统一服务，废弃旧的 `coins + titles` 专用发奖路径。
4. 加入受控指令奖励、总开关、占位符白名单和审计日志。
5. 更新签到与成就 GUI：展示四类奖励、已领取、待领取、失败原因。
6. 更新 [daily-checkin.md](D:/mod/qiandao/docs/modules/daily-checkin.md) 与 [achievements.md](D:/mod/qiandao/docs/modules/achievements.md)，提供旧格式迁移表与完整示例。

验收重点：四类奖励均可用于每日签到、月度签到和成就；背包满不丢奖；重试不重复发货币、物品或称号；称号模块关闭不静默吞奖；指令奖励默认禁用；无效热重载不影响旧配置继续运行。

## Project target

- Loader: fabric
- Minecraft: 1.21.11
- Namespace: omnitools
