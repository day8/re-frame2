#!/usr/bin/env bash
# verify-version-lockstep.sh (rf2-ace2; substrate-paths updated rf2-zha9;
# adapters/ rename rf2-0imy; tools/ coverage rf2-lwtke; coordinate-inventory
# completeness rf2-7fxf8)
#
# Asserts the lockstep-version contract documented in spec/Conventions.md
# §Packaging conventions: every published artefact picks up its version
# from the single repo-root VERSION file via :clein/build :version,
# and every artefact references its in-repo dependencies via :local/root
# coordinates (which the release workflow rewrites to the matching
# :mvn/version at deploy time).
#
# Per rf2-zha9 the adapters (reagent, uix) live at
# implementation/adapters/<name>/ (renamed from substrates/ per
# rf2-0imy) — one level deeper than the per-feature artefacts
# (schemas, machines, routing, flows, http, ssr, epoch) which stay at
# implementation/<name>/. The script tracks the difference: adapters
# declare :version "../../../VERSION" and :local/root "../../core";
# per-feature artefacts and core declare :version "../../VERSION" and
# (for non-core) :local/root "../core".
#
# Per rf2-lwtke the deployable jars under tools/* also participate in
# lockstep — every Clojars-publishable tool (xray, story, story-mcp,
# machines-viz, mcp-base) carries :clein/build :version "../../VERSION"
# and must not hand-edit a literal :mvn/version for any day8/re-frame2-*
# artefact.
#
# Per rf2-2ii52 each also has to be PACKAGEABLE, which a :version read
# cannot tell you: the map must satisfy clein's spec (`:main`), and every
# runtime coordinate must be one `clein pom` can express. Both classes had
# already shipped un-noticed — see check_clein_main /
# check_no_git_coords_in_runtime_deps below.
# tools/machines-viz/ (rf2-o9arp) ships day8/re-frame2-machines-viz with
# the same lockstep posture as xray — :local/root "../../implementation/core"
# in dev, rewritten to :mvn/version at release.
# tools/re-frame2-pair-mcp/ ships as a Node binary on npm and carries no
# :clein/build alias, so it is intentionally excluded. tools/template/
# is similarly excluded as of rf2-40vmd (rf2-dolpf §2.5): it ships via
# git-coord (no Clojars publish, no :clein/build alias) and the version
# literals consumed by the emitted app are guarded by the in-template
# `version_lockstep_test.clj` suite rather than by this script.
#
# This script is the single source of truth for the lockstep contract;
# both .github/workflows/test.yml (PR-time drift detection) and
# .github/workflows/release.yml (pre-deploy gate) invoke it.
#
# Exits 0 on success; non-zero on the first detected drift, with a
# GitHub-Actions-friendly ::error:: line on stderr-or-stdout. Running
# locally from the repo root prints the same messages and is the
# fastest way to debug a CI lockstep failure.
#
# Usage:
#   ./.github/scripts/verify-version-lockstep.sh
#
# rf2-ace2 / rf2-w05l / rf2-zha9 / rf2-lwtke.

set -euo pipefail

# Resolve repo root from script location so the script is callable from
# anywhere (CI working-directory, local dev shell, sub-repo).
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"

# Lockstep policy through 1.0 (per rf2-w05l): single root VERSION drives
# every artefact via :clein/build :version. Each non-core artefact
# references core via :local/root so changes to VERSION propagate to
# every artefact's pom at deploy time. Anything else is drift.
#
# Per rf2-zha9 the relative paths differ by tier:
#   - core + per-feature (implementation/<name>/):  :version "../../VERSION"     :local/root "../core"
#   - adapters (implementation/adapters/<name>/):   :version "../../../VERSION"  :local/root "../../core"

VERSION_FILE="${REPO_ROOT}/VERSION"
if [[ ! -f "${VERSION_FILE}" ]]; then
  echo "::error file=VERSION::repo-root VERSION file is missing"
  exit 2
fi
VERSION="$(tr -d '[:space:]' < "${VERSION_FILE}")"
if [[ -z "${VERSION}" ]]; then
  echo "::error file=VERSION::repo-root VERSION file is empty"
  exit 2
fi
echo "lockstep VERSION = ${VERSION}"

