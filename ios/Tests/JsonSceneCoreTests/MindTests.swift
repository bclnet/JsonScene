import XCTest
@testable import JsonSceneCore
import JsonUICore

final class MindTests: XCTestCase {
    func testReplyParsing() {
        XCTAssertEqual(MindReply.parse("[{\"say\": \"hi\"}, {\"play\": \"sing\"}]").commands, [.say("hi"), .play(animation: "sing", loop: nil, speed: nil)])
        XCTAssertEqual(MindReply.parse("Sure!\n```json\n[{\"say\": \"hi\"}]\n```").commands, [.say("hi")])
        XCTAssertEqual(MindReply.parse("{\"lookAt\": \"user\"}").commands, [.lookAt(.user)])
        XCTAssertEqual(MindReply.parse("Woof.").commands, [.say("Woof.")])
        XCTAssertEqual(MindReply.parse("  ").commands, [])
        XCTAssertEqual(MindReply.parse("[{\"nuke\": 1}]").commands, [])
    }

    func testPromptText() {
        let prompt = MindPrompt(actorId: "bush", actorName: "Bush", persona: "A shrub.", event: "tap", senses: ["userDistance": 1.5, "animations": ["idle", "sing"]], tools: ["say", "play"])
        XCTAssertTrue(prompt.systemText.contains("You are Bush"))
        XCTAssertTrue(prompt.systemText.contains("{\"say\""))
        XCTAssertTrue(prompt.systemText.contains("{\"play\""))
        XCTAssertFalse(prompt.systemText.contains("{\"moveTo\""))
        XCTAssertTrue(prompt.systemText.contains("Animations: idle, sing"))
        XCTAssertEqual(prompt.userText, "{\"event\":\"tap\",\"userDistance\":1.5}")
        XCTAssertGreaterThan(prompt.estimatedTokens, 20)
    }

    func testLedger() {
        var ledger = TokenLedger(budget: Mind.Budget(tokens: 1000, perTurn: 300, cooldown: 5))
        XCTAssertNil(ledger.check(estimated: 200, now: 0))
        XCTAssertEqual(ledger.check(estimated: 400, now: 0), .turnTooLarge(estimated: 400, limit: 300))
        ledger.charge(600, at: 0)
        XCTAssertEqual(ledger.remaining, 400)
        XCTAssertEqual(ledger.check(estimated: 100, now: 2), .coolingDown(remaining: 3))
        XCTAssertNil(ledger.check(estimated: 100, now: 6))
        ledger.charge(400, at: 6)
        XCTAssertTrue(ledger.isExhausted)
        XCTAssertEqual(ledger.check(estimated: 1, now: 100), .budgetExhausted)
        XCTAssertNil(TokenLedger(budget: Mind.Budget()).remaining)
    }

    func testCannedProvider() {
        let mind = Mind(["persona": "x", "canned": [["match": "^tap", "do": ["play": "sing"]], ["match": "hello|hi", "do": [["say": "hey"], ["set": ["a": 1]]]]]])
        let canned = CannedMindProvider(rules: mind.canned)
        XCTAssertEqual(canned.reply(to: MindPrompt(actorId: "a", persona: "", event: "tap"))?.commands, [.play(animation: "sing", loop: nil, speed: nil)])
        XCTAssertEqual(canned.reply(to: MindPrompt(actorId: "a", persona: "", event: "spoken", heard: "Well HELLO there"))?.commands, [.say("hey")])
        XCTAssertNil(canned.reply(to: MindPrompt(actorId: "a", persona: "", event: "near")))
    }

    struct FakeProvider: MindProvider {
        var reply: Result<MindReply, Error>
        func respond(to prompt: MindPrompt, completion: @escaping (Result<MindReply, Error>) -> Void) { completion(reply) }
    }
    struct Boom: Error {}

    func testSessionChargesBudgetAndFiltersTools() {
        let mind = Mind(["persona": "p", "tools": ["say"], "budget": ["tokens": 500, "perTurn": 400, "cooldown": 0], "canned": [["match": ".*", "do": ["say": "canned"]]]])
        let session = MindSession(actorId: "a", mind: mind, provider: FakeProvider(reply: .success(MindReply(commands: [.say("hi"), .moveTo(.target(.user), stopAt: nil)], tokensUsed: 490, text: "[…]"))))
        var got: [ActorCommand] = []
        session.respond(to: session.prompt(event: "tap", senses: [:]), now: 0) { got = (try? $0.get()) ?? [] }
        XCTAssertEqual(got, [.say("hi")], "moveTo is not an allowed tool")
        XCTAssertEqual(session.ledger.spent, 490)
        XCTAssertEqual(session.history.count, 1)
        // Second turn would exceed the budget: falls back to the canned rules.
        session.respond(to: session.prompt(event: "tap", senses: [:]), now: 1) { got = (try? $0.get()) ?? [] }
        XCTAssertEqual(got, [.say("canned")])
        XCTAssertEqual(session.ledger.spent, 490)
    }

    func testSessionWithoutProviderUsesCannedAndErrorsFallBack() {
        let mind = Mind(["persona": "p", "canned": [["match": "near", "do": ["lookAt": "user"]]]])
        let session = MindSession(actorId: "a", mind: mind)
        var got: [ActorCommand]?
        session.respond(to: session.prompt(event: "near", senses: [:])) { got = try? $0.get() }
        XCTAssertEqual(got, [.lookAt(.user)])
        let failing = MindSession(actorId: "a", mind: mind, provider: FakeProvider(reply: .failure(Boom())))
        failing.respond(to: failing.prompt(event: "near", senses: [:])) { got = try? $0.get() }
        XCTAssertEqual(got, [.lookAt(.user)])
        var error: MindError?
        failing.respond(to: failing.prompt(event: "tap", senses: [:]), now: 100) { if case .failure(let e) = $0 { error = e } }
        if case .provider = error {} else { XCTFail("expected a provider error, got \(String(describing: error))") }
    }

    func testSensesAreFilteredByMind() {
        let session = MindSession(actorId: "a", mind: Mind(["persona": "p", "senses": ["userDistance"]]))
        let prompt = session.prompt(event: "tap", senses: ["userDistance": 1, "state": ["x": 1], "animations": ["idle"]])
        XCTAssertEqual(prompt.senses.keys.sorted(), ["animations", "userDistance"])
    }

    func testMindRoundTrip() throws {
        let mind = try XCTUnwrap(TestSupport.example("scene-bush.json").actor("bush")?.mind)
        XCTAssertEqual(Mind(mind.value), mind)
    }
}
