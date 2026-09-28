//
//  SceneDocument.swift
//  JsonScene
//
//  The parsed form of a `Scene` node: environment, bounds and actors with
//  their bodies, mobility, behaviors and minds. Parsing never throws; problems
//  are collected in `issues` so a renderer can show the scene it understood
//  and report the rest.
//

import Foundation
import JsonUICore

public struct SceneDocument: Equatable {
    public static let nodeType = "Scene"

    public enum Anchor: String { case code, floor, free }

    public struct Environment: Equatable {
        public enum Lighting: String { case auto, none, studio }
        public var lighting: Lighting = .auto
        public var shadows: Bool = true
        public var ground: Bool = true
        public init() {}
        public init(_ value: JsonValue) {
            let o = value.objectValue ?? [:]
            lighting = o["lighting"]?.text.flatMap(Lighting.init) ?? .auto
            shadows = o["shadows"]?.flag ?? true
            ground = o["ground"]?.flag ?? true
        }
    }

    public var anchor: Anchor
    public var scale: Double
    public var bounds: Bounds
    public var environment: Environment
    public var actors: [Actor]
    /// Event handlers of the scene itself (`appear`, `disappear`, `tap`).
    public var on: [String: ActorScript]
    /// Parse problems, empty for a well formed scene.
    public var issues: [String]

    public init(anchor: Anchor = .code, scale: Double = 1, bounds: Bounds = .default, environment: Environment = Environment(), actors: [Actor] = [], on: [String: ActorScript] = [:], issues: [String] = []) {
        self.anchor = anchor; self.scale = scale; self.bounds = bounds; self.environment = environment
        self.actors = actors; self.on = on; self.issues = issues
    }

    /// Parses a `Scene` node. Any node type is accepted so a host can reuse the actor format under another name.
    public init(node: JsonNode) {
        var issues: [String] = []
        anchor = node["anchor"].text.flatMap(Anchor.init) ?? .code
        if node.has("anchor"), node["anchor"].text.flatMap(Anchor.init) == nil { issues.append("unknown anchor \(node["anchor"])") }
        scale = node["scale"].numberValue ?? 1
        bounds = Bounds(node["bounds"]) ?? .default
        environment = Environment(node["environment"])
        on = ActorScript.handlers(node["on"])
        var actors: [Actor] = []
        var ids = Set<String>()
        for (i, value) in (node["actors"].arrayValue ?? []).enumerated() {
            guard let object = value.objectValue else { issues.append("actors[\(i)] is not an object"); continue }
            var actor = Actor(object: object, index: i)
            if ids.contains(actor.id) { issues.append("duplicate actor id \"\(actor.id)\"") }
            ids.insert(actor.id)
            issues.append(contentsOf: actor.issues.map { "actor \"\(actor.id)\": \($0)" })
            actor.issues = []
            actors.append(actor)
        }
        self.actors = actors
        self.issues = issues
    }

    public init(value: JsonValue) throws {
        guard let node = JsonNode(value: value) else { throw SceneError.notANode }
        self.init(node: node)
    }

    public init(json: String) throws {
        try self.init(value: try JsonValue.parse(json))
    }

    /// The `Scene` node of a JsonUI document, if its root is one.
    public init?(document: JsonDocument) {
        guard document.root.type == SceneDocument.nodeType else { return nil }
        self.init(node: document.root)
    }

    public func actor(_ id: String) -> Actor? { actors.first { $0.id == id } }

    /// The node value (round trips the parsed form, defaults omitted).
    public var node: JsonNode {
        var props: [String: JsonValue] = [:]
        if anchor != .code { props["anchor"] = .string(anchor.rawValue) }
        if scale != 1 { props["scale"] = .number(scale) }
        if bounds != .default { props["bounds"] = bounds.value }
        if !on.isEmpty { props["on"] = ActorScript.value(handlers: on) }
        props["actors"] = .array(actors.map(\.value))
        return JsonNode(type: SceneDocument.nodeType, props: props)
    }
}

public enum SceneError: Error, Equatable {
    case notANode
    case notAScene(String)
}

// MARK: - Actor

