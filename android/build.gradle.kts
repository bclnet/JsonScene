// Root build for the JsonScene Android libraries.
//
//   jsonscene-core     pure Kotlin/JVM: Scene document, commands, steering, minds, simulation
//   jsonscene-compose  Android library: Filament (SceneView) renderer registered as the JsonUI "Scene" node
//   jsonscene-spatial  Android library: Meta Spatial SDK renderer for Quest (entities with Mesh + Animated)
plugins {
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.compose.compiler) apply false
}

allprojects {
    group = "com.bclnet.jsonscene"
    version = "1.0.0"
}
