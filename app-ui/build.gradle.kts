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
    // Pont de pilotage (bridge/) : état et actions de l'interface lus par réflexion.
    implementation(libs.kotlin.reflect)

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

/**
 * `ffmpeg -version` du FFmpeg livré, écrit dans VERSION.txt à côté de lui : la GPL demande de pouvoir fournir les
 * sources exactes du binaire distribué, et c'est ce fichier qui dit lequel (révision Git et options de compilation).
 */
val ffmpegVersion by tasks.registering {
    description = "Note la version exacte du FFmpeg livré (VERSION.txt)."
    dependsOn(downloadFfmpeg)
    val target = layout.buildDirectory.file("ffmpeg-version/VERSION.txt")
    outputs.file(target)
    outputs.upToDateWhen { false }
    doLast {
        val exe = File(ffmpegHome() ?: error("FFmpeg introuvable pour noter sa version"), "bin/ffmpeg.exe")
        val process = ProcessBuilder(exe.path, "-hide_banner", "-version").redirectErrorStream(true).start()
        val text = process.inputStream.bufferedReader().readText()
        check(process.waitFor() == 0) { "ffmpeg -version a échoué : $text" }
        target.get().asFile.apply { parentFile.mkdirs() }.writeText(text)
    }
}

/**
 * Chaque bibliothèque livrée doit figurer dans THIRD_PARTY_NOTICES.md : une dépendance ajoutée sans sa licence fait
 * échouer la construction de l'installeur. On cherche le groupe Maven ou un nom connu du projet dans le fichier.
 */
val checkThirdPartyNotices by tasks.registering {
    description = "Vérifie que THIRD_PARTY_NOTICES.md cite toutes les bibliothèques livrées."
    val notices = rootProject.file("THIRD_PARTY_NOTICES.md")
    val runtime = configurations.named("runtimeClasspath")
    inputs.file(notices)
    doLast {
        val text = notices.readText().lowercase()
        // Groupe Maven → mot attendu dans les notices (nom du projet tel qu'il y est écrit).
        val names = mapOf(
            "org.jetbrains.kotlin" to "kotlin standard library", "org.jetbrains.kotlinx" to "kotlinx",
            "org.jetbrains.compose" to "compose multiplatform", "org.jetbrains.androidx" to "androidx",
            "org.jetbrains.skiko" to "skiko", "org.jetbrains.runtime" to "jetbrains runtime api",
            "org.jetbrains" to "org.jetbrains:annotations", "org.jspecify" to "jspecify", "androidx" to "androidx",
            "com.charleskorn.kaml" to "kaml", "it.krzeminski" to "snakeyaml-engine-kmp", "com.squareup.okio" to "okio",
            "net.thauvin.erik.urlencoder" to "urlencoder", "io.github.oshai" to "kotlin-logging",
            "com.microsoft.onnxruntime" to "onnx runtime", "org.slf4j" to "slf4j", "ch.qos.logback" to "logback",
            "com.github.ajalt.clikt" to "clikt",
        )
        val groups = runtime.get().incoming.resolutionResult.allComponents
            .mapNotNull { (it.id as? org.gradle.api.artifacts.component.ModuleComponentIdentifier)?.group }.toSortedSet()
        val missing = groups.filter { group ->
            val name = names.entries.filter { group == it.key || group.startsWith(it.key + ".") }.maxByOrNull { it.key.length }?.value
            name == null || name !in text
        }
        check(missing.isEmpty()) { "Bibliothèques livrées absentes de THIRD_PARTY_NOTICES.md : $missing" }
    }
}

val prepareBundledResources by tasks.registering(Sync::class) {
    dependsOn(downloadFfmpeg, ffmpegVersion, checkThirdPartyNotices)
    into(appResources)
    // Licence de l'application et des composants livrés : obligatoires pour redistribuer (GPL, Apache, MIT).
    from(rootProject.projectDir) {
        include("LICENSE", "THIRD_PARTY_NOTICES.md")
        into("common")
    }
    from(rootProject.file("licenses")) {
        into("common/licenses")
    }
    from(ffmpegVersion) {
        into("windows/ffmpeg")
    }
    from(rootProject.file("config")) {
        into("common/config")
        // Modèles de transcription whisper (plusieurs centaines de Mo) : téléchargés à part, pas livrés dans l'installeur.
        exclude("models/ggml-*.bin")
        // Identifiant OAuth YouTube personnel, déposé à côté d'app.yaml : jamais livré à d'autres.
        exclude("client_secret*.json")
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
    if (name == "run") {
        systemProperty("highlights.config", rootProject.file("config/app.yaml").absolutePath)
        // Pont de pilotage pour le développement (gradlew :app-ui:run -Pbridge) : jamais ailleurs que dans `run`.
        if (project.hasProperty("bridge")) systemProperty("highlights.bridge", "true")
    }
}

/**
 * L'application installée ne doit jamais démarrer le pont de pilotage : le lanceur produit (Highlights.cfg) ne doit pas
 * poser highlights.bridge. (Le pont refuse aussi de démarrer sous un lanceur jpackage, voir ControlBridge.enabled.)
 */
fun checkNoBridge(cfgFiles: Collection<File>) {
    check(cfgFiles.isNotEmpty()) { "Lanceur de l'application introuvable : vérification du pont de pilotage impossible" }
    for (cfg in cfgFiles) {
        check("highlights.bridge" !in cfg.readText()) { "${cfg.name} active le pont de pilotage : interdit dans l'application livrée" }
    }
}

tasks.matching { it.name == "createDistributable" }.configureEach {
    val appDir = layout.buildDirectory.dir("compose/binaries/main/app")
    doLast { checkNoBridge(appDir.get().asFile.walkTopDown().filter { it.extension == "cfg" }.toList()) }
}

compose.desktop {
    application {
        mainClass = "dev.highlights.ui.MainKt"
        jvmArgs += listOf(
            "-Dkotlin-logging.logStartupMessage=false",
            "-Dfile.encoding=UTF-8",
        )
        check(jvmArgs.none { "highlights.bridge" in it }) { "Le pont de pilotage ne doit pas être activé dans l'application livrée" }
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
