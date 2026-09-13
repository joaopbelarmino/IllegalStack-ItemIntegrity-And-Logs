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
}
