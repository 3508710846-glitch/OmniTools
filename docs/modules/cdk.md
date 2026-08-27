# CDK 兑换码

## 1. 用途、场景与依赖

CDK 用于按活动向玩家投递统一奖励。适合开服礼、节日活动和补偿，不提供客户端文本输入 GUI，玩家通过命令兑换。奖励可引用货币、物品、称号、补签卡或受控指令；完整格式见[奖励参考](../reference/rewards.md)。

## 2. 根开关与禁用行为

根开关为 `modules.cdk.enabled`。关闭后 `/omnitools cdk` 不可用，不会继续处理新的兑换；已保存的兑换记录和待投递奖励不会被删除。重新启用后，活动从当前有效配置恢复。

## 3. 配置路径、生成与重载

配置文件为 `config/omnitools/cdk/config.json`，首次启动会生成 `format_version: 1`、默认安全限制和空活动列表。修改后执行：

```text
/omnitools reload cdk
```

涉及 `config/omnitools/config.json` 或 `common/` 模板时，使用完整 `/omnitools reload`。校验失败时旧快照继续运行。

## 4. 命令与默认权限

| 命令 | 权限动作 | 默认角色 | 说明 |
| --- | --- | --- | --- |
| `/omnitools cdk redeem <code>` | `cdk.redeem` | `PLAYER` | 兑换一个 CDK。 |
| `/omnitools cdk status` | `cdk.redeem` | `PLAYER` | 查看本人已记录活动的投递状态。 |
| `/omnitools cdk admin list` | `cdk.admin` | `ADMIN` | 查看当前活动的兑换计数。 |
| `/omnitools cdk admin audit <id>` | `cdk.admin` | `ADMIN` | 查看指定活动的兑换计数。 |

普通玩家只会收到“CDK 无效或当前不可用”；系统不会区分码不存在、过期、用尽或已兑换，以免泄露可枚举信息。

## 5. 配置字段

| 字段 | 类型 | 必填 | 范围 / 默认值 | 错误行为 |
| --- | --- | --- | --- | --- |
| `format_version` | 整数 | 是 | 必须为 `1` | 拒绝重载。 |
| `security` | 对象 | 是 | 见下表 | 缺失或越界时拒绝重载。 |
| `campaigns` | 数组 | 是 | 可为空 | 非数组时拒绝重载。 |
| `campaigns[].id` | 稳定 ID | 是 | 1--64 位小写字母、数字、`_`、`.`、`-` | 重复或无效时拒绝重载。 |
| `campaigns[].code` | 字符串 | 是 | 长度不超过 `security.max_code_length` | 原始码不写入日志或审计。 |
| `campaigns[].starts_at` / `expires_at` | UTC ISO-8601 时间 | 否 | 开始必须早于到期 | 无效时间或顺序拒绝重载。 |
| `campaigns[].max_uses` | 非负整数 | 是 | `0` 为不限；最大 10,000,000 | 到达限制后不再兑换。 |
| `campaigns[].rewards` | 奖励数组 | 是 | 至少一个；支持模板 | 无效奖励或引用拒绝重载。 |

| `security` 字段 | 范围 | 默认值 |
| --- | --- | --- |
| `max_code_length` | 4--256 | 64 |
| `cooldown_ticks` | 0--72,000 | 20 |
| `max_failed_attempts` | 1--100 | 5 |
| `lockout_seconds` | 1--86,400 | 60 |

## 6. 最小可用 JSON

将下列严格 JSON 写入 `config/omnitools/cdk/config.json`。活动的 `id` 是永久稳定 ID；已有兑换记录后不要改名、删除或复用它。

```json
{
  "format_version": 1,
  "security": {
    "max_code_length": 64,
    "cooldown_ticks": 20,
    "max_failed_attempts": 5,
    "lockout_seconds": 60
  },
  "campaigns": [
    {
      "id": "welcome_2026",
      "code": "OMNI-2026-WELCOME",
      "max_uses": 0,
      "rewards": [
        { "id": "welcome_coins", "type": "currency", "amount": 500 },
        { "id": "welcome_cards", "type": "makeup_card", "amount": 2 }
      ]
    }
  ]
}
```

## 7. JSONC 教学配置

带注释示例位于[配置平台示例目录](../examples/config-platform/cdk.jsonc)。它演示公共奖励模板覆盖；删除注释后才能写入真实配置文件。

## 8. 高级场景

用 `starts_at`、`expires_at` 和 `max_uses` 控制活动窗口与全服次数。`max_uses: 0` 表示不限制全服次数，但每个玩家按 UUID 对同一 `campaigns[].id` 永远只能兑换一次。

配置加载后只保留规范化码的 SHA-256 哈希。已有兑换记录的活动定义会被指纹保护：修改活动 ID、兑换码、奖励、总次数或有效期时，重载会拒绝该活动。需要新奖励时建立新的活动 ID，不要扩展旧活动。

## 9. 奖励、补签卡与模板

`campaigns[].rewards` 使用统一奖励数组，并可通过 `template` 或 `$ref` 引用 `common/rewards.json`。临时称号的 `active_days`、三种续期策略及“仅在佩戴时扣时间”的规则以[奖励参考](../reference/rewards.md)为准。

`makeup_card` 奖励发放的是签到模块玩家数据中的虚拟权益，不是可交易物品。补签资格、购买、每月额度和是否计入月度里程碑由[每日签到](daily-checkin.md#补签卡规则)配置和说明决定。

## 10. 数据、账本与备份

兑换事件 ID 固定为 `cdk:<campaign-id>:<player-uuid>`。统一奖励账本确保重复输入、重连和服务端中断恢复不会重复发放；背包容纳不下的物品仍进入奖励箱。CDK 兑换计数、玩家兑换记录和活动定义指纹保存于世界数据，升级或回档前必须备份世界 `data/` 和配置目录。

## 11. 热重载、验收与排错

重载成功后新增活动立即可兑换；失败后旧活动与旧安全限制继续运行。测试时应验证同一玩家重复兑换仅投递一次、两人同时兑换最后一个限量码只成功一人，以及包含 NBT 物品、限时称号和补签卡的奖励在重启后不重复发放。

若兑换失败，先检查模块开关、活动时间、总次数、玩家历史记录和奖励引用。不要尝试在日志中查找原始兑换码；该值不会被保留或回显。
