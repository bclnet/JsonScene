//
//  JsonSceneController.swift
//  JsonScene
//
//  Builds the SceneKit stage for a `Scene` node, runs the simulation every
//  frame and applies its outputs to the actor nodes. `JsonSceneView` wraps
//  it in SwiftUI; an AR app can instead add `stage` under its own anchor node
//  and call `update(time:viewer:)` from its render loop.
//

#if canImport(SceneKit)
import Foundation
import SceneKit
import JsonUICore

public final class JsonSceneController: NSObject, SCNSceneRendererDelegate {
    public let document: SceneDocument
    public let simulation: ActorSimulation
    public let context: JsonContext
    public let loader: ModelLoader
    public var locator: AssetLocator
    /// The node every actor hangs below: the scene origin (the code or the floor).
    public let stage = SCNNode()
    /// A ready made scene containing `stage`, lights and a camera, for non-AR use.
    public private(set) lazy var scene: SCNScene = makeScene()
    public private(set) var actorNodes: [String: ActorNode] = [:]
    public var onIssue: ((String) -> Void)?
    private var lastTime: TimeInterval?
    private var started = false

    public init(document: SceneDocument, context: JsonContext, loader: ModelLoader = SceneKitModelLoader(), locator: AssetLocator = AssetLocator(), mindProvider: MindProvider? = nil) {
        self.document = document
        self.context = context
        self.loader = loader
        self.locator = locator
        self.simulation = ActorSimulation(scene: document, context: context)
        super.init()
        simulation.mindProvider = mindProvider
        simulation.asyncOutputs = { [weak self] outputs in DispatchQueue.main.async { self?.apply(outputs) } }
        simulation.registerHostAction { [weak self] outputs in self?.apply(outputs) }
        stage.name = "jsonscene.stage"
        stage.scale = SCNVector3(document.scale, document.scale, document.scale)
        for actor in document.actors {
            let actorNode = ActorNode(actor: actor)
            actorNode.onAnimationEnd = { [weak self] name in self?.apply(self?.simulation.animationEnded(name, on: actor.id) ?? []) }
            actorNode.onSoundEnd = { [weak self] name in self?.apply(self?.simulation.soundEnded(name, on: actor.id) ?? []) }
            actorNodes[actor.id] = actorNode
            stage.addChildNode(actorNode.node)
            load(actorNode)
        }
        for issue in document.issues { onIssue?(issue); context.log(.warn, "Scene: \(issue)") }
    }

    public convenience init(node: JsonNode, context: JsonContext, loader: ModelLoader = SceneKitModelLoader(), locator: AssetLocator = AssetLocator(), mindProvider: MindProvider? = nil) {
        self.init(document: SceneDocument(node: node), context: context, loader: loader, locator: locator, mindProvider: mindProvider)
    }

    private func load(_ actorNode: ActorNode) {
        guard let body = actorNode.actor.body else { return }
        guard let source = body.model.source(preferring: loader.formats), let url = locator.resolve(source.url) else {
            report("actor \"\(actorNode.actor.id)\": no model in a supported format (\(loader.formats.joined(separator: ", ")))")
            actorNode.attachPlaceholder()
            return
        }
        loader.load(url: url, format: source.format) { [weak self, weak actorNode] result in
            DispatchQueue.main.async {
                guard let self = self, let actorNode = actorNode else { return }
                switch result {
                case .success(let model):
                    actorNode.attach(model: model)
                    if self.started, let name = actorNode.actor.body?.defaultAnimation, let clip = actorNode.actor.body?.animations[name] {
                        actorNode.play(name, loop: true, speed: clip.speed)
                    }
                case .failure(let error):
                    self.report("actor \"\(actorNode.actor.id)\": \(error.localizedDescription)")
                    actorNode.attachPlaceholder()
                }
            }
        }
    }

    private func report(_ message: String) {
        context.log(.warn, "Scene: \(message)")
        onIssue?(message)
    }

