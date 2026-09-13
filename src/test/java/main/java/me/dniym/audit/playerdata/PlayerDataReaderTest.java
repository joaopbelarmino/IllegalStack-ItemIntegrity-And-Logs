package main.java.me.dniym.audit.playerdata;

import net.querz.nbt.io.NBTUtil;
import net.querz.nbt.tag.CompoundTag;
import net.querz.nbt.tag.ListTag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class PlayerDataReaderTest {
    @TempDir Path temp;
    @Test void indexesDirectAndNestedComponentContainers()throws Exception{
        CompoundTag root=new CompoundTag();root.putInt("DataVersion",4500);
        ListTag<CompoundTag> inventory=new ListTag<>(CompoundTag.class);
        CompoundTag direct=item("minecraft:diamond_block",4);direct.putByte("Slot",(byte)0);inventory.add(direct);
        CompoundTag shulker=item("minecraft:shulker_box",1);shulker.putByte("Slot",(byte)1);
        CompoundTag components=new CompoundTag();ListTag<CompoundTag> container=new ListTag<>(CompoundTag.class);
        CompoundTag entry=new CompoundTag();entry.putByte("slot",(byte)0);entry.put("item",item("minecraft:netherite_block",64));container.add(entry);
        components.put("minecraft:container",container);shulker.put("components",components);inventory.add(shulker);root.put("Inventory",inventory);
        ListTag<CompoundTag> ender=new ListTag<>(CompoundTag.class);CompoundTag sponge=item("minecraft:sponge",12);sponge.putByte("Slot",(byte)2);ender.add(sponge);root.put("EnderItems",ender);
        Path file=temp.resolve(UUID.randomUUID()+".dat");NBTUtil.write(root,file.toFile());
        var snapshot=new PlayerDataReader().read(file.toFile());
        var netherite=snapshot.inventoryIndex().stream().filter(i->i.itemKey().equals("minecraft:netherite_block")).findFirst().orElseThrow();
        assertEquals(0,netherite.direct());assertEquals(64,netherite.nested());
        assertEquals(4,snapshot.inventoryIndex().stream().filter(i->i.itemKey().equals("minecraft:diamond_block")).findFirst().orElseThrow().direct());
        assertEquals(12,snapshot.enderIndex().stream().filter(i->i.itemKey().equals("minecraft:sponge")).findFirst().orElseThrow().direct());
    }
    private CompoundTag item(String id,int count){CompoundTag item=new CompoundTag();item.putString("id",id);item.putInt("count",count);return item;}
}
