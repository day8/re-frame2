#!/usr/bin/env bash
#
# rf2-bo8lq — run `implementation/epoch` under the REAL production gate.
#
# WHY THIS EXISTS.  `spec/Security.md` documents `-Dre-frame.debug=false` (and
# `RE_FRAME_DEBUG=false`) as the JVM/SSR production setting, and
# `re-frame.interop/debug-enabled?` reads it ONCE at namespace-load time.  Four
# artefacts had a lane that genuinely set it — core, routing, ssr, freehand —
# and epoch did not, so the suite whose SUBJECT is epoch's production posture
# was executed by no lane under that posture.
#
# WHAT THE PRE-EXISTING SUITE DID AND DID NOT COVER.  Stated precisely, because
# the loose version of this sentence is what made the gap hard to see.
# `implementation/epoch/test/re_frame/epoch_jvm_prod_gate_test.clj` rebinds
# `re-frame.interop/debug-enabled?` with `with-redefs`, and epoch reads the gate
# only as `(when interop/debug-enabled? …)` inside fn bodies — a runtime Var
# deref — so those rebinds DO reach epoch's own gated branches.  That suite was
# never vacuous, and this lane does not replace it.
#
# What a rebind cannot reach is everything the framework decided while it
# LOADED, under the dev default, before any test body ran: top-level
# registrations, `defonce` initialisation, interceptor chains composed once at
# load.  rf2-9c2jf lived in exactly that half — a `dispatch-sync` that ran its
# handler ZERO times under the documented gate, green for as long as it
# existed.  This lane executes that half.
#
# WHY EPOCH, SPECIFICALLY.  The gate's whole security rationale (rf2-vnjfg /
# rf2-0la4f) is that the epoch ring must NOT retain `:db-before` / `:db-after` /
# raw `:trace-events` in a production server's heap.  The suite asserting that
# retention is elided was the one suite that never ran in the posture it is
# about.
#
#     bash scripts/test-epoch-prod-gate.sh          run the lane
#     bash scripts/test-epoch-prod-gate.sh --plan   print the roster, run nothing
#
# CI arm: the `jvm-epoch-prod-gate` job in `.github/workflows/test.yml`, which
# is in `all-required-passed`'s `needs:`.
#
# HOW THE FLAG GETS THERE, AND HOW YOU KNOW IT ARRIVED.  The property lives in
# the `:prod-gate` alias's `:jvm-opts` (implementation/epoch/deps.edn), composed
# onto `:test` — so it is part of the LANE's definition rather than something a
# caller has to remember, and `:extra-paths` / `:extra-deps` cannot drift
# between the two lanes.  `re-frame.epoch-prod-gate-lane-pin-test` then runs
# INSIDE the lane and asserts, unconditionally, that the property reached this
# JVM and that the framework honoured it.
#
# THAT PIN CARRIES MORE WEIGHT HERE THAN IN ANY SIBLING LANE, and the reason is
# the measurement below rather than an argument.  Epoch's runtime is
# `debug-enabled?`-gated throughout, so nearly every assertion this lane makes
# is an assertion of ABSENCE — no record in the ring, no listener callback,
# `restore-epoch!` returning `false`, an empty projected history.  Absence is
# exactly what a lane running in the WRONG posture would ALSO observe, for the
# opposite reason: a dev-posture JVM that dispatched nothing has an empty ring
# too.  Without the pin, a lost flag would not weaken this lane's greens, it
# would INVERT what they mean.
#
# WHY THE ROSTER IS SO LOPSIDED, AND WHY THAT IS STRUCTURAL RATHER THAN DEBT.
# Measured 2026-08-15, every one of this artefact's 26 JVM test namespaces run
# ALONE under a real `-Dre-frame.debug=false`, 150s ceiling each:
#
#     RED  22      GREEN  3      TIMEOUT  1
#
# (dev-posture control, whole suite in one JVM: 539 tests / 7133 assertions,
# 0 failures, 53s.)
#
# Note `epoch-jvm-prod-gate-test` is counted RED above and is nonetheless IN
# this lane: exactly 2 of its 12 deftests failed, both dev-parity sanity checks,
# and both now carry core's existing `^:requires-debug` tag (rf2-d2841) which
# the `:prod-gate` alias excludes.  The other ten run here.
#
# That ratio is the one way epoch differs from core / routing / freehand / ssr.
# Those artefacts have production-real semantics that must SURVIVE the gate, so
# their exclusion lists shrink over time — routing's reached empty.  Epoch's
# production posture is that the artefact is OFF: `implementation/epoch/
# deps.edn`'s own header says its runtime paths are gated on
# `interop/debug-enabled?` precisely so production builds can eliminate them.  A
# suite exercising epoch's recording, restore, projection, redaction, silencing
# or attribution behaviour therefore CANNOT run under this gate — there is no
# behaviour to exercise — and its red is not a defect and not triage debt.  What
# belongs in this lane is the NEGATIVE claim: with the gate off, nothing is
# recorded, nothing fires, nothing is retained.
#
# The roster is still an EXCLUSION list, and the polarity is still the point: a
# namespace added to `implementation/epoch/test/` joins this lane BY DEFAULT and
# has to be excluded deliberately, so a new suite that quietly starts retaining
# state under the production gate reddens this job the day it lands.  An
# allowlist would have the opposite failure mode.
#
# TRIAGED (rf2-t7qh8, 2026-08-15) AND LEFT AT 22.  Every entry was classified,
# and the classes are recorded beside the entries below: 15 subject-elided, 6
# publication-elided, 1 non-terminating.  None was incidental, so the roster did
# not shrink and is not expected to.  The count is not the health metric here —
# a shrinking roster would mean epoch had acquired production behaviour, which
# is the opposite of its design.  What triage DID change is in the runnable
# half, not this list: see the note above `known_red`.
#
# BEFORE YOU ADD A LINE: a namespace that is red here is asserting epoch's DEV
# behaviour, or it is a genuine production defect (rf2-9c2jf was the latter).
# Decide which, and say which, in the comment beside the entry.

