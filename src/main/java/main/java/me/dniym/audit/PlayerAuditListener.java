package main.java.me.dniym.audit;

import main.java.me.dniym.audit.playerdata.PlayerDataIndexer;
import main.java.me.dniym.utils.Scheduler;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

final class PlayerAuditListener implements Listener {
    private final PlayerDataIndexer indexer;PlayerAuditListener(PlayerDataIndexer indexer){this.indexer=indexer;}
    @EventHandler(priority=EventPriority.MONITOR)public void join(PlayerJoinEvent event){indexer.joined(event.getPlayer());}
    @EventHandler(priority=EventPriority.MONITOR)public void quit(PlayerQuitEvent event){indexer.quit(event.getPlayer());}
    @EventHandler(priority=EventPriority.MONITOR)
    public void slot(io.papermc.paper.event.player.PlayerInventorySlotChangeEvent event){indexer.markOnline(event.getPlayer());}
    @EventHandler(priority=EventPriority.MONITOR)
    public void close(org.bukkit.event.inventory.InventoryCloseEvent event){
        if(event.getPlayer() instanceof org.bukkit.entity.Player p)indexer.markOnline(p);
    }
}
