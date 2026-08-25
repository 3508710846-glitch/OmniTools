# 侧边栏

## 1. 功能简介

侧边栏为每位玩家发送独立的原版 `SIDEBAR` scoreboard 数据包，可显示自定义标题、文字和占位符。它不修改全局 `ServerScoreboard`，原版客户端无需安装 OmniTools。

## 2. 模块开关

`config/omnitools/config.json`：

```json
{
  "modules": {
    "sidebar": { "enabled": true }
  }
}
```

禁用时服务端为所有在线玩家清除侧边栏，个人显示偏好不删除；重新启用后按原偏好恢复。

## 3. 初始配置

首次加载生成 `config/omnitools/sidebar/config.json`：

```json
{
  "format_version": 1,
  "default_visible": true,
  "refresh_interval_ticks": 20,
  "title": "&b&lOmniTools",
  "conflict_policy": "warn",
  "lines": [
    { "id": "player", "text": "&f玩家：&b%omnitools:title_plain%" },
    { "id": "balance", "text": "&e货币：&f%omnitools:balance_formatted%" },
    { "id": "checkin", "text": "&a签到天数：&f%omnitools:checkin_total_days%" },
    { "id": "streak", "text": "&6连续签到：&f%omnitools:checkin_streak_days%" },
    { "id": "online", "text": "&d今日在线：&f%omnitools:online_today_hms%" },
    { "id": "achievement", "text": "&b成就：&f%omnitools:achievements_unlocked%/%omnitools:achievements_total%" }
  ]
}
```

文件缺失时创建默认示例。格式错误时不覆盖原文件，完整重载保留旧侧边栏快照与当前显示。

## 4. 指令与权限

| 指令 | 别名 | 用途 | 默认权限 | 仅玩家 |
| --- | --- | --- | --- | --- |
| `/omnitools sidebar on` | 无 | 显示自己的侧边栏 | `sidebar.toggle` (`PLAYER`) | 是 |
| `/omnitools sidebar off` | 无 | 隐藏自己的侧边栏 | `sidebar.toggle` (`PLAYER`) | 是 |
| `/omnitools sidebar toggle` | 无 | 切换显示状态 | `sidebar.toggle` (`PLAYER`) | 是 |
| `/omnitools sidebar status` | 无 | 查看自己的显示状态 | `sidebar.status` (`PLAYER`) | 是 |

## 5. 配置字段

| 字段 | JSON 类型 | 必填 | 默认值/范围 | 作用与错误行为 |
| --- | --- | --- | --- | --- |
| `format_version` | number | 否 | `1`，正整数 | 配置格式标记。 |
| `default_visible` | boolean | 否 | `true` | 没有个人偏好记录的新玩家是否默认显示。 |
| `refresh_interval_ticks` | integer | 否 | `20`，范围 `5-600` | 刷新间隔；文本未变化时不发送更新。 |
| `title` | string | 否 | `""` | 标题，支持 `&` 颜色，格式化前后均最多 64 字符。 |
| `conflict_policy` | string | 否 | `warn`；可写 `warn`、`replace`、`disabled` | 当前版本会读取、校验并保存该字段，但尚未据此改变渲染行为；原版协议也不能可靠检测其他模组侧边栏占用。 |
| `lines` | array | 是 | 最多 15 项 | 自上而下的显示行。 |
| `lines[].id` | string | 是 | `[A-Za-z0-9_-]{1,32}`，唯一 | 行的稳定 ID。 |
| `lines[].text` | string | 是 | 非空，最多 256 输入字符 | 显示文字，支持颜色与占位符；渲染后的纯文本最多 40 字符。 |

## 6. 使用示例

自定义固定文字与内置占位符：

```json
{
  "format_version": 1,
  "default_visible": true,
  "refresh_interval_ticks": 40,
  "title": "&6我的服务器",
  "conflict_policy": "warn",
  "lines": [
    { "id": "welcome", "text": "&e欢迎来到服务器" },
    { "id": "money", "text": "&a余额：%omnitools:balance_formatted%" }
  ]
}
```

保存后执行 `/omnitools reload`。未知、未安装或解析失败的第三方占位符显示 `-` 并记录警告；配置字段非法时修正 JSON 后重载，旧显示不会被清空。

## 7. 数据保存

世界 `SavedData` 的 `SidebarPreferenceData` 保存 `玩家 UUID -> 是否显示`。JSON 只保存呈现规则。玩家重连、重生和切换维度时服务端重建或刷新个人侧边栏。

升级或迁移前必须同时备份世界目录和 `config/omnitools/`。

## 8. 热重载与依赖

成功重载立即重渲染在线玩家；模块禁用清除所有侧边栏。它可读取签到、在线奖励、称号和成就的内置占位符，但这些模块关闭时会返回各自的回退值。一个玩家的原版 `SIDEBAR` 槽位只有一个，其他模组随后覆盖时最终显示以后发送的数据为准。`conflict_policy` 目前不会可靠检测或阻止这种覆盖。完整候选快照失败时旧侧边栏继续运行。
