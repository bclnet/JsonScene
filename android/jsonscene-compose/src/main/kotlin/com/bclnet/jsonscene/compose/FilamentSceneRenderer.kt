/*
 * FilamentSceneRenderer.kt
 * JsonScene (Android)
 *
 * Renders a Scene with Filament through SceneView: a holder node per actor
 * (positioned by the simulation) with the glTF ModelNode below it.
 */
package com.bclnet.jsonscene.compose

import com.bclnet.jsonmind.ActorCommand
import com.bclnet.jsonmind.ActorScript
import com.bclnet.jsonmind.ActorStep
import com.bclnet.jsonmind.Mind
import com.bclnet.jsonmind.MindProvider
import com.bclnet.jsonmind.MindSession
import com.bclnet.jsonmind.Point3
import com.bclnet.jsonmind.Target
import android.content.Context
import com.bclnet.jsonscene.ActorPose
import com.bclnet.jsonscene.AnimationClip
import com.bclnet.jsonscene.AssetLocator
import com.bclnet.jsonscene.SceneDocument
import com.bclnet.jsonscene.Vec3
import com.bclnet.jsonscene.android.ActorRenderer
import com.bclnet.jsonscene.android.SceneDriver
import com.bclnet.jsonui.JsonContext
import com.bclnet.jsonui.JsonNode
import dev.romainguy.kotlin.math.Float3
import io.github.sceneview.SceneView
import io.github.sceneview.node.ModelNode
import io.github.sceneview.node.Node
import java.io.File

class FilamentSceneRenderer(
    context: Context,
    document: SceneDocument,
    jsonContext: JsonContext,
    locator: AssetLocator = AssetLocator(),
    mindProvider: MindProvider? = null,
) : ActorRenderer {
    constructor(context: Context, node: JsonNode, jsonContext: JsonContext, locator: AssetLocator = AssetLocator(), mindProvider: MindProvider? = null) :
        this(context, SceneDocument.of(node), jsonContext, locator, mindProvider)

    val driver = SceneDriver(context, document, jsonContext, this, locator, mindProvider)
    val document: SceneDocument get() = driver.document
    /** Speech bubbles: actor id and text, shown by the composable overlay. */
    var onSay: ((String, String) -> Unit)? = null
    private var view: SceneView? = null
    private val holders = HashMap<String, Node>()
    private val models = HashMap<String, ModelNode>()
    private val oneShots = HashMap<String, Runnable>()
    private val main = android.os.Handler(android.os.Looper.getMainLooper())

    init { driver.formats = listOf("glb", "gltf") }

    /** Creates the SceneView and the actor nodes, starts loading models. */
    fun createView(context: Context): SceneView {
        val view = SceneView(context)
        this.view = view
        val s = document.scale.toFloat()
        for (actor in document.actors) {
            val holder = Node(view.engine)
            holder.name = actor.id
            holder.position = Float3((actor.transform.position.x * s).toFloat(), (actor.transform.position.y * s).toFloat(), (actor.transform.position.z * s).toFloat())
            holder.rotation = Float3(actor.transform.rotation.pitch.toFloat(), actor.transform.rotation.yaw.toFloat(), actor.transform.rotation.roll.toFloat())
            holders[actor.id] = holder
            view.addChildNode(holder)
        }
        view.cameraNode.position = Float3(0f, 1.0f * s, 2.5f * s)
        view.cameraNode.rotation = Float3(-18f, 0f, 0f)
        view.setOnGestureListener(onSingleTapConfirmed = { _, node -> driver.tap(actorIdOf(node)) })
        view.onFrame = { nanos ->
            val cam = view.cameraNode.worldPosition
            // The camera looks down its local -Z axis: the third column of the world transform, negated.
            val z = view.cameraNode.worldTransform[2]
            driver.frame(nanos, Vec3(cam.x / s.toDouble(), cam.y / s.toDouble(), cam.z / s.toDouble()), Vec3(-z.x.toDouble(), -z.y.toDouble(), -z.z.toDouble()))
        }
        driver.loadModels()
        return view
    }

    private fun actorIdOf(node: Node?): String? {
        var n = node
        while (n != null) {
            holders.entries.firstOrNull { it.value === n }?.let { return it.key }
            n = n.parent
        }
        return null
    }

    fun destroy() {
        driver.stop()
        for (r in oneShots.values) main.removeCallbacks(r)
        view?.destroy()
        view = null
        driver.release()
    }

    // MARK: - ActorRenderer

    override fun load(actorId: String, file: File, format: String, onReady: () -> Unit, onError: (String) -> Unit) {
        val view = view ?: return onError("no view")
        val actor = document.actor(actorId) ?: return
        val holder = holders[actorId] ?: return
        val instance = runCatching { view.modelLoader.createModelInstance(file) }.getOrElse { return onError(it.message ?: "model could not be read") }
        if (instance == null) return onError("model could not be read")
        val model = ModelNode(modelInstance = instance, autoAnimate = false)
        val scale = ((actor.body?.scale ?: 1.0) * actor.transform.scale * document.scale).toFloat()
        model.scale = Float3(scale, scale, scale)
        models[actorId]?.let { holder.removeChildNode(it); it.destroy() }
        models[actorId] = model
        holder.addChildNode(model)
        onReady()
    }

    override fun play(actorId: String, animation: String, loop: Boolean, speed: Double) {
        val model = models[actorId] ?: return
        val clip = document.actor(actorId)?.body?.animations?.get(animation) ?: return
        oneShots.remove(actorId)?.let { main.removeCallbacks(it) }
        for (i in 0 until model.animationCount) model.stopAnimation(i)
        val animator = model.model.instance.animator
        val index = when (val c = clip.clip) {
            is AnimationClip.Clip.Index -> c.index
            is AnimationClip.Clip.Name -> (0 until animator.animationCount).firstOrNull { animator.getAnimationName(it) == c.name } ?: return
        }
        if (index !in 0 until animator.animationCount) return
        model.playAnimation(index, speed.toFloat(), loop)
        if (!loop) {
            val duration = animator.getAnimationDuration(index) / speed.coerceAtLeast(0.01)
            val r = Runnable { oneShots.remove(actorId); driver.animationEnded(animation, actorId) }
            oneShots[actorId] = r
            main.postDelayed(r, (duration * 1000).toLong())
        }
    }

    override fun move(actorId: String, pose: ActorPose) {
        val holder = holders[actorId] ?: return
        val actor = document.actor(actorId) ?: return
        val s = document.scale.toFloat()
        holder.position = Float3((pose.position.x * s).toFloat(), (pose.position.y * s).toFloat(), (pose.position.z * s).toFloat())
        holder.rotation = Float3(actor.transform.rotation.pitch.toFloat(), pose.heading.toFloat(), actor.transform.rotation.roll.toFloat())
    }

    override fun say(actorId: String, text: String) { onSay?.invoke(actorId, text) }
}
