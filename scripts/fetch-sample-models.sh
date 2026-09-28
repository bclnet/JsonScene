#!/usr/bin/env sh
# Downloads the Khronos glTF sample models the examples reference into assets/models/
# (CC0 / permissive licenses, see https://github.com/KhronosGroup/glTF-Sample-Assets).
set -e
cd "$(dirname "$0")/.."
mkdir -p assets/models
for m in Fox CesiumMan BoxAnimated; do
  echo "fetching $m.glb"
  curl -sSL -o "assets/models/$m.glb" "https://raw.githubusercontent.com/KhronosGroup/glTF-Sample-Assets/main/Models/$m/glTF-Binary/$m.glb"
done
ls -la assets/models
