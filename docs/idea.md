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

---

## Development request 2026/8/25 13:53:07

我在1.21.11安装本模组的fabric服务器进行测试，我的客户端没安装，可以正常进入，但是当我打开签到菜单时，提示“网络协议错误”并退出服务器，以下是报错日志：
---- Minecraft Network Protocol Error Report ----
// 0xBADF00D

Time: 2026-08-25 13:49:04
Description: Packet handling error

java.lang.IndexOutOfBoundsException: Index 0 out of bounds for length 0
	at java.base/jdk.internal.util.Preconditions.outOfBounds(Preconditions.java:100)
	at java.base/jdk.internal.util.Preconditions.outOfBoundsCheckIndex(Preconditions.java:106)
	at java.base/jdk.internal.util.Preconditions.checkIndex(Preconditions.java:302)
	at java.base/java.util.Objects.checkIndex(Objects.java:385)
	at java.base/java.util.ArrayList.get(ArrayList.java:427)
	at knot//net.minecraft.class_1703.method_7606(class_1703.java:662)
	at knot//net.minecraft.class_634.method_11131(class_634.java:1492)
	at knot//net.minecraft.class_2651.method_11447(class_2651.java:40)
	at knot//net.minecraft.class_2651.method_65081(class_2651.java:8)
	at knot//net.minecraft.class_11980$class_11981.mixinextras$bridge$method_65081$10(class_11980.java)
	at knot//net.minecraft.class_11980$class_11981.wrapOperation$cbk000$carpet-org-addition$exceptionReason(class_11980.java:521)
	at knot//net.minecraft.class_11980$class_11981.method_74450(class_11980.java:55)
	at knot//net.minecraft.class_11980.method_74449(class_11980.java:38)
	at knot//net.minecraft.class_310.method_1523(class_310.java:1337)
	at knot//net.minecraft.class_310.method_1514(class_310.java:966)
	at knot//net.minecraft.client.main.Main.main(Main.java:250)
	at net.fabricmc.loader.impl.game.minecraft.MinecraftGameProvider.launch(MinecraftGameProvider.java:514)
	at net.fabricmc.loader.impl.launch.knot.Knot.launch(Knot.java:72)
	at net.fabricmc.loader.impl.launch.knot.KnotClient.main(KnotClient.java:23)


A detailed walkthrough of the error, its code path and all known details is as follows:
---------------------------------------------------------------------------------------

-- Head --
Thread: Render thread
Stacktrace:
	at java.base/jdk.internal.util.Preconditions.outOfBounds(Preconditions.java:100)
	at java.base/jdk.internal.util.Preconditions.outOfBoundsCheckIndex(Preconditions.java:106)
	at java.base/jdk.internal.util.Preconditions.checkIndex(Preconditions.java:302)
	at java.base/java.util.Objects.checkIndex(Objects.java:385)
	at java.base/java.util.ArrayList.get(ArrayList.java:427)
	at knot//net.minecraft.class_1703.method_7606(class_1703.java:662)
	at knot//net.minecraft.class_634.method_11131(class_634.java:1492)
	at knot//net.minecraft.class_2651.method_11447(class_2651.java:40)

-- Incoming Packet --
Details:
	Type: clientbound/minecraft:container_set_data
	Is Terminal: false
	Is Skippable: false
Stacktrace:
	at knot//net.minecraft.class_2600.method_59803(class_2600.java:41)
	at knot//net.minecraft.class_8673.method_60882(class_8673.java:146)
	at knot//net.minecraft.class_8673.method_59807(class_8673.java:125)
	at knot//net.minecraft.class_11980$class_11981.method_74450(class_11980.java:60)
	at knot//net.minecraft.class_11980.method_74449(class_11980.java:38)
	at knot//net.minecraft.class_310.method_1523(class_310.java:1337)
	at knot//net.minecraft.class_310.method_1514(class_310.java:966)
	at knot//net.minecraft.client.main.Main.main(Main.java:250)
	at net.fabricmc.loader.impl.game.minecraft.MinecraftGameProvider.launch(MinecraftGameProvider.java:514)
	at net.fabricmc.loader.impl.launch.knot.Knot.launch(Knot.java:72)
	at net.fabricmc.loader.impl.launch.knot.KnotClient.main(KnotClient.java:23)

-- Connection --
Details:
	Protocol: play
	Flow: CLIENTBOUND
	Is Local: false
	Server type: OTHER
	Server brand: fabric

-- Dynamic Lighting --
Details:
	Description: This section contains information related to dynamic lighting, this may not be related to your crash.
	Mode: fancy
	Dynamic Light Sources: 2
	Spatial Hash Occupancy: 2 / 1024

