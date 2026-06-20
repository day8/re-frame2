#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

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
# `.github/workflows/expensive-tests.yml` (plus `test:examples-compile`, the
# example-build compile gate run in test.yml's cljs-browser job). Keep these
# commands in lockstep with that workflow's implementation browser/bundle list
# — `npm run test:script-policy` pins the inventory (see
# implementation/scripts/_rigorous-local-inventory.test.cjs).
(cd "$repo_root/implementation" && \
  npm run test:browser && \
  npm run test:browser-schemas-boundary-prod && \
  npm run test:browser-prod-elision && \
  npm run test:elision && \
  npm run test:bundle-isolation && \
  npm run test:reagent-slim:bundle-isolation && \
  npm run test:adapter-smokes && \
  npm run test:examples-compile && \
  npm run test:story-feature-load && \
  npm run test:story-play-scripts && \
  npm run test:xray-feature-gate && \
  npm run test:story-static)

printf 'PASS rigorous local suite\n'
