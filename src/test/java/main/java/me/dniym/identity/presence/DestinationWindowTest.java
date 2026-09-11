package main.java.me.dniym.identity.presence;

import main.java.me.dniym.identity.ItemIdentity;
import main.java.me.dniym.identity.ItemOrigin;
import org.junit.jupiter.api.Test;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.jupiter.api.Assertions.*;

class DestinationWindowTest {
    private static HolderRef player(int slot) { return new HolderRef.PlayerHolder(new UUID(0, 1), "A", slot); }
    @Test void ignoresUnrelatedItemsAndCoalescesSlotsByOwner() {
        DestinationWindow window = new DestinationWindow(2, 3, 1000, () -> 0);
        for (int i = 0; i < 10_000; i++) window.observe("other-" + i, player(i));
        assertEquals(0, window.size());
        assertTrue(window.begin("id", player(0), player(1)));
        window.observe("id", player(40));
        var result = window.finish("id");
        assertFalse(result.incomplete());
        assertEquals(java.util.List.of(player(40)), result.holders());
        assertEquals(0, window.size());
    }
    @Test void overflowAndExpiryFailOpenRatherThanClaimReconciliation() {
        AtomicLong clock = new AtomicLong();
        DestinationWindow window = new DestinationWindow(1, 2, 1000, clock::get);
        HolderRef ender = new HolderRef.EnderChestHolder(new UUID(0, 1), "A", 0);
        assertTrue(window.begin("id", player(0), ender));
        assertFalse(window.begin("other", player(0), ender));
        window.observe("id", new HolderRef.PlayerHolder(new UUID(0, 2), "B", 0));
        assertTrue(window.finish("id").incomplete());
        window.begin("id", player(0), ender);
        clock.set(1001);
        assertTrue(window.finish("id").incomplete());
    }
    @Test void observesHandoffsWithoutChangingCanonicalPolicy() {
        DestinationWindow window = new DestinationWindow(2, 3, 1000, () -> 0);
        PresenceStore store = new DestinationTrackingStore(new InMemoryPresenceStore(), window);
        ItemIdentity id = new ItemIdentity("ZI-test", 1, null, null, null, null, ItemOrigin.LEGACY_IMPORT);
        HolderRef ender = new HolderRef.EnderChestHolder(new UUID(0, 1), "A", 0);
        window.begin(id.id(), player(0), ender);
        store.commitHandoff(id, player(0), PresenceState.LIVE_CONFIRMED);
        store.observe(id, ender, PresenceState.PERSISTED_CONTAINER);
        assertEquals(player(0), store.getCanonical(id).orElseThrow().holder());
        assertEquals(2, window.finish(id.id()).holders().size());
        assertFalse(HolderRef.sameOwner(player(0), ender));
        assertTrue(HolderRef.sameOwner(ender, new HolderRef.EnderChestHolder(new UUID(0, 1), "renamed", 26)));
    }
}
