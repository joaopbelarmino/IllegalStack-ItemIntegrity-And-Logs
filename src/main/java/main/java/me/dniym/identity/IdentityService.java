package main.java.me.dniym.identity;

import io.papermc.paper.persistence.PersistentDataContainerView;
import main.java.me.dniym.identity.config.ItemIntegrityConfig;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.block.TileState;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

import java.security.SecureRandom;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * IdentityService — mecânico e "burro" de propósito: sabe gerar o formato
 * do Item ID, e ler/escrever as PDC keys. NÃO decide QUANDO ou POR QUE
 * atribuir uma identidade - isso é responsabilidade de quem chama
 * (MigrationService nesta etapa; futuros listeners de craft/anvil/etc
 * depois).
 *
 * Formato do id (aprovado):
 *   ZI-YYYYMMDDTHHmm-<world>-x<X>-y<Y>-z<Z>-<24 hex chars>
 * Sem localização confiável:
 *   ZI-YYYYMMDDTHHmm-unknown-xNA-yNA-zNA-<24 hex chars>
 *
 * Nunca depender de parsing do id pra recuperar dados - todos os campos
 * estruturados (registeredAt, world, x/y/z, origin) ficam gravados em PDC
 * keys próprias, lidos diretamente por readIdentity().
 */
public final class IdentityService {

    private static final String NAMESPACE = "zetra";
    private static final NamespacedKey KEY_ITEM_ID = new NamespacedKey(NAMESPACE, "item_id");
    private static final NamespacedKey KEY_REGISTERED_AT = new NamespacedKey(NAMESPACE, "registered_at");
    private static final NamespacedKey KEY_ORIGIN = new NamespacedKey(NAMESPACE, "origin");
    private static final NamespacedKey KEY_WORLD = new NamespacedKey(NAMESPACE, "registered_world");
    private static final NamespacedKey KEY_X = new NamespacedKey(NAMESPACE, "registered_x");
    private static final NamespacedKey KEY_Y = new NamespacedKey(NAMESPACE, "registered_y");
    private static final NamespacedKey KEY_Z = new NamespacedKey(NAMESPACE, "registered_z");

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final DateTimeFormatter ID_TIMESTAMP_FORMAT =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmm", Locale.ROOT);

    private final ItemIntegrityConfig config;

    public IdentityService(ItemIntegrityConfig config) {
        this.config = config;
    }

    /** @return a identidade lida do item, ou null se ele não tiver zetra:item_id. */
    public ItemIdentity readIdentity(ItemStack stack) {
        if (stack == null || stack.getType().isAir()) {
            return null;
        }
        return readIdentity(stack.getPersistentDataContainer());
    }

    /** @return a identidade lida do bloco persistente, ou null se ele nÃ£o tiver zetra:item_id. */
    public ItemIdentity readIdentity(TileState tileState) {
        if (tileState == null) {
            return null;
        }
        return readIdentity(tileState.getPersistentDataContainer());
    }

    ItemIdentity readIdentity(PersistentDataContainerView pdc) {
        if (!pdc.has(KEY_ITEM_ID, PersistentDataType.STRING)) {
            return null;
        }

        String id = pdc.get(KEY_ITEM_ID, PersistentDataType.STRING);
        Long registeredAt = pdc.has(KEY_REGISTERED_AT, PersistentDataType.LONG)
                ? pdc.get(KEY_REGISTERED_AT, PersistentDataType.LONG) : null;
        String originRaw = pdc.has(KEY_ORIGIN, PersistentDataType.STRING)
                ? pdc.get(KEY_ORIGIN, PersistentDataType.STRING) : null;
        String world = pdc.has(KEY_WORLD, PersistentDataType.STRING)
                ? pdc.get(KEY_WORLD, PersistentDataType.STRING) : null;
        Integer x = pdc.has(KEY_X, PersistentDataType.INTEGER) ? pdc.get(KEY_X, PersistentDataType.INTEGER) : null;
        Integer y = pdc.has(KEY_Y, PersistentDataType.INTEGER) ? pdc.get(KEY_Y, PersistentDataType.INTEGER) : null;
        Integer z = pdc.has(KEY_Z, PersistentDataType.INTEGER) ? pdc.get(KEY_Z, PersistentDataType.INTEGER) : null;

        ItemOrigin origin = ItemOrigin.UNKNOWN;
        if (originRaw != null) {
            try {
                origin = ItemOrigin.valueOf(originRaw);
            } catch (IllegalArgumentException ignored) {
                // origem gravada por uma versão futura/desconhecida - mantém UNKNOWN em vez de quebrar
            }
        }

        return new ItemIdentity(id, registeredAt != null ? registeredAt : 0L, world, x, y, z, origin);
    }

    /**
     * Atribui uma nova identidade a um item. NÃO verifica se já existe uma
     * - isso é responsabilidade de quem chama (ex: MigrationService só
     * chama isso quando readIdentity já retornou null). Chamar isso num
     * item que já tem identidade violaria a regra de imutabilidade.
     */
    public ItemIdentity assignIdentity(ItemStack stack, Location location, ItemOrigin origin) {
        long now = System.currentTimeMillis();
        String worldName = (location != null && location.getWorld() != null) ? location.getWorld().getName() : null;
        Integer x = location != null ? location.getBlockX() : null;
        Integer y = location != null ? location.getBlockY() : null;
        Integer z = location != null ? location.getBlockZ() : null;

        String id = formatId(now, worldName, x, y, z);
        ItemIdentity identity = new ItemIdentity(id, now, worldName, x, y, z, origin);
        writeIdentity(stack, identity);
        return identity;
    }

