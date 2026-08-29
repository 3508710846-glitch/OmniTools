# 文档已归档

此入口为兼容旧链接而保留。历史设计、工作请求和已过期方案已移至[历史需求记录](archive/request-history.md)。

归档内容不代表当前已实现功能，也不应作为配置、命令或行为的真源。请从[文档首页](index.md)进入当前文档；配置平台规则见[统一配置平台](config-platform.md)。

---

## Development request 2026/8/27 17:35:12

可以做，并且很适合作为独立的 `leaderboards` 模块。现有成就系统已经能解析全部原版统计族：挖掘、合成、使用、损坏、拾取、丢弃、击杀、被击杀和 `custom` 统计；目标还支持单 ID、多 ID、标签、分组、`*` 全量选择，见 [StatisticTargetResolver.java](/D:/mod/qiandao/src/main/java/dev/modmind/omnitools/achievement/StatisticTargetResolver.java)。

关键是不要复制成就的统计逻辑，而是提取共享的 `StatisticQuery` 查询规格，供成就和排行榜共同使用。

**模块设计**

```text
config/omnitools/
  leaderboards/
    config.json
```

根配置新增：

```json
"leaderboards": {
  "enabled": false
}
```

建议升级时默认关闭，避免旧服务器升级后立即扫描全部玩家统计文件；新服主配置榜单并启用后才开始工作。

排行榜配置建议：

```jsonc
{
  "format_version": 1,
  "refresh_interval_ticks": 200,
  "include_offline_players": true,
  "exclude_zero_scores": true,

  "target_groups": {
    "stone_family": ["minecraft:stone", "minecraft:deepslate"],
    "common_hostiles": [
      "minecraft:zombie",
      "minecraft:skeleton",
      "minecraft:creeper"
    ]
  },

  "leaderboards": [
    {
      "id": "mine_stone",
      "display": "&b石材矿工",
      "description": "累计挖掘石头",
      "icon": "minecraft:stone",
      "stat": {
        "type": "block_mined",
        "targets": ["minecraft:stone"],
        "aggregation": "sum",
        "unit": "count"
      }
    },
    {
      "id": "mine_stone_family",
      "display": "&7石材大师",
      "icon": "minecraft:deepslate",
      "stat": {
        "type": "block_mined",
        "targets": ["$stone_family"],
        "aggregation": "sum",
        "unit": "count"
      }
    },
    {
      "id": "mine_all_blocks",
      "display": "&e全方块挖掘",
      "icon": "minecraft:diamond_pickaxe",
      "stat": {
        "type": "block_mined",
        "targets": ["*"],
        "aggregation": "sum",
        "unit": "count"
      }
    },
    {
      "id": "place_all_blocks",
      "display": "&a建筑达人",
      "icon": "minecraft:bricks",
      "stat": {
        "type": "item_used",
        "targets": ["@block_items"],
        "aggregation": "sum",
        "unit": "count"
      }
    },
    {
      "id": "hostile_kills",
      "display": "&c猎魔榜",
      "icon": "minecraft:diamond_sword",
      "stat": {
        "type": "entity_killed",
        "targets": ["$common_hostiles"],
        "aggregation": "sum",
        "unit": "count"
      }
    },
    {
      "id": "fall_distance",
      "display": "&f坠落距离",
      "icon": "minecraft:feather",
      "stat": {
        "type": "custom",
        "custom_stat": "minecraft:fall_one_cm",
        "unit": "blocks"
      }
    },
    {
      "id": "hopper_inspections",
      "display": "&6漏斗检查",
      "icon": "minecraft:hopper",
      "stat": {
        "type": "custom",
        "custom_stat": "minecraft:inspect_hopper",
        "unit": "count"
      }
    }
  ]
}
```

`aggregation` 建议支持：

- `sum`：多个目标相加，适合“石头 + 深板岩总共挖掘”。
- `min`：取各目标最低值，适合“每种都挖过多少”的公平排名。
- `max`：取单一目标最高值。

**必须明确的原版限制**

原版没有精确的“方块放置数”统计。`item_used` 可用于某个方块物品的使用/放置近似值，但并不能保证每次使用都成功放置。

因此新增 `@block_items` 选择器：只展开 `BlockItem`，解决现有 `item_used + ["*"]` 会把食物、工具等所有物品使用也统计进去的问题。`block_mined + ["*"]` 则可以准确表示所有方块挖掘。

**运行架构**

- 新增 `ModuleId.LEADERBOARDS`、`LeaderboardConfig`、`LeaderboardService`、`LeaderboardScreenHandler`。
- `LeaderboardConfig` 加入 [OmniToolsConfigManager.java](/D:/mod/qiandao/src/main/java/dev/modmind/omnitools/config/OmniToolsConfigManager.java) 的模块注册、快照、全量重载和单模块重载。
- 将成就中的统计目标解析提取为共享 `statistics` 包；排行榜读取同一种 `type / targets / custom_stat / unit`。
- 排行榜只读原版玩家统计，不复制、修改或重置原版统计。
- 在线玩家定期更新；离线玩家从世界 `stats/<uuid>.json` 对应的 `ServerStatsCounter` 安全读取。
- 服务端启动时分批扫描，配置 `max_files_per_tick`，禁止在一个 tick 内同步读取大量玩家文件。
- 每个榜单维护内存快照：完整排序、前若干名、玩家个人名次、刷新时间。GUI、聊天、侧边栏只读快照，不能临时全量排序。
- 默认排除 0 分玩家；同分按玩家名稳定排序，名次采用密集排名，例如 `1, 1, 2`。

**成就联动**

第一版做“统计语义联动”，不让排行榜影响成就进度：

- 成就和排行榜使用同一套原版统计查询。
- 两者都支持同样的单目标、多目标、分组、标签与通配符。
- 可选 `linked_achievement` 字段仅在排行榜 GUI 中显示关联成就和玩家自身进度。
- 不建议第一版支持“按复杂成就条件树排名”。`all/any/not` 更适合判定是否完成，不天然对应唯一、可解释的分数。

**玩家交互**

新增权限动作：

```text
leaderboards.open -> PLAYER
leaderboards.chat -> PLAYER
```

建议指令：

```text
/omnitools leaderboard
/omnitools leaderboard open <id>
/omnitools leaderboard chat <id> [page]
/omnitools leaderboard list
/leaderboard
/top <id> [page]
```

GUI 使用现有原版 54 格箱子模式：

- 首页显示已配置榜单。
- 点击榜单进入排名页。
- 内容区显示 36 名玩家，页脚提供上一页、关闭、下一页。
- 每个条目展示：名次、头像或兜底图标、玩家名、格式化分数。
- 玩家不在当前页时，底部固定显示“我的排名 / 我的分数”。
- 聊天榜每页建议 10 名，提供可点击的上一页、下一页和打开 GUI。

**侧边栏联动**

现有侧边栏只能向每个玩家展示一个原版 scoreboard，因此不能同时显示多个榜。应将 [SidebarConfig.java](/D:/mod/qiandao/src/main/java/dev/modmind/omnitools/sidebar/SidebarConfig.java) 升级为 v3 的“页面”模型，而不是让排行榜直接抢占第二个侧边栏。

```jsonc
{
  "format_version": 3,
  "default_visible": true,
  "refresh_interval_ticks": 20,
  "conflict_policy": "skip",

  "presentation": {
    "mode": "rotate",
    "rotation_ticks": 200,
    "fixed_page": "main",
    "page_ids": ["main", "mine_all", "hostile_kills"]
  },

  "pages": [
    {
      "id": "main",
      "type": "text",
      "title": "&b&lOmniTools",
      "lines": [
        { "id": "money", "text": "&e货币: &f%balance_formatted%" }
      ]
    },
    {
      "id": "mine_all",
      "type": "leaderboard",
      "leaderboard_id": "mine_all_blocks",
      "title": "&e&l全方块挖掘榜",
      "max_entries": 10,
      "line_format": "&7#{rank} &f{player} &b{value}"
    }
  ]
}
```

规则：

- `presentation.mode: fixed`：只显示 `fixed_page`。
- `presentation.mode: rotate`：按 `rotation_ticks` 轮播 `page_ids`。
- 所有人共用服务器轮播节奏，避免玩家进服时间不同导致画面混乱。
- 榜单模块关闭时，排行榜页面自动跳过；静态页面仍正常显示。
- 保持侧边栏的 15 行上限、`skip/replace/restore` 冲突策略和玩家个人显示开关。
- v2 旧配置自动映射为一个 `type: "text"` 的 `main` 页面，避免破坏已有侧边栏。

**实施阶段**

1. 抽取共享统计查询和新增 `leaderboards` 配置、快照、校验。
2. 实现排行榜缓存，先支持在线与离线统计、同分名次、全量/单模块热重载。
3. 完成 GUI、聊天分页、命令与权限。
4. 升级侧边栏为页面与轮播模型，保持 v2 配置兼容。
5. 补 Schema、示例、中文文档和测试。

验收重点：离线玩家仍进入排行榜；多目标和 `*` 得分正确；错误配置不替换旧快照；排行榜不会在请求 GUI 或聊天时扫描磁盘；侧边栏轮播不破坏原有冲突策略；禁用模块后 GUI、聊天和侧边栏榜单均停止显示。

---

## Development request 2026/8/27 21:43:45

静态审查结论：OmniTools 已经从单一签到模组发展为一个较完整的纯服务端功能平台，但目前更像“功能已经很多、需要收敛稳定性”的阶段。最大风险不在功能数量，而在配置默认值、热重载一致性、排行榜性能和中心入口过度集中。以下结论基于源码和文档检查，未执行构建、单元测试或服务器冒烟测试。

**一、已经实现的内容**

| 영역 | 当前实现 |
| --- | --- |
| 服务端形态 | `fabric.mod.json` 设置了 `"environment": "server"`，没有客户端 entrypoint，GUI 通过服务端 ScreenHandler 提供。[fabric.mod.json](D:/mod/qiandao/src/main/resources/fabric.mod.json:8) |
| 模块化配置 | 根配置、模块配置、公共模板、配置迁移和版本号体系已经存在。`ModuleId` 已包含签到、CDK、在线奖励、商店、称号、成就、权限、命令菜单、侧边栏、排行榜等模块。[ModuleId.java](D:/mod/qiandao/src/main/java/dev/modmind/omnitools/config/ModuleId.java:7) |
| 配置加载与重载 | `OmniToolsConfigManager` 支持全量重载、单模块重载、模块开关、失败保留旧快照和原子写入。[OmniToolsConfigManager.java](D:/mod/qiandao/src/main/java/dev/modmind/omnitools/config/OmniToolsConfigManager.java:35) |
| 现有基础功能 | 每日签到、补签卡、在线奖励、商店与货币、称号、限时称号、称号效果、CDK、云存储、权限、命令菜单、侧边栏均有对应源码和配置文件。 |
| 奖励体系 | 奖励支持货币、物品、NBT/组件物品、称号、限时称号、指令和补签卡；奖励账本包含待处理、失败、阻塞、重试和人工结算状态。 |
| 成就系统 | 支持挖掘、合成、使用、损坏、拾取、丢弃、实体击杀、被实体击杀和 `custom` 统计；支持目标组、标签、通配符以及 `sum`、`each`、`any`、`all`、`not` 组合。[StatisticQuery.java](D:/mod/qiandao/src/main/java/dev/modmind/omnitools/statistics/StatisticQuery.java:28) |
| 占位符 | 内置占位符目前为 22 个，覆盖货币、签到、在线时长、称号、成就；可选 Text Placeholder API 通过反射桥接。[OmniToolsPlaceholderResolver.java](D:/mod/qiandao/src/main/java/dev/modmind/omnitools/OmniToolsPlaceholderResolver.java:12) |
| 侧边栏 | 支持文本页面、排行榜页面、固定页面、轮播页面、Placeholder API 和第三方侧边栏冲突策略。[SidebarConfig.java](D:/mod/qiandao/src/main/java/dev/modmind/omnitools/sidebar/SidebarConfig.java:337) |
| 排行榜 | 排行榜已接入配置快照、命令、GUI、聊天分页和侧边栏；支持离线统计文件、目标组、通配符、原版自定义统计和成就关联。[LeaderboardService.java](D:/mod/qiandao/src/main/java/dev/modmind/omnitools/leaderboard/LeaderboardService.java:23) |

**二、当前最明显的缺口**

1. **排行榜模块的根开关存在默认值矛盾**

   文档和 `defaults()` 都说明排行榜默认关闭，但运行配置 `run/config/omnitools/config.json` 没有 `leaderboards` 项。[run/config/omnitools/config.json](D:/mod/qiandao/run/config/omnitools/config.json:27)

   更严重的是，解析已有根配置时，缺失模块会走：

   ```java
   value == null || !value.isJsonObject() || bool(..., true)
   ```

   因此缺少 `leaderboards` 时会被解析为 `enabled=true`。[OmniToolsRootConfig.java](D:/mod/qiandao/src/main/java/dev/modmind/omnitools/config/OmniToolsRootConfig.java:126)

   结果是：文档说默认关闭，但某些旧配置实际会意外启用排行榜，开始扫描所有玩家统计文件。

2. **模块生命周期抽象没有真正接通**

   `ConfigurableModule.apply()`、`ConfigModuleRegistry.applyAll()` 已经定义，但当前唯一的 `applyAll` 调用点是接口本身，实际重载仍集中转发到 `ModMindEntry.applyRuntimeConfigChange()`。[ConfigModuleRegistry.java](D:/mod/qiandao/src/main/java/dev/modmind/omnitools/config/ConfigModuleRegistry.java:60)、[RuntimeConfigApplier.java](D:/mod/qiandao/src/main/java/dev/modmind/omnitools/config/RuntimeConfigApplier.java:8)

   这意味着新增模块时，仍可能需要修改中心入口，容易出现“配置已经加载，但运行时清理或刷新遗漏”的问题。

3. **`ModMindEntry.java` 过度集中**

   当前主入口约 1714 行，承担启动、命令注册、配置应用、GUI 打开、事件处理、侧边栏、排行榜和权限逻辑。它已经成为主要回归风险来源，后续每添加一个模块都会扩大影响范围。

