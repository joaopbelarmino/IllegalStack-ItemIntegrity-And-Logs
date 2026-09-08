package main.java.me.dniym.identity.listeners;

import main.java.me.dniym.IllegalStack;
import main.java.me.dniym.identity.IdentityService;
import main.java.me.dniym.identity.ItemIdentity;
import main.java.me.dniym.identity.presence.HolderRef;
import main.java.me.dniym.identity.presence.HolderType;
import main.java.me.dniym.identity.presence.PresenceState;
import main.java.me.dniym.identity.presence.PresenceStore;
import main.java.me.dniym.utils.Scheduler;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.ShulkerBox;
import org.bukkit.block.TileState;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockDropItemEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.ItemSpawnEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BlockStateMeta;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class ShulkerIdentityListener implements Listener {

    private final IllegalStack plugin;
    private final IdentityService identityService;
    private final PresenceStore presenceStore;
    private final Map<BlockKey, ItemIdentity> pendingBrokenShulkers = new ConcurrentHashMap<>();
    private final Map<BlockKey, TileState> pendingExplodedShulkers = new ConcurrentHashMap<>();

    public ShulkerIdentityListener(IllegalStack plugin, IdentityService identityService, PresenceStore presenceStore) {
        this.plugin = plugin;
        this.identityService = identityService;
        this.presenceStore = presenceStore;
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onShulkerPlace(BlockPlaceEvent event) {
        ItemStack placedItem = event.getItemInHand();
        ItemIdentity identity = identityService.readIdentity(placedItem);
        if (identity == null) {
            return;
        }

        Block block = event.getBlockPlaced();
        if (!(block.getState() instanceof ShulkerBox shulker)) {
            return;
        }

        if (identityService.copyIdentity(placedItem, shulker)) {
            shulker.update(true, false);
            commitBlock(identity, block);
            commitNestedToBlock(shulker, block);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onShulkerBreak(BlockBreakEvent event) {
        Block block = event.getBlock();
        if (!(block.getState() instanceof ShulkerBox shulker)) {
            return;
        }

        ItemIdentity identity = identityService.readIdentity(shulker);
        if (identity == null) {
            return;
        }

        BlockKey key = BlockKey.of(block);
        pendingBrokenShulkers.put(key, identity);
        Scheduler.runTaskLater(plugin, () -> pendingBrokenShulkers.remove(key), 40);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent event) {
        captureExplodedShulkers(event.blockList());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent event) {
        captureExplodedShulkers(event.blockList());
    }

    private void captureExplodedShulkers(List<Block> blocks) {
        for (Block block : blocks) {
            if (!(block.getState() instanceof ShulkerBox shulker)) {
                continue;
            }
            ItemIdentity identity = identityService.readIdentity(shulker);
            if (identity == null) {
                continue;
            }
            BlockKey key = BlockKey.of(block);
            pendingBrokenShulkers.put(key, identity);
            pendingExplodedShulkers.put(key, shulker);
            Scheduler.runTaskLater(plugin, () -> {
                pendingBrokenShulkers.remove(key);
                pendingExplodedShulkers.remove(key);
            }, 40);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onShulkerDrop(BlockDropItemEvent event) {
        BlockKey key = BlockKey.of(event.getBlock());
        ItemIdentity identity = pendingBrokenShulkers.remove(key);
        TileState tileState = event.getBlockState() instanceof TileState state ? state : pendingExplodedShulkers.remove(key);
        if (identity == null || tileState == null) {
            return;
        }

        List<Item> drops = event.getItems();
        for (Item drop : drops) {
            ItemStack stack = drop.getItemStack();
            if (!isShulker(stack.getType())) {
                continue;
            }
            identityService.copyIdentity(tileState, stack);
            drop.setItemStack(stack);
            commitItemEntity(identity, drop);
            if (tileState instanceof ShulkerBox shulker) {
                commitNestedToVirtual(shulker.getInventory().getContents(), identity,
                        "dropped_shulker:", drop.getUniqueId().toString());
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onItemSpawn(ItemSpawnEvent event) {
        Item drop = event.getEntity();
        ItemStack stack = drop.getItemStack();
        if (!isShulker(stack.getType())) {
            return;
        }

        BlockKey key = findPendingExplosion(drop.getLocation());
        if (key == null) {
            return;
        }
        ItemIdentity identity = pendingBrokenShulkers.remove(key);
        TileState tileState = pendingExplodedShulkers.remove(key);
        if (identity == null || tileState == null) {
            return;
        }

        identityService.copyIdentity(tileState, stack);
        drop.setItemStack(stack);
        commitItemEntity(identity, drop);
        if (tileState instanceof ShulkerBox shulker) {
            commitNestedToVirtual(shulker.getInventory().getContents(), identity,
                    "dropped_shulker:", drop.getUniqueId().toString());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerDrop(PlayerDropItemEvent event) {
        Item drop = event.getItemDrop();
        ItemIdentity identity = identityService.readIdentity(drop.getItemStack());
        if (identity != null) {
            commitItemEntity(identity, drop);
            commitNestedFromShulkerItem(drop.getItemStack(), identity,
                    "dropped_shulker:", drop.getUniqueId().toString());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent event) {
        if (!(event.getEntity() instanceof Player player)) {
            return;
        }
        ItemIdentity identity = identityService.readIdentity(event.getItem().getItemStack());
        if (identity == null) {
            return;
        }
        presenceStore.commitHandoff(identity,
                new HolderRef.PlayerHolder(player.getUniqueId(), player.getName(), null),
                PresenceState.LIVE_CONFIRMED);
        commitNestedFromShulkerItem(event.getItem().getItemStack(), identity,
                "carried_shulker:", player.getUniqueId().toString());
    }

    private void commitBlock(ItemIdentity identity, Block block) {
        Location location = block.getLocation();
        presenceStore.commitHandoff(identity, new HolderRef.ContainerHolder(
                HolderType.SHULKER_BLOCK,
                location.getWorld().getName(),
                location.getBlockX(),
                location.getBlockY(),
                location.getBlockZ(),
                null
        ), PresenceState.PERSISTED_BLOCK);
    }

    private void commitItemEntity(ItemIdentity identity, Item item) {
        Location location = item.getLocation();
        presenceStore.commitHandoff(identity, new HolderRef.ItemEntityHolder(
                item.getUniqueId(),
                item.getEntityId(),
                location.getWorld().getName(),
                location.getBlockX(),
                location.getBlockY(),
                location.getBlockZ()
        ), PresenceState.LIVE_CONFIRMED);
    }

    private void commitNestedToBlock(ShulkerBox shulker, Block block) {
        ItemStack[] contents = shulker.getInventory().getContents();
        for (int slot = 0; slot < contents.length; slot++) {
            ItemIdentity nested = identityService.readIdentity(contents[slot]);
            if (nested == null) {
                continue;
            }
            presenceStore.commitHandoff(nested, new HolderRef.ContainerHolder(HolderType.SHULKER_BLOCK,
                    block.getWorld().getName(), block.getX(), block.getY(), block.getZ(), slot),
                    PresenceState.PERSISTED_CONTAINER);
        }
    }

    private void commitNestedFromShulkerItem(ItemStack shulkerItem, ItemIdentity outerIdentity,
                                             String labelPrefix, String owner) {
        if (shulkerItem == null || !(shulkerItem.getItemMeta() instanceof BlockStateMeta meta)
                || !(meta.getBlockState() instanceof ShulkerBox shulker)) {
            return;
        }
        commitNestedToVirtual(shulker.getInventory().getContents(), outerIdentity, labelPrefix, owner);
    }

    private void commitNestedToVirtual(ItemStack[] contents, ItemIdentity outerIdentity,
                                       String labelPrefix, String owner) {
        for (int slot = 0; slot < contents.length; slot++) {
            ItemIdentity nested = identityService.readIdentity(contents[slot]);
            if (nested == null) {
                continue;
            }
            presenceStore.commitHandoff(nested, new HolderRef.VirtualHolder(HolderType.VIRTUAL_INVENTORY,
                    labelPrefix + outerIdentity.id(), owner, slot), PresenceState.LIVE_CONFIRMED);
        }
    }

    private boolean isShulker(Material material) {
        return material != null && material.name().endsWith("SHULKER_BOX");
    }

    private BlockKey findPendingExplosion(Location location) {
        BlockKey sameBlock = BlockKey.of(location.getBlock());
        if (pendingExplodedShulkers.containsKey(sameBlock)) {
            return sameBlock;
        }

        BlockKey best = null;
        double bestDistance = Double.MAX_VALUE;
        for (BlockKey key : pendingExplodedShulkers.keySet()) {
            if (!key.world().equals(location.getWorld().getName())) {
                continue;
            }
            double dx = key.x() + 0.5 - location.getX();
            double dy = key.y() + 0.5 - location.getY();
            double dz = key.z() + 0.5 - location.getZ();
            double distance = dx * dx + dy * dy + dz * dz;
            if (distance < bestDistance && distance <= 4.0) {
                bestDistance = distance;
                best = key;
            }
        }
        return best;
    }

    private record BlockKey(String world, int x, int y, int z) {
        static BlockKey of(Block block) {
            return new BlockKey(block.getWorld().getName(), block.getX(), block.getY(), block.getZ());
        }
    }
}
