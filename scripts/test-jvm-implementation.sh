#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

artefacts=(
  implementation/core
  implementation/adapters/reagent
  implementation/adapters/reagent-slim
  implementation/adapters/uix
  implementation/adapters/helix
  implementation/adapters/test-react
  implementation/schemas
  implementation/machines
  implementation/routing
  implementation/flows
  implementation/http
  implementation/ssr
  implementation/ssr-ring
  implementation/epoch
  # rf2-gj2ae — the adversarial-property security tier is `.cljc` and
  # advertises a cross-runtime contract (e.g. `re-frame.security.gen`'s
  # JVM `:clj` `long`-multiply vs the CLJS `Math.imul` arm, pinned by
  # `gen-parity-security-cljs-test`). Its own `:test` alias
  # (implementation/security/deps.edn) runs the SAME `.cljc` namespaces
  # under the JVM so a divergence between the two reader-conditional arms
  # goes RED here — previously only the CLJS side (`npm run test:security`
  # + the always-on `:node-test` gate) ever exercised the tier.
  implementation/security
)

for artefact in "${artefacts[@]}"; do
  printf '==> JVM %s\n' "$artefact"
  if ! (cd "$repo_root/$artefact" && clojure -M:test); then
    printf '\nFAIL JVM %s\nrepro: cd %s && clojure -M:test\n' "$artefact" "$artefact" >&2
    exit 1
  fi
done

printf 'PASS implementation JVM artefacts\n'
