#!/usr/bin/env sh
# preflight-xray-package.sh
#
# # Why this exists
#
# `clein pom` SILENTLY SKIPS `:local/root` coordinates. tools/xray/deps.edn
# declares TEN of them in its main `:deps` — core, epoch, routing, flows,
# schemas, resources, machines, fresco, machines-viz and reagent-slim —
# and release-xray.yml rewrites all ten. Run against the in-tree deps.edn
# with no rewrite at all, `clein pom` prints ten `Skipping coordinate` lines
# and writes a pom whose `<dependencies>` are four third-party artefacts and
# nothing else.
#
# That is the failure class preflight-story-package.sh closes for Story: the
# consumer installs the artefact, resolves a pom with holes in it, and hits
#
#     No such namespace: day8.re-frame2-epoch.…
#
# at compile time. Clojars has no yank, so the mistake is unrecoverable —
# bump-and-supersede only. This script is the gate that proves the rewrite
# took effect, run AFTER the rewrite and BEFORE `clojure -M:clein deploy`.
#
# # The required set is DERIVED, never hand-maintained
#
# This is the one place this script deliberately departs from its Story
# sibling, and the reason is the failure it exists for. Story's expected set
# is a literal in the script, and Xray's rewrite list is a literal in the
# workflow; a literal can drift away from deps.edn without a single gate
# noticing, because nothing ties it back. So the required set here is READ
# OUT OF deps.edn itself, from the PRISTINE committed copy (`git show
# HEAD:tools/xray/deps.edn` — the workspace copy has already been rewritten
# in place by the time this runs). Add an eleventh `:local/root` coordinate
# to Xray and this gate demands its rewrite on the next release with no edit
# here.
#
# The EDN is parsed with Clojure's own reader rather than grepped: this is
# the last gate before an irreversible publish, and a text parser that
# mis-reads a reformatted deps.edn would produce exactly the false PASS the
# script exists to prevent. The pom is parsed with ElementTree for the same
# reason (mirroring preflight-story-package.sh).
#
# # Assertions
#
#   1. every DIRECT dependency in the generated pom carries a COMPLETE
#      coordinate — non-empty groupId, artifactId AND version. A published
#      GAV with a hole in it is unresolvable on the consumer's machine.
#   2. every in-repo coordinate deps.edn declares at `:local/root` is
#      PRESENT in the pom. This is the skipped-coordinate hole itself.
#   3. every one of them is pinned to the exact lockstep VERSION. A rewrite
#      that fired with the wrong value is as broken as one that did not
#      fire — per spec/Conventions.md §Packaging conventions every
#      published artefact ships at the repo-root VERSION.
#
# There is deliberately NO "no unexpected extras" assertion (Story has
# one). That half needs a hand-maintained roster of third-party deps, which
# is the maintenance shape this script is avoiding, and Xray's third-party
# pins are guarded in deps.edn by the lockstep script.
#
# # An unpublishable coordinate is refused, never special-cased
#
# Every one of Xray's ten in-repo coordinates names an artefact that carries
# a `:clein/build`, so all ten are rewritten and this gate is expected to
# PASS on a correctly-ordered release — a framework `v*` tag at the same
# lockstep VERSION first, then `xray-v*`.
#
# NOTHING HERE IS SPECIAL-CASED TO A COORDINATE, and that is deliberate.
# The required set is DERIVED from the committed deps.edn every run. An
# in-repo coordinate naming an artefact with no `:clein/build` has no GAV to
# rewrite to, so release-xray.yml leaves it at `:local/root`, `clein pom`
# skips it, and this gate REFUSES the tag push with the coordinate named —
# on its own terms, because the derived set still lists it — rather than
# publishing a pom with a hole in it, or minting a GAV that cannot exist.
#
# # Runner / portability
#
# Linux-runner-only by design (sole caller is release-xray.yml on
# ubuntu-latest). POSIX sh + python3 — python3 is a runner requirement
# anyway, for the rewrite step that runs immediately before this, and
# ships on ubuntu-latest. No .ps1 sibling (same rationale as
# preflight-story-package.sh).
#
# # Usage
#
#   ./.github/scripts/preflight-xray-package.sh VERSION [XRAY_DIR]
#
# XRAY_DIR defaults to the current working directory (release-xray.yml runs
# it with working-directory: tools/xray).

set -eu

VERSION="${1:?usage: preflight-xray-package.sh VERSION [XRAY_DIR]}"
XRAY_DIR="${2:-.}"
cd "$XRAY_DIR"

REPO_ROOT=$(git rev-parse --show-toplevel)
PRISTINE_DEPS=$(mktemp)
REQUIRED_FILE=$(mktemp)
trap 'rm -f "$PRISTINE_DEPS" "$REQUIRED_FILE"' EXIT

