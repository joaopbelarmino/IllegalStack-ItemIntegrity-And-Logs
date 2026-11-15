package main.java.me.dniym.audit.model;

import java.util.List;
import java.util.UUID;

public record ContainerSnapshot(ContainerRef ref, byte[] inventoryBytes, String contentHash,
                                List<ItemAggregate> items, UUID lastPlayerUuid, String lastPlayerName,
                                AuditCause cause, long capturedAt, boolean destroyed, UUID destroyedBy,
                                int interactionCount) {
}
