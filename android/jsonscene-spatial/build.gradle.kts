plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    `maven-publish`
}

android {
    namespace = "com.bclnet.jsonscene.spatial"
    compileSdk = 35
    defaultConfig { minSdk = 29 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    publishing { singleVariant("release") { withSourcesJar() } }
}

kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}

dependencies {
    api(project(":jsonscene-android"))
    api(libs.jsonui.core)
    implementation(libs.meta.spatial.sdk)
    implementation(libs.meta.spatial.toolkit)
    implementation(libs.kotlinx.coroutines.android)
    testImplementation(libs.junit)
}

afterEvaluate {
    publishing { publications { create<MavenPublication>("release") { from(components["release"]); artifactId = "jsonscene-spatial" } } }
}
