package main.java.me.dniym.audit;

import main.java.me.dniym.IllegalStack;
import main.java.me.dniym.audit.container.*;
import main.java.me.dniym.audit.database.AuditDatabase;
import main.java.me.dniym.audit.gui.AuditGui;
import main.java.me.dniym.audit.model.AuditCause;
import main.java.me.dniym.audit.model.ContainerRef;
import main.java.me.dniym.audit.model.SearchResult;
import main.java.me.dniym.audit.playerdata.PlayerDataIndexer;
import main.java.me.dniym.audit.playerdata.PlayerDataReader;
import main.java.me.dniym.audit.search.ItemQuery;
import main.java.me.dniym.utils.Scheduler;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.io.IOException;
import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.CompletableFuture;

public final class AuditModule {
    private static final Logger LOGGER=LogManager.getLogger("IllegalStack/Audit");
    private final IllegalStack plugin;private AuditConfig config;private AuditDatabase database;
    private ContainerResolver resolver;private ItemAggregator aggregator;private DirtyContainerQueue dirty;
    private Scheduler.ScheduledTask cleanupTask;
    private final Set<UUID> pendingAdmins=new HashSet<>();
    private PlayerDataIndexer playerdata;private AuditGui gui;private volatile boolean enabled,closing;

    public AuditModule(IllegalStack plugin){
        this.plugin=plugin;
        try{config=new AuditConfig(plugin);}catch(IOException e){LOGGER.error("Falha ao ler configuracao audit",e);return;}
        if(!config.enabled()||IllegalStack.isFoliaServer())return;
        CompletableFuture.supplyAsync(()->{
            try{return new AuditDatabase(config.databaseFile(),config.dbQueueCapacity());}
            catch(SQLException e){throw new java.util.concurrent.CompletionException(e);}
        }).whenComplete((db,error)->{
            if(error!=null){LOGGER.error("Auditoria indisponivel; falha ao inicializar banco",error);return;}
            if(closing){db.close();return;}
            try{Scheduler.runTask(plugin,()->{
                if(closing){CompletableFuture.runAsync(db::close);return;}
                database=db;resolver=new ContainerResolver();
                var integrity=plugin.getItemIntegritySystem();aggregator=new ItemAggregator(integrity==null?null:integrity.identityService());
                dirty=new DirtyContainerQueue(plugin,config,database,resolver,aggregator);
                playerdata=new PlayerDataIndexer(plugin,config,database,aggregator);gui=new AuditGui(this);
                Bukkit.getPluginManager().registerEvents(new ContainerAuditListener(config,dirty),plugin);
                Bukkit.getPluginManager().registerEvents(gui,plugin);Bukkit.getPluginManager().registerEvents(new PlayerAuditListener(playerdata),plugin);
                enabled=true;
                database.cleanup(config.destroyedRetentionDays());database.cleanupEvidence(config.backupRetentionDays());
                cleanupTask=Scheduler.runTaskTimer(plugin,()->{database.cleanup(config.destroyedRetentionDays());database.cleanupEvidence(config.backupRetentionDays());},72000,72000);
                LOGGER.info("Auditoria habilitada: {}",config.databaseFile());
            });}catch(RuntimeException e){db.close();LOGGER.error("Falha ao agendar inicializacao de auditoria",e);}
        });
    }

    public boolean enabled(){return enabled;}
    public AuditConfig config(){return config;}
    public AuditDatabase database(){return database;}
    public DirtyContainerQueue dirty(){return dirty;}
    public ContainerResolver resolver(){return resolver;}
    public PlayerDataIndexer playerdata(){return playerdata;}
    public AuditGui gui(){return gui;}

    public CompletableFuture<List<SearchResult>> search(String scope,String raw,int page){
        ItemQuery q=ItemQuery.parse(raw);int offset=Math.max(0,page-1)*20;
        return q.type()==ItemQuery.Type.MATERIAL?database.search(scope,q.value(),20,offset):database.searchSerial(q.value(),20,offset);
    }
    public CompletableFuture<List<SearchResult>> suspicious(String scope){
        if(!config.suspiciousEnabled())return CompletableFuture.completedFuture(List.of());
        return database.suspicious(config.thresholds(),config.minimumScore(),50,scope);
    }

