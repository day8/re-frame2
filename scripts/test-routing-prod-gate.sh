#!/usr/bin/env bash
#
# Run `implementation/routing` under the REAL production gate.
#
# WHY THIS EXISTS.  `SECURITY.md` documents `-Dre-frame.debug=false` (and
# `RE_FRAME_DEBUG=false`) as the JVM/SSR production setting, and
# `re-frame.interop/debug-enabled?` reads it ONCE at namespace-load time.
# `scripts/test-core-prod-gate.sh` covers `implementation/core` ONLY, so every
# other artefact needs a lane of its own to be executed under the documented
# production configuration; this is routing's.
# The suites that CALL THEMSELVES production-gate tests rebind
# `interop/debug-enabled?` with `with-redefs` AFTER the framework has loaded,
# and a load-time gate is invisible to that.  That is not a theoretical gap:
# a `dispatch-sync` that ran its handler ZERO times under the documented gate
# would stay green in every such suite.
#
# WHY ROUTING, SPECIFICALLY.  Sub-classification — a PRIVACY invariant, not a
# diagnostic — genuinely egresses in production from routing's
# `:routing/route-sub-egress-path`.  Its always-on witness
# `re-frame.routing-sub-egress-production-test` asserts that under
# `-Dre-frame.debug=false`, and this script is the lane that runs it.
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
# WHY A ROSTER.  What goes red under the real gate is almost always a test
# asserting DEV INSTRUMENTATION — a `:rf.route/*` trace fired, an `:errors`
# sink received, `:doc` / `:source` retained on a route-algebra node — inline
# with the semantics it is really about.  Under `-Dre-frame.debug=false` the
# framework does not emit any of that, by design, so those are legitimate
# dev-posture tests rather than defects.  Such a namespace is split — its
# semantics run in both postures, its trace readings sit inside a posture arm,
# and a "## Posture split" ns docstring says which is which — and the roster
# holds it out of this lane until it is.
#
# The roster is an EXCLUSION list, not an allowlist.  The polarity is
# the point: a namespace added to `implementation/routing/test/` joins this lane
# BY DEFAULT and has to be excluded deliberately, so a new suite that breaks
# under the production gate reddens this job the day it lands.  An allowlist
# would have the opposite failure mode — silently not covering the new thing.
#
# THE ROSTER IS EMPTY: every one of this artefact's test namespaces runs under
# the gate.  The `-n` machinery is what makes the exclusion polarity above
# real, so the next namespace that goes red under the gate has a documented
# place to be rostered — with a bead — instead of quietly reddening the job
# forever.

set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

# First line names the tree this run resolved: a relative invocation resolves
# `${BASH_SOURCE[0]}` against the shell's actual cwd, so a backgrounded gate
# can silently run in, and grade, another worktree.  Invoke backgrounded gates
# by ABSOLUTE path.
printf 'gate root: %s\n' "$repo_root"

artefact="$repo_root/implementation/routing"
test_root="$artefact/test"

