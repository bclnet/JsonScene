//
//  ActorSimulation.swift
//  JsonScene
//
//  The renderer independent part of a running scene. Renderers feed it the
//  frame time and the viewer position and read back what to show: every
//  actor's pose, which animation should play, and the outputs (commands and
//  events) produced by handlers, behaviors and minds.
//
//  A renderer implements `ActorOutput` for the things only it can do (load
//  a model, play a clip, play a sound, speak) and calls `perform` / `tick`.
//

import Foundation
import JsonUICore
import JsonMind

/// Something the renderer must do for an actor.
public enum ActorOutput: Equatable {
    case play(actor: String, animation: String, loop: Bool, speed: Double)
    case sound(actor: String, name: String, loop: Bool)
    case stopSound(actor: String, name: String?)
    case say(actor: String, text: String)
    case event(actor: String, name: String, payload: [String: JsonValue])
}

public final class ActorState {
    public let actor: Actor
    public var pose: ActorPose
    public var runner: BehaviorRunner?
    public var mind: MindSession?
    /// Animation currently requested by locomotion, so it is only re-sent when it changes.
    public private(set) var locomotionAnimation: String?
    /// An explicit `moveTo` command in progress; it overrides behaviors until arrival or `stop`.
    public var commandGoal: SteeringGoal?
    public var commandTarget: Target?
    public var commandStopAt: Double = 0.6
    /// True while an explicit `play` is running and locomotion should not replace it.
    public var playingCommand = false
    public var isNear = false
    /// Queued steps waiting on a `wait`.
    var pending: [(runAt: Double, steps: [ActorStep])] = []

    init(actor: Actor, bounds: Bounds) {
        self.actor = actor
        self.pose = ActorPose(position: actor.transform.position, heading: actor.transform.rotation.yaw)
        if let mobility = actor.mobility, mobility.mode != .none {
            runner = BehaviorRunner(behaviors: actor.behaviors, mobility: mobility, bounds: bounds)
        }
        if let mind = actor.mind { self.mind = MindSession(actorId: actor.id, mind: mind) }
    }

    func setLocomotionAnimation(_ name: String?) -> Bool {
        guard name != locomotionAnimation else { return false }
        locomotionAnimation = name
        return true
    }
}

public final class ActorSimulation {
    public let scene: SceneDocument
    public let context: JsonContext
    public private(set) var actors: [String: ActorState] = [:]
    public private(set) var order: [String] = []
    public var userPosition: Vec3?
    /// Forward direction of the viewer, for `userLooking`.
    public var userForward: Vec3?
    public private(set) var time: Double = 0
    /// Attach a model here; actors with a `mind` use it.
    public var mindProvider: MindProvider? {
        didSet { for a in actors.values { a.mind?.provider = mindProvider } }
    }
    private var timerDue: [String: Double] = [:]

    public init(scene: SceneDocument, context: JsonContext) {
        self.scene = scene
        self.context = context
        for actor in scene.actors {
            actors[actor.id] = ActorState(actor: actor, bounds: scene.bounds)
            order.append(actor.id)
        }
    }

    public subscript(id: String) -> ActorState? { actors[id] }

    public var senses: BehaviorRunner.Senses {
        BehaviorRunner.Senses(userPosition: userPosition, actorPositions: actors.mapValues(\.pose.position))
    }

    // MARK: - Events and commands

    /// Fires an actor event: runs its `on` handler and wakes its mind when the event is a trigger.
    @discardableResult
    public func fire(_ event: String, on actorId: String, payload: [String: JsonValue] = [:], heard: String? = nil) -> [ActorOutput] {
        guard let state = actors[actorId] else { return [] }
        var outputs: [ActorOutput] = [.event(actor: actorId, name: event, payload: payload)]
        if let script = state.actor.on[event] {
            outputs += run(script.steps, on: state, payload: payload)
        }
        if let mind = state.mind, mind.wakes(on: event) {
            outputs += think(state, event: event, heard: heard)
        }
        return outputs
    }

    /// Fires a scene level event (`appear`, `disappear`, `tap` on empty space).
    public func fireScene(_ event: String) {
        guard let script = scene.on[event] else { return }
        for step in script.steps { if case .action(let a) = step { context.perform(a) } }
    }

    /// Runs commands on an actor (the `actor` host action, a mind reply).
    @discardableResult
    public func perform(_ commands: [ActorCommand], on actorId: String) -> [ActorOutput] {
        guard let state = actors[actorId] else { return [] }
        return run(commands.map(ActorStep.command), on: state, payload: [:])
    }

