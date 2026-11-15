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
    private final LinkedHashMap<UUID,Entry> discovery=new LinkedHashMap<>();
    private final Map<String,Set<UUID>> byChunk=new HashMap<>();
    private final LinkedHashMap<String,Long> topology=new LinkedHashMap<>(256,0.75f,true);
    private final Map<String,Long> discovered=new LinkedHashMap<>(256,0.75f,true){
        @Override protected boolean removeEldestEntry(Map.Entry<String,Long> e){return size()>16384;}
    };
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
        long now=System.currentTimeMillis();UUID id=resolved.ref().uuid();Entry entry=dirty.get(id);
        if(entry==null)entry=discovery.remove(id);
        if(entry==null){if(dirty.size()>=config.dirtyCapacity()){dropped++;return;}
            entry=new Entry(resolved,now);index(entry);
        }
        dirty.put(id,entry);
        var previousRef=entry.resolved.ref();var current=resolved.ref();
        if(!previousRef.world().equals(current.world())||(previousRef.x()>>4)!=(current.x()>>4)||(previousRef.z()>>4)!=(current.z()>>4)){
            unindex(entry);entry.resolved=resolved;index(entry);
        }else entry.resolved=resolved;
        entry.due=Math.min(now+config.debounceTicks()*50L,entry.first+config.maxDirtyTicks()*50L);
        if(cause!=AuditCause.INSPECTION||entry.cause==AuditCause.UNKNOWN)entry.cause=cause;
        if(player!=null){entry.player=player.getUniqueId();entry.name=player.getName();
            if(interaction){var old=entry.interactions.get(entry.player);
                entry.interactions.put(entry.player,new AuditDatabase.InteractionDelta(entry.name,old==null?now:old.first(),now,old==null?1:old.count()+1));
            }
        }
    }
    public void discover(Inventory inventory){
        if(!config.containersEnabled())return;
        resolver.resolve(inventory).ifPresent(r->{
            if(dirty.containsKey(r.ref().uuid())||discovery.containsKey(r.ref().uuid())||System.currentTimeMillis()-discovered.getOrDefault(r.ref().locationKey(),0L)<300000)return;
            if(discovery.size()>=Math.max(64,config.dirtyCapacity()/4)){dropped++;return;}
            Entry e=new Entry(r,System.currentTimeMillis());discovery.put(r.ref().uuid(),e);index(e);
        });
    }
    private Set<String> chunkKeys(ContainerRef ref){
        Set<String> keys=new HashSet<>();
        for(String part:parts(ref)){
            String[] split=part.split(":");
            if(part.startsWith("block:")&&split.length>=5)
                keys.add(ref.world()+":"+(Integer.parseInt(split[split.length-3])>>4)+":"+(Integer.parseInt(split[split.length-1])>>4));
        }
        if(keys.isEmpty())keys.add(ref.world()+":"+(ref.x()>>4)+":"+(ref.z()>>4));
        return keys;
    }
    private String[] parts(ContainerRef ref){return ref.locationKey().replaceFirst("^double:","").split("\\+");}
    private void index(Entry e){e.chunks=chunkKeys(e.resolved.ref());for(String key:e.chunks)byChunk.computeIfAbsent(key,k->new HashSet<>()).add(e.resolved.ref().uuid());}
    private void unindex(Entry e){for(String key:e.chunks){Set<UUID> ids=byChunk.get(key);if(ids!=null){ids.remove(e.resolved.ref().uuid());if(ids.isEmpty())byChunk.remove(key);}}}
    private Entry remove(UUID id){Entry e=dirty.remove(id);if(e==null)e=discovery.remove(id);if(e!=null)unindex(e);return e;}
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
        for(String key:parts(resolved.ref()))touch(key);
        discovered.remove(resolved.ref().locationKey());
        Entry entry=remove(resolved.ref().uuid());if(entry==null)entry=new Entry(resolved,System.currentTimeMillis());
        entry.cause=cause;if(player!=null){entry.player=player.getUniqueId();entry.name=player.getName();}
        capture(entry,true);
    }
    public long topologyRevision(ContainerRef ref){
        long latest=0;for(String key:parts(ref)){Long value=topology.get(key);if(value==null){touch(key);value=topology.get(key);}latest=Math.max(latest,value);}return latest;
    }
    private void touch(String key){
        topology.put(key,++topologyRevision);
        if(topology.size()>8192){var it=topology.keySet().iterator();it.next();it.remove();}
    }
    public void refreshChestTopology(Block block){
        touch("block:"+block.getWorld().getUID()+":"+block.getX()+":"+block.getY()+":"+block.getZ());
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
        if(world==null)return Optional.empty();
        for(String part:parts(ref)){
            String[] p=part.split(":");
            if(part.startsWith("block:")&&p.length>=5&&!world.isChunkLoaded(Integer.parseInt(p[p.length-3])>>4,Integer.parseInt(p[p.length-1])>>4))return Optional.empty();
        }
        if(!world.isChunkLoaded(ref.x()>>4,ref.z()>>4))return Optional.empty();
        return resolver.resolve(world.getBlockAt(ref.x(),ref.y(),ref.z())).filter(r->r.ref().locationKey().equals(ref.locationKey()));
    }
    public void unload(org.bukkit.Chunk chunk){
        String key=chunk.getWorld().getName()+":"+chunk.getX()+":"+chunk.getZ();
        for(UUID id:new HashSet<>(byChunk.getOrDefault(key,Set.of()))){Entry e=remove(id);if(e!=null){
            if(e.cause!=AuditCause.UNKNOWN||!e.interactions.isEmpty())capture(e,false);
        }}
    }
    public void unloadEntities(List<Entity> entities){
        for(Entity entity:entities)if(entity instanceof InventoryHolder holder)resolver.resolve(holder.getInventory()).ifPresent(r->{Entry e=remove(r.ref().uuid());if(e!=null)capture(e,false);});
    }
    private void flushDue(){
        long start=System.nanoTime(),now=System.currentTimeMillis();int captured=0,visited=0;
        if(cycleTick++%10==0)cycleCaptures=0;
        List<Entry> waiting=new ArrayList<>();
        var queue=dirty.isEmpty()?discovery:dirty;
        Iterator<Entry> it=queue.values().iterator();
        while(it.hasNext()&&captured<1&&cycleCaptures<config.snapshotBudget()&&visited++<256&&System.nanoTime()-start<config.snapshotBudgetNanos()){
            Entry e=it.next();if(e.due>now){it.remove();waiting.add(e);continue;}
            it.remove();unindex(e);
            var live=resolveLive(e.resolved.ref());
            if(live.isPresent()){e.resolved=live.get();capture(e,false);captured++;cycleCaptures++;}
            else dropped++;
        }
        for(Entry e:waiting)queue.put(e.resolved.ref().uuid(),e);
        lastElapsedNanos=System.nanoTime()-start;
    }
    private void capture(Entry e,boolean destroyed){
        try{
            if(!destroyed){var live=resolveLive(e.resolved.ref());if(live.isEmpty()){dropped++;return;}e.resolved=live.get();}
            byte[] bytes=SnapshotCodec.serialize(e.resolved.inventory().getContents());
            if(!database.submitRaw(e.resolved.ref(),bytes,e.player,e.name,e.cause,System.currentTimeMillis(),destroyed,Map.copyOf(e.interactions)))dropped++;
            else if(!destroyed)discovered.put(e.resolved.ref().locationKey(),System.currentTimeMillis());
        }catch(RuntimeException error){dropped++;if(System.currentTimeMillis()-lastError<60000)return;lastError=System.currentTimeMillis();plugin.getLogger().warning("Auditoria: falha de snapshot; nenhuma alteracao aplicada: "+error.getClass().getSimpleName());}
    }
    public int size(){return dirty.size()+discovery.size();}
    public long dropped(){return dropped;}
    public long lastElapsedNanos(){return lastElapsedNanos;}
    public void shutdown(){task.cancel();for(Entry entry:dirty.values())capture(entry,false);dirty.clear();discovery.clear();byChunk.clear();}
    private static final class Entry{
        Set<String> chunks=Set.of();
        ContainerResolver.Resolved resolved;final long first;long due;UUID player;String name;AuditCause cause=AuditCause.UNKNOWN;
        final Map<UUID,AuditDatabase.InteractionDelta> interactions=new HashMap<>();
        Entry(ContainerResolver.Resolved r,long now){resolved=r;first=now;due=now;}
    }
}