4. **排行榜还不是“所有原版统计”的完整抽象**

   当前支持 9 类统计域，但“方块放置”没有原版精确统计，只能用 `item_used` 近似。距离、时间、伤害等需要通过 `custom` 统计 ID，并不是所有统计都提供了专门的语义层。[docs/modules/leaderboards.md](D:/mod/qiandao/docs/modules/leaderboards.md:30)

   因此文档必须明确区分：

   - 精确支持的统计类型；
   - 通过 `custom_stat` 支持的原版统计；
   - 原版不存在、只能近似的统计，例如精确方块放置数。

5. **排行榜离线扫描仍有扩展性风险**

   `LeaderboardService` 会枚举 `stats/<uuid>.json`，并在服务器 tick 中按 `max_files_per_tick` 分批读取。[LeaderboardService.java](D:/mod/qiandao/src/main/java/dev/modmind/omnitools/leaderboard/LeaderboardService.java:82)

   虽然已经避免一次性扫描，但每次刷新仍可能：

   - 在主线程执行大量磁盘读取；
   - 对每个玩家计算所有榜单；
   - 对每个榜单完整排序；
   - 在大型服务器上产生较长的快照延迟。

   另外，离线玩家名称只从 `knownNames` 获取，未记录过的玩家可能显示为 UUID 前缀。[LeaderboardService.java](D:/mod/qiandao/src/main/java/dev/modmind/omnitools/leaderboard/LeaderboardService.java:29)

6. **文档、Schema 和运行时规则仍需持续对齐**

   目前同时存在 `docs/modules/`、`docs/guides/`、`docs/reference/`、`docs/examples/`、旧兼容页面和 `archive/`。文档维护清单已经存在，但还没有看到自动检查“源码字段、Schema、示例、文档表格”一致性的机制。[validation-checklist.md](D:/mod/qiandao/docs/maintainers/validation-checklist.md:7)

7. **可选 Placeholder API 的发布链路需要复核**

   `fabric.mod.json` 将 Placeholder API 放在 `suggests` 中，但 `build.gradle` 又以 `modImplementation` 引入本地 JAR。[fabric.mod.json](D:/mod/qiandao/src/main/resources/fabric.mod.json:22)、[build.gradle](D:/mod/qiandao/build.gradle:34)

   这不一定会导致最终 JAR 产生硬依赖，但必须确认发布包在没有 Placeholder API 的服务器上仍能正常启动和运行。

**三、最值得优先处理的风险**

**P0：配置开关语义错误**

优先修复缺失模块字段的默认值逻辑，特别是 `leaderboards`。建议统一为：

- 新配置生成：明确写出所有模块；
- 旧配置加载：缺失模块使用版本迁移策略；
- 不允许解析器直接把未知/缺失模块无条件当作 `true`；
- 增加测试覆盖“缺少 leaderboards 时必须为 false”。

**P0：完成真实运行验证**

当前只能确认代码路径存在，不能确认服务器行为。至少需要验证：

1. 首次启动是否正确生成所有配置；
2. 排行榜缺失根开关时的实际状态；
3. `/omnitools reload` 和单模块重载；
4. GUI 打开期间禁用模块；
5. 侧边栏和排行榜联动；
6. Placeholder API 安装与未安装两种环境；
7. 奖励发放中途断服、背包满、NBT 物品和指令奖励异常恢复。

**P1：拆分中心入口并启用模块生命周期**

建议让每个模块拥有自己的：

- 配置加载器；
- Schema/运行时校验；
- 命令注册器；
- `apply(previous, current, runtime)`；
- 启用、禁用和重载清理逻辑。

`ModMindEntry` 只保留启动、事件总线和服务容器，避免继续添加业务代码。

**P1：优化排行榜扫描**

建议按以下顺序处理：

1. 缓存统计文件的修改时间，未变化文件不重复解析；
2. 文件读取和解析采用有界后台队列，主线程只接收结果；
3. 增加每榜单最大排名数，使用 Top-K 而不是完整排序；
4. 增加扫描进度、耗时、文件数和快照年龄诊断；
5. 使用服务器用户缓存补全离线玩家名称；
6. 对 `StatisticQuery` 预解析统计对象，避免重复注册表查找。

**P1：加强奖励一致性和安全性**

指令奖励、NBT 物品、限时称号和背包满场景仍应视为高风险路径。奖励账本应保持幂等，不能通过删除记录解决失败；指令奖励需要继续受全局白名单、长度限制和权限配置约束。

**P2：完善文档与配置体验**

最终应形成一套机器可验证的发布流程：

- Java 配置类生成 Schema；
- Schema 生成带注释示例；
- 示例配置自动校验；
- 文档命令和字段从源码/Schema 交叉检查；
- 明确“已实现、近似实现、规划中”三种状态。

总体判断：核心功能覆盖面已经很高，排行榜和模块化配置也已经接入主架构；但在修复根配置默认值、完成服务器级验证、拆分 `ModMindEntry` 和控制排行榜资源消耗之前，不建议把当前状态视为稳定发行版。

---

## Development request 2026/8/27 22:40:45

可以，建议把礼包设计成一个独立的“礼包模块”，底层复用现有奖励、物品解析、奖励账本和 GUI 框架。

当前项目已经具备几个重要基础：

- 奖励类型集中在 [`RewardDefinition.java`](D:/mod/qiandao/src/main/java/dev/modmind/omnitools/reward/RewardDefinition.java:17)。
- 物品和完整 SNBT 解析集中在 [`ItemStackConfigParser.java`](D:/mod/qiandao/src/main/java/dev/modmind/omnitools/config/ItemStackConfigParser.java:28)。
- 奖励幂等、失败重试和奖励箱逻辑在 [`RewardGrantService.java`](D:/mod/qiandao/src/main/java/dev/modmind/omnitools/reward/RewardGrantService.java:147) 和 [`RewardClaimLedger.java`](D:/mod/qiandao/src/main/java/dev/modmind/omnitools/reward/RewardClaimLedger.java:27)。
- 当前奖励箱只展示“待投递物品”，因为 [`RewardInboxScreenHandler.java`](D:/mod/qiandao/src/main/java/dev/modmind/omnitools/RewardInboxScreenHandler.java:148) 只筛选带物品快照的账本记录，不能直接把礼包当成普通物品处理。

因此，推荐采用“虚拟礼包实例 + 独立礼包 GUI”的方案，而不是生成一个容易被复制、丢失或篡改的实体礼包物品。

**一、模块和文件结构**

新增模块：

```text
config/omnitools/
├── config.json
└── packages/
    └── config.json
```

新增模块 ID：

```text
packages
```

需要接入：

- `ModuleId.PACKAGES`
- `OmniToolsRootConfig`
- `OmniToolsConfigSnapshot`
- `OmniToolsConfigManager`
- `ConfigValidator`
- `ConfigModuleRegistry`
- `ConfigPaths`
- `/omnitools reload packages`

建议新增类：

```text
package/PackageConfig.java
package/PackageDefinition.java
package/PackageItem.java
package/PackageInstance.java
package/PackageData.java
package/PackageService.java
package/PackageDeliveryBatch.java
PackageScreenHandler.java
PackageConfirmScreenHandler.java
```

玩家获得的不是普通物品，而是一个服务端保存的礼包实例：

```text
礼包实例：
- instance_id
- package_id
- package_version
- display_name
- icon
- mode
- item_snapshot
- source_event
- status
- granted_at
```

礼包发放时保存礼包内容快照。以后服主修改礼包配置，不会改变已经发放但尚未打开的礼包。

**二、配置文件设计**

建议使用 `quantity` 表示礼包内的数量，而不是直接使用原版 ItemStack 的 `count`。这样可以支持超过 64 个物品，并由服务端自动拆分成多个堆叠。

```jsonc
{
  "format_version": 1,

  "settings": {
    "max_packages_per_player": 256,
    "max_quantity_per_entry": 2304,
    "delivery_policy": "inventory_then_inbox",
    "random_strategy": "uniform"
  },

  "packages": [
    {
      "id": "starter",
      "display": "&a新手礼包",
      "description": [
        "&7打开后获得全部新手物资"
      ],
      "icon": "minecraft:chest",
      "mode": "all",

      "items": [
        {
          "id": "bread",
          "item": "minecraft:bread",
          "quantity": 16
        },
        {
          "id": "iron",
          "item": "minecraft:iron_ingot",
          "quantity": 32
        },
        {
          "id": "starter_sword",
          "nbt": "{id:'minecraft:iron_sword',count:1,components:{'minecraft:custom_name':'{\"text\":\"新手铁剑\"}'}}",
          "quantity": 1
        }
      ]
    },

    {
      "id": "random_material",
      "display": "&b随机材料礼包",
      "description": [
        "&7随机获得其中一种材料"
      ],
      "icon": "minecraft:barrel",
      "mode": "random_one",

      "items": [
        {
          "id": "coal",
          "item": "minecraft:coal",
          "quantity": 128
        },
        {
          "id": "iron",
          "item": "minecraft:iron_ingot",
          "quantity": 64
        },
        {
          "id": "gold",
          "item": "minecraft:gold_ingot",
          "quantity": 32
        }
      ]
    }
  ]
}
```

字段规则：

| 字段 | 说明 |
| --- | --- |
| `id` | 礼包唯一 ID，发布后不要修改 |
| `display` | 礼包名称，支持颜色和占位符 |
| `description` | 礼包说明 |
| `icon` | GUI 中显示的物品 ID |
| `mode` | `all` 或 `random_one` |
| `items` | 礼包内容列表 |
| `item` | 普通物品 ID |
| `nbt` | 完整 ItemStack SNBT |
| `quantity` | 该物品实际发放数量 |
| `random_strategy` | 默认 `uniform`，按条目均匀随机 |
| `max_quantity_per_entry` | 防止配置错误导致一次发放过多物品 |

`quantity` 业务上不受 64 限制。比如配置 1000 个钻石，服务端自动拆分为 64、64、64……的多个堆叠。

但不建议真正取消所有安全上限。应当允许服主调整上限，同时保留服务器级最大值，避免误配置造成卡服或超大存档。

完整 SNBT 仍然复用现有的 32 KiB 限制和组件校验规则。由于现有物品解析器要求单个 ItemStack 数量不超过 64，礼包应解析“单个物品原型”，再由 `quantity` 进行批量发放。

**三、两种打开模式**

`all` 模式：

```text
一次打开获得 items 中的所有物品及其数量。
```

例如：

```text
面包 16
铁锭 32
新手铁剑 1
```

`random_one` 模式：

```text
一次打开只随机选择一个条目，并发放该条目的完整 quantity。
```

例如抽中煤炭后，玩家获得 128 个煤炭，不会再重复抽取其他条目。

建议第一版使用均匀随机：

```text
每个 items 条目的概率相同
```

后续可以增加：

```json
{
  "id": "diamond",
  "item": "minecraft:diamond",
  "quantity": 16,
  "weight": 5
}
```

但随机结果必须在服务器端保存，不能只在 GUI 点击时临时计算。否则玩家在断线、重启或重复点击时可能重新获得不同结果。

**四、发放流程**

礼包来源分为三种：

1. 管理员命令发放。
2. 奖励模块发放。
3. 商店购买后发放。

统一流程：

```text
创建礼包实例
    ↓
保存 package_id、版本和内容快照
    ↓
玩家打开礼包
    ↓
服务端锁定礼包实例
    ↓
all：生成全部物品
random_one：只生成一个随机物品
    ↓
保存已选择结果
    ↓
检查背包空间
    ↓
可以完全放入：直接投递
无法完全放入：进入奖励箱投递队列
    ↓
全部投递完成
    ↓
标记礼包实例为 OPENED
```

必须保存随机选择结果和投递状态。例如：

```text
PENDING
OPENING
DELIVERING
WAITING_INBOX
OPENED
BLOCKED
```

服务器崩溃后：

- 如果尚未选择随机结果，重新开始选择；
- 如果已经选择，必须使用原来的结果，不能重新随机；
- 如果部分物品已经进入投递流程，不能直接重复发放；
- 如果投递结果不明确，进入管理员可审计的阻塞状态。

建议新增通用的 `PackageDeliveryBatch`，而不是让一个礼包只对应一条物品账本记录。因为一个礼包可能包含多个物品堆叠，而当前奖励账本的一条物品记录只保存一个 ItemStack 快照。

**五、命令设计**

玩家命令：

```text
/omnitools packages
```

打开自己的礼包 GUI。

```text
/omnitools package open
```

打开礼包列表。

```text
/omnitools package open <instance_id>
```

打开指定礼包实例。

管理员命令：

```text
/omnitools package give <player> <package_id>
/omnitools package give <player> <package_id> <amount>
/omnitools package inspect <player>
/omnitools package remove <player> <instance_id>
```

建议新增权限动作：

```text
package.open      PLAYER
package.give      ADMIN
package.inspect    ADMIN
package.remove     ADMIN
```

这些动作应加入 [`CommandAction.java`](D:/mod/qiandao/src/main/java/dev/modmind/omnitools/permissions/CommandAction.java:8)，并允许在权限配置文件中覆盖。

管理员执行：

```text
/omnitools reload packages
```

只重载礼包配置，不影响已经发放的礼包实例。

**六、GUI 设计**

建议新增独立礼包 GUI，而不是直接修改现有奖励箱的物品列表逻辑。

礼包列表界面：

- 54 格箱子界面；
- 每个礼包实例使用配置中的 `icon`；
- 名称显示礼包名称；
- Lore 显示礼包模式、来源、发放时间；
- `all` 显示“打开后获得全部物品”；
- `random_one` 显示“打开后随机获得一件物品”；
- 点击礼包后进入确认界面；
- 关闭按钮固定在右上角；
- 翻页按钮使用现有 GUI 导航组件。

确认界面：

```text
[礼包图标]
新手礼包

打开后获得：
面包 x16
铁锭 x32
新手铁剑 x1

[确认打开] [取消]
```

随机礼包的确认界面不显示具体结果，只显示：

