# OmniTools

面向 Fabric 服务器的模块化实用工具模组，提供签到、在线奖励、货币与商店、称号、成就、云端存储和可配置指令权限。

| 项目 | 当前信息 |
| --- | --- |
| Minecraft | `1.21.11` |
| Fabric Loader | `>= 0.19.3` |
| Java | `21` |
| 当前版本 | `1.2.2` |
| 必需依赖 | Fabric API |
| 可选依赖 | Fabric Placeholder API |

## 功能概览

| 模块 | 功能 |
| --- | --- |
| 每日签到 | 每日签到、连续天数、月度奖励与签到记录 |
| 在线奖励 | 按每日在线时长领取货币 |
| 商店与货币 | 查询余额、配置商品、使用货币购买物品 |
| 称号 | 授予和佩戴称号，并显示在聊天、Tab 列表或头顶 |
| 称号效果 | 为称号关联游戏效果、属性、粒子或权限效果 |
| 成就 | 基于原版统计的条件树成就和一次性奖励 |
| 云端存储 | 可扩容的玩家个人存储空间 |
| 指令权限 | 为规范动作配置玩家、协管、管理员或服主门槛 |
| Placeholder API | 可选的文本占位符联动 |

成就支持原版统计、目标分组、标签、逻辑条件树和一次性奖励。详细规则见[成就配置指南](docs/achievements.md)。

## 环境要求

- Minecraft Java Edition `1.21.11`
- Fabric Loader `0.19.3` 或更高版本
- Fabric API
- Java `21`
- Fabric Placeholder API 仅在需要占位符联动时安装

## 安装

1. 将 OmniTools 和 Fabric API 放入服务器的 `mods/` 目录。
2. 需要占位符联动时，再安装兼容版本的 Fabric Placeholder API。
3. 启动服务器一次，OmniTools 会在 `config/omnitools/` 创建默认配置。
4. 编辑配置后执行 `/omnitools reload` 使其生效。

生产服务器修改配置或升级模组前，请备份世界目录和 `config/omnitools/`。

## 快速开始

1. 首次启动后查看 `config/omnitools/config.json`，确认时区和模块开关。
2. 使用 `/omnitools` 打开默认签到界面。
3. 使用 `/omnitools online`、`/omnitools shop`、`/omnitools title` 或 `/omnitools achievements` 打开对应功能。
4. 按需编辑各模块目录下的 `config.json`，然后由管理员执行 `/omnitools reload`。

模块关闭后不会删除已有玩家数据，但对应命令和服务端逻辑会停止工作。

## 命令与权限

命令是否可用同时受模块开关和动作权限控制。下表的默认角色来自当前源码默认值；服主可在 `permissions/config.json` 调整。

| 命令 | 用途 | 默认角色 |
| --- | --- | --- |
| `/omnitools`、`/omnitools open`、`/checkin` | 打开每日签到界面 | 玩家 |
| `/omnitools online [rewards]` | 打开在线奖励界面 | 玩家 |
| `/omnitools shop [open]` | 打开商店 | 玩家 |
| `/omnitools title [open]`、`/title` | 打开称号界面 | 玩家 |
| `/omnitools achievements [open]` | 打开成就界面 | 玩家 |
| `/omnitools storage [open]`、`/cloudstorage`、`/cstorage` | 打开云端存储 | 管理员 |
| `/omnitools modules` | 打开模块管理界面并热切换模块 | 管理员 |
| `/money`、`/omnitools currency` | 查询自己的货币余额 | 玩家 |
| `/balance [player]` | 查询余额；指定玩家需额外权限 | 玩家 / 管理员 |
| `/money add|remove|deduct|take` | 增减货币 | 管理员 |
| `/omnitools title give|add|remove|take` | 授予或回收称号 | 管理员 |
| `/omnitools clear [today]` | 清除当天签到数据 | 管理员 |
| `/omnitools reload` | 重载全部配置 | 管理员 |

### 模块管理

`/omnitools modules` 使用 `config.reload` 动作权限，默认仅管理员可用。该命令只能由游戏内玩家执行，用于打开三行箱子式模块管理界面；控制台可继续执行 `/omnitools reload`，但不能打开 GUI。

界面可切换每日签到、在线奖励、商店、称号、称号效果、成就、云端存储和指令权限八个模块。左键点击模块图标会立即尝试切换，底部的“重新读取磁盘配置”按钮等价于 `/omnitools reload`。切换成功后，命令、已打开的菜单和相关定时服务会立即按新状态更新。

