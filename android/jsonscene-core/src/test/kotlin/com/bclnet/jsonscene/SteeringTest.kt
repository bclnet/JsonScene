package com.bclnet.jsonscene

import com.bclnet.jsonmind.Target

import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SteeringTest {
    private val ground = Mobility(Mobility.Mode.GROUND, speed = 1.0, turnRate = 360.0)
    private val air = Mobility(Mobility.Mode.AIR, speed = 1.0, turnRate = 720.0, altitude = 0.5..1.5)

    @Test fun seekArrivesAndStopsShort() {
        var pose = ActorPose(Vec3.ZERO, 0.0)
        var arrived = false
        var steps = 0
        while (!arrived && steps < 100) {
            val r = Steering.step(pose, SteeringGoal.Seek(Vec3(0.0, 0.0, 3.0), 0.5), ground, Bounds.Radius(10.0), 0.1)
            pose = r.pose; arrived = r.arrived; steps++
        }
        assertTrue(arrived)
        assertEquals(2.5, pose.position.z, 1e-6)
        assertEquals(25, steps)
        assertEquals(0.0, pose.position.y, 0.0)
    }

    @Test fun turnsBeforeMoving() {
        val slow = Mobility(Mobility.Mode.GROUND, speed = 1.0, turnRate = 90.0)
        val r = Steering.step(ActorPose(Vec3.ZERO, 0.0), SteeringGoal.Seek(Vec3(0.0, 0.0, -3.0), 0.0), slow, Bounds.Radius(10.0), 0.5)
        assertEquals(Vec3.ZERO, r.pose.position)
        assertEquals(45.0, Math.abs(r.pose.heading), 1e-9)
        assertTrue(r.moving)
    }

    @Test fun staysInBounds() {
        val r = Steering.step(ActorPose(Vec3(0.0, 0.0, 1.9), 0.0), SteeringGoal.Seek(Vec3(0.0, 0.0, 5.0), 0.0), ground, Bounds.Radius(2.0), 1.0)
        assertEquals(2.0, r.pose.position.z, 1e-9)
    }

    @Test fun fleeKeepsDistanceAndSlidesWhenCornered() {
        var pose = ActorPose(Vec3(0.0, 0.0, 0.5), 0.0)
        repeat(20) { pose = Steering.step(pose, SteeringGoal.Flee(Vec3.ZERO, 1.5), ground, Bounds.Radius(3.0), 0.1).pose }
        assertTrue(pose.position.horizontalLength >= 1.5 - 1e-9)
        assertFalse(Steering.step(pose, SteeringGoal.Flee(Vec3.ZERO, 1.5), ground, Bounds.Radius(3.0), 0.1).moving)
        val cornered = Steering.step(ActorPose(Vec3(0.0, 0.0, 2.0), 0.0), SteeringGoal.Flee(Vec3(0.0, 0.0, 1.5), 2.0), ground, Bounds.Radius(2.0), 0.5)
        assertTrue(cornered.moving)
        assertNotEquals(0.0, cornered.pose.position.x, 0.0)
    }

    @Test fun airClampsAltitude() {
        var pose = ActorPose(Vec3(0.0, 1.0, 0.0), 0.0)
        repeat(40) { pose = Steering.step(pose, SteeringGoal.Seek(Vec3(1.0, 3.0, 1.0), 0.0), air, Bounds.Radius(5.0), 0.1).pose }
        assertTrue(pose.position.y <= 1.5 + 1e-9)
        assertEquals(1.0, pose.position.x, 1e-6)
        assertEquals(1.0, pose.position.z, 1e-6)
    }

    @Test fun faceOnlyTurns() {
        val r = Steering.step(ActorPose(Vec3.ZERO, 0.0), SteeringGoal.Face(Vec3(1.0, 0.0, 0.0)), ground, Bounds.Radius(2.0), 1.0)
        assertEquals(90.0, r.pose.heading, 1e-9)
        assertEquals(Vec3.ZERO, r.pose.position)
        assertFalse(r.moving)
    }

    @Test fun immobileNeverMoves() {
        val r = Steering.step(ActorPose(), SteeringGoal.Seek(Vec3(1.0, 0.0, 1.0), 0.0), Mobility(Mobility.Mode.NONE), Bounds.Radius(2.0), 1.0)
        assertEquals(ActorPose(), r.pose)
    }

    @Test fun runnerPicksHighestActivePriority() {
        val behaviors = listOf(
            Behavior(Behavior.Kind.Wander(1.0, 1.0..1.0)),
            Behavior(Behavior.Kind.Approach(Target.User, 0.5), 5, JsonPrimitive("\$called")),
            Behavior(Behavior.Kind.LookAt(Target.User), 1),
        )
        val runner = BehaviorRunner(behaviors, ground, Bounds.Radius(2.0), seed = 1)
        val senses = BehaviorRunner.Senses(Vec3(0.0, 0.0, 2.0))
        var called = false
        val first = runner.goals(ActorPose(), senses, 0.0) { it.`when` == null || called }
        assertEquals("wander", runner.active?.type)
        val target = (first.move as SteeringGoal.Seek).target
        assertTrue(Bounds.Radius(1.0).contains(target))
        assertEquals(SteeringGoal.Face(Vec3(0.0, 0.0, 2.0)), first.face)
        called = true
        val second = runner.goals(ActorPose(), senses, 1.0) { it.`when` == null || called }
        assertEquals("approach", runner.active?.type)
        assertEquals(SteeringGoal.Seek(Vec3(0.0, 0.0, 2.0), 0.5), second.move)
    }

    @Test fun wanderPausesBetweenLegs() {
        val runner = BehaviorRunner(listOf(Behavior(Behavior.Kind.Wander(1.0, 2.0..2.0))), ground, Bounds.Radius(2.0), seed = 3)
        val target = (runner.goals(ActorPose(), BehaviorRunner.Senses(), 0.0).move as SteeringGoal.Seek).target
        assertEquals(SteeringGoal.None, runner.goals(ActorPose(target), BehaviorRunner.Senses(), 1.0).move)
        assertEquals(SteeringGoal.None, runner.goals(ActorPose(target), BehaviorRunner.Senses(), 2.0).move)
        assertTrue(runner.goals(ActorPose(target), BehaviorRunner.Senses(), 3.5).move is SteeringGoal.Seek)
    }

    @Test fun patrolAdvances() {
        val runner = BehaviorRunner(listOf(Behavior(Behavior.Kind.Patrol(listOf(Vec3(1.0, 0.0, 0.0), Vec3(0.0, 0.0, 1.0)), false))), ground, Bounds.Radius(5.0))
        assertEquals(SteeringGoal.Seek(Vec3(1.0, 0.0, 0.0), 0.05), runner.goals(ActorPose(), BehaviorRunner.Senses(), 0.0).move)
        assertEquals(SteeringGoal.Seek(Vec3(0.0, 0.0, 1.0), 0.05), runner.goals(ActorPose(Vec3(1.0, 0.0, 0.0)), BehaviorRunner.Senses(), 1.0).move)
        assertEquals(SteeringGoal.None, runner.goals(ActorPose(Vec3(0.0, 0.0, 1.0)), BehaviorRunner.Senses(), 2.0).move)
    }
}