-- System Details --
Details:
	Minecraft Version: 1.21.11
	Minecraft Version ID: 1.21.11
	Operating System: Windows 11 (amd64) version 10.0
	Java Version: 21.0.3, Microsoft
	Java VM Version: OpenJDK 64-Bit Server VM (mixed mode), Microsoft
	Memory: 298428704 bytes (284 MiB) / 1442840576 bytes (1376 MiB) up to 3992977408 bytes (3808 MiB)
	CPUs: 16
	Processor Vendor: AuthenticAMD
	Processor Name: AMD Ryzen 7 7840H w/Radeon 780M Graphics
	Identifier: AuthenticAMD Family 25 Model 116 Stepping 1
	Microarchitecture: Zen 3
	Frequency (GHz): 3.79
	Number of physical packages: 1
	Number of physical CPUs: 8
	Number of logical CPUs: 16
	Graphics card #0 name: AMD Radeon 780M Graphics
	Graphics card #0 vendor: Advanced Micro Devices, Inc.
	Graphics card #0 VRAM (MiB): 512.00
	Graphics card #0 deviceId: VideoController1
	Graphics card #0 versionInfo: 31.0.14005.5002
	Graphics card #1 name: NVIDIA GeForce RTX 4060 Laptop GPU
	Graphics card #1 vendor: NVIDIA
	Graphics card #1 VRAM (MiB): 8188.00
	Graphics card #1 deviceId: VideoController2
	Graphics card #1 versionInfo: 32.0.15.7283
	Memory slot #0 capacity (MiB): 8192.00
	Memory slot #0 clockSpeed (GHz): 4.80
	Memory slot #0 type: DDR5
	Memory slot #1 capacity (MiB): 8192.00
	Memory slot #1 clockSpeed (GHz): 4.80
	Memory slot #1 type: DDR5
	Virtual memory max (MiB): 30938.21
	Virtual memory used (MiB): 28238.30
	Swap memory total (MiB): 15360.00
	Swap memory used (MiB): 5249.85
	Space in storage for jna.tmpdir (MiB): available: 12810.06, total: 51199.00
	Space in storage for org.lwjgl.system.SharedLibraryExtractPath (MiB): available: 12810.06, total: 51199.00
	Space in storage for io.netty.native.workdir (MiB): available: 12810.06, total: 51199.00
	Space in storage for java.io.tmpdir (MiB): available: 20930.28, total: 204814.00
	Space in storage for workdir (MiB): available: 12810.06, total: 51199.00
	JVM Flags: 12 total; -XX:HeapDumpPath=MojangTricksIntelDriversForPerformance_javaw.exe_minecraft.exe.heapdump -XX:-OmitStackTraceInFastThrow -Xmx3788m -XX:+UnlockExperimentalVMOptions -XX:+UseG1GC -XX:G1NewSizePercent=20 -XX:G1ReservePercent=20 -XX:G1HeapRegionSize=32M -XX:MaxGCPauseMillis=50 -XX:+PerfDisableSharedMem -XX:MinHeapFreeRatio=25 -XX:MaxHeapFreeRatio=40
	Debug Flags: 0 total; 
	Fabric Mods: 
		appleskin: AppleSkin 3.0.8+mc1.21.11
		betterf3: BetterF3 17.0.0
		betterstats: Better Statistics Screen 5.1.0+fabric-1.21.11
		c2me: Concurrent Chunk Management Engine 0.4.0-alpha.0.21+1.21.11
			c2me-base: Concurrent Chunk Management Engine (Base) 0.4.0-alpha.0.21+1.21.11
			c2me-client-uncapvd: Concurrent Chunk Management Engine (Client/Uncap View Distance) 0.4.0-alpha.0.21+1.21.11
			c2me-fixes-chunkio-threading-issues: Concurrent Chunk Management Engine (Fixes/Chunk IO/Threading Issues) 0.4.0-alpha.0.21+1.21.11
			c2me-fixes-general-threading-issues: Concurrent Chunk Management Engine (Fixes/General/Threading Issues) 0.4.0-alpha.0.21+1.21.11
			c2me-fixes-worldgen-threading-issues: Concurrent Chunk Management Engine (Fixes/WorldGen/Threading Issues) 0.4.0-alpha.0.21+1.21.11
			c2me-fixes-worldgen-vanilla-bugs: Concurrent Chunk Management Engine (Fixes/WorldGen/Vanilla Bugs) 0.4.0-alpha.0.21+1.21.11
			c2me-notickvd: Concurrent Chunk Management Engine (No Tick View Distance) 0.4.0-alpha.0.21+1.21.11
			c2me-opts-allocs: Concurrent Chunk Management Engine (Optimizations/Memory Allocations) 0.4.0-alpha.0.21+1.21.11
			c2me-opts-chunkio: Concurrent Chunk Management Engine (Optimizations/Chunk IO) 0.4.0-alpha.0.21+1.21.11
			c2me-opts-math: Concurrent Chunk Management Engine (Optimizations/Math) 0.4.0-alpha.0.21+1.21.11
			c2me-opts-scheduling: Concurrent Chunk Management Engine (Optimizations/Scheduling) 0.4.0-alpha.0.21+1.21.11
			c2me-opts-worldgen-general: Concurrent Chunk Management Engine (Optimizations/General WorldGen) 0.4.0-alpha.0.21+1.21.11
			c2me-opts-worldgen-vanilla: Concurrent Chunk Management Engine (Optimizations/Vanilla WorldGen) 0.4.0-alpha.0.21+1.21.11
			c2me-rewrites-chunk-serializer: Concurrent Chunk Management Engine (Rewrites/Chunk Serializer) 0.4.0-alpha.0.21+1.21.11
			c2me-rewrites-chunk-system: Concurrent Chunk Management Engine (Rewrites/Chunk System) 0.4.0-alpha.0.21+1.21.11
			c2me-rewrites-chunkio: Concurrent Chunk Management Engine (Rewrites/Chunk IO) 0.4.0-alpha.0.21+1.21.11
			c2me-server-utils: Concurrent Chunk Management Engine (Server Utils) 0.4.0-alpha.0.21+1.21.11
			c2me-threading-lighting: Concurrent Chunk Management Engine (Threading/Lighting) 0.4.0-alpha.0.21+1.21.11
			com_github_ben-manes_caffeine_caffeine: caffeine 3.2.1
			com_ibm_async_asyncutil: asyncutil 0.1.0
			com_ishland_c2me_tests_tests: tests 0.4.0-alpha.0.21
			io_reactivex_rxjava3_rxjava: rxjava 3.1.12
			mixinsquared: MixinSquared 0.3.7-beta.1
			net_objecthunter_exp4j: exp4j 0.4.8
			org_jctools_jctools-core: jctools-core 4.0.5
			org_reactivestreams_reactive-streams: reactive-streams 1.0.4
		carpet: Carpet Mod 1.4.194+v251223
		carpet-ams-addition: Carpet AMS Addition 26.1.2
			annotationtoolbox: AnnotationToolBox 0.3
			top_1024byteeeee_tiny_yaml: tiny_yaml 1.0.3
		carpet-extra: Carpet Extra 1.4.177
		carpet-fga-addition: Carpet FGA Addition 1.4.6+v2608120931-mc1.21.11
		carpet-org-addition: Carpet Org Addition 1.41.4
		carpet-tis-addition: Carpet TIS Addition 1.77.0
		cca: Crystal Carpet Addition 1.12.5+mc1.21.11
		chat_heads: Chat Heads 1.2.4
		cloth-config: Cloth Config v20 21.11.153
			cloth-basic-math: cloth-basic-math 0.6.1
		connectedglass: Connected Glass 1.1.14
		crashexploitfixer: CrashExploitFixer 2.0.0
		creativecore: CreativeCore 2.14.11
			net_neoforged_bus: bus 7.2.0
		customskinloader-bootstrap: CustomSkinLoader Bootstrap 15.0.1
		diggusmaximus: Diggus Maximus Reborn 1.7.9
		entityculling: EntityCulling 1.10.5
		fabric-api: Fabric API 0.141.5+1.21.11
			fabric-api-base: Fabric API Base 1.0.5+4ebb5c083e
			fabric-api-lookup-api-v1: Fabric API Lookup API (v1) 1.6.114+20dc27073e
			fabric-biome-api-v1: Fabric Biome API (v1) 17.1.1+4fc5413f3e
			fabric-block-api-v1: Fabric Block API (v1) 1.1.10+4ebb5c083e
			fabric-block-view-api-v2: Fabric BlockView API (v2) 1.0.39+4ebb5c083e
			fabric-command-api-v2: Fabric Command API (v2) 2.4.7+6b42a6003e
			fabric-content-registries-v0: Fabric Content Registries (v0) 10.2.14+4fc5413f3e
			fabric-convention-tags-v1: Fabric Convention Tags 2.1.55+7f945d5b3e
			fabric-convention-tags-v2: Fabric Convention Tags (v2) 2.17.3+8ef948ba3e
			fabric-crash-report-info-v1: Fabric Crash Report Info (v1) 0.3.23+4ebb5c083e
			fabric-data-attachment-api-v1: Fabric Data Attachment API (v1) 1.8.48+eed0806f3e
			fabric-data-generation-api-v1: Fabric Data Generation API (v1) 23.5.0+88d7da613e
			fabric-dimensions-v1: Fabric Dimensions API (v1) 4.0.28+4fc5413f3e
			fabric-entity-events-v1: Fabric Entity Events (v1) 3.1.1+1d0ab4303e
			fabric-events-interaction-v0: Fabric Events Interaction (v0) 4.1.1+3b89ecf63e
			fabric-game-rule-api-v1: Fabric Game Rule API (v1) 2.0.3+4fc5413f3e
			fabric-item-api-v1: Fabric Item API (v1) 11.5.20+d0c46b9e3e
			fabric-item-group-api-v1: Fabric Item Group API (v1) 4.2.36+4fc5413f3e
			fabric-key-binding-api-v1: Fabric Key Binding API (v1) 1.1.7+4fc5413f3e
			fabric-lifecycle-events-v1: Fabric Lifecycle Events (v1) 2.6.15+4ebb5c083e
			fabric-loot-api-v2: Fabric Loot API (v2) 3.0.73+3f89f5a53e
			fabric-loot-api-v3: Fabric Loot API (v3) 2.0.20+78c8b4663e
			fabric-message-api-v1: Fabric Message API (v1) 6.1.12+4ebb5c083e
			fabric-model-loading-api-v1: Fabric Model Loading API (v1) 6.0.15+4fc5413f3e
			fabric-networking-api-v1: Fabric Networking API (v1) 5.1.6+6b6d71a53e
			fabric-object-builder-api-v1: Fabric Object Builder API (v1) 21.1.40+4fc5413f3e
			fabric-particles-v1: Fabric Particles (v1) 4.2.12+4fc5413f3e
			fabric-recipe-api-v1: Fabric Recipe API (v1) 8.2.4+4ebb5c083e
			fabric-registry-sync-v0: Fabric Registry Sync (v0) 6.2.6+1718722b3e
			fabric-renderer-api-v1: Fabric Renderer API (v1) 8.0.3+f4ffd2e53e
			fabric-renderer-indigo: Fabric Renderer - Indigo 5.0.3+f4ffd2e53e
			fabric-rendering-fluids-v1: Fabric Rendering Fluids (v1) 3.1.43+4ebb5c083e
			fabric-rendering-v1: Fabric Rendering (v1) 16.2.10+0290ad933e
			fabric-resource-conditions-api-v1: Fabric Resource Conditions API (v1) 5.0.35+4fc5413f3e
			fabric-resource-loader-v0: Fabric Resource Loader (v0) 3.3.4+4fc5413f3e
			fabric-resource-loader-v1: Fabric Resource Loader (v1) 1.0.10+78c8b4663e
			fabric-screen-api-v1: Fabric Screen API (v1) 3.1.7+4ebb5c083e
			fabric-screen-handler-api-v1: Fabric Screen Handler API (v1) 1.3.162+4fc5413f3e
			fabric-serialization-api-v1: Fabric Serialization API (v1) 1.0.5+4ebb5c083e
			fabric-sound-api-v1: Fabric Sound API (v1) 1.0.51+4fc5413f3e
			fabric-tag-api-v1: Fabric Tag API (v1) 1.3.0+88d7da613e
			fabric-transfer-api-v1: Fabric Transfer API (v1) 6.0.25+4fc5413f3e
			fabric-transitive-access-wideners-v1: Fabric Transitive Access Wideners (v1) 7.1.0+014c8cec3e
		fabric-language-kotlin: Fabric Language Kotlin 1.13.13+kotlin.2.4.10
			org_jetbrains_kotlin_kotlin-reflect: kotlin-reflect 2.4.10
			org_jetbrains_kotlin_kotlin-stdlib: kotlin-stdlib 2.4.10
			org_jetbrains_kotlin_kotlin-stdlib-jdk7: kotlin-stdlib-jdk7 2.4.10
			org_jetbrains_kotlin_kotlin-stdlib-jdk8: kotlin-stdlib-jdk8 2.4.10
			org_jetbrains_kotlinx_atomicfu-jvm: atomicfu-jvm 0.33.0
			org_jetbrains_kotlinx_kotlinx-coroutines-core-jvm: kotlinx-coroutines-core-jvm 1.11.0
			org_jetbrains_kotlinx_kotlinx-coroutines-jdk8: kotlinx-coroutines-jdk8 1.11.0
			org_jetbrains_kotlinx_kotlinx-datetime-jvm: kotlinx-datetime-jvm 0.8.0
			org_jetbrains_kotlinx_kotlinx-io-bytestring-jvm: kotlinx-io-bytestring-jvm 0.9.1
			org_jetbrains_kotlinx_kotlinx-io-core-jvm: kotlinx-io-core-jvm 0.9.1
			org_jetbrains_kotlinx_kotlinx-serialization-cbor-jvm: kotlinx-serialization-cbor-jvm 1.11.0
			org_jetbrains_kotlinx_kotlinx-serialization-core-jvm: kotlinx-serialization-core-jvm 1.11.0
			org_jetbrains_kotlinx_kotlinx-serialization-json-jvm: kotlinx-serialization-json-jvm 1.11.0
		fabricloader: Fabric Loader 0.19.3
			mixinextras: MixinExtras 0.5.4
		fabrishot: Fabrishot 1.16.4
		fallingleaves: Falling Leaves 2.0.3
		fastquit: FastQuit 3.1.3+mc1.21.11
		forgeconfigapiport: Forge Config API Port 21.11.1
			com_electronwill_night-config_core: core 3.8.3
			com_electronwill_night-config_toml: toml 3.8.3
		fusion: Fusion 1.3.5
		fzzy_config: Fzzy Config 0.7.6+1.21.11
			blue_endless_jankson: jankson 1.2.3
			fabric-permissions-api-v0: fabric-permissions-api 0.6.1
			net_peanuuutz_tomlkt_tomlkt-jvm: tomlkt-jvm 0.3.7
		gammautils: Gamma Utils 2.5.10
		imblocker: IMBlocker 6.1.4
		immersive_paintings: Immersive Paintings 0.7.8
			com_twelvemonkeys_common_common-image: common-image 3.13.0
			com_twelvemonkeys_common_common-lang: common-lang 3.13.0
			com_twelvemonkeys_imageio_imageio-core: imageio-core 3.13.0
			com_twelvemonkeys_imageio_imageio-webp: imageio-webp 3.13.0
		inventoryprofilesnext: Inventory Profiles Next 2.2.6
		iris: Iris 1.10.8-snapshot+mc1.21.11-local
			io_github_douira_glsl-transformer: glsl-transformer 3.0.0-pre3
			org_anarres_jcpp: jcpp 1.4.14
			org_antlr_antlr4-runtime: antlr4-runtime 4.13.1
		itemscroller: Item Scroller 0.30.6
		jade: Jade 21.1.6+fabric
		java: OpenJDK 64-Bit Server VM 21
		jecharacters: Just Enough Characters 4.6.4
			com_github_towdium_pinin: PinIn 1.6.0
		jei: Just Enough Items 27.17.0.50
		lambdynlights: LambDynamicLights 4.9.1+1.21.11
			lambdynlights_runtime: LambDynamicLights (Runtime) 4.9.1+1.21.11
				lambdynlights_api: LambDynamicLights (API) 4.9.1+1.21.11
					yumi_commons_collections: Yumi Commons: Collections 1.0.0
					yumi_commons_core: Yumi Commons: Core 1.0.0
					yumi_commons_event: Yumi Commons: Event 1.0.0
				pride: Pride Lib 1.5.1+1.21.11
				spruceui: SpruceUI 9.1.0+1.21.11
				yumi_mc_core: Yumi Minecraft Libraries: Foundation 1.0.0-beta.1+1.21.11
		liangzi: liangzi 0.1.0
		libipn: libIPN 6.6.3
		litematica: Litematica 0.26.12
		litematica-printer-wrapper: Litematica Printer 1.2.4-bunnyi116+260510+build.38
			litematica-printer: Litematica Printer 1.2.4-bunnyi116+260510+build.38
				com_belerweb_pinyin4j: pinyin4j 2.5.1
		lithium: Lithium 0.21.4+mc1.21.11
		malilib: MaLiLib 0.27.16
			conditional-mixin: conditional mixin 0.6.4
		melody: Melody 1.0.15
		minecraft: Minecraft 1.21.11
		minihud: MiniHUD 0.38.13
		modmenu: Mod Menu 17.0.1-beta.1
		mousetweaks: Mouse Tweaks 2.30
		nochatreports: No Chat Reports 1.21.11-v2.18.0
		notenoughanimations: NotEnoughAnimations 1.12.4
		placeholder-api: Placeholder API 2.8.2+1.21.10
		presencefootsteps: Presence Footsteps 1.12.4+1.21.11
			kirin: Kirin UI 1.21.4+1.21.11
		quickcraft: QuickCraft 1.0.1
			mm: Manningham Mills 2.3
		quickshulker: Quick Shulker Multi 3.2.7-mc1.21.11-200-43d8da4-release
		rrls: Remove Reloading Screen 5.1.15+mc.1.21.11
		showdurability: Show Durability 1.1.2+1.21.11
		shulkerboxtooltip: Shulker Box Tooltip 5.2.16+1.21.11
		skinlayers3d: 3d-Skin-Layers 1.11.2
			transition: TRansition 1.0.21
			trender: TRender 1.0.15
		sodium: Sodium 0.8.14-beta.2+mc1.21.11
		sodium-extra: Sodium Extra 0.9.3+mc1.21.11
		sound_physics_remastered: Sound Physics Remastered 1.21.11-1.5.1
		supermartijn642corelib: SuperMartijn642's Core Lib 1.1.21
		syncmatica: Syncmatica 0.3.19
		syncmatica_r: Syncmatica Revolution 0.4.1
		tcdcommons: TCDCommons API 5.1.0+fabric-1.21.11
		tweakermore: TweakerMore 3.31.0
		tweakeroo: Tweakeroo 0.27.11
		uml: Universal Mod Localizer 1.2.1
		voicechat: Simple Voice Chat 1.21.11-2.6.20
			voicechat_api: Simple Voice Chat API 2.6.20
		worldedit: WorldEdit 7.4.2+7450-eb8e82c
			worldeditcui_protocol: WorldEditCUI Protocol (Fabric) 4.0.2
		xaerominimap: Xaero's Minimap 26.3.0
		xaeroplus: XaeroPlus 2.34.4
		xaeroworldmap: Xaero's World Map 1.43.0
			xaerolib: XaeroLib 1.6.1
		yet_another_config_lib_v3: YetAnotherConfigLib 3.8.2+1.21.11-fabric
			com_twelvemonkeys_common_common-io: common-io 3.12.0
			com_twelvemonkeys_imageio_imageio-metadata: imageio-metadata 3.12.0
			org_quiltmc_parsers_gson: gson 0.2.1
			org_quiltmc_parsers_json: json 0.2.1
		zoomify: Zoomify 2.15.2+1.21.11
			com_akuleshov7_ktoml-core-jvm: ktoml-core-jvm 0.7.1
	Loaded Shaderpack: [光影]ComplementaryShaders_v4.7.2.zip
		Profile: HIGH (+0 options changed by user)
	Yumi MC Core: 1.0.0-beta.1+1.21.11

