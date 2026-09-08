package main.java.me.dniym.identity;

import java.util.Objects;

/**
 * Identidade permanente de um item. Uma vez atribuída (por
 * IdentityService#assignIdentity), é IMUTÁVEL - ninguém deve criar uma
 * segunda instância com o mesmo id representando dados diferentes.
 *
 * registeredAtEpochMs/World/X/Y/Z representam o REGISTRO da identidade no
 * sistema, não necessariamente a criação real do item - ver
 * ItemOrigin.LEGACY_IMPORT, onde o item pode existir há muito mais tempo
 * do que a data/local aqui presentes.
 */
public final class ItemIdentity {

    private final String id;
    private final long registeredAtEpochMs;
    private final String registeredWorldRaw;
    private final Integer registeredX;
    private final Integer registeredY;
    private final Integer registeredZ;
    private final ItemOrigin origin;

    public ItemIdentity(String id, long registeredAtEpochMs, String registeredWorldRaw,
                         Integer registeredX, Integer registeredY, Integer registeredZ, ItemOrigin origin) {
        this.id = Objects.requireNonNull(id, "id é obrigatório");
        this.registeredAtEpochMs = registeredAtEpochMs;
        this.registeredWorldRaw = registeredWorldRaw;
        this.registeredX = registeredX;
        this.registeredY = registeredY;
        this.registeredZ = registeredZ;
        this.origin = origin != null ? origin : ItemOrigin.UNKNOWN;
    }

    public String id() {
        return id;
    }

    public long registeredAtEpochMs() {
        return registeredAtEpochMs;
    }

    public String registeredWorldRaw() {
        return registeredWorldRaw;
    }

    public Integer registeredX() {
        return registeredX;
    }

    public Integer registeredY() {
        return registeredY;
    }

    public Integer registeredZ() {
        return registeredZ;
    }

    public ItemOrigin origin() {
        return origin;
    }

    @Override
    public String toString() {
        return id;
    }

    // Igualdade e hash SÓ pelo id - é a chave primária real da identidade.
    // Duas instâncias com o mesmo id sempre representam o mesmo objeto
    // lógico, mesmo que tenham sido lidas em momentos/lugares diferentes.
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ItemIdentity other)) return false;
        return id.equals(other.id);
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }
}
