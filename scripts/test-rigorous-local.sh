#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

"$repo_root/scripts/test-fast-pr.sh"
"$repo_root/scripts/test-jvm-implementation.sh"
"$repo_root/scripts/test-jvm-tools.sh"

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
  npm run test:examples && \
  npm run test:examples-compile && \
  npm run test:story-feature-load && \
  npm run test:story-play-scripts && \
  npm run test:xray-feature-gate && \
  npm run test:story-static)

printf 'PASS rigorous local suite\n'
