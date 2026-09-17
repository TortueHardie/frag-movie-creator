plugins {
    id("highlights.kotlin-library")
    `java-test-fixtures`
}

dependencies {
    api(project(":core"))

    testFixturesApi(project(":core"))
}
