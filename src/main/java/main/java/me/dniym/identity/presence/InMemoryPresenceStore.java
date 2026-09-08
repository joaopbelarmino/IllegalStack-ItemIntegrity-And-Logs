package main.java.me.dniym.identity.presence;

import main.java.me.dniym.identity.ItemIdentity;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Implementação em memória, TEMPORÁRIA (etapas 1-3). NÃO sobrevive a um
 * restart do servidor - isso é esperado e foi aceito explicitamente nesta
 * fase. Será substituída por uma implementação com SQLite por trás na
 * etapa 4, mantendo esta mesma interface (PresenceStore), então nada que
 * dependa dela precisa mudar quando isso acontecer.
 */
public final class InMemoryPresenceStore implements PresenceStore {

    private final Map<String, PresenceRecord> canonical = new ConcurrentHashMap<>();
    private final Map<String, PresenceRecord> lastDivergent = new ConcurrentHashMap<>();

    @Override
    public synchronized Optional<PresenceRecord> getCanonical(ItemIdentity identity) {
        return getCanonical(identity.id());
    }

    @Override
    public synchronized Optional<PresenceRecord> getCanonical(String itemId) {
        return Optional.ofNullable(canonical.get(itemId));
    }

    @Override
    public synchronized PresenceObservation observe(ItemIdentity identity, HolderRef holder, PresenceState state) {
        PresenceRecord before = canonical.get(identity.id());
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

        return new PresenceObservation(identity, Optional.ofNullable(before), candidate, sameOwner, autoCommit, Optional.ofNullable(after));
    }

    @Override
    public synchronized PresenceRecord commitHandoff(ItemIdentity identity, HolderRef newHolder, PresenceState newState) {
        PresenceRecord before = canonical.get(identity.id());
        int nextRevision = before == null ? 1 : before.presenceRevision() + 1;
        PresenceRecord committed = new PresenceRecord(identity, newHolder, newState, nextRevision, System.currentTimeMillis());
        canonical.put(identity.id(), committed);
        lastDivergent.remove(identity.id());
        return committed;
    }

    @Override
    public synchronized Optional<PresenceRecord> getLastDivergentObservation(ItemIdentity identity) {
        return getLastDivergentObservation(identity.id());
    }

    @Override
    public synchronized Optional<PresenceRecord> getLastDivergentObservation(String itemId) {
        return Optional.ofNullable(lastDivergent.get(itemId));
    }
}
