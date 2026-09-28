//
//  Mind.swift
//  JsonScene
//
//  An actor's personality: the prompt material (`persona`, `senses`, `tools`),
//  the token `budget` it may spend, the events that wake it and the canned
//  rules used when no model is attached. `MindProvider` is what a host app
//  implements to plug in a model; `MindSession` enforces the budget and
//  cooldown and turns replies into actor commands.
//

import Foundation
import JsonUICore

public struct Mind: Equatable {
    public struct Budget: Equatable {
        /// Total tokens for the actor's lifetime; `nil` is unlimited.
        public var tokens: Int?
        /// Maximum tokens one turn may spend (prompt + reply).
        public var perTurn: Int
        /// Minimum seconds between turns.
        public var cooldown: Double

        public static let defaultPerTurn = 400
        public static let defaultCooldown = 5.0

        public init(tokens: Int? = nil, perTurn: Int = Budget.defaultPerTurn, cooldown: Double = Budget.defaultCooldown) {
            self.tokens = tokens; self.perTurn = perTurn; self.cooldown = cooldown
        }

        public init(_ value: JsonValue) {
            let o = value.objectValue ?? [:]
            self.init(tokens: o["tokens"]?.integerValue ?? value.integerValue, perTurn: o["perTurn"]?.integerValue ?? Budget.defaultPerTurn, cooldown: o["cooldown"]?.numberValue ?? Budget.defaultCooldown)
        }

        public var value: JsonValue {
            var o: [String: JsonValue] = [:]
            if let t = tokens { o["tokens"] = .number(Double(t)) }
            if perTurn != Budget.defaultPerTurn { o["perTurn"] = .number(Double(perTurn)) }
            if cooldown != Budget.defaultCooldown { o["cooldown"] = .number(cooldown) }
            return .object(o)
        }
    }

    public struct Rule: Equatable {
        /// Regular expression matched (case insensitively) against the event name and any heard text.
        public var match: String
        public var script: ActorScript

        public init(match: String, script: ActorScript) { self.match = match; self.script = script }

        public init?(_ value: JsonValue) {
            guard let o = value.objectValue, let m = o["match"]?.text, let s = o["do"].flatMap(ActorScript.init) else { return nil }
            self.init(match: m, script: s)
        }

        public var value: JsonValue { ["match": .string(match), "do": script.value] }

        public func matches(_ text: String) -> Bool {
            guard let re = try? NSRegularExpression(pattern: match, options: [.caseInsensitive]) else { return text.range(of: match, options: .caseInsensitive) != nil }
            return re.firstMatch(in: text, range: NSRange(text.startIndex..., in: text)) != nil
        }
    }

    public static let allSenses = ["userDistance", "userLooking", "timeOfDay", "state", "actors", "lastEvent"]
    public static let allTools = ["say", "play", "sound", "moveTo", "lookAt", "set"]
    public static let defaultTriggers = ["tap", "near"]

    public var persona: String
    public var senses: [String]
    public var tools: [String]
    public var budget: Budget
    public var triggers: [String]
    /// Seconds between `timer` turns when `timer` is a trigger.
    public var interval: Double
    public var canned: [Rule]

    public init(persona: String, senses: [String] = Mind.allSenses, tools: [String] = Mind.allTools, budget: Budget = Budget(), triggers: [String] = Mind.defaultTriggers, interval: Double = 30, canned: [Rule] = []) {
        self.persona = persona; self.senses = senses; self.tools = tools; self.budget = budget; self.triggers = triggers; self.interval = interval; self.canned = canned
    }

    public init(_ value: JsonValue) {
        let o = value.objectValue ?? [:]
        let strings = { (key: String, fallback: [String]) -> [String] in o[key]?.arrayValue.map { $0.compactMap(\.text) } ?? fallback }
        self.init(persona: o["persona"]?.text ?? value.text ?? "",
                  senses: strings("senses", Mind.allSenses),
                  tools: strings("tools", Mind.allTools),
                  budget: Budget(o["budget"] ?? .null),
                  triggers: strings("triggers", Mind.defaultTriggers),
                  interval: o["interval"]?.numberValue ?? 30,
                  canned: (o["canned"]?.arrayValue ?? []).compactMap(Rule.init))
    }

