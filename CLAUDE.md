# JsonScene

The `Scene` node for JsonUI: a JSON scene of 3D actors with bodies (glTF
models, animation clips, sounds, sockets), mobility (ground or air), steering
behaviors and, through JsonMind, minds. Renderers: SceneKit on iOS, Filament
via SceneView in Compose, Meta Spatial SDK on Quest.

Family: JsonUI → JsonMind → **JsonScene** → QRX. JsonScene re-exports JsonMind from
JsonSceneCore so hosts import one module. `docs/SCENE.md` is the reference,
`schema/scene.schema.json` the schema, `examples/` the scenes, bodies and minds.

## Layout

```
Package.swift                 deps: JsonUI, JsonMind (JSONUI_PATH / JSONMIND_PATH for local checkouts)
ios/Sources/JsonSceneCore     SceneDocument (anchor code/floor/free, bounds, environment, actors, on, issues), Actor,
                              Steering + BehaviorRunner (priority, when, override from `behave`), ActorSimulation
                              (events, timers, near/far, locomotion animation, `behaviors` sense, host action "actor"),
                              AssetLocator, Vec3
ios/Sources/JsonScene         SceneKit: JsonSceneController, JsonSceneView, ActorNode, ModelLoader, JsonSceneNode.register()
ios/Tests                     36 tests
android/jsonscene-core        Kotlin mirror: SceneDriver, simulation, steering
android/jsonscene-android     ActorRenderer and platform helpers
android/jsonscene-compose     FilamentSceneRenderer, JsonSceneView, JsonScene.register()
android/jsonscene-spatial     SpatialSceneRenderer for Meta Spatial SDK (Quest)
examples/bodies               box.json, fox.json, duck.json (sample models fetched by scripts/fetch-sample-models.sh)
examples/minds                mind fragments; scenes reference bodies and minds with $ref
assets/sounds                 sample sounds
third_party/JsonUI, JsonMind  submodules for the Gradle composite build
```

## Build and test

```
swift test                                   # Linux: core tests; JsonScene (SceneKit) compiles only in Xcode
cd android && ./gradlew build                # 34 Kotlin tests; includes JsonUI and JsonMind from third_party when standalone
scripts/fetch-sample-models.sh               # sample glTF bodies for the examples
```

## Conventions

- Behaviors are JsonScene's; minds are JsonMind's. A mind's `behave` command hands a behavior
  JSON to `BehaviorRunner.override`, which is how thinking reaches moving.
- Actor commands from a mind go through `ActorSimulation`; renderers only draw and animate.
- Hosts pass a `mindProvider` into the controller or renderer; with none, actors run canned rules.
- Scene documents use fragments for bodies and minds; keep the examples resolvable by URL.
- Keep Swift and Kotlin in step, with tests on both sides.

## Gotchas

- SceneView 2.3.0 is pinned: 4.x needs Kotlin 2.4 and sceneview-core 3.3.0 is missing on Maven.
- `android/settings.gradle.kts` includes JsonUI and JsonMind only when `gradle.parent == null`
  (QRX includes them itself). Included builds are named `{ name = "JsonUI" }` etc.
- The Meta Spatial renderer's animation behaviour and the Quest camera need device checks;
  neither runs in CI.
- Local `master` in a checkout may be stale; work was pushed from a `claude/...` branch.
