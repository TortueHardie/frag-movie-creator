plugins {
    id("highlights.kotlin-library")
}

// Assistant « nouveau jeu » : essaie les détecteurs d'images sur un repère marqué par l'utilisateur et écrit un profil.
// Il découpe modèles et zones lui-même et lit le texte des repères par l'OCR : dépendance de compilation sur
// analysis-vision (les autres modules ne la voient qu'au runtime, par ServiceLoader).
dependencies {
    api(project(":pipeline"))
    implementation(project(":analysis-vision"))

    testRuntimeOnly(project(":analysis"))
    testImplementation(testFixtures(project(":ffmpeg")))
}
