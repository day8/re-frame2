#!/usr/bin/env sh
# scripts/check-playground-sci-freshness.sh
#
# Committed SCI-bundle freshness gate (rf2-2h1yhk, made mechanically honest in
# rf2-i3e3q).
#
# The committed `docs/cljs/playground-rf2.js` is a vendored, prebuilt
# shadow-cljs `:advanced` bundle that bakes in re-frame2 core + reagent-slim +
# machines + flows + react@19 (it is the eval engine for the docs' live
# `cljs-rf2` cells). docs.yml rebuilds it fresh from main on every publish (so
# the DEPLOYED bundle is always current), but the COMMITTED snapshot is a
# vendored prebuilt that PR + post-merge gating relies on — and a committed
# bundle that has drifted out of sync with its source (any baked-in input
# changed without a rebuild) is a real local/PR-time hazard.
#
# WHAT IS PROVEN (and what is NOT).
#
# This gate proves ONE thing precisely: the committed bundle's recorded
# input-digest marker equals a fresh digest of the CURRENT declared inputs — i.e.
# the committed bundle was built from exactly today's source/config/lock. It does
# NOT prove the Closure bytes are a reproducible build of that source (they are
# not cross-machine reproducible — see below), and it does NOT prove runtime
# behaviour (the headless-Chromium smoke, run separately against a FRESH rebuild,
# is the runtime check). It rebuilds nothing.
#
# WHY A CONTENT DIGEST, NOT A `git diff` REBUILD. A Closure `:advanced` build's
# minified-symbol allocation is per-machine deterministic but NOT cross-machine
# reproducible (a Windows-committer JVM and a Linux-CI JVM emit different
# identifiers for byte-identical source), so a rebuild-and-diff of the bundle
# bytes is inherently flaky. What IS cross-machine stable is the SET OF SOURCE
# INPUTS. `scripts/playground-sci-input-digest.mjs` hashes that exact roster
# (core / reagent-slim / machines / flows source + deps.edn, the SCI bundle
# source, the shadow build config, and the npm lock) into one 64-hex digest;
# `docs/tools/playground/sci/scripts/copy-bundle.mjs` stamps it into the bundle
# it emits as `//# rf2-sci-input-digest=<hex>`. This gate recomputes the digest
# from the current inputs and compares. A source change to any declared input
# that is not followed by a rebuild leaves the committed marker != current inputs
# => STALE => this gate fails until the bundle is rebuilt + recommitted.
#
# WHY THE COMMITTED (not working-tree) BUNDLE. The PR-time `tools-playground`
# job runs `npm run build` (which OVERWRITES the working-tree bundle with a fresh
# build) BEFORE invoking this gate, so a working-tree read would always look
# fresh. This gate therefore reads the bundle from `HEAD` — the committed
# artefact — so it is honest regardless of build order or whether a rebuild ran.
#
# Retained as diagnostics (rf2-i3e3q): the stable machine-lifecycle sentinel —
# the committed bundle must carry the CURRENT creation marker (derived from
# transition.cljc) and NOT the retired `:rf.machine/bootstrap`. These are a fast,
# specific signal for the keyword-rename drift class; the input digest is the
# general contract that covers every baked-in input.
#
# Cross-platform: POSIX sh + git + node. Runs identically on the Linux CI runner,
# on macOS, and under Git Bash on Windows. node is required (it is set up in every
# CI context that runs this gate, and any local re-frame2 build already needs it).
#
# Run from the repo root:  sh scripts/check-playground-sci-freshness.sh
# Exit: 0 = fresh; 1 = stale (rebuild with `npm run build` in
# docs/tools/playground and recommit docs/cljs/playground-rf2.js).

set -eu

BUNDLE="docs/cljs/playground-rf2.js"
DIGEST_SCRIPT="scripts/playground-sci-input-digest.mjs"
MARKER_SRC="implementation/machines/src/re_frame/machines/transition.cljc"
RETIRED_TOKEN="rf.machine/bootstrap"

# --- Read the COMMITTED bundle (HEAD), not the working tree. -----------------
committed_bundle=$(mktemp)
# shellcheck disable=SC2064
trap "rm -f \"$committed_bundle\"" EXIT
if ! git show "HEAD:$BUNDLE" > "$committed_bundle" 2>/dev/null; then
  printf 'SCI-bundle freshness gate FAILED: could not read committed %s at HEAD.\n' "$BUNDLE" >&2
  printf 'Build it with `npm run build` in docs/tools/playground and commit it.\n' >&2
  exit 1
