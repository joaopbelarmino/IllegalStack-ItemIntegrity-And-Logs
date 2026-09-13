package main.java.me.dniym.audit.container;

import main.java.me.dniym.IllegalStack;
import main.java.me.dniym.audit.AuditConfig;
import main.java.me.dniym.audit.database.AuditDatabase;
import main.java.me.dniym.audit.model.AuditCause;
import main.java.me.dniym.audit.model.ContainerRef;
import main.java.me.dniym.audit.model.ContainerSnapshot;
import main.java.me.dniym.utils.Scheduler;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public final class DirtyContainerQueue {
    private static final Logger LOGGER = LogManager.getLogger("IllegalStack/Audit");
    private final IllegalStack plugin;
    private final AuditConfig config;
    private final AuditDatabase database;
    private final ContainerResolver resolver;
    private final ItemAggregator aggregator;
    private final Map<UUID, Entry> dirty = new LinkedHashMap<>();
    private final Scheduler.ScheduledTask task;
    private long dropped;

    public DirtyContainerQueue(IllegalStack plugin, AuditConfig config, AuditDatabase database,
                               ContainerResolver resolver, ItemAggregator aggregator) {
        this.plugin=plugin;this.config=config;this.database=database;this.resolver=resolver;this.aggregator=aggregator;
        this.task=Scheduler.runTaskTimer(plugin,this::flushDue,10,10);
    }

    public void mark(Inventory inventory, AuditCause cause, Player player, boolean interaction) {
        if(!config.containersEnabled())return;
        resolver.resolve(inventory).ifPresent(resolved -> mark(resolved.ref(),cause,player,interaction));
    }

    public void mark(Block block, AuditCause cause, Player player, boolean interaction) {
        if(!config.containersEnabled())return;
        resolver.resolve(block).ifPresent(resolved -> mark(resolved.ref(),cause,player,interaction));
    }

    private void mark(ContainerRef ref, AuditCause cause, Player player, boolean interaction) {
        long now=System.currentTimeMillis();
        Entry old=dirty.get(ref.uuid());
        if(old==null&&dirty.size()>=config.dirtyCapacity()){dropped++;return;}
        long firstDirty=old==null?now:old.firstDirty;
        long due=Math.min(now+config.debounceTicks()*50L,firstDirty+config.maxDirtyTicks()*50L);
        UUID playerId=player!=null?player.getUniqueId():old==null?null:old.playerId;
        String playerName=player!=null?player.getName():old==null?null:old.playerName;
        int interactions=(old==null?0:old.interactions)+(interaction?1:0);
        dirty.put(ref.uuid(),new Entry(ref,cause,playerId,playerName,firstDirty,due,interactions));
    }

    public void destroy(Block block, AuditCause cause, Player player) {
        if(!config.containersEnabled())return;
        resolver.resolve(block).ifPresent(resolved -> {
            dirty.remove(resolved.ref().uuid());
            capture(resolved, cause, player, 0, true);
        });
    }

    public void destroy(Inventory inventory, AuditCause cause, Player player) {
        if(!config.containersEnabled())return;
        resolver.resolve(inventory).ifPresent(resolved -> {
            dirty.remove(resolved.ref().uuid());
            capture(resolved, cause, player, 0, true);
        });
    }

    public void refreshChestTopology(Block block) {
        Scheduler.runTaskLater(plugin, () -> {
            mark(block, AuditCause.UNKNOWN, null, false);
            for (BlockFace face : new BlockFace[]{BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST}) {
                mark(block.getRelative(face), AuditCause.UNKNOWN, null, false);
            }
        }, 1L);
    }

    public Optional<ContainerResolver.Resolved> resolveLive(ContainerRef ref) {
        if(ref.entityUuid()!=null){
            Entity entity=Bukkit.getEntity(ref.entityUuid());
            if(entity instanceof InventoryHolder holder){
                Optional<ContainerResolver.Resolved> current=resolver.resolve(holder.getInventory());
                if(current.isPresent()&&current.get().ref().uuid().equals(ref.uuid()))return current;
            }
            return Optional.empty();
        }
        World world=Bukkit.getWorld(ref.world());
        if(world==null||!world.isChunkLoaded(ref.x()>>4,ref.z()>>4))return Optional.empty();
        Optional<ContainerResolver.Resolved> current=resolver.resolve(world.getBlockAt(ref.x(),ref.y(),ref.z()));
        return current.filter(value->value.ref().uuid().equals(ref.uuid()));
    }

    private void flushDue() { flushDue(false); }
    private void flushDue(boolean all) {
        if(!Bukkit.isPrimaryThread()&& !IllegalStack.isFoliaServer())return;
        long now=System.currentTimeMillis();int budget=config.snapshotBudget();
        Iterator<Map.Entry<UUID,Entry>> it=dirty.entrySet().iterator();
        ArrayList<Entry> due=new ArrayList<>(budget);
        while(it.hasNext()&&(all||due.size()<budget)){Map.Entry<UUID,Entry> next=it.next();if(!all&&next.getValue().due>now)continue;due.add(next.getValue());it.remove();}
        for(Entry entry:due){
            try{resolveLive(entry.ref).ifPresent(resolved->capture(resolved,entry.cause,entry.playerId,entry.playerName,entry.interactions,false));}
            catch(RuntimeException e){LOGGER.warn("[IllegalStack] Falha ao capturar container {}",entry.ref.uuid(),e);}
        }
    }

    private void capture(ContainerResolver.Resolved resolved, AuditCause cause, Player player, int interactions, boolean destroyed) {
        capture(resolved,cause,player==null?null:player.getUniqueId(),player==null?null:player.getName(),interactions,destroyed);
    }
    private void capture(ContainerResolver.Resolved resolved, AuditCause cause, UUID playerId, String playerName,
                         int interactions, boolean destroyed) {
        ItemStack[] copy=resolved.inventory().getContents();
        byte[] raw=SnapshotCodec.serialize(copy);
        ContainerSnapshot snapshot=new ContainerSnapshot(resolved.ref(),raw,SnapshotCodec.hash(raw),aggregator.aggregate(copy),
                playerId,playerName,cause,System.currentTimeMillis(),destroyed,destroyed?playerId:null,interactions);
        if(!database.submitContainer(snapshot,interactions>0))dropped++;
    }

    public int size(){return dirty.size();}
    public long dropped(){return dropped;}
    public void shutdown(){task.cancel();flushDue(true);}
    private record Entry(ContainerRef ref,AuditCause cause,UUID playerId,String playerName,long firstDirty,long due,int interactions){}
}
