# 命令菜单

## 1. 用途与场景

命令菜单是纯服务端原版箱子 GUI。注册表定义菜单 ID、文件和访问角色；页面文件定义 27 或 54 格物品及左/右键动作。

## 2. 前置条件、关联模块与开关

根开关为 `modules.command_menu.enabled`。命令动作还会经过根配置 `global.command_security` 白名单、长度和冷却限制。

## 3. 配置路径与重载

注册表：`config/omnitools/command_menu/config.json`；页面：`config/omnitools/command_menu/menus/<文件>.json`。修改两个文件后都执行 `/omnitools reload`。

## 4. 最小可用配置

最小菜单由注册表 `config.json` 和页面 `menus/main.json` 两个文件组成；下方两个文件必须同时写入对应路径。

## 5. 注释教学版 `jsonc`

下列代码带有注释，**不能直接复制到真实 `.json` 文件**。

注册表 `config.json`：

```jsonc
{
  "format_version": 1, // 命令菜单注册表格式版本。
  "allow_console_commands": false, // 控制台动作必须显式开启
  "menus": [{ "id": "main", "file": "main.json", "permission": "PLAYER" }] // 菜单 ID、页面文件和访问角色。
}
```

页面 `menus/main.json`：

```jsonc
{
  "format_version": 1, // 单个菜单页面格式版本。
  "title": "&b服务菜单", // 箱子 GUI 的标题。
  "size": 27, // 仅 27 或 54
  "items": [{ // 自定义菜单物品列表。
    "slot": 13, // 物品所在槽位。
    "item": "minecraft:compass", // 显示物品 ID。
    "name": "&a回主城", // 物品显示名称。
    "lore": ["&7左键传送"], // 物品 Lore 文本。
    "left_click": [{ "type": "command", "run_as": "player", "command": "spawn" }] // 左键以玩家身份执行 spawn。
  }]
}
```

## 6. 可直接复制版 `json`

注册表 `config.json`：

```json
{
  "format_version": 1,
  "allow_console_commands": false,
  "menus": [{ "id": "main", "file": "main.json", "permission": "PLAYER" }]
}
```

页面 `menus/main.json`：

```json
{
  "format_version": 1,
  "title": "&b服务菜单",
  "size": 27,
  "filler": { "item": "minecraft:black_stained_glass_pane", "name": " " },
  "items": [
    {
      "slot": 11,
      "item": "minecraft:compass",
      "name": "&a回主城",
      "lore": ["&7左键传送，右键关闭"],
      "left_click": [{ "type": "command", "run_as": "player", "command": "spawn" }],
      "right_click": [{ "type": "close_menu" }]
    },
    {
      "slot": 15,
      "item": "minecraft:paper",
      "name": "&e提示",
      "left_click": [{ "type": "message", "text": "&a欢迎，%title_plain%" }]
    }
  ]
}
```

## 7. 字段表

| 字段 | 类型 | 必填 | 默认/范围 | 常见错误 |
| --- | --- | --- | --- | --- |
| 注册表 `menus[].id` | 小写 ID | 是 | 1--64、唯一 | ID 重复。 |
| `file` | 单个 `.json` 文件名 | 是 | 只能在 `menus/` 下 | 写 `../` 路径。 |
| `permission` | 角色 | 是 | 四种角色 | 写 OP。 |
| 页面 `size` | 整数 | 否 | 27 或 54 | 写 36。 |
| `slot` | 整数 | 是 | 0 到 size-1、唯一 | 超出范围。 |
| `amount` | 整数 | 否 | 1--64 | 写 0。 |
| `left_click` / `right_click` | 动作数组 | 否 | 每侧最多 8 | 动作超过上限。 |
| 动作 `type` | `open_menu`/`close_menu`/`command`/`message` | 是 | 见示例 | 子菜单不存在。 |

## 8. 全部配置场景

`open_menu` 使用 `{ "type": "open_menu", "menu": "子菜单ID" }`；`close_menu` 无额外字段；`message` 使用 `text` 并可使用文本占位符。命令可 `run_as: "player"` 或 `"console"`，后者要求注册表 `allow_console_commands: true`。

命令只能使用受控 `{player_*}` 变量，禁止 `%...%`；根配置还必须允许命令根。例如上例的 `spawn` 需要 `allowed_roots` 包含 `spawn`。不使用 `*` 作为新服白名单。

## 9. 指令、权限与默认角色

`/omnitools menu <id>` 打开已注册菜单，默认 `PLAYER`；关闭动作默认 `PLAYER`。菜单自身还会检查注册表 `permission`。

## 10. 占位符

标题、名称、Lore 和 message 是玩家可见文本，可使用[内置](../reference/placeholders.md)与可选 API 占位符；命令内容例外。

## 11. 数据与升级

菜单配置不保存玩家数据。热重载会关闭失效菜单；禁用模块同样关闭已打开 GUI。

## 12. 验收与排错

确认 27 和 54 格菜单、左/右键、消息、关闭和子菜单跳转。命令被拒绝时检查根白名单及控制台开关。