    public var value: JsonValue {
        var o: [String: JsonValue] = ["persona": .string(persona)]
        if senses != Mind.allSenses { o["senses"] = .array(senses.map(JsonValue.string)) }
        if tools != Mind.allTools { o["tools"] = .array(tools.map(JsonValue.string)) }
        if budget != Budget() { o["budget"] = budget.value }
        if triggers != Mind.defaultTriggers { o["triggers"] = .array(triggers.map(JsonValue.string)) }
        if interval != 30 { o["interval"] = .number(interval) }
        if !canned.isEmpty { o["canned"] = .array(canned.map(\.value)) }
        return .object(o)
    }

    public func allows(_ command: ActorCommand) -> Bool { tools.contains(command.verb) }
}

// MARK: - Prompts and replies

/// What an actor perceives when a turn starts.
public struct MindPrompt: Equatable {
    public var actorId: String
    public var actorName: String?
    public var persona: String
    /// The event that started the turn (`tap`, `near`, `spoken`, `timer`).
    public var event: String
    /// Text heard from the user, for `spoken`.
    public var heard: String?
    /// Sense name → value, limited to the mind's `senses`.
    public var senses: [String: JsonValue]
    public var tools: [String]
    /// Earlier turns, oldest first.
    public var history: [MindTurn]
    public var maxTokens: Int

    public init(actorId: String, actorName: String? = nil, persona: String, event: String, heard: String? = nil, senses: [String: JsonValue] = [:], tools: [String] = Mind.allTools, history: [MindTurn] = [], maxTokens: Int = Mind.Budget.defaultPerTurn) {
        self.actorId = actorId; self.actorName = actorName; self.persona = persona; self.event = event; self.heard = heard
        self.senses = senses; self.tools = tools; self.history = history; self.maxTokens = maxTokens
    }

    /// Plain text version of the prompt, so any chat model can be used: the system prompt
    /// describes the actor and the reply format, the user message carries the event.
    public var systemText: String {
        var s = "You are \(actorName ?? actorId), a character in an augmented reality scene.\n"
        s += persona.isEmpty ? "" : "\(persona)\n"
        s += "\nReply with ONLY a JSON array of commands, nothing else. Allowed commands:\n"
        for tool in tools {
            switch tool {
            case "say": s += "  {\"say\": \"short text to speak\"}\n"
            case "play": s += "  {\"play\": \"animation name\"}\n"
            case "sound": s += "  {\"sound\": \"sound name\"}\n"
            case "moveTo": s += "  {\"moveTo\": \"user\"} or {\"moveTo\": [x, y, z]}\n"
            case "lookAt": s += "  {\"lookAt\": \"user\"}\n"
            case "set": s += "  {\"set\": {\"stateKey\": value}}\n"
            case "stop": s += "  {\"stop\": true}\n"
            case "wait": s += "  {\"wait\": seconds}\n"
            default: s += "  {\"\(tool)\": ...}\n"
            }
        }
        if let a = senses["animations"]?.arrayValue { s += "Animations: \(a.compactMap(\.text).joined(separator: ", "))\n" }
        if let a = senses["sounds"]?.arrayValue { s += "Sounds: \(a.compactMap(\.text).joined(separator: ", "))\n" }
        s += "Keep replies short: at most 3 commands and one sentence of speech."
        return s
    }

    public var userText: String {
        var o: [String: JsonValue] = ["event": .string(event)]
        if let h = heard { o["heard"] = .string(h) }
        for (k, v) in senses where k != "animations" && k != "sounds" { o[k] = v }
        return JsonValue.object(o).jsonString()
    }

