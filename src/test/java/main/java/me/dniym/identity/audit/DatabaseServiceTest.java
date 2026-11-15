package main.java.me.dniym.identity.audit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DatabaseServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void failedFinalWriteLeavesPendingAcrossRestart() throws Exception {
        File file = tempDir.resolve("failed-outcome.db").toFile();
        DatabaseService database = new DatabaseService(file, new AuditQueue(100), 20, 10, 7, 60000);
        try {
            assertTrue(database.writeAndConfirm(new AuditTask.PersistCase(caseSnapshot("pending", "pending",
                    "CONFIRMED_DUPLICATE", "DELETE_PENDING", new byte[]{1}))).get(5, TimeUnit.SECONDS));
            try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + file); Statement st = c.createStatement()) {
                st.execute("CREATE TRIGGER fail_outcome BEFORE UPDATE OF action ON integrity_cases "
                        + "BEGIN SELECT RAISE(ABORT, 'injected final write failure'); END");
            }
            assertFalse(database.writeAndConfirm(new AuditTask.PersistCase(caseSnapshot("pending", "pending",
                    "CONFIRMED_DUPLICATE", "REMOVED", new byte[]{2}))).get(5, TimeUnit.SECONDS));
        } finally { database.shutdown(); }
        DatabaseService reopened = new DatabaseService(file, new AuditQueue(100), 20, 10, 7, 60000);
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + file); Statement st = c.createStatement();
             ResultSet row = st.executeQuery("SELECT action, conflicting_item_snapshot FROM integrity_cases WHERE case_id='pending'")) {
            assertTrue(row.next()); assertEquals("DELETE_PENDING", row.getString(1));
            org.junit.jupiter.api.Assertions.assertArrayEquals(new byte[]{1}, row.getBytes(2));
        } finally { reopened.shutdown(); }
    }

    @Test
    void pendingDeletionUpdatesOnlyOutcomeAndCannotBeReverted() throws Exception {
        File file = tempDir.resolve("outcomes.db").toFile();
        DatabaseService database = new DatabaseService(file, new AuditQueue(100), 20, 10, 7, 60000);
        try {
            for (String outcome : new String[]{"REMOVED", "DELETE_ABORTED_REVALIDATION_FAILED", "DELETE_ABORTED_PERSISTENCE_FAILED"}) {
                var pending = caseSnapshot(outcome, outcome, "CONFIRMED_DUPLICATE", "DELETE_PENDING", new byte[]{1,2,3});
                assertTrue(database.writeAndConfirm(new AuditTask.PersistCase(pending)).get(5, TimeUnit.SECONDS));
                try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + file); Statement st = c.createStatement();
                     ResultSet row = st.executeQuery("SELECT action FROM integrity_cases WHERE case_id='" + outcome + "'")) {
                    assertTrue(row.next()); assertEquals("DELETE_PENDING", row.getString(1));
                }
                var completed = caseSnapshot(outcome, outcome, "CONFIRMED_DUPLICATE", outcome, new byte[]{9});
                assertTrue(database.writeAndConfirm(new AuditTask.PersistCase(completed)).get(5, TimeUnit.SECONDS));
                assertTrue(database.writeAndConfirm(new AuditTask.PersistCase(pending)).get(5, TimeUnit.SECONDS));
                try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + file); Statement st = c.createStatement();
                     ResultSet row = st.executeQuery("SELECT action, conflicting_item_snapshot FROM integrity_cases WHERE case_id='" + outcome + "'")) {
                    assertTrue(row.next()); assertEquals(outcome, row.getString(1));
                    org.junit.jupiter.api.Assertions.assertArrayEquals(new byte[]{1,2,3}, row.getBytes(2));
                    assertFalse(row.next());
                }
            }
        } finally { database.shutdown(); }
    }

    @Test
    void corruptHistoryStopsMaintenanceAndConfirmedWritesWithoutDeletingData() throws Exception {
        File file = tempDir.resolve("corrupt.db").toFile();
        createLegacyDatabase(file);
        int page, pageSize;
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + file); Statement st = c.createStatement()) {
            page = (int) scalar(st, "SELECT rootpage FROM sqlite_master WHERE name='item_events'");
            pageSize = (int) scalar(st, "PRAGMA page_size");
        }
        // Damage only a fixture's table B-tree header, while every connection is closed.
        try (var bytes = new java.io.RandomAccessFile(file, "rw")) {
            bytes.seek((long) (page - 1) * pageSize); bytes.writeByte(0x7f);
        }
        DatabaseService database = new DatabaseService(file, new AuditQueue(100), 20, 10, 7, 60000);
        try {
            var result = database.compactForCurrentModel().get(10, TimeUnit.SECONDS);
            assertFalse(result.success()); assertTrue(result.message().contains("SQLITE_CORRUPT"), result.message());
            assertFalse(database.writeAndConfirm(new AuditTask.PersistCase(caseSnapshot("blocked", "blocked",
                    "CONFIRMED_DUPLICATE", "WOULD_REMOVE", new byte[1]))).get(1, TimeUnit.SECONDS));
            assertFalse(database.compactForCurrentModel().get(1, TimeUnit.SECONDS).success());
            try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + file); Statement st = c.createStatement()) {
                assertEquals(1, scalar(st, "SELECT COUNT(*) FROM items"));
                assertEquals(1, scalar(st, "SELECT COUNT(*) FROM presence"));
                assertEquals(2, scalar(st, "SELECT COUNT(*) FROM integrity_cases"));
            }
            assertFalse(java.nio.file.Files.exists(tempDir.resolve("corrupt.db.compacting")));
        } finally { database.shutdown(); }
    }

    @Test
    void compactedFileRemainsVisibleToAnExistingReaderAndAcceptsSubsequentWrites() throws Exception {
        File file = tempDir.resolve("reader.db").toFile();
        DatabaseService database = new DatabaseService(file, new AuditQueue(100), 20, 10, 7, 60000);
        try (Connection reader = DriverManager.getConnection("jdbc:sqlite:" + file); Statement st = reader.createStatement()) {
            assertEquals(0, scalar(st, "SELECT COUNT(*) FROM integrity_cases"));
            assertTrue(database.compactForCurrentModel().get(10, TimeUnit.SECONDS).success());
            assertTrue(database.writeAndConfirm(new AuditTask.PersistCase(caseSnapshot("after", "after",
                    "CONFIRMED_DUPLICATE", "WOULD_REMOVE", new byte[]{1,2,3}))).get(5, TimeUnit.SECONDS));
            assertEquals(1, scalar(st, "SELECT COUNT(*) FROM integrity_cases"));
        } finally { database.shutdown(); }
    }

    @Test
    void possibleCasesRollUpAndManualCompactionKeepsOnlyLatestDetailedEvent() throws Exception {
        File file = tempDir.resolve("integrity.db").toFile();
        DatabaseService database = new DatabaseService(file, new AuditQueue(100), 20, 10,
                30, TimeUnit.HOURS.toMillis(6));
        try {
            IntegrityCaseSnapshot possibleOne = caseSnapshot("ZIC-possible-1", "incident-A",
                    "AMBIGUOUS_CONTAINER_PRESENCE", "MONITOR_ONLY", new byte[32_000]);
            IntegrityCaseSnapshot possibleTwo = caseSnapshot("ZIC-possible-2", "incident-A",
                    "AMBIGUOUS_CONTAINER_PRESENCE", "MONITOR_ONLY", new byte[32_000]);
            IntegrityCaseSnapshot confirmed = caseSnapshot("ZIC-confirmed", "incident-B",
                    "CONFIRMED_DUPLICATE", "WOULD_REMOVE", new byte[]{1, 2, 3, 4});

            assertTrue(database.writeAndConfirm(new AuditTask.PersistCase(possibleOne)).get(5, TimeUnit.SECONDS));
            assertTrue(database.writeAndConfirm(new AuditTask.PersistCase(possibleTwo)).get(5, TimeUnit.SECONDS));
            assertTrue(database.writeAndConfirm(new AuditTask.PersistCase(confirmed)).get(5, TimeUnit.SECONDS));

            for (int revision = 1; revision <= 3; revision++) {
                ItemEventSnapshot event = new ItemEventSnapshot("ZI-test", 1_000L + revision,
                        IdentityEventType.PRESENCE_COMMITTED, "PLAYER", null, null, null, null,
                        "00000000-0000-0000-0000-00000000000" + revision, "tester", revision, null,
                        "LIVE_CONFIRMED", revision, true);
                assertTrue(database.writeAndConfirm(new AuditTask.RecordEvent(event)).get(5, TimeUnit.SECONDS));
            }

            DatabaseService.SqlMaintenanceResult result = database.compactForCurrentModel().get(10, TimeUnit.SECONDS);
            assertTrue(result.success(), result.message());

            try (Connection read = DriverManager.getConnection("jdbc:sqlite:" + file.getAbsolutePath());
                 Statement statement = read.createStatement()) {
                assertEquals(1, scalar(statement, "SELECT COUNT(*) FROM integrity_cases"));
                assertEquals(4, scalar(statement,
                        "SELECT length(conflicting_item_snapshot) FROM integrity_cases WHERE case_id='ZIC-confirmed'"));
                assertEquals(1, scalar(statement, "SELECT COUNT(*) FROM integrity_case_rollups"));
                assertEquals(2, scalar(statement,
                        "SELECT occurrence_count FROM integrity_case_rollups WHERE incident_key='incident-A'"));
                assertEquals(0, scalar(statement, "SELECT COUNT(*) FROM item_events"));
                assertEquals(0, scalar(statement, "SELECT COUNT(*) FROM item_event_rollups"));
                assertEquals(2, scalar(statement, "SELECT COUNT(*) FROM item_event_tail"));
                assertEquals(3, scalar(statement, "SELECT revision FROM item_event_tail WHERE event_type='PRESENCE_COMMITTED'"));
                assertEquals(2, scalar(statement, "SELECT revision FROM item_event_tail WHERE event_type='PREVIOUS_COMMITTED'"));
            }
        } finally {
            database.shutdown();
        }
    }

    @Test
    void legacySchemaIsMigratedAndRepeatedPossibleCasesAreCompacted() throws Exception {
        File file = tempDir.resolve("legacy.db").toFile();
        createLegacyDatabase(file);

        DatabaseService database = new DatabaseService(file, new AuditQueue(100), 20, 10,
                30, TimeUnit.HOURS.toMillis(6));
        try {
            DatabaseService.SqlMaintenanceResult result = database.compactForCurrentModel().get(10, TimeUnit.SECONDS);
            assertTrue(result.success(), result.message());

            try (Connection read = DriverManager.getConnection("jdbc:sqlite:" + file.getAbsolutePath());
                 Statement statement = read.createStatement()) {
                assertEquals(0, scalar(statement, "SELECT COUNT(*) FROM item_events"));
                assertEquals(0, scalar(statement, "SELECT COUNT(*) FROM item_event_rollups"));
                assertEquals(1, scalar(statement, "SELECT COUNT(*) FROM item_event_tail"));
                assertEquals(0, scalar(statement, "SELECT COUNT(*) FROM integrity_cases"));
                assertEquals(0, scalar(statement, "SELECT COUNT(*) FROM integrity_case_rollups"));
                assertEquals(1, scalar(statement, "SELECT COUNT(*) FROM items"));
                assertEquals(1, scalar(statement, "SELECT COUNT(*) FROM presence"));
            }
        } finally {
            database.shutdown();
        }
    }

    @Test
    void largeLegacyHistoryStartsWithoutHeavyIndexesAndCompactsInChunks() throws Exception {
        File file = tempDir.resolve("chunked-legacy.db").toFile();
        createLegacyDatabase(file);
        appendLegacyHistory(file, 25_005, 1_000);

        DatabaseService database = new DatabaseService(file, new AuditQueue(100), 20, 10,
                30, TimeUnit.HOURS.toMillis(6));
        try {
            try (Connection read = DriverManager.getConnection("jdbc:sqlite:" + file.getAbsolutePath());
                 Statement statement = read.createStatement()) {
                assertFalse(indexExists(statement, "idx_item_events_time"));
                assertFalse(indexExists(statement, "idx_integrity_cases_created_at"));
            }

            DatabaseService.SqlMaintenanceResult result = database.compactForCurrentModel().get(30, TimeUnit.SECONDS);
            assertTrue(result.success(), result.message());

            try (Connection read = DriverManager.getConnection("jdbc:sqlite:" + file.getAbsolutePath());
                 Statement statement = read.createStatement()) {
                assertEquals(0, scalar(statement, "SELECT COUNT(*) FROM item_events"));
                assertEquals(0, scalar(statement, "SELECT COUNT(*) FROM item_event_rollups"));
                assertEquals(1, scalar(statement, "SELECT COUNT(*) FROM item_event_tail"));
                assertEquals(0, scalar(statement, "SELECT COUNT(*) FROM integrity_cases"));
                assertEquals(0, scalar(statement, "SELECT COUNT(*) FROM integrity_case_rollups"));
                assertTrue(indexExists(statement, "idx_item_events_time"));
                assertTrue(indexExists(statement, "idx_integrity_cases_created_at"));
            }
        } finally {
            database.shutdown();
        }
    }

    private IntegrityCaseSnapshot caseSnapshot(String caseId, String incidentKey, String decision,
                                                String action, byte[] itemSnapshot) {
        return new IntegrityCaseSnapshot(caseId, incidentKey, System.currentTimeMillis(), "MONITOR", decision,
                action, "LOW", "test", "ZI-test", "DIAMOND_PICKAXE",
                "PERSISTED_CONTAINER chest:world:1,2,3:slot4 rev=1 last=1",
                "LIVE_CONFIRMED player:tester:slot1 rev=2 last=2", "summary", itemSnapshot);
    }

    @Test
    void oldConfirmedSnapshotsSurviveRepeatedMaintenanceIncludingLegacyWouldRemove() throws Exception {
        File file = tempDir.resolve("proofs.db").toFile();
        createLegacyDatabase(file);
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + file); Statement st = c.createStatement()) {
            st.execute("INSERT INTO integrity_cases VALUES ('ZIC-proof',1,'MONITOR','DUPLICATE_DETECTED',"
                    + "'WOULD_REMOVE','CRITICAL','test','ZI-legacy','DIAMOND_PICKAXE','canonical','conflicting','summary',zeroblob(64))");
            st.execute("INSERT INTO item_event_rollups (item_uuid,event_type,first_seen,last_seen,occurrence_count)"
                    + " VALUES ('ZI-legacy','PRESENCE_COMMITTED',1,2,123456)");
        }
        DatabaseService database = new DatabaseService(file, new AuditQueue(100), 20, 10, 7, 60000);
        try {
            assertTrue(database.compactForCurrentModel().get(10, TimeUnit.SECONDS).success());
            assertTrue(database.compactForCurrentModel().get(10, TimeUnit.SECONDS).success());
            try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + file); Statement st = c.createStatement()) {
                assertEquals(64, scalar(st, "SELECT length(conflicting_item_snapshot) FROM integrity_cases WHERE case_id='ZIC-proof'"));
                assertEquals(0, scalar(st, "SELECT COUNT(*) FROM item_event_rollups"));
                assertEquals(0, scalar(st, "SELECT COUNT(*) FROM integrity_case_rollups"));
                assertEquals(1, scalar(st, "SELECT COUNT(*) FROM items"));
                assertEquals(1, scalar(st, "SELECT COUNT(*) FROM presence"));
            }
        } finally { database.shutdown(); }
    }

    @Test
    void stalePresenceCannotOverwriteNewerRevisionAndHistoryRemainsBounded() throws Exception {
        File file = tempDir.resolve("bounded.db").toFile();
        DatabaseService database = new DatabaseService(file, new AuditQueue(1000), 200, 10, 7, 60000);
        try {
            for (int i = 1; i <= 100; i++) {
                ItemEventSnapshot event = new ItemEventSnapshot("ZI-test", 1000L + i,
                        IdentityEventType.PRESENCE_COMMITTED, "PLAYER", null, null, null, null,
                        "player-" + i, "tester", 0, null, "LIVE_CONFIRMED", i, true);
                assertTrue(database.writeAndConfirm(new AuditTask.RecordEvent(event)).get(5, TimeUnit.SECONDS));
            }
            var old = new PresenceUpdateSnapshot("ZI-test", "PLAYER", null, null, null, null,
                    "old-player", "old", 1, null, "LIVE_CONFIRMED", 5, 1100);
            assertTrue(database.writeAndConfirm(new AuditTask.UpdatePresence(old)).get(5, TimeUnit.SECONDS));
            try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + file); Statement st = c.createStatement()) {
                assertEquals(0, scalar(st, "SELECT COUNT(*) FROM item_events"));
                assertEquals(2, scalar(st, "SELECT COUNT(*) FROM item_event_tail"));
                assertEquals(99, scalar(st, "SELECT revision FROM item_event_tail WHERE event_type='PREVIOUS_COMMITTED'"));
                assertEquals(100, scalar(st, "SELECT revision FROM presence"));
            }
        } finally { database.shutdown(); }
    }

    private void createLegacyDatabase(File file) throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + file.getAbsolutePath());
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE items (item_uuid TEXT PRIMARY KEY, material TEXT, created_at INTEGER, "
                    + "origin TEXT, registered_world TEXT, registered_x INTEGER, registered_y INTEGER, registered_z INTEGER, "
                    + "first_owner TEXT, last_owner TEXT, first_seen INTEGER, last_seen INTEGER, status TEXT, fingerprint BLOB)");
            statement.execute("CREATE TABLE presence (item_uuid TEXT PRIMARY KEY, holder_type TEXT, world TEXT, x INTEGER, "
                    + "y INTEGER, z INTEGER, player_uuid TEXT, player_name TEXT, slot INTEGER, entity_uuid TEXT, state TEXT, "
                    + "revision INTEGER, last_confirmed_at INTEGER)");
            statement.execute("CREATE TABLE item_events (id INTEGER PRIMARY KEY AUTOINCREMENT, item_uuid TEXT, time INTEGER, "
                    + "event_type TEXT, player_uuid TEXT, world TEXT, x INTEGER, y INTEGER, z INTEGER, holder_type TEXT, "
                    + "slot INTEGER, fingerprint BLOB)");
            statement.execute("CREATE TABLE integrity_cases (case_id TEXT PRIMARY KEY, created_at INTEGER, mode TEXT, "
                    + "decision TEXT, action TEXT, confidence TEXT, reason TEXT, item_uuid TEXT, material TEXT, "
                    + "canonical_summary TEXT, conflicting_summary TEXT, conflicting_item_summary TEXT, "
                    + "conflicting_item_snapshot BLOB)");
            statement.execute("CREATE TABLE item_event_rollups (id INTEGER PRIMARY KEY AUTOINCREMENT, item_uuid TEXT, "
                    + "event_type TEXT, holder_type TEXT, player_uuid TEXT, world TEXT, x INTEGER, y INTEGER, z INTEGER, "
                    + "slot INTEGER, first_seen INTEGER, last_seen INTEGER, occurrence_count INTEGER)");
            statement.execute("CREATE TABLE integrity_case_rollups (id INTEGER PRIMARY KEY AUTOINCREMENT, first_case_id TEXT, "
                    + "last_case_id TEXT, first_seen INTEGER, last_seen INTEGER, occurrence_count INTEGER, mode TEXT, "
                    + "decision TEXT, action TEXT, confidence TEXT, reason TEXT, item_uuid TEXT, material TEXT, "
                    + "canonical_summary TEXT, conflicting_summary TEXT, last_conflicting_item_summary TEXT, confirmed INTEGER)");

            statement.execute("INSERT INTO items VALUES ('ZI-legacy','DIAMOND_PICKAXE',1,'LEGACY_IMPORT','world',1,2,3,NULL,NULL,1,1,'ACTIVE',NULL)");
            statement.execute("INSERT INTO presence VALUES ('ZI-legacy','PLAYER',NULL,NULL,NULL,NULL,"
                    + "'00000000-0000-0000-0000-000000000001','tester',1,NULL,'LIVE_CONFIRMED',3,1003)");
            for (int i = 1; i <= 3; i++) {
                statement.execute("INSERT INTO item_events (item_uuid,time,event_type,player_uuid,holder_type,slot) VALUES "
                        + "('ZI-legacy'," + (1000 + i) + ",'PRESENCE_COMMITTED',"
                        + "'00000000-0000-0000-0000-000000000001','PLAYER'," + i + ")");
            }
            statement.execute("INSERT INTO integrity_cases VALUES ('ZIC-old-1',1001,'MONITOR',"
                    + "'AMBIGUOUS_CONTAINER_PRESENCE','MONITOR_ONLY','CRITICAL','CONTAINER_PRESENCE_UNAVAILABLE',"
                    + "'ZI-legacy','DIAMOND_PICKAXE','PERSISTED_CONTAINER chest:world:1,2,3:slot4 rev=1 last=1',"
                    + "'LIVE_CONFIRMED player:tester:slot1 rev=2 last=2','summary',zeroblob(32000))");
            statement.execute("INSERT INTO integrity_cases VALUES ('ZIC-old-2',1002,'MONITOR',"
                    + "'AMBIGUOUS_CONTAINER_PRESENCE','MONITOR_ONLY','CRITICAL','CONTAINER_PRESENCE_UNAVAILABLE',"
                    + "'ZI-legacy','DIAMOND_PICKAXE','PERSISTED_CONTAINER chest:world:1,2,3:slot4 rev=7 last=7',"
                    + "'LIVE_CONFIRMED player:tester:slot1 rev=8 last=8','summary',zeroblob(32000))");
        }
    }

    private void appendLegacyHistory(File file, int eventCount, int caseCount) throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + file.getAbsolutePath())) {
            connection.setAutoCommit(false);
            try (PreparedStatement event = connection.prepareStatement(
                    "INSERT INTO item_events (item_uuid,time,event_type,player_uuid,holder_type,slot) VALUES "
                            + "('ZI-legacy',?,'PRESENCE_COMMITTED',"
                            + "'00000000-0000-0000-0000-000000000001','PLAYER',?)");
                 PreparedStatement integrityCase = connection.prepareStatement(
                         "INSERT INTO integrity_cases VALUES (?,?,'MONITOR','AMBIGUOUS_CONTAINER_PRESENCE',"
                                 + "'MONITOR_ONLY','CRITICAL','CONTAINER_PRESENCE_UNAVAILABLE','ZI-legacy',"
                                 + "'DIAMOND_PICKAXE','PERSISTED_CONTAINER chest:world:1,2,3:slot4 rev=9 last=9',"
                                 + "'LIVE_CONFIRMED player:tester:slot1 rev=10 last=10','summary',zeroblob(32))")) {
                for (int i = 0; i < eventCount; i++) {
                    event.setLong(1, 2_000L + i);
                    event.setInt(2, i % 41);
                    event.addBatch();
                    if ((i + 1) % 1_000 == 0) {
                        event.executeBatch();
                    }
                }
                event.executeBatch();

                for (int i = 0; i < caseCount; i++) {
                    integrityCase.setString(1, "ZIC-bulk-" + i);
                    integrityCase.setLong(2, 2_000L + i);
                    integrityCase.addBatch();
                }
                integrityCase.executeBatch();
                connection.commit();
            } catch (Exception e) {
                connection.rollback();
                throw e;
            }
        }
    }

    private boolean indexExists(Statement statement, String indexName) throws Exception {
        try (ResultSet result = statement.executeQuery(
                "SELECT 1 FROM sqlite_master WHERE type='index' AND name='" + indexName + "'")) {
            return result.next();
        }
    }

    private long scalar(Statement statement, String sql) throws Exception {
        try (ResultSet result = statement.executeQuery(sql)) {
            return result.next() ? result.getLong(1) : 0L;
        }
    }
}
