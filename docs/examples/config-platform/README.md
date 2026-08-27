# 配置平台教学示例

本目录中的文件均为 `jsonc` 教学副本，不能直接复制到服务端。删除注释后，将内容写入下表的目标路径。根配置和 `common/` 文件变更使用完整重载；单独模块文件可使用 `/omnitools reload <module-id>`，也可使用完整重载。

| 教学文件 | 目标路径 | 格式版本 | 前置条件 | 重载命令 |
| --- | --- | --- | --- | --- |
| `root-config.jsonc` | `config/omnitools/config.json` | 4 | 无 | `/omnitools reload` |
| `common-rewards.jsonc` | `config/omnitools/common/rewards.json` | 1 | 无 | `/omnitools reload` |
| `common-conditions.jsonc` | `config/omnitools/common/conditions.json` | 1 | 无 | `/omnitools reload` |
| `common-texts.jsonc` | `config/omnitools/common/texts.json` | 1 | 无 | `/omnitools reload` |
| `daily-checkin.jsonc` | `config/omnitools/daily_checkin/config.json` | 3 | `daily_checkin` | `/omnitools reload daily_checkin` |
| `online-reward.jsonc` | `config/omnitools/online_reward/config.json` | 1 | `online_reward` | `/omnitools reload online_reward` |
| `shop.jsonc` | `config/omnitools/shop/config.json` | 1 | `shop` | `/omnitools reload shop` |
| `titles.jsonc` | `config/omnitools/titles/config.json` | 1 | `titles` | `/omnitools reload titles` |
| `title-effects.jsonc` | `config/omnitools/title_effects/config.json` | 1 | `title_effects` 与 `titles` | `/omnitools reload title_effects` |
| `achievement.jsonc` | `config/omnitools/achievements/config.json` | 2 | `achievements` | `/omnitools reload achievements` |
| `cdk.jsonc` | `config/omnitools/cdk/config.json` | 1 | `cdk` | `/omnitools reload cdk` |
| `cloud-storage.jsonc` | `config/omnitools/cloud_storage/config.json` | 1 | `cloud_storage` | `/omnitools reload cloud_storage` |
| `permissions.jsonc` | `config/omnitools/permissions/config.json` | 1 | `permissions` | `/omnitools reload permissions` |
| `command-menu.jsonc` | `config/omnitools/command_menu/config.json` | 1 | `command_menu` | `/omnitools reload command_menu` |
| `sidebar.jsonc` | `config/omnitools/sidebar/config.json` | 2 | `sidebar` | `/omnitools reload sidebar` |

每个文件对应的字段约束在 `docs/schemas/`；配置语义、模板引用和全量重载边界以[统一配置平台](../../config-platform.md)为准。
