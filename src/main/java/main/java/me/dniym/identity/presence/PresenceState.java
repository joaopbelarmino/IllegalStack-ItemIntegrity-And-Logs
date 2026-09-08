package main.java.me.dniym.identity.presence;

public enum PresenceState {
    LIVE_CONFIRMED,
    OFFLINE_COMMITTED,
    PERSISTED_BLOCK,
    PERSISTED_CONTAINER,
    TERMINAL_DESTROYED,
    TERMINAL_CONSUMED,
    TERMINAL_DESPAWNED,
    TERMINAL_BROKEN,
    TERMINAL_MERGED,
    STALE,
    UNKNOWN,
    MISSING;

    public boolean isTerminal() {
        return name().startsWith("TERMINAL_");
    }
}
