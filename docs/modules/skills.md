# 技能模块：独立 mcMMO 行为兼容层

OmniTools 的技能模块在 Fabric 服务端内独立实现 mcMMO 风格的成长与能力。它不复制或链接 Bukkit/Spigot 版 mcMMO 源码；如果服务器同时安装真正的 mcMMO，必须只启用其中一个经验和掉落系统。

本页描述当前实现，不把 Fishing、Taming、Salvage、Smelting 或 Party 等第二阶段功能写成已完成。

## 启用与入口

模块开关位于 `config/omnitools/config.json`：

```json
{
  "modules": {
    "skills": { "enabled": true }
  }
}
```

技能配置的唯一权威路径是：

```text
config/omnitools/skills/config.json
```

首次启动会生成 `format_version: 2` 的 mcMMO 配置。修改后执行 `/omnitools reload skills`；加载失败会保留上一份有效配置。玩家进度和操作账本保存在世界 SavedData 中，不要通过删除世界数据来“重置”技能。

玩家可使用：

| 命令 | 权限 | 作用 |
| --- | --- | --- |
| `/skills`、`/skills open` | `skills.open` | 打开技能总览和详情界面 |
| `/skills stats` | `skills.open` | 查看引擎、总战力和各技能经验 |
| `/skills ability <skill>` | `skills.open` | 查看能力等级、冷却和剩余持续时间 |
| `/omnitools skills add <skill> <amount>` | `skills.admin` | 管理员按操作 ID 发放技能经验 |

`/skills top` 和 `/party` 尚未实现，不应写入菜单或运营公告。

## 当前技能与 canonical ID

默认配置包含以下十项技能。奖励、占位符、事件和 SavedData 以 canonical ID 为稳定键：

| ID | 方向 | 主动能力 | 被动能力 |
| --- | --- | --- | --- |
| `mining` | Mining | Super Breaker | Double Drops |
| `woodcutting` | Woodcutting | Tree Feller | Lumberjack's Loot |
| `herbalism` | Herbalism | Green Terra | Herbalism Bonus |
| `excavation` | Excavation | Giga Drill Breaker | Archaeology |
| `swords` | Swords | Serrated Strikes | Bleed |
| `axes` | Axes | Skull Splitter | Critical Strikes |
| `archery` | Archery | Arrow Storm | Retrieval |
| `acrobatics` | Acrobatics | Graceful Roll | Dodge |
| `repair` | Repair | Arcane Forging | Super Repair |
| `alchemy` | Alchemy | Catalysis | Concoctions |

兼容层仍接受旧 ID：`miner → mining`、`lumberjack → woodcutting`、`farmer → herbalism`、`excavator → excavation`、`warrior/combat → swords`、`hunter/hunting → archery`、`guardian/defense → acrobatics`、`smithing/crafting → repair`、`healing/support → alchemy`、`exploration/survival → acrobatics`。这些映射只用于读取旧奖励和旧进度；新配置请直接使用 canonical ID。一个 mcMMO 配置不能同时定义 canonical ID 和其旧别名。

## 等级、经验与能力

- 每项技能等级为 `0–1000`，总战力是所有已配置技能等级之和。
- 服务器根据技能配置的 XP 曲线、来源白名单、来源间隔和每日上限结算经验。
- `mcmmo.xp_multiplier` 只在服务端应用；客户端不能指定最终经验。
- 等级 `100` 解锁主动和被动能力。能力阶段为 `100 / 250 / 500 / 750 / 1000`，对应能力等级 `1 / 3 / 5 / 7 / 10`。
- 主动能力使用服务端冷却和持续时间；技能界面、`/skills ability` 与占位符显示当前状态。
- mcMMO 引擎不消费旧专业树技能点，也不应用旧专业树的常驻属性加成；旧字段仍保留用于兼容读取和审计。

经验事件统一包含技能、来源、玩家 UUID、世界、原因、操作 ID 和反刷键。`operation_id` 为空的经验请求会被拒绝，防止绕开幂等与恢复账本。高频事件不直接逐次写文件，而是通过 SavedData 账本去重后更新玩家进度。

