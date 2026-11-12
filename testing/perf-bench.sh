#!/bin/bash
# perf-bench.sh — perf megaplan benchmark harness (Docs/perf-plan.md).
# Usage: perf-bench.sh <fixture> [--cold|--warm-cc|--warm] [--task <task>] [--runs N]
#
#   <fixture>   dir under testing/perf/ (e.g. fabric-modern)
#   --cold      wipe GRADLE_USER_HOME artifact caches (unimined, transforms,
#               modules-2) + project .gradle + build — distribution kept, so
#               this is a true cold-artifact-cache run, not a re-bootstrap
#   --warm-cc   keep all caches; wipe only the project's configuration-cache
#               + build — exercises config + transform-scheduling on warm disk
#   --warm      plain rerun (UP-TO-DATE/CC-hit semantics)
#   --task      gradle task (default: classes)
#   --runs      N samples (default: 3; cold defaults to 1)
#
# Prints per-run wall time, median, and a phase table parsed from
# build/unimined/perf.jsonl (written because -Punimined.perf is passed).
# Cold runs use GRADLE_USER_HOME=testing/perf/.cold-guh — a persistent dir so
# the Gradle distribution survives between samples while artifact caches are
# wiped each run.

set -u
FIXTURE="$1"; shift
MODE="--cold"; TASK="classes"; RUNS=""
while [ $# -gt 0 ]; do
    case "$1" in
        --cold|--warm-cc|--warm) MODE="$1" ;;
        --task) TASK="$2"; shift ;;
        --task=*) TASK="${1#--task=}" ;;
        --runs) RUNS="$2"; shift ;;
        --runs=*) RUNS="${1#--runs=}" ;;
        *) echo "unknown arg: $1" >&2; exit 2 ;;
    esac
    shift
done
RUNS="${RUNS:-$([ "$MODE" = "--cold" ] && echo 1 || echo 3)}"

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
FIXTURE_DIR="$ROOT/testing/perf/$FIXTURE"
[ -d "$FIXTURE_DIR" ] || { echo "no fixture $FIXTURE_DIR" >&2; exit 2; }

GUH=""
if [ "$MODE" = "--cold" ]; then
    GUH="$ROOT/testing/perf/.cold-guh"
    mkdir -p "$GUH"
fi

PERF_FILE="$FIXTURE_DIR/build/unimined/perf.jsonl"
WALLS=()

wipe() {
    case "$MODE" in
        --cold)
            rm -rf "$GUH/caches/unimined" "$GUH/caches/modules-2" \
                   "$GUH"/caches/transforms-* "$GUH/caches/jars-"* 2>/dev/null
            rm -rf "$FIXTURE_DIR/.gradle" "$FIXTURE_DIR/build" ;;
        --warm-cc)
            rm -rf "$FIXTURE_DIR/.gradle/configuration-cache" \
                   "$FIXTURE_DIR/build" ;;
        --warm)
            rm -f "$PERF_FILE" ;;
    esac
}

for i in $(seq 1 "$RUNS"); do
    wipe
    echo "=== run $i/$RUNS [$MODE] $TASK"
    t0=$(date +%s%N)
    # -D (not -P): apply() is skipped on configuration-cache hits, so a
    # Gradle property would never reach Perf.resolve on warm runs —
    # daemon system properties are always visible
    if [ -n "$GUH" ]; then
        GRADLE_USER_HOME="$GUH" "$ROOT/gradlew" -p "$FIXTURE_DIR" "$TASK" \
            -Dunimined.perf "-Dunimined.perf.file=$PERF_FILE" \
            --configuration-cache --console=plain --quiet \
            > "$FIXTURE_DIR/.bench-out.log" 2>&1
    else
        "$ROOT/gradlew" -p "$FIXTURE_DIR" "$TASK" \
            -Dunimined.perf "-Dunimined.perf.file=$PERF_FILE" \
            --configuration-cache --console=plain --quiet \
            > "$FIXTURE_DIR/.bench-out.log" 2>&1
    fi
    rc=$?
    t1=$(date +%s%N)
    ms=$(( (t1 - t0) / 1000000 ))
    if [ $rc -ne 0 ]; then
        echo "run $i FAILED (rc=$rc) — tail of $FIXTURE_DIR/.bench-out.log:"
        tail -25 "$FIXTURE_DIR/.bench-out.log" >&2
        exit $rc
    fi
    WALLS+=("$ms")
    echo "    wall: ${ms}ms"
    if [ -f "$PERF_FILE" ]; then
        mv "$PERF_FILE" "$FIXTURE_DIR/.perf-$i.jsonl"
    else
        : > "$FIXTURE_DIR/.perf-$i.jsonl"
    fi
done

# median wall time
med=$(printf '%s\n' "${WALLS[@]}" | sort -n | awk '{a[NR]=$1} END {print a[int((NR+1)/2)]}')

echo
echo "== wall times: ${WALLS[*]} | median: ${med}ms"

python3 - "$FIXTURE_DIR" "$RUNS" <<'PY'
import json, sys, glob, collections
fix, runs = sys.argv[1], int(sys.argv[2])
agg = collections.defaultdict(list)
for path in sorted(glob.glob(f"{fix}/.perf-*.jsonl")):
    per_run = collections.defaultdict(lambda: [0, 0])
    for line in open(path):
        try: e = json.loads(line)
        except Exception: continue
        ev, ms = e.get("ev"), e.get("ms")
        if ms is None: continue
        per_run[ev][0] += ms
        per_run[ev][1] += 1
    for ev, (tot, n) in per_run.items():
        agg[ev].append((tot, n))
print(f"{'phase':22} {'median ms':>10} {'count':>6}")
for ev, samples in sorted(agg.items(), key=lambda kv: -max(s[0] for s in kv[1])):
    tots = sorted(s[0] for s in samples)
    cnt = sorted(s[1] for s in samples)
    med = tots[len(tots)//2]
    print(f"{ev:22} {med:>10} {cnt[len(cnt)//2]:>6}")
PY

echo
echo "perf logs: $FIXTURE_DIR/.perf-*.jsonl ; gradle out: $FIXTURE_DIR/.bench-out.log"
