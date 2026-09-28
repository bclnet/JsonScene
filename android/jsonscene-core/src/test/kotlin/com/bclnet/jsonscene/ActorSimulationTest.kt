package com.bclnet.jsonscene

import com.bclnet.jsonui.JsonAction
import com.bclnet.jsonui.JsonRuntime
import com.bclnet.jsonui.jsonArrayOf
import com.bclnet.jsonui.jsonObjectOf
import com.bclnet.jsonui.jsonOf
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ActorSimulationTest {
    private fun simulation(name: String): ActorSimulation {
        val document = TestSupport.document(name)
        val runtime = JsonRuntime(document)
        return ActorSimulation(SceneDocument.of(document)!!, runtime.context)
    }

    @Test fun startPlaysDefaultAnimationAndAppear() {
        val sim = simulation("scene-bush.json")
        val outputs = sim.start()
        assertEquals(ActorOutput.Play("bush", "idle", true, 0.25), outputs.first())
        assertTrue(outputs.contains(ActorOutput.Event("bush", "appear")))
    }

    @Test fun tapRunsHandlerAndCannedMind() {
        val sim = simulation("scene-bush.json")
        sim.start()
        val outputs = sim.fire("tap", "bush")
        assertEquals(jsonOf("singing"), sim.context.store.get("mood"))
        assertTrue(outputs.contains(ActorOutput.Say("bush", "Ask, and the bush shall sing.")))
        assertTrue(outputs.contains(ActorOutput.Play("bush", "sing", true, 1.5)))
        assertTrue(outputs.contains(ActorOutput.Sound("bush", "song", false)))
        val ended = sim.soundEnded("song", "bush")
        assertTrue(ended.contains(ActorOutput.Play("bush", "idle", true, 0.25)))
        assertEquals(jsonOf("quiet"), sim.context.store.get("mood"))
    }

    @Test fun moveToUserArrivesAndFiresArrived() {
        val sim = simulation("scene-snoopy.json")
        sim.start()
        sim.userPosition = Vec3(0.0, 1.6, 2.0)
        assertEquals(emptyList<ActorOutput>(), sim.perform(listOf(ActorCommand.MoveTo(ActorCommand.MoveTarget.Of(Target.User), 0.6)), "snoopy"))
        var arrived = false
        var outputs: List<ActorOutput> = emptyList()
        for (i in 0 until 400) {
            outputs = sim.tick(0.05)
            if (outputs.contains(ActorOutput.Event("snoopy", "arrived"))) { arrived = true; break }
        }
        assertTrue(arrived)
        val snoopy = sim["snoopy"]!!
        assertEquals(0.6, snoopy.pose.position.horizontalDistance(Vec3(0.0, 0.0, 2.0)), 0.05)
        assertEquals(jsonOf(false), sim.context.store.get("called"))
        assertTrue(outputs.contains(ActorOutput.Play("snoopy", "happy", false, 1.5)))
    }

    @Test fun locomotionAnimationFollowsMovement() {
        val sim = simulation("scene-snoopy.json")
        sim.start()
        sim.userPosition = Vec3(0.0, 1.6, 2.4)
        sim.perform(listOf(ActorCommand.MoveTo(ActorCommand.MoveTarget.Point(Vec3(0.0, 0.0, -2.0)), 0.05)), "snoopy")
        val animations = mutableListOf<String>()
        repeat(200) { for (o in sim.tick(0.05)) if (o is ActorOutput.Play && o.actor == "snoopy") animations += o.animation }
        assertTrue(animations.contains("walk"))
        assertEquals("walk", animations.first())
    }

    @Test fun nearAndFarEvents() {
        val sim = simulation("scene-snoopy.json")
        sim.start()
        val start = sim["snoopy"]!!.pose.position
        sim.userPosition = start + Vec3(0.0, 0.0, 0.5)
        var outputs = sim.tick(0.01)
        assertTrue(outputs.contains(ActorOutput.Event("snoopy", "near")))
        assertTrue(outputs.contains(ActorOutput.Sound("snoopy", "bark", false)))
        sim.userPosition = start + Vec3(0.0, 0.0, 5.0)
        outputs = sim.tick(0.01)
        assertTrue(outputs.contains(ActorOutput.Event("snoopy", "far")))
    }

    @Test fun waitDefersSteps() {
        val sim = simulation("scene-bush.json")
        var outputs = sim.perform(listOf(ActorCommand.Say("one"), ActorCommand.Wait(1.0), ActorCommand.Say("two")), "bush")
        assertEquals(listOf(ActorOutput.Say("bush", "one")), outputs)
        outputs = sim.tick(0.5)
        assertEquals(emptyList<ActorOutput>(), outputs)
        outputs = sim.tick(0.6)
        assertEquals(listOf(ActorOutput.Say("bush", "two")), outputs)
    }

    @Test fun hostActionDrivesActors() {
        val sim = simulation("scene-bush.json")
        val received = mutableListOf<ActorOutput>()
        sim.registerHostAction { received += it }
        sim.context.perform(JsonAction.Host("actor", mapOf("id" to JsonPrimitive("bush"), "do" to jsonArrayOf(jsonObjectOf("play" to "sing"), jsonObjectOf("sound" to "song")))))
        assertEquals(listOf(ActorOutput.Play("bush", "sing", true, 1.5), ActorOutput.Sound("bush", "song", false)), received)
        received.clear()
        sim.context.perform(JsonAction.Host("actor", mapOf("id" to JsonPrimitive("bush"), "say" to JsonPrimitive("short form"))))
        assertEquals(listOf(ActorOutput.Say("bush", "short form")), received)
    }

    @Test fun emitAndAnimationEnd() {
        val sim = simulation("scene-snoopy.json")
        val outputs = sim.animationEnded("happy", "snoopy")
        assertTrue(outputs.contains(ActorOutput.Event("snoopy", "animationEnd", mapOf("animation" to JsonPrimitive("happy")))))
        assertTrue(outputs.contains(ActorOutput.Event("snoopy", "settle", mapOf("animation" to JsonPrimitive("happy")))))
        assertTrue(outputs.contains(ActorOutput.Play("snoopy", "idle", true, 1.0)))
    }

    @Test fun behaviorsRespectStateConditions() {
        val sim = simulation("scene-snoopy.json")
        sim.start()
        sim.userPosition = Vec3(0.0, 1.6, 0.3)
        sim.context.store.set(true, "scared")
        repeat(60) { sim.tick(0.05) }
        val snoopy = sim["snoopy"]!!
        assertEquals("flee", snoopy.runner?.active?.type)
        assertTrue(snoopy.pose.position.horizontalDistance(Vec3(0.0, 0.0, 0.3)) > 1.0)
        sim.context.store.set(false, "scared")
        sim.tick(0.05)
        assertEquals("wander", snoopy.runner?.active?.type)
    }

    @Test fun senses() {
        val sim = simulation("scene-snoopy.json")
        sim.userPosition = Vec3.ZERO
        sim.userForward = Vec3(0.0, 0.0, 1.0)
        val s = sim.senseValues(sim["snoopy"]!!)
        assertEquals(Vec3(0.8, 0.0, 0.4).length, s["userDistance"]!!.numberValue!!, 0.01)
        assertEquals(jsonOf(false), s["userLooking"])
        assertEquals(jsonArrayOf("happy", "idle", "run", "walk"), s["animations"])
        assertNotNull((s["actors"] as kotlinx.serialization.json.JsonObject)["woodstock"])
        assertNull((s["actors"] as kotlinx.serialization.json.JsonObject)["snoopy"])
        assertEquals("morning", ActorSimulation.timeOfDay(9))
        assertEquals("night", ActorSimulation.timeOfDay(23))
    }
}
