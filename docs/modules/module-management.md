# 模块管理与重载

## 1. 功能简介

模块管理 GUI 让管理员在游戏内查看并事务式切换模块。它使用 OmniTools 的自定义菜单类型及客户端界面；控制台不能打开 GUI，但可以执行完整配置重载。

## 2. 模块开关

模块开关集中在 `config/omnitools/config.json` 的 `modules.<id>.enabled`。根配置默认启用除 `permissions` 外的模块：`daily_checkin`、`online_reward`、`shop`、`titles`、`title_effects`、`achievements`、`cloud_storage`、`command_menu`、`sidebar`。

禁用模块不会删除玩家数据；对应菜单、命令和周期任务会停止或关闭，重新启用后读取保留数据。

## 3. 初始配置

根配置首次生成：

```json
{
  "format_version": 2,
  "global": {
    "debug": false,
    "timezone": "Asia/Shanghai",
    "reward_security": {
      "allow_command_rewards": false,
      "max_command_length": 1024
    }
  },
  "integrations": { "placeholder_api": { "enabled": true } },
  "modules": {
    "daily_checkin": { "enabled": true },
    "online_reward": { "enabled": true },
    "shop": { "enabled": true },
    "titles": { "enabled": true },
    "title_effects": { "enabled": true },
    "achievements": { "enabled": true },
    "cloud_storage": { "enabled": true },
    "permissions": { "enabled": false },
    "command_menu": { "enabled": true },
    "sidebar": { "enabled": true }
  }
}
```

各模块子配置按需生成；命令菜单注册表为空，侧边栏生成默认示例。配置文件损坏时不覆盖文件。

## 4. 指令与权限

| 指令 | 别名 | 用途 | 默认权限 | 仅玩家 |
| --- | --- | --- | --- | --- |
| `/omnitools modules` | 无 | 打开模块管理 GUI | `config.reload` (`ADMIN`) | 是 |
| `/omnitools reload` | 无 | 读取磁盘并发布完整配置 | `config.reload` (`ADMIN`) | 否 |

## 5. 配置字段

| 字段 | JSON 类型 | 必填 | 默认值/范围 | 作用与错误行为 |
| --- | --- | --- | --- | --- |
| `format_version` | integer | 否 | `2`（旧版 `1` 可读），必须为正整数 | 根配置格式版本；类型或范围错误会拒绝候选快照。 |
| `global.debug` | boolean | 否 | `false` | 全局调试标志。 |
| `global.timezone` | string | 否 | `Asia/Shanghai` | 有效 Java `ZoneId`，影响签到和在线时长跨日。 |
| `global.reward_security.allow_command_rewards` | boolean | 否 | `false` | 是否允许签到和成就配置 `command` 奖励；关闭时包含该类型奖励的候选快照被拒绝。 |
| `global.reward_security.max_command_length` | integer | 否 | `1024`，`1-16384` | 允许的奖励命令最大长度；超出范围或被奖励定义超过时拒绝候选快照。 |
| `integrations.placeholder_api.enabled` | boolean | 否 | `true` | 可选 Placeholder API 集成总开关，不属于模块列表。 |
| `modules.<id>.enabled` | boolean | 否 | 除 `permissions` 外 `true` | 模块运行时开关。 |

## 6. 使用示例

管理员在 GUI 点击模块图标即可切换。也可以编辑根配置后执行 `/omnitools reload`。模块管理 GUI 的“重新读取磁盘配置”按钮等价于该命令。

## 7. 数据保存

根配置和模块 JSON 只保存规则。签到、货币、在线时间、称号、成就、云存储和侧边栏偏好保存于世界 `SavedData`；历史配置迁移副本放在 `config/omnitools/legacy/`。

升级、迁移或在生产服切换模块前，必须同时备份世界目录和 `config/omnitools/`。

## 8. 热重载与依赖

统一流程是：读取全部启用模块配置，构造候选快照，完整校验，校验成功后原子发布，再执行运行时补偿。任一失败时不写正式配置、不替换快照。

切换成功后命令树刷新，失效菜单关闭；在线奖励停用前 `flushAll`；称号显示刷新；称号效果禁用时移除效果；成就启用时立即检查；侧边栏立即刷新或清除。模块 GUI 中，`title_effects` 启用要求先启用 `titles`；关闭 `titles` 前若效果定义非空，或签到/成就仍包含称号奖励，都会被拒绝。`permissions` 关闭回退源码默认角色。
