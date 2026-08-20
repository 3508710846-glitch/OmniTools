# qiandao

`qiandao` 是一个面向 Minecraft Java Edition 的 Fabric 每日签到模组。玩家可以在游戏内打开签到日历，完成当天签到，查看个人统计和全服签到顺序；签到记录、连续天数、虚拟货币余额和月度里程碑奖励均由服务器保存和校验。

## 功能

- 5×9 签到界面：按月份显示日期，最多显示 31 个日期槽位。
- 只有服务器当前日期可以签到；过去、未来日期和空白槽位不会修改数据。
- 已签到日期显示附魔书，未签到日期显示普通书，并区分过去、今天和未来状态。
- 显示下一次签到倒计时、当天签到名次、累计签到天数、连续签到天数、本月签到天数和虚拟货币余额。
- “今日签到记录”界面按签到时间排序，支持分页，每页最多 45 条记录，并显示玩家头像、名次和签到时间。
- 玩家加入服务器后，如果当天尚未签到，会收到可点击的签到提醒。
- 首次签到播放音效并向全服广播名次；每日签到和月度里程碑可发放虚拟货币。
- 管理员可以清除当天签到、查询或调整余额、重新加载奖励配置。
- 使用世界 `SavedData` 持久化，服务器重启或切换维度后数据仍然保留。

## 运行环境

- Minecraft Java Edition `1.21.11`
- Fabric Loader `0.19.3` 或更高版本
- Fabric API `0.141.6+1.21.11`（或与目标版本兼容的更新版本）
- Java `21` 或更高版本

这是一个双端模组。服务器和所有连接的客户端都需要安装 `qiandao` 与 Fabric API；客户端还需要加载本模组提供的 GUI 注册代码。

## 安装

1. 为目标实例安装 Java 21、Fabric Loader 和 Fabric API。
2. 从 `build/libs/` 获取 `qiandao-<版本>.jar`，或按照[构建](#构建)章节生成 JAR。
3. 将 JAR 同时放入服务器和客户端的 `mods/` 目录。
4. 启动服务器和客户端，并确认日志中出现 `qiandao initialized` 且没有依赖错误。

首次正常启动服务器后，模组会在服务器的 `config/qiandao-rewards.json` 创建默认奖励配置。

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

## 命令

### 玩家命令

| 用途 | 命令 |
| --- | --- |
| 打开签到界面 | `/qiandao`、`/qiandao open`、`/checkin` |
| 打开在线时长奖励界面 | `/qiandao online`、`/qiandao online rewards`、`/checkin online` |
| 查询自己的余额 | `/qiandao balance`、`/checkin balance`、`/qiandao currency`、`/qiandao currency balance`、`/qiandao currency get`、`/checkin currency`、`/checkin currency balance`、`/checkin currency get`、`/money`、`/money balance`、`/money get`、`/balance` |

### 管理员命令

以下命令需要 Minecraft `2` 级权限（控制台也可以执行）：

| 用途 | 主命令 | 可用别名 |
| --- | --- | --- |
| 查询指定玩家余额 | `/qiandao balance <玩家>` | `/checkin balance <玩家>`、`/qiandao currency <查询操作> <玩家>`、`/checkin currency <查询操作> <玩家>`、`/money <查询操作> <玩家>`、`/balance <玩家>` |
| 增加余额 | `/qiandao add <玩家> <数量>` | `/qiandao currency add <玩家> <数量>`、`/checkin currency add <玩家> <数量>`、`/money add <玩家> <数量>` |
| 扣除余额 | `/qiandao remove <玩家> <数量>` | `/qiandao currency <操作> <玩家> <数量>`、`/checkin currency <操作> <玩家> <数量>`、`/money <操作> <玩家> <数量>` |
| 清除今日签到 | `/qiandao clear` 或 `/qiandao clear today` | `/checkin clear`、`/checkin clear today` |
| 重新加载奖励配置 | `/qiandao reload` | 无 |

`<玩家>` 使用 Minecraft 的玩家选择器参数，可以一次指定多个已知玩家。数量必须是大于 0 的整数；扣除数量不会超过目标玩家当前余额。清除操作只影响服务器当前日期的签到状态、名次和时间，并重新计算连续签到，不会回滚已经发放的货币或月度奖励。

上表中的 `<查询操作>` 可替换为 `balance` 或 `get`；扣除命令中的 `<操作>` 可替换为 `remove`、`deduct` 或 `take`。这些子命令的参数顺序均为玩家选择器后跟正整数数量。

## 数据与备份

签到数据保存在主世界的 Minecraft `SavedData` 中，数据 ID 为 `qiandao_data`，通常对应：

```text
<世界目录>/data/qiandao_data.dat
```

数据按玩家 UUID 保存每日签到日期、名次、签到时间、累计天数、连续天数、余额和月度奖励领取记录。所有维度共享同一份数据。迁移或升级前应先完全停止服务器，并备份整个世界目录，尤其是 `data/qiandao_data.dat` 与 `config/qiandao-rewards.json`。只备份配置文件不会包含签到历史或余额。

服务器日期以 Java 进程系统时区为准；漏签后再次签到会从 1 天重新计算连续签到。模组不会向外部服务上传玩家数据。

## 常见问题

### 命令没有打开界面

确认命令由玩家而不是控制台执行，并检查服务器和客户端是否都安装了本模组及兼容版本的 Fabric API。

### 奖励配置没有生效

确认编辑的是服务器端 `config/qiandao-rewards.json`，JSON 格式有效且数值为非负整数，然后执行 `/qiandao reload`。查看服务器日志中的配置解析错误；出现错误时当前运行会使用默认奖励。

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
