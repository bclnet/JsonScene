/*
 * ActorSimulation.kt
 * JsonScene
 *
 * The renderer independent part of a running scene. Renderers feed it the
 * frame time and the viewer position and read back what to show: every
 * actor's pose, which animation should play, and the outputs (commands and
 * events) produced by handlers, behaviors and minds.
 */
package com.bclnet.jsonscene

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
import com.bclnet.jsonui.JsonAction
import com.bclnet.jsonui.JsonActionHandler
import com.bclnet.jsonui.JsonContext
import com.bclnet.jsonui.JsonLogLevel
import com.bclnet.jsonui.isTruthy
import com.bclnet.jsonui.jsonNumber
import com.bclnet.jsonui.stringValue
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.util.Calendar
import kotlin.math.round

/** Something the renderer must do for an actor. */
sealed class ActorOutput {
    data class Play(val actor: String, val animation: String, val loop: Boolean, val speed: Double) : ActorOutput()
    data class Sound(val actor: String, val name: String, val loop: Boolean) : ActorOutput()
    data class StopSound(val actor: String, val name: String?) : ActorOutput()
    data class Say(val actor: String, val text: String) : ActorOutput()
    data class Event(val actor: String, val name: String, val payload: Map<String, JsonElement> = emptyMap()) : ActorOutput()
}

class ActorState internal constructor(val actor: Actor, bounds: Bounds) {
    var pose: ActorPose = ActorPose(actor.transform.position, actor.transform.rotation.yaw)
    val runner: BehaviorRunner? = actor.mobility?.takeIf { it.mode != Mobility.Mode.NONE }?.let { BehaviorRunner(actor.behaviors, it, bounds) }
    val mind: MindSession? = actor.mind?.let { MindSession(actor.id, it) }
    /** Animation currently requested by locomotion, so it is only re-sent when it changes. */
    var locomotionAnimation: String? = null
        private set
    /** An explicit `moveTo` command in progress; it overrides behaviors until arrival or `stop`. */
    var commandGoal: SteeringGoal? = null
    var commandTarget: Target? = null
    var commandStopAt: Double = 0.6
    /** True while an explicit `play` is running and locomotion should not replace it. */
    var playingCommand = false
    var isNear = false
    /** Queued steps waiting on a `wait`. */
    internal val pending = mutableListOf<Pair<Double, List<ActorStep>>>()

    internal fun setLocomotionAnimation(name: String?): Boolean {
        if (name == locomotionAnimation) return false
        locomotionAnimation = name
        return true
    }
}

class ActorSimulation(val scene: SceneDocument, val context: JsonContext) {
    private val _actors = LinkedHashMap<String, ActorState>()
    val actors: Map<String, ActorState> get() = _actors
    val order: List<String> = scene.actors.map { it.id }
    var userPosition: Vec3? = null
    /** Forward direction of the viewer, for `userLooking`. */
    var userForward: Vec3? = null
    var time: Double = 0.0
        private set
    /** Attach a model here; actors with a `mind` use it. */
    var mindProvider: MindProvider? = null
        set(value) { field = value; _actors.values.forEach { it.mind?.provider = value } }
    /** Receives outputs produced after an asynchronous mind reply; the renderer sets this. */
    var asyncOutputs: ((List<ActorOutput>) -> Unit)? = null
    private val timerDue = HashMap<String, Double>()

    init {
        for (actor in scene.actors) _actors[actor.id] = ActorState(actor, scene.bounds)
    }

    operator fun get(id: String): ActorState? = _actors[id]

    val senses: BehaviorRunner.Senses get() = BehaviorRunner.Senses(userPosition, _actors.mapValues { it.value.pose.position })

    // MARK: - Events and commands

    /** Fires an actor event: runs its `on` handler and wakes its mind when the event is a trigger. */
    fun fire(event: String, actorId: String, payload: Map<String, JsonElement> = emptyMap(), heard: String? = null): List<ActorOutput> {
        val state = _actors[actorId] ?: return emptyList()
        val outputs = mutableListOf<ActorOutput>(ActorOutput.Event(actorId, event, payload))
        state.actor.on[event]?.let { outputs += run(it.steps, state, payload) }
        state.mind?.let { if (it.wakes(event)) outputs += think(state, event, heard) }
        return outputs
    }

    /** Fires a scene level event (`appear`, `disappear`, `tap` on empty space). */
    fun fireScene(event: String) {
        val script = scene.on[event] ?: return
        for (step in script.steps) if (step is ActorStep.Action) context.perform(step.action)
    }

    /** Runs commands on an actor (the `actor` host action, a mind reply). */
    fun perform(commands: List<ActorCommand>, actorId: String): List<ActorOutput> {
        val state = _actors[actorId] ?: return emptyList()
        return run(commands.map { ActorStep.Command(it) }, state, emptyMap())
    }

