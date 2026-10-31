#!/bin/sh
# Builds dist/linux/Lounge-x86_64.AppImage from lounge/ and web/. Run it on Linux:
#   sh packaging/linux/build-appimage.sh
# or from Windows or macOS with Docker, in the project folder:
#   docker run --rm -v "$PWD":/src -w /src debian:stable-slim sh packaging/linux/build-appimage.sh
set -eu
cd "$(dirname "$0")/../.."
if ! command -v wget >/dev/null 2>&1 && command -v apt-get >/dev/null 2>&1; then
    apt-get update -qq && apt-get install -y -qq wget ca-certificates file >/dev/null
fi
tool=/tmp/appimagetool-x86_64.AppImage
[ -x "$tool" ] || { wget -q -O "$tool" https://github.com/AppImage/appimagetool/releases/download/continuous/appimagetool-x86_64.AppImage && chmod +x "$tool"; }

app=$(mktemp -d)/Lounge.AppDir
mkdir -p "$app/usr/share/lounge/lounge"
cp lounge/server.py lounge/apps.py "$app/usr/share/lounge/lounge/"
cp -r web "$app/usr/share/lounge/"
cp packaging/linux/AppRun "$app/AppRun"
chmod +x "$app/AppRun"
cp packaging/linux/lounge.desktop packaging/linux/lounge.png "$app/"
mkdir -p dist/linux
# Runs appimagetool without FUSE, which containers do not have
APPIMAGE_EXTRACT_AND_RUN=1 ARCH=x86_64 "$tool" --no-appstream "$app" dist/linux/Lounge-x86_64.AppImage
