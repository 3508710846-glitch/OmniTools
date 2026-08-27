# 奖励示例

`four-rewards.json` 是一个严格 JSON 的奖励数组，可放入已启用的每日签到、在线奖励、成就或 CDK 的 `rewards` 字段。适用的奖励语法没有单独格式版本；使用它的模块仍须保留自己的 `format_version`。修改后执行相应的 `/omnitools reload <module-id>`，修改公共模板或根安全设置时改用 `/omnitools reload`。

物品奖励可二选一：普通改名、Lore、附魔优先使用 `item`、`count`、`components`；完整物品堆或复杂组件使用 `nbt`。指令奖励还必须在根配置同时启用总开关和命令根白名单。示例中的 `active_days` 称号只有在线且佩戴时扣除有效时间；完整字段规则以[统一奖励格式](../../reference/rewards.md)为准。