    public void openContainer(Player viewer,UUID id){
        database.loadContainer(id).whenComplete((stored,error)->Scheduler.runTask(plugin,()->{
            if(error!=null||stored==null){message(viewer,ChatColor.RED+"Container nao encontrado.");return;}
            try{ItemStack[] items=SnapshotCodec.deserialize(SnapshotCodec.decompress(stored.compressedInventory(),32*1024*1024));gui.openContainer(viewer,stored,items);}
            catch(Exception e){message(viewer,ChatColor.RED+"Snapshot do container nao pode ser aberto.");}
        }));
    }
    public void openLiveContainer(Player viewer,ContainerResolver.Resolved resolved){
        ItemStack[] items=resolved.inventory().getContents();byte[] raw=SnapshotCodec.serialize(items);String hash=SnapshotCodec.hash(raw);long now=System.currentTimeMillis();
        dirty.inspect(resolved);
        database.loadActive(resolved.ref().locationKey()).whenComplete((previous,error)->Scheduler.runTask(plugin,()->{
            if(!viewer.isOnline())return;
            var ref=previous==null?resolved.ref():previous.ref();
            var stored=new AuditDatabase.StoredContainer(ref,"ACTIVE",previous==null?now:previous.createdAt(),previous==null?now:previous.updatedAt(),
                previous==null?null:previous.lastPlayer(),previous==null?"INSPECTION":previous.lastCause(),hash,null);
            gui.openContainer(viewer,stored,items);
        }));
    }

    public void openPlayer(Player viewer,UUID id,boolean ender){
        Player online=Bukkit.getPlayer(id);
        if(online!=null&&online.isOnline()){
            ItemStack[] items=(ender?online.getEnderChest():online.getInventory()).getContents();
            gui.openPlayer(viewer,id,online.getName(),ender,items,"online",System.currentTimeMillis());return;
        }
        playerdata.readAsync(id).whenComplete((snapshot,error)->Scheduler.runTask(plugin,()->{
            if(error!=null||snapshot==null){message(viewer,ChatColor.RED+"Playerdata nao encontrado ou ilegivel.");return;}
            List<PlayerDataReader.RawSlot> raw=ender?snapshot.ender():snapshot.inventory();
            gui.openOfflinePlayer(viewer,id,ender,raw,snapshot.dataVersion(),snapshot.hash(),snapshot.mtime());
        }));
    }


