package main.java.me.dniym.identity;

import org.bukkit.Location;
import org.bukkit.inventory.ItemStack;

/**
 * Garante que todo item rastreável tenha uma identidade, atribuindo uma
 * nova com origin=LEGACY_IMPORT na primeira vez que um item antigo (sem
 * zetra:item_id) é encontrado. Isso NÃO gera nenhum alerta - é esperado e
 * aceito durante o período de bootstrap/migração.
 *
 * Aceito por decisão explícita: mesmo um item recém-criado pode receber
 * LEGACY_IMPORT nesta etapa, já que ainda não existem listeners
 * específicos de craft/anvil/smithing/etc. A proveniência correta chega
 * junto com os listeners de origem, mais adiante.
 */
public final class MigrationService {

    private final IdentityService identityService;

    public MigrationService(IdentityService identityService) {
        this.identityService = identityService;
    }

    /**
     * @return o resultado da migração (identidade existente ou recém-migrada,
     *         mais a flag freshlyAssigned), ou null se o item não é
     *         rastreável (ver TrackabilityPolicy).
     */
    public MigrationResult ensureIdentity(ItemStack stack, Location fallbackLocation) {
        if (!TrackabilityPolicy.isCandidate(stack)) {
            return null;
        }
        var pdc = stack.getPersistentDataContainer();
        if (TrackabilityPolicy.isExempt(pdc)) {
            return null;
        }
        ItemIdentity existing = identityService.readIdentity(pdc);
        if (existing != null) {
            return new MigrationResult(existing, false);
        }
        ItemIdentity fresh = identityService.assignIdentity(stack, fallbackLocation, ItemOrigin.LEGACY_IMPORT);
        return new MigrationResult(fresh, true);
    }
}
