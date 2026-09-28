//
//  ActorNode.swift
//  JsonScene
//
//  One actor on the SceneKit stage: the model, its animation players, sound
//  players and the speech bubble. `apply(_:)` performs an `ActorOutput`.
//

#if canImport(SceneKit)
import Foundation
import SceneKit
#if canImport(AVFoundation)
import AVFoundation
#endif

public final class ActorNode {
    public let actor: Actor
    /// The node the renderer positions; the model hangs below it.
    public let node = SCNNode()
    public private(set) var model: LoadedModel?
    private var currentAnimation: (name: String, player: SCNAnimationPlayer)?
    private var soundPlayers: [String: SCNAudioPlayer] = [:]
    private var bubble: SCNNode?
    private var bubbleTimer: Timer?
    /// Called when a one-shot animation or a sound finishes.
    public var onAnimationEnd: ((String) -> Void)?
    public var onSoundEnd: ((String) -> Void)?
    #if canImport(AVFoundation) && !os(tvOS)
    private static let speech = AVSpeechSynthesizer()
    #endif

    public init(actor: Actor) {
        self.actor = actor
        node.name = actor.id
        node.position = SCNVector3(actor.transform.position.x, actor.transform.position.y, actor.transform.position.z)
        node.eulerAngles = SCNVector3(Angle.radians(actor.transform.rotation.pitch), Angle.radians(actor.transform.rotation.yaw), Angle.radians(actor.transform.rotation.roll))
        let s = actor.transform.scale
        node.scale = SCNVector3(s, s, s)
    }

    public func attach(model: LoadedModel) {
        self.model?.node.removeFromParentNode()
        self.model = model
        let scale = actor.body?.scale ?? 1
        model.node.scale = SCNVector3(scale, scale, scale)
        // Animation players must be attached to a node to run.
        for (key, player) in model.animations where model.node.animationPlayer(forKey: key) == nil {
            model.node.addAnimationPlayer(player, forKey: "jsonscene." + key)
        }
        node.addChildNode(model.node)
    }

    /// A placeholder shown while the model loads or when it fails.
    public func attachPlaceholder(color: Any? = nil) {
        let box = SCNBox(width: 0.1, height: 0.1, length: 0.1, chamferRadius: 0.01)
        let n = SCNNode(geometry: box)
        n.position.y = 0.05
        node.addChildNode(n)
    }

    public func update(pose: ActorPose) {
        node.position = SCNVector3(pose.position.x, pose.position.y, pose.position.z)
        node.eulerAngles.y = SceneKitFloat(Angle.radians(pose.heading))
    }

    // MARK: - Outputs

    public func play(_ name: String, loop: Bool, speed: Double) {
        guard let clip = actor.body?.animations[name], let model = model, let player = model.player(for: clip.clip) else { return }
        if let current = currentAnimation, current.player !== player { current.player.stop(withBlendOutDuration: 0.2) }
        player.animation.repeatCount = loop ? .greatestFiniteMagnitude : 1
        player.animation.isRemovedOnCompletion = false
        player.speed = CGFloat(speed)
        player.animation.blendInDuration = 0.2
        player.animation.blendOutDuration = 0.2
        if !loop {
            player.animation.animationDidStop = { [weak self] _, _, finished in
                guard finished, let self = self, self.currentAnimation?.name == name else { return }
                DispatchQueue.main.async { self.onAnimationEnd?(name) }
            }
        } else {
            player.animation.animationDidStop = nil
        }
        currentAnimation = (name, player)
        player.play()
    }

    public func playSound(_ name: String, loop: Bool, locator: AssetLocator) {
        guard let sound = actor.body?.sounds[name], let url = locator.resolve(sound.url) else { return }
        stopSound(name)
        AssetCache.localFile(for: url) { [weak self] result in
            guard let self = self, case .success(let file) = result, let source = SCNAudioSource(url: file) else { return }
            source.loops = loop
            source.volume = Float(sound.volume)
            source.isPositional = sound.spatial
            source.load()
            DispatchQueue.main.async {
                let player = SCNAudioPlayer(source: source)
                player.didFinishPlayback = { [weak self] in
                    self?.soundPlayers.removeValue(forKey: name)
                    self?.onSoundEnd?(name)
                }
                self.soundPlayers[name] = player
                self.node.addAudioPlayer(player)
            }
        }
    }

    public func stopSound(_ name: String?) {
        let names = name.map { [$0] } ?? Array(soundPlayers.keys)
        for n in names {
            if let p = soundPlayers.removeValue(forKey: n) { node.removeAudioPlayer(p) }
        }
    }

    public func say(_ text: String, speak: Bool = true) {
        bubble?.removeFromParentNode()
        let geometry = SCNText(string: text, extrusionDepth: 0.2)
        geometry.font = .systemFont(ofSize: 6)
        geometry.flatness = 0.3
        geometry.isWrapped = true
        geometry.containerFrame = CGRect(x: 0, y: 0, width: 80, height: 40)
        geometry.firstMaterial?.diffuse.contents = SceneKitColor.white
        geometry.firstMaterial?.emission.contents = SceneKitColor.white
        let textNode = SCNNode(geometry: geometry)
        let (minB, maxB) = textNode.boundingBox
        textNode.pivot = SCNMatrix4MakeTranslation((maxB.x - minB.x) / 2, 0, 0)
        textNode.scale = SCNVector3(0.004, 0.004, 0.004)
        let holder = SCNNode()
        holder.addChildNode(textNode)
        holder.position.y = SceneKitFloat(bubbleHeight)
        holder.constraints = [SCNBillboardConstraint()]
        node.addChildNode(holder)
        bubble = holder
        bubbleTimer?.invalidate()
        bubbleTimer = Timer.scheduledTimer(withTimeInterval: max(2, Double(text.count) * 0.08), repeats: false) { [weak self] _ in
            self?.bubble?.removeFromParentNode()
            self?.bubble = nil
        }
        #if canImport(AVFoundation) && !os(tvOS)
        if speak {
            let utterance = AVSpeechUtterance(string: text)
            utterance.rate = AVSpeechUtteranceDefaultSpeechRate
            ActorNode.speech.speak(utterance)
        }
        #endif
    }

    private var bubbleHeight: Double {
        guard let model = model else { return 0.3 }
        let (_, maxB) = model.node.boundingBox
        return Double(maxB.y) * (actor.body?.scale ?? 1) + 0.1
    }

    /// The socket node (by model node name) or the actor node.
    public func socket(_ name: String) -> SCNNode {
        guard let nodeName = actor.body?.sockets[name], let n = model?.node.childNode(withName: nodeName, recursively: true) else { return node }
        return n
    }
}

#if os(macOS)
public typealias SceneKitFloat = CGFloat
public typealias SceneKitColor = NSColor
#else
public typealias SceneKitFloat = Float
public typealias SceneKitColor = UIColor
#endif
#endif
