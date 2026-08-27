# 排行榜

## 功能用途与依赖

排行榜按原版玩家统计生成可缓存的排序结果，支持挖掘、物品使用、实体击杀和 `custom` 统计。它只读取统计，不修改或重置原版数据。`include_offline_players` 开启时会分批读取世界 `stats/<uuid>.json`。

## 根开关与禁用行为

根配置 `modules.leaderboards.enabled` 默认是 `false`。禁用后停止扫描、清空内存快照，排行榜命令、GUI 和侧边栏排行榜页面均不可用；静态侧边栏页面仍可显示。

## 配置路径与格式

配置文件为 `config/omnitools/leaderboards/config.json`，首次启用时自动生成。当前 `format_version` 为 `1`，可复制示例见 [`leaderboards.jsonc`](../examples/config-platform/leaderboards.jsonc)，字段约束见 [`leaderboards.schema.json`](../schemas/leaderboards.schema.json)。

## 命令与默认权限

| 命令 | 默认权限 | 作用 |
| --- | --- | --- |
| `/omnitools leaderboard` | `leaderboards.open` | 打开榜单目录 |
| `/omnitools leaderboard open <id>` | `leaderboards.open` | 打开指定榜单 |
| `/omnitools leaderboard list` | `leaderboards.open` | 列出榜单 ID |
| `/omnitools leaderboard chat <id> [page]` | `leaderboards.chat` | 在聊天中分页显示 |
| `/leaderboard` | `leaderboards.open` | 兼容目录别名 |
| `/top <id> [page]` | `leaderboards.chat` | 兼容聊天别名 |

## 配置字段

| 字段 | 类型与范围 | 默认值 | 错误行为 |
| --- | --- | --- | --- |
| `format_version` | 整数，必须为 `1` | `1` | 版本不支持时拒绝重载 |
| `refresh_interval_ticks` | `20`--`72000` | `200` | 越界拒绝重载 |
| `include_offline_players` | 布尔值 | `true` | 非布尔值拒绝重载 |
| `exclude_zero_scores` | 布尔值 | `true` | 非布尔值拒绝重载 |
| `max_files_per_tick` | `1`--`64` | `8` | 越界拒绝重载 |
| `target_groups` | ID 到字符串数组 | `{}` | 空组、未知引用或循环引用拒绝重载 |
| `leaderboards` | 最多 128 个定义 | 默认石头榜 | 重复 ID、错误物品/统计目标拒绝重载 |

`stat.aggregation` 支持 `sum`、`min`、`max`；`targets` 支持 ID、标签、`$group`、`*`。`@block_items` 仅可用于 `item_used`，表示所有 `BlockItem` 的使用次数。原版没有精确方块放置统计，物品使用只能作为近似值。

## 最小可用 JSON

```json
{
  "format_version": 1,
  "leaderboards": [
    {
      "id": "mine_stone",
      "display": "&b石材矿工",
      "icon": "minecraft:stone",
      "stat": { "type": "block_mined", "targets": ["minecraft:stone"], "aggregation": "sum", "unit": "count" }
    }
  ]
}
```

## 高级场景与模板引用

排行榜统计查询与成就共享同一目标解析器，但排行榜不参与成就进度。`linked_achievement` 只在 GUI 榜单条目中显示关联 ID；它必须引用已启用成就模块中的现有成就。

## 数据、备份与 ID

排行榜快照只保存在内存，玩家统计由 Minecraft 原版保存。榜单 `id`、目标组 ID 和关联成就 ID 属于配置契约，发布后不要随意修改；变更前请备份 `config/omnitools/` 和世界 `stats/` 文件。

## 热重载与故障处理

执行 `/omnitools reload leaderboards` 可单独重载。重载成功会清空旧快照并在后台重新扫描；失败时保留旧配置和旧快照。GUI、聊天和侧边栏仅读取最近一次完整快照，不会在请求时扫描磁盘。
