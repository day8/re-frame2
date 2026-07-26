#!/usr/bin/env sh
# SCAFFOLDING for rf2-xu6rx — deleted before the PR.
#
# The A/B, run the way rf2-lnecd learned it has to be run on this box. A
# change to shared substrate code cannot be two arms of one page, so it is two
# builds; and two builds run back to back on a loaded workstation is exactly
# the comparison this repo has been burned by. The lnecd report measured the
# NO-SUBSTRATE floor arm — an arm no change here can touch — drifting 37%
# across rounds.
#
# So: the runs ALTERNATE (base, tuned, base, tuned...) with a rebuild between
# each, and every arm is read as a RATIO to the floor measured in ITS OWN run.
#
# The swap is a file copy, never `git stash` — a stash is repo-global and
# would contaminate every other worktree in flight.
#
#   sh scripts/studio-ab2.sh <rounds> <base-ref>
#
# The files to swap are listed one per line, relative to implementation/, in
# out/ab/files.txt.

set -e
cd "$(dirname "$0")/.."
ROUNDS="${1:-4}"
BASEREF="${2:-origin/main}"
LIST=out/ab/files.txt

mkdir -p out/ab/base out/ab/tuned

# Snapshot both sides once, before any build, so an interrupted run cannot
# leave the tree holding half of one arm.
while read -r f; do
  [ -n "$f" ] || continue
  key=$(echo "$f" | tr '/' '_')
  cp "$f" "out/ab/tuned/$key"
  git show "$BASEREF:implementation/$f" > "out/ab/base/$key"
done < "$LIST"

swap() {
  while read -r f; do
    [ -n "$f" ] || continue
    key=$(echo "$f" | tr '/' '_')
    cp "out/ab/$1/$key" "$f"
  done < "$LIST"
}

i=1
while [ "$i" -le "$ROUNDS" ]; do
  for arm in base tuned; do
    swap "$arm"
    sh scripts/studio-build.sh probe > "out/ab/build-$i-$arm.log" 2>&1
    node scripts/studio-probe.cjs --build studio-probe > "out/ab/run-$i-$arm.txt" 2>&1
    cp out/studio-probe-raw.json "out/ab/raw-$i-$arm.json"
    echo "round $i / $arm done"
  done
  i=$((i + 1))
done

swap tuned
echo "restored tuned sources"