# Map artefact name → on-disk subpath under implementation/. Adapters
# live under adapters/; per-feature artefacts (and core) stay flat.
# Order matches the topological deploy DAG in release.yml so a drift
# report reads top-down.
declare -A ARTEFACT_PATHS=(
  [core]="core"
  [schemas]="schemas"
  [reagent]="adapters/reagent"
  [reagent-slim]="adapters/reagent-slim"
  [uix]="adapters/uix"
  [machines]="machines"
  [routing]="routing"
  [flows]="flows"
  [http]="http"
  [ssr]="ssr"
  [ssr-ring]="ssr-ring"
  [resources]="resources"
  [epoch]="epoch"
)

# rf2-qmhysc — resources + ssr-ring both declare publishable
# :clein/build artefacts (day8/re-frame2-resources, day8/re-frame2-ssr-ring)
# and ship in the release/deploy matrix, so they MUST participate in the
# lockstep contract. They were previously omitted: version/local-root
# drift in those two publishable artefacts was unguarded, and the summary
# under-counted (it said "all 15"). The fail-on-drift inventory check
# below now also asserts that EVERY implementation/*/deps.edn carrying a
# :clein/build alias appears in this list, so a future publishable
# artefact cannot be omitted unnoticed.
#
# rf2-a32r7 — implementation/ui/ (re-frame.ui) is deliberately absent. Mike
# ruled on 2026-07-22 that day8/re-frame2-ui is not published: it is donor-only
# code being absorbed into Freehand (EP-0036) and the standalone artefact is
# deleted at the F6e gate (rf2-drpa3.57). It carries no :clein/build, so the
# conditional inventory guard below correctly leaves it alone.
ARTEFACTS=(core schemas reagent reagent-slim uix machines routing flows http ssr ssr-ring resources epoch)

# core is the lockstep root: it does not depend on any other re-frame2
# artefact, so the :local/root core-reference check below skips it.
NON_CORE=(schemas reagent reagent-slim uix machines routing flows http ssr ssr-ring resources epoch)

# Adapters (substrate adapters) are one directory deeper than per-feature
# artefacts.
ADAPTERS=(reagent reagent-slim uix)

is_adapter() {
  local needle="$1"
  for s in "${ADAPTERS[@]}"; do
    [[ "$s" == "$needle" ]] && return 0
  done
  return 1
}

errors=0

# Asserts (a) :clein/build :version points at the repo-root VERSION via
# the given relative path, and (b) the deps.edn carries no literal
# :mvn/version coordinate for any day8/re-frame2-* artefact in a
# non-comment line. Shared by implementation/* and tools/* artefacts.
#
# Args: $1 = absolute deps.edn path, $2 = repo-relative label for error
# lines, $3 = expected :version literal (e.g. '"../../VERSION"').
check_version_and_no_mvn_literal() {
  local deps_file="$1"
  local rel_label="$2"
  local expected_version="$3"

  if ! grep -qF ":version  ${expected_version}" "${deps_file}" \
     && ! grep -qF ":version ${expected_version}"  "${deps_file}"; then
    echo "::error file=${rel_label}::expected ':version ${expected_version}' in :clein/build (lockstep contract)"
    errors=$((errors + 1))
  fi

  # No artefact may carry a literal :mvn/version coordinate for any of
  # the day8/re-frame2-* artefacts in its committed deps.edn. The
  # release workflow rewrites :local/root → :mvn/version at deploy
  # time on a throwaway checkout; a literal in the committed file means
  # someone hand-edited it and the lockstep is broken.
  #
  # Strip comments first (deps.edn line comments start with `;;`) — the
  # consumer-facing usage examples in artefact deps.edn headers
  # legitimately show `day8/re-frame2 {:mvn/version "..."}` snippets.
  if sed 's/;;.*$//' "${deps_file}" | grep -qE 'day8/re-frame2[^[:space:]]*[[:space:]]+\{:mvn/version'; then
    echo "::error file=${rel_label}::found literal :mvn/version for a day8/re-frame2-* artefact in non-comment line (lockstep expects :local/root in committed deps.edn)"
    errors=$((errors + 1))
  fi
}

