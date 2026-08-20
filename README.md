# qiandao

`qiandao` 是一个面向 Minecraft Java Edition 的 Fabric 每日签到模组。玩家可以在游戏内打开签到日历，完成当天签到，查看个人统计和全服签到顺序；签到记录、连续天数、虚拟货币余额和月度里程碑奖励均由服务器保存和校验。

## 快速开始

1. 在服务器与每位玩家的客户端安装相同版本的 `qiandao`、Fabric Loader 与 Fabric API。
2. 启动服务器一次，让模组生成 `config/qiandao-rewards.json` 和 `config/qiandao-shop.json`。
3. 按需编辑这两个配置文件，并使用 `/qiandao reload` 重新加载，无须重启服务器。
4. 玩家使用 `/qiandao` 打开签到日历，使用 `/qiandao online` 领取在线时长奖励，使用 `/qiandao shop` 消费货币。

## 功能

- 5×9 签到界面：按月份显示日期，最多显示 31 个日期槽位。
- 只有服务器当前日期可以签到；过去、未来日期和空白槽位不会修改数据。
- 已签到日期显示附魔书，未签到日期显示普通书，并区分过去、今天和未来状态。
- 显示下一次签到倒计时、当天签到名次、累计签到天数、连续签到天数、本月签到天数和虚拟货币余额。
- “今日签到记录”界面按签到时间排序，支持分页，每页最多 45 条记录，并显示玩家头像、名次和签到时间。
- 玩家加入服务器后，如果当天尚未签到，会收到可点击的签到提醒。
- 首次签到播放音效并向全服广播名次；每日签到和月度里程碑可发放虚拟货币。
- 提供每日在线时长奖励与六行商店界面；商店前五行提供 45 个商品槽，商品、价格、物品组件及完整物品堆叠均由服务器配置并支持分页。
- 管理员可以清除当天签到、查询或调整余额、重新加载奖励与商店配置。
- 使用世界 `SavedData` 持久化，服务器重启或切换维度后数据仍然保留。

## 运行环境

- Minecraft Java Edition `1.21.11`
- Fabric Loader `0.19.3` 或更高版本
- Fabric API `0.141.6+1.21.11`（或与目标版本兼容的更新版本）
- Java `21` 或更高版本

这是一个双端模组。服务器和所有连接的客户端都需要安装相同版本的 `qiandao` 与兼容的 Fabric API；服务端与客户端版本不匹配时，无法保证正常连接或打开界面。

## 安装

