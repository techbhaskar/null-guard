# NullGuard for Gradle

A reusable binary plugin for Java Gradle projects. It runs the standalone CLI
from Maven Central with the project's compile classpath, without adding
NullGuard to application dependencies or starting the application.

Plugin version: `1.0.1-SNAPSHOT` (local/composite build, not published).
Default analyzer version: published `1.0.0`. The plugin and analyzer versions
are independent. The new analyzer fixes require a locally installed
`1.0.1-SNAPSHOT` until a new analyzer release is published.

## Use now: locally installed plugin

`publishToMavenLocal` has been verified for this development build. To use it on
the same machine, merge these repositories into `pluginManagement` in
`settings.gradle.kts`:

```kotlin
pluginManagement {
    repositories { mavenLocal(); gradlePluginPortal(); mavenCentral() }
}
```

Then use `id("com.arinits.nullguard") version "1.0.1-SNAPSHOT"` alongside your
existing Java/Java-library plugins in `build.gradle.kts`. Keep `mavenCentral()`
available for normal dependency resolution, then run `./gradlew nullguard`.
This plugin version exists locally, not in public repositories. Teammates can
use the composite build below or build/publish the integration locally themselves.

## Alternative: composite build

Clone NullGuard and add the integration build to your application's settings.
Kotlin DSL (`settings.gradle.kts`):

```kotlin
pluginManagement {
    includeBuild("/path/to/null-guard/nullguard-gradle-plugin")
}
```

Groovy DSL (`settings.gradle`):

```groovy
pluginManagement {
    includeBuild('/path/to/null-guard/nullguard-gradle-plugin')
}
```

Add to `build.gradle.kts`:

```kotlin
plugins {
    java
    id("com.arinits.nullguard")
}
repositories { mavenCentral() }
nullguard {
    toolVersion.set("1.0.0")
    failBuild.set(false)
    failThreshold.set("HIGH")
}
```

Equivalent `build.gradle`:

```groovy
plugins {
    id 'java'
    id 'com.arinits.nullguard'
}
repositories { mavenCentral() }
nullguard {
    toolVersion.set('1.0.0')
    failBuild.set(false)
    failThreshold.set('HIGH')
}
```

Then run:

```sh
./gradlew nullguard
```

Open `build/reports/nullguard/nullguard-dashboard-latest.html`. The integration
generates this offline, searchable HTML report from the CLI JSON. JSON and SARIF
are in the same directory. The task uses the Java toolchain configured for the
application; use JDK 17 or newer. Java sources are analyzed, not Kotlin sources.
The plugin waits for the Java plugin rather than applying it implicitly.
For Java library/Spring Boot projects, their existing Java plugin is sufficient.

## Configuration

| Option | Default | Purpose |
| --- | --- | --- |
| `toolVersion` | `1.0.0` | CLI artifact version to resolve |
| `failBuild` | `false` | Enable the hotspot build gate |
| `failThreshold` | `CRITICAL` | LOW, MEDIUM/MODERATE, HIGH, CRITICAL |
| `runOnCheck` | `false` | Include analysis in `check` |
| `reportDirectory` | `build/reports/nullguard` | Per-project report location |
| `excludes` | empty | Ant patterns relative to Java source roots |
| `maxHeapSize` | `1g` | Analyzer JVM heap limit |

Kotlin DSL example:

```kotlin
nullguard {
    runOnCheck.set(true)
    failBuild.set(false) // Review findings before opting into enforcement.
    excludes.addAll("**/generated/**", "**/legacy/**")
    maxHeapSize.set("2g")
    reportDirectory.set(layout.buildDirectory.dir("reports/nullguard"))
}
```

Report-only mode suppresses hotspot policy failures, not analysis errors. The
hotspot gate does not fail on every individual null warning. An A grade or an
empty hotspot list does not establish that every method is null-safe.

`nullguardTool` resolves the normal CLI JAR and its runtime dependencies.
Gradle caches each dependency separately, allowing reuse between versions and
projects. The development analyzer no longer includes Maven runtime libraries. Application compile
dependencies are passed separately through `--classpath`. No Maven installation
or global CLI JAR is needed in consuming projects.

## Multiple roots and generated sources

All main Java source roots are staged together, preserving package-relative
paths. Identical relative paths across roots fail rather than silently overwriting
source. Reports use the staged root under `build/tmp/nullguard/sources` in source
URIs; relative package/file paths still correspond to the original roots.
Do not delete that staged tree while inspecting SARIF source locations.

Custom source roots declared in `sourceSets.main.java` are picked up automatically.
For a separate generator, wire its task explicitly and add its file tree:

```kotlin
tasks.named<com.arinits.nullguard.gradle.NullGuardTask>("nullguard") {
    dependsOn("generateJava")
    sourceFiles.from(fileTree(layout.buildDirectory.dir("generated/custom-java")))
}
```

No main Java inputs means `NO-SOURCE`. Generated sources requiring annotation
processing are not automatically produced by this task; run/wire their generator.
The task does not compile the current application. Compile dependencies on other
Gradle projects retain their normal Gradle build dependencies.

## Multi-project builds

Apply the plugin to each Java subproject you want analyzed, with repositories
available through your normal project/settings repository configuration.

```sh
./gradlew :access-core:nullguard
./gradlew nullguard
```

The first command analyzes one subproject. Gradle's unqualified task selection
runs matching `nullguard` tasks across subprojects. Reports remain per project;
this version does not produce a combined cross-module graph or dashboard.

## Try the development analyzer

From the NullGuard repository, run `mvn clean install`, then add `mavenLocal()`
to the consuming build's repositories and set `toolVersion` to `1.0.1-SNAPSHOT`.
For other developers/CI, prefer a published immutable version once available.

## Build and release the integration

```sh
cd nullguard-gradle-plugin
./gradlew check
./gradlew publishToMavenLocal
```

Local publication includes the implementation JAR and Gradle plugin marker.
It does not publish to Maven Central or the Gradle Plugin Portal. Until a release
of the integration itself is published, do not advertise a remote
`plugins { id("com.arinits.nullguard") version "…" }` install as available.
The integration is an independent Gradle build; Maven's reactor does not build it.

Reports contain timestamps and absolute source URIs, so remote build caching is
disabled. Gradle input/output tracking and configuration-cache reuse are supported.

## Verification and product follow-ups

Functional tests cover Groovy and Kotlin DSL, multiple roots, exclusions, compile
classpath forwarding, duplicate paths, per-subproject reports, no-source projects,
opt-in check wiring, policy failure propagation and configuration-cache reuse.
An integration test resolves the published 1.0.0 CLI from Maven Central and checks
that a seeded null dereference appears in SARIF. An HTML test covers unsafe
finding text without relying on a browser or remote assets.

Useful next capabilities, separate from this implementation:

- Baselines and explicit suppressions so teams can focus on new findings.
- Pull request annotations derived from SARIF, with confidence and source evidence.
- A combined multi-module report and source-root mapping for editor links.
- A published Gradle plugin release for installation without a composite build.
- Kotlin-source analysis only after dedicated parser and semantic support exists.

Verified locally on JDK 17 with Gradle 8.14.4 and 9.7.1: all 8 tests pass,
including plugin validation. A separate Spring Boot REST guide Gradle project
also analyzed successfully (5 modeled methods, 2 findings using analyzer 1.0.0),
with JSON/SARIF/HTML output. The local Maven plugin marker/implementation can be
consumed through the normal versioned plugins DSL without a composite build.
CI additionally checks JDK 17/21 against both Gradle versions.
