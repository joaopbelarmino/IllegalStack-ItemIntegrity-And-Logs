package main.java.me.dniym.identity.listeners;

import io.papermc.paper.event.player.PlayerInventorySlotChangeEvent;
import main.java.me.dniym.IllegalStack;
import main.java.me.dniym.identity.*;
import main.java.me.dniym.identity.config.ItemIntegrityConfig;
import main.java.me.dniym.identity.conflict.ConflictDetector;
import main.java.me.dniym.identity.presence.*;
import main.java.me.dniym.utils.Scheduler;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.junit.jupiter.api.Test;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class SlotObservationTest {
    private final IdentityService identities = mock(IdentityService.class);
    private final MigrationService migration = mock(MigrationService.class);
    private final ConflictDetector detector = mock(ConflictDetector.class);
    private final PresenceStore store = new InMemoryPresenceStore();
    private final ItemIdentity id = new ItemIdentity("ZI-slot", 1, null, null, null, null, ItemOrigin.LEGACY_IMPORT);
    private final Map<ItemStack, ItemIdentity> known = new IdentityHashMap<>();
    private final ItemStack[] physical = new ItemStack[41];
    private final Player player = mock(Player.class);
    private final PlayerInventory inventory = mock(PlayerInventory.class);
    private final Inventory ender = mock(Inventory.class);
    private final InventoryView view = mock(InventoryView.class);

    @Test void destinationFirstMovesDoNotScheduleDuplicateRevalidation() {
        try (var scheduler = mockStatic(Scheduler.class); var bukkit = mockStatic(Bukkit.class)) {
            var listener = listener();
            Material[] materials = {Material.IRON_BOOTS, Material.IRON_LEGGINGS, Material.ELYTRA,
                    Material.IRON_HELMET, Material.SHIELD, Material.TOTEM_OF_UNDYING, Material.DIAMOND_PICKAXE};
            int[] destinations = {36, 37, 38, 39, 40, 40, 9};
            int[] raw = {8, 7, 6, 5, 45, 45, 9};
            for (int i = 0; i < materials.length; i++) {
                ItemStack stack = item(materials[i]);
                physical[0] = stack;
                listener.onSlotChange(event(0, 36, null));
                physical[0] = null; physical[destinations[i]] = stack;
                listener.onSlotChange(event(destinations[i], raw[i], null));
                assertEquals(destinations[i], ((HolderRef.PlayerHolder) store.getCanonical(id).orElseThrow().holder()).slot());
                listener.onSlotChange(event(0, 36, stack));
                physical[destinations[i]] = null;
                listener.onSlotChange(event(destinations[i], raw[i], stack));
            }
            verify(detector, never()).handlePlayerInventoryConflict(any(), anyInt(), any(), any(), any(), any());
            verify(inventory, never()).getContents();
        }
    }

    @Test void changedViewRejectsOutOfBoundsRawSlotWithoutReadingIt() {
        try (var scheduler = mockStatic(Scheduler.class); var bukkit = mockStatic(Bukkit.class)) {
            var listener = listener();
            when(view.countSlots()).thenReturn(20);
            listener.onSlotChange(event(0, 36, null));
            listener.onSlotChange(event(0, -1, null));
            verify(view, never()).getInventory(anyInt());
            verifyNoInteractions(migration, identities, detector);
        }
    }

    @Test void ordinaryStackableChangesDoNotReadPdcOrScheduleWork() {
        try (var scheduler = mockStatic(Scheduler.class); var bukkit = mockStatic(Bukkit.class)) {
            var listener = listener();
            Material material = spy(Material.COBBLESTONE);
            doReturn(64).when(material).getMaxStackSize();
            ItemStack stack = mock(ItemStack.class);
            when(stack.getType()).thenReturn(material);
            physical[0] = stack;
            for (int i = 0; i < 100; i++) listener.onSlotChange(event(0, 36, stack));
            verifyNoInteractions(identities, migration);
            verify(stack, never()).getPersistentDataContainer();
            scheduler.verify(() -> Scheduler.runTaskLater(any(), any(Runnable.class), anyLong(), any(org.bukkit.entity.Entity.class)), never());
        }
    }

    @Test void shieldTotemArmorAndSlotMovesObservePhysicalSlotWithoutFullScan() {
        try (var scheduler = mockStatic(Scheduler.class); var bukkit = mockStatic(Bukkit.class)) {
            var listener = listener();
            for (Material material : new Material[]{Material.SHIELD, Material.TOTEM_OF_UNDYING, Material.ELYTRA}) {
                ItemStack stack = item(material);
                physical[0] = stack;
                listener.onSlotChange(event(0, 36, null));
                physical[0] = null; physical[40] = stack;
                listener.onSlotChange(event(0, 36, stack));
                listener.onSlotChange(event(40, 45, null));
                assertEquals(40, ((HolderRef.PlayerHolder) store.getCanonical(id).orElseThrow().holder()).slot());
                physical[40] = null; physical[8] = stack;
                listener.onSlotChange(event(40, 45, stack));
                listener.onSlotChange(event(8, 44, null));
                assertEquals(8, ((HolderRef.PlayerHolder) store.getCanonical(id).orElseThrow().holder()).slot());
                physical[8] = null;
                listener.onSlotChange(event(8, 44, stack));
            }
            verify(inventory, never()).getContents();
            verify(detector, never()).handlePlayerInventoryConflict(any(), anyInt(), any(), any(), any(), any());
        }
    }

    @Test void actualTwoSlotsStillReachDetectorAndScannerDoesNotCreateAliasConflict() {
        try (var scheduler = mockStatic(Scheduler.class); var bukkit = mockStatic(Bukkit.class)) {
            var listener = listener();
            ItemStack first = item(Material.SHIELD), second = item(Material.SHIELD);
            physical[40] = first;
            listener.onSlotChange(event(40, 45, null));
            listener.scanPlayer(player, PresenceState.LIVE_CONFIRMED);
            verify(detector, never()).handlePlayerInventoryConflict(any(), anyInt(), any(), any(), any(), any());
            physical[0] = second;
            listener.onSlotChange(event(0, 36, null));
            verify(detector).handlePlayerInventoryConflict(eq(player), eq(0), eq(second), eq(id), any(), any());
        }
    }

    @Test void rightClickArmorEquipUsesArmorSlotInsteadOfMainHandAlias() {
        try (var scheduler = mockStatic(Scheduler.class); var bukkit = mockStatic(Bukkit.class)) {
            var listener = listener();
            Material[] armor = {Material.IRON_BOOTS, Material.IRON_LEGGINGS, Material.ELYTRA, Material.IRON_HELMET};
            for (int i = 0; i < armor.length; i++) {
                int slot = 36 + i;
                ItemStack stack = item(armor[i]);
                physical[0] = stack;
                listener.onSlotChange(event(0, 36, null));
                physical[0] = null;
                physical[slot] = stack;
                listener.onSlotChange(event(0, 36, stack));
                listener.onSlotChange(event(slot, 8 - i, null));
                assertEquals(slot, ((HolderRef.PlayerHolder) store.getCanonical(id).orElseThrow().holder()).slot());
                physical[slot] = null;
                listener.onSlotChange(event(slot, 8 - i, stack));
            }
            verify(detector, never()).handlePlayerInventoryConflict(any(), anyInt(), any(), any(), any(), any());
        }
    }

    @Test void lowerSlotCopyCannotDisplaceExistingCanonicalInPlayerOrEnder() {
        try (var scheduler = mockStatic(Scheduler.class); var bukkit = mockStatic(Bukkit.class)) {
            var listener = listener();
            ItemStack original = item(Material.SHIELD), copy = item(Material.SHIELD);
            physical[40] = original; physical[0] = copy;
            HolderRef official = new HolderRef.PlayerHolder(player.getUniqueId(), "tester", 40);
            store.commitHandoff(id, official, PresenceState.LIVE_CONFIRMED);
            listener.scanPlayer(player, PresenceState.LIVE_CONFIRMED);
            assertEquals(official, store.getCanonical(id).orElseThrow().holder());
            verify(detector).handlePlayerInventoryConflict(eq(player), eq(0), eq(copy), eq(id),
                    argThat(record -> record.holder().equals(official)), any());
            ItemStack[] enderItems = new ItemStack[27]; enderItems[26] = original; enderItems[0] = copy;
            HolderRef enderOfficial = new HolderRef.EnderChestHolder(player.getUniqueId(), "tester", 26);
            store.commitHandoff(id, enderOfficial, PresenceState.PERSISTED_CONTAINER);
            listener.scanEnderChest(player, PresenceState.PERSISTED_CONTAINER, enderItems);
            assertEquals(enderOfficial, store.getCanonical(id).orElseThrow().holder());
            verify(detector).handleStoredInventoryConflict(eq(copy), eq(id),
                    argThat(record -> record.holder().equals(enderOfficial)), any());
        }
    }

    @Test void containerRawSlotIsIgnoredAndEventCloneIsNeverMigrated() {
        try (var scheduler = mockStatic(Scheduler.class); var bukkit = mockStatic(Bukkit.class)) {
            var listener = listener();
            ItemStack stack = item(Material.ELYTRA);
            physical[0] = stack;
            when(view.getInventory(0)).thenReturn(ender);
            listener.onSlotChange(event(0, 0, null));
            verifyNoInteractions(migration);
            var event = event(0, 36, null);
            listener.onSlotChange(event);
            verify(event, never()).getNewItemStack();
            verify(migration).ensureIdentity(eq(stack), any());
            verify(inventory, never()).setItem(anyInt(), any());
        }
    }

    private IdentityPlayerInventoryListener listener() {
        ItemIntegrityConfig config = mock(ItemIntegrityConfig.class);
        when(config.slotEventsEnabled()).thenReturn(true);
        when(config.reconciliationPeriodTicks()).thenReturn(20L);
        when(config.externalCustodyMissingConfirmDelayTicks()).thenReturn(10L);
        when(player.getUniqueId()).thenReturn(new UUID(0, 1));
        when(player.getName()).thenReturn("tester");
        when(player.isOnline()).thenReturn(true);
        when(player.getInventory()).thenReturn(inventory);
        when(player.getEnderChest()).thenReturn(ender);
        when(player.getOpenInventory()).thenReturn(view);
        when(view.countSlots()).thenReturn(46);
        when(view.getInventory(anyInt())).thenReturn(inventory);
        when(inventory.getSize()).thenReturn(41);
        when(inventory.getContents()).thenAnswer(c -> physical.clone());
        when(inventory.getItem(anyInt())).thenAnswer(c -> physical[c.getArgument(0)]);
        when(ender.getSize()).thenReturn(27);
        when(ender.getContents()).thenReturn(new ItemStack[27]);
        when(migration.ensureIdentity(any(), any())).thenAnswer(c -> {
            ItemIdentity identity = known.get(c.getArgument(0));
            return identity == null ? null : new MigrationResult(identity, false);
        });
        return new IdentityPlayerInventoryListener(mock(IllegalStack.class, RETURNS_DEEP_STUBS), migration, store,
                null, detector, config, mock(VirtualCustodyService.class), identities);
    }
    private ItemStack item(Material material) {
        Material type = spy(material);
        doReturn(1).when(type).getMaxStackSize();
        ItemStack stack = mock(ItemStack.class);
        when(stack.getType()).thenReturn(type);
        when(identities.readIdentity(stack)).thenReturn(id);
        known.put(stack, id);
        return stack;
    }
    private PlayerInventorySlotChangeEvent event(int slot, int rawSlot, ItemStack previous) {
        PlayerInventorySlotChangeEvent event = mock(PlayerInventorySlotChangeEvent.class);
        when(event.getPlayer()).thenReturn(player);
        when(event.getSlot()).thenReturn(slot);
        when(event.getRawSlot()).thenReturn(rawSlot);
        when(event.getOldItemStack()).thenReturn(previous);
        return event;
    }
}
