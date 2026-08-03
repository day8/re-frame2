#!/usr/bin/env bash
#
# rf2-8alkj — run `implementation/freehand` under the REAL production gate.
#
# WHY THIS EXISTS.  `SECURITY.md` documents `-Dre-frame.debug=false` (and
# `RE_FRAME_DEBUG=false`) as the JVM/SSR production setting, and
# `re-frame.interop/debug-enabled?` reads it ONCE at namespace-load time.
# rf2-f8x2i built the first such lane (`implementation/core`) and rf2-hnrwo
# added `routing` and `ssr`; Freehand — 132 test namespaces, the whole EP-0036
# view substrate — was still executed by NO suite under the documented
# production configuration.  The suites that CALL THEMSELVES production-gate
# tests rebind `interop/debug-enabled?` with `with-redefs` AFTER the framework
# has loaded, and a load-time gate is invisible to that.  That is not a
# theoretical gap: rf2-9c2jf was `dispatch-sync` running its handler ZERO times
# under the documented gate, and it stayed green for as long as it existed.
#
# WHY FREEHAND, SPECIFICALLY.  Two of this artefact's load-bearing mechanisms
# are ABOUT the production posture rather than merely subject to it:
#
#   * the `{:reactive false}` opt-out (rf2-oxlpy) is a SAFETY MECHANISM whose
#     production behaviour is the thing under test.  rf2-3slzz's totality probe
#     (`reactive-false-totality-jvm-test`) was deliberately written
#     posture-agnostic — it reads `System/getProperty` and asserts the live
#     `interop/debug-enabled?` matches — so a second run genuinely IS the
#     production posture rather than the same one twice.  CI only ever ran the
#     dev one.
#   * `re-frame.freehand.evidence` exists in order to be COMPILED AWAY in a
#     release build.  PR #7177 closed the CLJS half of this gap
#     (`cljs-freehand-evidence-elision` now arms for `implementation/freehand/*`
#     and the browser prod-elision suite runs 120 tests / 526 assertions).  This
#     script is the JVM half.
#
#     bash scripts/test-freehand-prod-gate.sh          run the lane
#     bash scripts/test-freehand-prod-gate.sh --plan   print the roster, run nothing
#
# CI arm: the `jvm-freehand-prod-gate` job in `.github/workflows/test.yml`,
# which is in `all-required-passed`'s `needs:`.
#
# WHY A SEPARATE SCRIPT AND NOT A FLAG ON `test-core-prod-gate.sh`.  Each
# roster is artefact-specific and load-bearing — `verify_roster` hard-errors on
# an entry naming no live namespace, so several artefacts' triage debt in one
# list means a rename in one artefact fails a list another artefact owns.  The
# flag lives in a per-artefact `:prod-gate` alias's `:jvm-opts`, and the `:test`
# alias's own shape differs per artefact.  Above all, the EXCLUSION polarity
# below only holds when the roster and the namespace set live in the same
# artefact: an allowlist reaching across artefacts has the opposite failure
# mode.
#
# HOW THE FLAG GETS THERE, AND HOW YOU KNOW IT ARRIVED.  The property lives in
# the `:prod-gate` alias's `:jvm-opts` (implementation/freehand/deps.edn),
# composed onto `:test` — so it is part of the LANE's definition rather than
# something a caller has to remember, and `:extra-paths` / `:extra-deps` cannot
# drift between the two lanes.  `re-frame.freehand.prod-gate-lane-pin-test` then
# runs INSIDE the lane and asserts, unconditionally, that the property reached
# this JVM and that the framework honoured it.  Without that pin a lost flag
# would not go red: this roster is by construction a subset of what already
# passes in dev posture, so the lane would go GREEN on the wrong posture — the
# exact class of false green this whole file exists to close.  The pin's teeth
# were proved by inversion (rf2-8alkj): running the lane with
# `-Dre-frame.debug=true` reds both of its deftests and nothing else.
#
# WHY A ROSTER AND NOT THE WHOLE SUITE.  Nobody had ever run the Freehand suite
# under the real gate.  The known-red list below is what that first run found,
# with the reason per group.  The roster is an EXCLUSION list, not an
# allowlist.  The polarity is the point: a namespace added to
# `implementation/freehand/test/` joins this lane BY DEFAULT and has to be
# excluded deliberately, so a new suite that breaks under the production gate
# reddens this job the day it lands.  An allowlist would have the opposite
# failure mode — silently not covering the new thing.  The list shrinks as the
# triage beads land; when it reaches zero, the `-n` machinery still STAYS (see
# `test-routing-prod-gate.sh`, whose roster is already empty): it is what makes
# the exclusion polarity real, so the next namespace that goes red under the
# gate has a documented place to be rostered — with a bead — instead of quietly
# reddening the job forever.
#
# NEVER DELETE OR WEAKEN AN ASSERTION TO REACH GREEN.  Roster the namespace
# with a reason and file the bead.  The red count is a triage backlog, not an
# embarrassment — and if a namespace fails for a REAL reason, that is the whole
# point of the lane (rf2-9c2jf, rf2-rqje9 and rf2-bkvu5 all reached the
# operator intact that way).
#
# AND WATCH FOR THE VACUOUS PASS WHEN A LINE COMES OFF.  Four shapes pass under
# this gate for the wrong reason: a NEGATIVE over an empty trace/evidence ring;
# a namespace that is 100% dev instrumentation (guarding it wholesale and
# deleting its roster line reports GREEN for a namespace that executed
# nothing); a POSITIVE whose subject the gate short-circuits to a constant; and
# an ABSENCE assertion about a key the gate elides wholesale.  The general
# test: what would this assertion do if its subject simply never existed in
# this posture?  If the answer is "pass", it is vacuous.

