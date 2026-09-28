/*
 * Mind.kt
 * JsonScene
 *
 * An actor's personality: the prompt material (`persona`, `senses`, `tools`),
 * the token `budget` it may spend, the events that wake it and the canned
 * rules used when no model is attached. `MindProvider` is what a host app
 * implements to plug in a model; `MindSession` enforces the budget and
 * cooldown and turns replies into actor commands.
 */
package com.bclnet.jsonscene

import com.bclnet.jsonui.jsonNumber
import com.bclnet.jsonui.parseJson
import com.bclnet.jsonui.toJsonString
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

data class Mind(
    val persona: String,
    val senses: List<String> = ALL_SENSES,
    val tools: List<String> = ALL_TOOLS,
    val budget: Budget = Budget(),
    val triggers: List<String> = DEFAULT_TRIGGERS,
    /** Seconds between `timer` turns when `timer` is a trigger. */
    val interval: Double = 30.0,
    val canned: List<Rule> = emptyList(),
) {
    data class Budget(
        /** Total tokens for the actor's lifetime; `null` is unlimited. */
        val tokens: Int? = null,
        /** Maximum tokens one turn may spend (prompt + reply). */
        val perTurn: Int = DEFAULT_PER_TURN,
        /** Minimum seconds between turns. */
        val cooldown: Double = DEFAULT_COOLDOWN,
    ) {
        val value: JsonElement
            get() {
                val o = linkedMapOf<String, JsonElement>()
                tokens?.let { o["tokens"] = jsonNumber(it.toDouble()) }
                if (perTurn != DEFAULT_PER_TURN) o["perTurn"] = jsonNumber(perTurn.toDouble())
                if (cooldown != DEFAULT_COOLDOWN) o["cooldown"] = jsonNumber(cooldown)
                return JsonObject(o)
            }

        companion object {
            const val DEFAULT_PER_TURN = 400
            const val DEFAULT_COOLDOWN = 5.0

            fun of(value: JsonElement): Budget {
                val o = value as? JsonObject ?: JsonObject(emptyMap())
                return Budget(o["tokens"]?.integerValue ?: value.integerValue, o["perTurn"]?.integerValue ?: DEFAULT_PER_TURN, o["cooldown"]?.numberValue ?: DEFAULT_COOLDOWN)
            }
        }
    }

    data class Rule(/** Regular expression matched (case insensitively) against the event name and any heard text. */ val match: String, val script: ActorScript) {
        val value: JsonElement get() = JsonObject(mapOf("match" to JsonPrimitive(match), "do" to script.value))

        fun matches(text: String): Boolean = runCatching { Regex(match, RegexOption.IGNORE_CASE).containsMatchIn(text) }
            .getOrElse { text.contains(match, ignoreCase = true) }

        companion object {
            fun of(value: JsonElement): Rule? {
                val o = value as? JsonObject ?: return null
                val m = o["match"]?.text ?: return null
                val s = o["do"]?.let { ActorScript.of(it) } ?: return null
                return Rule(m, s)
            }
        }
    }

    fun allows(command: ActorCommand): Boolean = tools.contains(command.verb)

    val value: JsonElement
        get() {
            val o = linkedMapOf<String, JsonElement>("persona" to JsonPrimitive(persona))
            if (senses != ALL_SENSES) o["senses"] = JsonArray(senses.map { JsonPrimitive(it) })
            if (tools != ALL_TOOLS) o["tools"] = JsonArray(tools.map { JsonPrimitive(it) })
            if (budget != Budget()) o["budget"] = budget.value
            if (triggers != DEFAULT_TRIGGERS) o["triggers"] = JsonArray(triggers.map { JsonPrimitive(it) })
            if (interval != 30.0) o["interval"] = jsonNumber(interval)
            if (canned.isNotEmpty()) o["canned"] = JsonArray(canned.map { it.value })
            return JsonObject(o)
        }

    companion object {
        val ALL_SENSES = listOf("userDistance", "userLooking", "timeOfDay", "state", "actors", "lastEvent")
        val ALL_TOOLS = listOf("say", "play", "sound", "moveTo", "lookAt", "set")
        val DEFAULT_TRIGGERS = listOf("tap", "near")

        fun of(value: JsonElement): Mind {
            val o = value as? JsonObject ?: JsonObject(emptyMap())
            fun strings(key: String, fallback: List<String>): List<String> = (o[key] as? JsonArray)?.mapNotNull { it.text } ?: fallback
            return Mind(
                persona = o["persona"]?.text ?: value.text ?: "",
                senses = strings("senses", ALL_SENSES),
                tools = strings("tools", ALL_TOOLS),
                budget = Budget.of(o["budget"] ?: JsonNull),
                triggers = strings("triggers", DEFAULT_TRIGGERS),
                interval = o["interval"]?.numberValue ?: 30.0,
                canned = (o["canned"] as? JsonArray)?.mapNotNull { Rule.of(it) } ?: emptyList(),
            )
        }
    }
}

