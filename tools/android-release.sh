#!/usr/bin/env bash
# Builds the Android App Bundle for Google Play, signed with your key, and checks that your key really signed it.
#
# Usage: tools/android-release.sh [gradle options]
#
# The settings are in android/release.env, which git ignores: copy android/release.env.example and fill it in.
# Output: android/build/outputs/bundle/release/PingPlanet-release.aab
set -euo pipefail

die() { echo "android-release: $*" >&2; exit 1; }

root=$(cd "$(dirname "$0")/.." && pwd)
env_file=$root/android/release.env
[ -f "$env_file" ] || die "no $env_file: copy android/release.env.example and fill it in"

set -a
. "$env_file"
set +a

for name in ANDROID_HOME ANDROID_KEYSTORE_FILE ANDROID_KEYSTORE_PASSWORD ANDROID_KEY_ALIAS ANDROID_KEY_PASSWORD; do
    [ -n "${!name:-}" ] || die "$name is not set in $env_file"
done
[ -d "$ANDROID_HOME" ] || die "ANDROID_HOME is not a folder: $ANDROID_HOME"
case $ANDROID_KEYSTORE_FILE in
    /*) ;;
    *) ANDROID_KEYSTORE_FILE=$root/$ANDROID_KEYSTORE_FILE ;; # relative paths start at the repository
esac
[ -f "$ANDROID_KEYSTORE_FILE" ] || die "no keystore at $ANDROID_KEYSTORE_FILE"
command -v gradle >/dev/null || die "gradle not found: you need Gradle 8.14"

keytool=keytool
[ -z "${JAVA_HOME:-}" ] || keytool=$JAVA_HOME/bin/keytool
command -v "$keytool" >/dev/null || die "keytool not found: set JAVA_HOME in $env_file"

# The passwords stay in the environment, so they never appear in the list of running processes.
fingerprint() { sed -n 's/^[[:space:]]*SHA256: *//p' | head -n 1; }
key=$("$keytool" -J-Duser.language=en -list -v -keystore "$ANDROID_KEYSTORE_FILE" -alias "$ANDROID_KEY_ALIAS" \
    -storepass:env ANDROID_KEYSTORE_PASSWORD) \
    || die "cannot read key \"$ANDROID_KEY_ALIAS\": check the alias and ANDROID_KEYSTORE_PASSWORD"
expected=$(fingerprint <<<"$key")
[ -n "$expected" ] || die "cannot read key \"$ANDROID_KEY_ALIAS\": check the alias and ANDROID_KEYSTORE_PASSWORD"

# Google Play refuses anything weaker.
strength=$(sed -n 's/^Subject Public Key Algorithm: //p' <<<"$key" | head -n 1)
case $strength in
    *RSA*) bits=${strength%%-bit*} ;;
    *) die "Google Play needs an RSA key, this one is: ${strength:-unknown}" ;;
esac
[ "$bits" -ge 2048 ] || die "Google Play needs an RSA key of 2048 bits or more, this one is: $strength"

bundle=$root/android/build/outputs/bundle/release/PingPlanet-release.aab
rm -f "$bundle" # never leave an older bundle behind if this build fails
(cd "$root/android" && gradle --no-daemon "$@" bundleRelease)
[ -f "$bundle" ] || die "the build did not produce $bundle"

actual=$("$keytool" -J-Duser.language=en -printcert -jarfile "$bundle" | fingerprint)
[ "$actual" = "$expected" ] || die "the bundle is not signed with your key: expected $expected, found ${actual:-none}"

version=$(sed -n 's/.*<version>\([0-9]*\.[0-9]*\.[0-9]*\)<\/version>.*/\1/p' "$root/pom.xml" | head -n 1)
echo
echo "Version: $version"
echo "Signed with: $expected"
echo "Upload this to Google Play: $bundle"
