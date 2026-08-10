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
#   ./.github/scripts/verify-version-lockstep.sh --self-test
#
# `--self-test` checks the CHECKERS rather than the tree: it runs
# check_no_git_coords_in_runtime_deps and check_clein_main over sets of
# synthetic deps.edn and asserts each verdict. Both shipped once as
# line-oriented text greps and both were wrong for the same reason — the first
# only saw a git coordinate when the library symbol and its map sat on the same
# physical line (rf2-2ii52), the second asked the WHOLE FILE for a `:main` at
# the start of a line rather than the `:clein/build` map it claims to check
# (rf2-1xacx). Each passed a verdict on a tree it had not actually read. Both
# read EDN structure now, and the layouts are pinned below.
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

# ---- rf2-2ii52: the runtime coordinates `clein pom` cannot express -------
#
# `clein pom` can only express an :mvn/version coordinate. Handed a git
# coordinate it prints "Skipping coordinate: …" and generates a pom WITHOUT
# it — the same silent skip it has for :local/root, which the
# :local/root → :mvn/version rewrite exists to repair. There is no equivalent
# repair for a git coord: if the library is not on Clojars there is no version
# to rewrite to. Publishing anyway ships a jar whose pom omits a runtime
# dependency, and Clojars has no yank. This is not hypothetical — mcp-base and
# story-mcp both carried `day8/de-dupe` by :git/url, and the pom clein
# generated for story-mcp carried two of its ten dependencies.
#
# THIS CHECK USED TO BE A TEXT GREP, AND THE TEXT GREP WAS FORMATTING-BOUND.
# It extracted with `grep -oE '[^[:space:]{]+[[:space:]]+\{:git/url'`, which
# can only match when the library symbol and `{:git/url` sit on the SAME
# physical line. Perfectly ordinary deps.edn layout — the symbol on one line,
# its coordinate map opening on the next — produced no finding at all. So the
# gate passed because the text happened to be laid out the way the regex
# expected, not because the tree was clean, and the prevention this check was
# added to provide was really a property of one file's whitespace. It was
# wrong in the other direction too: it stripped only `;;`, so a `;`-commented
# coordinate, a `#_`-discarded one, and the characters `{:git/url` sitting
# inside a string literal each produced a spurious finding; and it delimited
# the main :deps map with a `sed` line range running from `:deps` to
# `:aliases`, which swallows every alias in the file if a deps.edn happens to
# declare `:aliases` first. Of seven probe shapes it judged one correctly.
#
# So it reads STRUCTURE now, through the same node EDN authority
# local_root_coords() uses above — and node is the runtime that is actually
# available here: test.yml runs this gate on a bare `actions/checkout` with no
# JDK, so a Clojure reader would break ordinary CI. The tag-push arm
# (.github/scripts/preflight-tool-package.sh) runs where clojure IS installed
# and asserts the stronger property this one cannot: that every coordinate
# resolves to a real GAV in a pom clein actually generated.
#
# Scope is the MAIN :deps map only — alias :extra-deps (clein itself, the
# cognitect test-runner) are build-time and never reach a published pom.
# Fetching that map BY KEY is what makes the scoping exact, rather than a
# guess about which line `:aliases` starts on.
#
# The class is "git coordinate", not "the literal characters :git/url". For an
# `io.github.…` / `com.github.…` library tools.deps infers the URL from the
# library name, so `{:git/tag "v0.5.1" :git/sha "dfb30dd"}` is a complete git
# coordinate carrying no :git/url at all — a shape this very repo already uses
# for the test-runner. Any key in the `git` namespace therefore counts, which
# is a strict superset of what the grep caught.
#
# There is deliberately NO allowlist. One used to sit here — a single
# `GIT_COORD_KNOWN_UNRESOLVED=(day8/de-dupe)` entry holding open the operator
# decision on the last such coordinate, with the instruction "DELETE THIS LINE
# when the ruling lands". Mike ruled route (b) on rf2-2ii52 (vendor the codec
# into mcp-base) and the line went with it. An allowlist is the wrong shape
# for this check anyway: an unpublishable runtime coordinate is not a policy
# exception, it is an artefact that cannot ship a correct pom. If one appears
# again the answer is to publish it, vendor it, or move the edge to late-bind
# — not to record it here.

