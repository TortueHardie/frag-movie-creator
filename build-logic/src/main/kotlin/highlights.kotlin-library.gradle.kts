import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.api.tasks.testing.logging.TestExceptionFormat

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.kotlin.plugin.serialization")
}

val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")

kotlin {
    jvmToolchain(21)
    compilerOptions {
        freeCompilerArgs.addAll("-Xjsr305=strict")
    }
}

dependencies {
    "testImplementation"(platform(libs.findLibrary("junit-bom").get()))
    "testImplementation"(libs.findLibrary("kotest-runner").get())
    "testImplementation"(libs.findLibrary("kotest-assertions").get())
    "testImplementation"(libs.findLibrary("coroutines-test").get())
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    maxHeapSize = "1g"
    // Les tests vérifient les textes en français (langue d'origine de l'application) quelle que soit la langue de la
    // machine ; les tests de traduction choisissent leur langue eux-mêmes.
    systemProperty("highlights.lang", "fr")
    testLogging {
        events("failed", "skipped")
        exceptionFormat = TestExceptionFormat.FULL
        showStandardStreams = false
    }
}
