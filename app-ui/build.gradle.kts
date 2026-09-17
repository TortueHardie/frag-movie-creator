import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    id("highlights.compose-app")
}

dependencies {
    implementation(project(":pipeline"))
    implementation(compose.desktop.currentOs)
    implementation(libs.compose.material3)
    implementation(libs.coroutines.swing)
    implementation(libs.logback)

    // Détecteurs chargés par ServiceLoader.
    runtimeOnly(project(":analysis"))
    runtimeOnly(project(":analysis-vision"))
    runtimeOnly(project(":analysis-ml"))

    testImplementation(testFixtures(project(":ffmpeg")))
}

compose.desktop {
    application {
        mainClass = "dev.highlights.ui.MainKt"
        jvmArgs += listOf(
            // L'application lit la config du projet : les profils modifiés dans config/ s'appliquent sans reconstruire.
            // Barres obliques : jpackage supprime les antislashs du fichier de lancement.
            "-Dhighlights.config=" + rootProject.file("config/app.yaml").invariantSeparatorsPath,
            "-Dkotlin-logging.logStartupMessage=false",
            "-Dfile.encoding=UTF-8",
        )
        nativeDistributions {
            targetFormats(TargetFormat.Msi)
            packageName = "Highlights"
            packageVersion = "1.0.0"
            description = "Montages automatiques des meilleurs moments de gameplay"
            includeAllModules = true
            windows {
                menuGroup = "Highlights"
                shortcut = true
            }
        }
    }
}
