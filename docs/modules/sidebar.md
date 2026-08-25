# 侧边栏

## 1. 用途与场景

侧边栏使用原版计分板显示动态文本。每位玩家可切换显示，文本通过统一渲染器解析内置与可选第三方占位符。

## 2. 前置条件、关联模块与开关

根开关为 `modules.sidebar.enabled`。Text Placeholder API 是可选依赖；未安装时内置占位符仍正常。

## 3. 配置路径与重载

文件为 `config/omnitools/sidebar/config.json`，修改后执行 `/omnitools reload`。

## 4--6. 最小配置、教学版与可复制版

教学版，不能直接复制：

```jsonc
{
  "format_version": 2,
  "default_visible": true,
  "refresh_interval_ticks": 20, // 5--600
  "title": "&b&lOmniTools",
  "conflict_policy": "skip", // skip、replace、restore
  "lines": [{ "id": "money", "text": "&e货币: &f%balance_formatted%" }]
}
```

可直接复制版：

```json
{
  "format_version": 2,
  "default_visible": true,
  "refresh_interval_ticks": 20,
  "title": "&b&lOmniTools",
  "conflict_policy": "skip",
  "lines": [
    { "id": "money", "text": "&e货币: &f%balance_formatted%" },
    { "id": "checkin", "text": "&a签到: &f%checkin_total_days%" },
    { "id": "online", "text": "&d在线: &f%online_today_hms%" },
    { "id": "world", "text": "&b世界: &f%world:name%" }
  ]
}
```

## 7. 字段表

| 字段 | 类型 | 必填 | 默认/范围 | 常见错误 |
| --- | --- | --- | --- | --- |
| `format_version` | 整数 | 否 | 2 | 使用非正整数。 |
| `default_visible` | 布尔 | 否 | true | 写成字符串。 |
| `refresh_interval_ticks` | 整数 | 否 | 5--600，默认 20 | 写 1。 |
| `title` | 文本 | 否 | 最长 64 | 太长或不可见。 |
| `lines` | 数组 | 是 | 最多 15 行 | 行 ID 重复。 |
| `lines[].id` | 字符串 | 是 | 1--32，字母数字下划线连字符 | 含空格。 |
| `lines[].text` | 文本 | 是 | 最长 256、非空 | 空文本。 |
| `conflict_policy` | 枚举 | 否 | `skip`/`replace`/`restore` | 新配置使用 `warn`/`disabled`。 |

## 8. 全部配置场景

`skip` 发现第三方侧边栏时不覆盖，推荐默认；`replace` 明确替换；`restore` 在 OmniTools 关闭时恢复先前显示目标。旧 `warn`、`disabled` 仅为兼容迁移值，会按 `skip` 处理，不能作为新配置示例。

## 9. 指令、权限与默认角色

`/omnitools sidebar toggle` 和 `status` 默认 `PLAYER`。

## 10. 占位符

可用[17 个内置占位符](../reference/placeholders.md)；可选 API 文法和 `%world:name%` 见[Placeholder API](../reference/placeholder-api.md)。

## 11. 数据与升级

每玩家显示偏好保存在 SavedData。禁用模块会清理 OmniTools 侧边栏；`restore` 可恢复此前目标。

## 12. 验收与排错

分别测试 `skip`、`replace`、`restore`，确认刷新周期与玩家切换命令。第三方占位符显示 `-` 时检查 API 与注册 ID。
