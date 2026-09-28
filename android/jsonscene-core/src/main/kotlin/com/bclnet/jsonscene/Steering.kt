/*
 * Steering.kt
 * JsonScene
 *
 * Movement of mobile actors, independent of any renderer: a pose (position
 * and heading), a goal (seek, flee, wander…), and `Steering.step` which
 * advances the pose by `dt` seconds with the actor's speed and turn rate.
 */
package com.bclnet.jsonscene

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

data class ActorPose(val position: Vec3 = Vec3.ZERO, /** Heading in degrees, 0 faces +Z, 90 faces +X. */ val heading: Double = 0.0)

sealed class SteeringGoal {
    object None : SteeringGoal() { override fun toString() = "None" }
    /** Go to a point and stop within `stopAt` of it. */
    data class Seek(val target: Vec3, val stopAt: Double) : SteeringGoal()
    /** Keep at least `distance` from a point. */
    data class Flee(val threat: Vec3, val distance: Double) : SteeringGoal()
    /** Only turn towards a point. */
    data class Face(val target: Vec3) : SteeringGoal()
}

data class SteeringResult(val pose: ActorPose, val moving: Boolean, /** True on the step where a seek goal was reached. */ val arrived: Boolean)

object Steering {
    /**
     * Advances `pose` towards `goal`.
     *
     * Ground actors move on the XZ plane and stay on y = 0; air actors also move
     * vertically and are clamped to the altitude range. The actor first turns
     * (at `turnRate`) and only moves when facing within 60° of the target, so it
     * does not slide sideways.
     */
    fun step(pose: ActorPose, goal: SteeringGoal, mobility: Mobility, bounds: Bounds, dt: Double): SteeringResult {
        if (dt <= 0 || mobility.mode == Mobility.Mode.NONE) return SteeringResult(pose, false, false)
        when (goal) {
            is SteeringGoal.None -> return SteeringResult(pose, false, false)
            is SteeringGoal.Face -> {
                val to = goal.target - pose.position
                val heading = if (to.horizontalLength > 1e-6) Angle.turn(pose.heading, to.yaw, mobility.turnRate * dt) else pose.heading
                return SteeringResult(pose.copy(heading = heading), false, false)
            }
            is SteeringGoal.Seek -> {
                val goalPoint = if (mobility.mode == Mobility.Mode.AIR) clampAltitude(goal.target, mobility) else goal.target.flattened
                val to = goalPoint - pose.position
                val distance = if (mobility.mode == Mobility.Mode.AIR) to.length else to.horizontalLength
                if (distance <= max(goal.stopAt, 1e-3)) return SteeringResult(pose, false, true)
                var heading = pose.heading
                if (to.horizontalLength > 1e-6) {
                    heading = Angle.turn(pose.heading, to.yaw, mobility.turnRate * dt)
                    if (abs(Angle.delta(heading, to.yaw)) > 60) return SteeringResult(pose.copy(heading = heading), true, false)
                }
                val travel = min(mobility.speed * dt, max(0.0, distance - goal.stopAt))
                var position = pose.position
                var moved = false
                if (travel > 0) { position += to.normalized * travel; moved = true }
                position = clamp(position, mobility, bounds)
                val remaining = if (mobility.mode == Mobility.Mode.AIR) goalPoint.distance(position) else goalPoint.horizontalDistance(position)
                return SteeringResult(ActorPose(position, heading), moved, remaining <= max(goal.stopAt, 1e-3) + 1e-9)
            }
            is SteeringGoal.Flee -> {
                val away = pose.position.flattened - goal.threat.flattened
                val current = away.horizontalLength
                if (current >= goal.distance) return SteeringResult(pose, false, false)
                // Run directly away, or away from the origin when standing on the threat.
                var direction = if (current > 1e-6) away.normalized else Vec3.direction(pose.heading)
                val candidate = pose.position + direction * (mobility.speed * dt)
                if (!bounds.contains(candidate)) {
                    // Cornered: slide along the boundary (turn 90°) instead of standing still.
                    direction = Vec3(direction.z, 0.0, -direction.x)
                }
                val heading = Angle.turn(pose.heading, direction.yaw, mobility.turnRate * dt)
                val position = clamp(pose.position + direction * (mobility.speed * dt), mobility, bounds)
                return SteeringResult(ActorPose(position, heading), true, false)
            }
        }
    }

    internal fun clampAltitude(p: Vec3, mobility: Mobility): Vec3 =
        Vec3(p.x, min(max(p.y, mobility.altitude.start), mobility.altitude.endInclusive), p.z)

    fun clamp(p: Vec3, mobility: Mobility, bounds: Bounds): Vec3 {
        val inBounds = bounds.clamp(p)
        return if (mobility.mode == Mobility.Mode.AIR) clampAltitude(inBounds, mobility) else inBounds.flattened
    }
}

