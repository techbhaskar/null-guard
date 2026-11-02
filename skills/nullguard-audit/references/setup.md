# Setup and native commands

## Java: published 2.0.0

The analyzer needs **Java 21**, even when the application targets an older Java release. Check `java -version` and the JVM reported by the selected build tool. Prefer project wrappers. Keep the application's source target unchanged.

Maven: run in the directory containing `pom.xml`:

```sh
./mvnw com.arinits.nullguard:nullguard-maven-plugin:2.0.0:analyze -Dnullguard.failBuild=false
```

Use `mvn` if there is no wrapper. Reports normally appear under each module's `target/nullguard/`. Compile first when bytecode/SAST coverage is wanted; a source-only audit does not imply bytecode analysis ran. The fully qualified goal needs no POM edit.

Gradle: merge into existing configuration; do not replace blocks. Add `mavenCentral()` to `pluginManagement.repositories` in settings, not only dependency repositories. Kotlin DSL:

```kotlin
// settings.gradle.kts
pluginManagement { repositories { mavenCentral(); gradlePluginPortal() } }
// build.gradle.kts
plugins { id("com.arinits.nullguard") version "2.0.0" }
repositories { mavenCentral() }
nullguard { toolVersion.set("2.0.0"); failBuild.set(false) }
tasks.named<com.arinits.nullguard.gradle.NullGuardTask>("nullguard") {
    javaLauncher.set(javaToolchains.launcherFor {
        languageVersion.set(JavaLanguageVersion.of(21))
    })
}
```

The existing Java or Java-library plugin must already supply Java source sets; apply it if the project has none. Groovy equivalents:

```groovy
// settings.gradle
pluginManagement { repositories { mavenCentral(); gradlePluginPortal() } }
// build.gradle
plugins { id 'com.arinits.nullguard' version '2.0.0' }
repositories { mavenCentral() }
nullguard { toolVersion.set('2.0.0'); failBuild.set(false) }
tasks.named('nullguard') {
    javaLauncher.set(javaToolchains.launcherFor {
        languageVersion.set(JavaLanguageVersion.of(21))
    })
}
```

Run `./gradlew nullguard`; use `gradlew.bat` on Windows. The helper enforces report-only for its audit invocation; direct native invocation honors the configured `failBuild` value. Compile/test with the existing project tasks separately. Native reports are per module at `build/reports/nullguard/`; configuration may change that directory. A no-source Gradle task can produce no report. Do not call that a successful clean audit.

## Python, JS/TS and C#/.NET packs

The tested source distribution identifies the packs as **2.0.0-preview.1**. Java's published 2.0.0 coordinates do not prove signed pack publication. Obtain a compatible executable CLI and signed archives from a verified release, or explicitly build and qualify a multi-language source distribution using its own build instructions. A Java-only checkout is insufficient. Do not download a guessed executable classifier or disable signature checks.

| Scope | CLI language | Pack ID | Additional runtime |
| --- | --- | --- | --- |
| Python | `python` | `python` | Python 3.11+ |
| JS/JSX | `javascript` | `ecmascript` | Node 22+ |
| TS/TSX | `typescript` | `ecmascript` | Node 22+ |
| SDK-style C# | `csharp` | `csharp` | .NET SDK 10 |

Check runtime versions and the CLI's installed manifests:

```sh
java -jar /absolute/nullguard-cli.jar packs list
```

When installation is requested and trusted local assets are available:

```sh
java -jar /absolute/nullguard-cli.jar packs install python --from=/absolute/python-pack.zip
```

The archive needs adjacent `.sha256` and `.asc` files, GPG/GPGV, and the signing key trusted by the CLI. Repeat for needed pack IDs; JS and TS share one pack. `NULLGUARD_PACK_HOME` selects pack storage. Do not replace or remove installed packs without need.

Native scan:

```sh
java -jar /absolute/nullguard-cli.jar /absolute/project --languages=java,python,javascript,typescript,csharp --output=/absolute/report-directory
```

The native CLI's `--languages=auto` includes every supported detected language, potentially Go/Rust too. The skill helper's `--languages auto` expands to Java, Python, JavaScript, TypeScript and C# explicitly, keeping its default scope to these five languages. Supply `--classpath` for Java types when needed. Each detected project is independent.

Scanning does not restore dependencies, import Python application modules, run tests, expand generators or install packs. Missing NuGet restore assets or generated code and unresolved JS/TS configurations are coverage gaps. If preparation is needed, explain it and use the application's established restore/build process within the requested scope.

Pack boundaries: Python dynamic attributes/decorators are not executed; JS/TS respects project configuration and excludes Angular HTML templates/browser lifecycle analysis; C# requires SDK-style projects and does not cover VB/F# or local/lambda CFG bodies. Use original report capabilities for the exact installed version.