public struct Actor: Equatable {
    public var id: String
    public var name: String?
    public var transform: Transform
    public var body: Body?
    public var mobility: Mobility?
    public var behaviors: [Behavior]
    public var mind: Mind?
    public var on: [String: ActorScript]
    /// Distance at which `near` / `far` fire, metres.
    public var nearDistance: Double
    public var issues: [String] = []

    public init(id: String, name: String? = nil, transform: Transform = .identity, body: Body? = nil, mobility: Mobility? = nil, behaviors: [Behavior] = [], mind: Mind? = nil, on: [String: ActorScript] = [:], nearDistance: Double = 1) {
        self.id = id; self.name = name; self.transform = transform; self.body = body; self.mobility = mobility
        self.behaviors = behaviors; self.mind = mind; self.on = on; self.nearDistance = nearDistance
    }

    public init(object o: [String: JsonValue], index: Int = 0) {
        var issues: [String] = []
        let id = o["id"]?.text ?? ""
        self.id = id.isEmpty ? "actor\(index)" : id
        if id.isEmpty { issues.append("missing id, using \"\(self.id)\"") }
        name = o["name"]?.text
        transform = Transform(o["transform"] ?? .null)
        if let bodyValue = o["body"] {
            var body = Body(bodyValue)
            issues.append(contentsOf: body.issues)
            body.issues = []
            self.body = body
        }
        if let m = o["mobility"] {
            if let mobility = Mobility(m) { self.mobility = mobility } else { issues.append("unknown mobility \(m)") }
        }
        var behaviors: [Behavior] = []
        for (i, b) in (o["behaviors"]?.arrayValue ?? []).enumerated() {
            if let behavior = Behavior(b) { behaviors.append(behavior) } else { issues.append("behaviors[\(i)] is not a behavior: \(b)") }
        }
        self.behaviors = behaviors
        if let m = o["mind"] { mind = Mind(m) }
        on = ActorScript.handlers(o["on"] ?? .null)
        nearDistance = o["nearDistance"]?.numberValue ?? 1
        if mobility == nil, behaviors.contains(where: { $0.needsMobility }) { issues.append("has movement behaviors but no mobility") }
        self.issues = issues
    }

    public var isMobile: Bool { mobility != nil && mobility?.mode != Mobility.Mode.none }

    public var value: JsonValue {
        var o: [String: JsonValue] = ["id": .string(id)]
        if let name = name { o["name"] = .string(name) }
        if transform != .identity { o["transform"] = transform.value }
        if let body = body { o["body"] = body.value }
        if let mobility = mobility { o["mobility"] = mobility.value }
        if !behaviors.isEmpty { o["behaviors"] = .array(behaviors.map(\.value)) }
        if let mind = mind { o["mind"] = mind.value }
        if !on.isEmpty { o["on"] = ActorScript.value(handlers: on) }
        if nearDistance != 1 { o["nearDistance"] = .number(nearDistance) }
        return .object(o)
    }
}

// MARK: - Body

public struct Body: Equatable {
    public var model: ModelRef
    public var scale: Double
    public var animations: [String: AnimationClip]
    public var defaultAnimation: String?
    public var sounds: [String: Sound]
    public var sockets: [String: String]
    public var issues: [String] = []

    public init(model: ModelRef, scale: Double = 1, animations: [String: AnimationClip] = [:], defaultAnimation: String? = nil, sounds: [String: Sound] = [:], sockets: [String: String] = [:]) {
        self.model = model; self.scale = scale; self.animations = animations; self.defaultAnimation = defaultAnimation; self.sounds = sounds; self.sockets = sockets
    }

    public init(_ value: JsonValue) {
        let o = value.objectValue ?? [:]
        var issues: [String] = []
        model = ModelRef(o["model"] ?? .null)
        if model.isEmpty { issues.append("body has no model") }
        scale = o["scale"]?.numberValue ?? 1
        var animations: [String: AnimationClip] = [:]
        for (name, v) in o["animations"]?.objectValue ?? [:] {
            if let clip = AnimationClip(v) { animations[name] = clip } else { issues.append("animation \"\(name)\" is not a clip") }
        }
        self.animations = animations
        defaultAnimation = o["default"]?.text ?? (animations["idle"] != nil ? "idle" : nil)
        var sounds: [String: Sound] = [:]
        for (name, v) in o["sounds"]?.objectValue ?? [:] {
            if let s = Sound(v) { sounds[name] = s } else { issues.append("sound \"\(name)\" has no url") }
        }
        self.sounds = sounds
        sockets = (o["sockets"]?.objectValue ?? [:]).compactMapValues(\.text)
        self.issues = issues
    }

