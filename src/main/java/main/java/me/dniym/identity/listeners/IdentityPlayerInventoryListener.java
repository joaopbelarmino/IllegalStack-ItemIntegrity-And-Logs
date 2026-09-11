package main.java.me.dniym.identity.listeners;

import main.java.me.dniym.IllegalStack;
import main.java.me.dniym.enums.Protections;
import main.java.me.dniym.identity.ItemIdentity;
import main.java.me.dniym.identity.IdentityService;
import main.java.me.dniym.identity.TrackabilityPolicy;
import io.papermc.paper.event.player.PlayerInventorySlotChangeEvent;
import main.java.me.dniym.identity.MigrationResult;
import main.java.me.dniym.identity.MigrationService;
import main.java.me.dniym.identity.VirtualCustodyService;
import main.java.me.dniym.identity.audit.AuditQueue;
import main.java.me.dniym.identity.audit.AuditTask;
import main.java.me.dniym.identity.audit.ItemSnapshot;
import main.java.me.dniym.identity.config.ItemIntegrityConfig;
import main.java.me.dniym.identity.conflict.ConflictDetector;
import main.java.me.dniym.identity.presence.HolderRef;
import main.java.me.dniym.identity.presence.PresenceObservation;
import main.java.me.dniym.identity.presence.PresenceRecord;
import main.java.me.dniym.identity.presence.PresenceState;
import main.java.me.dniym.identity.presence.PresenceStore;
import main.java.me.dniym.utils.Scheduler;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

public final class IdentityPlayerInventoryListener implements Listener {

    private static final Logger LOGGER = LogManager.getLogger("IllegalStack/ItemIdentity");

    private final Plugin plugin;
    private final MigrationService migrationService;
    private final IdentityService identityService;
    private final boolean useSlotEvents;
    private final PresenceStore presenceStore;
    private final AuditQueue auditQueue;
    private final ConflictDetector conflictDetector;
    private final ItemIntegrityConfig config;
    private final VirtualCustodyService virtualCustodyService;
    private final Map<java.util.UUID, Map<String, ItemIdentity>> lastSeenByPlayer = new ConcurrentHashMap<>();
    private final Map<java.util.UUID, PendingPlayerScan> pendingScans = new ConcurrentHashMap<>();
    private final Map<ExternalCheckKey, Map<String, ItemIdentity>> pendingExternalChecks = new ConcurrentHashMap<>();
    private final AtomicInteger suppressedDivergentWarnings = new AtomicInteger();
    private final Map<java.util.UUID, Map<Integer, PresenceRecord>> slotCache = new ConcurrentHashMap<>();
    private long slotEvents, slotNanos, maxSlotNanos, scannedPlayers, scannedSlots, scanNanos;
    private int nextPlayerIndex;
    private long lastStatsLogMs;
    private volatile long lastDivergentWarningMs;