# One `lib :git/key …` line per main-:deps coordinate carrying a key in the
# `git` namespace, in declaration order. Sibling of local_root_coords(): same
# reader, same main-:deps scoping, same fail-closed posture.
git_coord_libs() {
  node -e '
    const fs = require("fs");
    const { readEdn, isMap, isKeyword, mapGetKeyword } = require(process.argv[1]);
    const top = readEdn(fs.readFileSync(process.argv[2], "utf8"));
    if (!isMap(top)) throw new Error("top-level form is not a map");
    const deps = mapGetKeyword(top, "deps");
    if (deps === undefined) process.exit(0);
    if (!isMap(deps)) throw new Error(":deps is not a map");
    for (const [lib, coord] of deps.entries) {
      if (!isMap(coord)) continue;
      const gitKeys = coord.entries
        .filter(([k]) => isKeyword(k) && k.name.startsWith("git/"))
        .map(([k]) => ":" + k.name);
      if (gitKeys.length === 0) continue;
      if (lib === null || lib.edn !== "symbol") {
        throw new Error("a dependency key in :deps is not a symbol");
      }
      process.stdout.write(lib.name + " " + gitKeys.join(" ") + "\n");
    }
  ' "${EDN_READER}" "$1"
}

# Fail-closed on an unreadable deps.edn (exit, not a counted drift), for the
# same reason assert_local_roots_inventoried does: a reader that could not
# enumerate the coordinates cannot be the basis for reporting that none of
# them is a git coord.
check_no_git_coords_in_runtime_deps() {
  local deps_file="$1"
  local rel_label="$2"
  local derived entry lib keys

  if ! derived="$(git_coord_libs "${deps_file}")"; then
    echo "::error file=${rel_label}::failed to read this deps.edn's runtime coordinates structurally via implementation/scripts/lib/edn.cjs (is node on PATH? is the EDN well-formed?) — refusing to report a pom-expressibility verdict this script could not verify"
    exit 2
  fi

  while IFS= read -r entry; do
    [[ -z "${entry}" ]] && continue
    lib="${entry%% *}"
    keys="${entry#* }"
    echo "::error file=${rel_label}::runtime dep '${lib}' is a git coordinate (${keys}) — 'clein pom' SKIPS it silently, so a published jar would omit a runtime dependency and Clojars has no yank. Publish it to Clojars under a coordinate this artefact can pin, vendor it into the artefact, or move the edge to late-bind."
    errors=$((errors + 1))
  done <<< "${derived}"
}

# ---- rf2-1xacx: clein's own spec requires :main in :clein/build ----------
#
# Without it every clein invocation in the directory aborts before doing any
# work:
#
#   Error in the :clein/build map: {…} - failed: (contains? % :main)
#
# An artefact can therefore be perfectly version-pinned and still be impossible
# to package. rf2-4u3t1 found this in machines-viz; rf2-2ii52 found the same
# thing in mcp-base. Asserting it here is what stops a third.
#
# THIS CHECK WAS THE SAME FAULT AS ITS SIBLING ABOVE, ONE FUNCTION OVER. It
# asserted with `grep -qE '^[[:space:]]*:main[[:space:]]' "${deps_file}"` — a
# WHOLE-FILE text grep that never went near the `:aliases -> :clein/build` map
# it claims to be reading. It was wrong in both directions at once: a `:main`
# sitting in any OTHER alias (a `:run` alias, say) satisfied it, so an
# un-buildable artefact reported clean; and a perfectly good
# `{:lib day8/x :main day8.x.core}` written on ONE LINE did not, so a buildable
# one reported broken. A `:main ` at the start of a line inside a multiline
# STRING satisfied it too. Of six probe shapes it judged three correctly.
#
# So it fetches `:aliases`, then `:clein/build`, then `:main` BY KEY, through
# the same node EDN authority git_coord_libs() and local_root_coords() use —
# node being the runtime actually available: test.yml runs this gate on a bare
# `actions/checkout` with no JDK.
#
# Scope is NOT narrowed. Everything the grep flagged still reds, and a missing
# `:clein/build` alias — which the grep only caught by accident, when the file
# happened to carry no line-initial `:main` anywhere — now reds on its own
# terms, because an artefact in TOOLS with no build alias is exactly as
# un-packageable as one whose build alias lacks `:main`.