set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

# First line names the tree this run resolved — see scripts/test-fast-pr.sh
# (rf2-g2mxd): a relative invocation resolves `${BASH_SOURCE[0]}` against the
# shell's actual cwd, so a backgrounded gate can silently run in, and grade,
# another worktree.  Invoke backgrounded gates by ABSOLUTE path.
printf 'gate root: %s\n' "$repo_root"

artefact="$repo_root/implementation/epoch"
test_root="$artefact/test"

# ---------------------------------------------------------------------------
# The known-red roster.
#
# Every entry FAILS under `-Dre-frame.debug=false` today, with its measured
# count (2026-08-15, each namespace run alone, failures/assertions).  An entry
# that no longer names a real namespace is a hard error (see `verify_roster`
# below), so a rename cannot leave a stale exclusion quietly suppressing
# coverage.
# ---------------------------------------------------------------------------
# rf2-t7qh8 TRIAGED THIS ROSTER AND CHANGED NO ENTRY.  All 22 fall into three
# classes, below; NONE is the "could run, nobody tried" class, so the roster has
# no incidental members and nothing to shrink.  The discriminator that decides
# every case is one question — DOES THE GATE ERASE WHAT THE SUITE IS ABOUT? — and
# the two green projection suites are what make it sharp rather than rhetorical.
# `epoch-egress-resource-scope-test` and the projection suites in class A test
# the SAME transform; the green one SYNTHESISES its record as a literal, the red
# ones dispatch and read theirs back out of the ring.  Projection is ungated
# (tool_pair.cljc — the gate elides record ASSEMBLY, not record PROJECTION), so
# what fails is never the subject, it is the suite's ability to obtain one.
#
# THE ONE THING TRIAGE FOUND WORTH DOING was not on this list at all.  It was in
# the lane's RUNNABLE half: six of `epoch-jvm-prod-gate-test`'s ten lane-running
# deftests asserted an absence with no witness that the dispatch they reason
# about had happened — rf2-9c2jf's exact shape, inside a required job.  Each now
# reads its handler's app-db write back before asserting the absence.  That is
# the VACUOUS-PASS class rf2-o5dbf kept finding in routing, and for this artefact
# it lives among the greens, not among the reds.
known_red=(
  # ── CLASS A · SUBJECT ELIDED (17.  The 2026-08-15 triage below counted 15
  #    and the number was not kept up as entries landed; it is recounted here
  #    rather than restated.  `excluded` on the run's own summary line is the
  #    authority either way — it counts the array.)  These suites obtain
  #    their subject by
  #    dispatching and reading the ring back — recording, restore, replay,
  #    capture-buffer and projection-of-a-recorded-record.  Under the gate the
  #    ring never fills, so there is no subject to assert about; see the header
  #    and rf2-vnjfg / rf2-0la4f for why that is the intended posture.  NOT debt:
  #    re-including one would require inventing behaviour the artefact is
  #    designed not to have in production.  DISPOSITION: correct as-is.
  re-frame.actor-revertibility-restore-test              #  21 /   44
  re-frame.epoch-attribution-test                        # 107 /  208
  #    rf2-f8wu post-merge audit — the `:depth` transition serialization.
  #    Its subject is a writer PARKED INSIDE `record!`: under the gate
  #    `record!` is never reached, so the park never arms and the suite has
  #    nothing to observe.  Class A, and dev-only by construction rather
  #    than by choice.
  re-frame.epoch-depth-transition-race-test              #   2 /   14
  re-frame.epoch-drain-serialization-test                #  13 /   26
  re-frame.epoch-egress-redaction-cljs-test              #  62 /  117
  re-frame.epoch-egress-resource-trace-test              # 145 /  581
  re-frame.epoch-egress-trace-events-test                #   6 /   49
  re-frame.epoch-mcp-egress-conformance-test             #  89 /  141
  re-frame.epoch-override-capture-test                   #   6 /   13
  re-frame.epoch-privacy-test                            #  83 /  134
  re-frame.epoch-redact-fn-projection-test               #  23 /   45
  re-frame.epoch-redact-fn-test                          #  42 /   66
  re-frame.epoch-run-cause-test                          #  20 /   27
  re-frame.epoch-test                                    # 428 /  752
  re-frame.join-strict-mint-epoch-replay-test            #  17 /   29
  re-frame.machine-minted-cofx-replay-token-test         #   6 /    9
  #    rf2-ov144 — `replay-epoch!` by id. Dispatches, reads the ring back
  #    and re-dispatches from a retained record; under the gate the ring
  #    never fills and `replay-epoch!` returns `false` by design (pinned in
  #    `epoch-jvm-prod-gate-test` / `epoch-elision-prod-test`). Class A.
  re-frame.epoch-replay-cljs-test

  # ── CLASS B · PUBLICATION ELIDED (6).  Different mechanism, same verdict, and
  #    worth separating because the failure ratios invite the wrong conclusion —
  #    `lineage-285` fails 68 of 3608 assertions, which reads like a suite that
  #    almost runs.  It nearly does: the LEDGER these suites drive
  #    (`re-frame.epoch.state`) carries no `debug-enabled?` gate at all, so its
  #    arithmetic is posture-INDEPENDENT and its thousands of assertions pass
  #    here for the same reason they pass in dev.  What the gate erases is the
  #    PUBLICATION — `listeners.cljc`'s `on-frame-destroyed!` and the fan-out
  #    around it are gated — and the emitted silencing trace is the observable
  #    every one of these suites is actually about.
  #
  #    So splitting them would buy assertions, not coverage: a posture-
  #    independent path re-run in a second posture reports what the dev lane
  #    already reported, at the cost of permanent per-deftest tagging and, for
  #    `lineage-285` and `lineage-aba`, concurrency latches in a required job.
  #    The lane already carries two ungated-ledger suites under the real gate
  #    (`silence-decision-atomicity`, `silencing-emit-deadlock`), which is what
  #    covers the load-time risk; four more of the same shape add cost, not
  #    information.  DISPOSITION: correct as-is.
  re-frame.epoch-silence-contract-test                   #   6 /   21
  re-frame.epoch-silence-receiver-public-api-test        #   7 /   12
  re-frame.epoch-silencing-generation-emission-test      #   4 /   13
  re-frame.epoch-silencing-lineage-285-test              #  68 / 3608
  re-frame.epoch-silencing-lineage-aba-test              #   7 /   19
  re-frame.epoch-silencing-same-generation-rearm-test    #  17 /   34

  # ── CLASS C · HAZARDOUS: DOES NOT TERMINATE (1).  A different fact from "red"
  #    and recorded as such.  Its stress loops wait on epoch ids the no-op floor
  #    never mints, so each iteration burns its full budget.  RE-MEASURED under
  #    rf2-t7qh8, this namespace ALONE, on the same box minutes apart:
  #
  #        dev posture   7 tests / 929 assertions, 0 failures, exit 0 —   21s
  #        under gate    no summary line, no verdict, killed at a 200s bound
  #
  #    The shape is worse than the ratio suggests.  It emits a ~267KB burst of
  #    failures in its first half-minute and then produces NOTHING for the rest
  #    of the bound — from outside, indistinguishable from a healthy long run.
  #    A lane that WEDGES is worse than one that reds: it consumes the job's
  #    whole timeout and reports no verdict at all.  DISPOSITION: stays excluded
  #    on cost grounds, permanently, independent of how A and B are read.  This
  #    is the highest-risk line in the file to re-include — do not.
  re-frame.epoch-concurrency-stress-test
)

