package main.java.me.dniym.identity;

import main.java.me.dniym.identity.config.ItemIntegrityConfig;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.block.TileState;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.junit.jupiter.api.Test;

import java.time.ZoneId;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class IdentityPdcTest {
    private final Map<NamespacedKey, Object> data = new HashMap<>();
    private final PersistentDataContainer pdc = mock(PersistentDataContainer.class, call -> {
        NamespacedKey key = call.getArguments().length > 0 && call.getArgument(0) instanceof NamespacedKey k ? k : null;
        return switch (call.getMethod().getName()) {
            case "has" -> data.containsKey(key);
            case "get" -> data.get(key);
            case "set" -> { data.put(key, call.getArgument(2)); yield null; }
            case "remove" -> { data.remove(key); yield null; }
            default -> RETURNS_DEFAULTS.answer(call);
        };
    });

    @Test void stackableMaterialsAreRejectedWithoutReadingAnyPdcOrMeta() {
        ItemStack item = mock(ItemStack.class);
        Material material = spy(Material.COBBLESTONE);
        doReturn(64).when(material).getMaxStackSize();
        when(item.getType()).thenReturn(material);
        assertFalse(TrackabilityPolicy.isTrackable(item));
        verify(item, never()).getPersistentDataContainer();
        verify(item, never()).getItemMeta();
    }

    @Test void existingIdentityIsReadWithoutMetaCloneOrRewrite() {
        data.put(key("item_id"), "ZI-existing");
        data.put(key("registered_at"), 1234L);
        data.put(key("origin"), "LEGACY_IMPORT");
        data.put(key("registered_world"), "world");
        data.put(key("registered_x"), -7);
        data.put(key("registered_y"), 80);
        data.put(key("registered_z"), 9);
        ItemStack item = item(Material.SHIELD);
        var result = new MigrationService(service()).ensureIdentity(item, null);
        assertFalse(result.freshlyAssigned());
        assertEquals("ZI-existing", result.identity().id());
        assertEquals(1234L, result.identity().registeredAtEpochMs());
        assertEquals(-7, result.identity().registeredX());
        verify(item, times(1)).getPersistentDataContainer();
        verify(item, never()).getItemMeta();
        verify(item, never()).editPersistentDataContainer(any());
    }

    @Test void exemptionStillWinsAndCannotAssignIdentity() {
        data.put(key("integrity_exempt"), 1);
        ItemStack item = item(Material.ELYTRA);
        assertFalse(TrackabilityPolicy.isTrackable(item));
        assertNull(new MigrationService(service()).ensureIdentity(item, null));
        verify(item, never()).editPersistentDataContainer(any());
    }

    @Test void freshIdentityUsesEditPdcAndPreservesUnrelatedProperties() {
        NamespacedKey custom = new NamespacedKey("shop", "custom");
        data.put(custom, "preserved");
        ItemStack item = item(Material.TOTEM_OF_UNDYING);
        var result = new MigrationService(service()).ensureIdentity(item, null);
        assertTrue(result.freshlyAssigned());
        assertEquals(result.identity().id(), data.get(key("item_id")));
        assertEquals("preserved", data.get(custom));
        verify(item, never()).getItemMeta();
        verify(item, never()).setItemMeta(any());
    }

    @Test void itemToTileCopiesAllRegistrationFieldsWithoutMetaClone() {
        ItemStack item = item(Material.SHULKER_BOX);
        service().assignIdentity(item, null, ItemOrigin.LEGACY_IMPORT);
        data.put(key("registered_world"), "original");
        data.put(key("registered_x"), 1);
        data.put(key("registered_y"), 2);
        data.put(key("registered_z"), 3);
        TileState tile = mock(TileState.class);
        PersistentDataContainer target = mock(PersistentDataContainer.class);
        when(tile.getPersistentDataContainer()).thenReturn(target);
        assertTrue(service().copyIdentity(item, tile));
        verify(target).set(key("item_id"), PersistentDataType.STRING, (String) data.get(key("item_id")));
        verify(target).set(key("registered_at"), PersistentDataType.LONG, (Long) data.get(key("registered_at")));
        verify(target).set(key("origin"), PersistentDataType.STRING, "LEGACY_IMPORT");
        verify(target).set(key("registered_world"), PersistentDataType.STRING, "original");
        verify(target).set(key("registered_x"), PersistentDataType.INTEGER, 1);
        verify(target).set(key("registered_y"), PersistentDataType.INTEGER, 2);
        verify(target).set(key("registered_z"), PersistentDataType.INTEGER, 3);
        verify(item, never()).getItemMeta();
    }

    @SuppressWarnings("unchecked")
    private ItemStack item(Material material) {
        material = spy(material);
        doReturn(1).when(material).getMaxStackSize();
        ItemStack item = mock(ItemStack.class);
        when(item.getType()).thenReturn(material);
        when(item.getPersistentDataContainer()).thenReturn(pdc);
        when(item.editPersistentDataContainer(any())).thenAnswer(call -> {
            ((Consumer<PersistentDataContainer>) call.getArgument(0)).accept(pdc);
            return true;
        });
        return item;
    }
    private IdentityService service() {
        ItemIntegrityConfig config = mock(ItemIntegrityConfig.class);
        when(config.timezone()).thenReturn(ZoneId.of("UTC"));
        return new IdentityService(config);
    }
    private static NamespacedKey key(String key) { return new NamespacedKey("zetra", key); }
}
