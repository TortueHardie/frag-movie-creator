plugins {
    id("highlights.kotlin-library")
}

dependencies {
    api(project(":core"))

    testImplementation(testFixtures(project(":ffmpeg")))
    testImplementation(project(":ffmpeg"))
}
