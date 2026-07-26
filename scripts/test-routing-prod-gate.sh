#!/usr/bin/env bash
#
# rf2-hnrwo — run `implementation/routing` under the REAL production gate.
#
# WHY THIS EXISTS.  `SECURITY.md` documents `-Dre-frame.debug=false` (and
# `RE_FRAME_DEBUG=false`) as the JVM/SSR production setting, and
# `re-frame.interop/debug-enabled?` reads it ONCE at namespace-load time.  Until
# rf2-f8x2i nothing in `.github/workflows` or `scripts/` had ever set it, and
# what that bead built — `scripts/test-core-prod-gate.sh` — covers
# `implementation/core` ONLY.  Every other artefact, this one included, was
# still executed by no suite under the documented production configuration.
# The suites that CALL THEMSELVES production-gate tests rebind
# `interop/debug-enabled?` with `with-redefs` AFTER the framework has loaded,
# and a load-time gate is invisible to that.  That is not a theoretical gap:
# rf2-9c2jf was `dispatch-sync` running its handler ZERO times under the
# documented gate, and it stayed green for as long as it existed.
#
# WHY ROUTING, SPECIFICALLY.  rf2-u2x6w established that sub-classification —
# a PRIVACY invariant, not a diagnostic — genuinely egresses in production
# from routing's `:routing/route-sub-egress-path`.  Its always-on witness
# `re-frame.routing-sub-egress-production-test` was written and mutation-proved
# under `-Dre-frame.debug=false`, and then had nowhere to run: no lane reached
# this artefact.  This script is that lane.
#
#     bash scripts/test-routing-prod-gate.sh          run the lane
#     bash scripts/test-routing-prod-gate.sh --plan   print the roster, run nothing
#
# CI arm: the `jvm-routing-prod-gate` job in `.github/workflows/test.yml`,
# which is in `all-required-passed`'s `needs:`.
#
# WHY A SEPARATE SCRIPT AND NOT A FLAG ON `test-core-prod-gate.sh`.  The core
# roster is core-specific and load-bearing — `verify_roster` hard-errors on an
# entry naming no live namespace, so three artefacts' triage debt in one list
# means a rename in one artefact fails a list another artefact owns.  The flag
# lives in a per-artefact `:prod-gate` alias's `:jvm-opts`, and the `:test`
# alias's own shape differs per artefact.  Above all, the EXCLUSION polarity
# below only holds when the roster and the namespace set live in the same
# artefact: an allowlist reaching across artefacts has the opposite failure
# mode.
#
# HOW THE FLAG GETS THERE, AND HOW YOU KNOW IT ARRIVED.  The property lives in
# the `:prod-gate` alias's `:jvm-opts` (implementation/routing/deps.edn),
# composed onto `:test` — so it is part of the LANE's definition rather than
# something a caller has to remember, and `:extra-paths` / `:extra-deps` cannot
# drift between the two lanes.  `re-frame.routing-prod-gate-lane-pin-test` then
# runs INSIDE the lane and asserts, unconditionally, that the property reached
# this JVM and that the framework honoured it.  Without that pin a lost flag
# would not go red: this roster is by construction a subset of what already
# passes in dev posture, so the lane would go GREEN on the wrong posture — the
# exact class of false green this whole file exists to close.
#
# WHY A ROSTER AND NOT THE WHOLE SUITE.  Nobody had ever run the routing suite
# under the real gate.  Run on 2026-07-27 it is emphatically RED: 293 failures
# and 9 errors across 19 of its 36 test namespaces, and essentially every
# failure is a test asserting DEV INSTRUMENTATION — a `:rf.route/*` trace
# fired, an `:errors` sink received, `:doc` / `:source` retained on a
# route-algebra node — inline with the semantics it is really about.  Under
# `-Dre-frame.debug=false` the framework does not emit any of that, by design,
# so those are legitimate dev-posture tests rather than defects, and "make the
# whole suite green under the gate" is not a fix, it is a rewrite of how those
# assertions are spelled.  The triage bead is named per group below.
#
# The roster is therefore an EXCLUSION list, not an allowlist.  The polarity is
# the point: a namespace added to `implementation/routing/test/` joins this lane
# BY DEFAULT and has to be excluded deliberately, so a new suite that breaks
# under the production gate reddens this job the day it lands.  An allowlist
# would have the opposite failure mode — silently not covering the new thing.
# The list shrinks as rf2-o5dbf lands; when it reaches zero, this script is one
# line and the `-n` machinery goes away.

set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
artefact="$repo_root/implementation/routing"
test_root="$artefact/test"