管理员可使用 `/skills health` 查看本次运行中的经验成功/拒绝计数、来源分布，以及 XP 事务的 `PREPARED`、`COMMITTED`、`ROLLED_BACK` 数量，用于区分配置、限流、重复操作和持久化故障。

## 已实现的行为边界

- Mining、Woodcutting、Herbalism、Excavation：方块经验、服务器掉落表额外掉落、成熟作物处理和有限范围连锁砍树。
- Swords、Axes、Archery、Acrobatics：击杀经验、有限的强度/发光/抗性效果；每次事件最多结算一次。
- Repair、Alchemy：制作完成事件驱动的经验和受限的制造/药剂被动效果；不再周期遍历全部注册物品。
- 所有额外掉落排除创造模式、精准采集、命令/异常破坏等不安全来源；额外掉落不会再次触发同类被动。
- 范围能力有方块数量、碰撞和重入限制；世界、背包和实体修改只在服务端主线程执行。

外部领地保护、首领、悬赏和仇恨系统没有统一 API 时，能力采用安全降级，不穿墙、不透视地下资源，也不转移虚空或处决伤害。

## 配置结构

最小引擎设置如下，完整技能定义由首次生成的文件提供：

```json
{
  "format_version": 2,
  "engine": "mcmmo",
  "mcmmo": {
    "level_cap": 1000,
    "xp_multiplier": 1.0,
    "party_enabled": true,
    "legacy_enabled": false
  },
  "settings": {
    "max_level": 1000,
    "points_every_levels": 10,
    "max_daily_xp": 250000,
    "min_interval_ticks": 4,
    "hud": {
      "enabled": true,
      "bossbar_enabled": true,
      "duration_ticks": 60,
      "update_interval_ticks": 3,
      "actionbar_enabled": true,
      "level_up_title": true,
      "passive_feedback": true,
      "max_queued_messages": 3
    }
  },
  "trees": []
}
```

### 技能 HUD 反馈

技能经验成功写入账本后，服务端会复用一条个人 BossBar 显示技能名称、等级、本次合并经验和当前等级进度；连续获得经验会合并显示并按 `update_interval_ticks` 限频刷新，满级显示“已满级”。普通经验不额外逐条发送 ActionBar，避免挖掘等高频来源刷屏。达到新等级时会显示一次标题、副标题、音效和 ActionBar，不会为连续跨越的每一级逐条刷屏。主动能力使用 ActionBar 显示剩余时间，被动能力只在真实触发后提示。

HUD 状态只保存在内存中，玩家断线、重生、停服或技能模块关闭时会清理，不写入玩家 SavedData。HUD 出错只会记录结构化技能模块警告并跳过本次显示，已经成功结算的经验不会回滚。`duration_ticks` 支持 1–600，`update_interval_ticks` 支持 1–20，`max_queued_messages` 支持 0–16。

实际使用时 `trees` 不能为空；请保留生成文件中的十个定义。每项技能定义包含 `id`、`display`、`icon`、`attribute`、`sources`、`level_multipliers` 和两个 `skills`（一个 `active`、一个 `passive`）。主动/被动的 `tuning` 字段由服务端校验并按 1–10 级线性插值：

```json
{
  "id": "mining",
  "display": "Mining",
  "icon": "minecraft:diamond_pickaxe",
  "attribute": "block_break_speed",
  "sources": ["block_break", "reward", "command"],
  "level_multipliers": [{ "from_level": 1, "multiplier": 1.0 }],
  "skills": [
    {
      "id": "active",
      "display": "Super Breaker",
      "description": "达到阶段等级后自动强化。",
      "kind": "active",
      "unlock_level": 100,
      "max_level": 10,
      "point_cost": 0,
      "tuning": {
        "min_duration_seconds": 30,
        "max_duration_seconds": 120,
        "max_cooldown_seconds": 1800,
        "min_cooldown_seconds": 600,
        "min_value": 0.0,
        "max_value": 0.0
      }
    },
    {
      "id": "passive",
      "display": "Double Drops",
      "description": "由服务端按掉落表结算额外产出。",
      "kind": "passive",
      "unlock_level": 100,
      "max_level": 10,
      "point_cost": 0,
      "tuning": {
        "min_duration_seconds": 0,
        "max_duration_seconds": 0,
        "max_cooldown_seconds": 0,
        "min_cooldown_seconds": 0,
        "min_value": 0.05,
        "max_value": 0.40
      }
    }
  ]
}
```

