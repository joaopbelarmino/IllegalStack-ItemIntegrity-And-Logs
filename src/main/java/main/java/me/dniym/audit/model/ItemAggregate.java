package main.java.me.dniym.audit.model;

import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

public record ItemAggregate(String itemKey, long direct, long nested,
                            Map<String,Integer> serials, Map<String,Integer> customIds) {
    public ItemAggregate { serials=Map.copyOf(serials);customIds=Map.copyOf(customIds); }
    public ItemAggregate(String key,long direct,long nested,Set<String> serials,Set<String> custom) {
        this(key,direct,nested,serials.stream().collect(Collectors.toMap(v->v,v->1)),
                custom.stream().collect(Collectors.toMap(v->v,v->1)));
    }
}
