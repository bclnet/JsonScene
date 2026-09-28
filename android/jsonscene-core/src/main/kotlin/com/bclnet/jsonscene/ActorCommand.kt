/*
 * ActorCommand.kt
 * JsonScene
 *
 * The command vocabulary shared by event handlers, behaviors and minds:
 * `{ "play": "sing" }`, `{ "moveTo": "user" }`, `{ "say": "Hello" }`.
 * Anything that is not a command is a JsonUI action (`set`, scripts, host
 * actions), so an `on` handler mixes both freely.
 */
package com.bclnet.jsonscene

import com.bclnet.jsonui.JsonAction
import com.bclnet.jsonui.jsonNumber
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

sealed class ActorCommand {
    data class Play(val animation: String, val loop: Boolean? = null, val speed: Double? = null) : ActorCommand()
    data class Sound(val name: String, val loop: Boolean? = null) : ActorCommand()
    /** `null` stops every sound. */
    data class StopSound(val name: String?) : ActorCommand()
    data class Say(val text: String) : ActorCommand()
    data class MoveTo(val target: MoveTarget, val stopAt: Double? = null) : ActorCommand()
    data class LookAt(val target: Target) : ActorCommand()
    object Stop : ActorCommand() { override fun toString() = "Stop" }
    data class Wait(val seconds: Double) : ActorCommand()
    data class Emit(val event: String) : ActorCommand()

    sealed class MoveTarget {
        data class Point(val point: Vec3) : MoveTarget()
        data class Of(val target: Target) : MoveTarget()
    }

    val verb: String
        get() = when (this) {
            is Play -> "play"; is Sound -> "sound"; is StopSound -> "stopSound"; is Say -> "say"; is MoveTo -> "moveTo"
            is LookAt -> "lookAt"; is Stop -> "stop"; is Wait -> "wait"; is Emit -> "emit"
        }

    val value: JsonElement
        get() = when (this) {
            is Play -> JsonObject(linkedMapOf<String, JsonElement>("play" to JsonPrimitive(animation)).also { o ->
                loop?.let { o["loop"] = JsonPrimitive(it) }; speed?.let { o["speed"] = jsonNumber(it) }
            })
            is Sound -> JsonObject(linkedMapOf<String, JsonElement>("sound" to JsonPrimitive(name)).also { o -> loop?.let { o["loop"] = JsonPrimitive(it) } })
            is StopSound -> JsonObject(mapOf("stopSound" to (name?.let { JsonPrimitive(it) } ?: JsonPrimitive(true))))
            is Say -> JsonObject(mapOf("say" to JsonPrimitive(text)))
            is MoveTo -> JsonObject(linkedMapOf<String, JsonElement>().also { o ->
                o["moveTo"] = when (target) { is MoveTarget.Point -> target.point.value; is MoveTarget.Of -> target.target.value }
                stopAt?.let { o["stopAt"] = jsonNumber(it) }
            })
            is LookAt -> JsonObject(mapOf("lookAt" to target.value))
            is Stop -> JsonObject(mapOf("stop" to JsonPrimitive(true)))
            is Wait -> JsonObject(mapOf("wait" to jsonNumber(seconds)))
            is Emit -> JsonObject(mapOf("emit" to JsonPrimitive(event)))
        }

    companion object {
        val VERBS = listOf("play", "sound", "stopSound", "say", "moveTo", "lookAt", "stop", "wait", "emit")

        fun of(value: JsonElement): ActorCommand? {
            val o = value as? JsonObject ?: return null
            if (o.isEmpty()) return null
            // The verb is the one key that is a known command; extra keys are its options.
            val verb = VERBS.firstOrNull { o.containsKey(it) } ?: return null
            val arg = o[verb] ?: return null
            return when (verb) {
                "play" -> arg.text?.let { Play(it, o["loop"]?.flag, o["speed"]?.numberValue) }
                    ?: (arg as? JsonObject)?.let { p -> (p["animation"]?.text ?: p["name"]?.text)?.let { Play(it, p["loop"]?.flag, p["speed"]?.numberValue) } }
                "sound" -> arg.text?.let { Sound(it, o["loop"]?.flag) }
                    ?: (arg as? JsonObject)?.let { p -> (p["name"]?.text ?: p["sound"]?.text)?.let { Sound(it, p["loop"]?.flag) } }
                "stopSound" -> StopSound(arg.text)
                "say" -> arg.text?.let { Say(it) }
                "moveTo" -> when {
                    arg is JsonArray -> Vec3.of(arg)?.let { MoveTo(MoveTarget.Point(it), o["stopAt"]?.numberValue) }
                    arg.text != null -> Target.of(arg)?.let { MoveTo(MoveTarget.Of(it), o["stopAt"]?.numberValue) }
                    arg is JsonObject -> {
                        val t = arg["target"]?.let { Target.of(it) }
                        if (t != null) MoveTo(MoveTarget.Of(t), arg["stopAt"]?.numberValue)
                        else (arg["point"]?.let { Vec3.of(it) } ?: Vec3.of(arg))?.let { MoveTo(MoveTarget.Point(it), arg["stopAt"]?.numberValue) }
                    }
                    else -> null
                }
                "lookAt" -> Target.of(arg)?.let { LookAt(it) }
                "stop" -> Stop
                "wait" -> arg.numberValue?.let { Wait(it) }
                "emit" -> arg.text?.takeIf { it.isNotEmpty() }?.let { Emit(it) }
                else -> null
            }
        }
    }
}

/** One step of a handler: a command for the actor or a JsonUI action. */
sealed class ActorStep {
    data class Command(val command: ActorCommand) : ActorStep()
    data class Action(val action: JsonAction) : ActorStep()

    val value: JsonElement get() = when (this) { is Command -> command.value; is Action -> action.value }

    companion object {
        fun of(value: JsonElement): ActorStep? = ActorCommand.of(value)?.let { Command(it) } ?: JsonAction.of(value)?.let { Action(it) }
    }
}

/** A sequence of steps: a single step or an array of them. */
data class ActorScript(val steps: List<ActorStep>) {
    val isEmpty: Boolean get() = steps.isEmpty()
    val commands: List<ActorCommand> get() = steps.mapNotNull { (it as? ActorStep.Command)?.command }

    val value: JsonElement get() = if (steps.size == 1) steps[0].value else JsonArray(steps.map { it.value })

    companion object {
        fun ofCommands(commands: List<ActorCommand>) = ActorScript(commands.map { ActorStep.Command(it) })

        fun of(value: JsonElement): ActorScript? = when (value) {
            is JsonNull -> null
            is JsonArray -> ActorScript(value.mapNotNull { ActorStep.of(it) })
            else -> ActorStep.of(value)?.let { ActorScript(listOf(it)) }
        }

        /** Parses an `on` object: event name → script. */
        fun handlers(value: JsonElement): Map<String, ActorScript> =
            (value as? JsonObject)?.mapNotNull { (k, v) -> of(v)?.let { k to it } }?.toMap() ?: emptyMap()

        fun value(handlers: Map<String, ActorScript>): JsonElement = JsonObject(handlers.mapValues { it.value.value })
    }
}