# ---- rf2-7fxf8: the inventory must be COMPLETE, not merely correct -------
#
# Every check in this script asks one direction of the question: "is the
# coordinate this script expects present in deps.edn?". Nothing asked the
# converse — "is every coordinate deps.edn declares present in this
# script?" — and the converse is the direction the gate actually drifted.
# tools/xray/deps.edn grew from one in-repo `:local/root` coordinate to TEN
# (core, epoch, routing, flows, schemas, resources, machines, freehand,
# machines-viz, reagent-slim) while TOOLS_LOCAL_ROOTS below kept listing
# one; tools/story-mcp/deps.edn grew a second (mcp-base) while its entry
# kept listing one. Throughout, the gate printed "PASSED — all 18
# artefacts" while blind to ten of the eighteen in-repo coordinates the
# release workflows have to rewrite. A one-directional roster cannot report
# on what it does not list, so its green is an ACTIVE false assurance
# rather than a merely missing check.
#
# Every roster entry is therefore cross-checked against the set DERIVED
# from the committed deps.edn. Same principle as
# .github/scripts/preflight-xray-package.sh (rf2-5dut1), which derives its
# required set instead of hand-listing it for exactly this reason — with a
# different vehicle: the preflight is the tag-push arm and runs where
# clojure is installed, whereas this gate is the ORDINARY-CI arm (test.yml
# runs it on a bare `actions/checkout` with no JDK), so it reads EDN
# through the same node authority the inventory guard above uses
# (implementation/scripts/lib/edn.cjs, rf2-zef0e). Structure, never a text
# grep: a reformatted deps.edn, or a coordinate quoted in a `;;` comment or
# inside a `#_` discard, must not be able to produce the false PASS this
# check exists to prevent.
EDN_READER="${REPO_ROOT}/implementation/scripts/lib/edn.cjs"

# Every in-repo coordinate the deps.edn at $1 declares, one rendered
# `lib {:local/root "path"}` line each, in declaration order. Alias
# :extra-deps are out of scope by design: they are build-time (clein,
# test-runner, test-quiet) and never reach a published pom — the same
# main-:deps scoping check_no_git_coords_in_runtime_deps uses.
local_root_coords() {
  node -e '
    const fs = require("fs");
    const { readEdn, isMap, mapGetKeyword } = require(process.argv[1]);
    const top = readEdn(fs.readFileSync(process.argv[2], "utf8"));
    if (!isMap(top)) throw new Error("top-level form is not a map");
    const deps = mapGetKeyword(top, "deps");
    if (deps === undefined) process.exit(0);
    if (!isMap(deps)) throw new Error(":deps is not a map");
    for (const [lib, coord] of deps.entries) {
      if (!isMap(coord)) continue;
      const root = mapGetKeyword(coord, "local/root");
      if (root === undefined) continue;
      if (root === null || root.edn !== "string") {
        throw new Error(":local/root value is not a string literal");
      }
      process.stdout.write(lib.name + " {:local/root \"" + root.value + "\"}\n");
    }
  ' "${EDN_READER}" "$1"
}

# The "and nothing else" half of the contract: every coordinate $1 declares
# must appear verbatim in the newline-separated expected set $3, which the
# checks below ACCUMULATE as they run — so the set means precisely "the
# coordinates this script asserted", and a coordinate added to deps.edn
# without a matching roster line fails here.
#
# Fail-closed on an unreadable deps.edn (exit, not a counted drift): a
# reader that could not enumerate the coordinates cannot be the basis for
# reporting that they are all inventoried.
coords_checked=0
assert_local_roots_inventoried() {
  local deps_file="$1"
  local rel_label="$2"
  local expected="$3"
  local derived coord

  if ! derived="$(local_root_coords "${deps_file}")"; then
    echo "::error file=${rel_label}::failed to read this deps.edn's :local/root coordinates structurally via implementation/scripts/lib/edn.cjs (is node on PATH? is the EDN well-formed?) — refusing to report an inventory this script could not verify"
    exit 2
  fi

  while IFS= read -r coord; do
    [[ -z "${coord}" ]] && continue
    coords_checked=$((coords_checked + 1))
    if ! grep -qxF "${coord}" <<< "${expected}"; then
      echo "::error file=${rel_label}::in-repo coordinate '${coord}' is declared at :local/root but is NOT in this script's inventory, so NOTHING asserts the release workflow rewrites it to :mvn/version. 'clein pom' SKIPS :local/root coordinates silently, so the published pom would omit this runtime dependency and Clojars has no yank (rf2-7fxf8). Add it to this script AND to the rewrite step of the artefact's release workflow, in the same PR."
      errors=$((errors + 1))
    fi
  done <<< "${derived}"
}

