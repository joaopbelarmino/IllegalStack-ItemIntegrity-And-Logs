package main.java.me.dniym.identity.listeners;

import com.destroystokyo.paper.event.block.BlockDestroyEvent;
import com.destroystokyo.paper.event.player.PlayerArmorChangeEvent;
import main.java.me.dniym.IllegalStack;
import main.java.me.dniym.identity.IdentityService;
import main.java.me.dniym.identity.ItemIdentity;
import main.java.me.dniym.identity.MigrationResult;
import main.java.me.dniym.identity.MigrationService;
import main.java.me.dniym.identity.TrackabilityPolicy;
import main.java.me.dniym.identity.VirtualCustodyService;
import main.java.me.dniym.identity.audit.AuditQueue;
import main.java.me.dniym.identity.audit.AuditTask;
import main.java.me.dniym.identity.audit.ItemSnapshot;
import main.java.me.dniym.identity.conflict.ConflictDetector;
import main.java.me.dniym.identity.presence.HolderRef;
import main.java.me.dniym.identity.presence.HolderType;
import main.java.me.dniym.identity.presence.PresenceObservation;
import main.java.me.dniym.identity.presence.PresenceRecord;
import main.java.me.dniym.identity.presence.PresenceState;
import main.java.me.dniym.identity.presence.PresenceStore;
import main.java.me.dniym.utils.Scheduler;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.BlockState;
import org.bukkit.block.Container;
import org.bukkit.block.ShulkerBox;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.ItemFrame;
import org.bukkit.entity.Player;
import org.bukkit.entity.minecart.StorageMinecart;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockMultiPlaceEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.ItemDespawnEvent;
import org.bukkit.event.entity.ItemMergeEvent;
import org.bukkit.event.entity.ItemSpawnEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerArmorStandManipulateEvent;
import org.bukkit.event.inventory.FurnaceSmeltEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryCreativeEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerAttemptPickupItemEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerItemBreakEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.vehicle.VehicleDestroyEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BlockStateMeta;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;

public final class ItemIntegrityLifecycleListener implements Listener {

    private static final long PENDING_SPAWN_TTL_MS = 2_000L;

    private final IllegalStack plugin;
    private final IdentityService identityService;
    private final MigrationService migrationService;
    private final PresenceStore presenceStore;
    private final AuditQueue auditQueue;
    private final ConflictDetector conflictDetector;
    private final IdentityPlayerInventoryListener playerScanner;
    private final VirtualCustodyService virtualCustodyService;
    private final Map<String, Queue<PendingSpawn>> pendingLegitSpawns = new ConcurrentHashMap<>();
    private final Map<InventoryReconciliationKey, InventorySnapshot> pendingInventories = new ConcurrentHashMap<>();

