package main.java.me.dniym.identity.presence;

import main.java.me.dniym.identity.ItemIdentity;
import main.java.me.dniym.identity.ItemOrigin;
import main.java.me.dniym.identity.audit.AuditQueue;
import main.java.me.dniym.identity.audit.AuditTask;
import main.java.me.dniym.identity.audit.DatabaseService;
import main.java.me.dniym.identity.audit.IdentityEventType;
import main.java.me.dniym.identity.audit.ItemEventSnapshot;
import main.java.me.dniym.identity.audit.PresenceUpdateSnapshot;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.sql.SQLException;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * PresenceStore com persistência real por trás (etapa 4). A DECISÃO em
 * tempo real continua 100% em memória, igual à InMemoryPresenceStore
 * (canonical nunca sobrescrita por dono diferente sem commitHandoff
 * explícito) - essa classe adiciona:
 *
 *  1. Hidratação do cache a partir de `presence` JOIN `items` (uma vez, na
 *     construção) - reconstrói o ItemIdentity ESTRUTURADO COMPLETO.
 *     origin=UNKNOWN só aparece se a linha de `items` realmente não
 *     existir (LEFT JOIN retornando null de verdade).
 *  2. Write-through assíncrono, com DUAS intensidades diferentes:
 *     - `presence` (estado atual) é atualizada em TODA observação
 *       auto-commitada, mesmo trivial (é só um upsert de uma linha). *     - `item_events` (histórico permanente) só recebe uma linha nova
 *       quando a mudança é REALMENTE relevante: identidade nova, o dono
 *       LÓGICO mudou (comparado por HolderRef#sameOwner - player UUID,
 *       entity UUID, ou world+coordenadas do container/bloco; PLAYER
 *       João -> PLAYER Pedro conta, CHEST A -> CHEST B também conta,
 *       mesmo sendo o mesmo TIPO de holder dos dois lados), ou o estado
 *       mudou (ex: LIVE_CONFIRMED -> OFFLINE_COMMITTED). Reorganizar o
 *       inventário (só o slot muda, mesmo dono lógico) NUNCA gera linha
 *       de histórico - só atualiza `presence` (inclusive last_confirmed_at,
 *       que fica sempre em dia a cada scan, sem precisar de heartbeat
 *       separado pra isso).
 *
 * Se o SQLite falhar depois de já estar rodando, o cache em memória
 * continua funcionando normalmente - só a durabilidade/auditoria fica
 * comprometida (AuditQueue já é FAIL_OPEN por conta própria).
 */
public final class SqliteBackedPresenceStore implements PresenceStore {

    private static final Logger LOGGER = LogManager.getLogger("IllegalStack/ItemIntegrity");

    private final Map<String, PresenceRecord> canonical = new ConcurrentHashMap<>();
    private final Map<String, PresenceRecord> lastDivergent = new ConcurrentHashMap<>();
    private final AuditQueue auditQueue;

    public SqliteBackedPresenceStore(DatabaseService db, AuditQueue auditQueue) throws SQLException {
        this.auditQueue = auditQueue;
        hydrate(db);
    }

    private void hydrate(DatabaseService db) throws SQLException {
        int loaded = 0;
        int withoutIdentityRow = 0;
        for (Object[] row : db.hydratePresenceWithIdentity()) {
            PresenceRecord record = rowToRecord(row);
            if (record != null) {
                canonical.put(record.identity().id(), record);
                loaded++;
                if (record.identity().origin() == ItemOrigin.UNKNOWN) {
                    withoutIdentityRow++;
                    LOGGER.warn("[ItemIntegrity] ORPHAN_PRESENCE_RECORD: presença hidratada para {} sem linha correspondente em items (dado genuinamente ausente, não limitação da query).",
                            record.identity().id());
                }
            }
        }
        LOGGER.info("[ItemIntegrity] {} presença(s) hidratada(s) do SQLite.", loaded);
    }

