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

## Fragments

Bodies, minds, sounds and whole actors can be shared files referenced with
JsonUI [fragments](https://github.com/bclnet/JsonUI/blob/master/docs/SCHEMA.md#fragments)
and overridden in place:

```json
"body": { "$ref": "bodies/fox.json", "sounds": { "bark": "bark.wav" } },
"mind": { "$ref": "https://raw.githubusercontent.com/bclnet/JsonMind/master/examples/minds/snoopy.json" }
```

Renderers resolve fragments against the document's URL before parsing
(`JsonFragmentResolver` / `JsonFragments`), so `SceneDocument` always sees
plain JSON.

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

The actor's personality, a [JsonMind](https://github.com/bclnet/JsonMind) mind:
persona, senses, tools, token budget, triggers and canned rules
(`docs/MIND.md` there has the schema). Usually a fragment:

```json
"mind": { "$ref": "minds/snoopy.json", "budget": { "tokens": 5000 } }
```

The scene adds these senses to the mind's own: `animations`, `sounds` and
`behaviors` (the actor's repertoire, sent in the system prompt), and it
executes the mind's commands like any other. A mind steers with
`{ "behave": "approach" }`: the named behavior (from the actor's list, or any
behavior type) wins over the list until another `behave`, `idle` clears it.
The provider (TokenX) is attached by the app through `mindProvider`; until
then the canned rules answer.

## Commands

Commands are JsonMind's vocabulary (`play`, `sound`, `stopSound`, `say`,
`moveTo`, `lookAt`, `behave`, `stop`, `wait`, `emit`, plus any JsonUI action
such as `set`); see
[JsonMind's MIND.md](https://github.com/bclnet/JsonMind/blob/master/docs/MIND.md#commands).
Handlers, behaviors and minds all produce the same commands, and the scene
executes them: `moveTo` and `behave` need a mobile actor, `play` an animation
of that name, `sound` a sound of that name.

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