    public ItemIntegrityLifecycleListener(IllegalStack plugin, IdentityService identityService,
                                          MigrationService migrationService, PresenceStore presenceStore,
                                          AuditQueue auditQueue, ConflictDetector conflictDetector,
                                          IdentityPlayerInventoryListener playerScanner,
                                          VirtualCustodyService virtualCustodyService) {
        this.plugin = plugin;
        this.identityService = identityService;
        this.migrationService = migrationService;
        this.presenceStore = presenceStore;
        this.auditQueue = auditQueue;
        this.conflictDetector = conflictDetector;
        this.playerScanner = playerScanner;
        this.virtualCustodyService = virtualCustodyService;
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent event) {
        if (event.getWhoClicked() instanceof Player player) {
            scheduleInventoryReconciliation(event.getView().getTopInventory(), player, "inventory_click");
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInventoryDrag(InventoryDragEvent event) {
        if (event.getWhoClicked() instanceof Player player) {
            scheduleInventoryReconciliation(event.getView().getTopInventory(), player, "inventory_drag");
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInventoryCreative(InventoryCreativeEvent event) {
        if (event.getWhoClicked() instanceof Player player) {
            playerScanner.scheduleScan(player, PresenceState.LIVE_CONFIRMED);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerArmorChange(PlayerArmorChangeEvent event) {
        ItemIdentity equipped = identityService.readIdentity(event.getNewItem());
        ItemIdentity unequipped = identityService.readIdentity(event.getOldItem());
        if (equipped == null && unequipped == null) {
            return;
        }
        Player player = event.getPlayer();
        int expectedSlot = playerInventorySlot(event.getSlot());
        if (equipped != null) {
            commitEquippedArmorIfUnique(player, equipped, expectedSlot);
        }
        if (unequipped != null && (equipped == null || !unequipped.id().equals(equipped.id()))) {
            commitPlayerItemIfUnique(player, unequipped);
        }
        Scheduler.runTaskLater(plugin, () -> {
            if (equipped != null) {
                commitEquippedArmorIfUnique(player, equipped, expectedSlot);
            }
            if (unequipped != null && (equipped == null || !unequipped.id().equals(equipped.id()))) {
                commitPlayerItemIfUnique(player, unequipped);
            }
            playerScanner.scanPlayer(player, PresenceState.LIVE_CONFIRMED);
        }, 1, player);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerCommand(PlayerCommandPreprocessEvent event) {
        Map<String, ItemIdentity> before = trackedPlayerInventory(event.getPlayer());
        for (ItemIdentity identity : before.values()) {
            playerScanner.scheduleExternalCustodyCheck(event.getPlayer(), identity);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInventoryClose(InventoryCloseEvent event) {
        Player viewer = event.getPlayer() instanceof Player player ? player : null;
        Inventory inventory = event.getInventory();
        if (viewer != null) {
            scheduleInventoryReconciliation(inventory, viewer, "inventory_close");
        } else if (inventory.getLocation() != null) {
            scanInventory(inventory, null, "inventory_close");
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        pendingInventories.keySet().removeIf(key -> key.viewer().equals(event.getPlayer().getUniqueId()));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInventoryMoveItem(InventoryMoveItemEvent event) {
        ItemIdentity identity = identityService.readIdentity(event.getItem());
        if (identity == null) {
            return;
        }
        HolderRef destination = holderForInventory(event.getDestination(), null, null, "hopper_move");
        if (destination instanceof HolderRef.VirtualHolder virtualHolder) {
            observeVirtual(identity, virtualHolder);
            return;
        }
        presenceStore.commitHandoff(identity, destination, PresenceState.PERSISTED_CONTAINER);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerDeath(PlayerDeathEvent event) {
        Location location = event.getEntity().getLocation();
        for (ItemStack drop : event.getDrops()) {
            ItemIdentity identity = identityService.readIdentity(drop);
            if (identity != null) {
                addPendingSpawn(identity, location, "death_drop");
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onVehicleDestroy(VehicleDestroyEvent event) {
        if (!(event.getVehicle() instanceof StorageMinecart cart)) {
            return;
        }
        Location location = cart.getLocation();
        for (ItemStack item : cart.getInventory().getContents()) {
            ItemIdentity identity = identityService.readIdentity(item);
            if (identity != null) {
                addPendingSpawn(identity, location, "storage_minecart_destroy");
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onItemSpawn(ItemSpawnEvent event) {
        Item item = event.getEntity();
        ItemIdentity identity = identityService.readIdentity(item.getItemStack());
        if (identity == null) {
            return;
        }
        PendingSpawn pending = pollPendingSpawn(identity.id(), item.getLocation());
        if (pending != null) {
            presenceStore.commitHandoff(identity, itemHolder(item), PresenceState.LIVE_CONFIRMED);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onAttemptPickup(PlayerAttemptPickupItemEvent event) {
        playerScanner.scheduleScan(event.getPlayer(), PresenceState.LIVE_CONFIRMED);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent event) {
        if (event.getEntity() instanceof Player player) {
            playerScanner.scheduleScan(player, PresenceState.LIVE_CONFIRMED);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onItemDespawn(ItemDespawnEvent event) {
        ItemIdentity identity = identityService.readIdentity(event.getEntity().getItemStack());
        if (identity != null) {
            presenceStore.commitHandoff(identity, itemHolder(event.getEntity()), PresenceState.TERMINAL_DESPAWNED);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onItemMerge(ItemMergeEvent event) {
        ItemIdentity identity = identityService.readIdentity(event.getEntity().getItemStack());
        if (identity != null) {
            presenceStore.commitHandoff(identity, itemHolder(event.getEntity()), PresenceState.TERMINAL_MERGED);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onItemDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Item item)) {
            return;
        }
        ItemIdentity identity = identityService.readIdentity(item.getItemStack());
        if (identity == null) {
            return;
        }
        Scheduler.runTaskLater(plugin, () -> {
            if (!item.isValid() || item.isDead()) {
                presenceStore.commitHandoff(identity, itemHolder(item), PresenceState.TERMINAL_DESTROYED);
            }
        }, 1, item);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onConsume(PlayerItemConsumeEvent event) {
        ItemIdentity identity = identityService.readIdentity(event.getItem());
        if (identity != null) {
            presenceStore.commitHandoff(identity,
                    new HolderRef.PlayerHolder(event.getPlayer().getUniqueId(), event.getPlayer().getName(), null),
                    PresenceState.TERMINAL_CONSUMED);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onItemBreak(PlayerItemBreakEvent event) {
        ItemIdentity identity = identityService.readIdentity(event.getBrokenItem());
        if (identity != null) {
            presenceStore.commitHandoff(identity,
                    new HolderRef.PlayerHolder(event.getPlayer().getUniqueId(), event.getPlayer().getName(), null),
                    PresenceState.TERMINAL_BROKEN);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onFurnaceSmelt(FurnaceSmeltEvent event) {
        ItemIdentity identity = identityService.readIdentity(event.getSource());
        if (identity != null) {
            Location location = event.getBlock().getLocation();
            presenceStore.commitHandoff(identity, new HolderRef.ContainerHolder(HolderType.CONTAINER_OTHER,
                    location.getWorld().getName(), location.getBlockX(), location.getBlockY(), location.getBlockZ(), null),
                    PresenceState.TERMINAL_CONSUMED);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInteractEntity(PlayerInteractEntityEvent event) {
        Entity clicked = event.getRightClicked();
        if (clicked instanceof ItemFrame frame) {
            Scheduler.runTaskLater(plugin, () -> commitItemFrame(frame), 1, frame);
        } else if (clicked instanceof ArmorStand stand) {
            Scheduler.runTaskLater(plugin, () -> commitArmorStand(stand), 1, stand);
        }
        playerScanner.scheduleScan(event.getPlayer(), PresenceState.LIVE_CONFIRMED);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onArmorStandManipulate(PlayerArmorStandManipulateEvent event) {
        Map<String, ItemIdentity> affected = new LinkedHashMap<>();
        ItemIdentity standItem = identityService.readIdentity(event.getArmorStandItem());
        ItemIdentity playerItem = identityService.readIdentity(event.getPlayerItem());
        if (standItem != null) {
            affected.put(standItem.id(), standItem);
        }
        if (playerItem != null) {
            affected.put(playerItem.id(), playerItem);
        }
        if (affected.isEmpty()) {
            return;
        }

        ArmorStand stand = event.getRightClicked();
        Player player = event.getPlayer();
        Scheduler.runTaskLater(plugin, () -> {
            commitArmorStand(stand);
            for (ItemIdentity identity : affected.values()) {
                if (armorStandContains(stand, identity.id())) {
                    continue;
                }
                List<Integer> playerSlots = playerSlotsContaining(player, identity.id());
                if (playerSlots.size() == 1) {
                    presenceStore.commitHandoff(identity,
                            new HolderRef.PlayerHolder(player.getUniqueId(), player.getName(), playerSlots.get(0)),
                            PresenceState.LIVE_CONFIRMED);
                }
            }
            playerScanner.scanPlayer(player, PresenceState.LIVE_CONFIRMED);
        }, 1, stand);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onChunkLoad(ChunkLoadEvent event) {
        conflictDetector.onChunkLoaded(event.getWorld(), event.getChunk().getX(), event.getChunk().getZ());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockDestroy(BlockDestroyEvent event) {
        observeContainerState(event.getBlock().getState(), "paper_block_destroy");
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockMultiPlace(BlockMultiPlaceEvent event) {
        for (BlockState state : event.getReplacedBlockStates()) {
            observeContainerState(state, "paper_block_multi_place");
        }
    }

    private void scanInventory(Inventory inventory, Player viewer, String context) {
        scanInventory(inventory, viewer, captureInventory(inventory, viewer, context));
    }

    private void scanInventory(Inventory inventory, Player viewer, InventorySnapshot snapshot) {
        Map<String, NestedShulkerCustody> carriedShulkerContents = inventory.getType() == InventoryType.SHULKER_BOX
                ? indexCarriedShulkerContents(viewer) : Map.of();
        for (ObservedInventoryItem observed : snapshot.items()) {
            ItemStack item = observed.item();
            HolderRef holder = observed.holder();
            if (holder instanceof HolderRef.VirtualHolder) {
                ItemIdentity identity = observed.identity();
                if (identity != null) {
                    ShulkerMirror mirror = shulkerMirror(identity, inventory, viewer, carriedShulkerContents);
                    if (mirror.kind() == ShulkerMirrorKind.PHYSICAL_BLOCK) {
                        continue;
                    }
                    if (mirror.kind() == ShulkerMirrorKind.CARRIED_ITEM
                            && outerShulkerOwnedByViewer(mirror.custody(), viewer)) {
                        HolderRef.VirtualHolder nestedHolder = new HolderRef.VirtualHolder(
                                HolderType.VIRTUAL_INVENTORY,
                                "carried_shulker:" + mirror.custody().outerItemId(),
                                viewer.getUniqueId().toString(), mirror.custody().slot());
                        presenceStore.commitHandoff(identity, nestedHolder, PresenceState.LIVE_CONFIRMED);
                        continue;
                    }
                    observeVirtual(identity, (HolderRef.VirtualHolder) holder);
                }
            } else {
                MigrationResult migration = observed.identity() != null && TrackabilityPolicy.isTrackable(item)
                        ? new MigrationResult(observed.identity(), false)
                        : migrationService.ensureIdentity(item, inventory.getLocation());
                if (migration != null) {
                    registerIfFresh(migration, item, viewer);
                    presenceStore.commitHandoff(migration.identity(), holder, PresenceState.PERSISTED_CONTAINER);
                }
            }
        }
    }

    private void scheduleInventoryReconciliation(Inventory inventory, Player viewer, String context) {
        InventoryReconciliationKey key = new InventoryReconciliationKey(viewer.getUniqueId(), inventory);
        if (pendingInventories.containsKey(key)) return;
        InventorySnapshot before = captureInventory(inventory, viewer, context);
        pendingInventories.put(key, before);
        try {
            Scheduler.runTaskLater(plugin, () -> {
                if (!pendingInventories.remove(key, before) || !viewer.isOnline()) return;
                InventorySnapshot after = captureInventory(inventory, viewer, context);
                reconcileInventoryRemovals(viewer, before.tracked(), after.tracked());
                if (inventory.getLocation() != null || isVirtualInventoryCandidate(inventory)) {
                    scanInventory(inventory, viewer, after);
                }
                playerScanner.scanPlayer(viewer, PresenceState.LIVE_CONFIRMED);
            }, 1, viewer);
        } catch (RuntimeException error) {
            pendingInventories.remove(key, before);
            throw error;
        }
    }

    private InventorySnapshot captureInventory(Inventory inventory, Player viewer, String context) {
        Map<String, TrackedInventoryItem> found = new HashMap<>();
        List<ObservedInventoryItem> items = new ArrayList<>();
        ItemStack[] contents = inventory.getContents();
        for (int slot = 0; slot < contents.length; slot++) {
            ItemStack item = contents[slot];
            if (item == null || item.getType().isAir()) continue;
            ItemIdentity identity = identityService.readIdentity(item);
            HolderRef holder = holderForInventory(inventory, viewer, slot, context);
            items.add(new ObservedInventoryItem(item, identity, holder));
            if (identity != null) {
                found.putIfAbsent(identity.id(), new TrackedInventoryItem(identity, holder));
            }
        }
        return new InventorySnapshot(items, found);
    }

    private void reconcileInventoryRemovals(Player viewer, Map<String, TrackedInventoryItem> before,
                                            Map<String, TrackedInventoryItem> after) {
        if (before.isEmpty()) {
            return;
        }
        for (Map.Entry<String, TrackedInventoryItem> entry : before.entrySet()) {
            if (after.containsKey(entry.getKey())) {
                continue;
            }
            TrackedInventoryItem removed = entry.getValue();
            if (!canonicalMatchesInventorySource(removed.identity(), removed.source(), viewer)) {
                continue;
            }
            List<Integer> playerSlots = playerSlotsContaining(viewer, removed.identity().id());
            if (playerSlots.size() == 1) {
                presenceStore.commitHandoff(removed.identity(),
                        new HolderRef.PlayerHolder(viewer.getUniqueId(), viewer.getName(), playerSlots.get(0)),
                        PresenceState.LIVE_CONFIRMED);
            }
        }
    }

    private record InventoryReconciliationKey(java.util.UUID viewer, Inventory inventory) {}
    private record ObservedInventoryItem(ItemStack item, ItemIdentity identity, HolderRef holder) {}
    private record InventorySnapshot(List<ObservedInventoryItem> items, Map<String, TrackedInventoryItem> tracked) {}

    private boolean canonicalMatchesInventorySource(ItemIdentity identity, HolderRef source, Player viewer) {
        return presenceStore.getCanonical(identity).map(PresenceRecord::holder).map(canonical -> {
            if (HolderRef.sameOwner(canonical, source)) {
                return true;
            }
            return canonical instanceof HolderRef.VirtualHolder virtual
                    && viewer.getUniqueId().toString().equals(virtual.viewerOrOwner())
                    && virtual.label().startsWith("carried_shulker:");
        }).orElse(false);
    }

    private boolean isVirtualInventoryCandidate(Inventory inventory) {
        if (inventory == null || inventory.getLocation() != null) {
            return false;
        }
        InventoryType type = inventory.getType();
        return type != InventoryType.PLAYER && type != InventoryType.CRAFTING;
    }

    private ShulkerMirror shulkerMirror(ItemIdentity identity, Inventory inventory, Player viewer,
                                        Map<String, NestedShulkerCustody> carriedShulkerContents) {
        if (inventory.getType() != InventoryType.SHULKER_BOX) {
            return ShulkerMirror.none();
        }
        boolean physicalBlockMirror = presenceStore.getCanonical(identity)
                .map(PresenceRecord::holder)
                .filter(holder -> holder instanceof HolderRef.ContainerHolder)
                .map(holder -> (HolderRef.ContainerHolder) holder)
                .filter(holder -> holder.type() == HolderType.SHULKER_BLOCK)
                .map(holder -> containerStillContains(holder, identity.id()))
                .orElse(false);
        if (physicalBlockMirror) {
            return ShulkerMirror.physicalBlock();
        }
        NestedShulkerCustody custody = carriedShulkerContents.get(identity.id());
        return custody != null ? ShulkerMirror.carried(custody) : ShulkerMirror.none();
    }

    private Map<String, NestedShulkerCustody> indexCarriedShulkerContents(Player viewer) {
        Map<String, NestedShulkerCustody> found = new HashMap<>();
        if (viewer == null || !viewer.isOnline()) {
            return found;
        }
        ItemStack[] contents = viewer.getInventory().getContents();
        for (ItemStack item : contents) {
            indexShulkerItem(item, found);
        }
        if (contents.length <= 40) {
            indexShulkerItem(viewer.getInventory().getItemInOffHand(), found);
        }
        return found;
    }

    private void indexShulkerItem(ItemStack item, Map<String, NestedShulkerCustody> found) {
        if (item == null || item.getType().isAir() || !item.getType().name().endsWith("SHULKER_BOX")
                || !(item.getItemMeta() instanceof BlockStateMeta meta)
                || !(meta.getBlockState() instanceof ShulkerBox shulker)) {
            return;
        }
        ItemIdentity outer = identityService.readIdentity(item);
        if (outer == null) {
            return;
        }
        ItemStack[] shulkerContents = shulker.getInventory().getContents();
        for (int slot = 0; slot < shulkerContents.length; slot++) {
            ItemIdentity nested = identityService.readIdentity(shulkerContents[slot]);
            if (nested != null) {
                found.putIfAbsent(nested.id(), new NestedShulkerCustody(outer.id(), slot));
            }
        }
    }

    private boolean outerShulkerOwnedByViewer(NestedShulkerCustody custody, Player viewer) {
        if (custody == null || viewer == null) {
            return false;
        }
        return presenceStore.getCanonical(custody.outerItemId())
                .map(PresenceRecord::holder)
                .filter(holder -> holder instanceof HolderRef.PlayerHolder)
                .map(holder -> ((HolderRef.PlayerHolder) holder).playerId().equals(viewer.getUniqueId()))
                .orElse(false);
    }

    private boolean hasIdentity(ItemStack item, String itemId) {
        ItemIdentity identity = identityService.readIdentity(item);
        return identity != null && identity.id().equals(itemId);
    }

    private boolean containerStillContains(HolderRef.ContainerHolder holder, String itemId) {
        org.bukkit.World world = org.bukkit.Bukkit.getWorld(holder.world());
        if (world == null || !world.isChunkLoaded(holder.x() >> 4, holder.z() >> 4)) {
            return false;
        }
        BlockState state = world.getBlockAt(holder.x(), holder.y(), holder.z()).getState();
        if (!(state instanceof Container container)) {
            return false;
        }
        for (ItemStack item : container.getInventory().getContents()) {
            ItemIdentity current = identityService.readIdentity(item);
            if (current != null && current.id().equals(itemId)) {
                return true;
            }
        }
        return false;
    }

    private Map<String, ItemIdentity> trackedPlayerInventory(Player player) {
        Map<String, ItemIdentity> found = new HashMap<>();
        for (ItemStack item : player.getInventory().getContents()) {
            ItemIdentity identity = identityService.readIdentity(item);
            if (identity != null) {
                found.put(identity.id(), identity);
            }
        }
        ItemIdentity offhand = identityService.readIdentity(player.getInventory().getItemInOffHand());
        if (offhand != null) {
            found.put(offhand.id(), offhand);
        }
        return found;
    }

    private void observeContainerState(BlockState state, String context) {
        if (!(state instanceof Container container)) {
            return;
        }
        Location location = state.getLocation();
        if (location.getWorld() == null) {
            return;
        }
        HolderType type = holderType(state.getType());
        ItemStack[] contents = container.getInventory().getContents();
        for (int slot = 0; slot < contents.length; slot++) {
            ItemStack item = contents[slot];
            ItemIdentity identity = identityService.readIdentity(item);
            if (identity == null) {
                continue;
            }
            HolderRef holder = new HolderRef.ContainerHolder(type, location.getWorld().getName(),
                    location.getBlockX(), location.getBlockY(), location.getBlockZ(), slot);
            observe(identity, holder, PresenceState.PERSISTED_CONTAINER, item);
        }
    }

    private void observe(ItemIdentity identity, HolderRef holder, PresenceState state, ItemStack stack) {
        PresenceObservation observation = presenceStore.observe(identity, holder, state);
        if (!observation.sameOwnerAsCanonical() && observation.canonicalBefore().isPresent()) {
            PresenceRecord canonical = observation.canonicalBefore().get();
            conflictDetector.handleMonitorOnlyIncident(stack, identity, canonical, observation.candidate(),
                    canonical.state().isTerminal() ? "RESURRECTED_ITEM" : "DIVERGENT_VIRTUAL_OBSERVATION",
                    canonical.state().isTerminal() ? "RESURRECTED_ITEM" : "VIRTUAL_OR_EXTERNAL_HOLDER_OBSERVED");
        }
    }

    private void observeVirtual(ItemIdentity identity, HolderRef.VirtualHolder holder) {
        if (virtualCustodyService.tryCommitIntoVirtual(identity, holder)) {
            return;
        }

        // A GUI pode ser clone, preview ou espelho do item real. Mantemos a
        // observacao diagnostica no cache/auditoria, mas ela sozinha nunca e
        // tratada como uma segunda instancia fisica.
        presenceStore.observe(identity, holder, PresenceState.LIVE_CONFIRMED);
    }

    private HolderRef holderForInventory(Inventory inventory, Player viewer, Integer slot, String context) {
        Location location = inventory.getLocation();
        if (location == null || location.getWorld() == null) {
            String owner = viewer != null ? viewer.getUniqueId().toString() : null;
            return new HolderRef.VirtualHolder(HolderType.VIRTUAL_INVENTORY,
                    inventory.getType().name().toLowerCase(java.util.Locale.ROOT), owner, slot);
        }
        Material material = location.getBlock().getType();
        HolderType type = holderType(material);
        return new HolderRef.ContainerHolder(type, location.getWorld().getName(),
                location.getBlockX(), location.getBlockY(), location.getBlockZ(), slot);
    }

    private HolderType holderType(Material material) {
        if (material == Material.CHEST || material == Material.TRAPPED_CHEST) return HolderType.CHEST;
        if (material == Material.BARREL) return HolderType.BARREL;
        if (material != null && material.name().endsWith("SHULKER_BOX")) return HolderType.SHULKER_BLOCK;
        return HolderType.CONTAINER_OTHER;
    }

    private HolderRef.ItemEntityHolder itemHolder(Item item) {
        Location location = item.getLocation();
        return new HolderRef.ItemEntityHolder(item.getUniqueId(), item.getEntityId(),
                location.getWorld() != null ? location.getWorld().getName() : null,
                location.getBlockX(), location.getBlockY(), location.getBlockZ());
    }

    private void commitItemFrame(ItemFrame frame) {
        ItemStack item = frame.getItem();
        ItemIdentity identity = identityService.readIdentity(item);
        if (identity == null) {
            return;
        }
        Location location = frame.getLocation();
        presenceStore.commitHandoff(identity, new HolderRef.EntityHolder(HolderType.ITEM_FRAME, frame.getUniqueId(),
                location.getWorld().getName(), location.getBlockX(), location.getBlockY(), location.getBlockZ(), null),
                PresenceState.LIVE_CONFIRMED);
    }

    private void commitArmorStand(ArmorStand stand) {
        if (stand == null || !stand.isValid() || stand.isDead()) {
            return;
        }
        EntityEquipment equipment = stand.getEquipment();
        if (equipment == null) {
            return;
        }
        commitArmorStandItem(stand, equipment.getItemInMainHand(), "mainhand");
        commitArmorStandItem(stand, equipment.getItemInOffHand(), "offhand");
        ItemStack[] armor = equipment.getArmorContents();
        for (int i = 0; i < armor.length; i++) {
            commitArmorStandItem(stand, armor[i], "armor" + i);
        }
    }

    private void commitArmorStandItem(ArmorStand stand, ItemStack item, String slot) {
        ItemIdentity identity = identityService.readIdentity(item);
        if (identity == null) {
            return;
        }
        Location location = stand.getLocation();
        presenceStore.commitHandoff(identity, new HolderRef.EntityHolder(HolderType.ARMOR_STAND, stand.getUniqueId(),
                location.getWorld().getName(), location.getBlockX(), location.getBlockY(), location.getBlockZ(), slot),
                PresenceState.LIVE_CONFIRMED);
    }

    private boolean armorStandContains(ArmorStand stand, String itemId) {
        if (stand == null || !stand.isValid() || stand.isDead()) {
            return false;
        }
        EntityEquipment equipment = stand.getEquipment();
        if (equipment == null) {
            return false;
        }
        if (hasIdentity(equipment.getItemInMainHand(), itemId)
                || hasIdentity(equipment.getItemInOffHand(), itemId)) {
            return true;
        }
        for (ItemStack armor : equipment.getArmorContents()) {
            if (hasIdentity(armor, itemId)) {
                return true;
            }
        }
        return false;
    }

    private List<Integer> playerSlotsContaining(Player player, String itemId) {
        List<Integer> found = new java.util.ArrayList<>();
        ItemStack[] contents = player.getInventory().getContents();
        for (int slot = 0; slot < contents.length; slot++) {
            if (hasIdentity(contents[slot], itemId)) {
                found.add(slot);
            }
        }
        if (contents.length <= 40 && hasIdentity(player.getInventory().getItemInOffHand(), itemId)) {
            found.add(40);
        }
        return found;
    }

    private void commitEquippedArmorIfUnique(Player player, ItemIdentity identity, int expectedSlot) {
        if (expectedSlot < 0 || player == null || !player.isOnline()) {
            return;
        }
        List<Integer> slots = playerSlotsContaining(player, identity.id());
        if (slots.size() != 1 || slots.get(0) != expectedSlot) {
            return;
        }
        presenceStore.commitHandoff(identity,
                new HolderRef.PlayerHolder(player.getUniqueId(), player.getName(), expectedSlot),
                PresenceState.LIVE_CONFIRMED);
    }

    private void commitPlayerItemIfUnique(Player player, ItemIdentity identity) {
        if (player == null || !player.isOnline()) {
            return;
        }
        List<Integer> slots = playerSlotsContaining(player, identity.id());
        if (slots.size() != 1) {
            return;
        }
        presenceStore.commitHandoff(identity,
                new HolderRef.PlayerHolder(player.getUniqueId(), player.getName(), slots.get(0)),
                PresenceState.LIVE_CONFIRMED);
    }

    private int playerInventorySlot(EquipmentSlot slot) {
        if (slot == EquipmentSlot.FEET) return 36;
        if (slot == EquipmentSlot.LEGS) return 37;
        if (slot == EquipmentSlot.CHEST) return 38;
        if (slot == EquipmentSlot.HEAD) return 39;
        if (slot == EquipmentSlot.OFF_HAND) return 40;
        return -1;
    }

    private void registerIfFresh(MigrationResult migration, ItemStack item, Player viewer) {
        if (!migration.freshlyAssigned() || auditQueue == null) {
            return;
        }
        ItemIdentity identity = migration.identity();
        auditQueue.offer(new AuditTask.RegisterItem(new ItemSnapshot(identity.id(), item.getType().name(),
                identity.registeredAtEpochMs(), identity.origin().name(),
                identity.registeredWorldRaw(), identity.registeredX(), identity.registeredY(), identity.registeredZ(),
                viewer != null ? viewer.getUniqueId().toString() : null, identity.registeredAtEpochMs())));
    }

    private void addPendingSpawn(ItemIdentity identity, Location location, String reason) {
        pendingLegitSpawns.computeIfAbsent(identity.id(), ignored -> new ArrayDeque<>())
                .offer(new PendingSpawn(location.clone(), System.currentTimeMillis() + PENDING_SPAWN_TTL_MS, reason));
        Scheduler.runTaskLater(plugin, () -> cleanupPending(identity.id()), 50);
    }

    private PendingSpawn pollPendingSpawn(String itemId, Location location) {
        Queue<PendingSpawn> queue = pendingLegitSpawns.get(itemId);
        if (queue == null) {
            return null;
        }
        long now = System.currentTimeMillis();
        PendingSpawn best = null;
        for (PendingSpawn pending : queue) {
            if (pending.expiresAtMs() < now) {
                continue;
            }
            if (sameWorld(pending.location(), location) && pending.location().distanceSquared(location) <= 9.0) {
                best = pending;
                break;
            }
        }
        if (best != null) {
            queue.remove(best);
        }
        cleanupPending(itemId);
        return best;
    }

    private void cleanupPending(String itemId) {
        Queue<PendingSpawn> queue = pendingLegitSpawns.get(itemId);
        if (queue == null) {
            return;
        }
        long now = System.currentTimeMillis();
        queue.removeIf(pending -> pending.expiresAtMs() < now);
        if (queue.isEmpty()) {
            pendingLegitSpawns.remove(itemId);
        }
    }

    private boolean sameWorld(Location a, Location b) {
        return a.getWorld() != null && b.getWorld() != null && a.getWorld().equals(b.getWorld());
    }

    private record PendingSpawn(Location location, long expiresAtMs, String reason) {
    }

    private record NestedShulkerCustody(String outerItemId, int slot) {
    }

    private record TrackedInventoryItem(ItemIdentity identity, HolderRef source) {
    }

    private enum ShulkerMirrorKind {
        NONE,
        PHYSICAL_BLOCK,
        CARRIED_ITEM
    }

    private record ShulkerMirror(ShulkerMirrorKind kind, NestedShulkerCustody custody) {
        static ShulkerMirror none() {
            return new ShulkerMirror(ShulkerMirrorKind.NONE, null);
        }

        static ShulkerMirror physicalBlock() {
            return new ShulkerMirror(ShulkerMirrorKind.PHYSICAL_BLOCK, null);
        }

        static ShulkerMirror carried(NestedShulkerCustody custody) {
            return new ShulkerMirror(ShulkerMirrorKind.CARRIED_ITEM, custody);
        }
    }
}
