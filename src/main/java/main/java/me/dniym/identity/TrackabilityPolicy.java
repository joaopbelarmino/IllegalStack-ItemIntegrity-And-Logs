package main.java.me.dniym.identity;

import io.papermc.paper.persistence.PersistentDataContainerView;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

import java.util.EnumSet;
import java.util.Set;

/**
 * Decide se um item é candidato a receber identidade própria.
 *
 * Prioridade das regras (o filtro barato de material executa primeiro):
 *  1. zetra:integrity_exempt=true na PDC -> NUNCA rastreado, sem exceção.
 *     Pra itens técnicos/temporários de plugin (relógio de menu, seletor de
 *     servidor, gadget de lobby, item de GUI) que não representam gameplay
 *     persistente. Mesmo FORCE_INCLUDE nunca sobrepoe esta isencao.
 *  2. FORCE_EXCLUDE / FORCE_INCLUDE - pontos de extensão pra whitelist/
 *     blacklist futura via config.
 *  3. Regra base: getMaxStackSize() == 1 (itens naturalmente não-empilháveis).
 *
 * IMPORTANTE: isExempt() NÃO ignora todo item que tenha PDC de plugin -
 * só o item que tem explicitamente a flag `integrity_exempt=true`. Um item
 * customizado real de gameplay feito por outro plugin continua rastreado
 * normalmente (origin=PLUGIN quando o listener correspondente existir).
 */
public final class TrackabilityPolicy {

    private static final String NAMESPACE = "zetra";
    private static final NamespacedKey KEY_EXEMPT = new NamespacedKey(NAMESPACE, "integrity_exempt");
    private static final NamespacedKey KEY_EXEMPT_REASON = new NamespacedKey(NAMESPACE, "integrity_exempt_reason");

    private static final Set<Material> FORCE_EXCLUDE = EnumSet.noneOf(Material.class);
    private static final Set<Material> FORCE_INCLUDE = EnumSet.noneOf(Material.class);

    private TrackabilityPolicy() {
    }

    public static boolean isTrackable(ItemStack stack) {
        return isCandidate(stack) && !isExempt(stack);
    }

    public static boolean isCandidate(ItemStack stack) {
        if (stack == null) {
            return false;
        }
        Material type = stack.getType();
        if (FORCE_EXCLUDE.contains(type)) {
            return false;
        }
        if (FORCE_INCLUDE.contains(type)) {
            return true;
        }
        return type.getMaxStackSize() == 1;
    }

    /** true se o item foi explicitamente marcado como isento (item técnico/temporário de plugin, não gameplay). */
    public static boolean isExempt(ItemStack stack) {
        return stack != null && isExempt(stack.getPersistentDataContainer());
    }

    static boolean isExempt(PersistentDataContainerView pdc) {
        if (!pdc.has(KEY_EXEMPT, PersistentDataType.INTEGER)) {
            return false;
        }
        Integer flag = pdc.get(KEY_EXEMPT, PersistentDataType.INTEGER);
        return flag != null && flag != 0;
    }

    /**
     * Marca um item como isento do Item Integrity - ponto de extensão pra
     * outros sistemas (ou uma futura API pública) usarem. Não é chamado por
     * nada nesta versão; existe pronto pra uso futuro (ex: um plugin de
     * lobby/menu marcando seus próprios itens).
     */
    public static void markExempt(ItemStack stack, String reason) {
        if (stack == null) {
            return;
        }
        stack.editPersistentDataContainer(pdc -> {
            pdc.set(KEY_EXEMPT, PersistentDataType.INTEGER, 1);
            if (reason != null) {
                pdc.set(KEY_EXEMPT_REASON, PersistentDataType.STRING, reason);
            }
        });
    }
}
