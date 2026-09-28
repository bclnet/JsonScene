//
//  Vec3.swift
//  JsonScene
//
//  Minimal vector math shared by the steering code and the renderers.
//  Scene space is metres, +Y up, rotations in degrees.
//

import Foundation
import JsonUICore
import JsonMind

public struct Vec3: Equatable, Hashable {
    public var x: Double
    public var y: Double
    public var z: Double

    public static let zero = Vec3(0, 0, 0)

    public init(_ x: Double, _ y: Double, _ z: Double) { self.x = x; self.y = y; self.z = z }

    /// Parses `[x, y, z]`, `[x, z]` (y = 0) or `{ "x": .., "y": .., "z": .. }`.
    public init?(_ value: JsonValue) {
        if let a = value.arrayValue {
            let n = a.compactMap(\.numberValue)
            guard n.count == a.count, !n.isEmpty else { return nil }
            switch n.count {
            case 1: self.init(n[0], 0, 0)
            case 2: self.init(n[0], 0, n[1])
            default: self.init(n[0], n[1], n[2])
            }
        } else if let o = value.objectValue {
            self.init(o["x"]?.numberValue ?? 0, o["y"]?.numberValue ?? 0, o["z"]?.numberValue ?? 0)
        } else {
            return nil
        }
    }

    public var value: JsonValue { [.number(x), .number(y), .number(z)] }

    public init(_ p: Point3) { self.init(p.x, p.y, p.z) }
    public var point: Point3 { Point3(x, y, z) }

    public static func + (a: Vec3, b: Vec3) -> Vec3 { Vec3(a.x + b.x, a.y + b.y, a.z + b.z) }
    public static func - (a: Vec3, b: Vec3) -> Vec3 { Vec3(a.x - b.x, a.y - b.y, a.z - b.z) }
    public static func * (a: Vec3, s: Double) -> Vec3 { Vec3(a.x * s, a.y * s, a.z * s) }
    public static prefix func - (a: Vec3) -> Vec3 { Vec3(-a.x, -a.y, -a.z) }

    public var length: Double { (x * x + y * y + z * z).squareRoot() }
    /// Length on the ground plane.
    public var horizontalLength: Double { (x * x + z * z).squareRoot() }
    public var normalized: Vec3 { let l = length; return l > 1e-9 ? self * (1 / l) : .zero }
    public func distance(to other: Vec3) -> Double { (self - other).length }
    public func horizontalDistance(to other: Vec3) -> Double { (self - other).horizontalLength }
    public func dot(_ o: Vec3) -> Double { x * o.x + y * o.y + z * o.z }
    public func lerp(_ o: Vec3, _ t: Double) -> Vec3 { self + (o - self) * t }
    public var flattened: Vec3 { Vec3(x, 0, z) }

    /// Yaw in degrees of the direction `self`, where 0 faces +Z and 90 faces +X (turning left seen from above).
    public var yaw: Double { Angle.degrees(atan2(x, z)) }

    /// Unit vector on the ground plane for a yaw in degrees (see `yaw`).
    public static func direction(yaw: Double) -> Vec3 {
        let r = Angle.radians(yaw)
        return Vec3(sin(r), 0, cos(r))
    }
}

public enum Angle {
    public static func degrees(_ radians: Double) -> Double { radians * 180 / .pi }
    public static func radians(_ degrees: Double) -> Double { degrees * .pi / 180 }

    /// Wraps an angle to (-180, 180].
    public static func normalize(_ degrees: Double) -> Double {
        var d = degrees.truncatingRemainder(dividingBy: 360)
        if d <= -180 { d += 360 }
        if d > 180 { d -= 360 }
        return d
    }

    /// The shortest signed difference `to - from` in degrees.
    public static func delta(from: Double, to: Double) -> Double { normalize(to - from) }

    /// Turns `from` towards `to` by at most `maxStep` degrees.
    public static func turn(from: Double, to: Double, maxStep: Double) -> Double {
        let d = delta(from: from, to: to)
        if abs(d) <= maxStep { return normalize(to) }
        return normalize(from + (d > 0 ? maxStep : -maxStep))
    }
}