---

## Development request 2026/8/25 15:11:57

当前签到菜单的问题来自 [CheckinScreenHandler.java](D:/mod/qiandao/src/main/java/dev/modmind/omnitools/CheckinScreenHandler.java) 的布局：日期按每行 9 天排列，和自然月历不一致；所有奖励塞进“今天”的 Lore；记录、玩家信息、成就入口分散在最后一行。

交给工作台的改造方案如下。

```text
任务：重做每日签到原版箱子 GUI 的信息布局与可读性

目标：
保持纯服务端、原版客户端可用，不减少签到、奖励、记录、成就入口等功能；
将当前 5 行线性日期表改为“左侧月历 + 右侧信息栏 + 独立奖励详情页”。

一、主界面改为 6 行箱子

CheckinScreenHandler：
- ROWS 从 5 改为 6。
- CONTAINER_SIZE 改为 54。
- MenuType 改为 GENERIC_9x6。
- 不再使用 “day - 1” 作为固定槽位。
- 以周一为一周第一天，使用前 7 列绘制真实月历。
- 右侧第 8、9 列固定作为信息与导航栏。

布局：

┌─────────────────────────────┬─────────┬─────────┐
│ 周一 周二 周三 周四 周五 周六 周日 │ 玩家信息 │ 今日签到 │
│          左侧真实月历           │ 奖励详情 │ 月度进度 │
│      7 列 × 最多 6 周日期        │ 签到记录 │ 成就入口 │
│                                 │ 连续签到 │ 货币余额 │
│                                 │ 装饰占位 │ 装饰占位 │
│                                 │ 装饰占位 │ 装饰占位 │
└─────────────────────────────┴─────────┴─────────┘

槽位规则：
- 月历：每行 0-6 列，即 0-6、9-15、18-24、27-33、36-42、45-51。
- 信息栏：7、8、16、17、25、26、34、35。
- 其余右侧槽位仅使用深色玻璃板，形成稳定的视觉边界。
```

