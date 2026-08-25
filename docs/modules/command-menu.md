# 命令菜单

## 1. 功能简介

命令菜单让服主用 JSON 配置原版 27 或 54 格箱子菜单。每个格子可配置图标、名称、Lore、光效、左右键动作；所有点击、跳转和命令均由服务端执行。它使用原版 `GENERIC_9x3` 或 `GENERIC_9x6`，原版客户端无需安装 OmniTools。

## 2. 模块开关

根配置为 `config/omnitools/config.json`：

```json
{
  "modules": {
    "command_menu": { "enabled": true }
  }
}
```

禁用后命令菜单入口拒绝且已打开菜单关闭，配置文件保留。重新启用后重新读取注册表和菜单文件。

## 3. 初始配置

首次加载创建：

```text
config/omnitools/command_menu/config.json
config/omnitools/command_menu/menus/
```

注册表默认为空：

```json
{
  "format_version": 1,
  "menus": [],
  "allow_console_commands": false
}
```

没有注册菜单时不会生成示例菜单。注册菜单但其文件缺失时，会创建以下空 27 格页面。任一文件错误时不覆盖原文件，旧快照继续运行。

```json
{
  "format_version": 1,
  "title": "空菜单",
  "size": 27,
  "items": []
}
```

## 4. 指令与权限

| 指令 | 别名 | 用途 | 默认权限 | 仅玩家 |
| --- | --- | --- | --- | --- |
| `/omnitools menu` | 无 | 打开 ID 为 `main` 的菜单 | `command_menu.open` (`PLAYER`) | 是 |
| `/omnitools menu open <id>` | 无 | 打开指定菜单 | `command_menu.open` (`PLAYER`) | 是 |
| `/omnitools menu main` | 无 | 打开 `main` 菜单 | `command_menu.open` (`PLAYER`) | 是 |
| `/omnitools menu close` | 无 | 关闭当前命令菜单 | `command_menu.close` (`PLAYER`) | 是 |

打开时还必须满足注册表中该菜单的 `permission`。子菜单跳转会再次检查目标菜单权限。

## 5. 配置字段

注册表 `command_menu/config.json`：

| 字段 | JSON 类型 | 必填 | 默认值/范围 | 作用与错误行为 |
| --- | --- | --- | --- | --- |
| `format_version` | number | 否 | `1`，正整数 | 格式标记。 |
| `menus` | array | 是 | `[]` | 菜单注册项。 |
| `menus[].id` | string | 是 | `[a-z0-9_.-]{1,64}`，唯一 | 菜单跳转 ID。 |
| `menus[].file` | string | 是 | 单层 `.json` 文件名 | 仅允许 `menus/` 内安全文件名，禁止路径与 `..`。 |
| `menus[].permission` | string | 是 | `PLAYER`/`MODERATOR`/`ADMIN`/`OWNER` | 菜单级角色门槛。 |
| `allow_console_commands` | boolean | 否 | `false` | 是否允许 `run_as: "console"`。 |

菜单页 `command_menu/menus/<file>.json`：

| 字段 | JSON 类型 | 必填 | 默认值/范围 | 作用与错误行为 |
| --- | --- | --- | --- | --- |
| `format_version` | number | 否 | `1`，正整数 | 格式标记。 |
| `title` | string | 是 | 非空 | 菜单标题，支持 `&` 颜色。 |
| `size` | integer | 否 | `27`，仅 `27` 或 `54` | 箱子大小。 |
| `filler` | object | 否 | 空气 | 填充未配置格；字段规则见下表。 |
| `items` | array | 否 | `[]`，最多 54 项 | 按格子配置的按钮。 |
| `items[].slot` | integer | 是 | 0 到 `size - 1`，唯一 | 格子位置。 |
| `items[].item` | string | 是 | 有效且非空气物品 ID | 按钮图标。 |
| `items[].amount` | integer | 否 | `1`，`1-64` | 图标数量。 |
| `items[].name` | string | 否 | 无 | 自定义名称。 |
| `items[].lore` | string array | 否 | `[]` | Lore，最多原版允许的行数。 |
| `items[].glow` | boolean | 否 | `false` | 是否显示附魔光效。 |
| `left_click`、`right_click` | array | 否 | `[]`，各最多 8 项 | 对应鼠标键动作。 |