/**
 * Picks the active behavior and turns it into a steering goal, keeping the
 * little state behaviors need (the wander target, the patrol index).
 */
class BehaviorRunner(behaviors: List<Behavior>, val mobility: Mobility, bounds: Bounds, seed: Long = System.currentTimeMillis()) {
    class Senses(val userPosition: Vec3? = null, val actorPositions: Map<String, Vec3> = emptyMap()) {
        fun position(target: Target): Vec3? = when (target) { is Target.User -> userPosition; is Target.ActorId -> actorPositions[target.id] }
    }

    data class Goals(val move: SteeringGoal, val face: SteeringGoal?)

    val behaviors: List<Behavior> = behaviors.sortedByDescending { it.priority }
    val bounds: Bounds = mobility.bounds ?: bounds
    var activeIndex: Int? = null
        private set
    var wanderTarget: Vec3? = null
        private set
    var wanderPauseUntil: Double = 0.0
        private set
    var patrolIndex: Int = 0
        private set
    private val random = Random(seed)

    val active: Behavior? get() = activeIndex?.let { behaviors[it] }

    /**
     * The goals for this frame: a movement goal and an optional `lookAt` facing goal.
     * `isActive` tells whether a behavior's `when` condition holds (`null` conditions are always active).
     */
    fun goals(pose: ActorPose, senses: Senses, time: Double, isActive: (Behavior) -> Boolean = { it.`when` == null }): Goals {
        var face: SteeringGoal? = null
        var chosen: Int? = null
        for ((i, b) in behaviors.withIndex()) {
            if (!isActive(b)) continue
            val kind = b.kind
            if (kind is Behavior.Kind.LookAt) {
                if (face == null) senses.position(kind.target)?.let { face = SteeringGoal.Face(it) }
                continue
            }
            if (chosen == null) chosen = i
        }
        if (chosen != activeIndex) { activeIndex = chosen; wanderTarget = null; wanderPauseUntil = 0.0 }
        val index = chosen ?: return Goals(SteeringGoal.None, face)
        val move: SteeringGoal = when (val kind = behaviors[index].kind) {
            is Behavior.Kind.Idle, is Behavior.Kind.LookAt -> SteeringGoal.None
            is Behavior.Kind.Wander -> {
                val area: Bounds = kind.radius?.let { Bounds.Radius(it) } ?: bounds
                val target = wanderTarget
                if (target != null) {
                    val d = if (mobility.mode == Mobility.Mode.AIR) target.distance(pose.position) else target.horizontalDistance(pose.position)
                    if (d <= 0.1) {
                        wanderTarget = null
                        wanderPauseUntil = time + kind.pause.start + random.nextDouble() * (kind.pause.endInclusive - kind.pause.start)
                        SteeringGoal.None
                    } else SteeringGoal.Seek(target, 0.05)
                } else if (time >= wanderPauseUntil) {
                    var p = area.randomPoint(random)
                    if (mobility.mode == Mobility.Mode.AIR) p = p.copy(y = mobility.altitude.start + random.nextDouble() * (mobility.altitude.endInclusive - mobility.altitude.start))
                    wanderTarget = p
                    SteeringGoal.Seek(p, 0.05)
                } else SteeringGoal.None
            }
            is Behavior.Kind.Approach -> senses.position(kind.target)?.let { SteeringGoal.Seek(it, kind.stopAt) } ?: SteeringGoal.None
            is Behavior.Kind.Follow -> senses.position(kind.target)?.let { SteeringGoal.Seek(it, kind.stopAt) } ?: SteeringGoal.None
            is Behavior.Kind.Flee -> senses.position(kind.from)?.let { SteeringGoal.Flee(it, kind.distance) } ?: SteeringGoal.None
            is Behavior.Kind.Patrol -> {
                val points = kind.points
                if (patrolIndex >= points.size) { if (kind.loop) patrolIndex = 0 else return Goals(SteeringGoal.None, face) }
                val target = points[patrolIndex]
                val d = if (mobility.mode == Mobility.Mode.AIR) target.distance(pose.position) else target.horizontalDistance(pose.position)
                if (d <= 0.1) {
                    patrolIndex += 1
                    if (patrolIndex >= points.size && !kind.loop) return Goals(SteeringGoal.None, face)
                    if (kind.loop) patrolIndex %= points.size
                }
                SteeringGoal.Seek(points[min(patrolIndex, points.size - 1)], 0.05)
            }
            is Behavior.Kind.PerchOn -> senses.position(kind.target)?.let { SteeringGoal.Seek(Vec3(it.x, max(it.y, mobility.altitude.start), it.z), 0.02) } ?: SteeringGoal.None
            is Behavior.Kind.FlyTo -> SteeringGoal.Seek(kind.point, 0.05)
        }
        return Goals(move, face)
    }
}
