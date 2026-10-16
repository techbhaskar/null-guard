package com.nullguard.plugin;

import com.nullguard.analysis.model.ArchitecturalHotspot;
import com.nullguard.bootstrap.EngineBootstrap;
import com.nullguard.bootstrap.FinalAnalysisResult;
import com.nullguard.bootstrap.HtmlDashboardRenderer;
import com.nullguard.bootstrap.NullGuardConfig;
import com.nullguard.suggestions.model.Suggestion;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.project.MavenProject;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.SimpleDateFormat;
import java.util.Date;

/**
 * NullGuardMojo – Maven plugin entry point for the NullGuard Stability Intelligence Engine.
 *
 * <p><strong>Bound phase:</strong> {@code verify}
 * <p><strong>Goal:</strong> {@code analyze}
 *
 * <h3>Responsibilities</h3>
 * <ul>
 *   <li>Bind to the {@code verify} lifecycle phase</li>
 *   <li>Load project source path from Maven project model</li>
 *   <li>Build a {@link NullGuardConfig} from plugin parameters</li>
 *   <li>Delegate to {@link EngineBootstrap} – NO pipeline logic here</li>
 *   <li>Write HTML dashboard + JSON report to {@code target/nullguard/}</li>
 *   <li>Apply hotspot fail policy and exit with correct Maven failure code</li>
 * </ul>
 */
@Mojo(name = "analyze", defaultPhase = LifecyclePhase.VERIFY,
        requiresDependencyResolution = ResolutionScope.COMPILE)
public class NullGuardMojo extends AbstractMojo {

    // ── Maven-injected parameters ─────────────────────────────────────────────

    @Parameter(defaultValue = "${project}", required = true, readonly = true)
    private MavenProject project;

    /** Hotspot severity level at which the build fails (CRITICAL, HIGH, MODERATE, LOW). */
    @Parameter(property = "nullguard.failThreshold", defaultValue = "CRITICAL")
    private String failThreshold;

    /** Whether to fail the build when the threshold is breached. */
    @Parameter(property = "nullguard.failBuild", defaultValue = "false")
    private boolean failBuild;

    /** Scoring: decay factor for risk propagation (0–1). */
    @Parameter(property = "nullguard.scoringDecayFactor", defaultValue = "0.85")
    private double scoringDecayFactor;

    /** Scoring: penalty multiplier for external method calls. */
    @Parameter(property = "nullguard.externalPenaltyMultiplier", defaultValue = "1.2")
    private double externalPenaltyMultiplier;

    /** Scoring: convergence threshold for fixpoint iteration. */
    @Parameter(property = "nullguard.convergenceThreshold", defaultValue = "0.001")
    private double convergenceThreshold;

    /** Scoring: maximum fixpoint iterations. */
    @Parameter(property = "nullguard.maxScoringIterations", defaultValue = "100")
    private int maxScoringIterations;

    /**
     * Scoring: risk score at or above which a method is classified as high-risk.
     *
     * <p>Default is 60 to match {@code RiskLevel.HIGH}'s lower bound. It was 70, which
     * contradicted the band used to colour the graph.
     */
    @Parameter(property = "nullguard.highRiskThreshold", defaultValue = "60")
    private int highRiskThreshold;

