# GitHub Releases and Packages

[Release v1.1.0](https://github.com/techbhaskar/null-guard/releases/tag/v1.1.0)
provides the standalone CLI JAR, its GPG signature and a SHA-256 checksum. GitHub
also supplies source archives for the tag.

All 13 release components are published as public GitHub Maven packages.
The Maven registry mirror is
`https://maven.pkg.github.com/techbhaskar/null-guard`. It uses the same
`com.arinits.nullguard` coordinates and version `1.1.0` as Maven Central, including
the Gradle implementation and plugin marker. Artifacts are mirrored from the
verified signed Central bundle, rather than rebuilt with different contents.

## Authentication

GitHub requires authentication to download Maven packages, including public
packages. Configure a personal access token (classic) with `read:packages` locally;
do not put it in your build or commit it. GitHub Actions can use its repository
`GITHUB_TOKEN`. See [GitHub's Maven registry documentation](https://docs.github.com/en/packages/working-with-a-github-packages-registry/working-with-the-apache-maven-registry).

## Maven

Add server `github` to private Maven settings:

```xml
<server>
  <id>github</id>
  <username>${env.GITHUB_PACKAGES_USERNAME}</username>
  <password>${env.GITHUB_PACKAGES_TOKEN}</password>
</server>
```

Configure the registry under both `<repositories>` and `<pluginRepositories>`
when resolving the analyzer as a Maven plugin. Each entry needs id `github` and
URL `https://maven.pkg.github.com/techbhaskar/null-guard`. Keep Maven Central for
third-party dependencies.

```sh
mvn com.arinits.nullguard:nullguard-maven-plugin:1.1.0:analyze
```

## Gradle

Add a Maven repository with credentials under both `pluginManagement.repositories`
in settings and the normal `repositories` block in the build:

```kotlin
maven {
    url = uri("https://maven.pkg.github.com/techbhaskar/null-guard")
    credentials {
        username = providers.environmentVariable("GITHUB_PACKAGES_USERNAME").orNull
        password = providers.environmentVariable("GITHUB_PACKAGES_TOKEN").orNull
    }
}
```

Keep `mavenCentral()` in both places for third-party dependencies, then apply
`id("com.arinits.nullguard") version "1.1.0"`. The plugin resolves its CLI tool
through the normal repository block, so configuring only plugin repositories is
insufficient. Run `./gradlew nullguard` as described in the main README.
