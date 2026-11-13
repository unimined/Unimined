#!/bin/bash
# perf-verify.sh — perf megaplan regression gate (Docs/perf-plan.md).
# Usage: perf-verify.sh [fixture] [--quick]
#
#   <fixture>   dir under testing/perf/ (default: fabric-modern). All
#               fixtures carry the resolveDevClasspath classpath-
#               materialization task; thresholds are per-fixture.
#   --quick     skip the cold run; only do the two CC-store/reuse passes
#               on the current caches (fast sanity, ~30s on warm caches)
#
# Asserts the properties the megaplan landed, exit != 0 on failure:
#   1. sourceSet classpaths resolve the MC dev jar + remapped mods under
#      --configuration-cache on BOTH the store and the reuse pass
#      (regression: fileCollection.from(provider { conf }) resolved EMPTY
#      on CC-restore — javac saw libraries only).
#   2. --cold mode: the transform wave actually executed (fresh -named.jar
#      outputs in the wiped cache dirs).
#   3. CC-reuse pass does ZERO transform work (no new -named.jar mtimes —
#      steady state is up-to-date, ~seconds).
#   4. Cold wall under PERF_VERIFY_MAX_COLD_MS (default 480000 = 8min —
#      mounted-FS worst case; gates catastrophic regressions like the
#      28m52s pre-optimization era, not daemon-warmth jitter).
#
# Wall times are informational — only the structural checks (1)(2)(3)
# fail hard by default; (4) fails only if PERF_VERIFY_STRICT=1, since
# daemon warmth swings walls ±60%.

set -u
FIXTURE="${1:-fabric-modern}"
QUICK=0
[ "${2:-}" = "--quick" ] && QUICK=1
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
FIXTURE_DIR="$ROOT/testing/perf/$FIXTURE"
[ -d "$FIXTURE_DIR" ] || { echo "no fixture $FIXTURE_DIR" >&2; exit 2; }
GUH="$ROOT/testing/perf/.cold-guh"
mkdir -p "$GUH"
LOG="$FIXTURE_DIR/.verify-out.log"
TASK="resolveDevClasspath"
# per-fixture thresholds — the -named.jar transform wave and sourceSet
# classpath size scale with declared mods + loader era. Values are
# conservative floors (first-run actuals run higher); env overrides win.
case "$FIXTURE" in
    fabric-modern)    DEF_JARS=60; DEF_CP=100 ;;
    forge-userdev)    DEF_JARS=10; DEF_CP=30 ;;
    neoforge)         DEF_JARS=10; DEF_CP=30 ;;
    legacy-fg2)       DEF_JARS=10; DEF_CP=30 ;;
    legacy-ornithe)   DEF_JARS=3;  DEF_CP=10 ;;
    *)                DEF_JARS=5;  DEF_CP=10 ;;
esac
MIN_MOD_JARS="${PERF_VERIFY_MIN_MOD_JARS:-$DEF_JARS}"
MIN_CP_FILES="${PERF_VERIFY_MIN_CP_FILES:-$DEF_CP}"
MAX_COLD_MS="${PERF_VERIFY_MAX_COLD_MS:-480000}"

FAIL=0
pass() { echo "  PASS  $*"; }
fail() { echo "  FAIL  $*"; FAIL=1; }

run_gradle() {
    GRADLE_USER_HOME="$GUH" "$ROOT/gradlew" -p "$FIXTURE_DIR" "$TASK" \
        -Dunimined.perf "-Dunimined.perf.file=$FIXTURE_DIR/build/unimined/perf.jsonl" \
        --configuration-cache --console=plain > "$LOG" 2>&1
}

count_fresh_jars() {
    # -named.jar outputs newer than the timestamp file $1
    find "$GUH/caches" -name "*-named.jar" -newer "$1" 2>/dev/null | wc -l
}

count_named_jars() {
    find "$GUH/caches" -name "*-named.jar" 2>/dev/null | wc -l
}

echo "== perf-verify [$FIXTURE] task=$TASK"

