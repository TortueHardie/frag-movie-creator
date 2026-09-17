pluginManagement {
    includeBuild("build-logic")
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
        google()
    }
}

rootProject.name = "highlights"

include(
    "core",
    "ffmpeg",
    "analysis",
    "scoring",
    "editing",
    "export",
    "montage",
    "pipeline",
    "analysis-vision",
    "analysis-ml",
    "app-cli",
    "app-ui",
)
