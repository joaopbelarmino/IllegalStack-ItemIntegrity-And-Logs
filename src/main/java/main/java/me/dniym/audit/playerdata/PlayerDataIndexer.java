package main.java.me.dniym.audit.playerdata;

import main.java.me.dniym.IllegalStack;
import main.java.me.dniym.audit.AuditConfig;
import main.java.me.dniym.audit.container.ItemAggregator;
import main.java.me.dniym.audit.database.AuditDatabase;
import main.java.me.dniym.utils.Scheduler;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

public final class PlayerDataIndexer implements AutoCloseable {
    private static final Logger LOGGER= LogManager.getLogger("IllegalStack/Audit");
    private final IllegalStack plugin;private final AuditConfig config;private final AuditDatabase db;
    private final PlayerDataReader reader=new PlayerDataReader();private final ItemAggregator liveAggregator;
    private final ExecutorService worker=Executors.newSingleThreadExecutor(r->{Thread t=new Thread(r,"IllegalStack-Playerdata-Index");t.setDaemon(true);return t;});
    private final Map<UUID,Long> mtimes=new ConcurrentHashMap<>();private final Map<UUID,String> knownNames=new ConcurrentHashMap<>();private final AtomicBoolean scanning=new AtomicBoolean();
    private final File playerdataDirectory;
    private final File userCacheFile;
    private final AtomicLong scanned=new AtomicLong(),failed=new AtomicLong();private volatile long total,lastScan;
    private final Scheduler.ScheduledTask reconcileTask;

    public PlayerDataIndexer(IllegalStack plugin,AuditConfig config,AuditDatabase db,ItemAggregator liveAggregator){
        this.plugin=plugin;this.config=config;this.db=db;this.liveAggregator=liveAggregator;
        this.playerdataDirectory=new File(new File(Bukkit.getWorldContainer(),config.playerdataWorld()),"playerdata");
        this.userCacheFile=new File(Bukkit.getWorldContainer(),"usercache.json");
        reconcileTask=Scheduler.runTaskTimer(plugin,()->requestScan(false),config.reconcileTicks(),config.reconcileTicks());
        if(config.initialIndex())requestScan(false);
    }

    public void requestScan(boolean force){if(!config.playerdataEnabled()||!scanning.compareAndSet(false,true))return;worker.execute(()->scan(force));}
    public void request(UUID uuid){worker.execute(()->index(file(uuid),true));}
    public void indexOnline(Player player){
        knownNames.put(player.getUniqueId(),player.getName());
        var inv=liveAggregator.aggregate(player.getInventory().getContents());var end=liveAggregator.aggregate(player.getEnderChest().getContents());
        db.replacePlayer(player.getUniqueId(),player.getName(),System.currentTimeMillis(),"online",inv,end);
    }
    private void scan(boolean force){
        try{
            loadUserCache();cleanupBackups();
            if(mtimes.isEmpty()&&!force)mtimes.putAll(db.playerMtimes().join());
            File[] files=directory().listFiles((dir,name)->name.endsWith(".dat"));if(files==null)return;
            total=files.length;scanned.set(0);failed.set(0);
            for(File file:files){if(Thread.currentThread().isInterrupted())break;index(file,force);scanned.incrementAndGet();}
            lastScan=System.currentTimeMillis();
        }finally{scanning.set(false);}
    }
    private void loadUserCache(){
        if(!userCacheFile.isFile())return;
        try(var reader=Files.newBufferedReader(userCacheFile.toPath())){var root=com.google.gson.JsonParser.parseReader(reader);if(!root.isJsonArray())return;for(var element:root.getAsJsonArray())if(element.isJsonObject()){var o=element.getAsJsonObject();if(!o.has("uuid")||!o.has("name"))continue;try{knownNames.put(UUID.fromString(o.get("uuid").getAsString()),o.get("name").getAsString());}catch(IllegalArgumentException ignored){}}}catch(Exception e){LOGGER.warn("[IllegalStack] usercache.json nao pode ser lido: {}",e.getMessage());}
    }
    private void cleanupBackups(){
        File root=new File(plugin.getDataFolder(),"audit-backups/automatic");long cutoff=System.currentTimeMillis()-config.backupRetentionDays()*86_400_000L;if(!root.isDirectory())return;
        try(var paths=Files.walk(root.toPath())){paths.filter(Files::isRegularFile).filter(p->{try{return Files.getLastModifiedTime(p).toMillis()<cutoff;}catch(IOException e){return false;}}).forEach(p->{try{Files.deleteIfExists(p);}catch(IOException ignored){}});}catch(IOException e){LOGGER.warn("[IllegalStack] Falha ao limpar backups expirados: {}",e.getMessage());}
    }
    private void index(File file,boolean force){
        try{
            UUID id=UUID.fromString(file.getName().substring(0,file.getName().length()-4));long m=file.lastModified();
            if(!force&&mtimes.getOrDefault(id,-1L)==m)return;
            PlayerDataReader.Snapshot snapshot=readRetry(file);
            db.replacePlayer(id,knownNames.get(id),snapshot.mtime(),snapshot.hash(),snapshot.inventoryIndex(),snapshot.enderIndex()).join();mtimes.put(id,m);
        }catch(Exception e){failed.incrementAndGet();LOGGER.warn("[IllegalStack] Playerdata {} foi ignorado nesta rodada: {}",file.getName(),e.getMessage());}
    }
    private PlayerDataReader.Snapshot readRetry(File file)throws IOException{
        try{return reader.read(file);}catch(IOException first){try{Thread.sleep(config.retryDelayMs());}catch(InterruptedException e){Thread.currentThread().interrupt();throw first;}return reader.read(file);}
    }
    public PlayerDataReader.Snapshot read(UUID uuid)throws IOException{return reader.read(file(uuid));}
    public java.util.concurrent.CompletableFuture<PlayerDataReader.Snapshot> readAsync(UUID uuid){return java.util.concurrent.CompletableFuture.supplyAsync(()->{try{return read(uuid);}catch(IOException e){throw new java.util.concurrent.CompletionException(e);}},worker);}
    public File file(UUID uuid){return new File(directory(),uuid+".dat");}
    private File directory(){return playerdataDirectory;}
    public java.util.concurrent.CompletableFuture<File> backup(UUID uuid,boolean manual){return java.util.concurrent.CompletableFuture.supplyAsync(()->{
        File source=file(uuid);if(!source.isFile())throw new IllegalArgumentException("Playerdata nao encontrado");
        File folder=new File(plugin.getDataFolder(),"audit-backups/"+(manual?"manual":"automatic")+"/"+uuid);if(!folder.exists()&&!folder.mkdirs())throw new IllegalStateException("Falha ao criar pasta de backup");
        File target=new File(folder,Instant.now().toEpochMilli()+".dat");try{Files.copy(source.toPath(),target.toPath(),StandardCopyOption.COPY_ATTRIBUTES);return target;}catch(IOException e){throw new java.util.concurrent.CompletionException(e);}
    },worker);}
    public Progress progress(){return new Progress(scanning.get(),scanned.get(),total,failed.get(),lastScan);}
    @Override public void close(){reconcileTask.cancel();worker.shutdownNow();}
    public record Progress(boolean running,long scanned,long total,long failed,long lastScan){}
}