# Populated by the :local/root presence checks below, then consumed by the
# completeness pass. Keyed by artefact name; value is a newline-separated
# list of rendered coordinates.
declare -A IMPL_EXPECTED_LOCAL_ROOTS=()

for artefact in "${ARTEFACTS[@]}"; do
  subpath="${ARTEFACT_PATHS[$artefact]}"
  deps_file="${REPO_ROOT}/implementation/${subpath}/deps.edn"
  rel_label="implementation/${subpath}/deps.edn"

  if [[ ! -f "${deps_file}" ]]; then
    echo "::error file=${rel_label}::deps.edn missing for artefact '${artefact}'"
    errors=$((errors + 1))
    continue
  fi

  # Every artefact's :clein/build :version must point at the repo-root
  # VERSION via the right relative path for its tier. Any literal version
  # string here is the canonical drift signal — it bypasses the
  # single-source-of-truth and would let an artefact ship at a stale
  # version number.
  if is_adapter "${artefact}"; then
    # Adapters live one level deeper: implementation/adapters/<name>/.
    check_version_and_no_mvn_literal "${deps_file}" "${rel_label}" '"../../../VERSION"'
  else
    check_version_and_no_mvn_literal "${deps_file}" "${rel_label}" '"../../VERSION"'
  fi
done

# Every non-core artefact must reference core via :local/root, with
# the relative path matching its tier. The release workflow swaps this
# for :mvn/version $VERSION at deploy time; the swap only works if the
# in-repo source declares the right :local/root coordinate.
for artefact in "${NON_CORE[@]}"; do
  subpath="${ARTEFACT_PATHS[$artefact]}"
  deps_file="${REPO_ROOT}/implementation/${subpath}/deps.edn"
  rel_label="implementation/${subpath}/deps.edn"
  [[ -f "${deps_file}" ]] || continue
  if is_adapter "${artefact}"; then
    expected_local_root='day8/re-frame2 {:local/root "../../core"}'
  else
    expected_local_root='day8/re-frame2 {:local/root "../core"}'
  fi
  # rf2-qmhysc — collapse runs of whitespace before matching. ssr-ring's
  # deps.edn (newly in NON_CORE) column-aligns its dep map
  # (`day8/re-frame2     {:local/root …}`), so a single-space literal
  # grep -qF would spuriously report drift. Mirrors the tools/* loop's
  # `tr -s` normalisation below; the rewrite step in release.yml keys off
  # the `:local/root "<path>"` substring, which the normalisation
  # preserves.
  normalised_core="$(tr -s '[:space:]' ' ' < "${deps_file}")"
  if ! grep -qF "${expected_local_root}" <<< "${normalised_core}"; then
    echo "::error file=${rel_label}::expected '${expected_local_root}' (lockstep contract; the release workflow rewrites this to :mvn/version at deploy time)"
    errors=$((errors + 1))
  fi
  IMPL_EXPECTED_LOCAL_ROOTS["${artefact}"]+="${expected_local_root}"$'\n'
done

# rf2-qmhysc — ssr-ring is the one implementation artefact whose
# PUBLISHED :deps reference a SECOND in-repo framework artefact besides
# core: it depends on both day8/re-frame2 {:local/root "../core"} (checked
# in the NON_CORE loop above) AND day8/re-frame2-ssr {:local/root "../ssr"}
# (the Ring/Pedestal host adapter sits on top of the ssr renderer). The
# release workflow must rewrite BOTH :local/root coordinates to
# :mvn/version at deploy time, so the in-repo source must declare both —
# assert the ssr reference here.
SSR_RING_DEPS="${REPO_ROOT}/implementation/ssr-ring/deps.edn"
if [[ -f "${SSR_RING_DEPS}" ]]; then
  normalised_ssr_ring="$(tr -s '[:space:]' ' ' < "${SSR_RING_DEPS}")"
  if ! grep -qF 'day8/re-frame2-ssr {:local/root "../ssr"}' <<< "${normalised_ssr_ring}"; then
    echo "::error file=implementation/ssr-ring/deps.edn::expected 'day8/re-frame2-ssr {:local/root \"../ssr\"}' (lockstep contract; ssr-ring depends on ssr and the release workflow rewrites this to :mvn/version at deploy time)"
    errors=$((errors + 1))
  fi
  IMPL_EXPECTED_LOCAL_ROOTS[ssr-ring]+='day8/re-frame2-ssr {:local/root "../ssr"}'$'\n'
