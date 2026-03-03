package com.nullguard.cli;

import com.nullguard.bootstrap.EngineBootstrap;
import com.nullguard.bootstrap.FinalAnalysisResult;
import com.nullguard.bootstrap.NullGuardConfig;
import com.nullguard.analysis.model.ArchitecturalHotspot;
import com.nullguard.suggestions.model.Suggestion;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * NullGuardCliApplication – standalone CLI entry point for the NullGuard engine.
 *
 * <h3>Usage</h3>
 * <pre>
 *   java -jar nullguard-cli.jar &lt;source-path&gt; [options]
 *
 *   Options:
 *     --fail-build                  Fail with exit code 1 if threshold is breached
 *     --fail-threshold=&lt;SEVERITY&gt;   CRITICAL | HIGH | MODERATE | LOW  (default: CRITICAL)
 *     --decay-factor=&lt;float&gt;        Risk propagation decay factor      (default: 0.85)
 *     --ext-penalty=&lt;float&gt;         External call penalty multiplier   (default: 1.2)
 *     --convergence=&lt;float&gt;         Fixpoint convergence threshold     (default: 0.001)
 *     --max-iterations=&lt;int&gt;        Maximum fixpoint iterations        (default: 100)
 *     --high-risk-threshold=&lt;int&gt;   High-risk score threshold 0-100   (default: 70)
 *     --output=&lt;dir&gt;                Output directory for reports       (default: ./nullguard-out)
 * </pre>
 *
 * <h3>Exit codes</h3>
 * <ul>
 *   <li>0 – analysis completed successfully (and no fail-policy breach)</li>
 *   <li>1 – hotspot severity ≥ failThreshold and {@code --fail-build} is set</li>
 *   <li>2 – bad arguments / source path not found</li>
 *   <li>3 – analysis error / exception</li>
 * </ul>
 *
 * <p>This class performs NO analysis logic. It only:
 * <ol>
 *   <li>Parses CLI args</li>
 *   <li>Builds {@link NullGuardConfig}</li>
 *   <li>Delegates to {@link EngineBootstrap}</li>
 *   <li>Prints a summary</li>
 *   <li>Applies hotspot fail policy</li>
 * </ol>
 */
public final class NullGuardCliApplication {

    // ── Exit codes ────────────────────────────────────────────────────────────
    private static final int EXIT_OK              = 0;
    private static final int EXIT_FAIL_POLICY     = 1;
    private static final int EXIT_BAD_ARGS        = 2;
    private static final int EXIT_ANALYSIS_ERROR  = 3;

    // ── Main ─────────────────────────────────────────────────────────────────

    public static void main(String[] args) {
        System.exit(new NullGuardCliApplication().run(args));
    }