```text
随机获得以下物品中的一种
```

如果背包空间不足：

```text
礼包已经打开，无法直接放入背包的物品已转入奖励箱。
```

建议在现有 [`RewardInboxScreenHandler.java`](D:/mod/qiandao/src/main/java/dev/modmind/omnitools/RewardInboxScreenHandler.java:29) 中增加“礼包入口按钮”，但不要直接把礼包伪装成普通物品奖励。

**七、与奖励模块联动**

新增奖励类型：

```text
package
```

奖励配置：

```json
{
  "id": "starter_package",
  "type": "package",
  "package": "starter"
}
```

这样签到、在线奖励、成就、CDK 都可以使用：

```json
"rewards": [
  {
    "id": "daily_package",
    "type": "package",
    "package": "starter"
  }
]
```

需要修改：

- `RewardType`
- `RewardDefinition`
- `RewardDefinition.parse`
- `RewardGrantService`
- `ConfigValidator`
- `RewardClaimLedger`
- 统一奖励 Schema
- 奖励文档和示例

奖励发放时不直接给予物品，而是创建一个礼包实例。这样仍然可以使用现有的奖励事件 ID 保证幂等：

```text
achievement:<player_uuid>:<achievement_id>:starter_package
```

同一个成就重复检查不会重复创建礼包。

**八、与商店模块联动**

商店可以增加商品类型：

```json
{
  "index": 5,
  "type": "package",
  "package": "random_material",
  "price": 100
}
```

购买流程必须是事务式的：

```text
确认礼包存在
    ↓
确认玩家余额足够
    ↓
生成购买事务 ID
    ↓
扣除货币
    ↓
创建礼包实例
    ↓
标记购买完成
```

如果服务器在扣钱和创建礼包之间崩溃，必须通过购买账本恢复，不能出现“扣钱但没有礼包”的情况。

第一阶段建议只支持虚拟礼包。以后如果需要玩家之间交易，再增加带签名 NBT 的实体礼包物品，但那会引入复制、丢失、跨服和伪造风险。

**九、必须加入的安全限制**

建议至少加入以下限制：

- 礼包 ID 不允许重复；
- 礼包内容最多 256 个条目；
- 单个礼包最大总数量；
- 每个玩家最大未打开礼包数；
- SNBT 最大 32 KiB；
- 禁止礼包嵌套礼包，避免递归展开；
- 禁止客户端提交任意 `package_id` 或物品内容；
- GUI 点击时重新验证礼包实例所有权；
- 随机结果由服务端生成并保存；
- 已打开礼包不能再次打开；
- 配置重载不能改变已发放礼包快照；
- 所有管理员发放、删除、人工结算都写入审计日志。

**十、建议开发顺序**

1. **第一阶段：礼包配置和数据模型**

   新增 `packages/config.json`、配置解析、Schema、礼包实例 SavedData、数量拆分和 SNBT 校验。

2. **第二阶段：礼包服务和可靠投递**

   实现 `all`、`random_one`、随机结果持久化、背包不足进入奖励箱、崩溃恢复和幂等处理。

3. **第三阶段：命令和 GUI**

   增加玩家礼包列表、确认打开界面、管理员发放命令、权限节点和翻页。

4. **第四阶段：奖励模块联动**

   增加 `RewardType.PACKAGE`，使签到、成就、在线奖励和 CDK 可以发礼包。

5. **第五阶段：商店联动**

   增加礼包商品类型，加入货币扣除和礼包创建的事务账本。

6. **第六阶段：文档和验收**

   补充配置示例、NBT 教程、随机礼包说明、权限表、故障恢复说明和完整测试案例。

**十一、重点验收案例**

至少要验证：

- `all` 模式能完整发放所有物品；
- `random_one` 每次只选择一个条目；
- 随机条目数量大于 64 时能自动拆堆；
- NBT 物品的名称、Lore、附魔和组件保持不变；
- 背包不足时礼包不会丢失；
- 重复点击不会重复发放；
- 玩家断线、服务器重启后不会重新随机；
- 修改配置不会改变旧礼包内容；
- 奖励发放重复检查不会重复创建礼包；
- 商店扣款失败不会创建礼包；
- 商店扣款成功但服务器中断时可以恢复；
- 玩家不能打开其他玩家的礼包实例。

这套方案与当前 OmniTools 的模块化配置、统一奖励和奖励账本设计兼容，同时保留了以后接入商店、成就、CDK 和其他奖励来源的扩展空间。本轮仅进行了只读结构审查，没有修改、构建或测试项目。

---

## Development request 2026/8/28 00:02:35

将礼物模块的相关文档补充完整，按照之前的标准

---

## Development request 2026/8/28 11:56:23

可以。当前礼包模块已经有可用骨架：独立配置、虚拟礼包实例、`all/random_one` 两种模式、数量拆堆、NBT 物品、玩家 GUI、管理员发放命令，以及 `type: "package"` 奖励联动。核心文件包括 [`PackageConfig.java`](D:/mod/qiandao/src/main/java/dev/modmind/omnitools/packages/PackageConfig.java:1)、[`PackageService.java`](D:/mod/qiandao/src/main/java/dev/modmind/omnitools/packages/PackageService.java:1) 和 [`PackageScreenHandler.java`](D:/mod/qiandao/src/main/java/dev/modmind/omnitools/PackageScreenHandler.java:1)。

但它目前仍属于“基础可用版”。最需要优先修复的是：礼包奖励在 `APPLYING` 状态恢复时可能再次创建礼包，存在重复发放风险；[`RewardGrantService.java`](D:/mod/qiandao/src/main/java/dev/modmind/omnitools/reward/RewardGrantService.java:170) 的礼包恢复逻辑必须改为幂等查询，而不是无条件创建。

## 一、目标架构

礼包分为两层：

```text
PackageDefinition
    配置中的礼包模板，只负责定义内容

PackageInstance
    玩家实际拥有的礼包，保存完整快照

PackageDeliveryBatch
    一次开礼包产生的投递事务，保存每个物品的投递状态
```

继续使用虚拟礼包，不生成实体物品。这样可以避免礼包被丢弃、复制、伪造或通过容器漏洞转移。

## 二、配置设计

保持当前 `format_version: 1` 兼容，后续扩展时升级为 `2`。

当前稳定配置：

```json
{
  "format_version": 1,
  "settings": {
    "max_packages_per_player": 256,
    "max_quantity_per_entry": 2304,
    "max_total_quantity": 589824,
    "delivery_policy": "inventory_then_inbox",
    "random_strategy": "uniform"
  },
  "packages": [
    {
      "id": "starter",
      "display": "&a新手礼包",
      "description": ["&7打开后获得全部新手物资"],
      "icon": "minecraft:chest",
      "mode": "all",
      "version": 1,
      "items": [
        {
          "id": "bread",
          "item": "minecraft:bread",
          "count": 1,
          "quantity": 16
        },
        {
          "id": "named_sword",
          "nbt": "{id:'minecraft:iron_sword',count:1}",
          "quantity": 1
        }
      ]
    }
  ]
}
```

建议字段规则：

- `id`：稳定礼包 ID，不能随意复用。
- `version`：礼包定义版本，只用于审计和迁移。
- `mode`：`all` 或 `random_one`。
- `quantity`：业务数量，可以大于 64，服务端自动拆堆。
- `item`、`count`、`components` 与 `nbt` 继续二选一。
- 已发放礼包必须保存物品快照，配置重载不能修改旧礼包。
- 禁止礼包嵌套礼包，避免递归发放和资源爆炸。

后续 `format_version: 2` 可增加：

```json
{
  "random": {
    "strategy": "weighted",
    "pity": {
      "enabled": true,
      "after": 10,
      "guaranteed_items": ["rare_item"]
    }
  },
  "open_cooldown_ticks": 20,
  "expires_after_days": 7
}
```

第一版不建议同时实现太多随机规则，先稳定均匀随机，再增加权重和保底。

## 三、实例数据模型

当前 [`PackageInstance`](D:/mod/qiandao/src/main/java/dev/modmind/omnitools/packages/PackageInstance.java:1) 还需要补充：

```text
instance_id
owner_id
package_id
package_version
display_name
icon_id
mode
item_snapshots
quantities
source_event
source_reward_id
grant_key
status
selected_item_index
created_at
opened_at
```

其中：

```text
grant_key = source_event + "#" + source_reward_id
```

礼包奖励必须通过 `grant_key` 去重。

状态建议为：

```text
PENDING
OPENING
DELIVERING
WAITING_INBOX
OPENED
BLOCKED
EXPIRED
```

## 四、必须修复的幂等问题

礼包奖励流程应改成：

```text
奖励账本写入 APPLYING
        ↓
根据 grant_key 查找已有礼包实例
        ↓
已有实例：直接复用
没有实例：创建实例
        ↓
礼包实例保存成功
        ↓
奖励账本写入 GRANTED
```

服务器在以下时机崩溃时，都不能重复创建：

- 创建礼包后、账本标记前；
- 随机结果保存后、物品投递前；
- 背包投递过程中；
- 奖励重试时。

`PackageData` 应增加：

```java
findByGrantKey(...)
createIfAbsent(...)
```

而不是只使用随机 UUID 创建。

## 五、投递事务设计

当前 [`PackageDeliveryBatch`](D:/mod/qiandao/src/main/java/dev/modmind/omnitools/packages/PackageDeliveryBatch.java:1) 已存在，但还没有承担完整持久化职责。建议改为持久化事务：

```text
batch_id
package_instance_id
stacks[
  {
    stack_id,
    item_snapshot,
    quantity,
    status
  }
]
cursor
status
```

每个物品堆状态：

```text
PENDING
DELIVERING
DELIVERED
WAITING_INBOX
BLOCKED
```

投递流程：

```text
选择礼包
  ↓
锁定实例
  ↓
random_one：先保存随机结果
  ↓
生成拆分后的物品堆
  ↓
逐堆投递
  ↓
背包不足的堆进入 WAITING_INBOX
  ↓
全部完成后标记 OPENED
```

NBT 编解码必须统一使用带注册表的 `RegistryOps`。当前 [`PackageData.java`](D:/mod/qiandao/src/main/java/dev/modmind/omnitools/packages/PackageData.java:1) 使用裸 `NbtOps`，复杂组件或模组物品可能无法正确恢复，应与 [`ItemStackConfigParser.java`](D:/mod/qiandao/src/main/java/dev/modmind/omnitools/config/ItemStackConfigParser.java:28) 使用同一套 Codec 路径。

损坏存档时不要静默丢弃实例。当前反序列化存在忽略异常的行为，应改为：

- 记录完整日志；
- 将实例标记为 `BLOCKED`；
- 保留原始数据；
- 提供管理员检查和人工处理命令。

## 六、GUI 设计

当前 GUI 只有礼包列表，点击后直接打开，建议拆成三层。

### 礼包列表

- 54 格布局；
- 支持分页；
- 显示礼包图标、名称、描述、模式和来源；
- 显示“待投递”“阻塞”“即将过期”等状态；
- 增加奖励箱入口；
- 关闭按钮固定位置。

### 礼包预览

`all` 模式显示全部内容：

```text
面包 x16
铁锭 x32
新手剑 x1
```

`random_one` 模式显示：

```text
从以下物品中随机获得一种
```

预览不能产生随机结果。

### 确认界面

增加：

```text
确认打开
取消
```

随机礼包应在点击确认后由服务端选择并保存结果。

当前礼包名称使用 `Component.literal`，无法正确处理颜色和占位符。应统一调用 `TextTemplateRenderer`，使 `display`、`description` 和 Lore 支持现有文本占位符。

## 七、命令与权限

保留现有命令，并补齐建议：

```text
/omnitools packages
/omnitools package open
/omnitools package open <instance_id>
/omnitools package preview <instance_id>

/omnitools package give <player> <package_id> [amount]
/omnitools package inspect <player>
/omnitools package inspect <player> <instance_id>
/omnitools package retry <player> <instance_id>
/omnitools package resolve <player> <instance_id> grant|fail
/omnitools package remove <player> <instance_id>
```

新增权限动作：

```text
package.open       PLAYER
package.preview    PLAYER
package.give       ADMIN
package.inspect    ADMIN
package.retry      ADMIN
package.resolve    ADMIN
package.remove     ADMIN
```

管理员批量发放不能循环创建后遇到上限才失败，应先预检查容量，再一次性提交，避免只发出一部分。

## 八、奖励模块联动

现有 `RewardType.PACKAGE` 已经接入，配置形式为：

```json
{
  "id": "daily_package",
  "type": "package",
  "package": "starter"
}
```

需要确保以下规则：

- 礼包模块关闭时，奖励配置直接拒绝重载；
- 奖励账本用 `eventId + rewardId` 保证幂等；
- 已创建礼包后修改配置，不改变旧礼包；
- 礼包创建失败时进入 `BLOCKED`，不能简单标记成功；
- 奖励重试只能查找并恢复原礼包实例。

签到、在线奖励、成就和 CDK 都可以继续使用礼包奖励。

## 九、商店联动

商店礼包商品建议单独设计交易记录：

```json
{
  "index": 5,
  "type": "package",
  "package": "starter",
  "price": 100
}
```

购买事务：

```text
检查礼包定义
  ↓
检查余额
  ↓
创建 purchase_id
  ↓
扣除货币
  ↓
创建礼包实例
  ↓
标记购买完成
```

必须防止：

- 扣钱但没有礼包；
- 礼包创建成功但扣款失败；
- 断服后重复购买；
- 点击按钮重复购买。

商店联动应放在礼包核心稳定后实现。

## 十、安全限制

建议保留并补充：

- 每个玩家未打开礼包数量上限；
- 管理员单次发放数量上限；
- 单条数量和礼包总数量上限；
- 最大礼包定义数和最大条目数；
- SNBT 大小限制；
- GUI 点击冷却；
- 每个实例只能由所有者打开；
- 已打开礼包不可重复打开；
- 随机结果只能由服务端生成；
- 删除和人工结算必须写审计日志；
- 不允许客户端提交物品内容或随机结果。

## 十一、占位符和命令菜单联动

