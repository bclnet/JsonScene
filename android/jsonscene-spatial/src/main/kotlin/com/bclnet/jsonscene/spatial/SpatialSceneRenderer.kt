/*
 * SpatialSceneRenderer.kt
 * JsonScene (Meta Quest)
 *
 * Renders a Scene with Meta Spatial SDK entities: one entity per actor with
 * Mesh (glTF), Transform, Scale and Animated components. The stage pose
 * (where the scene origin is in the room) comes from the host app, which
 * knows where the glyph / QR code is. Speech goes to `onSay` so the app can
 * show it on a panel next to the actor.
 */
package com.bclnet.jsonscene.spatial

import com.bclnet.jsonmind.ActorCommand
import com.bclnet.jsonmind.ActorScript
import com.bclnet.jsonmind.ActorStep
import com.bclnet.jsonmind.Mind
import com.bclnet.jsonmind.MindProvider
import com.bclnet.jsonmind.MindSession
import com.bclnet.jsonmind.Point3
import com.bclnet.jsonmind.Target
import android.content.Context
import android.net.Uri
import com.bclnet.jsonscene.Actor
import com.bclnet.jsonscene.ActorPose
import com.bclnet.jsonscene.AssetLocator
import com.bclnet.jsonscene.SceneDocument
import com.bclnet.jsonscene.Vec3
import com.bclnet.jsonscene.android.ActorRenderer
import com.bclnet.jsonscene.android.SceneDriver
import com.bclnet.jsonui.JsonContext
import com.bclnet.jsonui.JsonNode
import com.meta.spatial.core.Entity
import com.meta.spatial.core.Pose
import com.meta.spatial.core.Quaternion
import com.meta.spatial.core.Vector3
import com.meta.spatial.toolkit.Animated
import com.meta.spatial.toolkit.Mesh
import com.meta.spatial.toolkit.PlaybackState
import com.meta.spatial.toolkit.PlaybackType
import com.meta.spatial.toolkit.Scale
import com.meta.spatial.toolkit.Transform
import com.meta.spatial.toolkit.Visible
import java.io.File

class SpatialSceneRenderer(
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

    /** Where the scene origin is in the room; actors are placed relative to it. */
    var stagePose: Pose = Pose(Vector3(0f, 0f, -1f), Quaternion(0f, 0f, 0f))
        set(value) { field = value; for (id in driver.simulation.order) driver.simulation[id]?.let { move(id, it.pose) } }
    /** Speech bubbles: actor id and text (the host shows them on a panel). */
    var onSay: ((String, String) -> Unit)? = null
    /** Files the mesh loader needs; kept so the entities can be rebuilt. */
    private val entities = HashMap<String, Entity>()
    private val files = HashMap<String, File>()
    val entityIds: Map<String, Entity> get() = entities

    init {
        driver.formats = listOf("glb", "gltf")
    }

    /** Creates the entities and starts loading; call once the Spatial scene is ready. */
    fun attach() {
        for (actor in document.actors) {
            val entity = Entity.create(listOf(Transform(worldPose(actor, ActorPose(actor.transform.position, actor.transform.rotation.yaw))), Visible(false)))
            entities[actor.id] = entity
        }
        driver.loadModels()
        driver.start()
    }

    /** The actor entity that was pointed at, if it is one of ours. */
    fun actorId(entity: Entity?): String? = entities.entries.firstOrNull { it.value == entity }?.key

    fun tap(entity: Entity?) = driver.tap(actorId(entity))

    /** Advances the simulation; `head` is the headset pose in the room. */
    fun frame(frameTimeNanos: Long, head: Pose?) {
        val viewer = head?.let { toStage(it.t) }
        val forward = head?.let { val f = it.q.times(Vector3(0f, 0f, -1f)); Vec3(f.x.toDouble(), f.y.toDouble(), f.z.toDouble()) }
        driver.frame(frameTimeNanos, viewer, forward)
    }

    fun detach() {
        driver.stop()
        for (e in entities.values) runCatching { e.destroy() }
        entities.clear()
        driver.release()
    }

    // MARK: - ActorRenderer

    override fun load(actorId: String, file: File, format: String, onReady: () -> Unit, onError: (String) -> Unit) {
        val actor = document.actor(actorId) ?: return
        val entity = entities[actorId] ?: return
        files[actorId] = file
        val scale = ((actor.body?.scale ?: 1.0) * actor.transform.scale * document.scale).toFloat()
        entity.setComponents(listOf(Mesh(Uri.fromFile(file)), Scale(Vector3(scale, scale, scale)), Visible(true)))
        onReady()
    }

    override fun play(actorId: String, animation: String, loop: Boolean, speed: Double) {
        val actor = document.actor(actorId) ?: return
        val entity = entities[actorId] ?: return
        val clip = actor.body?.animations?.get(animation) ?: return
        val animated = Animated(System.currentTimeMillis(), 0f, PlaybackState.PLAYING, if (loop) PlaybackType.LOOP else PlaybackType.CLAMP)
        when (val c = clip.clip) {
            is com.bclnet.jsonscene.AnimationClip.Clip.Index -> animated.track = c.index
            is com.bclnet.jsonscene.AnimationClip.Clip.Name -> animated.animationName = c.name
        }
        entity.setComponent(animated)
        // The Spatial SDK has no completion callback; report the end of one-shot clips after a nominal duration.
        if (!loop) oneShotEnd(actorId, animation, ONE_SHOT_SECONDS / speed)
    }

    private fun oneShotEnd(actorId: String, animation: String, seconds: Double) {
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ driver.animationEnded(animation, actorId) }, (seconds * 1000).toLong())
    }

    override fun move(actorId: String, pose: ActorPose) {
        val actor = document.actor(actorId) ?: return
        entities[actorId]?.setComponent(Transform(worldPose(actor, pose)))
    }

    override fun say(actorId: String, text: String) { onSay?.invoke(actorId, text) }

    // MARK: - Poses

    private fun worldPose(actor: Actor, pose: ActorPose): Pose {
        val s = document.scale.toFloat()
        val local = Vector3((pose.position.x * s).toFloat(), (pose.position.y * s).toFloat(), (pose.position.z * s).toFloat())
        val world = stagePose.t + stagePose.q.times(local)
        // Actor headings are yaw only; pitch and roll come from the authored transform.
        val rotation = stagePose.q.times(Quaternion(actor.transform.rotation.pitch.toFloat(), pose.heading.toFloat(), actor.transform.rotation.roll.toFloat()))
        return Pose(world, rotation)
    }

    private fun toStage(world: Vector3): Vec3 {
        val d = world - stagePose.t
        val inverse = stagePose.q.inverse()
        val local = inverse.times(d)
        val s = document.scale
        return Vec3(local.x / s, local.y / s, local.z / s)
    }

    companion object {
        /** Assumed length of a one-shot clip when the SDK does not report it. */
        const val ONE_SHOT_SECONDS = 2.0
    }
}
