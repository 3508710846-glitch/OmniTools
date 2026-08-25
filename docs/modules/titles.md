# 称号

## 1. 功能简介

称号模块管理称号定义、玩家拥有的称号和当前佩戴称号。佩戴称号会影响聊天名称、Tab 列表和头顶显示；可选的称号效果由 [title-effects.md](title-effects.md) 管理。称号选择界面使用 OmniTools 的自定义菜单类型及客户端界面。

## 2. 模块开关

根配置为 `config/omnitools/config.json`：

```json
{
  "modules": {
    "titles": { "enabled": true }
  }
}
```

禁用后称号菜单和称号聊天/Tab/头顶显示停止，已保存的拥有与选择状态不删除。若称号效果模块启用且效果定义非空，不能直接禁用称号模块。

## 3. 初始配置

首次加载生成 `config/omnitools/titles/config.json`。以下是首次生成的完整默认配置；三个称号分别引用默认效果 `health_2`、`speed_1`、`resistance_1`/`night_vision`：

```json
{
  "format_version": 1,
  "titles": [
    {
      "id": "geologist",
      "display": "\u00a77[\u00a7r地质学家\u00a77] \u00a7r",
      "rarity": "common",
      "effects": ["health_2"],
      "tooltip": ["\u00a77佩戴效果：", "\u00a7c\u2665 生命上限 +4"]
    },
    {
      "id": "architect",
      "display": "\u00a7b[\u00a7r建筑师\u00a7b] \u00a7r",
      "rarity": "rare",
      "effects": ["speed_1"],
      "tooltip": ["\u00a77佩戴效果：", "\u00a7a\u2714 移动速度提升"]
    },
    {
      "id": "legend",
      "display": "\u00a76[\u00a7r传说\u00a76] \u00a7r",
      "rarity": "legendary",
      "effects": ["resistance_1", "night_vision"],
      "tooltip": ["\u00a77佩戴效果：", "\u00a7a\u2714 抗性提升 I", "\u00a7a\u2714 永久夜视"]
    }
  ]
}
```

文件缺失时创建完整默认列表；格式错误时不覆盖原文件，旧快照继续运行。

## 4. 指令与权限

| 指令 | 别名 | 用途 | 默认权限 | 仅玩家 |
| --- | --- | --- | --- | --- |
| `/omnitools title [open]` | `/checkin title [open]`、`/title [open]` | 打开称号菜单 | `title.open` (`PLAYER`) | 是 |
| `/omnitools title give|add <玩家> <称号ID>` | `/checkin title give|add ...`、`/title give|add ...` | 授予称号 | `title.grant` (`ADMIN`) | 否 |
| `/omnitools title remove|take <玩家> <称号ID>` | `/checkin title remove|take ...`、`/title remove|take ...` | 回收称号 | `title.revoke` (`ADMIN`) | 否 |

玩家在菜单中选择已拥有称号；授予或回收在线玩家时会立即刷新显示和效果。

## 5. 配置字段

| 字段 | JSON 类型 | 必填 | 默认值/范围 | 作用与错误行为 |
| --- | --- | --- | --- | --- |
| `format_version` | 任意 JSON 值（当前未读取） | 否 | 首次生成写入 `1` | 当前读取器忽略该字段；它仅作为生成文件的格式标记。 |
| `titles` | array | 是 | 默认 3 项 | 称号列表。 |
| `titles[].id` | string | 是 | `[a-z0-9_.-]{1,64}`，唯一 | 稳定称号 ID，命令和奖励引用它。 |
| `titles[].display` | string | 是 | 最多 128 字符 | 显示文本，支持 `&` 颜色；去除格式后必须有可见文本。 |
| `titles[].rarity` | string | 是 | `common`、`rare`、`legendary` | 菜单稀有度。`rare` 和 `legendary` 出现在 Tab，`legendary` 也显示在头顶；非法值拒绝配置。 |
| `titles[].effects` | string array | 否 | `[]` | 引用的效果 ID；不能重复。效果模块启用时必须存在对应定义。 |
| `titles[].tooltip` | string array | 否 | `[]` | 菜单说明，每行最多 256 字符。 |

## 6. 使用示例

```json
{
  "format_version": 1,
  "titles": [
    {
      "id": "builder",
      "display": "&b[建筑师]",
      "rarity": "rare",
      "effects": ["speed_1"],
      "tooltip": ["&7通过活动获得", "&a速度提升"]
    }
  ]
}
```

先确保被引用的效果 ID 有效，再执行 `/omnitools reload`。若出现未知效果或重复 ID，按日志修正；旧称号配置继续运行。

## 7. 数据保存

世界 `SavedData` 的 `TitleData` 保存玩家名称映射、已拥有称号、当前选择和个人效果开关。称号 JSON 仅保存定义；删除定义不会主动清除已有数据。

升级或迁移前必须同时备份世界目录和 `config/omnitools/`。

## 8. 热重载与依赖

重载成功后在线玩家的称号显示刷新，失去权限的称号菜单会关闭。`title_effects` 启用时称号引用必须通过校验；禁用称号效果会清理由其施加的效果而不删除称号数据。统一快照校验失败时旧显示规则保留。
