package main.java.me.dniym.listeners;

import main.java.me.dniym.IllegalStack;
import main.java.me.dniym.checks.BundleCheck;
import main.java.me.dniym.enums.Protections;
import main.java.me.dniym.utils.Scheduler;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.CraftItemEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * BundleListener — dispara BundleCheck em todos os pontos onde um Bundle
 * poderia entrar/ser manipulado no inventário de um jogador: join, fechar
 * inventário (clique/shift-click/drag em qualquer GUI), craft, pickup do
 * chão, troca de item na mão, troca de mão, morte e logout — MAIS um
 * scanner periódico (mesmo padrão da fTimer usada por RemoveOverstackedItems).
 *
 * O scanner periódico existe porque bundle tem uma segunda forma de uso que
 * não dispara NENHUM evento de inventário: segurar botão direito no bundle
 * direto na hotbar (fora de qualquer tela de inventário aberta) esvazia o
 * conteúdo aos poucos, e adicionar itens segurando botão direito em cima
 * deles no chão/mundo também não abre nenhuma GUI. Sem o scanner periódico,
 * esses casos passariam batido pela proteção inteira.
 *
 * Despacho Folia-aware: usa o mesmo mecanismo de detecção que o restante do
 * IllegalStack (IllegalStack.isFoliaServer()) e despacha via
 * player.getScheduler() quando aplicável, para nunca mutar um inventário
 * fora da região dona da entidade.
 */
public class BundleListener implements Listener {

    private final IllegalStack plugin;
    private Scheduler.ScheduledTask scanTask;

    public BundleListener(IllegalStack plugin) {
        this.plugin = plugin;
        plugin.getServer().getPluginManager().registerEvents(this, plugin);

        // Reaproveita o mesmo intervalo configurado pra ItemScanTimer (a
        // varredura periódica que já existe pra RemoveOverstackedItems),
        // então não introduz uma config nova só pra isso.
        long period = Math.max(1, Protections.ItemScanTimer.getIntValue());
        this.scanTask = Scheduler.runTaskTimer(plugin, this::periodicScan, period, period);
    }

    private void periodicScan() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            scheduleSweep(player, "periodic-scan");
        }
    }

    private boolean enabled() {
        return Protections.BlockBundles.isEnabled();
    }

    private boolean dropOnGround() {
        return Protections.DropBundleItemsOnGround.isEnabled();
    }

    // ---- Gatilhos ----------------------------------------------------

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onJoin(PlayerJoinEvent event) {
        if (!enabled()) return;
        scheduleSweep(event.getPlayer(), "join");
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInventoryClose(InventoryCloseEvent event) {
        if (!enabled()) return;
        if (event.getPlayer() instanceof Player player) {
            scheduleSweep(player, "inventory-close");
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onCraftItem(CraftItemEvent event) {
        if (!enabled()) return;
        if (event.getWhoClicked() instanceof Player player) {
            scheduleSweep(player, "craft-item");
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent event) {
        if (!enabled()) return;
        if (event.getEntity() instanceof Player player) {
            scheduleSweep(player, "pickup");
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onItemHeld(PlayerItemHeldEvent event) {
        if (!enabled()) return;
        scheduleSweep(event.getPlayer(), "item-held");
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSwapHands(PlayerSwapHandItemsEvent event) {
        if (!enabled()) return;
        scheduleSweep(event.getPlayer(), "swap-hands");
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(PlayerDeathEvent event) {
        if (!enabled()) return;
        // Morte e um caso especial: os itens ja foram movidos para a lista de
        // drops antes do jogador sair do mundo, entao processamos os drops
        // diretamente em vez de agendar (nao existe "proximo tick seguro"
        // depois que o corpo ja gerou os itens no chao).
        List<ItemStack> drops = event.getDrops();
        List<ItemStack> replacement = new ArrayList<>();
        for (ItemStack stack : drops) {
            if (BundleCheck.isNonEmptyBundle(stack)) {
                replacement.addAll(BundleCheck.extractAndClear(stack));
                replacement.add(stack); // bundle agora vazio continua dropando (comportamento vanilla)
            } else {
                replacement.add(stack);
            }
        }
        drops.clear();
        drops.addAll(replacement);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onQuit(PlayerQuitEvent event) {
        if (!enabled()) return;
        // Sem agendamento: o jogador pode nao existir mais como entidade
        // quando um task futuro rodasse. Processa de forma sincrona, antes
        // dos dados serem persistidos.
        sweepNow(event.getPlayer(), "quit");
    }

    // ---- Nucleo de varredura -------------------------------------------

    private void scheduleSweep(Player player, String context) {
        if (IllegalStack.isFoliaServer()) {
            player.getScheduler().run(plugin, task -> sweepNow(player, context), null);
        } else {
            // Sempre adiado 1 tick, mesmo se ja estivermos na thread principal,
            // para nunca mutar o inventario durante o proprio evento de
            // clique/craft em andamento (ver Javadoc de InventoryClickEvent).
            Bukkit.getScheduler().runTask(plugin, () -> sweepNow(player, context));
        }
    }

    private void sweepNow(Player player, String context) {
        UUID id = player.getUniqueId();
        if (!BundleCheck.tryAcquire(id)) {
            return; // ja existe uma varredura em andamento para este jogador
        }
        try {
            if (!player.isOnline()) {
                return;
            }
            PlayerInventory inv = player.getInventory();
            ItemStack[] contents = inv.getContents();
            boolean processedAny = false;

            for (int slot = 0; slot < contents.length; slot++) {
                ItemStack stack = contents[slot];
                if (!BundleCheck.isNonEmptyBundle(stack)) {
                    continue;
                }
                List<ItemStack> extracted = BundleCheck.extractAndClear(stack);
                // Remocao por INDICE de slot, nunca por Inventory#remove(ItemStack)
                // baseado em equals() (evita remover a stack errada).
                inv.setItem(slot, null);
                BundleCheck.deliver(player, extracted, dropOnGround(), context + "/slot-" + slot);
                processedAny = true;
            }

            ItemStack offhand = inv.getItemInOffHand();
            if (BundleCheck.isNonEmptyBundle(offhand)) {
                List<ItemStack> extracted = BundleCheck.extractAndClear(offhand);
                inv.setItemInOffHand(null);
                BundleCheck.deliver(player, extracted, dropOnGround(), context + "/offhand");
                processedAny = true;
            }

            if (processedAny) {
                // CRÍTICO: mutações de inventário feitas fora do fluxo normal de
                // um clique (join, quit, scan periódico, etc) NÃO reenviam
                // automaticamente o pacote de inventário pro cliente — o servidor
                // já está correto, mas a tela do jogador continuaria mostrando o
                // estado antigo até ele mexer em algo que force um refresh
                // natural. updateInventory() força esse reenvio manualmente.
                // Isso é ainda mais importante em jogadores Bedrock via
                // Geyser/Floodgate, onde a tradução de pacotes é mais sensível a
                // dessincronia entre estado real do servidor e o que o cliente
                // mostra (ver pesquisa sobre limitações do Geyser).
                player.updateInventory();
            }
        } finally {
            BundleCheck.release(id);
        }
    }
}
