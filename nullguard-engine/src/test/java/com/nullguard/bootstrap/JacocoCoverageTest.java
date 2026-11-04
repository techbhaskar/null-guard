package com.nullguard.bootstrap;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class JacocoCoverageTest {
    @TempDir Path root;
    @Test void absentIsNotZeroAndInvalidIsVisible() throws Exception {
        assertEquals("NOT_AVAILABLE", JacocoCoverage.read(root, null).get("status"));
        Path report = root.resolve("broken.xml");
        Files.writeString(report, "<invalid");
        assertEquals("INVALID", JacocoCoverage.read(root, report).get("status"));
        Files.writeString(report, "<report/>");
        assertEquals("INVALID", JacocoCoverage.read(root, report).get("status"));
        Files.writeString(report, "<other/>");
        assertEquals("INVALID", JacocoCoverage.read(root, report).get("status"));
    }
    @Test void importsAggregateWithoutDoubleCountingAndAllowsStandardDoctype() throws Exception {
        Path report = root.resolve("target/site/jacoco-aggregate/jacoco.xml");
        Files.createDirectories(report.getParent());
        Files.writeString(report, """
            <!DOCTYPE report PUBLIC "-//JACOCO//DTD Report 1.1//EN" "report.dtd">
            <report name="test"><group name="module"><package name="a"><sourcefile name="A.java">
            <line nr="1" mi="0" ci="2"/><line nr="2" mi="2" ci="1"/><line nr="3" mi="2" ci="0"/>
            <counter type="LINE" missed="1" covered="2"/></sourcefile></package>
            <counter type="LINE" missed="1" covered="2"/></group>
            <counter type="LINE" missed="1" covered="2"/><counter type="BRANCH" missed="0" covered="0"/></report>
            """);
        var result = JacocoCoverage.read(root, null);
        assertEquals("AVAILABLE", result.get("status"));
        var counters = (Map<?,?>) result.get("counters");
        assertEquals(2L, ((Map<?,?>)counters.get("LINE")).get("covered"));
        assertNull(((Map<?,?>)counters.get("BRANCH")).get("percent"));
        var sources = (List<?>) result.get("sources");
        var source = (Map<?,?>) sources.get(0);
        assertEquals(List.of(3), source.get("missedLines"));
        assertEquals(List.of(2), source.get("partialLines"));
    }
    @Test void externalEntitiesCannotReadLocalFiles() throws Exception {
        Path secret = root.resolve("secret"); Files.writeString(secret, "PRIVATE_CONTENT");
        Path report = root.resolve("external.xml");
        Files.writeString(report, "<!DOCTYPE report [<!ENTITY secret SYSTEM '"+secret.toUri()+"'>]><report name='x'><package name='p'><sourcefile name='s'>&secret;</sourcefile></package><counter type='LINE' missed='0' covered='1'/></report>");
        assertFalse(JacocoCoverage.read(root, report).toString().contains("PRIVATE_CONTENT"));
        Files.writeString(report, "<report><counter type='LINE' missed='-1' covered='1'/></report>");
        assertEquals("INVALID", JacocoCoverage.read(root, report).get("status"));
    }
}