    private func makeScene() -> SCNScene {
        let scene = SCNScene()
        scene.rootNode.addChildNode(stage)
        if document.environment.lighting != .none {
            let key = SCNNode()
            key.light = SCNLight()
            key.light?.type = .directional
            key.light?.castsShadow = document.environment.shadows
            key.light?.intensity = 800
            key.eulerAngles = SCNVector3(-Float.pi / 3, Float.pi / 4, 0)
            scene.rootNode.addChildNode(key)
            let ambient = SCNNode()
            ambient.light = SCNLight()
            ambient.light?.type = .ambient
            ambient.light?.intensity = 300
            scene.rootNode.addChildNode(ambient)
        }
        if document.environment.ground {
            let floor = SCNFloor()
            floor.reflectivity = 0
            floor.firstMaterial?.diffuse.contents = SceneKitColor.white
            floor.firstMaterial?.lightingModel = .shadowOnly
            stage.addChildNode(SCNNode(geometry: floor))
        }
        let camera = SCNNode()
        camera.camera = SCNCamera()
        camera.name = "jsonscene.camera"
        camera.position = SCNVector3(0, 1.2, 2.5)
        camera.look(at: SCNVector3(0, 0.3, 0))
        scene.rootNode.addChildNode(camera)
        return scene
    }

    // MARK: - Running

    /// Plays the default animations and fires `appear`. Called by the view on first frame.
    public func start() {
        guard !started else { return }
        started = true
        apply(simulation.start())
    }

    public func stop() {
        simulation.fireScene("disappear")
        for id in simulation.order { apply(simulation.fire("disappear", on: id)) }
    }

    /// Advances the simulation. `viewer` is the viewer's position in stage coordinates (the camera or the headset).
    public func update(time: TimeInterval, viewer: Vec3?, viewerForward: Vec3? = nil) {
        if !started { start() }
        let dt = lastTime.map { min(max(time - $0, 0), 0.25) } ?? 0
        lastTime = time
        simulation.userPosition = viewer
        simulation.userForward = viewerForward
        guard dt > 0 else { return }
        let outputs = simulation.tick(dt: dt)
        for id in simulation.order {
            guard let state = simulation[id], state.actor.isMobile, let actorNode = actorNodes[id] else { continue }
            actorNode.update(pose: state.pose)
        }
        apply(outputs)
    }

    /// Converts a world point into stage coordinates.
    public func stagePoint(fromWorld p: SCNVector3) -> Vec3 {
        let local = stage.convertPosition(p, from: nil)
        return Vec3(Double(local.x), Double(local.y), Double(local.z))
    }

    /// Reports a tap on the actor at `node` (a hit test result), or on empty space.
    public func tap(on node: SCNNode?) {
        var n = node
        while let current = n {
            if let id = current.name, actorNodes[id] != nil, current.parent === stage {
                apply(simulation.fire("tap", on: id))
                return
            }
            n = current.parent
        }
        simulation.fireScene("tap")
    }

    /// Feeds speech recognised by the host to the actors that listen for it.
    public func heard(_ text: String, by actorId: String? = nil) {
        let ids = actorId.map { [$0] } ?? simulation.order
        for id in ids { apply(simulation.fire("spoken", on: id, payload: ["text": .string(text)], heard: text)) }
    }

    public func apply(_ outputs: [ActorOutput]) {
        for output in outputs {
            switch output {
            case .play(let id, let animation, let loop, let speed): actorNodes[id]?.play(animation, loop: loop, speed: speed)
            case .sound(let id, let name, let loop): actorNodes[id]?.playSound(name, loop: loop, locator: locator)
            case .stopSound(let id, let name): actorNodes[id]?.stopSound(name)
            case .say(let id, let text): actorNodes[id]?.say(text)
            case .event: break
            }
        }
    }

    // MARK: - SCNSceneRendererDelegate

    public func renderer(_ renderer: SCNSceneRenderer, updateAtTime time: TimeInterval) {
        let viewer = renderer.pointOfView.map { stagePoint(fromWorld: $0.worldPosition) }
        let forward = renderer.pointOfView.map { pov -> Vec3 in
            let f = pov.worldFront
            return Vec3(Double(f.x), Double(f.y), Double(f.z))
        }
        DispatchQueue.main.async { self.update(time: time, viewer: viewer, viewerForward: forward) }
    }
}
#endif
