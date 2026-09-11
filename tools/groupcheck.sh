#!/bin/bash
# Kompilacja grupowa wszystkich zrodel non-UI naraz. Bez AGP nie da sie
# rozwiazac Compose ani AndroidX, wiec liczy sie WYLACZNIE porownanie z
# baseline: nowe bledy dotyczace naszych wlasnych symboli to realne bledy.
export LANG=C.utf8 LC_ALL=C.utf8
SP="$(cd "$(dirname "$0")" && pwd)"; K=$SP/kotlinc
SRC=/home/user/claude-routines/jarvis-app/app/src/main/java
FILES=$(find $SRC -name '*.kt' | grep -v '/ui/' | grep -v 'Activity.kt$' | grep -v 'Screen.kt$')
java -cp "$K/kotlin-compiler.jar:$SP/khome/lib/annotations-13.0.jar" \
  org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -kotlin-home "$SP/khome" \
  -classpath "$K/android-all.jar:$K/kotlin-stdlib.jar:$K/coroutines.jar:$K/okhttp.jar:$K/okio.jar:$K/serialization-core.jar:$K/serialization-json.jar:$SP/aar/classes.jar" \
  -d /tmp/gc-out -nowarn $FILES 2>&1 | grep "error:"
