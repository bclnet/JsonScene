/*
 * Vec3.kt
 * JsonScene
 *
 * Minimal vector math shared by the steering code and the renderers.
 * Scene space is metres, +Y up, rotations in degrees.
 */
package com.bclnet.jsonscene

import com.bclnet.jsonui.jsonNumber
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

data class Vec3(val x: Double, val y: Double, val z: Double) {
    operator fun plus(o: Vec3) = Vec3(x + o.x, y + o.y, z + o.z)
    operator fun minus(o: Vec3) = Vec3(x - o.x, y - o.y, z - o.z)
    operator fun times(s: Double) = Vec3(x * s, y * s, z * s)
    operator fun unaryMinus() = Vec3(-x, -y, -z)

    val length: Double get() = sqrt(x * x + y * y + z * z)
    /** Length on the ground plane. */
    val horizontalLength: Double get() = sqrt(x * x + z * z)
    val normalized: Vec3 get() = length.let { if (it > 1e-9) this * (1 / it) else ZERO }
    fun distance(o: Vec3): Double = (this - o).length
    fun horizontalDistance(o: Vec3): Double = (this - o).horizontalLength
    fun dot(o: Vec3): Double = x * o.x + y * o.y + z * o.z
    fun lerp(o: Vec3, t: Double): Vec3 = this + (o - this) * t
    val flattened: Vec3 get() = Vec3(x, 0.0, z)

    /** Yaw in degrees of this direction, where 0 faces +Z and 90 faces +X. */
    val yaw: Double get() = Angle.degrees(atan2(x, z))

    val value: JsonElement get() = JsonArray(listOf(jsonNumber(x), jsonNumber(y), jsonNumber(z)))

    companion object {
        val ZERO = Vec3(0.0, 0.0, 0.0)

        /** Parses `[x, y, z]`, `[x, z]` (y = 0) or `{ "x": .., "y": .., "z": .. }`. */
        fun of(value: JsonElement): Vec3? = when (value) {
            is JsonArray -> {
                val n = value.mapNotNull { it.numberValue }
                if (n.size != value.size || n.isEmpty()) null
                else when (n.size) {
                    1 -> Vec3(n[0], 0.0, 0.0)
                    2 -> Vec3(n[0], 0.0, n[1])
                    else -> Vec3(n[0], n[1], n[2])
                }
            }
            is JsonObject -> Vec3(value["x"]?.numberValue ?: 0.0, value["y"]?.numberValue ?: 0.0, value["z"]?.numberValue ?: 0.0)
            else -> null
        }

        /** Unit vector on the ground plane for a yaw in degrees (see `yaw`). */
        fun direction(yaw: Double): Vec3 = Angle.radians(yaw).let { Vec3(sin(it), 0.0, cos(it)) }
    }
}

object Angle {
    fun degrees(radians: Double): Double = radians * 180 / Math.PI
    fun radians(degrees: Double): Double = degrees * Math.PI / 180

    /** Wraps an angle to (-180, 180]. */
    fun normalize(degrees: Double): Double {
        var d = degrees % 360
        if (d <= -180) d += 360
        if (d > 180) d -= 360
        return d
    }

    /** The shortest signed difference `to - from` in degrees. */
    fun delta(from: Double, to: Double): Double = normalize(to - from)

    /** Turns `from` towards `to` by at most `maxStep` degrees. */
    fun turn(from: Double, to: Double, maxStep: Double): Double {
        val d = delta(from, to)
        if (abs(d) <= maxStep) return normalize(to)
        return normalize(from + if (d > 0) maxStep else -maxStep)
    }
}

/** Euler rotation in degrees. */
data class Rotation(val pitch: Double = 0.0, val yaw: Double = 0.0, val roll: Double = 0.0) {
    val value: JsonElement get() = JsonArray(listOf(jsonNumber(pitch), jsonNumber(yaw), jsonNumber(roll)))

    companion object {
        val IDENTITY = Rotation()

        /** Parses `[pitch, yaw, roll]`, a single yaw number, or `{ "yaw": .. }`. */
        fun of(value: JsonElement): Rotation? {
            value.numberValue?.let { return Rotation(yaw = it) }
            if (value is JsonArray) return Vec3.of(value)?.let { Rotation(it.x, it.y, it.z) }
            if (value is JsonObject) return Rotation(value["pitch"]?.numberValue ?: 0.0, value["yaw"]?.numberValue ?: 0.0, value["roll"]?.numberValue ?: 0.0)
            return null
        }
    }
}

data class Transform(val position: Vec3 = Vec3.ZERO, val rotation: Rotation = Rotation.IDENTITY, val scale: Double = 1.0) {
    val value: JsonElement
        get() {
            val o = linkedMapOf<String, JsonElement>()
            if (position != Vec3.ZERO) o["position"] = position.value
            if (rotation != Rotation.IDENTITY) o["rotation"] = rotation.value
            if (scale != 1.0) o["scale"] = jsonNumber(scale)
            return JsonObject(o)
        }

    companion object {
        val IDENTITY = Transform()

        fun of(value: JsonElement): Transform {
            val o = value as? JsonObject ?: return IDENTITY
            return Transform(o["position"]?.let { Vec3.of(it) } ?: Vec3.ZERO, o["rotation"]?.let { Rotation.of(it) } ?: Rotation.IDENTITY, o["scale"]?.numberValue ?: 1.0)
        }
    }
}

/** The volume mobile actors may roam: a cylinder around the origin or a box. */
sealed class Bounds {
    data class Radius(val radius: Double) : Bounds()
    data class Box(val min: Vec3, val max: Vec3) : Bounds()

    val value: JsonElement
        get() = when (this) {
            is Radius -> JsonObject(mapOf("radius" to jsonNumber(radius)))
            is Box -> JsonObject(mapOf("min" to min.value, "max" to max.value))
        }

    fun contains(p: Vec3): Boolean = when (this) {
        is Radius -> p.horizontalLength <= radius + 1e-9
        is Box -> p.x >= min.x && p.x <= max.x && p.z >= min.z && p.z <= max.z
    }

    /** The nearest point inside the bounds (altitude is left alone). */
    fun clamp(p: Vec3): Vec3 = when (this) {
        is Radius -> p.horizontalLength.let { l -> if (l <= radius) p else Vec3(p.x * radius / l, p.y, p.z * radius / l) }
        is Box -> Vec3(min(max(p.x, min.x), max.x), p.y, min(max(p.z, min.z), max.z))
    }

    /** A uniformly distributed point on the ground plane inside the bounds. */
    fun randomPoint(random: Random): Vec3 = when (this) {
        is Radius -> {
            val a = random.nextDouble() * 2 * Math.PI
            val d = radius * sqrt(random.nextDouble())
            Vec3(d * sin(a), 0.0, d * cos(a))
        }
        is Box -> Vec3(min.x + random.nextDouble() * max(0.0, max.x - min.x), 0.0, min.z + random.nextDouble() * max(0.0, max.z - min.z))
    }

    companion object {
        val DEFAULT: Bounds = Radius(2.0)

        fun of(value: JsonElement): Bounds? {
            value.numberValue?.let { return Radius(it) }
            val o = value as? JsonObject ?: return null
            o["radius"]?.numberValue?.let { return Radius(it) }
            val lo = o["min"]?.let { Vec3.of(it) } ?: return null
            val hi = o["max"]?.let { Vec3.of(it) } ?: return null
            return Box(lo, hi)
        }
    }
}
