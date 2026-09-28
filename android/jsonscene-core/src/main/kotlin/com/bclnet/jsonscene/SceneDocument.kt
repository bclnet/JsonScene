/*
 * SceneDocument.kt
 * JsonScene
 *
 * The parsed form of a `Scene` node: environment, bounds and actors with
 * their bodies, mobility, behaviors and minds. Parsing never throws; problems
 * are collected in `issues` so a renderer can show the scene it understood
 * and report the rest.
 */
package com.bclnet.jsonscene

import com.bclnet.jsonui.JsonDocument
import com.bclnet.jsonui.JsonNode
import com.bclnet.jsonui.jsonNumber
import com.bclnet.jsonui.parseJson
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

data class SceneDocument(
    val anchor: Anchor = Anchor.CODE,
    val scale: Double = 1.0,
    val bounds: Bounds = Bounds.DEFAULT,
    val environment: Environment = Environment(),
    val actors: List<Actor> = emptyList(),
    /** Event handlers of the scene itself (`appear`, `disappear`, `tap`). */
    val on: Map<String, ActorScript> = emptyMap(),
    /** Parse problems, empty for a well formed scene. */
    val issues: List<String> = emptyList(),
) {
    enum class Anchor(val id: String) {
        CODE("code"), FLOOR("floor"), FREE("free");
        companion object { fun of(id: String?): Anchor? = entries.firstOrNull { it.id == id } }
    }

    data class Environment(val lighting: Lighting = Lighting.AUTO, val shadows: Boolean = true, val ground: Boolean = true) {
        enum class Lighting(val id: String) {
            AUTO("auto"), NONE("none"), STUDIO("studio");
            companion object { fun of(id: String?): Lighting? = entries.firstOrNull { it.id == id } }
        }
        companion object {
            fun of(value: JsonElement): Environment {
                val o = value as? JsonObject ?: return Environment()
                return Environment(Lighting.of(o["lighting"]?.text) ?: Lighting.AUTO, o["shadows"]?.flag ?: true, o["ground"]?.flag ?: true)
            }
        }
    }

    fun actor(id: String): Actor? = actors.firstOrNull { it.id == id }

    /** The node value (round trips the parsed form, defaults omitted). */
    val node: JsonNode
        get() {
            val props = linkedMapOf<String, JsonElement>()
            if (anchor != Anchor.CODE) props["anchor"] = JsonPrimitive(anchor.id)
            if (scale != 1.0) props["scale"] = jsonNumber(scale)
            if (bounds != Bounds.DEFAULT) props["bounds"] = bounds.value
            if (on.isNotEmpty()) props["on"] = ActorScript.value(on)
            props["actors"] = JsonArray(actors.map { it.value })
            return JsonNode(NODE_TYPE, props)
        }

    companion object {
        const val NODE_TYPE = "Scene"

        /** Parses a `Scene` node. Any node type is accepted so a host can reuse the actor format under another name. */
        fun of(node: JsonNode): SceneDocument {
            val issues = mutableListOf<String>()
            val anchor = Anchor.of(node["anchor"].text) ?: Anchor.CODE
            if (node.has("anchor") && Anchor.of(node["anchor"].text) == null) issues += "unknown anchor ${node["anchor"]}"
            val actors = mutableListOf<Actor>()
            val ids = HashSet<String>()
            (node["actors"] as? JsonArray)?.forEachIndexed { i, value ->
                val obj = value as? JsonObject
                if (obj == null) { issues += "actors[$i] is not an object"; return@forEachIndexed }
                val actor = Actor.of(obj, i)
                if (!ids.add(actor.id)) issues += "duplicate actor id \"${actor.id}\""
                issues += actor.issues.map { "actor \"${actor.id}\": $it" }
                actors += actor.copy(issues = emptyList())
            }
            return SceneDocument(
                anchor = anchor,
                scale = node["scale"].numberValue ?: 1.0,
                bounds = Bounds.of(node["bounds"]) ?: Bounds.DEFAULT,
                environment = Environment.of(node["environment"]),
                actors = actors,
                on = ActorScript.handlers(node["on"]),
                issues = issues,
            )
        }

        fun of(value: JsonElement): SceneDocument = of(JsonNode.fromValue(value) ?: throw SceneException("not a node"))

        fun parse(json: String): SceneDocument = of(parseJson(json))

        /** The `Scene` node of a JsonUI document, or null when its root is something else. */
        fun of(document: JsonDocument): SceneDocument? = if (document.root.type == NODE_TYPE) of(document.root) else null
    }
}