    public var value: JsonValue {
        var o: [String: JsonValue] = ["model": model.value]
        if scale != 1 { o["scale"] = .number(scale) }
        if !animations.isEmpty { o["animations"] = .object(animations.mapValues(\.value)) }
        if let d = defaultAnimation, d != (animations["idle"] != nil ? "idle" : nil) { o["default"] = .string(d) }
        if !sounds.isEmpty { o["sounds"] = .object(sounds.mapValues(\.value)) }
        if !sockets.isEmpty { o["sockets"] = .object(sockets.mapValues(JsonValue.string)) }
        return .object(o)
    }
}

/// A model given as one URL or as several formats.
public struct ModelRef: Equatable {
    /// Lower case format (file extension) → URL string.
    public var sources: [String: String]

    public init(sources: [String: String]) { self.sources = sources }

    public init(_ value: JsonValue) {
        if let s = value.text {
            sources = [ModelRef.format(of: s): s]
        } else if let o = value.objectValue {
            sources = Dictionary(uniqueKeysWithValues: o.compactMap { k, v in v.text.map { (k.lowercased(), $0) } })
        } else {
            sources = [:]
        }
    }

    public var isEmpty: Bool { sources.isEmpty }

    /// The first source whose format a renderer supports, in the renderer's order of preference.
    public func source(preferring formats: [String]) -> (format: String, url: String)? {
        for f in formats { if let u = sources[f.lowercased()] { return (f.lowercased(), u) } }
        return nil
    }

    /// Any source, `glb` first.
    public var primary: String? { source(preferring: ["glb", "gltf", "usdz"])?.url ?? sources.values.sorted().first }

    public static func format(of url: String) -> String {
        let path = url.split(separator: "?").first.map(String.init) ?? url
        guard let dot = path.lastIndex(of: "."), !path[dot...].contains("/") else { return "glb" }
        return String(path[path.index(after: dot)...]).lowercased()
    }

    public var value: JsonValue {
        if sources.count == 1, let (f, u) = sources.first, ModelRef.format(of: u) == f { return .string(u) }
        return .object(sources.mapValues(JsonValue.string))
    }
}

public struct AnimationClip: Equatable {
    /// glTF animation name or index.
    public enum Clip: Equatable {
        case name(String)
        case index(Int)
    }
    public var clip: Clip
    public var loop: Bool
    public var speed: Double

    public init(clip: Clip, loop: Bool = false, speed: Double = 1) { self.clip = clip; self.loop = loop; self.speed = speed }

    public init?(_ value: JsonValue) {
        if let s = value.text { self.init(clip: .name(s)) }
        else if let i = value.integerValue { self.init(clip: .index(i)) }
        else if let o = value.objectValue {
            let clip: Clip
            if let s = o["clip"]?.text { clip = .name(s) } else if let i = o["clip"]?.integerValue { clip = .index(i) } else { return nil }
            self.init(clip: clip, loop: o["loop"]?.flag ?? false, speed: o["speed"]?.numberValue ?? 1)
        } else { return nil }
    }

    public var clipValue: JsonValue {
        switch clip { case .name(let s): return .string(s); case .index(let i): return .number(Double(i)) }
    }

    public var value: JsonValue {
        if !loop, speed == 1 { return clipValue }
        var o: [String: JsonValue] = ["clip": clipValue]
        if loop { o["loop"] = true }
        if speed != 1 { o["speed"] = .number(speed) }
        return .object(o)
    }
}

public struct Sound: Equatable {
    public var url: String
    public var loop: Bool
    public var volume: Double
    public var spatial: Bool

    public init(url: String, loop: Bool = false, volume: Double = 1, spatial: Bool = true) { self.url = url; self.loop = loop; self.volume = volume; self.spatial = spatial }

