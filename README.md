# qiandao

`qiandao` 是一个适用于 Minecraft Java 版的 Fabric 每日签到模组。玩家可以通过命令打开签到 GUI，完成当天签到，查看自己的签到统计，并查看全服当天的签到顺序。

## 功能概览

- 提供 5x9 的月历签到界面，前四行显示当月日期。
- 只有当天日期可以完成签到；签到校验和计数全部在服务端执行。
- 已签到日期显示附魔书，未签到日期显示普通书，过去日期和未来日期显示对应状态。
- 底部一行第一个格子提供“查看今日签到记录”入口。
- 今日签到记录使用玩家头颅按签到时间从早到晚排列，并在头颅提示中显示玩家名称、签到名次和签到时间。
- 记录界面支持分页，每页最多显示 45 条记录，并提供返回、上一页和下一页按钮。
- 底部中央的玩家头颅显示当天签到名次、累计签到天数和连续签到天数。
- 玩家加入服务器时，如果当天尚未签到，会收到可点击的签到提醒。
- 签到成功播放音效，并向全服广播签到名次。
- 使用世界持久化数据保存签到记录，服务器重启后数据仍然保留，所有维度共享同一份记录。
- 管理员可以清除当天的全部签到；清除后签到记录、当天名次和当天时间都会移除，玩家可以重新签到。
- 每次成功签到都会发放每日奖励；连续签到第 3、5、10、15、25 天还会额外发放阶段奖励，奖励可通过服务器配置改为物品或命令。

## 运行环境

- Minecraft Java Edition `1.21.11`
- Fabric Loader `0.19.3` 或更高版本
- Fabric API `0.141.6+1.21.11`
- Java `21` 或更高版本

本模组同时包含服务端和客户端入口。服务器和每个连接服务器的客户端都需要安装本模组及 Fabric API。

## 安装

