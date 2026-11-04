package com.nullguard.analysis.hotspot;

import com.nullguard.analysis.config.AnalysisConfig;
import com.nullguard.analysis.model.ArchitecturalHotspot;
import com.nullguard.core.model.*;
import com.nullguard.core.spi.MethodAnalysisArtifacts.AdjustedRiskSource;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class HotspotDetectorTest {
    private final HotspotDetector detector = new HotspotDetector(new AnalysisConfig(10,.85,1,60,2));

    private MethodModel method(String name, Double adjustedRisk, Integer reach) {
        MethodModel method=MethodModel.builder().methodName(name).signature(name+"()").build();
        if (adjustedRisk!=null) method.setAdjustedRiskModel(new AdjustedRiskSource() {
            public double getAdjustedRisk() { return adjustedRisk; }
            public double getIntrinsicRisk() { throw new AssertionError("Use composed adjusted risk"); }
            public double getPropagatedRisk() { throw new AssertionError("Use composed adjusted risk"); }
            public double getApiExposureWeight() { throw new AssertionError("Use composed adjusted risk"); }
            public double getContractPenalty() { throw new AssertionError("Use composed adjusted risk"); }
        });
        if (reach!=null) method.setReachData(()->reach);
        return method;
    }
    private ModuleModel module(String module, String pkg, String type, MethodModel... methods) {
        var cls=ClassModel.builder().className(type);
        for (var method:methods) cls.addMethod(method);
        return ModuleModel.builder().moduleName(module)
                .addPackage(PackageModel.builder().packageName(pkg).addClass(cls.build()).build()).build();
    }
    private ProjectModel project(ModuleModel... modules) {
        var builder=ProjectModel.builder().projectName("fixture");
        for (var module:modules) builder.addModule(module);
        return builder.build();
    }

    @Test void candidatesRequireBothConfiguredThresholdsIncludingEquality() {
        assertTrue(detector.isHotspotCandidate(60,2));
        assertTrue(detector.isHotspotCandidate(90,10));
        assertFalse(detector.isHotspotCandidate(59.999,2));
        assertFalse(detector.isHotspotCandidate(60,1));
        assertFalse(detector.isHotspotCandidate(0,0));
        var custom=new HotspotDetector(new AnalysisConfig(10,.85,1,25,4));
        assertTrue(custom.isHotspotCandidate(25,4));
        assertFalse(custom.isHotspotCandidate(24,4));
        assertFalse(custom.isHotspotCandidate(25,3));
    }

    @Test void scoresUseNaturalLogAndGlobalStabilityUsesSeventyThirtyWeights() {
        assertEquals(60*Math.log(3),detector.computeHotspotScore(60,2),1e-12);
        assertEquals(0,detector.computeHotspotScore(80,0),1e-12);
        assertEquals(0,detector.computeHotspotScore(0,10),1e-12);
        assertEquals(62,detector.computeGlobalStability(80,20),1e-12);
        assertEquals(38,detector.computeGlobalStability(20,80),1e-12);
        assertEquals(100,detector.computeGlobalStability(100,100),1e-12);
    }

    @Test void severitiesChangeExactlyAtFortySixtyAndEighty() {
        assertEquals("LOW",detector.determineSeverity(0));
        assertEquals("LOW",detector.determineSeverity(39.999));
        assertEquals("MODERATE",detector.determineSeverity(40));
        assertEquals("MODERATE",detector.determineSeverity(59.999));
        assertEquals("HIGH",detector.determineSeverity(60));
        assertEquals("HIGH",detector.determineSeverity(79.999));
        assertEquals("CRITICAL",detector.determineSeverity(80));
        assertEquals("CRITICAL",detector.determineSeverity(150));
    }

    @Test void detectsAcrossModulesAndSortsEqualScoresByCanonicalMethodId() {
        var p=project(
            module("second","b","Service",method("same",60.0,2),method("highest",90.0,4)),
            module("first","a","Service",method("same",60.0,2),method("belowRisk",59.0,20),method("belowReach",99.0,1)));
        detector.detect(p);
        var hotspots=detector.getArchitecturalHotspots();
        assertEquals(List.of("b.Service#highest()","a.Service#same()","b.Service#same()"),
                hotspots.stream().map(ArchitecturalHotspot::getMethodRef).toList());
        assertEquals(90*Math.log(5),hotspots.get(0).getHotspotScore(),1e-12);
        assertEquals("CRITICAL",hotspots.get(0).getSeverity());
        assertEquals("HIGH",hotspots.get(1).getSeverity());
        assertThrows(UnsupportedOperationException.class,hotspots::clear);
        detector.detect(p);
        assertEquals(3,detector.getArchitecturalHotspots().size(),"Repeated detection must not duplicate findings");
    }

    @Test void absentArtifactsAreNotHotspotsAndEmptyRunClearsPreviousResults() {
        detector.detect(project(module("m","p","Service",method("hot",80.0,5))));
        assertEquals(1,detector.getArchitecturalHotspots().size());
        detector.detect(project(module("m","p","Service",method("noRisk",null,5),method("noReach",80.0,null),method("neither",null,null))));
        assertTrue(detector.getArchitecturalHotspots().isEmpty());
        detector.detect(project());
        assertTrue(detector.getArchitecturalHotspots().isEmpty());
    }
}
