//
//  JsonValueExtensions.swift
//  JsonScene
//
//  Strict accessors: JsonUI's `stringValue` renders any scalar or container as
//  text, which is right for labels but wrong when parsing a schema where a
//  string and an object mean different things.
//

import Foundation
import JsonUICore

extension JsonValue {
    /// The string only when the value is a JSON string.
    public var text: String? { if case .string(let s) = self { return s } else { return nil } }
    /// The number only when the value is a JSON number.
    public var numberValue: Double? { if case .number(let n) = self { return n } else { return nil } }
    /// The integer only when the value is an integral JSON number.
    public var integerValue: Int? { guard let n = numberValue, n == n.rounded(), abs(n) < 1e15 else { return nil }; return Int(n) }
    /// The bool only when the value is a JSON bool.
    public var flag: Bool? { if case .bool(let b) = self { return b } else { return nil } }
}
