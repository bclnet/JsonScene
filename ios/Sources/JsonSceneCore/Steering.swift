//
//  Steering.swift
//  JsonScene
//
//  Movement of mobile actors, independent of any renderer: a pose (position
//  and heading), a goal (seek, flee, wander…), and `Steering.step` which
//  advances the pose by `dt` seconds with the actor's speed and turn rate.
//

import Foundation

public struct ActorPose: Equatable {
    public var position: Vec3
    /// Heading in degrees, 0 faces +Z, 90 faces +X.
    public var heading: Double

    public init(position: Vec3 = .zero, heading: Double = 0) { self.position = position; self.heading = heading }
}

public enum SteeringGoal: Equatable {
    case none
    /// Go to a point and stop within `stopAt` of it.
    case seek(Vec3, stopAt: Double)
    /// Keep at least `distance` from a point.
    case flee(Vec3, distance: Double)
    /// Only turn towards a point.
    case face(Vec3)
}

public struct SteeringResult: Equatable {
    public var pose: ActorPose
    public var moving: Bool
    /// True on the step where a seek goal was reached.
    public var arrived: Bool
}

public enum Steering {
    /// Advances `pose` towards `goal`.
    ///
    /// Ground actors move on the XZ plane and stay on y = 0; air actors also move
    /// vertically and are clamped to the altitude range. The actor first turns
    /// (at `turnRate`) and only moves when facing within 60° of the target, so it
    /// does not slide sideways.
    public static func step(pose: ActorPose, goal: SteeringGoal, mobility: Mobility, bounds: Bounds, dt: Double) -> SteeringResult {
        guard dt > 0, mobility.mode != .none else { return SteeringResult(pose: pose, moving: false, arrived: false) }
        var pose = pose
        switch goal {
        case .none:
            return SteeringResult(pose: pose, moving: false, arrived: false)
        case .face(let target):
            let to = target - pose.position
            if to.horizontalLength > 1e-6 { pose.heading = Angle.turn(from: pose.heading, to: to.yaw, maxStep: mobility.turnRate * dt) }
            return SteeringResult(pose: pose, moving: false, arrived: false)
        case .seek(let target, let stopAt):
            let goalPoint = mobility.mode == .air ? clampAltitude(target, mobility) : target.flattened
            let to = goalPoint - pose.position
            let distance = mobility.mode == .air ? to.length : to.horizontalLength
            if distance <= max(stopAt, 1e-3) { return SteeringResult(pose: pose, moving: false, arrived: true) }
            var moved = false
            if to.horizontalLength > 1e-6 {
                pose.heading = Angle.turn(from: pose.heading, to: to.yaw, maxStep: mobility.turnRate * dt)
                if abs(Angle.delta(from: pose.heading, to: to.yaw)) > 60 { return SteeringResult(pose: pose, moving: true, arrived: false) }
            }
            let travel = min(mobility.speed * dt, max(0, distance - stopAt))
            if travel > 0 {
                pose.position = pose.position + to.normalized * travel
                moved = true
            }
            pose.position = clamp(pose.position, mobility: mobility, bounds: bounds)
            let remaining = mobility.mode == .air ? goalPoint.distance(to: pose.position) : goalPoint.horizontalDistance(to: pose.position)
            return SteeringResult(pose: pose, moving: moved, arrived: remaining <= max(stopAt, 1e-3) + 1e-9)
        case .flee(let threat, let distance):
            let away = pose.position.flattened - threat.flattened
            let current = away.horizontalLength
            if current >= distance { return SteeringResult(pose: pose, moving: false, arrived: false) }
            // Run directly away, or away from the origin when standing on the threat.
            var direction = current > 1e-6 ? away.normalized : Vec3.direction(yaw: pose.heading)
            let candidate = pose.position + direction * (mobility.speed * dt)
            if !bounds.contains(candidate) {
                // Cornered: slide along the boundary (turn 90°) instead of standing still.
                direction = Vec3(direction.z, 0, -direction.x)
            }
            pose.heading = Angle.turn(from: pose.heading, to: direction.yaw, maxStep: mobility.turnRate * dt)
            pose.position = clamp(pose.position + direction * (mobility.speed * dt), mobility: mobility, bounds: bounds)
            return SteeringResult(pose: pose, moving: true, arrived: false)
        }
    }

    static func clampAltitude(_ p: Vec3, _ mobility: Mobility) -> Vec3 {
        Vec3(p.x, min(max(p.y, mobility.altitude.lowerBound), mobility.altitude.upperBound), p.z)
    }

    public static func clamp(_ p: Vec3, mobility: Mobility, bounds: Bounds) -> Vec3 {
        let inBounds = bounds.clamp(p)
        return mobility.mode == .air ? clampAltitude(inBounds, mobility) : inBounds.flattened
    }
}