建议新增：

```text
%omnitools:packages_pending%
%omnitools:packages_waiting_inbox%
%omnitools:packages_opened%
%omnitools:package_last_opened%
%omnitools:package_last_item%
```

命令菜单增加：

```text
open_package_list
open_reward_inbox
```

侧边栏可显示：

```text
未打开礼包：%omnitools:packages_pending%
待投递物品：%omnitools:packages_waiting_inbox%
```

## 十二、开发阶段

1. **P0：修复可靠性**
   - `grant_key` 幂等；
   - 修复 `APPLYING` 恢复重复创建；
   - RegistryOps 编解码；
   - 持久化投递批次；
   - 增加礼包模块测试。

2. **P1：完善基础体验**
   - 礼包列表分页；
   - 预览和确认界面；
   - 颜色、Lore、占位符；
   - 奖励箱入口；
   - 管理员检查、重试和审计。

3. **P1：完善命令和权限**
   - 指定实例打开；
   - 批量发放事务；
   - 补充权限节点；
   - 增加命令建议和错误提示。

4. **P2：奖励与商店**
   - 稳定签到、成就、在线奖励和 CDK 联动；
   - 增加商店礼包商品；
   - 实现购买事务恢复。

5. **P2：高级玩法**
   - 权重随机；
   - 保底机制；
   - 重复物品转货币；
   - 限时礼包；
   - 礼包钥匙；
   - 每日开启次数；
   - 赛季礼包。

6. **P3：跨模块玩法**
   - 任务系统；
   - 赛季排行榜奖励；
   - 世界活动礼包；
   - 公会或团队礼包。

## 十三、验收标准

至少验证：

- 全部模式完整发放所有物品；
- 随机模式只发放一个条目；
- 随机结果重启后不改变；
- 数量超过 64 时正确拆堆；
- NBT、组件、Lore 和自定义名称保持不变；
- 背包不足时进入奖励箱；
- 重复点击不会重复发放；
- 奖励重试不会重复创建礼包；
- 配置重载不会改变旧实例；
- 删除不存在的实例不会误删其他礼包；
- 商店扣款和礼包创建具备事务恢复；
- 损坏数据不会静默丢失；
- 玩家无法打开其他玩家的实例。

总体建议：先把当前礼包模块提升为“可靠的虚拟资产系统”，再加入权重、保底、商店和赛季玩法。最优先处理的是奖励恢复幂等、物品持久化和投递事务，这三项直接关系到玩家物品和经济数据是否会重复或丢失。本轮仅进行了只读分析，没有修改、构建或测试项目。

---

## Development request 2026/8/28 15:15:13

可以。当前礼包模块已经有可用骨架：独立配置、虚拟礼包实例、`all/random_one` 两种模式、数量拆堆、NBT 物品、玩家 GUI、管理员发放命令，以及 `type: "package"` 奖励联动。核心文件包括 [`PackageConfig.java`](D:/mod/qiandao/src/main/java/dev/modmind/omnitools/packages/PackageConfig.java:1)、[`PackageService.java`](D:/mod/qiandao/src/main/java/dev/modmind/omnitools/packages/PackageService.java:1) 和 [`PackageScreenHandler.java`](D:/mod/qiandao/src/main/java/dev/modmind/omnitools/PackageScreenHandler.java:1)。

但它目前仍属于“基础可用版”。最需要优先修复的是：礼包奖励在 `APPLYING` 状态恢复时可能再次创建礼包，存在重复发放风险；[`RewardGrantService.java`](D:/mod/qiandao/src/main/java/dev/modmind/omnitools/reward/RewardGrantService.java:170) 的礼包恢复逻辑必须改为幂等查询，而不是无条件创建。

## 一、目标架构

礼包分为两层：

```text
PackageDefinition
    配置中的礼包模板，只负责定义内容

PackageInstance
    玩家实际拥有的礼包，保存完整快照

PackageDeliveryBatch
    一次开礼包产生的投递事务，保存每个物品的投递状态
```

继续使用虚拟礼包，不生成实体物品。这样可以避免礼包被丢弃、复制、伪造或通过容器漏洞转移。

## 二、配置设计

保持当前 `format_version: 1` 兼容，后续扩展时升级为 `2`。

当前稳定配置：

```json
{
  "format_version": 1,
  "settings": {
    "max_packages_per_player": 256,
    "max_quantity_per_entry": 2304,
    "max_total_quantity": 589824,
    "delivery_policy": "inventory_then_inbox",
    "random_strategy": "uniform"
  },
  "packages": [
    {
      "id": "starter",
      "display": "&a新手礼包",
      "description": ["&7打开后获得全部新手物资"],
      "icon": "minecraft:chest",
      "mode": "all",
      "version": 1,
      "items": [
        {
          "id": "bread",
          "item": "minecraft:bread",
          "count": 1,
          "quantity": 16
        },
        {
          "id": "named_sword",
          "nbt": "{id:'minecraft:iron_sword',count:1}",
          "quantity": 1
        }
      ]
    }
  ]
}
```

建议字段规则：

- `id`：稳定礼包 ID，不能随意复用。
- `version`：礼包定义版本，只用于审计和迁移。
- `mode`：`all` 或 `random_one`。
- `quantity`：业务数量，可以大于 64，服务端自动拆堆。
- `item`、`count`、`components` 与 `nbt` 继续二选一。
- 已发放礼包必须保存物品快照，配置重载不能修改旧礼包。
- 禁止礼包嵌套礼包，避免递归发放和资源爆炸。

后续 `format_version: 2` 可增加：

```json
{
  "random": {
    "strategy": "weighted",
    "pity": {
      "enabled": true,
      "after": 10,
      "guaranteed_items": ["rare_item"]
    }
  },
  "open_cooldown_ticks": 20,
  "expires_after_days": 7
}
```

第一版不建议同时实现太多随机规则，先稳定均匀随机，再增加权重和保底。

## 三、实例数据模型

当前 [`PackageInstance`](D:/mod/qiandao/src/main/java/dev/modmind/omnitools/packages/PackageInstance.java:1) 还需要补充：

```text
instance_id
owner_id
package_id
package_version
display_name
icon_id
mode
item_snapshots
quantities
source_event
source_reward_id
grant_key
status
selected_item_index
created_at
opened_at
```

其中：

```text
grant_key = source_event + "#" + source_reward_id
```

礼包奖励必须通过 `grant_key` 去重。

状态建议为：

```text
PENDING
OPENING
DELIVERING
WAITING_INBOX
OPENED
BLOCKED
EXPIRED
```

## 四、必须修复的幂等问题

礼包奖励流程应改成：

```text
奖励账本写入 APPLYING
        ↓
根据 grant_key 查找已有礼包实例
        ↓
已有实例：直接复用
没有实例：创建实例
        ↓
礼包实例保存成功
        ↓
奖励账本写入 GRANTED
```

服务器在以下时机崩溃时，都不能重复创建：

- 创建礼包后、账本标记前；
- 随机结果保存后、物品投递前；
- 背包投递过程中；
- 奖励重试时。

`PackageData` 应增加：

```java
findByGrantKey(...)
createIfAbsent(...)
```

而不是只使用随机 UUID 创建。

## 五、投递事务设计

当前 [`PackageDeliveryBatch`](D:/mod/qiandao/src/main/java/dev/modmind/omnitools/packages/PackageDeliveryBatch.java:1) 已存在，但还没有承担完整持久化职责。建议改为持久化事务：

```text
batch_id
package_instance_id
stacks[
  {
    stack_id,
    item_snapshot,
    quantity,
    status
  }
]
cursor
status
```

每个物品堆状态：

```text
PENDING
DELIVERING
DELIVERED
WAITING_INBOX
BLOCKED
```

投递流程：

```text
选择礼包
  ↓
锁定实例
  ↓
random_one：先保存随机结果
  ↓
生成拆分后的物品堆
  ↓
逐堆投递
  ↓
背包不足的堆进入 WAITING_INBOX
  ↓
全部完成后标记 OPENED
```

NBT 编解码必须统一使用带注册表的 `RegistryOps`。当前 [`PackageData.java`](D:/mod/qiandao/src/main/java/dev/modmind/omnitools/packages/PackageData.java:1) 使用裸 `NbtOps`，复杂组件或模组物品可能无法正确恢复，应与 [`ItemStackConfigParser.java`](D:/mod/qiandao/src/main/java/dev/modmind/omnitools/config/ItemStackConfigParser.java:28) 使用同一套 Codec 路径。

损坏存档时不要静默丢弃实例。当前反序列化存在忽略异常的行为，应改为：

- 记录完整日志；
- 将实例标记为 `BLOCKED`；
- 保留原始数据；
- 提供管理员检查和人工处理命令。

## 六、GUI 设计

当前 GUI 只有礼包列表，点击后直接打开，建议拆成三层。

### 礼包列表

- 54 格布局；
- 支持分页；
- 显示礼包图标、名称、描述、模式和来源；
- 显示“待投递”“阻塞”“即将过期”等状态；
- 增加奖励箱入口；
- 关闭按钮固定位置。

### 礼包预览

`all` 模式显示全部内容：

```text
面包 x16
铁锭 x32
新手剑 x1
```

`random_one` 模式显示：

```text
从以下物品中随机获得一种
```

预览不能产生随机结果。

### 确认界面

增加：

```text
确认打开
取消
```

随机礼包应在点击确认后由服务端选择并保存结果。

当前礼包名称使用 `Component.literal`，无法正确处理颜色和占位符。应统一调用 `TextTemplateRenderer`，使 `display`、`description` 和 Lore 支持现有文本占位符。

## 七、命令与权限

保留现有命令，并补齐建议：

```text
/omnitools packages
/omnitools package open
/omnitools package open <instance_id>
/omnitools package preview <instance_id>

/omnitools package give <player> <package_id> [amount]
/omnitools package inspect <player>
/omnitools package inspect <player> <instance_id>
/omnitools package retry <player> <instance_id>
/omnitools package resolve <player> <instance_id> grant|fail
/omnitools package remove <player> <instance_id>
```

新增权限动作：

```text
package.open       PLAYER
package.preview    PLAYER
package.give       ADMIN
package.inspect    ADMIN
package.retry      ADMIN
package.resolve    ADMIN
package.remove     ADMIN
```

管理员批量发放不能循环创建后遇到上限才失败，应先预检查容量，再一次性提交，避免只发出一部分。

## 八、奖励模块联动

现有 `RewardType.PACKAGE` 已经接入，配置形式为：

```json
{
  "id": "daily_package",
  "type": "package",
  "package": "starter"
}
```

需要确保以下规则：

- 礼包模块关闭时，奖励配置直接拒绝重载；
- 奖励账本用 `eventId + rewardId` 保证幂等；
- 已创建礼包后修改配置，不改变旧礼包；
- 礼包创建失败时进入 `BLOCKED`，不能简单标记成功；
- 奖励重试只能查找并恢复原礼包实例。

签到、在线奖励、成就和 CDK 都可以继续使用礼包奖励。

## 九、商店联动

商店礼包商品建议单独设计交易记录：

```json
{
  "index": 5,
  "type": "package",
  "package": "starter",
  "price": 100
}
```

购买事务：

```text
检查礼包定义
  ↓
检查余额
  ↓
创建 purchase_id
  ↓
扣除货币
  ↓
创建礼包实例
  ↓
标记购买完成
```

必须防止：

- 扣钱但没有礼包；
- 礼包创建成功但扣款失败；
- 断服后重复购买；
- 点击按钮重复购买。

商店联动应放在礼包核心稳定后实现。

## 十、安全限制

建议保留并补充：

- 每个玩家未打开礼包数量上限；
- 管理员单次发放数量上限；
- 单条数量和礼包总数量上限；
- 最大礼包定义数和最大条目数；
- SNBT 大小限制；
- GUI 点击冷却；
- 每个实例只能由所有者打开；
- 已打开礼包不可重复打开；
- 随机结果只能由服务端生成；
- 删除和人工结算必须写审计日志；
- 不允许客户端提交物品内容或随机结果。

## 十一、占位符和命令菜单联动

建议新增：

```text
%omnitools:packages_pending%
%omnitools:packages_waiting_inbox%
%omnitools:packages_opened%
%omnitools:package_last_opened%
%omnitools:package_last_item%
```

命令菜单增加：

```text
open_package_list
open_reward_inbox
```

侧边栏可显示：

```text
未打开礼包：%omnitools:packages_pending%
待投递物品：%omnitools:packages_waiting_inbox%
```

## 十二、开发阶段

1. **P0：修复可靠性**
   - `grant_key` 幂等；
   - 修复 `APPLYING` 恢复重复创建；
   - RegistryOps 编解码；
   - 持久化投递批次；
   - 增加礼包模块测试。

2. **P1：完善基础体验**
   - 礼包列表分页；
   - 预览和确认界面；
   - 颜色、Lore、占位符；
   - 奖励箱入口；
   - 管理员检查、重试和审计。

3. **P1：完善命令和权限**
   - 指定实例打开；
   - 批量发放事务；
   - 补充权限节点；
   - 增加命令建议和错误提示。

4. **P2：奖励与商店**
   - 稳定签到、成就、在线奖励和 CDK 联动；
   - 增加商店礼包商品；
   - 实现购买事务恢复。

5. **P2：高级玩法**
   - 权重随机；
   - 保底机制；
   - 重复物品转货币；
   - 限时礼包；
   - 礼包钥匙；
   - 每日开启次数；
   - 赛季礼包。

6. **P3：跨模块玩法**
   - 任务系统；
   - 赛季排行榜奖励；
   - 世界活动礼包；
   - 公会或团队礼包。

## 十三、验收标准

至少验证：

- 全部模式完整发放所有物品；
- 随机模式只发放一个条目；
- 随机结果重启后不改变；
- 数量超过 64 时正确拆堆；
- NBT、组件、Lore 和自定义名称保持不变；
- 背包不足时进入奖励箱；
- 重复点击不会重复发放；
- 奖励重试不会重复创建礼包；
- 配置重载不会改变旧实例；
- 删除不存在的实例不会误删其他礼包；
- 商店扣款和礼包创建具备事务恢复；
- 损坏数据不会静默丢失；
- 玩家无法打开其他玩家的实例。

