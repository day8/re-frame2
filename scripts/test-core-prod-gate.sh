#!/usr/bin/env bash
#
# rf2-f8x2i — run `implementation/core` under the REAL production gate.
#
# WHY THIS EXISTS.  `SECURITY.md` documents `-Dre-frame.debug=false` (and
# `RE_FRAME_DEBUG=false`) as the JVM/SSR production setting, and
# `re-frame.interop/debug-enabled?` reads it ONCE at namespace-load time.
# Nothing in `.github/workflows` or `scripts/` had ever set it, so the
# documented production configuration was executed by no suite anywhere.  The
# suites that CALL THEMSELVES production-gate tests
# (`jvm_prod_gate_integration_test`, `ep0008_producers_jvm_gate_test`, and
# friends) rebind `interop/debug-enabled?` with `with-redefs` AFTER the
# framework has loaded, and a load-time gate is invisible to that.  That is not
# a theoretical gap: rf2-9c2jf was `dispatch-sync` running its handler ZERO
# times under the documented gate — `:rf.error/no-such-handler`, app-db
# untouched, while `registrar/lookup` returned the handler at that same moment
# — and it stayed green for as long as it existed.
#
#     bash scripts/test-core-prod-gate.sh          run the lane
#     bash scripts/test-core-prod-gate.sh --plan   print the roster, run nothing
#
# CI arm: the `jvm-core-prod-gate` job in `.github/workflows/test.yml`, which
# is in `all-required-passed`'s `needs:`.
#
# HOW THE FLAG GETS THERE, AND HOW YOU KNOW IT ARRIVED.  The property lives in
# the `:prod-gate` alias's `:jvm-opts` (implementation/core/deps.edn), composed
# onto `:test` — so it is part of the LANE's definition rather than something a
# caller has to remember, and `:extra-paths` / `:extra-deps` cannot drift
# between the two lanes.  `re-frame.prod-gate-lane-pin-test` then runs INSIDE
# the lane and asserts, unconditionally, that the property reached this JVM and
# that the framework honoured it.  Without that pin a lost flag would not go
# red: this roster is by construction a subset of what already passes in dev
# posture, so the lane would go GREEN on the wrong posture — the exact class of
# false green this whole file exists to close.
#
# WHY A ROSTER AND NOT THE WHOLE SUITE.  Nobody had ever run the core suite
# under the real gate.  Run on 2026-07-27 it is emphatically RED: 91 of its 174
# test namespaces fail, and essentially every failure is a test asserting DEV
# INSTRUMENTATION — a trace fired, an `:errors` sink received, `:doc` metadata
# retained, source coords recorded — inline with the semantics it is really
# about.  Under `-Dre-frame.debug=false` the framework does not emit any of
# that, by design, so those are legitimate dev-posture tests rather than
# defects, and "make the whole suite green under the gate" is not a fix, it is
# a rewrite of how those assertions are spelled.  Triage beads are named per
# cluster below.
#
# What is left IS green, and it is not a rump: 154 namespaces, 1740 tests, 7342
# assertions, exit 0 — versus the zero suites that had ever executed under this
# posture before.  (It was
# 88 / 934 / 4340 tests/assertions before rf2-d2841 split the spine's
# dev-instrumentation assertions away from the semantics beside them,
# 94 / 1106 / 5135 before that bead's second pass took `fx-test` — the largest
# single entry, 107 failures and 25 errors across ~20 deftests — plus
# `sub-topology`, `init-platform`, `pattern-smoke` and `db-noop-commit`,
# 103 / 1213 / 5590 before its third pass took twenty more, 123 / 1401 / 6054
# before its FOURTH pass took seventeen more, and 141 / 1560 / 6707 before its
# FIFTH pass took thirteen more.  Each pass's namespaces are named in its
# commits — `git log --grep=rf2-d2841`.)
#
# The roster is therefore an EXCLUSION list, not an allowlist.  The polarity is
# the point: a namespace added to `implementation/core/test/` joins this lane
# BY DEFAULT and has to be excluded deliberately, so a new suite that breaks
# under the production gate reddens this job the day it lands.  An allowlist
# would have the opposite failure mode — silently not covering the new thing.
# The list shrinks as the beads land; when it reaches zero, this script is one
# line and the `-n` machinery goes away.

