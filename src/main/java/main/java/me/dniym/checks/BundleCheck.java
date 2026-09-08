package main.java.me.dniym.checks;

import main.java.me.dniym.enums.Msg;
import main.java.me.dniym.enums.Protections;
import main.java.me.dniym.listeners.fListener;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.BundleMeta;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * BundleCheck — extração segura de conteúdo de Bundles, no mesmo espírito dos
 * demais checks do IllegalStack (OverstackedItemCheck, RemoveItemTypesCheck):
 * uma classe de lógica pura, sem conhecimento de eventos Bukkit, chamada por
 * um listener dedicado (ver BundleListener).
 *
 * Referências que orientaram este design (pesquisa feita antes de implementar):
 *  - PaperMC/Paper#6550 (bundle "extendido" além de 64 itens): a checagem de
 *    capacidade rodava DEPOIS da inserção, não antes -> duplicação. Por isso
 *    aqui a origem é sempre limpa ANTES de qualquer entrega de conteúdo.
 *  - BundleMeta#getItems() (Spigot API) devolve lista IMUTÁVEL -> já serve
 *    como cópia defensiva, sem necessidade de clone manual.
 *  - Javadoc de InventoryClickEvent: mutação de inventário não é segura
 *    dentro do próprio evento de clique -> o listener despacha para o tick
 *    seguinte / scheduler correto, nunca mexe aqui dentro do handler.
 *  - Bundle tem 16 variações coloridas além do BUNDLE base (WHITE_BUNDLE,
 *    RED_BUNDLE, etc) — todas cobertas pela tag oficial Tag.ITEMS_BUNDLES,
 *    em vez de comparar contra um único Material fixo (que deixaria passar
 *    qualquer bundle tingido).
 */
public final class BundleCheck {

    private static final int MAX_NESTED_DEPTH = 4;

    // Tag.ITEMS_BUNDLES cobre o BUNDLE base + todas as 16 cores (e qualquer
    // variação futura que a Mojang adicionar), sem precisar listar Material
    // um por um. Existe desde que bundles tingíveis foram introduzidos;
    // detectamos a presença dela uma vez e caímos para checagem por nome em
    // servidores antigos o suficiente para não tê-la.
    private static final boolean HAS_BUNDLE_TAG;
    static {
        boolean has;
        try {
            Tag.class.getField("ITEMS_BUNDLES");
            has = true;
        } catch (NoSuchFieldException e) {
            has = false;
        }
        HAS_BUNDLE_TAG = has;
    }

    // Lock por jogador: evita que duas varreduras concorrentes (ex: join e
    // pickup quase simultâneos, ou Folia despachando em threads diferentes)
    // processem o mesmo inventário ao mesmo tempo.
    private static final Set<UUID> PROCESSING = ConcurrentHashMap.newKeySet();

    private BundleCheck() {
    }

    public static boolean tryAcquire(UUID playerId) {
        return PROCESSING.add(playerId);
    }

    public static void release(UUID playerId) {
        PROCESSING.remove(playerId);
    }

    /**
     * true se o Material é qualquer variação de Bundle (base ou tingida).
     */
    private static boolean isBundleMaterial(Material type) {
        if (type == null) {
            return false;
        }
        if (HAS_BUNDLE_TAG) {
            return Tag.ITEMS_BUNDLES.isTagged(type);
        }
        // Fallback só pra servidores antigos demais pra ter a tag: bundles
        // tingidos seguem o padrão de nome "<COR>_BUNDLE", e o base é "BUNDLE".
        String name = type.name();
        return name.equals("BUNDLE") || name.endsWith("_BUNDLE");
    }

    public static boolean isNonEmptyBundle(ItemStack stack) {
        if (stack == null || !isBundleMaterial(stack.getType())) {
            return false;
        }
        if (!(stack.getItemMeta() instanceof BundleMeta meta)) {
            return false;
        }
        return meta.hasItems();
    }

    /**
     * Extrai o conteúdo de um Bundle e limpa a origem ANTES de devolver
     * qualquer item para quem chamou. Nunca existe uma janela em que o
     * Bundle cheio e os itens extraídos coexistem — mesmo que o processo
     * seja interrompido logo em seguida (crash/kick/disconnect), o Bundle
     * já está vazio.
     */
    public static List<ItemStack> extractAndClear(ItemStack bundleStack) {
        return extractAndClear(bundleStack, 0);
    }

    private static List<ItemStack> extractAndClear(ItemStack bundleStack, int depth) {
        List<ItemStack> result = new ArrayList<>();

        if (!isNonEmptyBundle(bundleStack)) {
            return result;
        }

        if (depth >= MAX_NESTED_DEPTH) {
            fListener.getLog().append2(
                    "[BundleCheck] Profundidade de aninhamento suspeita excedida em um Bundle "
                            + "(possivel item malformado/exploit) - recursao abortada.");
            return result;
        }

        BundleMeta meta = (BundleMeta) bundleStack.getItemMeta();
        List<ItemStack> contents = meta.getItems(); // ja imutavel - copia defensiva da propria API

        meta.setItems(Collections.emptyList());
        bundleStack.setItemMeta(meta);

        for (ItemStack inner : contents) {
            if (inner == null || inner.getType() == Material.AIR) {
                continue;
            }
            if (isNonEmptyBundle(inner)) {
                result.addAll(extractAndClear(inner, depth + 1));
            } else {
                result.add(inner);
            }
        }

        return result;
    }

    /**
     * Entrega os itens extraídos: tenta encaixar no inventário, e o
     * excedente é dropado perto do jogador (se dropOnGround) ou descartado
     * com log de auditoria (nunca re-empacotado, nunca silenciosamente
     * perdido sem registro).
     */
    public static void deliver(Player player, List<ItemStack> items, boolean dropOnGround, String context) {
        if (items.isEmpty()) {
            return;
        }

        PlayerInventory inv = player.getInventory();
        List<ItemStack> overflow = new ArrayList<>();

        for (ItemStack item : items) {
            java.util.Map<Integer, ItemStack> leftover = inv.addItem(item);
            overflow.addAll(leftover.values());
        }

        if (!overflow.isEmpty() && dropOnGround) {
            World world = player.getWorld();
            Location loc = player.getLocation();
            for (ItemStack drop : overflow) {
                world.dropItemNaturally(loc, drop);
            }
            fListener.getLog().append(
                    Msg.BundleOverflowDropped.getValue(player, overflow.size(), player.getLocation()),
                    Protections.DropBundleItemsOnGround);
        } else if (!overflow.isEmpty()) {
            fListener.getLog().append(
                    Msg.BundleOverflowDiscarded.getValue(player, overflow.size(), player.getLocation()),
                    Protections.DropBundleItemsOnGround);
        }

        StringBuilder itemSummary = new StringBuilder();
        for (ItemStack item : items) {
            if (itemSummary.length() > 0) {
                itemSummary.append(", ");
            }
            itemSummary.append(item.getType().name())
                    .append(" x").append(item.getAmount());
        }
        fListener.getLog().append(
                Msg.BundleContentsExtracted.getValue(player, itemSummary.toString()),
                Protections.BlockBundles);
    }
}
