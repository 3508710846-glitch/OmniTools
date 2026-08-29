# Title Effects v2

The preferred title configuration is `config/omnitools/titles/config.json` with `format_version: 2`.

Each title stores an `effects` array of inline effect objects. Every object requires a unique `id` within that title and a `type`: `POTION`, `ATTRIBUTE`, `PARTICLE`, or `PERMISSION`. Type-specific fields are the same as the legacy effect configuration. `display` is optional and falls back to `name`; `effects: []` explicitly means that the title has no wearable effects.

```json
{
  "format_version": 2,
  "nameplate_mode": "scoreboard_team",
  "team_conflict_policy": "omnitools_priority",
  "titles": [
    {
      "id": "title_mine_1",
      "display": "§6[§r矿工大师 I§6] §r",
      "rarity": "legendary",
      "tooltip": [],
      "effects": [
        {
          "id": "haste_1",
          "name": "急迫 I",
          "type": "POTION",
          "effect": "minecraft:haste",
          "amplifier": 0,
          "duration": -1,
          "display": "§a✔ 急迫 I（+20% 挖掘速度）"
        }
      ]
    }
  ]
}
```

`format_version: 1` remains supported. In v1, `titles[].effects` is an array of IDs resolved from `config/omnitools/title_effects/config.json`. When a v2 title is present, its inline array takes precedence and is never mixed with legacy IDs. The `title_effects` module remains the global effect switch: disabling it keeps titles claimable, wearable, and previewable but prevents effect application.

Effects are rendered from the same title definition in the title GUI and achievement reward previews. Viewing either screen never grants an effect.
