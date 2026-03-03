package com.nullguard.callgraph.model;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
public final class GlobalCallGraph {
    private final Map<String, Set<String>> outgoing;
    private final Map<String, Set<String>> incoming;
    private final Set<String> externalNodes;
    public GlobalCallGraph(LinkedHashMap<String, LinkedHashSet<String>> outgoing,
                           LinkedHashMap<String, LinkedHashSet<String>> incoming,
                           Set<String> externalNodes) {
        this.outgoing = cloneMap(outgoing);
        this.incoming = cloneMap(incoming);
        this.externalNodes = Collections.unmodifiableSet(new LinkedHashSet<>(externalNodes));
    }
    private Map<String, Set<String>> cloneMap(Map<String, LinkedHashSet<String>> src) {
        Map<String, Set<String>> copy = new LinkedHashMap<>();
        for (Map.Entry<String, LinkedHashSet<String>> e : src.entrySet()) {
            copy.put(e.getKey(), Collections.unmodifiableSet(new LinkedHashSet<>(e.getValue())));
        }
        return Collections.unmodifiableMap(copy);
    }
    public Set<String> getCallees(String methodId) { return outgoing.getOrDefault(methodId, Collections.emptySet()); }
    public Set<String> getCallers(String methodId) { return incoming.getOrDefault(methodId, Collections.emptySet()); }
    public boolean isExternal(String methodId) { return externalNodes.contains(methodId); }
    public Map<String, Set<String>> getOutgoing() { return outgoing; }
    public Map<String, Set<String>> getIncoming() { return incoming; }
    public Set<String> getExternalNodes() { return externalNodes; }
}
