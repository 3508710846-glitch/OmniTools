# Packages

The packages module stores server-owned virtual package instances. Definitions are loaded from config/omnitools/packages/config.json and snapshots are persisted when granted, so reloads do not alter pending packages.

Enable modules.packages.enabled in the root configuration. Package modes are all and random_one. Quantity is a business quantity and may exceed one vanilla stack; delivery splits stacks automatically. Invalid SNBT, duplicate ids, nested packages and configured limits are rejected during load.

Players use /omnitools package open or /omnitools packages. Administrators use /omnitools package give, inspect and remove. Package rewards create an instance through the idempotent reward ledger.