# ---------------------------------------------------------------------------
# The known-red roster.
#
# Every entry is a namespace that FAILS under `-Dre-frame.debug=false` today,
# grouped by why, with its red-assertion count as measured on 2026-07-27.  The
# group names the bead that clears it.  An entry that no longer names a real
# namespace is a hard error (see `verify_roster` below), so a rename cannot
# leave a stale exclusion quietly suppressing coverage.
# ---------------------------------------------------------------------------
known_red=(
  # ── rf2-o5dbf — CLEARED 2026-07-27.  The four LOUD-REJECTION tripwires
  #    (`routing-boundary-totality-cljs-test`, `routing-can-leave-test`,
  #    `routing-url-strategy-test`, `routing-entry-denied-test`) were the
  #    first triage batch, because each one had to be read before it could be
  #    written off as instrumentation.  Verdict: EVERY rejection those suites
  #    assert does survive production — none is an rf2-9c2jf-class defect.
  #    What is dev-only is the CHANNEL each was observed through, and in two
  #    cases the observation was worse than dev-only:
  #      * `denial-is-safe-with-no-application-handler` and
  #        `repeated-denials-never-emit-a-loop-error` each asserted a NEGATIVE
  #        over the trace ring (`not-any? :rf.error/no-such-handler` /
  #        `:rf.error/route-guard-loop`).  Under the gate that ring is empty
  #        by design, so both would have passed VACUOUSLY the moment the
  #        roster line came off.  They are now inside the posture arm, with
  #        the deny itself asserted outside it.
  #      * the `url-strategy` consult tripwire is dev-only BY DESIGN
  #        (rf2-ecb4sx made the consult a trusted read); the production-real
  #        defence is the ungated registration preflight, which the same
  #        namespace already pins posture-independently.
  #    All four now carry a "## Posture split (rf2-o5dbf)" ns docstring.

  # ── rf2-o5dbf — the CLASSIFICATION / PRIVACY suites.  rf2-u2x6w already
  #    carved the always-on production legs of the egress contract into
  #    `re-frame.routing-sub-egress-production-test`, which is IN this lane
  #    and green; what is left here should be the dev-trace half.  "Should
  #    be" is a claim to verify, not to assume — sub-classification is the
  #    one invariant in this artefact whose failure is a privacy incident.
  #
  #    NOTE, and this is the trap `test-core-prod-gate.sh` documents as the
  #    `conformance-test` case: excluding `routing-egress-test` takes its
  #    ALWAYS-ON legs out of the lane along with its dev-trace ones.  That is
  #    survivable here only because rf2-u2x6w moved those legs into the
  #    dedicated namespace above.  Do not exclude a namespace whose always-on
  #    witnesses have no other home.
  re-frame.routing-egress-test                    # 43
  re-frame.routing-classification-test            # 12

  # ── rf2-o5dbf — dev-instrumentation assertions written inline with the
  #    semantics they sit next to: "exactly one `:rf.route/cleared` trace
  #    fired", "the `:rf.error/navigate-bad-request` diagnostic names
  #    `:unknown-keys`", "`:doc` / `:source` retained on the route-algebra
  #    node for tooling", "the `:rf.route.nav-token/stale-suppressed` trace
  #    carries the carried and current tokens".  Under
  #    `-Dre-frame.debug=false` the framework emits none of it, by design, so
  #    these are legitimate dev-posture tests — but they drag their semantic
  #    neighbours out of this lane with them.  Every line removed from here
  #    is a namespace whose semantics are now proven under the production
  #    posture.
  #
  #    NOTE for whoever finishes this list: a namespace whose EVERY deftest is
  #    about the dev trace has no semantic residue to run under the gate.
  #    Guarding it wholesale and deleting its roster line would report GREEN
  #    for a namespace that executed nothing — the false-green this lane
  #    exists to close.  Those want a var-level tag the lane excludes, not a
  #    posture guard.
  re-frame.route-algebra-view-test                #  5
  re-frame.routing-framework-authority-test       #  2
  re-frame.routing-nav-allocation-record-replay-test  #  1
  re-frame.routing-nav-fx-schemas-test            # 32
  re-frame.routing-nav-token-test                 # 32
  re-frame.routing-navigation-test                # 64
  re-frame.routing-plan-seam-test                 # 31
  re-frame.routing-prefetch-test                  # 18
  re-frame.routing-registry-test                  # 11
  re-frame.routing-scroll-test                    # 11
  re-frame.routing-subs-test                      #  4
  re-frame.routing-uncaptured-param-test          #  7
  re-frame.routing-url-bound-test                 #  4
)

# ---------------------------------------------------------------------------
# Roster derivation
# ---------------------------------------------------------------------------