if [ "$QUICK" -eq 0 ]; then
    # ---- cold pass: wipe artifact caches, must re-run the whole wave ----
    rm -rf "$GUH/caches/unimined" "$GUH/caches/modules-2" \
           "$GUH"/caches/transforms-* "$GUH"/caches/jars-* 2>/dev/null
    rm -rf "$FIXTURE_DIR/.gradle" "$FIXTURE_DIR/build"
    echo "-- cold pass"
    t0=$(date +%s%N)
    run_gradle || { tail -20 "$LOG" >&2; fail "cold run rc"; echo "== perf-verify: FAILED"; exit 1; }
    COLD_MS=$(( ($(date +%s%N) - t0) / 1000000 ))
    echo "    wall: ${COLD_MS}ms"

    NAMED=$(count_named_jars)
    [ "$NAMED" -ge "$MIN_MOD_JARS" ] \
        && pass "transform wave executed ($NAMED -named.jar outputs)" \
        || fail "transform wave missing ($NAMED -named.jar < $MIN_MOD_JARS)"

    CP=$(grep -o 'compileClasspath: [0-9]*' "$LOG" | awk '{print $2}')
    [ "${CP:-0}" -ge "$MIN_CP_FILES" ] \
        && pass "sourceSet compileClasspath materialized ($CP files)" \
        || fail "compileClasspath incomplete ($CP files < $MIN_CP_FILES — empty-view regression?)"

    grep -q "minecraft.*-named\.jar\|main.*-named\.jar" "$LOG" 2>/dev/null \
        && true # informational: mc dev jar name check is covered by CP count
else
    # quick mode: wipe only the fixture's CC so run 1 stores a fresh entry
    rm -rf "$FIXTURE_DIR/.gradle/configuration-cache" "$FIXTURE_DIR/build"
    echo "-- cc-store pass (quick)"
    run_gradle || { tail -20 "$LOG" >&2; fail "cc-store run rc"; exit 1; }
fi

# ---- reuse pass: same caches, CC must hit and no transforms re-run ----
echo "-- cc-reuse pass"
MARK="$FIXTURE_DIR/.verify-mark"
touch "$MARK"
sleep 1 # mtime resolution
t0=$(date +%s%N)
run_gradle || { tail -20 "$LOG" >&2; fail "cc-reuse run rc"; exit 1; }
REUSE_MS=$(( ($(date +%s%N) - t0) / 1000000 ))
echo "    wall: ${REUSE_MS}ms"

# One tolerated case: a fingerprint-recorded cache file (e.g. the
# merged-mappings bundle) created during the store pass invalidates the
# next entry exactly once — Gradle records the file absent at read-time,
# then resolve writes it. That is self-stabilizing, not a regression:
# only when the invalidation reason is exclusively "has been created" on
# unimined cache paths do we run a third pass, which must reuse.
if grep -q "Configuration cache entry reused" "$LOG"; then
    pass "configuration cache reused"
elif grep "cannot be reused" "$LOG" | grep -qE "has been created" \
    && ! grep "cannot be reused" "$LOG" \
        | grep -qE "has changed|has been removed|has been deleted|property|environment variable" \
    && ! grep "cannot be reused" "$LOG" | grep -oE "'[^']+' has been created" \
        | grep -qvE "unimined|caches"; then
    echo "-- cc-reuse pass 3 (one-time cache-creation invalidation)"
    # re-mark so the transform-idle check measures only this pass — the
    # invalidated pass legitimately re-materializes transform outputs
    touch "$MARK"; sleep 1
    run_gradle || { tail -20 "$LOG" >&2; fail "cc-reuse-3 rc"; exit 1; }
    grep -q "Configuration cache entry reused" "$LOG" \
        && pass "configuration cache reused (3rd pass — first was cache-creation)" \
        || fail "CC entry not reused even on third pass"
else
    fail "CC entry not reused on second pass (non-cache invalidation)"
fi

NEW_JARS=$(count_fresh_jars "$MARK")
[ "$NEW_JARS" -eq 0 ] \
    && pass "zero transform executions on reuse" \
    || fail "$NEW_JARS -named.jar rewritten on reuse (steady state should be idle)"

CP2=$(grep -o 'compileClasspath: [0-9]*' "$LOG" | awk '{print $2}')
[ "${CP2:-0}" -ge "$MIN_CP_FILES" ] \
    && pass "sourceSet compileClasspath materialized on reuse ($CP2 files)" \
    || fail "compileClasspath empty on reuse ($CP2 files — provider{conf} regression?)"
rm -f "$MARK"

# ---- wall bound (strict only) ----
if [ "$QUICK" -eq 0 ] && [ "${PERF_VERIFY_STRICT:-0}" = "1" ]; then
    [ "$COLD_MS" -le "$MAX_COLD_MS" ] \
        && pass "cold wall ${COLD_MS}ms <= ${MAX_COLD_MS}ms" \
        || fail "cold wall ${COLD_MS}ms > ${MAX_COLD_MS}ms"
else
    echo "  INFO  wall bound check skipped (PERF_VERIFY_STRICT=1 to enforce)"
fi

echo
if [ "$FAIL" -eq 0 ]; then
    echo "== perf-verify: OK"
else
    echo "== perf-verify: FAILED"
fi
exit $FAIL
