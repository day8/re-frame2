#!/usr/bin/env bash
# The shell reader for scripts/jvm-lanes.txt, the one list of JVM lanes.
#
#   scripts/jvm-lanes.sh rows                  every row as dir, aliases, slow,
#                                              predicate, ci (TAB-separated)
#   scripts/jvm-lanes.sh dirs implementation   the implementation/ rows' dirs
#   scripts/jvm-lanes.sh dirs tools            every other row's dir
#   scripts/jvm-lanes.sh dirs slow             the `slow` rows' dirs
#   scripts/jvm-lanes.sh json slow             the `slow` dirs as a JSON array
#
# All of them print in row order. JVM_LANES_FILE names a different rows file.
#
# When it is sourced, the file only defines `jvm_lanes_rows FILE`. The
# classifier sources it rather than run it, because it classifies hundreds of
# paths per test run and a subprocess per call is the cost that matters there.
#
# A malformed row is an error naming the file and line, never a skipped row: a
# reader that drops a row drops a lane, and nothing downstream would notice.

jvm_lanes_rows() {
  local file="$1" line n=0 dir aliases slow predicate ci extra
  if [ ! -f "$file" ]; then
    printf 'jvm-lanes: %s not found\n' "$file" >&2
    return 2
  fi
  while IFS= read -r line || [ -n "$line" ]; do
    n=$((n + 1))
    line="${line%%#*}"
    line="${line//$'\r'/}"
    dir='' aliases='' slow='' predicate='' ci='' extra=''
    read -r dir aliases slow predicate ci extra <<< "$line" || true
    [ -n "$dir" ] || continue
    if [ -z "$ci" ] || [ -n "$extra" ]; then
      printf '%s:%d: a row is five fields (dir aliases slow predicate ci): %s\n' \
        "$file" "$n" "$line" >&2
      return 2
    fi
    case "$slow" in
      slow|-) ;;
      *)
        printf '%s:%d: the slow field is "slow" or "-", not "%s"\n' "$file" "$n" "$slow" >&2
        return 2
        ;;
    esac
    printf '%s\t%s\t%s\t%s\t%s\n' "$dir" "$aliases" "$slow" "$predicate" "$ci"
  done < "$file"
}

if [ "${BASH_SOURCE[0]}" = "$0" ]; then
  set -euo pipefail
  root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
  file="${JVM_LANES_FILE:-$root/scripts/jvm-lanes.txt}"
  rows="$(jvm_lanes_rows "$file")"

  usage() {
    sed -n '4,9p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//' >&2
    exit 2
  }

  select_dirs() {
    local want="$1" dir aliases slow predicate ci
    while IFS=$'\t' read -r dir aliases slow predicate ci; do
      [ -n "$dir" ] || continue
      case "$want" in
        implementation) case "$dir" in implementation/*) printf '%s\n' "$dir" ;; esac ;;
        tools) case "$dir" in implementation/*) ;; *) printf '%s\n' "$dir" ;; esac ;;
        slow) [ "$slow" = slow ] && printf '%s\n' "$dir" ;;
      esac
    done <<< "$rows"
    return 0
  }

  case "${1:-} ${2:-}" in
    'rows ') printf '%s\n' "$rows" ;;
    'dirs implementation'|'dirs tools'|'dirs slow') select_dirs "$2" ;;
    'json slow')
      json='' sep=''
      while IFS= read -r dir; do
        [ -n "$dir" ] || continue
        json="$json$sep\"$dir\""
        sep=','
      done <<< "$(select_dirs slow)"
      printf '[%s]\n' "$json"
      ;;
    *) usage ;;
  esac
fi
