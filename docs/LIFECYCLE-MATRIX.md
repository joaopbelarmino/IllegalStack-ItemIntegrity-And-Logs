# Item Integrity - Lifecycle Matrix

This document is the internal source of truth for tracked item lifecycle coverage.
External anti-dupe projects are used only as reference for event coverage and edge
cases; this project keeps canonical presence, divergent observation, commitHandoff,
MONITOR/DELETE, writeAndConfirm, revalidation and FAIL_OPEN as the authority.

## Rules

- Legitimate movement must use `commitHandoff()`.
- Ambiguous observations must not overwrite canonical presence.
- Terminal state is only recorded from a strong event.
- DELETE is forbidden for virtual/external plugin holders.
- A virtual GUI observation is custody/diagnostic evidence, not proof of a
  second physical item. Physical holders still decide duplicate confirmation.
- No global chunk/container scans.
- No Bukkit API access from async tasks.

## Covered Paths

| Path | Event(s) | Action |
| --- | --- | --- |
| Player inventory heartbeat | Budgeted player scanner | Observe same owner; divergent goes to ConflictDetector |
| Player joins/quits | `PlayerJoinEvent`, `PlayerQuitEvent` | Immediate scan; quit commits `OFFLINE_COMMITTED` |
| Inventory click/drag/creative | `InventoryClickEvent`, `InventoryDragEvent`, `InventoryCreativeEvent` | Schedule player rescan |
| Physical inventory close | `InventoryCloseEvent` | Commit contained identities to physical container holder |
| Tracked item removed from physical container | Player/container observation + physical container revalidation | If old `PERSISTED_CONTAINER` no longer contains the ID, reconcile as `STALE_PRESENCE` and commit to the new real holder |
| Container presence cannot be revalidated | ConflictDetector main-thread validation | Mark ambiguous/monitor-only; never DELETE from stale container evidence alone |
| Virtual inventory custody | `InventoryClickEvent`, `InventoryDragEvent`, `InventoryCloseEvent` on non-physical GUI inventory | Normalize click/drag/close as one logical holder; commit `PLAYER -> VIRTUAL_INVENTORY` only after the previous player holder no longer physically contains the same ID. Otherwise retain a deduplicated diagnostic observation without opening a physical-dupe case |
| Virtual inventory release | Player inventory scanner | Commit `VIRTUAL_INVENTORY -> PLAYER` if the previous virtual custody exists and any online previous owner does not still physically contain the same ID |
| External plugin command storage | Budgeted player scanner + delayed revalidation | Commit `PLAYER -> EXTERNAL_PLUGIN` when a previously observed ID disappears from that player, canonical is still that player, and the player no longer physically contains the ID |
| External plugin release | Player inventory scanner | Commit `EXTERNAL_PLUGIN -> PLAYER` if the previous owner still does not physically contain the same ID |
| Hopper/container transfer | `InventoryMoveItemEvent` | Commit to destination physical container; virtual is observe-only |
| Player drop | `PlayerDropItemEvent` | Commit to item entity |
| Pickup | `EntityPickupItemEvent`, `PlayerAttemptPickupItemEvent` | Commit player handoff and schedule rescan |
| Player death drops | `PlayerDeathEvent` + `ItemSpawnEvent` | Short RAM pending spawn, then commit item entity |
| Item despawn | `ItemDespawnEvent` | Commit `TERMINAL_DESPAWNED` |
| Item merge | `ItemMergeEvent` | Commit `TERMINAL_MERGED` for disappearing entity |
| Item entity destroyed | `EntityDamageEvent` for `Item` | Recheck next tick, commit `TERMINAL_DESTROYED` if dead/invalid |
| Consumed item | `PlayerItemConsumeEvent` | Commit `TERMINAL_CONSUMED` |
| Broken tool/armor | `PlayerItemBreakEvent` | Commit `TERMINAL_BROKEN` |
| Smelted source item | `FurnaceSmeltEvent` | Commit `TERMINAL_CONSUMED` |
| Shulker item -> block | `BlockPlaceEvent` | Copy identity PDC to TileState, commit `SHULKER_BLOCK` |
| Shulker block -> drop | `BlockBreakEvent`, `BlockDropItemEvent` | Copy TileState identity to drop |
| Shulker TNT/block explosion | `EntityExplodeEvent`, `BlockExplodeEvent`, `ItemSpawnEvent` | Capture TileState before loss, copy identity to drop |
| Physical shulker GUI view | `InventoryClickEvent`, `InventoryCloseEvent` | If the GUI mirrors a known physical shulker that still contains the ID, ignore the virtual GUI observation as non-conflicting |
| Player-carried shulker GUI view | `InventoryClickEvent`, `InventoryCloseEvent` from shulker opener plugins | If the viewer has a shulker item whose embedded contents contain the same ID, treat the GUI as a temporary view of that carried shulker, not a second physical presence |
| Storage minecart destroyed | `VehicleDestroyEvent` + `ItemSpawnEvent` | Short RAM pending spawn, then commit item entity |
| Item frame | `PlayerInteractEntityEvent` | Next-tick read, commit `ITEM_FRAME` |
| Armor stand | `PlayerInteractEntityEvent` | Next-tick read equipment, commit `ARMOR_STAND` |
| Paper/Leaf 1.21.11 container overwrite | `BlockDestroyEvent`, `BlockMultiPlaceEvent` | Monitor-only snapshot/observation of container state |
| Offhand observation | Budgeted player scanner | `getContents()` slot 40 is the canonical offhand representation; explicit `getItemInOffHand()` is only used as fallback when slot 40 is absent |
| Initial duplicate anomaly | ConflictDetector targeted revalidation | First incompatible observation only schedules a short, item-specific physical revalidation; cases are emitted only as confirmed duplicate or ambiguous revalidation |
| Continuous ambiguous incident | ConflictDetector in-memory incident key | Emit once while the same logical state remains continuously observed; a new case is allowed only after the state disappears for the configured reset window |