// MARK: - Prompts and replies

/** What an actor perceives when a turn starts. */
data class MindPrompt(
    val actorId: String,
    val actorName: String? = null,
    val persona: String,
    /** The event that started the turn (`tap`, `near`, `spoken`, `timer`). */
    val event: String,
    /** Text heard from the user, for `spoken`. */
    val heard: String? = null,
    /** Sense name → value, limited to the mind's `senses`. */
    val senses: Map<String, JsonElement> = emptyMap(),
    val tools: List<String> = Mind.ALL_TOOLS,
    /** Earlier turns, oldest first. */
    val history: List<MindTurn> = emptyList(),
    val maxTokens: Int = Mind.Budget.DEFAULT_PER_TURN,
) {
    /**
     * Plain text version of the prompt, so any chat model can be used: the system prompt
     * describes the actor and the reply format, the user message carries the event.
     */
    val systemText: String
        get() = buildString {
            append("You are ${actorName ?: actorId}, a character in an augmented reality scene.\n")
            if (persona.isNotEmpty()) append("$persona\n")
            append("\nReply with ONLY a JSON array of commands, nothing else. Allowed commands:\n")
            for (tool in tools) {
                when (tool) {
                    "say" -> append("  {\"say\": \"short text to speak\"}\n")
                    "play" -> append("  {\"play\": \"animation name\"}\n")
                    "sound" -> append("  {\"sound\": \"sound name\"}\n")
                    "moveTo" -> append("  {\"moveTo\": \"user\"} or {\"moveTo\": [x, y, z]}\n")
                    "lookAt" -> append("  {\"lookAt\": \"user\"}\n")
                    "set" -> append("  {\"set\": {\"stateKey\": value}}\n")
                    "stop" -> append("  {\"stop\": true}\n")
                    "wait" -> append("  {\"wait\": seconds}\n")
                    else -> append("  {\"$tool\": ...}\n")
                }
            }
            (senses["animations"] as? JsonArray)?.let { append("Animations: ${it.mapNotNull { a -> a.text }.joinToString(", ")}\n") }
            (senses["sounds"] as? JsonArray)?.let { append("Sounds: ${it.mapNotNull { a -> a.text }.joinToString(", ")}\n") }
            append("Keep replies short: at most 3 commands and one sentence of speech.")
        }

    val userText: String
        get() {
            val o = linkedMapOf<String, JsonElement>("event" to JsonPrimitive(event))
            heard?.let { o["heard"] = JsonPrimitive(it) }
            for ((k, v) in senses) if (k != "animations" && k != "sounds") o[k] = v
            return JsonObject(o).toJsonString()
        }

    /** A rough token estimate (4 characters per token) used to enforce budgets before a call. */
    val estimatedTokens: Int get() = MindTurn.estimateTokens(systemText) + MindTurn.estimateTokens(userText) + history.sumOf { it.estimatedTokens }
}

data class MindReply(
    val commands: List<ActorCommand>,
    /** Tokens the provider reports for the turn; `null` means estimate. */
    val tokensUsed: Int? = null,
    /** The raw model text, kept for history. */
    val text: String = "",
) {
    companion object {
        /**
         * Parses a model's text reply: a JSON array of commands, tolerating code fences and prose around it.
         * Plain text with no JSON becomes a single `say`.
         */
        fun parse(text: String, tokensUsed: Int? = null): MindReply {
            var body = text.trim()
            if (body.startsWith("```")) {
                body = body.split("\n").drop(1).joinToString("\n")
                val fence = body.lastIndexOf("```")
                if (fence >= 0) body = body.substring(0, fence)
            }
            val start = body.indexOf('['); val end = body.lastIndexOf(']')
            if (start in 0 until end) {
                runCatching { parseJson(body.substring(start, end + 1)) }.getOrNull()?.let { value ->
                    return MindReply((value as? JsonArray)?.mapNotNull { ActorCommand.of(it) } ?: emptyList(), tokensUsed, text)
                }
            }
            val os = body.indexOf('{'); val oe = body.lastIndexOf('}')
            if (os in 0 until oe) {
                runCatching { parseJson(body.substring(os, oe + 1)) }.getOrNull()?.let { ActorCommand.of(it) }?.let { return MindReply(listOf(it), tokensUsed, text) }
            }
            val spoken = body.trim()
            return MindReply(if (spoken.isEmpty()) emptyList() else listOf(ActorCommand.Say(spoken)), tokensUsed, text)
        }
    }
}

/** One completed turn, kept as history. */
data class MindTurn(val event: String, val heard: String? = null, val replyText: String, val tokens: Int) {
    val estimatedTokens: Int get() = estimateTokens(event) + estimateTokens(heard ?: "") + estimateTokens(replyText)

    companion object {
        fun estimateTokens(text: String): Int = (text.toByteArray(Charsets.UTF_8).size + 3) / 4
    }
}

// MARK: - Providers

