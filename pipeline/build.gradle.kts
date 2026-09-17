plugins {
    id("highlights.kotlin-library")
}

dependencies {
    api(project(":core"))
    api(project(":ffmpeg"))
    api(project(":scoring"))
    api(project(":editing"))
    api(project(":export"))
    api(project(":montage"))

    // Les détecteurs sont découverts au runtime (ServiceLoader) : pas de dépendance de compilation.
    testRuntimeOnly(project(":analysis"))
    testRuntimeOnly(project(":analysis-vision"))
    testRuntimeOnly(project(":analysis-ml"))
    testImplementation(testFixtures(project(":ffmpeg")))
}
