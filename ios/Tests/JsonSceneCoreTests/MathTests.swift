import XCTest
@testable import JsonSceneCore
import JsonUICore
import JsonMind

final class MathTests: XCTestCase {
    func testVec3Parsing() {
        XCTAssertEqual(Vec3([1, 2, 3]), Vec3(1, 2, 3))
        XCTAssertEqual(Vec3([1, 3]), Vec3(1, 0, 3))
        XCTAssertEqual(Vec3(["x": 1, "z": 2]), Vec3(1, 0, 2))
        XCTAssertNil(Vec3("nope"))
        XCTAssertNil(Vec3([1, "a"]))
    }

    func testYawAndDirection() {
        XCTAssertEqual(Vec3(0, 0, 1).yaw, 0, accuracy: 1e-9)
        XCTAssertEqual(Vec3(1, 0, 0).yaw, 90, accuracy: 1e-9)
        XCTAssertEqual(Vec3(0, 0, -1).yaw, 180, accuracy: 1e-9)
        let d = Vec3.direction(yaw: 90)
        XCTAssertEqual(d.x, 1, accuracy: 1e-9)
        XCTAssertEqual(d.z, 0, accuracy: 1e-9)
    }

    func testAngleNormalizeAndTurn() {
        XCTAssertEqual(Angle.normalize(190), -170, accuracy: 1e-9)
        XCTAssertEqual(Angle.normalize(-190), 170, accuracy: 1e-9)
        XCTAssertEqual(Angle.delta(from: 170, to: -170), 20, accuracy: 1e-9)
        XCTAssertEqual(Angle.turn(from: 0, to: 90, maxStep: 30), 30, accuracy: 1e-9)
        XCTAssertEqual(Angle.turn(from: 0, to: -90, maxStep: 30), -30, accuracy: 1e-9)
        XCTAssertEqual(Angle.turn(from: 0, to: 20, maxStep: 30), 20, accuracy: 1e-9)
    }

    func testBounds() {
        let r = Bounds.radius(2)
        XCTAssertTrue(r.contains(Vec3(1, 5, 1)))
        XCTAssertFalse(r.contains(Vec3(2, 0, 2)))
        let c = r.clamp(Vec3(3, 1, 4))
        XCTAssertEqual(c.horizontalLength, 2, accuracy: 1e-9)
        XCTAssertEqual(c.y, 1)
        let box = Bounds(["min": [-1, 0, -1], "max": [1, 0, 1]])!
        XCTAssertEqual(box.clamp(Vec3(5, 0, -5)), Vec3(1, 0, -1))
        var g = SeededRandom(seed: 7)
        for _ in 0..<50 { XCTAssertTrue(box.contains(box.randomPoint(using: &g))); XCTAssertTrue(r.contains(r.randomPoint(using: &g))) }
        XCTAssertEqual(Bounds(3), .radius(3))
        XCTAssertNil(Bounds("x"))
    }

    func testTransformRoundTrip() {
        let t = Transform(["position": [1, 2, 3], "rotation": [0, 90, 0], "scale": 2])
        XCTAssertEqual(t.position, Vec3(1, 2, 3))
        XCTAssertEqual(t.rotation.yaw, 90)
        XCTAssertEqual(Transform(t.value), t)
        XCTAssertEqual(Transform(.null), .identity)
        XCTAssertEqual(Rotation(45)?.yaw, 45)
    }
}
