package main.java.me.dniym.audit.model;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

public record ContainerRef(UUID uuid, String locationKey, String world, int x, int y, int z,
                           UUID entityUuid, String type, int size) {
    public static UUID stableUuid(String locationKey) {
        return UUID.nameUUIDFromBytes(("illegalstack:audit:" + locationKey).getBytes(StandardCharsets.UTF_8));
    }
}
