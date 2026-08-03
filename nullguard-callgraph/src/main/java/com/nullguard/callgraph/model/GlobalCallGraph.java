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
    /**
     * Why each external node could not be resolved to a project method.
     *
     * <p>{@code ExternalReason} and {@code ExternalMethodNode} were declared but never
     * constructed: JDK calls, third-party calls and genuinely unresolvable in-project calls all
     * went into one undifferentiated string bucket, which made
     * {@code ProjectRiskSummary.totalExternalMethods} a count of resolver misses rather than a
     * count of external dependencies. Populated now, and empty for graphs built with the
     * 3-argument constructor.
     */
    private final Map<String, ExternalReason> externalReasons;

    public GlobalCallGraph(LinkedHashMap<String, LinkedHashSet<String>> outgoing,
                           LinkedHashMap<String, LinkedHashSet<String>> incoming,
                           Set<String> externalNodes) {
        this(outgoing, incoming, externalNodes, Map.of());
    }

    public GlobalCallGraph(LinkedHashMap<String, LinkedHashSet<String>> outgoing,
                           LinkedHashMap<String, LinkedHashSet<String>> incoming,
                           Set<String> externalNodes,
                           Map<String, ExternalReason> externalReasons) {
        this.outgoing = cloneMap(outgoing);
        this.incoming = cloneMap(incoming);
        this.externalNodes = Collections.unmodifiableSet(new LinkedHashSet<>(externalNodes));
        this.externalReasons = Collections.unmodifiableMap(new LinkedHashMap<>(externalReasons));
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

    /** @return why each external node is external; empty when the graph was built without reasons */
    public Map<String, ExternalReason> getExternalReasons() { return externalReasons; }

    /** @return the classification for one external node, or UNRESOLVED when unrecorded */
    public ExternalReason getExternalReason(String methodId) {
        return externalReasons.getOrDefault(methodId, ExternalReason.UNRESOLVED);
    }

    /** @return the external nodes as typed nodes rather than bare strings */
    public Set<ExternalMethodNode> getExternalMethodNodes() {
        LinkedHashSet<ExternalMethodNode> nodes = new LinkedHashSet<>();
        for (String id : externalNodes) {
            nodes.add(new ExternalMethodNode(id, getExternalReason(id)));
        }
        return Collections.unmodifiableSet(nodes);
    }

    /** @return count of external nodes carrying the given classification */
    public long countExternalByReason(ExternalReason reason) {
        return externalNodes.stream().filter(id -> getExternalReason(id) == reason).count();
    }
}
