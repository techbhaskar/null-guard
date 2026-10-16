# NullGuard for Gradle

Release `1.1.0` passed Central validation and is publishing. Version 1.0.1
is already published. The setup below uses the new version once Central confirms
it. The integration resolves the CLI and its runtime dependencies separately.

## Install from Maven Central

Merge Maven Central into plugin resolution in `settings.gradle.kts`:

```kotlin
pluginManagement {
    repositories { mavenCentral(); gradlePluginPortal() }
}
```

Then in `build.gradle.kts`:

```kotlin
plugins {
    java
    id("com.arinits.nullguard") version "1.1.0"
}
repositories { mavenCentral() }
nullguard {
    failBuild.set(false)
    failThreshold.set("HIGH")
}
```

Groovy equivalents (`settings.gradle`, then `build.gradle`):

```groovy
pluginManagement {
    repositories { mavenCentral(); gradlePluginPortal() }
}
```

```groovy
plugins {
    id 'java'
    id 'com.arinits.nullguard' version '1.1.0'
}
repositories { mavenCentral() }
nullguard {
    failBuild.set(false)
    failThreshold.set('HIGH')
}
```

Run `./gradlew nullguard`. No Maven installation, CLI download, local repository
or NullGuard checkout is needed after publication. This release is published to
Maven Central, not the Gradle Plugin Portal, so the pluginManagement repository
entry is required.

Open `build/reports/nullguard/nullguard-dashboard-latest.html`. The integration
creates a searchable offline HTML report from the CLI JSON; JSON and SARIF are
in the same directory. Use Java 17 or newer. Existing Java-library/Spring Boot
plugins can supply the Java source sets; do not apply Java twice. Kotlin sources
are not analyzed by this integration.

## Development/composite builds

For an unreleased integration build, replace the settings plugin repository
block with `pluginManagement { includeBuild("/path/to/null-guard/nullguard-gradle-plugin") }`
and omit the version in the plugins DSL. Alternatively, run `./gradlew publishToMavenLocal`
in the integration directory and configure `mavenLocal()` for plugin resolution.

## Configuration

| Option | Default | Purpose |
| --- | --- | --- |
| `toolVersion` | `1.1.0` | CLI artifact version to resolve |
| `failBuild` | `false` | Enable the hotspot build gate |
| `failThreshold` | `CRITICAL` | LOW, MEDIUM/MODERATE, HIGH, CRITICAL |
| `runOnCheck` | `false` | Include analysis in `check` |
| `reportDirectory` | `build/reports/nullguard` | Per-project report location |
| `excludes` | empty | Ant patterns relative to Java source roots |
| `maxHeapSize` | `2g` | Analyzer JVM heap limit |

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

## Try a locally built analyzer

Run `mvn clean install` in NullGuard, make `mavenLocal()` available in the consuming
build and select the locally built analyzer version with `toolVersion`. Prefer
immutable Central releases for shared CI.

## Build and release the integration

```sh
cd nullguard-gradle-plugin
./gradlew check
./gradlew publishToMavenLocal
```

Local publication includes the implementation JAR and Gradle plugin marker.
It does not publish to Maven Central or the Gradle Plugin Portal. The Central release workflow separately stages and signs these Gradle publications
with `-PcentralRelease`, then bundles them with the Maven reactor publications.
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
- Gradle Plugin Portal discovery in addition to Maven Central installation.
- Kotlin-source analysis only after dedicated parser and semantic support exists.

Verified locally on JDK 17 with Gradle 8.14.4 and 9.7.1: all 8 tests pass,
including plugin validation. A separate Spring Boot REST guide Gradle project
also analyzed successfully (5 modeled methods, 2 findings using analyzer 1.0.0),
with JSON/SARIF/HTML output. The local Maven plugin marker/implementation can be
consumed through the normal versioned plugins DSL without a composite build.
CI additionally checks JDK 17/21 against both Gradle versions.
