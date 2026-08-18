# qiandao

一个适用于 Minecraft Java 版的 Fabric 每日签到模组。玩家可以打开 5x9 的签到界面，点击当天的日期完成签到，并查看当天签到序号、累计签到天数和连续签到天数。

## 功能

- 提供 `/qiandao`、`/qiandao open` 和 `/checkin` 三个命令入口。
- 以当前月份的日期填充界面前四行（最多 31 天），超过当月天数的格子显示为灰色玻璃板。
- 已签到日期显示附魔书，未签到日期显示普通书；过去日期和未来日期分别标记对应状态。
- 只有当天的日期格可以完成签到，日期和槽位由服务器校验，避免客户端伪造签到。
- 底部一行中央的玩家头像显示当天签到序号、累计签到天数和当前连续签到天数。
- 使用世界持久化数据保存签到记录，服务器重启后数据仍然保留，并在所有维度之间共享。
- 同一天重复点击不会重复计数；跨天打开界面时会自动按照服务器当前日期刷新。

## 运行环境

- Minecraft Java 版 `1.21.11`
- Fabric Loader `0.19.3` 或更高版本
- Fabric API `0.141.6+1.21.11`（或与当前游戏版本匹配的兼容版本）
- Java `21` 或更高版本

这是一个同时包含服务端和客户端入口的模组。连接服务器的客户端也需要安装本模组及其依赖。

## 安装

1. 安装与 Minecraft `1.21.11` 匹配的 Fabric Loader、Fabric API 和 Java 21。
2. 从 `build/libs` 获取 `qiandao-<版本>.jar`，或使用下方的构建命令生成该文件。
3. 将模组 JAR 放入客户端和服务器的 `mods` 目录。
4. 启动游戏或服务器，并确认 Fabric API 已一同加载。

## 使用方法

在游戏中输入以下任意命令打开签到界面。命令只能由游戏内玩家执行：

```text
/qiandao
/qiandao open
/checkin
```

打开界面后：

1. 前四行显示当月日期。超过当月实际天数的格子会显示为灰色玻璃板。
2. 点击当天日期的格子完成签到。点击其他日期、空白格或使用非普通点击方式不会完成签到。
3. 签到成功后，当天格子会变为附魔书，并在聊天栏提示当天签到序号。序号按全服玩家当天的签到先后顺序计算。
4. 将鼠标悬停在底部中央的玩家头像上，可查看当天序号（未签到时显示预计序号）、累计签到天数和连续签到天数。

签到由服务器根据服务器 Java 虚拟机的系统时区确定日期。跨越午夜后，再次操作日期格会将已打开的界面刷新为新的一天。连续签到按自然日计算；漏签后再次签到会从 1 天重新开始。

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

清理后重新构建可执行 `clean build`。项目当前没有自动化测试用例，构建任务会完成 Java 编译、资源处理和 JAR 重映射检查。

## 项目结构

```text
src/main/java/dev/modmind/qiandao/
├── ModMindEntry.java       # 模组初始化与命令注册
├── ModMindClient.java      # 客户端界面注册
├── CheckinScreenHandler.java # 签到菜单、槽位和服务器校验
├── CheckinScreen.java      # 签到界面渲染
└── CheckinData.java        # 世界持久化签到数据

src/main/resources/assets/qiandao/
├── lang/zh_cn.json         # 简体中文文本
└── lang/en_us.json         # 英文文本
```

## 数据与隐私

签到记录保存在世界存档的 Minecraft `SavedData` 中，按玩家 UUID 区分玩家，并记录每天的签到状态、当天签到序号、累计天数和连续天数。模组不会向外部服务上传玩家数据。

## 许可证

本项目使用 [MIT License](LICENSE) 发布。
