package main.java.me.dniym.identity.audit;

/** O que pode ser colocado na AuditQueue - sempre um snapshot imutável, nunca um objeto Bukkit vivo. */
public sealed interface AuditTask {

    record RegisterItem(ItemSnapshot snapshot) implements AuditTask {
    }

    record RecordEvent(ItemEventSnapshot snapshot) implements AuditTask {
    }

    /** Só atualiza `presence` (o estado atual) - não gera linha em `item_events`. Ver PresenceUpdateSnapshot. */
    record UpdatePresence(PresenceUpdateSnapshot snapshot) implements AuditTask {
    }

    record PersistCase(IntegrityCaseSnapshot snapshot) implements AuditTask {
    }
}