# `present`, `absent`, or `no-build-alias` for the deps.edn's
# `:aliases -> :clein/build -> :main`. Sibling of git_coord_libs(): same
# reader, same fetch-by-key scoping, same fail-closed posture.
clein_build_main() {
  node -e '
    const fs = require("fs");
    const { readEdn, isMap, mapGetKeyword } = require(process.argv[1]);
    const top = readEdn(fs.readFileSync(process.argv[2], "utf8"));
    if (!isMap(top)) throw new Error("top-level form is not a map");
    const aliases = mapGetKeyword(top, "aliases");
    if (aliases === undefined) { process.stdout.write("no-build-alias\n"); process.exit(0); }
    if (!isMap(aliases)) throw new Error(":aliases is not a map");
    const build = mapGetKeyword(aliases, "clein/build");
    if (build === undefined) { process.stdout.write("no-build-alias\n"); process.exit(0); }
    if (!isMap(build)) throw new Error(":clein/build is not a map");
    process.stdout.write(
      (mapGetKeyword(build, "main") === undefined ? "absent" : "present") + "\n",
    );
  ' "${EDN_READER}" "$1"
}

# Fail-closed on an unreadable deps.edn (exit, not a counted drift), for the
# same reason its two siblings do: a reader that could not reach the build
# alias cannot be the basis for reporting that the artefact is buildable.
check_clein_main() {
  local deps_file="$1"
  local rel_label="$2"
  local verdict

  if ! verdict="$(clein_build_main "${deps_file}")"; then
    echo "::error file=${rel_label}::failed to read this deps.edn's :aliases -> :clein/build map structurally via implementation/scripts/lib/edn.cjs (is node on PATH? is the EDN well-formed?) — refusing to report a buildability verdict this script could not verify"
    exit 2
  fi

  case "${verdict}" in
    present) ;;
    absent)
      echo "::error file=${rel_label}:::clein/build is missing :main — clein's build-opts spec requires it, so every clein invocation in this directory aborts before doing any work (artefact is un-BUILDABLE)"
      errors=$((errors + 1))
      ;;
    *)
      echo "::error file=${rel_label}::no :aliases -> :clein/build alias — this artefact is published by clein, and clein has nothing to read here, so it cannot be BUILT at all"
      errors=$((errors + 1))
      ;;
  esac
}

# `--self-test` — the mutation pin, and the reason the rewrites above are
# checkable rather than merely asserted. Each case is a deps.edn a checker has
# to judge. Run by .github/workflows/test.yml alongside the gate itself.
#
# $1 checker function, $2 label, $3 expectation (FLAGGED | CLEAN | REFUSED),
# $4 deps.edn text. The checker runs inside a command substitution so its
# fail-closed `exit 2` bounds itself to that subshell and its `errors`
# increment cannot leak into the real tally.
_self_test_case() {
  local checker="$1" label="$2" expect="$3" body="$4" out rc verdict mark
  self_test_cases=$((self_test_cases + 1))
  printf '%s\n' "${body}" > "${SELF_TEST_TMP}/deps.edn"
  if out="$("${checker}" "${SELF_TEST_TMP}/deps.edn" "self-test" 2>&1)"; then
    rc=0
  else
    rc=$?
  fi
  if [[ "${rc}" -eq 2 ]]; then
    verdict=REFUSED
  elif grep -q '::error' <<< "${out}"; then
    verdict=FLAGGED
  else
    verdict=CLEAN
  fi
  if [[ "${verdict}" == "${expect}" ]]; then
    mark="  ok"
  else
    mark="FAIL"
    self_test_failures=$((self_test_failures + 1))
  fi
  printf '  %s  %-52s expected %-8s got %s\n' "${mark}" "${label}" "${expect}" "${verdict}"
}

# The same-line and multiline forms are the pair the rf2-2ii52 reopen named,
# and the negative cases pin the scoping the grep also got wrong.
git_coord_self_test() {
  _git_coord_case() { _self_test_case check_no_git_coords_in_runtime_deps "$@"; }

  echo "self-test: check_no_git_coords_in_runtime_deps"

  # --- a runtime git coordinate must be found however it is laid out -------
  _git_coord_case 'same-line symbol and coordinate map' FLAGGED \
'{:deps {org.clojure/clojure {:mvn/version "1.12.0"}
        day8/de-dupe {:git/url "https://github.com/day8/de-dupe.git" :git/sha "abc1234"}}}'

  # The shape the grep could not see: symbol on one line, map on the next.
  _git_coord_case 'symbol on one line, coordinate map on the next' FLAGGED \
'{:deps {org.clojure/clojure {:mvn/version "1.12.0"}
        day8/de-dupe
        {:git/url "https://github.com/day8/de-dupe.git"
         :git/sha "abc1234"}}}'

  _git_coord_case 'coordinate keys split across lines' FLAGGED \
