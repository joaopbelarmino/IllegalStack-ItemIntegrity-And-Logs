package main.java.me.dniym.identity.config;

import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;

import main.java.me.dniym.identity.conflict.ConflictMode;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.time.ZoneId;

/**
 * Config própria do subsistema Item Integrity, separada do config.yml
 * principal do IllegalStack: plugins/IllegalStack/item-integrity.yml.
 *
 * O modelo do IllegalStack (Protections enum, tudo boolean/int flat) não
 * comporta a config estruturada que este subsistema precisa (mode,
 * thresholds, webhook, etc), por isso um arquivo próprio.
 *
 * Esta etapa só usa enabled/migration.mode/identity.timezone. As demais
 * seções ficam no arquivo como esqueleto documentado pras etapas futuras.
 */
public final class ItemIntegrityConfig {

    private final File file;
    private YamlConfiguration yaml;

    public int revalidationPendingLimit() { return Math.max(16, Math.min(8192, yaml.getInt("conflict-detector.revalidation.max-pending", 2048))); }
    public int revalidationDestinationLimit() { return Math.max(2, Math.min(16, yaml.getInt("conflict-detector.revalidation.max-destinations", 8))); }
    public boolean slotEventsEnabled() { return yaml.getBoolean("scanner.slot-events", true); }
    public long reconciliationPeriodTicks() { return Math.max(20, yaml.getLong("scanner.reconciliation-period-ticks", 20)); }

    public ItemIntegrityConfig(Plugin plugin) {
        this.file = new File(plugin.getDataFolder(), "item-integrity.yml");
        load(plugin);
    }

