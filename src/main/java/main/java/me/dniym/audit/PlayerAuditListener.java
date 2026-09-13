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
    @EventHandler(priority=EventPriority.MONITOR)public void join(PlayerJoinEvent event){indexer.indexOnline(event.getPlayer());}
    @EventHandler(priority=EventPriority.MONITOR)public void quit(PlayerQuitEvent event){indexer.indexOnline(event.getPlayer());}
}
