
# NullGuard Architecture

**Project:** NullGuard  
**Version:** v1.1  
**Status:** Architecture Frozen (Updated with API Flow Analysis)  
**Type:** Static Analysis Platform for Java Stability Intelligence

---

# Overview

NullGuard is an enterprise-grade static analysis engine designed to detect and quantify stability risks in Java codebases.

The platform performs:

- Nullability analysis
- Risk propagation
- API contract verification
- API flow mapping
- Stability scoring
- Context-aware fix suggestions
- Propagation visualization

Unlike traditional linters, NullGuard analyzes inter-procedural behavior across the entire codebase and understands API-to-method risk propagation.

---

# Architecture Philosophy

NullGuard follows a layered static analysis architecture.

Each layer produces structured intermediate models that feed the next stage of analysis.

Key principles:

- Deterministic analysis
- Immutable intermediate models
- Full project-level reasoning
- No runtime instrumentation
- Scalable to large enterprise codebases

---

# System Architecture

Layer 1 – AST Parser  
Layer 2 – Control Flow Graph Builder  
Layer 3 – Null State Propagation Engine  
Layer 4 – Global Call Graph Builder  
Layer 5 – Risk Propagation Engine  
Layer 6 – Stability Scoring Engine  
Layer 7 – API Contract & Flow Analyzer  
Layer 8 – Suggestion Engine  
Layer 9 – Propagation Visualizer  

Execution flow proceeds strictly top-down.

---

# Analysis Pipeline

Java Source Code  
→ AST Parser  
→ Control Flow Graph Builder  
→ Null-State Analysis  
→ Method Summary Extraction  
→ Global Call Graph  
→ Risk Propagation Engine  
→ Stability Scoring  
→ API Contract & Flow Analyzer  
→ Suggestion Engine  
→ Visualization Export  

---

# Core Intermediate Representation (IR)

ProjectModel
 ├── GlobalCallGraph
 ├── PropagationGraph
 ├── ApiFlowGraph
 ├── ProjectRiskSummary
 └── Modules
      └── ModuleModel
           └── PackageModel
                └── ClassModel
                     └── MethodModel
                          ├── ControlFlowModel
                          ├── NullAnalysisModel
                          ├── MethodSummary
                          ├── RiskModel
                          ├── ContractModel
                          ├── Suggestions
                          └── Issues

---

# API Flow Model

ApiEndpointModel
 ├── path
 ├── httpMethod
 ├── controllerMethod
 ├── callPaths[]
 ├── apiRiskScore
 ├── propagationDepth
 └── hotspotIndicators

---

# API Flow Mapping

Example:

GET /orders/{id}

OrderController.getOrder()
    ↓
OrderService.fetchOrder()
    ↓
OrderRepository.findById()

---

# API Contract Verification

Detects:

- nullable return values crossing API boundaries
- missing null guards
- nullable parameters without validation
- contract mismatches between caller and callee

---

# API Blast Radius Analysis

Example propagation:

API → Service → Util → SharedUtil

Shared utilities used by many APIs become architectural hotspots.

---

# Risk Propagation

Propagation uses:

- reverse call graph traversal
- decay factor
- fixpoint iteration

---

# API Risk Amplification

AdjustedRisk = BaseRisk × log(N + 1)

Where N = number of APIs using the method.

---

# Stability Scoring

FinalRisk =
IntrinsicRisk
+ PropagatedRisk
+ APIExposureWeight
+ ContractPenalty

---

# Architectural Hotspot Detection

Triggered when:

methodUsedByAPIs > threshold  
AND propagatedRisk > threshold

---

# Suggestion Engine

Ranking formula:

Score =
(estimatedRiskReduction × 0.5)
+ (confidence × 0.3)
+ (priorityWeight × 0.2)

---

# Visualization

Exports graphs in:

- JSON
- Graphviz DOT

Supports propagation heatmaps and API dependency graphs.

---

# Configuration

Example:

nullguard:
  decayFactor: 0.6
  failBuildIfStabilityBelow: 75
  highRiskThreshold: 70
  propagationDepthLimit: 10

---

# Module Structure

nullguard-core  
nullguard-analysis  
nullguard-callgraph  
nullguard-scoring  
nullguard-suggestions  
nullguard-visualization  
nullguard-cli  
nullguard-maven-plugin  

---

# Non-Goals (v1.0)

- runtime agents
- CVE scanning
- SBOM generation
- bytecode analysis
- Kotlin support
- auto-fix rewriting
- IDE plugin
- SaaS dashboard

---

# Scalability Targets

Designed to support:

- 50k+ methods
- parallel analysis
- deterministic results
- enterprise monorepos

---

# Definition of Done

v1.0 complete when:

- full analysis pipeline works
- stability index computed
- risk propagation verified
- contract violations detected
- suggestions generated
- visualization export available
- CLI operational
- Maven plugin operational
- test coverage > 80%

---

# Architecture Status

This architecture is frozen for NullGuard v1.1.
