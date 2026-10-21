#!/usr/bin/env bash
# check-consumer-classpath.sh
#
# # Why this exists
#
# Every suite in this repository runs on a classpath no consumer has: the
# aggregate implementation/deps.edn puts every artefact on one classpath by
# :local/root, and each artefact's :test alias adds siblings its published
# :deps lack. A require that resolves only through a sibling therefore stays
# green everywhere here and fails on a consumer's machine — and Clojars has no
# yank, so a release that ships that hole ships it forever. The POM preflights
# (preflight-*-package.sh) check what a POM declares, never what it resolves.
#
# This check asks the consumer's question before anything publishes: given
# exactly the coordinates the documentation tells a consumer to write, does
# each require the documentation tells them to write resolve to exactly one
# file on the resulting classpath?
#
# # What it does
#
#   1. Builds every publishable artefact the way release.yml publishes it: a
#      scratch copy of its directory, the :local/root → :mvn/version rewrite
#      (rewrite-local-root-coord.sh) on that copy, then `clein jar` and
#      `clein pom`, installed as jar + POM into a scratch Maven local repo.
#      The publishable set is the :clein/build authority
#      (implementation/scripts/lib/publishable-runtimes.cjs), the same one
#      verify-version-lockstep.sh reads.
#   2. Reads each documented coordinate set, with its documented requires,
#      from the files that document it (check-consumer-classpath.clj names
#      them): the template's substrate deps.edn files, core's static feature
#      table, and the installation blocks of the UIx and Fresco pages. No
#      coordinate is spelled here, so renaming the coordinates needs no edit.
#   3. For each set, writes a deps.edn naming only that set (publishable libs
#      at the release VERSION) against the scratch repo, runs
#      `clojure -Srepro -Spath`, and looks each documented require up as
#      .clj/.cljc/.cljs across the classpath entries.
#
# It fails on a require no entry ships (a hole) and on one shipped by more
# than one entry (two jars carrying one namespace). It loads nothing and runs
# no shadow-cljs: transitive requires and CLJS compiles belong to the
# post-publish cold-repo gate.
#
# # Exit status
#
#   0  every documented require resolves to exactly one classpath entry
#   1  at least one hole or duplicate, or a set that does not resolve
#   2  input the check cannot classify (a missing documented set, a published
#      :local/root that is not a sibling artefact, ...) — fail closed
#   other  a build step failed (its own exit status)
#
# # Runner / portability
#
# Written for the Linux runners (release.yml's pre-release `test` job and the
# nightly expensive-tests.yml). Needs the Clojure CLI, a JDK, node (the
# publishable-artefact listing) and python3 (the rewrite step). It also runs
# under Git Bash on Windows, where the JVM is handed mixed-form paths.
#
# # Usage
#
#   ./.github/scripts/check-consumer-classpath.sh

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"

# The JVM reads paths natively: under Git Bash a POSIX spelling such as /tmp
# names a different directory for java.exe than for bash.
native() { if command -v cygpath >/dev/null 2>&1; then cygpath -m "$1"; else printf '%s\n' "$1"; fi; }

HELPER="$(native "${SCRIPT_DIR}/check-consumer-classpath.clj")"
VERSION="$(tr -d '[:space:]' < "${REPO_ROOT}/VERSION")"
if [ -z "${VERSION}" ]; then
  echo "::error file=VERSION::consumer-classpath: the repo-root VERSION file is empty"
  exit 2
fi

# Inside the checkout, on the ignored /target/ path, because clein runs
# `git rev-list HEAD --count` for every :version and fails outside a work
# tree — release.yml's clein always runs inside its checkout.
mkdir -p "${REPO_ROOT}/target"
SCRATCH="$(mktemp -d "${REPO_ROOT}/target/consumer-classpath.XXXXXX")"
trap 'status=$?; rm -rf "${SCRATCH}"; rmdir "${REPO_ROOT}/target" 2>/dev/null || true; exit "${status}"' EXIT
TREE="${SCRATCH}/tree"
M2="${SCRATCH}/m2"
SETS="${SCRATCH}/sets"
mkdir -p "${TREE}/implementation" "${M2}" "${SETS}"
cp -f "${REPO_ROOT}/VERSION" "${TREE}/VERSION"

