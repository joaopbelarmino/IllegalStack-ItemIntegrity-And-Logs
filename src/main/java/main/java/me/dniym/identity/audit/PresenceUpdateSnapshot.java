package main.java.me.dniym.identity.audit;

/**
 * Snapshot leve pra atualizar SÓ a tabela `presence` (o estado atual/
 * canônico), sem gerar linha em `item_events`. Usado quando uma observação
 * é auto-commitada mas não representa mudança relevante o bastante pra
 * virar histórico permanente - o dono lógico (ver HolderRef#sameOwner) e o
 * PresenceState continuam os mesmos, só o slot (ou timestamp de
 * reconfirmação) mudou. Ex: jogador reorganizou o inventário.
 *
 * Ver SqliteBackedPresenceStore para a lógica de quando usar isso vs
 * ItemEventSnapshot (que gera histórico de verdade).
 */
public record PresenceUpdateSnapshot(String itemId, String holderType, String world, Integer x, Integer y, Integer z,
                                      String playerUuid, String playerName, Integer slot, String entityUuid,
                                      String state, int presenceRevision, long timeEpochMs) {
}