    /// Outputs for the `appear` event of every actor, including their default animation.
    public func start() -> [ActorOutput] {
        var outputs: [ActorOutput] = []
        for id in order {
            guard let state = actors[id] else { continue }
            if let name = state.actor.body?.defaultAnimation, let clip = state.actor.body?.animations[name] {
                _ = state.setLocomotionAnimation(name)
                outputs.append(.play(actor: id, animation: name, loop: clip.loop || name == state.actor.mobility?.idleAnimation, speed: clip.speed))
            }
            outputs += fire("appear", on: id)
            if let mind = state.actor.mind, mind.triggers.contains("timer") { timerDue[id] = time + mind.interval }
        }
        fireScene("appear")
        return outputs
    }

    func run(_ steps: [ActorStep], on state: ActorState, payload: [String: JsonValue]) -> [ActorOutput] {
        var outputs: [ActorOutput] = []
        var index = 0
        while index < steps.count {
            let step = steps[index]
            index += 1
            switch step {
            case .action(let action):
                context.perform(action)
            case .command(let command):
                switch command {
                case .play(let name, let loop, let speed):
                    let clip = state.actor.body?.animations[name]
                    state.playingCommand = !(loop ?? clip?.loop ?? false)
                    _ = state.setLocomotionAnimation(name)
                    outputs.append(.play(actor: state.actor.id, animation: name, loop: loop ?? clip?.loop ?? false, speed: speed ?? clip?.speed ?? 1))
                case .sound(let name, let loop):
                    outputs.append(.sound(actor: state.actor.id, name: name, loop: loop ?? state.actor.body?.sounds[name]?.loop ?? false))
                case .stopSound(let name):
                    outputs.append(.stopSound(actor: state.actor.id, name: name))
                case .say(let text):
                    outputs.append(.say(actor: state.actor.id, text: context.resolve(.string(text)).stringValue ?? text))
                case .moveTo(let target, let stopAt):
                    guard state.actor.isMobile else { break }
                    state.commandStopAt = stopAt ?? 0.6
                    switch target {
                    case .point(let p): state.commandGoal = .seek(Vec3(p), stopAt: stopAt ?? 0.05); state.commandTarget = nil
                    case .target(let t): state.commandTarget = t; state.commandGoal = senses.position(of: t).map { .seek($0, stopAt: stopAt ?? 0.6) } ?? SteeringGoal.none
                    }
                case .lookAt(let target):
                    if let p = senses.position(of: target) {
                        let to = p - state.pose.position
                        if to.horizontalLength > 1e-6 { state.pose.heading = to.yaw }
                    }
                case .stop:
                    state.commandGoal = nil
                    state.commandTarget = nil
                case .wait(let seconds):
                    state.pending.append((runAt: time + seconds, steps: Array(steps[index...])))
                    return outputs
                case .emit(let event):
                    outputs += fire(event, on: state.actor.id, payload: payload)
                case .behave(let spec):
                    // A mind (or a handler) picks a behavior: by name from the actor's list, or described inline.
                    if let name = spec.text {
                        if name == "idle" || name == "none" { state.runner?.override = Behavior(kind: .idle) }
                        else if let existing = state.actor.behaviors.first(where: { $0.type == name }) { state.runner?.override = existing }
                        else if let kind = Behavior.kind(type: name, [:]) { state.runner?.override = Behavior(kind: kind) }
                    } else if let behavior = Behavior(spec) {
                        state.runner?.override = behavior
                    }
                    state.commandGoal = nil
                    state.commandTarget = nil
                }
            }
        }
        return outputs
    }

    func think(_ state: ActorState, event: String, heard: String?) -> [ActorOutput] {
        guard let session = state.mind else { return [] }
        let prompt = session.prompt(event: event, heard: heard, actorName: state.actor.name, senses: senseValues(for: state))
        var outputs: [ActorOutput] = []
        var finished = false
        session.respond(to: prompt, now: Date().timeIntervalSince1970) { [weak self] result in
            guard let self = self, let state = self.actors[state.actor.id] else { return }
            switch result {
            case .success(let commands):
                let produced = self.run(commands.map(ActorStep.command), on: state, payload: [:])
                if finished { self.asyncOutputs?(produced) } else { outputs += produced }
            case .failure(let error):
                self.context.log(.warn, "mind of \(state.actor.id): \(error)")
            }
        }
        finished = true
        return outputs
    }

    /// Receives outputs produced after an asynchronous mind reply; the renderer sets this.
    public var asyncOutputs: (([ActorOutput]) -> Void)?

