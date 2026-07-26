#!/usr/bin/env sh
# SCAFFOLDING for rf2-xu6rx — deleted before the PR.
#
# The SUBSTRATE SHARE, alternated. A CPU profile's bucket shares are a
# structural reading rather than a clock, but they are not therefore free of
# drift: the same unchanged code measured 36.71% and 38.04% substrate in two
# captures minutes apart, because `(program)`, `(idle)` and the collector move
# and they are in the denominator. So the headline number is taken the same way
# the clock is — alternating arms, rebuilt between each, reported as a range.
#
#   sh scripts/studio-profile-ab.sh <rounds> <base-ref>

set -e
cd "$(dirname "$0")/.."
ROUNDS="${1:-2}"
BASEREF="${2:-origin/main}"
LIST=out/ab/files.txt

mkdir -p out/ab/base out/ab/tuned out/ab/prof

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
    sh scripts/studio-build.sh named > "out/ab/prof/build-$i-$arm.log" 2>&1
    node scripts/studio-probe.cjs --build studio-probe-named --profile \
      --query "profile=w1-interpreted&n=150" > "out/ab/prof/prof-$i-$arm.txt" 2>&1
    cp out/studio-probe-named-profile.json "out/ab/prof/raw-$i-$arm.json"
    echo "profile round $i / $arm done"
  done
  i=$((i + 1))
done

swap tuned
echo "restored tuned sources"
