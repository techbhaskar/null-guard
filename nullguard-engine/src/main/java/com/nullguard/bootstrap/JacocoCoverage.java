package com.nullguard.bootstrap;

import java.nio.file.*;
import java.util.*;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.*;

/** Imports coverage produced by tests; never executes tests or infers coverage from static analysis. */
public final class JacocoCoverage {
    private JacocoCoverage() {}
    public static Map<String, Object> read(Path root, Path explicit) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "NOT_AVAILABLE");
        result.put("reason", "No JaCoCo XML report found. Run instrumented tests and supply --jacoco-report=<jacoco.xml>.");
        Path file = explicit;
        if (file == null) {
            for (String candidate : List.of("target/site/jacoco-aggregate/jacoco.xml", "target/site/jacoco/jacoco.xml", "build/reports/jacoco/test/jacocoTestReport.xml")) {
                Path path = root.resolve(candidate);
                if (Files.isRegularFile(path)) { file = path; break; }
            }
        }
        if (file == null) return result;
        result.put("reportPath", file.toAbsolutePath().normalize().toString());
        try {
            var factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            var builder = factory.newDocumentBuilder();
            builder.setErrorHandler(new org.xml.sax.helpers.DefaultHandler() {
                @Override public void fatalError(org.xml.sax.SAXParseException e) throws org.xml.sax.SAXException { throw e; }
            });
            Element report = builder.parse(file.toFile()).getDocumentElement();
            if (!report.getTagName().equals("report")) throw new IllegalArgumentException("Expected JaCoCo report root");
            Map<String, Object> totals = counters(report);
            if (totals.isEmpty()) throw new IllegalArgumentException("Report has no coverage counters");
            List<Map<String, Object>> sources = new ArrayList<>();
            NodeList packages = report.getElementsByTagName("package");
            for (int i = 0; i < packages.getLength(); i++) {
                Element pkg = (Element) packages.item(i);
                for (Node n = pkg.getFirstChild(); n != null; n = n.getNextSibling()) {
                    if (!(n instanceof Element src) || !src.getTagName().equals("sourcefile")) continue;
                    List<Integer> missed = new ArrayList<>(), partial = new ArrayList<>();
                    NodeList lines = src.getElementsByTagName("line");
                    for (int j = 0; j < lines.getLength(); j++) {
                        Element line = (Element) lines.item(j);
                        long mi = number(line, "mi"), ci = number(line, "ci");
                        if (mi > 0) (ci == 0 ? missed : partial).add(Math.toIntExact(number(line, "nr")));
                    }
                    sources.add(Map.of("path", pkg.getAttribute("name") + "/" + src.getAttribute("name"),
                            "counters", counters(src), "missedLines", missed, "partialLines", partial));
                }
            }
            result.put("status", "AVAILABLE");
            result.put("reason", "Imported test coverage. Report freshness and correspondence to this source revision are not verified. Partial lines count as covered in JaCoCo line coverage.");
            result.put("modifiedAt", Files.getLastModifiedTime(file).toInstant().toString());
            result.put("counters", totals);
            result.put("sources", sources);
        } catch (Exception e) {
            result.put("status", "INVALID");
            result.put("reason", "Cannot import JaCoCo XML: " + e.getMessage());
        }
        return result;
    }
    private static long number(Element e, String key) {
        long value = Long.parseLong(e.getAttribute(key));
        if (value < 0) throw new IllegalArgumentException("Negative coverage counter");
        return value;
    }
    private static Map<String, Object> counters(Element parent) {
        Map<String, Object> values = new LinkedHashMap<>();
        for (Node n = parent.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n instanceof Element e && e.getTagName().equals("counter")) {
                long missed = number(e, "missed"), covered = number(e, "covered");
                long total = Math.addExact(missed, covered);
                Map<String, Object> c = new LinkedHashMap<>();
                c.put("missed", missed); c.put("covered", covered);
                c.put("percent", total == 0 ? null : 100.0 * covered / total);
                values.put(e.getAttribute("type"), c);
            }
        }
        return values;
    }
}
