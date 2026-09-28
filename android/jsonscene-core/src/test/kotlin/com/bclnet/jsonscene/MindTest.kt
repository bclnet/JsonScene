package com.bclnet.jsonscene

import com.bclnet.jsonui.jsonArrayOf
import com.bclnet.jsonui.jsonObjectOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MindTest {
    @Test fun replyParsing() {
        assertEquals(listOf(ActorCommand.Say("hi"), ActorCommand.Play("sing")), MindReply.parse("[{\"say\": \"hi\"}, {\"play\": \"sing\"}]").commands)
        assertEquals(listOf(ActorCommand.Say("hi")), MindReply.parse("Sure!\n```json\n[{\"say\": \"hi\"}]\n```").commands)
        assertEquals(listOf(ActorCommand.LookAt(Target.User)), MindReply.parse("{\"lookAt\": \"user\"}").commands)
        assertEquals(listOf(ActorCommand.Say("Woof.")), MindReply.parse("Woof.").commands)
        assertEquals(emptyList<ActorCommand>(), MindReply.parse("  ").commands)
        assertEquals(emptyList<ActorCommand>(), MindReply.parse("[{\"nuke\": 1}]").commands)
    }

    @Test fun promptText() {
        val prompt = MindPrompt("bush", "Bush", "A shrub.", "tap", senses = mapOf("userDistance" to com.bclnet.jsonui.jsonOf(1.5), "animations" to jsonArrayOf("idle", "sing")), tools = listOf("say", "play"))
        assertTrue(prompt.systemText.contains("You are Bush"))
        assertTrue(prompt.systemText.contains("{\"say\""))
        assertTrue(prompt.systemText.contains("{\"play\""))
        assertFalse(prompt.systemText.contains("{\"moveTo\""))
        assertTrue(prompt.systemText.contains("Animations: idle, sing"))
        assertEquals("{\"event\":\"tap\",\"userDistance\":1.5}", prompt.userText)
        assertTrue(prompt.estimatedTokens > 20)
    }

    @Test fun ledger() {
        val ledger = TokenLedger(Mind.Budget(1000, 300, 5.0))
        assertNull(ledger.check(200, 0.0))
        assertEquals(MindError.TurnTooLarge(400, 300), ledger.check(400, 0.0))
        ledger.charge(600, 0.0)
        assertEquals(400, ledger.remaining)
        assertEquals(MindError.CoolingDown(3.0), ledger.check(100, 2.0))
        assertNull(ledger.check(100, 6.0))
        ledger.charge(400, 6.0)
        assertTrue(ledger.isExhausted)
        assertEquals(MindError.BudgetExhausted, ledger.check(1, 100.0))
        assertNull(TokenLedger(Mind.Budget()).remaining)
    }

    @Test fun cannedProvider() {
        val mind = Mind.of(jsonObjectOf("persona" to "x", "canned" to jsonArrayOf(
            jsonObjectOf("match" to "^tap", "do" to jsonObjectOf("play" to "sing")),
            jsonObjectOf("match" to "hello|hi", "do" to jsonArrayOf(jsonObjectOf("say" to "hey"), jsonObjectOf("set" to jsonObjectOf("a" to 1)))),
        )))
        val canned = CannedMindProvider(mind.canned)
        assertEquals(listOf(ActorCommand.Play("sing")), canned.reply(MindPrompt("a", persona = "", event = "tap"))?.commands)
        assertEquals(listOf(ActorCommand.Say("hey")), canned.reply(MindPrompt("a", persona = "", event = "spoken", heard = "Well HELLO there"))?.commands)
        assertNull(canned.reply(MindPrompt("a", persona = "", event = "near")))
    }

    @Test fun sessionChargesBudgetAndFiltersTools() {
        val mind = Mind.of(jsonObjectOf("persona" to "p", "tools" to jsonArrayOf("say"), "budget" to jsonObjectOf("tokens" to 500, "perTurn" to 400, "cooldown" to 0),
            "canned" to jsonArrayOf(jsonObjectOf("match" to ".*", "do" to jsonObjectOf("say" to "canned")))))
        val session = MindSession("a", mind, MindProvider { _, completion ->
            completion(Result.success(MindReply(listOf(ActorCommand.Say("hi"), ActorCommand.MoveTo(ActorCommand.MoveTarget.Of(Target.User))), 490, "[…]")))
        })
        var got: List<ActorCommand> = emptyList()
        session.respond(session.prompt("tap", senses = emptyMap()), 0.0) { got = it.getOrDefault(emptyList()) }
        assertEquals(listOf(ActorCommand.Say("hi")), got)
        assertEquals(490, session.ledger.spent)
        assertEquals(1, session.history.size)
        session.respond(session.prompt("tap", senses = emptyMap()), 1.0) { got = it.getOrDefault(emptyList()) }
        assertEquals(listOf(ActorCommand.Say("canned")), got)
        assertEquals(490, session.ledger.spent)
    }

    @Test fun sessionWithoutProviderUsesCannedAndErrorsFallBack() {
        val mind = Mind.of(jsonObjectOf("persona" to "p", "canned" to jsonArrayOf(jsonObjectOf("match" to "near", "do" to jsonObjectOf("lookAt" to "user")))))
        val session = MindSession("a", mind)
        var got: List<ActorCommand>? = null
        session.respond(session.prompt("near", senses = emptyMap())) { got = it.getOrNull() }
        assertEquals(listOf(ActorCommand.LookAt(Target.User)), got)
        val failing = MindSession("a", mind, MindProvider { _, completion -> completion(Result.failure(IllegalStateException("boom"))) })
        failing.respond(failing.prompt("near", senses = emptyMap())) { got = it.getOrNull() }
        assertEquals(listOf(ActorCommand.LookAt(Target.User)), got)
        var error: Throwable? = null
        failing.respond(failing.prompt("tap", senses = emptyMap()), 100.0) { error = it.exceptionOrNull() }
        assertTrue(error is MindError.Provider)
    }

    @Test fun sensesAreFilteredByMind() {
        val session = MindSession("a", Mind.of(jsonObjectOf("persona" to "p", "senses" to jsonArrayOf("userDistance"))))
        val prompt = session.prompt("tap", senses = mapOf("userDistance" to com.bclnet.jsonui.jsonOf(1), "state" to jsonObjectOf("x" to 1), "animations" to jsonArrayOf("idle")))
        assertEquals(listOf("animations", "userDistance"), prompt.senses.keys.sorted())
    }

    @Test fun mindRoundTrip() {
        val mind = TestSupport.example("scene-bush.json").actor("bush")!!.mind!!
        assertEquals(mind, Mind.of(mind.value))
    }
}
