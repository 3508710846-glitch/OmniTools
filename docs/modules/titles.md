# 称号

## 1. 用途与场景

称号可由奖励或管理员授予，玩家在原版箱子中选择佩戴；显示文本、稀有度、说明和关联效果均可配置。

## 2. 前置条件、关联模块与开关

根开关为 `modules.titles.enabled`。效果列表依赖 `title_effects`；含称号奖励的模块也依赖此模块。

## 3. 配置路径与重载

文件为 `config/omnitools/titles/config.json`，修改后 `/omnitools reload`。

## 4--6. 最小配置、教学版与可复制版

教学版，不能直接复制：

```jsonc
{
  "format_version": 1,
  "nameplate_mode": "scoreboard_team", // 或 disabled
  "team_conflict_policy": "preserve_external_team", // 不抢占外部队伍
  "titles": [{
    "id": "geologist",
    "display": "&7[地质学家]&r ",
    "rarity": "common",
    "effects": ["health_2"],
    "tooltip": ["&c生命上限 +4"]
  }]
}
```

可直接复制版：

```json
{
  "format_version": 1,
  "nameplate_mode": "scoreboard_team",
  "team_conflict_policy": "preserve_external_team",
  "titles": [
    {
      "id": "geologist",
      "display": "&7[地质学家]&r ",
      "rarity": "common",
      "effects": ["health_2"],
      "tooltip": ["&c生命上限 +4"]
    }
  ]
}
```

## 7. 字段表

| 字段 | 类型 | 必填 | 默认/范围 | 常见错误 |
| --- | --- | --- | --- | --- |
| `nameplate_mode` | `scoreboard_team` / `disabled` | 否 | `scoreboard_team` | 写不存在的模式。 |
| `team_conflict_policy` | `omnitools_priority` / `preserve_external_team` | 否 | `omnitools_priority` | 忽略已有队伍冲突。 |
| `titles` | 数组 | 是 | ID 唯一 | 使用重复 ID。 |
| `display` | 文本 | 是 | 可见文字不超过 128 | 空文本。 |
| `rarity` | 稀有度 | 是 | `common`、`rare`、`legendary` | 使用不存在的 `epic`。 |
| `effects` / `tooltip` | 字符串数组 | 否 | 效果 ID 需存在 | 引用未定义效果。 |

## 8. 全部配置场景

`scoreboard_team` 使用队伍前缀显示头顶称号；有其他队伍模组时推荐 `preserve_external_team`。不想显示头顶文本则使用 `disabled`，不影响佩戴、占位符或效果状态。

## 9. 指令、权限与默认角色

`/omnitools title` 默认 `PLAYER`；`grant`、`revoke` 默认 `ADMIN`。是否允许原生命令授予由权限模块的 `allow_title_command_grants` 控制。

## 10. 占位符

`%title_id%`、`%title%`、`%title_plain%`、`%title_effects_enabled%`。

## 11. 数据与升级

拥有和佩戴状态保存在 SavedData。重命名 ID 会使旧奖励或已拥有记录无法对应，应只改显示文本。

## 12. 验收与排错

授予一个称号，打开称号 GUI 佩戴并关闭效果；验证外部队伍存在时采用所选冲突策略。
