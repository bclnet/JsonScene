# Examples

| file | what |
| --- | --- |
| `scene-bush.json` | The singing bush: one static actor with a `sing` animation, a song, a mind with a token budget and canned rules, and state updates when the song ends. |
| `scene-snoopy.json` | Snoopy and Woodstock: a ground actor (the Khronos "Fox" stands in for Snoopy) that wanders, comes when called (`tap`) and flees when `scared`, plus an air actor (the Khronos "Duck", which has no animations, so it just flies) that perches on his back. |

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
