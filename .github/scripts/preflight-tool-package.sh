#!/usr/bin/env sh
# preflight-tool-package.sh (rf2-2ii52)
#
# # Why this exists
#
# `clein pom` can only express an `:mvn/version` coordinate. Handed
# anything else it prints `Skipping coordinate: …` and writes a pom
# WITHOUT it. Two kinds of coordinate hit that:
#
#   - `:local/root`, which the `:local/root → :mvn/version` rewrite
#     exists to repair (rf2-do3m2 / #6340); and
#   - `:git/url`, for which there is NO repair — if the library is not on
#     Clojars there is no version to rewrite to. That is the hole that
#     left `day8/re-frame2-mcp-base` and `day8/re-frame2-story-mcp`
#     un-publishable until rf2-2ii52 vendored `day8/de-dupe` away.
#
# Clojars has no yank, so a pom with a hole in it is unrecoverable —
# bump-and-supersede only. This script is the gate that proves the
# rewrite took effect, run AFTER it and BEFORE `clojure -M:clein deploy`.
#
# # Everything is DERIVED from the committed deps.edn
#
# There is no roster in this file. The required in-repo set, the expected
# third-party set, and the refusal on an inexpressible coordinate all come
# out of the artefact's own `deps.edn` — read from the PRISTINE COMMITTED
# copy (`git show HEAD:…`), because the workspace copy has already been
# rewritten in place by the time this runs.
#
# That is deliberate, and it is the lesson of the two gates that drifted.
# preflight-story-package.sh carries a literal expected set;
# release-xray.yml carried a literal rewrite list that fell from ten
# coordinates to two without a gate noticing; verify-version-lockstep.sh
# inventoried 8 of 18 coordinates while printing "all artefacts, 0
# drifts". A one-directional roster cannot report on what it does not
# list, so its green is an ACTIVE false assurance. Add a dependency to the
# artefact and this gate demands it on the next release with no edit here.
#
# The EDN is parsed with Clojure's own reader and the pom with
# ElementTree, never grepped: this is the last gate before an irreversible
# publish, and a text parser that mis-reads a reformatted file would
# produce exactly the false PASS the script exists to prevent.
#
# # Assertions
#
#   1. the committed deps.edn declares NO runtime coordinate `clein pom`
#      cannot express (no `:git/url` and no bare coordinate) — refused up
#      front, because no amount of rewriting repairs one.
#   2. every DIRECT dependency in the generated pom carries a COMPLETE
#      coordinate — non-empty groupId, artifactId AND version. A published
#      GAV with a hole in it is unresolvable on the consumer's machine.
#   3. every `:local/root` coordinate the committed deps.edn declares is
#      PRESENT in the pom, at exactly the lockstep VERSION. A rewrite that
#      fired with the wrong value is as broken as one that did not fire.
#   4. every `:mvn/version` coordinate it declares is present, with a
#      non-empty version. The literal pins live in deps.edn where
#      verify-version-lockstep.sh already guards them, so this asserts
#      presence, not equality.
#   5. NOTHING ELSE is published. A dependency that reached the pom
#      without being in the committed main `:deps` is an alias leak (a
#      `:test` dep escaping into the jar), and it reds here.
#
# `org.clojure/clojure` is the one allowed extra: the Clojure CLI's root
# basis contributes it whether or not the artefact declares it, so it
# appears in the pom of an artefact whose deps.edn never mentions it
# (story-mcp is exactly that case). Allowed, not required — this gate
# should not red on a change in CLI internals.
#
# # Runner / portability
#
# Linux-runner-only by design (callers are the release workflows on
# ubuntu-latest). POSIX sh + the Clojure CLI + python3, all of which the
# deploy job already has. No .ps1 sibling (same rationale as
# preflight-story-package.sh).
#
# # Usage
#
#   ./.github/scripts/preflight-tool-package.sh GROUP/ARTIFACT VERSION [DIR]
#
# DIR defaults to the current working directory (the release workflows run
# it with `working-directory:` set to the artefact).

set -eu

LIB="${1:?usage: preflight-tool-package.sh GROUP/ARTIFACT VERSION [DIR]}"
VERSION="${2:?usage: preflight-tool-package.sh GROUP/ARTIFACT VERSION [DIR]}"
ARTEFACT_DIR="${3:-.}"
cd "$ARTEFACT_DIR"

GROUP=$(printf '%s' "$LIB" | cut -d/ -f1)
ARTIFACT=$(printf '%s' "$LIB" | cut -d/ -f2)
if [ -z "$GROUP" ] || [ -z "$ARTIFACT" ] || [ "$GROUP" = "$LIB" ]; then
  echo "::error::preflight: LIB must be GROUP/ARTIFACT (got '$LIB')"
  exit 2
fi

