#!/usr/bin/env bash
#
# Run `implementation/ssr` under the REAL production gate.
#
# WHY THIS EXISTS.  `SECURITY.md` documents `-Dre-frame.debug=false` (and
# `RE_FRAME_DEBUG=false`) as the JVM/SSR production setting, and
# `re-frame.interop/debug-enabled?` reads it ONCE at namespace-load time.
# `scripts/test-core-prod-gate.sh` covers `implementation/core` ONLY.  SSR is
# the artefact where that matters most literally: this is the one that RUNS on
# a JVM in production, under exactly that property, and this lane is what
# executes it that way.  The suites that CALL THEMSELVES production-gate tests
# rebind `interop/debug-enabled?` with `with-redefs` AFTER the framework has
# loaded, and a load-time gate is invisible to that.  That is not a
# theoretical gap: a `dispatch-sync` that ran its handler ZERO times under the
# documented gate would stay green in every such suite.
#
# WHY SSR, SPECIFICALLY.  Sub-classification — a PRIVACY invariant, not a
# diagnostic — genuinely reaches
# `re-frame.ssr.payload-policy/project-routing-egress` in production, the code
# that decides what leaves the server inside a hydration payload.  Its
# always-on witness `re-frame.ssr-routing-egress-production-test` asserts that
# under `-Dre-frame.debug=false`, and this script is the lane that runs it.
#
#     bash scripts/test-ssr-prod-gate.sh          run the lane
#     bash scripts/test-ssr-prod-gate.sh --plan   print the posture, run nothing
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
# not go red: this suite passes in dev posture, so the lane would go GREEN on
# the wrong posture — the exact class of false green this whole file exists to
# close.  With no roster, that pin is the only signal that the posture is the
# one intended.
#
# THERE IS NO ROSTER.  The runner discovers every `.*-test$` namespace under
# `implementation/ssr/test/` and runs it, so a namespace added there joins the
# lane BY DEFAULT, and a new suite that breaks under the production gate
# reddens this job the day it lands, with nothing to edit and no `-n` selector
# that could silently fail to match.
#
# What goes red under the gate here is rarely dev-posture SPELLING alone: the
# recurring finding is that a production-visible witness exists and the test
# is reading the dev copy of it.  Guarding is the last resort in this artefact,
# not the first move.  The shapes that do occur:
#
#   * Dev-instrumentation assertions written inline with semantics, and
#     `data-rf2-source-coord` / `data-rf-view` annotations prod-elided at the
#     core `reg-view` boundary: both legitimate dev-posture tests, posture-split
#     so the semantics they are entangled with run here.
#   * Error projection looks like a dev-only channel and is not: the URL-driven
#     route miss is on the always-on axis, so a production server answers an
#     unroutable URL with 404, not HTTP 200, and
#     `re-frame.ssr-route-miss-404-production-test` is the proof, in this lane.
#   * The Spec 010 step-5 fx-args gate does not run in a release build, so the
#     reserved `:rf.server/*` family guards its own args in every build —
#     otherwise a malformed `:rf.server/*` fx would RUN and its args land on the
#     response accumulator.  `ssr-end-to-end-test`'s
#     `ssr-server-fx-args-schema-boundary` pins that as a two-posture contract.
#
# Before excluding a namespace, look for its always-on witness first: on this
# artefact most clusters that look "obviously dev-only" have one.

set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

# First line names the tree this run resolved: a relative invocation resolves
# `${BASH_SOURCE[0]}` against the shell's actual cwd, so a backgrounded gate
# can silently run in, and grade, another worktree.  Invoke backgrounded gates
# by ABSOLUTE path.
printf 'gate root: %s\n' "$repo_root"

artefact="$repo_root/implementation/ssr"

# ---------------------------------------------------------------------------
# Report the posture BEFORE running, so the log carries the evidence
# ---------------------------------------------------------------------------
printf '==> implementation/ssr under the REAL production gate\n'
printf '    jvm property : -Dre-frame.debug=false (implementation/ssr/deps.edn, :prod-gate :jvm-opts)\n'
printf '    posture pin  : re-frame.ssr-prod-gate-lane-pin-test (red if the property did not arrive)\n'
printf '    namespaces   : the WHOLE suite — every `.*-test$` namespace under\n'
printf '                   implementation/ssr/test, discovered by the runner.\n'
printf '                   rf2-lwtlk: the known-red roster reached zero and the\n'
printf '                   `-n` selector went with it.\n'

if [ "${1:-}" = "--plan" ]; then
  printf '    runnable     : all of them — there is no selector and no exclusion list\n'
  printf '    excluded     : none\n'
  exit 0
fi

# The coverage floor.  `re-frame.test-quiet.runner` reds any SUITE lane that
# executed fewer than RF2_MIN_TESTS tests, so a lane that collapsed — a
# renamed directory, a discovery default that matched nothing — cannot report
# itself green with `Ran 0 tests`.  Calibrated below the observed count with
# room for ordinary churn; raise it when the suite grows materially.  The
# calibration is ~13% headroom against the observed count, applied as a
# convention rather than in response to any one namespace: no floor with
# usable headroom could notice a single small namespace vanishing.
#
# THIS FLOOR IS THE LANE'S ONLY STRUCTURAL GUARD.  There is no `-n` selector,
# so if namespace discovery ever silently narrows (a renamed `test/`
# directory, a runner default changing out from under `.*-test$`), `Ran 0
# tests` reaching CI green is prevented HERE and nowhere else.  Keep the
# headroom tight enough to mean something: a floor the lane could lose half
# its suite and still clear is not a floor.
export RF2_MIN_TESTS="${RF2_MIN_TESTS:-510}"

cd "$artefact"
if ! clojure -M:test:prod-gate; then
  printf '\nFAIL implementation/ssr under -Dre-frame.debug=false\n' >&2
  printf 'repro: bash scripts/test-ssr-prod-gate.sh\n' >&2
  printf 'A namespace that is green in `clojure -M:test` and red here is asserting\n' >&2
  printf 'DEV INSTRUMENTATION, or it is a genuine production defect (rf2-9c2jf was\n' >&2
  printf 'the latter). Decide which BEFORE reaching for a posture guard.\n' >&2
  printf '\n' >&2
  printf 'And check for a production-visible witness first. Emptying the roster\n' >&2
  printf '(rf2-lwtlk) turned up an always-on axis behind four of the five clusters\n' >&2
  printf 'that looked dev-only: `emit-fx-error!` and `emit-safe-redirect-error!`\n' >&2
  printf 'fan BOTH axes, and `error-emit-projection-listener` — not the trace-cb\n' >&2
  printf 'one — is what stamps :status on a production JVM. A `when\n' >&2
  printf 'interop/debug-enabled?` arm around an assertion that had an always-on\n' >&2
  printf 'source moves a live contract out of the posture that ships.\n' >&2
  exit 1
fi

printf 'PASS implementation/ssr under -Dre-frame.debug=false (whole suite, no exclusions)\n'