    // ── Mojo entry point ──────────────────────────────────────────────────────

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {

        getLog().info("-------------------------------------------------------");
        getLog().info(" NullGuard: Stability Intelligence Engine (v1.0)");
        getLog().info("-------------------------------------------------------");
        getLog().info("Analyzing project : " + project.getName());
        getLog().info("Source directory  : " + project.getBuild().getSourceDirectory());

        Path sourcePath = Paths.get(project.getBuild().getSourceDirectory());
        if (!Files.exists(sourcePath)) {
            getLog().info("Source directory does not exist – skipping analysis: " + sourcePath);
            return;
        }

        try {
            // ── 1. Build configuration ──────────────────────────────────────
            Path outputDir = Paths.get(project.getBuild().getDirectory(), "nullguard");
            NullGuardConfig config = NullGuardConfig.defaults()
                    .failBuild(failBuild)
                    .failThreshold(failThreshold)
                    .scoringDecayFactor(scoringDecayFactor)
                    .externalPenaltyMultiplier(externalPenaltyMultiplier)
                    .convergenceThreshold(convergenceThreshold)
                    .maxScoringIterations(maxScoringIterations)
                    .highRiskThreshold(highRiskThreshold)
                    .outputDirectory(outputDir)
                    .sourceRoots(project.getCompileSourceRoots().stream()
                            .map(Paths::get)
                            .toList())
                    .classpathEntries(project.getCompileClasspathElements().stream()
                            .map(Paths::get)
                            .toList())
                    .build();

            // ── 2. Run pipeline via bootstrap ───────────────────────────────
            getLog().info("Initialising NullGuard engine...");
            EngineBootstrap bootstrap = new EngineBootstrap(config);

            getLog().info("Running analysis pipeline...");
            FinalAnalysisResult result = bootstrap.run(sourcePath);

            // ── 3. Write outputs ────────────────────────────────────────────
            writeOutputs(result, outputDir);

            // ── 4. Print concise summary ────────────────────────────────────
            printSummary(result);

            // ── 5. Apply hotspot fail policy ────────────────────────────────
            applyFailPolicy(result);

        } catch (MojoFailureException policyFailure) {
            throw policyFailure;
        } catch (Exception e) {
            getLog().error("NullGuard analysis failed", e);
            throw new MojoExecutionException("NullGuard analysis failed: " + e.getMessage(), e);
        }

        getLog().info("NullGuard analysis completed successfully.");
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private void writeOutputs(FinalAnalysisResult result, Path outputDir) throws Exception {
        File dir = outputDir.toFile();
        if (!dir.exists()) {
            dir.mkdirs();
        }

        String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date());
        String jsonContent = result.getVisualizations().getJsonGraph();
        String dotContent  = result.getVisualizations().getDotGraph();
        Files.writeString(outputDir.resolve("nullguard-results.sarif"), result.getVisualizations().getSarif());

        // ── JSON ──────────────────────────────────────────────────────────────
        Path jsonFile = outputDir.resolve("nullguard-report-" + timestamp + ".json");
        Path jsonLatest = outputDir.resolve("nullguard-report-latest.json");
        Files.writeString(jsonFile, jsonContent);
        Files.writeString(jsonLatest, jsonContent);

        // ── DOT ───────────────────────────────────────────────────────────────
        Path dotFile  = outputDir.resolve("nullguard-graph-" + timestamp + ".dot");
        Path dotLatest = outputDir.resolve("nullguard-graph-latest.dot");
        Files.writeString(dotFile, dotContent);
        Files.writeString(dotLatest, dotContent);

        // ── HTML Dashboard ────────────────────────────────────────────────────
        String htmlContent = HtmlDashboardRenderer.render(result, timestamp, jsonContent, project.getName());
        Path htmlFile   = outputDir.resolve("nullguard-dashboard-" + timestamp + ".html");
        Path htmlLatest = outputDir.resolve("nullguard-dashboard-latest.html");
        Files.writeString(htmlFile, htmlContent);
        Files.writeString(htmlLatest, htmlContent);

        getLog().info("JSON report  → " + jsonLatest.toAbsolutePath());
        getLog().info("HTML dashboard → " + htmlLatest.toAbsolutePath());
    }

    private void printSummary(FinalAnalysisResult result) {
        var summary = result.getRiskSummary();
        getLog().info("┌─────────────────────────────────────────┐");
        getLog().info("│  NullGuard Analysis Summary              │");
        getLog().info("├─────────────────────────────────────────┤");
        if (!summary.isAvailable()) {
            // Nothing was scored. Warn instead of reporting a grade, so a broken analysis
            // cannot be mistaken for a passing build.
            getLog().warn("NullGuard produced no data: no methods were scored. This usually means "
                        + "sources failed to parse or no CFGs were built - it does NOT mean the "
                        + "project is risk-free.");
            getLog().info("└─────────────────────────────────────────┘");
            return;
        }
        getLog().info(String.format("│  Grade           : %-20s │", summary.getGrade()));
        getLog().info(String.format("│  Stability Index : %-20.2f │", summary.getStabilityIndex()));
        getLog().info(String.format("│  Total Methods   : %-20d │", summary.getTotalMethods()));
        getLog().info(String.format("│  High Risk Meth. : %-20d │", summary.getHighRiskMethods()));
        getLog().info(String.format("│  Hotspots        : %-20d │", result.getHotspots().size()));
        getLog().info(String.format("│  Suggestions     : %-20d │", result.getSuggestions().size()));
        getLog().info(String.format("│  Pipeline time   : %-18dms │", result.getTiming().getTotalPipelineDurationMs()));
        getLog().info("└─────────────────────────────────────────┘");

        if (!result.getHotspots().isEmpty()) {
            getLog().info("Top Architectural Hotspots:");
            result.getHotspots().stream().limit(5).forEach(h ->
                getLog().info(String.format("  [%s] %s (score=%.2f)",
                        h.getSeverity(), h.getMethodRef(), h.getHotspotScore())));
        }

        if (!result.getSuggestions().isEmpty()) {
            getLog().info("Top Suggestions:");
            result.getSuggestions().stream().limit(3).forEach(s ->
                getLog().info("  → " + s.getMessage()));
        }
    }

    /**
     * Fails the Maven build if {@code failBuild=true} AND the result contains
     * at least one hotspot at or above the configured {@code failThreshold} severity.
     *
     * <p>Exit rules:
     * <ul>
     *   <li>hotspot severity ≥ failThreshold AND failBuild=true → {@code MojoFailureException} (exit 1)</li>
     *   <li>otherwise → normal completion (exit 0)</li>
     * </ul>
     */
    private void applyFailPolicy(FinalAnalysisResult result) throws MojoFailureException {
        if (!failBuild) {
            return;
        }
        if (result.hasHotspotsAtOrAboveSeverity(failThreshold)) {
            String msg = String.format(
                    "NullGuard: build failed – hotspot(s) at or above severity '%s' detected. " +
                    "Total hotspots: %d. Set nullguard.failBuild=false to suppress.",
                    failThreshold, result.getHotspots().size());
            getLog().error(msg);
            throw new MojoFailureException(msg);
        }
    }

}
