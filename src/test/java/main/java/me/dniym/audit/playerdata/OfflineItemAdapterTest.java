package main.java.me.dniym.audit.playerdata;

import net.querz.nbt.tag.CompoundTag;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class OfflineItemAdapterTest {
    @Test void emitsGzipWithOriginalComponentsAndDataVersion()throws Exception{
        CompoundTag item=new CompoundTag();item.putString("id","minecraft:elytra");item.putInt("count",1);
        CompoundTag components=new CompoundTag();components.putString("minecraft:custom_name","Test");item.put("components",components);
        try(var api=mockStatic(ItemStack.class)){
            api.when(()->ItemStack.deserializeBytes(any(byte[].class))).thenAnswer(call->{
                byte[] bytes=call.getArgument(0);assertEquals(0x1f,bytes[0]&255);assertEquals(0x8b,bytes[1]&255);
                var decoded=(CompoundTag)PlayerDataReader.readCompressed(bytes).getTag();
                assertEquals(4500,decoded.getInt("DataVersion").orElseThrow());
                assertEquals(components,decoded.get("components"));return mock(ItemStack.class);
            });
            assertNotNull(new OfflineItemAdapter().decode(item,4500));
            assertFalse(item.containsKey("DataVersion"));
        }
    }
    @Test void acceptsOnlyFinalPlayerdataFileNames(){
        assertTrue(PlayerDataIndexer.isPlayerFile("12345678-1234-1234-1234-123456789abc.dat"));
        assertFalse(PlayerDataIndexer.isPlayerFile("12345678-1234-1234-1234-123456789abc-1.dat"));
    }
}
