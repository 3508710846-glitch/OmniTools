# 命令菜单

## 1. 模块用途和适用场景

命令菜单将可配置的物品、Lore、左右键动作和子菜单组织为原版箱子 GUI。它适合提供传送、帮助或服务器功能入口；所有点击、权限和命令校验均在服务端完成。

## 2. 模块依赖与关联模块

模块 ID 为 `command_menu`。控制台命令动作还依赖根配置的命令白名单、最大长度、冷却和 `allow_console_commands`；玩家可见文本可使用内置与可选第三方占位符。

## 3. 模块开关配置

```json
{ "modules": { "command_menu": { "enabled": true } } }
```

禁用会关闭所有命令菜单并拒绝打开、跳转和执行动作，配置文件与玩家数据不受影响。

## 4. 初始配置文件位置

注册表首次生成在 `config/omnitools/command_menu/config.json`，页面文件位于 `config/omnitools/command_menu/menus/`。修改任一文件后执行 `/omnitools reload`。

## 5. 最小可用配置

注册表：

```json
{
  "format_version": 1,
  "menus": [{ "id": "main", "file": "main.json", "permission": "PLAYER" }],
  "allow_console_commands": false
}
```

页面 `menus/main.json`：

```json
{ "format_version": 1, "title": "服务器菜单", "size": 27, "items": [] }
```

## 6. 完整配置示例

注册表：

```json
{
  "format_version": 1,
  "menus": [{ "id": "main", "file": "main.json", "permission": "PLAYER" }],
  "allow_console_commands": true
}
```

页面 `menus/main.json`：

```json
{
  "format_version": 1,
  "title": "&b服务器菜单",
  "size": 27,
  "filler": { "item": "minecraft:black_stained_glass_pane", "amount": 1, "name": " " },
  "items": [
    {
      "slot": 13,
      "item": "minecraft:compass",
      "amount": 1,
      "name": "&e返回大厅",
      "lore": ["&7点击执行 /spawn"],
      "left_click": [{ "type": "command", "run_as": "console", "command": "spawn" }],
      "right_click": [{ "type": "close_menu" }]
    }
  ]
}
```

示例只有在根配置白名单包含 `spawn` 时才可执行。

## 7. 配置字段表

| 字段 | 类型 | 必填 | 默认值或范围 | 重载方式 |
| --- | --- | --- | --- | --- |
| `config.json.format_version` | integer | 否 | 首次生成 `1` | reload |
| `menus` | object array | 是 | 每项具有唯一 `id`、安全 `.json` `file` 和 `permission` 角色 | reload |
| `allow_console_commands` | boolean | 否 | `false` | reload |
| 页面 `title` | string | 是 | 服务端渲染文本 | reload |
| 页面 `size` | integer | 是 | `27` 或 `54` | reload |
| `filler` | object | 否 | `item`、`amount`、`name`、`lore` | reload |
| `items[].slot` | integer | 是 | 唯一且小于 `size` | reload |
| `item`、`amount`、`name`、`lore`、`glow` | 物品字段 | 是/否 | 有效物品；数量 1-64 | reload |
| `left_click`、`right_click` | array | 否 | 每侧最多 8 个动作 | reload |
| 动作 `type` | string | 是 | `open_menu`、`close_menu`、`command`、`message` | reload |
| 动作字段 | object | 按类型 | open 使用 `menu`；command 使用 `run_as` 与 `command`；message 使用 `message` | reload |

## 8. 指令、别名和权限节点

| 指令 | 别名 | 权限节点 | 默认角色 |
| --- | --- | --- | --- |
| `/omnitools menu`（打开 `main`）或 `/omnitools menu open <id>` | 无 | `command_menu.open` | PLAYER |
| `/omnitools menu close` | 无 | `command_menu.close` | PLAYER |
| `/omnitools reload` | 无 | `config.reload` | ADMIN |

## 9. GUI 操作说明

菜单使用原版 3 行或 6 行箱子。配置的左右键动作由服务端执行；`open_menu` 在已注册页面间跳转，`close` 关闭窗口。玩家不能拿走配置物品，点击频率受根 `cooldown_ticks` 限制。

## 10. 占位符列表及用途

标题、物品名称、Lore 和提示文本会使用统一文本渲染器，可使用 17 个内置占位符与可选 API 文本占位符。控制台命令 `value` 不会解析第三方文本占位符，只接受受控变量。

## 11. 数据保存位置和升级影响

菜单定义在 `config/omnitools/command_menu/`，不保存玩家业务进度。升级旧配置时，未曾存在的命令菜单模块会保持禁用；现有菜单文件会保留。

## 12. 与其他模块的联动

菜单可打开服务器命令可达的其他模块入口，并可显示货币、称号、签到、在线或成就占位符。命令安全属于根配置与奖励系统共享规则。

## 13. 常见错误及解决方法

| 现象 | 处理 |
| --- | --- |
| 菜单没有打开 | 检查模块开关、`command_menu.open` 权限、菜单 ID 和注册表文件名。 |
| 控制台命令被拒绝 | 设置 `allow_console_commands: true`，并将命令根加入根白名单。 |
| reload 失败 | 检查重复槽位、页面尺寸、物品 ID、未知子菜单或动作数量。 |

## 14. 可复制的验收清单

- [ ] 27 格和 54 格页面均可打开，左右键动作正确。
- [ ] 子菜单跳转与关闭正常，UI 物品不能取走。
- [ ] 白名单外的控制台命令无法执行。
- [ ] 关闭模块后已打开菜单立即关闭。
