#!/bin/sh
# TapMame: fetch the prebuilt MAME core libraries from the upstream
# MAME4droid-Current release and place them into jniLibs.
#
# The 348MB libMAME4droid.so is deliberately NOT committed to this repo
# (GitHub rejects files >100MB). It is the unmodified upstream GPL build;
# its corresponding source is the upstream repo at the matching tag.
# TapMame's own changes live entirely in the Java layer.
set -e
TAG="v1.37.6"
APK="MAME4droid.2026-1.37.6-release.apk"
URL="https://github.com/seleuco/MAME4droid-Current/releases/download/$TAG/$APK"
DEST="android-MAME4droid/app/src/main/jniLibs/arm64-v8a"

cd "$(dirname "$0")"
mkdir -p "$DEST"
TMP=$(mktemp -d)
echo "Downloading upstream release $TAG ..."
curl -sL -o "$TMP/m4d.apk" "$URL"
unzip -o -q "$TMP/m4d.apk" "lib/arm64-v8a/libMAME4droid.so" "lib/arm64-v8a/libmame4droid-jni.so" -d "$TMP"
cp "$TMP/lib/arm64-v8a/libMAME4droid.so" "$TMP/lib/arm64-v8a/libmame4droid-jni.so" "$DEST/"
rm -rf "$TMP"
echo "Core libraries installed into $DEST"
