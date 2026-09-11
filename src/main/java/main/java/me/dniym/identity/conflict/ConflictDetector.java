package main.java.me.dniym.identity.conflict;

import main.java.me.dniym.IllegalStack;
import main.java.me.dniym.identity.IdentityService;
import main.java.me.dniym.identity.ItemIdentity;
import main.java.me.dniym.identity.audit.AuditTask;
import main.java.me.dniym.identity.audit.DatabaseService;
import main.java.me.dniym.identity.audit.IntegrityCaseSnapshot;
import main.java.me.dniym.identity.config.ItemIntegrityConfig;
import main.java.me.dniym.identity.presence.HolderRef;
import main.java.me.dniym.identity.presence.HolderType;
import main.java.me.dniym.identity.presence.PresenceObservation;
import main.java.me.dniym.identity.presence.PresenceRecord;
import main.java.me.dniym.identity.presence.PresenceState;
import main.java.me.dniym.identity.presence.PresenceStore;
import main.java.me.dniym.identity.presence.DestinationWindow;
import main.java.me.dniym.identity.presence.DestinationTrackingStore;
import main.java.me.dniym.utils.Scheduler;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.World;
import org.bukkit.block.BlockState;
import org.bukkit.block.Container;
import org.bukkit.block.ShulkerBox;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.ItemFrame;
import org.bukkit.entity.Player;
import org.bukkit.entity.minecart.StorageMinecart;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.bukkit.util.io.BukkitObjectOutputStream;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public final class ConflictDetector {

    private static final Logger LOGGER = LogManager.getLogger("IllegalStack/ItemIntegrity");
    private static final String DUPLICATE_REASON = "SAME_ITEM_ID_MULTIPLE_INCOMPATIBLE_PRESENCES";
    private static final String RESURRECTED_REASON = "RESURRECTED_ITEM";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final IllegalStack plugin;
    private final ItemIntegrityConfig config;
    private final IdentityService identityService;
    private final DatabaseService databaseService;
    private final DiscordWebhookNotifier webhookNotifier;
    private final CaseFileLogger caseFileLogger;
    private final PresenceStore presenceStore;
    private final DestinationWindow destinations;
    private final Map<String, IncidentMemory> activeIncidents = new ConcurrentHashMap<>();
    private final Map<String, Long> pendingRevalidations = new ConcurrentHashMap<>();
    private final Map<ChunkKey, Map<String, PendingContainerConflict>> pendingContainerConflicts =
            new ConcurrentHashMap<>();
    private final ConcurrentLinkedQueue<PendingContainerLocator> pendingContainerOrder = new ConcurrentLinkedQueue<>();
    private final AtomicInteger pendingContainerCount = new AtomicInteger();
    private final AtomicInteger suppressedPossibleConsoleCases = new AtomicInteger();
    private volatile long lastPossibleConsoleCaseMs;
    private volatile long lastIncidentCleanupMs;
    private long revalidationCount, revalidationNanos, revalidationMaxNanos, revalidationSaturated;

    public String metrics() {
        return "pending-revalidations=" + pendingRevalidations.size() + " revalidation-count=" + revalidationCount
                + " revalidation-total-ms=" + revalidationNanos / 1_000_000.0
                + " revalidation-max-ms=" + revalidationMaxNanos / 1_000_000.0
                + " revalidation-capacity-rejections=" + revalidationSaturated;
    }

    public ConflictDetector(IllegalStack plugin, ItemIntegrityConfig config, IdentityService identityService,
                            DatabaseService databaseService, DiscordWebhookNotifier webhookNotifier,
                            CaseFileLogger caseFileLogger, PresenceStore presenceStore) {
        this.plugin = plugin;
        this.config = config;
        this.identityService = identityService;
        this.databaseService = databaseService;
        this.webhookNotifier = webhookNotifier;
        this.caseFileLogger = caseFileLogger;
        this.presenceStore = presenceStore;
        this.destinations = presenceStore instanceof DestinationTrackingStore tracked ? tracked.destinations()
                : new DestinationWindow(2048, 8, 30_000, System::currentTimeMillis);
    }

    public void handlePlayerInventoryObservation(Player player, int slot, ItemStack stack,
                                                 PresenceObservation observation) {
        if (observation.sameOwnerAsCanonical() || observation.canonicalBefore().isEmpty()) {
            return;
        }

        PresenceRecord canonical = observation.canonicalBefore().get();
        PresenceRecord conflicting = observation.candidate();
        if (canonical.state().isTerminal() && !conflicting.state().isTerminal()) {
            handleMonitorOnlyIncident(stack, observation.identity(), canonical, conflicting,
                    "RESURRECTED_ITEM", RESURRECTED_REASON);
            return;
        }

        handlePlayerInventoryConflict(player, slot, stack, observation.identity(), canonical, conflicting);
    }

    public void handlePlayerInventoryConflict(Player player, int slot, ItemStack stack, ItemIdentity identity,
                                              PresenceRecord canonical, PresenceRecord conflicting) {
        handleConflict(player, slot, stack, identity, canonical, conflicting,
                "DUPLICATE_DETECTED", DUPLICATE_REASON, true);
    }

    public void handleMonitorOnlyIncident(ItemStack stack, ItemIdentity identity, PresenceRecord canonical,
                                          PresenceRecord conflicting, String decision, String reason) {
        handleConflict(null, -1, stack, identity, canonical, conflicting, decision, reason, false);
    }

    public void handleStoredInventoryConflict(ItemStack stack, ItemIdentity identity, PresenceRecord canonical,
                                              PresenceRecord conflicting) {
        if (canonical.state().isTerminal() && !conflicting.state().isTerminal()) {
            handleMonitorOnlyIncident(stack, identity, canonical, conflicting, "RESURRECTED_ITEM", RESURRECTED_REASON);
            return;
        }
        handleConflict(null, -1, stack, identity, canonical, conflicting,
                "ANOMALY_DETECTED", DUPLICATE_REASON, true);
    }

    private void handleConflict(Player player, int slot, ItemStack stack, ItemIdentity identity,
                                PresenceRecord canonical, PresenceRecord conflicting, String decision,
                                String reason, boolean allowDelete) {
        if (reconcilePlayerSlotAlias(identity, canonical, conflicting)) {
            return;
        }
        ConflictConfidence confidence = confidence(canonical, conflicting, reason);
        if (confidence != ConflictConfidence.CRITICAL) {
            return;
        }
        if (withinGrace(canonical, conflicting) && !config.conflictRevalidationEnabled()) {
            return;
        }
        ContainerConflictDisposition containerDisposition = reconcileContainerPresence(identity, canonical, conflicting);
        if (containerDisposition == ContainerConflictDisposition.STALE_RECONCILED) {
            return;
        }
        if (containerDisposition == ContainerConflictDisposition.AMBIGUOUS) {
            registerPendingContainerConflict(identity, canonical, conflicting, stack);
            allowDelete = false;
            decision = "AMBIGUOUS_CONTAINER_PRESENCE";
            reason = "CONTAINER_PRESENCE_UNAVAILABLE";
            confidence = ConflictConfidence.LOW;
        }

        if (DUPLICATE_REASON.equals(reason) && config.conflictRevalidationEnabled()) {
            String activeKey = incidentKey(identity.id(), canonical.holder(), conflicting.holder(), reason,
                    config.conflictMode());
            if (touchActiveIncident(activeKey, System.currentTimeMillis())) {
                return;
            }
            scheduleTargetedRevalidation(player, slot, stack, identity, canonical, conflicting, decision, reason,
                    allowDelete, confidence);
            return;
        }

        if (DUPLICATE_REASON.equals(reason)) {
            allowDelete = false;
            decision = "AMBIGUOUS_REVALIDATION";
            confidence = ConflictConfidence.LOW;
        }

        finalizeConflict(player, slot, stack, identity, canonical, conflicting, decision, reason, allowDelete,
                confidence, RevalidationSummary.notRun());
    }

    private void finalizeConflict(Player player, int slot, ItemStack stack, ItemIdentity identity,
                                  PresenceRecord canonical, PresenceRecord conflicting, String decision,
                                  String reason, boolean allowDelete, ConflictConfidence confidence,
                                  RevalidationSummary revalidation) {
        boolean virtual = isVirtual(canonical.holder()) || isVirtual(conflicting.holder());
        ConflictMode configuredMode = config.conflictMode();
        ConflictAction intendedAction = configuredMode == ConflictMode.DELETE && allowDelete && !virtual
                ? ConflictAction.REMOVED
                : ConflictAction.WOULD_REMOVE;
        if (configuredMode == ConflictMode.DELETE && virtual) {
            intendedAction = ConflictAction.DELETE_ABORTED_VIRTUAL_HOLDER;
        }
        if (!allowDelete) {
            intendedAction = ConflictAction.MONITOR_ONLY;
        }
        boolean confirmedDuplicate = "CONFIRMED_DUPLICATE".equals(decision) && !virtual;
        boolean deleteAllowed = allowDelete;

        String key = incidentKey(identity.id(), canonical.holder(), conflicting.holder(), reason, configuredMode);
        long now = System.currentTimeMillis();
        if (!shouldEmitIncident(key, now, confirmedDuplicate)) {
            return;
        }

        IntegrityCaseSnapshot snapshot = createSnapshot(configuredMode, decision, intendedAction, confidence, reason,
                identity, canonical, conflicting, stack, revalidation, key,
                confirmedDuplicate || (configuredMode == ConflictMode.DELETE && deleteAllowed && !virtual));
        boolean confirmedCase = isConfirmedDuplicateCase(snapshot);
        if (confirmedCase) {
            LOGGER.warn("[ItemIntegrity] Case {} {} item={} canonical={} conflicting={} action={}",
                    snapshot.caseId(), reason, identity.id(), canonical.holder().describe(),
                    conflicting.holder().describe(), intendedAction);
        } else {
            maybeWarnPossibleCaseSummary(snapshot);
        }
        caseFileLogger.append(snapshot.caseId(), formatDetailedLog(snapshot, canonical, conflicting), confirmedCase);

        if (configuredMode == ConflictMode.DELETE && allowDelete && !virtual && snapshot.conflictingItemSnapshot() == null) {
            persistAndNotify(withAction(snapshot, ConflictAction.DELETE_ABORTED_PERSISTENCE_FAILED), canonical, conflicting);
            return;
        }

        if (databaseService == null) {
            LOGGER.warn("[ItemIntegrity] Conflito detectado para {}, mas SQLite indisponivel - FAIL_OPEN.", identity.id());
            sendWebhook(withAction(snapshot, ConflictAction.DELETE_ABORTED_PERSISTENCE_FAILED),
                    canonical, conflicting, confirmedDuplicate);
            return;
        }

        databaseService.writeAndConfirm(new AuditTask.PersistCase(snapshot)).thenAccept(persisted -> {
            if (!persisted) {
                ConflictAction action = configuredMode == ConflictMode.DELETE
                        ? ConflictAction.DELETE_ABORTED_PERSISTENCE_FAILED
                        : ConflictAction.MONITOR_ABORTED_PERSISTENCE_FAILED;
                sendWebhook(withAction(snapshot, action), canonical, conflicting, confirmedDuplicate);
                return;
            }
            if (configuredMode != ConflictMode.DELETE || !deleteAllowed || virtual) {
                sendWebhook(snapshot, canonical, conflicting, confirmedDuplicate);
                return;
            }
            Runnable remove = () -> {
                ConflictAction action;
                try {
                    action = removeIfStillConflicting(identity, canonical, conflicting, stack)
                            ? ConflictAction.REMOVED : ConflictAction.DELETE_ABORTED_REVALIDATION_FAILED;
                } catch (RuntimeException error) {
                    action = ConflictAction.DELETE_ABORTED_REVALIDATION_FAILED;
                }
                IntegrityCaseSnapshot completed = withAction(snapshot, action);
                caseFileLogger.append(snapshot.caseId(), formatDetailedLog(completed, canonical, conflicting), confirmedDuplicate);
                databaseService.writeAndConfirm(new AuditTask.PersistCase(completed))
                        .thenRun(() -> sendWebhook(completed, canonical, conflicting, confirmedDuplicate));
            };
            if (player != null) runOnPlayerThread(player, remove);
            else Scheduler.runTaskLater(plugin, remove, 1);
        });
    }

    private void persistAndNotify(IntegrityCaseSnapshot snapshot, PresenceRecord canonical, PresenceRecord conflicting) {
        if (databaseService != null) {
            databaseService.writeAndConfirm(new AuditTask.PersistCase(snapshot))
                    .thenRun(() -> sendWebhook(snapshot, canonical, conflicting, false));
        } else {
            sendWebhook(snapshot, canonical, conflicting, false);
        }
    }

    private void sendWebhook(IntegrityCaseSnapshot snapshot, PresenceRecord canonical, PresenceRecord conflicting,
                             boolean confirmedDuplicate) {
        String message = formatWebhook(snapshot, canonical, conflicting);
        if (confirmedDuplicate) {
            webhookNotifier.sendConfirmed(message);
        } else {
            webhookNotifier.sendPossible(message);
        }
    }

    private boolean isConfirmedDuplicateCase(IntegrityCaseSnapshot snapshot) {
        return "CONFIRMED_DUPLICATE".equals(snapshot.decision())
                || ConflictAction.REMOVED.name().equals(snapshot.action());
    }

    private void maybeWarnPossibleCaseSummary(IntegrityCaseSnapshot snapshot) {
        int suppressed = suppressedPossibleConsoleCases.incrementAndGet();
        long now = System.currentTimeMillis();
        if (now - lastPossibleConsoleCaseMs < 60_000L) {
            return;
        }
        lastPossibleConsoleCaseMs = now;
        suppressedPossibleConsoleCases.set(0);
        LOGGER.warn("[ItemIntegrity] {} caso(s) possiveis/ambiguos recentes. Ultimo={} decisao={} motivo={}. Detalhes em plugins/IllegalStack/item-integrity-possible-cases.log",
                suppressed, snapshot.caseId(), snapshot.decision(), snapshot.reason());
    }

    private boolean reconcilePlayerSlotAlias(ItemIdentity identity, PresenceRecord canonical, PresenceRecord conflicting) {
        if (!(canonical.holder() instanceof HolderRef.PlayerHolder canonicalPlayer)
                || !(conflicting.holder() instanceof HolderRef.PlayerHolder conflictingPlayer)
                || !canonicalPlayer.playerId().equals(conflictingPlayer.playerId())) {
            return false;
        }

        Integer canonicalSlot = canonicalPlayer.slot();
        Integer conflictingSlot = conflictingPlayer.slot();
        if (Objects.equals(canonicalSlot, conflictingSlot)) {
            presenceStore.commitHandoff(identity, moreSpecificPlayerRecord(canonical, conflicting).holder(),
                    moreSpecificPlayerRecord(canonical, conflicting).state());
            return true;
        }
        if (canonicalSlot == null || conflictingSlot == null) {
            PresenceRecord specific = canonicalSlot != null ? canonical : conflicting;
            presenceStore.commitHandoff(identity, specific.holder(), specific.state());
            return true;
        }
        return false;
    }

    private PresenceRecord moreSpecificPlayerRecord(PresenceRecord a, PresenceRecord b) {
        HolderRef.PlayerHolder pa = (HolderRef.PlayerHolder) a.holder();
        HolderRef.PlayerHolder pb = (HolderRef.PlayerHolder) b.holder();
        if (pa.slot() != null || pb.slot() == null) {
            return a;
        }
        return b;
    }

    private void scheduleTargetedRevalidation(Player player, int slot, ItemStack stack, ItemIdentity identity,
                                              PresenceRecord canonical, PresenceRecord conflicting, String decision,
                                              String reason, boolean allowDelete, ConflictConfidence confidence) {
        String key = identity.id();
        long now = System.currentTimeMillis();
        pendingRevalidations.entrySet().removeIf(entry -> now - entry.getValue() > 30_000);
        if (!pendingRevalidations.containsKey(key) && pendingRevalidations.size() >= config.revalidationPendingLimit()) {
            revalidationSaturated++;
            return;
        }
        Long previous = pendingRevalidations.putIfAbsent(key, now);
        if (previous != null) {
            destinations.observe(identity.id(), canonical.holder());
            destinations.observe(identity.id(), conflicting.holder());
            return;
        }
        if (!destinations.begin(identity.id(), canonical.holder(), conflicting.holder())) {
            pendingRevalidations.remove(key);
            return;
        }
        ItemStack stackSnapshot = stack != null ? stack.clone() : null;

        Runnable task = () -> {
            if (!pendingRevalidations.remove(key, now)) return;
            long started = System.nanoTime();
            TargetedRevalidation result = revalidateCurrentPhysicalState(identity, canonical, conflicting, now);
            long elapsed = System.nanoTime() - started;
            revalidationCount++; revalidationNanos += elapsed; revalidationMaxNanos = Math.max(revalidationMaxNanos, elapsed);
            if (result.status() == RevalidationStatus.TRANSIENT_RECONCILED) {
                result.singlePresence().ifPresent(found ->
                        presenceStore.commitHandoff(identity, found.holder(), found.state()));
                return;
            }

            PresenceRecord finalCanonical = result.canonical().orElse(canonical);
            PresenceRecord finalConflicting = result.conflicting().orElse(conflicting);
            boolean finalAllowDelete = allowDelete && result.status() == RevalidationStatus.CONFIRMED_DUPLICATE;
            // If the original canonical instance moved, do not guess which remaining copy to destroy.
            finalAllowDelete &= finalCanonical.holder().describe().equals(canonical.holder().describe());
            ItemStack verified = physicalItem(finalConflicting.holder());
            if (verified == null) finalAllowDelete = false;
            ItemStack finalStack = verified != null ? verified.clone() : stackSnapshot;
            String finalDecision = switch (result.status()) {
                case CONFIRMED_DUPLICATE -> "CONFIRMED_DUPLICATE";
                case AMBIGUOUS_REVALIDATION -> "AMBIGUOUS_REVALIDATION";
                case TRANSIENT_RECONCILED -> "TRANSIENT_CONFLICT_RECONCILED";
            };
            String finalReason = result.status() == RevalidationStatus.CONFIRMED_DUPLICATE
                    ? reason
                    : result.status().name();
            ConflictConfidence finalConfidence = result.status() == RevalidationStatus.CONFIRMED_DUPLICATE
                    ? confidence
                    : ConflictConfidence.LOW;
            finalizeConflict(player, slot, finalStack, identity, finalCanonical, finalConflicting, finalDecision,
                    finalReason, finalAllowDelete, finalConfidence, result.summary());
        };

        long delay = config.conflictRevalidationDelayTicks();
        try {
            if (player != null && player.isOnline()) {
                Scheduler.runTaskLater(plugin, task, delay, player);
            } else {
                Scheduler.runTaskLater(plugin, task, delay);
            }
        } catch (RuntimeException error) {
            pendingRevalidations.remove(key, now);
            destinations.finish(identity.id());
        }
    }

    TargetedRevalidation revalidateCurrentPhysicalState(ItemIdentity identity, PresenceRecord canonical,
                                                                PresenceRecord conflicting, long firstObservedAtMs) {
        presenceStore.getCanonical(identity).ifPresent(p -> destinations.observe(identity.id(), p.holder()));
        presenceStore.getLastDivergentObservation(identity).ifPresent(p -> destinations.observe(identity.id(), p.holder()));
        DestinationWindow.Result hints = destinations.finish(identity.id());
        Map<String, List<PhysicalPresence>> reads = new java.util.LinkedHashMap<>();
        List<PhysicalPresence> canonicalFound = reads.computeIfAbsent(HolderRef.logicalOwnerKey(canonical.holder()),
                ignored -> findPhysicalPresences(identity, canonical.holder()));
        List<PhysicalPresence> conflictingFound = new ArrayList<>(reads.computeIfAbsent(HolderRef.logicalOwnerKey(conflicting.holder()),
                ignored -> findPhysicalPresences(identity, conflicting.holder())));
        for (HolderRef destination : hints.holders()) {
            if (!reads.containsKey(HolderRef.logicalOwnerKey(destination))) {
                List<PhysicalPresence> extra = findPhysicalPresences(identity, destination);
                reads.put(HolderRef.logicalOwnerKey(destination), extra);
                conflictingFound.addAll(extra);
            }
        }
        if (hints.incomplete()) conflictingFound.add(PhysicalPresence.unavailable(conflicting.holder(), "limite/expiracao de destinos"));
        boolean unavailable = canonicalFound.stream().anyMatch(PhysicalPresence::unavailable)
                || conflictingFound.stream().anyMatch(PhysicalPresence::unavailable);

        List<PhysicalPresence> found = new ArrayList<>();
        found.addAll(canonicalFound.stream().filter(p -> !p.unavailable()).toList());
        found.addAll(conflictingFound.stream().filter(p -> !p.unavailable()).toList());
        List<PhysicalPresence> unique = uniquePhysicalPresences(found);
        long elapsedMs = Math.max(0, System.currentTimeMillis() - firstObservedAtMs);
        RevalidationSummary summary = new RevalidationSummary(elapsedMs, canonical.holder().describe(),
                conflicting.holder().describe(), canonicalFound, conflictingFound);

        if (unavailable) {
            return new TargetedRevalidation(RevalidationStatus.AMBIGUOUS_REVALIDATION, summary,
                    firstAvailableRecord(canonicalFound).or(() -> java.util.Optional.of(canonical)),
                    firstAvailableRecord(conflictingFound).or(() -> java.util.Optional.of(conflicting)),
                    java.util.Optional.empty());
        }
        if (unique.size() >= 2) {
            PresenceRecord finalCanonical = chooseCanonicalAfterRevalidation(canonical, unique);
            PresenceRecord finalConflicting = unique.stream()
                    .map(PhysicalPresence::record)
                    .filter(record -> !record.holder().describe().equals(finalCanonical.holder().describe()))
                    .findFirst()
                    .orElse(conflicting);
            return new TargetedRevalidation(RevalidationStatus.CONFIRMED_DUPLICATE, summary,
                    java.util.Optional.of(finalCanonical), java.util.Optional.of(finalConflicting), java.util.Optional.empty());
        }
        return new TargetedRevalidation(RevalidationStatus.TRANSIENT_RECONCILED, summary,
                firstRecord(unique), java.util.Optional.empty(), firstRecord(unique));
    }

    private List<PhysicalPresence> uniquePhysicalPresences(List<PhysicalPresence> found) {
        List<PhysicalPresence> unique = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (PhysicalPresence presence : found) {
            String key = presence.record().holder().describe();
            if (seen.add(key)) {
                unique.add(presence);
            }
        }
        return unique;
    }

    private java.util.Optional<PresenceRecord> firstRecord(List<PhysicalPresence> found) {
        return found.isEmpty() ? java.util.Optional.empty() : java.util.Optional.of(found.get(0).record());
    }

    private java.util.Optional<PresenceRecord> firstAvailableRecord(List<PhysicalPresence> found) {
        return found.stream().filter(presence -> !presence.unavailable())
                .map(PhysicalPresence::record).findFirst();
    }

    private PresenceRecord chooseCanonicalAfterRevalidation(PresenceRecord canonical, List<PhysicalPresence> found) {
        for (PhysicalPresence presence : found) {
            if (presence.record().holder().describe().equals(canonical.holder().describe())) {
                return presence.record();
            }
        }
        return found.get(0).record();
    }

    private List<PhysicalPresence> findPhysicalPresences(ItemIdentity identity, HolderRef holder) {
        try {
            return findPhysicalPresencesUnsafe(identity, holder);
        } catch (RuntimeException error) {
            return List.of(PhysicalPresence.unavailable(holder, "falha ao consultar estado fisico"));
        }
    }

    private List<PhysicalPresence> findPhysicalPresencesUnsafe(ItemIdentity identity, HolderRef holder) {
        if (IllegalStack.isFoliaServer()) return List.of(PhysicalPresence.unavailable(holder, "revalidacao multi-regiao nao suportada"));
        if (holder instanceof HolderRef.EnderChestHolder ender) {
            Player owner = Bukkit.getPlayer(ender.playerId());
            if (owner == null || !owner.isOnline()) return List.of(PhysicalPresence.unavailable(holder, "dono do Ender Chest offline"));
            List<PhysicalPresence> found = new ArrayList<>();
            ItemStack[] items = owner.getEnderChest().getContents();
            for (int slot = 0; slot < items.length; slot++) if (sameIdentity(items[slot], identity)) {
                found.add(PhysicalPresence.found(new PresenceRecord(identity,
                        new HolderRef.EnderChestHolder(owner.getUniqueId(), owner.getName(), slot),
                        PresenceState.PERSISTED_CONTAINER, 1, System.currentTimeMillis())));
            }
            return found;
        }
        if (holder instanceof HolderRef.PlayerHolder playerHolder) {
            return findInPlayerInventory(identity, playerHolder);
        }
        if (holder instanceof HolderRef.ItemEntityHolder itemHolder) {
            return findItemEntity(identity, itemHolder);
        }
        if (holder instanceof HolderRef.ContainerHolder containerHolder) {
            return findInContainer(identity, containerHolder);
        }
        if (holder instanceof HolderRef.EntityHolder entityHolder) {
            return findEntityHolder(identity, entityHolder);
        }
        return List.of(PhysicalPresence.unavailable(holder, "holder sem revalidacao fisica direta"));
    }

    private List<PhysicalPresence> findInPlayerInventory(ItemIdentity identity, HolderRef.PlayerHolder holder) {
        Player player = Bukkit.getPlayer(holder.playerId());
        if (player == null || !player.isOnline()) {
            return List.of(PhysicalPresence.unavailable(holder, "player offline"));
        }
        List<PhysicalPresence> found = new ArrayList<>();
        PlayerInventory inventory = player.getInventory();
        ItemStack[] contents = inventory.getContents();
        for (int slot = 0; slot < contents.length; slot++) {
            if (sameIdentity(contents[slot], identity)) {
                HolderRef.PlayerHolder precise = new HolderRef.PlayerHolder(player.getUniqueId(), player.getName(), slot);
                found.add(PhysicalPresence.found(new PresenceRecord(identity, precise,
                        PresenceState.LIVE_CONFIRMED, 1, System.currentTimeMillis())));
            }
        }
        if (contents.length <= 40 && sameIdentity(inventory.getItemInOffHand(), identity)) {
            HolderRef.PlayerHolder precise = new HolderRef.PlayerHolder(player.getUniqueId(), player.getName(), 40);
            found.add(PhysicalPresence.found(new PresenceRecord(identity, precise,
                    PresenceState.LIVE_CONFIRMED, 1, System.currentTimeMillis())));
        }
        return found;
    }

    private List<PhysicalPresence> findItemEntity(ItemIdentity identity, HolderRef.ItemEntityHolder holder) {
        World world = holder.world() != null ? Bukkit.getWorld(holder.world()) : null;
        if (world == null || holder.x() == null || holder.z() == null
                || !world.isChunkLoaded(holder.x() >> 4, holder.z() >> 4)) {
            return List.of(PhysicalPresence.unavailable(holder, "item entity/chunk indisponivel"));
        }
        Entity entity = Bukkit.getEntity(holder.entityUuid());
        if (!(entity instanceof Item item) || !item.isValid() || item.isDead()) {
            return List.of();
        }
        if (!sameIdentity(item.getItemStack(), identity)) {
            return List.of();
        }
        HolderRef.ItemEntityHolder precise = new HolderRef.ItemEntityHolder(item.getUniqueId(), item.getEntityId(),
                item.getWorld().getName(), item.getLocation().getBlockX(), item.getLocation().getBlockY(),
                item.getLocation().getBlockZ());
        return List.of(PhysicalPresence.found(new PresenceRecord(identity, precise,
                PresenceState.LIVE_CONFIRMED, 1, System.currentTimeMillis())));
    }

    private List<PhysicalPresence> findInContainer(ItemIdentity identity, HolderRef.ContainerHolder holder) {
        World world = Bukkit.getWorld(holder.world());
        if (world == null || !world.isChunkLoaded(holder.x() >> 4, holder.z() >> 4)) {
            return List.of(PhysicalPresence.unavailable(holder, "container/chunk indisponivel"));
        }
        BlockState state = world.getBlockAt(holder.x(), holder.y(), holder.z()).getState();
        if (!(state instanceof Container container)) {
            return List.of();
        }
        List<PhysicalPresence> found = new ArrayList<>();
        ItemStack[] contents = container.getInventory().getContents();
        for (int slot = 0; slot < contents.length; slot++) {
            if (sameIdentity(contents[slot], identity)) {
                HolderRef.ContainerHolder precise = new HolderRef.ContainerHolder(holder.type(), holder.world(),
                        holder.x(), holder.y(), holder.z(), slot);
                found.add(PhysicalPresence.found(new PresenceRecord(identity, precise,
                        PresenceState.PERSISTED_CONTAINER, 1, System.currentTimeMillis())));
            }
        }
        return found;
    }

    private List<PhysicalPresence> findEntityHolder(ItemIdentity identity, HolderRef.EntityHolder holder) {
        World world = holder.world() != null ? Bukkit.getWorld(holder.world()) : null;
        if (world == null || holder.x() == null || holder.z() == null
                || !world.isChunkLoaded(holder.x() >> 4, holder.z() >> 4)) {
            return List.of(PhysicalPresence.unavailable(holder, "entidade/chunk indisponivel"));
        }

        Entity entity = Bukkit.getEntity(holder.entityUuid());
        if (entity == null || !entity.isValid() || entity.isDead()) {
            return List.of();
        }
        if (holder.type() == HolderType.ARMOR_STAND && entity instanceof ArmorStand stand) {
            return findOnArmorStand(identity, stand);
        }
        if (holder.type() == HolderType.ITEM_FRAME && entity instanceof ItemFrame frame) {
            return entityItemPresence(identity, frame, frame.getItem(), null);
        }
        if (holder.type() == HolderType.STORAGE_MINECART && entity instanceof StorageMinecart minecart) {
            return findInEntityInventory(identity, minecart, minecart.getInventory());
        }
        return List.of();
    }

    private List<PhysicalPresence> findOnArmorStand(ItemIdentity identity, ArmorStand stand) {
        EntityEquipment equipment = stand.getEquipment();
        if (equipment == null) {
            return List.of();
        }
        List<PhysicalPresence> found = new ArrayList<>();
        found.addAll(entityItemPresence(identity, stand, equipment.getItemInMainHand(), "mainhand"));
        found.addAll(entityItemPresence(identity, stand, equipment.getItemInOffHand(), "offhand"));
        ItemStack[] armor = equipment.getArmorContents();
        for (int slot = 0; slot < armor.length; slot++) {
            found.addAll(entityItemPresence(identity, stand, armor[slot], "armor" + slot));
        }
        return found;
    }

    private List<PhysicalPresence> findInEntityInventory(ItemIdentity identity, Entity entity, Inventory inventory) {
        List<PhysicalPresence> found = new ArrayList<>();
        ItemStack[] contents = inventory.getContents();
        for (int slot = 0; slot < contents.length; slot++) {
            found.addAll(entityItemPresence(identity, entity, contents[slot], "slot" + slot));
        }
        return found;
    }

    private List<PhysicalPresence> entityItemPresence(ItemIdentity identity, Entity entity, ItemStack stack,
                                                       String detail) {
        if (!sameIdentity(stack, identity)) {
            return List.of();
        }
        HolderType type = entity instanceof ArmorStand ? HolderType.ARMOR_STAND
                : entity instanceof ItemFrame ? HolderType.ITEM_FRAME : HolderType.STORAGE_MINECART;
        org.bukkit.Location location = entity.getLocation();
        HolderRef.EntityHolder precise = new HolderRef.EntityHolder(type, entity.getUniqueId(),
                entity.getWorld().getName(), location.getBlockX(), location.getBlockY(), location.getBlockZ(), detail);
        return List.of(PhysicalPresence.found(new PresenceRecord(identity, precise,
                PresenceState.LIVE_CONFIRMED, 1, System.currentTimeMillis())));
    }

    private ConflictConfidence confidence(PresenceRecord canonical, PresenceRecord conflicting, String reason) {
        if (RESURRECTED_REASON.equals(reason)) {
            return ConflictConfidence.CRITICAL;
        }
        if (!isStrong(canonical.state()) || !isStrong(conflicting.state())) {
            return ConflictConfidence.LOW;
        }
        return ConflictConfidence.CRITICAL;
    }

    private boolean isStrong(PresenceState state) {
        return state == PresenceState.LIVE_CONFIRMED
                || state == PresenceState.OFFLINE_COMMITTED
                || state == PresenceState.PERSISTED_BLOCK
                || state == PresenceState.PERSISTED_CONTAINER;
    }

    private boolean withinGrace(PresenceRecord canonical, PresenceRecord conflicting) {
        String transition = transitionKey(canonical.holder().type(), conflicting.holder().type());
        if (transition == null) {
            return false;
        }
        long graceMs = config.handoffGraceMs(transition);
        return graceMs > 0 && System.currentTimeMillis() - canonical.lastConfirmedAtMs() <= graceMs;
    }

    private String transitionKey(HolderType from, HolderType to) {
        if (from == HolderType.PLAYER && to == HolderType.ITEM_ENTITY) return "PLAYER_TO_ITEM_ENTITY";
        if (from == HolderType.ITEM_ENTITY && to == HolderType.PLAYER) return "ITEM_ENTITY_TO_PLAYER";
        if (from == HolderType.PLAYER && isContainer(to)) return "PLAYER_TO_CONTAINER";
        if (isContainer(from) && to == HolderType.PLAYER) return "CONTAINER_TO_PLAYER";
        if (isContainer(from) && isContainer(to)) return "CONTAINER_TO_CONTAINER";
        if (from == HolderType.SHULKER_BLOCK && to == HolderType.ITEM_ENTITY) return "SHULKER_BLOCK_TO_ITEM_ENTITY";
        return null;
    }

    private boolean isContainer(HolderType type) {
        return type == HolderType.CHEST || type == HolderType.BARREL || type == HolderType.ENDER_CHEST
                || type == HolderType.CONTAINER_OTHER || type == HolderType.SHULKER_BLOCK;
    }

    private boolean isVirtual(HolderRef holder) {
        return holder.type() == HolderType.VIRTUAL_INVENTORY || holder.type() == HolderType.EXTERNAL_PLUGIN;
    }

    private ContainerConflictDisposition reconcileContainerPresence(ItemIdentity identity, PresenceRecord canonical,
                                                                    PresenceRecord conflicting) {
        if (canonical.state() != PresenceState.PERSISTED_CONTAINER
                || !(canonical.holder() instanceof HolderRef.ContainerHolder container)
                || !(conflicting.holder() instanceof HolderRef.PlayerHolder)) {
            return ContainerConflictDisposition.NOT_CONTAINER;
        }

        ContainerPhysicalState physicalState = validateContainerContains(identity.id(), container);
        if (physicalState == ContainerPhysicalState.CONTAINS_ITEM) {
            return ContainerConflictDisposition.CONFIRMED_STILL_PRESENT;
        }
        if (physicalState == ContainerPhysicalState.MISSING_ITEM_OR_CONTAINER_GONE) {
            presenceStore.commitHandoff(identity, conflicting.holder(), conflicting.state());
            LOGGER.info("[ItemIntegrity] STALE_PRESENCE reconciliada: item={} container_antigo={} novo_holder={}",
                    identity.id(), container.describe(), conflicting.holder().describe());
            return ContainerConflictDisposition.STALE_RECONCILED;
        }

        return ContainerConflictDisposition.AMBIGUOUS;
    }

    private ContainerPhysicalState validateContainerContains(String itemId, HolderRef.ContainerHolder holder) {
        World world = Bukkit.getWorld(holder.world());
        if (world == null) {
            return ContainerPhysicalState.UNAVAILABLE;
        }
        if (!world.isChunkLoaded(holder.x() >> 4, holder.z() >> 4)) {
            return ContainerPhysicalState.UNAVAILABLE;
        }

        BlockState state = world.getBlockAt(holder.x(), holder.y(), holder.z()).getState();
        if (!(state instanceof Container container)) {
            return ContainerPhysicalState.MISSING_ITEM_OR_CONTAINER_GONE;
        }
        for (ItemStack item : container.getInventory().getContents()) {
            ItemIdentity current = identityService.readIdentity(item);
            if (current != null && current.id().equals(itemId)) {
                return ContainerPhysicalState.CONTAINS_ITEM;
            }
        }
        return ContainerPhysicalState.MISSING_ITEM_OR_CONTAINER_GONE;
    }

    private void registerPendingContainerConflict(ItemIdentity identity, PresenceRecord canonical,
                                                  PresenceRecord conflicting, ItemStack stack) {
        if (!(canonical.holder() instanceof HolderRef.ContainerHolder container)
                || !(conflicting.holder() instanceof HolderRef.PlayerHolder playerHolder)) {
            return;
        }
        long now = System.currentTimeMillis();
        cleanupExpiredPendingContainers(now, 16);
        if (pendingContainerCount.get() >= config.pendingContainerCapacity()) {
            return;
        }

        ChunkKey chunk = new ChunkKey(container.world(), container.x() >> 4, container.z() >> 4);
        String key = identity.id() + '|' + HolderRef.logicalOwnerKey(container) + '|'
                + HolderRef.logicalOwnerKey(playerHolder);
        long expiresAt = now + config.pendingContainerTtlMs();
        PendingContainerConflict pending = new PendingContainerConflict(identity, container, playerHolder,
                stack != null ? stack.clone() : null, expiresAt);
        Map<String, PendingContainerConflict> bucket = pendingContainerConflicts.computeIfAbsent(chunk,
                ignored -> new ConcurrentHashMap<>());
        if (bucket.putIfAbsent(key, pending) == null) {
            pendingContainerCount.incrementAndGet();
            pendingContainerOrder.offer(new PendingContainerLocator(chunk, key, expiresAt));
        }
    }

    /** Reconciles only entries for a chunk that the server loaded naturally. */
    public void onChunkLoaded(World world, int chunkX, int chunkZ) {
        if (world == null) {
            return;
        }
        ChunkKey chunkKey = new ChunkKey(world.getName(), chunkX, chunkZ);
        Map<String, PendingContainerConflict> bucket = pendingContainerConflicts.remove(chunkKey);
        if (bucket == null || bucket.isEmpty()) {
            return;
        }
        pendingContainerCount.addAndGet(-bucket.size());
        List<PendingContainerConflict> pending = new ArrayList<>(bucket.values());
        org.bukkit.Location anchor = new org.bukkit.Location(world, chunkX << 4, world.getMinHeight(), chunkZ << 4);
        processPendingContainerBatch(anchor, pending, 0);
    }

    private void processPendingContainerBatch(org.bukkit.Location anchor, List<PendingContainerConflict> pending,
                                              int start) {
        long now = System.currentTimeMillis();
        int end = Math.min(pending.size(), start + config.pendingContainerBatchPerTick());
        for (int index = start; index < end; index++) {
            PendingContainerConflict conflict = pending.get(index);
            if (conflict.expiresAtMs() <= now) {
                continue;
            }
            ContainerPhysicalState state = validateContainerContains(conflict.identity().id(), conflict.container());
            if (state == ContainerPhysicalState.MISSING_ITEM_OR_CONTAINER_GONE) {
                reconcilePendingContainerToPlayer(conflict);
            } else if (state == ContainerPhysicalState.UNAVAILABLE) {
                registerPendingContainerConflict(conflict);
            }
            // CONTAINS_ITEM remains canonical. A current player observation will
            // pass through normal two-stage physical revalidation.
        }
        if (end < pending.size()) {
            Scheduler.runTaskLater(plugin, () -> processPendingContainerBatch(anchor, pending, end), 1, anchor);
        }
    }

    private void reconcilePendingContainerToPlayer(PendingContainerConflict pending) {
        Player player = Bukkit.getPlayer(pending.player().playerId());
        if (player == null || !player.isOnline()) {
            return;
        }
        runOnPlayerThread(player, () -> {
            List<PhysicalPresence> found = findInPlayerInventory(pending.identity(), pending.player());
            if (found.size() == 1) {
                PresenceRecord current = found.get(0).record();
                presenceStore.commitHandoff(pending.identity(), current.holder(), current.state());
                return;
            }
            if (found.size() >= 2) {
                PresenceRecord first = found.get(0).record();
                PresenceRecord second = found.get(1).record();
                int slot = ((HolderRef.PlayerHolder) second.holder()).slot();
                handlePlayerInventoryConflict(player, slot, pending.stack(), pending.identity(), first, second);
            }
        });
    }

    private void registerPendingContainerConflict(PendingContainerConflict pending) {
        if (pending.expiresAtMs() <= System.currentTimeMillis()
                || pendingContainerCount.get() >= config.pendingContainerCapacity()) {
            return;
        }
        ChunkKey chunk = new ChunkKey(pending.container().world(), pending.container().x() >> 4,
                pending.container().z() >> 4);
        String key = pending.identity().id() + '|' + HolderRef.logicalOwnerKey(pending.container()) + '|'
                + HolderRef.logicalOwnerKey(pending.player());
        Map<String, PendingContainerConflict> bucket = pendingContainerConflicts.computeIfAbsent(chunk,
                ignored -> new ConcurrentHashMap<>());
        if (bucket.putIfAbsent(key, pending) == null) {
            pendingContainerCount.incrementAndGet();
            pendingContainerOrder.offer(new PendingContainerLocator(chunk, key, pending.expiresAtMs()));
        }
    }

    private void cleanupExpiredPendingContainers(long now, int budget) {
        for (int cleaned = 0; cleaned < budget; cleaned++) {
            PendingContainerLocator locator = pendingContainerOrder.peek();
            if (locator == null || locator.expiresAtMs() > now) {
                return;
            }
            pendingContainerOrder.poll();
            Map<String, PendingContainerConflict> bucket = pendingContainerConflicts.get(locator.chunk());
            if (bucket == null) {
                continue;
            }
            PendingContainerConflict current = bucket.get(locator.key());
            if (current != null && current.expiresAtMs() <= now && bucket.remove(locator.key(), current)) {
                pendingContainerCount.decrementAndGet();
            }
            if (bucket.isEmpty()) {
                pendingContainerConflicts.remove(locator.chunk(), bucket);
            }
        }
    }

    private IntegrityCaseSnapshot createSnapshot(ConflictMode mode, String decision, ConflictAction action,
                                                 ConflictConfidence confidence, String reason, ItemIdentity identity,
                                                 PresenceRecord canonical, PresenceRecord conflicting, ItemStack stack,
                                                 RevalidationSummary revalidation, String incidentKey,
                                                 boolean captureFullItem) {
        String material = stack != null ? stack.getType().name() : "UNKNOWN";
        String itemSummary = summarizeItem(stack) + "\n\n" + revalidation.describe();
        return new IntegrityCaseSnapshot(caseId(), incidentKey, System.currentTimeMillis(), mode.name(), decision,
                action.name(), confidence.name(), reason, identity.id(), material,
                summarize(canonical), summarize(conflicting), itemSummary,
                captureFullItem ? serializeItem(stack) : null);
    }

    private IntegrityCaseSnapshot withAction(IntegrityCaseSnapshot source, ConflictAction action) {
        return new IntegrityCaseSnapshot(source.caseId(), source.incidentKey(), source.createdAtEpochMs(),
                source.mode(), source.decision(),
                action.name(), source.confidence(), source.reason(), source.itemId(), source.material(),
                source.canonicalSummary(), source.conflictingSummary(), source.conflictingItemSummary(),
                source.conflictingItemSnapshot());
    }

    private String incidentKey(String itemId, HolderRef canonical, HolderRef conflicting, String reason,
                               ConflictMode mode) {
        boolean sameLogicalOwner = HolderRef.sameOwner(canonical, conflicting);
        return itemId + '|' + incidentHolderKey(canonical, sameLogicalOwner) + '|'
                + incidentHolderKey(conflicting, sameLogicalOwner) + '|' + reason + '|' + mode.name();
    }

    private String incidentHolderKey(HolderRef holder, boolean includeSlot) {
        String key = HolderRef.logicalOwnerKey(holder);
        if (!includeSlot) {
            return key;
        }
        if (holder instanceof HolderRef.PlayerHolder player && player.slot() != null) {
            return key + ":slot" + player.slot();
        }
        if (holder instanceof HolderRef.EnderChestHolder ender && ender.slot() != null) return key + ":slot" + ender.slot();
        if (holder instanceof HolderRef.ContainerHolder container && container.slot() != null) {
            return key + ":slot" + container.slot();
        }
        if (holder instanceof HolderRef.VirtualHolder virtual && virtual.slot() != null) {
            return key + ":slot" + virtual.slot();
        }
        return key;
    }

    private boolean shouldEmitIncident(String key, long now, boolean confirmedDuplicate) {
        long resetMs = confirmedDuplicate ? config.sameIncidentResetMs() : config.possibleRepeatLogMs();
        AtomicBoolean emit = new AtomicBoolean();
        activeIncidents.compute(key, (ignored, previous) -> {
            emit.set(previous == null || now - previous.lastObservedAtMs() > resetMs);
            return new IncidentMemory(now);
        });
        cleanupIncidentMemory(now, Math.max(resetMs, config.possibleRepeatLogMs()));
        return emit.get();
    }

    private boolean touchActiveIncident(String key, long now) {
        long resetMs = config.sameIncidentResetMs();
        AtomicBoolean active = new AtomicBoolean();
        activeIncidents.computeIfPresent(key, (ignored, previous) -> {
            if (now - previous.lastObservedAtMs() <= resetMs) {
                active.set(true);
                return new IncidentMemory(now);
            }
            return null;
        });
        return active.get();
    }

    private void cleanupIncidentMemory(long now, long resetMs) {
        if (now - lastIncidentCleanupMs < 300_000L) {
            return;
        }
        lastIncidentCleanupMs = now;
        long staleBefore = now - Math.max(3_600_000L, resetMs * 4L);
        activeIncidents.entrySet().removeIf(entry -> entry.getValue().lastObservedAtMs() < staleBefore);
    }

    boolean removeIfStillConflicting(ItemIdentity identity, PresenceRecord canonical,
                                             PresenceRecord conflicting, ItemStack expected) {
        if (IllegalStack.isFoliaServer() || expected == null) return false;
        if (!(conflicting.holder() instanceof HolderRef.PlayerHolder)
                && !(conflicting.holder() instanceof HolderRef.EnderChestHolder)) return false;
        if (canonical.holder().describe().equals(conflicting.holder().describe())) return false;
        List<PhysicalPresence> official = findPhysicalPresences(identity, canonical.holder());
        if (official.stream().anyMatch(PhysicalPresence::unavailable)
                || official.stream().noneMatch(p -> p.record().holder().describe().equals(canonical.holder().describe()))) return false;
        Inventory target = physicalInventory(conflicting.holder());
        Integer slot = holderSlot(conflicting.holder());
        if (target == null || slot == null || slot < 0 || slot >= target.getSize()) return false;
        ItemStack current = target.getItem(slot);
        if (!sameIdentity(current, identity) || !expected.equals(current)) return false;
        target.setItem(slot, null);
        return true;
    }

    private Integer holderSlot(HolderRef holder) {
        if (holder instanceof HolderRef.PlayerHolder p) return p.slot();
        if (holder instanceof HolderRef.EnderChestHolder p) return p.slot();
        if (holder instanceof HolderRef.ContainerHolder c) return c.slot();
        return null;
    }

    private Inventory physicalInventory(HolderRef holder) {
        if (IllegalStack.isFoliaServer()) return null;
        if (holder instanceof HolderRef.PlayerHolder p) {
            Player owner = Bukkit.getPlayer(p.playerId());
            return owner != null && owner.isOnline() ? owner.getInventory() : null;
        }
        if (holder instanceof HolderRef.EnderChestHolder p) {
            Player owner = Bukkit.getPlayer(p.playerId());
            return owner != null && owner.isOnline() ? owner.getEnderChest() : null;
        }
        if (holder instanceof HolderRef.ContainerHolder c) {
            World world = Bukkit.getWorld(c.world());
            if (world != null && world.isChunkLoaded(c.x() >> 4, c.z() >> 4)
                    && world.getBlockAt(c.x(), c.y(), c.z()).getState() instanceof Container container) return container.getInventory();
        }
        return null;
    }

    private ItemStack physicalItem(HolderRef holder) {
        Inventory inventory = physicalInventory(holder);
        Integer slot = holderSlot(holder);
        if (inventory != null && slot != null && slot >= 0 && slot < inventory.getSize()) return inventory.getItem(slot);
        if (holder instanceof HolderRef.ItemEntityHolder e && !IllegalStack.isFoliaServer()
                && Bukkit.getEntity(e.entityUuid()) instanceof Item item && item.isValid()) return item.getItemStack();
        return null;
    }

    private boolean sameIdentity(ItemStack stack, ItemIdentity identity) {
        ItemIdentity current = identityService.readIdentity(stack);
        return current != null && current.id().equals(identity.id());
    }

    private void runOnPlayerThread(Player player, Runnable runnable) {
        if (IllegalStack.isFoliaServer()) {
            player.getScheduler().run(plugin, task -> runnable.run(), null);
        } else {
            Bukkit.getScheduler().runTask(plugin, runnable);
        }
    }

    private byte[] serializeItem(ItemStack stack) {
        if (stack == null) {
            return null;
        }
        try (ByteArrayOutputStream out = new ByteArrayOutputStream();
             BukkitObjectOutputStream objectOut = new BukkitObjectOutputStream(out)) {
            objectOut.writeObject(stack);
            return out.toByteArray();
        } catch (IOException | RuntimeException e) {
            LOGGER.warn("[ItemIntegrity] Falha ao serializar snapshot do item conflitante - FAIL_OPEN para DELETE.", e);
            return null;
        }
    }

    private String formatWebhook(IntegrityCaseSnapshot snapshot, PresenceRecord canonical, PresenceRecord conflicting) {
        return """
                **Caso:** `%s`
                **Modo:** `%s`
                **Acao:** `%s`
                **Confianca:** `%s`
                **Decisao:** `%s`

                **Item ID:** `%s`
                **Material:** `%s`
                **Motivo:** `%s`

                **Presenca oficial**
                `%s`

                **Presenca conflitante**
                `%s`

                **Por que a oficial venceu**
                `%s`

                **Revalidacao**
                %s
                """.formatted(snapshot.caseId(), translateMode(snapshot.mode()), translateAction(snapshot.action()),
                translateConfidence(snapshot.confidence()),
                translateDecision(snapshot.decision()), snapshot.itemId(), snapshot.material(),
                translateReason(snapshot.reason()), summarize(canonical), summarize(conflicting),
                canonicalReasonCompact(canonical), compactRevalidation(snapshot));
    }

    private String compactRevalidation(IntegrityCaseSnapshot snapshot) {
        String summary = snapshot.conflictingItemSummary();
        if (summary == null || summary.isBlank()) {
            return "`indisponivel`";
        }
        int marker = summary.indexOf("ANOMALIA INICIAL");
        if (marker < 0) {
            marker = summary.indexOf("Revalidacao:");
        }
        String compact = marker >= 0 ? summary.substring(marker) : "Revalidacao: nao informada";
        compact = compact.length() > 900 ? compact.substring(0, 897) + "..." : compact;
        return "```text\n" + compact + "\n```";
    }

    private String formatDetailedLog(IntegrityCaseSnapshot snapshot, PresenceRecord canonical, PresenceRecord conflicting) {
        return """
                Modo: %s
                Decisao: %s
                Acao: %s
                Confianca: %s
                Item ID: %s
                Material: %s
                Motivo: %s

                PRESENCA OFICIAL
                %s

                PRESENCA CONFLITANTE
                %s

                POR QUE A OFICIAL VENCEU
                %s

                ITEM CONFLITANTE
                %s
                """.formatted(snapshot.mode(), snapshot.decision(), snapshot.action(), snapshot.confidence(),
                snapshot.itemId(), snapshot.material(), translateReason(snapshot.reason()), summarizeMultiline(canonical),
                summarizeMultiline(conflicting), canonicalReason(canonical), snapshot.conflictingItemSummary());
    }

    private String canonicalReasonCompact(PresenceRecord canonical) {
        return canonical.holder().type() + "/" + canonical.state()
                + " rev=" + canonical.presenceRevision()
                + " last=" + Instant.ofEpochMilli(canonical.lastConfirmedAtMs());
    }

    private String translateDecision(String decision) {
        if ("DUPLICATE_DETECTED".equals(decision)) return "Duplicidade detectada";
        if ("CONFIRMED_DUPLICATE".equals(decision)) return "Duplicidade confirmada apos revalidacao";
        if ("AMBIGUOUS_REVALIDATION".equals(decision)) return "Revalidacao ambigua";
        if ("TRANSIENT_CONFLICT_RECONCILED".equals(decision)) return "Conflito transitorio reconciliado";
        if ("RESURRECTED_ITEM".equals(decision)) return "Item terminal reapareceu";
        if ("AMBIGUOUS_CONTAINER_PRESENCE".equals(decision)) return "Presenca antiga de container nao revalidada";
        return decision;
    }

    private String translateMode(String mode) {
        if ("MONITOR".equals(mode)) return "Monitoramento";
        if ("DELETE".equals(mode)) return "Remocao automatica";
        return mode;
    }

    private String translateAction(String action) {
        return switch (action) {
            case "WOULD_REMOVE" -> "Removeria se estivesse em DELETE";
            case "REMOVED" -> "Item conflitante removido";
            case "MONITOR_ONLY" -> "Somente monitoramento";
            case "DELETE_ABORTED_REVALIDATION_FAILED" -> "Remocao abortada: revalidacao falhou";
            case "DELETE_ABORTED_PERSISTENCE_FAILED" -> "Remocao abortada: persistencia falhou";
            case "DELETE_ABORTED_VIRTUAL_HOLDER" -> "Remocao bloqueada: inventario virtual/plugin externo";
            case "MONITOR_ABORTED_PERSISTENCE_FAILED" -> "Monitoramento sem persistencia confirmada";
            default -> action;
        };
    }

    private String translateConfidence(String confidence) {
        if ("CRITICAL".equals(confidence)) return "Critica";
        if ("LOW".equals(confidence)) return "Baixa";
        return confidence;
    }

    private String translateReason(String reason) {
        if (DUPLICATE_REASON.equals(reason)) return "Mesmo Item ID em presencas incompatíveis";
        if (RESURRECTED_REASON.equals(reason)) return "Item marcado como terminal reapareceu";
        if ("VIRTUAL_OR_EXTERNAL_HOLDER_OBSERVED".equals(reason)) return "Observado em inventario virtual/plugin externo";
        if ("CONTAINER_PRESENCE_UNAVAILABLE".equals(reason)) return "Container antigo nao pode ser revalidado com seguranca";
        if ("AMBIGUOUS_REVALIDATION".equals(reason)) return "Segunda verificacao nao conseguiu provar duas instancias com seguranca";
        if ("TRANSIENT_RECONCILED".equals(reason)) return "A segunda verificacao encontrou so uma instancia fisica";
        return reason;
    }

    private String canonicalReason(PresenceRecord canonical) {
        return "Registration: " + canonical.identity().registeredAtEpochMs() + '\n'
                + "Last confirmed: " + Instant.ofEpochMilli(canonical.lastConfirmedAtMs()) + '\n'
                + "Holder/state strength: " + canonical.holder().type() + " / " + canonical.state() + '\n'
                + "Revision: " + canonical.presenceRevision();
    }

    private String summarizeItem(ItemStack stack) {
        if (stack == null) {
            return "Item snapshot: unavailable";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("Amount: ").append(stack.getAmount()).append('\n');
        if (!stack.getType().name().endsWith("SHULKER_BOX")
                || !(stack.getItemMeta() instanceof BlockStateMeta meta)
                || !(meta.getBlockState() instanceof ShulkerBox shulker)) {
            sb.append("Nested snapshot: not a shulker");
            return sb.toString();
        }

        ItemStack[] contents = shulker.getInventory().getContents();
        int used = 0;
        StringBuilder slots = new StringBuilder();
        for (int i = 0; i < contents.length; i++) {
            ItemStack content = contents[i];
            if (content == null || content.getType().isAir()) {
                continue;
            }
            used++;
            if (slots.length() > 0) {
                slots.append("; ");
            }
            ItemIdentity nested = identityService.readIdentity(content);
            slots.append(i).append('=').append(content.getType().name()).append('x').append(content.getAmount());
            if (nested != null) {
                slots.append('[').append(nested.id()).append(']');
            }
        }
        sb.append("Shulker slots: ").append(used).append('/').append(contents.length);
        sb.append('\n').append(used == 0 ? "Slots: empty" : "Slots: " + slots);
        return sb.toString();
    }

    private String summarize(PresenceRecord record) {
        return record.state().name() + " " + record.holder().describe()
                + " rev=" + record.presenceRevision()
                + " last=" + record.lastConfirmedAtMs();
    }

    private String summarizeMultiline(PresenceRecord record) {
        HolderRef holder = record.holder();
        StringBuilder sb = new StringBuilder();
        sb.append("Holder: ").append(holder.type().name()).append('\n');
        sb.append("Logical holder: ").append(holder.describe()).append('\n');
        if (holder instanceof HolderRef.EnderChestHolder ender) {
            sb.append("Ender Chest owner: ").append(ender.playerName()).append(" / ").append(ender.playerId()).append('\n');
            sb.append("Slot: ").append(ender.slot()).append('\n');
        }
        if (holder instanceof HolderRef.PlayerHolder p) {
            sb.append("Player: ").append(p.playerName()).append(" / ").append(p.playerId()).append('\n');
            sb.append("Slot: ").append(p.slot()).append('\n');
        } else if (holder instanceof HolderRef.ContainerHolder c) {
            sb.append("World: ").append(c.world()).append('\n');
            sb.append("Position: ").append(c.x()).append(',').append(c.y()).append(',').append(c.z()).append('\n');
            sb.append("Slot: ").append(c.slot()).append('\n');
        } else if (holder instanceof HolderRef.ItemEntityHolder e) {
            sb.append("Entity: ").append(e.entityUuid()).append('\n');
            sb.append("World: ").append(e.world()).append('\n');
            sb.append("Position: ").append(e.x()).append(',').append(e.y()).append(',').append(e.z()).append('\n');
        } else if (holder instanceof HolderRef.EntityHolder e) {
            sb.append("Entity: ").append(e.entityUuid()).append('\n');
            sb.append("World: ").append(e.world()).append('\n');
            sb.append("Position: ").append(e.x()).append(',').append(e.y()).append(',').append(e.z()).append('\n');
        } else if (holder instanceof HolderRef.VirtualHolder v) {
            sb.append("Virtual: ").append(v.label()).append('\n');
            sb.append("Owner/viewer: ").append(v.viewerOrOwner()).append('\n');
            sb.append("Slot: ").append(v.slot()).append('\n');
        }
        sb.append("State: ").append(record.state().name()).append('\n');
        sb.append("Revision: ").append(record.presenceRevision()).append('\n');
        sb.append("Last confirmed: ").append(Instant.ofEpochMilli(record.lastConfirmedAtMs()));
        return sb.toString();
    }

    private String caseId() {
        byte[] bytes = new byte[8];
        RANDOM.nextBytes(bytes);
        StringBuilder sb = new StringBuilder("ZIC-");
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    enum RevalidationStatus {
        CONFIRMED_DUPLICATE,
        TRANSIENT_RECONCILED,
        AMBIGUOUS_REVALIDATION
    }

    private record IncidentMemory(long lastObservedAtMs) {
    }

    private record ChunkKey(String world, int x, int z) {
    }

    private record PendingContainerConflict(ItemIdentity identity, HolderRef.ContainerHolder container,
                                            HolderRef.PlayerHolder player, ItemStack stack, long expiresAtMs) {
    }

    private record PendingContainerLocator(ChunkKey chunk, String key, long expiresAtMs) {
    }

    record TargetedRevalidation(RevalidationStatus status, RevalidationSummary summary,
                                        java.util.Optional<PresenceRecord> canonical,
                                        java.util.Optional<PresenceRecord> conflicting,
                                        java.util.Optional<PresenceRecord> singlePresence) {
    }

    private record RevalidationSummary(long elapsedMs, String initialCanonical, String initialConflicting,
                                       List<PhysicalPresence> canonicalFound,
                                       List<PhysicalPresence> conflictingFound) {
        static RevalidationSummary notRun() {
            return new RevalidationSummary(0, "not-run", "not-run", List.of(), List.of());
        }

        String describe() {
            if ("not-run".equals(initialCanonical)) {
                return "Revalidacao: nao executada";
            }
            return """
                    ANOMALIA INICIAL
                    Canonical inicial: %s
                    Conflitante inicial: %s

                    REVALIDACAO DIRECIONADA
                    Delay observado: %dms
                    Canonical encontrado: %s
                    Conflitante/destinos observados encontrados: %s
                    """.formatted(initialCanonical, initialConflicting, elapsedMs,
                    describeFound(canonicalFound), describeFound(conflictingFound));
        }

        private String describeFound(List<PhysicalPresence> found) {
            if (found.isEmpty()) {
                return "nenhuma instancia fisica encontrada";
            }
            StringBuilder sb = new StringBuilder();
            for (PhysicalPresence presence : found) {
                if (sb.length() > 0) {
                    sb.append("; ");
                }
                if (presence.unavailable()) {
                    sb.append("indisponivel(").append(presence.detail()).append(")");
                } else {
                    sb.append(presence.record().holder().describe());
                }
            }
            return sb.toString();
        }
    }

    private record PhysicalPresence(PresenceRecord record, boolean unavailable, String detail) {
        static PhysicalPresence found(PresenceRecord record) {
            return new PhysicalPresence(record, false, "found");
        }

        static PhysicalPresence unavailable(HolderRef holder, String detail) {
            PresenceRecord placeholder = new PresenceRecord(null, holder, PresenceState.UNKNOWN, 0,
                    System.currentTimeMillis());
            return new PhysicalPresence(placeholder, true, detail);
        }
    }

    private enum ContainerConflictDisposition {
        NOT_CONTAINER,
        CONFIRMED_STILL_PRESENT,
        STALE_RECONCILED,
        AMBIGUOUS
    }

    private enum ContainerPhysicalState {
        CONTAINS_ITEM,
        MISSING_ITEM_OR_CONTAINER_GONE,
        UNAVAILABLE
    }
}
