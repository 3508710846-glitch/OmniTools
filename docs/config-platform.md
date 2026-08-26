# Unified configuration platform

OmniTools uses one root snapshot and typed module files. A reload reads every enabled module,
validates the complete candidate, and publishes it only after all checks pass. A failed reload keeps
the previous snapshot.

## Files

```text
config/omnitools/
  config.json
  common/rewards.json
  common/conditions.json
  common/texts.json
  <module>/config.json
```

`config.json` is the only file that controls module switches, timezone, language, integrations, and
command security. The `common` files contain data-only templates. They cannot execute commands or
override ledger, permission, NBT, text-length, condition-depth, or item-count limits.

## Template references

Use `template` (or `$ref`) inside a reward or achievement condition object. Fields in the module
entry override fields from the common template:

```json
{
  "id": "daily_welcome",
  "template": "welcome_currency",
  "amount": 250
}
```

Unknown references, cycles, and more than four nested references reject the complete reload. Existing
module files without references remain valid and keep their original format versions.

## Authoring files

- `config.json` and module `config.json` are strict JSON and can be loaded directly.
- `config.jsonc` files under `docs/examples/config-platform/` are teaching copies; comments must be
  removed before copying them into `config/`.
- `docs/schemas/` contains JSON Schema starting points for editor completion. Runtime validation is
  still performed by the typed Java parsers.

## Module examples and schemas

`docs/examples/config-platform/` contains a teaching copy for the root file, all three common
files, and each module configuration. Copy the relevant JSON shape into the matching file under
`config/omnitools/`; a module example does not enable its module.

| Module | Teaching example | Schema |
| --- | --- | --- |
| Daily check-in | `daily-checkin.jsonc` | `daily-checkin.schema.json` |
| Online reward | `online-reward.jsonc` | `online-reward.schema.json` |
| Shop | `shop.jsonc` | `shop.schema.json` |
| Titles | `titles.jsonc` | `titles.schema.json` |
| Title effects | `title-effects.jsonc` | `title-effects.schema.json` |
| Achievements | `achievement.jsonc` | `achievements.schema.json` |
| CDK | `cdk.jsonc` | `cdk.schema.json` |
| Cloud storage | `cloud-storage.jsonc` | `cloud-storage.schema.json` |
| Permissions | `permissions.jsonc` | `permissions.schema.json` |
| Command menu registry | `command-menu.jsonc` | `command-menu.schema.json` |
| Sidebar | `sidebar.jsonc` | `sidebar.schema.json` |

The common schemas are `common-rewards.schema.json`, `common-conditions.schema.json`, and
`common-texts.schema.json`. Template references are accepted only by reward lists and achievement
conditions; they do not make command execution, permission bypass, or persistent-data rules
configurable.

The platform warns about unknown fields, while malformed values and unsafe references block reload.
Use a new `format_version` and a migration step for incompatible changes; do not silently reinterpret
an existing reward ID or ledger event.

## Reload scope

Use `/omnitools reload` after changing the root file or any file under `common/`; it reparses every
enabled module and publishes one validated snapshot. Use `/omnitools reload <module-id>` after
changing only that module's `config.json`. The partial form reparses the selected module, reuses the
active definitions of every other module, still runs complete cross-module validation, and publishes
nothing when validation fails. Valid module ids are the directory names listed above.
