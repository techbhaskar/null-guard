package com.nullguard.visualization.trace;

import com.nullguard.callgraph.model.GlobalCallGraph;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.Stack;

public final class ImpactTracer {

    public List<String> traceImpactPath(String methodId, GlobalCallGraph callGraph, int depthLimit) {
        List<String> result = new ArrayList<>();
        Set<String> visited = new LinkedHashSet<>();
        
        Stack<Frame> stack = new Stack<>();
        stack.push(new Frame(methodId, 0));
        
        while (!stack.isEmpty()) {
            Frame current = stack.pop();
            String currentMethod = current.methodId;
            int currentDepth = current.depth;
            
            if (currentDepth > depthLimit) {
                continue;
            }
            
            if (!visited.contains(currentMethod)) {
                visited.add(currentMethod);
                result.add(currentMethod);
                
                Set<String> callers = callGraph.getCallers(currentMethod);
                if (callers != null && currentDepth < depthLimit) {
                    List<String> sortedCallers = new ArrayList<>(callers);
                    Collections.sort(sortedCallers, Collections.reverseOrder());
                    for (String caller : sortedCallers) {
                        if (!visited.contains(caller)) {
                            stack.push(new Frame(caller, currentDepth + 1));
                        }
                    }
                }
            }
        }
        
        return result;
    }
    
    private static class Frame {
        String methodId;
        int depth;
        Frame(String methodId, int depth) {
            this.methodId = methodId;
            this.depth = depth;
        }
    }
}
