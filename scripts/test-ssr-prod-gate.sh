#!/usr/bin/env bash
#
# rf2-hnrwo — run `implementation/ssr` under the REAL production gate.
#
# WHY THIS EXISTS.  `SECURITY.md` documents `-Dre-frame.debug=false` (and
# `RE_FRAME_DEBUG=false`) as the JVM/SSR production setting, and
# `re-frame.interop/debug-enabled?` reads it ONCE at namespace-load time.  Until
# rf2-f8x2i nothing in `.github/workflows` or `scripts/` had ever set it, and
# what that bead built — `scripts/test-core-prod-gate.sh` — covers
# `implementation/core` ONLY.  SSR is the artefact where the omission is most
# literal: this is the one that RUNS on a JVM in production, under exactly that
# property, and no suite had ever executed it that way.  The suites that CALL
# THEMSELVES production-gate tests rebind `interop/debug-enabled?` with
# `with-redefs` AFTER the framework has loaded, and a load-time gate is
# invisible to that.  That is not a theoretical gap: rf2-9c2jf was
# `dispatch-sync` running its handler ZERO times under the documented gate, and
# it stayed green for as long as it existed.
#
# WHY SSR, SPECIFICALLY.  rf2-u2x6w established that sub-classification — a
# PRIVACY invariant, not a diagnostic — genuinely reaches
# `re-frame.ssr.payload-policy/project-routing-egress` in production, the code
# that decides what leaves the server inside a hydration payload.  Its
# always-on witness `re-frame.ssr-routing-egress-production-test` was written
# and mutation-proved under `-Dre-frame.debug=false`, and then had nowhere to
# run: no lane reached this artefact.  This script is that lane.
#
#     bash scripts/test-ssr-prod-gate.sh          run the lane
#     bash scripts/test-ssr-prod-gate.sh --plan   print the roster, run nothing
#
# CI arm: the `jvm-ssr-prod-gate` job in `.github/workflows/test.yml`, which is
# in `all-required-passed`'s `needs:`.
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
# the `:prod-gate` alias's `:jvm-opts` (implementation/ssr/deps.edn), composed
# onto `:test` — so it is part of the LANE's definition rather than something a
# caller has to remember, and `:extra-paths` / `:extra-deps` cannot drift
# between the two lanes.  `re-frame.ssr-prod-gate-lane-pin-test` then runs
# INSIDE the lane and asserts, unconditionally, that the property reached this
# JVM and that the framework honoured it.  Without that pin a lost flag would
# not go red: this roster is by construction a subset of what already passes in
# dev posture, so the lane would go GREEN on the wrong posture — the exact
# class of false green this whole file exists to close.
#
# WHY A ROSTER AND NOT THE WHOLE SUITE.  Nobody had ever run the ssr suite
# under the real gate.  Run on 2026-07-27 it is emphatically RED: 217 failures
# and 1 error across 21 of its 48 test namespaces.  Three shapes account for
# all of it — dev-instrumentation assertions written inline with semantics,
# `data-rf2-source-coord` / `data-rf-view` annotations that are prod-elided at
# the core `reg-view` boundary, and the error-projection cluster whose status
# codes depend on the development trace listener.  The first two are legitimate
# dev-posture tests rather than defects.  The third is a documented design
# choice with a real production consequence and is under a RULING (rf2-rqje9)
# before anyone writes it off.  Either way "make the whole suite green under
# the gate" is not a fix; the triage bead is named per group below.
#
# The roster is therefore an EXCLUSION list, not an allowlist.  The polarity is
# the point: a namespace added to `implementation/ssr/test/` joins this lane BY
# DEFAULT and has to be excluded deliberately, so a new suite that breaks under
# the production gate reddens this job the day it lands.  An allowlist would
# have the opposite failure mode — silently not covering the new thing.  The
# list shrinks as rf2-lwtlk lands; when it reaches zero, this script is one
# line and the `-n` machinery goes away.