set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
core="$repo_root/implementation/core"
test_root="$core/test"

# ---------------------------------------------------------------------------
# The known-red roster.
#
# Every entry is a namespace that FAILS under `-Dre-frame.debug=false` today,
# grouped by why.  Each group names the bead that clears it.  An entry that no
# longer names a real namespace is a hard error (see `verify_roster` below), so
# a rename cannot leave a stale exclusion quietly suppressing coverage.
# ---------------------------------------------------------------------------
known_red=(
  # ── NOT a gate failure — a SUBSET artefact, and the only one.  This suite
  #    asserts that every optional per-feature artefact is loaded on the test
  #    classpath (`feature-loaded?` for :http, :epoch, ...).  Those probe keys
  #    are populated by whichever namespace `require`s the artefact, and
  #    cognitect-test-runner requires only the namespaces a `-n` filter keeps —
  #    so excluding `conformance-test` / `examples-test` for gate reasons takes
  #    the loads with them.  Green under the gate when the WHOLE suite runs;
  #    red only because this lane runs a slice.  It comes back on its own when
  #    the two rosters below empty.
  re-frame.features-cljs-test

  # ── rf2-r9bra — NOT obviously instrumentation. Each of these fails on an
  #    assertion about STATE, RESOLUTION or LIFECYCLE rather than about a
  #    trace or a sink, which is the shape rf2-9c2jf had. They need a human
  #    verdict — dev-posture test, or genuine production defect — before
  #    anyone writes them off. The redaction/sensitivity pair is first in
  #    the queue: if either protection is applied anywhere other than a
  #    trace payload, a production build is shipping unredacted data.
  re-frame.conformance-test
  re-frame.core-api-additions-test
  re-frame.elision-multi-owner-cljs-test
  re-frame.examples-test
  re-frame.frame-upsert-linearization-jvm-test
  re-frame.live-run-frame-resolution-cljs-test
  re-frame.machine-handler-meta-test
  re-frame.partitioned-commit-test
  re-frame.redact-interceptor-test
  re-frame.sensitive-stamping-test
  re-frame.subs-image-local-classification-cljs-test

  # ── rf2-d2841 — dev-instrumentation assertions written inline with the
  #    semantics they sit next to: "exactly one <...> trace fired", "the
  #    :errors sink received", ":doc retained for tooling", "handler-meta
  #    carries :ns". Under -Dre-frame.debug=false the framework emits none
  #    of it, by design, so these are legitimate dev-posture tests — but
  #    they drag their semantic neighbours out of this lane with them.
  #    Every line removed from here is a namespace whose semantics are now
  #    proven under the production posture.
  #
  #    The SPINE is done: smoke / drain / events / sub-cache / interceptor /
  #    frame-lifecycle came off in the first rf2-d2841 pass. The split shape
  #    they use — instrumentation assertions kept VERBATIM inside a
  #    `(when interop/debug-enabled? …)` arm marked `rf2-d2841`, everything
  #    outside such an arm posture-independent — is documented in each of
  #    those namespaces' docstrings under "## Posture split (rf2-d2841)".
  #    `fx-test` — 107 failures / 25 errors across ~20 deftests, the big one
  #    — came off in the second pass.
  #
  #    THE SPLIT IS NOT ALWAYS A GUARD, AND REACHING FOR ONE FIRST COSTS
  #    COVERAGE. Four `:rf.error/*` categories in the fx subsystem
  #    (`fx-handler-exception`, `no-such-fx`, `override-fallthrough`,
  #    `reserved-fx-override`) are ALWAYS-ON: they fan through
  #    `emit-fx-error!` → `error-emit/emit-error-both!`, which additionally
  #    LIFTS `:failing-id` and `:reason` onto the record whenever the failing
  #    component differs from the dispatched event (Spec 009 §Component
  #    attribution). Assertions about those survive the gate verbatim once
  #    they read the `:errors` stream instead of the `:trace` stream — and
  #    that includes the NEGATIVE ones, which over the dev ring pass for free
  #    under the gate. Check the category's Channel column in Spec 009's
  #    catalogue before guarding anything: `diagnostic` means dev-only by
  #    design (`:rf.error/effect-map-shape`), `always-on` means there is a
  #    production witness to be had.
  #
  #    AND THE DEV-ONLY HALF IS NOT ALWAYS A TRACE. `sub-topology-test`'s
  #    was REFLECTION METADATA — `:doc` and the auto-captured `:ns` / `:line`
  #    / `:file` source-coords, elided in production — while the topology
  #    SHAPE around them was entirely posture-independent. The false-green
  #    that shape produces is worth recognising on sight: a negative about a
  #    key that is elided WHOLESALE (`no-doc-key-when-not-supplied`) passes
  #    under the gate because the key never exists, not because the
  #    registration omitted it. Same class as a negative over an empty ring,
  #    different subject. `db-noop-commit-test` is the counter-example worth
  #    copying: its adversarial discriminators read frame-state OBJECT
  #    IDENTITY off `frame/frame-state-value`, which needs no channel at all.
  #
  #    NOTE for whoever finishes this list: a namespace whose EVERY deftest
  #    is about the dev trace (the `trace-listener-*` cohort, `trace-test`,
  #    `db-pending-trace-test`, `sub-dispose-trace-test`, …) has no semantic
  #    residue to run under the gate. Guarding it wholesale and deleting its
  #    roster line would report GREEN for a namespace that executed nothing
  #    — the false-green this lane exists to close. Those want a var-level
  #    tag the lane excludes (the `-e :requires-debug` idea above), not a
  #    posture guard.
  #
  #    THE THIRD PASS'S LESSON: THE FILES THAT ALREADY LOOK GREEN ARE WHERE
  #    THE ROT IS. Of the eleven namespaces taken off in that pass, seven were
  #    on this list for a handful of red assertions each — but every one of
  #    them ALSO carried assertions that were passing under the gate for no
  #    reason whatsoever. `configure-test` had four `(<= (count
  #    (rf/trace-buffer …)) N)` retention caps, all true over the `[]` that
  #    `trace-buffer` returns in production; `unknown-dispatch-opts-warn-test`
  #    had four `(empty? (unknown-opt-warnings …))` negatives certifying keys
  #    as recognised over a stream that is empty for every key. Fixing only
  #    the red assertions and deleting the roster line would have promoted
  #    those into the lane as permanent false green. Read the WHOLE namespace,
  #    not the failure list.
  #
  #    AND `configure-test` IS THE CAUTIONARY TALE FOR THE OTHER DIRECTION.
  #    The pass-2 note guessed that "the retention it configures IS production
  #    state even though the warning is not". It is not:
  #    `trace.tooling/configure-trace-buffer!` opens BOTH arms with
  #    `(when (and interop/debug-enabled? …))`, so the knob and its warning
  #    are equally dev-only. Check the implementation, not the plausible
  #    story about it.
  #
  #    THE FOURTH PASS'S LESSON: ASK WHERE THE SUBJECT LIVES, NOT WHERE THE
  #    TEST HAPPENS TO READ IT.  Over half of that pass's seventeen namespaces
  #    needed no guard at all for their main claim, because the thing under
  #    test was production state that the test had merely been OBSERVING
  #    through the trace.  `:source :fx-dispatch`, `:rf.cofx`, `:fx-overrides`
  #    and `:interceptor-overrides` are slots on the DISPATCH ENVELOPE, and a
  #    user fx-handler is handed that envelope verbatim as `(:envelope m)` —
  #    the surface `cascade-envelope-propagation-test/fx-handler-ctx-carries-
  #    envelope-slot` pins.  An `:ovc/probe`-style fx inside each level of a
  #    cascade reads every one of them with no trace involvement, in BOTH
  #    postures.  `substrate-source-test` went from contributing nothing under
  #    this lane to contributing sixteen assertions that way.
  #
  #    THE SAME QUESTION, ASKED OF THE ERROR AXIS, IS "IS THE CATEGORY
  #    PROMOTED?"  `:rf.error/classification-effect-shape`,
  #    `:rf.error/sub-input-fn-exception`, `:rf.error/sub-input-fn-bad-return`
  #    and `:rf.error/no-such-sub` all fan through
  #    `error-emit/emit-error-both!`, so "it fails loud" is provable on the
  #    `:errors` stream in production posture.  What does NOT survive is the
  #    DETAIL: `emit-error-both!` lifts only `:failing-id` / `:reason`, and
  #    only when `:failing-id` differs from `:event-id`.  So a category with
  #    no `:failing-id` — classification-effect-shape — reaches production
  #    saying THAT an effect was malformed but not WHICH KEY.
  #
  #    AND A CORRECTION.  `fx-args-trace-egress-cljs-test`'s docstring claimed
  #    the `:rf.fx/args` slot on the fx error traces "is production-survivable
  #    — it fans out through the always-on error-emit listener".  The CATEGORY
  #    is promoted; the SLOT is not.  `fx.cljc`'s `:rf.error/no-such-fx` site
  #    says so outright: "the tight-record discipline is intact: `:rf.fx/args`
  #    stays on the dev trace and does NOT reach the production record".  That
  #    is the third triage reason across four passes that turned out to be
  #    wrong when checked against the source.  Check the source.
  #
  #    ONE MORE FALSE-GREEN SHAPE, worth recognising because it is not a trace
  #    ring: `core-epoch-egress-profile-test` read its claims off the EPOCH
  #    RING, which `epoch.capture/observe-trace-event!` feeds from the dev
  #    trace.  Empty ring → nil record → nil marker, and SIX assertions on an
  #    egress-PRIVACY surface passed on the nil: two `(= x y)` where both were
  #    nil, `(not (contains? nil :digest))`, a `count` over an empty string, a
  #    `not-any?` over an empty history, and a human-sentence check on a nil
  #    message.  The fix was not a guard — `projected-record` is a pure
  #    function of a record plus the frame's durable elision registry, so the
  #    profile rows now drive a SYNTHETIC record and run in both postures.
  re-frame.db-pending-trace-test
  re-frame.observation-port-cljs-test
  re-frame.sub-dispose-trace-test
  re-frame.trace-buffer-test
  re-frame.trace-listener-concurrent-drain-serialization-test
  re-frame.trace-listener-concurrent-serialization-test
  re-frame.trace-listener-continuation-neutral-cljs-test
  re-frame.trace-listener-deferred-batch-fifo-cljs-test
  re-frame.trace-listener-drain-deadlock-test
  re-frame.trace-listener-frame-tagged-serialization-test
  re-frame.trace-listener-nested-emission-order-cljs-test
  re-frame.trace-listener-post-drain-settled-state-cljs-test
  re-frame.trace-listener-reentrant-dispatch-sync-deferral-test
  re-frame.trace-listener-test
  re-frame.trace-test
  re-frame.trace.structural-retention-cljs-test
)

