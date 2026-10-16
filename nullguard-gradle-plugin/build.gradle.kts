plugins {
    `java-gradle-plugin`
    `maven-publish`
}
group = "com.arinits.nullguard"
version = "1.0.1-SNAPSHOT"
repositories { mavenCentral() }
java {
    toolchain { languageVersion.set(JavaLanguageVersion.of(17)) }
    withSourcesJar()
    withJavadocJar()
}
gradlePlugin {
    plugins {
        create("nullguard") {
            id = "com.arinits.nullguard"
            implementationClass = "com.arinits.nullguard.gradle.NullGuardPlugin"
            displayName = "NullGuard Java null-safety analysis"
            description = "Analyze Java source with the NullGuard CLI and the Gradle compile classpath."
        }
    }
}
dependencies {
    testImplementation(gradleTestKit())
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.0")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.10.0")
}
tasks.test {
    useJUnitPlatform()
    systemProperty("test.gradle.version", providers.gradleProperty("testGradleVersion").getOrElse("8.14.4"))
    testLogging { events("passed", "failed", "skipped") }
}

publishing {
    publications.withType<MavenPublication>().configureEach {
        pom {
            name.set("NullGuard Gradle integration")
            description.set("Java null-safety analysis with Gradle source sets and compile classpaths")
            url.set("https://github.com/techbhaskar/null-guard")
            licenses { license { name.set("MIT License"); url.set("https://opensource.org/license/mit") } }
            developers { developer { id.set("techbhaskar"); name.set("Arinits"); url.set("https://arinits.com") } }
            scm { url.set("https://github.com/techbhaskar/null-guard"); connection.set("scm:git:https://github.com/techbhaskar/null-guard.git") }
        }
    }
}
