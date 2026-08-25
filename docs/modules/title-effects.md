# 称号效果

## 1. 功能简介

称号效果为玩家已选择的称号添加药水、属性、粒子或受限的原生权限效果。称号配置中的 `effects` 数组通过效果 ID 引用本模块定义。

## 2. 模块开关

根配置为 `config/omnitools/config.json`：

```json
{
  "modules": {
    "title_effects": { "enabled": true }
  }
}
```

模块管理 GUI 在 `titles` 禁用时会拒绝启用 `title_effects`。直接编辑根配置时，只有效果定义为空时，`title_effects: true` 与 `titles: false` 的候选快照才可通过；只要存在任何效果定义，`titles` 就必须启用。当效果定义非空且效果模块已启用时不能关闭 `titles`。禁用效果模块会立即移除其管理的全部效果，不删除称号和玩家数据。

## 3. 初始配置

首次加载生成 `config/omnitools/title_effects/config.json`。以下是完整的七项默认定义：

```json
{
  "format_version": 1,
  "speed_1": {
    "name": "速度 I",
    "type": "POTION",
    "effect": "minecraft:speed",
    "amplifier": 0,
    "duration": -1,
    "display": "&a移动速度提升 20%"
  },
  "speed_2": {
    "name": "速度 II",
    "type": "POTION",
    "effect": "minecraft:speed",
    "amplifier": 1,
    "duration": -1,
    "display": "&a移动速度提升 40%"
  },
  "resistance_1": {
    "name": "抗性提升 I",
    "type": "POTION",
    "effect": "minecraft:resistance",
    "amplifier": 0,
    "duration": -1,
    "display": "&a抗性提升 I（减少所受伤害）"
  },
  "health_2": {
    "name": "生命提升 II",
    "type": "ATTRIBUTE",
    "attribute": "minecraft:generic.max_health",
    "operation": "ADDITION",
    "amount": 4.0,
    "display": "&c♥ 生命上限 +4"
  },
  "night_vision": {
    "name": "夜视",
    "type": "POTION",
    "effect": "minecraft:night_vision",
    "amplifier": 0,
    "duration": -1,
    "display": "&f永久夜视（无需药水）"
  },
  "fire_resistance": {
    "name": "防火",
    "type": "POTION",
    "effect": "minecraft:fire_resistance",
    "amplifier": 0,
    "duration": -1,
    "display": "&6免疫火焰伤害"
  },
  "particle_redstone": {
    "name": "红石粒子",
    "type": "PARTICLE",
    "particle": "minecraft:redstone",
    "frequency": 10,
    "display": "&c行走时飘落红石粒子"
  }
}
```

文件缺失时创建完整默认定义；损坏时不覆盖文件，旧快照继续运行。

## 4. 指令与权限

本模块没有独立命令；效果随称号选择与个人效果开关应用。相关入口如下：

| 指令 | 别名 | 用途 | 默认权限 | 仅玩家 |
| --- | --- | --- | --- | --- |
| `/omnitools title [open]` | `/checkin title [open]`、`/title [open]` | 在称号菜单选择称号、切换个人效果开关 | `title.open` (`PLAYER`) | 是 |
| `/omnitools reload` | 无 | 重载效果定义 | `config.reload` (`ADMIN`) | 否 |

管理员通过称号命令授予或回收称号；这些命令的权限见 [titles.md](titles.md)。

## 5. 配置字段

所有效果 ID 是根对象的键，必须匹配 `[a-z0-9_.-]{1,64}`；也可用 `effects` 对象包装定义。

| 字段 | JSON 类型 | 必填 | 默认值/范围 | 作用与错误行为 |
| --- | --- | --- | --- | --- |
| `format_version` | 任意 JSON 值（当前未读取） | 否 | 首次生成写入 `1` | 当前读取器忽略该字段；它仅作为生成文件的格式标记。 |
| `<效果ID>.name` | string | 否 | 效果 ID | 管理名称。 |
| `<效果ID>.type` | string | 是 | `POTION`、`ATTRIBUTE`、`PARTICLE`、`PERMISSION` | 效果类型。 |
| `<效果ID>.effect` | string | `type=POTION` 时是 | 有效药水 ID | 为空或无效 ID 拒绝配置。 |
| `<效果ID>.amplifier` | integer | 否，`POTION` 使用 | `0`，`>= 0` | 药水等级，原版从 0 起计。 |
| `<效果ID>.duration` | integer | 否，`POTION` 使用 | `-1`；`-1` 或正整数 | `-1` 为无限时长；`0` 与小于 `-1` 的值拒绝配置。 |
| `<效果ID>.attribute` | string | `type=ATTRIBUTE` 时是 | 无 | 有效属性 ID，不能为空。 |
| `<效果ID>.operation` | string | 否，`ATTRIBUTE` 使用 | `ADDITION` | 可用 `ADDITION`、`ADD_VALUE`、`ADD_MULTIPLIED_BASE`、`MULTIPLY_BASE`、`ADD_MULTIPLIED_TOTAL`、`MULTIPLY_TOTAL`。 |
| `<效果ID>.amount` | number | 否，`ATTRIBUTE` 使用 | `0.0`，有限数 | 属性修正值；非有限数拒绝配置。 |
| `<效果ID>.particle` | string | `type=PARTICLE` 时是 | 无 | 有效粒子 ID，不能为空。 |
| `<效果ID>.frequency` | integer | 否，`PARTICLE` 使用 | `10`，`>= 1` | 每隔多少玩家 tick 尝试显示粒子。 |
| `permission` | string | `PERMISSION` | 有效 ID | 仅允许 `omnitools:cloud_storage` 或 `omnitools:command.*`；后者还受权限配置总开关限制。 |
| `display` | string | 否 | `name` | 在称号界面展示的效果文字。 |

## 6. 使用示例

```json
{
  "format_version": 1,
  "slow_fall": {
    "name": "缓降",
    "type": "POTION",
    "effect": "minecraft:slow_falling",
    "amplifier": 0,
    "duration": -1,
    "display": "&b缓慢下落"
  }
}
```

在 `titles/config.json` 的称号中加入 `"effects": ["slow_fall"]`，再执行 `/omnitools reload`。无效注册表 ID、权限 ID 或参数会拒绝整个候选快照。

## 7. 数据保存

效果定义保存在 JSON。玩家是否启用称号效果和选择的称号保存在世界 `TitleData`；运行时药水、属性、粒子和权限状态不作为配置数据保存。

升级或迁移前必须同时备份世界目录和 `config/omnitools/`。

## 8. 热重载与依赖

成功重载会刷新在线玩家的称号效果；禁用模块调用全量清理。模块管理 GUI 要求先启用 `titles`，且只要定义非空，快照校验也要求 `titles` 启用；称号引用的效果 ID 必须存在。任一配置错误时旧快照和现有效果继续运行。
