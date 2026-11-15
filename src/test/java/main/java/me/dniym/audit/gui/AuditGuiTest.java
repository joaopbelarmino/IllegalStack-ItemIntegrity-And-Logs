package main.java.me.dniym.audit.gui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AuditGuiTest {
    @Test void mapsPlayerdataInventoryArmorAndOffhandSlots() {
        assertEquals(0,AuditGui.offlineIndex(0,false));
        assertEquals(35,AuditGui.offlineIndex(35,false));
        assertEquals(36,AuditGui.offlineIndex(100,false));
        assertEquals(39,AuditGui.offlineIndex(103,false));
        assertEquals(40,AuditGui.offlineIndex(-106,false));
        assertEquals(40,AuditGui.offlineIndex(150,false));
        assertEquals(-1,AuditGui.offlineIndex(99,false));
        assertEquals(26,AuditGui.offlineIndex(26,true));
    }
}
