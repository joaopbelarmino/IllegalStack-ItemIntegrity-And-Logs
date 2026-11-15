package main.java.me.dniym.audit.model;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

public record ContainerRef(UUID uuid, String locationKey, String world, int x, int y, int z,
                           UUID entityUuid, String type, int size) {
    private static final java.util.Map<String,UUID> CACHE=new java.util.LinkedHashMap<>(256,0.75f,true){
        @Override protected boolean removeEldestEntry(java.util.Map.Entry<String,UUID> e){return size()>8192;}
    };
    public static synchronized UUID stableUuid(String locationKey) {
        return CACHE.computeIfAbsent(locationKey,ContainerRef::calculate);
    }
    private static UUID calculate(String locationKey) {
        return UUID.nameUUIDFromBytes(("illegalstack:audit:" + locationKey).getBytes(StandardCharsets.UTF_8));
    }
}
