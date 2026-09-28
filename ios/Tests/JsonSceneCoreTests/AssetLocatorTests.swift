import XCTest
@testable import JsonSceneCore

final class AssetLocatorTests: XCTestCase {
    func testResolution() {
        let locator = AssetLocator(base: URL(string: "https://example.com/scenes/bush.json"), bundle: { name in URL(string: "bundle:///\(name)") })
        XCTAssertEqual(locator.resolve("https://x/y.glb")?.absoluteString, "https://x/y.glb")
        XCTAssertEqual(locator.resolve("models/fox.glb")?.absoluteString, "https://example.com/scenes/models/fox.glb")
        XCTAssertEqual(locator.resolve("/tmp/a.glb")?.path, "/tmp/a.glb")
        XCTAssertEqual(locator.resolve("bundle:song.wav")?.absoluteString, "bundle:///song.wav")
        XCTAssertNil(locator.resolve(""))
        XCTAssertNil(AssetLocator().resolve("relative.glb"))
    }
}
