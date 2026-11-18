plugins {
    id("highlights.kotlin-app")
}

dependencies {
    implementation(project(":pipeline"))
    implementation(project(":assistant"))
    implementation(libs.clikt)
    implementation(libs.logback)

    // Détecteurs chargés par ServiceLoader : ajouter un module de détection = ajouter une ligne ici.
    runtimeOnly(project(":analysis"))
    runtimeOnly(project(":analysis-vision"))
    runtimeOnly(project(":analysis-ml"))
}

application {
    mainClass = "dev.highlights.cli.MainKt"
    applicationName = "app"
    applicationDefaultJvmArgs = listOf(
        "-Dfile.encoding=UTF-8",
        "-Dkotlin-logging.logStartupMessage=false",
    )
}

tasks.named<JavaExec>("run") {
    workingDir = rootDir
}

distributions {
    main {
        contents {
            from(rootProject.file("config")) { into("config") }
        }
    }
}

// Vérité terrain : réanalyse les captures relevées dans verite/ et mesure les détecteurs (lancée à la main, longue).
// Arguments en plus : -Pverite="--videos D:\Vidéo" (ou "--sessions <dossier>" pour mesurer les analyses enregistrées).
tasks.register<JavaExec>("verite") {
    group = "verification"
    description = "Mesure précision et rappel des détecteurs contre la vérité terrain de verite/ (captures requises)."
    classpath = sourceSets["main"].runtimeClasspath
    mainClass = application.mainClass
    jvmArgs("-Dfile.encoding=UTF-8", "-Dhighlights.config=${rootDir}/config/app.yaml")
    workingDir = rootDir
    args(listOf("verite", "verite") + (providers.gradleProperty("verite").orNull?.split(" ")?.filter { it.isNotBlank() } ?: emptyList()))
}
