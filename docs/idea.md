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