二、月历映射与状态视觉

新增明确的日期映射：

```text
monthStartOffset = 当月 1 日是周几（周一 = 0）
slot(day) = ((monthStartOffset + day - 1) / 7) * 9
          + ((monthStartOffset + day - 1) % 7)
```

点击时使用 `slot -> day` 映射反查日期，禁止再假设“槽位等于日期减一”。

日期状态使用图标和颜色表达，不依赖玩家逐个阅读 Lore：

| 状态 | 图标 | 颜色 | 名称 |
|---|---|---|---|
| 今天，未签到 | `CLOCK` | 金色 | `今天 - 点击签到` |
| 今天，已签到 | `LIME_STAINED_GLASS_PANE` + 光效 | 绿色 | `今天 - 已签到` |
| 过去，已签到 | `LIME_STAINED_GLASS_PANE` | 绿色 | `N 日 - 已签到` |
| 过去，未签到 | `RED_STAINED_GLASS_PANE` | 红色 | `N 日 - 未签到` |
| 未来日期 | `GRAY_STAINED_GLASS_PANE` | 深灰 | `N 日 - 未开始` |
| 月历留空位置 | `BLACK_STAINED_GLASS_PANE` | 无名称 | 无 Lore |

日期格 Lore 最多两行：

```text
8 月 25 日，周二
今天可签到
```

只有今天日期格可执行签到；已签到、过去、未来及留空格均不可操作。`hasSignedToday()` 改为直接读取 `CheckinData`，不要再依据 UI 物品判断状态。

三、右侧信息栏

右侧内容必须拆分信息，避免一个玩家头像塞入六七行数据。

| 槽位 | 图标 | 内容与行为 |
|---|---|---|
| `7` | 玩家头颅 | 玩家名、今天签到状态、累计签到天数 |
| `8` | 时钟/绿宝石 | 今日签到主按钮；未签到可点击，已签到仅展示状态 |
| `16` | 箱子 | `奖励详情`，点击打开独立奖励页 |
| `17` | 地图或指南针 | 本月进度、下一个月度里程碑、还差天数 |
| `25` | 时钟 | `签到记录`，保留现有跳转 |
| `26` | 下界之星 | `我的成就`，保留现有跳转与权限检查 |
| `34` | 营火或烈焰粉 | 连续签到天数 |
| `35` | 金锭 | 当前货币余额 |

玩家头颅只保留核心摘要：

```text
今日：已签到 / 可签到
累计：36 天
```

不要重复展示连续签到、本月进度、余额，它们已有独立信息格。

四、独立奖励详情页

新增 `CheckinRewardInfoScreenHandler`，使用原版 `GENERIC_9x6`，由主界面槽位 `16` 打开。

原因：
当前 `appendRewardLore(...)` 会把每日奖励和已达到的全部月度奖励堆到今天日期格。四种奖励启用后，Lore 会非常长，月度里程碑越多越难阅读。

奖励页规则：

- 每日奖励显示为一个独立物品。
- 每个“月度里程碑”显示为一个独立物品。
- 里程碑物品显示状态：未达成、已可领取、已领取、待处理。
- 单个奖励项用对应图标显示：
  - 货币：金粒或金锭。
  - 物品：奖励物品本身。
  - 称号：命名牌。
  - 指令：命令方块。
- 奖励数量过多时分页，不能截断或丢失。
- `返回签到` 固定在左下角，点击回到主界面。
- 奖励页为只读；实际签到仍只能通过主页面的“今天”格或右侧“今日签到”格触发。

主界面的“奖励详情”仅显示简短摘要：

```text
每日奖励：2 项
本月进度：8 天
下一里程碑：10 天
点击查看全部奖励
```

五、交互与数据一致性

- 今天月历格和右侧“今日签到”格共用同一个服务端签到方法。
- 无论连续点击、双击、Shift 点击还是客户端伪造槽位，仍由 `CheckinData.signIn(...)` 保证一天只签到一次。
- 月度奖励继续经 `RewardClaimLedger` 处理；奖励待处理时，在主界面今日按钮和奖励详情页显示黄色提示。
- 打开主菜单、签到成功、奖励重试、跨日、配置热重载时刷新界面。
- 日期跨月时重新计算日历映射；旧的点击槽位必须失效并提示菜单已刷新。
- 模块禁用、权限失效时关闭已打开的签到主界面和奖励详情界面。

六、文本规范

新增或调整语言键，保持短句：

```text
今日可签到
今天已签到
未签到
未开始
奖励详情
本月签到：%d 天
下一里程碑：%d 天
还差：%d 天
连续签到：%d 天
累计签到：%d 天
查看签到记录
查看我的成就
返回签到
```

避免在主界面使用以下冗长文本：

