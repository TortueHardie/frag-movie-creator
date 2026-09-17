plugins {
    id("highlights.kotlin-library")
}

dependencies {
    api(project(":core"))
    api(project(":editing"))
    api(project(":export"))

    testImplementation(project(":ffmpeg"))
    testImplementation(testFixtures(project(":ffmpeg")))
}
