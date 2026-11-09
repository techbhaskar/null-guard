# Get started with NullGuard

NullGuard brings code safety, architectural risk, API exposure, dependency advisories, coverage context and memory insights into a shared review workflow. Inspect the evidence and prioritize fixes across your services and mixed-language repositories. See the [project overview and capability guides](../../docs/README.md) for feature scope and release boundaries.

NullGuard analyzes source and supporting artifacts without running your application. Java provides the full native analysis workflow; optional language packs are experimental and report-only.

Choose a Java build integration or the standalone CLI for a mixed-language repository. Start in report-only mode and review analysis completeness before interpreting a score or a quiet report.

## Choose the Java integration

Use **Java 21 or newer to run NullGuard** and keep your application's compilation
target unchanged. Both integrations below use published version **2.0.0** from
Maven Central; no GitHub token or NullGuard checkout is needed.

| Build | What to configure | Where |
| --- | --- | --- |
| Maven | `com.arinits.nullguard:nullguard-maven-plugin:2.0.0` | Optional plugin under `pom.xml` → `<build>` → `<plugins>`; the one-off command needs no edit |
| Gradle | Plugin ID `com.arinits.nullguard`, version `2.0.0` | Maven Central in `settings.gradle(.kts)` plugin resolution, plus the plugin in `build.gradle(.kts)` |

## Maven: one command or a build plugin

For a one-off scan, run this from the directory containing **your service's
`pom.xml`**. Maven downloads the plugin from Maven Central automatically; this
command needs no POM change:

```bash
mvn com.arinits.nullguard:nullguard-maven-plugin:2.0.0:analyze -Dnullguard.failBuild=false
```

To keep the configuration in the project, merge this plugin into
**`pom.xml` → `<build>` → `<plugins>`**:

```xml
<build>
    <plugins>
        <plugin>
            <groupId>com.arinits.nullguard</groupId>
            <artifactId>nullguard-maven-plugin</artifactId>
            <version>2.0.0</version>
            <configuration>
                <failBuild>false</failBuild>
            </configuration>
        </plugin>
    </plugins>
</build>
```

Run the same fully qualified Maven command after adding the configuration.
This is a **build plugin**, so place it under `<build><plugins>`, not application
`<dependencies>`. For automatic execution during `mvn verify`, see
[Add it to a service build](../../README.md#add-it-to-a-service-build).

Open `target/nullguard/nullguard-dashboard-latest.html`.

## Gradle: plugin resolution and application

For Kotlin DSL, merge this into **`settings.gradle.kts`**. The plugin is
published to Maven Central; `gradlePluginPortal()` alone will not resolve it.
Keep `pluginManagement` at the top of the settings file:

```kotlin
pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
    }
}
```

Then merge this into **`build.gradle.kts`** for the Java project being analyzed:

```kotlin
plugins {
    java
    id("com.arinits.nullguard") version "2.0.0"
}

repositories {
    mavenCentral()
}

nullguard {
    failBuild.set(false)
}
```

Keep existing Java, Java-library or Spring Boot plugins. If one already supplies
Java source sets, you do not need to add another `java` declaration. If dependency
repositories are centralized in settings, add Maven Central there instead of
adding a project-level `repositories` block.

For Groovy DSL (`settings.gradle` and `build.gradle`), use the
[complete Groovy examples](../../README.md#groovy-dsl-setup).

Run from your Gradle project:

```bash
./gradlew nullguard
```

On Windows, use `gradlew.bat nullguard`. No `implementation(...)` dependency or
manual CLI download is required: the plugin resolves
`com.arinits.nullguard:nullguard-cli:2.0.0` in a separate tool configuration.
Its Gradle plugin marker is
`com.arinits.nullguard:com.arinits.nullguard.gradle.plugin:2.0.0`; the `plugins`
block resolves this automatically.

Open `build/reports/nullguard/nullguard-dashboard-latest.html`.

Start in **report-only mode**. Choose one finding, inspect its source, and rerun
after any fix. Review individual findings even when the grade is A or no hotspots
are reported.


## Mixed languages with the CLI

Download the signed CLI for your platform from the [2.0.0 release](https://github.com/techbhaskar/null-guard/releases/tag/v2.0.0). Verify the checksum and signature using the release's instructions. You need Java 21 and the runtimes for the language packs you select.

```bash
# Installation is explicit; scanning does not install language packs.
java -jar nullguard-cli.jar packs install python ecmascript csharp
java -jar nullguard-cli.jar packs list

# Analyze the detected projects with the installed packs.
java -jar nullguard-cli.jar . \
  --languages=java,python,javascript,typescript,csharp \
  --output=nullguard-out
```

JS and TS share the `ecmascript` pack. Go and Rust packs are also available. Use `--languages=auto` to select every supported detected language. Each project is analyzed independently; NullGuard does not infer cross-language call chains.

Open `nullguard-out/nullguard-dashboard-latest.html`. The CLI also writes JSON schema 2.0 and SARIF reports. A complete scan can still contain findings and unresolved symbols. Exit code `3` means analysis failed or coverage was partial; retained results must not be mistaken for a clean scan.

Read the [language and runtime matrix](languages.md) and the [full multi-language guide](../../docs/multilang/README.md) before setting up packs. Non-Java packs remain experimental and report-only.

## Make the first fix

1. Check failed and unsupported file counts, unresolved calls, missing dependencies, and exhausted analysis budgets.
2. Select a finding and inspect its actual source and guard evidence. Java reports additionally show supported caller and endpoint exposure.
3. Preserve the intended behavior for missing values. Add a relevant regression test, apply a focused fix, and rerun tests and the same analysis.

An A grade or zero findings does not prove that a project is safe. See the [accuracy protocol](../../docs/evaluation/accuracy-protocol.md) for what the evidence supports.

## Use it through your agent

Install the free [NullGuard audit skill](agent-skill.md) to guide a coding agent through this workflow. It supports Java, Python, JavaScript, TypeScript and C#, including mixed repositories, and includes report-only GitHub Actions templates.