```text
每日签到奖励为……
月度里程碑（N 天）……
点击查看今天的签到名次和时间……
```

这些详细说明只放入奖励详情页或签到记录页。

七、验收标准

- 任何月份的 1 至 31 日都按真实星期位置显示，前后空位正确。
- 玩家能一眼区分已签到、漏签、今天、未来日期。
- 主界面不再在单个日期格堆叠全部奖励说明。
- 每日奖励、所有月度里程碑、待处理奖励均可从奖励详情页查看。
- 签到、签到记录、成就跳转、玩家信息、货币、连续签到、本月进度全部保留。
- 原版客户端无需安装 OmniTools，界面仍是标准 6 行箱子。
- 右侧固定栏在所有月份中保持相同位置，不因月份天数变化而移动。
- 玩家不能取走任何 UI 物品，玩家背包操作保持原有行为。
```

这个方案会让主界面只承担“今天能不能签到、这个月签了多少、去哪看详情”三件事，奖励细节放到单独页面，能明显改善原版箱子 GUI 的扫读体验。

---

## Development request 2026/8/25 17:33:01

下面方案可直接转交工作台。目标是在不删减现有十个模块和纯服务端兼容性的前提下，优先修复正确性风险，再优化性能、配置安全和可维护性。

**阶段 A：数据正确性与安全，优先完成**

1. 修复 Placeholder API 注册错误。  
   当前注册循环遍历占位符 ID，但回调实际把 `argument` 当作 ID 传入解析器，外部调用 `%omnitools:balance%` 等可能无法正确解析。修改为闭包捕获循环变量 `id`。  
   位置：[FabricPlaceholderRegistrar.java](D:/mod/qiandao/src/main/java/dev/modmind/omnitools/FabricPlaceholderRegistrar.java:18)

2. 重构奖励发放为可恢复状态机。  
   当前货币、物品和指令奖励会先标记账本 `GRANTED`，再改货币、背包或执行指令；服务器在中间崩溃时可能漏发。  
   建议状态：`PENDING -> APPLYING -> GRANTED / BLOCKED / FAILED`，启动与玩家登录时扫描 `APPLYING` 项并对账恢复。

3. 明确奖励一致性边界。  
   - 货币、称号：记录奖励事件 ID 到同一份 OmniTools 持久化数据，做到可验证的“至多一次”。  
   - 物品：改为“奖励收件箱/待领取队列”，背包满时保留待领；领取成功后再完成事件。  
   - 指令：只能承诺“最多派发一次”，不能承诺严格一次。记录派发时间、事件 ID、解析后的命令和异常；提供管理员确认/补偿命令，禁止自动重放。  
   位置：[RewardGrantService.java](D:/mod/qiandao/src/main/java/dev/modmind/omnitools/reward/RewardGrantService.java:15)

4. 补充管理员奖励排障指令：  
   `omnitools reward inspect <player> [event]`、`retry <player> <event>`、`resolve <player> <event> grant|fail`。全部走现有权限模块，输出审计日志。

验收：17 个内置占位符可被第三方 Placeholder API 正确读取；模拟背包满、重连和异常后，奖励不会静默丢失，管理员可定位每一条异常奖励。

**阶段 B：性能与数据生命周期**

1. 成就检查改为有预算的分批调度。  
   目前每 10 tick 遍历所有在线玩家和全部成就；玩家或统计条件增多会造成卡顿。改为每 tick 处理固定数量的“玩家-成就”任务，并配置：
   `check_interval_ticks`、`max_players_per_tick`、`max_conditions_per_tick`、`full_recheck_seconds`。打开成就菜单和领取奖励时仍对当前玩家实时校验，保证不漏解锁。  
   位置：[AchievementService.java](D:/mod/qiandao/src/main/java/dev/modmind/omnitools/AchievementService.java:78)

2. 缓存一次检查周期内的统计值。  
   同一玩家的同一统计项只读取一次，逻辑组合条件共享 `StatisticEvaluationContext`，避免 `ALL/ANY/SUM/NOT` 重复计算。

3. 为签到与奖励账本增加生命周期策略。  
   [CheckinData.java](D:/mod/qiandao/src/main/java/dev/modmind/omnitools/CheckinData.java:54) 会永久保存每日签到、排名和时间；月度统计还会遍历全部历史日期。  
   新增 `data_retention` 配置：`full`、`monthly_summary`、`archive`。默认 `full` 保持旧行为；启用精简前必须导出并生成归档。当前月保留明细，历史月聚合为总签到数、连签峰值、奖励状态。

4. 账本清理必须和来源状态联动。  
   仅清理“来源已永久完成且可由签到/成就数据证明”的终态记录，不能按时间盲删，否则可能再次发奖。

验收：高玩家数、高成就数时单 tick 检查量受配置上限约束；长期运行后的数据体积可预测，历史统计仍可展示。

**阶段 C：配置、模块运行时与集成**

1. 完整实现根配置版本迁移。  
   当前运行中的 `config.json` 缺少 `language`、`reward_security`、`command_menu`、`sidebar` 等字段，而缺失模块会默认启用。  
   位置：[OmniToolsRootConfig.java](D:/mod/qiandao/src/main/java/dev/modmind/omnitools/config/OmniToolsRootConfig.java:94)  
   迁移应：备份旧文件、补齐字段、写入升级日志、对“旧版本不存在的新模块”默认禁用，避免升级后静默开启新功能。

2. 建立模块生命周期接口。  
   每个模块实现 `validate`、`reload`、`enable`、`disable`、`shutdown`，并声明依赖关系。例如称号效果依赖称号模块；禁用模块时关闭相关 GUI、停止任务、保留数据且不再处理新事件。

3. 落实侧边栏冲突策略。  
   当前配置存在 `conflict_policy`，但侧边栏服务没有真正按策略处理。实现：
   - `skip`：已有第三方侧边栏时不覆盖，建议默认；
   - `replace`：明确替换；
   - `restore`：关闭 OmniTools 侧边栏时恢复之前的显示目标。  
   位置：[SidebarService.java](D:/mod/qiandao/src/main/java/dev/modmind/omnitools/sidebar/SidebarService.java:215)

4. 统一动态文本渲染器。  
   将内置占位符、Fabric Placeholder API、语言文本和格式解析集中到 `TextTemplateRenderer`。应用到侧边栏、命令菜单标题与 Lore、签到提示、成就名称/描述、称号展示文本。每玩家每 tick 缓存一次，未知占位符记录一次警告并安全回退。

5. 收紧可配置命令执行。  
   命令菜单与奖励指令增加命令根白名单、最大长度、冷却、执行身份（控制台/玩家）和审计日志；占位符只能替换为文本，不能拼接绕过命令限制。

验收：旧配置升级后没有新模块意外启用；热重载失败保留旧快照；侧边栏不再抢占其他模组的显示栏。

**阶段 D：结构、测试与文档**

1. 拆分入口类。  
   将 `ModMindEntry` 收敛为启动与注册入口，配置、模块生命周期、命令注册、定时任务和集成注册分别由专用服务负责。

2. 建立自动化测试，当前项目未发现 `src/test`。  
   最低覆盖：配置迁移与校验、17 个占位符、奖励状态转换、成就逻辑树、模块启停、侧边栏冲突策略、命令菜单配置校验。

3. 增加原版客户端连接的服务端烟雾测试。  
   验证启动、配置迁移、模块重载、原版 Chest GUI、侧边栏、可选 Placeholder API 缺失与存在两种环境。

4. 重整文档。  
   `docs/modules/` 按模块说明“功能、依赖、指令、完整配置、占位符、迁移、常见故障”；另增统一升级指南、数据备份与恢复指南、奖励一致性说明。

建议严格按 A → B → C → D 实施，每阶段独立构建、启动验证并保留可回滚配置备份。本轮仅做了只读核查，未执行构建或测试。

---

## Development request 2026/8/25 19:11:48

目标

保持原版客户端可直接连接，不引入客户端代码或自定义网络包。
不重置任何 SavedData、货币、签到、成就、称号与奖励账本。
配置热重载失败时继续使用旧快照。
新安装安全默认，旧服升级不破坏既有命令菜单。
阶段 1：命令安全与诊断，工作量 S

现有 CommandSecurityConfig.defaults() 已是“默认拒绝”，但已迁移的运行配置为 allowed_roots: ["*"]、冷却为 0。保留旧服兼容行为，但增加启动与重载警告：

检测到 * 时提示“宽松模式”，列出受影响的命令菜单和指令奖励数量。
新配置示例改为显式白名单，例如 spawn、home、warp，并建议 cooldown_ticks: 10。
新增 /omnitools diagnose，输出配置版本、模块状态、Placeholder API 可用性、宽松命令白名单、未处理奖励数量、侧边栏冲突状态。
将命令菜单中硬编码英文提示统一改为 ServerText，与全局语言设置一致。
涉及：CommandSecurityConfig.java、ConfigMigration.java。

验收：新安装配置无法执行任意控制台命令；旧服保持可用但会显示风险提示；diagnose 不修改任何数据。

---

## Development request 2026/8/25 19:46:03

阶段 2：奖励处理体验，工作量 M

奖励账本已具备 PENDING -> APPLYING -> GRANTED/BLOCKED/FAILED，并已有 /omnitools rewards retry|inspect|resolve。下一步不应重造命令，而是补齐 GUI 和玩家入口：

增加 /omnitools rewards open 玩家奖励箱，仅展示自己待领取/待重试的物品奖励。
背包空间不足时，点击物品再次尝试投递；成功后更新同一条账本记录。
增加管理员原版箱子 GUI：按状态筛选奖励、显示事件 ID、玩家、奖励类型、阻塞原因与解析后的指令。
管理员操作只允许“标记已处理”或“标记失败”；对 item_delivery_outcome_unknown、command_dispatch_outcome_unknown 仍禁止自动重放。
对账日志记录操作者、旧状态、新状态和时间。
复用：RewardClaimLedger.java、RewardGrantService.java、ModMindEntry.java。

验收：背包满时奖励不丢失；崩溃边界的物品和指令奖励不会被静默自动重放；服主可完整定位并结案异常。

---

## Development request 2026/8/25 20:13:11

阶段 3：统一文本与占位符，工作量 M

TextTemplateRenderer 已用于侧边栏和命令菜单消息，应扩展到所有服主可配置的玩家可见文本：

签到 GUI、奖励详情、在线奖励、商店名称/Lore、成就名称/描述、称号说明、命令菜单标题和物品 Lore。
统一顺序：内置 OmniTools 占位符 → 可选 Fabric Placeholder API → 颜色格式解析。
保留每玩家每 tick 缓存；未知占位符显示安全回退值并仅记录一次警告。
禁止将第三方文本占位符用于“控制台命令内容”，命令仍只接受受控的 {player_name} 等变量。
验收：不开 Placeholder API 时所有模块正常显示；安装 API 后，配置文本可使用玩家、世界和服务器占位符；不改变奖励或命令执行语义。

---

## Development request 2026/8/25 20:31:46

阶段 4：
在线奖励接入统一奖励，工作量 M

在线奖励目前应升级为与签到、成就相同的 RewardDefinition 列表：

每个时长里程碑支持货币、物品、称号、指令。
事件 ID 使用 online:<uuid>:<epoch_day>:<milestone>，继续交给现有 RewardGrantService 与账本。
旧货币配置自动迁移为单条 currency 奖励，避免升级后重复领取。
GUI 显示奖励预览、已领取、待领取、模块依赖阻塞原因。
称号和指令奖励继续受现有标题模块校验、命令总开关和命令白名单约束。

---

## Development request 2026/8/25 20:51:24

阶段 5：发布验证与文档，工作量 M

扩展现有测试：配置 v1/v2/v3 迁移、命令白名单、奖励状态转换、占位符回退、成就调度预算、模块热重载。
加入隔离服务器烟雾测试：启动、原版客户端连接、打开所有原版 Chest GUI、关闭/重开模块、无 Placeholder API 环境。
更新 README、升级指南、奖励一致性文档；为成就提供“采集、战斗、探索、距离、容器交互”预设 JSON。

---

## Development request 2026/8/25 21:20:32

**任务：对五阶段成果进行完整自检**

请不要先修改代码，先生成一份 `PASS / WARN / FAIL` 报告。每个结论必须附带检查命令、文件路径或日志证据。

**一、静态结构检查**

1. 检查 `fabric.mod.json`：
   - 环境为 `server`。
   - 仅注册服务端入口。
   - 不存在客户端入口、自定义网络载荷、自定义 `MenuType`。
2. 检查十个模块是否都有：
   - 根配置开关。
   - 独立配置文件。
   - 权限节点。
   - 禁用后的运行时处理。
3. 检查可选 Placeholder API：
   - 依赖为编译期或可选依赖。
   - 未安装时服务器仍能启动。
   - 已安装时内置占位符能注册。
4. 检查是否残留旧 `qiandao` 命名、旧配置路径或客户端类。

**二、配置与热重载检查**

验证：

- `config/omnitools/config.json` 当前格式版本为 3。
- v1/v2 配置能迁移到 v3，并生成备份。
- 新模块不会在旧配置升级后静默启用。
- 配置错误时保留旧快照。
- 模块禁用后关闭 GUI、停止任务、清理侧边栏和称号效果。
- 模块重新启用后能恢复运行。
- `/omnitools reload` 与模块管理 GUI 状态一致。

重点文件：

- `OmniToolsRootConfig.java`
- `ConfigMigration.java`
- `OmniToolsConfigManager.java`
- `ModuleControlService.java`

**三、奖励系统检查**

分别验证签到、成就、在线奖励是否都支持：

- 货币
- 物品
- 称号
- 指令

检查账本状态流转：

```text
PENDING -> APPLYING -> GRANTED
                    -> BLOCKED / FAILED
