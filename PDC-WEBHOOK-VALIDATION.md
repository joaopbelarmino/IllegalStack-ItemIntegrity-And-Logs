# PDC reads and webhook delivery

Scope: reduce repeated Item Integrity work and harden Discord delivery only.
No stack UUID module, SQL schema change, new periodic scanner, or detection
confidence/rule change is included. Existing configuration remains compatible.

## Runtime changes

- Read ItemStack PDC directly through Paper's read-only view, avoiding ItemMeta
  cloning. Identity writes use editPersistentDataContainer; shulker block-meta
  transfer remains in place. Registration fields and unrelated PDC are preserved.
- Reject excluded/non-trackable materials before checking exemption PDC.
  Migration reuses one PDC view for exemption and identity reads.
- Coalesce pending player scans into one next-tick task per player.
- Coalesce click/drag reconciliation per player and inventory until the next tick.
  Reuse the after snapshot for reconciliation and observation; keep separate
  slot observations even when their item IDs match.
- Batch delayed external-custody checks by player, observation tick and delay.
  Read the physical inventory once per batch. Later ticks retain their full
  configured delay; canonical ownership and physical absence checks remain.
- Pending inventory/player work is discarded on quit. No additional periodic
  task was introduced; the existing budgeted scanner cadence is unchanged.

## Discord

- Structured JSON escaping, Portuguese embeds and disabled mentions.
- One in-flight request; bounded queue of 25 waiting notifications.
- Connection timeout 10 seconds; request timeout 15 seconds.
- Success requires HTTP 2xx. Transport failures, 429 and 5xx receive at most
  three total attempts. Honor Discord retry/reset headers and retry_after.
- 401/403/404 suspend the affected endpoint until its URL changes or restart.
- Queue pressure favors confirmed cases over queued possible cases.
- Delivery-error summaries are limited to one per minute and omit URLs,
  tokens, response bodies and exception messages.
- Notifications are best-effort, not a durable outbox: queue overflow/restart
  can lose delivery, and a network timeout after acceptance can yield a repeated
  Discord message. Consult the existing case log/SQLite for the audit record.

## Validation

Run `gradlew test --no-daemon` with Java 21 and the project's Leaf 1.21.11 API.
The suite covers direct PDC reads/writes, exemption/filter behavior, all seven
registration fields on ItemStack-to-TileState transfer, scan/task coalescing,
separate observations for matching IDs in different slots, custody batching,
offhand presence, webhook escaping, bounded retries, 429, endpoint failure,
queue pressure and shutdown cancellation, plus existing database/holder tests.

No JAR packaging was requested. No real Discord endpoint was contacted by tests.
Warnings observed: existing ConflictDetector deprecated API usage, Mockito JVM
class-data-sharing notice, and Gradle's test-framework automatic-loading
deprecation for Gradle 9 (current wrapper remains 8.10.2).

## Before merging to stable main

Gameplay/load validation remains pending. Test in MONITOR on Leaf 1.21.11:

1. Inspect an equipment/shulker ID, move between slots/offhand/chest, place/break
   the shulker and restart; confirm the same original registration fields.
2. Rapid click/drag and shop-command transfers: confirm final canonical custody
   and that two actual copies in separate player slots still reach revalidation.
3. Trigger a test possible and confirmed case; verify the intended Discord
   channels, readable embeds and the corresponding local audit records.
4. Compare a profiler capture under similar player/hopper activity. Unit tests
   prove reduced duplicate work in covered paths, not a production TPS gain.
