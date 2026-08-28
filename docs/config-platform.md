# 统一配置平台

本页是根配置、模块开关、公共模板和重载语义的唯一说明。模块页面只说明本模块如何引用这些能力；根字段的逐项约束见[根配置参考](reference/root-config.md)。

OmniTools 使用一份根快照和带类型的模块文件。重载会读取所有已启用模块，校验完整候选配置，并且只在所有检查通过后发布；失败时保留上一份快照。

## 配置文件

```text
config/omnitools/
  config.json
  common/rewards.json
  common/conditions.json
  common/texts.json
  <module>/config.json
```

`config.json` 是唯一控制模块开关、时区、语言、集成与命令安全的文件。`common/rewards.json` 是共享奖励库，`conditions.json` 和 `texts.json` 分别保存条件与文本模板。它们都不能绕过账本、权限、NBT、文本长度、条件深度或物品数量限制。

## 奖励库和模板引用

`common/rewards.json` 的推荐格式为 V2：`rewards` 中的键是稳定奖励 ID，`sets` 将已有奖励组合为可复用奖励组。签到、在线奖励、成就和 CDK 的 `rewards` 数组均可引用一个奖励或奖励组：

```json
[
  { "set": "daily_basic" },
  { "reward": "starter_package" }
]
```

集合可以嵌套；未知引用、循环引用、超过 16 层嵌套，以及展开后的重复奖励 ID 都会拒绝整次重载。引用只能包含 `reward` 或 `set`，不能在调用处覆盖奖励类型、数量、物品 NBT 或指令。奖励 ID 是账本业务键，已上线 ID 不得改作不同含义；需要调整语义时新增 ID，再修改奖励组。

V1 的 `templates`、`template` 与 `$ref` 仍受支持。模板引用保留调用处字段覆盖的旧行为：

在奖励或成就条件对象中使用 `template`（或 `$ref`）引用公共模板。模块条目中的字段会覆盖模板字段：

```json
{
  "id": "daily_welcome",
  "template": "welcome_currency",
  "amount": 250
}
```

未知引用、循环引用或超过四层嵌套会拒绝整次重载。未使用引用的既有模块配置仍有效，并维持各自的格式版本。

## 编写文件

- `config.json` 和模块 `config.json` 都是可直接加载的严格 JSON。
- `docs/examples/config-platform/` 下的 `config.jsonc` 是教学副本；写入 `config/` 前必须删除注释。
- `docs/schemas/` 提供编辑器补全所需的 JSON Schema；运行时仍由带类型的 Java 解析器校验。

## 模块教学示例与 Schema

`docs/examples/config-platform/` 含根配置、三个公共文件和每个模块配置的教学副本。将对应 JSON 结构复制到 `config/omnitools/` 下的匹配路径；示例本身不会启用模块。每个示例的目标路径、前置开关、适用版本和重载命令见[示例目录](examples/config-platform/README.md)。

| 模块 | 教学示例 | Schema |
| --- | --- | --- |
| 每日签到 | `daily-checkin.jsonc` | `daily-checkin.schema.json` |
| 在线奖励 | `online-reward.jsonc` | `online-reward.schema.json` |
| 商店 | `shop.jsonc` | `shop.schema.json` |
| 称号 | `titles.jsonc` | `titles.schema.json` |
| 称号效果 | `title-effects.jsonc` | `title-effects.schema.json` |
| 成就 | `achievement.jsonc` | `achievements.schema.json` |
| CDK | `cdk.jsonc` | `cdk.schema.json` |
| 云存储 | `cloud-storage.jsonc` | `cloud-storage.schema.json` |
| 权限 | `permissions.jsonc` | `permissions.schema.json` |
| 命令菜单注册表 | `command-menu.jsonc` | `command-menu.schema.json` |
| 侧边栏 | `sidebar.jsonc` | `sidebar.schema.json` |
| 排行榜 | `leaderboards.jsonc` | `leaderboards.schema.json` |
| 礼包 | `packages.jsonc` | `packages.schema.json` |

公共 Schema 为 `common-rewards.schema.json`、`common-conditions.schema.json` 和 `common-texts.schema.json`。奖励库引用仅可用于签到、在线奖励、成就和 CDK 的奖励列表；条件模板仅可用于成就条件。两者都不能让命令执行、权限绕过或持久化数据规则变为可配置项。

平台会警告未知字段；格式错误和不安全引用会阻止重载。对不兼容修改必须新建 `format_version` 并提供迁移步骤；不得静默重解释既有奖励 ID 或账本事件。

## 重载范围

修改根配置或 `common/` 下任何文件后使用 `/omnitools reload`：它会重新解析所有已启用模块，并发布一份已校验的快照。只修改某一模块 `config.json` 后可使用 `/omnitools reload <module-id>`：它重解析目标模块、复用其他模块的活动定义，仍执行完整跨模块校验；校验失败时不发布任何内容。有效模块 ID 为上表中的目录名。
