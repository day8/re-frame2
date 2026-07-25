#!/usr/bin/env sh
# preflight-story-package.sh (rf2-wht9a)
#
# # Why this exists
#
# `clein pom` SILENTLY SKIPS `:local/root` coordinates. tools/story/deps.edn
# declares FIVE of them — core, the Reagent adapter, machines, HTTP, and
# Xray — so a pom built straight from the in-tree deps.edn declares NONE of
# Story's in-repo dependencies. It carries exactly three: org.clojure/clojure,
# reagent/reagent and metosin/malli. Every framework artefact Story cannot
# run without is absent.
#
# Publishing that pom would recreate, at the package boundary, the exact
# consumer compile failure rf2-r8trk fixed in source:
#
#     No such namespace: day8.re-frame2-xray.mount
#     ... in re_frame/story/xray_preset.cljc
#
# The `:local/root → :mvn/version` rewrite in release-story.yml is the only
# thing standing between that and Clojars — and Clojars has no yank, so the
# mistake is unrecoverable (bump-and-supersede only). This script is the
# gate that proves the rewrite took effect, run AFTER the rewrite and
# BEFORE `clojure -M:clein deploy`.
#
# # Assertions
#
#   1. every DIRECT dependency in the generated pom carries a COMPLETE
#      coordinate — non-empty groupId, artifactId AND version. A published
#      GAV with a hole in it is unresolvable on the consumer's machine.
#   2. the DIRECT dependency set is EXACTLY the eight members below —
#      every expected member present, and nothing else. The present half
#      catches the skipped-:local/root hole this script exists for; the
#      no-extras half makes a NEW published dependency a deliberate act
#      (it reds here until someone adds it to EXPECTED in the same PR).
#   3. every IN-REPO dependency is pinned to the exact lockstep VERSION.
#      A rewrite that fired with the wrong value is as broken as one that
#      did not fire — and per spec/Conventions.md §Packaging conventions
#      every published artefact ships at the repo-root VERSION.
#
# Third-party versions (clojure, reagent, malli) are asserted NON-EMPTY
# but never equal to a literal: org.clojure/clojure floats with whatever
# the Clojure CLI install lands on the runner, and the reagent/malli pins
# live in deps.edn where the lockstep script already guards them.
#
# # Set-shaped, not presence-shaped
#
# This mirrors preflight-reagent-slim-package.sh, and for the same reason
# (rf2-do3m2): an absence-only check passes on an EMPTY `<dependencies/>`,
# which is precisely the pom the unrewritten deps.edn produces. Bind to
# the expected membership, not to the absence of known-bad names.
#
# # Runner / portability
#
# Linux-runner-only by design (sole caller is release-story.yml on
# ubuntu-latest). POSIX sh + python3 — python3 is already a runner
# requirement for the rewrite step that runs immediately before this, and
# ships on ubuntu-latest. The pom is parsed with ElementTree rather than
# grepped: this is the last gate before an irreversible publish, and a
# text parser that mis-reads a reformatted pom would produce exactly the
# false PASS this script exists to prevent. No .ps1 sibling (same
# rationale as preflight-reagent-slim-package.sh).
#
# # Usage
#
#   ./.github/scripts/preflight-story-package.sh VERSION [STORY_DIR]
#
# STORY_DIR defaults to the current working directory (release-story.yml
# runs it with working-directory: tools/story).

set -eu

VERSION="${1:?usage: preflight-story-package.sh VERSION [STORY_DIR]}"
STORY_DIR="${2:-.}"
cd "$STORY_DIR"

echo "preflight: building Story pom at lockstep version ${VERSION} (build only — NOT deploy)"
clojure -M:clein pom

# clein writes the pom under target/classes/META-INF/maven/<group>/<artifact>/pom.xml
POM="target/classes/META-INF/maven/day8/re-frame2-story/pom.xml"
if [ ! -f "$POM" ]; then
  echo "::error::preflight: expected pom not found at $POM"
  exit 2
fi
echo "preflight: pom = $POM"

if ! VERSION="$VERSION" python3 - "$POM" <<'PYTHON'
import os
import sys
import xml.etree.ElementTree as ET

pom_path = sys.argv[1]
version = os.environ["VERSION"]

# The five in-repo coordinates release-story.yml rewrites from :local/root
# to :mvn/version. Each MUST appear in the pom at exactly the lockstep
# VERSION — their presence is the whole point of this gate.
IN_REPO = {
    ("day8", "re-frame2"),
    ("day8", "re-frame2-reagent"),
    ("day8", "re-frame2-machines"),
    ("day8", "re-frame2-http"),
    ("day8", "re-frame2-xray"),
}

# Third-party + implicit-root deps. Present, complete, but version-free
# assertions (see the header note).
THIRD_PARTY = {
    ("org.clojure", "clojure"),
    ("reagent", "reagent"),
    ("metosin", "malli"),
}

EXPECTED = IN_REPO | THIRD_PARTY

MISSING_HINT = {
    coord: (
        " NB: `clein pom` SKIPS :local/root coordinates outright, so this is"
        " exactly the pom produced when release-story.yml's :local/root ->"
        " :mvn/version rewrite did not take effect for this coordinate."
        " Publishing it would ship a Story that cannot compile on a"
        " consumer's machine (rf2-r8trk at the package boundary)."
    )
    for coord in IN_REPO
}

# Test-only deps live under Story's :test alias precisely so they never
# reach the published jar. Seeing one here means an alias leaked into
# :deps.
EXTRA_HINT = {
    ("day8", "re-frame2-ui"):
        " re-frame2-ui NEVER PUBLISHES — it is in-tree donor code, consumed"
        " by :local/root and test-only, so it must stay under Story's :test"
        " alias, never in the published :deps.",
    ("day8", "re-frame2-epoch"):
        " The epoch artefact is test-only for Story (both consumers read"
        " the late-bound facade) — it must stay under the :test alias.",
    ("org.clojure", "test.check"):
        " test.check is :test-alias only; the generate adapter late-binds"
        " it via requiring-resolve so the published jar never depends on it.",
}


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

for coord in sorted(EXPECTED - declared_set):
    errors.append(
        "pom is MISSING the required DIRECT dependency %s/%s.%s"
        % (coord[0], coord[1], MISSING_HINT.get(coord, ""))
    )

for coord in sorted(declared_set - EXPECTED):
    errors.append(
        "pom declares an UNEXPECTED DIRECT dependency %s/%s.%s"
        " If this dependency is intentional, add it to EXPECTED in"
        " .github/scripts/preflight-story-package.sh in the same PR."
        % (coord[0], coord[1], EXTRA_HINT.get(coord, ""))
    )

# Lockstep: every in-repo coordinate at the exact repo-root VERSION.
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

for message in errors:
    print("::error::preflight: %s" % message)

if errors:
    sys.exit(1)

print("preflight: pom declares exactly the expected dependency set")
print("  in-repo (at lockstep %s): %s"
      % (version, ", ".join("%s/%s" % c for c in sorted(IN_REPO))))
print("  third-party: %s" % ", ".join("%s/%s" % c for c in sorted(THIRD_PARTY)))
PYTHON
then
  echo "::error::preflight: Story published-package verification FAILED — ABORTING before clein deploy touches Clojars"
  exit 1
fi

echo "preflight: Story published-package verification PASSED"
exit 0
