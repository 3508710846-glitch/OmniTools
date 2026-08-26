# 称号

## 1. 模块用途与适用场景

称号模块允许玩家拥有、佩戴和卸下称号。它只使用原版服务端的聊天、队伍和箱子界面；原版客户端可以直接连接。

## 2. 前置条件、关联模块与模块开关

- 根开关：`config/omnitools/config.json` 中的 `modules.titles.enabled`。
- 称号定义：`config/omnitools/titles/config.json`。
- 称号效果定义：`config/omnitools/title_effects/config.json`；该模块关闭时，称号仍可佩戴，但不会提供效果。

## 3. 配置文件路径与修改后指令

修改配置后执行 `/omnitools reload`。重载失败会继续使用原有有效快照。

## 4. 最小可用配置

最小配置定义一个可佩戴的 `common` 称号；效果和临时授权可在后续章节增加。

## 5. 注释教学版 `jsonc`

下面的 `jsonc` 仅用于教学，包含注释，不能直接复制到真实 JSON 文件。

```jsonc
{
  "format_version": 1, // 当前称号定义格式
  "nameplate_mode": "scoreboard_team", // 头顶显示；也可用 disabled
  "team_conflict_policy": "preserve_external_team", // 有其他队伍模组时优先保留其队伍
  "titles": [
    {
      "id": "vip", // 稳定 ID，奖励和玩家数据均以此关联
      "display": "&6[VIP]&r ", // 可见显示文本，可用颜色格式
      "rarity": "legendary", // common、rare 或 legendary
      "effects": ["vip_speed"], // title_effects 中的效果 ID；可为空数组
      "tooltip": ["&e限时 VIP 称号"] // 称号 GUI Lore
    }
  ]
}
```

## 6. 可直接复制版 `json`

可直接复制的严格 JSON：

```json
{
  "format_version": 1,
  "nameplate_mode": "scoreboard_team",
  "team_conflict_policy": "preserve_external_team",
  "titles": [
    {
      "id": "vip",
      "display": "&6[VIP]&r ",
      "rarity": "legendary",
      "effects": ["vip_speed"],
      "tooltip": ["&e限时 VIP 称号"]
    }
  ]
}
```

## 7. 字段表

| 字段 | 类型 | 必填 | 规则与默认值 |
| --- | --- | --- | --- |
| `format_version` | 整数 | 否 | 当前为 `1`。 |
| `nameplate_mode` | 字符串 | 否 | `scoreboard_team`（默认）或 `disabled`。 |
| `team_conflict_policy` | 字符串 | 否 | `omnitools_priority`（默认）或 `preserve_external_team`。 |
| `titles[].id` | 字符串 | 是 | 1-64 位小写字母、数字、`_`、`.`、`-`，必须唯一。不要重命名已发布的 ID。 |
| `titles[].display` | 字符串 | 是 | 1-128 个可见字符。 |
| `titles[].rarity` | 字符串 | 是 | `common`、`rare`、`legendary`。 |
| `titles[].effects` | 字符串数组 | 否 | 已定义的称号效果 ID。 |
| `titles[].tooltip` | 字符串数组 | 否 | 每行最多 256 个字符。 |

## 8. 全部配置场景

### 8.1 临时称号奖励

临时称号不是称号定义中的字段，而是统一 `title` 奖励的 `duration`。每日签到、在线奖励和成就都使用同一种奖励定义，完整语法见[统一奖励](../reference/rewards.md#称号的有效佩戴时长)。

`active_days` 只在玩家同时在线且佩戴该称号时消耗：

```text
1 active_day = 24 小时有效佩戴时间 = 1,728,000 个服务器 tick
```

- 拥有但未佩戴、离线、切换到其他称号以及服务器停机时，时间不会减少。
- 称号效果开关不影响计时；它只控制效果，不改变佩戴状态。
- 过期时服务器会在同一次状态更新中撤销授权、卸下称号、移除效果并刷新显示。
- 删除称号定义不会删除玩家的授权；该称号会暂时不可见且不计时，重新加入同 ID 定义后恢复。
- 省略 `duration` 时为永久称号，完全兼容旧奖励配置和旧玩家数据。

## 9. 指令、权限节点与默认角色

称号 GUI 的每张称号卡会显示永久状态或剩余有效佩戴时间。过期称号会自动从可选列表移除。

| 指令 | 权限节点 | 默认角色 |
| --- | --- | --- |
| `/omnitools title`、`/omnitools titles`、`/titles` | `omnitools.title.open` | PLAYER |
| `/omnitools titles time` | `omnitools.title.open` | PLAYER |
| `/omnitools titles select <id>` | `omnitools.title.open` | PLAYER |
| `/omnitools titles clear` | `omnitools.title.open` | PLAYER |
| `/omnitools titles admin grant <player> <title> <days\|permanent>` | `omnitools.title.grant` | ADMIN |
| `/omnitools titles admin revoke <player> <title>` | `omnitools.title.revoke` | ADMIN |

管理员以天数授予的临时称号默认采用 `extend` 续期策略。旧的 `give`、`add`、`remove`、`take` 指令保留，授予结果为永久称号。

## 10. 可用占位符与示例

除下方临时称号占位符外，基础称号占位符包括 `title_id`、`title`、`title_plain`、`title_effects_enabled`。

临时称号还提供：

- `%omnitools:title_remaining_days%`
- `%omnitools:title_remaining_hours%`
- `%omnitools:title_remaining_hms%`
- `%omnitools:title_is_temporary%`
- `%omnitools:title_is_equipped%`

没有可显示的已佩戴称号时，数值为 `0`、时间为 `00:00:00`、布尔值为 `false`。完整列表见[内置占位符](../reference/placeholders.md)。

## 11. 数据保存位置及升级影响

玩家称号保存于世界 SavedData `omnitools_titles`。新的授权记录保存模式、剩余有效 tick、累计授予 tick、授予时间和续期策略；旧的 `unlocked` 字段继续写入以保持兼容。

已有玩家的旧 `unlocked` 称号会自动迁移为永久授权，并保留原有 `selected` 和 `effects_enabled` 状态。不会重置货币、签到、成就、称号或奖励账本。

## 12. 验收步骤与故障排查

1. 用管理员指令授予 1 天临时称号，执行 `/titles select vip`，再执行 `/titles time`。
2. 保持在线佩戴约一分钟，剩余时间应减少约一分钟；执行 `/titles clear` 后不再减少。
3. 重启服务器后再次查询，剩余时间应保留。
4. 时间用尽后，该称号不再出现在 GUI 可选列表，也不再提供显示或效果。
5. 若 `/omnitools reload` 报错，修正 JSON 后重试；失败的重载不会覆盖正在运行的配置。
