# 文档发布前检查

每次修改用户可见文档时，提交前完成下列检查。

- 所有 Markdown 相对链接存在且锚点可解析；README 到任一模块主说明不超过三次跳转。
- 严格 `.json` 示例可解析；`.jsonc` 仅作为教学文件，并写明目标路径和移除注释的要求。
- 文档中的命令和默认角色能在 `CommandAction` 与 `ModMindEntry` 中找到。
- 配置字段、默认值、格式版本和模块 ID 能在对应 `*Config`、`ModuleId`、默认生成配置及 Schema 中找到。
- 根配置当前为 `format_version: 4`；模块示例使用各自 Schema 支持的格式版本。
- 奖励、称号、物品与模板 ID 的文字说明和配置内容一致。
- 礼包文档必须区分当前实现与规划能力：`grantKey` 幂等、实例快照、持久化投递批次、确认 GUI、商店购买事务，以及固定/随机/玩家自选技能经验均可作为当前行为；权重随机、保底机制、礼包专属占位符、实体礼包交易和商店自动退款/重发必须标为未实现。
- 礼包示例必须同时通过 `docs/schemas/packages.schema.json` 和运行时 `PackageConfig` 的字段约束；严格 JSON 代码块不能包含注释，教学 `jsonc` 必须注明目标路径。
- UTF-8 中文、JSON 转义和代码块语言标记正常显示。
- 旧链接页面仍只包含跳转；`archive/` 不被 README 或 `docs/index.md` 主导航引用。
- README 仅保留介绍、安装、最短上手和文档入口，没有重新变成模块配置手册。

建议命令：`rg --files docs README.md` 盘点文件，使用 JSON 解析器检查所有严格 `.json` 示例，并运行 ModMind `validate_content` 验证项目 JSON 资源。对涉及运行时配置的事实变更，还应执行对应的构建与服务端冒烟测试。
