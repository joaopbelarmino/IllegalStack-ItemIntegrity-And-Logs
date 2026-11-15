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
        Path file=temp.resolve(UUID.randomUUID()+".dat");try(var gzip=new java.util.zip.GZIPOutputStream(java.nio.file.Files.newOutputStream(file));
            var out=new net.querz.nbt.io.NBTOutputStream(gzip)){out.writeTag(new net.querz.nbt.io.NamedTag("",root),32);}
        var snapshot=new PlayerDataReader().read(file.toFile());
        var netherite=snapshot.inventoryIndex().stream().filter(i->i.itemKey().equals("minecraft:netherite_block")).findFirst().orElseThrow();
        assertEquals(0,netherite.direct());assertEquals(64,netherite.nested());
        assertEquals(4,snapshot.inventoryIndex().stream().filter(i->i.itemKey().equals("minecraft:diamond_block")).findFirst().orElseThrow().direct());
        assertEquals(12,snapshot.enderIndex().stream().filter(i->i.itemKey().equals("minecraft:sponge")).findFirst().orElseThrow().direct());
    }

    @Test void deepItemDoesNotHideOtherInventoryOrEnderItems()throws Exception{
        CompoundTag root=new CompoundTag(), heavy=item("minecraft:bundle",1), node=heavy;
        for(int i=0;i<80;i++){CompoundTag child=new CompoundTag();node.put("deep",child);node=child;}
        ListTag<CompoundTag> inv=new ListTag<>(CompoundTag.class);inv.add(heavy);inv.add(item("minecraft:diamond",7));root.put("Inventory",inv);
        ListTag<CompoundTag> end=new ListTag<>(CompoundTag.class);end.add(item("minecraft:emerald",9));root.put("EnderItems",end);
        Path file=temp.resolve(UUID.randomUUID()+".dat");
        try(var gzip=new java.util.zip.GZIPOutputStream(java.nio.file.Files.newOutputStream(file));var out=new net.querz.nbt.io.NBTOutputStream(gzip)){out.writeTag(new net.querz.nbt.io.NamedTag("",root),256);}
        var snapshot=new PlayerDataReader().read(file.toFile());
        assertTrue(snapshot.inventoryIndex().stream().anyMatch(i->i.itemKey().equals("minecraft:bundle")&&i.direct()==1));
        assertTrue(snapshot.inventoryIndex().stream().anyMatch(i->i.itemKey().equals("minecraft:diamond")&&i.direct()==7));
        assertTrue(snapshot.inventoryIndex().stream().anyMatch(i->i.itemKey().equals("audit:nbt_limit")));
        assertTrue(snapshot.enderIndex().stream().anyMatch(i->i.itemKey().equals("minecraft:emerald")&&i.direct()==9));
    }
    @Test void serializedContainerKeepsHealthyItemsAfterDeepItem()throws Exception{
        CompoundTag heavy=item("minecraft:shulker_box",1),node=heavy;
        for(int i=0;i<80;i++){CompoundTag child=new CompoundTag();node.put("deep",child);node=child;}
        var buffer=new java.io.ByteArrayOutputStream();var envelope=new java.io.DataOutputStream(buffer);envelope.writeByte(1);envelope.writeInt(2);
        for(CompoundTag item:java.util.List.of(heavy,item("minecraft:diamond",3))){
            var compressed=new java.io.ByteArrayOutputStream();
            try(var gzip=new java.util.zip.GZIPOutputStream(compressed);var out=new net.querz.nbt.io.NBTOutputStream(gzip)){out.writeTag(new net.querz.nbt.io.NamedTag("",item),256);}
            byte[] bytes=compressed.toByteArray();envelope.writeInt(bytes.length);envelope.write(bytes);
        }
        var result=new PlayerDataReader().aggregateSerialized(buffer.toByteArray());
        assertTrue(result.stream().anyMatch(i->i.itemKey().equals("minecraft:diamond")&&i.direct()==3));
        assertTrue(result.stream().anyMatch(i->i.itemKey().equals("audit:nbt_limit")));
    }

    @Test void largeNodeListIsPartialButNextPlayerItemIsIndexed()throws Exception{
        CompoundTag root=new CompoundTag(),heavy=item("minecraft:written_book",1);
        ListTag<CompoundTag> pages=new ListTag<>(CompoundTag.class);
        for(int i=0;i<100005;i++){CompoundTag page=new CompoundTag();page.putString("text","x");pages.add(page);}
        heavy.put("pages",pages);
        ListTag<CompoundTag> inv=new ListTag<>(CompoundTag.class);inv.add(heavy);inv.add(item("minecraft:diamond",7));root.put("Inventory",inv);
        Path file=temp.resolve(UUID.randomUUID()+".dat");
        try(var gzip=new java.util.zip.GZIPOutputStream(java.nio.file.Files.newOutputStream(file));var out=new net.querz.nbt.io.NBTOutputStream(gzip)){out.writeTag(new net.querz.nbt.io.NamedTag("",root),64);}
        var snapshot=new PlayerDataReader().read(file.toFile());
        assertTrue(snapshot.inventoryIndex().stream().anyMatch(i->i.itemKey().equals("minecraft:diamond")&&i.direct()==7));
        assertTrue(snapshot.inventoryIndex().stream().anyMatch(i->i.itemKey().equals("audit:nbt_limit")));
    }
    private CompoundTag item(String id,int count){CompoundTag item=new CompoundTag();item.putString("id",id);item.putInt("count",count);return item;}
}