    public init?(_ value: JsonValue) {
        if let s = value.text { self.init(url: s) }
        else if let o = value.objectValue, let url = o["url"]?.text {
            self.init(url: url, loop: o["loop"]?.flag ?? false, volume: o["volume"]?.numberValue ?? 1, spatial: o["spatial"]?.flag ?? true)
        } else { return nil }
    }

    public var value: JsonValue {
        if !loop, volume == 1, spatial { return .string(url) }
        var o: [String: JsonValue] = ["url": .string(url)]
        if loop { o["loop"] = true }
        if volume != 1 { o["volume"] = .number(volume) }
        if !spatial { o["spatial"] = false }
        return .object(o)
    }
}

// MARK: - Mobility

public struct Mobility: Equatable {
    public enum Mode: String { case none, ground, air }
    public var mode: Mode
    public var speed: Double
    public var turnRate: Double
    public var altitude: ClosedRange<Double>
    public var bounds: Bounds?
    public var idleAnimation: String
    public var moveAnimation: String

    public static let defaultSpeed = 0.5
    public static let defaultTurnRate = 180.0
    public static let defaultAltitude = 0.3...1.5

    public init(mode: Mode, speed: Double = Mobility.defaultSpeed, turnRate: Double = Mobility.defaultTurnRate, altitude: ClosedRange<Double> = Mobility.defaultAltitude, bounds: Bounds? = nil, idleAnimation: String = "idle", moveAnimation: String? = nil) {
        self.mode = mode; self.speed = speed; self.turnRate = turnRate; self.altitude = altitude; self.bounds = bounds
        self.idleAnimation = idleAnimation
        self.moveAnimation = moveAnimation ?? (mode == .air ? "fly" : "walk")
    }

    public init?(_ value: JsonValue) {
        if let s = value.text { guard let mode = Mode(rawValue: s) else { return nil }; self.init(mode: mode); return }
        guard let o = value.objectValue, let mode = Mode(rawValue: o["mode"]?.text ?? "ground") else { return nil }
        var altitude = Mobility.defaultAltitude
        if let a = o["altitude"]?.arrayValue, a.count == 2, let lo = a[0].numberValue, let hi = a[1].numberValue, lo <= hi { altitude = lo...hi }
        self.init(mode: mode, speed: o["speed"]?.numberValue ?? Mobility.defaultSpeed, turnRate: o["turnRate"]?.numberValue ?? Mobility.defaultTurnRate,
                  altitude: altitude, bounds: o["bounds"].flatMap(Bounds.init), idleAnimation: o["idle"]?.text ?? "idle", moveAnimation: o["move"]?.text)
    }

    public var value: JsonValue {
        var o: [String: JsonValue] = ["mode": .string(mode.rawValue)]
        if speed != Mobility.defaultSpeed { o["speed"] = .number(speed) }
        if turnRate != Mobility.defaultTurnRate { o["turnRate"] = .number(turnRate) }
        if mode == .air, altitude != Mobility.defaultAltitude { o["altitude"] = [.number(altitude.lowerBound), .number(altitude.upperBound)] }
        if let b = bounds { o["bounds"] = b.value }
        if idleAnimation != "idle" { o["idle"] = .string(idleAnimation) }
        if moveAnimation != (mode == .air ? "fly" : "walk") { o["move"] = .string(moveAnimation) }
        return .object(o)
    }
}

// MARK: - Behaviors

/// A target of a behavior or command: the viewer or another actor.
public enum Target: Equatable, Hashable {
    case user
    case actor(String)

    public init?(_ value: JsonValue) {
        guard let s = value.text, !s.isEmpty else { return nil }
        self = s == "user" ? .user : .actor(s)
    }

    public var value: JsonValue { switch self { case .user: return "user"; case .actor(let id): return .string(id) } }
}

public struct Behavior: Equatable {
    public enum Kind: Equatable {
        case idle
        case wander(radius: Double?, pause: ClosedRange<Double>)
        case approach(target: Target, stopAt: Double)
        case flee(from: Target, distance: Double)
        case follow(target: Target, stopAt: Double)
        case patrol(points: [Vec3], loop: Bool)
        case lookAt(target: Target)
        case perchOn(target: Target, socket: String?)
        case flyTo(point: Vec3)
    }

