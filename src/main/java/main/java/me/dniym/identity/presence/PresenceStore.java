package main.java.me.dniym.identity.presence;

import main.java.me.dniym.identity.ItemIdentity;

import java.util.Optional;

public interface PresenceStore {

    Optional<PresenceRecord> getCanonical(ItemIdentity identity);

    Optional<PresenceRecord> getCanonical(String itemId);

    /**
     * Registra uma observação. Se não houver canonical ainda, ou se o
     * holder observado for o MESMO dono da canonical atual (ver
     * HolderRef#sameOwner), a observação é auto-commitada como nova
     * canonical (é só uma atualização de revisão/timestamp/slot dentro da
     * mesma cadeia legítima). Caso contrário (dono DIFERENTE), a canonical
     * NÃO é sobrescrita - a observação fica só registrada como candidata,
     * pra não apagar evidência antes do ConflictDetector decidir o que
     * fazer.
     */
    PresenceObservation observe(ItemIdentity identity, HolderRef holder, PresenceState state);

    /**
     * Promove explicitamente uma observação a canonical, independente de
     * dono. Só deve ser chamado por lógica que já determinou que a
     * movimentação é legítima (handoff real). Nada nas etapas 1-3 chama
     * isso automaticamente pra holders diferentes - existe pronto pro
     * ConflictDetector (etapa 7) usar.
     */
    PresenceRecord commitHandoff(ItemIdentity identity, HolderRef newHolder, PresenceState newState);

    /** Última observação divergente conhecida (dono diferente da canonical), se houver - só para debug/inspeção. */
    Optional<PresenceRecord> getLastDivergentObservation(ItemIdentity identity);

    Optional<PresenceRecord> getLastDivergentObservation(String itemId);
}
