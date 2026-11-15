# Inventory Audit beta

**Current safety status: administrative transfers are blocked. See the follow-up correction below.**

This module is part of IllegalStack, but its implementation and persistence are isolated under `audit/` and `item-audit.db`. It does not alter the Item Integrity canonical-presence rules.

## Data model

- Offline player inventory and Ender Chest are read from `<world>/playerdata/<uuid>.dat` on a dedicated worker. SQLite stores only a rebuildable item index, file hash and modification time.
- Persistent containers keep one current compressed Paper inventory snapshot, one material index and aggregated player interactions. Ordinary history is not stored.
- A double chest uses a route based on both sorted block locations. SQLite retains separate generations when a destroyed location is reused and retires overlapping active single/double records on observation.
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

The previous transfer implementation is retained for recovery-protocol work, but is not executable in this build. It was designed to transfer selected whole stacks after confirmation. Before applying the operation, the live source hash and every selected slot are revalidated. The operation aborts if the administrator lacks space or either side changed. The GUI never places source items on the cursor.

Offline playerdata and destroyed/unloaded containers are read-only in this beta. Offline mutation remains disabled until the full UUID lock, login exclusion, second read, temporary file, fsync and atomic replacement protocol is implemented and tested. This is an intentional integrity boundary, not a stubbed destructive path.

## Background work

- Dirty-container flush: every tick, at most one capture per tick and eight per ten ticks by default, with a soft time budget. A single serialization cannot be interrupted; unload/destruction captures are immediate.
- Playerdata reconciliation: every 300 seconds by default, async, changed files only.
- Destroyed-container and completed transfer-evidence cleanup: startup and hourly.

## Dependency

Offline NBT reading uses `io.github.canary-prism:querz-nbt:6.2.1` (Apache License 2.0), relocated inside the shaded JAR. It is used only on the playerdata worker; live inventories continue to use Paper APIs.

## Required gameplay validation

## Audit review corrections

- Existing ZI identities are serials with occurrence counts, including duplicates within one inventory. Historical indexed duplicates remain suspicions, not proof of simultaneous physical copies.
- Player-owned Ender/menu inventories are excluded from the container resolver. Playerdata search totals and global pagination are calculated in SQL before pagination.
- Offline snapshots use GZIP with input size, depth and allocation bounds. Hash and parser consume the same file bytes. Temporary playerdata filenames are excluded.
- Inspection does not attribute staff as the last interacting player. Pending unload captures retain per-player interaction counts.
- Transfer remains intentional, as requested by the owner. Full source and destination evidence is committed before mutation. Both live inventories are revalidated and a destination layout is applied without ignoring addItem leftovers. No offline editing is enabled.
- Parsing, hashing, compression and database work use workers. Serialization of live Bukkit items remains on the server thread. Reader queries use a separate bounded executor; dirty work and queued serialized bytes are bounded.
- Existing Item Integrity cases feed audit flags without introducing a second physical duplicate detector.

Remaining limitations: gameplay validation is mandatory; a crash between live mutation and the final audit update requires manual evidence review, not automatic replay. This change does not implement a full illegal-enchantment policy, arbitrary custom-item validation, or offline editing. Cross-chunk double-chest unload and extreme snapshot workloads still need server regression coverage. Indexed observations can be stale and must never authorize automatic deletion.

Regression checklist: same ZI twice in one inventory; Ender without duplicate container rows; simple-to-double-to-simple chest; destruction and replacement at identical coordinates; two players interacting before unload; offline enchanted item rendering; globally paginated searches; admin inspection without interaction changes; full admin inventory and source changes during transfer persistence.

Before production use, validate chest and double-chest open/close, hopper bursts, shulker and bundle nesting, block and entity container destruction, restart persistence, online inventory transfer with exact/full space, and searches after a playerdata save. Keep administrative transfer permissions restricted to trusted operators.
## Follow-up safety correction

Administrative transfers are TEMPORARILY BLOCKED in this build, even if an older
YAML enables removal. Queries, snapshots and manual backups remain available.
This is containment of the crash window, NOT a completed durable delivery protocol.
No recovery replay, item deletion or delivery takes place automatically.

- Structural NBT limits produce a bounded read-only projection and an
  `audit:nbt_limit` index marker. Deep subtrees do not discard healthy sibling
  items. Container evidence retains the original serialized bytes, never the
  projection. Playerdata files are never modified. Truly unreadable roots produce
  an explicit read-failure suspicion; previous indexed rows are not current proof.
- Repeated serials are scored only within one target's snapshot, including
  INV+ENDER captured together. Different targets remain searchable by serial,
  but historical coincidence alone no longer earns duplication points.
  Item Integrity remains responsible for physical multi-holder confirmation.
- Player slot/close events coalesce online indexing for 30 seconds. One due player
  is serialized per tick at most. Startup scanning, live parsing and interactive
  read/backup requests use separate bounded worker queues. No Bukkit runs there.
- Discovery has a separate low-priority bounded queue. Recently captured locations
  skip repeated discovery for five minutes (bounded cache, no global world scan).
  Player/hopper events do not share its capacity. Discovery can be delayed or
  dropped under sustained activity; actual interactivity is preferred.
- Dirty entries are indexed by chunk, including both sides of a double chest.
  Captures re-resolve live inventories. Topology revisions concern the referenced
  position(s), not unrelated placements anywhere on the server.
- Entity inventory wrappers use logical equality, not Java reference equality.
- Startup validates a real Paper-serialized stone through the independent parser.
  An unsupported format disables this audit module explicitly.
- `audit.evidence.retention-days` now controls audit evidence independently from
  backup file retention.

New periodic work: a lightweight online dirty-player pump every tick, limited to
one due inventory/Ender capture. Existing container pump and offline reconciliation
remain. Serialization has a per-operation cost that cannot be interrupted by a
time budget. Performance has not been measured on a running Leaf server.

Required tests on Leaf: online item-to-chest handoff without a scored historical
duplicate; real same-snapshot repeated ID; minecart/chest boat open/close; flying
through loaded containers while another player interacts; cross-chunk double
chest unload; malformed/deep item with normal siblings. Transfers must remain
blocked until a separate crash-recovery protocol is implemented and validated.