```

重点场景：

- 背包满时物品进入奖励箱，不丢失。
- 重连或重载不会重复发放。
- 指令奖励受总开关和命令白名单限制。
- 崩溃后的 `APPLYING` 项不会自动危险重放。
- 玩家奖励箱和管理员奖励账本 GUI 可打开。
- `retry / inspect / resolve` 权限正确。

重点文件：

- `RewardGrantService.java`
- `RewardClaimLedger.java`
- `RewardInboxScreenHandler.java`
- `RewardLedgerScreenHandler.java`

**四、成就系统检查**

验证：

- 原版统计类型映射正确。
- `all / any / not / sum` 逻辑正确。
- 目标组、标签和通配符正确展开。
- 距离、时间、伤害单位换算正确。
- 成就检查遵守玩家数和条件数预算。
- 配置热重载后调度队列清空并重建。
- 成就预设 JSON 均能通过配置校验。

**五、文本与占位符检查**

统计并验证：

- 当前 17 个 OmniTools 内置占位符。
- Placeholder API 未安装时安全回退。
- 已安装时第三方占位符可用于侧边栏和配置文本。
- 签到、在线奖励、商店、成就、称号、命令菜单文本使用统一渲染器。
- 未知占位符只警告一次，不导致服务器报错。
- 命令执行仍使用受控变量，不能直接使用任意第三方文本替换命令。

**六、纯服务端兼容性检查**

必须测试：

- 无 OmniTools 客户端连接。
- 无 Placeholder API 启动。
- 有 Placeholder API 启动。
- 打开签到、在线奖励、商店、称号、成就、云存储、命令菜单和模块管理 GUI。
- 翻页、返回、领取、关闭和模块禁用后的界面行为。
- 侧边栏 `skip / replace / restore` 策略。

当前记录显示服务端启动和构建已通过，但客户端自动启动曾出现 `spawn UNKNOWN`，因此原版客户端逐个点击 GUI 仍应标记为“待人工验收”，不能直接判定全部通过。

**七、自动化验证**

工作台应执行并记录：

```text
gradlew test
gradlew build
内容校验
服务端启动烟雾测试
无 Placeholder API 启动测试
配置迁移测试
奖励状态机测试
模块热重载测试
```

最终报告必须包含：

```text
已通过：
警告：
失败：
待人工验证：
涉及文件：
建议修复优先级：
```

---

## Development request 2026/8/25 21:41:10

下面方案可直接转交工作台执行，目标是让文档结构清晰、内容不重复，并与当前 OmniTools 代码和配置保持一致。

**一、现状整理**

当前已有：

- `README.md`：项目首页、安装、快速开始、模块链接。
- `docs/modules/`：十个模块文档。
- `docs/upgrade-guide.md`：配置升级说明。
- `docs/backup-and-recovery.md`：备份恢复说明。
- `docs/reward-consistency.md`：奖励账本和异常处理。
- `docs/presets/achievements/`：五组成就预设。
- `docs/idea.md`、`docs/archive/idea.md`：设计记录。
- `docs/last-ai-change.json`、`docs/last-ai-response.txt`：工作台内部记录，不应出现在用户导航中。

主要问题是：模块文档章节不统一，配置、指令、权限、占位符和故障处理分散，README 仍承担过多入口说明，缺少统一文档索引。

**二、目标目录**

建议采用以下结构：

```text
README.md                         # 只保留首页介绍和快速入口