    private void load(Plugin plugin) {
        if (!file.exists()) {
            plugin.getDataFolder().mkdirs();
            writeDefaultTemplate(plugin);
        }
        this.yaml = YamlConfiguration.loadConfiguration(file);

        boolean changed = false;
        for (var entry : java.util.Map.<String, Object>of(
                "conflict-detector.revalidation.max-pending", 2048,
                "conflict-detector.revalidation.max-destinations", 8,
                "scanner.slot-events", true, "scanner.reconciliation-period-ticks", 20).entrySet()) {
            if (!yaml.isSet(entry.getKey())) { yaml.set(entry.getKey(), entry.getValue()); changed = true; }
        }
        if (!yaml.isSet("enabled")) {
            yaml.set("enabled", true);
            changed = true;
        }
        if (!yaml.isSet("identity.timezone")) {
            yaml.set("identity.timezone", "SYSTEM_DEFAULT");
            changed = true;
        }
        if (!yaml.isSet("migration.mode")) {
            yaml.set("migration.mode", true);
            changed = true;
        }
        if (!yaml.isSet("sqlite.file")) {
            yaml.set("sqlite.file", "item-integrity.db");
            changed = true;
        }
        if (!yaml.isSet("sqlite.batch-size")) {
            yaml.set("sqlite.batch-size", 200);
            changed = true;
        }
        if (!yaml.isSet("sqlite.flush-interval-ms")) {
            yaml.set("sqlite.flush-interval-ms", 1000);
            changed = true;
        }
        if (!yaml.isSet("sqlite.queue-capacity")) {
            yaml.set("sqlite.queue-capacity", 20000);
            changed = true;
        }
        if (!yaml.isSet("sqlite.possible-retention-days")) {
            yaml.set("sqlite.possible-retention-days", 7);
            changed = true;
        }
        if (!yaml.isSet("sqlite.maintenance-interval-minutes")) {
            yaml.set("sqlite.maintenance-interval-minutes", 360);
            changed = true;
        }
        if (!yaml.isSet("conflict-detector.mode")) {
            yaml.set("conflict-detector.mode", "MONITOR");
            changed = true;
        }
        if (!yaml.isSet("conflict-detector.case-cooldown-seconds")) {
            yaml.set("conflict-detector.case-cooldown-seconds", 60);
            changed = true;
        }
        if (!yaml.isSet("conflict-detector.same-incident-reset-seconds")) {
            yaml.set("conflict-detector.same-incident-reset-seconds", 300);
            changed = true;
        }
        if (!yaml.isSet("conflict-detector.possible-repeat-log-seconds")) {
            yaml.set("conflict-detector.possible-repeat-log-seconds", 21600);
            changed = true;
        }
        if (!yaml.isSet("conflict-detector.pending-container.ttl-minutes")) {
            yaml.set("conflict-detector.pending-container.ttl-minutes", 1440);
            changed = true;
        }
        if (!yaml.isSet("conflict-detector.pending-container.capacity")) {
            yaml.set("conflict-detector.pending-container.capacity", 20000);
            changed = true;
        }
        if (!yaml.isSet("conflict-detector.pending-container.batch-per-tick")) {
            yaml.set("conflict-detector.pending-container.batch-per-tick", 32);
            changed = true;
        }
        if (!yaml.isSet("conflict-detector.revalidation.enabled")) {
            yaml.set("conflict-detector.revalidation.enabled", true);
            changed = true;
        }
        if (!yaml.isSet("conflict-detector.revalidation.delay-ticks")) {
            yaml.set("conflict-detector.revalidation.delay-ticks", 3);
            changed = true;
        }
        if (!yaml.isSet("conflict-detector.grace.default-ms")) {
            yaml.set("conflict-detector.grace.default-ms", 750);
            changed = true;
        }
        for (String key : new String[]{"PLAYER_TO_ITEM_ENTITY", "ITEM_ENTITY_TO_PLAYER", "PLAYER_TO_CONTAINER",
                "CONTAINER_TO_PLAYER", "CONTAINER_TO_CONTAINER", "SHULKER_BLOCK_TO_ITEM_ENTITY"}) {
            String path = "conflict-detector.grace." + key;
            if (!yaml.isSet(path)) {
                yaml.set(path, 750);
                changed = true;
            }
        }
        if (!yaml.isSet("scanner.max-players-per-cycle")) {
            yaml.set("scanner.max-players-per-cycle", 8);
            changed = true;
        }
        if (!yaml.isSet("scanner.max-items-per-cycle")) {
            yaml.set("scanner.max-items-per-cycle", 512);
            changed = true;
        }
        if (!yaml.isSet("scanner.stats-log-interval-seconds")) {
            yaml.set("scanner.stats-log-interval-seconds", 300);
            changed = true;
        }
        if (!yaml.isSet("external-custody.missing-confirm-delay-ticks")) {
            yaml.set("external-custody.missing-confirm-delay-ticks", 10);
            changed = true;
        }
        if (!yaml.isSet("logging.divergent-warning-interval-seconds")) {
            yaml.set("logging.divergent-warning-interval-seconds", 30);
            changed = true;
        }
        if (!yaml.isSet("webhook.enabled")) {
            yaml.set("webhook.enabled", false);
            changed = true;
        }
        if (!yaml.isSet("webhook.url")) {
            yaml.set("webhook.url", "");
            changed = true;
        }
        if (!yaml.isSet("webhook.min-interval-ms")) {
            yaml.set("webhook.min-interval-ms", 2500);
            changed = true;
        }
        if (!yaml.isSet("webhook.confirmed.enabled")) {
            yaml.set("webhook.confirmed.enabled", yaml.getBoolean("webhook.enabled", false));
            changed = true;
        }
        if (!yaml.isSet("webhook.confirmed.url")) {
            yaml.set("webhook.confirmed.url", yaml.getString("webhook.url", ""));
            changed = true;
        }
        if (!yaml.isSet("webhook.possible.enabled")) {
            yaml.set("webhook.possible.enabled", yaml.getBoolean("webhook.enabled", false));
            changed = true;
        }
        if (!yaml.isSet("webhook.possible.url")) {
            yaml.set("webhook.possible.url", yaml.getString("webhook.url", ""));
            changed = true;
        }
        if (changed) {
            save();
        }
    }

    private void writeDefaultTemplate(Plugin plugin) {
        try (FileWriter writer = new FileWriter(file)) {
            writer.write(DEFAULT_TEMPLATE);
        } catch (IOException e) {
            plugin.getLogger().warning("[ItemIntegrity] Não consegui criar item-integrity.yml: " + e.getMessage());
        }
    }

    public void save() {
        try {
            yaml.save(file);
        } catch (IOException ignored) {
            // falha ao salvar config não deve derrubar o servidor - os valores em memória continuam válidos
        }
    }

    public boolean isEnabled() {
        return yaml.getBoolean("enabled", true);
    }

    public boolean isMigrationMode() {
        return yaml.getBoolean("migration.mode", true);
    }

    public String sqliteFileName() {
        return yaml.getString("sqlite.file", "item-integrity.db");
    }

    public int sqliteBatchSize() {
        return yaml.getInt("sqlite.batch-size", 200);
    }

    public long sqliteFlushIntervalMs() {
        return yaml.getLong("sqlite.flush-interval-ms", 1000);
    }

    public int sqliteQueueCapacity() {
        return yaml.getInt("sqlite.queue-capacity", 20000);
    }

