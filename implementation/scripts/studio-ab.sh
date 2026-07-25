#!/usr/bin/env sh
# SCAFFOLDING for rf2-lnecd — deleted before the PR.
#
# The three-way, run honestly. The tuned interpreter is a change to SHARED
# code, so `current interpreted` and `tuned interpreted` cannot be two arms of
# one page. They are two builds, and two builds run one after the other on a
# loaded workstation is exactly the comparison this repo has been burned by.
#
# So the runs ALTERNATE: A B A B A B, rebuilding between each, with the
# `git stash`-free swap below (a stash is repo-global and would contaminate
# every other worktree in flight). Load drift then lands on both arms.
#
#   sh scripts/studio-ab.sh <rounds>

set -e
cd "$(dirname "$0")/.."
ROUNDS="${1:-3}"
SRC=freehand/src/re_frame/freehand/conversion.cljc
BASE=../out/studio-ab

mkdir -p out
cp "$SRC" out/conversion.tuned.cljc
git show HEAD~1:implementation/freehand/src/re_frame/freehand/conversion.cljc > out/conversion.current.cljc

i=1
while [ "$i" -le "$ROUNDS" ]; do
  for arm in current tuned; do
    cp "out/conversion.$arm.cljc" "$SRC"
    npx shadow-cljs release studio-probe >/dev/null 2>&1
    echo "===== round $i / $arm ====="
    node scripts/studio-probe.cjs --build studio-probe 2>&1 | sed -n '/MOUNT (ms)/,/^$/p'
  done
  i=$((i + 1))
done

cp out/conversion.tuned.cljc "$SRC"
echo "restored tuned source"