    public boolean copyIdentity(ItemStack source, TileState target) {
        if (source == null || target == null) {
            return false;
        }
        return copyIdentity(source.getPersistentDataContainer(), target.getPersistentDataContainer());
    }

    public boolean copyIdentity(TileState source, ItemStack target) {
        if (source == null || target == null) {
            return false;
        }
        ItemMeta meta = target.getItemMeta();
        if (meta == null) {
            return false;
        }

        PersistentDataContainer sourcePdc = source.getPersistentDataContainer();
        boolean copied = copyIdentity(sourcePdc, meta.getPersistentDataContainer());
        if (meta instanceof BlockStateMeta blockStateMeta && blockStateMeta.getBlockState() instanceof TileState embeddedState) {
            copyIdentity(sourcePdc, embeddedState.getPersistentDataContainer());
            blockStateMeta.setBlockState(embeddedState);
        }
        target.setItemMeta(meta);
        return copied;
    }

    private void writeIdentity(ItemStack stack, ItemIdentity identity) {
        stack.editPersistentDataContainer(pdc -> writeIdentity(pdc, identity));
    }

    private void writeIdentity(PersistentDataContainer pdc, ItemIdentity identity) {
        pdc.set(KEY_ITEM_ID, PersistentDataType.STRING, identity.id());
        pdc.set(KEY_REGISTERED_AT, PersistentDataType.LONG, identity.registeredAtEpochMs());
        pdc.set(KEY_ORIGIN, PersistentDataType.STRING, identity.origin().name());
        if (identity.registeredWorldRaw() != null) {
            pdc.set(KEY_WORLD, PersistentDataType.STRING, identity.registeredWorldRaw());
        }
        if (identity.registeredX() != null) {
            pdc.set(KEY_X, PersistentDataType.INTEGER, identity.registeredX());
        }
        if (identity.registeredY() != null) {
            pdc.set(KEY_Y, PersistentDataType.INTEGER, identity.registeredY());
        }
        if (identity.registeredZ() != null) {
            pdc.set(KEY_Z, PersistentDataType.INTEGER, identity.registeredZ());
        }
    }

    private boolean copyIdentity(PersistentDataContainerView source, PersistentDataContainer target) {
        if (!source.has(KEY_ITEM_ID, PersistentDataType.STRING)) {
            return false;
        }
        copyString(source, target, KEY_ITEM_ID);
        copyLong(source, target, KEY_REGISTERED_AT);
        copyString(source, target, KEY_ORIGIN);
        copyString(source, target, KEY_WORLD);
        copyInteger(source, target, KEY_X);
        copyInteger(source, target, KEY_Y);
        copyInteger(source, target, KEY_Z);
        return true;
    }

    private void copyString(PersistentDataContainerView source, PersistentDataContainer target, NamespacedKey key) {
        if (source.has(key, PersistentDataType.STRING)) {
            target.set(key, PersistentDataType.STRING, source.get(key, PersistentDataType.STRING));
        } else {
            target.remove(key);
        }
    }

    private void copyLong(PersistentDataContainerView source, PersistentDataContainer target, NamespacedKey key) {
        if (source.has(key, PersistentDataType.LONG)) {
            target.set(key, PersistentDataType.LONG, source.get(key, PersistentDataType.LONG));
        } else {
            target.remove(key);
        }
    }

    private void copyInteger(PersistentDataContainerView source, PersistentDataContainer target, NamespacedKey key) {
        if (source.has(key, PersistentDataType.INTEGER)) {
            target.set(key, PersistentDataType.INTEGER, source.get(key, PersistentDataType.INTEGER));
        } else {
            target.remove(key);
        }
    }

    private String formatId(long epochMs, String worldName, Integer x, Integer y, Integer z) {
        ZoneId zone = config.timezone();
        String timestamp = Instant.ofEpochMilli(epochMs).atZone(zone).format(ID_TIMESTAMP_FORMAT);
        String worldPart = sanitizeWorldName(worldName);
        String coordsPart = (x != null && y != null && z != null)
                ? "x" + x + "-y" + y + "-z" + z
                : "xNA-yNA-zNA";
        String randomPart = randomHex(12);
        return "ZI-" + timestamp + "-" + worldPart + "-" + coordsPart + "-" + randomPart;
    }

    /** Sanitiza SÓ o necessário pro nome caber na string do id - o nome original fica intacto na PDC key separada. */
    private String sanitizeWorldName(String worldName) {
        if (worldName == null || worldName.isBlank()) {
            return "unknown";
        }
        StringBuilder sb = new StringBuilder(worldName.length());
        for (char c : worldName.toCharArray()) {
            if (Character.isLetterOrDigit(c) || c == '_' || c == '-') {
                sb.append(c);
            } else {
                sb.append('_');
            }
        }
        return sb.length() == 0 ? "unknown" : sb.toString();
    }

    private String randomHex(int byteLength) {
        byte[] bytes = new byte[byteLength];
        RANDOM.nextBytes(bytes);
        StringBuilder sb = new StringBuilder(byteLength * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
}
