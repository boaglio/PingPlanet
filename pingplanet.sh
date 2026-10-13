#!/usr/bin/env bash
# PingPlanet launcher for Linux and macOS.
# Builds the jar with Maven on the first run, then runs it.
#
# Usage: ./pingplanet.sh [--build] [pingplanet arguments]   (./pingplanet.sh --help lists them)
#   --build   rebuild the jar before starting (after pulling new code, for example)

set -euo pipefail

DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
MIN_JAVA=21

die() { echo "pingplanet: $*" >&2; exit 1; }

build=false
args=()
for arg in "$@"; do
    case "$arg" in
        --build) build=true ;;
        *) args+=("$arg") ;;
    esac
done

# Prefer JAVA_HOME, fall back to java on the PATH.
if [[ -n "${JAVA_HOME:-}" && -x "$JAVA_HOME/bin/java" ]]; then
    JAVA="$JAVA_HOME/bin/java"
elif command -v java >/dev/null 2>&1; then
    JAVA="java"
else
    die "Java not found. Install Java $MIN_JAVA or newer (e.g. https://adoptium.net) or set JAVA_HOME."
fi

# "25.0.4" -> 25, "1.8.0_402" -> 8
version="$("$JAVA" -version 2>&1 | awk -F'"' '/version/ {print $2; exit}')"
major="${version%%.*}"
[[ "$major" == "1" ]] && major="$(echo "$version" | cut -d. -f2)"
[[ "$major" =~ ^[0-9]+$ ]] || die "could not read the Java version from: $("$JAVA" -version 2>&1 | head -1)"
(( major >= MIN_JAVA )) || die "Java $MIN_JAVA or newer is required, but found Java $version ($JAVA)."

find_jar() {
    local jars=("$DIR"/target/pingplanet-*.jar)
    [[ -e "${jars[0]}" ]] && echo "${jars[0]}"
}

JAR="$(find_jar || true)"
if $build || [[ -z "$JAR" ]]; then
    command -v mvn >/dev/null 2>&1 || die "Maven (mvn) is needed to build PingPlanet. Install it from https://maven.apache.org"
    echo "Building PingPlanet..."
    mvn -q -B -f "$DIR/pom.xml" package || die "build failed."
    JAR="$(find_jar)" || die "build finished but no jar was found in $DIR/target."
fi

exec "$JAVA" -jar "$JAR" "${args[@]+"${args[@]}"}"
