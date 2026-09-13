package main.java.me.dniym.audit.database;

import main.java.me.dniym.audit.container.SnapshotCodec;
import main.java.me.dniym.audit.model.ContainerRef;
import main.java.me.dniym.audit.model.ContainerSnapshot;
import main.java.me.dniym.audit.model.ItemAggregate;
import main.java.me.dniym.audit.model.SearchResult;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.File;
import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.Map;
import java.util.HashMap;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

public final class AuditDatabase implements AutoCloseable {
    private static final Logger LOGGER = LogManager.getLogger("IllegalStack/Audit");
    private final ThreadPoolExecutor executor;
    private final Connection connection;
    private final AtomicLong dropped = new AtomicLong();
    private final AtomicLong suppressedFailures = new AtomicLong();
    private final AtomicLong lastFailureLog = new AtomicLong();
    private volatile boolean healthy = true;

    public AuditDatabase(File file, int capacity) throws SQLException {
        connection = DriverManager.getConnection("jdbc:sqlite:" + file.getAbsolutePath());
        initialize();
        executor = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(capacity), runnable -> {
            Thread t = new Thread(runnable, "IllegalStack-Audit-DB"); t.setDaemon(true); return t;
        }, (task, ignored) -> {
            dropped.incrementAndGet();
            throw new RejectedExecutionException("fila do banco de auditoria cheia");
        });
    }

    private void initialize() throws SQLException {
        try (Statement s = connection.createStatement()) {
            s.execute("PRAGMA journal_mode=WAL"); s.execute("PRAGMA synchronous=NORMAL");
            s.execute("PRAGMA busy_timeout=5000"); s.execute("PRAGMA wal_autocheckpoint=1000");
            s.execute("CREATE TABLE IF NOT EXISTS audit_meta (key TEXT PRIMARY KEY, value TEXT NOT NULL)");
            s.execute("INSERT INTO audit_meta VALUES ('schema_version','1') ON CONFLICT(key) DO NOTHING");
            s.execute("""
                    CREATE TABLE IF NOT EXISTS containers (
                      uuid TEXT PRIMARY KEY, location_key TEXT UNIQUE NOT NULL, world TEXT, x INTEGER, y INTEGER, z INTEGER,
                      entity_uuid TEXT, type TEXT, size INTEGER, status TEXT NOT NULL, created_at INTEGER NOT NULL,
                      updated_at INTEGER NOT NULL, destroyed_at INTEGER, destroyed_by TEXT,
                      last_player_uuid TEXT, last_player_name TEXT, last_cause TEXT, content_hash TEXT)
                    """);
            s.execute("CREATE INDEX IF NOT EXISTS idx_containers_status_updated ON containers(status,updated_at)");
            s.execute("CREATE INDEX IF NOT EXISTS idx_containers_location ON containers(world,x,y,z)");
            s.execute("CREATE TABLE IF NOT EXISTS container_state (container_uuid TEXT PRIMARY KEY, inventory_blob BLOB NOT NULL)");
            s.execute("""
                    CREATE TABLE IF NOT EXISTS container_item_index (
                      container_uuid TEXT NOT NULL, item_key TEXT NOT NULL, direct_amount INTEGER NOT NULL,
                      nested_amount INTEGER NOT NULL, PRIMARY KEY(container_uuid,item_key))
                    """);
            s.execute("CREATE INDEX IF NOT EXISTS idx_container_items_key ON container_item_index(item_key,direct_amount,nested_amount)");
            s.execute("""
                    CREATE TABLE IF NOT EXISTS container_players (
                      container_uuid TEXT NOT NULL, player_uuid TEXT NOT NULL, player_name TEXT,
                      first_seen INTEGER NOT NULL, last_seen INTEGER NOT NULL, interactions INTEGER NOT NULL,
                      PRIMARY KEY(container_uuid,player_uuid))
                    """);
            s.execute("CREATE INDEX IF NOT EXISTS idx_container_players_last ON container_players(container_uuid,last_seen DESC)");
            s.execute("""
                    CREATE TABLE IF NOT EXISTS player_files (
                      player_uuid TEXT PRIMARY KEY, player_name TEXT, file_mtime INTEGER, content_hash TEXT, updated_at INTEGER)
                    """);
            s.execute("""
                    CREATE TABLE IF NOT EXISTS player_item_index (
                      player_uuid TEXT NOT NULL, source TEXT NOT NULL, item_key TEXT NOT NULL,
                      direct_amount INTEGER NOT NULL, nested_amount INTEGER NOT NULL,
                      PRIMARY KEY(player_uuid,source,item_key))
                    """);
            s.execute("CREATE INDEX IF NOT EXISTS idx_player_items_key ON player_item_index(item_key,source,direct_amount,nested_amount)");
            s.execute("""
                    CREATE TABLE IF NOT EXISTS serial_index (
                      serial TEXT NOT NULL, target_type TEXT NOT NULL, target_uuid TEXT NOT NULL, source TEXT NOT NULL,
                      item_key TEXT NOT NULL, updated_at INTEGER NOT NULL,
                      PRIMARY KEY(serial,target_type,target_uuid,source,item_key))
                    """);
            s.execute("CREATE INDEX IF NOT EXISTS idx_serial_value ON serial_index(serial)");
            s.execute("""
                    CREATE TABLE IF NOT EXISTS integrity_flags (
                      id INTEGER PRIMARY KEY AUTOINCREMENT, target_type TEXT, target_uuid TEXT, reason TEXT,
                      score INTEGER, detail TEXT, first_seen INTEGER, last_seen INTEGER, occurrences INTEGER,
                      UNIQUE(target_type,target_uuid,reason,detail))
                    """);
            s.execute("""
                    CREATE TABLE IF NOT EXISTS admin_actions (
                      id INTEGER PRIMARY KEY AUTOINCREMENT, staff_uuid TEXT, staff_name TEXT, target_type TEXT,
                      target_uuid TEXT, action TEXT, item_summary TEXT, timestamp INTEGER)
                    """);
        }
    }

    public boolean submitContainer(ContainerSnapshot snapshot, boolean interaction) {
        return execute(() -> saveContainer(snapshot, interaction));
    }

    private void saveContainer(ContainerSnapshot snapshot, boolean interaction) {
        try {
            connection.setAutoCommit(false);
            ContainerRef r = snapshot.ref();
            String oldHash = null;
            try (PreparedStatement q = connection.prepareStatement("SELECT content_hash FROM containers WHERE uuid=?")) {
                q.setString(1, r.uuid().toString()); try (ResultSet rows = q.executeQuery()) { if (rows.next()) oldHash = rows.getString(1); }
            }
            try (PreparedStatement p = connection.prepareStatement("""
                    INSERT INTO containers(uuid,location_key,world,x,y,z,entity_uuid,type,size,status,created_at,updated_at,
                      destroyed_at,destroyed_by,last_player_uuid,last_player_name,last_cause,content_hash)
                    VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                    ON CONFLICT(uuid) DO UPDATE SET location_key=excluded.location_key,world=excluded.world,x=excluded.x,
                      y=excluded.y,z=excluded.z,entity_uuid=excluded.entity_uuid,type=excluded.type,size=excluded.size,
                      status=excluded.status,updated_at=excluded.updated_at,destroyed_at=excluded.destroyed_at,
                      destroyed_by=excluded.destroyed_by,last_player_uuid=COALESCE(excluded.last_player_uuid,containers.last_player_uuid),
                      last_player_name=COALESCE(excluded.last_player_name,containers.last_player_name),
                      last_cause=excluded.last_cause,content_hash=excluded.content_hash
                    """)) {
                int i=1; p.setString(i++,r.uuid().toString()); p.setString(i++,r.locationKey()); p.setString(i++,r.world());
                p.setInt(i++,r.x()); p.setInt(i++,r.y()); p.setInt(i++,r.z()); set(p,i++,r.entityUuid()); p.setString(i++,r.type());
                p.setInt(i++,r.size()); p.setString(i++,snapshot.destroyed()?"DESTROYED":"ACTIVE"); p.setLong(i++,snapshot.capturedAt());
                p.setLong(i++,snapshot.capturedAt()); if(snapshot.destroyed())p.setLong(i++,snapshot.capturedAt());else p.setNull(i++,Types.BIGINT);
                set(p,i++,snapshot.destroyedBy()); set(p,i++,snapshot.lastPlayerUuid()); p.setString(i++,snapshot.lastPlayerName());
                p.setString(i++,snapshot.cause().name()); p.setString(i,snapshot.contentHash()); p.executeUpdate();
            }
            if (!snapshot.contentHash().equals(oldHash)) {
                try (PreparedStatement p=connection.prepareStatement("INSERT INTO container_state VALUES(?,?) ON CONFLICT(container_uuid) DO UPDATE SET inventory_blob=excluded.inventory_blob")) {
                    p.setString(1,r.uuid().toString()); p.setBytes(2, SnapshotCodec.compress(snapshot.inventoryBytes())); p.executeUpdate();
                }
                replaceItems("container_item_index", "container_uuid", r.uuid().toString(), snapshot.items());
                try(PreparedStatement p=connection.prepareStatement("DELETE FROM serial_index WHERE target_type='CONTAINER' AND target_uuid=?")){p.setString(1,r.uuid().toString());p.executeUpdate();}
                if(!snapshot.destroyed())replaceSerials("CONTAINER", r.uuid().toString(), "CONTAINER", snapshot.items(), snapshot.capturedAt());
            } else if(snapshot.destroyed()) {
                try(PreparedStatement p=connection.prepareStatement("DELETE FROM serial_index WHERE target_type='CONTAINER' AND target_uuid=?")){p.setString(1,r.uuid().toString());p.executeUpdate();}
            }
            if (interaction && snapshot.lastPlayerUuid()!=null) {
                try (PreparedStatement p=connection.prepareStatement("""
                        INSERT INTO container_players VALUES(?,?,?,?,?,?)
                        ON CONFLICT(container_uuid,player_uuid) DO UPDATE SET player_name=excluded.player_name,
                          last_seen=excluded.last_seen,interactions=container_players.interactions+excluded.interactions
                        """)) {
                    p.setString(1,r.uuid().toString());p.setString(2,snapshot.lastPlayerUuid().toString());
                    p.setString(3,snapshot.lastPlayerName());p.setLong(4,snapshot.capturedAt());p.setLong(5,snapshot.capturedAt());p.setInt(6,snapshot.interactionCount());p.executeUpdate();
                }
            }
            connection.commit();
        } catch (Exception e) { rollback(e); }
        finally { autoCommit(); }
    }

    public CompletableFuture<Boolean> replacePlayer(UUID player, String name, long mtime, String hash,
                                                     List<ItemAggregate> inventory, List<ItemAggregate> ender) {
        return supply(() -> {
            try {
                connection.setAutoCommit(false);
                try (PreparedStatement p=connection.prepareStatement("INSERT INTO player_files VALUES(?,?,?,?,?) ON CONFLICT(player_uuid) DO UPDATE SET player_name=COALESCE(excluded.player_name,player_files.player_name),file_mtime=excluded.file_mtime,content_hash=excluded.content_hash,updated_at=excluded.updated_at")) {
                    p.setString(1,player.toString());p.setString(2,name);p.setLong(3,mtime);p.setString(4,hash);p.setLong(5,System.currentTimeMillis());p.executeUpdate();
                }
                try (PreparedStatement p=connection.prepareStatement("DELETE FROM player_item_index WHERE player_uuid=?")) {p.setString(1,player.toString());p.executeUpdate();}
                insertPlayerItems(player,"INV",inventory); insertPlayerItems(player,"ENDER",ender);
                try (PreparedStatement p=connection.prepareStatement("DELETE FROM serial_index WHERE target_type='PLAYER' AND target_uuid=?")){p.setString(1,player.toString());p.executeUpdate();}
                replaceSerials("PLAYER",player.toString(),"INV",inventory,System.currentTimeMillis());
                replaceSerials("PLAYER",player.toString(),"ENDER",ender,System.currentTimeMillis());
                connection.commit(); return true;
            } catch(Exception e){rollback(e);return false;} finally {autoCommit();}
        });
    }

    private void insertPlayerItems(UUID player,String source,List<ItemAggregate> items)throws SQLException{
        try(PreparedStatement p=connection.prepareStatement("INSERT INTO player_item_index VALUES(?,?,?,?,?)")){
            for(ItemAggregate a:items){p.setString(1,player.toString());p.setString(2,source);p.setString(3,a.itemKey());p.setLong(4,a.direct());p.setLong(5,a.nested());p.addBatch();}p.executeBatch();
        }
    }
    private void replaceItems(String table,String idColumn,String id,List<ItemAggregate> items)throws SQLException{
        try(PreparedStatement p=connection.prepareStatement("DELETE FROM "+table+" WHERE "+idColumn+"=?")){p.setString(1,id);p.executeUpdate();}
        try(PreparedStatement p=connection.prepareStatement("INSERT INTO "+table+" VALUES(?,?,?,?)")){
            for(ItemAggregate a:items){p.setString(1,id);p.setString(2,a.itemKey());p.setLong(3,a.direct());p.setLong(4,a.nested());p.addBatch();}p.executeBatch();
        }
    }
    private void replaceSerials(String targetType,String target,String source,List<ItemAggregate> items,long now)throws SQLException{
        try(PreparedStatement p=connection.prepareStatement("INSERT OR REPLACE INTO serial_index VALUES(?,?,?,?,?,?)")){
            for(ItemAggregate a:items){
                for(String serial:a.serials()){bindSerial(p,serial,targetType,target,source,a.itemKey(),now);p.addBatch();}
                for(String custom:a.customIds()){bindSerial(p,"custom:"+custom,targetType,target,source,a.itemKey(),now);p.addBatch();}
            }p.executeBatch();
        }
    }
    private void bindSerial(PreparedStatement p,String serial,String targetType,String target,String source,String itemKey,long now)throws SQLException{p.setString(1,serial);p.setString(2,targetType);p.setString(3,target);p.setString(4,source);p.setString(5,itemKey);p.setLong(6,now);}

    public CompletableFuture<List<SearchResult>> search(String scope,String itemKey,int limit,int offset){
        return supply(() -> {
            List<SearchResult> out=new ArrayList<>();
            try {
                if(!"bau".equals(scope)&&!"container".equals(scope)) searchPlayers(out,scope,itemKey,limit,offset);
                if("all".equals(scope)||"bau".equals(scope)||"container".equals(scope)) searchContainers(out,itemKey,limit,offset);
                out.sort(java.util.Comparator.comparingLong(SearchResult::total).reversed());
                return out.size()>limit?new ArrayList<>(out.subList(0,limit)):out;
            }catch(SQLException e){fail(e);return List.of();}
        });
    }
    public CompletableFuture<List<SearchResult>> searchSerial(String value,int limit,int offset){return supply(()->{List<SearchResult>out=new ArrayList<>();try(PreparedStatement p=connection.prepareStatement("SELECT serial,target_type,target_uuid,source,item_key FROM serial_index WHERE serial=? ORDER BY updated_at DESC LIMIT ? OFFSET ?")){p.setString(1,value);p.setInt(2,limit);p.setInt(3,offset);try(ResultSet r=p.executeQuery()){while(r.next())out.add(new SearchResult(r.getString(2),r.getString(3),r.getString(3),r.getString(4),r.getString(5),1,0,1,0,"serial="+r.getString(1)));}}catch(SQLException e){fail(e);}return out;});}

    public CompletableFuture<List<SearchResult>> suspicious(Map<String,main.java.me.dniym.audit.AuditConfig.Threshold> thresholds,int minimum,int limit){return supply(()->{
        Map<String,MutableSuspicious> values=new HashMap<>();
        try(Statement s=connection.createStatement();ResultSet r=s.executeQuery("SELECT 'PLAYER',player_uuid,source,item_key,direct_amount,nested_amount FROM player_item_index UNION ALL SELECT 'CONTAINER',container_uuid,'CONTAINER',item_key,direct_amount,nested_amount FROM container_item_index")){
            while(r.next()){
                var threshold=thresholds.get(stripNamespace(r.getString(4)));if(threshold==null)continue;long total=r.getLong(5)+r.getLong(6);if(total<threshold.warning())continue;
                String id=r.getString(1)+":"+r.getString(2);MutableSuspicious v=values.computeIfAbsent(id,k->new MutableSuspicious(rString(r,1),rString(r,2),rString(r,3)));
                int score=threshold.score()*(total>=threshold.critical()?2:1);v.score+=score;v.details.add(r.getString(4)+"="+total);v.total+=total;
            }
        }catch(SQLException e){fail(e);return List.of();}
        try(Statement s=connection.createStatement();ResultSet r=s.executeQuery("SELECT serial,target_type,target_uuid,source,item_key FROM serial_index WHERE serial NOT LIKE 'custom:%' AND serial IN (SELECT serial FROM serial_index GROUP BY serial HAVING COUNT(DISTINCT target_type||':'||target_uuid||':'||source)>1)")){
            while(r.next()){String id=r.getString(2)+":"+r.getString(3);MutableSuspicious v=values.computeIfAbsent(id,k->new MutableSuspicious(rString(r,2),rString(r,3),rString(r,4)));v.score+=100;v.details.add("DUPLICATE_SERIAL="+r.getString(1));}
        }catch(SQLException e){fail(e);}
        List<SearchResult> out=new ArrayList<>();values.values().stream().filter(v->v.score>=minimum).sorted(java.util.Comparator.comparingInt((MutableSuspicious v)->v.score).reversed()).limit(limit).forEach(v->out.add(new SearchResult(v.type,v.id,v.id,v.source,"suspicious",0,0,v.total,v.score,String.join(", ",v.details))));return out;
    });}
    private String stripNamespace(String key){int i=key.indexOf(':');return (i>=0?key.substring(i+1):key).toLowerCase(java.util.Locale.ROOT);}
    private String rString(ResultSet r,int column){try{return r.getString(column);}catch(SQLException e){return "";}}
    private void searchPlayers(List<SearchResult> out,String scope,String key,int limit,int offset)throws SQLException{
        String filter=switch(scope){case "playerdata_inv","inv"->" AND p.source='INV'";case "playerdata_end","end"->" AND p.source='ENDER'";default->"";};
        String sql="SELECT p.player_uuid,COALESCE(f.player_name,p.player_uuid),p.source,p.direct_amount,p.nested_amount FROM player_item_index p LEFT JOIN player_files f ON f.player_uuid=p.player_uuid WHERE p.item_key=?"+filter+" ORDER BY p.direct_amount+p.nested_amount DESC LIMIT ? OFFSET ?";
        try(PreparedStatement p=connection.prepareStatement(sql)){p.setString(1,key);p.setInt(2,limit);p.setInt(3,offset);try(ResultSet r=p.executeQuery()){while(r.next())out.add(new SearchResult("PLAYER",r.getString(1),r.getString(2),r.getString(3),key,r.getLong(4),r.getLong(5),r.getLong(4)+r.getLong(5),0,""));}}
    }
    private void searchContainers(List<SearchResult> out,String key,int limit,int offset)throws SQLException{
        try(PreparedStatement p=connection.prepareStatement("SELECT c.uuid,c.type,c.world,c.x,c.y,c.z,c.last_player_name,c.last_cause,i.direct_amount,i.nested_amount FROM container_item_index i JOIN containers c ON c.uuid=i.container_uuid WHERE i.item_key=? AND c.status='ACTIVE' ORDER BY i.direct_amount+i.nested_amount DESC LIMIT ? OFFSET ?")){
            p.setString(1,key);p.setInt(2,limit);p.setInt(3,offset);try(ResultSet r=p.executeQuery()){while(r.next())out.add(new SearchResult("CONTAINER",r.getString(1),r.getString(2),"CONTAINER",key,r.getLong(9),r.getLong(10),r.getLong(9)+r.getLong(10),0,r.getString(3)+":"+r.getInt(4)+","+r.getInt(5)+","+r.getInt(6)+" last="+r.getString(7)+" cause="+r.getString(8)));}}
    }

    public CompletableFuture<StoredContainer> loadContainer(UUID id){return supply(()->{
        try(PreparedStatement p=connection.prepareStatement("SELECT c.*,s.inventory_blob FROM containers c LEFT JOIN container_state s ON s.container_uuid=c.uuid WHERE c.uuid=?")){p.setString(1,id.toString());try(ResultSet r=p.executeQuery()){if(!r.next())return null;return new StoredContainer(new ContainerRef(id,r.getString("location_key"),r.getString("world"),r.getInt("x"),r.getInt("y"),r.getInt("z"),uuid(r.getString("entity_uuid")),r.getString("type"),r.getInt("size")),r.getString("status"),r.getLong("created_at"),r.getLong("updated_at"),r.getString("last_player_name"),r.getString("last_cause"),r.getString("content_hash"),r.getBytes("inventory_blob"));}}catch(Exception e){fail(e);return null;}});}

    public CompletableFuture<List<PlayerInteraction>> interactions(UUID id){return supply(()->{List<PlayerInteraction>out=new ArrayList<>();try(PreparedStatement p=connection.prepareStatement("SELECT player_uuid,player_name,first_seen,last_seen,interactions FROM container_players WHERE container_uuid=? ORDER BY last_seen DESC LIMIT 45")){p.setString(1,id.toString());try(ResultSet r=p.executeQuery()){while(r.next())out.add(new PlayerInteraction(UUID.fromString(r.getString(1)),r.getString(2),r.getLong(3),r.getLong(4),r.getLong(5)));}}catch(Exception e){fail(e);}return out;});}

    public CompletableFuture<Status> status(){return supply(()->{try(Statement s=connection.createStatement()){return new Status(healthy,scalar(s,"SELECT COUNT(*) FROM player_files"),scalar(s,"SELECT COUNT(*) FROM containers WHERE status='ACTIVE'"),scalar(s,"SELECT COUNT(*) FROM containers WHERE status='DESTROYED'"),scalar(s,"SELECT COUNT(*) FROM player_item_index")+scalar(s,"SELECT COUNT(*) FROM container_item_index"),executor.getQueue().size(),dropped.get());}catch(SQLException e){fail(e);return new Status(false,0,0,0,0,executor.getQueue().size(),dropped.get());}});}
    public boolean logAdmin(UUID staff,String name,String targetType,String target,String summary){return execute(()->{try(PreparedStatement p=connection.prepareStatement("INSERT INTO admin_actions(staff_uuid,staff_name,target_type,target_uuid,action,item_summary,timestamp) VALUES(?,?,?,?,?,?,?)")){set(p,1,staff);p.setString(2,name);p.setString(3,targetType);p.setString(4,target);p.setString(5,"TRANSFER_TO_ADMIN");p.setString(6,summary);p.setLong(7,System.currentTimeMillis());p.executeUpdate();}catch(SQLException e){fail(e);}});}
    public CompletableFuture<Map<UUID,Long>> playerMtimes(){return supply(()->{Map<UUID,Long>out=new HashMap<>();try(Statement s=connection.createStatement();ResultSet r=s.executeQuery("SELECT player_uuid,file_mtime FROM player_files")){while(r.next())try{out.put(UUID.fromString(r.getString(1)),r.getLong(2));}catch(IllegalArgumentException ignored){}}catch(SQLException e){fail(e);}return out;});}
    public boolean cleanup(int destroyedDays){return execute(()->{
        long cutoff=System.currentTimeMillis()-destroyedDays*86_400_000L;
        try{connection.setAutoCommit(false);
            for(String table:new String[]{"container_state","container_item_index","container_players"})try(PreparedStatement p=connection.prepareStatement("DELETE FROM "+table+" WHERE container_uuid IN (SELECT uuid FROM containers WHERE status='DESTROYED' AND destroyed_at<?)")){p.setLong(1,cutoff);p.executeUpdate();}
            try(PreparedStatement p=connection.prepareStatement("DELETE FROM serial_index WHERE target_type='CONTAINER' AND target_uuid IN (SELECT uuid FROM containers WHERE status='DESTROYED' AND destroyed_at<?)")){p.setLong(1,cutoff);p.executeUpdate();}
            try(PreparedStatement p=connection.prepareStatement("DELETE FROM containers WHERE status='DESTROYED' AND destroyed_at<?")){p.setLong(1,cutoff);p.executeUpdate();}
            connection.commit();
        }catch(SQLException e){rollback(e);}finally{autoCommit();}
    });}

    private long scalar(Statement s,String sql)throws SQLException{try(ResultSet r=s.executeQuery(sql)){return r.next()?r.getLong(1):0;}}
    private boolean execute(Runnable task){if(executor.isShutdown())return false;try{executor.execute(task);return true;}catch(RejectedExecutionException e){dropped.incrementAndGet();return false;}}
    private <T> CompletableFuture<T> supply(Callable<T> task){CompletableFuture<T> f=new CompletableFuture<>();if(!execute(()->{try{f.complete(task.call());}catch(Exception e){f.completeExceptionally(e);}}))f.completeExceptionally(new RejectedExecutionException());return f;}
    private void rollback(Exception e){try{connection.rollback();}catch(SQLException ignored){}fail(e);}
    private void autoCommit(){try{connection.setAutoCommit(true);}catch(SQLException e){fail(e);}}
    private void fail(Exception e){
        healthy=false;
        long now=System.currentTimeMillis();long previous=lastFailureLog.get();
        boolean shouldLog=previous==0?lastFailureLog.compareAndSet(0,now):now-previous>=60_000L&&lastFailureLog.compareAndSet(previous,now);
        if(shouldLog){
            long suppressed=suppressedFailures.getAndSet(0);
            LOGGER.error("[IllegalStack] Falha no banco de auditoria; modulo segue fail-open. Falhas suprimidas desde o ultimo aviso: {}",suppressed,e);
        }else suppressedFailures.incrementAndGet();
    }
    private void set(PreparedStatement p,int i,UUID u)throws SQLException{if(u==null)p.setNull(i,Types.VARCHAR);else p.setString(i,u.toString());}
    private UUID uuid(String s){try{return s==null?null:UUID.fromString(s);}catch(IllegalArgumentException e){return null;}}
    @Override public void close(){executor.shutdown();try{executor.awaitTermination(5,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();}try{connection.close();}catch(SQLException e){LOGGER.warn("[IllegalStack] Falha ao fechar item-audit.db",e);}}

    public record StoredContainer(ContainerRef ref,String status,long createdAt,long updatedAt,String lastPlayer,String lastCause,String hash,byte[] compressedInventory){}
    public record PlayerInteraction(UUID uuid,String name,long firstSeen,long lastSeen,long interactions){}
    public record Status(boolean healthy,long players,long activeContainers,long destroyedContainers,long indexedItems,int queue,long dropped){}
    private static final class MutableSuspicious{final String type,id,source;int score;long total;final List<String>details=new ArrayList<>();MutableSuspicious(String type,String id,String source){this.type=type;this.id=id;this.source=source;}}
}
