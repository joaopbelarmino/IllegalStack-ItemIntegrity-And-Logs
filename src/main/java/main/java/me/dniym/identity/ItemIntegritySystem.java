package main.java.me.dniym.identity;

import main.java.me.dniym.IllegalStack;
import main.java.me.dniym.identity.audit.AuditQueue;
import main.java.me.dniym.identity.audit.DatabaseService;
import main.java.me.dniym.identity.config.ItemIntegrityConfig;
import main.java.me.dniym.identity.conflict.CaseFileLogger;
import main.java.me.dniym.identity.conflict.ConflictDetector;
import main.java.me.dniym.identity.conflict.DiscordWebhookNotifier;
import main.java.me.dniym.identity.listeners.IdentityPlayerInventoryListener;
import main.java.me.dniym.identity.listeners.ItemIntegrityLifecycleListener;
import main.java.me.dniym.identity.listeners.ShulkerIdentityListener;
import main.java.me.dniym.identity.presence.InMemoryPresenceStore;
import main.java.me.dniym.identity.presence.PresenceStore;
import main.java.me.dniym.identity.presence.SqliteBackedPresenceStore;

import java.io.File;
import java.util.concurrent.CompletableFuture;

/**
 * Ponto único de composição do subsistema Item Integrity. Instanciado uma
 * vez no onEnable() do IllegalStack.
 *
 * Etapas implementadas: 1 (IdentityService/PDC), 2 (MigrationService/
 * LEGACY_IMPORT), 3 (PresenceStore - canonical vs observação), 4 (SQLite/
 * AuditQueue - persistência e hidratação, write-through assíncrono).
 *
 * FAIL_OPEN: se o SQLite não conseguir inicializar por qualquer motivo
 * (arquivo bloqueado, driver ausente, disco cheio, etc), o sistema
 * continua funcionando com PresenceStore 100% em memória (mesmo
 * comportamento das etapas 1-3) em vez de derrubar o plugin inteiro ou
 * bloquear o servidor.
 */
public final class ItemIntegritySystem {
    private IdentityPlayerInventoryListener playerInventoryListener;
    public String scannerMetrics() {
        return playerInventoryListener == null ? "disabled" : playerInventoryListener.metrics() + " " + conflictDetector.metrics();
    }

    private final ItemIntegrityConfig config;
    private final IdentityService identityService;
    private final PresenceStore presenceStore;
    private final MigrationService migrationService;
    private final AuditQueue auditQueue; // null se o SQLite falhou ao iniciar
    private final DatabaseService databaseService; // null se o SQLite falhou ao iniciar
    private final DiscordWebhookNotifier webhookNotifier;
    private final CaseFileLogger caseFileLogger;
    private final ConflictDetector conflictDetector;

    public ItemIntegritySystem(IllegalStack plugin) {
        this.config = new ItemIntegrityConfig(plugin);
        this.identityService = new IdentityService(config);
        this.migrationService = new MigrationService(identityService);

        AuditQueue queue = null;
        DatabaseService db = null;
        PresenceStore store;

        if (config.isEnabled()) {
            try {
                queue = new AuditQueue(config.sqliteQueueCapacity());
                File dbFile = new File(plugin.getDataFolder(), config.sqliteFileName());
                db = new DatabaseService(dbFile, queue, config.sqliteBatchSize(), config.sqliteFlushIntervalMs(),
                        config.sqliteHistoryRetentionDays(), config.sqliteMaintenanceIntervalMs());
                store = new SqliteBackedPresenceStore(db, queue);
            } catch (Exception e) {
                plugin.getLogger().warning("[ItemIntegrity] SQLite falhou ao iniciar ("
                        + e.getMessage() + ") - caindo para modo em memória (FAIL_OPEN, sem persistência/auditoria).");
                queue = null;
                if (db != null) db.shutdown();
                db = null;
                store = new InMemoryPresenceStore();
            }
        } else {
            store = new InMemoryPresenceStore();
        }

        this.auditQueue = queue;
        this.databaseService = db;
        store = new main.java.me.dniym.identity.presence.DestinationTrackingStore(store,
                new main.java.me.dniym.identity.presence.DestinationWindow(config.revalidationPendingLimit(),
                        config.revalidationDestinationLimit(), 30_000, System::currentTimeMillis));
        this.presenceStore = store;
        VirtualCustodyService virtualCustodyService = new VirtualCustodyService(identityService, presenceStore);
        this.webhookNotifier = new DiscordWebhookNotifier(config);
        this.caseFileLogger = new CaseFileLogger(plugin);
        this.conflictDetector = new ConflictDetector(plugin, config, identityService, databaseService,
                webhookNotifier, caseFileLogger, presenceStore);

        if (config.isEnabled()) {
            playerInventoryListener = new IdentityPlayerInventoryListener(plugin,
                    migrationService, presenceStore, auditQueue, conflictDetector, config, virtualCustodyService, identityService);
            new ItemIntegrityLifecycleListener(plugin, identityService, migrationService, presenceStore, auditQueue,
                    conflictDetector, playerInventoryListener, virtualCustodyService);
            new ShulkerIdentityListener(plugin, identityService, presenceStore);
            plugin.getLogger().info("[ItemIntegrity] Habilitado - etapas 1-4 (IdentityService, MigrationService, "
                    + "PresenceStore, " + (databaseService != null ? "SQLite ativo" : "SQLite indisponível, em memória")
                    + "). Modo observação, sem remoção automática.");
        }
    }

    /** Encerra o writer thread do SQLite de forma limpa - chamado do onDisable(). */
    public void shutdown() {
        if (databaseService != null) {
            databaseService.shutdown();
        }
        webhookNotifier.shutdown();
        caseFileLogger.shutdown();
    }

    public ItemIntegrityConfig config() {
        return config;
    }

    public IdentityService identityService() {
        return identityService;
    }

    public PresenceStore presenceStore() {
        return presenceStore;
    }

    public MigrationService migrationService() {
        return migrationService;
    }

    public CompletableFuture<DatabaseService.SqlMaintenanceResult> compactSqlForCurrentModel() {
        if (databaseService == null) {
            return CompletableFuture.completedFuture(new DatabaseService.SqlMaintenanceResult(false,
                    "SQLite indisponivel; sistema esta em memoria.", System.currentTimeMillis(),
                    System.currentTimeMillis(), -1, -1, -1, -1, -1, -1, -1, -1));
        }
        return databaseService.compactForCurrentModel();
    }
}
