/*
 * JsonSceneView.kt
 * JsonScene (Android)
 *
 * The `Scene` node as a composable, and `JsonScene.register()` which adds it
 * to a JsonUI view registry.
 */
package com.bclnet.jsonscene.compose

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.bclnet.jsonscene.AssetLocator
import com.bclnet.jsonscene.MindProvider
import com.bclnet.jsonscene.SceneDocument
import com.bclnet.jsonui.JsonContext
import com.bclnet.jsonui.JsonNode
import com.bclnet.jsonui.compose.JsonViewRegistry

object JsonScene {
    /** Registers the `Scene` node with a JsonUI registry (the shared one by default). */
    fun register(registry: JsonViewRegistry = JsonViewRegistry.shared, locator: AssetLocator = AssetLocator(), mindProvider: MindProvider? = null) {
        registry.register(SceneDocument.NODE_TYPE) { node, context, _ -> JsonSceneView(node, context, locator, mindProvider) }
    }
}

@Composable
fun JsonSceneView(node: JsonNode, context: JsonContext, locator: AssetLocator = AssetLocator(), mindProvider: MindProvider? = null, modifier: Modifier = Modifier.fillMaxSize()) {
    val appContext = LocalContext.current
    val renderer = remember(node) { FilamentSceneRenderer(appContext, node, context, locator, mindProvider) }
    JsonSceneView(renderer, modifier)
}

@Composable
fun JsonSceneView(renderer: FilamentSceneRenderer, modifier: Modifier = Modifier.fillMaxSize()) {
    var issue by remember { mutableStateOf(renderer.document.issues.firstOrNull()) }
    var speech by remember { mutableStateOf<Pair<String, String>?>(null) }
    DisposableEffect(renderer) {
        renderer.driver.onIssue = { issue = it }
        renderer.onSay = { id, text -> speech = id to text }
        onDispose { renderer.destroy() }
    }
    Box(modifier) {
        AndroidView(factory = { ctx -> renderer.createView(ctx) }, modifier = Modifier.fillMaxSize())
        speech?.let { (id, text) ->
            val name = renderer.document.actor(id)?.name ?: id
            Surface(modifier = Modifier.align(Alignment.TopCenter).padding(12.dp), shape = MaterialTheme.shapes.medium, tonalElevation = 3.dp) {
                Text("$name: $text", modifier = Modifier.padding(10.dp), style = MaterialTheme.typography.bodyMedium)
            }
        }
        issue?.let {
            Surface(modifier = Modifier.align(Alignment.BottomCenter).padding(8.dp), shape = MaterialTheme.shapes.small, tonalElevation = 2.dp) {
                Text(it, modifier = Modifier.padding(6.dp), style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}
