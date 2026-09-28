# The Scene node

JsonScene adds one node type to [JsonUI](https://github.com/bclnet/JsonUI):
`Scene`. A scene is a 3D stage with *actors*: a singing bush that stays put,
or a dog that wanders around the room. It lives inside an ordinary JsonUI
document, so a scene has the same `_ui` header (state, script, strings), the
same `"$path"` bindings and the same host actions as a form.

```json
{
  "_ui": { "version": 1, "state": { "mood": "quiet" } },
  "type": "Scene",
  "anchor": "code",
  "actors": [
    {
      "id": "bush",
      "body": {
        "model": "https://raw.githubusercontent.com/KhronosGroup/glTF-Sample-Assets/main/Models/BoxAnimated/glTF-Binary/BoxAnimated.glb",
        "animations": { "idle": { "clip": 0, "loop": true }, "sing": { "clip": 0, "speed": 2 } },
        "sounds": { "song": "https://example.com/song.mp3" }
      },
      "on": {
        "tap": [ { "play": "sing" }, { "sound": "song" }, { "set": { "mood": "singing" } } ],
        "soundEnd": [ { "play": "idle" }, { "set": { "mood": "quiet" } } ]
      }
    }
  ]
}
```

Everything an actor does is a *command* from one vocabulary (`play`, `sound`,
`say`, `moveTo`, …). Commands come from three sources that the renderer treats
alike: event handlers (`on`), behaviors (autonomous movement) and a mind (an
AI model spending a token budget). Adding the mind later never changes the
document format, only who emits the commands.

## Scene

| key | type | meaning |
| --- | --- | --- |
| `type` | `"Scene"` | required |
| `anchor` | `"code"` \| `"floor"` \| `"free"` | where the scene origin sits: on the glyph / QR code (default), on the floor below it, or floating in front of the viewer |
| `scale` | number | uniform scale of the whole scene, default 1 |
| `bounds` | `{ "radius": r }` or `{ "min": [x,y,z], "max": [x,y,z] }` | where mobile actors may go, in scene metres; default radius 2 |
| `environment` | object | `lighting` (`"auto"` default, `"none"`, `"studio"`), `shadows` (bool), `ground` (bool, an invisible shadow catcher) |
| `actors` | array | the actors, see below |
| `on` | object | scene events: `appear`, `disappear`, `tap` (tap on empty space) |

Positions use metres, +Y up, and the scene origin is the anchor point. A
`"code"` anchored scene puts `[0, 0, 0]` at the centre of the code with +Z
towards the viewer; a `"floor"` anchored one projects that point onto the
floor. Rotations are `[pitch, yaw, roll]` in degrees.

## Actor

| key | type | meaning |
| --- | --- | --- |
| `id` | string | required, unique in the scene; used by `moveTo`, `lookAt`, `perchOn` and the `actor` host action |
| `name` | string | display name (speech bubbles, accessibility) |
| `transform` | `{ "position": [x,y,z], "rotation": [pitch,yaw,roll], "scale": s }` | initial pose, default at the origin |
| `body` | object | model, animations, sounds; an actor without a body is invisible (a sound source or a waypoint) |
| `mobility` | object | how the actor moves; absent means it never leaves its spot (the bush) |
| `behaviors` | array | autonomous behaviours, highest active priority wins |
| `mind` | object | a personality that emits commands through an AI model within a budget |
| `on` | object | event handlers made of commands and JsonUI actions |

### `body`

| key | type | meaning |
| --- | --- | --- |
| `model` | string or object | a glTF binary (`.glb`) URL, or an object keyed by format so each platform picks what it renders best: `{ "glb": url, "usdz": url }`. Relative URLs resolve against the document URL. |
| `scale` | number | model units to metres, default 1 (the Khronos Fox is centimetres: `0.01`) |
| `animations` | object | name → clip. A clip is a string or an integer (the glTF animation name or index) or `{ "clip": ..., "loop": bool, "speed": n }` |
| `default` | string | animation played on appear, default `"idle"` when it exists |
| `sounds` | object | name → sound. A sound is a URL or `{ "url": ..., "loop": bool, "volume": 0..1, "spatial": bool }` |
| `sockets` | object | logical point → model node name (`{ "mouth": "Head" }`), used to position speech and by `perchOn` |

### `mobility`

| key | type | meaning |
| --- | --- | --- |
| `mode` | `"ground"` \| `"air"` | walks on the floor plane, or flies inside an altitude range |
| `speed` | number | metres per second, default 0.5 |
| `turnRate` | number | degrees per second, default 180 |
| `altitude` | `[min, max]` | for `air`, default `[0.3, 1.5]` |
| `bounds` | as the scene bounds | overrides the scene bounds for this actor |
| `idle`, `move` | string | animation names for standing and moving, default `"idle"` and `"walk"` (`"fly"` for `air`) |

### `behaviors`

Each behavior has a `type`, an optional `priority` (default 0), an optional
`when` (a dynamic value, e.g. `"${state.called}"`, `"$scared"`) and type
specific keys. Every frame the runner picks the highest priority behavior
whose `when` is true (or has none) and steers towards its goal.

| type | keys | does |
| --- | --- | --- |
| `idle` | | stands still, plays the idle animation |
| `wander` | `radius` (default: bounds), `pause` (seconds between legs, default `[1, 4]`) | random walk / flight around the origin |
| `approach` | `target` (actor id or `"user"`), `stopAt` (metres, default 0.6) | goes to the target and stops short of it |
| `flee` | `from`, `distance` (default 1.5) | keeps at least `distance` from the target |
| `follow` | `target`, `stopAt` (default 1.0) | approach that re-runs whenever the target moves |
| `patrol` | `points` (array of `[x,y,z]`), `loop` (default true) | visits the points in order |
| `lookAt` | `target` | turns (and only turns) towards the target; combines with any other behavior |
| `perchOn` | `target`, `socket` | air only, lands on another actor's socket and follows it |
| `flyTo` | `point` | air only, flies to a point and hovers |

### `mind`

| key | type | meaning |
| --- | --- | --- |
| `persona` | string | who the actor is, in prose; the system prompt |
| `senses` | array | what the actor is told each turn: `userDistance`, `userLooking`, `timeOfDay`, `state` (a snapshot of the document state), `actors` (the other actors' positions), `lastEvent` |
| `tools` | array | commands the model may emit, default all of `say`, `play`, `sound`, `moveTo`, `lookAt`, `set` |
| `budget` | `{ "tokens": total, "perTurn": max, "cooldown": seconds }` | the token allowance for this actor's lifetime, the cap per turn and the minimum pause between turns |
| `triggers` | array | events that start a turn: `tap`, `near`, `far`, `spoken`, `timer` (with `interval` seconds); default `["tap", "near"]` |
| `canned` | array | rules used when no model is attached (and as a fallback when the budget is exhausted): `{ "match": "regex over the event and heard text", "do": [commands] }` |

The provider is not part of the document. The host app attaches a
`MindProvider` (Swift) / `MindProvider` (Kotlin) that turns a
`MindPrompt` into a `MindReply`; JsonScene ships the canned rule provider and
the prompt / reply format, so any model can be wired in a few lines. Speech
(`say`) is spoken on device by the renderer.

## Commands

A command is an object with one verb key. Handlers, behaviors and minds all
produce the same commands.

| command | form | does |
| --- | --- | --- |
| `play` | `{ "play": "sing" }`, `{ "play": { "animation": "sing", "loop": false, "speed": 1 } }` | plays a body animation |
| `sound` | `{ "sound": "song" }`, `{ "sound": { "name": "song", "loop": true } }` | plays a body sound |
| `stopSound` | `{ "stopSound": "song" }` or `{ "stopSound": true }` | stops one or every sound |
| `say` | `{ "say": "Hello!" }` | shows a speech bubble and speaks the text |
| `moveTo` | `{ "moveTo": [x, y, z] }`, `{ "moveTo": "user" }`, `{ "moveTo": { "target": "bush", "stopAt": 0.4 } }` | walks or flies to a point or a target (mobile actors only) |
| `lookAt` | `{ "lookAt": "user" }` | turns towards a target |
| `stop` | `{ "stop": true }` | stops moving |
| `wait` | `{ "wait": 1.5 }` | pauses the command sequence |
| `set` | `{ "set": { "mood": "singing" } }` | sets document state (a JsonUI action) |
| `emit` | `{ "emit": "sang" }` | fires the actor's `on.sang` handler |

Anything that is not a command is treated as a JsonUI action, so `"js: ..."`
scripts and `{ "name": "toast", "args": {...} }` host actions work in handlers.

### Events (`on`)

`appear`, `disappear`, `tap`, `near` (the user came within `nearDistance`,
default 1 m), `far`, `animationEnd` (with `$event.animation`), `soundEnd`
(`$event.sound`), `arrived` (a `moveTo` completed), `spoken` (`$event.text`),
and any name fired with `emit`.

## Host action

The renderer registers one host action, `actor`, so buttons and scripts in
the same document can drive the stage:

```json
{ "type": "Button", "title": "Sing", "action": { "name": "actor", "args": { "id": "bush", "do": [ { "play": "sing" }, { "sound": "song" } ] } } }
```

From a script: `host.invoke("actor", { id: "snoopy", do: [{ moveTo: "user" }] })`.

## Platform notes

| platform | renderer | models | sound |
| --- | --- | --- | --- |
| iOS | SceneKit (`JsonScene` target) | `.usdz`, `.scn` and `.dae` natively; `.glb` when the app links [GLTFKit2](https://github.com/warrenm/GLTFKit2) | AVAudioEngine / SCNAudioSource |
| Android | Filament through SceneView (`jsonscene-compose`) | `.glb` | MediaPlayer |
| Meta Quest | Meta Spatial SDK entities (`jsonscene-spatial`) | `.glb` (`Mesh` + `Animated` components) | MediaPlayer |