总体建议：先把当前礼包模块提升为“可靠的虚拟资产系统”，再加入权重、保底、商店和赛季玩法。最优先处理的是奖励恢复幂等、物品持久化和投递事务，这三项直接关系到玩家物品和经济数据是否会重复或丢失。本轮仅进行了只读分析，没有修改、构建或测试项目。

---

## Development request 2026/8/28 15:17:15

继续

---

## Development request 2026/8/28 15:35:49

可以。当前礼包模块已经有可用骨架：独立配置、虚拟礼包实例、`all/random_one` 两种模式、数量拆堆、NBT 物品、玩家 GUI、管理员发放命令，以及 `type: "package"` 奖励联动。核心文件包括 [`PackageConfig.java`](D:/mod/qiandao/src/main/java/dev/modmind/omnitools/packages/PackageConfig.java:1)、[`PackageService.java`](D:/mod/qiandao/src/main/java/dev/modmind/omnitools/packages/PackageService.java:1) 和 [`PackageScreenHandler.java`](D:/mod/qiandao/src/main/java/dev/modmind/omnitools/PackageScreenHandler.java:1)。

但它目前仍属于“基础可用版”。最需要优先修复的是：礼包奖励在 `APPLYING` 状态恢复时可能再次创建礼包，存在重复发放风险；[`RewardGrantService.java`](D:/mod/qiandao/src/main/java/dev/modmind/omnitools/reward/RewardGrantService.java:170) 的礼包恢复逻辑必须改为幂等查询，而不是无条件创建。

## 一、目标架构

礼包分为两层：

```text
PackageDefinition
    配置中的礼包模板，只负责定义内容

PackageInstance
    玩家实际拥有的礼包，保存完整快照

PackageDeliveryBatch
    一次开礼包产生的投递事务，保存每个物品的投递状态
```

继续使用虚拟礼包，不生成实体物品。这样可以避免礼包被丢弃、复制、伪造或通过容器漏洞转移。

## 二、配置设计

保持当前 `format_version: 1` 兼容，后续扩展时升级为 `2`。

当前稳定配置：

```json
{
  "format_version": 1,
  "settings": {
    "max_packages_per_player": 256,
    "max_quantity_per_entry": 2304,
    "max_total_quantity": 589824,
    "delivery_policy": "inventory_then_inbox",
    "random_strategy": "uniform"
  },
  "packages": [
    {
      "id": "starter",
      "display": "&a新手礼包",
      "description": ["&7打开后获得全部新手物资"],
      "icon": "minecraft:chest",
      "mode": "all",
      "version": 1,
      "items": [
        {
          "id": "bread",
          "item": "minecraft:bread",
          "count": 1,
          "quantity": 16
        },
        {
          "id": "named_sword",
          "nbt": "{id:'minecraft:iron_sword',count:1}",
          "quantity": 1
        }
      ]
    }
  ]
}
```

建议字段规则：

- `id`：稳定礼包 ID，不能随意复用。
- `version`：礼包定义版本，只用于审计和迁移。
- `mode`：`all` 或 `random_one`。
- `quantity`：业务数量，可以大于 64，服务端自动拆堆。
- `item`、`count`、`components` 与 `nbt` 继续二选一。
- 已发放礼包必须保存物品快照，配置重载不能修改旧礼包。
- 禁止礼包嵌套礼包，避免递归发放和资源爆炸。

后续 `format_version: 2` 可增加：

```json
{
  "random": {
    "strategy": "weighted",
    "pity": {
      "enabled": true,
      "after": 10,
      "guaranteed_items": ["rare_item"]
    }
  },
  "open_cooldown_ticks": 20,
  "expires_after_days": 7
}
```

第一版不建议同时实现太多随机规则，先稳定均匀随机，再增加权重和保底。

## 三、实例数据模型

当前 [`PackageInstance`](D:/mod/qiandao/src/main/java/dev/modmind/omnitools/packages/PackageInstance.java:1) 还需要补充：

```text
instance_id
owner_id
package_id
package_version
display_name
icon_id
mode
item_snapshots
quantities
source_event
source_reward_id
grant_key
status
selected_item_index
created_at
opened_at
```

其中：

```text
grant_key = source_event + "#" + source_reward_id
```

礼包奖励必须通过 `grant_key` 去重。

状态建议为：

```text
PENDING
OPENING
DELIVERING
WAITING_INBOX
OPENED
BLOCKED
EXPIRED
```

## 四、必须修复的幂等问题

礼包奖励流程应改成：

```text
奖励账本写入 APPLYING
        ↓
根据 grant_key 查找已有礼包实例
        ↓
已有实例：直接复用
没有实例：创建实例
        ↓
礼包实例保存成功
        ↓
奖励账本写入 GRANTED
```

服务器在以下时机崩溃时，都不能重复创建：

- 创建礼包后、账本标记前；
- 随机结果保存后、物品投递前；
- 背包投递过程中；
- 奖励重试时。

`PackageData` 应增加：

```java
findByGrantKey(...)
createIfAbsent(...)
```

而不是只使用随机 UUID 创建。

## 五、投递事务设计

当前 [`PackageDeliveryBatch`](D:/mod/qiandao/src/main/java/dev/modmind/omnitools/packages/PackageDeliveryBatch.java:1) 已存在，但还没有承担完整持久化职责。建议改为持久化事务：

```text
batch_id
package_instance_id
stacks[
  {
    stack_id,
    item_snapshot,
    quantity,
    status
  }
]
cursor
status
```

每个物品堆状态：

```text
PENDING
DELIVERING
DELIVERED
WAITING_INBOX
BLOCKED
```

投递流程：

```text
选择礼包
  ↓
锁定实例
  ↓
random_one：先保存随机结果
  ↓
生成拆分后的物品堆
  ↓
逐堆投递
  ↓
背包不足的堆进入 WAITING_INBOX
  ↓
全部完成后标记 OPENED
```

NBT 编解码必须统一使用带注册表的 `RegistryOps`。当前 [`PackageData.java`](D:/mod/qiandao/src/main/java/dev/modmind/omnitools/packages/PackageData.java:1) 使用裸 `NbtOps`，复杂组件或模组物品可能无法正确恢复，应与 [`ItemStackConfigParser.java`](D:/mod/qiandao/src/main/java/dev/modmind/omnitools/config/ItemStackConfigParser.java:28) 使用同一套 Codec 路径。

损坏存档时不要静默丢弃实例。当前反序列化存在忽略异常的行为，应改为：

- 记录完整日志；
- 将实例标记为 `BLOCKED`；
- 保留原始数据；
- 提供管理员检查和人工处理命令。

## 六、GUI 设计

当前 GUI 只有礼包列表，点击后直接打开，建议拆成三层。

### 礼包列表

- 54 格布局；
- 支持分页；
- 显示礼包图标、名称、描述、模式和来源；
- 显示“待投递”“阻塞”“即将过期”等状态；
- 增加奖励箱入口；
- 关闭按钮固定位置。

### 礼包预览

`all` 模式显示全部内容：

```text
面包 x16
铁锭 x32
新手剑 x1
```

`random_one` 模式显示：

```text
从以下物品中随机获得一种
```

预览不能产生随机结果。

### 确认界面

增加：

```text
确认打开
取消
```

随机礼包应在点击确认后由服务端选择并保存结果。

当前礼包名称使用 `Component.literal`，无法正确处理颜色和占位符。应统一调用 `TextTemplateRenderer`，使 `display`、`description` 和 Lore 支持现有文本占位符。

## 七、命令与权限

保留现有命令，并补齐建议：

```text
/omnitools packages
/omnitools package open
/omnitools package open <instance_id>
/omnitools package preview <instance_id>

/omnitools package give <player> <package_id> [amount]
/omnitools package inspect <player>
/omnitools package inspect <player> <instance_id>
/omnitools package retry <player> <instance_id>
/omnitools package resolve <player> <instance_id> grant|fail
/omnitools package remove <player> <instance_id>
```

新增权限动作：

```text
package.open       PLAYER
package.preview    PLAYER
package.give       ADMIN
package.inspect    ADMIN
package.retry      ADMIN
package.resolve    ADMIN
package.remove     ADMIN
```

管理员批量发放不能循环创建后遇到上限才失败，应先预检查容量，再一次性提交，避免只发出一部分。

## 八、奖励模块联动

现有 `RewardType.PACKAGE` 已经接入，配置形式为：

```json
{
  "id": "daily_package",
  "type": "package",
  "package": "starter"
}
```

需要确保以下规则：

- 礼包模块关闭时，奖励配置直接拒绝重载；
- 奖励账本用 `eventId + rewardId` 保证幂等；
- 已创建礼包后修改配置，不改变旧礼包；
- 礼包创建失败时进入 `BLOCKED`，不能简单标记成功；
- 奖励重试只能查找并恢复原礼包实例。

签到、在线奖励、成就和 CDK 都可以继续使用礼包奖励。

## 九、商店联动

商店礼包商品建议单独设计交易记录：

```json
{
  "index": 5,
  "type": "package",
  "package": "starter",
  "price": 100
}
```

购买事务：

```text
检查礼包定义
  ↓
检查余额
  ↓
创建 purchase_id
  ↓
扣除货币
  ↓
创建礼包实例
  ↓
标记购买完成
```

必须防止：

- 扣钱但没有礼包；
- 礼包创建成功但扣款失败；
- 断服后重复购买；
- 点击按钮重复购买。

商店联动应放在礼包核心稳定后实现。

## 十、安全限制

建议保留并补充：

- 每个玩家未打开礼包数量上限；
- 管理员单次发放数量上限；
- 单条数量和礼包总数量上限；
- 最大礼包定义数和最大条目数；
- SNBT 大小限制；
- GUI 点击冷却；
- 每个实例只能由所有者打开；
- 已打开礼包不可重复打开；
- 随机结果只能由服务端生成；
- 删除和人工结算必须写审计日志；
- 不允许客户端提交物品内容或随机结果。

## 十一、占位符和命令菜单联动

建议新增：

```text
%omnitools:packages_pending%
%omnitools:packages_waiting_inbox%
%omnitools:packages_opened%
%omnitools:package_last_opened%
%omnitools:package_last_item%
```

命令菜单增加：

```text
open_package_list
open_reward_inbox
```

侧边栏可显示：

```text
未打开礼包：%omnitools:packages_pending%
待投递物品：%omnitools:packages_waiting_inbox%
```

## 十二、开发阶段

1. **P0：修复可靠性**
   - `grant_key` 幂等；
   - 修复 `APPLYING` 恢复重复创建；
   - RegistryOps 编解码；
   - 持久化投递批次；
   - 增加礼包模块测试。

2. **P1：完善基础体验**
   - 礼包列表分页；
   - 预览和确认界面；
   - 颜色、Lore、占位符；
   - 奖励箱入口；
   - 管理员检查、重试和审计。

3. **P1：完善命令和权限**
   - 指定实例打开；
   - 批量发放事务；
   - 补充权限节点；
   - 增加命令建议和错误提示。

4. **P2：奖励与商店**
   - 稳定签到、成就、在线奖励和 CDK 联动；
   - 增加商店礼包商品；
   - 实现购买事务恢复。

5. **P2：高级玩法**
   - 权重随机；
   - 保底机制；
   - 重复物品转货币；
   - 限时礼包；
   - 礼包钥匙；
   - 每日开启次数；
   - 赛季礼包。

6. **P3：跨模块玩法**
   - 任务系统；
   - 赛季排行榜奖励；
   - 世界活动礼包；
   - 公会或团队礼包。

## 十三、验收标准

至少验证：

- 全部模式完整发放所有物品；
- 随机模式只发放一个条目；
- 随机结果重启后不改变；
- 数量超过 64 时正确拆堆；
- NBT、组件、Lore 和自定义名称保持不变；
- 背包不足时进入奖励箱；
- 重复点击不会重复发放；
- 奖励重试不会重复创建礼包；
- 配置重载不会改变旧实例；
- 删除不存在的实例不会误删其他礼包；
- 商店扣款和礼包创建具备事务恢复；
- 损坏数据不会静默丢失；
- 玩家无法打开其他玩家的实例。

总体建议：先把当前礼包模块提升为“可靠的虚拟资产系统”，再加入权重、保底、商店和赛季玩法。最优先处理的是奖励恢复幂等、物品持久化和投递事务，这三项直接关系到玩家物品和经济数据是否会重复或丢失。本轮仅进行了只读分析，没有修改、构建或测试项目。

---

## Development request 2026/8/28 17:18:03

超大礼包可能卡服。当前允许总数量最高 589824，打开时会预先拆成物品堆，并对每一堆前后同步保存数据；不可堆叠物品或大数量礼包会产生非常多的磁盘写入。
管理员无法真正处置 BLOCKED 礼包。现有 inspect 仅返回实例总数，remove 会直接删除；没有逐堆审计、确认已发/未发、受控恢复工具。
/omnitools package give 按玩家、按数量逐个创建。中途达到实例上限或发生异常时，之前创建的礼包不会回滚，属于部分成功。
已打开礼包只从 GUI 隐藏，数据默认一直保留；长期运行会累积实例和投递批次。
当前随机仅等权；商店礼包商品及“扣币与创建礼包”的事务恢复尚未实现。
自动化测试只覆盖少量数据模型状态，未覆盖真实投递、背包满恢复、重启、权限和压力场景。
交给工作台的实施方案

第一阶段：先补稳定性和数据安全。

将投递批次改为“逻辑条目 + 已投递数量”，不要在一次点击中预生成全部物理物品堆。
打开礼包只创建投递任务；服务器每 tick 最多投递 delivery_stacks_per_tick 堆，始终在服务端主线程修改背包。
保留现有 DELIVERING -> BLOCKED 的保守策略：发生不确定中断时绝不自动补发。
配置增加安全项，并提供 V1 到 V2 迁移：
"settings": {
  "max_pending_packages_per_player": 256,
  "max_delivery_stacks_per_package": 216,
  "delivery_stacks_per_tick": 4,
  "history_retention_days": 90
}
package give 改为“预检全部目标玩家容量和定义，再统一创建”。任何目标不满足条件时整次命令零创建；命令结果输出成功数、失败原因和批次 ID。
仅自动清理已 OPENED、无未完成投递批次、且奖励账本已确认完成的历史实例；PENDING、WAITING_INBOX、BLOCKED 永不自动删除。
第二阶段：补管理员恢复和审计能力。