'{:deps {org.clojure/clojure {:mvn/version "1.12.0"}
        day8/de-dupe {
          :git/url "https://github.com/day8/de-dupe.git"
          :git/sha "abc1234"}}}'

  # tools.deps infers the URL for io.github.… libs, so this is a complete git
  # coordinate with no :git/url anywhere in it.
  _git_coord_case 'git coordinate with no :git/url (:git/tag + :git/sha)' FLAGGED \
'{:deps {io.github.day8/de-dupe {:git/tag "v0.3.0" :git/sha "abc1234"}}}'

  _git_coord_case 'nested inside an otherwise expressible :deps map' FLAGGED \
'{:paths ["src"]
 :deps {org.clojure/clojure {:mvn/version "1.12.0"}
        day8/re-frame2 {:local/root "../core"}
        day8/de-dupe {:git/tag "v0.3.0"
                      :git/sha "abc1234"}}
 :aliases {}}'

  # --- and must NOT be found where it does not bind at runtime -------------
  _git_coord_case 'build-time git coordinate in an alias :extra-deps' CLEAN \
'{:deps {org.clojure/clojure {:mvn/version "1.12.0"}}
 :aliases {:test {:extra-deps {io.github.cognitect-labs/test-runner
                               {:git/tag "v0.5.1" :git/sha "dfb30dd"}}}}}'

  # …including when :aliases is declared FIRST, which the sed line range that
  # used to delimit the main :deps map swallowed wholesale.
  _git_coord_case 'alias :extra-deps with :aliases declared before :deps' CLEAN \
'{:aliases {:test {:extra-deps {io.github.cognitect-labs/test-runner
                                {:git/tag "v0.5.1" :git/sha "dfb30dd"}}}}
 :deps {org.clojure/clojure {:mvn/version "1.12.0"}}}'

  _git_coord_case 'coordinate commented out with a single ;' CLEAN \
'{:deps {org.clojure/clojure {:mvn/version "1.12.0"}
        ; day8/de-dupe {:git/url "https://github.com/day8/de-dupe.git"}
        }}'

  _git_coord_case 'coordinate removed with a #_ discard' CLEAN \
'{:deps {#_day8/de-dupe #_{:git/url "https://github.com/day8/de-dupe.git"}
        org.clojure/clojure {:mvn/version "1.12.0"}}}'

  _git_coord_case ':git/url appearing inside a string literal' CLEAN \
'{:deps {org.clojure/clojure {:mvn/version "1.12.0"}
        day8/re-frame2 {:local/root "../core ; x {:git/url y"}}}'

  # --- unreadable input is refused, never reported clean -------------------
  _git_coord_case 'malformed EDN' REFUSED \
'{:deps {org.clojure/clojure {:mvn/version "1.12.0"}'
}

# rf2-1xacx. The whole-file grep judged three of these six wrongly — in both
# directions, which is why the pin carries CLEAN cases as well as FLAGGED ones.
clein_main_self_test() {
  _clein_main_case() { _self_test_case check_clein_main "$@"; }

  echo "self-test: check_clein_main"

  # --- :main in the build alias is what makes the artefact buildable -------
  _clein_main_case ':main in :clein/build, one key per line' CLEAN \
'{:aliases {:clein/build {:lib day8/re-frame2-xray
                          :main day8.re-frame2.xray.main
                          :version "0.0.1"}}}'

  # The layout the grep could not see: nothing starts the line but `{`.
  _clein_main_case ':main on the same line as :lib' CLEAN \
'{:aliases {:clein/build {:lib day8/re-frame2-xray :main day8.re-frame2.xray.main}}}'

  # --- and its absence must red however the file is laid out ---------------
  _clein_main_case ':main absent from :clein/build' FLAGGED \
'{:aliases {:clein/build {:lib day8/re-frame2-xray :version "0.0.1"}}}'

  # The shape the grep false-PASSED: `:main` is present in the file, but in an
  # alias clein never reads, so `clojure -M:clein pom` still aborts.
  _clein_main_case ':main only in an unrelated alias' FLAGGED \
'{:aliases {:clein/build {:lib day8/re-frame2-xray}
           :run {:main-opts ["-m" "day8.re-frame2.xray"]
                 :main day8.re-frame2.xray.main}}}'

  # `:main ` at the start of a line inside a multiline string also satisfied
  # the grep. Structure does not see into strings.
  _clein_main_case ':main at line-start inside a string literal' FLAGGED \