    public func senseValues(for state: ActorState) -> [String: JsonValue] {
        var s: [String: JsonValue] = [:]
        if let user = userPosition {
            s["userDistance"] = .number((user.distance(to: state.pose.position) * 100).rounded() / 100)
            if let forward = userForward {
                let to = (state.pose.position - user).normalized
                s["userLooking"] = .bool(forward.normalized.dot(to) > 0.8)
            }
        }
        s["timeOfDay"] = .string(ActorSimulation.timeOfDay(Date()))
        s["state"] = context.store.snapshot
        s["actors"] = .object(actors.filter { $0.key != state.actor.id }.mapValues { $0.pose.position.value })
        if let body = state.actor.body {
            s["animations"] = .array(body.animations.keys.sorted().map(JsonValue.string))
            s["sounds"] = .array(body.sounds.keys.sorted().map(JsonValue.string))
        }
        if state.actor.isMobile { s["behaviors"] = .array(Array(Set(state.actor.behaviors.map(\.type) + ["idle"])).sorted().map(JsonValue.string)) }
        return s
    }

    static func timeOfDay(_ date: Date) -> String {
        let hour = Calendar.current.component(.hour, from: date)
        switch hour {
        case 5..<12: return "morning"
        case 12..<17: return "afternoon"
        case 17..<21: return "evening"
        default: return "night"
        }
    }

    // MARK: - Frames

    /// Advances every actor by `dt` seconds and returns what changed.
    @discardableResult
    public func tick(dt: Double) -> [ActorOutput] {
        time += dt
        var outputs: [ActorOutput] = []
        let senses = self.senses
        for id in order {
            guard let state = actors[id] else { continue }
            // Delayed steps.
            let due = state.pending.filter { $0.runAt <= time }
            if !due.isEmpty {
                state.pending.removeAll { $0.runAt <= time }
                for item in due { outputs += run(item.steps, on: state, payload: [:]) }
            }
            // Near / far.
            if let user = userPosition {
                let near = user.distance(to: state.pose.position) <= state.actor.nearDistance
                if near != state.isNear {
                    state.isNear = near
                    outputs += fire(near ? "near" : "far", on: id)
                }
            }
            // Timer minds.
            if let due = timerDue[id], time >= due, let mind = state.actor.mind {
                timerDue[id] = time + mind.interval
                outputs += fire("timer", on: id)
            }
            // Movement.
            guard var runner = state.runner, let mobility = state.actor.mobility else { continue }
            var moveGoal: SteeringGoal
            var faceGoal: SteeringGoal?
            if var goal = state.commandGoal {
                if let t = state.commandTarget, let p = senses.position(of: t) { goal = .seek(p, stopAt: state.commandStopAt); state.commandGoal = goal }
                moveGoal = goal
            } else {
                let goals = runner.goals(pose: state.pose, senses: senses, time: time) { behavior in
                    guard let when = behavior.when else { return true }
                    return context.resolve(when).isTruthy
                }
                moveGoal = goals.move
                faceGoal = goals.face
            }
            var result = Steering.step(pose: state.pose, goal: moveGoal, mobility: mobility, bounds: runner.bounds, dt: dt)
            if !result.moving, let face = faceGoal {
                result.pose = Steering.step(pose: result.pose, goal: face, mobility: mobility, bounds: runner.bounds, dt: dt).pose
            }
            state.pose = result.pose
            state.runner = runner
            if result.arrived, state.commandGoal != nil {
                state.commandGoal = nil
                state.commandTarget = nil
                outputs += fire("arrived", on: id)
            }
            // Locomotion animation.
            if !state.playingCommand {
                let wanted = result.moving ? mobility.moveAnimation : mobility.idleAnimation
                if state.actor.body?.animations[wanted] != nil, state.setLocomotionAnimation(wanted) {
                    let clip = state.actor.body?.animations[wanted]
                    outputs.append(.play(actor: id, animation: wanted, loop: true, speed: clip?.speed ?? 1))
                }
            }
        }
        return outputs
    }

    /// The renderer reports a one-shot animation finishing so locomotion can resume.
    @discardableResult
    public func animationEnded(_ animation: String, on actorId: String) -> [ActorOutput] {
        guard let state = actors[actorId] else { return [] }
        state.playingCommand = false
        _ = state.setLocomotionAnimation(nil)
        return fire("animationEnd", on: actorId, payload: ["animation": .string(animation)])
    }

    @discardableResult
    public func soundEnded(_ sound: String, on actorId: String) -> [ActorOutput] {
        fire("soundEnd", on: actorId, payload: ["sound": .string(sound)])
    }

    // MARK: - Host action

    /// Registers the `actor` host action: `{ "name": "actor", "args": { "id": "bush", "do": [...] } }`.
    /// Outputs are delivered through `handler`.
    public func registerHostAction(handler: @escaping ([ActorOutput]) -> Void) {
        context.runtime.actions.register("actor") { [weak self] _, args, _ in
            guard let self = self, let id = args["id"].stringValue else { return nil }
            var commands: [ActorCommand] = []
            if let script = ActorScript(args["do"]) { commands = script.commands }
            else if let single = ActorCommand(args) { commands = [single] }
            handler(self.perform(commands, on: id))
            return nil
        }
    }
}