    /**
     * Core execution logic (non-static so it can be tested without System.exit).
     *
     * @param args CLI arguments
     * @return exit code
     */
    public int run(String[] args) {
        if (args.length == 0) {
            printUsage();
            return EXIT_BAD_ARGS;
        }

        // ── Argument parsing ──────────────────────────────────────────────────
        Path sourcePath         = null;
        boolean failBuild       = false;
        String  failThreshold   = "CRITICAL";
        double  decayFactor     = 0.85;
        double  extPenalty      = 1.2;
        double  convergence     = 0.001;
        int     maxIterations   = 100;
        int     highRiskThresh  = 70;
        Path    outputDir       = Paths.get("nullguard-out");

        for (String arg : args) {
            if (arg.startsWith("--")) {
                if (arg.equals("--fail-build")) {
                    failBuild = true;
                } else if (arg.startsWith("--fail-threshold=")) {
                    failThreshold = arg.substring("--fail-threshold=".length()).toUpperCase();
                } else if (arg.startsWith("--decay-factor=")) {
                    decayFactor = Double.parseDouble(arg.substring("--decay-factor=".length()));
                } else if (arg.startsWith("--ext-penalty=")) {
                    extPenalty = Double.parseDouble(arg.substring("--ext-penalty=".length()));
                } else if (arg.startsWith("--convergence=")) {
                    convergence = Double.parseDouble(arg.substring("--convergence=".length()));
                } else if (arg.startsWith("--max-iterations=")) {
                    maxIterations = Integer.parseInt(arg.substring("--max-iterations=".length()));
                } else if (arg.startsWith("--high-risk-threshold=")) {
                    highRiskThresh = Integer.parseInt(arg.substring("--high-risk-threshold=".length()));
                } else if (arg.startsWith("--output=")) {
                    outputDir = Paths.get(arg.substring("--output=".length()));
                } else {
                    System.err.println("[NullGuard] Unknown option: " + arg);
                    return EXIT_BAD_ARGS;
                }
            } else {
                // First positional argument is the source path
                if (sourcePath == null) {
                    sourcePath = Paths.get(arg);
                }
            }
        }

        if (sourcePath == null) {
            System.err.println("[NullGuard] ERROR: source path is required as first argument.");
            printUsage();
            return EXIT_BAD_ARGS;
        }

        if (!Files.exists(sourcePath)) {
            System.err.println("[NullGuard] ERROR: source path does not exist: " + sourcePath.toAbsolutePath());
            return EXIT_BAD_ARGS;
        }

        // ── Build configuration ───────────────────────────────────────────────
        NullGuardConfig config = NullGuardConfig.defaults()
                .failBuild(failBuild)
                .failThreshold(failThreshold)
                .scoringDecayFactor(decayFactor)
                .externalPenaltyMultiplier(extPenalty)
                .convergenceThreshold(convergence)
                .maxScoringIterations(maxIterations)
                .highRiskThreshold(highRiskThresh)
                .outputDirectory(outputDir)
                .build();

        // ── Execute via bootstrap ─────────────────────────────────────────────
        try {
            System.out.println("-------------------------------------------------------");
            System.out.println(" NullGuard: Stability Intelligence Engine (v1.0)");
            System.out.println("-------------------------------------------------------");
            System.out.println("Source path : " + sourcePath.toAbsolutePath());
            System.out.println("Output dir  : " + outputDir.toAbsolutePath());
            System.out.println();

            EngineBootstrap bootstrap = new EngineBootstrap(config);
            FinalAnalysisResult result = bootstrap.run(sourcePath);

            printSummary(result);

            // ── Write JSON report ─────────────────────────────────────────────
            writeReports(result, outputDir);

            // ── Apply fail policy ─────────────────────────────────────────────
            return applyFailPolicy(result, failBuild, failThreshold);

        } catch (Exception e) {
            System.err.println("[NullGuard] ANALYSIS FAILED: " + e.getMessage());
            e.printStackTrace(System.err);
            return EXIT_ANALYSIS_ERROR;
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private void printSummary(FinalAnalysisResult result) {
        var s = result.getRiskSummary();
        System.out.println("┌─────────────────────────────────────────┐");
        System.out.println("│  NullGuard Analysis Summary              │");
        System.out.println("├─────────────────────────────────────────┤");
        System.out.printf ("│  Grade           : %-20s │%n", s.getGrade());
        System.out.printf ("│  Stability Index : %-20.2f │%n", s.getStabilityIndex());
        System.out.printf ("│  Total Methods   : %-20d │%n", s.getTotalMethods());
        System.out.printf ("│  High Risk Meth. : %-20d │%n", s.getHighRiskMethods());
        System.out.printf ("│  Max Risk        : %-20.2f │%n", s.getMaxRisk());
        System.out.printf ("│  Blast Radius    : %-20.2f │%n", s.getBlastRadiusScore());
        System.out.printf ("│  Hotspots        : %-20d │%n", result.getHotspots().size());
        System.out.printf ("│  Suggestions     : %-20d │%n", result.getSuggestions().size());
        System.out.printf ("│  Pipeline time   : %-18dms │%n", result.getTiming().getTotalPipelineDurationMs());
        System.out.println("└─────────────────────────────────────────┘");

        if (!result.getHotspots().isEmpty()) {
            System.out.println("\nTop Architectural Hotspots:");
            result.getHotspots().stream().limit(10).forEach(h ->
                System.out.printf("  [%-8s] %s  (score=%.2f)%n",
                        h.getSeverity(), h.getMethodRef(), h.getHotspotScore()));
        }

        if (!result.getSuggestions().isEmpty()) {
            System.out.println("\nTop Suggestions:");
            result.getSuggestions().stream().limit(5).forEach(sug ->
                System.out.println("  → [" + sug.getSuggestionType() + "] " + sug.getMessage()));
        }

        System.out.println("\nStep Timings:");
        result.getTiming().getStepDurationsMs().forEach((step, ms) ->
            System.out.printf("  %-28s %4d ms%n", step, ms));
        System.out.println();
    }

    private void writeReports(FinalAnalysisResult result, Path outputDir) {
        try {
            if (!Files.exists(outputDir)) {
                Files.createDirectories(outputDir);
            }
            Path jsonFile = outputDir.resolve("nullguard-report-latest.json");
            Files.writeString(jsonFile, result.getVisualizations().getJsonGraph());
            Path dotFile  = outputDir.resolve("nullguard-graph-latest.dot");
            Files.writeString(dotFile, result.getVisualizations().getDotGraph());
            System.out.println("Reports written to: " + outputDir.toAbsolutePath());
        } catch (Exception e) {
            System.err.println("[NullGuard] WARNING: Failed to write reports – " + e.getMessage());
        }
    }

    /**
     * Returns exit code 1 if fail policy is triggered, 0 otherwise.
     */
    private int applyFailPolicy(FinalAnalysisResult result, boolean failBuild, String failThreshold) {
        if (failBuild && result.hasHotspotsAtOrAboveSeverity(failThreshold)) {
            System.err.println("[NullGuard] BUILD FAILED – hotspot severity ≥ " + failThreshold +
                               " detected (" + result.getHotspots().size() + " hotspots).");
            return EXIT_FAIL_POLICY;
        }
        System.out.println("[NullGuard] Analysis passed.");
        return EXIT_OK;
    }

    private void printUsage() {
        System.out.println("Usage: nullguard-cli.jar <source-path> [options]");
        System.out.println("Options:");
        System.out.println("  --fail-build                  Fail with exit 1 if threshold breached");
        System.out.println("  --fail-threshold=<SEVERITY>   CRITICAL|HIGH|MODERATE|LOW  (default: CRITICAL)");
        System.out.println("  --decay-factor=<float>        Risk propagation decay       (default: 0.85)");
        System.out.println("  --ext-penalty=<float>         External call penalty        (default: 1.2)");
        System.out.println("  --convergence=<float>         Convergence threshold        (default: 0.001)");
        System.out.println("  --max-iterations=<int>        Max fixpoint iterations      (default: 100)");
        System.out.println("  --high-risk-threshold=<int>   High-risk score (0-100)      (default: 70)");
        System.out.println("  --output=<dir>                Output directory             (default: ./nullguard-out)");
    }
}
