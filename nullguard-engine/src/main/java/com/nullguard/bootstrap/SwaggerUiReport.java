package com.nullguard.bootstrap;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.Map;

/** Offline Swagger UI assets; no CDN, remote validator, credential persistence, or automatic API calls. */
final class SwaggerUiReport {
    private static final String WEBJAR = "/META-INF/resources/webjars/swagger-ui/5.33.2/";
    static void write(Path directory, VisualizationBundle bundle) throws IOException {
        Path assets = Files.createDirectories(directory.resolve("swagger-ui"));
        for (String asset : new String[]{"swagger-ui-bundle.js", "swagger-ui.css", "swagger-ui-bundle.js.LICENSE.txt"}) {
            try (InputStream input = SwaggerUiReport.class.getResourceAsStream(WEBJAR + asset)) {
                if (input == null) throw new IOException("Missing bundled Swagger UI asset: " + asset);
                Files.copy(input, assets.resolve(asset), StandardCopyOption.REPLACE_EXISTING);
            }
        }
        try (InputStream license = SwaggerUiReport.class.getResourceAsStream("swagger-ui-LICENSE")) {
            if (license == null) throw new IOException("Missing Swagger UI license");
            Files.copy(license, assets.resolve("LICENSE"), StandardCopyOption.REPLACE_EXISTING);
        }
        Files.writeString(assets.resolve("NOTICE.txt"), "Swagger UI 5.33.2, Copyright SmartBear Software. Apache License 2.0.\nhttps://github.com/swagger-api/swagger-ui\nhttps://www.apache.org/licenses/LICENSE-2.0\n", StandardCharsets.UTF_8);
        try (Writer writer = Files.newBufferedWriter(directory.resolve("nullguard-swagger.html"), StandardCharsets.UTF_8)) {
            writer.write("""
                <!doctype html><html lang="en"><head><meta charset="utf-8">
                <meta name="viewport" content="width=device-width,initial-scale=1">
                <meta name="referrer" content="no-referrer">
                <title>NullGuard Swagger UI</title><link rel="stylesheet" href="swagger-ui/swagger-ui.css">
                </head><body><p>NullGuard static API documentation. Live requests require explicit opt-in and server-side CORS.
                Credentials entered with Authorize stay in this page's memory only; never put secrets in report configuration.</p>
                <div id="swagger-ui"></div><script src="swagger-ui/swagger-ui-bundle.js"></script>
                <script>const spec =
                """);
            VisualizationBundle.writeValueForScript(bundle.getOpenApiSpec(), writer);
            writer.write("""
                ;SwaggerUIBundle({dom_id:'#swagger-ui',spec:spec,validatorUrl:null,
                persistAuthorization:false,queryConfigEnabled:false,withCredentials:false,
                supportedSubmitMethods:spec['x-nullguard-try-it-out'] ? ['get','put','post','delete','options','head','patch','trace'] : [],
                tryItOutEnabled:false,deepLinking:true});</script></body></html>
                """);
        }
    }
}
