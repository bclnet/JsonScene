//
//  AssetLocator.swift
//  JsonScene
//
//  Resolves model and sound references: absolute URLs as they are, relative
//  ones against the document URL, and `bundle:name.ext` against the app.
//

import Foundation

public struct AssetLocator {
    public var base: URL?
    /// Resolver for `bundle:` references, set by the app (e.g. `Bundle.main.url(forResource:withExtension:)`).
    public var bundle: ((String) -> URL?)?

    public init(base: URL? = nil, bundle: ((String) -> URL?)? = nil) { self.base = base; self.bundle = bundle }

    public func resolve(_ reference: String) -> URL? {
        let trimmed = reference.trimmingCharacters(in: .whitespaces)
        guard !trimmed.isEmpty else { return nil }
        if trimmed.hasPrefix("bundle:") { return bundle?(String(trimmed.dropFirst("bundle:".count))) }
        if let url = URL(string: trimmed), url.scheme != nil { return url }
        if trimmed.hasPrefix("/") { return URL(fileURLWithPath: trimmed) }
        return base.flatMap { URL(string: trimmed, relativeTo: $0)?.absoluteURL }
    }
}
