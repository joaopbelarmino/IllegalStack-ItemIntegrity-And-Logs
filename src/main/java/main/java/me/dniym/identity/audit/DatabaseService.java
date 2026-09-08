package main.java.me.dniym.identity.audit;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Camada de persistência SQLite do Item Integrity System.
 *
 * Regras de threading seguidas à risca:
 *  - Um único writer thread dedicado (não usa o pool async do Bukkit, pra
 *    garantir exatamente uma conexão/thread cuidando do banco).
 *  - A server thread NUNCA espera por I/O daqui pro caminho de rotina - só
 *    chama AuditQueue.offer() (não-bloqueante) pra presença comum.
 *  - hydratePresenceWithIdentity() é a ÚNICA operação síncrona no startup,
 *    roda uma vez só, no onEnable().
 *
 * SEMÂNTICA DE DURABILIDADE - duas classes de escrita, propositalmente
 * diferentes:
 *
 *  1. ROTINA (presença comum: join, scan periódico, quit) - passa por
 *     AuditQueue.offer(), que é fire-and-forget. Existe uma JANELA DE
 *     WRITE-BEHIND real: entre o momento em que a canonical muda em
 *     memória e o momento em que o writer thread realmente comita aquele
 *     lote no SQLite (até `flush-interval-ms`), se o processo morrer
 *     (crash, kill -9, queda de energia), esse intervalo de eventos NÃO
 *     está no banco ainda. Isso é uma limitação CONHECIDA E ACEITA pra
 *     esse tipo de evento - o pior caso é perder um pedaço pequeno de
 *     histórico de auditoria, não perder um item ou corromper estado de
 *     jogo (a canonical em memória continua correta até o próximo
 *     restart, quando ela seria reconstruída via hidratação do que FOI
 *     commitado).
 *
 *  2. CONFIRMADA (writeAndConfirm) - pra uso futuro de ações destrutivas
 *     (DELETE_WITH_BACKUP, etapa 7+). NUNCA fire-and-forget: enfileira com
 *     prioridade sobre o lote de rotina, escreve e comita numa transação
 *     própria, e só resolve o CompletableFuture (true) depois que o
 *     commit no SQLite realmente aconteceu. Se o commit falhar, resolve
 *     false - quem chama NUNCA deve interpretar "chamei writeAndConfirm"
 *     como "já está persistido": só o future resolvendo true é a
 *     confirmação real. O fluxo futuro de remoção deve ser:
 *     snapshot -> writeAndConfirm(...).join()/thenAccept(...) -> só then
 *     executar a remoção de volta na server thread.
 *
 * FAIL_OPEN: se a inicialização do banco falhar, o construtor lança
 * exceção - quem chama (ItemIntegritySystem) captura isso e cai de volta
 * pro modo em memória puro, sem persistência, em vez de derrubar o plugin.
 */
public final class DatabaseService {

    private static final Logger LOGGER = LogManager.getLogger("IllegalStack/ItemIntegrity");
    private static final long EVENT_MAINTENANCE_ID_SPAN = 25_000L;
    private static final long CASE_MAINTENANCE_ROWID_SPAN = 1_000L;
    private static final int CHECKPOINT_EVERY_CHUNKS = 32;

    private record ConfirmedWriteRequest(AuditTask task, CompletableFuture<Boolean> future) {
    }

    private record MaintenanceRequest(CompletableFuture<SqlMaintenanceResult> future) {
    }

    public record SqlMaintenanceResult(boolean success, String message, long startedAtEpochMs, long finishedAtEpochMs,
                                       long sizeBeforeBytes, long sizeAfterBytes, long itemEventsBefore,
                                       long itemEventsAfter, long casesBefore, long casesAfter,
                                       long caseRollups, long eventRollups) {
        public long savedBytes() {
            return Math.max(0L, sizeBeforeBytes - sizeAfterBytes);
        }
    }

    private final AuditQueue queue;
    private Connection connection;
    private final File databaseFile;
    private final Thread writerThread;
    private final AtomicBoolean running = new AtomicBoolean(true);
    private volatile String persistenceFailure;
    private long lastWriteErrorMs;
    private final AtomicBoolean manualMaintenancePending = new AtomicBoolean(false);
    private final int batchSize;
    private final long flushIntervalMs;
    private final long historyRetentionMs;
    private final long maintenanceIntervalMs;
    private volatile long nextRetentionMaintenanceAtMs;
    private final LinkedBlockingQueue<ConfirmedWriteRequest> confirmedWriteQueue = new LinkedBlockingQueue<>(512);
    private final LinkedBlockingQueue<MaintenanceRequest> maintenanceQueue = new LinkedBlockingQueue<>();

    public DatabaseService(File databaseFile, AuditQueue queue, int batchSize, long flushIntervalMs,
                           int historyRetentionDays, long maintenanceIntervalMs) throws SQLException {
        this.queue = queue;
        this.databaseFile = databaseFile;
        this.batchSize = batchSize;
        this.flushIntervalMs = flushIntervalMs;
        this.historyRetentionMs = Math.max(1L, historyRetentionDays) * 86_400_000L;
        this.maintenanceIntervalMs = Math.max(60_000L, maintenanceIntervalMs);
        this.nextRetentionMaintenanceAtMs = System.currentTimeMillis() + this.maintenanceIntervalMs;

        // Uma conexão só, usada exclusivamente pelo writer thread. SQLite
        // não é feito pra múltiplas conexões concorrentes escrevendo - por
        // isso "um único writer" não é só preferência de design, é o jeito
        // correto de usar SQLite mesmo.
        this.connection = openConnection(databaseFile.toPath());
        try { initSchema(); }
        catch (SQLException e) { closeConnectionQuietly(); throw e; }

        this.writerThread = new Thread(this::writerLoop, "IllegalStack-ItemIntegrity-Writer");
        this.writerThread.setDaemon(true);
        this.writerThread.start();

        LOGGER.info("[ItemIntegrity] SQLite inicializado em {}", databaseFile.getAbsolutePath());
        LOGGER.info("[ItemIntegrity] SQLite runtime={} driver={}", connection.getMetaData().getDatabaseProductVersion(),
                connection.getMetaData().getDriverVersion());
    }