    public IdentityPlayerInventoryListener(Plugin plugin, MigrationService migrationService,
                                           PresenceStore presenceStore, AuditQueue auditQueue,
                                           ConflictDetector conflictDetector, ItemIntegrityConfig config,
                                           VirtualCustodyService virtualCustodyService, IdentityService identityService) {
        this.plugin = plugin;
        this.migrationService = migrationService;
        this.identityService = identityService;
        this.presenceStore = presenceStore;
        this.auditQueue = auditQueue;
        this.conflictDetector = conflictDetector;
        this.config = config;
        this.useSlotEvents = config.slotEventsEnabled() && !IllegalStack.isFoliaServer();
        this.virtualCustodyService = virtualCustodyService;
        plugin.getServer().getPluginManager().registerEvents(this, plugin);

        long period = slotEventsEnabled() ? config.reconciliationPeriodTicks() : Math.max(1, Protections.ItemScanTimer.getIntValue());
        Scheduler.runTaskTimer(plugin, this::periodicScan, period, period);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onJoin(PlayerJoinEvent event) {
        scheduleScan(event.getPlayer(), PresenceState.LIVE_CONFIRMED);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onQuit(PlayerQuitEvent event) {
        java.util.UUID id = event.getPlayer().getUniqueId();
        pendingScans.remove(id);
        pendingExternalChecks.keySet().removeIf(key -> key.playerId().equals(id));
        scanPlayer(event.getPlayer(), PresenceState.OFFLINE_COMMITTED);
        slotCache.remove(id);
    }

    public boolean slotEventsEnabled() { return useSlotEvents; }

    /** The event's converted slot belongs to PlayerInventory, not to a container's raw slots. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onSlotChange(PlayerInventorySlotChangeEvent event) {
        if (!slotEventsEnabled() || event.isAsynchronous()) return;
        long started = System.nanoTime();
        try {
            Player player = event.getPlayer();
            int slot = event.getSlot();
            var view = player.getOpenInventory();
            int rawSlot = event.getRawSlot();
            if (slot < 0 || slot >= player.getInventory().getSize()
                    || rawSlot < 0 || rawSlot >= view.countSlots()
                    || view.getInventory(rawSlot) != player.getInventory()) return;
            ItemStack previous = event.getOldItemStack();
            ItemStack live = player.getInventory().getItem(slot);
            if (!TrackabilityPolicy.isCandidate(previous) && !TrackabilityPolicy.isCandidate(live)) {
                Map<Integer, PresenceRecord> cached = slotCache.get(player.getUniqueId());
                PresenceRecord removed = cached == null ? null : cached.remove(slot);
                if (removed != null) scheduleExternalCustodyCheck(player, removed.identity());
                return;
            }
            ItemIdentity old = TrackabilityPolicy.isCandidate(previous) ? identityService.readIdentity(previous) : null;
            Map<Integer, PresenceRecord> cache = slotCache.computeIfAbsent(player.getUniqueId(), ignored -> new HashMap<>());
            cache.remove(slot);
            Map<String, PresenceRecord> siblings = new HashMap<>();
            cache.values().forEach(record -> siblings.putIfAbsent(record.identity().id(), record));
            // Never migrate the event's clones: inspect/write the current physical slot only.
            ItemIdentity current = observeSlot(player, slot, live,
                    PresenceState.LIVE_CONFIRMED, siblings);
            if (old != null && (current == null || !old.id().equals(current.id()))) scheduleExternalCustodyCheck(player, old);
        } finally {
            long elapsed = System.nanoTime() - started;
            slotEvents++; slotNanos += elapsed; maxSlotNanos = Math.max(maxSlotNanos, elapsed);
        }
    }

    public String metrics() {
        return "slot-events=" + slotEvents + " slot-total-ms=" + slotNanos / 1_000_000.0
                + " slot-max-ms=" + maxSlotNanos / 1_000_000.0 + " scanned-players=" + scannedPlayers
                + " scanned-slots=" + scannedSlots + " scan-total-ms=" + scanNanos / 1_000_000.0
                + " pending-player-scans=" + pendingScans.size() + " pending-custody=" + pendingExternalChecks.size();
    }

    private void periodicScan() {
        List<Player> players = new ArrayList<>(Bukkit.getOnlinePlayers());
        if (players.isEmpty()) {
            nextPlayerIndex = 0;
            return;
        }

        long started = System.nanoTime();
        int maxPlayers = config.scannerMaxPlayersPerCycle();
        int maxItems = config.scannerMaxItemsPerCycle();
        int playersProcessed = 0;
        int itemsProcessed = 0;
        int initialIndex = Math.floorMod(nextPlayerIndex, players.size());

        while (playersProcessed < maxPlayers && itemsProcessed < maxItems && playersProcessed < players.size()) {
            int index = Math.floorMod(initialIndex + playersProcessed, players.size());
            Player player = players.get(index);
            int cost = estimatedInventorySize(player);
            if (playersProcessed > 0 && itemsProcessed + cost > maxItems) break;
            scanPlayer(player, PresenceState.LIVE_CONFIRMED);
            playersProcessed++;
            itemsProcessed += estimatedInventorySize(player);
        }

        nextPlayerIndex = Math.floorMod(initialIndex + playersProcessed, players.size());
        maybeLogScannerStats(playersProcessed, itemsProcessed,
                Math.max(0, players.size() - playersProcessed), System.nanoTime() - started);
    }

    public void scheduleScan(Player player, PresenceState state) {
        if (player == null) {
            return;
        }
        java.util.UUID id = player.getUniqueId();
        PendingPlayerScan pending = new PendingPlayerScan(state);
        if (pendingScans.putIfAbsent(id, pending) != null) return;
        try {
            Scheduler.runTaskLater(plugin, () -> {
                if (pendingScans.remove(id, pending) && player.isOnline()) {
                    scanPlayer(player, pending.state());
                }
            }, 1, player);
        } catch (RuntimeException error) {
            pendingScans.remove(id, pending);
            throw error;
        }
    }

    public void scanPlayer(Player player, PresenceState state) {
        if (player == null) {
            return;
        }
        PlayerInventory inv = player.getInventory();
        long started = System.nanoTime();
        slotCache.computeIfAbsent(player.getUniqueId(), ignored -> new HashMap<>()).clear();
        Map<String, PresenceRecord> seenThisScan = new HashMap<>();
        Map<String, ItemIdentity> currentSeen = new HashMap<>();
        ItemStack[] contents = inv.getContents();
        for (int slot = 0; slot < contents.length; slot++) {
            ItemIdentity identity = observeSlot(player, slot, contents[slot], state, seenThisScan);
            if (identity != null) {
                currentSeen.put(identity.id(), identity);
            }
        }
        if (contents.length <= 40) {
            ItemIdentity offhand = observeSlot(player, 40, inv.getItemInOffHand(), state, seenThisScan);
            if (offhand != null) {
                currentSeen.put(offhand.id(), offhand);
            }
        }
        updateMissingItems(player, state, currentSeen);
        scanEnderChest(player, state == PresenceState.OFFLINE_COMMITTED ? state : PresenceState.PERSISTED_CONTAINER);
        scannedPlayers++; scannedSlots += contents.length + player.getEnderChest().getSize();
        scanNanos += System.nanoTime() - started;
    }

    public void scanEnderChest(Player player, PresenceState state) {
        scanEnderChest(player, state, player.getEnderChest().getContents());
    }

    public void scanEnderChest(Player player, PresenceState state, ItemStack[] contents) {
        Map<String, PresenceRecord> seen = new HashMap<>();
        for (int slot = 0; slot < contents.length; slot++) {
            ItemStack item = contents[slot];
            if (!TrackabilityPolicy.isCandidate(item)) continue;
            MigrationResult result = migrationService.ensureIdentity(item, player.getLocation());
            if (result == null) continue;
            ItemIdentity id = result.identity();
            registerFresh(result, item, player);
            HolderRef holder = new HolderRef.EnderChestHolder(player.getUniqueId(), player.getName(), slot);
            presenceStore.getCanonical(id).map(PresenceRecord::holder)
                    .filter(previous -> previous instanceof HolderRef.VirtualHolder virtual
                            && HolderRef.normalizeVirtualLabel(virtual.label()).equals("ender_chest")
                            && player.getUniqueId().toString().equals(virtual.viewerOrOwner()))
                    .ifPresent(previous -> presenceStore.commitHandoff(id, holder, state));
            PresenceRecord candidate = new PresenceRecord(id, holder, state, 1, System.currentTimeMillis());
            PresenceRecord retained = presenceStore.getCanonical(id).orElse(null);
            if (retained != null && !retained.state().isTerminal()
                    && retained.holder() instanceof HolderRef.EnderChestHolder previous
                    && previous.playerId().equals(player.getUniqueId()) && previous.slot() != null
                    && previous.slot() != slot && previous.slot() >= 0 && previous.slot() < contents.length
                    && hasIdentity(contents[previous.slot()], id)) {
                seen.put(id.id(), retained);
                if (conflictDetector != null) conflictDetector.handleStoredInventoryConflict(item, id, retained, candidate);
                continue;
            }
            PresenceRecord duplicate = seen.putIfAbsent(id.id(), candidate);
            if (duplicate != null && !duplicate.holder().describe().equals(holder.describe()) && conflictDetector != null) {
                conflictDetector.handleStoredInventoryConflict(item, id, duplicate, candidate);
                continue;
            }
            PresenceObservation observation = presenceStore.observe(id, holder, state);
            if (!observation.sameOwnerAsCanonical() && conflictDetector != null) {
                conflictDetector.handleStoredInventoryConflict(item, id, observation.canonicalBefore().orElseThrow(), candidate);
            }
        }
    }

    private ItemIdentity observeSlot(Player player, int slot, ItemStack stack, PresenceState state,
                                     Map<String, PresenceRecord> seenThisScan) {
        if (!TrackabilityPolicy.isCandidate(stack)) return null;
        MigrationResult migration = migrationService.ensureIdentity(stack, player.getLocation());
        if (migration == null) {
            return null;
        }
        ItemIdentity identity = migration.identity();

        registerFresh(migration, stack, player);

        HolderRef holder = new HolderRef.PlayerHolder(player.getUniqueId(), player.getName(), slot == -1 ? null : slot);
        PresenceRecord candidate = new PresenceRecord(identity, holder, state, 1, System.currentTimeMillis());
        slotCache.computeIfAbsent(player.getUniqueId(), ignored -> new HashMap<>()).put(slot, candidate);
        PresenceRecord retained = presenceStore.getCanonical(identity).orElse(null);
        if (retained != null && !retained.state().isTerminal()
                && retained.holder() instanceof HolderRef.PlayerHolder previous
                && previous.playerId().equals(player.getUniqueId()) && previous.slot() != null
                && previous.slot() != slot && previous.slot() >= 0 && previous.slot() < player.getInventory().getSize()
                && hasIdentity(player.getInventory().getItem(previous.slot()), identity)) {
            seenThisScan.put(identity.id(), retained);
            if (conflictDetector != null) conflictDetector.handlePlayerInventoryConflict(player, slot, stack, identity, retained, candidate);
            return identity;
        }
        PresenceRecord scanDuplicate = seenThisScan.get(identity.id());
        // Slot notifications may arrive destination-first; the cache is only a hint.
        if (scanDuplicate != null && scanDuplicate.holder() instanceof HolderRef.PlayerHolder cached
                && cached.playerId().equals(player.getUniqueId()) && cached.slot() != null
                && (cached.slot() < 0 || cached.slot() >= player.getInventory().getSize()
                    || !hasIdentity(player.getInventory().getItem(cached.slot()), identity))) {
            slotCache.get(player.getUniqueId()).remove(cached.slot());
            seenThisScan.remove(identity.id());
            scanDuplicate = null;
        }
        if (scanDuplicate != null && conflictDetector != null
                && !scanDuplicate.holder().describe().equals(holder.describe())) {
            conflictDetector.handlePlayerInventoryConflict(player, slot, stack, identity, scanDuplicate, candidate);
            return identity;
        }
        seenThisScan.put(identity.id(), candidate);

        if (virtualCustodyService.tryCommitVirtualToPlayer(identity, (HolderRef.PlayerHolder) holder)) {
            return identity;
        }

        PresenceObservation observation = presenceStore.observe(identity, holder, state);
        if (!observation.sameOwnerAsCanonical() && conflictDetector != null) {
            maybeLogDivergentObservation(identity, observation, holder);
            conflictDetector.handlePlayerInventoryObservation(player, slot, stack, observation);
        }
        return identity;
    }

    private void registerFresh(MigrationResult migration, ItemStack stack, Player player) {
        if (!migration.freshlyAssigned() || auditQueue == null) return;
        ItemIdentity identity = migration.identity();
        auditQueue.offer(new AuditTask.RegisterItem(new ItemSnapshot(identity.id(), stack.getType().name(),
                identity.registeredAtEpochMs(), identity.origin().name(), identity.registeredWorldRaw(),
                identity.registeredX(), identity.registeredY(), identity.registeredZ(), player.getUniqueId().toString(),
                identity.registeredAtEpochMs())));
    }

    private boolean hasIdentity(ItemStack stack, ItemIdentity expected) {
        ItemIdentity current = identityService.readIdentity(stack);
        return current != null && expected.id().equals(current.id());
    }

    private void updateMissingItems(Player player, PresenceState state, Map<String, ItemIdentity> currentSeen) {
        java.util.UUID playerId = player.getUniqueId();
        Map<String, ItemIdentity> previousSeen = lastSeenByPlayer.get(playerId);
        if (state == PresenceState.LIVE_CONFIRMED && previousSeen != null) {
            for (Map.Entry<String, ItemIdentity> entry : previousSeen.entrySet()) {
                if (!currentSeen.containsKey(entry.getKey())) {
                    scheduleExternalCustodyCheck(player, entry.getValue());
                }
            }
        }

        if (state == PresenceState.OFFLINE_COMMITTED) {
            lastSeenByPlayer.remove(playerId);
        } else {
            lastSeenByPlayer.put(playerId, currentSeen);
        }
    }

    public void scheduleExternalCustodyCheck(Player player, ItemIdentity identity) {
        java.util.UUID playerId = player.getUniqueId();
        long delay = config.externalCustodyMissingConfirmDelayTicks();
        // A batch is tick-scoped: later observations never get a shorter confirmation delay.
        ExternalCheckKey key = new ExternalCheckKey(playerId, Bukkit.getCurrentTick(), delay);
        Map<String, ItemIdentity> batch = pendingExternalChecks.get(key);
        if (batch != null) {
            batch.putIfAbsent(identity.id(), identity);
            return;
        }
        batch = new HashMap<>();
        batch.put(identity.id(), identity);
        pendingExternalChecks.put(key, batch);
        Map<String, ItemIdentity> scheduledBatch = batch;
        try {
            Scheduler.runTaskLater(plugin, () -> {
                if (!pendingExternalChecks.remove(key, scheduledBatch) || !player.isOnline()) return;
                virtualCustodyService.tryCommitMissingPlayerItemsToExternal(scheduledBatch.values(), player);
            }, delay, player);
        } catch (RuntimeException error) {
            pendingExternalChecks.remove(key, scheduledBatch);
            throw error;
        }
    }

    private void maybeLogDivergentObservation(ItemIdentity identity, PresenceObservation observation, HolderRef holder) {
        long interval = config.divergentWarningIntervalMs();
        if (interval <= 0) {
            return;
        }
        long now = System.currentTimeMillis();
        int suppressed = suppressedDivergentWarnings.incrementAndGet();
        if (now - lastDivergentWarningMs < Math.max(60_000L, interval)) {
            return;
        }
        lastDivergentWarningMs = now;
        suppressedDivergentWarnings.set(0);
        String canonical = observation.canonicalBefore().map(r -> r.holder().describe()).orElse("nenhuma");
        LOGGER.warn("[ItemIdentity] {} observacao(oes) divergente(s) recentes. Ultima item={} canonical={} nova_observacao={}. Detalhes em item-integrity-possible-cases.log/SQLite",
                suppressed, identity.id(), canonical, holder.describe());
    }

    private int estimatedInventorySize(Player player) {
        if (player == null) {
            return 0;
        }
        int contents = player.getInventory().getSize();
        return (contents > 40 ? contents : contents + 1) + player.getEnderChest().getSize();
    }

    private record PendingPlayerScan(PresenceState state) {}
    private record ExternalCheckKey(java.util.UUID playerId, int tick, long delay) {}

    private void maybeLogScannerStats(int playersProcessed, int itemsProcessed, int backlog, long elapsedNanos) {
        long interval = config.scannerStatsLogIntervalMs();
        if (interval <= 0) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastStatsLogMs < interval) {
            return;
        }
        lastStatsLogMs = now;
        LOGGER.info("[ItemIntegrity] Scanner budget: players={} items~={} backlog={} elapsed={}ms",
                playersProcessed, itemsProcessed, backlog, elapsedNanos / 1_000_000L);
    }
}
