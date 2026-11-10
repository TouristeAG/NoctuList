#!/usr/bin/env bash
# Runs firebase-tools with a Java 21+ runtime (the emulators refuse older JDKs).
# Uses $JAVA_HOME when it is recent enough, otherwise Android Studio's bundled JBR.
set -euo pipefail
cd "$(dirname "$0")"
java_major() { "$1/bin/java" -version 2>&1 | awk -F'"' '/version/ {split($2, v, "."); print v[1]; exit}'; }
pick_java() {
  for candidate in "${JAVA_HOME:-}" "/Applications/Android Studio.app/Contents/jbr/Contents/Home" \
      "$HOME/Applications/Android Studio.app/Contents/jbr/Contents/Home" \
      $(/usr/libexec/java_home -v 21+ 2>/dev/null || true); do
    if [ -n "$candidate" ] && [ -x "$candidate/bin/java" ] && [ "$(java_major "$candidate")" -ge 21 ]; then
      echo "$candidate"; return 0
    fi
  done
  echo "No Java 21+ found. Install a JDK 21 or set JAVA_HOME." >&2; return 1
}
export JAVA_HOME="$(pick_java)"
export PATH="$JAVA_HOME/bin:$PATH"
node sync-config.mjs
exec firebase "$@" --project demo-noctulist
