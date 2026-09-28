/*
 * SceneDriver.kt
 * JsonScene (Android)
 *
 * The part of a running scene that is the same for every Android renderer:
 * owns the simulation, resolves and downloads assets, plays sounds and
 * speech, registers the `actor` host action, times frames and forwards the
 * remaining outputs (animations, poses, speech bubbles) to an ActorRenderer.
 */
package com.bclnet.jsonscene.android

import com.bclnet.jsonmind.ActorCommand
import com.bclnet.jsonmind.ActorScript
import com.bclnet.jsonmind.ActorStep
import com.bclnet.jsonmind.Mind
import com.bclnet.jsonmind.MindProvider
import com.bclnet.jsonmind.MindSession
import com.bclnet.jsonmind.Point3
import com.bclnet.jsonmind.Target
import com.bclnet.jsonui.flag
import com.bclnet.jsonui.integerValue
import com.bclnet.jsonui.numberValue
import com.bclnet.jsonui.text
import android.content.Context
import com.bclnet.jsonscene.ActorOutput
import com.bclnet.jsonscene.ActorPose
import com.bclnet.jsonscene.ActorSimulation
import com.bclnet.jsonscene.AssetLocator
import com.bclnet.jsonscene.SceneDocument
import com.bclnet.jsonscene.Vec3
import com.bclnet.jsonui.JsonContext
import com.bclnet.jsonui.JsonLogLevel
import com.bclnet.jsonui.JsonNode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive
import java.io.File

/** What an engine specific renderer does with an actor. */
interface ActorRenderer {
    /** Loads the actor's model from a local file; call `onReady` when it can be animated. */
    fun load(actorId: String, file: File, format: String, onReady: () -> Unit, onError: (String) -> Unit)
    fun play(actorId: String, animation: String, loop: Boolean, speed: Double)
    fun move(actorId: String, pose: ActorPose)
    fun say(actorId: String, text: String)
    fun event(actorId: String, name: String) {}
}

class SceneDriver(
    context: Context,
    val document: SceneDocument,
    val context2: JsonContext,
    val renderer: ActorRenderer,
    val locator: AssetLocator = AssetLocator(),
    mindProvider: MindProvider? = null,
) {
    constructor(context: Context, node: JsonNode, jsonContext: JsonContext, renderer: ActorRenderer, locator: AssetLocator = AssetLocator(), mindProvider: MindProvider? = null) :
        this(context, SceneDocument.of(node), jsonContext, renderer, locator, mindProvider)

    val simulation = ActorSimulation(document, context2)
    val cache = AssetCache(context)
    val audio = ActorAudio(context)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var lastFrameNanos: Long? = null
    private var started = false
    /** Called with each problem (unsupported model, download failure, parse issue). */
    var onIssue: ((String) -> Unit)? = null
    /** Formats the renderer can load, in order of preference. */
    var formats: List<String> = listOf("glb", "gltf")
    private val loaded = HashSet<String>()

    init {
        simulation.mindProvider = mindProvider
        simulation.asyncOutputs = { outputs -> scope.launch { apply(outputs) } }
        simulation.registerHostAction { apply(it) }
        for (issue in document.issues) report(issue)
    }

    /** Starts loading models; the renderer's nodes must exist. */
    fun loadModels() {
        for (actor in document.actors) {
            val body = actor.body ?: continue
            val source = body.model.source(formats)
            val uri = source?.let { locator.resolve(it.second) }
            if (source == null || uri == null) {
                report("actor \"${actor.id}\": no model in a supported format (${formats.joinToString(", ")})")
                continue
            }
            scope.launch {
                val file = runCatching { cache.localFile(uri) }.getOrElse { report("actor \"${actor.id}\": ${it.message}"); return@launch }
                renderer.load(actor.id, file, source.first, onReady = {
                    loaded += actor.id
                    if (started) {
                        val name = body.defaultAnimation
                        val clip = name?.let { body.animations[it] }
                        if (name != null && clip != null) renderer.play(actor.id, name, true, clip.speed)
                    }
                }, onError = { report("actor \"${actor.id}\": $it") })
            }
        }
    }

    private fun report(message: String) {
        context2.log(JsonLogLevel.Warn, "Scene: $message")
        onIssue?.invoke(message)
    }

    fun start() {
        if (started) return
        started = true
        apply(simulation.start())
    }

    fun stop() {
        simulation.fireScene("disappear")
        for (id in simulation.order) apply(simulation.fire("disappear", id))
    }

    /** Advances the simulation from a frame callback. `viewer` is the viewer's position in stage coordinates. */
    fun frame(frameTimeNanos: Long, viewer: Vec3?, viewerForward: Vec3? = null) {
        if (!started) start()
        val last = lastFrameNanos
        lastFrameNanos = frameTimeNanos
        simulation.userPosition = viewer
        simulation.userForward = viewerForward
        val dt = if (last == null) 0.0 else ((frameTimeNanos - last) / 1e9).coerceIn(0.0, 0.25)
        if (dt <= 0) return
        val outputs = simulation.tick(dt)
        for (id in simulation.order) {
            val state = simulation[id] ?: continue
            if (state.actor.isMobile) renderer.move(id, state.pose)
        }
        apply(outputs)
    }

    fun tap(actorId: String?) {
        if (actorId != null && simulation[actorId] != null) apply(simulation.fire("tap", actorId)) else simulation.fireScene("tap")
    }

    /** Feeds speech recognised by the host to the actors that listen for it. */
    fun heard(text: String, actorId: String? = null) {
        for (id in actorId?.let { listOf(it) } ?: simulation.order) apply(simulation.fire("spoken", id, mapOf("text" to JsonPrimitive(text)), text))
    }

    fun perform(commands: List<ActorCommand>, actorId: String) = apply(simulation.perform(commands, actorId))

    fun animationEnded(animation: String, actorId: String) = apply(simulation.animationEnded(animation, actorId))

    fun apply(outputs: List<ActorOutput>) {
        for (output in outputs) {
            when (output) {
                is ActorOutput.Play -> if (output.actor in loaded) renderer.play(output.actor, output.animation, output.loop, output.speed)
                is ActorOutput.Sound -> playSound(output.actor, output.name, output.loop)
                is ActorOutput.StopSound -> audio.stop(output.actor, output.name)
                is ActorOutput.Say -> { renderer.say(output.actor, output.text); audio.speak(output.text) }
                is ActorOutput.Event -> renderer.event(output.actor, output.name)
            }
        }
    }

    private fun playSound(actorId: String, name: String, loop: Boolean) {
        val sound = document.actor(actorId)?.body?.sounds?.get(name) ?: return
        val uri = locator.resolve(sound.url) ?: return
        scope.launch {
            val file = runCatching { cache.localFile(uri) }.getOrElse { report("sound \"$name\": ${it.message}"); return@launch }
            runCatching { audio.play(actorId, name, sound, file, loop) { apply(simulation.soundEnded(name, actorId)) } }
                .onFailure { report("sound \"$name\": ${it.message}") }
        }
    }

    fun release() {
        scope.cancel()
        audio.release()
    }
}