    private void initSchema() throws SQLException {
        try (Statement st = connection.createStatement()) {
            st.execute("PRAGMA journal_mode=WAL;");
            st.execute("PRAGMA synchronous=NORMAL;");
            st.execute("PRAGMA busy_timeout=5000;");
            st.execute("PRAGMA wal_autocheckpoint=1000;");
            st.execute("PRAGMA journal_size_limit=67108864;");
            st.execute("PRAGMA temp_store=FILE;");
            st.execute("PRAGMA auto_vacuum=INCREMENTAL;");

            st.execute("""
                    CREATE TABLE IF NOT EXISTS items (
                        item_uuid TEXT PRIMARY KEY,
                        material TEXT,
                        created_at INTEGER,
                        origin TEXT,
                        registered_world TEXT,
                        registered_x INTEGER,
                        registered_y INTEGER,
                        registered_z INTEGER,
                        first_owner TEXT,
                        last_owner TEXT,
                        first_seen INTEGER,
                        last_seen INTEGER,
                        status TEXT,
                        fingerprint BLOB
                    );
                    """);

            st.execute("""
                    CREATE TABLE IF NOT EXISTS item_events (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        item_uuid TEXT,
                        time INTEGER,
                        event_type TEXT,
                        player_uuid TEXT,
                        world TEXT,
                        x INTEGER,
                        y INTEGER,
                        z INTEGER,
                        holder_type TEXT,
                        slot INTEGER,
                        fingerprint BLOB
                    );
                    """);
            // Mirror durável da presença canonical ATUAL (não é log, é
            // "estado atual") - usado só pra hidratar o cache em memória no
            // startup, evitando decisão em tempo real dependente de SQLite.
            st.execute("""
                    CREATE TABLE IF NOT EXISTS presence (
                        item_uuid TEXT PRIMARY KEY,
                        holder_type TEXT,
                        world TEXT,
                        x INTEGER,
                        y INTEGER,
                        z INTEGER,
                        player_uuid TEXT,
                        player_name TEXT,
                        slot INTEGER,
                        entity_uuid TEXT,
                        state TEXT,
                        revision INTEGER,
                        last_confirmed_at INTEGER
                    );
                    """);

            st.execute("""
                    CREATE TABLE IF NOT EXISTS integrity_cases (
                        case_id TEXT PRIMARY KEY,
                        created_at INTEGER,
                        mode TEXT,
                        decision TEXT,
                        action TEXT,
                        confidence TEXT,
                        reason TEXT,
                        item_uuid TEXT,
                        material TEXT,
                        canonical_summary TEXT,
                        conflicting_summary TEXT,
                        conflicting_item_summary TEXT,
                        conflicting_item_snapshot BLOB
                    );
                    """);

            ensureColumn(st, "integrity_cases", "conflicting_item_summary", "TEXT");
            ensureColumn(st, "item_events", "player_name", "TEXT");
            ensureColumn(st, "item_events", "entity_uuid", "TEXT");
            ensureColumn(st, "item_events", "state", "TEXT");
            ensureColumn(st, "item_events", "revision", "INTEGER");
            ensureColumn(st, "item_events", "auto_committed", "INTEGER");
            ensureColumn(st, "integrity_cases", "incident_key", "TEXT");
            st.execute("""
                    CREATE TABLE IF NOT EXISTS item_event_rollups (
                        event_key TEXT PRIMARY KEY,
                        item_uuid TEXT,
                        event_type TEXT,
                        holder_type TEXT,
                        player_uuid TEXT,
                        player_name TEXT,
                        entity_uuid TEXT,
                        world TEXT,
                        x INTEGER,
                        y INTEGER,
                        z INTEGER,
                        slot INTEGER,
                        state TEXT,
                        first_seen INTEGER,
                        last_seen INTEGER,
                        occurrence_count INTEGER,
                        last_revision INTEGER
                    );
                    """);
            ensureColumn(st, "item_event_rollups", "event_key", "TEXT");
            ensureColumn(st, "item_event_rollups", "player_name", "TEXT");
            ensureColumn(st, "item_event_rollups", "entity_uuid", "TEXT");
            ensureColumn(st, "item_event_rollups", "state", "TEXT");
            ensureColumn(st, "item_event_rollups", "last_revision", "INTEGER");
            st.execute("CREATE UNIQUE INDEX IF NOT EXISTS idx_item_event_rollups_key ON item_event_rollups(event_key);");

            st.execute("""
                    CREATE TABLE IF NOT EXISTS integrity_case_rollups (
                        incident_key TEXT PRIMARY KEY,
                        first_case_id TEXT,
                        last_case_id TEXT,
                        first_seen INTEGER,
                        last_seen INTEGER,
                        occurrence_count INTEGER,
                        mode TEXT,
                        decision TEXT,
                        action TEXT,
                        confidence TEXT,
                        reason TEXT,
                        item_uuid TEXT,
                        material TEXT,
                        canonical_summary TEXT,
                        conflicting_summary TEXT,
                        last_conflicting_item_summary TEXT,
                        confirmed INTEGER
                    );
                    """);
            ensureColumn(st, "integrity_case_rollups", "incident_key", "TEXT");
            st.execute("CREATE UNIQUE INDEX IF NOT EXISTS idx_integrity_case_rollups_key ON integrity_case_rollups(incident_key);");
            st.execute("""
                    CREATE TABLE IF NOT EXISTS item_event_tail (
                        item_uuid TEXT NOT NULL, event_type TEXT NOT NULL, time INTEGER,
                        player_uuid TEXT, player_name TEXT, entity_uuid TEXT, world TEXT,
                        x INTEGER, y INTEGER, z INTEGER, holder_type TEXT, slot INTEGER,
                        state TEXT, revision INTEGER, auto_committed INTEGER,
                        PRIMARY KEY (item_uuid, event_type)
                    )
                    """);
            st.execute("""
                    CREATE TRIGGER IF NOT EXISTS item_event_tail_previous BEFORE UPDATE ON item_event_tail
                    WHEN OLD.event_type='PRESENCE_COMMITTED' AND NEW.time >= OLD.time
                      AND (OLD.player_uuid IS NOT NEW.player_uuid OR OLD.entity_uuid IS NOT NEW.entity_uuid
                        OR OLD.world IS NOT NEW.world OR OLD.x IS NOT NEW.x OR OLD.y IS NOT NEW.y OR OLD.z IS NOT NEW.z
                        OR OLD.holder_type IS NOT NEW.holder_type OR OLD.state IS NOT NEW.state)
                    BEGIN
                        INSERT INTO item_event_tail VALUES
                          (OLD.item_uuid,'PREVIOUS_COMMITTED',OLD.time,OLD.player_uuid,OLD.player_name,
                           OLD.entity_uuid,OLD.world,OLD.x,OLD.y,OLD.z,OLD.holder_type,OLD.slot,
                           OLD.state,OLD.revision,OLD.auto_committed)
                        ON CONFLICT(item_uuid,event_type) DO UPDATE SET
                          time=excluded.time,player_uuid=excluded.player_uuid,player_name=excluded.player_name,
                          entity_uuid=excluded.entity_uuid,world=excluded.world,x=excluded.x,y=excluded.y,z=excluded.z,
                          holder_type=excluded.holder_type,slot=excluded.slot,state=excluded.state,
                          revision=excluded.revision,auto_committed=excluded.auto_committed;
                    END
                    """);
        }
    }

