package main.java.me.dniym.audit.container;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.inventory.DoubleChestInventory;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ContainerResolverTest {
    @Test void eitherDoubleChestSideResolvesOneCanonicalIdentity(){
        World world=mock(World.class);when(world.getUID()).thenReturn(UUID.randomUUID());
        BlockState leftHolder=state(world,20,64,10),rightHolder=state(world,19,64,10);
        Inventory left=mock(Inventory.class),right=mock(Inventory.class);when(left.getHolder(false)).thenReturn((InventoryHolder)leftHolder);when(right.getHolder(false)).thenReturn((InventoryHolder)rightHolder);
        DoubleChestInventory doubleChest=mock(DoubleChestInventory.class);when(doubleChest.getLeftSide()).thenReturn(left);when(doubleChest.getRightSide()).thenReturn(right);when(doubleChest.getSize()).thenReturn(54);
        var first=new ContainerResolver().resolve(doubleChest).orElseThrow().ref();
        when(doubleChest.getLeftSide()).thenReturn(right);when(doubleChest.getRightSide()).thenReturn(left);
        var reversed=new ContainerResolver().resolve(doubleChest).orElseThrow().ref();
        assertEquals(first.uuid(),reversed.uuid());assertEquals(first.locationKey(),reversed.locationKey());assertEquals(19,first.x());
    }
    @Test void rejectsPlayerOwnedEnderAndPluginMenus(){
        var player=mock(org.bukkit.entity.Player.class);var inventory=mock(Inventory.class);
        when(inventory.getHolder(false)).thenReturn(player);
        when(player.getEnderChest()).thenReturn(inventory);
        assertTrue(new ContainerResolver().resolve(inventory).isEmpty());
        verifyNoInteractions(player);
    }
    @Test void rejectsUnrelatedEntityInventory(){
        var entity=mock(org.bukkit.entity.minecart.StorageMinecart.class);
        Inventory physical=mock(Inventory.class),menu=mock(Inventory.class);
        when(entity.getInventory()).thenReturn(physical);when(menu.getHolder(false)).thenReturn(entity);
        assertTrue(new ContainerResolver().resolve(menu).isEmpty());
    }
    private BlockState state(World world,int x,int y,int z){BlockState state=mock(BlockState.class,withSettings().extraInterfaces(InventoryHolder.class));Block block=mock(Block.class);when(state.getBlock()).thenReturn(block);when(state.getLocation()).thenReturn(new Location(world,x,y,z));return state;}
}
