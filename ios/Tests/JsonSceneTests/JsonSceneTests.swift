import XCTest
@testable import JsonScene
import JsonUICore

#if canImport(SceneKit)
import SceneKit

final class JsonSceneTests: XCTestCase {
    func testControllerBuildsStage() throws {
        let document = try JsonDocument(json: """
        { "type": "Scene", "actors": [ { "id": "a", "transform": { "position": [1, 0, 2] }, "body": { "model": "https://example.com/missing.glb" } }, { "id": "b" } ] }
        """)
        let runtime = JsonRuntime(document: document)
        let controller = JsonSceneController(node: document.root, context: runtime.context)
        XCTAssertEqual(controller.actorNodes.count, 2)
        XCTAssertEqual(controller.stage.childNodes.count, 2)
        XCTAssertEqual(controller.actorNodes["a"]?.node.position.x, 1)
        XCTAssertNotNil(controller.scene.rootNode.childNode(withName: "jsonscene.camera", recursively: false))
        controller.tap(on: controller.actorNodes["a"]?.node)
    }

    func testHostActionIsRegistered() throws {
        let document = try JsonDocument(json: "{ \"type\": \"Scene\", \"actors\": [ { \"id\": \"a\" } ] }")
        let runtime = JsonRuntime(document: document)
        _ = JsonSceneController(node: document.root, context: runtime.context)
        XCTAssertTrue(runtime.actions.contains("actor"))
    }

    func testRegistersSceneNode() {
        let registry = JsonViewRegistry()
        JsonSceneNode.register(in: registry)
        XCTAssertNotNil(registry.builder(for: "Scene"))
    }
}
#endif
