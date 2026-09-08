package main.java.me.dniym.identity.audit;

/**
 * Snapshot imutável, criado na server thread, capturando os dados
 * necessários pra registrar uma identidade na tabela `items`. Nunca
 * carrega referência viva de ItemStack/Bukkit - só dados primitivos, seguro
 * pra passar pra a thread async do DatabaseService.
 *
 * registeredWorld/X/Y/Z são os mesmos dados que já estão gravados na PDC
 * do item (ver IdentityService) - espelhados aqui pra sobreviver a um
 * restart via hidratação, sem depender de reabrir/ler o item de novo.
 */
public record ItemSnapshot(String itemId, String material, long createdAtEpochMs, String origin,
                            String registeredWorld, Integer registeredX, Integer registeredY, Integer registeredZ,
                            String firstOwnerUuid, long firstSeenEpochMs) {
}
