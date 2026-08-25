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

---

## Development request 2026/8/25 12:42:24

可以做到“客户端无需安装 OmniTools，现有业务功能不丢失”，但不能同时保留目前专用 GUI 的像素级外观。纯服务端只能使用原版网络协议，因此界面会统一为原版箱子 GUI，按钮、分页、物品提示、左右键操作、配置重载等功能仍然保留。

当前项目非常适合改造：签到、商店、称号、成就、云储存等核心逻辑均在服务端；侧边栏已经通过原版计分板包发送；命令菜单已经是原版 `GENERIC_9x3 / GENERIC_9x6` 容器，可作为改造样板。

**一、改造目标**

最终产物满足：

- 服务端安装 OmniTools，玩家可用纯原版客户端进入。
- Fabric 客户端即使未安装 OmniTools 也能正常使用全部模块。
- 保留签到、在线奖励、商店、货币、称号、称号效果、成就、权限、云储存、命令菜单、模块管理、侧边栏、Placeholder API、统一奖励系统。
- 保留所有现有配置路径和玩家持久化数据，不重置货币、签到、称号、成就、云储存或奖励账本。
- 不要求客户端安装资源包，也不发送自定义网络包。

**二、当前阻塞点**

当前不是纯服务端的原因有三项：

| 现状 | 问题 | 服务端化方案 |
|---|---|---|
| `ModMindClient` 注册 8 个专用 Screen | 原版客户端不认识自定义菜单类型 | 统一改为原版箱子容器 |
| 多个 `ScreenHandler` 注册自定义 `MenuType` | 打开自定义菜单时原版客户端无法构造界面 | 使用 `MenuType.GENERIC_9x3` 至 `GENERIC_9x6` |
| `PlayerNameTagMixin` 位于客户端 Mixin | 头顶称号依赖客户端渲染钩子 | 改用服务端计分板队伍前后缀 |
| 大量 `Component.translatable("gui.omnitools...")` | 原版客户端没有 OmniTools 语言文件 | 服务端将自定义文本预先解析成 `Component.literal` |

[CommandMenuScreenHandler.java](D:/mod/qiandao/src/main/java/dev/modmind/omnitools/commandmenu/CommandMenuScreenHandler.java) 已经证明该路线可行：它使用原版箱子菜单类型，客户端无需注册 Screen。

**三、GUI 改造方案**

将以下界面全部改为原版容器 GUI：

| 功能 | 文件 | 容器类型 |
|---|---|---|
| 每日签到 | `CheckinScreenHandler` | `GENERIC_9x5` |
| 签到记录 | `CheckinRecordsScreenHandler` | `GENERIC_9x6` |
| 在线奖励 | `OnlineTimeRewardScreenHandler` | `GENERIC_9x3` |
| 商店 | `ShopScreenHandler` | `GENERIC_9x6` |
| 称号 | `TitleScreenHandler` | `GENERIC_9x6` |
| 云储存 | `CloudStorageScreenHandler` | `GENERIC_9x6` |
| 成就 | `AchievementScreenHandler` | `GENERIC_9x6` |
| 模块管理 | `ModuleManagerScreenHandler` | `GENERIC_9x3` |
| 命令菜单 | `CommandMenuScreenHandler` | 已完成，保持现状 |

实施方式：

1. 每个 Handler 保持继承 `ChestMenu`，不修改服务端点击、分页、权限校验和数据操作逻辑。
2. 移除各 Handler 的自定义 `TYPE` 注册，构造器改为传入对应原版 `MenuType.GENERIC_9xN`。
3. 保持 `SimpleMenuProvider` 打开逻辑，标题由服务端发送普通文本。
4. 将原专用 GUI 绘制的文字，移到按钮名称、物品 Lore 和状态图标中。
5. 保持所有 GUI 物品不可取走、不可 Shift 转移，继续由服务端校验点击者 UUID、权限、模块开关和槽位范围。

具体表现：

- 签到倒计时：放到时钟或玩家头像物品 Lore，每秒或每 20 tick 更新。
- 商店、称号、成就、记录页数：放到中间状态物品 Lore。
- 分页：保留左右箭头物品与点击逻辑。
- 成就条件、四类奖励、待领取原因：继续显示在成就物品 Lore。
- 模块管理：保留绿色/红色状态物品，左键切换、重载按钮和依赖阻止提示。
- 命令菜单：无需架构调整，仅确认所有显示文本为服务端文本。

原来的客户端界面文件最终可移除：

```text
ModMindClient.java
CheckinScreen.java
CheckinRecordsScreen.java
OnlineTimeRewardScreen.java
ShopScreen.java
TitleScreen.java
CloudStorageScreen.java
AchievementScreen.java
ModuleManagerScreen.java
```

**四、服务端文本与语言方案**

这是纯服务端化必须单独完成的一项。原版客户端没有 `assets/omnitools/lang/zh_cn.json`，因此服务端发送 `Component.translatable("gui.omnitools...")` 时，玩家会直接看到语言键。

新增：

```text
text/
├── ServerLocalization.java
└── ServerText.java
```

规则：

- 启动时从模组 Jar 内读取 `assets/omnitools/lang/zh_cn.json` 与 `en_us.json`。
- 使用主配置 `global.language` 选择服务端统一显示语言。
- 将所有 `omnitools` 自定义翻译键在服务端转为 `Component.literal(...)`。
- 配置内的菜单标题、Lore、称号显示名也按普通文本发送。
- 原版物品名称仍由玩家客户端按自身语言显示，不受影响。
- 缺失翻译时回退到 `en_us`，再回退到翻译键，且服务器日志只警告一次。

