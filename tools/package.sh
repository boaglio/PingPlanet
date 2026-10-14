#!/usr/bin/env bash
# Builds PingPlanet packages with Java included, for the machine it runs on, with jpackage (JDK 21+).
#
# Usage: tools/package.sh <pingplanet.jar> <version> <output folder>
#
#   Linux:   PingPlanet-<version>-linux-x64.tar.gz  and  pingplanet_<version>_amd64.deb
#   Windows: PingPlanet-<version>-windows-x64.zip   (run from Git Bash; the release workflow does)
#
# The bundled Java runtime holds only the modules PingPlanet uses, so nobody needs Java installed.
set -euo pipefail

die() { echo "package: $*" >&2; exit 1; }

[ $# -eq 3 ] || die "usage: tools/package.sh <pingplanet.jar> <version> <output folder>"
jar=$1 version=$2 out=$3
[ -f "$jar" ] || die "no jar at $jar"
command -v jpackage >/dev/null || die "jpackage not found: use a JDK 21 or newer"

root=$(cd "$(dirname "$0")/.." && pwd)
icons=$root/src/packaging
work=$(mktemp -d "${TMPDIR:-/tmp}/pingplanet-package.XXXXXX")
trap 'rm -rf "$work"' EXIT
mkdir -p "$work/input" "$out"
cp "$jar" "$work/input/pingplanet.jar"
out=$(cd "$out" && pwd)
if command -v cygpath >/dev/null; then # Git Bash: jpackage is a Windows program and needs C:/ paths
    root=$(cygpath -m "$root") icons=$(cygpath -m "$icons") work=$(cygpath -m "$work") out=$(cygpath -m "$out")
fi

# jdeps lists java.base, java.desktop, java.net.http and java.prefs. The others: FlatLaf (jdk.unsupported),
# screen readers (jdk.accessibility), console code pages on Windows (jdk.charsets), TLS on Java 21 (jdk.crypto.ec).
modules=java.base,java.desktop,java.net.http,java.prefs,jdk.unsupported,jdk.accessibility,jdk.charsets,jdk.crypto.ec

common=(
    --name PingPlanet
    --app-version "$version"
    --vendor PingPlanet
    --description "Speed test for your local network and for servers around the world"
    --copyright "GNU General Public License v3.0"
    --input "$work/input"
    --main-jar pingplanet.jar
    --main-class pingplanet.PingPlanet
    --add-modules "$modules"
    --jlink-options "--strip-debug --no-man-pages --no-header-files --compress=zip-6"
    --java-options "--enable-native-access=ALL-UNNAMED"
)

case "$(uname -s)" in
    Linux)
        jpackage "${common[@]}" --type app-image --icon "$icons/pingplanet.png" --dest "$work/image"
        tar -C "$work/image" -czf "$out/PingPlanet-$version-linux-x64.tar.gz" PingPlanet
        jpackage "${common[@]}" --type deb --icon "$icons/pingplanet.png" --dest "$work/deb" \
            --license-file "$root/LICENSE" \
            --about-url https://github.com/boaglio/PingPlanet \
            --linux-package-name pingplanet \
            --linux-app-category net \
            --linux-menu-group "Network;" \
            --linux-shortcut
        mv "$work"/deb/*.deb "$out/pingplanet_${version}_amd64.deb"
        ;;
    MINGW* | MSYS* | CYGWIN*)
        # PingPlanet.exe opens the window without a console; pingplanet-cli.exe is for the terminal.
        printf 'win-console=true\nicon=%s\n' "$icons/pingplanet.ico" > "$work/cli.properties"
        jpackage "${common[@]}" --type app-image --icon "$icons/pingplanet.ico" --dest "$work/image" \
            --add-launcher "pingplanet-cli=$work/cli.properties"
        (cd "$work/image" && 7z a -tzip -bso0 "$out/PingPlanet-$version-windows-x64.zip" PingPlanet)
        ;;
    *)
        die "unsupported system $(uname -s): packages are built on Linux and Windows"
        ;;
esac

ls -l "$out"