set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
artefact="$repo_root/implementation/ssr"
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
  # ── rf2-rqje9 — RULING LANDED (2026-07-27); THE DEFECT IS FIXED, THE
  #    RESIDUE IS SPELLING.  These five used to fail on assertions of the
  #    form "the buffered `:rf.error/no-such-handler` projects 404 onto
  #    `:status`", and the consequence the gate made visible was that a
  #    production server answered an unroutable URL with HTTP 200.  The
  #    ruling was NOT "intended": rf2-ov56u promoted the URL-driven route
  #    miss onto the always-on error axis and `:kind`-gated the default
  #    projector's 404 arm, so a production route miss now answers 404 for
  #    real.  `re-frame.ssr-route-miss-404-production-test` is that proof and
  #    runs IN this lane.
  #
  #    WHAT IS LEFT IS NOT THE DEFECT.  Every remaining red in these five
  #    synthesises its error straight onto the DEV trace bus
  #    (`trace/emit-error!` from a test helper, `@traces` reads) rather than
  #    driving a real emit site, so the gate empties the bus and the
  #    assertion observes nothing — dev-instrumentation spelling of exactly
  #    the shape the rest of this roster carries.  They are rf2-lwtlk group
  #    (c)'s ordinary posture split, which was sequenced to WAIT for
  #    rf2-ov56u's merge; that wait is over.  Re-run and split; do not read
  #    a red here as the 200-for-404 defect returning, and check
  #    `ssr-route-miss-404-production-test` first if you suspect it has.
  re-frame.ssr-end-to-end-test                    # 119
  re-frame.ssr-error-two-frame-attribution-test   #   5
  re-frame.ssr-error-view-failed-no-project-test  #   2
  re-frame.ssr-flush-response-result-test         #  12
  re-frame.ssr-head-resolution-no-project-test    #   2

  # ── rf2-lwtlk — SOURCE-COORD ANNOTATION.  CLEARED.  All three namespaces
  #    (`source-coord-parity-test`, `ssr-keyword-head-contract-test`,
  #    `ssr-source-coord-test`) now split their posture: the
  #    `data-rf2-source-coord` / `data-rf-view` assertions are kept verbatim
  #    inside `(when interop/debug-enabled? …)` arms, the RENDER they were
  #    entangled with is asserted posture-independently, and each gained a
  #    `when-not` arm that pins the bare bytes the REAL gate emits.  The
  #    negative annotation assertions moved into the dev arm WITH the
  #    positives: "the outer view did not stamp itself" is vacuously true
  #    where nothing stamps anything.

  # ── rf2-lwtlk — dev-instrumentation assertions written inline with the
  #    semantics they sit next to.  CLEARED for eleven namespaces: the
  #    `:rf.ssr/schema-digest-mismatch` / `-version-mismatch` traces, `:doc`
  #    on the reg-head and reg-cofx registry slots, the
  #    `:rf.ssr.head/cleanup-failed` warning, `:rf.cofx/skipped-on-platform`,
  #    the `:rf.ssr/suspense-boundary-failed` / `-duplicate-id` streaming
  #    traces, the hydration-mismatch trace's `:server-hash` /
  #    `:client-hash` tags, the EP-0001 ownership diagnostics, and the
  #    `:rf.error/malformed-hydration-payload` /
  #    `-hydration-frame-id-mismatch` rejection diagnostics.  Each is kept
  #    VERBATIM inside a `(when interop/debug-enabled? …)` arm marked
  #    `rf2-lwtlk`, with a `## Posture split` section in the ns docstring;
  #    the semantics they were entangled with now run in this lane.
  #
  #    THE LARGER HALF WAS THE ASSERTIONS THAT WERE NOT FAILING.  A NEGATIVE
  #    over the trace ring — `(is (empty? @diags))`, `(is (not-any? …
  #    (ops)))`, "no malformed diagnostic on a well-formed payload" — passes
  #    AUTOMATICALLY under this gate, where the ring is empty for every
  #    input.  Roughly two dozen such assertions moved into the dev arms
  #    alongside their positive counterparts.  Left outside they would have
  #    been the quiet half of the same false green.
  #
  #    NOTE for whoever finishes this list: a namespace whose EVERY deftest is
  #    about the dev trace has no semantic residue to run under the gate.
  #    Guarding it wholesale and deleting its roster line would report GREEN
  #    for a namespace that executed nothing — the false-green this lane
  #    exists to close.  `ssr-compatibility-checks-test` was exactly that
  #    (both fxs are best-effort: no state change, no return value, the trace
  #    IS the output), and rather than roster it off it gained two
  #    production-real deftests — the fx registrations with their
  #    `:platforms #{:client}` gate, and a proof that a DOUBLE mismatch
  #    neither throws nor stops the effect queued behind it.

  # ── rf2-bkvu5 — HELD FOR A RULING.  ONE deftest,
  #    `flow-output-schema-failure-rejects-candidate-before-install`, fails
  #    for a REAL reason: under `-Dre-frame.debug=false` a flow output that
  #    violates the registered app-db schema is NOT rejected — the invalid
  #    candidate INSTALLS, and the handler's own `:db` installs with it.
  #    That is not trace spelling.  `router.cljc`'s `validate-event!` and
  #    `schemas/validate.cljc` both document it: every `validate-*!` body
  #    sits inside its own `(if interop/debug-enabled? … true)` gate and
  #    "Production builds return `true` unconditionally".  So `reg-app-schema`
  #    is a no-op in production and the EP-0025 atomic-candidate-rejection
  #    contract is a DEV-POSTURE contract.  Adjacent to rf2-rqje9 in class —
  #    a documented choice whose consequence nobody had executed — but a
  #    different surface, and none of rf2-rqje9's three candidate shapes
  #    addresses it, so it has its own bead.
  #
  #    Every OTHER assertion in this namespace has already been posture-split
  #    by rf2-lwtlk (the `by-op` / `ops` trace signatures are in dev arms, and
  #    two flow-eval witnesses were added off the trace bus so "the flow
  #    computed its bad output" and "the flow threw" survive the gate).  The
  #    line below therefore comes out with a one-line change once rf2-bkvu5
  #    lands.  Do not split that one deftest before it does.
  re-frame.flows-integration-test                 #   2

  # ── rf2-lwtlk / rf2-76gom — the conformance corpus.  CLEARED, and it did
  #    NOT clear by guarding.  Nine `ssr-*.edn` fixtures used to fail here,
  #    all nine on `:trace-emissions` claims read off the DEV bus, and two
  #    of them ALSO reported `public-error actual: nil`.
  #
  #    The public-error half was never a framework fault: the runner's
  #    `pe-check` sourced its error event from `@traces` before feeding
  #    `ssr/project-error`, so under the gate it reported nil whatever the
  #    framework did — `:ssr/error-known-mapping` did NOT clear with
  #    rf2-ov56u for that reason, contrary to the rf2-rqje9 ruling's
  #    expectation.  rf2-76gom re-sourced it from the ALWAYS-ON `:errors`
  #    axis, synthesised into the projector's envelope by the same generic
  #    lift `error-emit-projection-listener` performs, so both fixtures now
  #    adjudicate the wire under the gate.  Mutation-proved: remove the
  #    fallback and exactly those two go red here.
  #
  #    The `:trace-emissions` half kept its dev-bus assertions VERBATIM in a
  #    dev arm AND gained a production counterpart rather than a hole: a
  #    `{:operation :rf.event/run-start :tags {:rf.trace/event-id X}}` claim
  #    says event X RAN, which the always-on `:events` substrate records in
  #    production, so all 17 such claims are adjudicated under BOTH postures
  #    (mutation-proved: stub the capture and nine fixtures go red).
  #    `:trace-not-emitted` moved into the dev arm with them — a negative
  #    over an empty ring passes with the runtime removed.

  # ── rf2-lwtlk — the PAYLOAD-POLICY suite.  CLEARED, and the roster note
  #    that asked for verification rather than assumption was answered:
  #    the single red assertion was the `:rf.ssr/invalid-version` WARNING
  #    trace, the dev half of a rejection whose PRODUCTION half — a semver
  #    string never reaching `:rf/version`, which falls back to the integer
  #    v1 — was green under the gate throughout.  Nothing that decides what
  #    egresses was involved.  The always-on privacy witness for the egress
  #    proper, `re-frame.ssr-routing-egress-production-test` (rf2-u2x6w),
  #    has been in this lane and green since it was built.
)

