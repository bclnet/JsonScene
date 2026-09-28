import XCTest
@testable import JsonSceneCore
import JsonUICore

final class SceneDocumentTests: XCTestCase {
    func testBushExample() throws {
        let scene = try TestSupport.example("scene-bush.json")
        XCTAssertEqual(scene.issues, [])
        XCTAssertEqual(scene.anchor, .code)
        XCTAssertEqual(scene.actors.count, 1)
        let bush = try XCTUnwrap(scene.actor("bush"))
        XCTAssertEqual(bush.name, "Singing Bush")
        XCTAssertNil(bush.mobility)
        XCTAssertFalse(bush.isMobile)
        let body = try XCTUnwrap(bush.body)
        XCTAssertEqual(body.model.primary?.hasSuffix("BoxAnimated.glb"), true)
        XCTAssertEqual(body.scale, 0.15)
        XCTAssertEqual(body.animations["sing"], AnimationClip(clip: .index(0), loop: true, speed: 1.5))
        XCTAssertEqual(body.defaultAnimation, "idle")
        XCTAssertEqual(body.sounds["song"]?.spatial, true)
        let mind = try XCTUnwrap(bush.mind)
        XCTAssertEqual(mind.budget, Mind.Budget(tokens: 20000, perTurn: 300, cooldown: 8))
        XCTAssertEqual(mind.tools, ["say", "play", "sound"])
        XCTAssertEqual(mind.triggers, ["tap", "spoken"])
        XCTAssertEqual(mind.canned.count, 3)
        XCTAssertEqual(bush.on["soundEnd"]?.steps.count, 2)
        XCTAssertEqual(bush.on["appear"]?.commands, [.play(animation: "idle", loop: nil, speed: nil)])
    }

    func testSnoopyExample() throws {
        let scene = try TestSupport.example("scene-snoopy.json")
        XCTAssertEqual(scene.issues, [])
        XCTAssertEqual(scene.anchor, .floor)
        XCTAssertEqual(scene.bounds, .radius(2.5))
        let snoopy = try XCTUnwrap(scene.actor("snoopy"))
        XCTAssertEqual(snoopy.transform.rotation.yaw, 180)
        XCTAssertEqual(snoopy.mobility?.mode, .ground)
        XCTAssertEqual(snoopy.mobility?.speed, 0.6)
        XCTAssertEqual(snoopy.mobility?.moveAnimation, "walk")
        XCTAssertEqual(snoopy.behaviors.map(\.type), ["flee", "approach", "lookAt", "wander"])
        XCTAssertEqual(snoopy.behaviors[0].priority, 10)
        XCTAssertEqual(snoopy.behaviors[0].when, "$scared")
        XCTAssertEqual(snoopy.behaviors[1].kind, .approach(target: .user, stopAt: 0.6))
        XCTAssertEqual(snoopy.body?.sockets["nose"], "b_Head_05")
        let woodstock = try XCTUnwrap(scene.actor("woodstock"))
        XCTAssertEqual(woodstock.mobility?.mode, .air)
        XCTAssertEqual(woodstock.mobility?.altitude, 0.4...1.4)
        XCTAssertEqual(woodstock.mobility?.moveAnimation, "fly")
        XCTAssertEqual(woodstock.behaviors[0].kind, .perchOn(target: .actor("snoopy"), socket: "back"))
    }

    func testDocumentRoot() throws {
        let document = try TestSupport.document("scene-snoopy.json")
        XCTAssertEqual(document.root.type, "Scene")
        XCTAssertEqual(document.header.state["called"], false)
        XCTAssertNotNil(SceneDocument(document: document))
        XCTAssertNil(SceneDocument(document: JsonDocument(root: JsonNode(kind: .form))))
    }

