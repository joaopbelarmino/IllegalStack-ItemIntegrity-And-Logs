package main.java.me.dniym.identity.audit;

/**
 * Snapshot imutável de um evento de presença, criado na server thread.
 * Todos os campos são primitivos/String - nunca referências vivas de
 * Bukkit (HolderRef, ItemStack, Player). Isso é o que vai pra
 * `item_events` e, quando autoCommitted, também atualiza a tabela
 * `presence` (o mirror durável da canonical atual).
 */
public record ItemEventSnapshot(String itemId, long timeEpochMs, IdentityEventType eventType,
                                 String holderType, String world, Integer x, Integer y, Integer z,
                                 String playerUuid, String playerName, Integer slot, String entityUuid,
                                 String state, int presenceRevision, boolean autoCommitted) {
}