class SceneException(message: String) : IllegalArgumentException(message)

// MARK: - Actor

data class Actor(
    val id: String,
    val name: String? = null,
    val transform: Transform = Transform.IDENTITY,
    val body: Body? = null,
    val mobility: Mobility? = null,
    val behaviors: List<Behavior> = emptyList(),
    val mind: Mind? = null,
    val on: Map<String, ActorScript> = emptyMap(),
    /** Distance at which `near` / `far` fire, metres. */
    val nearDistance: Double = 1.0,
    val issues: List<String> = emptyList(),
) {
    val isMobile: Boolean get() = mobility != null && mobility.mode != Mobility.Mode.NONE

    val value: JsonElement
        get() {
            val o = linkedMapOf<String, JsonElement>("id" to JsonPrimitive(id))
            name?.let { o["name"] = JsonPrimitive(it) }
            if (transform != Transform.IDENTITY) o["transform"] = transform.value
            body?.let { o["body"] = it.value }
            mobility?.let { o["mobility"] = it.value }
            if (behaviors.isNotEmpty()) o["behaviors"] = JsonArray(behaviors.map { it.value })
            mind?.let { o["mind"] = it.value }
            if (on.isNotEmpty()) o["on"] = ActorScript.value(on)
            if (nearDistance != 1.0) o["nearDistance"] = jsonNumber(nearDistance)
            return JsonObject(o)
        }

    companion object {
        fun of(o: JsonObject, index: Int = 0): Actor {
            val issues = mutableListOf<String>()
            val rawId = o["id"]?.text ?: ""
            val id = if (rawId.isEmpty()) "actor$index" else rawId
            if (rawId.isEmpty()) issues += "missing id, using \"$id\""
            val body = o["body"]?.let { Body.of(it) }?.also { issues += it.issues }?.copy(issues = emptyList())
            var mobility: Mobility? = null
            o["mobility"]?.let { m ->
                mobility = Mobility.of(m)
                if (mobility == null) issues += "unknown mobility $m"
            }
            val behaviors = mutableListOf<Behavior>()
            (o["behaviors"] as? JsonArray)?.forEachIndexed { i, b ->
                val behavior = Behavior.of(b)
                if (behavior != null) behaviors += behavior else issues += "behaviors[$i] is not a behavior: $b"
            }
            if (mobility == null && behaviors.any { it.needsMobility }) issues += "has movement behaviors but no mobility"
            return Actor(
                id = id,
                name = o["name"]?.text,
                transform = Transform.of(o["transform"] ?: JsonNull),
                body = body,
                mobility = mobility,
                behaviors = behaviors,
                mind = o["mind"]?.let { Mind.of(it) },
                on = ActorScript.handlers(o["on"] ?: JsonNull),
                nearDistance = o["nearDistance"]?.numberValue ?: 1.0,
                issues = issues,
            )
        }
    }
}

// MARK: - Body

data class Body(
    val model: ModelRef,
    val scale: Double = 1.0,
    val animations: Map<String, AnimationClip> = emptyMap(),
    val defaultAnimation: String? = null,
    val sounds: Map<String, Sound> = emptyMap(),
    val sockets: Map<String, String> = emptyMap(),
    val issues: List<String> = emptyList(),
) {
    val value: JsonElement
        get() {
            val o = linkedMapOf<String, JsonElement>("model" to model.value)
            if (scale != 1.0) o["scale"] = jsonNumber(scale)
            if (animations.isNotEmpty()) o["animations"] = JsonObject(animations.mapValues { it.value.value })
            val implicitDefault = if (animations.containsKey("idle")) "idle" else null
            if (defaultAnimation != null && defaultAnimation != implicitDefault) o["default"] = JsonPrimitive(defaultAnimation)
            if (sounds.isNotEmpty()) o["sounds"] = JsonObject(sounds.mapValues { it.value.value })
            if (sockets.isNotEmpty()) o["sockets"] = JsonObject(sockets.mapValues { JsonPrimitive(it.value) })
            return JsonObject(o)
        }

    companion object {
        fun of(value: JsonElement): Body {
            val o = value as? JsonObject ?: JsonObject(emptyMap())
            val issues = mutableListOf<String>()
            val model = ModelRef.of(o["model"] ?: JsonNull)
            if (model.isEmpty) issues += "body has no model"
            val animations = linkedMapOf<String, AnimationClip>()
            (o["animations"] as? JsonObject)?.forEach { (name, v) ->
                val clip = AnimationClip.of(v)
                if (clip != null) animations[name] = clip else issues += "animation \"$name\" is not a clip"
            }
            val sounds = linkedMapOf<String, Sound>()
            (o["sounds"] as? JsonObject)?.forEach { (name, v) ->
                val s = Sound.of(v)
                if (s != null) sounds[name] = s else issues += "sound \"$name\" has no url"
            }
            return Body(
                model = model,
                scale = o["scale"]?.numberValue ?: 1.0,
                animations = animations,
                defaultAnimation = o["default"]?.text ?: if (animations.containsKey("idle")) "idle" else null,
                sounds = sounds,
                sockets = (o["sockets"] as? JsonObject)?.mapNotNull { (k, v) -> v.text?.let { k to it } }?.toMap() ?: emptyMap(),
                issues = issues,
            )
        }
    }
}

