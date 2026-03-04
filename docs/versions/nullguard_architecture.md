# NullGuard Architecture

**Project:** NullGuard\
**Version:** v1.0\
**Status:** Architecture Frozen\
**Type:** Static Analysis Platform for Java Stability Intelligence

------------------------------------------------------------------------

# Overview

NullGuard is an enterprise-grade static analysis engine designed to
detect and quantify stability risks in Java codebases.

The platform performs:

-   Nullability analysis
-   Risk propagation
-   API contract verification
-   Stability scoring
-   Context-aware fix suggestions
-   Propagation visualization

Unlike traditional linters, NullGuard analyzes inter-procedural behavior
across the entire codebase.

------------------------------------------------------------------------

# Architecture Philosophy

NullGuard follows a layered static analysis architecture.

Each layer produces structured intermediate models that feed the next
stage of analysis.

Key principles:

-   Deterministic analysis
-   Immutable intermediate models
-   Full project-level reasoning
-   No runtime instrumentation
-   Scalable to large enterprise codebases

------------------------------------------------------------------------

# System Architecture

Layer 1 -- AST Parser\
Layer 2 -- Control Flow Graph Builder\
Layer 3 -- Null State Propagation Engine\
Layer 4 -- Call Graph Builder\
Layer 5 -- Risk Propagation Engine\
Layer 6 -- Stability Scoring Engine\
Layer 7 -- API Contract Analyzer\
Layer 8 -- Suggestion Engine\
Layer 9 -- Propagation Visualizer

Execution flow proceeds strictly top-down.

------------------------------------------------------------------------

# Analysis Pipeline

Java Source Code\
→ AST Parser\
→ Control Flow Graph Builder\
→ Null-State Analysis\
→ Method Summary Extraction\
→ Global Call Graph\
→ Risk Propagation Engine\
→ Stability Scoring\
→ API Contract Analyzer\
→ Suggestion Engine\
→ Visualization Export

------------------------------------------------------------------------

# Core Intermediate Representation (IR)

ProjectModel ├── GlobalCallGraph ├── PropagationGraph ├──
ProjectRiskSummary └── Modules └── ModuleModel └── PackageModel └──
ClassModel └── MethodModel ├── ControlFlowModel ├── NullAnalysisModel
├── MethodSummary ├── RiskModel ├── ContractModel ├── Suggestions └──
Issues

------------------------------------------------------------------------

# Core Components

## Control Flow Model

Represents the method execution graph.

Contains: - Basic blocks - Control edges - Entry / Exit nodes

Used for null state propagation and dereference detection.

------------------------------------------------------------------------

## Null Analysis Model

Tracks nullability states across control flow.

States:

NULL\
NON_NULL\
UNKNOWN

The engine uses a forward data-flow algorithm with fixpoint iteration.

------------------------------------------------------------------------

## Method Summary

Each method generates an immutable summary containing:

-   return nullability
-   parameter contracts
-   null propagation behavior
-   intrinsic risk profile

------------------------------------------------------------------------

## Global Call Graph

Represents method invocation relationships across the project.

Features:

-   directed graph
-   outgoing adjacency list
-   incoming adjacency list

Supports interface and inheritance resolution.

------------------------------------------------------------------------

## Risk Propagation Engine

Risks discovered in deep utility methods propagate upward through
callers.

Propagation uses:

-   reverse call graph traversal
-   decay factor
-   fixpoint iteration

Example:

DeepUtility → Service → Controller → API

------------------------------------------------------------------------

# Stability Scoring

Produces a project stability index (0--100).

Metrics include:

-   intrinsic risks
-   propagated risks
-   contract violations
-   dereference counts

Output:

-   StabilityIndex
-   Grade (A--F)
-   HighRiskMethodRatio
-   ContractViolationCount
-   BlastRadiusScore

------------------------------------------------------------------------

# API Contract Analyzer

Detects:

-   nullable return violations
-   parameter contract mismatches
-   boundary amplification risks

------------------------------------------------------------------------

# Suggestion Engine

Generates context-aware fixes.

Ranking formula:

Score = (estimatedRiskReduction × 0.5) + (confidence × 0.3) +
(priorityWeight × 0.2)

------------------------------------------------------------------------

# Visualization

Exports graphs for visualization.

Supported formats:

-   JSON
-   Graphviz DOT

Supports heatmaps and blast-radius tracing.

------------------------------------------------------------------------

# Configuration

Example configuration:

nullguard: decayFactor: 0.6 failBuildIfStabilityBelow: 75
highRiskThreshold: 70 propagationDepthLimit: 10

------------------------------------------------------------------------

# Module Structure

nullguard-core\
nullguard-analysis\
nullguard-callgraph\
nullguard-scoring\
nullguard-suggestions\
nullguard-visualization\
nullguard-cli\
nullguard-maven-plugin

------------------------------------------------------------------------

# Non-Goals (v1.0)

-   runtime agents
-   CVE scanning
-   SBOM generation
-   bytecode analysis
-   Kotlin support
-   auto-fix rewriting
-   IDE plugin
-   SaaS dashboard

------------------------------------------------------------------------

# Scalability Targets

Designed to support:

-   50k+ methods
-   parallel analysis
-   deterministic results
-   enterprise monorepos

------------------------------------------------------------------------

# Definition of Done

v1.0 complete when:

-   full analysis pipeline works
-   stability index computed
-   risk propagation verified
-   contract violations detected
-   suggestions generated
-   visualization export available
-   CLI operational
-   Maven plugin operational
-   test coverage \> 80%

------------------------------------------------------------------------

# Architecture Status

This architecture is frozen for NullGuard v1.0.
