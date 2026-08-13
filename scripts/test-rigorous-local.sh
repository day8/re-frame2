#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

# First line names the tree this run resolved — see scripts/test-fast-pr.sh
# (rf2-g2mxd): a relative invocation resolves `${BASH_SOURCE[0]}` against the
# shell's actual cwd, so a backgrounded gate can silently run in, and grade,
# another worktree.  Invoke backgrounded gates by ABSOLUTE path.
printf 'gate root: %s\n' "$repo_root"

"$repo_root/scripts/test-fast-pr.sh"
"$repo_root/scripts/test-jvm-implementation.sh"
"$repo_root/scripts/test-jvm-tools.sh"

# rf2-bv2qqm — the heavy `^:slow` / `^:stress` JVM tests are EXCLUDED from
# the default `:test` gate (`-e :slow -e :stress`) so the PR/local fast path
# stays quick. They are NOT lost: this rigorous sweep runs them via each
# artefact's `:slow-test` alias (`-i :slow -i :stress`), mirroring the nightly
# `jvm-slow-tests` job in `.github/workflows/expensive-tests.yml`. `:test`
# (fast) + `:slow-test` (here) partition the suite — every test runs once.
printf '==> implementation JVM slow/stress tests (:slow-test alias)\n'
slow_artefacts=(
  implementation/core
  implementation/machines
  implementation/routing
  implementation/flows
  implementation/ssr
  implementation/ssr-ring
)
for artefact in "${slow_artefacts[@]}"; do
  printf '==> JVM slow %s\n' "$artefact"
  if ! (cd "$repo_root/$artefact" && clojure -M:slow-test); then
    printf '\nFAIL JVM slow %s\nrepro: cd %s && clojure -M:slow-test\n' \
      "$artefact" "$artefact" >&2
    exit 1
  fi
done

printf '==> implementation rigorous browser/bundle gates\n'
# Local mirror of the rigorous browser-bundle-and-story sweep in
# `.github/workflows/expensive-tests.yml`, PLUS the Freehand mounted-correctness
# lane — the `:browser-test-freehand-bench` build, whose only scheduled home is
# `freehand-bench.yml` (rf2-rmtj0).
#
# THIS MIRRORS THE EXPENSIVE SWEEP, NOT THE PR LANE (rf2-0l1nv). A command earns
# a line below when BOTH hold: (a) it yields a VERDICT, so a non-zero exit means
# something is broken, rather than producing a build artefact or publishing a
# record; and (b) no PR run gates it, its only scheduled home being a nightly or
# workflow_dispatch lane. Gates that live only in `test.yml` fail (b) and are
# deliberately absent — that workflow is surface-classified, so the change that
# would break one is the change that queues it, and a PR cannot land past it in
# silence. A nightly-only verdict has no such protection, and closing exactly
# that window is the entire reason this script exists.
#
# On that bench lane: it runs here for its mounted-correctness VERDICT — DOM
# parity between interpreted and compiled views, cell-elision counts, exact
# event/write/recompute/render counts, controlled-input value/caret/node identity
# under contention, cross-substrate parity — which are deterministic browser
# facts even though they are collected inside a benchmark harness. rf2-mf4uy
# moved those seven `re-frame.freehand.bench.*` namespaces out of `:browser-test`
# and this sweep lost them with it. What this sweep does NOT reproduce is the
# evidence-publication semantics of `.github/workflows/freehand-bench.yml`: that
# scheduled lane, on its pinned hardware class and with a compiled-in revision,
# remains the sole authority for citable numbers. A local run deliberately
# labels its records unattributable; this script consumes the verdict and
# ignores the distributions.
#
# The last two are not browser gates and are here on the same derivation
# (rf2-a9oic).  `test:cljs-perf-emit-nightly` runs the `:node-test-perf-nightly`
# lane, whose `-emit-nightly-test$` selector the consolidated `:node-test`
# build's `cljs-test$` does not match, so its namespaces run in that lane alone;
# `test:ui-warm-watch` drives a real Shadow warm watch and one live emitted
# runtime through eight scripted edit passes plus a disk-cache restart.  Both
# yield a VERDICT and both are nightly-only, so both satisfy (a) and (b) — and
# until this change neither ran anywhere at all.
#
# The TEN after those (rf2-mvq7, from the rf2-2uy8 B+ ruling) are the
# narrow-armed gates: each runs at PR time only when `detect_changed_surfaces`
# arms it, and until that change none had any arm that ran against main at all,
# so a cross-surface regression in one sat green there until some PR happened to
# touch its narrow surface.  They join the nightly sweep and therefore join this
# mirror.  The `hicasso-controlled` / `hicasso-hmr` / `ui-g8` trio is
# deliberately NOT here, for the same reason it is not in the nightly — the
# declared-hole comment in `expensive-tests.yml` carries the exposure each one
# accepts.
#
# Keep these commands in lockstep with that workflow's implementation
# browser/bundle list — the `test:script-policy` gate pins the inventory (see
# implementation/scripts/_rigorous-local-inventory.test.cjs).  That pin is not
# decorative: it is what caught this list drifting behind the ten additions
# above, in the same spine run that was meant merely to confirm them.
(cd "$repo_root/implementation" && \
  npm run test:browser && \
  npm run bench:freehand-browser && \
  npm run test:browser-schemas-boundary-prod && \
  npm run test:browser-prod-elision && \
  npm run test:elision && \
  npm run test:freehand-evidence-elision && \
  npm run test:freehand-reachability && \
  npm run test:bundle-isolation && \
  npm run test:reagent-slim:bundle-isolation && \
  npm run test:adapter-smokes && \
  npm run test:examples-compile && \
  npm run test:story-feature-load && \
  npm run test:story-play-scripts && \
  npm run test:xray-feature-gate && \
  npm run test:story-static && \
  npm run test:cljs-perf-emit-nightly && \
  npm run test:ui-warm-watch && \
  npm run test:ssr-node && \
  npm run test:perf-bundle && \
  npm run test:schemas-bundle && \
  npm run test:ui-g1 && \
  npm run test:ui-g13 && \
  npm run test:ui-facade-isolation && \
  npm run test:reagent-slim:smoke && \
  npm run test:testbed-tenant-switcher && \
  npm run test:tools-machines-viz && \
  npm run build:machines-viz-viewer)

printf 'PASS rigorous local suite\n'