docs/
├── index.md                      # 文档总索引
├── modules/
│   ├── daily-checkin.md
│   ├── online-reward.md
│   ├── shop-and-currency.md
│   ├── titles.md
│   ├── title-effects.md
│   ├── achievements.md
│   ├── cloud-storage.md
│   ├── permissions.md
│   ├── command-menu.md
│   └── sidebar.md
├── guides/
│   ├── module-management.md
│   ├── placeholder-api.md
│   ├── upgrade-guide.md
│   ├── backup-and-recovery.md
│   └── reward-consistency.md
├── presets/
│   └── achievements/
└── archive/
    ├── idea.md
    └── agent-records/
```

`docs/modules/` 保持现有文件名，避免破坏已有链接；新增 `docs/index.md` 作为统一入口。

`docs/last-ai-change.json` 和 `docs/last-ai-response.txt` 只作为工作台内部记录，不放入用户文档导航。

**三、README 重写范围**

README 只保留：

1. OmniTools 简介。
2. Minecraft、Fabric、Java 版本要求。
3. 纯服务端特性说明。
4. 安装步骤。
5. 首次启动和 `/omnitools reload`。
6. 文档总索引链接。
7. 常见问题入口。
8. 版本和变更日志链接。

不要在 README 中重复完整配置字段、奖励状态机或成就条件语法。

**四、每个模块统一章节**

所有 `docs/modules/*.md` 按以下顺序重写：

1. 模块用途和适用场景。
2. 模块依赖与关联模块。
3. 模块开关配置。
4. 初始配置文件位置。
5. 最小可用配置。
6. 完整配置示例。
7. 配置字段表：类型、必填、默认值、范围、重载方式。
8. 指令、别名和权限节点。
9. GUI 操作说明。
10. 占位符列表及用途。
11. 数据保存位置和升级影响。
12. 与其他模块的联动。
13. 常见错误及解决方法。
14. 可复制的验收清单。

模块内容应具体对应当前实现，例如：

- 签到：六行周历、奖励详情页、月度奖励、签到记录。
- 在线奖励：在线时长、里程碑、奖励账本。
- 成就：原版统计、`all/any/not/sum`、目标组、预设文件。
- 命令菜单：菜单注册、页面配置、按钮动作、命令安全白名单。
- 侧边栏：刷新周期、占位符、冲突策略。
- 权限：服主、管理员、玩家三种角色和每条指令的默认权限。

**五、公共指南内容**

`guides/` 只放跨模块内容：

- `module-management.md`：模块状态、热重载、依赖关系和失败回滚。
- `placeholder-api.md`：17 个 OmniTools 占位符、第三方 Placeholder API、安装与禁用行为。
- `reward-consistency.md`：`PENDING/APPLYING/GRANTED/BLOCKED/FAILED` 状态和异常处理。
- `upgrade-guide.md`：旧 `qiandao` 配置迁移、根配置版本、备份文件。
- `backup-and-recovery.md`：世界数据、`config/omnitools/` 和奖励账本的备份恢复。

模块文档只保留相关链接，不复制这些公共规则。

**六、配置文档规范**

所有示例必须：

- 使用 `config/omnitools/` 路径。
- 明确区分根配置和模块配置。
- 标注“首次启动生成”“修改后需要 reload”。
- 同时提供最小配置和完整配置。
- 与代码中的当前格式版本一致。
- 不直接把 `run/config` 运行时文件当作唯一示例来源。
- 所有 JSON 使用 UTF-8、合法 JSON 和统一缩进。

**七、工作台执行阶段**

1. 清点现有文档，建立旧路径到新路径的映射。
2. 创建 `docs/index.md`，精简 README。
3. 按统一模板重写十个模块文档。
4. 整理公共指南和奖励、升级、备份说明。
5. 将成就预设加入成就文档，并逐个标注用途。
6. 统一所有 Markdown 链接、标题层级和术语。
7. 搜索并清理面向用户文档中的旧 `qiandao`、过时版本号和旧配置路径。
8. 检查 JSON 示例、命令名称、权限节点和代码实际实现是否一致。
9. 执行 Markdown 链接检查、JSON 解析检查和文档内容校验。
10. 最后生成文档变更摘要和未覆盖功能清单。

**验收标准**

- 用户从 README 三次点击内可到达任意模块文档。
- 每个模块都有指令、权限、配置、GUI、占位符和故障处理章节。
- 不存在重复且互相矛盾的配置说明。
- 文档中不再使用旧项目名称或旧配置路径。
- 所有示例可以直接复制并通过配置校验。
- 文档明确区分“已实现”“可选依赖”和“规划功能”。
- 工作台最终输出修改文件清单、链接检查结果、JSON 校验结果和剩余风险。

本轮仅整理文档重构方案，未修改文件。

---

## Development request 2026/8/25 23:06:29

## 文档重构目标

将文档改造成“零基础服主可复制配置”的教程体系，并以当前代码解析器与 `ConfigValidator` 为唯一真源，禁止根据旧示例猜测字段。

关键规则：

- 真正的 `config.json` 必须是严格 JSON，不能写 `//` 或 `/* */` 注释。
- 每个配置场景同时提供两份代码：
  - `jsonc`：带逐行中文注释，仅用于教学。
  - `json`：无注释、可直接复制到服务器使用。
- 文档明确标注“推荐新格式”和“旧格式兼容”，不能混在同一示例中。
- 所有文档统一 UTF-8 编码；排查当前乱码、失效链接、重复入口。
- 保留旧文档链接的跳转页或迁移提示，不能直接删除导致外链失效。

## 建议目录

```text
docs/
├── index.md                         # 首页，只放简介、安装、快速开始、文档导航
├── getting-started/
│   ├── first-setup.md                # 第一次启动、配置文件位置、reload、备份
│   ├── configuration-basics.md       # JSON 基础、物品 ID、颜色、常见错误
│   └── troubleshooting.md            # 报错与排查
├── reference/
│   ├── root-config.md                # 总配置全部字段
│   ├── rewards.md                    # 四类统一奖励
│   ├── placeholders.md               # OmniTools 内置占位符完整表
│   └── placeholder-api.md            # 可选 Text Placeholder API 占位符
├── modules/
│   ├── daily-checkin.md
│   ├── online-reward.md
│   ├── shop-and-currency.md
│   ├── titles.md
│   ├── title-effects.md
│   ├── achievements.md
│   ├── cloud-storage.md
│   ├── permissions.md
│   ├── command-menu.md
│   └── sidebar.md
├── examples/
│   ├── minimal-server/
│   ├── reward-examples/
│   └── achievement-examples/
├── presets/                          # 保留现有可直接加载的 JSON 预设
├── guides/                           # 迁移、备份、模块管理等跨模块主题
└── archive/                          # 历史设计，不作为正式配置教程入口
```

当前 `docs/guides/`、`docs/modules/` 与根目录中存在重复主题，应选择上述路径作为唯一正式入口；旧 `docs/modules/module-management.md`、`docs/modules/placeholder-api.md` 等改为跳转说明。

## 每个模块文档的固定结构

所有 `docs/modules/*.md` 必须采用同一章节顺序：

1. 模块用途与适用场景。
2. 前置条件、关联模块、模块开关位置。
3. 配置文件路径与修改后执行的指令。
4. 最小可用配置。
5. 注释教学版 `jsonc`。
6. 可直接复制版 `json`。
7. 字段表：字段、类型、是否必填、默认值、范围、示例、常见错误。
8. 全部配置场景示例。
9. 指令、权限节点、默认角色。
10. 可用占位符与示例。
11. 数据保存位置及升级影响。
12. 验收步骤与故障排查。

## 配置覆盖清单

工作台必须逐项补齐以下示例，不能只给“完整大配置”。

| 配置 | 必须覆盖的情况 |
|---|---|
| 根配置 `config/omnitools/config.json` | 全局语言/时区、数据保留、十个模块开关、指令奖励安全开关、命令根白名单、长度/冷却限制、Placeholder API 集成开关 |
| 每日签到 | 最低配每日奖励、月度里程碑、四类奖励、新 `rewards` 格式、旧 `dailyCoins/monthlyRewards` 兼容迁移 |
| 在线奖励 | 单个时长奖励、多里程碑、四类奖励、新格式与旧 `coins` 兼容格式 |
| 商店 | 普通物品、价格、槽位、`components`、购买失败原因；明确不推荐/不支持的旧 NBT 写法 |
| 称号 | 稀有度、展示文本、描述、解锁、佩戴、效果关联、头顶/队伍名称冲突策略 |
| 称号效果 | `POTION`、`ATTRIBUTE`、`PARTICLE`、`PERMISSION` 四类效果各一个可运行示例 |
| 成就 | 下方“成就专章”全部情况 |
| 云存储 | 默认页数、扩容价格、最大页数、禁用模块时的行为 |
| 权限 | `PLAYER`、`MODERATOR`、`ADMIN`、`OWNER`；字符串简写与对象完整写法；原生命令节点授权 |
| 命令菜单 | 菜单注册、页面文件、27/54 格、物品、Lore、左/右键、关闭、跳转子菜单、玩家/控制台执行、权限与命令安全 |
| 侧边栏 | 标题、行、刷新频率、内置/第三方占位符、长度限制、`skip`/`replace`/`restore` 三种冲突策略 |

特别修正：侧边栏新配置只推荐 `skip`、`replace`、`restore`。旧值 `warn`、`disabled` 只作为兼容迁移说明，不能出现在新用户可复制的配置中。

## 成就文档拆分

不要用一个巨型 JSON 解释全部成就。将 `docs/modules/achievements.md` 链接到多个独立、可复制的例子：

```text
examples/achievement-examples/
├── 01-mine-one-block.json
├── 02-sum-multiple-targets.json
├── 03-each-target-must-pass.json
├── 04-any-condition.json
├── 05-all-conditions.json
├── 06-not-condition.json
├── 07-distance-statistics.json
├── 08-time-statistics.json
├── 09-damage-statistics.json
├── 10-entity-and-boss.json
├── 11-target-groups-tags-wildcards.json
└── 12-four-reward-types.json
```

成就条件必须解释并各自给出示例：

- 条件节点：`stat`、`sum`、`all`、`any`、`not`。
- 统计域：`block_mined`、`item_crafted`、`item_used`、`item_broken`、`item_picked_up`、`item_dropped`、`entity_killed`、`entity_killed_by`、`custom`。
- 多目标统计方式：`sum`、`each`、`any`。
- 目标写法：普通 ID、`$target_group`、`#namespace:tag`、通配符 `*`。
- 单位：距离 `cm/meters/blocks/kilometers`，时间 `ticks/seconds/minutes/hours`，伤害 `damage/hearts`，其余为 `count`。
- 明确限制：原版没有严格独立的“方块放置数”统计；`item_used` 只能近似反映物品使用或放置，不能宣传为精确放置统计。

## 统一奖励教程

新增 `docs/reference/rewards.md`，并由签到、在线奖励、成就复用链接，不要维护三份不同说明。

支持四种奖励：

- `currency`
- `item`：使用 `item`、`count`、`components`，明确禁止示例使用 NBT。
- `title`
- `command`：仅允许 `run_as: "console"`，并同时受根配置的奖励开关与 `allowed_roots` 白名单限制。

命令奖励和命令菜单中只能使用：

```text
{player_name}
{player_uuid}
{player_x}
{player_y}
{player_z}
{player_world}
```

第三方 `%...%` 文本占位符不得进入控制台命令，必须在文档中作为安全红线说明。

## 占位符文档

`docs/reference/placeholders.md` 必须完整列出当前 OmniTools 保证支持的 17 个占位符，表格字段为：占位符、说明、依赖模块、模块关闭时回退值、配置示例。

| 分类 | 占位符 |
|---|---|
| 货币 | `balance`、`balance_formatted` |
| 签到 | `checkin_today`、`checkin_today_rank`、`checkin_total_days`、`checkin_streak_days`、`checkin_month_days` |
| 在线奖励 | `online_today_seconds`、`online_today_minutes`、`online_today_hms` |
| 称号 | `title_id`、`title`、`title_plain`、`title_effects_enabled` |
| 成就 | `achievements_unlocked`、`achievements_claimed`、`achievements_total` |

标准写法为：

```text
%omnitools:balance%
```

侧边栏额外允许简写：

```text
%balance%
```

回退规则也必须写清：

- 数值为 `0`。
- 布尔值为 `false`。
- 称号文本为空。
- `online_today_hms` 为 `00:00:00`。
- 未知文本占位符显示 `-`，并仅记录一次警告。

`docs/reference/placeholder-api.md` 单独说明可选 Text Placeholder API：

- 未安装 API 或 `integrations.placeholder_api.enabled=false` 时，OmniTools 仍可正常启动。
- 仅在 API 存在且集成开关开启时解析第三方文本占位符。
- 依据当前内置的 Placeholder API `2.8.2`，按“玩家、世界、服务器、计分板”四类列出已验证占位符，并标明 API 版本。
- 不得声称可预先列出“所有第三方模组占位符”；第三方内容由服务器实际安装的模组动态注册，应提供检测与排错方法。

## 工作台验收

完成前必须输出一份文档审计报告，至少包括：

- 每个模块、每种配置场景是否已有“教学版 + 可复制版”。
- 所有 `json` 代码块可被 JSON 解析。
- `jsonc` 明确标注“不能直接复制到真实 JSON 文件”。
- 示例通过配置加载器或 `ConfigValidator` 验证。
- 字段名、默认值、枚举值、路径与当前解析代码一致。
- 检索并处理旧名称 `qiandao`、旧配置路径、旧冲突策略 `warn/disabled`、过时版本号。
- 检查全部 Markdown 链接、锚点和预设文件链接。
- 检查文件编码，统一为 UTF-8，避免终端/编辑器乱码。
- 列出新增、迁移、归档和保留跳转的文档文件。
- 说明未覆盖的人工验收项，例如实际服务器中第三方 Placeholder API 模组是否已注册对应变量。

当前资料的依据应优先来自配置解析与验证代码，例如 [AchievementConfig.java](D:/mod/qiandao/src/main/java/dev/modmind/omnitools/AchievementConfig.java)、[ConfigValidator.java](D:/mod/qiandao/src/main/java/dev/modmind/omnitools/config/ConfigValidator.java)、[OmniToolsPlaceholderResolver.java](D:/mod/qiandao/src/main/java/dev/modmind/omnitools/OmniToolsPlaceholderResolver.java)，而不是直接复制 `run/config` 中可能保留旧格式的示例。
