#!/usr/bin/env bash
#
# Run `implementation/core` under the REAL production gate.
#
# WHY THIS EXISTS.  `spec/Security.md` documents `-Dre-frame.debug=false` (and
# `RE_FRAME_DEBUG=false`) as the JVM/SSR production setting, and
# `re-frame.interop/debug-enabled?` reads it ONCE at namespace-load time.
# Without a lane that genuinely sets it, the documented production
# configuration is executed by no suite anywhere.  The
# suites that CALL THEMSELVES production-gate tests
# (`jvm_prod_gate_integration_test`, `ep0008_producers_jvm_gate_test`, and
# friends) rebind `interop/debug-enabled?` with `with-redefs` AFTER the
# framework has loaded, and a load-time gate is invisible to that.  The gap is
# concrete: a `dispatch-sync` that runs its handler ZERO times under the
# documented gate — `:rf.error/no-such-handler`, app-db untouched, while
# `registrar/lookup` returns the handler at that same moment — stays green in
# every rebinding suite.
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
# WHY A ROSTER AND NOT THE WHOLE SUITE.  Run whole under the real gate, the
# core suite is RED.  A test asserting DEV INSTRUMENTATION — a trace fired, an
# `:errors` sink received, `:doc` metadata retained, source coords recorded —
# inline with the semantics it is really about fails there, because under
# `-Dre-frame.debug=false` the framework does not emit any of that, by design.
# Those are legitimate dev-posture tests rather than defects, and "make the
# whole suite green under the gate" is not a fix, it is a rewrite of how those
# assertions are spelled.  Such a namespace is SPLIT or TAGGED (below) so that
# it joins the lane carrying its real claims; what still fails is on the
# known-red roster, grouped by why.
#
# What the lane runs IS green, and it is not a rump: every namespace outside
# that roster.  A fully tagged namespace contributes no test here BY DESIGN —
# the lane's claim about it is that it LOADS under the production gate, not
# that its dev-only deftests ran.
#
# THE TRACE SUITES ARE TAGGED, NOT ROSTERED.  The ten `trace-listener-*`
# suites plus `trace`, `db-pending-trace`, `sub-dispose-trace`, `trace-buffer`
# and `trace.structural-retention-cljs` are about the trace machinery ITSELF,
# end to end, so they have no semantic residue to separate: the
# `(when interop/debug-enabled? …)` split would leave EMPTY deftests reporting
# success — the class-2 false green this lane exists to close.  Each of their
# dev-trace deftests carries `^:requires-debug` instead, and the `:prod-gate`
# alias skips that tag (`implementation/core/deps.edn`, `-e :requires-debug`).
#
# THE TAG IS HONEST WHERE A GUARD WOULD LIE.  A guard says "this ran and
# passed"; the tag says "this namespace requires the debug build", and the
# tally backs it up — cognitect's `contains-tests?` drops a fully-tagged
# namespace before `run-tests` sees it, so those deftests contribute no test
# and no assertion here.  Nothing green is claimed for work that did not
# happen.
#
# IT IS ALSO STRICTLY MORE COVERAGE THAN A ROSTER LINE.  cognitect
# `require`s every `-n` namespace BEFORE filtering vars, so every tagged
# namespace is LOADED under `-Dre-frame.debug=false`.  A top-level form that
# blows up under the production gate reddens this job; a rostered namespace
# would not be on the lane's classpath at all.  (The tag also defuses a hazard
# those files carry: several HANG rather than fail under the gate — a
# `CountDownLatch` waiting on trace events that never arrive — and the tag
# means their bodies never run here.  Anyone probing them by hand with a bare
# `-n` still needs a timeout.)
#
# AND THE GRANULARITY IS VAR-LEVEL BY NECESSITY AS WELL AS BY CHOICE.
# cognitect's `-e` reads VAR metadata; it never looks at the `(ns …)` form's
# metadata, so a namespace-level marker would be silently inert.  Var
# granularity is also what preserves this file's polarity one level down: a new
# deftest added to a tagged namespace is UNTAGGED, so it joins the lane by
# default and has to be excluded deliberately, exactly as a new FILE does.
#
# WHAT REMAINS EXCLUDED, and why it is not more of the same work: the subset
# artefact `features-cljs-test`, and namespaces that fail on assertions about
# STATE, RESOLUTION or LIFECYCLE rather than instrumentation (see the roster).
#
# The roster is therefore an EXCLUSION list, not an allowlist.  The polarity is
# the point: a namespace added to `implementation/core/test/` joins this lane
# BY DEFAULT and has to be excluded deliberately, so a new suite that breaks
# under the production gate reddens this job the day it lands.  An allowlist
# would have the opposite failure mode — silently not covering the new thing.
# The list shrinks as its entries are resolved; when it reaches zero, this
# script is one line and the `-n` machinery goes away.

set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

# First line names the tree this run resolved — see scripts/test-fast-pr.sh:
# a relative invocation resolves `${BASH_SOURCE[0]}` against the
# shell's actual cwd, so a backgrounded gate can silently run in, and grade,
# another worktree.  Invoke backgrounded gates by ABSOLUTE path.
printf 'gate root: %s\n' "$repo_root"