    /** Outputs for the `appear` event of every actor, including their default animation. */
    fun start(): List<ActorOutput> {
        val outputs = mutableListOf<ActorOutput>()
        for (id in order) {
            val state = _actors[id] ?: continue
            val name = state.actor.body?.defaultAnimation
            val clip = name?.let { state.actor.body?.animations?.get(it) }
            if (name != null && clip != null) {
                state.setLocomotionAnimation(name)
                outputs += ActorOutput.Play(id, name, clip.loop || name == state.actor.mobility?.idleAnimation, clip.speed)
            }
            outputs += fire("appear", id)
            state.actor.mind?.let { if (it.triggers.contains("timer")) timerDue[id] = time + it.interval }
        }
        fireScene("appear")
        return outputs
    }

    private fun run(steps: List<ActorStep>, state: ActorState, payload: Map<String, JsonElement>): List<ActorOutput> {
        val outputs = mutableListOf<ActorOutput>()
        var index = 0
        while (index < steps.size) {
            val step = steps[index]
            index += 1
            when (step) {
                is ActorStep.Action -> context.perform(step.action)
                is ActorStep.Command -> when (val command = step.command) {
                    is ActorCommand.Play -> {
                        val clip = state.actor.body?.animations?.get(command.animation)
                        val loop = command.loop ?: clip?.loop ?: false
                        state.playingCommand = !loop
                        state.setLocomotionAnimation(command.animation)
                        outputs += ActorOutput.Play(state.actor.id, command.animation, loop, command.speed ?: clip?.speed ?: 1.0)
                    }
                    is ActorCommand.Sound -> outputs += ActorOutput.Sound(state.actor.id, command.name, command.loop ?: state.actor.body?.sounds?.get(command.name)?.loop ?: false)
                    is ActorCommand.StopSound -> outputs += ActorOutput.StopSound(state.actor.id, command.name)
                    is ActorCommand.Say -> outputs += ActorOutput.Say(state.actor.id, context.resolve(JsonPrimitive(command.text)).stringValue ?: command.text)
                    is ActorCommand.MoveTo -> if (state.actor.isMobile) {
                        state.commandStopAt = command.stopAt ?: 0.6
                        when (val target = command.target) {
                            is ActorCommand.MoveTarget.Point -> { state.commandGoal = SteeringGoal.Seek(Vec3.of(target.point), command.stopAt ?: 0.05); state.commandTarget = null }
                            is ActorCommand.MoveTarget.Of -> {
                                state.commandTarget = target.target
                                state.commandGoal = senses.position(target.target)?.let { SteeringGoal.Seek(it, command.stopAt ?: 0.6) } ?: SteeringGoal.None
                            }
                        }
                    }
                    is ActorCommand.LookAt -> senses.position(command.target)?.let { p ->
                        val to = p - state.pose.position
                        if (to.horizontalLength > 1e-6) state.pose = state.pose.copy(heading = to.yaw)
                    }
                    is ActorCommand.Stop -> { state.commandGoal = null; state.commandTarget = null }
                    is ActorCommand.Wait -> {
                        state.pending += (time + command.seconds) to steps.subList(index, steps.size).toList()
                        return outputs
                    }
                    is ActorCommand.Emit -> outputs += fire(command.event, state.actor.id, payload)
                    is ActorCommand.Behave -> {
                        // A mind (or a handler) picks a behavior: by name from the actor's list, or described inline.
                        val spec = command.behavior
                        val name = spec.text
                        state.runner?.override = when {
                            name == "idle" || name == "none" -> Behavior(Behavior.Kind.Idle)
                            name != null -> state.actor.behaviors.firstOrNull { it.type == name } ?: Behavior.kind(name, JsonObject(emptyMap()))?.let { Behavior(it) } ?: state.runner?.override
                            else -> Behavior.of(spec) ?: state.runner?.override
                        }
                        state.commandGoal = null
                        state.commandTarget = null
                    }
                }
            }
        }
        return outputs
    }

    private fun think(state: ActorState, event: String, heard: String?): List<ActorOutput> {
        val session = state.mind ?: return emptyList()
        val prompt = session.prompt(event, heard, state.actor.name, senseValues(state))
        val outputs = mutableListOf<ActorOutput>()
        var finished = false
        session.respond(prompt, System.currentTimeMillis() / 1000.0) { result ->
            val current = _actors[state.actor.id] ?: return@respond
            result.fold(
                onSuccess = { commands ->
                    val produced = run(commands.map { ActorStep.Command(it) }, current, emptyMap())
                    if (finished) asyncOutputs?.invoke(produced) else outputs += produced
                },
                onFailure = { error -> context.log(JsonLogLevel.Warn, "mind of ${state.actor.id}: ${error.message}") },
            )
        }
        finished = true
        return outputs
    }