    func testIssues() throws {
        let scene = try SceneDocument(json: """
        { "type": "Scene", "anchor": "roof", "actors": [
            { "body": { "animations": { "bad": true } } },
            { "id": "a", "mobility": "swim" },
            { "id": "a", "behaviors": [ { "type": "wander" }, { "type": "teleport" } ] },
            "nope"
        ] }
        """)
        XCTAssertEqual(scene.anchor, .code)
        XCTAssertEqual(scene.actors.map(\.id), ["actor0", "a", "a"])
        XCTAssertTrue(scene.issues.contains { $0.contains("unknown anchor") })
        XCTAssertTrue(scene.issues.contains { $0.contains("missing id") })
        XCTAssertTrue(scene.issues.contains { $0.contains("no model") })
        XCTAssertTrue(scene.issues.contains { $0.contains("not a clip") })
        XCTAssertTrue(scene.issues.contains { $0.contains("unknown mobility") })
        XCTAssertTrue(scene.issues.contains { $0.contains("duplicate actor id") })
        XCTAssertTrue(scene.issues.contains { $0.contains("behaviors[1]") })
        XCTAssertTrue(scene.issues.contains { $0.contains("no mobility") })
        XCTAssertTrue(scene.issues.contains { $0.contains("actors[3]") })
    }

    func testRoundTrip() throws {
        for name in ["scene-bush.json", "scene-snoopy.json"] {
            let scene = try TestSupport.example(name)
            let again = SceneDocument(node: scene.node)
            XCTAssertEqual(again.actors, scene.actors, name)
            XCTAssertEqual(again.bounds, scene.bounds, name)
            XCTAssertEqual(again.anchor, scene.anchor, name)
        }
    }

    func testModelRef() {
        XCTAssertEqual(ModelRef("https://x/y/Fox.glb?x=1").sources, ["glb": "https://x/y/Fox.glb?x=1"])
        XCTAssertEqual(ModelRef("https://x/y/fox").sources, ["glb": "https://x/y/fox"])
        let multi = ModelRef(["glb": "a.glb", "USDZ": "a.usdz"])
        XCTAssertEqual(multi.source(preferring: ["usdz", "glb"])?.url, "a.usdz")
        XCTAssertEqual(multi.source(preferring: ["scn"])?.url, nil)
        XCTAssertEqual(multi.primary, "a.glb")
        XCTAssertEqual(ModelRef("a.usdz").value, "a.usdz")
        XCTAssertTrue(ModelRef(.null).isEmpty)
    }

    func testBehaviorParsing() {
        XCTAssertEqual(Behavior("idle")?.kind, .idle)
        XCTAssertEqual(Behavior(["type": "patrol", "points": [[0, 0], [1, 1]], "loop": false])?.kind, .patrol(points: [Vec3(0, 0, 0), Vec3(1, 0, 1)], loop: false))
        XCTAssertNil(Behavior(["type": "patrol"]))
        XCTAssertNil(Behavior(["type": "flyTo"]))
        XCTAssertEqual(Behavior(["type": "wander", "pause": 2])?.kind, .wander(radius: nil, pause: 2...2))
        XCTAssertEqual(Behavior(["type": "flee", "from": "cat"])?.kind, .flee(from: .actor("cat"), distance: 1.5))
        for b in [Behavior(["type": "approach", "stopAt": 0.3, "priority": 2, "when": "$x"])!, Behavior(["type": "perchOn", "target": "s", "socket": "back"])!] {
            XCTAssertEqual(Behavior(b.value), b)
        }
    }

    func testMobilityDefaults() {
        let air = Mobility(["mode": "air"])!
        XCTAssertEqual(air.moveAnimation, "fly")
        XCTAssertEqual(air.altitude, Mobility.defaultAltitude)
        XCTAssertEqual(Mobility("ground")?.speed, Mobility.defaultSpeed)
        XCTAssertEqual(Mobility(["speed": 2])?.mode, .ground)
        XCTAssertNil(Mobility(["mode": "teleport"]))
        XCTAssertEqual(Mobility(air.value), air)
    }
}
