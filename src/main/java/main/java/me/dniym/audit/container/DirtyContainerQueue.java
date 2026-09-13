package main.java.me.dniym.audit.container;

import main.java.me.dniym.IllegalStack;
import main.java.me.dniym.audit.AuditConfig;
import main.java.me.dniym.audit.database.AuditDatabase;
import main.java.me.dniym.audit.model.*;
import main.java.me.dniym.utils.Scheduler;
import org.bukkit.*;
import org.bukkit.block.*;
import org.bukkit.entity.*;
import org.bukkit.inventory.*;
import java.util.*;

/** All live inventory references are confined to the server thread. */
public final class DirtyContainerQueue {
    private final IllegalStack plugin;
    private final AuditConfig config;
    private final AuditDatabase database;
    private final ContainerResolver resolver;
    private final LinkedHashMap<UUID,Entry> dirty=new LinkedHashMap<>();
    private final Scheduler.ScheduledTask task;
    private long dropped, topologyRevision, lastElapsedNanos,lastError;
    private int cycleTick,cycleCaptures;
    public DirtyContainerQueue(IllegalStack plugin,AuditConfig config,AuditDatabase database,ContainerResolver resolver,ItemAggregator ignored){
        this.plugin=plugin;this.config=config;this.database=database;this.resolver=resolver;
        task=Scheduler.runTaskTimer(plugin,this::flushDue,1,1);
    }
    public void mark(Inventory inventory,AuditCause cause,Player player,boolean interaction){
        if(!config.containersEnabled())return;
        resolver.resolve(inventory).ifPresent(r->mark(r,cause,player,interaction));
    }
    public void mark(Block block,AuditCause cause,Player player,boolean interaction){
        if(!config.containersEnabled()||!block.getWorld().isChunkLoaded(block.getX()>>4,block.getZ()>>4))return;
        resolver.resolve(block).ifPresent(r->mark(r,cause,player,interaction));
    }
    private void mark(ContainerResolver.Resolved resolved,AuditCause cause,Player player,boolean interaction){
        long now=System.currentTimeMillis();Entry entry=dirty.get(resolved.ref().uuid());
        if(entry==null){if(dirty.size()>=config.dirtyCapacity()){dropped++;return;}
            entry=new Entry(resolved,now);dirty.put(resolved.ref().uuid(),entry);
        }
        entry.resolved=resolved;entry.due=Math.min(now+config.debounceTicks()*50L,entry.first+config.maxDirtyTicks()*50L);
        if(cause!=AuditCause.INSPECTION||entry.cause==AuditCause.UNKNOWN)entry.cause=cause;
        if(player!=null){entry.player=player.getUniqueId();entry.name=player.getName();
            if(interaction){var old=entry.interactions.get(entry.player);
                entry.interactions.put(entry.player,new AuditDatabase.InteractionDelta(entry.name,old==null?now:old.first(),now,old==null?1:old.count()+1));
            }
        }
    }
    public void inspect(ContainerResolver.Resolved resolved){mark(resolved,AuditCause.INSPECTION,null,false);}
    public void destroy(Block block,AuditCause cause,Player player){
        if(!config.containersEnabled())return;
        resolver.resolve(block).ifPresent(r->destroy(r,cause,player));
    }
    public void destroy(Inventory inventory,AuditCause cause,Player player){
        if(!config.containersEnabled())return;
        resolver.resolve(inventory).ifPresent(r->destroy(r,cause,player));
    }
    private void destroy(ContainerResolver.Resolved resolved,AuditCause cause,Player player){
        topologyRevision++;
        Entry entry=dirty.remove(resolved.ref().uuid());if(entry==null)entry=new Entry(resolved,System.currentTimeMillis());
        entry.cause=cause;if(player!=null){entry.player=player.getUniqueId();entry.name=player.getName();}
        capture(entry,true);
    }
    public long topologyRevision(){return topologyRevision;}
    public void refreshChestTopology(Block block){
        topologyRevision++;
        Scheduler.runTaskLater(plugin,()->{
            mark(block,AuditCause.UNKNOWN,null,false);
            for(BlockFace face:new BlockFace[]{BlockFace.NORTH,BlockFace.SOUTH,BlockFace.EAST,BlockFace.WEST})
                mark(block.getRelative(face),AuditCause.UNKNOWN,null,false);
        },1L);
    }
    public Optional<ContainerResolver.Resolved> resolveLive(ContainerRef ref){
        if(ref.entityUuid()!=null){
            Entity entity=Bukkit.getEntity(ref.entityUuid());
            return entity instanceof InventoryHolder holder?resolver.resolve(holder.getInventory()).filter(r->r.ref().locationKey().equals(ref.locationKey())):Optional.empty();
        }
        World world=Bukkit.getWorld(ref.world());
        if(world==null||!world.isChunkLoaded(ref.x()>>4,ref.z()>>4))return Optional.empty();
        return resolver.resolve(world.getBlockAt(ref.x(),ref.y(),ref.z())).filter(r->r.ref().locationKey().equals(ref.locationKey()));
    }
    public void unload(org.bukkit.Chunk chunk){
        Iterator<Entry> it=dirty.values().iterator();
        while(it.hasNext()){Entry e=it.next();var r=e.resolved.ref();
            if(r.world().equals(chunk.getWorld().getName())&&(r.x()>>4)==chunk.getX()&&(r.z()>>4)==chunk.getZ()){it.remove();capture(e,false);}
        }
    }
    public void unloadEntities(List<Entity> entities){
        for(Entity entity:entities)if(entity instanceof InventoryHolder holder)resolver.resolve(holder.getInventory()).ifPresent(r->{Entry e=dirty.remove(r.ref().uuid());if(e!=null)capture(e,false);});
    }
    private void flushDue(){
        long start=System.nanoTime(),now=System.currentTimeMillis();int captured=0,visited=0;
        if(cycleTick++%10==0)cycleCaptures=0;
        List<Entry> waiting=new ArrayList<>();
        Iterator<Entry> it=dirty.values().iterator();
        while(it.hasNext()&&captured<1&&cycleCaptures<config.snapshotBudget()&&visited++<256&&System.nanoTime()-start<config.snapshotBudgetNanos()){
            Entry e=it.next();if(e.due>now){it.remove();waiting.add(e);continue;}
            it.remove();
            if(resolveLive(e.resolved.ref()).isPresent()){capture(e,false);captured++;cycleCaptures++;}
            else dropped++;
        }
        for(Entry e:waiting)dirty.put(e.resolved.ref().uuid(),e);
        lastElapsedNanos=System.nanoTime()-start;
    }
    private void capture(Entry e,boolean destroyed){
        try{
            byte[] bytes=SnapshotCodec.serialize(e.resolved.inventory().getContents());
            if(!database.submitRaw(e.resolved.ref(),bytes,e.player,e.name,e.cause,System.currentTimeMillis(),destroyed,Map.copyOf(e.interactions)))dropped++;
        }catch(RuntimeException error){dropped++;if(System.currentTimeMillis()-lastError<60000)return;lastError=System.currentTimeMillis();plugin.getLogger().warning("Auditoria: falha de snapshot; nenhuma alteracao aplicada: "+error.getClass().getSimpleName());}
    }
    public int size(){return dirty.size();}
    public long dropped(){return dropped;}
    public long lastElapsedNanos(){return lastElapsedNanos;}
    public void shutdown(){task.cancel();for(Entry entry:dirty.values())capture(entry,false);dirty.clear();}
    private static final class Entry{
        ContainerResolver.Resolved resolved;final long first;long due;UUID player;String name;AuditCause cause=AuditCause.UNKNOWN;
        final Map<UUID,AuditDatabase.InteractionDelta> interactions=new HashMap<>();
        Entry(ContainerResolver.Resolved r,long now){resolved=r;first=now;due=now;}
    }
}
