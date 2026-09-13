# Inventory Audit beta

This module is part of IllegalStack, but its implementation and persistence are isolated under `audit/` and `item-audit.db`. It does not alter the Item Integrity canonical-presence rules.

## Data model

- Offline player inventory and Ender Chest are read from `<world>/playerdata/<uuid>.dat` on a dedicated worker. SQLite stores only a rebuildable item index, file hash and modification time.
- Persistent containers keep one current compressed Paper inventory snapshot, one material index and aggregated player interactions. Ordinary history is not stored.
- A logical double chest uses one deterministic identity based on both sorted block locations.
- Destroyed containers retain their last snapshot for the configured retention period and are read-only.
- Item aggregation includes shulker/container items and bundles up to depth 3 and 4,096 visited items.
- `zetra:serial` and existing Item Integrity IDs are indexed for exact lookup. Duplicate serials across distinct current targets are marked suspicious.

## Performance boundaries

- Bukkit inventories are copied and serialized only on the server thread.
- SQLite and playerdata I/O never run in event handlers.
- Hopper activity is coalesced for `debounce-ticks`; continuously active containers are captured after `max-dirty-ticks` at the latest.
- At most `snapshot-budget-per-cycle` dirty containers are captured every 10 ticks.
- Chunk-load indexing inspects only naturally loaded chunks and is capped at 128 holders per event. It never force-loads chunks.
- Database and dirty queues are bounded. Saturation rejects work fail-open and is exposed by `/stack audit status`.

## Commands

The existing `istack` command has the `stack` alias. Audit subcommands use the existing IllegalStack prefix.

- `/stack search <playerdata|playerdata_inv|playerdata_end|bau|container|all> <item|serial:id|custom:id> [page]`
- `/stack search suspeito [playerdata|bau|all]`
- `/stack view <inv|end> <nick|uuid>`
- `/stack view bau <uuid|here>`
- `/stack view bau <world> <x> <y> <z>`
- `/stack reindex playerdata [nick|uuid]`
- `/stack backup playerdata <nick|uuid>`
- `/stack audit <status|reload>`

## Administrative transfer

Online player inventories, Ender Chests and loaded active containers can transfer selected whole stacks into the administrator inventory after confirmation. Before applying the operation, the live source hash and every selected slot are revalidated. The operation aborts if the administrator lacks space or either side changed. The GUI never places source items on the cursor.

Offline playerdata and destroyed/unloaded containers are read-only in this beta. Offline mutation remains disabled until the full UUID lock, login exclusion, second read, temporary file, fsync and atomic replacement protocol is implemented and tested. This is an intentional integrity boundary, not a stubbed destructive path.

## Background work

- Dirty-container flush: every 10 ticks, bounded by configuration.
- Playerdata reconciliation: every 300 seconds by default, async, changed files only.
- Destroyed-container cleanup: queued once during module startup.

## Dependency

Offline NBT reading uses `io.github.canary-prism:querz-nbt:6.2.1` (Apache License 2.0), relocated inside the shaded JAR. It is used only on the playerdata worker; live inventories continue to use Paper APIs.

## Required gameplay validation

Before production use, validate chest and double-chest open/close, hopper bursts, shulker and bundle nesting, block and entity container destruction, restart persistence, online inventory transfer with exact/full space, and searches after a playerdata save. Keep administrative transfer permissions restricted to trusted operators.
