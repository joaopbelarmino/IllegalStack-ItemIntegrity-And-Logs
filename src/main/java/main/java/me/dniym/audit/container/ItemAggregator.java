package main.java.me.dniym.audit.container;

import main.java.me.dniym.audit.model.ItemAggregate;
import main.java.me.dniym.identity.IdentityService;
import main.java.me.dniym.identity.ItemIdentity;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.bukkit.inventory.meta.BundleMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public final class ItemAggregator {
    private static final NamespacedKey SERIAL = new NamespacedKey("zetra", "serial");
    private final IdentityService identities;
    private final int maxDepth;
    private final int maxVisited;

    public ItemAggregator(IdentityService identities) { this(identities, 3, 4096); }
    ItemAggregator(IdentityService identities, int maxDepth, int maxVisited) {
        this.identities = identities; this.maxDepth = maxDepth; this.maxVisited = maxVisited;
    }

    public List<ItemAggregate> aggregate(ItemStack[] contents) {
        Map<String, Mutable> values = new HashMap<>();
        Counter counter = new Counter();
        if (contents != null) for (ItemStack item : contents) visit(item, 0, false, values, counter);
        List<ItemAggregate> out = new ArrayList<>();
        values.forEach((key, value) -> out.add(new ItemAggregate(key, value.direct, value.nested,
                Set.copyOf(value.serials), Set.copyOf(value.customIds))));
        return out;
    }

    private void visit(ItemStack item, int depth, boolean nested, Map<String, Mutable> values, Counter counter) {
        if (item == null || item.getType().isAir() || counter.value++ >= maxVisited) return;
        String key = item.getType().getKey().toString().toLowerCase(Locale.ROOT);
        Mutable value = values.computeIfAbsent(key, ignored -> new Mutable());
        if (nested) value.nested += item.getAmount(); else value.direct += item.getAmount();
        String serial = item.getPersistentDataContainer().get(SERIAL, PersistentDataType.STRING);
        if (serial != null && !serial.isBlank()) value.serials.add(serial);
        ItemIdentity identity = identities == null ? null : identities.readIdentity(item);
        if (identity != null) value.customIds.add(identity.id());
        if (depth >= maxDepth || !item.hasItemMeta()) return;
        if (item.getItemMeta() instanceof BlockStateMeta blockMeta
                && blockMeta.getBlockState() instanceof InventoryHolder holder) {
            for (ItemStack child : holder.getInventory().getContents()) visit(child, depth + 1, true, values, counter);
        } else if (item.getItemMeta() instanceof BundleMeta bundle && bundle.hasItems()) {
            for (ItemStack child : bundle.getItems()) visit(child, depth + 1, true, values, counter);
        }
    }

    private static final class Counter { int value; }
    private static final class Mutable {
        long direct, nested;
        final HashSet<String> serials = new HashSet<>(), customIds = new HashSet<>();
    }
}