纯服务端无法可靠实现“每位玩家按自己的客户端语言显示 OmniTools 自定义文本”；这是没有客户端协作时的协议限制。建议以服主的 `global.language` 作为统一语言。

**五、称号显示方案**

聊天称号、Tab 列表称号和称号效果已经是服务端能力，可继续保留：

- 聊天：`TitleDisplayService` 已在服务端重组聊天文本。
- Tab 列表：保留 [ServerPlayerTabListMixin.java](D:/mod/qiandao/src/main/java/dev/modmind/omnitools/mixin/ServerPlayerTabListMixin.java)。
- 称号效果：继续由服务端给玩家施加效果。

头顶称号需要调整。当前 [PlayerNameTagMixin.java](D:/mod/qiandao/src/main/java/dev/modmind/omnitools/mixin/client/PlayerNameTagMixin.java) 是客户端渲染依赖，移除后不能原样保留。

推荐改为服务端原版计分板队伍：

```json
"titles": {
  "nameplate_mode": "scoreboard_team"
}
```

实现规则：

1. 玩家装备称号时，服务端创建或更新 OmniTools 管理的队伍前缀/后缀。
2. 前缀使用称号文本，玩家名称仍由原版客户端绘制。
3. 卸下称号、禁用模块、称号被收回时恢复原状态。
4. Tab 与聊天继续使用现有逻辑，避免重复格式化。
5. 清理旧版 `omnitools:title` 自定义名称标记。

需要明确的兼容性边界：原版协议中一个玩家同一时间只能属于一个计分板队伍。若服务器同时有其他模组或插件强占玩家队伍前后缀，无法保证两者都完整显示。

建议增加配置：

```json
"titles": {
  "nameplate_mode": "scoreboard_team",
  "team_conflict_policy": "omnitools_priority"
}
```

可选值：

- `omnitools_priority`：优先保留头顶称号，适合本模组主导称号显示的服务器。
- `preserve_external_team`：保留外部队伍显示，但该玩家头顶称号不显示，同时向管理员记录冲突。

在没有其他队伍系统的常规服务器中，称号功能可完整保留。

**六、保留不变的模块**

以下模块无需客户端代码，主要做原版兼容验证：

- 签到、在线时长、货币、奖励账本。
- 成就统计、条件树、四种奖励与待领取重试。
- 商店购买与物品组件。
- 权限节点、命令权限和模块热重载。
- 云储存的服务端物品持久化。
- 称号效果。
- Placeholder API 服务端注册与解析。
- 命令菜单执行、子菜单跳转、关闭菜单。
- 侧边栏。

其中 [SidebarService.java](D:/mod/qiandao/src/main/java/dev/modmind/omnitools/sidebar/SidebarService.java) 已使用原版计分板数据包向单个玩家发送侧边栏，本身就是纯服务端实现，无需改为客户端 HUD。

**七、Fabric 元数据与清理**

完成 GUI 和文本迁移后：

```json
{
  "environment": "server",
  "entrypoints": {
    "main": ["dev.modmind.omnitools.ModMindEntry"]
  }
}
```

同步调整：

- 删除 `client` entrypoint。
- 从 `omnitools.mixins.json` 删除 `client.PlayerNameTagMixin`。
- 保留服务端 Mixin：权限和 Tab 列表。
- 删除所有 `net.minecraft.client.*` 引用。
- 删除不再需要的客户端 Screen 注册与客户端 Screen 类。
- 不引入自定义 Payload，也不要求客户端安装 Placeholder API。

**八、实施阶段**

1. 建立服务端文本层，并替换所有发送给玩家的 OmniTools 翻译键。
2. 以签到、在线奖励、模块管理为第一批，改用原版 `ChestMenu` 类型。
3. 迁移商店、称号、云储存、成就、签到记录。
4. 移除 `ModMindClient` 和全部专用客户端 Screen。
5. 将头顶称号改为服务端计分板队伍适配器，处理旧名称标记清理。
6. 调整 `fabric.mod.json`、Mixin 配置和依赖。
7. 用完全未安装 OmniTools 的原版 1.21.11 客户端进行端到端验收。
8. 更新 README，明确“服务端安装、客户端无需安装”和称号队伍冲突边界。

**九、验收标准**

- 原版客户端能连接服务器，登录时无缺失模组、未知 Payload、未知菜单类型错误。
- 所有 `/omnitools`、`/checkin`、`/money`、`/title`、`/menu`、侧边栏命令可正常使用。
- 所有 GUI 均显示为原版箱子界面，点击、分页、右键、关闭、权限限制正常。
- 不出现 `gui.omnitools.*`、`message.omnitools.*` 等未翻译键。
- 签到、成就奖励、商店、云储存重启后数据完整。
- 模块 GUI 热重载后，已打开的原版容器正确刷新或关闭。
- 侧边栏在原版客户端显示、可开关、占位符更新正常。
- 聊天、Tab、头顶称号按配置显示；检测到外部队伍冲突时按策略处理。
- 使用旧版 OmniTools 客户端连接新服务端也不会因菜单类型不同而出错。

最后需划定范围：当前 OmniTools 的业务功能可以纯服务端化；未来“玩家缩小、进入一格空间”这类会改变客户端碰撞预测、视角或模型尺寸的玩法，无法在纯服务端条件下完整实现，必须保留客户端组件或改为服务端允许的近似机制。
