plugins {
    id("highlights.kotlin-library")
}

dependencies {
    api(libs.coroutines.core)
    api(libs.serialization.json)
    api(libs.kaml)
    api(libs.kotlin.logging)
    api(libs.slf4j.api)
}