新增管理员权限与命令：

/omnitools package list <player> [status] [page]
/omnitools package inspect <player> <instance_uuid>
/omnitools package resolve <player> <instance_uuid> <stack_uuid> delivered confirm
/omnitools package resolve <player> <instance_uuid> <stack_uuid> pending confirm
/omnitools package cancel <player> <instance_uuid> confirm
inspect 必须显示礼包来源、grantKey、配置版本、随机结果、每个堆的数量和状态、时间戳。resolve 只能由管理员明确确认“该堆已发”或“确认未发”；禁止对 BLOCKED 一键重试。所有高风险操作写入独立审计日志。

第三阶段：补商店联动。

商店商品支持 type: "package"。
新建持久化购买事务：PREPARED -> CHARGED -> PACKAGE_CREATED -> COMPLETED。
使用稳定键 shop:<transactionId>#<rewardId> 创建礼包，扣币也使用同一事务 ID 幂等。
重启后只能继续已证明安全的步骤；扣币或发包结果不确定时进入管理员审计，不能自动退款或重发。

---

## Development request 2026/8/28 17:54:22

第三阶段：补商店联动。

商店商品支持 type: "package"。
新建持久化购买事务：PREPARED -> CHARGED -> PACKAGE_CREATED -> COMPLETED。
使用稳定键 shop:<transactionId>#<rewardId> 创建礼包，扣币也使用同一事务 ID 幂等。
重启后只能继续已证明安全的步骤；扣币或发包结果不确定时进入管理员审计，不能自动退款或重发。

---

## Development request 2026/8/28 18:29:53

可以，而且建议做成“奖励库”，不建议新增一个可关闭的独立玩法模块。

当前项目已有合适基础：`common/rewards.json` 已支持单条奖励模板，签到、在线奖励、成就、CDK 都经由同一 `RewardDefinition` 和奖励账本发放。但现在仍需在各模块保留奖励数组与本地 `id`，现有公共配置也只有两个简单模板，尚未实现“定义一套奖励组合，多个模块直接调用”。见 [CommonConfig.java](/D:/mod/qiandao/src/main/java/dev/modmind/omnitools/config/CommonConfig.java:78)、[RewardDefinition.java](/D:/mod/qiandao/src/main/java/dev/modmind/omnitools/reward/RewardDefinition.java:71)。

**建议方案**

将 `config/omnitools/common/rewards.json` 从“模板文件”升级为“奖励库”。它不放入 `modules` 总开关，因为签到、成就等模块都依赖它，关闭会导致引用失效。

```json
{
  "format_version": 2,
  "rewards": {
    "coins_100": {
      "type": "currency",
      "amount": 100
    },
    "starter_package": {
      "type": "package",
      "package": "starter"
    },
    "geologist_7d": {
      "type": "title",
      "title": "geologist",
      "duration": { "mode": "active_days", "days": 7 },
      "renewal": "extend"
    }
  },
  "sets": {
    "daily_basic": {
      "rewards": ["coins_100"]
    },
    "mine_stone_1000": {
      "rewards": ["coins_100", "geologist_7d", "starter_package"]
    }
  }
}
```

各业务模块只引用奖励或奖励组，不再重复写货币、物品、称号、指令、礼包的具体内容：

```json
{
  "id": "mine_stone_1000",
  "condition": { "...": "..." },
  "rewards": [
    { "set": "mine_stone_1000" }
  ]
}
```

也允许组合：

```json
"rewards": [
  { "set": "daily_basic" },
  { "reward": "starter_package" }
]
```

实现要求：

- 新增不可变 `RewardCatalog`，由 `CommonConfig` 加载 `rewards` 和 `sets`。
- 奖励库键名同时作为稳定的奖励 `id`，解析时自动补入 `id`，调用处不得覆盖奖励类型、数量、NBT、指令等内容。
- `{ "reward": "..." }` 解析为一个奖励；`{ "set": "..." }` 递归展开为多个奖励。
- 支持集合嵌套，但必须检测未知引用、循环引用、最大深度、同一事件展开后重复奖励 ID。
- 保留 V1 的 `templates`、`template`、`$ref`，保证旧配置继续可用；V2 是推荐写法。
- 在完整 `/omnitools reload` 时先加载奖励库，再加载签到、在线奖励、成就、CDK，最后沿用现有跨模块校验。称号、礼包、补签卡、命令安全规则仍由现有 [ConfigValidator.java](/D:/mod/qiandao/src/main/java/dev/modmind/omnitools/config/ConfigValidator.java:162) 校验。
- 不修改 `RewardGrantService` 和奖励账本语义。最终仍是已解析的 `RewardDefinition`，因此幂等、防重复、物品/NBT 快照、礼包创建逻辑保持不变。

关键规则：奖励 ID 必须视为永久业务 ID。已上线的 `coins_100` 不要改成物品或改作其他用途；需要调整语义时新增 `coins_200_v2`，再修改奖励组。否则已有奖励账本可能将“旧奖励已发”误判为“新奖励已发”。

工作台验收应覆盖：四个模块都能引用同一奖励组；奖励组组合与重复 ID 被正确处理；循环/未知引用阻止整次重载；V1 模板与旧的内联 `rewards` 数组仍可使用；奖励库中的称号、礼包、命令仍触发现有安全校验。

这样能真正做到“奖励只定义一次，业务模块只选择奖励组”，且不会重造现有统一发奖与幂等机制。

---

## Development request 2026/8/28 18:58:27

可以。建议新增一个独立汇总文档：

```text
docs/omnitools-handbook.md
```

原有 `README.md`、`docs/index.md`、模块文档、示例和 Schema 均保持不变。新文件只负责把信息按“新手使用顺序”重新组织，并链接到原始文档，避免多份内容长期不一致。

**文档定位**

- 面向服主和配置人员的完整使用手册。
- 以当前实现为准，不把规划功能写成已实现。
- 原始文档仍是事实来源，新文档提供总结、示例和跳转。
- `archive/` 内容只放入“历史与规划”章节。
- `last-ai-change.json`、`last-ai-response.txt` 等内部文件不纳入用户教程。

**推荐目录**

```markdown
# OmniTools 完整使用手册

## 1. 项目简介与版本要求
## 2. 五分钟完成首次配置
## 3. 配置文件结构
## 4. 模块总览
## 5. 统一配置平台
## 6. 每日签到模块
## 7. 在线奖励模块
## 8. 成就模块
## 9. CDK 与补签卡
## 10. 奖励系统
## 11. 商店与货币
## 12. 称号与称号效果
## 13. 礼包模块
## 14. 排行榜模块
## 15. 侧边栏模块
## 16. 命令菜单模块
## 17. 权限配置
## 18. 云存储
## 19. 占位符
## 20. GUI 使用规范
## 21. 数据保存、备份与恢复
## 22. 热重载与模块管理
## 23. 故障排查
## 24. 配置示例索引
## 25. Schema 与编辑器支持
## 26. 版本升级说明
## 27. 已实现功能、缺口与规划
## 28. 原始文档对照表
```

**每个模块统一采用相同结构**

每个模块章节都按以下顺序编写：

1. 功能用途和适用场景。
2. 是否默认启用。
3. 配置文件路径。
4. 最小可运行配置。
5. 完整配置字段说明。
6. 可用指令和默认权限。
7. 支持的占位符。
8. 与其他模块的联动。
9. 数据保存和重载行为。
10. 常见错误及解决方法。
11. 高级配置示例。
12. 原始模块文档链接。

模块章节应覆盖当前项目中的：

- 每日签到
- 在线奖励
- 成就
- CDK 与补签卡
- 奖励
- 商店与货币
- 称号、称号效果
- 礼包
- 排行榜
- 侧边栏
- 命令菜单
- 权限
- 云存储

**统一奖励章节**

重点说明当前已有的奖励类型：

```text
currency
item
title
command
makeup_card
package
```

同时解释：

- 奖励 ID 的唯一性和稳定性。
- NBT/SNBT 物品写法。
- 限时称号和永久称号。
- 礼包奖励的创建、预览、投递和恢复。
- 奖励账本的幂等机制。
- `common/rewards.json` 当前支持的是奖励模板引用。
- “完整奖励库”属于后续设计，不应在汇总文档中误写成已实现功能。

**占位符章节**

将 [docs/reference/placeholders.md](/D:/mod/qiandao/docs/reference/placeholders.md) 和 [docs/reference/placeholder-api.md](/D:/mod/qiandao/docs/reference/placeholder-api.md) 的内容整理成表格：

| 占位符 | 用途 | 支持位置 | 依赖 |
|---|---|---|---|
| 玩家名称 | 显示玩家名 | GUI、侧边栏、文本 | 内置 |
| 玩家坐标 | 显示坐标 | 侧边栏、文本 | 内置 |
| 签到天数 | 显示签到数据 | 签到 GUI、侧边栏 | OmniTools |
| 货币余额 | 显示余额 | GUI、侧边栏 | 商店模块 |
| 成就进度 | 显示进度 | 成就 GUI、文本 | 成就模块 |
| Text Placeholder API 占位符 | 第三方文本 | 支持的文本位置 | 可选依赖 |

完整占位符列表只维护一份，汇总文档引用参考页，避免重复维护造成遗漏。

**文档来源对照表**

末尾增加一张“原始文档地图”，列出当前文档的用途：

- `README.md`：项目简介和安装入口。
- `docs/index.md`：现有文档导航。
- `docs/config-platform.md`：统一配置和重载。
- `docs/modules/*.md`：模块详细说明。
- `docs/reference/*.md`：奖励、占位符和根配置参考。
- `docs/guides/*.md`：运维、升级、备份和一致性。
- `docs/examples/**/*.json(c)`：配置示例。
- `docs/schemas/*.json`：编辑器校验。
- `docs/presets/**/*.json`：成就预设。
- `docs/archive/**`：历史方案和需求记录。
- `CHANGELOG.md`：版本变更记录。

**交给工作台的执行阶段**

1. 扫描现有文档，并依据 [document-map.md](/D:/mod/qiandao/docs/maintainers/document-map.md) 分类。
2. 提取每个模块的真实配置、指令、权限、占位符和重载行为。
3. 以 `docs/omnitools-handbook.md` 为唯一新增文件进行整理。
4. 所有功能标注为“已实现”“未实现”“规划中”或“需要第三方模组”。
5. 示例优先链接现有 `docs/examples/`，不要复制大量 JSON。
6. 所有链接使用相对路径，不写本机绝对路径。
7. 检查代码块闭合、JSON 示例合法、指令名称一致、Schema 链接有效。
8. 检查是否误把 `archive/` 中的旧方案写成当前功能。
9. 最终只新增该 Markdown 文件，不修改任何旧文档。

**验收标准**

- 新手只阅读该文件即可完成首次安装和基础配置。
- 每个现有模块都有独立章节。
- 每个章节都能跳转到对应原始文档、示例和 Schema。
- 所有当前支持的奖励类型和占位符都有说明。
- 已实现功能与规划功能明确区分。
- 不产生与旧文档冲突的第二套配置规则。
- 原有文档内容、路径和链接均未被修改。

---

## Development request 2026/8/28 19:31:08

当前项目已从“单一签到模组”发展为较完整的纯服务端运营框架，功能覆盖面很高；下一阶段的重点不应继续堆叠玩法，而应优先补齐文档、事务恢复验证和性能边界。

**已实现**

- 平台层：模块总开关、单模块重载、统一权限、配置候选快照校验后原子发布，加载失败会保留旧配置。[配置管理](D:/mod/qiandao/src/main/java/dev/modmind/omnitools/config/OmniToolsConfigManager.java)
- 核心玩法：签到与补签、在线奖励、CDK、货币商店、称号、成就、云存储、命令菜单、侧边栏、排行榜、礼包。
- 成就：支持原版统计及 `stat / sum / all / any / not` 条件树，并包含调度预算。
- 统一奖励：签到、在线奖励、成就、CDK 可复用货币、物品、称号、指令、补签卡、礼包等奖励；奖励库 V2 支持命名奖励集、嵌套引用和循环检测。[奖励库](D:/mod/qiandao/src/main/java/dev/modmind/omnitools/config/RewardCatalog.java) [发奖服务](D:/mod/qiandao/src/main/java/dev/modmind/omnitools/reward/RewardGrantService.java)
- 礼包：已支持 V2 配置、全量/随机领取、按 tick 分批投递、待领取记录、历史清理、损坏数据隔离、审计日志与管理员 `inspect/resolve/cancel` 处理。[礼包服务](D:/mod/qiandao/src/main/java/dev/modmind/omnitools/packages/PackageService.java)
- 商店购买礼包：已有持久化事务链 `PREPARED -> CHARGED -> PACKAGE_CREATED -> COMPLETED`，服务器启动时会尝试恢复可证明安全的中断状态。[购买事务](D:/mod/qiandao/src/main/java/dev/modmind/omnitools/ShopPurchaseService.java)

**主要缺口**

| 类型 | 现状 | 影响 |
|---|---|---|
| 文档同步 | 礼包、奖励库、商店事务的文档仍混有“未实现”和旧 V1 配置描述 | 服主可能按过期字段配置，无法正确运维 |
| 端到端验证 | 现有测试侧重解析与数据模型，缺少真实服务器中断恢复流程 | 无法证明扣款、发礼包、重启恢复不会造成重复或遗漏 |
| 性能验证 | 礼包和商店关键节点仍会同步落盘 | 高并发、不可堆叠物品或慢磁盘时可能拖慢 TPS |
| 运维闭环 | 礼包已有人工处置命令；商店阻塞事务的“继续、退款、结案”流程仍需确认或补齐 | 管理员遇到异常交易时处理成本高 |
| 可选依赖兼容 | Placeholder API 使用反射降级，但尚应验证安装、缺失、禁用三种场景 | 侧边栏和文本渲染可能出现版本兼容问题 |