    public void transferContainer(Player admin,AuditDatabase.StoredContainer stored,ItemStack[] snapshot,Set<Integer> slots){
        if(!"ACTIVE".equals(stored.status())){message(admin,"Snapshot somente leitura.");return;}
        java.util.function.Supplier<Inventory> source=()->dirty.resolveLive(stored.ref()).map(ContainerResolver.Resolved::inventory).orElse(null);
        long topology=dirty.topologyRevision();
        database.loadActive(stored.ref().locationKey()).whenComplete((active,error)->Scheduler.runTask(plugin,()->{
            if(error!=null||active==null||!active.ref().uuid().equals(stored.ref().uuid())||topology!=dirty.topologyRevision()){
                message(admin,"Registro do container mudou ou ainda nao foi salvo; reabra a interface.");return;
            }
            transfer(admin,source,stored.hash(),snapshot,slots,"CONTAINER",stored.ref().uuid().toString(),()->{
                Inventory current=source.get();if(current!=null)dirty.mark(current,AuditCause.ADMIN_TRANSFER,null,false);
            });
        }));
    }
    public void transferOnlinePlayer(Player admin,UUID owner,boolean ender,String hash,ItemStack[] snapshot,Set<Integer> slots){
        if(owner.equals(admin.getUniqueId())){message(admin,"Transferencia para o mesmo jogador nao e permitida.");return;}
        java.util.function.Supplier<Inventory> source=()->{Player p=Bukkit.getPlayer(owner);return p==null||!p.isOnline()?null:ender?p.getEnderChest():p.getInventory();};
        transfer(admin,source,hash,snapshot,slots,ender?"PLAYER_ENDER":"PLAYER_INV",owner.toString(),()->{
            Player p=Bukkit.getPlayer(owner);if(p!=null)playerdata.indexOnline(p);playerdata.indexOnline(admin);
        });
    }
    private void transfer(Player admin,java.util.function.Supplier<Inventory> sourceSupplier,String expectedHash,
                          ItemStack[] snapshot,Set<Integer> slots,String type,String target,Runnable after){
        if(!enabled||!config.removalEnabled()||!admin.hasPermission("illegalstack.audit.edit")||!database.healthy()){
            message(admin,"Transferencia indisponivel; confira permissao e banco.");return;
        }
        if(slots.isEmpty()||!pendingAdmins.add(admin.getUniqueId()))return;
        try{
            Inventory source=sourceSupplier.get();if(source==null)throw new IllegalStateException("Origem indisponivel.");
            ItemStack[] before=cloneItems(source.getContents()),destination=cloneItems(admin.getInventory().getStorageContents());
            byte[] sourceBytes=SnapshotCodec.serialize(before),destinationBytes=SnapshotCodec.serialize(destination);
            if(!SnapshotCodec.hash(sourceBytes).equals(expectedHash))throw new IllegalStateException("Origem mudou; reabra a interface.");
            List<ItemStack> moving=new ArrayList<>();ItemStack[] afterSource=cloneItems(before);
            for(int slot:slots){
                if(slot<0||slot>=before.length||slot>=snapshot.length||before[slot]==null||!before[slot].equals(snapshot[slot]))throw new IllegalStateException("Slot mudou.");
                moving.add(before[slot].clone());afterSource[slot]=null;
            }
            ItemStack[] afterDestination=planDestination(destination,moving,admin.getInventory().getMaxStackSize());
            if(afterDestination==null)throw new IllegalStateException("Inventario do administrador sem espaco.");
            long topology=dirty.topologyRevision();
            String summary=moving.stream().map(i->i.getAmount()+"x "+i.getType().name()).collect(java.util.stream.Collectors.joining(", "));
            database.prepareTransfer(admin.getUniqueId(),admin.getName(),type,target,summary,sourceBytes,destinationBytes)
                .whenComplete((action,error)->Scheduler.runTask(plugin,()->{
                    try{
                        if(error!=null){message(admin,"Snapshot/auditoria falhou; nenhum item transferido.");return;}
                        Inventory current=sourceSupplier.get();
                        if(!enabled||!admin.isOnline()||!config.removalEnabled()||!admin.hasPermission("illegalstack.audit.edit")||current==null
                                ||(type.equals("CONTAINER")&&topology!=dirty.topologyRevision())
                                ||!Arrays.equals(before,current.getContents())||!Arrays.equals(destination,admin.getInventory().getStorageContents())){
                            database.finishTransfer(action,"TRANSFER_ABORTED_REVALIDATION");message(admin,"Estado mudou; transferencia cancelada.");return;
                        }
                        String outcome="TRANSFERRED";
                        try{
                            current.setContents(afterSource);admin.getInventory().setStorageContents(afterDestination);
                        }catch(RuntimeException ex){
                            // Both snapshots were committed before touching inventories. Never retry delivery automatically.
                            try{current.setContents(before);admin.getInventory().setStorageContents(destination);outcome="TRANSFER_ROLLED_BACK";}
                            catch(RuntimeException rollback){outcome="TRANSFER_RECOVERY_REQUIRED";}
                        }
                        final String result=outcome;
                        database.finishTransfer(action,result).whenComplete((saved,failure)->Scheduler.runTask(plugin,()->{
                            message(admin,result+(failure!=null||!Boolean.TRUE.equals(saved)?" (resultado pendente no banco; nao repetir automaticamente)":""));
                        }));
                        after.run();
                    }finally{pendingAdmins.remove(admin.getUniqueId());}
                }));
        }catch(RuntimeException ex){pendingAdmins.remove(admin.getUniqueId());message(admin,"Transferencia cancelada: "+ex.getMessage());}
    }
    private static ItemStack[] cloneItems(ItemStack[] items){
        ItemStack[] result=new ItemStack[items.length];for(int i=0;i<items.length;i++)result[i]=items[i]==null?null:items[i].clone();return result;
    }
    static boolean canFit(ItemStack[] original,List<ItemStack> moving){return planDestination(original,moving,64)!=null;}
    static ItemStack[] planDestination(ItemStack[] original,List<ItemStack> moving,int inventoryMax){
        ItemStack[] simulated=cloneItems(original);
        for(ItemStack item:moving){
            int left=item.getAmount(),max=Math.min(inventoryMax,item.getMaxStackSize());
            if(max<1||left<1)return null;
            for(ItemStack current:simulated){
                if(current==null||!current.isSimilar(item))continue;
                int placed=Math.min(left,Math.max(0,max-current.getAmount()));
                current.setAmount(current.getAmount()+placed);left-=placed;if(left==0)break;
            }
            for(int i=0;i<simulated.length&&left>0;i++){
                if(simulated[i]!=null&&!simulated[i].getType().isAir())continue;
                int placed=Math.min(left,max);simulated[i]=item.asQuantity(placed);left-=placed;
            }
            if(left>0)return null;
        }
        return simulated;
    }
    public void reload()throws IOException{config.reload();}
    public void shutdown(){closing=true;enabled=false;if(cleanupTask!=null)cleanupTask.cancel();if(playerdata!=null)playerdata.close();if(dirty!=null)dirty.shutdown();if(database!=null)database.close();}
    public static void message(org.bukkit.command.CommandSender sender,String text){sender.sendMessage(ChatColor.AQUA+main.java.me.dniym.enums.Msg.PluginPrefix.getValue()+" "+text);}
    public record TransferResult(boolean success,String message){}
}
