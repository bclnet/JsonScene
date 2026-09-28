import XCTest
@testable import JsonSceneCore
import JsonUICore

final class ActorSimulationTests: XCTestCase {
    func makeSimulation(_ name: String) throws -> ActorSimulation {
        let document = try TestSupport.document(name)
        let runtime = JsonRuntime(document: document)
        return ActorSimulation(scene: try XCTUnwrap(SceneDocument(document: document)), context: runtime.context)
    }

    func testStartPlaysDefaultAnimationAndAppear() throws {
        let sim = try makeSimulation("scene-bush.json")
        let outputs = sim.start()
        XCTAssertEqual(outputs.first, .play(actor: "bush", animation: "idle", loop: true, speed: 0.25))
        XCTAssertTrue(outputs.contains(.event(actor: "bush", name: "appear", payload: [:])))
    }

    func testTapRunsHandlerAndCannedMind() throws {
        let sim = try makeSimulation("scene-bush.json")
        _ = sim.start()
        let outputs = sim.fire("tap", on: "bush")
        XCTAssertEqual(sim.context.store.get("mood"), "singing")
        XCTAssertTrue(outputs.contains(.say(actor: "bush", text: "Ask, and the bush shall sing.")))
        XCTAssertTrue(outputs.contains(.play(actor: "bush", animation: "sing", loop: true, speed: 1.5)))
        XCTAssertTrue(outputs.contains(.sound(actor: "bush", name: "song", loop: false)))
        let ended = sim.soundEnded("song", on: "bush")
        XCTAssertTrue(ended.contains(.play(actor: "bush", animation: "idle", loop: true, speed: 0.25)))
        XCTAssertEqual(sim.context.store.get("mood"), "quiet")
    }

    func testMoveToUserArrivesAndFiresArrived() throws {
        let sim = try makeSimulation("scene-snoopy.json")
        _ = sim.start()
        sim.userPosition = Vec3(0, 1.6, 2)
        var outputs = sim.perform([.moveTo(.target(.user), stopAt: 0.6)], on: "snoopy")
        XCTAssertEqual(outputs, [])
        var arrived = false
        for _ in 0..<400 {
            outputs = sim.tick(dt: 0.05)
            if outputs.contains(.event(actor: "snoopy", name: "arrived", payload: [:])) { arrived = true; break }
        }
        XCTAssertTrue(arrived)
        let snoopy = try XCTUnwrap(sim["snoopy"])
        XCTAssertEqual(snoopy.pose.position.horizontalDistance(to: Vec3(0, 0, 2)), 0.6, accuracy: 0.05)
        XCTAssertEqual(sim.context.store.get("called"), false, "the arrived handler cleared it")
        XCTAssertTrue(outputs.contains(.play(actor: "snoopy", animation: "happy", loop: false, speed: 1.5)))
    }

    func testLocomotionAnimationFollowsMovement() throws {
        let sim = try makeSimulation("scene-snoopy.json")
        _ = sim.start()
        sim.userPosition = Vec3(0, 1.6, 2.4)
        _ = sim.perform([.moveTo(.point(Vec3(0, 0, -2)), stopAt: 0.05)], on: "snoopy")
        var animations: [String] = []
        for _ in 0..<200 {
            for case .play(actor: "snoopy", animation: let a, _, _) in sim.tick(dt: 0.05) { animations.append(a) }
        }
        XCTAssertTrue(animations.contains("walk"))
        XCTAssertEqual(animations.first, "walk")
    }

    func testNearAndFarEvents() throws {
        let sim = try makeSimulation("scene-snoopy.json")
        _ = sim.start()
        let start = try XCTUnwrap(sim["snoopy"]).pose.position
        sim.userPosition = start + Vec3(0, 0, 0.5)
        var outputs = sim.tick(dt: 0.01)
        XCTAssertTrue(outputs.contains(.event(actor: "snoopy", name: "near", payload: [:])))
        XCTAssertTrue(outputs.contains(.sound(actor: "snoopy", name: "bark", loop: false)), "canned near rule")
        sim.userPosition = start + Vec3(0, 0, 5)
        outputs = sim.tick(dt: 0.01)
        XCTAssertTrue(outputs.contains(.event(actor: "snoopy", name: "far", payload: [:])))
    }

    func testWaitDefersSteps() throws {
        let sim = try makeSimulation("scene-bush.json")
        var outputs = sim.perform([.say("one"), .wait(seconds: 1), .say("two")], on: "bush")
        XCTAssertEqual(outputs, [.say(actor: "bush", text: "one")])
        outputs = sim.tick(dt: 0.5)
        XCTAssertEqual(outputs, [])
        outputs = sim.tick(dt: 0.6)
        XCTAssertEqual(outputs, [.say(actor: "bush", text: "two")])
    }

    func testHostActionDrivesActors() throws {
        let sim = try makeSimulation("scene-bush.json")
        var received: [ActorOutput] = []
        sim.registerHostAction { received += $0 }
        sim.context.perform(.host(name: "actor", args: ["id": "bush", "do": [["play": "sing"], ["sound": "song"]]]))
        XCTAssertEqual(received, [.play(actor: "bush", animation: "sing", loop: true, speed: 1.5), .sound(actor: "bush", name: "song", loop: false)])
        received = []
        sim.context.perform(.host(name: "actor", args: ["id": "bush", "say": "short form"]))
        XCTAssertEqual(received, [.say(actor: "bush", text: "short form")])
    }

    func testEmitAndAnimationEnd() throws {
        let sim = try makeSimulation("scene-snoopy.json")
        let outputs = sim.animationEnded("happy", on: "snoopy")
        XCTAssertTrue(outputs.contains(.event(actor: "snoopy", name: "animationEnd", payload: ["animation": "happy"])))
        XCTAssertTrue(outputs.contains(.event(actor: "snoopy", name: "settle", payload: ["animation": "happy"])))
        XCTAssertTrue(outputs.contains(.play(actor: "snoopy", animation: "idle", loop: true, speed: 1)))
    }

    func testBehaviorsRespectStateConditions() throws {
        let sim = try makeSimulation("scene-snoopy.json")
        _ = sim.start()
        sim.userPosition = Vec3(0, 1.6, 0.3)
        sim.context.store.set(.bool(true), at: "scared")
        for _ in 0..<60 { _ = sim.tick(dt: 0.05) }
        let snoopy = try XCTUnwrap(sim["snoopy"])
        XCTAssertEqual(snoopy.runner?.active?.type, "flee")
        XCTAssertGreaterThan(snoopy.pose.position.horizontalDistance(to: Vec3(0, 0, 0.3)), 1.0)
        sim.context.store.set(.bool(false), at: "scared")
        _ = sim.tick(dt: 0.05)
        XCTAssertEqual(snoopy.runner?.active?.type, "wander")
    }

    func testSenses() throws {
        let sim = try makeSimulation("scene-snoopy.json")
        sim.userPosition = Vec3(0, 0, 0)
        sim.userForward = Vec3(0, 0, 1)
        let s = sim.senseValues(for: try XCTUnwrap(sim["snoopy"]))
        XCTAssertEqual(s["userDistance"]?.doubleValue ?? 0, Vec3(0.8, 0, 0.4).length, accuracy: 0.01)
        XCTAssertEqual(s["userLooking"], false)
        XCTAssertEqual(s["animations"], ["happy", "idle", "run", "walk"])
        XCTAssertNotNil(s["actors"]?.objectValue?["woodstock"])
        XCTAssertNil(s["actors"]?.objectValue?["snoopy"])
    }
}
