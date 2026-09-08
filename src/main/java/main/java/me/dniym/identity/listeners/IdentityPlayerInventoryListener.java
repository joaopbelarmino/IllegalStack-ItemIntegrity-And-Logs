package main.java.me.dniym.identity.listeners;

import main.java.me.dniym.IllegalStack;
import main.java.me.dniym.enums.Protections;
import main.java.me.dniym.identity.ItemIdentity;
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
    private final PresenceStore presenceStore;
    private final AuditQueue auditQueue;
    private final ConflictDetector conflictDetector;
    private final ItemIntegrityConfig config;
    private final VirtualCustodyService virtualCustodyService;
    private final Map<java.util.UUID, Map<String, ItemIdentity>> lastSeenByPlayer = new ConcurrentHashMap<>();
    private final AtomicInteger suppressedDivergentWarnings = new AtomicInteger();
    private int nextPlayerIndex;
    private long lastStatsLogMs;
    private volatile long lastDivergentWarningMs;

    public IdentityPlayerInventoryListener(Plugin plugin, MigrationService migrationService,
                                           PresenceStore presenceStore, AuditQueue auditQueue,
                                           ConflictDetector conflictDetector, ItemIntegrityConfig config,
                                           VirtualCustodyService virtualCustodyService) {
        this.plugin = plugin;
        this.migrationService = migrationService;
        this.presenceStore = presenceStore;
        this.auditQueue = auditQueue;
        this.conflictDetector = conflictDetector;
        this.config = config;
        this.virtualCustodyService = virtualCustodyService;
        plugin.getServer().getPluginManager().registerEvents(this, plugin);

        long period = Math.max(1, Protections.ItemScanTimer.getIntValue());
        Scheduler.runTaskTimer(plugin, this::periodicScan, period, period);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onJoin(PlayerJoinEvent event) {
        scheduleScan(event.getPlayer(), PresenceState.LIVE_CONFIRMED);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onQuit(PlayerQuitEvent event) {
        scanPlayer(event.getPlayer(), PresenceState.OFFLINE_COMMITTED);
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
        if (IllegalStack.isFoliaServer()) {
            player.getScheduler().run(plugin, task -> scanPlayer(player, state), null);
        } else {
            Bukkit.getScheduler().runTask(plugin, () -> scanPlayer(player, state));
        }
    }

    public void scanPlayer(Player player, PresenceState state) {
        if (player == null) {
            return;
        }
        PlayerInventory inv = player.getInventory();
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
    }

    private ItemIdentity observeSlot(Player player, int slot, ItemStack stack, PresenceState state,
                                     Map<String, PresenceRecord> seenThisScan) {
        MigrationResult migration = migrationService.ensureIdentity(stack, player.getLocation());
        if (migration == null) {
            return null;
        }
        ItemIdentity identity = migration.identity();

        if (migration.freshlyAssigned() && auditQueue != null) {
            ItemSnapshot snapshot = new ItemSnapshot(identity.id(), stack.getType().name(),
                    identity.registeredAtEpochMs(), identity.origin().name(),
                    identity.registeredWorldRaw(), identity.registeredX(), identity.registeredY(), identity.registeredZ(),
                    player.getUniqueId().toString(), identity.registeredAtEpochMs());
            auditQueue.offer(new AuditTask.RegisterItem(snapshot));
        }

        HolderRef holder = new HolderRef.PlayerHolder(player.getUniqueId(), player.getName(), slot == -1 ? null : slot);
        PresenceRecord candidate = new PresenceRecord(identity, holder, state, 1, System.currentTimeMillis());
        PresenceRecord scanDuplicate = seenThisScan.get(identity.id());
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
        String playerName = player.getName();
        Scheduler.runTaskLater(plugin, () -> {
            Player current = Bukkit.getPlayer(playerId);
            if (current == null || !current.isOnline()) {
                return;
            }
            virtualCustodyService.tryCommitMissingPlayerItemToExternal(identity, playerId, playerName);
        }, config.externalCustodyMissingConfirmDelayTicks(), player);
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
        int contents = player.getInventory().getContents().length;
        return contents > 40 ? contents : contents + 1;
    }

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