`filler` 使用与按钮相同的显示字段，但没有槽位、光效或点击动作：

| 字段 | JSON 类型 | 必填 | 默认值/范围 | 作用与错误行为 |
| --- | --- | --- | --- | --- |
| `filler.item` | string | 是 | 有效且非空气物品 ID | 未配置按钮的填充物；无效或空气 ID 会拒绝配置。 |
| `filler.amount` | integer | 否 | `1`，`1-64` | 填充物数量；范围外或非整数会拒绝配置。 |
| `filler.name` | string | 否 | 无 | 填充物显示名称，支持 `&` 颜色代码；非字符串会拒绝配置。 |
| `filler.lore` | string array | 否 | `[]`，最多原版允许的 Lore 行数 | 填充物说明；任一元素不是字符串或行数过多会拒绝配置。 |

每个 `items[].left_click[]` 或 `items[].right_click[]` 动作对象的字段如下：

| 字段 | JSON 类型 | 必填 | 默认值/范围 | 作用与错误行为 |
| --- | --- | --- | --- | --- |
| `type` | string | 是 | `open_menu`、`close_menu`、`message`、`command` | 动作类型；未知值拒绝配置。 |
| `menu` | string | `type=open_menu` 时是 | 已注册菜单 ID | 直接跳转目标菜单；目标不存在拒绝配置。 |
| `text` | string | `type=message` 时是 | 非空 | 向点击者显示服务端解析后的文本。 |
| `command` | string | `type=command` 时是 | 非空，最多 1024 字符 | 仅执行配置中固定的命令文本。 |
| `run_as` | string | 否，仅 `command` 使用 | `player`；可为 `player` 或 `console` | 控制台命令需要 `allow_console_commands: true`；非法值拒绝配置。 |

命令替换变量仅允许 `{player_name}`、`{player_uuid}`、`{player_x}`、`{player_y}`、`{player_z}`、`{player_world}`。`open_menu` 和 `close_menu` 执行后会停止本次点击的后续动作。普通左/右键以外的 Shift、拖拽、双击和数字键操作均由服务端拒绝。

## 6. 使用示例

注册表：

```json
{
  "format_version": 1,
  "menus": [
    { "id": "main", "file": "main.json", "permission": "PLAYER" }
  ],
  "allow_console_commands": false
}
```

`menus/main.json`：

```json
{
  "format_version": 1,
  "title": "&6服务器菜单",
  "size": 27,
  "items": [
    {
      "slot": 13,
      "item": "minecraft:clock",
      "name": "&e每日签到",
      "left_click": [
        { "type": "command", "run_as": "player", "command": "omnitools open" },
        { "type": "close_menu" }
      ]
    }
  ]
}
```

修改后运行 `/omnitools reload`。缺失目标菜单、越界槽位、无效物品或未授权控制台命令会拒绝配置；旧菜单继续可用。

## 7. 数据保存

命令菜单只保存 JSON 定义，不保存玩家进度。当前打开的菜单是临时服务端容器；玩家退出或菜单关闭后不保留状态。

升级或迁移前必须同时备份世界目录和 `config/omnitools/`。

## 8. 热重载与依赖

成功重载刷新命令树和已打开页面；被删除的菜单、禁用模块或失去权限的玩家菜单会关闭。页面大小变化时会关闭并重新打开同一菜单。普通和 Shift/拖拽/双击/数字键等非普通点击均被服务端拒绝，快速移动返回空。统一快照失败时当前菜单不关闭。
