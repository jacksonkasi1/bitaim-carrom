#!/usr/bin/env bash
# Runs the app's REAL BoardDetector + ShotPlanner on screenshots (no phone needed).
# Usage: tools/cv-test/run.sh screenshot1.jpg [screenshot2.png ...]
# Output: printed detections + annotated images in tools/cv-test/out/
#   green box = board, green rings = pockets, yellow = striker,
#   magenta = coins, cyan = striker→hit spot, green line = coin→pocket,
#   yellow dot = gold spot.
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
SRC="$HERE/../../android/app/src/main/java/com/bitaim/carromaim/cv"
JAR="$HERE/lib/opencv-4.5.1-2.jar"
mkdir -p "$HERE/lib" "$HERE/out" "$HERE/classes"
[ -f "$JAR" ] || curl -fL -o "$JAR" https://repo.maven.apache.org/maven2/org/openpnp/opencv/4.5.1-2/opencv-4.5.1-2.jar
javac -encoding UTF-8 -d "$HERE/classes" -cp "$JAR" \
  $(find "$HERE/stubs" -name '*.java') \
  "$SRC/BoardDetector.java" "$SRC/Coin.java" "$SRC/GameState.java" "$SRC/ShotPlanner.java" \
  "$SRC/TeamMatcher.java" "$SRC/ShotTracker.java" "$SRC/RoutePlanner.java" "$SRC/PhysicsVerifier.java" \
  "$SRC/AutoPlayController.java" "$SRC/GoldSceneLock.java" \
  "$SRC/ClusterPlanner.java" \
  "$SRC/AimGuideDetector.java" \
  "$SRC/AimAlignment.java" \
  "$SRC/TargetColourLock.java" "$SRC/QueenRule.java" \
  "$HERE/Harness.java"
cd "$HERE" && java -cp "classes:$JAR" Harness "$@"
