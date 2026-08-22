# qiandao

`qiandao` 是面向 Minecraft Java Edition Fabric 服务器的综合玩家服务模组，提供每日签到、在线时长奖励、虚拟货币、配置化商店、称号与称号效果、原版统计驱动的自定义成就，以及玩家独立的云端存储。

所有奖励、余额、物品、成就状态和权限判断均由服务端完成。客户端只负责显示服务端生成的 GUI，不参与奖励条件判定。

## 环境要求

- Minecraft Java Edition `1.21.11`
- Fabric Loader 与 Fabric API（版本以 `gradle.properties` 和 `fabric.mod.json` 为准）
- Java `21`
- 服务端与客户端安装兼容版本的模组；只安装服务端不能显示客户端 GUI

## 功能概览

### 每日签到

`/qiandao` 或 `/qiandao open` 打开签到日历。签到日期、名次、连续天数、月度进度和余额由世界 `SavedData` 保存。每日和月度奖励在签到成功后自动发放，重复点击不会重复领取。

### 在线时长奖励

`/qiandao online` 打开在线奖励 GUI。在线时间按服务端配置的时区累计，奖励达到分钟数后手动领取。奖励使用稳定 `id`，领取键为 `day:reward_id`；旧版本的 `day:slot` 记录会按旧顺序兼容读取。

### 商店

`/qiandao shop` 打开六行分页商店。商品、价格、数量、组件和 SNBT 均来自配置，购买时服务端原子扣除余额并把完整物品放入玩家背包，背包已满时按 Minecraft 常规规则掉落。

### 称号与效果

`/qiandao title` 打开称号 GUI。称号定义和玩家状态分离保存：管理员定义在配置中，玩家拥有、佩戴和效果开关保存在 `TitleData`。称号可以显示在聊天、Tab 列表和头顶，并关联药水、属性、粒子或受限权限效果。

### 自定义成就

`/qiandao achievements` 打开成就 GUI。成就进度直接读取 Minecraft 原版统计：

```java
player.getStats().getValue(Stats.BLOCK_MINED.get(block));
player.getStats().getValue(Stats.ENTITY_KILLED.get(entityType));
```

模组不会重复记录挖掘或击杀次数。达到要求后成就永久解锁，玩家在 GUI 中手动领取一次货币和称号奖励；原版统计重置不会撤销已解锁状态。

### 云端存储

拥有 `qiandao:cloud_storage` 权限或 Minecraft 管理员权限的玩家可使用 `/qiandao storage`。每页为 `6 x 9` 箱子界面，前五行是 45 个存储槽，最后一行是上一页、状态、余额、扩展和下一页按钮。玩家可消费货币扩展页数，初始页数、扩展价格和最大页数可配置。

## 配置目录

首次启动后会生成：

```text
config/qiandao/
├── config.json                    # 主配置和模块开关
├── daily_checkin/config.json      # 每日签到和月度奖励
├── online_reward/config.json      # 在线时长奖励
├── shop/config.json               # 商店商品
├── titles/config.json             # 称号定义
├── title_effects/config.json      # 称号效果定义
├── achievements/config.json       # 原版统计成就
├── cloud_storage/config.json      # 云存储扩展设置
└── legacy/                        # 迁移后的旧配置备份和 manifest.json
```

所有模块文件使用 UTF-8，并带有整数 `format_version`。配置目录只保存管理员可编辑的定义；玩家数据不写入这些文件。

### 主配置示例