fi

# rf2-7fxf8 — and the converse, for every implementation artefact including
# core (whose expected set is empty: core is the lockstep root and must
# stay dependency-free within the repo). release.yml's rewrite is a
# hand-written matrix of one `local-root` value per leaf — ssr-ring is off
# that matrix precisely because it has a SECOND coordinate — so an in-repo
# edge nobody inventoried here is an edge nobody rewrites there.
for artefact in "${ARTEFACTS[@]}"; do
  deps_file="${REPO_ROOT}/implementation/${ARTEFACT_PATHS[$artefact]}/deps.edn"
  [[ -f "${deps_file}" ]] || continue
  assert_local_roots_inventoried \
    "${deps_file}" \
    "implementation/${ARTEFACT_PATHS[$artefact]}/deps.edn" \
    "${IMPL_EXPECTED_LOCAL_ROOTS[$artefact]:-}"
done

# rf2-qmhysc — inventory drift guard. The whole risk this script exists
# to close is "a publishable artefact ships at a stale version / broken
# :local/root because it was never wired into the lockstep inventory" —
# exactly how resources + ssr-ring slipped through before. Make that
# class of omission impossible to reintroduce silently: every
# implementation/*/deps.edn carrying a :clein/build alias MUST appear in
# the ARTEFACT_PATHS list above. A new publishable artefact added without
# a matching ARTEFACTS entry fails the build here.
declare -A KNOWN_IMPL_PATHS=()
for artefact in "${ARTEFACTS[@]}"; do
  KNOWN_IMPL_PATHS["${ARTEFACT_PATHS[$artefact]}"]=1
done
# Adapters live under implementation/adapters/<name>/; their subpaths are
# already registered (adapters/reagent, …).
#
# rf2-zef0e — publishability is discovered via the shared EDN-AWARE authority
# (implementation/scripts/lib/publishable-runtimes.cjs), the SAME structural
# result the bundle-isolation gate consumes, instead of a duplicated textual
# grep that could drift. The authority reads each deps.edn's real
# :aliases/:clein/build KEY: a genuine alias survives `;` inside EDN strings,
# and a :clein/build token inside a string, a `;` comment, or a `#_` discard
# form (e.g. implementation/ui/ and implementation/adapters/test-react/ each
# only MENTION the alias in prose) is correctly NOT treated as publishable. It
# emits one implementation/-relative subpath per line for every publishable
# artefact under implementation/ (bounded flat-plus-nested, same reach as the
# previous `find -mindepth 2 -maxdepth 3`). If it cannot run we FAIL CLOSED
# (exit) rather than skip the inventory guard.
PUBLISHABLE_AUTHORITY="${REPO_ROOT}/implementation/scripts/lib/publishable-runtimes.cjs"
if ! publishable_subpaths="$(node "${PUBLISHABLE_AUTHORITY}" "${REPO_ROOT}/implementation")"; then
  echo "::error file=implementation/scripts/lib/publishable-runtimes.cjs::failed to enumerate publishable artefacts via the structural EDN authority (is node available on PATH?)"
  exit 2
fi
while IFS= read -r subpath; do
  [[ -z "${subpath}" ]] && continue
  if [[ -z "${KNOWN_IMPL_PATHS[$subpath]:-}" ]]; then
    echo "::error file=implementation/${subpath}/deps.edn::implementation/${subpath} declares a :clein/build (publishable) artefact but is NOT in the lockstep ARTEFACTS inventory — add it to ARTEFACT_PATHS / ARTEFACTS / NON_CORE in this script AND to the release.yml deploy matrix + release notes (rf2-qmhysc)"
    errors=$((errors + 1))
  fi
