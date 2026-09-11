package main.java.me.dniym.identity.listeners;

import main.java.me.dniym.IllegalStack;
import main.java.me.dniym.identity.*;
import main.java.me.dniym.identity.config.ItemIntegrityConfig;
import main.java.me.dniym.identity.presence.*;
import main.java.me.dniym.utils.Scheduler;
import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class InventoryWorkBatchTest {
    @Test void pickupRequestsCoalesceAndQuitCancelsPendingWork() {
        try (MockedStatic<Scheduler> scheduler = mockStatic(Scheduler.class)) {
            List<Runnable> jobs = jobs(scheduler);
            var scanner = spy(scanner(mock(VirtualCustodyService.class)));
            Player player = player();
            doNothing().when(scanner).scanPlayer(any(), any());
            for (int i = 0; i < 30; i++) scanner.scheduleScan(player, PresenceState.LIVE_CONFIRMED);
            assertEquals(1, jobs.size());
            jobs.removeFirst().run();
            verify(scanner, times(1)).scanPlayer(player, PresenceState.LIVE_CONFIRMED);
            scanner.scheduleScan(player, PresenceState.LIVE_CONFIRMED);
            PlayerQuitEvent quit = mock(PlayerQuitEvent.class);
            when(quit.getPlayer()).thenReturn(player);
            scanner.onQuit(quit);
            jobs.removeFirst().run();
            verify(scanner, times(1)).scanPlayer(player, PresenceState.LIVE_CONFIRMED);
        }
    }

    @Test void commandChecksBatchIdsButDoNotShortenDelayForLaterTicks() {
        try (MockedStatic<Scheduler> scheduler = mockStatic(Scheduler.class);
             MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            AtomicInteger tick = new AtomicInteger(1);
            bukkit.when(Bukkit::getCurrentTick).thenAnswer(call -> tick.get());
            List<Runnable> jobs = jobs(scheduler);
            VirtualCustodyService custody = mock(VirtualCustodyService.class);
            var scanner = scanner(custody);
            Player player = player();
            for (int i = 0; i < 20; i++) scanner.scheduleExternalCustodyCheck(player, identity("ZI-" + i));
            scanner.scheduleExternalCustodyCheck(player, identity("ZI-0"));
            assertEquals(1, jobs.size());
            tick.incrementAndGet();
            scanner.scheduleExternalCustodyCheck(player, identity("ZI-later"));
            assertEquals(2, jobs.size());
            jobs.removeFirst().run();
            verify(custody).tryCommitMissingPlayerItemsToExternal(argThat(ids -> ids.size() == 20), eq(player));
            jobs.removeFirst().run();
            verify(custody).tryCommitMissingPlayerItemsToExternal(argThat(ids -> ids.size() == 1), eq(player));
        }
    }

    @Test void repeatedClickAndDragShareBeforeAndAfterSnapshots() {
        try (MockedStatic<Scheduler> scheduler = mockStatic(Scheduler.class);
             var registries = main.java.me.dniym.identity.TestMenuRegistry.install()) {
            List<Runnable> jobs = jobs(scheduler);
            var scanner = mock(IdentityPlayerInventoryListener.class);
            IdentityService identities = mock(IdentityService.class);
            PresenceStore store = spy(new InMemoryPresenceStore());
            var listener = new ItemIntegrityLifecycleListener(mock(IllegalStack.class, RETURNS_DEEP_STUBS),
                    identities, mock(MigrationService.class), store,
                    null, null, scanner, mock(VirtualCustodyService.class));
            Player player = player();
            Inventory inventory = mock(Inventory.class);
            ItemStack first = mock(ItemStack.class), second = mock(ItemStack.class);
            org.bukkit.Material shield = spy(org.bukkit.Material.SHIELD);
            doReturn(false).when(shield).isAir();
            when(first.getType()).thenReturn(shield);
            when(second.getType()).thenReturn(shield);
            ItemIdentity duplicate = identity("ZI-two-slots");
            when(identities.readIdentity(first)).thenReturn(duplicate);
            when(identities.readIdentity(second)).thenReturn(duplicate);
            ItemStack[] contents = new ItemStack[54];
            contents[0] = first;
            contents[1] = second;
            when(inventory.getContents()).thenReturn(contents);
            InventoryType chest = InventoryType.CHEST;
            when(inventory.getType()).thenReturn(chest);
            InventoryClickEvent click = mock(InventoryClickEvent.class, RETURNS_DEEP_STUBS);
            when(click.getWhoClicked()).thenReturn(player);
            when(click.getView().getTopInventory()).thenReturn(inventory);
            InventoryDragEvent drag = mock(InventoryDragEvent.class, RETURNS_DEEP_STUBS);
            when(drag.getWhoClicked()).thenReturn(player);
            when(drag.getView().getTopInventory()).thenReturn(inventory);
            for (int i = 0; i < 10; i++) { listener.onInventoryClick(click); listener.onInventoryDrag(drag); }
            assertEquals(1, jobs.size());
            verify(inventory, times(1)).getContents();
            jobs.removeFirst().run();
            verify(inventory, times(2)).getContents();
            verify(scanner, times(1)).scanPlayer(player, PresenceState.LIVE_CONFIRMED);
            String viewerId = player.getUniqueId().toString();
            verify(store).observe(eq(duplicate), eq(new HolderRef.VirtualHolder(HolderType.VIRTUAL_INVENTORY,
                    "chest", viewerId, 0)), eq(PresenceState.LIVE_CONFIRMED));
            verify(store).observe(eq(duplicate), eq(new HolderRef.VirtualHolder(HolderType.VIRTUAL_INVENTORY,
                    "chest", viewerId, 1)), eq(PresenceState.LIVE_CONFIRMED));
        }
    }

    @Test void bulkCustodyReadsPhysicalInventoryOnceAndKeepsOffhandAndChangedCanonical() {
        Player player = player();
        PlayerInventory inventory = mock(PlayerInventory.class);
        when(player.getInventory()).thenReturn(inventory);
        ItemStack offhand = mock(ItemStack.class);
        ItemStack[] contents = new ItemStack[41];
        contents[40] = offhand;
        when(inventory.getContents()).thenReturn(contents);
        IdentityService identities = mock(IdentityService.class);
        ItemIdentity retained = identity("ZI-offhand"), gone = identity("ZI-gone"), moved = identity("ZI-moved");
        when(identities.readIdentity(offhand)).thenReturn(retained);
        PresenceStore store = new InMemoryPresenceStore();
        var owner = new HolderRef.PlayerHolder(player.getUniqueId(), "test", 40);
        store.commitHandoff(retained, owner, PresenceState.LIVE_CONFIRMED);
        store.commitHandoff(gone, owner, PresenceState.LIVE_CONFIRMED);
        var other = new HolderRef.PlayerHolder(UUID.randomUUID(), "other", 0);
        store.commitHandoff(moved, other, PresenceState.LIVE_CONFIRMED);
        int changed = new VirtualCustodyService(identities, store)
                .tryCommitMissingPlayerItemsToExternal(List.of(retained, gone, moved), player);
        assertEquals(1, changed);
        assertEquals(owner, store.getCanonical(retained).orElseThrow().holder());
        assertEquals(other, store.getCanonical(moved).orElseThrow().holder());
        assertEquals(HolderType.EXTERNAL_PLUGIN, store.getCanonical(gone).orElseThrow().holder().type());
        verify(inventory, times(1)).getContents();
    }

    private static IdentityPlayerInventoryListener scanner(VirtualCustodyService custody) {
        ItemIntegrityConfig config = mock(ItemIntegrityConfig.class);
        when(config.externalCustodyMissingConfirmDelayTicks()).thenReturn(10L);
        return new IdentityPlayerInventoryListener(mock(IllegalStack.class, RETURNS_DEEP_STUBS),
                mock(MigrationService.class), new InMemoryPresenceStore(), null, null, config, custody);
    }
    private static Player player() {
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        when(player.isOnline()).thenReturn(true);
        return player;
    }
    private static ItemIdentity identity(String id) {
        return new ItemIdentity(id, 1234, "world", 0, 64, 0, ItemOrigin.LEGACY_IMPORT);
    }
    private static List<Runnable> jobs(MockedStatic<Scheduler> scheduler) {
        List<Runnable> jobs = new ArrayList<>();
        scheduler.when(() -> Scheduler.runTaskLater(any(), any(Runnable.class), anyLong(), any(Entity.class)))
                .thenAnswer(call -> { jobs.add(call.getArgument(1)); return null; });
        return jobs;
    }
}
