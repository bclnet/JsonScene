# Examples

| file | what |
| --- | --- |
| `scene-bush.json` | The singing bush: one static actor with a `sing` animation, a song, and a mind (a token budget, canned rules) that sings when tapped. |
| `scene-snoopy.json` | Snoopy and Woodstock: a ground actor (the Khronos "Fox" stands in for Snoopy) that wanders, comes when called (`tap`) and flees when `scared`, plus an air actor (the Khronos "Duck", which has no animations, so it just flies) that perches on his back. |
| `bodies/` | body fragments (model, scale, animations, sockets) the scenes refer to with `{ "$ref": "bodies/fox.json" }` |
| `minds/` | mind fragments, copied from [JsonMind](https://github.com/bclnet/JsonMind/tree/master/examples/minds) |

The scenes use JsonUI [fragments](https://github.com/bclnet/JsonUI/blob/master/docs/SCHEMA.md#fragments):
a body or a mind is a `$ref` to a shared file, with keys beside it (the
sounds, a budget) overriding the fragment. A renderer resolves the fragments
against the document's URL before parsing, so the same body can serve many
scenes and a mind can be swapped by changing one line.

The models are the Khronos glTF sample assets (CC BY 4.0, see
<https://github.com/KhronosGroup/glTF-Sample-Assets>). `scripts/fetch-sample-models.sh`
downloads them into `assets/models/` for offline use; replace the `model`
URLs with your own rigged characters when you have them. The song is a
synthesised tune in `assets/sounds/`.

To show a scene from a QR code with [QRX](https://github.com/bclnet/QRX),
point the code at the raw file:

```
size: *4
https://raw.githubusercontent.com/bclnet/JsonScene/master/examples/scene-bush.json
```
