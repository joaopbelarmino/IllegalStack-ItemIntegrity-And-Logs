package main.java.me.dniym.audit.model;

public record SearchResult(String targetType, String targetId, String displayName, String source,
                           String itemKey, long direct, long nested, long total, int score,
                           String detail) {
}