fi
if [ ! -s "$committed_bundle" ]; then
  printf 'SCI-bundle freshness gate FAILED: committed %s is empty at HEAD.\n' "$BUNDLE" >&2
  exit 1
fi

violations=0

# --- (A) AUTHORITATIVE: input-digest freshness. ------------------------------
# Recompute the digest of the current declared inputs and compare it to the
# marker the build stamped into the committed bundle.
if ! current_digest=$(node "$DIGEST_SCRIPT"); then
  printf 'SCI-bundle freshness gate FAILED: could not compute the input digest\n' >&2
  printf '(node %s failed). Is node on PATH?\n' "$DIGEST_SCRIPT" >&2
  exit 1
fi

embedded_digest=$(grep -oE 'rf2-sci-input-digest=[0-9a-f]+' "$committed_bundle" \
  | head -n1 | sed -E 's/^rf2-sci-input-digest=//' || true)

if [ -z "$embedded_digest" ]; then
  printf 'STALE: committed %s carries NO `rf2-sci-input-digest` marker.\n' "$BUNDLE" >&2
  printf '(It predates the input-digest freshness contract, or was hand-edited.)\n' >&2
  violations=$((violations + 1))
elif [ "$embedded_digest" != "$current_digest" ]; then
  printf 'STALE: committed %s was built from different inputs than are on disk now.\n' "$BUNDLE" >&2
  printf '  committed input-digest: %s\n' "$embedded_digest" >&2
  printf '  current  input-digest: %s\n' "$current_digest" >&2
  printf 'A declared baked-in input (core / reagent-slim / machines / flows source\n' >&2
  printf 'or deps.edn, the SCI bundle source, the shadow build config, or the npm\n' >&2
  printf 'lock) changed without rebuilding the committed bundle.\n' >&2
  violations=$((violations + 1))
fi

# --- (B) DIAGNOSTIC: machine-lifecycle sentinel (fast, specific). ------------
if [ ! -f "$MARKER_SRC" ]; then
  printf 'SCI-bundle freshness gate FAILED: marker source %s not found.\n' "$MARKER_SRC" >&2
  printf 'The machines lifecycle source moved; update this guard to point at it.\n' >&2
  exit 1
fi

# The current creation marker keyword — the LAST `:rf.machine/<name>` token on
# its own value line in the `(def start-marker ...)` form (the docstring names
# the retired token too). Tied to the value-on-its-own-line shape so a future
# rename is picked up automatically.
marker_kw=$(grep -oE ':rf\.machine/[a-z][a-z-]*\)' "$MARKER_SRC" | head -n1 | sed -E 's/^:([a-z.]+\/[a-z-]+)\)$/\1/')

if [ -z "$marker_kw" ]; then
  printf 'SCI-bundle freshness gate FAILED: could not derive the creation marker\n' >&2
  printf 'keyword from %s (expected a `(def start-marker ... :rf.machine/<name>)`\n' "$MARKER_SRC" >&2
  printf 'value form). Update this guard if the def shape changed.\n' >&2
  exit 1
fi

# (B1) The current marker MUST be in the committed bundle.
if ! grep -q "$marker_kw" "$committed_bundle"; then
  printf 'STALE: the current machine creation marker `:%s` (from %s) is NOT in committed %s.\n' \
    "$marker_kw" "$MARKER_SRC" "$BUNDLE" >&2
  violations=$((violations + 1))
fi

# (B2) The retired marker MUST NOT be in the committed bundle.
if grep -q "$RETIRED_TOKEN" "$committed_bundle"; then
  printf 'STALE: the RETIRED marker `:%s` is still present in committed %s.\n' \
    "$RETIRED_TOKEN" "$BUNDLE" >&2
  violations=$((violations + 1))
fi

if [ "$violations" -ne 0 ]; then
  printf '\n' >&2
  printf 'SCI-bundle freshness gate FAILED (%s issue(s)).\n' "$violations" >&2
  printf 'The committed %s is out of sync with current implementation source.\n' "$BUNDLE" >&2
  printf 'Rebuild it:  cd docs/tools/playground && npm run build\n' >&2
  printf 'then commit the regenerated docs/cljs/playground-rf2.js. See beads rf2-2h1yhk / rf2-i3e3q.\n' >&2
  exit 1
fi

printf 'SCI-bundle freshness gate OK: committed %s input-digest %s matches current inputs;\n' \
  "$BUNDLE" "$current_digest"
printf 'carries the current creation marker `:%s` and no retired `:%s`.\n' \
  "$marker_kw" "$RETIRED_TOKEN"
exit 0