/** A model given as one URL or as several formats. Keys are lower case formats (file extensions). */
data class ModelRef(val sources: Map<String, String>) {
    val isEmpty: Boolean get() = sources.isEmpty()

    /** The first source whose format a renderer supports, in the renderer's order of preference. */
    fun source(preferring: List<String>): Pair<String, String>? {
        for (f in preferring) sources[f.lowercase()]?.let { return f.lowercase() to it }
        return null
    }

    /** Any source, `glb` first. */
    val primary: String? get() = source(listOf("glb", "gltf", "usdz"))?.second ?: sources.values.sorted().firstOrNull()

    val value: JsonElement
        get() {
            if (sources.size == 1) {
                val (f, u) = sources.entries.first()
                if (formatOf(u) == f) return JsonPrimitive(u)
            }
            return JsonObject(sources.mapValues { JsonPrimitive(it.value) })
        }

    companion object {
        fun of(value: JsonElement): ModelRef {
            value.text?.let { return ModelRef(mapOf(formatOf(it) to it)) }
            val o = value as? JsonObject ?: return ModelRef(emptyMap())
            return ModelRef(o.mapNotNull { (k, v) -> v.text?.let { k.lowercase() to it } }.toMap())
        }

        fun formatOf(url: String): String {
            val path = url.substringBefore('?')
            val dot = path.lastIndexOf('.')
            if (dot < 0 || path.substring(dot).contains('/')) return "glb"
            return path.substring(dot + 1).lowercase()
        }
    }
}

data class AnimationClip(val clip: Clip, val loop: Boolean = false, val speed: Double = 1.0) {
    /** glTF animation name or index. */
    sealed class Clip {
        data class Name(val name: String) : Clip()
        data class Index(val index: Int) : Clip()
    }

    val clipValue: JsonElement get() = when (clip) { is Clip.Name -> JsonPrimitive(clip.name); is Clip.Index -> jsonNumber(clip.index.toDouble()) }

    val value: JsonElement
        get() {
            if (!loop && speed == 1.0) return clipValue
            val o = linkedMapOf<String, JsonElement>("clip" to clipValue)
            if (loop) o["loop"] = JsonPrimitive(true)
            if (speed != 1.0) o["speed"] = jsonNumber(speed)
            return JsonObject(o)
        }

    companion object {
        fun of(value: JsonElement): AnimationClip? {
            value.text?.let { return AnimationClip(Clip.Name(it)) }
            value.integerValue?.let { return AnimationClip(Clip.Index(it)) }
            val o = value as? JsonObject ?: return null
            val clip = o["clip"]?.text?.let { Clip.Name(it) } ?: o["clip"]?.integerValue?.let { Clip.Index(it) } ?: return null
            return AnimationClip(clip, o["loop"]?.flag ?: false, o["speed"]?.numberValue ?: 1.0)
        }
    }
}

data class Sound(val url: String, val loop: Boolean = false, val volume: Double = 1.0, val spatial: Boolean = true) {
    val value: JsonElement
        get() {
            if (!loop && volume == 1.0 && spatial) return JsonPrimitive(url)
            val o = linkedMapOf<String, JsonElement>("url" to JsonPrimitive(url))
            if (loop) o["loop"] = JsonPrimitive(true)
            if (volume != 1.0) o["volume"] = jsonNumber(volume)
            if (!spatial) o["spatial"] = JsonPrimitive(false)
            return JsonObject(o)
        }

