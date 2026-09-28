//
//  JsonSceneView.swift
//  JsonScene
//
//  SwiftUI view for a `Scene` node: an SCNView driven by JsonSceneController,
//  with tap handling and a small issue banner.
//

#if canImport(SceneKit) && canImport(SwiftUI)
import SwiftUI
import SceneKit
import JsonUICore

public struct JsonSceneView: View {
    @StateObject private var holder: ControllerHolder

    public init(node: JsonNode, context: JsonContext, loader: ModelLoader = SceneKitModelLoader(), locator: AssetLocator = AssetLocator(), mindProvider: MindProvider? = nil) {
        _holder = StateObject(wrappedValue: ControllerHolder(controller: JsonSceneController(node: node, context: context, loader: loader, locator: locator, mindProvider: mindProvider)))
    }

    public init(controller: JsonSceneController) {
        _holder = StateObject(wrappedValue: ControllerHolder(controller: controller))
    }

    public var body: some View {
        ZStack(alignment: .bottom) {
            SceneKitContainer(controller: holder.controller)
            if let issue = holder.issue {
                Text(issue)
                    .font(.caption)
                    .padding(6)
                    .background(.thinMaterial, in: RoundedRectangle(cornerRadius: 6))
                    .padding(8)
            }
        }
        .onDisappear { holder.controller.stop() }
    }

    final class ControllerHolder: ObservableObject {
        let controller: JsonSceneController
        @Published var issue: String?
        init(controller: JsonSceneController) {
            self.controller = controller
            issue = controller.document.issues.first
            controller.onIssue = { [weak self] message in DispatchQueue.main.async { self?.issue = message } }
        }
    }
}

#if os(macOS)
struct SceneKitContainer: NSViewRepresentable {
    let controller: JsonSceneController
    func makeNSView(context: Context) -> SCNView { makeView(controller: controller, coordinator: context.coordinator) }
    func updateNSView(_ view: SCNView, context: Context) {}
    func makeCoordinator() -> TapCoordinator { TapCoordinator(controller: controller) }
}
#else
struct SceneKitContainer: UIViewRepresentable {
    let controller: JsonSceneController
    func makeUIView(context: Context) -> SCNView { makeView(controller: controller, coordinator: context.coordinator) }
    func updateUIView(_ view: SCNView, context: Context) {}
    func makeCoordinator() -> TapCoordinator { TapCoordinator(controller: controller) }
}
#endif

private func makeView(controller: JsonSceneController, coordinator: TapCoordinator) -> SCNView {
    let view = SCNView()
    view.scene = controller.scene
    view.delegate = controller
    view.isPlaying = true
    view.rendersContinuously = true
    view.autoenablesDefaultLighting = controller.document.environment.lighting == .studio
    view.allowsCameraControl = true
    #if os(macOS)
    view.backgroundColor = .clear
    view.addGestureRecognizer(NSClickGestureRecognizer(target: coordinator, action: #selector(TapCoordinator.tap(_:))))
    #else
    view.backgroundColor = .clear
    view.addGestureRecognizer(UITapGestureRecognizer(target: coordinator, action: #selector(TapCoordinator.tap(_:))))
    #endif
    return view
}

final class TapCoordinator: NSObject {
    let controller: JsonSceneController
    init(controller: JsonSceneController) { self.controller = controller }

    #if os(macOS)
    @objc func tap(_ recognizer: NSClickGestureRecognizer) {
        guard let view = recognizer.view as? SCNView else { return }
        let point = recognizer.location(in: view)
        controller.tap(on: view.hitTest(point, options: [.boundingBoxOnly: true]).first?.node)
    }
    #else
    @objc func tap(_ recognizer: UITapGestureRecognizer) {
        guard let view = recognizer.view as? SCNView else { return }
        let point = recognizer.location(in: view)
        controller.tap(on: view.hitTest(point, options: [.boundingBoxOnly: true]).first?.node)
    }
    #endif
}
#endif