core="$repo_root/implementation/core"
test_root="$core/test"

# ---------------------------------------------------------------------------
# The known-red roster.
#
# Every entry is a namespace that FAILS under `-Dre-frame.debug=false`,
# grouped by why.  An entry that no
# longer names a real namespace is a hard error (see `verify_roster` below), so
# a rename cannot leave a stale exclusion quietly suppressing coverage.
# ---------------------------------------------------------------------------
known_red=(
  # ── NOT a gate failure — a SUBSET artefact, and the only one.  This suite
  #    asserts that every optional per-feature artefact is loaded on the test
  #    classpath (`(get-in (features) [:http :loaded?])` and friends).  Those probe keys
  #    are populated by whichever namespace `require`s the artefact, and
  #    cognitect-test-runner requires only the namespaces a `-n` filter keeps —
  #    so excluding `conformance-test` / `examples-test` for gate reasons takes
  #    the loads with them.  Green under the gate when the WHOLE suite runs;
  #    red only because this lane runs a slice.  It comes back on its own when
  #    the roster below empties.
  re-frame.features-cljs-test

  # ── NOT obviously instrumentation. Each of these fails on an
  #    assertion about STATE, RESOLUTION or LIFECYCLE rather than about a
  #    trace or a sink — the shape a zero-dispatch production defect has.
  #    They need a human
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

  # ── HOW A DEV-INSTRUMENTATION NAMESPACE STAYS OFF THIS ROSTER. A test
  #    that asserts dev instrumentation inline with the semantics it sits
  #    next to — "exactly one <...> trace fired", "the :errors sink
  #    received", ":doc retained for tooling", "handler-meta carries :ns" —
  #    fails under -Dre-frame.debug=false, by design, and would drag its
  #    semantic neighbours out of this lane with it. So such a namespace is
  #    SPLIT: instrumentation assertions kept VERBATIM inside a
  #    `(when interop/debug-enabled? …)` dev-instrumentation arm, everything
  #    outside such an arm posture-independent — documented in each split
  #    namespace's docstring under "## Posture split". A namespace about the
  #    trace machinery itself is TAGGED instead — see "THE TRACE SUITES ARE
  #    TAGGED, NOT ROSTERED" in the header.
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
  #    is REFLECTION METADATA — `:doc` and the auto-captured `:ns` / `:line`
  #    / `:file` source-coords, elided in production — while the topology
  #    SHAPE around them is entirely posture-independent. The false-green
  #    that shape produces is worth recognising on sight: a negative about a
  #    key that is elided WHOLESALE (`no-doc-key-when-not-supplied`) passes
  #    under the gate because the key never exists, not because the
  #    registration omitted it. Same class as a negative over an empty ring,
  #    different subject. `db-noop-commit-test` is the counter-example worth
  #    copying: its adversarial discriminators read frame-state OBJECT
  #    IDENTITY off `frame/frame-state-value`, which needs no channel at all.
  #
  #    A NAMESPACE CAN LOOK LIKE 100% DEV INSTRUMENTATION AND NOT BE.
  #    `sub-dispose-trace-test` carries PRODUCTION sub-cache claims — that a
  #    synchronous dispose leaves a resubscribe rebuilding a FRESH reaction,
  #    and that one input's throwing release does not abort the walk over the
  #    others — inline with dispose-emit assertions that are red under the
  #    gate. Both are about `interop/dispose!` and the cache map, neither
  #    needs the trace, and both are left UNTAGGED, so they are in the lane.
  #    Read the whole namespace before accepting "it is all instrumentation".
  #
  #    THE FILES THAT ALREADY LOOK GREEN ARE WHERE THE ROT IS. A namespace
  #    rostered for a handful of red assertions can ALSO carry assertions that
  #    pass under the gate for no reason whatsoever: an `(<= (count
  #    (rf/trace-buffer …)) N)` retention cap is true over the `[]` that
  #    `trace-buffer` returns in production, and an
  #    `(empty? (unknown-opt-warnings …))` negative certifies keys as
  #    recognised over a stream that is empty for every key. Fixing only the
  #    red assertions and deleting the roster line would promote those into
  #    the lane as permanent false green. Read the WHOLE namespace, not the
  #    failure list.
  #
  #    AND CHECK THE IMPLEMENTATION, NOT THE PLAUSIBLE STORY ABOUT IT. "The
  #    retention a knob configures IS production state even though its
  #    warning is not" sounds right and is wrong for
  #    `trace.tooling/configure-trace-buffer!`, which opens BOTH arms with
  #    `(when (and interop/debug-enabled? …))`, so the knob and its warning
  #    are equally dev-only.
  #
  #    ASK WHERE THE SUBJECT LIVES, NOT WHERE THE TEST HAPPENS TO READ IT.
  #    A test's main claim is often production state that the test merely
  #    OBSERVES through the trace, and then it needs no guard at all.
  #    `:source :fx-dispatch`, `:rf.cofx`, `:fx-overrides`
  #    and `:interceptor-overrides` are slots on the DISPATCH ENVELOPE, and a
  #    user fx-handler is handed that envelope verbatim as `(:envelope m)` —
  #    the surface `cascade-envelope-propagation-test/fx-handler-ctx-carries-
  #    envelope-slot` pins.  An `:ovc/probe`-style fx inside each level of a
  #    cascade reads every one of them with no trace involvement, in BOTH
  #    postures — which is how `substrate-source-test` contributes assertions
  #    to this lane.
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
  #    AND A PROMOTED CATEGORY DOES NOT PROMOTE EVERY SLOT.  The
  #    `:rf.fx/args` slot on the fx error traces is NOT production-survivable
  #    even though its category fans out through the always-on error-emit
  #    listener: `fx.cljc`'s `:rf.error/no-such-fx` site says "the
  #    tight-record discipline is intact: `:rf.fx/args` stays on the dev trace
  #    and does NOT reach the production record".  A triage reason that reads
  #    right can still be wrong against the source.  Check the source.
  #
  #    ONE MORE FALSE-GREEN SHAPE, worth recognising because it is not a trace
  #    ring: the EPOCH RING, which `epoch.capture/observe-trace-event!` feeds
  #    from the dev trace.  Empty ring → nil record → nil marker, so a claim
  #    read off it passes on the nil: an `(= x y)` where both are nil,
  #    `(not (contains? nil :digest))`, a `count` over an empty string, a
  #    `not-any?` over an empty history, a human-sentence check on a nil
  #    message.  The remedy is not a guard — the epoch egress projection (the
  #    `:kind :rf/epoch-record` arm of `rf/project-egress`) is a pure function
  #    of a record plus the frame's durable elision registry, so
  #    `core-epoch-egress-profile-test`'s rows drive a SYNTHETIC record and run
  #    in both postures.
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
  #    Clojure reader cannot read — green over exactly such a file while the
  #    lane runs that file's tests fewer.  Whether a file is
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
# green with `Ran 0 tests`.
#
# The floor is a COLLAPSE DETECTOR, not a target: it must sit close enough
# under the observed count that THE SMALLEST BATCH ANYONE LANDS IS STILL
# VISIBLE.  A floor left behind by a growing lane is not conservative, it is
# inert.  It tracks TESTS, not roster lines, because a small batch of
# namespaces can carry a large share of the lane.
#
# It is also the budget for tagging.  `^:requires-debug` is a VAR-level tag,
# and it is a way to lose coverage QUIETLY: a roster line sits in this
# reviewed file, whereas an over-broad tag is one word in a test file, and
# nothing else counts what it excluded.  So the floor keeps ~35 tests of
# slack under the observed count: tagging more than that reddens this lane
# and the author has to come here and move the number — which is exactly the
# review prompt a coverage-reducing change should trigger.  That is
# deliberate.  Do not raise the floor to make room for a tag without saying,
# in the same diff, what was tagged and why it has no production residue.
#
# THE SLACK DRIFTS WHILE NOBODY TOUCHES THE FLOOR.  The lane grows on other
# work's test files, which silently widens the tagging budget — the same "a
# floor left behind by a growing lane is inert" failure, arriving through the
# back door.  So the rule is not "raise it when you add namespaces" but "keep
# the SLACK at ~35", and it wants checking whenever this lane's observed count
# is read.
#
# LOWERING the floor is the dangerous direction.  It is safe only when the drop
# is accounted for to a namespace DELETED in the same diff rather than
# unrostered or tagged, the lane still reports `0 failures, 0 errors`, and the
# same ~35 tests of slack survive.  Lowering it further, or lowering it without
# a deletion beside it, is a floor rotting downward to meet a collapse.
#
# Note which direction the OTHER failure mode falls.  If `-e :requires-debug`
# is ever dropped from the `:prod-gate` alias or misspelled, the tagged
# namespaces run under `-Dre-frame.debug=false` and this lane goes RED, loudly.
# A lost exclusion cannot go quietly green here; only an over-applied tag can,
# and that is what the tagging budget above is for.
#
# WHAT THE FLOOR DOES NOT CATCH: a test file whose `(ns ...)` FORM is
# malformed is invisible to `cognitect.test-runner`'s namespace DISCOVERY, so
# a `-n` selector naming it matches nothing and the lane simply runs one
# namespace fewer, exit 0.  The floor catches that collapse only once enough
# tests have gone missing, and a handful of tests is well inside the headroom
# a growing lane has to leave itself.
#
# The check that catches it reads the form instead of scraping the line, so it
# belongs in a process that already has a reader and the lane's classpath:
# `re-frame.test-quiet.runner` refuses to start when any file in a discovery
# directory will not reach the runner as its own namespace.  That is the
# wrapper EVERY JVM artefact's `:test` alias invokes, so the rule covers every
# lane in the repo rather than this one, and it fires before a single test
# runs.  `verify_roster` above cannot see this class on its own — see its
# guard #1.
export RF2_MIN_TESTS="${RF2_MIN_TESTS:-1860}"

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
