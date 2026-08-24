# 命令菜单配置指南

命令菜单是服务端配置模块，使用 Minecraft 原版 `GENERIC_9x3` 和 `GENERIC_9x6` 箱子菜单。客户端无需安装 OmniTools 即可看到菜单、点击按钮、执行动作和跳转子菜单。模块开关位于 `config/omnitools/config.json`：

```json
{
  "modules": {
    "command_menu": { "enabled": true }
  }
}
```

## 文件结构

```text
config/omnitools/command_menu/
├── config.json
└── menus/
    ├── main.json
    └── server.json
```

首次加载命令菜单模块时，如果注册表不存在，会生成空注册表和 `menus/` 文件夹。没有注册菜单时不会生成示例菜单或快捷按钮。注册了菜单但对应文件不存在时，只生成一个空的 27 格菜单文件。配置格式错误时重载失败，并继续使用上一份有效快照。

## 注册表

`command_menu/config.json` 只注册菜单，不保存格子内容：

```json
{
  "format_version": 1,
  "menus": [
    { "id": "main", "file": "main.json", "permission": "PLAYER" },
    { "id": "server", "file": "server.json", "permission": "PLAYER" },
    { "id": "admin", "file": "admin.json", "permission": "ADMIN" }
  ],
  "allow_console_commands": false
}
```

`id` 必须唯一，并匹配 `[a-z0-9_.-]{1,64}`。`file` 只能是 `menus/` 下的一层 `.json` 文件名，例如 `main.json`；不能包含 `..`、路径分隔符、绝对路径或盘符。`permission` 可用 `PLAYER`、`MODERATOR`、`ADMIN`、`OWNER`。`allow_console_commands` 默认为 `false`，关闭时任何 `run_as: "console"` 动作都会使配置校验失败。

## 菜单文件

例如 `command_menu/menus/main.json`：

```json
{
  "format_version": 1,
  "title": "&6服务器主菜单",
  "size": 27,
  "filler": {
    "item": "minecraft:gray_stained_glass_pane",
    "name": " "
  },
  "items": [
    {
      "slot": 11,
      "item": "minecraft:clock",
      "amount": 1,
      "name": "&e每日签到",
      "lore": ["&7领取每日签到奖励", "", "&a左键：打开签到界面"],
      "glow": true,
      "left_click": [
        { "type": "command", "run_as": "player", "command": "omnitools open" },
        { "type": "close_menu" }
      ]
    },
    {
      "slot": 13,
      "item": "minecraft:emerald",
      "name": "&a服务器功能",
      "left_click": [{ "type": "open_menu", "menu": "server" }]
    },
    {
      "slot": 15,
      "item": "minecraft:barrier",
      "name": "&c关闭菜单",
      "left_click": [{ "type": "close_menu" }]
    }
  ]
}
```

`size` 只能为 `27` 或 `54`；槽位从 `0` 开始，分别允许 `0-26` 或 `0-53`。`item` 必须是有效物品 ID，`amount` 为 `1-64`，同一菜单不能重复槽位，`items` 可以为空。`filler` 可选，用于填充未配置槽位。名称和 Lore 接受 `&` 颜色代码；`glow` 为 `true` 时显示附魔光效。

## 点击动作

每个物品可分别配置 `left_click` 和 `right_click`，每个数组最多 8 个动作。动作按数组顺序执行；`open_menu` 或 `close_menu` 执行后立即停止本次点击。

```json
{ "type": "open_menu", "menu": "server" }
{ "type": "close_menu" }
{ "type": "message", "text": "&c该功能暂未开放" }
{ "type": "command", "run_as": "player", "command": "omnitools balance" }
{ "type": "command", "run_as": "console", "command": "say {player_name} 打开了菜单" }
```

`open_menu` 会重新检查目标菜单的角色权限；`command` 的 `run_as` 默认为 `player`。控制台命令只有在注册表启用 `allow_console_commands` 后才允许。命令只接受以下服务端替换变量：`{player_name}`、`{player_uuid}`、`{player_x}`、`{player_y}`、`{player_z}`、`{player_world}`。未知变量会使配置校验失败。

## 命令与权限

```text
/omnitools menu open <menu_id>
/omnitools menu close
/omnitools menu main
```

`command_menu.open` 默认角色为 `PLAYER`，并且还需要菜单注册项自身的 `permission`。子菜单跳转会重新校验目标菜单，不能借由父菜单绕过权限。`command_menu.close` 默认角色为 `PLAYER`；玩家始终可以关闭自己打开的命令菜单。控制台不能打开或关闭玩家菜单。

## 安全与重载

所有点击和命令执行均在服务端完成。菜单只接受拥有者的普通左键、右键，拒绝 Shift 点击、拖拽、双击收集、数字键交换和快速移动；`quickMoveStack` 始终返回空。客户端不能提交任意命令文本，服务端只执行已加载快照中的固定命令。

`/omnitools reload` 会读取注册表和所有已注册菜单文件，全部校验成功后才替换配置快照并刷新命令树。新增、修改菜单会刷新已打开的菜单；被删除的菜单和模块被禁用时会立即关闭。任一文件错误时不替换旧快照、不关闭现有菜单，并将原因写入服务器日志。
