import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import java.security.MessageDigest

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

/** Build FFmpeg téléchargé quand la machine n'en fournit pas : archive Windows 64 bits, GPL (libx264, AMF, NVENC, QSV). */
val ffmpegUrl = (findProperty("ffmpegUrl") as String?)
    ?: "https://github.com/BtbN/FFmpeg-Builds/releases/download/latest/ffmpeg-master-latest-win64-gpl.zip"

/** Empreinte SHA-256 attendue de cette archive (-PffmpegSha256=…). Absente : le téléchargement n'est pas vérifié. */
val ffmpegSha256 = findProperty("ffmpegSha256") as String?

val downloadedFfmpeg = layout.buildDirectory.dir("ffmpeg")

/** FFmpeg déjà présent sur la machine : -PffmpegDir=<dossier contenant bin/ffmpeg.exe>, sinon installation winget. */
fun localFfmpegHome(): File? {
    (findProperty("ffmpegDir") as String?)?.let { return file(it) }
    val packages = File(System.getenv("LOCALAPPDATA") ?: return null, "Microsoft/WinGet/Packages")
    return packages.listFiles { f -> f.name.startsWith("Gyan.FFmpeg") }.orEmpty()
        .flatMap { it.listFiles().orEmpty().toList() }
        .filter { File(it, "bin/ffmpeg.exe").isFile }
        .maxByOrNull { it.name }
}

/** Dossier contenant bin/ffmpeg.exe : celui de la machine, sinon celui qui vient d'être téléchargé. */
fun ffmpegHome(): File? = localFfmpegHome()
    ?: downloadedFfmpeg.get().asFile.listFiles().orEmpty().firstOrNull { File(it, "bin/ffmpeg.exe").isFile }

/**
 * Télécharge et décompresse FFmpeg si la machine n'en a pas : l'installeur se construit alors sur n'importe quel
 * poste (et sur une machine d'intégration continue) sans rien installer à la main.
 */
val downloadFfmpeg by tasks.registering {
    description = "Télécharge FFmpeg pour Windows si la machine n'en fournit pas."
    val target = downloadedFfmpeg
    val url = ffmpegUrl
    val expected = ffmpegSha256
    outputs.dir(target)
    onlyIf { localFfmpegHome() == null }
    doLast {
        val dir = target.get().asFile
        if (dir.listFiles().orEmpty().any { File(it, "bin/ffmpeg.exe").isFile }) return@doLast
        dir.deleteRecursively()
        dir.mkdirs()
        val archive = File(dir, "ffmpeg.zip")
        logger.lifecycle("Téléchargement de FFmpeg : $url")
        uri(url).toURL().openStream().use { input -> archive.outputStream().use { input.copyTo(it) } }

        val digest = MessageDigest.getInstance("SHA-256")
        archive.inputStream().use { input ->
            val buffer = ByteArray(1 shl 16)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        val sha = digest.digest().joinToString("") { "%02x".format(it) }
        if (expected != null) {
            check(sha.equals(expected, ignoreCase = true)) { "FFmpeg téléchargé : empreinte $sha au lieu de $expected" }
        } else {
            logger.lifecycle("FFmpeg téléchargé, SHA-256 $sha (à figer avec -PffmpegSha256 pour un installeur reproductible)")
        }

        copy {
            from(zipTree(archive))
            into(dir)
        }
        archive.delete()
        check(ffmpegHome() != null) { "L'archive $url ne contient pas bin/ffmpeg.exe" }
    }
}

val prepareBundledResources by tasks.registering(Sync::class) {
    dependsOn(downloadFfmpeg)
    into(appResources)
    from(rootProject.file("config")) {
        into("common/config")
        // Installée, l'application écrit dans le dossier Vidéos de l'utilisateur plutôt qu'à côté de la config.
        filesMatching("app.yaml") {
            filter { line -> if (line.startsWith("outputDir:")) "outputDir: ~/Videos/Highlights" else line }
        }
    }
    // Résolu à l'exécution : le dossier n'existe qu'une fois FFmpeg téléchargé.
    from({ ffmpegHome()?.let { File(it, "bin") } }) {
        include("ffmpeg.exe", "ffprobe.exe")
        into("windows/ffmpeg")
    }
    // Licence GPL de FFmpeg : obligatoire pour le redistribuer.
    from({ ffmpegHome() }) {
        include("LICENSE", "LICENSE.txt", "README.txt")
        into("windows/ffmpeg")
    }
}

tasks.matching { it.name == "prepareAppResources" }.configureEach { dependsOn(prepareBundledResources) }

// L'installeur doit fonctionner sur un PC sans FFmpeg : refuser de l'assembler sans lui.
tasks.matching { it.name == "createDistributable" || it.name.startsWith("package") }.configureEach {
    doFirst {
        check(appResources.get().file("windows/ffmpeg/ffmpeg.exe").asFile.isFile) {
            "FFmpeg introuvable : il est téléchargé automatiquement (-PffmpegUrl pour changer d'archive), " +
                "sinon passer -PffmpegDir=<dossier contenant bin/ffmpeg.exe> ou installer « winget install Gyan.FFmpeg »"
        }
    }
}

/**
 * Version portable : le même contenu que l'installeur, en ZIP. Pour qui ne peut pas (ou ne veut pas) lancer un MSI :
 * décompresser et lancer Highlights.exe, sans installation ni droits particuliers.
 */
val portableZip by tasks.registering(Zip::class) {
    group = "compose desktop"
    description = "Application complète en ZIP, à décompresser et lancer sans installation."
    dependsOn("createDistributable")
    from(layout.buildDirectory.dir("compose/binaries/main/app"))
    archiveFileName.set("Highlights-${findProperty("highlights.version")}-portable.zip")
    destinationDirectory.set(layout.buildDirectory.dir("distributions"))
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
