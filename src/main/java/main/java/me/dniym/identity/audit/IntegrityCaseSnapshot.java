package main.java.me.dniym.identity.audit;

public record IntegrityCaseSnapshot(String caseId, String incidentKey, long createdAtEpochMs, String mode, String decision,
                                    String action, String confidence, String reason, String itemId,
                                    String material, String canonicalSummary, String conflictingSummary,
                                    String conflictingItemSummary, byte[] conflictingItemSnapshot) {
}