    /// A rough token estimate (4 characters per token) used to enforce budgets before a call.
    public var estimatedTokens: Int { MindTurn.estimateTokens(systemText) + MindTurn.estimateTokens(userText) + history.reduce(0) { $0 + $1.estimatedTokens } }
}

public struct MindReply: Equatable {
    public var commands: [ActorCommand]
    /// Tokens the provider reports for the turn; `nil` means estimate.
    public var tokensUsed: Int?
    /// The raw model text, kept for history.
    public var text: String

    public init(commands: [ActorCommand], tokensUsed: Int? = nil, text: String = "") {
        self.commands = commands; self.tokensUsed = tokensUsed; self.text = text
    }

    /// Parses a model's text reply: a JSON array of commands, tolerating code fences and prose around it.
    /// Plain text with no JSON becomes a single `say`.
    public static func parse(_ text: String, tokensUsed: Int? = nil) -> MindReply {
        var body = text.trimmingCharacters(in: .whitespacesAndNewlines)
        if body.hasPrefix("```") {
            body = body.split(separator: "\n", omittingEmptySubsequences: false).dropFirst().joined(separator: "\n")
            if let fence = body.range(of: "```", options: .backwards) { body = String(body[..<fence.lowerBound]) }
        }
        if let start = body.firstIndex(of: "["), let end = body.lastIndex(of: "]"), start < end,
           let value = try? JsonValue.parse(String(body[start...end])) {
            let commands = (value.arrayValue ?? []).compactMap(ActorCommand.init)
            return MindReply(commands: commands, tokensUsed: tokensUsed, text: text)
        }
        if let start = body.firstIndex(of: "{"), let end = body.lastIndex(of: "}"), start < end,
           let value = try? JsonValue.parse(String(body[start...end])), let command = ActorCommand(value) {
            return MindReply(commands: [command], tokensUsed: tokensUsed, text: text)
        }
        let spoken = body.trimmingCharacters(in: .whitespacesAndNewlines)
        return MindReply(commands: spoken.isEmpty ? [] : [.say(spoken)], tokensUsed: tokensUsed, text: text)
    }
}

/// One completed turn, kept as history.
public struct MindTurn: Equatable {
    public var event: String
    public var heard: String?
    public var replyText: String
    public var tokens: Int

    public init(event: String, heard: String? = nil, replyText: String, tokens: Int) { self.event = event; self.heard = heard; self.replyText = replyText; self.tokens = tokens }

    public var estimatedTokens: Int { MindTurn.estimateTokens(event) + MindTurn.estimateTokens(heard ?? "") + MindTurn.estimateTokens(replyText) }

    public static func estimateTokens(_ text: String) -> Int { (text.utf8.count + 3) / 4 }
}

// MARK: - Providers

public enum MindError: Error, Equatable {
    case budgetExhausted
    case turnTooLarge(estimated: Int, limit: Int)
    case coolingDown(remaining: Double)
    case noProvider
    case provider(String)
}

/// A source of replies. The library ships `CannedMindProvider`; a host app wires a model.
public protocol MindProvider {
    func respond(to prompt: MindPrompt, completion: @escaping (Result<MindReply, Error>) -> Void)
}

/// Rule based replies from the `canned` list; costs no tokens.
public struct CannedMindProvider: MindProvider {
    public var rules: [Mind.Rule]
    public init(rules: [Mind.Rule]) { self.rules = rules }

    public func reply(to prompt: MindPrompt) -> MindReply? {
        let text = [prompt.event, prompt.heard ?? ""].joined(separator: " ")
        guard let rule = rules.first(where: { $0.matches(text) }) else { return nil }
        return MindReply(commands: rule.script.commands, tokensUsed: 0, text: rule.script.value.jsonString())
    }

    public func respond(to prompt: MindPrompt, completion: @escaping (Result<MindReply, Error>) -> Void) {
        completion(.success(reply(to: prompt) ?? MindReply(commands: [], tokensUsed: 0)))
    }
}