    public var kind: Kind
    public var priority: Int
    /// Dynamic value; `nil` means always.
    public var when: JsonValue?

    public static let defaultPause = 1.0...4.0

    public init(kind: Kind, priority: Int = 0, when: JsonValue? = nil) { self.kind = kind; self.priority = priority; self.when = when }

    public init?(_ value: JsonValue) {
        if let s = value.text { guard let kind = Behavior.kind(type: s, [:]) else { return nil }; self.init(kind: kind); return }
        guard let o = value.objectValue, let type = o["type"]?.text, let kind = Behavior.kind(type: type, o) else { return nil }
        self.init(kind: kind, priority: o["priority"]?.integerValue ?? 0, when: o["when"])
    }

    static func kind(type: String, _ o: [String: JsonValue]) -> Kind? {
        func target(_ key: String) -> Target { o[key].flatMap(Target.init) ?? .user }
        switch type {
        case "idle": return .idle
        case "wander":
            var pause = Behavior.defaultPause
            if let p = o["pause"]?.numberValue { pause = p...p }
            else if let a = o["pause"]?.arrayValue, a.count == 2, let lo = a[0].numberValue, let hi = a[1].numberValue, lo <= hi { pause = lo...hi }
            return .wander(radius: o["radius"]?.numberValue, pause: pause)
        case "approach": return .approach(target: target("target"), stopAt: o["stopAt"]?.numberValue ?? 0.6)
        case "flee": return .flee(from: target("from"), distance: o["distance"]?.numberValue ?? 1.5)
        case "follow": return .follow(target: target("target"), stopAt: o["stopAt"]?.numberValue ?? 1.0)
        case "patrol":
            let points = (o["points"]?.arrayValue ?? []).compactMap(Vec3.init)
            guard !points.isEmpty else { return nil }
            return .patrol(points: points, loop: o["loop"]?.flag ?? true)
        case "lookAt": return .lookAt(target: target("target"))
        case "perchOn": return .perchOn(target: target("target"), socket: o["socket"]?.text)
        case "flyTo":
            guard let p = o["point"].flatMap(Vec3.init) else { return nil }
            return .flyTo(point: p)
        default: return nil
        }
    }

    public var type: String {
        switch kind {
        case .idle: return "idle"
        case .wander: return "wander"
        case .approach: return "approach"
        case .flee: return "flee"
        case .follow: return "follow"
        case .patrol: return "patrol"
        case .lookAt: return "lookAt"
        case .perchOn: return "perchOn"
        case .flyTo: return "flyTo"
        }
    }

    /// Whether the behavior moves the actor (as opposed to just turning it).
    public var needsMobility: Bool {
        switch kind {
        case .idle, .lookAt: return false
        default: return true
        }
    }

    public var value: JsonValue {
        var o: [String: JsonValue] = ["type": .string(type)]
        switch kind {
        case .idle: break
        case .wander(let radius, let pause):
            if let r = radius { o["radius"] = .number(r) }
            if pause != Behavior.defaultPause { o["pause"] = [.number(pause.lowerBound), .number(pause.upperBound)] }
        case .approach(let t, let stopAt): o["target"] = t.value; if stopAt != 0.6 { o["stopAt"] = .number(stopAt) }
        case .flee(let t, let d): o["from"] = t.value; if d != 1.5 { o["distance"] = .number(d) }
        case .follow(let t, let stopAt): o["target"] = t.value; if stopAt != 1.0 { o["stopAt"] = .number(stopAt) }
        case .patrol(let points, let loop): o["points"] = .array(points.map(\.value)); if !loop { o["loop"] = false }
        case .lookAt(let t): o["target"] = t.value
        case .perchOn(let t, let socket): o["target"] = t.value; if let s = socket { o["socket"] = .string(s) }
        case .flyTo(let p): o["point"] = p.value
        }
        if priority != 0 { o["priority"] = .number(Double(priority)) }
        if let w = when { o["when"] = w }
        return .object(o)
    }
}
