package main.java.me.dniym.identity.presence;

import main.java.me.dniym.identity.ItemIdentity;

public record PresenceRecord(ItemIdentity identity, HolderRef holder, PresenceState state,
                              int presenceRevision, long lastConfirmedAtMs) {
}
