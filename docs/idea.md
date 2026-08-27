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
