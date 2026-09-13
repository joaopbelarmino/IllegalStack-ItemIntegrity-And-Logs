package main.java.me.dniym.audit.database;

import main.java.me.dniym.audit.container.SnapshotCodec;
import main.java.me.dniym.audit.model.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class AuditDatabaseTest {
    @TempDir Path temp;
    @Test void storesOnlyCurrentContainerStateAndAggregatesInteractions()throws Exception{
        AuditDatabase db=new AuditDatabase(temp.resolve("audit.db").toFile(),100);
        try{
            ContainerRef ref=new ContainerRef(ContainerRef.stableUuid("block:w:1:2:3"),"block:w:1:2:3","world",1,2,3,null,"CHEST",27);
            ItemAggregate item=new ItemAggregate("minecraft:netherite_block",64,128,Set.of("serial-a"),Set.of());
            UUID player=UUID.randomUUID();byte[] raw={1,2,3};
            assertTrue(db.submitContainer(new ContainerSnapshot(ref,raw,"hash",List.of(item),player,"Admin",AuditCause.PLAYER,10,false,null,3),true));
            assertEquals(1,db.search("bau","minecraft:netherite_block",20,0).get().size());
            assertTrue(db.submitContainer(new ContainerSnapshot(ref,raw,"hash",List.of(item),player,"Admin",AuditCause.PLAYER,20,false,null,4),true));
            assertEquals(7,db.interactions(ref.uuid()).get().getFirst().interactions());
            var loaded=db.loadContainer(ref.uuid()).get();assertEquals("hash",loaded.hash());assertArrayEquals(raw,SnapshotCodec.decompress(loaded.compressedInventory(),1024));
            assertEquals(1,db.searchSerial("serial-a",20,0).get().size());
        }finally{db.close();}
    }
    @Test void replacementPreservesDestroyedSnapshotAndDoubleChestRetiresSingles()throws Exception{
        try(var db=new AuditDatabase(temp.resolve("generations.db").toFile(),100)){
            var a=ref("block:w:1:2:3",1);var b=ref("block:w:2:2:3",2);
            save(db,a,64,"a",false,1);save(db,b,32,"b",false,2);
            var doubleChest=ref("double:block:w:1:2:3+block:w:2:2:3",1);
            save(db,doubleChest,96,"double",false,3);
            assertEquals(1,db.search("bau","minecraft:diamond",20,0).get().size());
            assertEquals("REPLACED",db.loadContainer(a.uuid()).get().status());
            save(db,doubleChest,96,"final",true,4);
            assertTrue(db.search("bau","minecraft:diamond",20,0).get().isEmpty());
            save(db,doubleChest,1,"new",false,5);
            assertEquals("final",db.loadContainer(doubleChest.uuid()).get().hash());
            assertEquals("DESTROYED",db.loadContainer(doubleChest.uuid()).get().status());
            assertNotEquals(doubleChest.uuid(),db.loadActive(doubleChest.locationKey()).get().ref().uuid());
        }
    }
    @Test void globalPaginationAndPlayerTotalsDoNotLoseRows()throws Exception{
        try(var db=new AuditDatabase(temp.resolve("pages.db").toFile(),100)){
            UUID p=UUID.randomUUID();db.replacePlayer(p,"P",1,"p",List.of(item(60)),List.of(item(60))).get();
            var totals=db.search("playerdata","minecraft:diamond",20,0).get();assertEquals(1,totals.size());assertEquals(120,totals.getFirst().total());
            save(db,ref("block:w:1:2:3",1),100,"a",false,1);
            save(db,ref("block:w:2:2:3",2),50,"b",false,2);
            var page1=db.search("all","minecraft:diamond",2,0).get();
            var page2=db.search("all","minecraft:diamond",2,2).get();
            assertEquals(100,page1.getFirst().total());assertEquals(60,page2.getFirst().total());assertEquals(50,page2.getLast().total());
            var thresholds=java.util.Map.of("diamond",new main.java.me.dniym.audit.AuditConfig.Threshold(64,256,40));
            var suspects=db.suspicious(thresholds,30,20,"playerdata").get();assertEquals(1,suspects.size());assertEquals(40,suspects.getFirst().score());
        }
    }
    @Test void sameInventoryMultiplicityUsesItemIntegrityIds()throws Exception{
        try(var db=new AuditDatabase(temp.resolve("multiplicity.db").toFile(),100)){
            UUID id=UUID.randomUUID();
            var repeated=new ItemAggregate("minecraft:elytra",2,0,java.util.Map.of("ZI-same",2),java.util.Map.of());
            db.replacePlayer(id,"P",1,"p",List.of(repeated),List.of()).get();
            assertEquals(2,db.searchSerial("ZI-same",10,0).get().getFirst().total());
            assertEquals(1,db.suspicious(java.util.Map.of(),30,10).get().size());
            db.replacePlayer(id,"P",2,"p2",List.of(new ItemAggregate("minecraft:elytra",1,0,java.util.Map.of("ZI-same",1),java.util.Map.of())),List.of()).get();
            assertTrue(db.suspicious(java.util.Map.of(),30,10).get().isEmpty());
        }
    }
    @Test void pendingTransferIsCommittedBeforeResultAndSurvivesReopen()throws Exception{
        var file=temp.resolve("transfer.db").toFile();long id;
        try(var db=new AuditDatabase(file,100)){
            id=db.prepareTransfer(UUID.randomUUID(),"Admin","PLAYER_INV","target","1x ELYTRA",new byte[]{1},new byte[]{2}).get();
            try(var c=java.sql.DriverManager.getConnection("jdbc:sqlite:"+file);var st=c.createStatement();var r=st.executeQuery("SELECT action FROM admin_actions WHERE id="+id)){assertTrue(r.next());assertEquals("TRANSFER_PENDING",r.getString(1));}
            assertTrue(db.finishTransfer(id,"TRANSFER_ABORTED_REVALIDATION").get());
            assertFalse(db.finishTransfer(id,"TRANSFERRED").get());
        }
        try(var db=new AuditDatabase(file,100);var c=java.sql.DriverManager.getConnection("jdbc:sqlite:"+file);var st=c.createStatement();var r=st.executeQuery("SELECT action FROM admin_actions WHERE id="+id)){
            assertTrue(r.next());assertEquals("TRANSFER_ABORTED_REVALIDATION",r.getString(1));
        }
    }
    private ContainerRef ref(String key,int x){return new ContainerRef(ContainerRef.stableUuid(key),key,"world",x,2,3,null,"CHEST",27);}
    private ItemAggregate item(int amount){return new ItemAggregate("minecraft:diamond",amount,0,Set.of(),Set.of());}
    private void save(AuditDatabase db,ContainerRef ref,int amount,String hash,boolean destroyed,long now){
        assertTrue(db.submitContainer(new ContainerSnapshot(ref,new byte[]{1},hash,List.of(item(amount)),null,null,AuditCause.PLAYER,now,destroyed,null,0),false));
    }
    @Test void suspiciousThresholdAndDuplicateSerialAreIndexed()throws Exception{
        AuditDatabase db=new AuditDatabase(temp.resolve("suspect.db").toFile(),100);
        try{
            var threshold=java.util.Map.of("netherite_block",new main.java.me.dniym.audit.AuditConfig.Threshold(64,256,40));
            UUID a=UUID.randomUUID(),b=UUID.randomUUID();
            ItemAggregate item=new ItemAggregate("minecraft:netherite_block",256,0,Set.of("same"),Set.of());
            db.replacePlayer(a,"A",1,"a",List.of(item),List.of()).get();db.replacePlayer(b,"B",1,"b",List.of(item),List.of()).get();
            var results=db.suspicious(threshold,30,10).get();assertEquals(2,results.size());assertTrue(results.stream().allMatch(r->r.score()>=100));
        }finally{db.close();}
    }
}
