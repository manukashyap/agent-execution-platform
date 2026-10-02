package com.conversive.aep.definition.validation;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Dependency graph over the well-formed nodes (unique ids). Edges to unknown ids are ignored here;
 * they are reported as {@code MISSING_DEPENDENCY} separately.
 */
final class DefinitionGraph {

    private final Map<String, List<String>> deps;
    private final List<String> order;

    /** @param deps node id to its resolved dependencies, in submitted node order */
    DefinitionGraph(Map<String, List<String>> deps) {
        this.deps = new LinkedHashMap<>(deps);
        this.order = topologicalOrder();
    }

    List<String> dependencies(String id) {
        return deps.getOrDefault(id, List.of());
    }

    boolean contains(String id) {
        return deps.containsKey(id);
    }

    boolean acyclic() {
        return order.size() == deps.size();
    }

    /** Ids in a dependency-respecting order; empty when the graph has a cycle. */
    Optional<List<String>> order() {
        return acyclic() ? Optional.of(List.copyOf(order)) : Optional.empty();
    }

    /** Ids that are on, or downstream of, a cycle (those Kahn's algorithm could not place). */
    List<String> unordered() {
        Set<String> placed = Set.copyOf(order);
        return deps.keySet().stream().filter(id -> !placed.contains(id)).toList();
    }

    /** Longest-path depth of every node (roots are 0); only meaningful when acyclic. */
    Map<String, Integer> levels() {
        Map<String, Integer> level = new HashMap<>();
        for (String id : order) {
            int depth = dependencies(id).stream().filter(level::containsKey)
                    .mapToInt(dep -> level.get(dep) + 1).max().orElse(0);
            level.put(id, depth);
        }
        return level;
    }

    private List<String> topologicalOrder() {
        Map<String, Integer> indegree = new HashMap<>();
        Map<String, List<String>> dependents = new HashMap<>();
        deps.forEach((id, ds) -> {
            List<String> known = ds.stream().filter(deps::containsKey).distinct().toList();
            indegree.put(id, known.size());
            known.forEach(dep -> dependents.computeIfAbsent(dep, k -> new ArrayList<>()).add(id));
        });
        Deque<String> ready = new ArrayDeque<>();
        deps.keySet().stream().filter(id -> indegree.get(id) == 0).forEach(ready::add);
        List<String> sorted = new ArrayList<>();
        while (!ready.isEmpty()) {
            String id = ready.poll();
            sorted.add(id);
            for (String next : dependents.getOrDefault(id, List.of())) {
                if (indegree.merge(next, -1, Integer::sum) == 0) {
                    ready.add(next);
                }
            }
        }
        return sorted;
    }
}