# Every namespace DECLARED under implementation/routing/test/, read from the
# `(ns ...)` form rather than derived from the path — a lane selector is
# applied by the runner to the declared name, so that is the name that has to
# match.
declared_nses() {
  find "$test_root" -type f \( -name '*.clj' -o -name '*.cljc' \) -exec \
    sed -n 's/^(ns[[:space:]]\{1,\}\(\^{[^}]*}[[:space:]]*\)\{0,1\}\([^[:space:])]\{1,\}\).*/\2/p' {} + \
    | sort -u
}

# `cognitect.test-runner` discovers namespaces under `test/` and keeps those
# matching `.*-test$` (the default `-r`); `-n` then filters that SET.  Mirror
# the same filter here so the two cannot disagree about what the universe is.
test_nses() {
  declared_nses | grep -E -- '-test$'
}

# Two guards, because a silently-shrinking roster is the failure mode this lane
# is supposed to make impossible.
verify_roster() {
  local nses="$1" files ns_count missing=()

  # 1. The `(ns ...)` scrape must still see every test file.  If the regex ever
  #    stops matching, the lane quietly narrows and stays green.
  files="$(find "$test_root" -type f \( -name '*_test.clj' -o -name '*_test.cljc' \) | wc -l)"
  ns_count="$(printf '%s\n' "$nses" | grep -c . || true)"
  if [ "$files" -ne "$ns_count" ]; then
    printf 'FAIL prod-gate roster: %s test files under %s but %s `-test` namespaces scraped.\n' \
      "$files" "${test_root#"$repo_root"/}" "$ns_count" >&2
    printf '     The `(ns ...)` scrape in declared_nses() has drifted from the tree.\n' >&2
    return 1
  fi

  # 2. Every exclusion must still name a live namespace.  A stale entry is an
  #    exclusion nobody can see the effect of.
  for ns in "${known_red[@]}"; do
    printf '%s\n' "$nses" | grep -q -x -F -- "$ns" || missing+=("$ns")
  done
  if [ ${#missing[@]} -ne 0 ]; then
    printf 'FAIL prod-gate roster: %s known-red entr(y|ies) name no live namespace:\n' \
      "${#missing[@]}" >&2
    printf '  %s\n' "${missing[@]}" >&2
    printf '     Renamed or deleted? Drop the entry from known_red in %s.\n' \
      "${BASH_SOURCE[0]#"$repo_root"/}" >&2
    return 1
  fi
}

all_nses="$(test_nses)"
verify_roster "$all_nses"

excluded="$(printf '%s\n' "${known_red[@]}" | sort -u)"
runnable="$(printf '%s\n' "$all_nses" | grep -v -x -F -f <(printf '%s\n' "$excluded"))"

runnable_count="$(printf '%s\n' "$runnable" | grep -c . || true)"
excluded_count="$(printf '%s\n' "$excluded" | grep -c . || true)"
total_count="$(printf '%s\n' "$all_nses" | grep -c . || true)"

# ---------------------------------------------------------------------------
# Report the posture BEFORE running, so the log carries the evidence
# ---------------------------------------------------------------------------
printf '==> implementation/routing under the REAL production gate\n'
printf '    jvm property : -Dre-frame.debug=false (implementation/routing/deps.edn, :prod-gate :jvm-opts)\n'
printf '    posture pin  : re-frame.routing-prod-gate-lane-pin-test (red if the property did not arrive)\n'
printf '    namespaces   : %s of %s (%s excluded as known-red — rf2-o5dbf)\n' \
  "$runnable_count" "$total_count" "$excluded_count"

if [ "${1:-}" = "--plan" ]; then
  printf '    runnable:\n'
  printf '      %s\n' $runnable
  printf '    excluded:\n'
  printf '      %s\n' $excluded
  exit 0
fi

# The coverage floor.  `re-frame.test-quiet.runner` reds any SUITE lane that
# executed fewer than RF2_MIN_TESTS tests, so a roster that collapsed — a
# renamed directory, an `-n` list that matched nothing — cannot report itself
# green with `Ran 0 tests`.  Calibrated below the observed count with room for
# ordinary churn; raise it when the roster grows materially.
export RF2_MIN_TESTS="${RF2_MIN_TESTS:-85}"

args=()
for ns in $runnable; do
  args+=(-n "$ns")
done

cd "$artefact"
if ! clojure -M:test:prod-gate "${args[@]}"; then
  printf '\nFAIL implementation/routing under -Dre-frame.debug=false\n' >&2
  printf 'repro: bash scripts/test-routing-prod-gate.sh\n' >&2
  printf 'A namespace that is green in `clojure -M:test` and red here is asserting\n' >&2
  printf 'DEV INSTRUMENTATION, or it is a genuine production defect (rf2-9c2jf was\n' >&2
  printf 'the latter). Decide which before touching the known_red roster.\n' >&2
  exit 1
fi

printf 'PASS implementation/routing under -Dre-frame.debug=false (%s namespaces)\n' "$runnable_count"