    private Connection openConnection(Path path) throws SQLException {
        return DriverManager.getConnection("jdbc:sqlite:" + path.toAbsolutePath());
    }

    private void ensureColumn(Statement st, String table, String column, String definition) throws SQLException {
        try (ResultSet rs = st.executeQuery("PRAGMA table_info(" + table + ")")) {
            while (rs.next()) {
                if (column.equalsIgnoreCase(rs.getString("name"))) {
                    return;
                }
            }
        }
        st.execute("ALTER TABLE " + table + " ADD COLUMN " + column + " " + definition);
    }

    /**
     * Carrega a presença canonical JUNTO com os dados estruturados
     * completos da identidade (JOIN com `items`) - operação SÍNCRONA, mas
     * só roda uma vez, no onEnable(). Cada linha vira um Object[] cru (19
     * colunas: 13 de `presence` + created_at/origin/registered_world/x/y/z
     * de `items`) - SqliteBackedPresenceStore reconstrói HolderRef/
     * PresenceRecord/ItemIdentity completos a partir disso.
     *
     * Usa LEFT JOIN de propósito: se por algum motivo uma linha de
     * presence não tiver items correspondente (não deveria acontecer em
     * uso normal, já que sempre registramos items antes/junto), os campos
     * de identidade vêm null e SqliteBackedPresenceStore trata isso como
     * origin=UNKNOWN de verdade (dado realmente ausente, não limitação da
     * query).
     */
    public synchronized List<Object[]> hydratePresenceWithIdentity() throws SQLException {
        List<Object[]> rows = new ArrayList<>();
        String sql = """
                SELECT p.item_uuid, p.holder_type, p.world, p.x, p.y, p.z, p.player_uuid, p.player_name,
                       p.slot, p.entity_uuid, p.state, p.revision, p.last_confirmed_at,
                       i.created_at, i.origin, i.registered_world, i.registered_x, i.registered_y, i.registered_z
                FROM presence p LEFT JOIN items i ON p.item_uuid = i.item_uuid
                """;
        try (Statement st = connection.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                Object[] row = new Object[19];
                for (int i = 0; i < 19; i++) {
                    row[i] = rs.getObject(i + 1);
                }
                rows.add(row);
            }
        }
        return rows;
    }

    private void writerLoop() {
        while (running.get() || queue.size() > 0 || !confirmedWriteQueue.isEmpty() || !maintenanceQueue.isEmpty()) {
            try {
                // Prioridade: pedidos de escrita CONFIRMADA (ex: futuro
                // DELETE_WITH_BACKUP) sempre processados antes do lote de
                // rotina, cada um na sua própria transação, com commit
                // real garantido antes do future resolver.
                ConfirmedWriteRequest confirmed;
                while ((confirmed = confirmedWriteQueue.poll()) != null) {
                    processConfirmedWrite(confirmed);
                }

                MaintenanceRequest maintenance;
                while ((maintenance = maintenanceQueue.poll()) != null) {
                    processMaintenance(maintenance);
                }

                List<AuditTask> batch = queue.takeBatch(batchSize, flushIntervalMs);
                if (!batch.isEmpty()) {
                    writeBatch(batch);
                }
                maybeRunRetentionMaintenance();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                // FAIL_OPEN: qualquer exceção aqui é logada e o loop
                // continua - nunca deixa uma falha de auditoria derrubar a
                // thread inteira (o que pararia toda persistência futura
                // silenciosamente).
                LOGGER.error("[ItemIntegrity] Erro no writer thread do SQLite - lote descartado, continuando.", e);
            }
        }
        closeConnectionQuietly();
    }

    /**
     * Escreve e comita uma tarefa avulsa FORA do fluxo de lote de rotina,
     * com confirmação real - pra uso futuro de ações destrutivas. O
     * CompletableFuture só resolve DEPOIS do commit real acontecer (true)
     * ou de uma falha confirmada (false) - nunca antes.
     */
    public CompletableFuture<Boolean> writeAndConfirm(AuditTask task) {
        CompletableFuture<Boolean> future = new CompletableFuture<>();
        if (!running.get() || persistenceFailure != null || !confirmedWriteQueue.offer(new ConfirmedWriteRequest(task, future))) future.complete(false);
        return future;
    }

    public CompletableFuture<SqlMaintenanceResult> compactForCurrentModel() {
        CompletableFuture<SqlMaintenanceResult> future = new CompletableFuture<>();
        if (!running.get() || persistenceFailure != null) {
            future.complete(new SqlMaintenanceResult(false, persistenceFailure == null ? "SQLite encerrado" : persistenceFailure,
                    0, 0, 0, 0, -1, -1, -1, -1, -1, -1));
            return future;
        }
        if (!manualMaintenancePending.compareAndSet(false, true)) {
            future.complete(new SqlMaintenanceResult(false, "Ja existe uma manutencao SQLite em andamento.",
                    System.currentTimeMillis(), System.currentTimeMillis(), databaseSizeBytes(), databaseSizeBytes(),
                    -1, -1, -1, -1, -1, -1));
            return future;
        }
        maintenanceQueue.offer(new MaintenanceRequest(future));
        return future;
    }

    private void processConfirmedWrite(ConfirmedWriteRequest request) {
        boolean success = writeBatch(List.of(request.task()));
        request.future().complete(success);
    }

    private synchronized void processMaintenance(MaintenanceRequest request) {
        long started = System.currentTimeMillis();
        long sizeBefore = databaseSizeBytes();
        try {
            LOGGER.info("[ItemIntegrity] Compactacao SQLite: verificando integridade antes de alterar dados.");
            checkIntegrity();
            flushQueuedRoutineWrites();
            long itemEventsBefore = countRows("item_events");
            long casesBefore = countRows("integrity_cases");

            LOGGER.info("[ItemIntegrity] Compactacao SQLite: preparando e reduzindo item_events em lotes.");
            compactItemEventsInChunks();
            LOGGER.info("[ItemIntegrity] Compactacao SQLite: agregando casos possiveis em lotes.");
            compactPossibleCasesInChunks();
            discardExpiredSummaries();

            long eventRollups = countRows("item_event_rollups");
            long caseRollups = countRows("integrity_case_rollups");
            long itemEventsAfter = countRows("item_events");
            long casesAfter = countRows("integrity_cases");

            LOGGER.info("[ItemIntegrity] Compactacao SQLite: VACUUM transacional, sem trocar arquivos; nao reinicie o servidor.");
            vacuumInPlace(itemEventsAfter, casesAfter, eventRollups, caseRollups);

            long finished = System.currentTimeMillis();
            LOGGER.info("[ItemIntegrity] Compactacao SQLite concluida em {} ms.", finished - started);
            request.future().complete(new SqlMaintenanceResult(true, "OK; item_event_tail=" + countRows("item_event_tail"), started, finished, sizeBefore,
                    databaseSizeBytes(), itemEventsBefore, itemEventsAfter, casesBefore, casesAfter,
                    caseRollups, eventRollups));
        } catch (Exception e) {
            disableIfCorrupt(e);
            try {
                connection.rollback();
                connection.setAutoCommit(true);
            } catch (SQLException ignored) {
            }
            LOGGER.error("[ItemIntegrity] Falha na compactacao manual do SQLite - FAIL_OPEN, banco mantido.", e);
            long finished = System.currentTimeMillis();
            request.future().complete(new SqlMaintenanceResult(false, persistenceFailure == null ? e.getMessage() : persistenceFailure, started, finished, sizeBefore,
                    databaseSizeBytes(), -1, -1, -1, -1, -1, -1));
        } finally {
            manualMaintenancePending.set(false);
            nextRetentionMaintenanceAtMs = System.currentTimeMillis() + maintenanceIntervalMs;
        }
    }

    private void checkIntegrity() throws SQLException {
        try (Statement st = connection.createStatement(); ResultSet rs = st.executeQuery("PRAGMA quick_check(1)")) {
            if (!rs.next() || !"ok".equalsIgnoreCase(rs.getString(1))) {
                throw new SQLException("SQLITE_CORRUPT: quick_check falhou; preservar banco e recuperar uma copia offline", "", 11);
            }
        }
    }

    private void disableIfCorrupt(Exception e) {
        for (Throwable cause = e; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sql && ((sql.getErrorCode() & 255) == 11 || (sql.getErrorCode() & 255) == 26)) {
                persistenceFailure = "SQLITE_CORRUPT: persistencia suspensa (FAIL_OPEN). Compactacao nao repara corrupcao; "
                        + "preserve DB/WAL e restaure backup validado ou recupere uma copia offline.";
                return;
            }
        }
    }

    private void vacuumInPlace(long events, long cases, long eventRollups, long caseRollups) throws SQLException, IOException {
        Map<String, Long> preserved = Map.of("items", countRows("items"), "presence", countRows("presence"),
                "item_event_tail", countRows("item_event_tail"));
        long required = Math.multiplyExact(Math.max(1, databaseSizeBytes()), 2L) + 64L * 1024 * 1024;
        long available = Files.getFileStore(databaseFile.toPath().toAbsolutePath()).getUsableSpace();
        if (available < required) throw new IOException("Espaco insuficiente para VACUUM seguro: livre="
                + available + " necessario~=" + required + ". Historico reduzido; arquivo ainda nao encolheu.");
        // Let SQLite manage locking, journaling and rollback. Never unlink live WAL/SHM or swap inodes.
        try (Statement st = connection.createStatement()) {
            st.execute("PRAGMA wal_checkpoint(TRUNCATE)");
            st.execute("VACUUM");
            checkIntegrity();
            requireCount(st, "item_events", events);
            requireCount(st, "integrity_cases", cases);
            requireCount(st, "item_event_rollups", eventRollups);
            requireCount(st, "integrity_case_rollups", caseRollups);
            for (var entry : preserved.entrySet()) requireCount(st, entry.getKey(), entry.getValue());
            createHistoryIndexes(st);
            try (ResultSet rs = st.executeQuery("PRAGMA wal_checkpoint(TRUNCATE)")) {
                if (!rs.next() || rs.getInt(1) != 0) throw new SQLException("VACUUM concluido; checkpoint ocupado. Nao apague WAL.");
            }
        }
    }

    private void compactItemEventsInChunks() throws SQLException {
        try (Statement st = connection.createStatement()) {
            st.execute("DROP TABLE IF EXISTS item_integrity_event_latest_work");
            st.execute("""
                    CREATE TABLE item_integrity_event_latest_work (
                        item_uuid TEXT PRIMARY KEY,
                        latest_id INTEGER NOT NULL
                    )
                    """);
        }

        int chunks = 0;
        long lowerExclusive = 0L;
        while (true) {
            long upperInclusive = nextExistingIdBoundary("item_events", "id", lowerExclusive,
                    EVENT_MAINTENANCE_ID_SPAN);
            if (upperInclusive <= lowerExclusive) {
                break;
            }
            connection.setAutoCommit(false);
            try (Statement st = connection.createStatement()) {
                st.executeUpdate("""
                        INSERT INTO item_integrity_event_latest_work (item_uuid, latest_id)
                        SELECT COALESCE(item_uuid, ''), MAX(id)
                        FROM item_events
                        WHERE id > %d AND id <= %d
                        GROUP BY COALESCE(item_uuid, '')
                        ON CONFLICT(item_uuid) DO UPDATE SET
                            latest_id = MAX(item_integrity_event_latest_work.latest_id, excluded.latest_id)
                        """.formatted(lowerExclusive, upperInclusive));
                connection.commit();
            } catch (SQLException e) {
                connection.rollback();
                throw e;
            } finally {
                connection.setAutoCommit(true);
            }
            checkpointChunk(++chunks);
            lowerExclusive = upperInclusive;
        }

        chunks = 0;
        lowerExclusive = 0L;
        while (true) {
            long upperInclusive = nextExistingIdBoundary("item_events", "id", lowerExclusive,
                    EVENT_MAINTENANCE_ID_SPAN);
            if (upperInclusive <= lowerExclusive) {
                break;
            }
            String rows = "id > " + lowerExclusive + " AND id <= " + upperInclusive
                    + " AND id <> COALESCE((SELECT latest_id FROM item_integrity_event_latest_work work "
                    + "WHERE work.item_uuid = COALESCE(item_events.item_uuid, '')), -1)";
            connection.setAutoCommit(false);
            try (Statement st = connection.createStatement()) {
                st.executeUpdate("DELETE FROM item_events WHERE " + rows);
                connection.commit();
            } catch (SQLException e) {
                connection.rollback();
                throw e;
            } finally {
                connection.setAutoCommit(true);
            }
            checkpointChunk(++chunks);
            lowerExclusive = upperInclusive;
        }

        try (Statement st = connection.createStatement()) {
            st.executeUpdate("""
                    INSERT INTO item_event_tail
                    SELECT item_uuid, COALESCE(event_type, 'PRESENCE_COMMITTED'), time,
                           player_uuid, player_name, entity_uuid, world, x, y, z,
                           holder_type, slot, state, revision, auto_committed
                    FROM item_events WHERE item_uuid IS NOT NULL
                    ON CONFLICT(item_uuid, event_type) DO UPDATE SET
                        time=excluded.time, player_uuid=excluded.player_uuid,
                        player_name=excluded.player_name, entity_uuid=excluded.entity_uuid,
                        world=excluded.world, x=excluded.x, y=excluded.y, z=excluded.z,
                        holder_type=excluded.holder_type, slot=excluded.slot,
                        state=excluded.state, revision=excluded.revision,
                        auto_committed=excluded.auto_committed
                    WHERE excluded.time > item_event_tail.time
                    """);
            st.executeUpdate("DELETE FROM item_events");
            st.execute("DROP TABLE IF EXISTS item_integrity_event_latest_work");
            st.execute("PRAGMA wal_checkpoint(TRUNCATE)");
        }
    }

    private void compactPossibleCasesInChunks() throws SQLException {
        String possible = "NOT (decision = 'CONFIRMED_DUPLICATE' OR action IN ('REMOVED','WOULD_REMOVE') "
                + "OR decision = 'RESURRECTED_ITEM' OR action LIKE 'DELETE_ABORTED%')";
        int chunks = 0;
        long lowerExclusive = 0L;
        while (true) {
            long upperInclusive = nextExistingIdBoundary("integrity_cases", "rowid", lowerExclusive,
                    CASE_MAINTENANCE_ROWID_SPAN);
            if (upperInclusive <= lowerExclusive) {
                break;
            }
            String rows = possible + " AND rowid > " + lowerExclusive + " AND rowid <= " + upperInclusive;
            connection.setAutoCommit(false);
            try (Statement st = connection.createStatement()) {
                rollUpCases(st, rows);
                st.executeUpdate("DELETE FROM integrity_cases WHERE " + rows);
                connection.commit();
            } catch (SQLException e) {
                connection.rollback();
                throw e;
            } finally {
                connection.setAutoCommit(true);
            }
            checkpointChunk(++chunks);
            lowerExclusive = upperInclusive;
        }
        try (Statement st = connection.createStatement()) {
            st.execute("PRAGMA wal_checkpoint(TRUNCATE)");
        }
    }

    private void checkpointChunk(int chunk) throws SQLException {
        if (chunk % CHECKPOINT_EVERY_CHUNKS != 0) {
            return;
        }
        try (Statement st = connection.createStatement()) {
            st.execute("PRAGMA wal_checkpoint(TRUNCATE)");
        }
    }

    private long nextExistingIdBoundary(String table, String idColumn, long lowerExclusive, long limit)
            throws SQLException {
        try (Statement st = connection.createStatement()) {
            return scalarLong(st, "SELECT COALESCE(MAX(" + idColumn + "), 0) FROM (SELECT " + idColumn
                    + " FROM " + table + " WHERE " + idColumn + " > " + lowerExclusive
                    + " ORDER BY " + idColumn + " LIMIT " + limit + ")");
        }
    }

    private void closeConnectionQuietly() {
        try { if (connection != null) connection.close(); }
        catch (SQLException ignored) { }
    }

    private void createHistoryIndexes(Statement st) throws SQLException {
        st.execute("CREATE INDEX IF NOT EXISTS idx_item_events_item_uuid ON item_events(item_uuid)");
        st.execute("CREATE INDEX IF NOT EXISTS idx_item_events_time ON item_events(time)");
        st.execute("CREATE INDEX IF NOT EXISTS idx_integrity_cases_created_at ON integrity_cases(created_at)");
    }

    private void rollUpItemEvents(Statement st, String whereClause) throws SQLException {
        st.executeUpdate("""
                WITH source AS (
                    SELECT id, item_uuid, time, event_type, player_uuid, player_name, entity_uuid, world,
                           x, y, z, holder_type, slot, state, revision,
                           COALESCE(item_uuid, '') || char(31) || COALESCE(event_type, '') || char(31)
                           || COALESCE(holder_type, '') || char(31) || COALESCE(player_uuid, '') || char(31)
                           || COALESCE(entity_uuid, '') || char(31) || COALESCE(world, '') || char(31)
                           || COALESCE(x, '') || char(31) || COALESCE(y, '') || char(31) || COALESCE(z, '') || char(31)
                           || COALESCE(state, '') AS stable_event_key
                    FROM item_events
                    WHERE %s
                ), ranked AS (
                    SELECT *, ROW_NUMBER() OVER (PARTITION BY stable_event_key ORDER BY time DESC, id DESC) AS newest
                    FROM source
                ), aggregated AS (
                    SELECT stable_event_key, item_uuid, event_type, holder_type, player_uuid, entity_uuid, world, x, y, z,
                           MIN(time) AS first_seen, MAX(time) AS last_seen, COUNT(*) AS occurrence_count,
                           MAX(CASE WHEN newest = 1 THEN player_name END) AS player_name,
                           MAX(CASE WHEN newest = 1 THEN slot END) AS slot,
                           MAX(CASE WHEN newest = 1 THEN state END) AS state,
                           MAX(CASE WHEN newest = 1 THEN revision END) AS last_revision
                    FROM ranked
                    GROUP BY stable_event_key, item_uuid, event_type, holder_type, player_uuid, entity_uuid, world, x, y, z
                )
                INSERT INTO item_event_rollups (event_key, item_uuid, event_type, holder_type, player_uuid, player_name,
                                                entity_uuid, world, x, y, z, slot, state, first_seen, last_seen,
                                                occurrence_count, last_revision)
                SELECT stable_event_key, item_uuid, event_type, holder_type, player_uuid, player_name, entity_uuid,
                       world, x, y, z, slot, state, first_seen, last_seen, occurrence_count, last_revision
                FROM aggregated
                WHERE 1
                ON CONFLICT(event_key) DO UPDATE SET
                    first_seen = MIN(item_event_rollups.first_seen, excluded.first_seen),
                    last_seen = MAX(item_event_rollups.last_seen, excluded.last_seen),
                    occurrence_count = item_event_rollups.occurrence_count + excluded.occurrence_count,
                    player_name = CASE WHEN excluded.last_seen >= item_event_rollups.last_seen
                                       THEN excluded.player_name ELSE item_event_rollups.player_name END,
                    slot = CASE WHEN excluded.last_seen >= item_event_rollups.last_seen
                                THEN excluded.slot ELSE item_event_rollups.slot END,
                    last_revision = CASE WHEN excluded.last_seen >= item_event_rollups.last_seen
                                         THEN excluded.last_revision ELSE item_event_rollups.last_revision END
                """.formatted(whereClause));
    }

    private void rollUpCases(Statement st, String whereClause) throws SQLException {
        st.executeUpdate("""
                WITH normalized AS (
                    SELECT rowid AS source_rowid, case_id, created_at, mode, decision, action, confidence, reason,
                           item_uuid, material, canonical_summary, conflicting_summary, conflicting_item_summary,
                           incident_key,
                           CASE WHEN instr(canonical_summary, ' rev=') > 0
                                THEN substr(canonical_summary, 1, instr(canonical_summary, ' rev=') - 1)
                                ELSE COALESCE(canonical_summary, '') END AS canonical_stable,
                           CASE WHEN instr(conflicting_summary, ' rev=') > 0
                                THEN substr(conflicting_summary, 1, instr(conflicting_summary, ' rev=') - 1)
                                ELSE COALESCE(conflicting_summary, '') END AS conflicting_stable
                    FROM integrity_cases
                    WHERE %s
                ), source AS (
                    SELECT *, COALESCE(NULLIF(incident_key, ''),
                           COALESCE(item_uuid, '') || char(31) || canonical_stable || char(31)
                           || conflicting_stable || char(31) || COALESCE(reason, '') || char(31)
                           || COALESCE(mode, '')) AS stable_incident_key
                    FROM normalized
                ), ranked AS (
                    SELECT *,
                           ROW_NUMBER() OVER (PARTITION BY stable_incident_key ORDER BY created_at ASC, case_id ASC) AS oldest,
                           ROW_NUMBER() OVER (PARTITION BY stable_incident_key ORDER BY created_at DESC, case_id DESC) AS newest
                    FROM source
                ), aggregated AS (
                    SELECT stable_incident_key,
                           MAX(CASE WHEN oldest = 1 THEN case_id END) AS first_case_id,
                           MAX(CASE WHEN newest = 1 THEN case_id END) AS last_case_id,
                           MIN(created_at) AS first_seen, MAX(created_at) AS last_seen, COUNT(*) AS occurrence_count,
                           MAX(CASE WHEN newest = 1 THEN mode END) AS mode,
                           MAX(CASE WHEN newest = 1 THEN decision END) AS decision,
                           MAX(CASE WHEN newest = 1 THEN action END) AS action,
                           MAX(CASE WHEN newest = 1 THEN confidence END) AS confidence,
                           MAX(CASE WHEN newest = 1 THEN reason END) AS reason,
                           MAX(CASE WHEN newest = 1 THEN item_uuid END) AS item_uuid,
                           MAX(CASE WHEN newest = 1 THEN material END) AS material,
                           MAX(CASE WHEN newest = 1 THEN canonical_summary END) AS canonical_summary,
                           MAX(CASE WHEN newest = 1 THEN conflicting_summary END) AS conflicting_summary,
                           MAX(CASE WHEN newest = 1 THEN conflicting_item_summary END) AS last_item_summary,
                           MAX(CASE WHEN decision = 'CONFIRMED_DUPLICATE' OR action = 'REMOVED'
                                         OR decision = 'RESURRECTED_ITEM' OR action LIKE 'DELETE_ABORTED%%'
                                    THEN 1 ELSE 0 END) AS confirmed
                    FROM ranked
                    GROUP BY stable_incident_key
                )
                INSERT INTO integrity_case_rollups (incident_key, first_case_id, last_case_id, first_seen, last_seen,
                                                    occurrence_count, mode, decision, action, confidence, reason,
                                                    item_uuid, material, canonical_summary, conflicting_summary,
                                                    last_conflicting_item_summary, confirmed)
                SELECT stable_incident_key, first_case_id, last_case_id, first_seen, last_seen, occurrence_count,
                       mode, decision, action, confidence, reason, item_uuid, material, canonical_summary,
                       conflicting_summary, last_item_summary, confirmed
                FROM aggregated
                WHERE 1
                ON CONFLICT(incident_key) DO UPDATE SET
                    first_seen = MIN(integrity_case_rollups.first_seen, excluded.first_seen),
                    last_seen = MAX(integrity_case_rollups.last_seen, excluded.last_seen),
                    occurrence_count = integrity_case_rollups.occurrence_count + excluded.occurrence_count,
                    last_case_id = excluded.last_case_id,
                    mode = excluded.mode,
                    decision = excluded.decision,
                    action = excluded.action,
                    confidence = excluded.confidence,
                    reason = excluded.reason,
                    material = excluded.material,
                    canonical_summary = excluded.canonical_summary,
                    conflicting_summary = excluded.conflicting_summary,
                    last_conflicting_item_summary = excluded.last_conflicting_item_summary,
                    confirmed = MAX(integrity_case_rollups.confirmed, excluded.confirmed)
                """.formatted(whereClause));
    }

    private synchronized void maybeRunRetentionMaintenance() {
        if (persistenceFailure != null) return;
        long now = System.currentTimeMillis();
        if (now < nextRetentionMaintenanceAtMs || manualMaintenancePending.get()) {
            return;
        }
        nextRetentionMaintenanceAtMs = now + maintenanceIntervalMs;
        try {
            // Legacy bulk migration is explicit via /istack restart sql.
            discardExpiredSummaries();
            try (Statement st = connection.createStatement()) {
                st.execute("PRAGMA incremental_vacuum(2000)");
                st.execute("PRAGMA optimize");
            }
        } catch (SQLException e) {
            disableIfCorrupt(e);
            try {
                connection.rollback();
                connection.setAutoCommit(true);
            } catch (SQLException ignored) {
            }
            LOGGER.warn("[ItemIntegrity] Manutencao incremental do SQLite falhou; tentando novamente no proximo ciclo: {}",
                    e.getMessage());
        }
    }

    private void discardExpiredSummaries() throws SQLException {
        long cutoff = System.currentTimeMillis() - historyRetentionMs;
        int chunks = 0;
        for (String table : List.of("item_event_rollups", "integrity_case_rollups")) {
            String filter = table.equals("item_event_rollups") ? "1=1"
                    : "COALESCE(confirmed,0)=0 AND COALESCE(action,'') NOT IN ('REMOVED','WOULD_REMOVE') "
                      + "AND COALESCE(action,'') NOT LIKE 'DELETE_ABORTED%' "
                      + "AND COALESCE(decision,'') NOT IN ('CONFIRMED_DUPLICATE','RESURRECTED_ITEM') AND last_seen < " + cutoff;
            int removed;
            do {
                try (Statement st = connection.createStatement()) {
                    removed = st.executeUpdate("DELETE FROM " + table + " WHERE rowid IN (SELECT rowid FROM "
                            + table + " WHERE " + filter + " LIMIT 1000)");
                }
                checkpointChunk(++chunks);
            } while (removed > 0);
        }
    }

    private void flushQueuedRoutineWrites() throws InterruptedException, SQLException {
        int remaining = queue.size();
        while (remaining > 0) {
            List<AuditTask> batch = queue.takeBatch(Math.min(remaining, batchSize), 0);
            if (batch.isEmpty()) {
                return;
            }
            if (!writeBatch(batch)) {
                throw new SQLException("Falha ao descarregar AuditQueue antes da manutencao");
            }
            remaining -= batch.size();
        }
    }

    private long countRows(String table) throws SQLException {
        try (Statement st = connection.createStatement()) {
            return scalarLong(st, "SELECT COUNT(*) FROM " + table);
        }
    }

    private void requireCount(Statement st, String table, long expected) throws SQLException {
        long actual = scalarLong(st, "SELECT COUNT(*) FROM " + table);
        if (actual != expected) throw new SQLException("Validacao falhou para " + table
                + ": esperado=" + expected + ", atual=" + actual);
    }

    private long scalarLong(Statement st, String sql) throws SQLException {
        try (ResultSet rs = st.executeQuery(sql)) {
            return rs.next() ? rs.getLong(1) : 0L;
        }
    }

    private long databaseSizeBytes() {
        long total = fileSize(databaseFile.toPath());
        total += fileSize(databaseFile.toPath().resolveSibling(databaseFile.getName() + "-wal"));
        total += fileSize(databaseFile.toPath().resolveSibling(databaseFile.getName() + "-shm"));
        return total;
    }

    private long fileSize(java.nio.file.Path path) {
        try {
            return Files.exists(path) ? Files.size(path) : 0L;
        } catch (IOException e) {
            return 0L;
        }
    }

    /** @return true se o lote foi commitado com sucesso, false se houve rollback (erro logado internamente - FAIL_OPEN). */
    private synchronized boolean writeBatch(List<AuditTask> batch) {
        if (persistenceFailure != null) return false;
        batch = coalescePresenceWrites(batch);
        try {
            connection.setAutoCommit(false);
            try (PreparedStatement insertItem = connection.prepareStatement(
                    "INSERT INTO items (item_uuid, material, created_at, origin, registered_world, registered_x, registered_y, registered_z, "
                            + "first_owner, last_owner, first_seen, last_seen, status, fingerprint) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'ACTIVE', NULL) "
                            + "ON CONFLICT(item_uuid) DO NOTHING");
                 PreparedStatement insertEvent = connection.prepareStatement(
                         "INSERT INTO item_event_tail (item_uuid, time, event_type, player_uuid, player_name, entity_uuid, "
                                 + "world, x, y, z, holder_type, slot, state, revision, auto_committed) "
                                 + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) "
                                 + "ON CONFLICT(item_uuid,event_type) DO UPDATE SET time=excluded.time, "
                                 + "player_uuid=excluded.player_uuid, player_name=excluded.player_name, "
                                 + "entity_uuid=excluded.entity_uuid, world=excluded.world, x=excluded.x, y=excluded.y, "
                                 + "z=excluded.z, holder_type=excluded.holder_type, slot=excluded.slot, "
                                 + "state=excluded.state, revision=excluded.revision, auto_committed=excluded.auto_committed "
                                 + "WHERE excluded.time >= item_event_tail.time");
                 PreparedStatement updateItemSeen = connection.prepareStatement(
                         "UPDATE items SET last_owner = ?, last_seen = ? WHERE item_uuid = ?");
                 PreparedStatement upsertPresence = connection.prepareStatement(
                         "INSERT INTO presence (item_uuid, holder_type, world, x, y, z, player_uuid, player_name, slot, entity_uuid, state, revision, last_confirmed_at) "
                                 + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) "
                                 + "ON CONFLICT(item_uuid) DO UPDATE SET holder_type=excluded.holder_type, world=excluded.world, "
                                 + "x=excluded.x, y=excluded.y, z=excluded.z, player_uuid=excluded.player_uuid, player_name=excluded.player_name, "
                                 + "slot=excluded.slot, entity_uuid=excluded.entity_uuid, state=excluded.state, revision=excluded.revision, "
                                 + "last_confirmed_at=excluded.last_confirmed_at "
                                 + "WHERE excluded.last_confirmed_at > presence.last_confirmed_at "
                                 + "OR (excluded.last_confirmed_at = presence.last_confirmed_at "
                                 + "AND excluded.revision >= presence.revision)");
                 PreparedStatement insertCase = connection.prepareStatement(
                         "INSERT INTO integrity_cases (case_id, created_at, mode, decision, action, confidence, reason, item_uuid, material, "
                                 + "canonical_summary, conflicting_summary, conflicting_item_summary, conflicting_item_snapshot, incident_key) "
                                 + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) "
                                 + "ON CONFLICT(case_id) DO NOTHING");
                 PreparedStatement upsertCaseRollup = connection.prepareStatement(
                         "INSERT INTO integrity_case_rollups (incident_key, first_case_id, last_case_id, first_seen, last_seen, "
                                 + "occurrence_count, mode, decision, action, confidence, reason, item_uuid, material, canonical_summary, "
                                 + "conflicting_summary, last_conflicting_item_summary, confirmed) "
                                 + "VALUES (?, ?, ?, ?, ?, 1, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0) "
                                 + "ON CONFLICT(incident_key) DO UPDATE SET last_case_id=excluded.last_case_id, "
                                 + "last_seen=excluded.last_seen, occurrence_count=COALESCE(integrity_case_rollups.occurrence_count, 0)+1, "
                                 + "mode=excluded.mode, decision=excluded.decision, action=excluded.action, confidence=excluded.confidence, "
                                 + "reason=excluded.reason, material=excluded.material, canonical_summary=excluded.canonical_summary, "
                                 + "conflicting_summary=excluded.conflicting_summary, "
                                 + "last_conflicting_item_summary=excluded.last_conflicting_item_summary")) {

                for (AuditTask task : batch) {
                    if (task instanceof AuditTask.RegisterItem(ItemSnapshot s)) {
                        insertItem.setString(1, s.itemId());
                        insertItem.setString(2, s.material());
                        insertItem.setLong(3, s.createdAtEpochMs());
                        insertItem.setString(4, s.origin());
                        insertItem.setString(5, s.registeredWorld());
                        setNullableInt(insertItem, 6, s.registeredX());
                        setNullableInt(insertItem, 7, s.registeredY());
                        setNullableInt(insertItem, 8, s.registeredZ());
                        insertItem.setString(9, s.firstOwnerUuid());
                        insertItem.setString(10, s.firstOwnerUuid());
                        insertItem.setLong(11, s.firstSeenEpochMs());
                        insertItem.setLong(12, s.firstSeenEpochMs());
                        insertItem.addBatch();
                    } else if (task instanceof AuditTask.UpdatePresence(PresenceUpdateSnapshot p)) {
                        // SÓ atualiza presence - de propósito NÃO toca em
                        // item_events nem em items.last_seen. Isso é
                        // exatamente o caminho de "reorganizou o
                        // inventário"/reconfirmação de rotina que não deve
                        // virar histórico permanente (ver ponto 2/3 da
                        // revisão final).
                        upsertPresence.setString(1, p.itemId());
                        upsertPresence.setString(2, p.holderType());
                        upsertPresence.setString(3, p.world());
                        setNullableInt(upsertPresence, 4, p.x());
                        setNullableInt(upsertPresence, 5, p.y());
                        setNullableInt(upsertPresence, 6, p.z());
                        upsertPresence.setString(7, p.playerUuid());
                        upsertPresence.setString(8, p.playerName());
                        setNullableInt(upsertPresence, 9, p.slot());
                        upsertPresence.setString(10, p.entityUuid());
                        upsertPresence.setString(11, p.state());
                        upsertPresence.setInt(12, p.presenceRevision());
                        upsertPresence.setLong(13, p.timeEpochMs());
                        upsertPresence.addBatch();
                    } else if (task instanceof AuditTask.RecordEvent(ItemEventSnapshot e)) {
                        insertEvent.setString(1, e.itemId());
                        insertEvent.setLong(2, e.timeEpochMs());
                        insertEvent.setString(3, e.eventType().name());
                        insertEvent.setString(4, e.playerUuid());
                        insertEvent.setString(5, e.playerName());
                        insertEvent.setString(6, e.entityUuid());
                        insertEvent.setString(7, e.world());
                        setNullableInt(insertEvent, 8, e.x());
                        setNullableInt(insertEvent, 9, e.y());
                        setNullableInt(insertEvent, 10, e.z());
                        insertEvent.setString(11, e.holderType());
                        setNullableInt(insertEvent, 12, e.slot());
                        insertEvent.setString(13, e.state());
                        insertEvent.setInt(14, e.presenceRevision());
                        insertEvent.setInt(15, e.autoCommitted() ? 1 : 0);
                        insertEvent.addBatch();

                        updateItemSeen.setString(1, e.playerUuid());
                        updateItemSeen.setLong(2, e.timeEpochMs());
                        updateItemSeen.setString(3, e.itemId());
                        updateItemSeen.addBatch();

                        if (e.autoCommitted()) {
                            upsertPresence.setString(1, e.itemId());
                            upsertPresence.setString(2, e.holderType());
                            upsertPresence.setString(3, e.world());
                            setNullableInt(upsertPresence, 4, e.x());
                            setNullableInt(upsertPresence, 5, e.y());
                            setNullableInt(upsertPresence, 6, e.z());
                            upsertPresence.setString(7, e.playerUuid());
                            upsertPresence.setString(8, e.playerName());
                            setNullableInt(upsertPresence, 9, e.slot());
                            upsertPresence.setString(10, e.entityUuid());
                            upsertPresence.setString(11, e.state());
                            upsertPresence.setInt(12, e.presenceRevision());
                            upsertPresence.setLong(13, e.timeEpochMs());
                            upsertPresence.addBatch();
                        }
                    } else if (task instanceof AuditTask.PersistCase(IntegrityCaseSnapshot c)) {
                        if (isDetailedCase(c)) {
                            insertCase.setString(1, c.caseId());
                            insertCase.setLong(2, c.createdAtEpochMs());
                            insertCase.setString(3, c.mode());
                            insertCase.setString(4, c.decision());
                            insertCase.setString(5, c.action());
                            insertCase.setString(6, c.confidence());
                            insertCase.setString(7, c.reason());
                            insertCase.setString(8, c.itemId());
                            insertCase.setString(9, c.material());
                            insertCase.setString(10, c.canonicalSummary());
                            insertCase.setString(11, c.conflictingSummary());
                            insertCase.setString(12, c.conflictingItemSummary());
                            insertCase.setBytes(13, c.conflictingItemSnapshot());
                            insertCase.setString(14, c.incidentKey());
                            insertCase.addBatch();
                        } else {
                            upsertCaseRollup.setString(1, c.incidentKey());
                            upsertCaseRollup.setString(2, c.caseId());
                            upsertCaseRollup.setString(3, c.caseId());
                            upsertCaseRollup.setLong(4, c.createdAtEpochMs());
                            upsertCaseRollup.setLong(5, c.createdAtEpochMs());
                            upsertCaseRollup.setString(6, c.mode());
                            upsertCaseRollup.setString(7, c.decision());
                            upsertCaseRollup.setString(8, c.action());
                            upsertCaseRollup.setString(9, c.confidence());
                            upsertCaseRollup.setString(10, c.reason());
                            upsertCaseRollup.setString(11, c.itemId());
                            upsertCaseRollup.setString(12, c.material());
                            upsertCaseRollup.setString(13, c.canonicalSummary());
                            upsertCaseRollup.setString(14, c.conflictingSummary());
                            upsertCaseRollup.setString(15, c.conflictingItemSummary());
                            upsertCaseRollup.addBatch();
                        }
                    }
                }

                insertItem.executeBatch();
                insertEvent.executeBatch();
                updateItemSeen.executeBatch();
                upsertPresence.executeBatch();
                insertCase.executeBatch();
                upsertCaseRollup.executeBatch();
            }
            connection.commit();
            return true;
        } catch (SQLException e) {
            disableIfCorrupt(e);
            long now = System.currentTimeMillis();
            if (now - lastWriteErrorMs >= 60_000L) {
                lastWriteErrorMs = now;
                LOGGER.error("[ItemIntegrity] Falha SQLite - lote perdido (FAIL_OPEN); avisos limitados a 1/minuto.", e);
            }
            try {
                connection.rollback();
            } catch (SQLException ignored) {
            }
            return false;
        } finally {
            try {
                connection.setAutoCommit(true);
            } catch (SQLException ignored) {
            }
        }
    }

    private List<AuditTask> coalescePresenceWrites(List<AuditTask> batch) {
        Map<String, AuditTask.UpdatePresence> latest = new LinkedHashMap<>();
        List<AuditTask> result = new ArrayList<>();
        for (AuditTask task : batch) {
            if (task instanceof AuditTask.UpdatePresence update) {
                latest.merge(update.snapshot().itemId(), update, (a, b) ->
                        a.snapshot().timeEpochMs() > b.snapshot().timeEpochMs() ? a : b);
            } else {
                result.addAll(latest.values());
                latest.clear();
                result.add(task);
            }
        }
        result.addAll(latest.values());
        return result;
    }

    private void setNullableInt(PreparedStatement ps, int index, Integer value) throws SQLException {
        if (value == null) {
            ps.setNull(index, java.sql.Types.INTEGER);
        } else {
            ps.setInt(index, value);
        }
    }

    private boolean isDetailedCase(IntegrityCaseSnapshot snapshot) {
        return "CONFIRMED_DUPLICATE".equals(snapshot.decision())
                || "WOULD_REMOVE".equals(snapshot.action())
                || "RESURRECTED_ITEM".equals(snapshot.decision())
                || "REMOVED".equals(snapshot.action())
                || snapshot.action().startsWith("DELETE_ABORTED");
    }

    /** Sinaliza o writer thread pra terminar depois de esvaziar a fila, e fecha a conexão. Chamado do onDisable(). */
    public void shutdown() {
        running.set(false);
        try {
            writerThread.join(5000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (writerThread.isAlive()) {
            LOGGER.warn("[ItemIntegrity] Writer SQLite ainda executa manutencao; conexao nao sera fechada por outra thread.");
            return;
        }
        try {
            connection.close();
        } catch (SQLException ignored) {
        }
    }
}
