package main.java.me.dniym.audit.playerdata;

import main.java.me.dniym.audit.model.ItemAggregate;
import net.querz.nbt.io.NBTUtil;
import net.querz.nbt.io.NamedTag;
import net.querz.nbt.tag.CompoundTag;
import net.querz.nbt.tag.ListTag;
import net.querz.nbt.tag.NumberTag;
import net.querz.nbt.tag.StringTag;
import net.querz.nbt.tag.Tag;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Bounded, read-only playerdata parser. It never touches Bukkit from its worker thread. */
public final class PlayerDataReader {
    private static final int MAX_DEPTH=3, MAX_ITEMS=4096;

    public Snapshot read(File file) throws IOException {
        byte[] bytes=Files.readAllBytes(file.toPath());
        if(bytes.length>64*1024*1024)throw new IOException("playerdata excede 64 MiB");
        NamedTag named=NBTUtil.read(file);
        if(!(named.getTag() instanceof CompoundTag root))throw new IOException("root NBT nao e compound");
        List<RawSlot> inventory=slots(root.getListTag("Inventory"));
        List<RawSlot> ender=slots(root.getListTag("EnderItems"));
        return new Snapshot(uuid(file),file.lastModified(),hash(bytes),inventory,ender,
                root.getInt("DataVersion").orElse(0),aggregate(inventory),aggregate(ender));
    }

    private List<RawSlot> slots(ListTag<?> list){
        List<RawSlot> out=new ArrayList<>();if(list==null)return out;int seen=0;
        for(Tag<?> tag:list){if(++seen>MAX_ITEMS)break;if(tag instanceof CompoundTag item)out.add(new RawSlot(number(item,"Slot",-1),item.clone()));}
        return out;
    }

    private List<ItemAggregate> aggregate(List<RawSlot> slots){
        Map<String,Mutable> out=new HashMap<>();Counter count=new Counter();
        for(RawSlot slot:slots)visit(slot.item(),0,false,out,count);
        List<ItemAggregate> result=new ArrayList<>();
        out.forEach((key,value)->result.add(new ItemAggregate(key,value.direct,value.nested,Set.copyOf(value.serials),Set.copyOf(value.custom))));
        return result;
    }

    private void visit(CompoundTag item,int depth,boolean nested,Map<String,Mutable> out,Counter counter){
        if(counter.value++>=MAX_ITEMS)return;
        String id=string(item,"id",string(item,"Id",null));if(id==null)return;
        String key=id.toLowerCase(Locale.ROOT);long amount=Math.max(1,number(item,"count",number(item,"Count",1)));
        Mutable value=out.computeIfAbsent(key,ignored->new Mutable());if(nested)value.nested+=amount;else value.direct+=amount;
        CompoundTag components=compound(item,"components");
        if(components!=null){
            collectCustomData(components,value);
            if(depth<MAX_DEPTH){
                visitContainerList(components.getListTag("minecraft:container"),depth,out,counter);
                visitItemList(components.getListTag("minecraft:bundle_contents"),depth,out,counter);
            }
        }
        CompoundTag legacy=compound(item,"tag");
        if(legacy!=null&&depth<MAX_DEPTH){
            CompoundTag block=compound(legacy,"BlockEntityTag");if(block!=null)visitItemList(block.getListTag("Items"),depth,out,counter);
        }
    }

    private void visitContainerList(ListTag<?> list,int depth,Map<String,Mutable> out,Counter counter){
        if(list==null)return;for(Tag<?> t:list)if(t instanceof CompoundTag entry){CompoundTag item=compound(entry,"item");if(item!=null)visit(item,depth+1,true,out,counter);}
    }
    private void visitItemList(ListTag<?> list,int depth,Map<String,Mutable> out,Counter counter){
        if(list==null)return;for(Tag<?> t:list)if(t instanceof CompoundTag item)visit(item,depth+1,true,out,counter);
    }
    private void collectCustomData(CompoundTag components,Mutable value){
        CompoundTag custom=compound(components,"minecraft:custom_data");if(custom==null)return;
        String serial=string(custom,"zetra:serial",null),id=string(custom,"zetra:item_id",null);
        if(serial!=null)value.serials.add(serial);if(id!=null)value.custom.add(id);
        CompoundTag publicBukkit=compound(custom,"PublicBukkitValues");if(publicBukkit!=null){
            serial=string(publicBukkit,"zetra:serial",null);id=string(publicBukkit,"zetra:item_id",null);
            if(serial!=null)value.serials.add(serial);if(id!=null)value.custom.add(id);
        }
    }
    private CompoundTag compound(CompoundTag c,String key){Tag<?> t=c.get(key);return t instanceof CompoundTag v?v:null;}
    private String string(CompoundTag c,String key,String fallback){Tag<?> t=c.get(key);return t instanceof StringTag s?s.getValue():fallback;}
    private int number(CompoundTag c,String key,int fallback){Tag<?> t=c.get(key);return t instanceof NumberTag<?> n?n.asInt():fallback;}
    private UUID uuid(File file)throws IOException{String name=file.getName();if(name.endsWith(".dat"))name=name.substring(0,name.length()-4);try{return UUID.fromString(name);}catch(IllegalArgumentException e){throw new IOException("UUID invalido: "+name,e);}}
    private String hash(byte[] bytes){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}catch(NoSuchAlgorithmException e){throw new IllegalStateException(e);}}
    public record RawSlot(int slot,CompoundTag item){}
    public record Snapshot(UUID uuid,long mtime,String hash,List<RawSlot> inventory,List<RawSlot> ender,int dataVersion,List<ItemAggregate> inventoryIndex,List<ItemAggregate> enderIndex){}
    private static final class Counter{int value;}
    private static final class Mutable{long direct,nested;final HashSet<String>serials=new HashSet<>(),custom=new HashSet<>();}
}
