package main.java.me.dniym.audit;

import main.java.me.dniym.IllegalStack;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AuditConfigTest {
    @TempDir Path temp;
    @Test void transferIsBlockedEvenWhenOldConfigurationAllowsRemoval()throws Exception{
        var plugin=mock(IllegalStack.class);when(plugin.getDataFolder()).thenReturn(temp.toFile());
        var config=new AuditConfig(plugin);
        assertFalse(config.removalEnabled());
        assertTrue(config.enabled());
        assertTrue(config.playerdataEnabled());
    }
    @Test void containerSettingsFollowTheFileOnReload()throws Exception{
        var plugin=mock(IllegalStack.class);when(plugin.getDataFolder()).thenReturn(temp.toFile());
        var config=new AuditConfig(plugin);
        assertTrue(config.containersEnabled());
        assertEquals(60L,config.debounceTicks());assertEquals(600L,config.maxDirtyTicks());assertEquals(10000,config.dirtyCapacity());
        assertEquals(8,config.snapshotBudget());assertEquals(2_000_000L,config.snapshotBudgetNanos());
        var file=temp.resolve("item-audit.yml");
        java.nio.file.Files.writeString(file,java.nio.file.Files.readString(file)
                .replace("debounce-ticks: 60","debounce-ticks: 900").replace("dirty-capacity: 10000","dirty-capacity: 5")
                .replace("snapshot-budget-per-cycle: 8","snapshot-budget-per-cycle: 3").replace("snapshot-budget-ms: 2.0","snapshot-budget-ms: 0.5")
                .replaceFirst("containers:\\n    enabled: true","containers:\n    enabled: false"));
        config.reload();
        assertFalse(config.containersEnabled());
        assertEquals(900L,config.debounceTicks());assertEquals(900L,config.maxDirtyTicks());assertEquals(64,config.dirtyCapacity());
        assertEquals(3,config.snapshotBudget());assertEquals(500_000L,config.snapshotBudgetNanos());
    }
}