/// Picks the active behavior and turns it into a steering goal, keeping the
/// little state behaviors need (the wander target, the patrol index).
public struct BehaviorRunner {
    public struct Senses {
        public var userPosition: Vec3?
        public var actorPositions: [String: Vec3]
        public init(userPosition: Vec3? = nil, actorPositions: [String: Vec3] = [:]) { self.userPosition = userPosition; self.actorPositions = actorPositions }

        public func position(of target: Target) -> Vec3? {
            switch target { case .user: return userPosition; case .actor(let id): return actorPositions[id] }
        }
    }

    public var behaviors: [Behavior]
    public var mobility: Mobility
    public var bounds: Bounds
    public private(set) var activeIndex: Int?
    public private(set) var wanderTarget: Vec3?
    public private(set) var wanderPauseUntil: Double = 0
    public private(set) var patrolIndex = 0
    var random: SeededRandom

    public init(behaviors: [Behavior], mobility: Mobility, bounds: Bounds, seed: UInt64 = UInt64(Date().timeIntervalSince1970 * 1000)) {
        self.behaviors = behaviors.sorted { $0.priority > $1.priority }
        self.mobility = mobility
        self.bounds = mobility.bounds ?? bounds
        self.random = SeededRandom(seed: seed)
    }

    public var active: Behavior? { activeIndex.map { behaviors[$0] } }

    /// The goals for this frame: a movement goal and an optional `lookAt` facing goal.
    /// `isActive` tells whether a behavior's `when` condition holds (`nil` conditions are always active).
    public mutating func goals(pose: ActorPose, senses: Senses, time: Double, isActive: (Behavior) -> Bool = { $0.when == nil }) -> (move: SteeringGoal, face: SteeringGoal?) {
        var face: SteeringGoal?
        var move: SteeringGoal = .none
        var chosen: Int?
        for (i, b) in behaviors.enumerated() where isActive(b) {
            if case .lookAt(let target) = b.kind {
                if face == nil, let p = senses.position(of: target) { face = .face(p) }
                continue
            }
            if chosen == nil { chosen = i }
        }
        if chosen != activeIndex { activeIndex = chosen; wanderTarget = nil; wanderPauseUntil = 0 }
        guard let index = chosen else { return (.none, face) }
        let behavior = behaviors[index]
        switch behavior.kind {
        case .idle, .lookAt:
            move = .none
        case .wander(let radius, let pause):
            let area: Bounds = radius.map { .radius($0) } ?? bounds
            if let target = wanderTarget {
                let d = mobility.mode == .air ? target.distance(to: pose.position) : target.horizontalDistance(to: pose.position)
                if d <= 0.1 {
                    wanderTarget = nil
                    wanderPauseUntil = time + Double.random(in: pause, using: &random)
                } else {
                    move = .seek(target, stopAt: 0.05)
                }
            } else if time >= wanderPauseUntil {
                var p = area.randomPoint(using: &random)
                if mobility.mode == .air { p.y = Double.random(in: mobility.altitude, using: &random) }
                wanderTarget = p
                move = .seek(p, stopAt: 0.05)
            }
        case .approach(let target, let stopAt), .follow(let target, let stopAt):
            if let p = senses.position(of: target) { move = .seek(p, stopAt: stopAt) }
        case .flee(let from, let distance):
            if let p = senses.position(of: from) { move = .flee(p, distance: distance) }
        case .patrol(let points, let loop):
            if patrolIndex >= points.count { if loop { patrolIndex = 0 } else { return (.none, face) } }
            let target = points[patrolIndex]
            let d = mobility.mode == .air ? target.distance(to: pose.position) : target.horizontalDistance(to: pose.position)
            if d <= 0.1 { patrolIndex += 1; if patrolIndex >= points.count, !loop { return (.none, face) }; if loop { patrolIndex %= points.count } }
            move = .seek(points[min(patrolIndex, points.count - 1)], stopAt: 0.05)
        case .perchOn(let target, _):
            if let p = senses.position(of: target) { move = .seek(Vec3(p.x, max(p.y, mobility.altitude.lowerBound), p.z), stopAt: 0.02) }
        case .flyTo(let point):
            move = .seek(point, stopAt: 0.05)
        }
        return (move, face)
    }
}

/// A small deterministic generator so behaviors are testable.
public struct SeededRandom: RandomNumberGenerator {
    private var state: UInt64
    public init(seed: UInt64) { state = seed == 0 ? 0x9E3779B97F4A7C15 : seed }
    public mutating func next() -> UInt64 {
        state &+= 0x9E3779B97F4A7C15
        var z = state
        z = (z ^ (z >> 30)) &* 0xBF58476D1CE4E5B9
        z = (z ^ (z >> 27)) &* 0x94D049BB133111EB
        return z ^ (z >> 31)
    }
}
