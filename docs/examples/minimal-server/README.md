# 最小服务器示例

先复制 [root-config.json](root-config.json) 到 `config/omnitools/config.json`。该文件适用根配置 `format_version: 4`，默认启用大多数基础模块，关闭权限模块，并且不开放指令奖励或控制台菜单命令，适合新服。

再从各[模块主说明](../../index.md#模块配置)复制严格 JSON 配置到对应模块目录。首次部署和任意根配置修改后执行 `/omnitools reload`；模块文件的单独修改可使用 `/omnitools reload <module-id>`。服务器重载成功前不要删除世界 `data/` 或奖励账本。
