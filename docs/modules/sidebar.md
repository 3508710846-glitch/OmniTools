# 侧边栏

## 1. 模块用途和适用场景

侧边栏使用原版计分板向单个玩家发送标题和多行文本，可显示 OmniTools 进度与可选 Placeholder API 文本。原版客户端无需资源包或 HUD 模组。

## 2. 模块依赖与关联模块

模块 ID 为 `sidebar`。它可读取签到、在线、称号、成就和货币占位符，但这些模块关闭时返回安全回退值。第三方文本占位符需要可选 Placeholder API。

## 3. 模块开关配置

```json
{ "modules": { "sidebar": { "enabled": true } } }
```

禁用会清除 OmniTools 自己的侧边栏显示和刷新任务，不删除玩家可见性偏好。

## 4. 初始配置文件位置

首次启动生成 `config/omnitools/sidebar/config.json`。修改后执行 `/omnitools reload`。

## 5. 最小可用配置

```json
{
  "format_version": 2,
  "default_visible": true,
  "refresh_interval_ticks": 20,
  "title": "&bOmniTools",
  "conflict_policy": "skip",
  "lines": [{ "id": "balance", "text": "&e余额: &f%omnitools:balance_formatted%" }]
}
```

## 6. 完整配置示例

```json
{
  "format_version": 2,
  "default_visible": true,
  "refresh_interval_ticks": 20,
  "title": "&b&lOmniTools",
  "conflict_policy": "restore",
  "lines": [
    { "id": "title", "text": "&f称号: &b%omnitools:title_plain%" },
    { "id": "balance", "text": "&e货币: &f%omnitools:balance_formatted%" },
    { "id": "checkin", "text": "&a签到: &f%omnitools:checkin_total_days% 天" },
    { "id": "online", "text": "&d在线: &f%omnitools:online_today_hms%" }
  ]
}
```

## 7. 配置字段表

| 字段 | 类型 | 必填 | 默认值或范围 | 重载方式 |
| --- | --- | --- | --- | --- |
| `format_version` | integer | 否 | 当前 `2` | reload |
| `default_visible` | boolean | 否 | `true` | reload |
| `refresh_interval_ticks` | integer | 是 | 范围 `5-600` | reload |
| `title` | string | 是 | 输入与纯文本均最多 64 字符 | reload |
| `conflict_policy` | string | 是 | `skip`、`replace`、`restore` | reload |
| `lines` | array | 是 | 最多 15 行 | reload |
| `lines[].id` | string | 是 | 唯一，`[A-Za-z0-9_-]{1,32}` | reload |
| `lines[].text` | string | 是 | 非空，输入最多 256 字符 | reload |

旧 v1 的 `warn` 和 `disabled` 会兼容映射为保守的 `skip`。不要把它们用于新配置。

## 8. 指令、别名和权限节点

| 指令 | 别名 | 权限节点 | 默认角色 |
| --- | --- | --- | --- |
| `/omnitools sidebar toggle` | 无 | `sidebar.toggle` | PLAYER |
| `/omnitools sidebar status` | 无 | `sidebar.status` | PLAYER |
| `/omnitools reload` | 无 | `config.reload` | ADMIN |

## 9. GUI 操作说明

侧边栏不使用 GUI。玩家通过 `toggle` 变更自己的可见性偏好；管理员使用模块管理 GUI 或根配置控制模块状态。设置会在重连、重生和维度切换时由服务端恢复。

## 10. 占位符列表及用途

支持全部 17 个 `%omnitools:<id>%` 占位符；内置简写 `%<id>%` 也可用于侧边栏。可选 API 启用后可解析第三方文本占位符；未知值显示 `-` 并只记录一次警告。详见[Placeholder API](../guides/placeholder-api.md)。

## 11. 数据保存位置和升级影响

呈现规则在模块 JSON；每名玩家的显示偏好保存到世界 `SidebarPreferenceData`。升级或禁用模块不删除偏好，重新启用会按新配置重新渲染。

## 12. 与其他模块的联动

侧边栏读取其他模块的占位符，但不强依赖它们。`skip` 在存在外部显示目标时不覆盖；`replace` 明确使用 OmniTools 显示；`restore` 在 OmniTools 清理时恢复先前目标。原版每名玩家只有一个侧边栏显示位，仍应与其他侧边栏模组协调。

## 13. 常见错误及解决方法

| 现象 | 处理 |
| --- | --- |
| 不显示侧边栏 | 检查模块开关、个人 toggle 状态和 `skip` 是否因外部目标跳过。 |
| 显示被其他模组覆盖 | 选择合适的冲突策略，或协调另一侧边栏模组的刷新顺序。 |
| 文本为 `-` | 检查第三方 API 是否安装、占位符拼写与集成开关。 |

## 14. 可复制的验收清单

- [ ] 玩家可切换显示，重连后偏好保留。
- [ ] 5、20、600 tick 的合法刷新周期按配置工作。
- [ ] `skip`、`replace`、`restore` 三种冲突策略分别符合预期。
- [ ] 未安装 Placeholder API 时内置文本与侧边栏仍正常。
