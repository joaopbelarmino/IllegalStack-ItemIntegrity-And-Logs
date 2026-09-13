package main.java.me.dniym.audit.container;

import main.java.me.dniym.audit.AuditConfig;
import main.java.me.dniym.audit.model.AuditCause;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.*;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.inventory.*;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.vehicle.VehicleDestroyEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import io.papermc.paper.event.entity.ItemTransportingEntityValidateTargetEvent;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public final class ContainerAuditListener implements Listener {
    private final AuditConfig config;
    private final DirtyContainerQueue dirty;
    private final Map<String,Long> transportingThrottle=new HashMap<>();
    public ContainerAuditListener(AuditConfig config,DirtyContainerQueue dirty){this.config=config;this.dirty=dirty;}

    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true)
    public void open(InventoryOpenEvent event){if(event.getPlayer() instanceof Player p)dirty.mark(event.getInventory(),AuditCause.PLAYER,p,true);}
    @EventHandler(priority=EventPriority.MONITOR)
    public void close(InventoryCloseEvent event){if(event.getPlayer() instanceof Player p)dirty.mark(event.getInventory(),AuditCause.PLAYER,p,false);}
    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true)
    public void move(InventoryMoveItemEvent event){dirty.mark(event.getSource(),AuditCause.HOPPER,null,false);dirty.mark(event.getDestination(),AuditCause.HOPPER,null,false);}
    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true)
    public void pickup(InventoryPickupItemEvent event){dirty.mark(event.getInventory(),AuditCause.HOPPER_PICKUP,null,false);}
    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true)
    public void dispense(BlockDispenseEvent event){dirty.mark(event.getBlock(),event.getBlock().getType().name().contains("DROPPER")?AuditCause.DROPPER:AuditCause.DISPENSER,null,false);}
    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true)
    public void smelt(FurnaceSmeltEvent event){dirty.mark(event.getBlock(),AuditCause.FURNACE,null,false);}
    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true)
    public void burn(FurnaceBurnEvent event){dirty.mark(event.getBlock(),AuditCause.FURNACE,null,false);}
    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true)
    public void brew(BrewEvent event){dirty.mark(event.getBlock(),AuditCause.BREWING,null,false);}
    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true)
    public void craft(CrafterCraftEvent event){dirty.mark(event.getBlock(),AuditCause.CRAFTER,null,false);}
    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true)
    public void breakBlock(BlockBreakEvent event){
        dirty.destroy(event.getBlock(),AuditCause.BLOCK_BREAK,event.getPlayer());
        String type=event.getBlock().getType().name();
        if(type.equals("CHEST")||type.equals("TRAPPED_CHEST"))dirty.refreshChestTopology(event.getBlock());
    }
    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true)
    public void blockExplosion(BlockExplodeEvent event){for(Block block:event.blockList())dirty.destroy(block,AuditCause.EXPLOSION,null);}
    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true)
    public void entityExplosion(EntityExplodeEvent event){for(Block block:event.blockList())dirty.destroy(block,AuditCause.EXPLOSION,null);}
    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true)
    public void vehicleDestroy(VehicleDestroyEvent event){
        if(event.getVehicle() instanceof InventoryHolder holder)dirty.destroy(holder.getInventory(),AuditCause.VEHICLE_DESTROY,event.getAttacker() instanceof Player p?p:null);
    }
    @EventHandler(priority=EventPriority.MONITOR)
    public void chunkLoad(ChunkLoadEvent event){
        if(!config.indexChunkLoad())return;
        int seen=0;
        for(BlockState state:event.getChunk().getTileEntities()){
            if(state instanceof InventoryHolder holder){dirty.mark(holder.getInventory(),AuditCause.UNKNOWN,null,false);if(++seen>=128)break;}
        }
        if(seen<128)for(var entity:event.getChunk().getEntities())if(entity instanceof InventoryHolder holder){dirty.mark(holder.getInventory(),AuditCause.UNKNOWN,null,false);if(++seen>=128)break;}
    }
    @EventHandler(priority=EventPriority.MONITOR)
    public void itemTransportTarget(ItemTransportingEntityValidateTargetEvent event){
        long now=System.currentTimeMillis();String key=event.getEntity().getUniqueId()+":"+event.getBlock().getWorld().getUID()+":"+event.getBlock().getX()+":"+event.getBlock().getY()+":"+event.getBlock().getZ();
        Long previous=transportingThrottle.put(key,now);if(previous!=null&&now-previous<1000)return;
        if(transportingThrottle.size()>2048)transportingThrottle.entrySet().removeIf(e->now-e.getValue()>30_000);
        dirty.mark(event.getBlock(),AuditCause.COPPER_GOLEM,null,false);
    }
}
