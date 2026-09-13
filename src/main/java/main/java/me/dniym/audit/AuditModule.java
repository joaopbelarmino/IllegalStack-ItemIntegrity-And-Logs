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
    private final IllegalStack plugin;private final AuditConfig config;private final AuditDatabase database;
    private final ContainerResolver resolver;private final ItemAggregator aggregator;private final DirtyContainerQueue dirty;
    private final PlayerDataIndexer playerdata;private final AuditGui gui;private volatile boolean enabled;

    public AuditModule(IllegalStack plugin){
        this.plugin=plugin;AuditConfig c=null;AuditDatabase db=null;ContainerResolver r=null;ItemAggregator a=null;DirtyContainerQueue d=null;PlayerDataIndexer p=null;AuditGui g=null;
        try{
            c=new AuditConfig(plugin);enabled=c.enabled();
            if(enabled&&IllegalStack.isFoliaServer()){LOGGER.warn("[IllegalStack] Auditoria de containers desativada no Folia; suporte regional ainda nao e seguro.");enabled=false;}
            if(enabled){
                db=new AuditDatabase(c.databaseFile(),c.dbQueueCapacity());r=new ContainerResolver();
                var integrity=plugin.getItemIntegritySystem();a=new ItemAggregator(integrity==null?null:integrity.identityService());
                d=new DirtyContainerQueue(plugin,c,db,r,a);p=new PlayerDataIndexer(plugin,c,db,a);g=new AuditGui(this);
                Bukkit.getPluginManager().registerEvents(new ContainerAuditListener(c,d),plugin);Bukkit.getPluginManager().registerEvents(g,plugin);
                Bukkit.getPluginManager().registerEvents(new PlayerAuditListener(p),plugin);
                db.cleanup(c.destroyedRetentionDays());
                LOGGER.info("[IllegalStack] Auditoria de inventarios habilitada em {}",c.databaseFile().getAbsolutePath());
            }
        }catch(IOException|SQLException|RuntimeException e){enabled=false;LOGGER.error("[IllegalStack] Auditoria desativada; IllegalStack principal continua ativo.",e);}
        config=c;database=db;resolver=r;aggregator=a;dirty=d;playerdata=p;gui=g;
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
        return database.suspicious(config.thresholds(),config.minimumScore(),50).thenApply(rows->{
        if(scope==null||scope.equals("all")||scope.equals("suspeito"))return rows;
        boolean containers=scope.equals("bau")||scope.equals("container");
        return rows.stream().filter(r->containers?r.targetType().equals("CONTAINER"):r.targetType().equals("PLAYER")).toList();
    });}

    public void openContainer(Player viewer,UUID id){
        database.loadContainer(id).whenComplete((stored,error)->Scheduler.runTask(plugin,()->{
            if(error!=null||stored==null){message(viewer,ChatColor.RED+"Container nao encontrado.");return;}
            try{ItemStack[] items=SnapshotCodec.deserialize(SnapshotCodec.decompress(stored.compressedInventory(),32*1024*1024));gui.openContainer(viewer,stored,items);}
            catch(Exception e){message(viewer,ChatColor.RED+"Snapshot do container nao pode ser aberto.");}
        }));
    }
    public void openLiveContainer(Player viewer,ContainerResolver.Resolved resolved){
        ItemStack[] items=resolved.inventory().getContents();byte[] raw=SnapshotCodec.serialize(items);String hash=SnapshotCodec.hash(raw);long now=System.currentTimeMillis();
        AuditDatabase.StoredContainer stored=new AuditDatabase.StoredContainer(resolved.ref(),"ACTIVE",now,now,viewer.getName(),"PLAYER",hash,null);
        dirty.mark(resolved.inventory(),AuditCause.PLAYER,viewer,true);gui.openContainer(viewer,stored,items);
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

    public TransferResult transferContainer(Player admin,AuditDatabase.StoredContainer stored,ItemStack[] snapshot,Set<Integer> slots){
        if(!config.removalEnabled()||!admin.hasPermission("illegalstack.audit.edit"))return new TransferResult(false,"Sem permissao para transferir.");
        Optional<ContainerResolver.Resolved> current=dirty.resolveLive(stored.ref());
        if(current.isEmpty())return new TransferResult(false,"Container nao esta carregado ou mudou de identidade.");
        Inventory source=current.get().inventory();byte[] raw=SnapshotCodec.serialize(source.getContents());
        if(!SnapshotCodec.hash(raw).equals(stored.hash()))return new TransferResult(false,"Container mudou desde que a interface foi aberta.");
        List<ItemStack> moving=new ArrayList<>();
        for(int slot:slots){if(slot<0||slot>=source.getSize()||slot>=snapshot.length)return new TransferResult(false,"Slot invalido.");ItemStack now=source.getItem(slot);if(now==null||!now.equals(snapshot[slot]))return new TransferResult(false,"Um item mudou; reabra a interface.");moving.add(now.clone());}
        if(!canFit(admin.getInventory(),moving))return new TransferResult(false,"Seu inventario nao possui espaco para todas as stacks.");
        for(int slot:slots)source.setItem(slot,null);for(ItemStack item:moving)admin.getInventory().addItem(item);
        String summary=moving.stream().map(i->i.getAmount()+"x "+i.getType().name()).reduce((x,y)->x+", "+y).orElse("");
        database.logAdmin(admin.getUniqueId(),admin.getName(),"CONTAINER",stored.ref().uuid().toString(),summary);
        dirty.mark(source,AuditCause.PLAYER,admin,false);LOGGER.warn("[IllegalStack] {} transferiu de {}: {}",admin.getName(),stored.ref().uuid(),summary);
        return new TransferResult(true,"Transferido: "+summary);
    }

    public TransferResult transferOnlinePlayer(Player admin,UUID owner,boolean ender,String expectedHash,ItemStack[] snapshot,Set<Integer> slots){
        Player target=Bukkit.getPlayer(owner);if(target==null||!target.isOnline())return new TransferResult(false,"Jogador ficou offline; operacao cancelada.");
        Inventory source=ender?target.getEnderChest():target.getInventory();String hash=SnapshotCodec.hash(SnapshotCodec.serialize(source.getContents()));
        if(!hash.equals(expectedHash))return new TransferResult(false,"Inventario mudou desde que a interface foi aberta.");
        List<ItemStack> moving=new ArrayList<>();for(int slot:slots){if(slot<0||slot>=source.getSize()||slot>=snapshot.length)return new TransferResult(false,"Slot invalido.");ItemStack now=source.getItem(slot);if(now==null||!now.equals(snapshot[slot]))return new TransferResult(false,"Item mudou; reabra a interface.");moving.add(now.clone());}
        if(!canFit(admin.getInventory(),moving))return new TransferResult(false,"Seu inventario nao possui espaco para todas as stacks.");
        for(int slot:slots)source.setItem(slot,null);for(ItemStack item:moving)admin.getInventory().addItem(item);target.saveData();playerdata.indexOnline(target);
        String summary=moving.stream().map(i->i.getAmount()+"x "+i.getType().name()).reduce((x,y)->x+", "+y).orElse("");database.logAdmin(admin.getUniqueId(),admin.getName(),ender?"PLAYER_ENDER":"PLAYER_INV",owner.toString(),summary);LOGGER.warn("[IllegalStack] {} transferiu de {}: {}",admin.getName(),owner,summary);return new TransferResult(true,"Transferido: "+summary);
    }

    private boolean canFit(Inventory inventory,List<ItemStack> moving){
        return canFit(inventory.getStorageContents(),moving);
    }

    static boolean canFit(ItemStack[] original,List<ItemStack> moving){
        ItemStack[] simulated=new ItemStack[original.length];
        for(int i=0;i<original.length;i++)simulated[i]=original[i]==null?null:original[i].clone();
        for(ItemStack item:moving){
            int left=item.getAmount();
            for(ItemStack current:simulated){
                if(current==null||!current.isSimilar(item))continue;
                int placed=Math.min(left,Math.max(0,current.getMaxStackSize()-current.getAmount()));
                current.setAmount(current.getAmount()+placed);left-=placed;if(left==0)break;
            }
            for(int i=0;i<simulated.length&&left>0;i++){
                if(simulated[i]!=null&&!simulated[i].getType().isAir())continue;
                int placed=Math.min(left,item.getMaxStackSize());simulated[i]=item.asQuantity(placed);left-=placed;
            }
            if(left>0)return false;
        }
        return true;
    }
    public void reload()throws IOException{config.reload();}
    public void shutdown(){enabled=false;if(playerdata!=null)playerdata.close();if(dirty!=null)dirty.shutdown();if(database!=null)database.close();}
    public static void message(org.bukkit.command.CommandSender sender,String text){sender.sendMessage(ChatColor.AQUA+"[IllegalStack] "+text);}
    public record TransferResult(boolean success,String message){}
}