# ---------------------------------------------------------------------------
# Roster derivation
# ---------------------------------------------------------------------------

# Every namespace DECLARED under implementation/core/test/, read from the `(ns
# ...)` form rather than derived from the path — a lane selector is applied by
# the runner to the declared name, so that is the name that has to match.
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
  #
  #    THE COUNT IS ABOUT THIS SCRIPT'S SCRAPE, NOT ABOUT DISCOVERY.  It reads
  #    the `(ns ` line as TEXT, so it goes on counting a file whose FORM the
  #    Clojure reader cannot read — measured green over exactly such a file
  #    while the lane ran eight tests fewer (rf2-vruo9).  Whether a file is
  #    really discoverable is decided by a reader, in
  #    `re-frame.test-quiet.runner/discovery-defects`, which the run below
  #    passes through before any test executes.  Do not reach for a cleverer
  #    regex here: a scrape that imitated a reader would be a second way to be
  #    wrong about the same thing.
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
printf '==> implementation/core under the REAL production gate\n'
printf '    jvm property : -Dre-frame.debug=false (implementation/core/deps.edn, :prod-gate :jvm-opts)\n'
printf '    posture pin  : re-frame.prod-gate-lane-pin-test (red if the property did not arrive)\n'
printf '    namespaces   : %s of %s (%s excluded as known-red)\n' \
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
# RAISED 800 -> 1360 (rf2-d2841, third pass), 1360 -> 1510 (fourth pass), then
# 1510 -> 1690 (fifth pass).  800 was calibrated against the 915-test lane of
# rf2-f8x2i's original run and had stopped doing its job.  1360 was calibrated
# against 1401 and stopped doing its job the same way, and 1510 has now done
# the same in its turn: the lane runs 1740 tests, and the thirteen namespaces
# the fifth pass added are 180 of them — losing the whole batch lands at 1560,
# which 1510 waves through.  1690 notices that while leaving ~3% for ordinary
# churn.
#
# The floor is a COLLAPSE DETECTOR, not a target, and the rule that follows
# from that is why it has moved every pass: it must sit close enough under the
# observed count that THE SMALLEST BATCH ANYONE LANDS IS STILL VISIBLE.  A
# floor left behind by a growing lane is not conservative, it is inert.
#
# WHAT THE FLOOR DOES NOT CATCH, and what caught it out during the fifth pass:
# a test file whose `(ns ...)` FORM is malformed is invisible to
# `cognitect.test-runner`'s namespace DISCOVERY, so a `-n` selector naming it
# matches nothing and the lane simply runs one namespace fewer, exit 0.  The
# floor catches that collapse only once enough tests have gone missing —
# measured at eight, out of 2190, which is well inside the ~3% of headroom a
# growing lane has to leave itself.
#
# CLOSED (rf2-vruo9), and NOT here.  The check that catches it reads the form
# instead of scraping the line, so it belongs in a process that already has a
# reader and the lane's classpath: `re-frame.test-quiet.runner` now refuses to
# start when any file in a discovery directory will not reach the runner as
# its own namespace.  That is the wrapper EVERY JVM artefact's `:test` alias
# invokes, so the rule covers every lane in the repo rather than this one, and
# it fires before a single test runs.  `verify_roster` below is unchanged and
# still cannot see this class on its own — see its guard #1.
export RF2_MIN_TESTS="${RF2_MIN_TESTS:-1690}"

args=()
for ns in $runnable; do
  args+=(-n "$ns")
done

cd "$core"
if ! clojure -M:test:prod-gate "${args[@]}"; then
  printf '\nFAIL implementation/core under -Dre-frame.debug=false\n' >&2
  printf 'repro: bash scripts/test-core-prod-gate.sh\n' >&2
  printf 'A namespace that is green in `clojure -M:test` and red here is asserting\n' >&2
  printf 'DEV INSTRUMENTATION, or it is a genuine production defect (rf2-9c2jf was\n' >&2
  printf 'the latter). Decide which before touching the known_red roster.\n' >&2
  exit 1
fi

printf 'PASS implementation/core under -Dre-frame.debug=false (%s namespaces)\n' "$runnable_count"
