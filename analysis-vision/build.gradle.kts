plugins {
    id("highlights.kotlin-library")
}

dependencies {
    api(project(":core"))

    testImplementation(project(":ffmpeg"))
    testImplementation(testFixtures(project(":ffmpeg")))
}
