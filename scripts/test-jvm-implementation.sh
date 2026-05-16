#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

artefacts=(
  implementation/core
  implementation/adapters/reagent
  implementation/adapters/reagent-slim
  implementation/adapters/uix
  implementation/adapters/helix
  implementation/schemas
  implementation/machines
  implementation/routing
  implementation/flows
  implementation/http
  implementation/ssr
  implementation/ssr-ring
  implementation/epoch
)

for artefact in "${artefacts[@]}"; do
  printf '==> JVM %s\n' "$artefact"
  if ! (cd "$repo_root/$artefact" && clojure -M:test); then
    printf '\nFAIL JVM %s\nrepro: cd %s && clojure -M:test\n' "$artefact" "$artefact" >&2
    exit 1
  fi
done

printf 'PASS implementation JVM artefacts\n'
