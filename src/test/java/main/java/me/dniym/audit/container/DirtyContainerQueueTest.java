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
    @Test void unloadCapturesBeforeDebounceAndPreservesEachPlayersInteractions(){
        try(var scheduler=mockStatic(Scheduler.class);var codec=mockStatic(SnapshotCodec.class)){
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
            dirty.unload(chunk);
            verify(db).submitRaw(eq(ref),any(),eq(bid),eq("B"),eq(AuditCause.PLAYER),anyLong(),eq(false),argThat(map->map.size()==2&&map.get(aid).count()==1&&map.get(bid).count()==1));
            assertEquals(0,dirty.size());
        }
    }
}
