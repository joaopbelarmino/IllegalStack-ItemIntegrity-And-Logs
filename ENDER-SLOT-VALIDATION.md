# Ender Chest, bounded destinations and slot events

This branch builds on the PDC/webhook change. Inventory audit/search/transfer,
playerdata backups and stack UUID tracking are explicitly NOT included.

## Behavior

- Ender Chest custody uses `EnderChestHolder(owner UUID, name, slot)`.
  It is distinct from the owner's ordinary inventory. Only the actual
  `Player.getEnderChest()` inventory is classified this way, not a plugin GUI
  that merely declares the same inventory type.
- Join, quit and budgeted reconciliation include the 27 Ender Chest slots.
  Opening/clicking/closing the actual Ender Chest reuses the existing delayed
  reconciliation. Offline owners cannot be physically revalidated: fail open.
- Ender owner and slot reuse the existing SQLite presence columns and survive
  restart. Existing data is not reset or migrated destructively. Old virtual
  Ender observations for the same owner can be reconciled when their real Ender
  Chest is observed.
- A pending revalidation records at most eight logical destinations for that
  item ID. Subsequent observations and commitHandoff calls supply hints. The
  second check reads the actual holders, not old RAM records. Unrelated items
  allocate no destination history. Repeated slots in one owner cause one read.
- Limits: 2,048 pending IDs, eight destinations each, 30-second expiry. Overflow
  or inaccessible holders is ambiguous, never grounds for DELETE. No global
  scan and no chunk load. Transfers through completely unobserved external
  storage cannot be inferred by this bounded model.
- The final destructive check now requires the original canonical physical
  location still to contain the ID AND the target stack to match its snapshot.
  Only a conflicting player/Ender slot can be removed. If the canonical moved,
  the case remains monitor-only rather than guessing which copy is canonical.
  SQLite first commits DELETE_PENDING and the immutable original snapshot.
  After the final check, only the pending action is updated to the actual outcome.
  A failed final write leaves DELETE_PENDING (not proof of removal) and emits an
  error; the case file and webhook still report the observed outcome. Historical
  REMOVED rows cannot be repaired without independent evidence.
- PlayerInventorySlotChangeEvent uses the converted slot and verifies it belongs
  to PlayerInventory. It observes the live slot, never migrates event clones.
  Mainhand/offhand/armor aliases still reconcile; two distinct physical slots
  continue to reach targeted revalidation. Ordinary stackable changes skip PDC.
  Destination-first notifications verify cached sibling slots physically before
  scheduling a conflict. Out-of-range raw slots from changed views are ignored.
  Pending-map expiry sweeps run at most once per TTL, without a new task;
  destination expiry walks only the expired insertion-order prefix.
  Player removal chat remains intentionally absent; administrators receive cases.
- Pickup/click/creative/armor/command full-player scans are no longer requested
  in paths covered by slot events. Existing specialized lifecycle handlers and
  the slower fallback scanner remain. This does not promise coverage for every
  inventory mutation made by third-party plugins.

## Configuration

New keys in `plugins/IllegalStack/item-integrity.yml` (restart after editing):

```yaml
scanner:
  slot-events: true
  reconciliation-period-ticks: 20
conflict-detector:
  revalidation:
    max-pending: 2048
    max-destinations: 8
```

Existing scanner player/item budgets remain. Minimum item budget is 68, one
atomic player + Ender reconciliation. With defaults (8 players, 512 slots), at
most seven players are reconciled each 20-tick cycle. No new periodic task;
the existing scanner runs less frequently when slot events are enabled.
Slot events can be disabled to restore full-player event scans and the existing
ItemScanTimer cadence. Disabling physical revalidation prevents confirmation
and destructive decisions, rather than making DELETE trust a first observation.

`/istack metrics` uses `illegalstack.itemintegrity.inspect` and reports cumulative
slot-event count/time/max, full scans/slots/time, revalidation time/count and
capacity rejections, and queued work since startup.
These counters are not a measurement of production TPS.

## Regression validation

Run `gradlew test --no-daemon` on Java 21. Tests cover:

- Shield/totem/elytra moves through hand, offhand and ordinary slots.
- Scanner + slot observation of the same offhand and real two-slot duplication.
- A lower-numbered duplicate slot cannot displace the existing physical canonical.
- Converted versus raw slots; event clones never written; cheap stackable path.
- Actual Ender Chest versus same-type plugin GUI.
- Player/Ender duplication, legitimate transfer, offline owner and SQLite restart.
- Third destination during a pending conflict, limits/expiry and owner aliases.
- Final deletion abort when canonical disappears or target changes.
- No target edit before async SQLite confirmation, and no edit on commit failure.
- Unavailable containers do not trigger a chunk load.

The existing database, webhook and inventory-work tests remain in the suite.
Compiler warnings are from existing legacy potion/enchantment/horse APIs (seven
removal warnings), plus existing unchecked/deprecation notices. Gradle also
warns about automatic test-framework loading before Gradle 9; Mockito emits
its JVM class-data-sharing notice. None is a new failed compilation.
No JAR packaging or production deployment is included in this change.
The user explicitly deferred server performance measurement to their own use
and will report high usage/TPS drops. Gameplay on a real Leaf server and real
Discord delivery have not been certified by unit tests; keep MONITOR initially.

## Manual acceptance before stable main

1. Move the same equipment ID INV -> Ender -> INV, open/close, then reconnect and
   restart. Check `/istack inspect` and `/istack lookup <id>` for stable identity
   and owner-specific ENDER_CHEST custody.
2. Copy a test ID into INV + Ender, two Ender slots and different online owners.
   Expect one confirmed MONITOR case for persistent incompatible physical copies.
3. While a conflict is pending, move one copy to another observed holder. Verify
   both copies are still detected; an unloaded/offline destination is ambiguous.
4. Test shield/totem swaps, right-click armor/elytra equip, shift-click, drag,
   creative and plugin inventory writes. Legitimate transfers must not confirm.
5. DELETE only in a test server: move/change the target or remove the canonical
   while persistence is pending. No deletion should occur. If both remain,
   only the conflicting player/Ender slot may be removed after SQLite commit.

Target is Leaf/Paper 1.21.11. Cross-region Folia physical revalidation fails open;
these changes do not claim new multi-region support.

API reference: https://jd.papermc.io/paper/1.21.11/io/papermc/paper/event/player/PlayerInventorySlotChangeEvent.html