    /**
     * Row shape (19 colunas, ver DatabaseService#hydratePresenceWithIdentity):
     * 0 item_uuid, 1 holder_type, 2 world, 3 x, 4 y, 5 z, 6 player_uuid,
     * 7 player_name, 8 slot, 9 entity_uuid, 10 state, 11 revision,
     * 12 last_confirmed_at, 13 created_at, 14 origin, 15 registered_world,
     * 16 registered_x, 17 registered_y, 18 registered_z.
     */
    private PresenceRecord rowToRecord(Object[] row) {
        String itemUuid = (String) row[0];
        String holderTypeStr = (String) row[1];
        String world = (String) row[2];
        Integer x = (Integer) row[3];
        Integer y = (Integer) row[4];
        Integer z = (Integer) row[5];
        String playerUuidStr = (String) row[6];
        String playerName = (String) row[7];
        Integer slot = (Integer) row[8];
        String entityUuidStr = (String) row[9];
        String stateStr = (String) row[10];
        Number revisionNum = (Number) row[11];
        Number lastConfirmedNum = (Number) row[12];

        Number createdAtNum = (Number) row[13];
        String originStr = (String) row[14];
        String registeredWorld = (String) row[15];
        Integer registeredX = (Integer) row[16];
        Integer registeredY = (Integer) row[17];
        Integer registeredZ = (Integer) row[18];

        if (itemUuid == null || holderTypeStr == null || stateStr == null) {
            return null;
        }

        HolderType type;
        PresenceState state;
        try {
            type = HolderType.valueOf(holderTypeStr);
            state = PresenceState.valueOf(stateStr);
        } catch (IllegalArgumentException e) {
            LOGGER.warn("[ItemIntegrity] Linha de presence com holder_type/state inválido pra {} - ignorada na hidratação.", itemUuid);
            return null;
        }

        HolderRef holder;
        try {
            if (type == HolderType.PLAYER) {
                if (playerUuidStr == null) return null;
                holder = new HolderRef.PlayerHolder(UUID.fromString(playerUuidStr), playerName, slot);
            } else if (type == HolderType.ITEM_ENTITY) {
                if (entityUuidStr == null) return null;
                holder = new HolderRef.ItemEntityHolder(UUID.fromString(entityUuidStr), null, world, x, y, z);
            } else if (type == HolderType.ITEM_FRAME || type == HolderType.ARMOR_STAND
                    || type == HolderType.STORAGE_MINECART) {
                if (entityUuidStr == null) return null;
                holder = new HolderRef.EntityHolder(type, UUID.fromString(entityUuidStr), world, x, y, z, null);
            } else if (type == HolderType.VIRTUAL_INVENTORY || type == HolderType.EXTERNAL_PLUGIN) {
                holder = new HolderRef.VirtualHolder(type, world != null ? world : "unknown", playerName, slot);
            } else {
                if (world == null || x == null || y == null || z == null) return null;
                holder = new HolderRef.ContainerHolder(type, world, x, y, z, slot);
            }
        } catch (Exception e) {
            LOGGER.warn("[ItemIntegrity] Linha de presence incompleta/corrompida pra {} - ignorada na hidratação.", itemUuid);
            return null;
        }

        ItemOrigin origin = ItemOrigin.UNKNOWN;
        if (originStr != null) {
            try {
                origin = ItemOrigin.valueOf(originStr);
            } catch (IllegalArgumentException ignored) {
            }
        }
        long registeredAt = createdAtNum != null ? createdAtNum.longValue() : 0L;
        ItemIdentity identity = new ItemIdentity(itemUuid, registeredAt, registeredWorld,
                registeredX, registeredY, registeredZ, origin);

        int revision = revisionNum != null ? revisionNum.intValue() : 1;
        long lastConfirmed = lastConfirmedNum != null ? lastConfirmedNum.longValue() : System.currentTimeMillis();

        return new PresenceRecord(identity, holder, state, revision, lastConfirmed);
    }

    @Override
    public Optional<PresenceRecord> getCanonical(ItemIdentity identity) {
        return getCanonical(identity.id());
    }

    @Override
    public Optional<PresenceRecord> getCanonical(String itemId) {
        return Optional.ofNullable(canonical.get(itemId));
    }

    @Override
    public synchronized PresenceObservation observe(ItemIdentity identity, HolderRef holder, PresenceState state) {
        PresenceRecord before = canonical.get(identity.id());
        PresenceRecord previousDivergent = lastDivergent.get(identity.id());
        int nextRevision = before == null ? 1 : before.presenceRevision() + 1;
        PresenceRecord candidate = new PresenceRecord(identity, holder, state, nextRevision, System.currentTimeMillis());

        boolean sameOwner = before == null || HolderRef.sameOwner(before.holder(), holder);
        boolean autoCommit = before == null || sameOwner;

        PresenceRecord after = before;
        if (autoCommit) {
            after = candidate;
            canonical.put(identity.id(), after);
            lastDivergent.remove(identity.id());
        } else {
            lastDivergent.put(identity.id(), candidate);
        }

        if (!autoCommit) {
            // Uma divergencia nova e relevante; a mesma GUI/container sendo
            // reobservada em todo scan apenas atualiza o hot state em RAM.
            if (isMeaningfulDivergent(previousDivergent, candidate)) {
                enqueueHistoryEvent(identity, holder, state, nextRevision, false,
                        IdentityEventType.PRESENCE_DIVERGENT_OBSERVED);
            }
        } else if (isMeaningfulChange(before, candidate)) {
            enqueueHistoryEvent(identity, holder, state, nextRevision, true, IdentityEventType.PRESENCE_COMMITTED);
        } else {
            // Reconfirmação trivial (mesmo dono/tipo/estado, só slot ou
            // timestamp mudando) - só atualiza presence, não vira linha
            // permanente de histórico.
            enqueuePresenceOnly(identity, holder, state, nextRevision);
        }

        return new PresenceObservation(identity, Optional.ofNullable(before), candidate, sameOwner, autoCommit, Optional.ofNullable(after));
    }

