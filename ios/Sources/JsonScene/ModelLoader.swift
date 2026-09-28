//
//  ModelLoader.swift
//  JsonScene
//
//  Loads an actor's model into a SceneKit node. SceneKit reads `.usdz`,
//  `.scn` and `.dae` itself; `.glb` needs GLTFKit2, which is used when the
//  app links it (`GLTFKit2ModelLoader`).
//

#if canImport(SceneKit)
import Foundation
import SceneKit
#if canImport(GLTFKit2)
import GLTFKit2
#endif

public struct LoadedModel {
    /// The model's root node; animations are attached as `SCNAnimationPlayer`s somewhere in its subtree.
    public var node: SCNNode
    /// Animation players by name, plus by index (`"0"`, `"1"`…) in file order.
    public var animations: [String: SCNAnimationPlayer]

    public init(node: SCNNode, animations: [String: SCNAnimationPlayer]) { self.node = node; self.animations = animations }

    public func player(for clip: AnimationClip.Clip) -> SCNAnimationPlayer? {
        switch clip {
        case .name(let n): return animations[n]
        case .index(let i): return animations["\(i)"]
        }
    }
}

public enum ModelLoadError: Error, LocalizedError {
    case unsupportedFormat(String)
    case download(String)
    case parse(String)

    public var errorDescription: String? {
        switch self {
        case .unsupportedFormat(let f): return "no loader for .\(f) models"
        case .download(let m): return "download failed: \(m)"
        case .parse(let m): return "model could not be read: \(m)"
        }
    }
}

public protocol ModelLoader {
    /// Formats this loader reads, in order of preference.
    var formats: [String] { get }
    func load(url: URL, format: String, completion: @escaping (Result<LoadedModel, Error>) -> Void)
}

/// Fetches remote assets into the caches directory once.
public enum AssetCache {
    public static var directory: URL = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask).first!.appendingPathComponent("JsonScene", isDirectory: true)

    /// Calls back with a local file URL for `url` (itself when it is already a file).
    public static func localFile(for url: URL, completion: @escaping (Result<URL, Error>) -> Void) {
        if url.isFileURL { completion(.success(url)); return }
        let name = String(url.absoluteString.hashValue, radix: 36).replacingOccurrences(of: "-", with: "_") + "." + url.pathExtension
        let target = directory.appendingPathComponent(name)
        if FileManager.default.fileExists(atPath: target.path) { completion(.success(target)); return }
        URLSession.shared.downloadTask(with: url) { temp, _, error in
            guard let temp = temp else { completion(.failure(ModelLoadError.download(error?.localizedDescription ?? "no data"))); return }
            do {
                try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
                _ = try? FileManager.default.removeItem(at: target)
                try FileManager.default.moveItem(at: temp, to: target)
                completion(.success(target))
            } catch {
                completion(.failure(error))
            }
        }.resume()
    }
}

/// SceneKit's own importers (`.usdz`, `.scn`, `.dae`, plus `.glb` through GLTFKit2 when linked).
public struct SceneKitModelLoader: ModelLoader {
    public init() {}

    public var formats: [String] {
        #if canImport(GLTFKit2)
        return ["glb", "gltf", "usdz", "scn", "dae"]
        #else
        return ["usdz", "scn", "dae"]
        #endif
    }

    public func load(url: URL, format: String, completion: @escaping (Result<LoadedModel, Error>) -> Void) {
        guard formats.contains(format) else { completion(.failure(ModelLoadError.unsupportedFormat(format))); return }
        AssetCache.localFile(for: url) { result in
            switch result {
            case .failure(let error): completion(.failure(error))
            case .success(let file):
                DispatchQueue.global(qos: .userInitiated).async {
                    #if canImport(GLTFKit2)
                    if format == "glb" || format == "gltf" {
                        GLTFKit2ModelLoader().load(url: file, format: format, completion: completion)
                        return
                    }
                    #endif
                    do {
                        let scene = try SCNScene(url: file, options: [.checkConsistency: false])
                        let root = SCNNode()
                        for child in scene.rootNode.childNodes { root.addChildNode(child) }
                        completion(.success(LoadedModel(node: root, animations: SceneKitModelLoader.collectAnimations(in: root))))
                    } catch {
                        completion(.failure(ModelLoadError.parse(error.localizedDescription)))
                    }
                }
            }
        }
    }

    /// Finds every animation player in the subtree, keyed by its key and by index in discovery order.
    public static func collectAnimations(in node: SCNNode) -> [String: SCNAnimationPlayer] {
        var players: [String: SCNAnimationPlayer] = [:]
        var index = 0
        node.enumerateHierarchy { n, _ in
            for key in n.animationKeys {
                guard let player = n.animationPlayer(forKey: key) else { continue }
                player.stop()
                players[key] = player
                players["\(index)"] = player
                index += 1
            }
        }
        return players
    }
}

#if canImport(GLTFKit2)
/// Loads glTF through GLTFKit2 (https://github.com/warrenm/GLTFKit2).
public struct GLTFKit2ModelLoader: ModelLoader {
    public init() {}
    public var formats: [String] { ["glb", "gltf"] }

    public func load(url: URL, format: String, completion: @escaping (Result<LoadedModel, Error>) -> Void) {
        AssetCache.localFile(for: url) { result in
            switch result {
            case .failure(let error): completion(.failure(error))
            case .success(let file):
                GLTFAsset.load(with: file, options: [:]) { _, status, asset, error, _ in
                    guard status == .complete, let asset = asset else {
                        if status == .error || error != nil { completion(.failure(ModelLoadError.parse(error?.localizedDescription ?? "unknown"))) }
                        return
                    }
                    let source = GLTFSCNSceneSource(asset: asset)
                    let root = SCNNode()
                    for child in source.defaultScene?.rootNode.childNodes ?? [] { root.addChildNode(child) }
                    var players: [String: SCNAnimationPlayer] = [:]
                    for (index, animation) in source.animations.enumerated() {
                        // GLTFSCNAnimation carries one player per animated channel; group them under one key.
                        let group = CAAnimationGroup()
                        group.animations = animation.channels.map(\.animation)
                        group.duration = animation.channels.map(\.animation.duration).max() ?? 0
                        let player = SCNAnimationPlayer(animation: SCNAnimation(caAnimation: group))
                        player.stop()
                        players[animation.name] = player
                        players["\(index)"] = player
                    }
                    completion(.success(LoadedModel(node: root, animations: players)))
                }
            }
        }
    }
}
#endif
#endif
