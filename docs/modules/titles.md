# 称号

## 1. 模块用途和适用场景

称号模块管理称号定义、玩家拥有状态与当前佩戴。佩戴后的文本可显示在聊天、Tab 列表和头顶；头顶显示使用服务端原版计分板队伍前缀，不要求客户端模组。

## 2. 模块依赖与关联模块

模块 ID 为 `titles`。`title_effects` 的效果定义与签到、在线奖励、成就的 `title` 奖励都会引用它。效果定义非空时不能关闭本模块。

## 3. 模块开关配置

```json
{ "modules": { "titles": { "enabled": true } } }
```

禁用会关闭称号 GUI，移除 OmniTools 的聊天、Tab 与头顶显示；玩家拥有与选择状态仍保留。

## 4. 初始配置文件位置

首次启动生成 `config/omnitools/titles/config.json`。修改后执行 `/omnitools reload`。

## 5. 最小可用配置

```json
{
  "format_version": 1,
  "titles": [
    { "id": "builder", "display": "&b[建筑师] ", "rarity": "rare" }
  ]
}
```

## 6. 完整配置示例

```json
{
  "format_version": 1,
  "nameplate_mode": "scoreboard_team",
  "team_conflict_policy": "preserve_external_team",
  "titles": [
    {
      "id": "builder",
      "display": "&b[建筑师] ",
      "rarity": "rare",
      "effects": ["speed_1"],
      "tooltip": ["&7活动奖励", "&a移动速度提升"]
    }
  ]
}
```

## 7. 配置字段表

| 字段 | 类型 | 必填 | 默认值或范围 | 重载方式 |
| --- | --- | --- | --- | --- |
| `format_version` | integer | 否 | 首次生成 `1` | reload |
| `nameplate_mode` | string | 否 | `scoreboard_team` 或 `disabled` | reload |
| `team_conflict_policy` | string | 否 | `omnitools_priority` 或 `preserve_external_team` | reload |
| `titles` | array | 是 | 称号列表 | reload |
| `titles[].id` | string | 是 | `[a-z0-9_.-]{1,64}`，唯一 | reload |
| `display` | string | 是 | 最多 128 字符，支持 `&` 颜色 | reload |
| `rarity` | string | 是 | `common`、`rare`、`legendary` | reload |
| `effects` | string array | 否 | 已定义效果 ID，不能重复 | reload |
| `tooltip` | string array | 否 | 每行最多 256 字符 | reload |

## 8. 指令、别名和权限节点

| 指令 | 别名 | 权限节点 | 默认角色 |
| --- | --- | --- | --- |
| `/omnitools title [open]` | `/checkin title [open]`、`/title [open]` | `title.open` | PLAYER |
| `/omnitools title give|add <player> <titleId>` | `/title give|add ...` | `title.grant` | ADMIN |
| `/omnitools title remove|take <player> <titleId>` | `/title remove|take ...` | `title.revoke` | ADMIN |

## 9. GUI 操作说明

称号菜单展示玩家拥有的称号、稀有度、说明和效果。点击拥有的称号进行佩戴或切换个人效果开关；玩家不能取走菜单物品。授予、回收和 reload 后在线玩家立即刷新。

## 10. 占位符列表及用途

`%omnitools:title_id%`、`title`、`title_plain` 和 `title_effects_enabled` 可用于侧边栏及其他可配置文本。未佩戴或模块关闭时返回空文本或 `false`。

## 11. 数据保存位置和升级影响

玩家名称映射、拥有称号、当前选择和个人效果开关保存在世界 `TitleData`。删除 JSON 定义不会自动删除既有玩家数据；升级前不要重命名已被奖励或命令引用的称号 ID。

## 12. 与其他模块的联动

称号效果引用 `title_effects`。签到、在线奖励和成就可以授予称号；侧边栏可显示当前称号。头顶显示与外部队伍系统有原版协议层面的单队伍限制。

## 13. 常见错误及解决方法

| 现象 | 处理 |
| --- | --- |
| 头顶没有称号 | 检查 `nameplate_mode`、是否已佩戴，以及外部队伍冲突策略。 |
| reload 失败 | 检查重复 ID、无效稀有度、过长文本或不存在的效果 ID。 |
| 奖励称号被阻塞 | 启用本模块并定义相同称号 ID，然后在奖励箱重试。 |

## 14. 可复制的验收清单

- [ ] 授予、选择和回收称号后，GUI、聊天和 Tab 同步刷新。
- [ ] 两种外部队伍冲突策略均符合预期。
- [ ] 称号奖励只能引用存在的 ID。
- [ ] 禁用模块时显示清理，SavedData 保留。
