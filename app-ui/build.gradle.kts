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

// Ressources livrées avec l'application (dossier « resources » de l'installation) :
// common/config = config par défaut (recopiée dans %APPDATA%\Highlights au premier lancement), windows/ffmpeg = FFmpeg.
val appResources = layout.buildDirectory.dir("appResources")

/** FFmpeg à livrer : -PffmpegDir=<dossier contenant bin/ffmpeg.exe>, sinon l'installation winget Gyan.FFmpeg. */
fun ffmpegHome(): File? {
    (findProperty("ffmpegDir") as String?)?.let { return file(it) }
    val packages = File(System.getenv("LOCALAPPDATA") ?: return null, "Microsoft/WinGet/Packages")
    return packages.listFiles { f -> f.name.startsWith("Gyan.FFmpeg") }.orEmpty()
        .flatMap { it.listFiles().orEmpty().toList() }
        .filter { File(it, "bin/ffmpeg.exe").isFile }
        .maxByOrNull { it.name }
}

val prepareBundledResources by tasks.registering(Sync::class) {
    into(appResources)
    from(rootProject.file("config")) {
        into("common/config")
        // Installée, l'application écrit dans le dossier Vidéos de l'utilisateur plutôt qu'à côté de la config.
        filesMatching("app.yaml") {
            filter { line -> if (line.startsWith("outputDir:")) "outputDir: ~/Videos/Highlights" else line }
        }
    }
    ffmpegHome()?.let { home ->
        from(File(home, "bin")) {
            include("ffmpeg.exe", "ffprobe.exe")
            into("windows/ffmpeg")
        }
        // Licence GPL de FFmpeg : obligatoire pour le redistribuer.
        from(home) {
            include("LICENSE", "README.txt")
            into("windows/ffmpeg")
        }
    }
}

tasks.matching { it.name == "prepareAppResources" }.configureEach { dependsOn(prepareBundledResources) }

// L'installeur doit fonctionner sur un PC sans FFmpeg : refuser de l'assembler sans lui.
tasks.matching { it.name == "createDistributable" || it.name.startsWith("package") }.configureEach {
    doFirst {
        check(appResources.get().file("windows/ffmpeg/ffmpeg.exe").asFile.isFile) {
            "FFmpeg introuvable : installer « winget install Gyan.FFmpeg » ou passer -PffmpegDir=<dossier contenant bin/ffmpeg.exe>"
        }
    }
}

// En développement (gradlew run), l'application lit directement la config du projet : les profils modifiés dans
// config/ s'appliquent sans reconstruire.
tasks.withType<JavaExec>().configureEach {
    if (name == "run") systemProperty("highlights.config", rootProject.file("config/app.yaml").absolutePath)
}

compose.desktop {
    application {
        mainClass = "dev.highlights.ui.MainKt"
        jvmArgs += listOf(
            "-Dkotlin-logging.logStartupMessage=false",
            "-Dfile.encoding=UTF-8",
        )
        nativeDistributions {
            targetFormats(TargetFormat.Msi)
            packageName = "Highlights"
            // Format MSI : trois nombres ; l'augmenter à chaque installeur, sinon Windows refuse la mise à jour.
            packageVersion = findProperty("highlights.version") as String
            description = "Montages automatiques des meilleurs moments de gameplay"
            vendor = "Highlights"
            includeAllModules = true
            appResourcesRootDir.set(appResources)
            windows {
                menuGroup = "Highlights"
                shortcut = true
                menu = true
                // Installation dans le profil de l'utilisateur : pas besoin de droits administrateur.
                perUserInstall = true
                dirChooser = true
                // Fixe pour toujours : c'est ce qui permet à une nouvelle version de remplacer l'ancienne.
                upgradeUuid = "4642553d-2bc4-4caa-baa1-c77702d3ab28"
            }
        }
    }
}
