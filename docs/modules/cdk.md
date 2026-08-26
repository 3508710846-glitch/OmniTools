# CDK 兑换码

CDK 是独立模块，根开关为 `modules.cdk.enabled`，配置文件为 `config/omnitools/cdk/config.json`。首版通过命令输入兑换码，不使用客户端文本输入 GUI。

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
      "starts_at": "2026-08-01T00:00:00Z",
      "expires_at": "2026-09-01T00:00:00Z",
      "max_uses": 0,
      "rewards": [
        { "id": "coins", "type": "currency", "amount": 500 },
        { "id": "makeup_cards", "type": "makeup_card", "amount": 2 },
        {
          "id": "vip_7d",
          "type": "title",
          "title": "architect",
          "duration": { "mode": "active_days", "days": 7 },
          "renewal": "extend"
        }
      ]
    }
  ]
}
```

`campaigns[].id` 是永久稳定 ID。每位玩家按 UUID 对同一个活动只能兑换一次；`max_uses: 0` 表示不限全服次数，其他正整数限制全服总兑换次数。活动仅保存规范化后兑换码的哈希，服务端日志、普通玩家反馈和管理员审计都不会回显原始兑换码。

一旦活动已有兑换记录，不能修改或删除其活动 ID、兑换码、奖励、总次数和有效期。`/omnitools reload` 将拒绝这种配置并保留旧快照；要发放新奖励请建立新活动 ID。

命令：

- `/omnitools cdk redeem <code>`：兑换。
- `/omnitools cdk status`：查看本人的已记录且待投递的活动。
- `/omnitools cdk admin list`：查看所有当前活动的兑换计数。
- `/omnitools cdk admin audit <id>`：查看一个活动的兑换计数。

玩家兑换事件固定为 `cdk:<campaign-id>:<player-uuid>`，使用统一奖励账本投递。重复输入、重连和服务器中断恢复不会重复发放货币、补签卡、称号或物品；待处理物品仍由奖励箱处理。

# 补签卡

补签卡属于签到模块的玩家数据，不是可交易物品。配置位于 `config/omnitools/daily_checkin/config.json`：

```json
"makeup": {
  "enabled": true,
  "max_cards": 99,
  "max_backfill_days": 7,
  "max_uses_per_calendar_month": 3,
  "earliest_eligible_day": "first_seen",
  "affects_streak": true,
  "daily_reward_policy": "none",
  "counts_for_monthly_milestones": true,
  "purchase": { "enabled": true, "price": 200 }
}
```

只能补今天之前、过去 `max_backfill_days` 天以内、且不早于玩家首次进入服务器的漏签日期。已签到、未来日期、卡不足和本月次数用完都会在不扣卡的情况下失败。补签在一份玩家存档同步操作中完成日期校验、扣卡、记录来源和连续签到重算。

默认不补发每日奖励（`daily_reward_policy: "none"`），但会计入月度进度。只有明确设置 `daily_reward_policy: "grant"` 才会补发每日奖励；这是高经济影响选项。`counts_for_monthly_milestones` 控制补签是否参与月度里程碑。

命令：

- `/omnitools checkin cards`
- `/omnitools checkin cards buy <amount>`
- `/omnitools checkin makeup <yyyy-MM-dd>`
- `/omnitools checkin cards admin give <player> <amount>`
- `/omnitools checkin cards admin take <player> <amount>`

签到日历将历史漏签显示为时钟入口，点击后必须再次确认才会扣卡。主页的补签卡信息格显示持有数量、本月使用次数和购买入口。

# 验收案例

1. 同一 CDK 连续兑换两次，奖励只写入一次；两名玩家同时兑换最后一个限量活动，只有一人成功。
2. CDK 同时包含 NBT 物品、`active_days` 称号和补签卡时，重启后不会重复发放。
3. 无卡、已签到、超期、未来日期或月度额度耗尽时，补签不扣卡。
4. 补昨天后连续签到正确衔接，且不会改变今天的签到名次。
5. 卸下限时称号、重启服务器、称号到期自动卸下、永久称号再获临时奖励时，剩余有效时间符合奖励参考规则。
6. `/omnitools reload` 失败时，旧 CDK 和补签规则仍继续生效。
