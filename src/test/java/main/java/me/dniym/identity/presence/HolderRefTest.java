package main.java.me.dniym.identity.presence;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HolderRefTest {

    @Test
    void virtualInventoryEventsShareTheSameLogicalOwner() {
        UUID viewer = UUID.randomUUID();
        HolderRef click = new HolderRef.VirtualHolder(HolderType.VIRTUAL_INVENTORY,
                "shulker_box:inventory_click", viewer.toString(), 4);
        HolderRef close = new HolderRef.VirtualHolder(HolderType.VIRTUAL_INVENTORY,
                "shulker_box:inventory_close", viewer.toString(), 4);

        assertTrue(HolderRef.sameOwner(click, close));
        assertEquals(click.describe(), close.describe());
    }

    @Test
    void playerLogicalOwnerIgnoresSlotButPhysicalDescriptionKeepsIt() {
        UUID player = UUID.randomUUID();
        HolderRef slotOne = new HolderRef.PlayerHolder(player, "tester", 1);
        HolderRef slotForty = new HolderRef.PlayerHolder(player, "tester", 40);

        assertTrue(HolderRef.sameOwner(slotOne, slotForty));
        assertEquals(HolderRef.logicalOwnerKey(slotOne), HolderRef.logicalOwnerKey(slotForty));
    }
}
