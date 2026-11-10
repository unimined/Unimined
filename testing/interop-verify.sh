#!/bin/bash
# Cross-toolchain interop gate — P9 parity (issues #72/#105/#149).
# Asserts a loom-published intermediary jar and era SRG deps survive
# RemapDependencyTransform with their metadata intact.
#
# Usage: interop-verify.sh <fixture> <dep-name> [<dep-name>...]
#   dep-name = artifact base (e.g. modmenu-7.2.2,
#   fabric-transitive-access-wideners-v1-1.3.3+08b73de490,
#   fabric-language-kotlin-1.11.0+kotlin.2.0.0)
#
# Locates the dev-namespace remapped output under the Gradle transform
# caches — `<name>-<devns>.jar` (`-named` yarn, `-mcp` forge-era, …)
# — and checks per-artifact invariants:
#   *-refmap.json present          — mixin refmaps survive remap
#   accessWidener header 'named'   — AW files rewritten to dev ns
#   META-INF/jars/ entries         — nested jars preserved
# Every check that matches a trait the artifact actually ships is a
# verdict row; a dep with none of the traits is still checked for the
# remapped jar's existence (the baseline interop guarantee).
#
# Requires a prior `classes` run (transforms populate the caches).

set -u
FIXTURE="$1"; shift
[ -d "$FIXTURE" ] || { echo "FAIL: no fixture dir $FIXTURE"; exit 2; }
[ -d "$FIXTURE/build/classes" ] || {
    echo "FAIL: $FIXTURE has no build/classes — run 'classes' first"; exit 2; }

CACHE_GLOBS=( "$HOME"/.gradle/caches/*/transforms/*/transformed "$HOME"/.gradle/caches/transforms-*/*/transformed )

named_jar() {
    local base="$1" d
    for d in "${CACHE_GLOBS[@]}"; do
        [ -d "$d" ] || continue
        find "$d" -name "${base}-*.jar" -type f 2>/dev/null | head -1 | grep . && return 0
    done
    return 1
}

fails=0; checks=0
ok()  { checks=$((checks+1)); echo "  PASS $1"; }
bad() { checks=$((checks+1)); fails=$((fails+1)); echo "  FAIL $1"; }

for dep in "$@"; do
    echo "dep: $dep"
    JAR=$(named_jar "$dep") || { bad "no remapped $dep-<devns>.jar in transform caches"; continue; }
    ok "remapped jar exists ($(basename "$JAR"))"

    if unzip -l "$JAR" | grep -q 'refmap\.json'; then
        ok "refmap present: $(unzip -l "$JAR" | grep -o ' *[^ ]*refmap\.json' | head -1)"
    fi
    if unzip -l "$JAR" | grep -qi 'accesswidener'; then
        hdr=$(unzip -p "$JAR" '*.accesswidener' 2>/dev/null | head -1)
        case "$hdr" in
            accessWidener*named*) ok "access widener rewritten to dev ns ($hdr)" ;;
            *)                    bad "access widener not rewritten ($hdr)" ;;
        esac
    fi
    n=$(unzip -l "$JAR" | grep -c 'META-INF/jars/.*\.jar')
    [ "$n" -gt 0 ] && ok "nested jars preserved ($n)"
done

echo "verdict: $((checks-fails))/$checks checks passed"
[ $fails -eq 0 ] && echo VERIFIED || { echo "FAIL($fails)"; exit 1; }