done <<< "${publishable_subpaths}"

# Tools/* deployable jars (rf2-lwtke). Each tools/<name>/deps.edn that
# carries a :clein/build alias publishes to Clojars at the same lockstep
# version as the framework artefacts above — every consumer that pins
# `day8/re-frame2 {:mvn/version X}` should be able to pin
# `day8/re-frame2-xray {:mvn/version X}` and get a coherent set. Without
# this loop a hand-edit to tools/<name>/deps.edn would not surface as a
# drift report and could cut a broken release.
#
# Each entry lists the tool's on-disk subpath under tools/ and the
# expected :local/root references that the release workflow's
# :local/root → :mvn/version rewrite consumes. Tools live two levels
# down from the repo root (tools/<name>/), same depth as
# implementation/<name>/, so :version "../../VERSION" is correct.
#
# tools/re-frame2-pair-mcp/ is deliberately excluded: it ships as a Node binary
# on npm (@day8/re-frame2-pair-mcp) and carries no :clein/build alias —
# there is no Clojars publish path for it to drift on. Per its
# deps.edn header it has no :local/root dep on implementation/ either.
#
# tools/template/ is similarly excluded as of rf2-40vmd (rf2-dolpf §2.5):
# it ships via git-coord rather than Clojars and no longer carries a
# :clein/build alias. The template's pin literals (rf2-version,
# shadow-version, react-version) are guarded by an in-template lockstep
# test (`test/day8/re_frame2_template/version_lockstep_test.clj`) which
# reads the same sources of truth this script does (repo-root VERSION,
# implementation/package.json).
#
# tools/mcp-base/ was ABSENT from this inventory until rf2-2ii52, which is
# how it reached a fifth Clojars coordinate (day8/re-frame2-mcp-base) that
# nothing guarded — and, separately, how it sat un-BUILDABLE (no `:main`)
# without any gate noticing. story-mcp depends on it via :local/root, so
# story-mcp cannot publish until mcp-base does; a lockstep gate that could
# not see mcp-base could not see that either.
declare -A TOOLS_PATHS=(
  [xray]="xray"
  [story]="story"
  [story-mcp]="story-mcp"
  [machines-viz]="machines-viz"
  [mcp-base]="mcp-base"
)

# Newline-separated `tool|"day8/re-frame2-x {:local/root \"…\"}"` pairs
# expressing every re-frame2-* :local/root coordinate the release workflow
# would need to rewrite to :mvn/version at deploy time. A bash
# associative array can't carry multi-valued entries cleanly, so we use
# a single multi-line string and split on `|`.
#
# rf2-7fxf8 — Xray's entry listed ONE of its ten in-repo coordinates and
# story-mcp's listed one of its two, so ten of the eighteen coordinates the
# release workflows must rewrite were asserted by nothing at all. The
# completeness pass at the end of the tools loop now derives the true set
# from each deps.edn, so this list cannot silently fall behind again.
#
# Xray's `day8/re-frame2-freehand` line asserts ONLY what the loop below
# asserts of every entry: that the coordinate is declared at `:local/root`
# in the committed deps.edn, which is true today and green. It does NOT
# assert that freehand is publishable — implementation/freehand/deps.edn
# deliberately carries no `:clein/build` (publication is EP-0036 F6
# territory), so `day8/re-frame2-freehand` cannot be rewritten to any
# `:mvn/version`. Whether Xray is publishable before Freehand ships is an
# OPEN OPERATOR DECISION (rf2-5dut1) that this gate neither makes nor
# routes around; preflight-xray-package.sh is where it comes due, by
# refusing the deploy.
TOOLS_LOCAL_ROOTS=$(cat <<'EOF'
xray|day8/re-frame2 {:local/root "../../implementation/core"}
xray|day8/re-frame2-epoch {:local/root "../../implementation/epoch"}
xray|day8/re-frame2-routing {:local/root "../../implementation/routing"}
xray|day8/re-frame2-flows {:local/root "../../implementation/flows"}
xray|day8/re-frame2-schemas {:local/root "../../implementation/schemas"}
xray|day8/re-frame2-resources {:local/root "../../implementation/resources"}
xray|day8/re-frame2-machines {:local/root "../../implementation/machines"}
xray|day8/re-frame2-freehand {:local/root "../../implementation/freehand"}
xray|day8/re-frame2-machines-viz {:local/root "../machines-viz"}
xray|day8/reagent-slim {:local/root "../../implementation/adapters/reagent-slim"}
story|day8/re-frame2 {:local/root "../../implementation/core"}
story|day8/re-frame2-reagent {:local/root "../../implementation/adapters/reagent"}
story|day8/re-frame2-machines {:local/root "../../implementation/machines"}
# rf2-wht9a — Story's HTTP + Xray edges were absent from this inventory, so
# the verifier's green could not catch either omission. Both are main-`:deps`
# runtime coordinates (`:network` world slot / the RHS inspector), and
# release-story.yml rewrites all FIVE before packaging: a coordinate missing
# from this list is a coordinate nothing asserts is rewritable.
story|day8/re-frame2-http {:local/root "../../implementation/http"}
story|day8/re-frame2-xray {:local/root "../xray"}
story-mcp|day8/re-frame2-story {:local/root "../story"}
story-mcp|day8/re-frame2-mcp-base {:local/root "../mcp-base"}
machines-viz|day8/re-frame2 {:local/root "../../implementation/core"}
EOF
)

