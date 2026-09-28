import XCTest
@testable import JsonSceneCore
import JsonUICore
import JsonMind

final class SteeringTests: XCTestCase {
    let ground = Mobility(mode: .ground, speed: 1, turnRate: 360)
    let air = Mobility(mode: .air, speed: 1, turnRate: 720, altitude: 0.5...1.5)

    func testSeekArrivesAndStopsShort() {
        var pose = ActorPose(position: .zero, heading: 0)
        var arrived = false
        var steps = 0
        while !arrived, steps < 100 {
            let r = Steering.step(pose: pose, goal: .seek(Vec3(0, 0, 3), stopAt: 0.5), mobility: ground, bounds: .radius(10), dt: 0.1)
            pose = r.pose
            arrived = r.arrived
            steps += 1
        }
        XCTAssertTrue(arrived)
        XCTAssertEqual(pose.position.z, 2.5, accuracy: 1e-6)
        XCTAssertEqual(steps, 25)
        XCTAssertEqual(pose.position.y, 0)
    }

    func testTurnsBeforeMoving() {
        let slow = Mobility(mode: .ground, speed: 1, turnRate: 90)
        let r = Steering.step(pose: ActorPose(position: .zero, heading: 0), goal: .seek(Vec3(0, 0, -3), stopAt: 0), mobility: slow, bounds: .radius(10), dt: 0.5)
        XCTAssertEqual(r.pose.position, .zero, "still turning")
        XCTAssertEqual(abs(r.pose.heading), 45, accuracy: 1e-9)
        XCTAssertTrue(r.moving)
    }

    func testStaysInBounds() {
        let r = Steering.step(pose: ActorPose(position: Vec3(0, 0, 1.9), heading: 0), goal: .seek(Vec3(0, 0, 5), stopAt: 0), mobility: ground, bounds: .radius(2), dt: 1)
        XCTAssertEqual(r.pose.position.z, 2, accuracy: 1e-9)
    }

    func testFleeKeepsDistanceAndSlidesWhenCornered() {
        var pose = ActorPose(position: Vec3(0, 0, 0.5), heading: 0)
        for _ in 0..<20 {
            pose = Steering.step(pose: pose, goal: .flee(Vec3(0, 0, 0), distance: 1.5), mobility: ground, bounds: .radius(3), dt: 0.1).pose
        }
        XCTAssertGreaterThanOrEqual(pose.position.horizontalLength, 1.5 - 1e-9)
        XCTAssertFalse(Steering.step(pose: pose, goal: .flee(.zero, distance: 1.5), mobility: ground, bounds: .radius(3), dt: 0.1).moving)
        // Cornered against the boundary: keeps moving (sideways) instead of freezing.
        let cornered = Steering.step(pose: ActorPose(position: Vec3(0, 0, 2), heading: 0), goal: .flee(Vec3(0, 0, 1.5), distance: 2), mobility: ground, bounds: .radius(2), dt: 0.5)
        XCTAssertTrue(cornered.moving)
        XCTAssertNotEqual(cornered.pose.position.x, 0)
    }

    func testAirClampsAltitude() {
        var pose = ActorPose(position: Vec3(0, 1, 0), heading: 0)
        for _ in 0..<40 { pose = Steering.step(pose: pose, goal: .seek(Vec3(1, 3, 1), stopAt: 0), mobility: air, bounds: .radius(5), dt: 0.1).pose }
        XCTAssertLessThanOrEqual(pose.position.y, 1.5 + 1e-9)
        XCTAssertEqual(pose.position.x, 1, accuracy: 1e-6)
        XCTAssertEqual(pose.position.z, 1, accuracy: 1e-6)
    }

    func testFaceOnlyTurns() {
        let r = Steering.step(pose: ActorPose(position: .zero, heading: 0), goal: .face(Vec3(1, 0, 0)), mobility: ground, bounds: .radius(2), dt: 1)
        XCTAssertEqual(r.pose.heading, 90, accuracy: 1e-9)
        XCTAssertEqual(r.pose.position, .zero)
        XCTAssertFalse(r.moving)
    }

