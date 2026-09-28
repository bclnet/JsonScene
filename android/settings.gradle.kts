pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "JsonScene"

// JsonUI is a git submodule (third_party/JsonUI); its Android modules are substituted by coordinates.
// When JsonScene is itself an included build (QRX does this), the outer build supplies JsonUI instead,
// because two included builds may not share the root project name "JsonUI".
if (gradle.parent == null) includeBuild("../third_party/JsonUI/android")

include(":jsonscene-core")
include(":jsonscene-android")
include(":jsonscene-compose")
include(":jsonscene-spatial")