另外，当前运行配置中 `packages`、`leaderboards`、`permissions` 处于关闭状态，因此这些模块虽已有源码，实际服内路径仍未被当前配置验证。

**最高优先级风险**

1. **文档与代码事实不一致**。这是当前最直接的用户风险，尤其是 [礼包文档](D:/mod/qiandao/docs/modules/packages.md)、[奖励参考](D:/mod/qiandao/docs/reference/rewards.md) 与实际 V2 实现互相矛盾。
2. **跨持久化事务缺少故障演练**。重点覆盖“扣币后崩溃”“创建礼包后崩溃”“背包满分批投递”“重启后恢复”“管理员 resolve/cancel”。
3. **同步保存的主线程压力未知**。礼包已经有每 tick 限额，但需要在大量待发物品和多人同时购买时测量实际 MSPT。

**建议顺序**

- P0：以源码为唯一事实源，重写并统一奖励、礼包、商店事务文档与示例配置。
- P0：补真实服务器集成测试/烟雾测试，覆盖交易中断与恢复。
- P1：增加投递队列、同步保存耗时与阻塞事务数量的日志/监控，并做压力测试。
- P1：完善商店异常交易的管理员处理命令和审计闭环。
- P2：确认稳定性后，再扩展礼包、排行榜和跨模块玩法。

---

## Development request 2026/8/28 22:20:10

问题出在显示层，不是统计计算层：

- `StatisticTargetResolver` 会把 `"*"` 展开成全部方块，最多 2048 个目标。
- 展开后 `AchievementScreenHandler` 只知道“有很多目标”，于是调用 `joinTargetNames()` 把名称全部拼接。
- `StatCondition.progress()` 实际已经正确计算了总数，所以可以直接显示 `0/10000`。

建议采用“数量阈值 + 聚合摘要”：

```text
1 个目标：挖掘石头：0/10000
少量目标：石头、深板岩合计：0/32
大量目标：全部方块合计：0/10000
```

具体可在 [AchievementScreenHandler.java](D:/mod/qiandao/src/main/java/dev/modmind/omnitools/AchievementScreenHandler.java:280) 中处理：

```java
if (stat.match() == TargetMatch.SUM) {
    int count = stat.requirements().size();

    if (count > 8) {
        lore.add(prefix.copy().append(ServerText.translatable(
                "gui.omnitools.achievement.condition.stat_sum_compact",
                count, progress.current(), stat.atLeast()))
                .withStyle(color));
    } else {
        // 保留现在的名称列表显示
    }
}
```

中文文案可以是：

```json
"gui.omnitools.achievement.condition.stat_sum_compact": "目标合计（%d 项）：%d/%d"
```

对于方块通配符，最好显示成：

```text
所有方块合计：0/10000
```

如果未来希望让配置作者自行控制，可以扩展类似字段：

```json
"display": {
  "mode": "aggregate",
  "label": "所有方块"
}
```

不过这个字段当前尚未被解析，不能直接加入现有配置。仅修改 `target_groups`、标签或把 `*` 换成其他通配写法无法解决，因为它们最终都会被展开成多个目标。`SumCondition` 在 [AchievementScreenHandler.java](D:/mod/qiandao/src/main/java/dev/modmind/omnitools/AchievementScreenHandler.java:312) 也应采用同样的摘要逻辑。

本次仅读取项目，未修改文件、构建或测试。

---

## Development request 2026/8/28 22:29:13

当前问题的根源是：目标在加载时已经被 `*`、标签或目标组展开成很多实体，之后界面用 `joinTargetNames()` 拼成一条长文本；而 [GuiTextService.java](D:/mod/qiandao/src/main/java/dev/modmind/omnitools/GuiTextService.java:20) 又会把每行截断到 80 个字符，导致末尾的 `0/10000` 被截掉。

建议把多目标显示统一改成 `TargetSummaryFormatter`，不要直接拼接全部名称。

| 条件 | 推荐显示 |
|---|---|
| `sum`，1 个目标 | `挖掘石头：0/10000` |
| `sum`，2~4 个目标 | `石头、深板岩合计：0/32` |
| `sum`，超过 4 个目标 | `目标合计（2048 项）：0/10000` |
| `each` | `已完成目标：2/8`，下面只列未完成的前 3~4 个 |
| `any` | `任一目标达标：0/1（候选 2048 项）` |
| 通配符/标签 | 若保留原始表达式，显示“全部方块”“矿石标签”等语义名称 |

核心规则是“进度永远放前面”，例如：

```text
进度：0/10000
范围：全部方块（2048 项）
```

实现时需要同时处理：

- [AchievementScreenHandler.java](D:/mod/qiandao/src/main/java/dev/modmind/omnitools/AchievementScreenHandler.java:280) 的 `StatCondition` 分支。
- 同文件 [AchievementScreenHandler.java](D:/mod/qiandao/src/main/java/dev/modmind/omnitools/AchievementScreenHandler.java:312) 的 `SumCondition` 分支。
- `joinTargetNames()` 改为最多显示少量名称，并追加“还有 N 项”。
- `each` 只优先显示未完成目标，避免前面的已完成目标占满 Lore。
- 统一限制成就卡片最多 4~5 行详情，为底部状态行预留空间。

建议新增文案键：

```json
"gui.omnitools.achievement.condition.aggregate": "目标合计（%d 项）：%d/%d",
"gui.omnitools.achievement.condition.each_progress": "已完成目标：%d/%d",
"gui.omnitools.achievement.condition.hidden_targets": "还有 %d 个目标未显示"
```

如果希望显示“全部方块”“原木标签”这类语义名称，需要在 [StatisticTargetResolver.java](D:/mod/qiandao/src/main/java/dev/modmind/omnitools/achievement/StatisticTargetResolver.java) 展开目标时保留原始表达式；否则只能显示通用的“目标合计（N 项）”。

这样既保留小规模成就的可读性，也能保证通配符、多方块、多物品成就始终看得到准确进度。本次仅读取项目，未修改文件或运行测试。

---

## Development request 2026/8/28 22:33:49

当前问题的根源是：目标在加载时已经被 `*`、标签或目标组展开成很多实体，之后界面用 `joinTargetNames()` 拼成一条长文本；而 [GuiTextService.java](D:/mod/qiandao/src/main/java/dev/modmind/omnitools/GuiTextService.java:20) 又会把每行截断到 80 个字符，导致末尾的 `0/10000` 被截掉。

建议把多目标显示统一改成 `TargetSummaryFormatter`，不要直接拼接全部名称。

| 条件 | 推荐显示 |
|---|---|
| `sum`，1 个目标 | `挖掘石头：0/10000` |
| `sum`，2~4 个目标 | `石头、深板岩合计：0/32` |
| `sum`，超过 4 个目标 | `目标合计（2048 项）：0/10000` |
| `each` | `已完成目标：2/8`，下面只列未完成的前 3~4 个 |
| `any` | `任一目标达标：0/1（候选 2048 项）` |
| 通配符/标签 | 若保留原始表达式，显示“全部方块”“矿石标签”等语义名称 |

核心规则是“进度永远放前面”，例如：

```text
进度：0/10000
范围：全部方块（2048 项）
```

实现时需要同时处理：

- [AchievementScreenHandler.java](D:/mod/qiandao/src/main/java/dev/modmind/omnitools/AchievementScreenHandler.java:280) 的 `StatCondition` 分支。
- 同文件 [AchievementScreenHandler.java](D:/mod/qiandao/src/main/java/dev/modmind/omnitools/AchievementScreenHandler.java:312) 的 `SumCondition` 分支。
- `joinTargetNames()` 改为最多显示少量名称，并追加“还有 N 项”。
- `each` 只优先显示未完成目标，避免前面的已完成目标占满 Lore。
- 统一限制成就卡片最多 4~5 行详情，为底部状态行预留空间。

建议新增文案键：

```json
"gui.omnitools.achievement.condition.aggregate": "目标合计（%d 项）：%d/%d",
"gui.omnitools.achievement.condition.each_progress": "已完成目标：%d/%d",
"gui.omnitools.achievement.condition.hidden_targets": "还有 %d 个目标未显示"
```

如果希望显示“全部方块”“原木标签”这类语义名称，需要在 [StatisticTargetResolver.java](D:/mod/qiandao/src/main/java/dev/modmind/omnitools/achievement/StatisticTargetResolver.java) 展开目标时保留原始表达式；否则只能显示通用的“目标合计（N 项）”。

这样既保留小规模成就的可读性，也能保证通配符、多方块、多物品成就始终看得到准确进度。本次仅读取项目，未修改文件或运行测试。

---

## Development request 2026/8/28 22:36:51

当前问题的根源是：目标在加载时已经被 `*`、标签或目标组展开成很多实体，之后界面用 `joinTargetNames()` 拼成一条长文本；而 [GuiTextService.java](D:/mod/qiandao/src/main/java/dev/modmind/omnitools/GuiTextService.java:20) 又会把每行截断到 80 个字符，导致末尾的 `0/10000` 被截掉。

建议把多目标显示统一改成 `TargetSummaryFormatter`，不要直接拼接全部名称。

| 条件 | 推荐显示 |
|---|---|
| `sum`，1 个目标 | `挖掘石头：0/10000` |
| `sum`，2~4 个目标 | `石头、深板岩合计：0/32` |
| `sum`，超过 4 个目标 | `目标合计（2048 项）：0/10000` |
| `each` | `已完成目标：2/8`，下面只列未完成的前 3~4 个 |
| `any` | `任一目标达标：0/1（候选 2048 项）` |
| 通配符/标签 | 若保留原始表达式，显示“全部方块”“矿石标签”等语义名称 |

核心规则是“进度永远放前面”，例如：

```text
进度：0/10000
范围：全部方块（2048 项）
```

实现时需要同时处理：

- [AchievementScreenHandler.java](D:/mod/qiandao/src/main/java/dev/modmind/omnitools/AchievementScreenHandler.java:280) 的 `StatCondition` 分支。
- 同文件 [AchievementScreenHandler.java](D:/mod/qiandao/src/main/java/dev/modmind/omnitools/AchievementScreenHandler.java:312) 的 `SumCondition` 分支。
- `joinTargetNames()` 改为最多显示少量名称，并追加“还有 N 项”。
- `each` 只优先显示未完成目标，避免前面的已完成目标占满 Lore。
- 统一限制成就卡片最多 4~5 行详情，为底部状态行预留空间。

建议新增文案键：

```json
"gui.omnitools.achievement.condition.aggregate": "目标合计（%d 项）：%d/%d",
"gui.omnitools.achievement.condition.each_progress": "已完成目标：%d/%d",
"gui.omnitools.achievement.condition.hidden_targets": "还有 %d 个目标未显示"
```

如果希望显示“全部方块”“原木标签”这类语义名称，需要在 [StatisticTargetResolver.java](D:/mod/qiandao/src/main/java/dev/modmind/omnitools/achievement/StatisticTargetResolver.java) 展开目标时保留原始表达式；否则只能显示通用的“目标合计（N 项）”。

这样既保留小规模成就的可读性，也能保证通配符、多方块、多物品成就始终看得到准确进度。本次仅读取项目，未修改文件或运行测试。

---

## Development request 2026/8/28 22:39:28

当前问题的根源是：目标在加载时已经被 `*`、标签或目标组展开成很多实体，之后界面用 `joinTargetNames()` 拼成一条长文本；而 [GuiTextService.java](D:/mod/qiandao/src/main/java/dev/modmind/omnitools/GuiTextService.java:20) 又会把每行截断到 80 个字符，导致末尾的 `0/10000` 被截掉。

建议把多目标显示统一改成 `TargetSummaryFormatter`，不要直接拼接全部名称。

| 条件 | 推荐显示 |
|---|---|
| `sum`，1 个目标 | `挖掘石头：0/10000` |
| `sum`，2~4 个目标 | `石头、深板岩合计：0/32` |
| `sum`，超过 4 个目标 | `目标合计（2048 项）：0/10000` |
| `each` | `已完成目标：2/8`，下面只列未完成的前 3~4 个 |
| `any` | `任一目标达标：0/1（候选 2048 项）` |
| 通配符/标签 | 若保留原始表达式，显示“全部方块”“矿石标签”等语义名称 |

核心规则是“进度永远放前面”，例如：

```text
进度：0/10000
范围：全部方块（2048 项）
```

实现时需要同时处理：

- [AchievementScreenHandler.java](D:/mod/qiandao/src/main/java/dev/modmind/omnitools/AchievementScreenHandler.java:280) 的 `StatCondition` 分支。
- 同文件 [AchievementScreenHandler.java](D:/mod/qiandao/src/main/java/dev/modmind/omnitools/AchievementScreenHandler.java:312) 的 `SumCondition` 分支。
- `joinTargetNames()` 改为最多显示少量名称，并追加“还有 N 项”。
- `each` 只优先显示未完成目标，避免前面的已完成目标占满 Lore。
- 统一限制成就卡片最多 4~5 行详情，为底部状态行预留空间。

建议新增文案键：

```json
"gui.omnitools.achievement.condition.aggregate": "目标合计（%d 项）：%d/%d",
"gui.omnitools.achievement.condition.each_progress": "已完成目标：%d/%d",
"gui.omnitools.achievement.condition.hidden_targets": "还有 %d 个目标未显示"
```

如果希望显示“全部方块”“原木标签”这类语义名称，需要在 [StatisticTargetResolver.java](D:/mod/qiandao/src/main/java/dev/modmind/omnitools/achievement/StatisticTargetResolver.java) 展开目标时保留原始表达式；否则只能显示通用的“目标合计（N 项）”。

这样既保留小规模成就的可读性，也能保证通配符、多方块、多物品成就始终看得到准确进度。本次仅读取项目，未修改文件或运行测试。

---

## Development request 2026/8/28 22:40:14