REPO_ROOT=$(git rev-parse --show-toplevel)
REL_DIR=$(git rev-parse --show-prefix)
REL_DEPS="${REL_DIR}deps.edn"

PRISTINE_DEPS=$(mktemp)
COORDS_FILE=$(mktemp)
SCRATCH=$(mktemp -d)
trap 'rm -rf "$PRISTINE_DEPS" "$COORDS_FILE" "$SCRATCH"' EXIT

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

# The COMMITTED deps.edn — the workspace copy has already been rewritten in
# place by the rewrite step, so it can no longer say which coordinates were
# supposed to be rewritten, nor what the artefact actually depends on.
if ! git -C "$REPO_ROOT" show "HEAD:${REL_DEPS}" > "$PRISTINE_DEPS"; then
  echo "::error::preflight: could not read the committed ${REL_DEPS} from HEAD"
  exit 2
fi

echo "preflight: deriving ${LIB}'s runtime coordinates from the committed ${REL_DEPS}"

# One `<kind> <group> <artifact>` line per main-:deps coordinate, where
# <kind> is local-root / mvn / UNEXPRESSIBLE. Alias :extra-deps are out of
# scope by design: build-time only, never reaching the pom — the same
# main-:deps scoping verify-version-lockstep.sh uses. The reader runs in a
# scratch directory so reading a deps.edn does not require resolving the
# graph that deps.edn describes.
( cd "$SCRATCH" && clojure -Sdeps '{:paths []}' -M -e "
(require '[clojure.edn :as edn] '[clojure.string :as str])
(let [deps (:deps (edn/read-string (slurp \"$(jvm_path "$PRISTINE_DEPS")\")))]
  (spit \"$(jvm_path "$COORDS_FILE")\"
        (str/join (for [[lib coord] (sort-by key deps)
                        :let [kind (cond (:local/root coord)  \"local-root\"
                                         (:mvn/version coord) \"mvn\"
                                         :else                \"UNEXPRESSIBLE\")]]
                    (str kind \" \" (or (namespace lib) (name lib))
                         \" \" (name lib) \"\n\")))))" ) > /dev/null

if [ ! -s "$COORDS_FILE" ]; then
  echo "::error::preflight: found ZERO runtime coordinates in the committed ${REL_DEPS} — the reader found nothing to require, which means this gate would pass vacuously. Refusing."
  exit 2
fi
echo "preflight: derived coordinates:"
sed 's/^/  /' "$COORDS_FILE"

echo "preflight: building ${LIB} pom at lockstep version ${VERSION} (build only — NOT deploy)"
clojure -M:clein pom

# clein writes the pom under target/classes/META-INF/maven/<group>/<artifact>/pom.xml
POM="target/classes/META-INF/maven/${GROUP}/${ARTIFACT}/pom.xml"
if [ ! -f "$POM" ]; then
  echo "::error::preflight: expected pom not found at $POM"
  exit 2
fi
echo "preflight: pom = $POM"

if ! LIB="$LIB" VERSION="$VERSION" COORDS_FILE="$COORDS_FILE" REL_DEPS="$REL_DEPS" \
     python3 - "$POM" <<'PYTHON'
import os
import sys
import xml.etree.ElementTree as ET

pom_path = sys.argv[1]
lib = os.environ["LIB"]
version = os.environ["VERSION"]
rel_deps = os.environ["REL_DEPS"]

IN_REPO = set()
THIRD_PARTY = set()
UNEXPRESSIBLE = set()

with open(os.environ["COORDS_FILE"]) as handle:
    for line in handle:
        parts = line.split()
        if not parts:
            continue
        kind, group, artifact = parts[0], parts[1], parts[2]
        {"local-root": IN_REPO,
         "mvn": THIRD_PARTY,
         "UNEXPRESSIBLE": UNEXPRESSIBLE}[kind].add((group, artifact))

# The Clojure CLI's root basis contributes this whether or not the artefact
# declares it. Allowed, never required.
IMPLICIT_ROOT = {("org.clojure", "clojure")}

errors = []

# ---- 1. inexpressible coordinates, refused before the pom is even read ----
for coord in sorted(UNEXPRESSIBLE):
    errors.append(
        "%s/%s is declared in %s with a coordinate `clein pom` CANNOT"
        " express (a :git/url, or no :mvn/version and no :local/root)."
        " clein drops it SILENTLY, so the published jar would omit a"
        " runtime dependency and Clojars has no yank. There is no rewrite"
        " that repairs this — publish the library to Clojars under a"
        " coordinate this artefact can pin, VENDOR it into the artefact"
        " (rf2-2ii52 did exactly that for day8/de-dupe), or move the edge"
        " to late-bind."
        % (coord[0], coord[1], rel_deps)
    )

EXPECTED = IN_REPO | THIRD_PARTY

if not EXPECTED and not errors:
    print("::error::preflight: the committed %s declares no expressible"
          " runtime coordinate, so every assertion below would be vacuous."
          " Refusing." % rel_deps)
    sys.exit(1)


def localname(tag):
    """Tag name without its {namespace} prefix."""
    return tag.rsplit("}", 1)[-1]


def child_text(parent, name):
    for el in parent:
        if localname(el.tag) == name:
            return (el.text or "").strip()
    return ""


try:
    root = ET.parse(pom_path).getroot()
except ET.ParseError as exc:
    print("::error::preflight: pom is not well-formed XML: %s" % exc)
    sys.exit(1)

# Only <project>'s DIRECT <dependencies> child, never a
# <dependencyManagement> block's (which declares versions but not deps).
dependencies = []
for container in root:
    if localname(container.tag) != "dependencies":
        continue
    dependencies.extend(
        el for el in container if localname(el.tag) == "dependency"
    )

# ---- 2. every declared dependency carries a complete GAV ------------------
declared = {}
for index, dep in enumerate(dependencies, start=1):
    gav = {name: child_text(dep, name)
           for name in ("groupId", "artifactId", "version")}
    label = "%s/%s" % (gav["groupId"] or "<no groupId>",
                       gav["artifactId"] or "<no artifactId>")
    for name in ("groupId", "artifactId", "version"):
        if not gav[name]:
            errors.append(
                "dependency #%d (%s) has a missing or empty <%s> — an"
                " incomplete GAV is unresolvable for consumers"
                % (index, label, name)
            )
    if gav["groupId"] and gav["artifactId"]:
        declared[(gav["groupId"], gav["artifactId"])] = gav["version"]

declared_set = set(declared)

MISSING_HINT = (
    " NB: `clein pom` SKIPS :local/root coordinates outright, so this is"
    " exactly the pom produced when the release workflow's :local/root ->"
    " :mvn/version rewrite did not cover this coordinate. The rewrite"
    " derives its set from this same deps.edn"
    " (.github/scripts/rewrite-in-repo-coords.sh), so a coordinate missing"
    " here means the rewrite step did not run, or ran in the wrong"
    " directory."
)

# ---- 3. every in-repo coordinate present, at the lockstep VERSION --------
missing_in_repo = sorted(IN_REPO - declared_set)
for coord in missing_in_repo:
    errors.append(
        "pom is MISSING the in-repo dependency %s/%s, which %s declares at"
        " :local/root.%s" % (coord[0], coord[1], rel_deps, MISSING_HINT)
    )

for coord in sorted(IN_REPO & declared_set):
    found = declared[coord]
    if found != version:
        errors.append(
            "in-repo dependency %s/%s is at version '%s', expected the"
            " lockstep '%s'. Every published artefact ships at the repo-root"
            " VERSION (spec/Conventions.md #packaging-conventions) — a"
            " rewrite that fired with the wrong value is as broken as one"
            " that did not fire at all."
            % (coord[0], coord[1], found, version)
        )

# ---- 4. every third-party coordinate present ----------------------------
for coord in sorted(THIRD_PARTY - declared_set):
    errors.append(
        "pom is MISSING the third-party dependency %s/%s, which %s declares"
        " at :mvn/version. A consumer resolving this jar would not get it."
        % (coord[0], coord[1], rel_deps)
    )

# ---- 5. and nothing else ------------------------------------------------
for coord in sorted(declared_set - EXPECTED - IMPLICIT_ROOT):
    errors.append(
        "pom declares the DIRECT dependency %s/%s, which the committed %s"
        " does NOT declare in its main :deps. That is an alias leak — a"
        " :test / :gen / :clein dependency escaping into the published jar."
        " Move it back under its alias, or declare it as a real runtime"
        " dependency."
        % (coord[0], coord[1], rel_deps)
    )

for message in errors:
    print("::error::preflight: %s" % message)

if errors:
    if missing_in_repo:
        print(
            "::error::preflight: %d of %d in-repo coordinate(s) are absent"
            " from the pom: %s"
            % (len(missing_in_repo), len(IN_REPO),
               ", ".join("%s/%s" % c for c in missing_in_repo))
        )
    sys.exit(1)

print("preflight: %s's pom declares exactly the dependency set %s does"
      % (lib, rel_deps))
print("  in-repo (at lockstep %s): %s"
      % (version, ", ".join("%s/%s" % c for c in sorted(IN_REPO)) or "(none)"))
print("  third-party: %s"
      % (", ".join("%s/%s" % c for c in sorted(THIRD_PARTY)) or "(none)"))
PYTHON
then
  echo "::error::preflight: ${LIB} published-package verification FAILED — ABORTING before clein deploy touches Clojars"
  exit 1
fi

echo "preflight: ${LIB} published-package verification PASSED"
exit 0