## SQLite Retention

- `items` and `presence` are current state and are never removed by retention.
- Possible/ambiguous cases are written directly to `integrity_case_rollups`
  without an ItemStack BLOB per observation.
- Confirmed duplicate/removal cases keep the full snapshot during the detailed
  retention window, then become an aggregate rollup.
- `item_events` keeps meaningful transitions; repeated observations and
  repeated `commitHandoff()` to the same logical owner only update `presence`.
- Maintenance runs on the SQLite writer thread. It never calls Bukkit APIs and
  never performs SQL on the server thread.

## Monitor-Only / Future Integration

- Auction House, shops, crates, mail and custom menus are treated as virtual
  or external holders until explicit integration exists. DELETE stays forbidden
  for these holders.
- Nested shulker contents are snapshot read-only in integrity cases; they do not
  create recursive live presence yet.
- Piston shulker mechanics are not blocked here. If a duplicate physical
  presence appears, ConflictDetector owns the decision.
- Stackables remain out of scope.

## Regression Checks

Run these in MONITOR before considering DELETE safe:

| Check | Expected result |
| --- | --- |
| Shield in offhand, scanner running | No `player:slot40` vs generic `player` case |
| Totem in offhand, scanner running | No `player:slot40` vs generic `player` case |
| Move tracked item mainhand -> offhand | Canonical becomes `PLAYER slot40`, no duplicate case |
| Move tracked item offhand -> normal inventory slot | Canonical becomes the new slot, no duplicate case |
| Scanner and listener observe the same offhand item | One physical presence after revalidation, no case/webhook |
| Open a tracked shulker from hand using a shulker-opener plugin | Contents visible in the GUI do not create `virtual_inventory:shulker_box` duplicate alerts while the same IDs exist in the carried shulker item |
| Same `ZI-...` physically present in two different slots | Targeted revalidation finds both slots and emits `CONFIRMED_DUPLICATE` |
| Fast legitimate handoff `PLAYER -> DROP -> PLAYER` | First anomaly, then `TRANSIENT_CONFLICT_RECONCILED`, no case/webhook |
| `PLAYER A` and `PLAYER B` both still physically hold same `ZI-...` | Targeted revalidation emits `CONFIRMED_DUPLICATE` |