'{:aliases {:clein/build {:lib day8/re-frame2-xray
                          :doc "usage:
:main is set below
"}}}'

  _clein_main_case ':main removed with a #_ discard' FLAGGED \
'{:aliases {:clein/build {:lib day8/re-frame2-xray
                          #_:main #_day8.re-frame2.xray.main}}}'

  # --- no build alias at all is un-buildable too, and says so --------------
  _clein_main_case 'no :clein/build alias' FLAGGED \
'{:aliases {:test {:extra-paths ["test"]}}}'

  _clein_main_case 'no :aliases map at all' FLAGGED \
'{:deps {org.clojure/clojure {:mvn/version "1.12.0"}}}'

  # --- rf2-vr11t: the build alias ends with a discarded key ----------------
  # The reader used to throw `unexpected '}'` on this, which made the WHOLE
  # gate exit 2 — un-runnable, from an ordinary commented-out last entry.
  _clein_main_case ':main present, alias ends with a #_ discard' CLEAN \
'{:aliases {:clein/build {:lib day8/re-frame2-xray
                          :main day8.re-frame2.xray.main
                          #_:javac-opts #_["-source" "8"]}}}'

  # --- unreadable input is refused, never reported clean -------------------
  _clein_main_case 'malformed EDN' REFUSED \
'{:aliases {:clein/build {:lib day8/re-frame2-xray'
}

if [[ "${1:-}" == "--self-test" ]]; then
  SELF_TEST_TMP="$(mktemp -d)"
  self_test_cases=0
  self_test_failures=0

  git_coord_self_test
  clein_main_self_test

  rm -rf "${SELF_TEST_TMP}"

  if [[ "${self_test_failures}" -gt 0 ]]; then
    echo "::error::self-test FAILED — ${self_test_failures} of ${self_test_cases} case(s) misjudged"
    exit 1
  fi
  echo "self-test PASSED — all ${self_test_cases} cases judged correctly"
  exit 0
fi

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
# rf2-7fxf8 — Xray's entry listed ONE of its in-repo coordinates and
# story-mcp's listed one of its two, so ten of the eighteen coordinates the
# release workflows must rewrite were asserted by nothing at all. The
# completeness pass at the end of the tools loop now derives the true set
# from each deps.edn, so this list cannot silently fall behind again.
#
# Xray's `day8/re-frame2-freehand` and `day8/re-frame2-hicasso` lines assert
# ONLY what the loop below asserts of every entry: that the coordinate is
# declared at `:local/root` in the committed deps.edn, which is true today
# and green. Neither asserts the artefact is PUBLISHABLE — implementation/
# freehand/deps.edn and implementation/hicasso/deps.edn both deliberately
# carry no `:clein/build` (Freehand's publication is EP-0036 F6 territory;
# Hicasso's release wiring is rf2-hic-008's), so neither coordinate can be
# rewritten to any `:mvn/version`. They are the TWO coordinates
# release-xray.yml deliberately leaves at `:local/root`; the other nine are
# rewritten there. Whether Xray is publishable before they ship is an OPEN
# OPERATOR DECISION (rf2-5dut1 / rf2-hic-023) that this gate neither makes
# nor routes around; preflight-xray-package.sh is where it comes due, by
# refusing the deploy — and its unpublishable-coordinate pin is the ledger.
TOOLS_LOCAL_ROOTS=$(cat <<'EOF'
xray|day8/re-frame2 {:local/root "../../implementation/core"}
xray|day8/re-frame2-epoch {:local/root "../../implementation/epoch"}
xray|day8/re-frame2-routing {:local/root "../../implementation/routing"}
xray|day8/re-frame2-flows {:local/root "../../implementation/flows"}
xray|day8/re-frame2-schemas {:local/root "../../implementation/schemas"}
xray|day8/re-frame2-resources {:local/root "../../implementation/resources"}
xray|day8/re-frame2-machines {:local/root "../../implementation/machines"}
xray|day8/re-frame2-freehand {:local/root "../../implementation/freehand"}
xray|day8/re-frame2-hicasso {:local/root "../../implementation/hicasso"}
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
# coordinate `clein pom` silently DROPS. Both are cheap to assert. Both —
# check_clein_main (rf2-1xacx) and check_no_git_coords_in_runtime_deps
# (rf2-2ii52) — are defined above, next to the EDN reader they share with the
# coordinate-inventory pass, and both are pinned by `--self-test`.

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