1. 为目标实例安装 Java 21、Fabric Loader 和 Fabric API。
2. 从 `build/libs/` 获取 `qiandao-<版本>.jar`，或按照[构建](#构建)章节生成 JAR。
3. 将 JAR 同时放入服务器和客户端的 `mods/` 目录。
4. 启动服务器和客户端，并确认日志中出现 `qiandao initialized` 且没有依赖错误。

首次正常启动服务器后，模组会在服务器的 `config/qiandao-rewards.json` 创建默认奖励配置，并在
`config/qiandao-shop.json` 创建默认商店配置。

## 玩家使用

### 打开签到界面

以下命令都可以打开签到界面，命令必须由玩家执行：

```text
/qiandao
/qiandao open
/checkin
```

控制台不能直接打开玩家 GUI，但可以执行管理命令。

### 在线时长奖励

玩家可使用下列命令打开每日在线时长奖励界面：

```text
/qiandao online
/qiandao online rewards
/checkin online
```

在线时长以服务器实际连接时间累计，并按服务器时区在每日零点重置。奖励格会显示今日已累计分钟数；未达到档位时显示红色未附魔时钟，达到后显示绿色可领取状态，领取后时钟会附魔并向全服广播。每个档位每天只能领取一次。

### 完成签到

打开界面后，在当前月份找到今天的日期槽位并点击。签到成功后，槽位会变为附魔书，界面显示当天名次，玩家收到奖励并触发广播。重复点击不会重复签到或重复领取奖励。

日期和倒计时使用服务器 Java 进程的系统时区，客户端本地时区不会影响日期切换。跨过服务器午夜后，重新打开界面或点击旧日期即可刷新状态。

### 查看今日记录

点击签到界面底部的时钟图标打开记录界面。记录按签到时间从早到晚排列；在线玩家显示实时头像，离线玩家使用已保存的 UUID 头像。将鼠标悬停在头像上可查看玩家名称、签到名次和 `HH:mm:ss` 格式的签到时间。底部按钮可返回签到界面或切换分页。

## 奖励配置

配置文件位于服务器目录：

```text
config/qiandao-rewards.json
```

默认内容如下：

```json
{
  "dailyCoins": 100,
  "monthlyRewards": {
    "5": 500,
    "10": 1000,
    "15": 2000,
    "25": 5000
  },
  "onlineTimeRewards": [
    { "minutes": 30, "coins": 50 },
    { "minutes": 60, "coins": 100 },
    { "minutes": 120, "coins": 250 }
  ]
}
```

`dailyCoins` 是每次签到奖励；`monthlyRewards` 的键是本月累计签到天数，奖励在每个自然月中每个里程碑只领取一次。`onlineTimeRewards` 必须恰好包含三个按 `minutes` 升序排列的档位，`minutes` 为正整数，`coins` 为非负整数。所有奖励都是模组内部的数字货币，不会生成物品，也不会自动调用其他经济模组。

修改后重启服务器，或由有权限的管理员执行：

```text
/qiandao reload
```

配置解析失败时，本次运行会记录错误并使用内置默认值。读取旧版本配置时仍兼容 `dailyReward`、`daily` 和 `monthlyCoins` 字段，缺少标准字段的配置会被补写为标准格式。

## 商店配置

商店使用签到和在线时长奖励共享的货币余额。首次启动服务器时会创建
`config/qiandao-shop.json`，默认只有一个钻石商品：

```json
[
  {
    "index": 0,
    "item": "minecraft:diamond",
    "count": 1,
    "price": 20
  }
]
```

配置文件的根节点是商品数组。下方是包含第二页商品的完整示例：

```json
[
  {
    "index": 0,
    "item": "minecraft:diamond",
    "count": 1,
    "price": 20
  },
  {
    "index": 45,
    "item": "minecraft:golden_apple",
    "count": 3,
    "price": 100
  }
]
```

| 字段 | 必填 | 说明 |
| --- | --- | --- |
| `index` | 是 | 从 `0` 开始的全局商品槽位编号；`0` 至 `44` 为第一页，`45` 至 `89` 为第二页，以此类推。每个编号只能出现一次。 |
| `item` | 使用普通写法时必填 | Minecraft 物品注册 ID，例如 `minecraft:diamond`。 |
| `count` | 使用普通写法时必填 | 单次购买得到的物品数量，必须是正整数。 |
| `price` | 是 | 购买所需货币，必须是非负整数；`0` 表示免费。 |
| `components` | 否 | Minecraft 1.21.11 物品组件字符串，直接附在物品 ID 后解析。 |
| `nbt` | 否 | 完整物品堆叠的 SNBT 字符串。设置后优先使用此字段，不再读取 `item`、`count` 或 `components`。 |

未配置商品的槽位会以灰色玻璃板填充。`index` 大于 `44` 时会自动显示对应的后续页面；底栏左右两侧的箭头可在存在相邻页面时切换。

普通写法的 `components` 应使用游戏接受的组件语法。对于较长或复杂的物品数据，可使用 `nbt`
写入完整物品堆叠 SNBT（包含 `id`、`count`，可选 `components`），例如：

```json
[
  {
    "index": 0,
    "price": 500,
    "nbt": "{id:\"minecraft:diamond\",count:64}"
  }
]
```

编辑后执行 `/qiandao reload` 即可重新加载。若任意商品的 JSON、物品 ID、物品组件、SNBT、槽位或数值不合法，整个商店会暂时禁用；修正配置并重新加载后恢复。玩家使用 `/qiandao shop`、`/qiandao shop open`、`/checkin shop` 或 `/checkin shop open` 打开商店，底栏中央头颅的悬停提示会显示当前余额与当前页码。

购买时由服务器再次校验余额与商品配置。扣款成功后，商品优先放入玩家背包；背包无法容纳的剩余部分会掉落在玩家位置。

## 命令

### 玩家命令

| 用途 | 命令 |
| --- | --- |
| 打开签到界面 | `/qiandao`、`/qiandao open`、`/checkin` |
| 打开在线时长奖励界面 | `/qiandao online`、`/qiandao online rewards`、`/checkin online` |
| 打开商店 | `/qiandao shop`、`/qiandao shop open`、`/checkin shop`、`/checkin shop open` |
| 查询自己的余额 | `/qiandao balance`、`/checkin balance`、`/qiandao currency`、`/qiandao currency balance`、`/qiandao currency get`、`/checkin currency`、`/checkin currency balance`、`/checkin currency get`、`/money`、`/money balance`、`/money get`、`/balance` |

### 管理员命令

以下命令需要 Minecraft `2` 级权限（控制台也可以执行）：

| 用途 | 主命令 | 可用别名 |
| --- | --- | --- |
| 查询指定玩家余额 | `/qiandao balance <玩家>` | `/checkin balance <玩家>`、`/qiandao currency <查询操作> <玩家>`、`/checkin currency <查询操作> <玩家>`、`/money <查询操作> <玩家>`、`/balance <玩家>` |
| 增加余额 | `/qiandao add <玩家> <数量>` | `/qiandao currency add <玩家> <数量>`、`/checkin currency add <玩家> <数量>`、`/money add <玩家> <数量>` |
| 扣除余额 | `/qiandao remove <玩家> <数量>` | `/qiandao currency <操作> <玩家> <数量>`、`/checkin currency <操作> <玩家> <数量>`、`/money <操作> <玩家> <数量>` |
| 清除今日签到 | `/qiandao clear` 或 `/qiandao clear today` | `/checkin clear`、`/checkin clear today` |
| 重新加载奖励和商店配置 | `/qiandao reload` | 无 |

`<玩家>` 使用 Minecraft 的玩家选择器参数，可以一次指定多个已知玩家。数量必须是大于 0 的整数；扣除数量不会超过目标玩家当前余额。清除操作只影响服务器当前日期的签到状态、名次和时间，并重新计算连续签到，不会回滚已经发放的货币或月度奖励。

上表中的 `<查询操作>` 可替换为 `balance` 或 `get`；扣除命令中的 `<操作>` 可替换为 `remove`、`deduct` 或 `take`。这些子命令的参数顺序均为玩家选择器后跟正整数数量。

## 数据与备份

签到数据保存在主世界的 Minecraft `SavedData` 中，数据 ID 为 `qiandao_data`，通常对应：

```text
<世界目录>/data/qiandao_data.dat
```

数据按玩家 UUID 保存每日签到日期、名次、签到时间、累计天数、连续天数、余额、月度奖励领取记录、当日在线时长和在线奖励领取记录。所有维度共享同一份数据。迁移或升级前应先完全停止服务器，并备份整个世界目录，尤其是 `data/qiandao_data.dat`、`config/qiandao-rewards.json` 与 `config/qiandao-shop.json`。只备份配置文件不会包含签到历史、在线时长或余额。

服务器日期以 Java 进程系统时区为准；漏签后再次签到会从 1 天重新计算连续签到。模组不会向外部服务上传玩家数据。

## 常见问题

### 命令没有打开界面

确认命令由玩家而不是控制台执行，并检查服务器和客户端是否都安装了本模组及兼容版本的 Fabric API。

### 奖励配置没有生效

确认编辑的是服务器端 `config/qiandao-rewards.json`，JSON 格式有效且数值为非负整数，然后执行 `/qiandao reload`。查看服务器日志中的配置解析错误；出现错误时当前运行会使用默认奖励。

### 商店打开后没有商品

确认编辑的是服务器端 `config/qiandao-shop.json`，根节点为 JSON 数组，商品的 `index` 没有重复，且物品 ID、组件或 SNBT 能被 Minecraft 1.21.11 解析。任一条商品不合法都会让商店临时禁用；修正后执行 `/qiandao reload`，并查看服务器日志中的具体错误。

### 日期或倒计时不正确

检查服务器操作系统的日期、时间和时区。客户端时区不参与签到判断。跨过午夜后重新打开界面即可刷新。

### 重启或换图后记录消失

确认启动时使用的是原来的世界目录。若有备份，从对应世界恢复 `data/qiandao_data.dat`；仅恢复奖励配置不会恢复历史记录和余额。

## 构建

项目使用 Gradle Wrapper，无需单独安装 Gradle：

```bash
# Linux/macOS
./gradlew build

# Windows PowerShell
.\gradlew.bat build
```

产物位于 `build/libs/`：

- `qiandao-<版本>.jar`：可安装的模组 JAR
- `qiandao-<版本>-sources.jar`：源代码 JAR

版本号由 `gradle.properties` 中的 `mod_version` 定义。需要完全清理时执行 `clean build`。

提交改动前建议至少运行一次 `build`，确认 Java 21、资源 JSON 和 Fabric 元数据均能正常打包。构建不需要单独安装 Gradle；Wrapper 会使用项目声明的 Minecraft、Fabric Loader 和 Fabric API 版本。

## 项目结构

```text
src/main/java/dev/modmind/qiandao/
├── ModMindEntry.java                 # 初始化、提醒、命令和奖励重载
├── ModMindClient.java                # 客户端 GUI 注册
├── CheckinData.java                  # SavedData、签到统计、余额和月度记录
├── CheckinRewardConfig.java          # 奖励配置读取、校验和生成
├── CheckinRewardService.java         # 每日及月度奖励发放
├── OnlineTimeRewardService.java      # 在线时长累计与奖励领取校验
├── OnlineTimeRewardScreenHandler.java # 在线时长奖励菜单和服务端校验
├── OnlineTimeRewardScreen.java       # 在线时长奖励界面渲染
├── ShopConfig.java                   # 商店配置与完整物品堆叠 SNBT 读取
├── ShopScreenHandler.java            # 商店菜单、分页和服务端购买校验
├── ShopScreen.java                   # 商店界面渲染
├── CheckinScreenHandler.java         # 签到菜单、日期槽位和服务端校验
├── CheckinScreen.java                # 签到界面渲染
├── CheckinRecordsScreenHandler.java  # 今日记录排序、分页和头像物品
└── CheckinRecordsScreen.java         # 记录界面渲染

src/main/resources/
├── fabric.mod.json                   # 模组元数据和依赖声明
└── assets/qiandao/lang/
    ├── zh_cn.json                    # 简体中文文本
    └── en_us.json                    # English text
```

## 许可证

本项目使用 [MIT License](LICENSE) 发布。