set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

# First line names the tree this run resolved — see scripts/test-fast-pr.sh
# (rf2-g2mxd): a relative invocation resolves `${BASH_SOURCE[0]}` against the
# shell's actual cwd, so a backgrounded gate can silently run in, and grade,
# another worktree.  Invoke backgrounded gates by ABSOLUTE path.
printf 'gate root: %s\n' "$repo_root"

artefact="$repo_root/implementation/freehand"
test_root="$artefact/test"

# ---------------------------------------------------------------------------
# The known-red roster.
#
# Every entry is a namespace that FAILS under `-Dre-frame.debug=false` today,
# grouped by why.  Each group names the bead that clears it.  An entry that no
# longer names a real namespace is a hard error (see `verify_roster` below), so
# a rename cannot leave a stale exclusion quietly suppressing coverage.
# ---------------------------------------------------------------------------
known_red=(
  # ── rf2-74a89 — THE DEV-ONLY TOOLING SUBSTRATE.  The four big entries, and
  #    the least ambiguous: every structure these namespaces read is BUILT
  #    only inside `interop/debug-enabled?`, so under the gate the read
  #    answers nil / empty and the assertion observes nothing.  This is not
  #    inference — each source says so itself: `occurrences.cljc`'s docstring
  #    ("Every call site is inside `re-frame.interop/debug-enabled?`"),
  #    `cell.cljc`'s evidence seam (the gate stands alone as the body's
  #    outermost form, lines ~1157 and ~1233 — written that way so Closure
  #    folds it), and `tool.cljc`'s five gated reads.  These are legitimate
  #    dev-posture tests, not defects.
  #
  #    WATCH THE VACUOUS PASS ON THE WAY OUT.  Several of these are close to
  #    100% dev instrumentation.  Guarding one WHOLESALE and deleting its
  #    roster line would report GREEN for a namespace that executed nothing —
  #    the false green this lane exists to close.  Those want a var-level tag
  #    the lane excludes, or a production-real counterpart added in the same
  #    pass (the `ssr-compatibility-checks-test` treatment), not a blanket
  #    posture guard.
  #
  #    ALL FOUR CLEARED 2026-07-28 (rf2-74a89).  Each keeps every record /
  #    row / escape / read assertion VERBATIM inside a
  #    `(when interop/debug-enabled? …)` arm, NEGATIVES INCLUDED, and each
  #    gained the production-real counterpart this note asks for — stated as
  #    a POSITIVE fact rather than an absence, because "nothing happened" is
  #    what a vacuous pass says too:
  #
  #      * `evidence-seam` / `occurrence-index` assert the CELL LIFECYCLE
  #        both surfaces are a projection OF — `:published` / `:abandoned`,
  #        `:connected` / `:disconnected`, the published dependency set and
  #        committed frame, the advanced generation of a compatible reload —
  #        outside the arm.  Beside them: an installed sink is called ZERO
  #        times, a throwing sink is UNREACHABLE rather than merely
  #        contained, a record the evidence schema would refuse is not a
  #        production failure mode at all because none is built, and
  #        `the-index-costs-a-production-build-nothing` holds two live
  #        occurrences across a read and finds no rows — not tidiness, but
  #        unbounded growth avoided in the one build where nothing reads it.
  #      * `tool-reads` was the largest entry and had the cleanest answer:
  #        `v/manifest` is the ONE read `tool.cljc` exempts from the gate,
  #        and it is byte-identical in both postures, because the analyzer
  #        ran at macro expansion.  The three id-taking reads are
  #        PROJECTIONS of it, so the census itself —
  #        two subscription sites, four event sites, the five per-site facts
  #        rf2-z0blg made the manifest publish, and the interpreted
  #        declaration's absent analysis — is now asserted at its source in
  #        both postures.  `the-index-holds-one-row-per-declaration-not-a-
  #        history` needed no arm at all: `registry/register!` is ungated,
  #        only the CALL SITE in the expansion carries the gate.
  #      * `explain-render` is the most one-sided, and its docstring says so
  #        rather than dressing it up: the read is gated at the door and both
  #        structures it folds are dev-only.  It earns its place on the
  #        CORRELATED COMMIT PATH — `connect!` binds `trace/*handler-scope*`
  #        and commits inside a cascade, which nothing else in the artefact's
  #        suites drives — plus the door's inertness: under the gate a
  #        DECLARED, MOUNTED, CORRELATED view answers `nil`, never an
  #        envelope carrying `:explanations []` with `:complete? true` on it.
  #        An empty-but-confident answer would tell a tool nothing is
  #        rendering in a build where plenty is, which is worse than no
  #        answer at all.
  #
  #    THE VACUOUS PASSES THIS ROSTER LINE WAS HIDING, so the next reader
  #    knows the shape: `(= [] seen)` and `(nil? (last-evidence-sink-escape))`
  #    over an elided seam; `(= [] (rows-for …))` after a disconnect, after an
  #    abandoned render and before a reconnect; `(nil? (:loss r))` over a nil
  #    projection; `(nil? (:cause e))`, `(nil? (:dispatch-id e))` and
  #    `(= [] (:candidates e))` over an explanation that does not exist;
  #    `(every? #(not (contains? % :value)) …)` over an empty read set; and
  #    `an-undeclared-id-answers-nil` in TWO namespaces, where under the gate
  #    every id answers nil so the nil says nothing about the id.  Each is
  #    inside its posture arm now.

  # ── rf2-74a89 — THE PROPS-SCHEMA CLOSURE, CLEARED 2026-07-28.  READ THIS
  #    ONE BEFORE ASSUMING THE NEXT LOOK-ALIKE IS A DEFECT.
  #    `descriptor.cljc` ~line 591 gates the whole closing-schema arm and
  #    says why: "a schema is a compile-time and tooling fact: production
  #    renders the same tree either way, and the CLJS branch folds away".  So
  #    under the gate an UNDECLARED prop on a closing schema is ACCEPTED.
  #    That is the same class as Spec 010's `validate-fx!` (routing's
  #    `nav-fx-schemas`, rf2-o5dbf batch 7) and NOT the rf2-9c2jf class: the
  #    COMPILED tier still refuses the identical row statically at analyze
  #    time, in either posture, and that is now asserted directly by
  #    `which-tier-refuses-an-undeclared-prop-depends-on-the-posture` — with
  #    a non-map props slot refused in BOTH postures beside it, because the
  #    call ABI is always on and that is what makes the boundary's production
  #    `:accept` a verdict rather than a check that never ran.
  #
  #    `behaviors-` and `errors-tree-cljs-test` were the same mechanism seen
  #    from a caller: each drove a malformed boundary call and asserted
  #    `:rf.error/view-bad-props`.  Under the gate the call is STILL REFUSED
  #    — the door's OWN always-on roster check fires instead — so REFUSED is
  #    asserted posture-independently (neither namespace stated it before)
  #    and only the id rides the dev arm.
  #
  #    AND THE TRAP ON THE WAY OUT, which was the bulk of the work here: this
  #    suite's POSITIVE controls passed under the gate for the wrong reason.
  #    The schema-less arm, the open escape and the opaque-schema arm all
  #    assert a boundary `:accept`, and under the gate EVERYTHING accepts.
  #    All three travel inside the posture arm with the reject rows.

  # ── rf2-74a89 — SOURCE-COORD ELISION, CLEARED 2026-07-28.  `v/describe`'s
  #    `:source` is REGISTRATION metadata and `defview` emits the slim
  #    `prod-coords-form` for it under the gate, which omits `:column` —
  #    matching core's `reg-*` elision.  The MANIFEST's coordinates are a
  #    compile-time analyzer fact and survive byte-identical, so the
  #    inheritance claim keeps its full strength by naming the declaration's
  #    column the same way `event-column` was already named, with the
  #    verbatim descriptor equality in the dev arm proving the two agree.
  #    Two always-on rows were added: every roster coordinate is TOTAL in
  #    either posture (the property production depends on, and the one an
  #    over-eager elision would take), and the registration coord really is
  #    elided — so the asymmetry is a decision rather than a drift.

  # ── THE ROSTER IS EMPTY, and the `-n` machinery STAYS (see
  #    `test-routing-prod-gate.sh`, cleared the same way).  It is what makes
  #    the exclusion polarity real: every one of this artefact's test
  #    namespaces now runs under `-Dre-frame.debug=false` BY DEFAULT, and the
  #    next namespace that goes red under the gate has a documented place to
  #    be rostered — with a bead — instead of quietly reddening the job.
)

