package main.java.me.dniym.identity.presence;

import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

/**
 * Logical holder for an item identity. Slot changes inside the same logical
 * holder are not ownership changes.
 */
public sealed interface HolderRef {

    HolderType type();

    String describe();

    record PlayerHolder(UUID playerId, String playerName, Integer slot) implements HolderRef {
        public PlayerHolder {
            Objects.requireNonNull(playerId, "playerId is required for PlayerHolder");
        }

        @Override
        public HolderType type() {
            return HolderType.PLAYER;
        }

        @Override
        public String describe() {
            return "player:" + (playerName != null ? playerName : playerId)
                    + (slot != null ? ":slot" + slot : "");
        }
    }

    record ItemEntityHolder(UUID entityUuid, Integer runtimeEntityId, String world,
                            Integer x, Integer y, Integer z) implements HolderRef {
        public ItemEntityHolder {
            Objects.requireNonNull(entityUuid, "entityUuid is required for ItemEntityHolder");
        }

        @Override
        public HolderType type() {
            return HolderType.ITEM_ENTITY;
        }

        @Override
        public String describe() {
            String loc = (world != null && x != null) ? (world + ":" + x + "," + y + "," + z) : "unknown";
            return "item_entity:" + entityUuid + ":" + loc;
        }
    }

    record ContainerHolder(HolderType type, String world, int x, int y, int z, Integer slot) implements HolderRef {
        public ContainerHolder {
            Objects.requireNonNull(type, "type is required for ContainerHolder");
            Objects.requireNonNull(world, "world is required for ContainerHolder");
            if (type == HolderType.PLAYER || type == HolderType.ITEM_ENTITY
                    || type == HolderType.VIRTUAL_INVENTORY || type == HolderType.EXTERNAL_PLUGIN
                    || type == HolderType.ITEM_FRAME || type == HolderType.ARMOR_STAND
                    || type == HolderType.STORAGE_MINECART) {
                throw new IllegalArgumentException("ContainerHolder cannot use type " + type);
            }
        }

        @Override
        public String describe() {
            return type.name().toLowerCase(Locale.ROOT) + ":" + world + ":" + x + "," + y + "," + z
                    + (slot != null ? ":slot" + slot : "");
        }
    }

    record EntityHolder(HolderType type, UUID entityUuid, String world, Integer x, Integer y, Integer z,
                        String detail) implements HolderRef {
        public EntityHolder {
            Objects.requireNonNull(type, "type is required for EntityHolder");
            Objects.requireNonNull(entityUuid, "entityUuid is required for EntityHolder");
            if (type != HolderType.ITEM_FRAME && type != HolderType.ARMOR_STAND && type != HolderType.STORAGE_MINECART) {
                throw new IllegalArgumentException("EntityHolder cannot use type " + type);
            }
        }

        @Override
        public String describe() {
            String loc = (world != null && x != null) ? (world + ":" + x + "," + y + "," + z) : "unknown";
            return type.name().toLowerCase(Locale.ROOT) + ":" + entityUuid + ":" + loc
                    + (detail != null ? ":" + detail : "");
        }
    }

    record VirtualHolder(HolderType type, String label, String viewerOrOwner, Integer slot) implements HolderRef {
        public VirtualHolder {
            Objects.requireNonNull(type, "type is required for VirtualHolder");
            Objects.requireNonNull(label, "label is required for VirtualHolder");
            if (type != HolderType.VIRTUAL_INVENTORY && type != HolderType.EXTERNAL_PLUGIN) {
                throw new IllegalArgumentException("VirtualHolder cannot use type " + type);
            }
        }

        @Override
        public String describe() {
            return type.name().toLowerCase(Locale.ROOT) + ":" + normalizeVirtualLabel(label)
                    + (viewerOrOwner != null ? ":" + viewerOrOwner : "")
                    + (slot != null ? ":slot" + slot : "");
        }
    }

    static boolean sameOwner(HolderRef a, HolderRef b) {
        if (a == null || b == null) {
            return false;
        }
        if (a instanceof PlayerHolder pa && b instanceof PlayerHolder pb) {
            return pa.playerId().equals(pb.playerId());
        }
        if (a instanceof ItemEntityHolder ea && b instanceof ItemEntityHolder eb) {
            return ea.entityUuid().equals(eb.entityUuid());
        }
        if (a instanceof ContainerHolder ca && b instanceof ContainerHolder cb) {
            return ca.type() == cb.type() && ca.world().equals(cb.world())
                    && ca.x() == cb.x() && ca.y() == cb.y() && ca.z() == cb.z();
        }
        if (a instanceof EntityHolder ea && b instanceof EntityHolder eb) {
            return ea.type() == eb.type() && ea.entityUuid().equals(eb.entityUuid());
        }
        if (a instanceof VirtualHolder va && b instanceof VirtualHolder vb) {
            return va.type() == vb.type()
                    && normalizeVirtualLabel(va.label()).equals(normalizeVirtualLabel(vb.label()))
                    && Objects.equals(va.viewerOrOwner(), vb.viewerOrOwner());
        }
        return false;
    }

    /** Stable owner key used for conflict deduplication; slots are physical detail, not custody. */
    static String logicalOwnerKey(HolderRef holder) {
        if (holder instanceof PlayerHolder player) {
            return "player:" + player.playerId();
        }
        if (holder instanceof ItemEntityHolder entity) {
            return "item_entity:" + entity.entityUuid();
        }
        if (holder instanceof ContainerHolder container) {
            return container.type().name().toLowerCase(Locale.ROOT) + ":" + container.world() + ":"
                    + container.x() + "," + container.y() + "," + container.z();
        }
        if (holder instanceof EntityHolder entity) {
            return entity.type().name().toLowerCase(Locale.ROOT) + ":" + entity.entityUuid();
        }
        if (holder instanceof VirtualHolder virtual) {
            return virtual.type().name().toLowerCase(Locale.ROOT) + ":"
                    + normalizeVirtualLabel(virtual.label()) + ":"
                    + Objects.toString(virtual.viewerOrOwner(), "unknown");
        }
        return holder != null ? holder.type().name().toLowerCase(Locale.ROOT) : "unknown";
    }

    static String normalizeVirtualLabel(String label) {
        String normalized = label == null ? "unknown" : label.toLowerCase(Locale.ROOT);
        for (String suffix : new String[]{":inventory_click", ":inventory_drag", ":inventory_close"}) {
            if (normalized.endsWith(suffix)) {
                return normalized.substring(0, normalized.length() - suffix.length());
            }
        }
        return normalized;
    }
}
