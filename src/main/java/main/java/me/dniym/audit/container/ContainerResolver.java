package main.java.me.dniym.audit.container;

import main.java.me.dniym.audit.model.ContainerRef;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.DoubleChest;
import org.bukkit.entity.Entity;
import org.bukkit.inventory.DoubleChestInventory;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public final class ContainerResolver {
    public Optional<Resolved> resolve(Inventory inventory) {
        if (inventory == null) return Optional.empty();
        InventoryHolder holder = inventory.getHolder(false);
        if (inventory instanceof DoubleChestInventory doubleInventory) {
            return doubleChest(doubleInventory);
        }
        if (holder instanceof BlockState state) return block(state.getBlock(), inventory);
        if (holder instanceof Entity entity) return entity(entity, inventory);
        if (holder instanceof DoubleChest chest && chest.getInventory() instanceof DoubleChestInventory doubleInventory) {
            return doubleChest(doubleInventory);
        }
        return Optional.empty();
    }

    public Optional<Resolved> resolve(Block block) {
        if (block == null || !(block.getState() instanceof InventoryHolder holder)) return Optional.empty();
        return resolve(holder.getInventory());
    }

    private Optional<Resolved> doubleChest(DoubleChestInventory inventory) {
        Location left = location(inventory.getLeftSide().getHolder(false));
        Location right = location(inventory.getRightSide().getHolder(false));
        if (left == null || right == null || left.getWorld() == null || right.getWorld() == null) return Optional.empty();
        String a = blockKey(left), b = blockKey(right);
        List<String> sorted = java.util.stream.Stream.of(a, b).sorted().toList();
        Location canonical = a.compareTo(b) <= 0 ? left : right;
        String key = "double:" + sorted.get(0) + "+" + sorted.get(1);
        ContainerRef ref = new ContainerRef(ContainerRef.stableUuid(key), key, canonical.getWorld().getName(),
                canonical.getBlockX(), canonical.getBlockY(), canonical.getBlockZ(), null, "DOUBLE_CHEST", inventory.getSize());
        return Optional.of(new Resolved(ref, inventory));
    }

    private Optional<Resolved> block(Block block, Inventory inventory) {
        Location l = block.getLocation();
        if (l.getWorld() == null) return Optional.empty();
        String key = blockKey(l);
        ContainerRef ref = new ContainerRef(ContainerRef.stableUuid(key), key, l.getWorld().getName(),
                l.getBlockX(), l.getBlockY(), l.getBlockZ(), null, block.getType().name(), inventory.getSize());
        return Optional.of(new Resolved(ref, inventory));
    }

    private Optional<Resolved> entity(Entity entity, Inventory inventory) {
        Location l = entity.getLocation();
        if (l.getWorld() == null) return Optional.empty();
        String key = "entity:" + entity.getUniqueId();
        ContainerRef ref = new ContainerRef(ContainerRef.stableUuid(key), key, l.getWorld().getName(),
                l.getBlockX(), l.getBlockY(), l.getBlockZ(), entity.getUniqueId(), entity.getType().name(), inventory.getSize());
        return Optional.of(new Resolved(ref, inventory));
    }

    private Location location(InventoryHolder holder) {
        if (holder instanceof BlockState state) return state.getLocation();
        return holder instanceof Entity entity ? entity.getLocation() : null;
    }

    private String blockKey(Location l) {
        UUID world = l.getWorld().getUID();
        return "block:" + world + ":" + l.getBlockX() + ":" + l.getBlockY() + ":" + l.getBlockZ();
    }

    public record Resolved(ContainerRef ref, Inventory inventory) {}
}
