# 侧边栏

## 1. 功能用途、适用场景与依赖

侧边栏使用原版计分板向玩家展示动态信息。页面可以是文本页，也可以读取排行榜模块的内存快照。Placeholder API 为可选依赖；未安装时内置占位符仍可用，第三方占位符显示为未解析文本。

## 2. 根开关与禁用后的行为

根开关为 <code>modules.sidebar.enabled</code>，默认开启。禁用后清理 OmniTools 侧边栏并停止刷新；排行榜模块关闭时，侧边栏会自动跳过排行榜页面，静态文本页继续显示。

## 3. 配置路径、首次生成行为与格式版本

配置文件为 <code>config/omnitools/sidebar/config.json</code>。首次加载会生成 v3 配置，当前有效格式为 <code>format_version: 3</code>。v1/v2 文件会在内存中映射为单个 <code>main</code> 文本页，不会破坏旧字段；保存或手工迁移后再使用 v3 页面结构。修改后执行 <code>/omnitools reload sidebar</code>。

## 4. 命令与默认权限

| 命令 | 默认权限 | 作用 |
| --- | --- | --- |
| <code>/omnitools sidebar toggle</code> | <code>PLAYER</code> | 切换自己的侧边栏显示 |
| <code>/omnitools sidebar status</code> | <code>PLAYER</code> | 查看当前显示状态 |
| <code>/omnitools reload sidebar</code> | <code>omnitools.admin</code> | 重载侧边栏配置 |

## 5. 配置字段表

| 字段 | 类型、范围与默认值 | 错误行为 |
| --- | --- | --- |
| <code>format_version</code> | 整数，必须为 <code>3</code>（v1/v2 自动兼容） | 其他版本拒绝重载 |
| <code>default_visible</code> | 布尔，默认 <code>true</code> | 非布尔值拒绝重载 |
| <code>refresh_interval_ticks</code> | 整数 <code>5</code>--<code>600</code>，默认 <code>20</code> | 越界拒绝重载 |
| <code>conflict_policy</code> | <code>skip</code>、<code>replace</code>、<code>restore</code>，默认 <code>skip</code> | 未知值拒绝重载 |
| <code>presentation.mode</code> | <code>fixed</code> 或 <code>rotate</code>，默认 <code>fixed</code> | 未知值拒绝重载 |
| <code>presentation.fixed_page</code> | 页面 ID；固定模式必填 | 引用不存在页面拒绝重载 |
| <code>presentation.rotation_ticks</code> | 整数 <code>20</code>--<code>72000</code>，默认 <code>200</code> | 越界拒绝重载 |
| <code>presentation.page_ids</code> | 页面 ID 数组；轮播模式至少一项 | 重复或未知 ID 拒绝重载 |
| <code>pages</code> | 最多 64 个页面 | 超限拒绝重载 |
| <code>pages[].type</code> | <code>text</code> 或 <code>leaderboard</code> | 未知类型拒绝重载 |
| <code>pages[].lines</code> | 文本页最多 15 行 | 空文本页或超限拒绝重载 |
| <code>pages[].leaderboard_id</code> | 排行榜页面必填，引用排行榜模块 ID | 无效 ID 拒绝重载 |
| <code>pages[].max_entries</code> | 排行榜页 <code>1</code>--<code>15</code>，默认 <code>10</code> | 越界拒绝重载 |
| <code>pages[].line_format</code> | 非空文本，最长 256 字符 | 空值或超限拒绝重载 |

## 6. 最小可用 JSON 配置

    {
      "format_version": 3,
      "default_visible": true,
      "refresh_interval_ticks": 20,
      "conflict_policy": "skip",
      "presentation": { "mode": "fixed", "fixed_page": "main", "rotation_ticks": 200, "page_ids": ["main"] },
      "pages": [{
        "id": "main",
        "type": "text",
        "title": "&b&lOmniTools",
        "lines": [{ "id": "money", "text": "&e货币: &f%balance_formatted%" }]
      }]
    }

## 7. 带注释的 JSONC 教学配置

完整教学配置见 [sidebar.jsonc](../examples/config-platform/sidebar.jsonc)。核心结构如下：

    {
      "format_version": 3,
      "presentation": {
        "mode": "rotate", // fixed 或 rotate；所有玩家共用服务器时钟
        "rotation_ticks": 200,
        "fixed_page": "main",
        "page_ids": ["main", "mine_all", "hostile_kills"]
      },
      "pages": [
        { "id": "main", "type": "text", "title": "&b&lOmniTools", "lines": [
          { "id": "money", "text": "&e货币: &f%balance_formatted%" }
        ] },
        { "id": "mine_all", "type": "leaderboard", "leaderboard_id": "mine_all_blocks",
          "title": "&e&l全方块挖掘榜", "max_entries": 10,
          "line_format": "&7#{rank} &f{player} &b{value}" }
      ]
    }

排行榜页面只能读取已生成快照，不会在刷新或玩家请求时扫描磁盘。排行榜模块关闭、榜单 ID 暂不可用或快照尚未生成时，该页面被跳过。

## 8. 常见高级场景

- <code>fixed</code> 模式始终显示 <code>fixed_page</code>。
- <code>rotate</code> 模式按服务器统一 tick 时钟轮播 <code>page_ids</code>，不会因玩家进服时间不同而错位。
- <code>skip</code> 发现第三方侧边栏时保留对方显示；<code>replace</code> 临时替换；<code>restore</code> 关闭 OmniTools 后恢复原目标。
- 页面总行数仍受原版 15 行上限约束；占位符见 [占位符参考](../reference/placeholders.md)，第三方扩展见 [Placeholder API](../reference/placeholder-api.md)。

## 9. 相关模块与模板引用

侧边栏不定义奖励模板。排行榜页引用 <code>leaderboards</code> 模块中的榜单 ID；跨模块根开关和重载语义见 [统一配置平台](../config-platform.md)。

## 10. 数据保存、备份与不可随意修改的 ID

玩家显示偏好保存在 OmniTools SavedData；页面 ID、行 ID 和排行榜 ID 是配置契约，发布后不要随意修改。备份 <code>config/omnitools/sidebar/config.json</code>，并按运维指南备份世界 <code>data/</code>。

## 11. 热重载后的即时行为与常见故障

执行 <code>/omnitools reload sidebar</code> 后立即使用新页面和轮播状态；失败时保留旧配置。若排行榜页不显示，先确认 <code>modules.leaderboards.enabled</code>、榜单 ID、快照刷新状态和 <code>leaderboards</code> 权限，再检查 <code>conflict_policy</code> 是否跳过了第三方侧边栏。
