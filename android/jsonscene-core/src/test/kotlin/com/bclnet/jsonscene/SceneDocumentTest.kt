package com.bclnet.jsonscene

import com.bclnet.jsonui.JsonDocument
import com.bclnet.jsonui.JsonNode
import com.bclnet.jsonui.jsonArrayOf
import com.bclnet.jsonui.jsonObjectOf
import com.bclnet.jsonui.jsonOf
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SceneDocumentTest {
    @Test fun bushExample() {
        val scene = TestSupport.example("scene-bush.json")
        assertEquals(emptyList<String>(), scene.issues)
        assertEquals(SceneDocument.Anchor.CODE, scene.anchor)
        assertEquals(1, scene.actors.size)
        val bush = scene.actor("bush")!!
        assertEquals("Singing Bush", bush.name)
        assertNull(bush.mobility)
        assertFalse(bush.isMobile)
        val body = bush.body!!
        assertTrue(body.model.primary!!.endsWith("BoxAnimated.glb"))
        assertEquals(0.15, body.scale, 0.0)
        assertEquals(AnimationClip(AnimationClip.Clip.Index(0), loop = true, speed = 1.5), body.animations["sing"])
        assertEquals("idle", body.defaultAnimation)
        assertEquals(true, body.sounds["song"]?.spatial)
        val mind = bush.mind!!
        assertEquals(Mind.Budget(20000, 300, 8.0), mind.budget)
        assertEquals(listOf("say", "play", "sound"), mind.tools)
        assertEquals(listOf("tap", "spoken"), mind.triggers)
        assertEquals(3, mind.canned.size)
        assertEquals(2, bush.on["soundEnd"]?.steps?.size)
        assertEquals(listOf(ActorCommand.Play("idle")), bush.on["appear"]?.commands)
    }

    @Test fun snoopyExample() {
        val scene = TestSupport.example("scene-snoopy.json")
        assertEquals(emptyList<String>(), scene.issues)
        assertEquals(SceneDocument.Anchor.FLOOR, scene.anchor)
        assertEquals(Bounds.Radius(2.5), scene.bounds)
        val snoopy = scene.actor("snoopy")!!
        assertEquals(180.0, snoopy.transform.rotation.yaw, 0.0)
        assertEquals(Mobility.Mode.GROUND, snoopy.mobility?.mode)
        assertEquals(0.6, snoopy.mobility?.speed)
        assertEquals("walk", snoopy.mobility?.moveAnimation)
        assertEquals(listOf("flee", "approach", "lookAt", "wander"), snoopy.behaviors.map { it.type })
        assertEquals(10, snoopy.behaviors[0].priority)
        assertEquals(JsonPrimitive("\$scared"), snoopy.behaviors[0].`when`)
        assertEquals(Behavior.Kind.Approach(Target.User, 0.6), snoopy.behaviors[1].kind)
        assertEquals("b_Head_05", snoopy.body?.sockets?.get("nose"))
        val woodstock = scene.actor("woodstock")!!
        assertEquals(Mobility.Mode.AIR, woodstock.mobility?.mode)
        assertEquals(0.4..1.4, woodstock.mobility?.altitude)
        assertEquals("fly", woodstock.mobility?.moveAnimation)
        assertEquals(Behavior.Kind.PerchOn(Target.ActorId("snoopy"), "back"), woodstock.behaviors[0].kind)
    }

    @Test fun documentRoot() {
        val document = TestSupport.document("scene-snoopy.json")
        assertEquals("Scene", document.root.type)
        assertEquals(jsonOf(false), document.header.state["called"])
        assertNotNull(SceneDocument.of(document))
        assertNull(SceneDocument.of(JsonDocument(root = JsonNode(JsonNode.Kind.Form))))
    }

    @Test fun issues() {
        val scene = SceneDocument.parse("""
        { "type": "Scene", "anchor": "roof", "actors": [
            { "body": { "animations": { "bad": true } } },
            { "id": "a", "mobility": "swim" },
            { "id": "a", "behaviors": [ { "type": "wander" }, { "type": "teleport" } ] },
            "nope"
        ] }
        """)
        assertEquals(SceneDocument.Anchor.CODE, scene.anchor)
        assertEquals(listOf("actor0", "a", "a"), scene.actors.map { it.id })
        for (expected in listOf("unknown anchor", "missing id", "no model", "not a clip", "unknown mobility", "duplicate actor id", "behaviors[1]", "no mobility", "actors[3]")) {
            assertTrue("issue containing '$expected' in ${scene.issues}", scene.issues.any { it.contains(expected) })
        }
    }

    @Test fun roundTrip() {
        for (name in listOf("scene-bush.json", "scene-snoopy.json")) {
            val scene = TestSupport.example(name)
            val again = SceneDocument.of(scene.node)
            assertEquals(name, scene.actors, again.actors)
            assertEquals(name, scene.bounds, again.bounds)
            assertEquals(name, scene.anchor, again.anchor)
        }
    }

    @Test fun modelRef() {
        assertEquals(mapOf("glb" to "https://x/y/Fox.glb?x=1"), ModelRef.of(JsonPrimitive("https://x/y/Fox.glb?x=1")).sources)
        assertEquals(mapOf("glb" to "https://x/y/fox"), ModelRef.of(JsonPrimitive("https://x/y/fox")).sources)
        val multi = ModelRef.of(jsonObjectOf("glb" to "a.glb", "USDZ" to "a.usdz"))
        assertEquals("a.usdz", multi.source(listOf("usdz", "glb"))?.second)
        assertNull(multi.source(listOf("scn")))
        assertEquals("a.glb", multi.primary)
        assertEquals(JsonPrimitive("a.usdz"), ModelRef.of(JsonPrimitive("a.usdz")).value)
        assertTrue(ModelRef.of(jsonOf(null)).isEmpty)
    }

    @Test fun behaviorParsing() {
        assertEquals(Behavior.Kind.Idle, Behavior.of(JsonPrimitive("idle"))?.kind)
        assertEquals(Behavior.Kind.Patrol(listOf(Vec3.ZERO, Vec3(1.0, 0.0, 1.0)), false), Behavior.of(jsonObjectOf("type" to "patrol", "points" to jsonArrayOf(jsonArrayOf(0, 0), jsonArrayOf(1, 1)), "loop" to false))?.kind)
        assertNull(Behavior.of(jsonObjectOf("type" to "patrol")))
        assertNull(Behavior.of(jsonObjectOf("type" to "flyTo")))
        assertEquals(Behavior.Kind.Wander(null, 2.0..2.0), Behavior.of(jsonObjectOf("type" to "wander", "pause" to 2))?.kind)
        assertEquals(Behavior.Kind.Flee(Target.ActorId("cat"), 1.5), Behavior.of(jsonObjectOf("type" to "flee", "from" to "cat"))?.kind)
        for (b in listOf(Behavior.of(jsonObjectOf("type" to "approach", "stopAt" to 0.3, "priority" to 2, "when" to "\$x"))!!, Behavior.of(jsonObjectOf("type" to "perchOn", "target" to "s", "socket" to "back"))!!)) {
            assertEquals(b, Behavior.of(b.value))
        }
    }

    @Test fun mobilityDefaults() {
        val air = Mobility.of(jsonObjectOf("mode" to "air"))!!
        assertEquals("fly", air.moveAnimation)
        assertEquals(Mobility.DEFAULT_ALTITUDE, air.altitude)
        assertEquals(Mobility.DEFAULT_SPEED, Mobility.of(JsonPrimitive("ground"))?.speed)
        assertEquals(Mobility.Mode.GROUND, Mobility.of(jsonObjectOf("speed" to 2))?.mode)
        assertNull(Mobility.of(jsonObjectOf("mode" to "teleport")))
        assertEquals(air, Mobility.of(air.value))
    }
}