当前问题的根源是：目标在加载时已经被 `*`、标签或目标组展开成很多实体，之后界面用 `joinTargetNames()` 拼成一条长文本；而 [GuiTextService.java](D:/mod/qiandao/src/main/java/dev/modmind/omnitools/GuiTextService.java:20) 又会把每行截断到 80 个字符，导致末尾的 `0/10000` 被截掉。

建议把多目标显示统一改成 `TargetSummaryFormatter`，不要直接拼接全部名称。

| 条件 | 推荐显示 |
|---|---|
| `sum`，1 个目标 | `挖掘石头：0/10000` |
| `sum`，2~4 个目标 | `石头、深板岩合计：0/32` |
| `sum`，超过 4 个目标 | `目标合计（2048 项）：0/10000` |
| `each` | `已完成目标：2/8`，下面只列未完成的前 3~4 个 |
| `any` | `任一目标达标：0/1（候选 2048 项）` |
| 通配符/标签 | 若保留原始表达式，显示“全部方块”“矿石标签”等语义名称 |

核心规则是“进度永远放前面”，例如：

```text
进度：0/10000
范围：全部方块（2048 项）
```

实现时需要同时处理：

- [AchievementScreenHandler.java](D:/mod/qiandao/src/main/java/dev/modmind/omnitools/AchievementScreenHandler.java:280) 的 `StatCondition` 分支。
- 同文件 [AchievementScreenHandler.java](D:/mod/qiandao/src/main/java/dev/modmind/omnitools/AchievementScreenHandler.java:312) 的 `SumCondition` 分支。
- `joinTargetNames()` 改为最多显示少量名称，并追加“还有 N 项”。
- `each` 只优先显示未完成目标，避免前面的已完成目标占满 Lore。
- 统一限制成就卡片最多 4~5 行详情，为底部状态行预留空间。

建议新增文案键：

```json
"gui.omnitools.achievement.condition.aggregate": "目标合计（%d 项）：%d/%d",
"gui.omnitools.achievement.condition.each_progress": "已完成目标：%d/%d",
"gui.omnitools.achievement.condition.hidden_targets": "还有 %d 个目标未显示"
```

如果希望显示“全部方块”“原木标签”这类语义名称，需要在 [StatisticTargetResolver.java](D:/mod/qiandao/src/main/java/dev/modmind/omnitools/achievement/StatisticTargetResolver.java) 展开目标时保留原始表达式；否则只能显示通用的“目标合计（N 项）”。

这样既保留小规模成就的可读性，也能保证通配符、多方块、多物品成就始终看得到准确进度。本次仅读取项目，未修改文件或运行测试。

---

## Development request 2026/8/28 22:44:06

当前问题的根源是：目标在加载时已经被 `*`、标签或目标组展开成很多实体，之后界面用 `joinTargetNames()` 拼成一条长文本；而 [GuiTextService.java](D:/mod/qiandao/src/main/java/dev/modmind/omnitools/GuiTextService.java:20) 又会把每行截断到 80 个字符，导致末尾的 `0/10000` 被截掉。

建议把多目标显示统一改成 `TargetSummaryFormatter`，不要直接拼接全部名称。

| 条件 | 推荐显示 |
|---|---|
| `sum`，1 个目标 | `挖掘石头：0/10000` |
| `sum`，2~4 个目标 | `石头、深板岩合计：0/32` |
| `sum`，超过 4 个目标 | `目标合计（2048 项）：0/10000` |
| `each` | `已完成目标：2/8`，下面只列未完成的前 3~4 个 |
| `any` | `任一目标达标：0/1（候选 2048 项）` |
| 通配符/标签 | 若保留原始表达式，显示“全部方块”“矿石标签”等语义名称 |

核心规则是“进度永远放前面”，例如：

```text
进度：0/10000
范围：全部方块（2048 项）
```

实现时需要同时处理：

- [AchievementScreenHandler.java](D:/mod/qiandao/src/main/java/dev/modmind/omnitools/AchievementScreenHandler.java:280) 的 `StatCondition` 分支。
- 同文件 [AchievementScreenHandler.java](D:/mod/qiandao/src/main/java/dev/modmind/omnitools/AchievementScreenHandler.java:312) 的 `SumCondition` 分支。
- `joinTargetNames()` 改为最多显示少量名称，并追加“还有 N 项”。
- `each` 只优先显示未完成目标，避免前面的已完成目标占满 Lore。
- 统一限制成就卡片最多 4~5 行详情，为底部状态行预留空间。

建议新增文案键：

```json
"gui.omnitools.achievement.condition.aggregate": "目标合计（%d 项）：%d/%d",
"gui.omnitools.achievement.condition.each_progress": "已完成目标：%d/%d",
"gui.omnitools.achievement.condition.hidden_targets": "还有 %d 个目标未显示"
```

如果希望显示“全部方块”“原木标签”这类语义名称，需要在 [StatisticTargetResolver.java](D:/mod/qiandao/src/main/java/dev/modmind/omnitools/achievement/StatisticTargetResolver.java) 展开目标时保留原始表达式；否则只能显示通用的“目标合计（N 项）”。

这样既保留小规模成就的可读性，也能保证通配符、多方块、多物品成就始终看得到准确进度。本次仅读取项目，未修改文件或运行测试。

---

## Development request 2026/8/28 22:50:14

我在测试中发现，成就涉及多种方块、物品时，显示的不是进度0/10000，而是将涉及方块的名字全部列举出来，非常影响观感而且不能查看进度，下面是我的配置文件的一部分：{
			"id": "ach_mine_2",
			"display": "矿工大师 II",
			"description": "达成矿工大师里程碑：II 阶段",
			"icon": "minecraft:iron_pickaxe",
			"requirements": {
				"match": "sum",
				"at_least": 10000,
				"stat": "block_mined",
				"type": "stat",
				"targets": [
					"*"
				]
			},
			"rewards": [{
					"id": "ach_mine_2_coins",
					"type": "currency",
					"amount": 500
				},
				{
					"id": "ach_mine_2_title",
					"type": "title",
					"title": "title_mine_2"
				}
			]
		},

---

## Development request 2026/8/29 10:42:00

继续

---

## Development request 2026/8/29 13:50:10

可以，建议采用“单文件、内嵌效果、兼容旧格式”的完整方案。

当前实现中：

- `TitleConfig` 只保存称号和效果 ID；
- `TitleEffectConfig` 保存效果详情；
- `TitleEffectService` 根据 ID 应用效果；
- `AchievementScreenHandler` 根据 ID 查找效果预览；
- `ConfigValidator` 负责检查称号引用的效果是否存在。

相关代码见 [TitleConfig.java](D:/mod/qiandao/src/main/java/dev/modmind/omnitools/TitleConfig.java)、[TitleEffectService.java](D:/mod/qiandao/src/main/java/dev/modmind/omnitools/TitleEffectService.java) 和 [AchievementScreenHandler.java](D:/mod/qiandao/src/main/java/dev/modmind/omnitools/AchievementScreenHandler.java)。

**一、最终配置格式**

继续使用：

```text
config/omnitools/titles/config.json
```

升级为 `format_version: 2`：

```json
{
  "format_version": 2,
  "nameplate_mode": "scoreboard_team",
  "team_conflict_policy": "omnitools_priority",
  "titles": [
    {
      "id": "title_mine_1",
      "display": "§6[§r矿工大师 I§6] §r",
      "rarity": "legendary",
      "tooltip": [],
      "effects": [
        {
          "id": "haste_1",
          "name": "急迫 I",
          "type": "POTION",
          "effect": "minecraft:haste",
          "amplifier": 0,
          "duration": -1,
          "display": "§a✔ 急迫 I（+20% 挖掘速度）"
        }
      ]
    },
    {
      "id": "plain_title",
      "display": "§7[普通称号]§r ",
      "rarity": "common",
      "tooltip": [],
      "effects": []
    }
  ]
}
```

无效果称号统一使用：

```json
"effects": []
```

不建议使用空字符串：

```json
"effects": ""
```

因为效果本质上是数组，且一个称号可以拥有多个效果。

**二、字段规则**

每个效果对象支持当前已有的四类效果：

- `POTION`：`effect`、`amplifier`、`duration`
- `ATTRIBUTE`：`attribute`、`operation`、`amount`
- `PARTICLE`：`particle`、`frequency`
- `PERMISSION`：`permission`

通用字段：

```json
{
  "id": "haste_1",
  "name": "急迫 I",
  "type": "POTION",
  "display": "§a✔ 急迫 I"
}
```

建议保留 `id`，用于日志、报错和调试。效果 ID 只需在同一个称号内唯一。

`display` 用于成就页面和称号页面的预览；如果省略，可以回退使用 `name`。

**三、成就奖励不变**

成就仍然只引用称号 ID：

```json
{
  "id": "ach_mine_1_title",
  "type": "title",
  "title": "title_mine_1"
}
```

玩家数据也不需要迁移，因为 `TitleData` 保存的是称号 ID、佩戴状态和有效期，不保存效果定义。

**四、运行时调整**

最终结构中，`TitleEffectService` 不再执行：

```java
titleEffectConfig().definition(effectId)
```

而是直接遍历：

```java
selectedTitle.effects()
```

然后应用每个内嵌效果。

`modules.title_effects.enabled` 建议保留，作为全局效果开关：

- `titles` 关闭：不能使用称号；
- `title_effects` 关闭：称号仍可领取和佩戴，但不施加效果；
- 称号自身 `"effects": []`：该称号没有效果。

**五、界面显示规则**

建议新增一个统一的称号预览渲染方法，供两个界面共用：

- 称号页面：显示称号说明和效果；
- 成就页面：显示奖励称号、`tooltip` 和每个效果的 `display`；
- 成就未领取或已领取状态都显示预览；
- 无效果时显示“无佩戴效果”；
- 不再要求用户把效果说明重复写入 `tooltip`。

成就页面最终效果类似：

```text
奖励称号：[矿工大师 I]
佩戴效果：
  ✔ 急迫 I（+20% 挖掘速度）
```

这里仅表示预览，不会因为查看成就就给玩家施加急迫效果。

**六、兼容与迁移**

建议分两个版本处理：

第一阶段：

- 同时支持旧版 `format_version: 1`；
- 旧版仍从 `title_effects/config.json` 读取效果；
- 新版从 `titles/config.json` 读取内嵌效果；
- 新版存在时优先使用新版；
- 两种格式同时存在时输出警告，不自动混合。

第二阶段：

- 启动时检测旧版两个文件；
- 自动生成带时间戳的备份；
- 将旧称号和旧效果合并写入新版文件；
- 原文件放入 `config/omnitools/legacy/`；
- 在 `manifest.json` 中记录迁移来源。

现有 [ConfigMigration.java](D:/mod/qiandao/src/main/java/dev/modmind/omnitools/config/ConfigMigration.java) 已经采用备份和归档策略，可以沿用。

**七、需要修改的代码范围**

主要包括：

- `TitleConfig`：支持 v2 内嵌效果；
- `TitleEffectConfig`：作为旧版兼容层，最终可移除；
- `OmniToolsConfigSnapshot`：最终不再需要独立的 `titleEffects`；
- `OmniToolsConfigManager`：调整模块加载和快照；
- `ConfigValidator`：改为校验内嵌效果；
- `TitleEffectService`：直接应用称号效果；
- `TitleScreenHandler`：显示内嵌效果；
- `AchievementScreenHandler`：显示奖励效果预览；
- `titles.schema.json`：新增 v2 结构；
- 配置文档和示例。

**八、测试验收**

至少覆盖：

- 一个称号包含一个效果；
- 一个称号包含多个效果；
- `"effects": []` 正常加载；
- 四种效果类型都能解析和应用；
- 无效 Minecraft ID 被拒绝；
- `duration: 0` 被拒绝；
- 重复效果 ID 被拒绝；
- 成就页面显示称号和效果预览；
- 称号页面显示相同效果；
- 关闭 `title_effects` 后不施加效果；
- 旧版配置可以正常加载或迁移；
- 配置失败时保留上一份有效快照。

这个设计的核心是：奖励仍然只依赖称号 ID，但称号的展示、效果应用和成就预览都从同一个称号对象读取，避免两个配置文件不同步。

---

## Development request 2026/8/29 16:28:47

是的，当前成就卡片有明确的行数限制，这正是称号属性预览未显示的主要原因。

[GuiTextService.java](D:/mod/qiandao/src/main/java/dev/modmind/omnitools/GuiTextService.java) 将卡片 Lore 限制为 **6 行总计**，其中最后一行固定保留给状态提示（“可领取”“已领取”等），因此实际内容最多只有 **5 行**。而 [AchievementScreenHandler.java](D:/mod/qiandao/src/main/java/dev/modmind/omnitools/AchievementScreenHandler.java) 的排列顺序是：

```text
成就描述
进度/条件
货币奖励
称号奖励名称
称号 tooltip
称号效果 display
状态提示
```

你的单个急迫称号通常已经是第 6 条详细内容，会在放入 Lore 前被截掉。

此外 [GuiStatusItem.java](D:/mod/qiandao/src/main/java/dev/modmind/omnitools/GuiStatusItem.java) 也再次硬编码按 `6` 行截断，所以只改 `GuiTextService` 不够。

建议仅放宽成就界面，不影响其他菜单：

1. 在 `GuiTextService` 增加带最大行数参数的 `cardLore` 方法，例如允许成就页 10 行。
2. 在 `GuiStatusItem.create` 增加 `maximumLoreLines` 参数的重载，默认仍保持 6 行。
3. 在 `AchievementScreenHandler.achievementItem` 调用新重载，传入 `10`。
4. 其他界面继续用原来的默认 6 行。

效果是：

```text
成就描述
完成进度
奖励：500 金币
奖励称号：[矿工大师 I]
佩戴效果：
✔ 急迫 I（+20% 挖掘速度）
可领取
```

不建议直接把全局 `6` 改成很大数，因为称号、商店、签到等所有 GUI 卡片都会一起变长。成就界面最好单独使用 `8` 至 `10` 行；若称号可能配置多个效果，再限制最多显示 3 项，并追加 `还有 N 项效果`，避免 Lore 过长。
