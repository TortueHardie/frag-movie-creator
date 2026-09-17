plugins {
    id("highlights.kotlin-app")
}

dependencies {
    implementation(project(":pipeline"))
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
