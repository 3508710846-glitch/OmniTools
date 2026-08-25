# 称号效果

## 1. 模块用途和适用场景

称号效果为当前佩戴称号添加药水、属性、粒子或受限原生权限效果。效果只在服务端运行，不要求客户端安装 OmniTools。

## 2. 模块依赖与关联模块

模块 ID 为 `title_effects`，依赖 `titles`。效果 ID 由称号配置的 `effects` 数组引用；效果定义非空时，称号模块必须启用。

## 3. 模块开关配置

```json
{ "modules": { "title_effects": { "enabled": true } } }
```

禁用会立刻移除本模块施加的药水、属性、粒子和权限状态，不删除称号、效果定义或玩家偏好。

## 4. 初始配置文件位置

首次启动生成 `config/omnitools/title_effects/config.json`。修改后需要 `/omnitools reload`。

## 5. 最小可用配置

```json
{
  "format_version": 1,
  "slow_fall": {
    "name": "缓降",
    "type": "POTION",
    "effect": "minecraft:slow_falling",
    "amplifier": 0,
    "duration": -1
  }
}
```

## 6. 完整配置示例

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
  "health_2": {
    "name": "生命提升 II",
    "type": "ATTRIBUTE",
    "attribute": "minecraft:generic.max_health",
    "operation": "ADDITION",
    "amount": 4.0,
    "display": "&c生命上限 +4"
  }
}
```

## 7. 配置字段表

每个根对象键都是效果 ID，必须匹配 `[a-z0-9_.-]{1,64}`。

| 字段 | 类型 | 必填 | 默认值或范围 | 重载方式 |
| --- | --- | --- | --- | --- |
| `format_version` | integer | 否 | 首次生成 `1` | reload |
| `<id>.name` | string | 否 | 使用效果 ID | reload |
| `<id>.type` | string | 是 | `POTION`、`ATTRIBUTE`、`PARTICLE`、`PERMISSION` | reload |
| `effect`、`amplifier`、`duration` | string、integer、integer | POTION | 有效药水；等级 >= 0；时长为 `-1` 或正数 | reload |
| `attribute`、`operation`、`amount` | string、string、number | ATTRIBUTE | 有效属性；有限数值 | reload |
| `particle`、`frequency` | string、integer | PARTICLE | 有效粒子；频率 >= 1 | reload |
| `permission` | string | PERMISSION | `omnitools:cloud_storage` 或 `omnitools:command.*` | reload |
| `display` | string | 否 | 默认名称，支持颜色 | reload |

## 8. 指令、别名和权限节点

本模块没有独立命令。玩家通过 `/omnitools title`（`title.open`，默认 PLAYER）在称号 GUI 选择称号和个人效果开关；管理员用 `/omnitools reload`（`config.reload`，默认 ADMIN）加载定义。

## 9. GUI 操作说明

效果不单独打开 GUI。称号菜单在对应称号的 Lore 中显示效果，并提供玩家个人效果开关。修改佩戴称号、重生、重连或 reload 后，服务端重新应用有效效果。

## 10. 占位符列表及用途

`%omnitools:title_effects_enabled%` 表示玩家的个人效果开关；称号显示文本可用 `title`、`title_plain`。详见[Placeholder API](../guides/placeholder-api.md)。

## 11. 数据保存位置和升级影响

效果定义位于 JSON；玩家的启用偏好和称号选择位于世界 `TitleData`。运行时药水、属性和粒子不是配置数据，禁用时会清理。升级前保留现有效果 ID，避免称号引用失效。

## 12. 与其他模块的联动

仅由称号模块引用；PERMISSION 类型会与权限模块的命令角色共同决定最终权限。云存储原生节点可被受限权限效果授予。

## 13. 常见错误及解决方法

| 现象 | 处理 |
| --- | --- |
| 效果没有生效 | 确认 `titles` 与本模块已启用、称号已佩戴且效果 ID 存在。 |
| 不能关闭称号模块 | 先禁用效果模块或移除所有效果定义与称号引用。 |
| reload 失败 | 检查注册表 ID、时长、属性操作和权限节点限制。 |

## 14. 可复制的验收清单

- [ ] 佩戴称号后药水或属性立即生效。
- [ ] 关闭个人效果后运行时效果被移除。
- [ ] 禁用模块后不遗留效果，重新启用后可恢复。
- [ ] 不存在的效果 ID 会阻止错误快照发布。
