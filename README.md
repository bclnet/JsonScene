# JsonScene

Animated 3D characters described in JSON, rendered on iOS, Android and Meta
Quest. JsonScene adds one node type, `Scene`, to
[JsonUI](https://github.com/bclnet/JsonUI): a stage with *actors* that have a
glTF body with animations and sounds, optional mobility (they walk or fly
around the room), behaviours (wander, approach, flee, follow, patrol, look at,
perch on) and a *mind*: a personality that reacts through an AI model within
a token budget. The singing bush from *The Three Amigos* is one static actor;
Snoopy and Woodstock are two mobile ones (`examples/`).

```json
{
  "type": "Scene",
  "actors": [
    { "id": "snoopy",
      "body": { "model": "Fox.glb", "scale": 0.005, "animations": { "idle": "Survey", "walk": "Walk" } },
      "mobility": { "mode": "ground", "speed": 0.6 },
      "behaviors": [ { "type": "approach", "target": "user", "priority": 5, "when": "$called" }, { "type": "wander" } ],
      "mind": { "persona": "You are Snoopy…", "budget": { "tokens": 50000, "perTurn": 400 } },
      "on": { "tap": { "set": { "called": true } } } }
  ]
}
```

`docs/SCENE.md` is the format reference. Every actor is driven by one command
vocabulary (`play`, `sound`, `say`, `moveTo`, `lookAt`, `behave`, …) whatever
the source: an `on` handler, a behaviour or a mind. The vocabulary and the
minds themselves live in [JsonMind](https://github.com/bclnet/JsonMind);
JsonScene owns the stage: bodies, mobility, behaviours (the geometry that
runs every frame and costs no tokens) and the renderers. Bodies and minds are
usually [fragments](https://github.com/bclnet/JsonUI/blob/master/docs/SCHEMA.md#fragments)
shared between scenes (`examples/bodies`, `examples/minds`).

## Libraries

| platform | core (no UI, tested on the JVM / Linux) | renderer |
| --- | --- | --- |
| iOS, macOS | `JsonSceneCore` (Swift package, `ios/`) | `JsonScene`: SceneKit, `.usdz` / `.scn` / `.dae` natively, `.glb` with [GLTFKit2](https://github.com/warrenm/GLTFKit2) |
| Android | `jsonscene-core` (Kotlin/JVM, `android/`) | `jsonscene-compose`: Filament through [SceneView](https://github.com/SceneView/sceneview-android), `.glb` |
| Meta Quest | `jsonscene-core` | `jsonscene-spatial`: Meta Spatial SDK entities (`Mesh` + `Animated`), `.glb` |

`jsonscene-android` holds what the two Android renderers share: the asset
cache, sounds (MediaPlayer), speech (TextToSpeech) and `SceneDriver`, which
runs the simulation and hands animations and poses to an `ActorRenderer`.

The cores contain the scene document model, the steering and behaviour runner
(`Steering`, `BehaviorRunner`) and the actor simulation (`ActorSimulation`:
events, `wait`, near/far, timers, locomotion animation, `behave`). The mind
machinery comes from JsonMind; the app attaches a token stream by setting a
`MindProvider` (TokenX), and until then the `canned` rules answer.

## Using it

Swift:

```swift
import JsonUI
import JsonScene

JsonSceneNode.register()                   // adds "Scene" to JsonViewRegistry.shared
let model = try JsonUIModel(json: sceneJson)
JsonUIView(model: model)                   // renders the Scene node with SceneKit
```

An AR app that already has a SceneKit anchor uses `JsonSceneController`
directly: add `controller.stage` under the anchor node and call
`controller.update(time:viewer:)` from the render loop.

Android (Compose):

```kotlin
JsonScene.register()                       // com.bclnet.jsonscene.compose
JsonUIView(document = JsonDocument.parse(sceneJson))
```

Quest (Spatial SDK):

```kotlin
val renderer = SpatialSceneRenderer(this, document.root, runtime.context)
renderer.stagePose = Pose(codePosition, codeRotation)   // where the glyph is
renderer.attach()
// each frame: renderer.frame(nanos, headPose)
```

Documents can also drive actors from buttons and scripts through the `actor`
host action: `{ "name": "actor", "args": { "id": "bush", "do": [ { "play": "sing" } ] } }`.

## Building

```
# Swift core (Linux or macOS); the SceneKit target builds on Apple platforms
swift test
# with a local JsonUI checkout instead of the GitHub dependency
JSONUI_PATH=/path/to/JsonUI swift test

# Android and Quest libraries (need the JsonUI and JsonMind submodules)
git submodule update --init
cd android && ./gradlew build

# the Khronos sample models used by the examples, for offline use
scripts/fetch-sample-models.sh
```

## Layout

```
Package.swift             Swift manifest (root, so SwiftPM can add the package by URL)
ios/Sources/JsonSceneCore scene document, commands, steering, minds, simulation
ios/Sources/JsonScene     SceneKit renderer, JsonUI registration
ios/Tests                 JsonSceneCoreTests (36, run on Linux), JsonSceneTests (Apple)
android/jsonscene-core    Kotlin mirror of the core with 34 JVM tests
android/jsonscene-android asset cache, audio, speech, SceneDriver
android/jsonscene-compose Filament / SceneView renderer and composable
android/jsonscene-spatial Meta Spatial SDK renderer
docs/SCENE.md             the format
schema/scene.schema.json  JSON Schema for a Scene node
examples/                 the singing bush and Snoopy scenes, body and mind fragments
assets/sounds/            the bush's song (synthesised, CC0)
third_party/JsonUI        JsonUI submodule
third_party/JsonMind      JsonMind submodule (minds and the command vocabulary)
```

## License

MIT, see [LICENSE](LICENSE). The example models are Khronos glTF sample
assets (CC BY 4.0).
