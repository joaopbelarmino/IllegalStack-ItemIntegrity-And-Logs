package main.java.me.dniym.identity.presence;

import main.java.me.dniym.identity.ItemIdentity;

import java.util.Optional;

/**
 * Resultado de uma observação. NÃO decide se é conflito - só registra que
 * a observação aconteceu, se bateu com o dono canonical atual
 * (sameOwnerAsCanonical) e, por consequência, se foi auto-commitada como
 * nova canonical ou ficou como candidata NÃO-commitada aguardando revisão
 * do futuro ConflictDetector.
 */
public record PresenceObservation(ItemIdentity identity, Optional<PresenceRecord> canonicalBefore,
                                   PresenceRecord candidate, boolean sameOwnerAsCanonical,
                                   boolean autoCommitted, Optional<PresenceRecord> canonicalAfter) {
}
