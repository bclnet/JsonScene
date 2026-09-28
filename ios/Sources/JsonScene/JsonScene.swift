//
//  JsonScene.swift
//  JsonScene
//
//  SceneKit renderer for the `Scene` node. `JsonSceneNode.register()` adds the
//  node to a JsonUI view registry so a document whose root (or any child)
//  is a `Scene` renders through `JsonSceneView`.
//

@_exported import JsonSceneCore

#if canImport(SceneKit) && canImport(SwiftUI)
import SwiftUI
import JsonUI

public enum JsonSceneNode {
    /// Registers the `Scene` node with a JsonUI registry (the shared one by default).
    public static func register(in registry: JsonViewRegistry = .shared, loader: ModelLoader = SceneKitModelLoader(), mindProvider: MindProvider? = nil) {
        registry.register(SceneDocument.nodeType) { node, context, _ in
            AnyView(JsonSceneView(node: node, context: context, loader: loader, mindProvider: mindProvider))
        }
    }
}
#endif
