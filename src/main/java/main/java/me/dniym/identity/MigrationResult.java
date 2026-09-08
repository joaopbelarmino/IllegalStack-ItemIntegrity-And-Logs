package main.java.me.dniym.identity;

/** freshlyAssigned=true significa que essa foi a primeira vez que este item recebeu identidade (LEGACY_IMPORT ou outra origem futura). */
public record MigrationResult(ItemIdentity identity, boolean freshlyAssigned) {
}