`settings.max_level` 与 `points_every_levels` 对 mcMMO 固定为 `1000/10`；属性上限、称号经验上限和每日经验上限仍由服务端硬校验，不能通过配置绕过。

## 奖励、称号、礼包和侧边栏联动

- 签到、成就、CDK、礼包和商城通过统一的 `grantSkillXp()` 入口发放经验，不直接修改玩家 SavedData。
- 称号的 `SKILL_XP` 效果只给经验结算增加有限倍率，不提高技能属性或被动额外掉落上限。
- 侧边栏和文本模板可使用本页的技能占位符；模板渲染发生在服务端，外部 Placeholder API 缺失时仍能显示内置值。

内置占位符示例：

| 占位符 | 含义 |
| --- | --- |
| `%skill_engine%` | 当前引擎（`mcmmo`、`professional` 或 `legacy`） |
| `%skill_power_level%` | 总战力等级 |
| `%skill_level_mining%` | Mining 当前等级 |
| `%skill_xp_mining%` / `%skill_xp_total_mining%` | 当前等级内经验 / 累计经验 |
| `%skill_ability_level_mining%` | Mining 能力等级 |
| `%skill_ability_cooldown_mining%` | 主动技能剩余冷却（秒） |
| `%skill_ability_active_mining%` | 主动技能剩余持续时间（秒） |

其他技能将 `mining` 替换成对应 canonical ID。旧 ID 只在服务端解析时兼容，不建议写进新的侧边栏模板。

## 旧数据迁移与回滚

旧的 professional/legacy 配置会在首次读取时切换到 mcMMO 引擎设置，并保留管理员自定义的树、文案和倍率。旧世界的树数据通过别名在第一次访问时映射到 canonical ID；原始 SavedData 不会被删除。迁移前应备份世界 `data/` 和技能配置，确认 `/skills stats` 的等级和累计经验后再继续运营。

如果旧数据缺少足够来源信息，兼容层不会把经验随机拆到多个新技能；应保留为待人工处理的 legacy credit，再通过后续迁移工具兑换。当前发行版尚未提供 `/skills migrate` 管理命令。

## 操作账本与故障安全

XP 操作和被动/主动副作用使用不同的操作 ID namespace。带操作 ID 的 XP 会在进度修改前立即写入 `omnitools_skill_xp_transactions` 的 `PREPARED` 前后快照；后续进度与 `COMMITTED` 状态在主线程每 20 tick 合并保存，停服时强制刷写。启动恢复只处理 `PREPARED`：当前进度等于目标快照时确认提交，等于原快照时补齐目标快照；两者均不匹配时保留事务供人工检查，绝不覆盖未知进度。`COMMITTED` 与 `ROLLED_BACK` 均保持终态。回滚记录允许使用同一操作 ID 安全重试。被动/主动副作用仍使用 `omnitools_skill_ledger`：

- 重放同一事件只会返回重复操作，不会再次发放经验、效果或掉落。
- 账本按玩家保存并有界裁剪；测试世界缺少 SavedData 时退回有界内存账本并默认拒绝重复操作。
- 发生异常时由技能模块故障边界隔离单次事件，记录技能、玩家、世界、坐标、操作 ID、等级、能力状态和降级动作。
- 服务器重启不会重置主动技能冷却或已确认的操作 ID。

## 尚未实现与后续阶段

以下内容属于规划或第二阶段：Fishing、Taming、Salvage、Smelting、独立 Defense/Support/Exploration 技能、Party 经验共享、`/skills top` 排行榜，以及对真正 mcMMO 的双向数据导入。它们不能作为当前配置可用字段或已上线玩法宣传。

开发和升级时请同时检查 [配置迁移指南](../guides/upgrade-guide.md)、[奖励一致性](../guides/reward-consistency.md) 和 [文档地图](../maintainers/document-map.md)。
