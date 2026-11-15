package main.java.me.dniym.identity.presence;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.LongSupplier;

/** Bounded evidence hints for pending conflicts, never evidence of physical duplication. */
public final class DestinationWindow {
    public static final long DEFAULT_TTL_MS = 30_000;
    private final Map<String, Entry> pending = new LinkedHashMap<>();
    private final int capacity, destinations;
    private final long ttlMs;
    private final LongSupplier clock;
    public DestinationWindow(int capacity, int destinations, long ttlMs, LongSupplier clock) {
        this.capacity = capacity; this.destinations = destinations; this.ttlMs = ttlMs; this.clock = clock;
    }
    public synchronized boolean begin(String id, HolderRef first, HolderRef second) {
        long now = clock.getAsLong();
        // Insertion order is expiry order: visit only the expired prefix.
        var expired = pending.entrySet().iterator();
        while (expired.hasNext()) {
            if (expired.next().getValue().expires > now) break;
            expired.remove();
        }
        if (!pending.containsKey(id) && pending.size() >= capacity) return false;
        pending.computeIfAbsent(id, ignored -> new Entry(now + ttlMs));
        observe(id, first); observe(id, second);
        return true;
    }
    public synchronized void observe(String id, HolderRef holder) {
        Entry entry = pending.get(id);
        if (entry == null) return;
        if (entry.expires <= clock.getAsLong()) { pending.remove(id); return; }
        String key = HolderRef.logicalOwnerKey(holder);
        if (!entry.holders.containsKey(key) && entry.holders.size() >= destinations) {
            entry.overflow = true;
        } else entry.holders.put(key, holder);
    }
    public synchronized Result finish(String id) {
        Entry entry = pending.remove(id);
        return entry == null || entry.expires <= clock.getAsLong()
                ? new Result(List.of(), true) : new Result(List.copyOf(entry.holders.values()), entry.overflow);
    }
    public synchronized int size() { return pending.size(); }
    public record Result(List<HolderRef> holders, boolean incomplete) {}
    private static final class Entry {
        final long expires;
        final Map<String, HolderRef> holders = new LinkedHashMap<>();
        boolean overflow;
        Entry(long expires) { this.expires = expires; }
    }
}
