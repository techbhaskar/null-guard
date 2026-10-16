package com.arinits.nullguard.gradle;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class HtmlReportTest {
    @TempDir Path directory;
    @Test void findingTextCannotCloseTheDataScriptOrInjectHtml() throws Exception {
        Files.writeString(directory.resolve("nullguard-report-latest.json"),
                "{\"findings\":[{\"message\":\"</script><img src=x onerror=alert(1)>\"}]}");
        HtmlReport.write(directory);
        String html = Files.readString(directory.resolve("nullguard-dashboard-latest.html"));
        assertFalse(html.contains("<img src=x"));
        assertTrue(html.contains("\\u003c/script\\u003e"));
        assertTrue(html.contains("cell.textContent=value"));
        assertFalse(html.contains("https://"));
    }
}