/// Euler rotation in degrees.
public struct Rotation: Equatable, Hashable {
    public var pitch: Double
    public var yaw: Double
    public var roll: Double

    public static let identity = Rotation(pitch: 0, yaw: 0, roll: 0)

    public init(pitch: Double = 0, yaw: Double = 0, roll: Double = 0) { self.pitch = pitch; self.yaw = yaw; self.roll = roll }

    /// Parses `[pitch, yaw, roll]`, a single yaw number, or `{ "yaw": .. }`.
    public init?(_ value: JsonValue) {
        if let n = value.numberValue { self.init(yaw: n) }
        else if let v = Vec3(value), value.arrayValue != nil { self.init(pitch: v.x, yaw: v.y, roll: v.z) }
        else if let o = value.objectValue { self.init(pitch: o["pitch"]?.numberValue ?? 0, yaw: o["yaw"]?.numberValue ?? 0, roll: o["roll"]?.numberValue ?? 0) }
        else { return nil }
    }

    public var value: JsonValue { [.number(pitch), .number(yaw), .number(roll)] }
}

public struct Transform: Equatable, Hashable {
    public var position: Vec3
    public var rotation: Rotation
    public var scale: Double

    public static let identity = Transform()

    public init(position: Vec3 = .zero, rotation: Rotation = .identity, scale: Double = 1) {
        self.position = position; self.rotation = rotation; self.scale = scale
    }

    public init(_ value: JsonValue) {
        let o = value.objectValue ?? [:]
        self.init(position: o["position"].flatMap(Vec3.init) ?? .zero,
                  rotation: o["rotation"].flatMap(Rotation.init) ?? .identity,
                  scale: o["scale"]?.numberValue ?? 1)
    }

    public var value: JsonValue {
        var o: [String: JsonValue] = [:]
        if position != .zero { o["position"] = position.value }
        if rotation != .identity { o["rotation"] = rotation.value }
        if scale != 1 { o["scale"] = .number(scale) }
        return .object(o)
    }
}

/// The volume mobile actors may roam: a cylinder around the origin or a box.
public enum Bounds: Equatable {
    case radius(Double)
    case box(min: Vec3, max: Vec3)

    public static let `default` = Bounds.radius(2)

    public init?(_ value: JsonValue) {
        if let r = value.numberValue { self = .radius(r) }
        else if let o = value.objectValue {
            if let r = o["radius"]?.numberValue { self = .radius(r) }
            else if let lo = o["min"].flatMap(Vec3.init), let hi = o["max"].flatMap(Vec3.init) { self = .box(min: lo, max: hi) }
            else { return nil }
        } else { return nil }
    }

    public var value: JsonValue {
        switch self {
        case .radius(let r): return ["radius": .number(r)]
        case .box(let lo, let hi): return ["min": lo.value, "max": hi.value]
        }
    }

    public func contains(_ p: Vec3) -> Bool {
        switch self {
        case .radius(let r): return p.horizontalLength <= r + 1e-9
        case .box(let lo, let hi): return p.x >= lo.x && p.x <= hi.x && p.z >= lo.z && p.z <= hi.z
        }
    }

    /// The nearest point inside the bounds (altitude is left alone).
    public func clamp(_ p: Vec3) -> Vec3 {
        switch self {
        case .radius(let r):
            let l = p.horizontalLength
            return l <= r ? p : Vec3(p.x * r / l, p.y, p.z * r / l)
        case .box(let lo, let hi):
            return Vec3(min(max(p.x, lo.x), hi.x), p.y, min(max(p.z, lo.z), hi.z))
        }
    }

    /// A uniformly distributed point on the ground plane inside the bounds.
    public func randomPoint<G: RandomNumberGenerator>(using g: inout G) -> Vec3 {
        switch self {
        case .radius(let r):
            let a = Double.random(in: 0..<(2 * .pi), using: &g)
            let d = r * Double.random(in: 0...1, using: &g).squareRoot()
            return Vec3(d * sin(a), 0, d * cos(a))
        case .box(let lo, let hi):
            return Vec3(Double.random(in: lo.x...max(lo.x, hi.x), using: &g), 0, Double.random(in: lo.z...max(lo.z, hi.z), using: &g))
        }
    }
}
