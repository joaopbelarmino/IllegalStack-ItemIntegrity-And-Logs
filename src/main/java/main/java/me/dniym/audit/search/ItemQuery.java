package main.java.me.dniym.audit.search;

import org.bukkit.Material;

import java.util.Locale;

public record ItemQuery(Type type,String value){
    public enum Type{MATERIAL,SERIAL,CUSTOM}
    public static ItemQuery parse(String raw){
        if(raw==null||raw.isBlank())throw new IllegalArgumentException("Informe um item");String value=raw.trim();
        int split=value.indexOf(':');if(split>0){String prefix=value.substring(0,split).toLowerCase(Locale.ROOT);String rest=value.substring(split+1);
            if(prefix.equals("serial"))return new ItemQuery(Type.SERIAL,rest);
            if(prefix.equals("custom"))return new ItemQuery(Type.CUSTOM,"custom:"+rest);
            if(prefix.equals("material"))value=rest;
            else if(prefix.equals("minecraft"))value=rest;
        }
        Material material=Material.matchMaterial(value);if(material==null)throw new IllegalArgumentException("Material desconhecido: "+value);
        return new ItemQuery(Type.MATERIAL,material.getKey().toString().toLowerCase(Locale.ROOT));
    }
}
