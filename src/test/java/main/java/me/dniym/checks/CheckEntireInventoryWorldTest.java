package main.java.me.dniym.checks;

import main.java.me.dniym.enums.Protections;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.*;

class CheckEntireInventoryWorldTest {

    private final boolean enchant = Protections.FixIllegalEnchantmentLevels.isEnabled();
    private final boolean attrib = Protections.RemoveCustomAttributes.isEnabled();

    @AfterEach
    void restore() {
        Protections.FixIllegalEnchantmentLevels.setEnabled(enchant);
        Protections.RemoveCustomAttributes.setEnabled(attrib);
        Protections.DisableInWorlds.getTxtSet().remove("arena");
    }

    private static Inventory inventory(World world, int items) {
        ItemStack[] contents = new ItemStack[items];
        for (int i = 0; i < items; i++) {
            ItemStack is = mock(ItemStack.class, withSettings().stubOnly());
            when(is.getType()).thenReturn(Material.AIR);
            when(is.getEnchantments()).thenReturn(Map.of());
            when(is.getAmount()).thenReturn(1);
            when(is.getMaxStackSize()).thenReturn(64);
            contents[i] = is;
        }
        Inventory inv = mock(Inventory.class);
        when(inv.getContents()).thenReturn(contents);
        when(inv.getStorageContents()).thenReturn(contents);
        when(inv.getLocation()).thenAnswer(a -> new Location(world, 0, 64, 0));
        return inv;
    }

    @Test
    void resolvesTheWorldOncePerScanAndOnlyWhileScanning() {
        Protections.FixIllegalEnchantmentLevels.setEnabled(true);
        Protections.RemoveCustomAttributes.setEnabled(true);
        World world = mock(World.class);
        when(world.getName()).thenReturn("world");
        Inventory inv = inventory(world, 5);

        assertFalse(CheckUtils.CheckEntireInventory(inv));
        verify(inv, times(1)).getLocation();

        // Outside a scan every call resolves again, as before.
        Protections.FixIllegalEnchantmentLevels.isEnabled(inv);
        Protections.FixIllegalEnchantmentLevels.isEnabled(inv);
        verify(inv, times(3)).getLocation();
    }

    @Test
    void cachedWorldStillHonoursDisableInWorlds() {
        Protections.FixIllegalEnchantmentLevels.setEnabled(true);
        Protections.DisableInWorlds.getTxtSet().add("arena");
        World arena = mock(World.class);
        when(arena.getName()).thenReturn("arena");
        Inventory inv = inventory(arena, 1);

        Protections.beginScan(inv);
        try {
            assertFalse(Protections.FixIllegalEnchantmentLevels.isEnabled(inv));
            assertFalse(Protections.FixIllegalEnchantmentLevels.isEnabled(inv));
        } finally {
            Protections.endScan();
        }
        verify(inv, times(1)).getLocation();
    }

    @Test
    void noWorldLookupWhenEveryProtectionIsOff() {
        Protections.FixIllegalEnchantmentLevels.setEnabled(false);
        Protections.RemoveCustomAttributes.setEnabled(false);
        Inventory inv = inventory(mock(World.class), 3);

        assertFalse(CheckUtils.CheckEntireInventory(inv));
        verify(inv, never()).getLocation();
    }
}
