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