/// Tracks the tokens an actor has spent.
public struct TokenLedger: Equatable {
    public var budget: Mind.Budget
    public private(set) var spent: Int = 0
    public private(set) var turns: Int = 0
    public private(set) var lastTurnAt: TimeInterval?

    public init(budget: Mind.Budget) { self.budget = budget }

    public var remaining: Int? { budget.tokens.map { max(0, $0 - spent) } }
    public var isExhausted: Bool { remaining.map { $0 <= 0 } ?? false }

    /// Checks whether a turn estimated at `tokens` may start at `now`.
    public func check(estimated tokens: Int, now: TimeInterval) -> MindError? {
        if isExhausted { return .budgetExhausted }
        if let last = lastTurnAt, now - last < budget.cooldown { return .coolingDown(remaining: budget.cooldown - (now - last)) }
        if tokens > budget.perTurn { return .turnTooLarge(estimated: tokens, limit: budget.perTurn) }
        if let r = remaining, tokens > r { return .budgetExhausted }
        return nil
    }

    public mutating func charge(_ tokens: Int, at now: TimeInterval) {
        spent += max(0, tokens)
        turns += 1
        lastTurnAt = now
    }
}

/// Runs one actor's mind: budget, cooldown, history and the fallback to canned rules.
public final class MindSession {
    public let actorId: String
    public let mind: Mind
    public var provider: MindProvider?
    public private(set) var ledger: TokenLedger
    public private(set) var history: [MindTurn] = []
    /// How many past turns are sent with each prompt.
    public var historyLimit = 6
    private let canned: CannedMindProvider

    public init(actorId: String, mind: Mind, provider: MindProvider? = nil) {
        self.actorId = actorId
        self.mind = mind
        self.provider = provider
        self.ledger = TokenLedger(budget: mind.budget)
        self.canned = CannedMindProvider(rules: mind.canned)
    }

    public func wakes(on event: String) -> Bool { mind.triggers.contains(event) }

    public func prompt(event: String, heard: String? = nil, actorName: String? = nil, senses: [String: JsonValue]) -> MindPrompt {
        let allowed = senses.filter { mind.senses.contains($0.key) || $0.key == "animations" || $0.key == "sounds" }
        return MindPrompt(actorId: actorId, actorName: actorName, persona: mind.persona, event: event, heard: heard, senses: allowed, tools: mind.tools, history: Array(history.suffix(historyLimit)), maxTokens: mind.budget.perTurn)
    }

    /// Asks the model, or the canned rules when there is no model or no budget. The commands returned
    /// are already filtered by the mind's `tools`.
    public func respond(to prompt: MindPrompt, now: TimeInterval = Date().timeIntervalSince1970, completion: @escaping (Result<[ActorCommand], MindError>) -> Void) {
        guard let provider = provider else {
            completion(.success(filter(canned.reply(to: prompt)?.commands ?? [])))
            return
        }
        if let error = ledger.check(estimated: prompt.estimatedTokens, now: now) {
            if case .coolingDown = error { completion(.failure(error)); return }
            // No budget left: fall back to the canned rules so the actor still reacts.
            if let reply = canned.reply(to: prompt) { completion(.success(filter(reply.commands))) } else { completion(.failure(error)) }
            return
        }
        provider.respond(to: prompt) { [weak self] result in
            guard let self = self else { return }
            switch result {
            case .success(let reply):
                let tokens = reply.tokensUsed ?? (prompt.estimatedTokens + MindTurn.estimateTokens(reply.text))
                self.ledger.charge(tokens, at: now)
                self.history.append(MindTurn(event: prompt.event, heard: prompt.heard, replyText: reply.text, tokens: tokens))
                completion(.success(self.filter(reply.commands)))
            case .failure(let error):
                if let reply = self.canned.reply(to: prompt) { completion(.success(self.filter(reply.commands))) }
                else { completion(.failure(.provider("\(error)"))) }
            }
        }
    }

    func filter(_ commands: [ActorCommand]) -> [ActorCommand] { commands.filter { mind.allows($0) } }
}