sealed class MindError(message: String) : Exception(message) {
    object BudgetExhausted : MindError("token budget exhausted")
    data class TurnTooLarge(val estimated: Int, val limit: Int) : MindError("turn of $estimated tokens exceeds $limit")
    data class CoolingDown(val remaining: Double) : MindError("cooling down for $remaining s")
    object NoProvider : MindError("no mind provider")
    data class Provider(val reason: String) : MindError(reason)
}

/** A source of replies. The library ships `CannedMindProvider`; a host app wires a model. */
fun interface MindProvider {
    fun respond(prompt: MindPrompt, completion: (Result<MindReply>) -> Unit)
}

/** Rule based replies from the `canned` list; costs no tokens. */
class CannedMindProvider(val rules: List<Mind.Rule>) : MindProvider {
    fun reply(prompt: MindPrompt): MindReply? {
        val text = listOf(prompt.event, prompt.heard ?: "").joinToString(" ")
        val rule = rules.firstOrNull { it.matches(text) } ?: return null
        return MindReply(rule.script.commands, 0, rule.script.value.toJsonString())
    }

    override fun respond(prompt: MindPrompt, completion: (Result<MindReply>) -> Unit) {
        completion(Result.success(reply(prompt) ?: MindReply(emptyList(), 0)))
    }
}

/** Tracks the tokens an actor has spent. */
class TokenLedger(val budget: Mind.Budget) {
    var spent: Int = 0
        private set
    var turns: Int = 0
        private set
    var lastTurnAt: Double? = null
        private set

    val remaining: Int? get() = budget.tokens?.let { maxOf(0, it - spent) }
    val isExhausted: Boolean get() = remaining?.let { it <= 0 } ?: false

    /** Checks whether a turn estimated at `tokens` may start at `now` (seconds). */
    fun check(estimated: Int, now: Double): MindError? {
        if (isExhausted) return MindError.BudgetExhausted
        lastTurnAt?.let { last -> if (now - last < budget.cooldown) return MindError.CoolingDown(budget.cooldown - (now - last)) }
        if (estimated > budget.perTurn) return MindError.TurnTooLarge(estimated, budget.perTurn)
        remaining?.let { if (estimated > it) return MindError.BudgetExhausted }
        return null
    }

    fun charge(tokens: Int, now: Double) {
        spent += maxOf(0, tokens)
        turns += 1
        lastTurnAt = now
    }
}

/** Runs one actor's mind: budget, cooldown, history and the fallback to canned rules. */
class MindSession(val actorId: String, val mind: Mind, var provider: MindProvider? = null) {
    val ledger = TokenLedger(mind.budget)
    private val _history = mutableListOf<MindTurn>()
    val history: List<MindTurn> get() = _history.toList()
    /** How many past turns are sent with each prompt. */
    var historyLimit = 6
    private val canned = CannedMindProvider(mind.canned)

    fun wakes(event: String): Boolean = mind.triggers.contains(event)

    fun prompt(event: String, heard: String? = null, actorName: String? = null, senses: Map<String, JsonElement>): MindPrompt {
        val allowed = senses.filterKeys { mind.senses.contains(it) || it == "animations" || it == "sounds" }
        return MindPrompt(actorId, actorName, mind.persona, event, heard, allowed, mind.tools, _history.takeLast(historyLimit), mind.budget.perTurn)
    }

    /**
     * Asks the model, or the canned rules when there is no model or no budget. The commands returned
     * are already filtered by the mind's `tools`.
     */
    fun respond(prompt: MindPrompt, now: Double = System.currentTimeMillis() / 1000.0, completion: (Result<List<ActorCommand>>) -> Unit) {
        val provider = provider
        if (provider == null) {
            completion(Result.success(filter(canned.reply(prompt)?.commands ?: emptyList())))
            return
        }
        ledger.check(prompt.estimatedTokens, now)?.let { error ->
            if (error is MindError.CoolingDown) { completion(Result.failure(error)); return }
            // No budget left: fall back to the canned rules so the actor still reacts.
            val reply = canned.reply(prompt)
            if (reply != null) completion(Result.success(filter(reply.commands))) else completion(Result.failure(error))
            return
        }
        provider.respond(prompt) { result ->
            result.fold(
                onSuccess = { reply ->
                    val tokens = reply.tokensUsed ?: (prompt.estimatedTokens + MindTurn.estimateTokens(reply.text))
                    ledger.charge(tokens, now)
                    _history += MindTurn(prompt.event, prompt.heard, reply.text, tokens)
                    completion(Result.success(filter(reply.commands)))
                },
                onFailure = { error ->
                    val reply = canned.reply(prompt)
                    if (reply != null) completion(Result.success(filter(reply.commands)))
                    else completion(Result.failure(MindError.Provider(error.message ?: error.toString())))
                },
            )
        }
    }

    internal fun filter(commands: List<ActorCommand>): List<ActorCommand> = commands.filter { mind.allows(it) }
}
