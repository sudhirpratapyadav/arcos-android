#!/bin/bash
# Builds the arcos library and the joystick demo without Gradle.
#
# Produces:
#   build/arcos.jar        library classes, for a plain jar dependency
#   build/arcos.aar        library as an Android archive, with its manifest
#   build/arcos-demo.apk   the demo app, signed with a debug key
#
# Gradle is only needed by people consuming this from Maven/JitPack; this script
# is the path that works on a machine with nothing but a JDK and the SDK bits.
#
# Usage:
#   ./build.sh            build everything and run the tests
#   ./build.sh test       run the tests only (no SDK needed)
#   ./build.sh lib        library only
set -e
cd "$(dirname "$0")"

SDK="${SDK:-${ANDROID_SDK:-$HOME/android-sdk/android-11}}"
OUT=build
MIN_API=21
PKG=com.arcos.demo

LIB_SRC=$(find arcos/src/main/java -name '*.java')
TEST_SRC=$(find arcos/src/test/java -name '*.java')
DEMO_SRC=$(find demo/src/main/java -name '*.java')

# ---------------------------------------------------------------- tests
# The protocol core has no Android imports, so it runs on a desktop JVM. The two
# transports that do touch Android are excluded here rather than stubbed.
run_tests() {
  echo "== tests"
  local tdir=$OUT/test-classes
  rm -rf "$tdir" && mkdir -p "$tdir"
  local pure
  pure=$(find arcos/src/main/java/com/arcos -maxdepth 1 -name '*.java')
  pure="$pure arcos/src/main/java/com/arcos/transport/SimTransport.java"
  pure="$pure arcos/src/main/java/com/arcos/transport/TcpTransport.java"
  javac -nowarn -d "$tdir" $pure $TEST_SRC
  java -cp "$tdir" com.arcos.ProtocolTest tools/golden_vectors.txt
  java -cp "$tdir" com.arcos.SimulationTest
}

if [ "$1" = "test" ]; then
  run_tests
  exit 0
fi

[ -x "$SDK/aapt2" ] || { echo "SDK not found at $SDK - set SDK= or ANDROID_SDK="; exit 1; }

rm -rf $OUT/classes $OUT/dex $OUT/aar $OUT/lib
mkdir -p $OUT/classes $OUT/dex $OUT/aar

# ------------------------------------------------------------ dependencies
# usb-serial-for-android supplies the chip-specific drivers. It is pure Java, so
# only its classes.jar matters — there is nothing native to unpack.
DEPS=""
for aar in libs/*.aar; do
  [ -e "$aar" ] || continue
  n=$(basename "$aar" .aar)
  mkdir -p "$OUT/aar/$n"
  unzip -q -o "$aar" -d "$OUT/aar/$n"
  if [ -f "$OUT/aar/$n/classes.jar" ]; then
    DEPS="$DEPS:$OUT/aar/$n/classes.jar"
    echo "== dependency $n"
  fi
done
DEPS="${DEPS#:}"

# ------------------------------------------------------------------ library
echo "== javac library"
javac --release 8 -nowarn -classpath "$SDK/android.jar${DEPS:+:$DEPS}" \
      -d $OUT/classes $LIB_SRC 2>&1 | grep -v 'bootstrap class path' || true

echo "== arcos.jar"
(cd $OUT/classes && jar cf ../arcos.jar com)

echo "== arcos.aar"
rm -rf $OUT/aar-stage && mkdir -p $OUT/aar-stage
cp $OUT/arcos.jar $OUT/aar-stage/classes.jar
cp arcos/src/main/AndroidManifest.xml $OUT/aar-stage/AndroidManifest.xml
: > $OUT/aar-stage/R.txt
(cd $OUT/aar-stage && zip -q -r ../arcos.aar .)

run_tests

if [ "$1" = "lib" ]; then
  echo
  echo "Built: $OUT/arcos.jar and $OUT/arcos.aar"
  exit 0
fi

# --------------------------------------------------------------------- demo
echo "== demo apk"
mkdir -p $OUT/demo-classes $OUT/demo-dex
javac --release 8 -nowarn \
      -classpath "$SDK/android.jar:$OUT/classes${DEPS:+:$DEPS}" \
      -d $OUT/demo-classes $DEMO_SRC 2>&1 | grep -v 'bootstrap class path' || true

"$SDK/aapt2" link -o $OUT/demo.unsigned.apk --manifest demo/AndroidManifest.xml \
    -I "$SDK/android.jar" --min-sdk-version $MIN_API

java -cp "$SDK/lib/d8.jar" com.android.tools.r8.D8 \
     --lib "$SDK/android.jar" --release --min-api $MIN_API --output $OUT/demo-dex \
     $(find $OUT/demo-classes $OUT/classes -name '*.class') $(echo "$DEPS" | tr ':' ' ')

(cd $OUT/demo-dex && zip -q -j ../demo.unsigned.apk classes.dex)
"$SDK/zipalign" -f 4 $OUT/demo.unsigned.apk $OUT/demo.aligned.apk

if [ ! -f debug.keystore ]; then
  keytool -genkeypair -keystore debug.keystore -storepass android -keypass android \
    -alias androiddebugkey -keyalg RSA -keysize 2048 -validity 10000 \
    -dname "CN=Android Debug,O=Android,C=US"
fi

java -jar "$SDK/lib/apksigner.jar" sign --ks debug.keystore \
     --ks-pass pass:android --key-pass pass:android \
     --out $OUT/arcos-demo.apk $OUT/demo.aligned.apk

echo
echo "Built: $OUT/arcos.jar, $OUT/arcos.aar, $OUT/arcos-demo.apk"
