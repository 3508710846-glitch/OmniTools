# 配置基础

真实配置文件必须是严格 JSON：键和字符串使用双引号，最后一项后没有逗号，且不能包含 `//` 或 `/* */` 注释。文档中的 `jsonc` 是教学版，`json` 才可直接复制。

物品使用命名空间 ID，例如 `minecraft:diamond`。普通改名、Lore、附魔优先使用原版物品组件字符串，例如 `"components": "[minecraft:custom_name='{\"text\":\"礼包\"}']"`。统一奖励也支持完整 ItemStack SNBT 的 `nbt` 字段，适合复杂容器内容或已有商店 SNBT；`nbt` 不能与 `item`、`count`、`components` 同时出现，详见[统一奖励](../reference/rewards.md)。

玩家可见文本可使用 `&` 颜色代码和 `%omnitools:balance%` 这类文本占位符。配置命令不是文本模板：命令只允许 `{player_name}`、`{player_uuid}`、`{player_x}`、`{player_y}`、`{player_z}`、`{player_world}`，不能使用 `%...%`。

保存 UTF-8 编码。编辑后执行 `/omnitools reload`，然后查看控制台第一条 `[omnitools]` 配置错误；错误重载不会替换当前快照。