切换前会加载并校验候选配置。启用模块时若其子配置无效，或称号与称号效果的依赖不满足，界面会显示原因，磁盘和运行中的模块状态均保持不变。Placeholder API 属于独立集成开关，不在此界面中管理。完整规则见[配置指南的模块管理章节](docs/configuration.md#模块管理)。

角色与 Minecraft 权限等级的对应关系如下：

| 角色 | 等级 |
| --- | --- |
| `PLAYER` | `0` |
| `MODERATOR` | `1` |
| `ADMIN` | `2` |
| `OWNER` | `4` |

控制台命令源可使用非 GUI 命令；`/omnitools modules` 例外，它必须由游戏内玩家打开。云端存储默认还允许具有原生 `omnitools:cloud_storage` 节点的玩家访问；可在权限配置中关闭该兼容行为。

当前规范动作 ID：

```text
checkin.open
online.open
shop.open
title.open
achievements.open
storage.open
currency.balance.self
currency.balance.other
currency.add
currency.remove
checkin.clear
title.grant
title.revoke
config.reload
```

完整权限配置和安全开关说明见[配置指南](docs/configuration.md#指令权限)。

## 配置结构

所有可编辑配置位于 `config/omnitools/`：

```text
config/omnitools/
├── config.json
├── daily_checkin/config.json
├── online_reward/config.json
├── shop/config.json
├── titles/config.json
├── title_effects/config.json
├── achievements/config.json
├── cloud_storage/config.json
├── permissions/config.json
└── legacy/
```

根配置只负责全局选项、可选集成和模块开关：

```json
{
  "format_version": 2,
  "global": {
    "debug": false,
    "timezone": "Asia/Shanghai"
  },
  "integrations": {
    "placeholder_api": {
      "enabled": true
    }
  },
  "modules": {
    "daily_checkin": { "enabled": true },
    "online_reward": { "enabled": true },
    "shop": { "enabled": true },
    "titles": { "enabled": true },
    "title_effects": { "enabled": true },
    "achievements": { "enabled": true },
    "cloud_storage": { "enabled": true },
    "permissions": { "enabled": false }
  }
}
```

- `global.debug`：全局调试标记。
- `global.timezone`：Java `ZoneId`，影响签到日期和在线时长跨日切分。
- `integrations.placeholder_api.enabled`：Placeholder API 集成总开关。
- `modules.*.enabled`：对应模块开关。

模块配置、权限配置和迁移细节见[配置指南](docs/configuration.md)。

## Placeholder API 联动

Placeholder API 是可选依赖。未安装该 API，或在根配置中关闭集成时，OmniTools 仍可正常启动。具体文本写法由使用 Placeholder API 的下游模组决定，本文只列出注册 ID。

```text
omnitools:balance
omnitools:balance_formatted
omnitools:checkin_today
omnitools:checkin_today_rank
omnitools:checkin_total_days
omnitools:checkin_streak_days
omnitools:checkin_month_days
omnitools:online_today_seconds
omnitools:online_today_minutes
omnitools:online_today_hms
omnitools:title_id
omnitools:title
omnitools:title_plain
omnitools:title_effects_enabled
omnitools:achievements_unlocked
omnitools:achievements_claimed
omnitools:achievements_total
```

占位符只读当前上下文玩家的数据，不会触发签到、领奖、扣币或配置写入。对应模块关闭、集成关闭或没有玩家上下文时会返回安全默认值。

## 数据、重载与迁移

- `/omnitools reload` 会先读取并校验全部模块配置；全部成功后才一次性发布新配置快照。
- 新配置无效时，服务器继续使用上一份有效配置和权限，不会替换正在运行的快照。
- 配置迁移会将识别到的历史 `qiandao` 配置归档到 `config/omnitools/legacy/`，并记录迁移清单。
- 签到、货币、称号、成就和云端存储属于世界 `SavedData`。备份或迁移服务器时请备份完整世界目录。
- 不要手动编辑世界中的 `SavedData` 文件。

## 常见问题

### 修改配置后没有生效

确认 JSON 语法正确，并由具有 `config.reload` 权限的命令源执行 `/omnitools reload`。重载失败时服务器会保留旧快照，日志会给出校验原因。

### 玩家看不到某条命令或无法打开界面

检查对应 `modules.*.enabled` 开关，以及该命令动作在 `permissions/config.json` 中的最低角色。云端存储还可能受原生权限节点设置影响。

### Placeholder API 没有解析 OmniTools 数据

确认下游模组实际安装了兼容的 Placeholder API，并检查 `integrations.placeholder_api.enabled`。OmniTools 不会把 Placeholder API 打包进自身 JAR。

### 升级前需要备份什么

至少备份世界目录和 `config/omnitools/`。配置迁移不会主动删除旧文件，但备份仍是生产服升级的必要步骤。

## 开发与构建

开发环境要求 Java 21 和 Fabric 1.21.11。构建命令：

```powershell
.\gradlew.bat build
```

## 许可证

本项目使用 [MIT License](LICENSE)。
