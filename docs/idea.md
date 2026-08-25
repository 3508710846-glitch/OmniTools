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