TOOLS=(xray story story-mcp machines-viz mcp-base)

# ---- rf2-2ii52: two failure classes this gate used to be blind to ---------
#
# It read `:version` and the `:local/root` coordinates, and nothing else. So
# it reported "all 17 artefacts pinned" while `day8/re-frame2-machines-viz`
# could not be BUILT at all, and while two artefacts carried a runtime
# coordinate `clein pom` silently DROPS. Both are cheap to assert.

# (1) clein's own spec requires `:main` in `:clein/build`. Without it every
# clein invocation in the directory aborts before doing any work:
#
#   Error in the :clein/build map: {…} - failed: (contains? % :main)
#
# An artefact can therefore be perfectly version-pinned and still be
# impossible to package. rf2-4u3t1 found this in machines-viz; rf2-2ii52
# found the same thing in mcp-base. Asserting it here is what stops a third.
check_clein_main() {
  local deps_file="$1"
  local rel_label="$2"

  if ! grep -qE '^[[:space:]]*:main[[:space:]]' "${deps_file}"; then
    echo "::error file=${rel_label}:::clein/build is missing :main — clein's build-opts spec requires it, so every clein invocation in this directory aborts before doing any work (artefact is un-BUILDABLE)"
    errors=$((errors + 1))
  fi
}

# (2) `clein pom` can only express an :mvn/version coordinate. Handed a
# :git/url it prints "Skipping coordinate: …" and generates a pom WITHOUT
# it — the same silent-skip it has for :local/root, which the
# :local/root → :mvn/version rewrite exists to repair. There is no
# equivalent repair for a git coord: if the library is not on Clojars there
# is no version to rewrite to. Publishing anyway ships a jar whose pom omits
# a runtime dependency, and Clojars has no yank.
#
# Scope is the MAIN :deps map only — alias :extra-deps (clein itself,
# test-runner, …) are build-time and never reach the pom.
#
# KNOWN-UNRESOLVED, deliberately listed rather than silently tolerated:
# `day8/de-dupe` is a runtime dep of mcp-base and story-mcp and is NOT on
# Clojars (searched: no results; https://clojars.org/day8/de-dupe is a 404).
# Resolving it is a product decision — publish it, vendor it, or accept that
# these two artefacts are not Clojars-publishable — tracked on rf2-2ii52.
# Neither artefact has a deploy workflow, so nothing can ship a broken pom
# today. DELETE THIS LINE when the ruling lands; the gate then completes.
GIT_COORD_KNOWN_UNRESOLVED=(day8/de-dupe)

