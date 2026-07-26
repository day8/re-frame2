#!/usr/bin/env sh
# SCAFFOLDING for rf2-lnecd — deleted before the PR.
#
# The three-way, run honestly. The tuned interpreter is a change to SHARED
# code, so `current interpreted` and `tuned interpreted` cannot be two arms of
# one page: they are two builds. Two builds run one after the other on a
# loaded workstation is exactly the comparison this repo has been burned by —
# a first pass here saw the NO-SUBSTRATE floor arm, which the change cannot
# touch, move 4.7ms -> 6.4ms between rounds.
#
# So two things. The runs ALTERNATE (current, tuned, current, tuned…) with a
# rebuild between each, so load drift lands on both arms; and every arm is
# read as a RATIO to the floor measured in ITS OWN run, which is the same work
# in every run and therefore an in-run calibrator.
#
# The swap is a file copy, not `git stash`: a stash is repo-global and would
# contaminate every other worktree in flight.
#
#   sh scripts/studio-ab.sh <rounds>

set -e
cd "$(dirname "$0")/.."
ROUNDS="${1:-4}"
SRC=freehand/src/re_frame/freehand/rules.cljc

mkdir -p out
cp "$SRC" out/rules.tuned.cljc
git show HEAD~2:implementation/freehand/src/re_frame/freehand/rules.cljc > out/rules.current.cljc

i=1
while [ "$i" -le "$ROUNDS" ]; do
  for arm in current tuned; do
    cp "out/rules.$arm.cljc" "$SRC"
    npx shadow-cljs release studio-probe >/dev/null 2>&1
    node scripts/studio-probe.cjs --build studio-probe > "out/ab-$i-$arm.txt" 2>&1
    echo "round $i / $arm done"
  done
  i=$((i + 1))
done

cp out/rules.tuned.cljc "$SRC"
echo "restored tuned source"
