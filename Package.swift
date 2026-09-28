// swift-tools-version:5.9
import PackageDescription
import Foundation

// The manifest stays at the repository root so the package can be added by URL;
// the Swift sources live in ios/ next to the Android project in android/.
// JsonUI comes from GitHub; set JSONUI_PATH to build against a local checkout.
let jsonUI: Package.Dependency = ProcessInfo.processInfo.environment["JSONUI_PATH"].map { .package(path: $0) }
    ?? .package(url: "https://github.com/bclnet/JsonUI", branch: "claude/ios-android-form-libraries-2jjfw5")
// JsonMind holds the minds and the command vocabulary; set JSONMIND_PATH for a local checkout.
let jsonMind: Package.Dependency = ProcessInfo.processInfo.environment["JSONMIND_PATH"].map { .package(path: $0) }
    ?? .package(url: "https://github.com/bclnet/JsonMind", branch: "claude/jsonmind")

let package = Package(
    name: "JsonScene",
    platforms: [
        .iOS(.v16), .macOS(.v13)
    ],
    products: [
        // Scene / actor schema, steering, behaviors and the actor simulation. Platform independent; minds come from JsonMind.
        .library(name: "JsonSceneCore", targets: ["JsonSceneCore"]),
        // SceneKit renderer registered as the JsonUI "Scene" node.
        .library(name: "JsonScene", targets: ["JsonScene"]),
    ],
    dependencies: [jsonUI, jsonMind],
    targets: [
        .target(
            name: "JsonSceneCore",
            dependencies: [.product(name: "JsonUICore", package: "JsonUI"), .product(name: "JsonMind", package: "JsonMind")],
            path: "ios/Sources/JsonSceneCore"),
        .target(
            name: "JsonScene",
            dependencies: ["JsonSceneCore", .product(name: "JsonUI", package: "JsonUI")],
            path: "ios/Sources/JsonScene"),
        .testTarget(
            name: "JsonSceneCoreTests",
            dependencies: ["JsonSceneCore"],
            path: "ios/Tests/JsonSceneCoreTests"),
        .testTarget(
            name: "JsonSceneTests",
            dependencies: ["JsonScene"],
            path: "ios/Tests/JsonSceneTests"),
    ]
)
