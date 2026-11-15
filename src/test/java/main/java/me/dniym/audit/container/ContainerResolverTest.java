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

    @Test void differentWrappersOfSameMinecartInventoryAreAccepted(){
        var owner=mock(org.bukkit.entity.minecart.StorageMinecart.class);
        Object backing=new Object();
        Inventory physical=wrapper(owner,backing),screen=wrapper(owner,backing);
        World world=mock(World.class);when(world.getName()).thenReturn("world");
        when(owner.getInventory()).thenReturn(physical);when(owner.getLocation()).thenReturn(new Location(world,1,2,3));
        when(owner.getUniqueId()).thenReturn(UUID.randomUUID());when(owner.getType()).thenReturn(org.bukkit.entity.EntityType.CHEST_MINECART);
        assertNotSame(physical,screen);assertEquals(physical,screen);
        assertTrue(new ContainerResolver().resolve(screen).isPresent());
    }
    private Inventory wrapper(InventoryHolder owner,Object backing){
        return (Inventory)java.lang.reflect.Proxy.newProxyInstance(Inventory.class.getClassLoader(),new Class[]{Inventory.class},new Wrapper(owner,backing));
    }
    private record Wrapper(InventoryHolder owner,Object backing) implements java.lang.reflect.InvocationHandler{
        public Object invoke(Object self,java.lang.reflect.Method method,Object[] args){
            return switch(method.getName()){
                case "equals" -> args[0]!=null&&java.lang.reflect.Proxy.isProxyClass(args[0].getClass())&&java.lang.reflect.Proxy.getInvocationHandler(args[0]) instanceof Wrapper other&&other.backing==backing;
                case "hashCode" -> System.identityHashCode(backing);
                case "getHolder" -> owner;
                case "getSize" -> 27;
                default -> null;
            };
        }
    }
    private BlockState state(World world,int x,int y,int z){BlockState state=mock(BlockState.class,withSettings().extraInterfaces(InventoryHolder.class));Block block=mock(Block.class);when(state.getBlock()).thenReturn(block);when(state.getLocation()).thenReturn(new Location(world,x,y,z));return state;}
}
