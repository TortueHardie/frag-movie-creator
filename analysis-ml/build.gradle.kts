plugins {
    id("highlights.kotlin-library")
}

dependencies {
    api(project(":core"))
    implementation(libs.onnxruntime)

    testImplementation(project(":ffmpeg"))
    testImplementation(testFixtures(project(":ffmpeg")))
}
