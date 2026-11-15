package main.java.me.dniym.identity.presence;

import main.java.me.dniym.identity.ItemIdentity;
import main.java.me.dniym.identity.ItemOrigin;
import main.java.me.dniym.identity.audit.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

class EnderPersistenceTest {
    @TempDir Path directory;
    @Test void enderOwnerAndSlotSurviveSqliteRestartWithoutSchemaReset() throws Exception {
        var file = directory.resolve("ender.db").toFile();
        AuditQueue queue = new AuditQueue(100);
        ItemIdentity id = new ItemIdentity("ZI-ender", 123, "world", 1, 2, 3, ItemOrigin.LEGACY_IMPORT);
        HolderRef.EnderChestHolder holder = new HolderRef.EnderChestHolder(new UUID(0, 12), "tester", 26);
        DatabaseService db = new DatabaseService(file, queue, 20, 10, 7, 60_000);
        try {
            assertTrue(db.writeAndConfirm(new AuditTask.RegisterItem(new ItemSnapshot(id.id(), "SHIELD", 123,
                    "LEGACY_IMPORT", "world", 1, 2, 3, holder.playerId().toString(), 123))).get(5, TimeUnit.SECONDS));
            var store = new SqliteBackedPresenceStore(db, queue);
            store.commitHandoff(id, holder, PresenceState.OFFLINE_COMMITTED);
        } finally { db.shutdown(); }
        DatabaseService reopened = new DatabaseService(file, new AuditQueue(100), 20, 10, 7, 60_000);
        try {
            var store = new SqliteBackedPresenceStore(reopened, new AuditQueue(100));
            PresenceRecord record = store.getCanonical(id).orElseThrow();
            assertEquals(holder, record.holder());
            assertEquals(id, record.identity());
            assertEquals(PresenceState.OFFLINE_COMMITTED, record.state());
        } finally { reopened.shutdown(); }
    }
}