# ---------------------------------------------------------------------------
# Roster derivation
# ---------------------------------------------------------------------------

# Every namespace DECLARED under implementation/freehand/test/, read from the
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
if [ -n "$excluded" ]; then
  runnable="$(printf '%s\n' "$all_nses" | grep -v -x -F -f <(printf '%s\n' "$excluded"))"
else
  runnable="$all_nses"
fi

runnable_count="$(printf '%s\n' "$runnable" | grep -c . || true)"
excluded_count="$(printf '%s\n' "$excluded" | grep -c . || true)"
total_count="$(printf '%s\n' "$all_nses" | grep -c . || true)"

# ---------------------------------------------------------------------------
# Report the posture BEFORE running, so the log carries the evidence
# ---------------------------------------------------------------------------
printf '==> implementation/freehand under the REAL production gate\n'
printf '    jvm property : -Dre-frame.debug=false (implementation/freehand/deps.edn, :prod-gate :jvm-opts)\n'
printf '    posture pin  : re-frame.freehand.prod-gate-lane-pin-test (red if the property did not arrive)\n'
printf '    namespaces   : %s of %s (%s excluded as known-red — rf2-74a89)\n' \
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
# ordinary churn; raise it when the roster shrinks materially.
#
# Calibrated against the first green run: 125 namespaces / 1159 tests / 7930
# assertions (2026-07-28), floor 1000.  RAISED to 1090 with the roster's last
# line (rf2-74a89, 2026-07-28): the lane now runs 135 of 135 namespaces / 1268
# tests / 8457 assertions, and a floor left at the 125-namespace number would
# no longer notice the whole triage batch disappearing.  1090 keeps the same
# ~14% headroom `test-routing-prod-gate.sh` and `test-ssr-prod-gate.sh`
# settled on — low enough not to trip on ordinary churn, high enough that
# losing a tenth of the lane cannot report itself green.
export RF2_MIN_TESTS="${RF2_MIN_TESTS:-1090}"

args=()
for ns in $runnable; do
  args+=(-n "$ns")
done

cd "$artefact"
if ! clojure -M:test:prod-gate "${args[@]}"; then
  printf '\nFAIL implementation/freehand under -Dre-frame.debug=false\n' >&2
  printf 'repro: bash scripts/test-freehand-prod-gate.sh\n' >&2
  printf 'A namespace that is green in `clojure -M:test` and red here is asserting\n' >&2
  printf 'DEV INSTRUMENTATION, or it is a genuine production defect (rf2-9c2jf was\n' >&2
  printf 'the latter). Decide which before touching the known_red roster.\n' >&2
  exit 1
fi

printf 'PASS implementation/freehand under -Dre-frame.debug=false (%s namespaces)\n' "$runnable_count"