    fun senseValues(state: ActorState): Map<String, JsonElement> {
        val s = linkedMapOf<String, JsonElement>()
        userPosition?.let { user ->
            s["userDistance"] = jsonNumber(round(user.distance(state.pose.position) * 100) / 100)
            userForward?.let { forward ->
                val to = (state.pose.position - user).normalized
                s["userLooking"] = JsonPrimitive(forward.normalized.dot(to) > 0.8)
            }
        }
        s["timeOfDay"] = JsonPrimitive(timeOfDay(Calendar.getInstance().get(Calendar.HOUR_OF_DAY)))
        s["state"] = context.store.snapshot
        s["actors"] = JsonObject(_actors.filterKeys { it != state.actor.id }.mapValues { it.value.pose.position.value })
        state.actor.body?.let { body ->
            s["animations"] = JsonArray(body.animations.keys.sorted().map { JsonPrimitive(it) })
            s["sounds"] = JsonArray(body.sounds.keys.sorted().map { JsonPrimitive(it) })
        }
        if (state.actor.isMobile) s["behaviors"] = JsonArray((state.actor.behaviors.map { it.type } + "idle").toSet().sorted().map { JsonPrimitive(it) })
        return s
    }

    // MARK: - Frames

    /** Advances every actor by `dt` seconds and returns what changed. */
    fun tick(dt: Double): List<ActorOutput> {
        time += dt
        val outputs = mutableListOf<ActorOutput>()
        val senses = this.senses
        for (id in order) {
            val state = _actors[id] ?: continue
            // Delayed steps.
            val due = state.pending.filter { it.first <= time }
            if (due.isNotEmpty()) {
                state.pending.removeAll { it.first <= time }
                for ((_, steps) in due) outputs += run(steps, state, emptyMap())
            }
            // Near / far.
            userPosition?.let { user ->
                val near = user.distance(state.pose.position) <= state.actor.nearDistance
                if (near != state.isNear) {
                    state.isNear = near
                    outputs += fire(if (near) "near" else "far", id)
                }
            }
            // Timer minds.
            val dueAt = timerDue[id]
            val mind = state.actor.mind
            if (dueAt != null && time >= dueAt && mind != null) {
                timerDue[id] = time + mind.interval
                outputs += fire("timer", id)
            }
            // Movement.
            val runner = state.runner ?: continue
            val mobility = state.actor.mobility ?: continue
            val moveGoal: SteeringGoal
            var faceGoal: SteeringGoal? = null
            val commandGoal = state.commandGoal
            if (commandGoal != null) {
                var goal: SteeringGoal = commandGoal
                state.commandTarget?.let { t -> senses.position(t)?.let { goal = SteeringGoal.Seek(it, state.commandStopAt); state.commandGoal = goal } }
                moveGoal = goal
            } else {
                val goals = runner.goals(state.pose, senses, time) { behavior -> behavior.`when`?.let { context.resolve(it).isTruthy } ?: true }
                moveGoal = goals.move
                faceGoal = goals.face
            }
            var result = Steering.step(state.pose, moveGoal, mobility, runner.bounds, dt)
            if (!result.moving) faceGoal?.let { result = result.copy(pose = Steering.step(result.pose, it, mobility, runner.bounds, dt).pose) }
            state.pose = result.pose
            if (result.arrived && state.commandGoal != null) {
                state.commandGoal = null
                state.commandTarget = null
                outputs += fire("arrived", id)
            }
            // Locomotion animation.
            if (!state.playingCommand) {
                val wanted = if (result.moving) mobility.moveAnimation else mobility.idleAnimation
                val clip = state.actor.body?.animations?.get(wanted)
                if (clip != null && state.setLocomotionAnimation(wanted)) outputs += ActorOutput.Play(id, wanted, true, clip.speed)
            }
        }
        return outputs
    }

    /** The renderer reports a one-shot animation finishing so locomotion can resume. */
    fun animationEnded(animation: String, actorId: String): List<ActorOutput> {
        val state = _actors[actorId] ?: return emptyList()
        state.playingCommand = false
        state.setLocomotionAnimation(null)
        return fire("animationEnd", actorId, mapOf("animation" to JsonPrimitive(animation)))
    }

    fun soundEnded(sound: String, actorId: String): List<ActorOutput> = fire("soundEnd", actorId, mapOf("sound" to JsonPrimitive(sound)))

    // MARK: - Host action

    /**
     * Registers the `actor` host action: `{ "name": "actor", "args": { "id": "bush", "do": [...] } }`.
     * Outputs are delivered through `handler`.
     */
    fun registerHostAction(handler: (List<ActorOutput>) -> Unit) {
        context.runtime.actions.register("actor", JsonActionHandler { _, args, _ ->
            val o = args as? JsonObject ?: return@JsonActionHandler null
            val id = o["id"]?.text ?: return@JsonActionHandler null
            val commands = o["do"]?.let { ActorScript.of(it) }?.commands ?: ActorCommand.of(o)?.let { listOf(it) } ?: emptyList()
            handler(perform(commands, id))
            null
        })
    }

    companion object {
        fun timeOfDay(hour: Int): String = when (hour) {
            in 5..11 -> "morning"
            in 12..16 -> "afternoon"
            in 17..20 -> "evening"
            else -> "night"
        }
    }
}