    companion object {
        fun of(value: JsonElement): Sound? {
            value.text?.let { return Sound(it) }
            val o = value as? JsonObject ?: return null
            val url = o["url"]?.text ?: return null
            return Sound(url, o["loop"]?.flag ?: false, o["volume"]?.numberValue ?: 1.0, o["spatial"]?.flag ?: true)
        }
    }
}

// MARK: - Mobility

data class Mobility(
    val mode: Mode,
    val speed: Double = DEFAULT_SPEED,
    val turnRate: Double = DEFAULT_TURN_RATE,
    val altitude: ClosedFloatingPointRange<Double> = DEFAULT_ALTITUDE,
    val bounds: Bounds? = null,
    val idleAnimation: String = "idle",
    val moveAnimation: String = if (mode == Mode.AIR) "fly" else "walk",
) {
    enum class Mode(val id: String) {
        NONE("none"), GROUND("ground"), AIR("air");
        companion object { fun of(id: String?): Mode? = entries.firstOrNull { it.id == id } }
    }

    val value: JsonElement
        get() {
            val o = linkedMapOf<String, JsonElement>("mode" to JsonPrimitive(mode.id))
            if (speed != DEFAULT_SPEED) o["speed"] = jsonNumber(speed)
            if (turnRate != DEFAULT_TURN_RATE) o["turnRate"] = jsonNumber(turnRate)
            if (mode == Mode.AIR && altitude != DEFAULT_ALTITUDE) o["altitude"] = JsonArray(listOf(jsonNumber(altitude.start), jsonNumber(altitude.endInclusive)))
            bounds?.let { o["bounds"] = it.value }
            if (idleAnimation != "idle") o["idle"] = JsonPrimitive(idleAnimation)
            if (moveAnimation != (if (mode == Mode.AIR) "fly" else "walk")) o["move"] = JsonPrimitive(moveAnimation)
            return JsonObject(o)
        }

    companion object {
        const val DEFAULT_SPEED = 0.5
        const val DEFAULT_TURN_RATE = 180.0
        val DEFAULT_ALTITUDE = 0.3..1.5

        fun of(value: JsonElement): Mobility? {
            value.text?.let { return Mode.of(it)?.let { m -> Mobility(m) } }
            val o = value as? JsonObject ?: return null
            val mode = Mode.of(o["mode"]?.text ?: "ground") ?: return null
            var altitude = DEFAULT_ALTITUDE
            (o["altitude"] as? JsonArray)?.let { a ->
                if (a.size == 2) {
                    val lo = a[0].numberValue; val hi = a[1].numberValue
                    if (lo != null && hi != null && lo <= hi) altitude = lo..hi
                }
            }
            val move = o["move"]?.text ?: if (mode == Mode.AIR) "fly" else "walk"
            return Mobility(mode, o["speed"]?.numberValue ?: DEFAULT_SPEED, o["turnRate"]?.numberValue ?: DEFAULT_TURN_RATE, altitude,
                o["bounds"]?.let { Bounds.of(it) }, o["idle"]?.text ?: "idle", move)
        }
    }
}

// MARK: - Behaviors

/** A target of a behavior or command: the viewer or another actor. */
sealed class Target {
    object User : Target() { override fun toString() = "User" }
    data class ActorId(val id: String) : Target()

    val value: JsonElement get() = JsonPrimitive(when (this) { is User -> "user"; is ActorId -> id })

    companion object {
        fun of(value: JsonElement): Target? {
            val s = value.text?.takeIf { it.isNotEmpty() } ?: return null
            return if (s == "user") User else ActorId(s)
        }
    }
}

data class Behavior(val kind: Kind, val priority: Int = 0, /** Dynamic value; `null` means always. */ val `when`: JsonElement? = null) {
    sealed class Kind {
        object Idle : Kind() { override fun toString() = "Idle" }
        data class Wander(val radius: Double?, val pause: ClosedFloatingPointRange<Double>) : Kind()
        data class Approach(val target: Target, val stopAt: Double) : Kind()
        data class Flee(val from: Target, val distance: Double) : Kind()
        data class Follow(val target: Target, val stopAt: Double) : Kind()
        data class Patrol(val points: List<Vec3>, val loop: Boolean) : Kind()
        data class LookAt(val target: Target) : Kind()
        data class PerchOn(val target: Target, val socket: String?) : Kind()
        data class FlyTo(val point: Vec3) : Kind()
    }