check_no_git_coords_in_runtime_deps() {
  local deps_file="$1"
  local rel_label="$2"
  local main_deps
  local lib

  # The main :deps map is everything between the top-level `:deps` key and
  # the `:aliases` key that follows it in every artefact deps.edn here.
  main_deps="$(sed -n '/^[[:space:]]*:deps[[:space:]]/,/^[[:space:]]*:aliases/p' "${deps_file}" \
               | sed 's/;;.*$//')"

  while read -r lib; do
    [[ -z "${lib}" ]] && continue
    for known in "${GIT_COORD_KNOWN_UNRESOLVED[@]}"; do
      [[ "${lib}" == "${known}" ]] && continue 2
    done
    echo "::error file=${rel_label}::runtime dep '${lib}' uses a :git/url coordinate — 'clein pom' SKIPS it silently, so a published jar would omit a runtime dependency (publish it to Clojars, vendor it, or add it to GIT_COORD_KNOWN_UNRESOLVED with a tracking bead)"
    errors=$((errors + 1))
  done < <(grep -B 0 -E '\{:git/url' <<< "${main_deps}" \
           | grep -oE '[^[:space:]{]+[[:space:]]+\{:git/url' \
           | sed -E 's/[[:space:]]+\{:git\/url$//')
}

for tool in "${TOOLS[@]}"; do
  subpath="${TOOLS_PATHS[$tool]}"
  deps_file="${REPO_ROOT}/tools/${subpath}/deps.edn"
  rel_label="tools/${subpath}/deps.edn"

  if [[ ! -f "${deps_file}" ]]; then
    echo "::error file=${rel_label}::deps.edn missing for tool '${tool}'"
    errors=$((errors + 1))
    continue
  fi

  # Tools sit at tools/<name>/, same depth from VERSION as the
  # per-feature artefacts under implementation/<name>/.
  check_version_and_no_mvn_literal "${deps_file}" "${rel_label}" '"../../VERSION"'

  # rf2-2ii52 — buildability and pom-expressibility, the two classes a
  # :version/:local/root read cannot see.
  check_clein_main "${deps_file}" "${rel_label}"
  check_no_git_coords_in_runtime_deps "${deps_file}" "${rel_label}"

  # Belt-and-braces: assert each expected :local/root coordinate. The
  # release workflow's rewrite step (release.yml) keys off the
  # `:local/root "<path>"` substring; the artefact-key pairing here is
  # an extra signal that a hand-edit hasn't, say, swapped the keys.
  #
  # tools/story/deps.edn pads its dep map with column-aligned whitespace
  # (`day8/re-frame2          {:local/root …}`) so we collapse all
  # runs of whitespace to a single space before matching.
  normalised="$(tr -s '[:space:]' ' ' < "${deps_file}")"
  tool_expected=""
  while IFS='|' read -r entry_tool entry_local_root; do
    [[ -z "${entry_tool}" ]] && continue
    [[ "${entry_tool}" == "${tool}" ]] || continue
    if ! grep -qF "${entry_local_root}" <<< "${normalised}"; then
      echo "::error file=${rel_label}::expected '${entry_local_root}' (lockstep contract; the release workflow rewrites this to :mvn/version at deploy time)"
      errors=$((errors + 1))
    fi
    tool_expected+="${entry_local_root}"$'\n'
  done <<< "${TOOLS_LOCAL_ROOTS}"

  # rf2-7fxf8 — and the converse. This is the direction that was missing,
  # and the direction Xray drifted in.
  assert_local_roots_inventoried "${deps_file}" "${rel_label}" "${tool_expected}"
done

if [[ "${errors}" -gt 0 ]]; then
  echo "::error::lockstep version verification FAILED (${errors} drift(s) detected)"
  exit 1
fi

total_count=$((${#ARTEFACTS[@]} + ${#TOOLS[@]}))
echo "lockstep version verification PASSED — all ${total_count} artefacts (${#ARTEFACTS[@]} implementation/ + ${#TOOLS[@]} tools/) pinned to repo-root VERSION ${VERSION}"
# rf2-7fxf8 — report what was actually SEEN, not merely what was listed.
# The line above was printed unchanged while ten in-repo coordinates were
# outside the inventory entirely; this one is derived from the committed
# deps.edn files, so it cannot overstate the gate's reach.
echo "lockstep coordinate inventory COMPLETE — ${coords_checked} in-repo :local/root coordinate(s) declared across those artefacts, every one of them inventoried here"
exit 0
