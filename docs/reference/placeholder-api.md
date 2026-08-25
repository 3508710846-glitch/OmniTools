# 可选 Text Placeholder API

OmniTools 声明的可选依赖为 Text Placeholder API `2.8.2+1.21.10`。未安装 API，或根配置 `integrations.placeholder_api.enabled` 为 `false` 时，OmniTools 仍能正常启动；第三方文本占位符会安全回退为 `-`。

在已安装并启用 API 时，外部占位符写作 `%namespace:path 可选参数%`。例如：

```text
&a坐标: %player:pos_x% %player:pos_y% %player:pos_z%
&b世界: %world:name%  &eTPS: %server:tps%
&f积分: %player:objective kills%
```

已按 API 2.8.2 内置注册表核对的类别：

| 类别 | 已验证例子 | 说明 |
| --- | --- | --- |
| 玩家 | `%player:uuid%`、`%player:health%`、`%player:biome%` | 使用当前玩家上下文。 |
| 世界 | `%world:id%`、`%world:name%`、`%world:day%`、`%world:time%` | 无玩家上下文时 API 通常使用主世界。 |
| 服务器 | `%server:tps%`、`%server:mspt%`、`%server:uptime%`、`%server:version%` | 来自当前服务端。 |
| 计分板 | `%player:objective <目标名>%` | 参数是现有 objective 名称。 |

第三方模组可动态注册更多 `namespace:path`，因此不能在 OmniTools 文档中预列“全部第三方占位符”。用 `/omnitools diagnose` 确认 API 可用；若显示 `-`，先检查模组已安装、集成开关和占位符 ID/参数。

这些文本占位符只能用于玩家可见文本，绝不能用于命令菜单或奖励的控制台命令；命令安全规则见[统一奖励](rewards.md)。
