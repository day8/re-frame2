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
#
# THE ROSTER IS NOW EMPTY (rf2-o5dbf, 2026-07-27).  All 19 entries were triaged
# and split; every one of this artefact's test namespaces runs under the gate.
# The `-n` machinery STAYS: it is what makes the exclusion polarity above real,
# so the next namespace that goes red under the gate has a documented place to
# be rostered — with a bead — instead of quietly reddening the job forever.

set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

# First line names the tree this run resolved — see scripts/test-fast-pr.sh
# (rf2-g2mxd): a relative invocation resolves `${BASH_SOURCE[0]}` against the
# shell's actual cwd, so a backgrounded gate can silently run in, and grade,
# another worktree.  Invoke backgrounded gates by ABSOLUTE path.
printf 'gate root: %s\n' "$repo_root"

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

  # ── rf2-o5dbf — the CLASSIFICATION / PRIVACY suites, CLEARED 2026-07-27.
  #    The claim to verify was that what remained in `routing-egress-test`
  #    beyond rf2-u2x6w's carve-out was the dev-trace half.  VERIFIED, and
  #    the split is now explicit in both namespaces' docstrings: the
  #    `:sensitive` RETENTION (`rf/handler-meta`, both public arities), the
  #    pure carrier scrub (`egress/redact-url-carriers` /
  #    `redact-url-tag`), the in-process rawness, the lowering / re-rooting
  #    into the elision registry and the real SSR `payload-policy` consumer
  #    are all production-real and now run under the gate.  Only readings OFF
  #    THE TRACE BUS are dev-gated.
  #
  #    Both suites also carried VACUOUS PASSES that this roster line was
  #    hiding: `(not (re-find #"SECRET100" (pr-str payload)))` over a nil
  #    trace payload, and four `(is (empty? warnings))` advisory-quiet tests
  #    over an empty trace ring.  Each would have reported a privacy
  #    guarantee the framework never executed the moment the line came off.
  #    They are inside the posture arm now, and the always-on DETECTION
  #    (`classification/unpromoted-query-keys`) is asserted in their place.
  #
  #    The `conformance-test` trap `test-core-prod-gate.sh` documents no
  #    longer applies to these two, but the rule still does: do not exclude a
  #    namespace whose always-on witnesses have no other home.
  #    `re-frame.routing-sub-egress-production-test` stays IN the lane.

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
  #    CLEARED 2026-07-27 (rf2-o5dbf, batch 3 — the small end of the list):
  #    route-algebra-view, routing-framework-authority,
  #    routing-nav-allocation-record-replay, routing-subs,
  #    routing-uncaptured-param, routing-url-bound.  Each now carries a
  #    "## Posture split (rf2-o5dbf)" ns docstring.
  #
  #    `routing-framework-authority-test` is the closest thing in this
  #    artefact to the "no semantic residue" case the note above warns about,
  #    and is called out here because the next reader deserves to know: FIVE
  #    of its six deftests assert `(is (empty? @warns))` over the
  #    `:rf.warning/app-handler-runtime-effect` trace, and the sixth is
  #    explicitly the control that proves the other five are not vacuous.
  #    Under the gate every one of those six is meaningless, so all six are
  #    inside the posture arm.  It stays in the lane because the navigation
  #    SCAFFOLDING each case drives — the route commits, the pending slot
  #    fills and clears, `:rf.route/continue` completes — is real runtime-db
  #    state that does execute.  Be clear-eyed about the trade: under the
  #    production gate this namespace contributes routing semantics, not the
  #    ownership contract it is named for.
  #    CLEARED 2026-07-27 (rf2-o5dbf, batch 8 — THE LAST TWO):
  #    routing-navigation, routing-plan-seam.  With these the roster is
  #    EMPTY: every one of this artefact's 37 test namespaces now runs under
  #    `-Dre-frame.debug=false`, and the `-n` selector list is the whole set.
  #
  #    `routing-plan-seam` carried the sharpest vacuous pass the programme has
  #    found.  `an-executed-navigations-plan-trace-is-not-a-carrier` asserts
  #    `(is (not (re-find #"SECRET100" (pr-str tags))))` over a REAL
  #    navigation's plan trace — and under the gate `tags` is nil, so it
  #    certified that a secret stayed out of an egress copy the framework
  #    never made.  Its `tok-99` fragment sibling is the same.  Both are
  #    inside the posture arm now; outside it the suite asserts
  #    `resolver/plan-trace-tags` — the PURE fn the emit site calls — over the
  #    same address, which is the redaction itself with no bus in between.
  #
  #    `routing-navigation` is the artefact's LOUD-REJECTION verdict, and it
  #    matches the four tripwires batch 1 read: EVERY rejection survives
  #    production.  `address/classify` and the event-shape gate run
  #    unconditionally and return `{}` from the handler, so a malformed
  #    request leaves the slice untouched and pushes no URL under the gate
  #    exactly as in dev — only the `:rf.error/navigate-bad-request`
  #    diagnostic beside them goes quiet.  Because that diagnostic was the
  #    only place the PER-RULE discrimination was read, the always-on
  #    replacement is `address/classify` itself: same fn the handler calls,
  #    same request, no gate between the call and the verdict.  The same
  #    holds for the param-validation reject — slice unchanged in BOTH
  #    postures — which is a DIFFERENT shape from the nav-fx-schemas note
  #    below, where the verdict itself short-circuits to `true`.
  #
  #    Eight more negatives-over-an-empty-ring across the two: the only
  #    assertion in `transitioned-well-formed-url-does-not-emit-malformed-
  #    trace`, the two `(is (empty? (planned …)))` non-commit legs, three
  #    nav-token/fragment-changed denials on the short-circuit paths, the
  #    schema-validation denial on the unmatched-without-404 commit, and —
  #    worst — `(is (empty? (filter …)))` in
  #    `commit-traces-suppressed-from-trace-disabled-frame`, which IS that
  #    deftest's point and would have certified a leak-suppression the
  #    framework had no occasion to perform.
  #
  #    CLEARED 2026-07-27 (rf2-o5dbf, batch 7): routing-nav-fx-schemas.
  #    READ THIS ONE BEFORE ASSUMING THE NEXT LOOK-ALIKE IS A DEFECT.  Its
  #    failures were `(is (false? (validate-through-hook …)))` returning
  #    TRUE under the gate — the fx-args gate accepting args it rejects in
  #    dev, which reads exactly like the rf2-9c2jf class.  It is not.
  #    `re-frame.schemas.validate/validate-fx!` is literally
  #    `(if interop/debug-enabled? (run-validation …) true)`, per Spec 010
  #    §Production builds: the per-step `validate-*!` hot-path fns are
  #    dev-only and production-build validation is the OPT-IN boundary
  #    interceptor `:rf.schema/at-boundary`, which routes through
  #    `validate-with-registered-fn` outside the gate.
  #
  #    That short-circuit also makes the suite's POSITIVE control pass for
  #    the wrong reason — `true` because validation did not run, not because
  #    the args conform.  The always-on replacement is `m/validate` against
  #    the LIVE registration's `:schema`: same schema the hook consults, no
  #    gate between the call and the verdict.
  #
  #    CLEARED 2026-07-27 (rf2-o5dbf, batch 6): routing-nav-token.  Stale
  #    suppression is ENFORCEMENT, not advice — the superseded completion's
  #    app `:rf/reply-to` target is never dispatched, so app-db and
  #    runtime-db are provably unchanged in either posture.  Everything
  #    SPELLED on the `:rf.route.nav-token/stale-suppressed` trace (the
  #    carried/current token pair, `:completed-at`, the `:rf.reply/work-id`
  #    join key, the EP-0011 envelope vocabulary) is dev-only.  Three
  #    `:completed-at` deftests had NO non-trace assertion at all and would
  #    have executed nothing; each gained the app-db witness the suppression
  #    actually is.
  #
  #    CLEARED 2026-07-27 (rf2-o5dbf, batch 5): routing-prefetch.  Its
  #    `@calls` atom is the stubbed `:routing/on-route-prefetch` warm hook —
  #    a late-bound fn, NOT a trace — so "did prefetch reach planning, and
  #    with which resolved identity" was always production-visible; only the
  #    summary / rejection REPORTING is dev-gated.  Note the carrier-absence
  #    trio at its foot: with no trace the `tags` map is nil, so all three
  #    would have certified a PRIVACY guarantee about a payload that was
  #    never built.
  #
  #    CLEARED 2026-07-27 (rf2-o5dbf, batch 4): routing-registry,
  #    routing-scroll.  Both had NEGATIVE trace assertions that this roster
  #    line was hiding — the shadow advisory's two "registers with ZERO
  #    shadow warnings" blocks, and five `(not (contains? (first tags)
  #    :fx-id))` legs where `(first tags)` is nil under the gate.  The
  #    always-on witnesses that replaced them are `match-url` (the shadow
  #    advisory's whole claim is about which route wins at match time) and
  #    the fx `:platforms #{:client}` declarations (the cause of every
  #    skip-on-platform trace the tag-spelling test was about).
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
#
# rf2-o5dbf raised this 85 -> 380 -> 455.  The original 85 was calibrated
# against an observed 98 (~87%); by the time the roster was down to two entries
# the lane ran 438, so an 85 floor sat FIVE TIMES below what it guards — a
# roster collapse to a fifth of the lane would still have reported green.  With
# the roster now EMPTY the lane runs 524, and 455 restores the same ~87% ratio.
# That is also the floor's final job: with no exclusions left, this is the only
# thing standing between a `-n` list that matched nothing and a green report.
export RF2_MIN_TESTS="${RF2_MIN_TESTS:-455}"

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