# ---------------------------------------------------------------------------
# Roster derivation
# ---------------------------------------------------------------------------

# Every namespace DECLARED under implementation/epoch/test/, read from the
# `(ns ...)` form rather than derived from the path — a lane selector is applied
# by the runner to the declared name, so that is the name that has to match.
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
printf '==> implementation/epoch under the REAL production gate\n'
printf '    jvm property : -Dre-frame.debug=false (implementation/epoch/deps.edn, :prod-gate :jvm-opts)\n'
printf '    posture pin  : re-frame.epoch-prod-gate-lane-pin-test (red if the property did not arrive)\n'
printf '    namespaces   : %s of %s (%s excluded — epoch is OFF under this gate; see the header + rf2-t7qh8)\n' \
  "$runnable_count" "$total_count" "$excluded_count"

if [ "${1:-}" = "--plan" ]; then
  printf '    runnable:\n'
  printf '      %s\n' $runnable
  printf '    excluded:\n'
  printf '      %s\n' $excluded
  exit 0
fi

# The coverage floor.  `re-frame.test-quiet.runner` reds any lane that executed
# fewer than RF2_MIN_TESTS tests, so a roster that collapsed — a renamed
# directory, an `-n` list that matched nothing — cannot report itself green with
# `Ran 0 tests`.  This lane runs 24 tests: 12 from the three green rostered
# namespaces, 10 from `epoch-jvm-prod-gate-test` (12 minus its two
# `^:requires-debug` deftests), and 2 from the pin.  20 is that with room for
# ordinary churn, the same ~85% ratio scripts/test-routing-prod-gate.sh settled
# on.  Raise it when the roster grows materially.
export RF2_MIN_TESTS="${RF2_MIN_TESTS:-20}"

args=()
for ns in $runnable; do
  args+=(-n "$ns")
done

cd "$artefact"
if ! clojure -M:test:prod-gate "${args[@]}"; then
  printf '\nFAIL implementation/epoch under -Dre-frame.debug=false\n' >&2
  printf 'repro: bash scripts/test-epoch-prod-gate.sh\n' >&2
  printf 'A namespace that is green in `clojure -M:test` and red here is asserting\n' >&2
  printf 'epoch DEV BEHAVIOUR, which under this gate does not happen at all, or it\n' >&2
  printf 'is a genuine production defect (rf2-9c2jf was the latter). Decide which\n' >&2
  printf 'before touching the known_red roster, and say which in its comment.\n' >&2
  exit 1
fi

printf 'PASS implementation/epoch under -Dre-frame.debug=false (%s namespaces)\n' "$runnable_count"