1. 为目标 Minecraft 实例安装 Fabric Loader、Fabric API 和 Java 21。
2. 从本项目的 `build/libs` 目录取得 `qiandao-<版本>.jar`，或者先按照[构建](#构建)章节生成模组文件。
3. 将模组 JAR 放入服务器和客户端各自的 `mods` 目录。
4. 启动服务器和客户端，确认 Fabric Loader、Fabric API 与 `qiandao` 均已加载。

客户端也必须安装模组，因为签到界面和今日记录界面包含客户端 GUI 注册。

## 使用方法

### 打开签到界面

在游戏内输入以下任意命令：

```text
/qiandao
/qiandao open
/checkin
```

这些打开界面的命令需要由玩家执行，不能由服务器控制台直接打开玩家界面。

### 完成签到

1. 打开签到界面后，前四行显示当前月份的日期，最多显示 31 个日期格。
2. 点击当天日期的格子完成签到。点击其他日期、灰色空格或使用非普通拾取点击不会完成签到。
3. 签到成功后，当天格子变为附魔书，行动栏会显示当天签到名次，并播放音效。
4. 同一个玩家当天重复点击不会重复计数。

签到名次按全服玩家当天成功签到的先后顺序计算。日期由服务器 Java 虚拟机的系统时区确定；跨过午夜后，操作已打开的签到界面会触发日期刷新。

### 查看今日签到记录

在签到界面底部一行的第一个格子点击时钟图标，即可打开“今日签到记录”界面。

- 记录从左到右、从上到下按签到时间排列。
- 每个记录使用对应玩家的头颅；在线玩家显示实时皮肤，离线玩家使用已保存的玩家 UUID。
- 将鼠标悬停在头颅上，可以查看玩家名称、签到名次和 `HH:mm:ss` 格式的签到时间。
- 记录超过一页时，使用底部的上一页和下一页按钮切换；点击返回按钮回到签到界面。
- 旧版本存档中没有保存签到时间的历史记录会显示“签到时间：未知”。

### 加入提醒

玩家加入服务器时，若当天尚未签到，聊天栏会显示“点击签到”文本。点击该文本会执行 `/qiandao` 并打开签到界面；已经签到的玩家不会重复收到这条提醒。

### 签到奖励

玩家首次完成当天签到后，会立刻获得每日签到奖励。连续签到达到 3、5、10、15、25 天时，会在当天奖励之外再获得对应的连续签到奖励；同一天重复点击不会重复发放。

服务器首次启动本模组时会创建 `config/qiandao-rewards.json`。默认配置会发放 2 个面包，并分别在第 3、5、10、15、25 天额外发放铁锭、金锭、钻石、绿宝石和下界合金碎片。修改后重启服务器即可应用新奖励。

配置包含 `dailyRewards` 和 `streakRewards` 两部分。以下是展示物品奖励与命令奖励混用方式的示例，可按需替换生成的默认配置：

```json
{
  "dailyRewards": [
    { "type": "item", "item": "minecraft:bread", "count": 2 },
    { "type": "command", "command": "effect give {player} minecraft:regeneration 5 0 true" }
  ],
  "streakRewards": {
    "3": [
      { "type": "item", "item": "minecraft:iron_ingot", "count": 3 }
    ],
    "5": [
      { "type": "command", "command": "your_currency_mod add {player} 100" }
    ],
    "10": [
      { "type": "item", "item": "minecraft:diamond", "count": 2 }
    ],
    "15": [
      { "type": "item", "item": "minecraft:emerald", "count": 8 }
    ],
    "25": [
      { "type": "item", "item": "minecraft:netherite_scrap", "count": 2 }
    ]
  }
}
```

- 物品奖励使用 `type: "item"`，`item` 为命名空间物品 ID，`count` 必须是 1 到 256 的整数。数量超过单组上限时会自动拆分；背包满时物品会掉落在玩家脚下。
- 命令奖励使用 `type: "command"`。命令不需要写 `/`，由服务器以最高权限、签到玩家为命令实体执行。
- 命令中的 `{player}` 会替换为签到玩家名称；由于命令实体就是签到玩家，也可以使用 `@s`。例如效果命令可写为 `effect give @s minecraft:speed 30 0 true`。
- 无效物品 ID 或执行失败的命令会记录到服务器日志，并跳过该奖励，不会阻止签到数据保存或其他奖励发放。

## 管理员命令

以下命令用于清除当天的全部签到：

```text
/qiandao clear
/qiandao clear today
/checkin clear
/checkin clear today
```

`clear` 和 `clear today` 等价。命令需要 Minecraft 2 级权限（通常是对应权限等级的管理员），服务器控制台也可以执行。命令执行后会：

- 删除当天所有玩家的签到状态、签到名次和签到时间；
- 删除当天的全服签到计数；
- 从玩家累计签到天数中移除当天，并重新计算连续签到状态；
- 让被清除的玩家可以再次签到，重新签到时从当天第 1 名开始计数。

该命令只影响服务器当前日期，不会删除其他日期的历史记录。执行后会向命令执行者返回实际清除的玩家数量。

## 数据与日期规则

- 签到数据保存在世界存档的 Minecraft `SavedData` 中，并按玩家 UUID 区分玩家。
- 保存内容包括每日签到状态、签到名次、签到时间、最近一次名称、累计签到天数和连续签到天数。
- 数据在服务器重启后仍然保留，并在服务器的所有维度之间共享。
- 日期使用服务器 Java 进程的系统时区，而不是客户端本地时区。
- 漏签后再次签到会从 1 天重新开始连续签到；管理员清除当天签到后，连续签到和累计天数也会按清除后的历史记录重新计算。
- 模组不会向外部服务上传玩家数据。

## 构建

项目使用 Gradle Wrapper，无需单独安装 Gradle。在项目根目录执行：

```bash
# Linux/macOS
./gradlew build

# Windows PowerShell
.\gradlew.bat build
```

构建产物位于 `build/libs/`：

- `qiandao-<版本>.jar`：可安装的模组文件
- `qiandao-<版本>-sources.jar`：源代码包

当前版本号由 `gradle.properties` 中的 `mod_version` 定义。需要完整清理后再构建时，可执行：

```bash
# Linux/macOS
./gradlew clean build

# Windows PowerShell
.\gradlew.bat clean build
```

## 项目结构

```text
src/main/java/dev/modmind/qiandao/
├── ModMindEntry.java                 # 模组初始化、加入提醒和命令注册
├── ModMindClient.java                # 客户端菜单界面注册
├── CheckinData.java                  # 世界持久化签到数据和统计逻辑
├── CheckinRewardConfig.java           # 奖励配置读取、校验与默认配置生成
├── CheckinRewardService.java          # 物品与命令奖励发放
├── CheckinScreenHandler.java         # 签到菜单、日期槽位和服务端校验
├── CheckinScreen.java                # 签到界面渲染
├── CheckinRecordsScreenHandler.java  # 今日记录菜单、排序、分页和头颅物品
└── CheckinRecordsScreen.java         # 今日记录界面渲染

src/main/resources/assets/qiandao/
├── lang/zh_cn.json                   # 简体中文文本
└── lang/en_us.json                   # 英文文本
```

## 许可证

本项目使用 [MIT License](LICENSE) 发布。