    /**
     * true se a mudança é relevante o bastante pra virar histórico
     * permanente. Compara pelo HOLDER LÓGICO (dono real - player UUID,
     * entity UUID, ou world+coordenadas do container/bloco - ver
     * HolderRef#sameOwner), NÃO pelo tipo de holder. PLAYER João -> PLAYER
     * Pedro é relevante mesmo sendo PLAYER->PLAYER; CHEST A -> CHEST B é
     * relevante mesmo sendo CHEST->CHEST. Só o slot mudando dentro do
     * MESMO dono lógico não conta - isso é só reorganização de inventário.
     */
    private boolean isMeaningfulChange(PresenceRecord before, PresenceRecord candidate) {
        if (before == null) {
            return true; // identidade nova / primeira presença conhecida
        }
        if (!HolderRef.sameOwner(before.holder(), candidate.holder())) {
            return true; // dono lógico diferente, independente do tipo
        }
        if (before.state() != candidate.state()) {
            return true; // ex: LIVE_CONFIRMED -> OFFLINE_COMMITTED
        }
        return false; // mesmo dono lógico, só slot/timestamp mudando - não vira histórico
    }

    private boolean isMeaningfulDivergent(PresenceRecord before, PresenceRecord candidate) {
        return before == null
                || !HolderRef.sameOwner(before.holder(), candidate.holder())
                || before.state() != candidate.state();
    }

    @Override
    public synchronized PresenceRecord commitHandoff(ItemIdentity identity, HolderRef newHolder, PresenceState newState) {
        PresenceRecord before = canonical.get(identity.id());
        int nextRevision = before == null ? 1 : before.presenceRevision() + 1;
        PresenceRecord committed = new PresenceRecord(identity, newHolder, newState, nextRevision, System.currentTimeMillis());
        canonical.put(identity.id(), committed);
        lastDivergent.remove(identity.id());

        if (isMeaningfulChange(before, committed)) {
            enqueueHistoryEvent(identity, newHolder, newState, nextRevision, true,
                    IdentityEventType.PRESENCE_COMMITTED);
        } else {
            enqueuePresenceOnly(identity, newHolder, newState, nextRevision);
        }
        return committed;
    }

    @Override
    public Optional<PresenceRecord> getLastDivergentObservation(ItemIdentity identity) {
        return getLastDivergentObservation(identity.id());
    }

    @Override
    public Optional<PresenceRecord> getLastDivergentObservation(String itemId) {
        return Optional.ofNullable(lastDivergent.get(itemId));
    }

    private void enqueueHistoryEvent(ItemIdentity identity, HolderRef holder, PresenceState state, int revision,
                                      boolean autoCommitted, IdentityEventType type) {
        HolderFields f = HolderFields.of(holder);
        ItemEventSnapshot snapshot = new ItemEventSnapshot(identity.id(), System.currentTimeMillis(), type,
                holder.type().name(), f.world, f.x, f.y, f.z, f.playerUuid, f.playerName, f.slot, f.entityUuid,
                state.name(), revision, autoCommitted);
        auditQueue.offer(new AuditTask.RecordEvent(snapshot));
    }

    private void enqueuePresenceOnly(ItemIdentity identity, HolderRef holder, PresenceState state, int revision) {
        HolderFields f = HolderFields.of(holder);
        PresenceUpdateSnapshot snapshot = new PresenceUpdateSnapshot(identity.id(), holder.type().name(),
                f.world, f.x, f.y, f.z, f.playerUuid, f.playerName, f.slot, f.entityUuid, state.name(),
                revision, System.currentTimeMillis());
        auditQueue.offer(new AuditTask.UpdatePresence(snapshot));
    }

    private record HolderFields(String playerUuid, String playerName, String entityUuid,
                                 String world, Integer x, Integer y, Integer z, Integer slot) {
        static HolderFields of(HolderRef holder) {
            if (holder instanceof HolderRef.PlayerHolder p) {
                return new HolderFields(p.playerId().toString(), p.playerName(), null, null, null, null, null, p.slot());
            }
            if (holder instanceof HolderRef.ItemEntityHolder e) {
                return new HolderFields(null, null, e.entityUuid().toString(), e.world(), e.x(), e.y(), e.z(), null);
            }
            if (holder instanceof HolderRef.ContainerHolder c) {
                return new HolderFields(null, null, null, c.world(), c.x(), c.y(), c.z(), c.slot());
            }
            if (holder instanceof HolderRef.EntityHolder e) {
                return new HolderFields(null, null, e.entityUuid().toString(), e.world(), e.x(), e.y(), e.z(), null);
            }
            if (holder instanceof HolderRef.VirtualHolder v) {
                return new HolderFields(null, v.viewerOrOwner(), null, v.label(), null, null, null, v.slot());
            }
            return new HolderFields(null, null, null, null, null, null, null, null);
        }
    }
}
