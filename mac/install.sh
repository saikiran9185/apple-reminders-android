#!/bin/zsh
set -e
cd "$(dirname "$0")"
command -v xcodegen >/dev/null || { echo "brew install xcodegen"; exit 1; }
echo "▸ Generating…"; xcodegen generate >/dev/null
echo "▸ Building…"
xcodebuild -project RemindersBridge.xcodeproj -scheme RemindersBridge \
  -configuration Release -derivedDataPath .build build >/dev/null
APP=".build/Build/Products/Release/Reminders Bridge.app"
[ -d "$APP" ] || { echo "no app bundle"; exit 1; }
codesign --force --sign - --entitlements Resources/App.entitlements "$APP"
osascript -e 'quit app "Reminders Bridge"' 2>/dev/null || true
sleep 1
rm -rf "/Applications/Reminders Bridge.app"
cp -R "$APP" "/Applications/Reminders Bridge.app"
echo "✓ Installed"; open "/Applications/Reminders Bridge.app"
