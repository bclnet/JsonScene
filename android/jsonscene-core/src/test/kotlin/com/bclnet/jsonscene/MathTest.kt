package com.bclnet.jsonscene

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
import kotlin.random.Random

class MathTest {
    @Test fun vec3Parsing() {
        assertEquals(Vec3(1.0, 2.0, 3.0), Vec3.of(jsonArrayOf(1, 2, 3)))
        assertEquals(Vec3(1.0, 0.0, 3.0), Vec3.of(jsonArrayOf(1, 3)))
        assertEquals(Vec3(1.0, 0.0, 2.0), Vec3.of(jsonObjectOf("x" to 1, "z" to 2)))
        assertNull(Vec3.of(JsonPrimitive("nope")))
        assertNull(Vec3.of(jsonArrayOf(1, "a")))
    }

    @Test fun yawAndDirection() {
        assertEquals(0.0, Vec3(0.0, 0.0, 1.0).yaw, 1e-9)
        assertEquals(90.0, Vec3(1.0, 0.0, 0.0).yaw, 1e-9)
        assertEquals(180.0, Vec3(0.0, 0.0, -1.0).yaw, 1e-9)
        val d = Vec3.direction(90.0)
        assertEquals(1.0, d.x, 1e-9)
        assertEquals(0.0, d.z, 1e-9)
    }

    @Test fun angleNormalizeAndTurn() {
        assertEquals(-170.0, Angle.normalize(190.0), 1e-9)
        assertEquals(170.0, Angle.normalize(-190.0), 1e-9)
        assertEquals(20.0, Angle.delta(170.0, -170.0), 1e-9)
        assertEquals(30.0, Angle.turn(0.0, 90.0, 30.0), 1e-9)
        assertEquals(-30.0, Angle.turn(0.0, -90.0, 30.0), 1e-9)
        assertEquals(20.0, Angle.turn(0.0, 20.0, 30.0), 1e-9)
    }

    @Test fun bounds() {
        val r = Bounds.Radius(2.0)
        assertTrue(r.contains(Vec3(1.0, 5.0, 1.0)))
        assertFalse(r.contains(Vec3(2.0, 0.0, 2.0)))
        val c = r.clamp(Vec3(3.0, 1.0, 4.0))
        assertEquals(2.0, c.horizontalLength, 1e-9)
        assertEquals(1.0, c.y, 0.0)
        val box = Bounds.of(jsonObjectOf("min" to jsonArrayOf(-1, 0, -1), "max" to jsonArrayOf(1, 0, 1)))!!
        assertEquals(Vec3(1.0, 0.0, -1.0), box.clamp(Vec3(5.0, 0.0, -5.0)))
        val random = Random(7)
        repeat(50) { assertTrue(box.contains(box.randomPoint(random))); assertTrue(r.contains(r.randomPoint(random))) }
        assertEquals(Bounds.Radius(3.0), Bounds.of(jsonOf(3)))
        assertNull(Bounds.of(JsonPrimitive("x")))
    }

    @Test fun transformRoundTrip() {
        val t = Transform.of(jsonObjectOf("position" to jsonArrayOf(1, 2, 3), "rotation" to jsonArrayOf(0, 90, 0), "scale" to 2))
        assertEquals(Vec3(1.0, 2.0, 3.0), t.position)
        assertEquals(90.0, t.rotation.yaw, 0.0)
        assertEquals(t, Transform.of(t.value))
        assertEquals(Transform.IDENTITY, Transform.of(jsonOf(null)))
        assertEquals(45.0, Rotation.of(jsonOf(45))?.yaw)
        assertNotNull(Rotation.of(jsonObjectOf("yaw" to 1)))
    }
}