helper() { (cd "${SCRATCH}" && clojure -Srepro -M "${HELPER}" "$@"); }

echo "consumer-classpath: VERSION ${VERSION}, scratch ${SCRATCH}"

# ── 1. the publishable artefacts, in build order ─────────────────────────────
rels_out="$(node "${REPO_ROOT}/implementation/scripts/lib/publishable-runtimes.cjs" "${REPO_ROOT}/implementation")"
mapfile -t RELS <<< "${rels_out}"
if [ -z "${rels_out}" ] || [ "${#RELS[@]}" -eq 0 ]; then
  echo "::error::consumer-classpath: no publishable artefact found under implementation/"
  exit 2
fi
plan_out="$(helper plan "$(native "${REPO_ROOT}/implementation")" "${RELS[@]}")"

ORDER=()
declare -A LIB ROOTS
while IFS= read -r line; do
  # A JVM on Windows ends its lines with CRLF.
  IFS=$'\t' read -r tag rel lib roots <<< "${line%$'\r'}"
  [ "${tag}" = "PLAN" ] || continue
  ORDER+=("${rel}")
  LIB[${rel}]="${lib}"
  ROOTS[${rel}]="${roots}"
done <<< "${plan_out}"
if [ "${#ORDER[@]}" -ne "${#RELS[@]}" ]; then
  echo "::error::consumer-classpath: the build plan names ${#ORDER[@]} artefact(s), the :clein/build authority ${#RELS[@]}"
  exit 2
fi

# ── 2. scratch copies, rewritten exactly as release.yml rewrites them ───────
LOCALIZE=()
for rel in "${ORDER[@]}"; do
  mkdir -p "${TREE}/implementation/${rel}"
  tar -C "${REPO_ROOT}/implementation/${rel}" \
      --exclude=target --exclude=.cpcache --exclude=node_modules --exclude=.shadow-cljs \
      -cf - . | tar -C "${TREE}/implementation/${rel}" -xf -
  for root in ${ROOTS[${rel}]}; do
    (cd "${TREE}/implementation/${rel}" && "${SCRIPT_DIR}/rewrite-local-root-coord.sh" "${root}" "${VERSION}")
  done
  LOCALIZE+=("$(native "${TREE}/implementation/${rel}/deps.edn")")
done
helper localize "$(native "${M2}")" "${LOCALIZE[@]}"

# ── 3. jar + POM per artefact, installed into the scratch repo ──────────────
for rel in "${ORDER[@]}"; do
  lib="${LIB[${rel}]}"
  group="${lib%%/*}"
  artifact="${lib#*/}"
  dir="${TREE}/implementation/${rel}"
  echo "consumer-classpath: building ${lib} ${VERSION} from implementation/${rel}"
  (cd "${dir}" && clojure -M:clein jar && clojure -M:clein pom --target-dir "$(native "${SCRATCH}/pom/${rel}")")
  jars=("${dir}"/target/*.jar)
  if [ "${#jars[@]}" -ne 1 ] || [ ! -f "${jars[0]}" ]; then
    echo "::error::consumer-classpath: expected exactly one jar under implementation/${rel}/target, found ${#jars[@]}"
    exit 2
  fi
  dest="${M2}/${group//.//}/${artifact}/${VERSION}"
  mkdir -p "${dest}"
  cp -f "${jars[0]}" "${dest}/${artifact}-${VERSION}.jar"
  cp -f "${SCRATCH}/pom/${rel}/pom.xml" "${dest}/${artifact}-${VERSION}.pom"
done

# ── 4. each documented set, resolved against the scratch repo ───────────────
helper sets "$(native "${REPO_ROOT}")" "${VERSION}" "$(native "${M2}")" "$(native "${SETS}")" "${RELS[@]}"
for set_dir in "${SETS}"/*/; do
  if (cd "${set_dir}" && clojure -Srepro -Spath > classpath.out 2> classpath.err); then
    echo 0 > "${set_dir}/classpath.exit"
  else
    echo "$?" > "${set_dir}/classpath.exit"
  fi
done

# ── 5. the verdict ──────────────────────────────────────────────────────────
helper lookup "$(native "${SETS}")"
