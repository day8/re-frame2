#!/usr/bin/env sh
# rewrite-in-repo-coords.sh (rf2-2ii52)
#
# # What this does
#
# Rewrites EVERY in-repo `:local/root` coordinate in one artefact's
# deps.edn to `:mvn/version <VERSION>`, on the throwaway runner checkout,
# before clein packages the jar. It is a thin derived driver over the
# single-coordinate helper `rewrite-local-root-coord.sh`, which does the
# comment-aware matching and the fail-loud invariants.
#
# # Why it exists: the list must be DERIVED, not typed into a workflow
#
# `clein pom` SILENTLY SKIPS `:local/root` coordinates, so every one of
# them has to be rewritten or the published pom omits a runtime
# dependency — and Clojars has no yank. The existing release workflows
# spell their coordinates out inline, one `rewrite-local-root-coord.sh`
# call per line, and that literal is precisely what drifted: tools/xray
# grew from one in-repo coordinate to TEN while release-xray.yml kept
# rewriting two, and nothing noticed for as long as nobody cut a tag
# (rf2-5dut1 / rf2-7fxf8). A hand-maintained roster cannot report on what
# it does not list.
#
# So this script reads the coordinates OUT OF the artefact's own deps.edn.
# Add a `:local/root` dependency and its rewrite happens on the next
# release with no edit here and none in the workflow.
#
# The EDN is parsed with Clojure's own reader, never grepped: a
# reformatted deps.edn, or a coordinate quoted inside a `;;` comment or a
# `#_` discard, must not be able to change what gets rewritten. The reader
# runs in a scratch directory with `-Sdeps '{:paths []}'` so it resolves
# nothing but Clojure itself — reading a deps.edn must not require
# resolving the graph that deps.edn describes.
#
# # Fail-loud invariants
#
#   - deps.edn missing or unreadable                            → exit 2
#   - ZERO :local/root coordinates found                        → exit 2
#     (a vacuous success here is indistinguishable from a correct one, and
#     the artefact this runs for is expected to have at least one; an
#     artefact with none does not need this script)
#   - any single-coordinate rewrite failing (absent / ambiguous) → its own
#     exit code, propagated (3 / 4 — see rewrite-local-root-coord.sh)
#
# # Runner / portability
#
# Linux-runner-only by design (callers are the release workflows on
# ubuntu-latest). POSIX sh + the Clojure CLI, which the deploy job has
# already installed by the time this runs. No .ps1 sibling (same
# rationale as rewrite-local-root-coord.sh).
#
# # Usage
#
#   ./.github/scripts/rewrite-in-repo-coords.sh VERSION [ARTEFACT_DIR]
#
# ARTEFACT_DIR defaults to the current working directory (the release
# workflows run it with `working-directory:` set to the artefact).

set -eu

VERSION="${1:?usage: rewrite-in-repo-coords.sh VERSION [ARTEFACT_DIR]}"
ARTEFACT_DIR="${2:-.}"
cd "$ARTEFACT_DIR"

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
ARTEFACT_ABS=$(pwd)

if [ ! -f deps.edn ]; then
  echo "::error::expected deps.edn in $ARTEFACT_ABS — not found"
  exit 2
fi

COORDS_FILE=$(mktemp)
SCRATCH=$(mktemp -d)
trap 'rm -rf "$COORDS_FILE" "$SCRATCH"' EXIT

# The Clojure reader below is handed absolute paths. On a POSIX runner they
# pass through unchanged; under Git-for-Windows' MSYS shell a `/tmp/...`
# path is not a path the JVM can open, so convert when cygpath is present.
# Costs nothing on the runner and is what makes this gate verifiable
# locally on a Windows checkout.
jvm_path() {
  if command -v cygpath > /dev/null 2>&1; then
    cygpath -m "$1"
  else
    printf '%s' "$1"
  fi
}

# One `:local/root` path per line, in declaration order. Alias :extra-deps
# are out of scope by design: they are build-time (clein, test-runner,
# test-quiet) and never reach a published pom — the same main-:deps scoping
# verify-version-lockstep.sh uses.
( cd "$SCRATCH" && clojure -Sdeps '{:paths []}' -M -e "
(require '[clojure.edn :as edn] '[clojure.string :as str])
(let [deps (:deps (edn/read-string (slurp \"$(jvm_path "$ARTEFACT_ABS/deps.edn")\")))]
  (spit \"$(jvm_path "$COORDS_FILE")\"
        (str/join (for [[_lib coord] deps
                        :let [root (:local/root coord)]
                        :when root]
                    (str root \"\n\")))))" ) > /dev/null

COUNT=$(grep -c . "$COORDS_FILE" || true)
if [ "$COUNT" -eq 0 ]; then
  echo "::error::rewrite: found ZERO :local/root coordinates in $ARTEFACT_ABS/deps.edn. Either the reader failed or the artefact has no in-repo dependency — in both cases this script has nothing to assert and a silent success would be indistinguishable from a correct one. Refusing."
  exit 2
fi

echo "rewrite: ${COUNT} in-repo coordinate(s) derived from deps.edn:"
sed 's/^/  /' "$COORDS_FILE"

while IFS= read -r local_root; do
  [ -z "$local_root" ] && continue
  "$SCRIPT_DIR/rewrite-local-root-coord.sh" "$local_root" "$VERSION" deps.edn
done < "$COORDS_FILE"

echo "--- Rewritten dep coords:"
grep -E "day8/re-frame2" deps.edn || true
