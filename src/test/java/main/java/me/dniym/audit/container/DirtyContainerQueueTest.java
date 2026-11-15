package main.java.me.dniym.audit.container;

import main.java.me.dniym.IllegalStack;
import main.java.me.dniym.audit.AuditConfig;
import main.java.me.dniym.audit.database.AuditDatabase;
import main.java.me.dniym.audit.model.*;
import main.java.me.dniym.utils.Scheduler;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.junit.jupiter.api.Assertions.*;

class DirtyContainerQueueTest {

    @Test void unrelatedPlacementDoesNotChangeObservedContainersRevision(){
        try(var scheduler=mockStatic(Scheduler.class)){
            var dirty=new DirtyContainerQueue(mock(IllegalStack.class),mock(AuditConfig.class),mock(AuditDatabase.class),mock(ContainerResolver.class),mock(ItemAggregator.class));
            UUID worldId=UUID.randomUUID();World world=mock(World.class);when(world.getUID()).thenReturn(worldId);
            var ref=new ContainerRef(UUID.randomUUID(),"block:"+worldId+":1:2:3","world",1,2,3,null,"CHEST",27);
            long original=dirty.topologyRevision(ref);
            var distant=mock(org.bukkit.block.Block.class);when(distant.getWorld()).thenReturn(world);when(distant.getX()).thenReturn(900);
            dirty.refreshChestTopology(distant);assertEquals(original,dirty.topologyRevision(ref));
            var same=mock(org.bukkit.block.Block.class);when(same.getWorld()).thenReturn(world);when(same.getX()).thenReturn(1);when(same.getY()).thenReturn(2);when(same.getZ()).thenReturn(3);
            dirty.refreshChestTopology(same);assertNotEquals(original,dirty.topologyRevision(ref));
        }
    }
    @Test void fullDiscoveryQueueDoesNotRejectPlayerInteraction(){
        try(var scheduler=mockStatic(Scheduler.class)){
            var config=mock(AuditConfig.class);when(config.containersEnabled()).thenReturn(true);when(config.dirtyCapacity()).thenReturn(64);
            var resolver=mock(ContainerResolver.class);
            var dirty=new DirtyContainerQueue(mock(IllegalStack.class),config,mock(AuditDatabase.class),resolver,mock(ItemAggregator.class));
            for(int i=0;i<100;i++){
                Inventory inv=mock(Inventory.class);var ref=new ContainerRef(UUID.randomUUID(),"block:w:"+i+":2:3","world",i,2,3,null,"CHEST",27);
                when(resolver.resolve(inv)).thenReturn(Optional.of(new ContainerResolver.Resolved(ref,inv)));dirty.discover(inv);
            }
            assertEquals(64,dirty.size());long dropped=dirty.dropped();
            Inventory real=mock(Inventory.class);var ref=new ContainerRef(UUID.randomUUID(),"block:w:1000:2:3","world",1000,2,3,null,"CHEST",27);
            when(resolver.resolve(real)).thenReturn(Optional.of(new ContainerResolver.Resolved(ref,real)));
            dirty.mark(real,AuditCause.PLAYER,null,true);
            assertEquals(65,dirty.size());assertEquals(dropped,dirty.dropped());
        }
    }
    @Test void unloadCapturesBeforeDebounceAndPreservesEachPlayersInteractions(){
        try(var bukkit=mockStatic(Bukkit.class);var scheduler=mockStatic(Scheduler.class);var codec=mockStatic(SnapshotCodec.class)){
            AuditConfig config=mock(AuditConfig.class);when(config.containersEnabled()).thenReturn(true);when(config.dirtyCapacity()).thenReturn(64);when(config.debounceTicks()).thenReturn(60L);when(config.maxDirtyTicks()).thenReturn(600L);
            var db=mock(AuditDatabase.class);var resolver=mock(ContainerResolver.class);Inventory inventory=mock(Inventory.class);
            var ref=new ContainerRef(UUID.randomUUID(),"block:w:1:2:3","world",1,2,3,null,"CHEST",27);
            var resolved=new ContainerResolver.Resolved(ref,inventory);when(resolver.resolve(inventory)).thenReturn(Optional.of(resolved));
            when(inventory.getContents()).thenReturn(new ItemStack[27]);codec.when(()->SnapshotCodec.serialize(any(ItemStack[].class))).thenReturn(new byte[]{1});
            var dirty=new DirtyContainerQueue(mock(IllegalStack.class),config,db,resolver,mock(ItemAggregator.class));
            Player a=mock(Player.class),b=mock(Player.class);UUID aid=UUID.randomUUID(),bid=UUID.randomUUID();
            when(a.getUniqueId()).thenReturn(aid);when(a.getName()).thenReturn("A");when(b.getUniqueId()).thenReturn(bid);when(b.getName()).thenReturn("B");
            dirty.mark(inventory,AuditCause.PLAYER,a,true);dirty.mark(inventory,AuditCause.PLAYER,b,true);
            verifyNoInteractions(db);
            Chunk chunk=mock(Chunk.class);World world=mock(World.class);when(world.getName()).thenReturn("world");when(chunk.getWorld()).thenReturn(world);
            bukkit.when(()->Bukkit.getWorld("world")).thenReturn(world);when(world.isChunkLoaded(0,0)).thenReturn(true);
            org.bukkit.block.Block block=mock(org.bukkit.block.Block.class);when(world.getBlockAt(1,2,3)).thenReturn(block);when(resolver.resolve(block)).thenReturn(Optional.of(resolved));
            dirty.unload(chunk);
            verify(db).submitRaw(eq(ref),any(),eq(bid),eq("B"),eq(AuditCause.PLAYER),anyLong(),eq(false),argThat(map->map.size()==2&&map.get(aid).count()==1&&map.get(bid).count()==1));
            assertEquals(0,dirty.size());
        }
    }
}
