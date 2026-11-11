#!/bin/bash
# CROPCAST — portable build entry point.
# Requires: JDK 17, Android SDK (ANDROID_HOME), Gradle 9.6.0 via the wrapper.
# Usage: ./build.sh [gradle args...]   (e.g. ./build.sh :app:assembleDebug)
set -e
cd "$(dirname "$0")"
if [ -z "$JAVA_HOME" ]; then
  if [ -d /usr/lib/jvm/java-17-openjdk-amd64 ]; then
    export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64
  else
    echo "ERROR: set JAVA_HOME to a JDK 17 install." >&2
    exit 1
  fi
fi
if [ -z "$ANDROID_HOME" ]; then
  echo "ERROR: set ANDROID_HOME to your Android SDK location." >&2
  exit 1
fi
if [ -x ./gradlew ]; then
  exec ./gradlew --no-daemon "$@"
else
  exec gradle --no-daemon "$@"
fi