# The COMMITTED deps.edn — the workspace copy has already been rewritten
# in place by release-xray.yml's rewrite step, so it can no longer say
# which coordinates were supposed to be rewritten.
git -C "$REPO_ROOT" show HEAD:tools/xray/deps.edn > "$PRISTINE_DEPS"

echo "preflight: reading Xray's in-repo runtime coordinates from the committed deps.edn"
clojure -Sdeps '{:paths []}' -M -e "
(require '[clojure.edn :as edn] '[clojure.string :as str])
(let [deps (:deps (edn/read-string (slurp \"$PRISTINE_DEPS\")))]
  (spit \"$REQUIRED_FILE\"
        (str/join (for [[lib coord] (sort-by key deps)
                        :when (:local/root coord)]
                    (str lib \"\n\")))))" > /dev/null

REQUIRED_COUNT=$(grep -c . "$REQUIRED_FILE" || true)
if [ "${REQUIRED_COUNT}" -eq 0 ]; then
  echo "::error::preflight: found ZERO :local/root coordinates in the committed tools/xray/deps.edn — the reader found nothing to require, which means this gate would pass vacuously. Refusing."
  exit 2
fi
echo "preflight: ${REQUIRED_COUNT} in-repo coordinate(s) must appear in the published pom:"
sed 's/^/  /' "$REQUIRED_FILE"

echo "preflight: building Xray pom at lockstep version ${VERSION} (build only — NOT deploy)"
clojure -M:clein pom

# clein writes the pom under target/classes/META-INF/maven/<group>/<artifact>/pom.xml
POM="target/classes/META-INF/maven/day8/re-frame2-xray/pom.xml"
if [ ! -f "$POM" ]; then
  echo "::error::preflight: expected pom not found at $POM"
  exit 2
fi
echo "preflight: pom = $POM"

if ! VERSION="$VERSION" REQUIRED_FILE="$REQUIRED_FILE" python3 - "$POM" <<'PYTHON'
import os
import sys
import xml.etree.ElementTree as ET

pom_path = sys.argv[1]
version = os.environ["VERSION"]

with open(os.environ["REQUIRED_FILE"]) as handle:
    # Each line is a Clojure lib symbol, `group/artifact`, straight from
    # deps.edn. A group-less symbol is not a legal deps.edn coordinate for
    # a Maven artefact, so the split is total.
    REQUIRED = set()
    for line in handle:
        line = line.strip()
        if not line:
            continue
        group, _, artifact = line.partition("/")
        REQUIRED.add((group, artifact))

MISSING_HINT = (
    " NB: `clein pom` SKIPS :local/root coordinates outright, so this is"
    " exactly the pom produced when release-xray.yml's :local/root ->"
    " :mvn/version rewrite did not cover this coordinate. Publishing it"
    " would ship an Xray that cannot compile on a consumer's machine."
    " Fix: add the coordinate to the rewrite step in"
    " .github/workflows/release-xray.yml AND to TOOLS_LOCAL_ROOTS in"
    " .github/scripts/verify-version-lockstep.sh, in the same PR."
)

# MISSING_HINT is the only advice because every in-repo coordinate Xray
# declares has a publishable target. A coordinate with NO publishable target
# would need a second, coordinate-specific hint telling the operator NOT to
# follow MISSING_HINT, because adding an unpublishable coordinate to the
# rewrite step mints a GAV that cannot exist. Add such a hint only together
# with the workflow-side omission of that coordinate, since the two are one
# mechanism.


def localname(tag):
    """Tag name without its {namespace} prefix."""
    return tag.rsplit("}", 1)[-1]


def child_text(parent, name):
    for el in parent:
        if localname(el.tag) == name:
            return (el.text or "").strip()
    return ""


errors = []

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
missing = sorted(REQUIRED - declared_set)

for coord in missing:
    errors.append(
        "pom is MISSING the in-repo dependency %s/%s, which"
        " tools/xray/deps.edn declares at :local/root.%s"
        % (coord[0], coord[1], MISSING_HINT)
    )

# Lockstep: every in-repo coordinate that DID land, at the exact VERSION.
for coord in sorted(REQUIRED & declared_set):
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

for message in errors:
    print("::error::preflight: %s" % message)

if errors:
    if missing:
        print(
            "::error::preflight: %d of %d in-repo coordinate(s) are absent"
            " from the pom: %s"
            % (len(missing), len(REQUIRED),
               ", ".join("%s/%s" % c for c in missing))
        )
    sys.exit(1)

print("preflight: pom declares all %d in-repo coordinate(s) at lockstep %s"
      % (len(REQUIRED), version))
print("  %s" % ", ".join("%s/%s" % c for c in sorted(REQUIRED)))
PYTHON
then
  echo "::error::preflight: Xray published-package verification FAILED — ABORTING before clein deploy touches Clojars"
  exit 1
fi

echo "preflight: Xray published-package verification PASSED"
exit 0
