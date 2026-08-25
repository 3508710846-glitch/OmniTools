# 称号效果

## 1. 用途与场景

称号效果在玩家佩戴含该效果 ID 的称号时生效。支持药水、属性、粒子和权限四类效果，所有效果都由服务端处理。

## 2. 前置条件、关联模块与开关

必须同时启用 `modules.titles.enabled` 与 `modules.title_effects.enabled`。称号配置中的 `effects` 必须引用本文件已定义的 ID。

## 3. 配置路径与重载

文件为 `config/omnitools/title_effects/config.json`，修改后执行 `/omnitools reload`。

## 4--6. 最小配置、教学版与可复制版

教学版，不能直接复制：

```jsonc
{
  "format_version": 1,
  "effects": {
    "speed_1": {
      "type": "POTION", // 四种之一：POTION、ATTRIBUTE、PARTICLE、PERMISSION
      "effect": "minecraft:speed",
      "amplifier": 0,
      "duration": -1
    }
  }
}
```

可直接复制版，四类效果各一项：

```json
{
  "format_version": 1,
  "effects": {
    "speed_1": {
      "name": "速度 I",
      "type": "POTION",
      "effect": "minecraft:speed",
      "amplifier": 0,
      "duration": -1,
      "display": "&a速度 I"
    },
    "health_2": {
      "name": "生命提升",
      "type": "ATTRIBUTE",
      "attribute": "minecraft:generic.max_health",
      "operation": "ADDITION",
      "amount": 4.0,
      "display": "&c生命上限 +4"
    },
    "trail": {
      "name": "粒子足迹",
      "type": "PARTICLE",
      "particle": "minecraft:flame",
      "frequency": 10,
      "display": "&6火焰足迹"
    },
    "member_permission": {
      "name": "成员权限",
      "type": "PERMISSION",
      "permission": "omnitools:member",
      "display": "&b成员权限"
    }
  }
}
```

## 7. 字段表

| 字段 | 类型 | 必填 | 默认/范围 | 常见错误 |
| --- | --- | --- | --- | --- |
| 效果 ID | 字符串键 | 是 | 小写 ID，1--64 | 与称号引用不一致。 |
| `type` | 枚举 | 是 | 四种大写类型 | 使用 `potion` 等未知值。 |
| `effect` | 物品/效果 ID | POTION | 有效药水 ID | 忘记 `minecraft:`。 |
| `amplifier` | 整数 | 否 | >= 0 | 把 I 写成 1（I 是 0）。 |
| `duration` | 整数 | 否 | `-1` 永久，不能 0 | 写 0。 |
| `attribute`、`amount` | ID、有限数 | ATTRIBUTE | 必填 | 属性 ID 拼错。 |
| `operation` | `ADDITION`、`ADD_MULTIPLIED_BASE`、`ADD_MULTIPLIED_TOTAL` | 否 | `ADDITION` | 使用旧别名以外的字符串。 |
| `particle`、`frequency` | ID、正整数 | PARTICLE | 频率 >= 1 | 频率 0。 |
| `permission` | 资源 ID | PERMISSION | 必填 | 不是 `namespace:path`。 |

## 8. 全部配置场景

上方可复制版覆盖 POTION、ATTRIBUTE、PARTICLE、PERMISSION。关联时在称号 `effects` 数组写对应 ID，例如 `["speed_1", "trail"]`。

## 9. 指令、权限与默认角色

没有独立玩家命令；玩家从称号 GUI 切换佩戴或效果状态，称号 GUI 默认 `PLAYER`。

## 10. 占位符

`%title_effects_enabled%` 表示玩家是否允许称号效果，称号模块关闭时为 `false`。

## 11. 数据与升级

定义存于配置，玩家的效果开关存于 SavedData。禁用模块会清理当前生效效果，不删除玩家开关。

## 12. 验收与排错

为称号添加一种效果，重载、佩戴、取消佩戴并重新登录。配置报错时先确认类型和 ID，再检查称号引用。