    public int sqliteHistoryRetentionDays() {
        return Math.max(1, yaml.getInt("sqlite.possible-retention-days", 7));
    }

    public long sqliteMaintenanceIntervalMs() {
        return Math.max(60_000L, yaml.getLong("sqlite.maintenance-interval-minutes", 360) * 60_000L);
    }

    public ConflictMode conflictMode() {
        String raw = yaml.getString("conflict-detector.mode", "MONITOR");
        try {
            return ConflictMode.valueOf(raw == null ? "MONITOR" : raw.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return ConflictMode.MONITOR;
        }
    }

    public long conflictCaseCooldownMs() {
        return Math.max(0, yaml.getLong("conflict-detector.case-cooldown-seconds", 60) * 1000L);
    }

    public long sameIncidentResetMs() {
        return Math.max(conflictCaseCooldownMs(),
                yaml.getLong("conflict-detector.same-incident-reset-seconds", 300) * 1000L);
    }

    public long possibleRepeatLogMs() {
        return Math.max(sameIncidentResetMs(),
                yaml.getLong("conflict-detector.possible-repeat-log-seconds", 21600) * 1000L);
    }

    public long pendingContainerTtlMs() {
        return Math.max(60_000L,
                yaml.getLong("conflict-detector.pending-container.ttl-minutes", 1440) * 60_000L);
    }

    public int pendingContainerCapacity() {
        return Math.max(100, yaml.getInt("conflict-detector.pending-container.capacity", 20000));
    }

    public int pendingContainerBatchPerTick() {
        return Math.max(1, yaml.getInt("conflict-detector.pending-container.batch-per-tick", 32));
    }

    public boolean conflictRevalidationEnabled() {
        return yaml.getBoolean("conflict-detector.revalidation.enabled", true);
    }

    public long conflictRevalidationDelayTicks() {
        return Math.max(1, yaml.getLong("conflict-detector.revalidation.delay-ticks", 3));
    }

    public long handoffGraceMs(String transitionKey) {
        long fallback = Math.max(0, yaml.getLong("conflict-detector.grace.default-ms", 750));
        if (transitionKey == null || transitionKey.isBlank()) {
            return fallback;
        }
        return Math.max(0, yaml.getLong("conflict-detector.grace." + transitionKey, fallback));
    }

    public int scannerMaxPlayersPerCycle() {
        return Math.max(1, yaml.getInt("scanner.max-players-per-cycle", 8));
    }

    public int scannerMaxItemsPerCycle() {
        // One atomic player reconciliation: 41 inventory slots + 27 Ender Chest slots.
        return Math.max(68, yaml.getInt("scanner.max-items-per-cycle", 512));
    }

    public long scannerStatsLogIntervalMs() {
        return Math.max(0, yaml.getLong("scanner.stats-log-interval-seconds", 300) * 1000L);
    }

    public long externalCustodyMissingConfirmDelayTicks() {
        return Math.max(1, yaml.getLong("external-custody.missing-confirm-delay-ticks", 10));
    }

    public long divergentWarningIntervalMs() {
        return Math.max(0, yaml.getLong("logging.divergent-warning-interval-seconds", 30) * 1000L);
    }

    public boolean webhookEnabled() {
        return yaml.getBoolean("webhook.enabled", false);
    }

    public String webhookUrl() {
        return yaml.getString("webhook.url", "");
    }

    public long webhookMinIntervalMs() {
        return Math.max(0, yaml.getLong("webhook.min-interval-ms", 2500));
    }

    public boolean webhookConfirmedEnabled() {
        return yaml.getBoolean("webhook.confirmed.enabled", webhookEnabled());
    }

    public String webhookConfirmedUrl() {
        String specific = yaml.getString("webhook.confirmed.url", "");
        return specific == null || specific.isBlank() ? webhookUrl() : specific;
    }

    public boolean webhookPossibleEnabled() {
        return yaml.getBoolean("webhook.possible.enabled", webhookEnabled());
    }

    public String webhookPossibleUrl() {
        String specific = yaml.getString("webhook.possible.url", "");
        return specific == null || specific.isBlank() ? webhookUrl() : specific;
    }

    public ZoneId timezone() {
        String tz = yaml.getString("identity.timezone", "SYSTEM_DEFAULT");
        if (tz == null || tz.equalsIgnoreCase("SYSTEM_DEFAULT")) {
            return ZoneId.systemDefault();
        }
        try {
            return ZoneId.of(tz);
        } catch (Exception e) {
            return ZoneId.systemDefault();
        }
    }

    private static final String DEFAULT_TEMPLATE = """
            # Item Integrity System - configuracao propria, separada do config.yml
            # principal do IllegalStack. Esse arquivo cresce conforme as etapas do
            # sistema forem implementadas.

            enabled: true

            identity:
              # SYSTEM_DEFAULT usa o fuso horario do proprio servidor. Pode trocar pra
              # um fuso IANA especifico (ex: "America/Sao_Paulo") se preferir fixo.
              timezone: SYSTEM_DEFAULT

            migration:
              # Enquanto true, itens rastreaveis encontrados sem zetra:item_id recebem
              # um novo id com origin=LEGACY_IMPORT, sem gerar alerta. Quando desligar
              # isso no futuro, itens rastreaveis aparecendo sem id passam a ser
              # tratados como suspeitos (UNTRACKED_ITEM_APPEARED) pelo ConflictDetector.
              mode: true

            # ==========================================================================
            # As secoes abaixo ainda NAO tem logica implementada - ficam aqui so como
            # esqueleto documentado, pra quando as etapas seguintes chegarem.
            # ==========================================================================

            presence:
              # etapa 3 (parcial) - ver ConflictDetector (etapa 7) pra politica completa

            sqlite:
              # arquivo do banco, relativo a plugins/IllegalStack/
              file: item-integrity.db
              # quantas tarefas de auditoria o writer agrupa numa unica transacao
              batch-size: 200
              # tempo maximo (ms) que o writer espera antes de gravar um lote parcial
              flush-interval-ms: 1000
              # tamanho maximo da fila em memoria antes de comecar a descartar
              # entradas antigas (FAIL_OPEN - nunca bloqueia a server thread)
              queue-capacity: 20000
              # Somente resumos de casos possiveis expiram. Confirmados mantem snapshot.
              # O historico de rotina agora guarda somente as ultimas transicoes por ID.
              possible-retention-days: 7
              # Executada no writer SQLite, nunca na server thread.
              maintenance-interval-minutes: 360

            conflict-detector:
              # MONITOR registra WOULD_REMOVE + webhook, mas nunca remove.
              # DELETE remove somente depois de persistir o caso e revalidar.
              mode: MONITOR
              case-cooldown-seconds: 60
              # O mesmo estado continuo gera um caso apenas uma vez. Se sumir por
              # este tempo e reaparecer, passa a ser um novo incidente.
              same-incident-reset-seconds: 300
              # Casos apenas possiveis/ambiguos repetem no arquivo/SQLite no
              # maximo uma vez a cada 6 horas enquanto os holders nao mudarem.
              possible-repeat-log-seconds: 21600
              pending-container:
                # Container descarregado fica pendente e e reconciliado quando
                # o chunk carregar naturalmente. O plugin nunca carrega o chunk.
                ttl-minutes: 1440
                capacity: 20000
                batch-per-tick: 32
              revalidation:
                # Primeira anomalia agenda uma revalidacao fisica direcionada
                # alguns ticks depois. So vira duplicidade confirmada se a
                # segunda leitura ainda encontrar duas instancias reais.
                enabled: true
                delay-ticks: 3
              grace:
                default-ms: 750
                PLAYER_TO_ITEM_ENTITY: 750
                ITEM_ENTITY_TO_PLAYER: 750
                PLAYER_TO_CONTAINER: 750
                CONTAINER_TO_PLAYER: 750
                CONTAINER_TO_CONTAINER: 750
                SHULKER_BLOCK_TO_ITEM_ENTITY: 750

            scanner:
              max-players-per-cycle: 8
              max-items-per-cycle: 512
              stats-log-interval-seconds: 300

            external-custody:
              # Quando um item some do inventario de um player sem evento vanilla
              # conhecido, revalida apos este atraso antes de marcar EXTERNAL_PLUGIN.
              missing-confirm-delay-ticks: 10

            delete-quarantine:
              # etapa 7/10 - DELETE_WITH_BACKUP so ativa depois que sqlite existir

            webhook:
              enabled: false
              url: ""
              min-interval-ms: 2500
              confirmed:
                enabled: false
                url: ""
              possible:
                enabled: false
                url: ""

            logging:
              # Maximo de 1 aviso bruto de observacao divergente por chave neste intervalo.
              # Casos confirmados continuam sendo persistidos no SQLite e logados uma vez
              # por conflict-detector.case-cooldown-seconds.
              divergent-warning-interval-seconds: 30

            shulker:
              # etapa 5
            """;
}
