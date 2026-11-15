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
    private final AtomicLong pendingBytes=new AtomicLong();
    private final AtomicLong suppressedFailures = new AtomicLong();
    private final AtomicLong lastFailureLog = new AtomicLong();
    private volatile boolean healthy = true;
    private final Connection readerConnection;
    private final ThreadPoolExecutor readers;

    public AuditDatabase(File file, int capacity) throws SQLException {
        connection = DriverManager.getConnection("jdbc:sqlite:" + file.getAbsolutePath());
        initialize();
        migrate();
        readerConnection=DriverManager.getConnection("jdbc:sqlite:"+file.getAbsolutePath());
        try(var statement=readerConnection.createStatement()){statement.execute("PRAGMA query_only=ON");statement.execute("PRAGMA busy_timeout=5000");}
        readers=new ThreadPoolExecutor(1,1,0,TimeUnit.MILLISECONDS,new ArrayBlockingQueue<>(64),r->{Thread t=new Thread(r,"IllegalStack-Audit-Queries");t.setDaemon(true);return t;},new ThreadPoolExecutor.AbortPolicy());
        executor = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(capacity), runnable -> {
            Thread t = new Thread(runnable, "IllegalStack-Audit-DB"); t.setDaemon(true); return t;
        }, (task, ignored) -> {
            throw new RejectedExecutionException("fila do banco de auditoria cheia");
        });
    }

    private void initialize() throws SQLException {
        try (Statement s = connection.createStatement()) {
            s.execute("PRAGMA journal_mode=WAL"); s.execute("PRAGMA synchronous=NORMAL");
            s.execute("PRAGMA busy_timeout=5000"); s.execute("PRAGMA wal_autocheckpoint=1000");
            s.execute("CREATE TABLE IF NOT EXISTS audit_read_failures (target_type TEXT NOT NULL,target_uuid TEXT NOT NULL,detail TEXT,observed_at INTEGER NOT NULL,PRIMARY KEY(target_type,target_uuid))");
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
            s.execute("CREATE TABLE IF NOT EXISTS transfer_evidence(action_id INTEGER PRIMARY KEY,source_blob BLOB NOT NULL,destination_blob BLOB NOT NULL,created_at INTEGER NOT NULL)");
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


    private void migrate()throws SQLException {
        try(var statement=connection.createStatement()){
            long version;
            try(var rows=statement.executeQuery("SELECT value FROM audit_meta WHERE key='schema_version'")){version=rows.next()?rows.getLong(1):1;}
            if(version>2)throw new SQLException("Schema de auditoria mais recente que o plugin");
            if(version==2)return;
            connection.setAutoCommit(false);
            try {
                statement.execute("CREATE TABLE containers_v2 AS SELECT * FROM containers WHERE 0");
                statement.execute("INSERT INTO containers_v2 SELECT * FROM containers");
                statement.execute("DROP TABLE containers");
                statement.execute("ALTER TABLE containers_v2 RENAME TO containers");
                statement.execute("CREATE UNIQUE INDEX idx_containers_uuid ON containers(uuid)");
                statement.execute("CREATE UNIQUE INDEX idx_container_active_location ON containers(location_key) WHERE status='ACTIVE'");
                statement.execute("CREATE INDEX idx_containers_status_updated ON containers(status,updated_at)");
                statement.execute("CREATE INDEX idx_containers_location ON containers(world,x,y,z)");
                statement.execute("ALTER TABLE serial_index ADD COLUMN occurrences INTEGER NOT NULL DEFAULT 1");
                statement.execute("UPDATE serial_index SET serial=substr(serial,8) WHERE serial LIKE 'custom:ZI-%'");
                statement.execute("UPDATE containers SET status='INVALIDATED',destroyed_at=strftime('%s','now')*1000 WHERE type='PLAYER'");
                statement.execute("DELETE FROM serial_index WHERE target_type='CONTAINER' AND target_uuid IN (SELECT uuid FROM containers WHERE status!='ACTIVE')");
                statement.execute("UPDATE audit_meta SET value='2' WHERE key='schema_version'");
                connection.commit();
            } catch(SQLException ex){connection.rollback();throw ex;}
            finally {connection.setAutoCommit(true);}
        }
    }

    private ContainerRef generation(ContainerRef input)throws SQLException {
        try(var query=connection.prepareStatement("SELECT uuid FROM containers WHERE location_key=? AND status='ACTIVE'")){
            query.setString(1,input.locationKey());
            try(var rows=query.executeQuery()){if(rows.next())return withId(input,UUID.fromString(rows.getString(1)));}
        }
        UUID id=input.uuid();
        try(var query=connection.prepareStatement("SELECT 1 FROM containers WHERE uuid=?")){
            query.setString(1,id.toString());try(var rows=query.executeQuery()){if(rows.next())id=UUID.randomUUID();}
        }
        return withId(input,id);
    }
    private ContainerRef withId(ContainerRef r,UUID id){return new ContainerRef(id,r.locationKey(),r.world(),r.x(),r.y(),r.z(),r.entityUuid(),r.type(),r.size());}

    private void retireOverlapping(ContainerRef incoming,long timestamp)throws SQLException {
        if(incoming.entityUuid()!=null)return;
        String[] keys=incoming.locationKey().startsWith("double:")?incoming.locationKey().substring(7).split("\\+"):new String[]{incoming.locationKey()};
        try(var query=connection.prepareStatement("SELECT uuid,location_key FROM containers WHERE status='ACTIVE' AND world=? AND x BETWEEN ? AND ? AND z BETWEEN ? AND ? AND y=?")){
            query.setString(1,incoming.world());query.setInt(2,incoming.x()-1);query.setInt(3,incoming.x()+1);
            query.setInt(4,incoming.z()-1);query.setInt(5,incoming.z()+1);query.setInt(6,incoming.y());
            java.util.List<String> retired=new ArrayList<>();
            try(var rows=query.executeQuery()){while(rows.next()){
                String key=rows.getString(2);if(key.equals(incoming.locationKey()))continue;
                var oldKeys=java.util.Set.of(key.startsWith("double:")?key.substring(7).split("\\+"):new String[]{key});
                if(java.util.Arrays.stream(keys).anyMatch(oldKeys::contains))retired.add(rows.getString(1));
            }}
            for(String id:retired){
                try(var update=connection.prepareStatement("UPDATE containers SET status='REPLACED',destroyed_at=? WHERE uuid=?")){update.setLong(1,timestamp);update.setString(2,id);update.executeUpdate();}
                try(var delete=connection.prepareStatement("DELETE FROM serial_index WHERE target_type='CONTAINER' AND target_uuid=?")){delete.setString(1,id);delete.executeUpdate();}
            }
        }
    }

    public boolean submitRaw(ContainerRef ref,byte[] bytes,UUID player,String name,main.java.me.dniym.audit.model.AuditCause cause,
                             long now,boolean destroyed,java.util.Map<UUID,InteractionDelta> interactions) {
        long queued=pendingBytes.addAndGet(bytes.length);
        if(queued>64L*1024*1024){pendingBytes.addAndGet(-bytes.length);dropped.incrementAndGet();return false;}
        boolean accepted=execute(()->{
            try{
                var items=new main.java.me.dniym.audit.playerdata.PlayerDataReader().aggregateSerialized(bytes);
                saveContainer(new ContainerSnapshot(ref,bytes,SnapshotCodec.hash(bytes),items,player,name,cause,now,destroyed,destroyed?player:null,0),false,interactions);
            }catch(Exception e){storeReadFailure("CONTAINER",ref.uuid(),e.getClass().getSimpleName());}finally{pendingBytes.addAndGet(-bytes.length);}
        });
        if(!accepted)pendingBytes.addAndGet(-bytes.length);
        return accepted;
    }
    public void recordReadFailure(String type,UUID id,String detail){execute(()->storeReadFailure(type,id,detail));}
    private void storeReadFailure(String type,UUID id,String detail){
        try(var p=connection.prepareStatement("INSERT INTO audit_read_failures VALUES(?,?,?,?) ON CONFLICT(target_type,target_uuid) DO UPDATE SET detail=excluded.detail,observed_at=excluded.observed_at")){
            p.setString(1,type);p.setString(2,id.toString());p.setString(3,detail);p.setLong(4,System.currentTimeMillis());p.executeUpdate();
        }catch(SQLException e){fail(e);}
    }
    private void clearReadFailure(String type,UUID id)throws SQLException{
        try(var p=connection.prepareStatement("DELETE FROM audit_read_failures WHERE target_type=? AND target_uuid=?")){p.setString(1,type);p.setString(2,id.toString());p.executeUpdate();}
    }
    public record InteractionDelta(String name,long first,long last,int count){}
    private void saveInteractions(String id,Map<UUID,InteractionDelta> interactions)throws SQLException {
        if(interactions.isEmpty())return;
        try(var p=connection.prepareStatement("INSERT INTO container_players VALUES(?,?,?,?,?,?) ON CONFLICT(container_uuid,player_uuid) DO UPDATE SET player_name=excluded.player_name,first_seen=MIN(container_players.first_seen,excluded.first_seen),last_seen=MAX(container_players.last_seen,excluded.last_seen),interactions=container_players.interactions+excluded.interactions")){
            for(var entry:interactions.entrySet()){var v=entry.getValue();p.setString(1,id);p.setString(2,entry.getKey().toString());p.setString(3,v.name());p.setLong(4,v.first());p.setLong(5,v.last());p.setInt(6,v.count());p.addBatch();}p.executeBatch();
        }
    }

    public boolean submitContainer(ContainerSnapshot snapshot, boolean interaction) {
        return execute(() -> saveContainer(snapshot, interaction));
    }

    private boolean saveContainer(ContainerSnapshot snapshot, boolean interaction) {return saveContainer(snapshot,interaction,Map.of());}
    private boolean saveContainer(ContainerSnapshot snapshot, boolean interaction,Map<UUID,InteractionDelta> interactions) {
        try {
            connection.setAutoCommit(false);
            ContainerRef r = generation(snapshot.ref());
            retireOverlapping(r,snapshot.capturedAt());
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
                      last_cause=CASE WHEN excluded.last_cause=\'INSPECTION\' THEN containers.last_cause ELSE excluded.last_cause END,content_hash=excluded.content_hash
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
            clearReadFailure("CONTAINER",snapshot.ref().uuid());
            saveInteractions(r.uuid().toString(),interactions);
            connection.commit();healthy=true;return true;
        } catch (Exception e) { rollback(e);return false; }
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
                clearReadFailure("PLAYER",player);
                connection.commit(); healthy=true;return true;
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
        try(PreparedStatement p=connection.prepareStatement("INSERT OR REPLACE INTO serial_index VALUES(?,?,?,?,?,?,?)")){
            for(ItemAggregate a:items){
                for(var serial:a.serials().entrySet()){bindSerial(p,serial.getKey(),targetType,target,source,a.itemKey(),now);p.setInt(7,serial.getValue());p.addBatch();}
                for(var custom:a.customIds().entrySet()){bindSerial(p,"custom:"+custom.getKey(),targetType,target,source,a.itemKey(),now);p.setInt(7,custom.getValue());p.addBatch();}
            }p.executeBatch();
        }
    }
    private void bindSerial(PreparedStatement p,String serial,String targetType,String target,String source,String itemKey,long now)throws SQLException{p.setString(1,serial);p.setString(2,targetType);p.setString(3,target);p.setString(4,source);p.setString(5,itemKey);p.setLong(6,now);}


    public CompletableFuture<List<SearchResult>> search(String scope,String itemKey,int limit,int offset){
        return query(()->{
            String players="SELECT 'PLAYER' target,p.player_uuid id,COALESCE(f.player_name,p.player_uuid) name,p.source source,p.item_key item,SUM(p.direct_amount) direct,SUM(p.nested_amount) nested,'' detail FROM player_item_index p LEFT JOIN player_files f ON f.player_uuid=p.player_uuid WHERE p.item_key=?";
            if(scope.equals("playerdata_inv")||scope.equals("inv"))players+=" AND source='INV'";
            if(scope.equals("playerdata_end")||scope.equals("end"))players+=" AND source='ENDER'";
            if(scope.equals("playerdata"))players=players.replace("p.source source","'INV+ENDER' source")+" GROUP BY p.player_uuid,p.item_key";
            else players+=" GROUP BY p.player_uuid,p.source,p.item_key";
            String containers="SELECT 'CONTAINER' target,c.uuid id,c.type name,'CONTAINER' source,i.item_key item,i.direct_amount direct,i.nested_amount nested,c.world||':'||c.x||','||c.y||','||c.z FROM container_item_index i JOIN containers c ON c.uuid=i.container_uuid WHERE c.status='ACTIVE' AND i.item_key=?";
            String union=scope.equals("all")?players+" UNION ALL "+containers:scope.equals("bau")||scope.equals("container")?containers:players;
            try(var p=readerConnection.prepareStatement("SELECT * FROM ("+union+") ORDER BY direct+nested DESC,id,source LIMIT ? OFFSET ?")){
                int n=1;p.setString(n++,itemKey);if(scope.equals("all"))p.setString(n++,itemKey);p.setInt(n++,limit);p.setInt(n,offset);
                List<SearchResult> out=new ArrayList<>();try(var r=p.executeQuery()){while(r.next())out.add(new SearchResult(r.getString(1),r.getString(2),r.getString(3),r.getString(4),r.getString(5),r.getLong(6),r.getLong(7),r.getLong(6)+r.getLong(7),0,r.getString(8)));}return out;
            }
        });
    }
    public CompletableFuture<List<SearchResult>> searchSerial(String value,int limit,int offset){
        return query(()->{
            List<SearchResult> out=new ArrayList<>();
            try(var p=readerConnection.prepareStatement("SELECT serial,target_type,target_uuid,source,item_key,occurrences FROM serial_index WHERE serial=? ORDER BY updated_at DESC,target_uuid,source LIMIT ? OFFSET ?")){
                p.setString(1,value);p.setInt(2,limit);p.setInt(3,offset);
                try(var r=p.executeQuery()){while(r.next())out.add(new SearchResult(r.getString(2),r.getString(3),r.getString(3),r.getString(4),r.getString(5),r.getLong(6),0,r.getLong(6),0,"serial="+r.getString(1)));}
            }return out;
        });
    }
    public CompletableFuture<List<SearchResult>> suspicious(Map<String,main.java.me.dniym.audit.AuditConfig.Threshold> thresholds,int minimum,int limit){
        return suspicious(thresholds,minimum,limit,"all");
    }
    public CompletableFuture<List<SearchResult>> suspicious(Map<String,main.java.me.dniym.audit.AuditConfig.Threshold> thresholds,int minimum,int limit,String scope){
        return query(()->{
            Map<String,MutableSuspicious> values=new HashMap<>();
            for(var threshold:thresholds.entrySet()){
                String key=threshold.getKey().contains(":")?threshold.getKey():"minecraft:"+threshold.getKey();
                String sql="SELECT 'PLAYER',player_uuid,'INV+ENDER',SUM(direct_amount+nested_amount) FROM player_item_index WHERE item_key=? GROUP BY player_uuid HAVING SUM(direct_amount+nested_amount)>=? UNION ALL SELECT 'CONTAINER',i.container_uuid,'CONTAINER',i.direct_amount+i.nested_amount FROM container_item_index i JOIN containers c ON c.uuid=i.container_uuid WHERE c.status='ACTIVE' AND i.item_key=? AND i.direct_amount+i.nested_amount>=?";
                try(var p=readerConnection.prepareStatement(sql)){
                    p.setString(1,key);p.setLong(2,threshold.getValue().warning());p.setString(3,key);p.setLong(4,threshold.getValue().warning());
                    try(var r=p.executeQuery()){while(r.next()){
                        String id=r.getString(1)+":"+r.getString(2);var v=values.computeIfAbsent(id,k->new MutableSuspicious(rString(r,1),rString(r,2),rString(r,3)));
                        long total=r.getLong(4);v.total+=total;v.score+=threshold.getValue().score()*(total>=threshold.getValue().critical()?2:1);v.details.add(key+"="+total);
                    }}
                }
            }
            try(var p=readerConnection.prepareStatement("SELECT serial,target_type,target_uuid,source FROM serial_index WHERE serial NOT LIKE 'custom:%' AND  (serial,target_type,target_uuid) IN (SELECT serial,target_type,target_uuid FROM serial_index WHERE serial NOT LIKE 'custom:%' GROUP BY serial,target_type,target_uuid HAVING SUM(occurrences)>1)");var r=p.executeQuery()){
                while(r.next()){String id=r.getString(2)+":"+r.getString(3);var v=values.computeIfAbsent(id,k->new MutableSuspicious(rString(r,2),rString(r,3),rString(r,4)));String reason="SAME_SNAPSHOT_REPEATED_ID="+r.getString(1);if(!v.details.contains(reason)){v.score+=100;v.details.add(reason);}}
            }
            try(var p=readerConnection.prepareStatement("SELECT 'PLAYER',player_uuid FROM player_item_index WHERE item_key='audit:nbt_limit' UNION SELECT 'CONTAINER',i.container_uuid FROM container_item_index i JOIN containers c ON c.uuid=i.container_uuid WHERE i.item_key='audit:nbt_limit' AND c.status='ACTIVE'");var r=p.executeQuery()){
                while(r.next()){String id=r.getString(1)+":"+r.getString(2);var v=values.computeIfAbsent(id,k->new MutableSuspicious(rString(r,1),rString(r,2),"PARTIAL"));v.score+=30;v.details.add("NBT_LIMIT: indice parcial, nao prova duplicacao");}
            }
            try(var p=readerConnection.prepareStatement("SELECT target_type,target_uuid,detail FROM audit_read_failures");var r=p.executeQuery()){
                while(r.next()){String id=r.getString(1)+":"+r.getString(2);var v=values.computeIfAbsent(id,k->new MutableSuspicious(rString(r,1),rString(r,2),"UNREADABLE"));v.score+=30;v.details.add("NBT_READ_FAILED: estado atual desconhecido, "+r.getString(3));}
            }
            try(var p=readerConnection.prepareStatement("SELECT s.target_type,s.target_uuid,s.source,f.reason,MAX(f.score) FROM integrity_flags f JOIN serial_index s ON s.serial=f.target_uuid WHERE f.target_type='ITEM' GROUP BY s.target_type,s.target_uuid,f.reason");var r=p.executeQuery()){
                while(r.next()){String id=r.getString(1)+":"+r.getString(2);var v=values.computeIfAbsent(id,k->new MutableSuspicious(rString(r,1),rString(r,2),rString(r,3)));v.score+=r.getInt(5);v.details.add("ITEM_INTEGRITY_HISTORY="+r.getString(4));}
            }
            return values.values().stream().filter(v->scope.equals("all")||scope.equals("suspeito")||(scope.equals("bau")?v.type.equals("CONTAINER"):v.type.equals("PLAYER")))
                .filter(v->v.score>=minimum).sorted(java.util.Comparator.comparingInt((MutableSuspicious v)->v.score).reversed().thenComparing(v->v.id)).limit(limit)
                .map(v->new SearchResult(v.type,v.id,v.id,v.source,"suspicious",0,0,v.total,v.score,String.join(", ",v.details)+"; indice historico, exige revalidacao fisica")).toList();
        });
    }
    private String rString(ResultSet r,int column){try{return r.getString(column);}catch(SQLException e){throw new IllegalStateException(e);}}

    public CompletableFuture<StoredContainer> loadContainer(UUID id){return supply(()->{
        try(PreparedStatement p=connection.prepareStatement("SELECT c.*,s.inventory_blob FROM containers c LEFT JOIN container_state s ON s.container_uuid=c.uuid WHERE c.uuid=?")){p.setString(1,id.toString());try(ResultSet r=p.executeQuery()){if(!r.next())return null;return new StoredContainer(new ContainerRef(id,r.getString("location_key"),r.getString("world"),r.getInt("x"),r.getInt("y"),r.getInt("z"),uuid(r.getString("entity_uuid")),r.getString("type"),r.getInt("size")),r.getString("status"),r.getLong("created_at"),r.getLong("updated_at"),r.getString("last_player_name"),r.getString("last_cause"),r.getString("content_hash"),r.getBytes("inventory_blob"));}}catch(Exception e){fail(e);return null;}});}

    public CompletableFuture<List<PlayerInteraction>> interactions(UUID id){return supply(()->{List<PlayerInteraction>out=new ArrayList<>();try(PreparedStatement p=connection.prepareStatement("SELECT player_uuid,player_name,first_seen,last_seen,interactions FROM container_players WHERE container_uuid=? ORDER BY last_seen DESC LIMIT 45")){p.setString(1,id.toString());try(ResultSet r=p.executeQuery()){while(r.next())out.add(new PlayerInteraction(UUID.fromString(r.getString(1)),r.getString(2),r.getLong(3),r.getLong(4),r.getLong(5)));}}catch(Exception e){fail(e);}return out;});}

    public CompletableFuture<Status> status(){return supply(()->{try(Statement s=connection.createStatement()){return new Status(healthy,scalar(s,"SELECT COUNT(*) FROM player_files"),scalar(s,"SELECT COUNT(*) FROM containers WHERE status='ACTIVE'"),scalar(s,"SELECT COUNT(*) FROM containers WHERE status='DESTROYED'"),scalar(s,"SELECT COUNT(*) FROM player_item_index")+scalar(s,"SELECT COUNT(*) FROM container_item_index"),executor.getQueue().size(),dropped.get());}catch(SQLException e){fail(e);return new Status(false,0,0,0,0,executor.getQueue().size(),dropped.get());}});}
    public boolean logAdmin(UUID staff,String name,String targetType,String target,String summary){return execute(()->{try(PreparedStatement p=connection.prepareStatement("INSERT INTO admin_actions(staff_uuid,staff_name,target_type,target_uuid,action,item_summary,timestamp) VALUES(?,?,?,?,?,?,?)")){set(p,1,staff);p.setString(2,name);p.setString(3,targetType);p.setString(4,target);p.setString(5,"TRANSFER_TO_ADMIN");p.setString(6,summary);p.setLong(7,System.currentTimeMillis());p.executeUpdate();}catch(SQLException e){fail(e);}});}
    public CompletableFuture<Map<UUID,Long>> playerMtimes(){return supply(()->{Map<UUID,Long>out=new HashMap<>();try(Statement s=connection.createStatement();ResultSet r=s.executeQuery("SELECT player_uuid,file_mtime FROM player_files")){while(r.next())try{out.put(UUID.fromString(r.getString(1)),r.getLong(2));}catch(IllegalArgumentException ignored){}}catch(SQLException e){fail(e);}return out;});}
    public boolean cleanup(int destroyedDays){return execute(()->{
        long cutoff=System.currentTimeMillis()-destroyedDays*86_400_000L;
        try{connection.setAutoCommit(false);
            for(String table:new String[]{"container_state","container_item_index","container_players"})try(PreparedStatement p=connection.prepareStatement("DELETE FROM "+table+" WHERE container_uuid IN (SELECT uuid FROM containers WHERE status!='ACTIVE' AND destroyed_at<?)")){p.setLong(1,cutoff);p.executeUpdate();}
            try(PreparedStatement p=connection.prepareStatement("DELETE FROM serial_index WHERE target_type='CONTAINER' AND target_uuid IN (SELECT uuid FROM containers WHERE status!='ACTIVE' AND destroyed_at<?)")){p.setLong(1,cutoff);p.executeUpdate();}
            try(PreparedStatement p=connection.prepareStatement("DELETE FROM containers WHERE status!='ACTIVE' AND destroyed_at<?")){p.setLong(1,cutoff);p.executeUpdate();}
            connection.commit();
        }catch(SQLException e){rollback(e);}finally{autoCommit();}
    });}

    public CompletableFuture<StoredContainer> loadActive(String location){
        return supply(()->{try(var p=connection.prepareStatement("SELECT uuid FROM containers WHERE location_key=? AND status='ACTIVE'")){
            p.setString(1,location);try(var r=p.executeQuery()){return r.next()?loadContainerNow(UUID.fromString(r.getString(1))):null;}
        }});
    }
    private StoredContainer loadContainerNow(UUID id)throws SQLException{
        try(var p=connection.prepareStatement("SELECT c.*,s.inventory_blob FROM containers c LEFT JOIN container_state s ON s.container_uuid=c.uuid WHERE c.uuid=?")){
            p.setString(1,id.toString());try(var r=p.executeQuery()){
                if(!r.next())return null;
                return new StoredContainer(new ContainerRef(id,r.getString("location_key"),r.getString("world"),r.getInt("x"),r.getInt("y"),r.getInt("z"),uuid(r.getString("entity_uuid")),r.getString("type"),r.getInt("size")),r.getString("status"),r.getLong("created_at"),r.getLong("updated_at"),r.getString("last_player_name"),r.getString("last_cause"),r.getString("content_hash"),r.getBytes("inventory_blob"));
            }
        }
    }
    public CompletableFuture<Boolean> removePlayer(UUID id){return supply(()->{
        connection.setAutoCommit(false);try{
            for(String table:new String[]{"player_files","player_item_index"})try(var p=connection.prepareStatement("DELETE FROM "+table+" WHERE player_uuid=?")){p.setString(1,id.toString());p.executeUpdate();}
            try(var p=connection.prepareStatement("DELETE FROM serial_index WHERE target_type='PLAYER' AND target_uuid=?")){p.setString(1,id.toString());p.executeUpdate();}
            connection.commit();return true;
        }catch(SQLException e){connection.rollback();throw e;}finally{connection.setAutoCommit(true);}
    });}
    private <T> CompletableFuture<T> query(Callable<T> task){
        CompletableFuture<T> result=new CompletableFuture<>();
        // Enqueue a barrier so reads observe all writes already accepted by this API.
        if(!execute(()->{try{readers.execute(()->{try{result.complete(task.call());}catch(Exception e){fail(e);result.completeExceptionally(e);}});}catch(RejectedExecutionException e){result.completeExceptionally(e);}}))result.completeExceptionally(new RejectedExecutionException());
        return result;
    }
    public void recordIntegrity(String itemId,String reason,String detail,int score){
        execute(()->{try(var p=connection.prepareStatement("INSERT INTO integrity_flags(target_type,target_uuid,reason,score,detail,first_seen,last_seen,occurrences) VALUES('ITEM',?,?,?,?,?,?,1) ON CONFLICT(target_type,target_uuid,reason,detail) DO UPDATE SET last_seen=excluded.last_seen,occurrences=integrity_flags.occurrences+1,score=excluded.score")){
            p.setString(1,itemId);p.setString(2,reason);p.setInt(3,score);p.setString(4,detail);long now=System.currentTimeMillis();p.setLong(5,now);p.setLong(6,now);p.executeUpdate();
        }catch(SQLException e){fail(e);}});
    }
    public CompletableFuture<Long> prepareTransfer(UUID staff,String name,String targetType,String target,String summary,byte[] source,byte[] destination){
        return supply(()->{
            connection.setAutoCommit(false);
            try(var p=connection.prepareStatement("INSERT INTO admin_actions(staff_uuid,staff_name,target_type,target_uuid,action,item_summary,timestamp) VALUES(?,?,?,?,?,?,?)")){
                p.setString(1,staff.toString());p.setString(2,name);p.setString(3,targetType);p.setString(4,target);p.setString(5,"TRANSFER_PENDING");p.setString(6,summary);p.setLong(7,System.currentTimeMillis());p.executeUpdate();
                long id;try(var q=connection.createStatement();var r=q.executeQuery("SELECT last_insert_rowid()")){r.next();id=r.getLong(1);}
                try(var q=connection.prepareStatement("INSERT INTO transfer_evidence VALUES(?,?,?,?)")){
                    q.setLong(1,id);q.setBytes(2,SnapshotCodec.compress(source));q.setBytes(3,SnapshotCodec.compress(destination));q.setLong(4,System.currentTimeMillis());q.executeUpdate();
                }
                connection.commit();healthy=true;return id;
            }catch(Exception e){connection.rollback();fail(e);throw e;}finally{connection.setAutoCommit(true);}
        });
    }
    public CompletableFuture<Boolean> finishTransfer(long id,String action){
        return supply(()->{try(var p=connection.prepareStatement("UPDATE admin_actions SET action=? WHERE id=? AND action='TRANSFER_PENDING'")){
            p.setString(1,action);p.setLong(2,id);return p.executeUpdate()==1;
        }});
    }
    public void cleanupEvidence(int days){execute(()->{try(var p=connection.prepareStatement("DELETE FROM transfer_evidence WHERE created_at<? AND action_id IN (SELECT id FROM admin_actions WHERE action!='TRANSFER_PENDING')")){
        p.setLong(1,System.currentTimeMillis()-days*86_400_000L);p.executeUpdate();
        try(var flags=connection.prepareStatement("DELETE FROM integrity_flags WHERE last_seen<?")){flags.setLong(1,System.currentTimeMillis()-days*86_400_000L);flags.executeUpdate();}
    }catch(SQLException e){fail(e);}});}
    public boolean healthy(){return healthy;}
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
    @Override public void close(){
        execute(()->{try{connection.close();}catch(SQLException e){fail(e);}readers.shutdown();});
        executor.shutdown();
        try{executor.awaitTermination(5,TimeUnit.SECONDS);if(readers.awaitTermination(5,TimeUnit.SECONDS))readerConnection.close();}
        catch(InterruptedException e){Thread.currentThread().interrupt();}catch(SQLException e){fail(e);}
    }

    public record StoredContainer(ContainerRef ref,String status,long createdAt,long updatedAt,String lastPlayer,String lastCause,String hash,byte[] compressedInventory){}
    public record PlayerInteraction(UUID uuid,String name,long firstSeen,long lastSeen,long interactions){}
    public record Status(boolean healthy,long players,long activeContainers,long destroyedContainers,long indexedItems,int queue,long dropped){}
    private static final class MutableSuspicious{final String type,id,source;int score;long total;final List<String>details=new ArrayList<>();MutableSuspicious(String type,String id,String source){this.type=type;this.id=id;this.source=source;}}
}