```json
{
  "format_version": 1,
  "global": {
    "debug": false,
    "timezone": "Asia/Shanghai"
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

`timezone` 用于签到日期、在线时长跨日切分和 GUI 时间显示，使用 Java `ZoneId`，例如 `Asia/Shanghai`、`UTC`。

### 每日签到

```json
{
  "format_version": 1,
  "dailyCoins": 100,
  "monthlyRewards": { "5": 500, "10": 1000, "15": 2000, "25": 5000 }
}
```

### 在线奖励

```json
{
  "format_version": 1,
  "rewards": [
    { "id": "online_30m", "minutes": 30, "coins": 50 },
    { "id": "online_60m", "minutes": 60, "coins": 100 }
  ]
}
```

奖励 ID 必须唯一且匹配 `[a-z0-9_.-]{1,64}`。发布后的 ID 不应复用；删除奖励不会删除历史领取键。

### 商店

推荐格式为 `{ "format_version": 1, "products": [] }`。解析器仍兼容旧版根数组格式。每个商品的 `id`、价格、物品 ID、数量、组件和 SNBT 会在服务端加载时校验。

### 称号和称号效果

`titles/config.json` 保存称号的 `id`、显示文本、稀有度、效果引用和提示文本。`title_effects/config.json` 保存药水、属性、粒子和权限效果定义。称号效果引用不存在时，整次配置快照不会提交。

权限效果只允许明确的 `qiandao:cloud_storage` 或 `qiandao:command.*` 节点，禁止通过配置直接授予任意管理员级别权限。

### 成就

```json
{
  "format_version": 1,
  "achievements": [
    {
      "id": "stone_breaker",
      "display": "石匠",
      "description": "挖掘石头 1000 个",
      "icon": "minecraft:stone",
      "requirements": [
        { "type": "block_mined", "target": "minecraft:stone", "count": 1000 }
      ],
      "rewards": { "coins": 500, "titles": ["geologist"] }
    }
  ]
}
```

支持的统计类型是 `block_mined` 和 `entity_killed`。方块、生物及图标会在加载时解析并缓存；目标不存在、数量非正数、ID 重复或称号引用无效时配置不会替换当前有效快照。

### 云端存储

```json
{
  "format_version": 1,
  "expansionCost": 1000,
  "maxPages": 2
}
```

`maxPages` 至少为 1，默认玩家拥有 1 页。云存储物品和页数保存在世界数据中，不会因配置重载丢失。

## 模块开关与重载

使用 `/qiandao reload` 重新读取主配置和所有模块配置。加载流程会先迁移旧配置、读取主配置、等待服务端注册表可用、校验所有模块和跨模块引用，然后一次性替换配置快照。

- 缺失文件：生成默认文件。
- 重载失败：保留上一份有效快照，不清空其他模块。
- 禁用模块：命令、GUI 点击、Tick、加入/断开事件和称号显示逻辑均停止；SavedData 保留。
- 重新启用模块：无需重启服务器，在线玩家会重新检查成就、称号显示和效果。

模块依赖关系：在线奖励、商店、成就和云存储共享核心余额；`title_effects` 依赖 `titles`；`permissions` 目前仅作预留开关。

## 命令

玩家命令：

| 用途 | 命令 |
| --- | --- |
| 签到 | `/qiandao`、`/qiandao open` |
| 在线奖励 | `/qiandao online`、`/qiandao online rewards` |
| 商店 | `/qiandao shop`、`/qiandao shop open` |
| 称号 | `/qiandao title`、`/title` |
| 成就 | `/qiandao achievements` |
| 云存储 | `/qiandao storage`、`/cloudstorage`、`/cstorage` |
| 余额 | `/qiandao balance`、`/qiandao currency`、`/money`、`/balance` |

管理员命令（Minecraft 权限等级 2，控制台也可执行）：

| 用途 | 命令 |
| --- | --- |
| 查询指定玩家余额 | `/qiandao balance <玩家>` |
| 增加/扣除余额 | `/qiandao add <玩家> <数量>`、`/qiandao remove <玩家> <数量>` |
| 授予/回收称号 | `/qiandao title give <玩家> <称号ID>`、`/qiandao title remove <玩家> <称号ID>` |
| 清除今日签到 | `/qiandao clear [today]` |
| 重载配置 | `/qiandao reload` |

## 持久化与备份

玩家运行时数据使用世界 `SavedData`，典型文件为：

```text
<世界>/data/qiandao_data.dat                 # 签到、余额、月度奖励、在线时长
<世界>/data/qiandao_titles.dat               # 称号拥有、佩戴和效果开关
<世界>/data/qiandao_achievements.dat         # 成就解锁和领取状态
<世界>/data/qiandao_cloud_storage.dat        # 云存储物品和页数
```

备份或迁移前应停止服务端并备份整个世界目录，以及 `config/qiandao/` 和 `config/qiandao/legacy/`。不要只备份配置文件，否则无法恢复余额、物品和领取状态。

## 旧配置迁移

首次加载时会迁移以下旧文件，且不会删除源文件：

| 旧文件 | 新位置 |
| --- | --- |
| `qiandao-rewards.json` | `daily_checkin/config.json` 与 `online_reward/config.json` |
| `qiandao-shop.json` | `shop/config.json` |
| `qiandao-titles.json` | `titles/config.json`，玩家状态导入 `qiandao_titles.dat` |
| `qiandao-title-effects.json` | `title_effects/config.json` |
| `qiandao-achievements.json` | `achievements/config.json` |
| `qiandao-cloud-storage.json` | `cloud_storage/config.json` |

迁移只在目标不存在时执行，成功后把旧文件复制到 `legacy/` 并写入 `legacy/manifest.json`。原称号玩家状态只导入一次，避免重启重复覆盖 SavedData。

## 权限

云存储使用原生权限节点 `qiandao:cloud_storage`。Minecraft 管理员等级 2 及以上默认绕过该节点；其他玩家需要由权限后端授予该节点。称号效果产生的权限同样经过白名单校验，不会改变任意原生管理员等级。

## 故障排查

- GUI 无法打开：确认服务端和客户端模组版本一致，并检查对应模块是否启用及玩家是否拥有权限。
- 配置修改未生效：确认 JSON 使用 UTF-8、ID 唯一、目标注册表 ID 正确，然后执行 `/qiandao reload`。
- 重载失败：查看服务端日志；旧快照会继续运行，修复配置后再次重载即可。
- 成就进度不正确：确认原版统计中存在目标；模组只读取原版统计，不维护第二份计数。
- 在线奖励重复或错位：不要复用奖励 ID；旧槽位记录会按旧顺序转换为稳定 ID。
- 重启后数据缺失：确认使用了原来的世界目录，并恢复对应的 `data/qiandao_*.dat` 文件。

## 构建与验证

Windows PowerShell：

```powershell
.\gradlew.bat compileJava
.\gradlew.bat build
```

Linux/macOS：

```bash
./gradlew compileJava
./gradlew build
```

产物位于 `build/libs/`。ModMind 工作流还会执行资源内容校验、托管构建和隔离服务端烟雾测试；测试实例不会打开用户的 Minecraft 窗口。

## 许可证

本项目使用 [MIT License](LICENSE) 发布。
