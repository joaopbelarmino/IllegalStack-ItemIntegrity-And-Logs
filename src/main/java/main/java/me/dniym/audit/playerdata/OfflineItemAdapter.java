package main.java.me.dniym.audit.playerdata;

import net.querz.nbt.io.NBTOutputStream;
import net.querz.nbt.io.NamedTag;
import net.querz.nbt.tag.CompoundTag;
import org.bukkit.inventory.ItemStack;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

/** Conversion is invoked only on the server thread because Paper's registry is required. */
public final class OfflineItemAdapter {
    public ItemStack decode(CompoundTag source,int dataVersion)throws IOException{
        CompoundTag item=source.clone();
        if(!item.containsKey("DataVersion"))item.putInt("DataVersion",dataVersion);
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();
        try(NBTOutputStream out=new NBTOutputStream(bytes)){out.writeTag(new NamedTag("",item),512);}
        return ItemStack.deserializeBytes(bytes.toByteArray());
    }
}