# ---------------------------------------------------------------------------
# The known-red roster.
#
# Every entry is a namespace that FAILS under `-Dre-frame.debug=false`,
# grouped by why, with its red-assertion count; the group names the bead that
# clears it.  An entry that does not name a real namespace is a hard error
# (see `verify_roster` below), so a rename cannot leave a stale exclusion
# quietly suppressing coverage.
# ---------------------------------------------------------------------------
known_red=(
  # EMPTY.  Before rostering a namespace here, triage it.  Under the gate the
  # trace bus is silent by design, so what reads as a failure is usually a
  # dev-instrumentation assertion inline with real semantics, and what reads
  # as a pass can be vacuous.  What that triage finds, in this artefact:
  #
  #    * A NEGATIVE over the trace ring passes VACUOUSLY — `not-any?
  #      :rf.error/no-such-handler`, `(is (empty? warnings))`,
  #      `(not (re-find #"SECRET100" (pr-str tags)))` over a nil trace
  #      payload — and a privacy or leak-suppression one certifies a guarantee
  #      the framework never executed.  Put it inside the posture arm and
  #      assert the always-on witness outside it: the PURE fn the emit site
  #      calls (`resolver/plan-trace-tags`, `address/classify`,
  #      `classification/unpromoted-query-keys`, `match-url`), the declaration
  #      the trace reports (an fx's `:platforms #{:client}`), or the app-db /
  #      runtime-db state the behaviour actually is.
  #
  #    * A LOUD REJECTION usually survives production and only its CHANNEL is
  #      dev-only.  `address/classify` and the event-shape gate run
  #      unconditionally and return `{}` from the handler, so a malformed
  #      navigation request leaves the slice untouched and pushes no URL in
  #      both postures — only the `:rf.error/navigate-bad-request` diagnostic
  #      beside them goes quiet.  Likewise stale nav-token suppression is
  #      ENFORCEMENT, not advice: the superseded completion's app
  #      `:rf/reply-to` target is never dispatched, so app-db and runtime-db
  #      are unchanged in either posture, and only what is SPELLED on the
  #      `:rf.route.nav-token/stale-suppressed` trace is dev-only.  And a
  #      stubbed late-bound hook (`:routing/on-route-prefetch`'s `@calls`
  #      atom) is not a trace at all, so it is production-visible.
  #
  #    * The `:sensitive` RETENTION (`rf/handler-meta`, both public arities),
  #      the pure carrier scrub (`egress/redact-url-carriers` /
  #      `redact-url-tag`), the in-process rawness, the lowering / re-rooting
  #      into the elision registry and the real SSR `payload-policy` consumer
  #      are production-real and run under the gate.  Only readings OFF THE
  #      TRACE BUS are dev-gated.
  #
  #    * A namespace whose EVERY deftest is about the dev trace has no
  #      semantic residue to run under the gate.  Guarding it wholesale and
  #      leaving it off this roster would report GREEN for a namespace that
  #      executed nothing — the false-green this lane exists to close.  Those
  #      want a var-level tag the lane excludes, not a posture guard.
  #      `routing-framework-authority-test` is the closest thing in this
  #      artefact: FIVE of its six deftests assert `(is (empty? @warns))` over
  #      the `:rf.warning/app-handler-runtime-effect` trace, and the sixth is
  #      the control that proves the other five are not vacuous, so all six
  #      sit inside the posture arm.  It is in the lane because the navigation
  #      SCAFFOLDING each case drives — the route commits, the pending slot
  #      fills and clears, `:rf.route/continue` completes — is real runtime-db
  #      state that does execute.  Be clear-eyed about the trade: under the
  #      production gate this namespace contributes routing semantics, not the
  #      ownership contract it is named for.
  #
  #    * An ACCEPT where dev rejects is not automatically a defect — read this
  #      before assuming the next look-alike is one.  `(is (false?
  #      (validate-through-hook …)))` returns TRUE under the gate, which reads
  #      exactly like a production defect and is not:
  #      `re-frame.schemas.validate/validate-fx!` is literally
  #      `(if interop/debug-enabled? (run-validation …) true)`, per Spec 010
  #      §Production builds: the per-step `validate-*!` hot-path fns are
  #      dev-only and production-build validation is the OPT-IN boundary
  #      flag `:boundary? true`, which routes through
  #      `validate-with-registered-fn` outside the gate.  That short-circuit
  #      also makes a POSITIVE control pass for the wrong reason — `true`
  #      because validation did not run, not because the args conform.  The
  #      always-on replacement is `m/validate` against the LIVE registration's
  #      `:schema`: same schema the hook consults, no gate between the call
  #      and the verdict.
  #
  #    Do not exclude a namespace whose always-on witnesses have no other
  #    home: `re-frame.routing-sub-egress-production-test` runs IN the lane.
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
# The calibration is ~87% of the observed count.  A floor far below what it
# guards is a formality: a roster collapse to a fraction of the lane would
# still report green.  With no exclusions left, this is the only thing
# standing between a `-n` list that matched nothing and a green report.
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