    func testImmobileNeverMoves() {
        let r = Steering.step(pose: ActorPose(), goal: .seek(Vec3(1, 0, 1), stopAt: 0), mobility: Mobility(mode: .none), bounds: .radius(2), dt: 1)
        XCTAssertEqual(r.pose, ActorPose())
    }

    func testRunnerPicksHighestActivePriority() {
        let behaviors = [Behavior(kind: .wander(radius: 1, pause: 1...1)), Behavior(kind: .approach(target: .user, stopAt: 0.5), priority: 5, when: "$called"), Behavior(kind: .lookAt(target: .user), priority: 1)]
        var runner = BehaviorRunner(behaviors: behaviors, mobility: ground, bounds: .radius(2), seed: 1)
        let senses = BehaviorRunner.Senses(userPosition: Vec3(0, 0, 2))
        var called = false
        let first = runner.goals(pose: ActorPose(), senses: senses, time: 0) { $0.when == nil || called }
        XCTAssertEqual(runner.active?.type, "wander")
        guard case .seek(let target, _) = first.move else { return XCTFail("wander should seek a point") }
        XCTAssertTrue(Bounds.radius(1).contains(target))
        XCTAssertEqual(first.face, .face(Vec3(0, 0, 2)))
        called = true
        let second = runner.goals(pose: ActorPose(), senses: senses, time: 1) { $0.when == nil || called }
        XCTAssertEqual(runner.active?.type, "approach")
        XCTAssertEqual(second.move, .seek(Vec3(0, 0, 2), stopAt: 0.5))
    }

    func testWanderPausesBetweenLegs() {
        var runner = BehaviorRunner(behaviors: [Behavior(kind: .wander(radius: 1, pause: 2...2))], mobility: ground, bounds: .radius(2), seed: 3)
        let g = runner.goals(pose: ActorPose(), senses: BehaviorRunner.Senses(), time: 0) { _ in true }
        guard case .seek(let target, _) = g.move else { return XCTFail() }
        // Standing on the target: the leg ends and a pause starts.
        let atTarget = runner.goals(pose: ActorPose(position: target), senses: BehaviorRunner.Senses(), time: 1) { _ in true }
        XCTAssertEqual(atTarget.move, SteeringGoal.none)
        XCTAssertEqual(runner.goals(pose: ActorPose(position: target), senses: BehaviorRunner.Senses(), time: 2).move, SteeringGoal.none)
        if case .seek = runner.goals(pose: ActorPose(position: target), senses: BehaviorRunner.Senses(), time: 3.5).move {} else { XCTFail("should wander again after the pause") }
    }

    func testPatrolAdvances() {
        var runner = BehaviorRunner(behaviors: [Behavior(kind: .patrol(points: [Vec3(1, 0, 0), Vec3(0, 0, 1)], loop: false))], mobility: ground, bounds: .radius(5))
        XCTAssertEqual(runner.goals(pose: ActorPose(), senses: BehaviorRunner.Senses(), time: 0).move, .seek(Vec3(1, 0, 0), stopAt: 0.05))
        XCTAssertEqual(runner.goals(pose: ActorPose(position: Vec3(1, 0, 0)), senses: BehaviorRunner.Senses(), time: 1).move, .seek(Vec3(0, 0, 1), stopAt: 0.05))
        XCTAssertEqual(runner.goals(pose: ActorPose(position: Vec3(0, 0, 1)), senses: BehaviorRunner.Senses(), time: 2).move, SteeringGoal.none)
    }

    func testSeededRandomIsDeterministic() {
        var a = SeededRandom(seed: 42), b = SeededRandom(seed: 42)
        XCTAssertEqual((0..<5).map { _ in a.next() }, (0..<5).map { _ in b.next() })
    }
}
