import Foundation
import XCTest
@testable import JsonSceneCore
import JsonUICore
import JsonMind

enum TestSupport {
    /// The repository's examples directory (this file is ios/Tests/JsonSceneCoreTests/TestSupport.swift).
    static var examples: URL {
        var url = URL(fileURLWithPath: #filePath)
        for _ in 0..<4 { url.deleteLastPathComponent() }
        return url.appendingPathComponent("examples")
    }

    /// Resolves `$ref` fragments (bodies, minds) against the example files.
    static func resolver() -> JsonFragmentResolver { JsonFragmentResolver { url in try JsonValue.parse(try Data(contentsOf: url)) } }

    static func example(_ name: String) throws -> SceneDocument {
        let url = examples.appendingPathComponent(name)
        return try SceneDocument(value: try resolver().resolve(try JsonValue.parse(try Data(contentsOf: url)), base: url))
    }

    static func document(_ name: String) throws -> JsonDocument {
        let url = examples.appendingPathComponent(name)
        return try JsonDocument(value: try JsonValue.parse(try Data(contentsOf: url)), base: url, resolver: resolver())
    }
}
