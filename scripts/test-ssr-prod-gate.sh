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
# close.  That pin matters MORE now, not less: with the roster gone there is no
# second signal left that the posture is the one intended.
#
# THERE USED TO BE A ROSTER.  IT IS EMPTY, SO IT IS GONE (rf2-lwtlk).
#
# Nobody had ever run the ssr suite under the real gate.  Run on 2026-07-27 it
# was emphatically RED: 217 failures and 1 error across 21 of its 48 test
# namespaces, and this script carried those 21 as a known-red EXCLUSION list,
# grouped by cause, each group naming the bead that would clear it.
#
# rf2-lwtlk was that triage stream and it has finished.  Four shapes accounted
# for all 21.  Dev-instrumentation assertions written inline with semantics,
# and `data-rf2-source-coord` / `data-rf-view` annotations prod-elided at the
# core `reg-view` boundary: both legitimate dev-posture tests, posture-split so
# the semantics they were entangled with run here.  The error-projection
# cluster, which looked like a design choice and was not — rf2-ov56u promoted
# the URL-driven route miss onto the always-on axis, so a production server no
# longer answers an unroutable URL with HTTP 200, and
# `re-frame.ssr-route-miss-404-production-test` is the proof, in this lane.
# And `ssr-end-to-end-test`, the last line, whose
# `ssr-server-fx-args-schema-boundary` was failing for a REAL reason: the Spec
# 010 step-5 fx-args gate does not run in a release build, so a malformed
# `:rf.server/*` fx RAN and its args landed on the response accumulator.
# rf2-dtpfv fixed that — the reserved family guards its own args in every build
# — and the deftest was rewritten as a two-posture contract rather than split.
#
# Note how little of the 21 was dev-posture SPELLING once each was actually
# read: the recurring finding was that a production-visible witness existed and
# the test was reading the dev copy of it.  Guarding is the last resort in this
# artefact, not the first move.
#
# WHAT REPLACES THE ROSTER.  Nothing — and that is the strongest possible
# polarity.  The exclusion list's whole virtue was that a namespace added to
# `implementation/ssr/test/` joined the lane BY DEFAULT and had to be excluded
# deliberately.  With the list empty, the runner discovers every `.*-test$`
# namespace under `test/` and runs it, so a new suite that breaks under the
# production gate reddens this job the day it lands, with nothing to edit and
# no `-n` selector that could silently fail to match.  The two roster guards
# (`verify_roster`) existed to keep that selector honest and went with it.
#
# If a namespace ever has to be excluded again, do not reintroduce a list
# without reading the history above first: on this artefact, four of the five
# "obviously dev-only" clusters turned out to have an always-on witness.

set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

# First line names the tree this run resolved — see scripts/test-fast-pr.sh
# (rf2-g2mxd): a relative invocation resolves `${BASH_SOURCE[0]}` against the
# shell's actual cwd, so a backgrounded gate can silently run in, and grade,
# another worktree.  Invoke backgrounded gates by ABSOLUTE path.
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
# ~13% headroom against the current observed count.
#
# RAISED 380 -> 410 by rf2-76gom / rf2-lwtlk.  The roster is down to 2 entries
# and the lane runs 473 tests / 2093 assertions across 48 of 50 namespaces.
#
# RAISED 410 -> 425 by rf2-lwtlk when `flows-integration-test` came off behind
# the rf2-bkvu5 ruling.  This raise is CONVENTION MAINTENANCE, not a response
# to that one namespace: 9 tests is 1.8% of the lane and no floor with usable
# headroom could ever notice 9 tests vanishing.  What had drifted is the
# headroom itself — the lane grew 473 -> 490 tests (2093 -> 2184 assertions,
# now 50 of 51 namespaces) while the floor stayed at 410, widening the gap
# from the ~13% this file has twice calibrated to, to 16.3%.  425 restores
# ~13% against the observed 490.  Raise it once more when
# `ssr-end-to-end-test` comes off behind rf2-dtpfv; that is the LAST entry,
# and the `-n` machinery goes away with it.
#
# RAISED 425 -> 510 by rf2-lwtlk — the raise the note above asked for, and the
# last one this file will describe as roster-driven.  `ssr-end-to-end-test`
# came off behind rf2-dtpfv, the roster reached ZERO, and the lane is now the
# whole suite: 589 tests / 2698 assertions, measured green under
# `-Dre-frame.debug=false` on 2026-07-28.  425 against 589 is 27.8% headroom —
# the floor had become a formality again, exactly as at 215 and at 380.  510
# restores the ~13% this file has now calibrated to four times.
#
# THIS FLOOR IS NOW THE LANE'S ONLY STRUCTURAL GUARD.  While the roster
# existed, a collapsed `-n` selector was caught twice over — by
# `verify_roster` and by this number.  The selector is gone, so if namespace
# discovery ever silently narrows (a renamed `test/` directory, a runner
# default changing out from under `.*-test$`), `Ran 0 tests` reaching CI green
# is prevented HERE and nowhere else.  Keep the headroom tight enough to mean
# something: a floor the lane could lose half its suite and still clear is not
# a floor.
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