# ---------------------------------------------------------------------------
# Roster derivation
# ---------------------------------------------------------------------------

# Every namespace DECLARED under implementation/ssr/test/, read from the `(ns
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
printf '==> implementation/ssr under the REAL production gate\n'
printf '    jvm property : -Dre-frame.debug=false (implementation/ssr/deps.edn, :prod-gate :jvm-opts)\n'
printf '    posture pin  : re-frame.ssr-prod-gate-lane-pin-test (red if the property did not arrive)\n'
printf '    namespaces   : %s of %s (%s excluded as known-red — rf2-lwtlk, rf2-rqje9)\n' \
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
# RAISED 215 -> 380 by rf2-lwtlk.  The original figure was calibrated against
# 247 observed tests across 28 namespaces.  The roster has since shrunk from
# 21 entries to 7, and the lane runs 436 tests / 2014 assertions across 42 —
# so 215 had stopped being a floor and become a formality: the lane could have
# lost HALF its namespaces and still cleared it.  380 restores the original
# ~13% headroom against the current observed count.  Raise it again as the
# remaining seven come off.
export RF2_MIN_TESTS="${RF2_MIN_TESTS:-380}"

args=()
for ns in $runnable; do
  args+=(-n "$ns")
done

cd "$artefact"
if ! clojure -M:test:prod-gate "${args[@]}"; then
  printf '\nFAIL implementation/ssr under -Dre-frame.debug=false\n' >&2
  printf 'repro: bash scripts/test-ssr-prod-gate.sh\n' >&2
  printf 'A namespace that is green in `clojure -M:test` and red here is asserting\n' >&2
  printf 'DEV INSTRUMENTATION, or it is a genuine production defect (rf2-9c2jf was\n' >&2
  printf 'the latter). Decide which before touching the known_red roster.\n' >&2
  exit 1
fi

printf 'PASS implementation/ssr under -Dre-frame.debug=false (%s namespaces)\n' "$runnable_count"
