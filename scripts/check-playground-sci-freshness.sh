#!/usr/bin/env sh
# scripts/check-playground-sci-freshness.sh
#
# SCI-bundle freshness guard (rf2-2h1yhk).
#
# The committed `docs/cljs/playground-rf2.js` is a vendored, prebuilt
# shadow-cljs `:advanced` bundle that bakes in re-frame2 core + reagent-slim +
# the machines artefact (it is the eval engine for the docs' live `cljs-rf2`
# cells). It is deployed verbatim by docs.yml and NEVER rebuilt by the docs
# build — so an `implementation/` change that alters the bundle's compiled
# surface (e.g. renaming a reserved keyword) can leave the deployed artefact
# silently stale. That is exactly what happened with the machine lifecycle
# creation marker: the source renamed `:rf.machine/bootstrap` -> the current
# marker, but the committed bundle still carried the retired token.
#
# Why this is a CONTENT check, not a `git diff` rebuild check. The CI
# bundle-drift gate (tools-playground in .github/workflows/test.yml)
# deliberately does NOT byte-diff `playground-rf2.js`: a Closure `:advanced`
# build's minified-symbol allocation is per-machine deterministic but NOT
# cross-machine reproducible (a Windows-committer JVM and a Linux-CI JVM emit
# different identifiers for byte-identical source), so a rebuild-and-diff is
# inherently flaky. This guard instead asserts the committed bundle agrees with
# CURRENT source on the reserved machine-lifecycle creation marker — a stable,
# deterministic, cross-machine string contract that is precisely the surface
# that drifted. It rebuilds nothing.
#
# What it checks (derived from source, so it auto-tracks a future rename):
#   1. The CURRENT creation marker keyword — read from the canonical
#      `(def start-marker ... :rf.machine/<name>)` form in
#      implementation/machines/src/re_frame/machines/transition.cljc — MUST be
#      present in the committed bundle. (Absent => stale; rebuild + recommit.)
#   2. The RETIRED marker token `:rf.machine/bootstrap` MUST NOT be present.
#      (Present => stale; the rename predates the bundle's last build.)
#
# Cross-platform: POSIX sh. Runs identically on the Linux CI runner, on macOS,
# and under Git Bash on Windows. No bashisms.
#
# Run from the repo root:  sh scripts/check-playground-sci-freshness.sh
# Exit: 0 = fresh; 1 = stale (rebuild with `npm run build:rf2` in
# docs/tools/playground and recommit docs/cljs/playground-rf2.js).

set -eu

BUNDLE="docs/cljs/playground-rf2.js"
MARKER_SRC="implementation/machines/src/re_frame/machines/transition.cljc"
RETIRED_TOKEN="rf.machine/bootstrap"

if [ ! -s "$BUNDLE" ]; then
  printf 'SCI-bundle freshness gate FAILED: %s is missing or empty.\n' "$BUNDLE" >&2
  printf 'Build it with `npm run build:rf2` in docs/tools/playground and commit it.\n' >&2
  exit 1
fi

if [ ! -f "$MARKER_SRC" ]; then
  printf 'SCI-bundle freshness gate FAILED: marker source %s not found.\n' "$MARKER_SRC" >&2
  printf 'The machines lifecycle source moved; update this guard to point at it.\n' >&2
  exit 1
fi

# Derive the CURRENT creation marker keyword from the canonical source-of-truth
# def. The form is `(def start-marker "<docstring>" :rf.machine/<name>)`; the
# keyword is the LAST `:rf.machine/<name>` token in that def (the docstring
# names the retired token too, so we cannot just take the first match). We grep
# every `:rf.machine/<name>` occurrence in the file, keep the one that is the
# bare value form on its own line (`  :rf.machine/start)`), and strip to the
# token. This is intentionally tied to the def's value-on-its-own-line shape so
# a future rename of the keyword is picked up automatically.
marker_kw=$(grep -oE ':rf\.machine/[a-z][a-z-]*\)' "$MARKER_SRC" | head -n1 | sed -E 's/^:([a-z.]+\/[a-z-]+)\)$/\1/')

if [ -z "$marker_kw" ]; then
  printf 'SCI-bundle freshness gate FAILED: could not derive the creation marker\n' >&2
  printf 'keyword from %s (expected a `(def start-marker ... :rf.machine/<name>)`\n' "$MARKER_SRC" >&2
  printf 'value form). Update this guard if the def shape changed.\n' >&2
  exit 1
fi

violations=0

# (1) The current marker MUST be in the committed bundle.
if ! grep -q "$marker_kw" "$BUNDLE"; then
  printf 'STALE: the current machine creation marker `:%s` (from %s) is NOT in %s.\n' \
    "$marker_kw" "$MARKER_SRC" "$BUNDLE" >&2
  violations=$((violations + 1))
fi

# (2) The retired marker MUST NOT be in the committed bundle.
if grep -q "$RETIRED_TOKEN" "$BUNDLE"; then
  printf 'STALE: the RETIRED marker `:%s` is still present in %s.\n' \
    "$RETIRED_TOKEN" "$BUNDLE" >&2
  violations=$((violations + 1))
fi

if [ "$violations" -ne 0 ]; then
  printf '\n' >&2
  printf 'SCI-bundle freshness gate FAILED (%s issue(s)).\n' "$violations" >&2
  printf 'The committed %s is out of sync with current implementation source.\n' "$BUNDLE" >&2
  printf 'Rebuild it:  cd docs/tools/playground && npm run build:rf2\n' >&2
  printf 'then commit the regenerated docs/cljs/playground-rf2.js. See bead rf2-2h1yhk.\n' >&2
  exit 1
fi

printf 'SCI-bundle freshness gate OK: %s carries the current creation marker `:%s` and no retired `:%s`.\n' \
  "$BUNDLE" "$marker_kw" "$RETIRED_TOKEN"
exit 0
