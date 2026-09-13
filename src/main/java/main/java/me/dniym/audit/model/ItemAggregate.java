package main.java.me.dniym.audit.model;

import java.util.Set;

public record ItemAggregate(String itemKey, long direct, long nested, Set<String> serials, Set<String> customIds) {
    public long total() { return direct + nested; }
}
