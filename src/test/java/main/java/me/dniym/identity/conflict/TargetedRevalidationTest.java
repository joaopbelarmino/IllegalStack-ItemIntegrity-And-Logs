package main.java.me.dniym.identity.conflict;

import main.java.me.dniym.IllegalStack;
import main.java.me.dniym.identity.*;
import main.java.me.dniym.identity.config.ItemIntegrityConfig;
import main.java.me.dniym.identity.presence.*;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class TargetedRevalidationTest {
    private final ItemIdentity id = new ItemIdentity("ZI-test", 1, null, null, null, null, ItemOrigin.LEGACY_IMPORT);
    private final IdentityService identities = mock(IdentityService.class);
    private final DestinationWindow window = new DestinationWindow(10, 4, 30_000, System::currentTimeMillis);
    private final PresenceStore store = new DestinationTrackingStore(new InMemoryPresenceStore(), window);
    private final ConflictDetector detector = new ConflictDetector(mock(IllegalStack.class), mock(ItemIntegrityConfig.class),
            identities, null, mock(DiscordWebhookNotifier.class), mock(CaseFileLogger.class), store);
    private final Map<UUID, Player> players = new HashMap<>();

    @Test void playerAndEnderAreIndependentPhysicalLocations() {
        try (var bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(() -> Bukkit.getPlayer(any(UUID.class))).thenAnswer(c -> players.get(c.getArgument(0)));
            Player owner = player(1);
            ItemStack first = item(), second = item();
            contents(owner.getInventory(), first);
            contents(owner.getEnderChest(), second);
            var canonical = record(new HolderRef.PlayerHolder(owner.getUniqueId(), "A", 0));
            var other = record(new HolderRef.EnderChestHolder(owner.getUniqueId(), "A", 0));
            assertEquals(ConflictDetector.RevalidationStatus.CONFIRMED_DUPLICATE, revalidate(canonical, other).status());
            verify(owner.getEnderChest(), times(1)).getContents();
        }
    }

    @Test void legitimateTransferReconcilesButOfflineOwnerIsAmbiguous() {
        try (var bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(() -> Bukkit.getPlayer(any(UUID.class))).thenAnswer(c -> players.get(c.getArgument(0)));
            Player owner = player(1);
            contents(owner.getInventory()); contents(owner.getEnderChest(), item());
            var canonical = record(new HolderRef.PlayerHolder(owner.getUniqueId(), "A", 0));
            var ender = record(new HolderRef.EnderChestHolder(owner.getUniqueId(), "A", 0));
            var result = revalidate(canonical, ender);
            assertEquals(ConflictDetector.RevalidationStatus.TRANSIENT_RECONCILED, result.status());
            assertEquals(ender.holder(), result.singlePresence().orElseThrow().holder());
            when(owner.isOnline()).thenReturn(false);
            assertEquals(ConflictDetector.RevalidationStatus.AMBIGUOUS_REVALIDATION, revalidate(canonical, ender).status());
        }
    }

    @Test void followsObservedThirdDestinationWithoutRescanningOriginalPlayerTwice() {
        try (var bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(() -> Bukkit.getPlayer(any(UUID.class))).thenAnswer(c -> players.get(c.getArgument(0)));
            Player a = player(1), b = player(2), c = player(3);
            contents(a.getInventory(), item()); contents(b.getInventory()); contents(c.getEnderChest(), item());
            var canonical = record(new HolderRef.PlayerHolder(a.getUniqueId(), "A", 0));
            var original = record(new HolderRef.PlayerHolder(b.getUniqueId(), "B", 0));
            var moved = new HolderRef.EnderChestHolder(c.getUniqueId(), "C", 0);
            window.begin(id.id(), canonical.holder(), original.holder());
            store.observe(id, moved, PresenceState.PERSISTED_CONTAINER);
            var result = detector.revalidateCurrentPhysicalState(id, canonical, original, System.currentTimeMillis());
            assertEquals(ConflictDetector.RevalidationStatus.CONFIRMED_DUPLICATE, result.status());
            assertEquals(moved, result.conflicting().orElseThrow().holder());
            verify(a.getInventory(), times(1)).getContents();
        }
    }

    @Test void samePlayerTwoSlotsRemainDuplicateAndGenericAliasReadsOnlyOnce() {
        try (var bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(() -> Bukkit.getPlayer(any(UUID.class))).thenAnswer(c -> players.get(c.getArgument(0)));
            Player owner = player(1);
            contents(owner.getInventory(), item(), item());
            var first = record(new HolderRef.PlayerHolder(owner.getUniqueId(), "A", 0));
            var second = record(new HolderRef.PlayerHolder(owner.getUniqueId(), "A", 1));
            assertEquals(ConflictDetector.RevalidationStatus.CONFIRMED_DUPLICATE, revalidate(first, second).status());
            verify(owner.getInventory(), times(1)).getContents();
        }
    }

    @Test void finalDeleteRequiresUnchangedTargetAndCanonicalStillPresent() {
        try (var bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(() -> Bukkit.getPlayer(any(UUID.class))).thenAnswer(c -> players.get(c.getArgument(0)));
            Player owner = player(1);
            ItemStack canonicalItem = item(), target = item();
            contents(owner.getInventory(), canonicalItem);
            contents(owner.getEnderChest(), target);
            when(owner.getEnderChest().getSize()).thenReturn(27);
            when(owner.getEnderChest().getItem(0)).thenReturn(target);
            var canonical = record(new HolderRef.PlayerHolder(owner.getUniqueId(), "A", 0));
            var ender = record(new HolderRef.EnderChestHolder(owner.getUniqueId(), "A", 0));
            assertFalse(detector.removeIfStillConflicting(id, canonical, ender, item()));
            contents(owner.getInventory());
            assertFalse(detector.removeIfStillConflicting(id, canonical, ender, target));
            verify(owner.getEnderChest(), never()).setItem(anyInt(), any());
            contents(owner.getInventory(), canonicalItem);
            assertTrue(detector.removeIfStillConflicting(id, canonical, ender, target));
            verify(owner.getEnderChest()).setItem(0, null);
            verify(owner.getInventory(), never()).setItem(anyInt(), any());
        }
    }

    @Test void unavailableContainerDoesNotLoadChunkAndCannotConfirm() {
        try (var bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(() -> Bukkit.getPlayer(any(UUID.class))).thenAnswer(c -> players.get(c.getArgument(0)));
            org.bukkit.World world = mock(org.bukkit.World.class);
            bukkit.when(() -> Bukkit.getWorld("world")).thenReturn(world);
            Player owner = player(1);
            contents(owner.getInventory(), item());
            var container = record(new HolderRef.ContainerHolder(HolderType.CHEST, "world", 100, 64, 200, 0));
            var player = record(new HolderRef.PlayerHolder(owner.getUniqueId(), "A", 0));
            assertEquals(ConflictDetector.RevalidationStatus.AMBIGUOUS_REVALIDATION, revalidate(container, player).status());
            verify(world, never()).getBlockAt(anyInt(), anyInt(), anyInt());
            verify(world, never()).loadChunk(anyInt(), anyInt());
        }
    }

    @Test void staleMainHandObservationAndCurrentOffhandReconcileToOneCopy() {
        try (var bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(() -> Bukkit.getPlayer(any(UUID.class))).thenAnswer(c -> players.get(c.getArgument(0)));
            Player owner = player(1);
            ItemStack[] current = new ItemStack[41]; current[40] = item();
            when(owner.getInventory().getContents()).thenReturn(current);
            var before = record(new HolderRef.PlayerHolder(owner.getUniqueId(), "A", 0));
            var after = record(new HolderRef.PlayerHolder(owner.getUniqueId(), "A", 40));
            var result = revalidate(before, after);
            assertEquals(ConflictDetector.RevalidationStatus.TRANSIENT_RECONCILED, result.status());
            assertEquals(after.holder(), result.singlePresence().orElseThrow().holder());
        }
    }

    @Test void deleteWaitsForCommitAndRevalidatesAgainBeforeRemoving() {
        for (boolean commitSucceeds : new boolean[]{false, true}) {
            try (var bukkit = mockStatic(Bukkit.class); var scheduler = mockStatic(main.java.me.dniym.utils.Scheduler.class)) {
                bukkit.when(() -> Bukkit.getPlayer(any(UUID.class))).thenAnswer(c -> players.get(c.getArgument(0)));
                Queue<Runnable> jobs = new ArrayDeque<>();
                scheduler.when(() -> main.java.me.dniym.utils.Scheduler.runTaskLater(any(), any(Runnable.class), anyLong()))
                        .thenAnswer(call -> { jobs.add(call.getArgument(1)); return null; });
                Player owner = player(1);
                ItemStack canonicalItem = item(), target = item();
                when(target.getType()).thenReturn(org.bukkit.Material.SHIELD);
                when(target.clone()).thenReturn(target);
                when(target.serialize()).thenReturn(Map.of("type", "SHIELD"));
                contents(owner.getInventory(), canonicalItem); contents(owner.getEnderChest(), target);
                when(owner.getEnderChest().getItem(0)).thenReturn(target);
                when(owner.getEnderChest().getSize()).thenReturn(27);
                var canonical = record(new HolderRef.PlayerHolder(owner.getUniqueId(), "A", 0));
                var other = record(new HolderRef.EnderChestHolder(owner.getUniqueId(), "A", 0));
                ItemIntegrityConfig config = mock(ItemIntegrityConfig.class);
                when(config.conflictMode()).thenReturn(ConflictMode.DELETE);
                when(config.conflictRevalidationEnabled()).thenReturn(true);
                when(config.conflictRevalidationDelayTicks()).thenReturn(3L);
                when(config.revalidationPendingLimit()).thenReturn(100);
                var db = mock(main.java.me.dniym.identity.audit.DatabaseService.class);
                var commit = new java.util.concurrent.CompletableFuture<Boolean>();
                when(db.writeAndConfirm(any())).thenReturn(commit);
                var detector = new ConflictDetector(mock(IllegalStack.class), config, identities, db,
                        mock(DiscordWebhookNotifier.class), mock(CaseFileLogger.class), store);
                detector.handleStoredInventoryConflict(target, id, canonical, other);
                assertEquals(1, jobs.size());
                jobs.remove().run();
                var saved = org.mockito.ArgumentCaptor.forClass(main.java.me.dniym.identity.audit.AuditTask.class);
                verify(db).writeAndConfirm(saved.capture());
                var snapshot = ((main.java.me.dniym.identity.audit.AuditTask.PersistCase) saved.getValue()).snapshot();
                assertNotNull(snapshot.conflictingItemSnapshot());
                verify(owner.getEnderChest(), never()).setItem(anyInt(), any());
                assertTrue(jobs.isEmpty());
                // The canonical can disappear while the async commit is pending.
                contents(owner.getInventory());
                commit.complete(commitSucceeds);
                if (commitSucceeds) {
                    assertEquals(1, jobs.size());
                    jobs.remove().run();
                } else assertTrue(jobs.isEmpty());
                verify(owner.getEnderChest(), never()).setItem(anyInt(), any());
            }
        }
    }

    private ConflictDetector.TargetedRevalidation revalidate(PresenceRecord a, PresenceRecord b) {
        window.begin(id.id(), a.holder(), b.holder());
        return detector.revalidateCurrentPhysicalState(id, a, b, System.currentTimeMillis());
    }
    private PresenceRecord record(HolderRef holder) { return new PresenceRecord(id, holder, PresenceState.LIVE_CONFIRMED, 1, 1); }
    private ItemStack item() {
        ItemStack stack = mock(ItemStack.class);
        when(identities.readIdentity(stack)).thenReturn(id);
        return stack;
    }
    private Player player(int number) {
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(new UUID(0, number));
        when(player.getName()).thenReturn(number == 1 ? "A" : number == 2 ? "B" : "C");
        when(player.isOnline()).thenReturn(true);
        PlayerInventory inventory = mock(PlayerInventory.class);
        Inventory ender = mock(Inventory.class);
        when(player.getInventory()).thenReturn(inventory);
        when(player.getEnderChest()).thenReturn(ender);
        players.put(player.getUniqueId(), player);
        return player;
    }
    private void contents(Inventory inventory, ItemStack... items) {
        ItemStack[] all = new ItemStack[inventory instanceof PlayerInventory ? 41 : 27];
        System.arraycopy(items, 0, all, 0, items.length);
        when(inventory.getContents()).thenReturn(all);
    }
}
