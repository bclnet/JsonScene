import Foundation
import XCTest
@testable import JsonSceneCore
import JsonUICore

enum TestSupport {
    /// The repository's examples directory (this file is ios/Tests/JsonSceneCoreTests/TestSupport.swift).
    static var examples: URL {
        var url = URL(fileURLWithPath: #filePath)
        for _ in 0..<4 { url.deleteLastPathComponent() }
        return url.appendingPathComponent("examples")
    }

    static func example(_ name: String) throws -> SceneDocument {
        let data = try Data(contentsOf: examples.appendingPathComponent(name))
        return try SceneDocument(value: try JsonValue.parse(data))
    }

    static func document(_ name: String) throws -> JsonDocument {
        try JsonDocument(data: try Data(contentsOf: examples.appendingPathComponent(name)))
    }
}