    val type: String
        get() = when (kind) {
            is Kind.Idle -> "idle"; is Kind.Wander -> "wander"; is Kind.Approach -> "approach"; is Kind.Flee -> "flee"; is Kind.Follow -> "follow"
            is Kind.Patrol -> "patrol"; is Kind.LookAt -> "lookAt"; is Kind.PerchOn -> "perchOn"; is Kind.FlyTo -> "flyTo"
        }

    /** Whether the behavior moves the actor (as opposed to just turning it). */
    val needsMobility: Boolean get() = kind !is Kind.Idle && kind !is Kind.LookAt

    val value: JsonElement
        get() {
            val o = linkedMapOf<String, JsonElement>("type" to JsonPrimitive(type))
            when (kind) {
                is Kind.Idle -> {}
                is Kind.Wander -> {
                    kind.radius?.let { o["radius"] = jsonNumber(it) }
                    if (kind.pause != DEFAULT_PAUSE) o["pause"] = JsonArray(listOf(jsonNumber(kind.pause.start), jsonNumber(kind.pause.endInclusive)))
                }
                is Kind.Approach -> { o["target"] = kind.target.value; if (kind.stopAt != 0.6) o["stopAt"] = jsonNumber(kind.stopAt) }
                is Kind.Flee -> { o["from"] = kind.from.value; if (kind.distance != 1.5) o["distance"] = jsonNumber(kind.distance) }
                is Kind.Follow -> { o["target"] = kind.target.value; if (kind.stopAt != 1.0) o["stopAt"] = jsonNumber(kind.stopAt) }
                is Kind.Patrol -> { o["points"] = JsonArray(kind.points.map { it.value }); if (!kind.loop) o["loop"] = JsonPrimitive(false) }
                is Kind.LookAt -> o["target"] = kind.target.value
                is Kind.PerchOn -> { o["target"] = kind.target.value; kind.socket?.let { o["socket"] = JsonPrimitive(it) } }
                is Kind.FlyTo -> o["point"] = kind.point.value
            }
            if (priority != 0) o["priority"] = jsonNumber(priority.toDouble())
            `when`?.let { o["when"] = it }
            return JsonObject(o)
        }

    companion object {
        val DEFAULT_PAUSE = 1.0..4.0

        fun of(value: JsonElement): Behavior? {
            value.text?.let { return kind(it, JsonObject(emptyMap()))?.let { k -> Behavior(k) } }
            val o = value as? JsonObject ?: return null
            val type = o["type"]?.text ?: return null
            val kind = kind(type, o) ?: return null
            return Behavior(kind, o["priority"]?.integerValue ?: 0, o["when"])
        }

        private fun kind(type: String, o: JsonObject): Kind? {
            fun target(key: String): Target = o[key]?.let { Target.of(it) } ?: Target.User
            return when (type) {
                "idle" -> Kind.Idle
                "wander" -> {
                    var pause = DEFAULT_PAUSE
                    o["pause"]?.numberValue?.let { pause = it..it }
                    (o["pause"] as? JsonArray)?.let { a ->
                        if (a.size == 2) {
                            val lo = a[0].numberValue; val hi = a[1].numberValue
                            if (lo != null && hi != null && lo <= hi) pause = lo..hi
                        }
                    }
                    Kind.Wander(o["radius"]?.numberValue, pause)
                }
                "approach" -> Kind.Approach(target("target"), o["stopAt"]?.numberValue ?: 0.6)
                "flee" -> Kind.Flee(target("from"), o["distance"]?.numberValue ?: 1.5)
                "follow" -> Kind.Follow(target("target"), o["stopAt"]?.numberValue ?: 1.0)
                "patrol" -> {
                    val points = (o["points"] as? JsonArray)?.mapNotNull { Vec3.of(it) } ?: emptyList()
                    if (points.isEmpty()) null else Kind.Patrol(points, o["loop"]?.flag ?: true)
                }
                "lookAt" -> Kind.LookAt(target("target"))
                "perchOn" -> Kind.PerchOn(target("target"), o["socket"]?.text)
                "flyTo" -> o["point"]?.let { Vec3.of(it) }?.let { Kind.FlyTo(it) }
                else -> null
            }
        }
    }
}
