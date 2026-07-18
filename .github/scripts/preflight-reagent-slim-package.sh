#!/usr/bin/env sh
# preflight-reagent-slim-package.sh (rf2-olo8rc)
#
# # Why this exists
#
# The reagent-slim publication-time ns rename
# (.github/scripts/transform-reagent-slim-ns.sh) is the ONE release step
# where the published artefact diverges from the tested tree. Nothing
# downstream verifies the OUTPUT (jar ns set, pom dep set) before
# `clojure -M:clein deploy` mutates Clojars — an IRREVERSIBLE path
# (Clojars has no yank; recovery is bump-and-supersede only). The
# monorepo test:reagent-slim:bundle-isolation gate runs off the
# in-tree classpath, NOT the generated jar/pom, so it does not catch a
# broken publication transform.
#
# This script is the pre-deploy package preflight. It runs IN-PLACE in
# the reagent-slim adapter directory on the throwaway runner checkout,
# AFTER both the :local/root → :mvn/version rewrite and the ns-rename
# transform have run, and BEFORE clein deploy. It builds (NOT deploys)
# the jar + pom and asserts the published ABI.
#
# # Assertions (the published-package invariants)
#
#   1. jar CONTAINS re_frame/adapter/reagent.cljs  (the canonical
#      `re-frame.adapter.reagent` ns the slim artefact must ship at).
#   2. jar does NOT contain any reagent_slim entry  (the slim-suffixed
#      ns must have been renamed away by the transform).
#   3. every DIRECT dependency in the generated pom carries a COMPLETE
#      coordinate — non-empty groupId, artifactId AND version. A published
#      GAV with a hole in it is unresolvable on the consumer's machine.
#   4. the DIRECT dependency set is EXACTLY {org.clojure/clojure,
#      day8/re-frame2} — every expected member present, and nothing else.
#      Required-member presence catches the empty/absent <dependencies>
#      hole (see rf2-do3m2 below); the no-extras half subsumes the two
#      forbidden-dependency checks this script used to spell out by hand
#      (day8/re-frame2-reagent — a slim consumer must not pull the bridge
#      adapter; and stock reagent/reagent — the slim rewrite REPLACES
#      stock Reagent rather than depending on it), and additionally
#      catches the next unintended dep nobody thought to hardcode.
#      ── DIRECT-dep level ONLY: transitive reagent reaching a consumer
#      via day8/re-frame2 (core) stays until core drops it, so a
#      full-transitive-absence assertion would be a FALSE invariant today.
#      Versions are asserted NON-EMPTY, never equal to a literal: the
#      org.clojure/clojure version floats with whatever Clojure CLI
#      install-clojure-cli.sh lands on the runner.
#
# # Why assertion 3+4 are set-shaped, not presence-shaped (rf2-do3m2)
#
# Assertions 3 and 4 originally fired only on the PRESENCE of a forbidden
# dependency — two `grep -q "<artifactId>…"` absence checks. An empty
# `<dependencies/>`, an absent `<dependencies>` block, and a dependency
# with an empty `<version/>` therefore ALL printed PASSED.
#
# That is not a hypothetical shape. `clein pom` SKIPS `:local/root`
# coordinates outright ("Skipping coordinate: {:local/root …}"), so the
# pom generated from the UNREWRITTEN in-tree deps.edn carries no
# day8/re-frame2 dependency whatsoever. release.yml's
# `:local/root → :mvn/version` rewrite step is the only thing standing
# between that and a published artefact whose dependency set is empty —
# and a presence-only preflight waves it straight through to Clojars,
# where it cannot be unpublished.
#
# The no-extras half is a deliberate tripwire: a NEW legitimate dependency
# on the slim artefact reds this gate until someone adds it to EXPECTED
# below. That is the intended workflow — the published dependency set of
# an irreversibly-released artefact should change only on purpose.
#
# # Fallback scope note (rf2-olo8rc)
#
# A consumer-compile assertion (a temp shadow-cljs project resolving
# [re-frame.adapter.reagent] + [reagent2.core] with
# day8/re-frame2-reagent ABSENT) was scoped. The temp-project npm/shadow
# wiring is gnarly on the runner, so per the ruling's fallback this
# script asserts jar-content + pom-dep + ns-grep instead — which still
# catches every failure mode the previous input-grep-only step missed
# (a transform that left the slim ns in place, mv'd the wrong file, or a
# deps drift that re-introduced a direct bridge / stock-reagent dep).
#
# # Runner / portability
#
# Linux-runner-only by design (the sole caller is release.yml deploy-leaf
# on ubuntu-latest). POSIX sh; the external tools are clojure (already set
# up by the deploy-leaf job), `jar` (ships with the JDK the job installs)
# and `python3`, used to parse the pom. python3 is not a new runner
# requirement: the deploy-leaf job's `:local/root → :mvn/version` rewrite
# step already shells out to it a few steps earlier, and it is present on
# ubuntu-latest out of the box. It is used deliberately in preference to a
# line-oriented sh/grep parse — this is the last gate before an
# irreversible publish, and a text parser that mis-reads a reformatted pom
# would produce exactly the class of false PASS this script exists to
# prevent. No .ps1 sibling — confirmed Linux-only.
#
# # Usage
#
#   ./.github/scripts/preflight-reagent-slim-package.sh [ADAPTER_DIR]
#
# ADAPTER_DIR defaults to the current working directory (release.yml runs
# it with working-directory: implementation/adapters/reagent-slim).

set -eu

ADAPTER_DIR="${1:-.}"
cd "$ADAPTER_DIR"

echo "preflight: building reagent-slim jar + pom (build only — NOT deploy)"
clojure -M:clein jar
clojure -M:clein pom

# ── Locate the jar ──────────────────────────────────────────────────
# clein writes target/<artifactId>-<version>.jar. We don't hardcode the
# version, so glob target/reagent-slim-*.jar and require exactly one.
JAR=""
for candidate in target/reagent-slim-*.jar; do
  if [ -f "$candidate" ]; then
    if [ -n "$JAR" ]; then
      echo "::error::preflight: more than one target/reagent-slim-*.jar found — stale build artefacts?"
      exit 2
    fi
    JAR="$candidate"
  fi
done
if [ -z "$JAR" ]; then
  echo "::error::preflight: no target/reagent-slim-*.jar produced by clein jar"
  exit 2
fi
echo "preflight: jar = $JAR"

# clein writes the pom under target/classes/META-INF/maven/<group>/<artifact>/pom.xml
POM="target/classes/META-INF/maven/day8/reagent-slim/pom.xml"
if [ ! -f "$POM" ]; then
  echo "::error::preflight: expected pom not found at $POM"
  exit 2
fi
echo "preflight: pom = $POM"

errors=0

# ── 1 + 2: jar content (ns set) ─────────────────────────────────────
JAR_ENTRIES="$(jar tf "$JAR")"

if ! printf '%s\n' "$JAR_ENTRIES" | grep -qx "re_frame/adapter/reagent.cljs"; then
  echo "::error::preflight: published jar is MISSING re_frame/adapter/reagent.cljs (canonical adapter ns) — the publication ns-rename did not run or produced the wrong path"
  errors=$((errors + 1))
fi

if printf '%s\n' "$JAR_ENTRIES" | grep -q "reagent_slim"; then
  echo "::error::preflight: published jar CONTAINS a reagent_slim entry — the slim ns was not renamed away:"
  printf '%s\n' "$JAR_ENTRIES" | grep "reagent_slim" | sed 's/^/::error::  /'
  errors=$((errors + 1))
fi

# ── 3 + 4: the pom's DIRECT dependency SET ──────────────────────────
# Bind to the expected membership, not to the absence of two known-bad
# names — see the "Why assertion 3+4 are set-shaped" note in the header
# (rf2-do3m2). Parsed with ElementTree rather than grepped so a
# reformatted or namespaced pom cannot yield a false PASS.
if ! python3 - "$POM" <<'PYTHON'
import sys
import xml.etree.ElementTree as ET

pom_path = sys.argv[1]

# The DIRECT dependency set a correct reagent-slim pom declares — EXACTLY
# these members, no more, no fewer.
#
#   org.clojure/clojure   the implicit root dep the Clojure CLI contributes
#   day8/re-frame2        the core coordinate; release.yml rewrites the
#                         in-tree :local/root "../../core" to :mvn/version
#                         immediately before this preflight runs
#
# ADDING A DEPENDENCY to the slim artefact? Add it here in the same PR.
# This set is a deliberate tripwire on the published ABI of an artefact
# that cannot be unpublished.
EXPECTED = {
    ("org.clojure", "clojure"),
    ("day8", "re-frame2"),
}

# Extra context for the shapes with a specific history. A bare "unexpected
# dependency" would be true but unhelpful for these two.
EXTRA_HINT = {
    ("day8", "re-frame2-reagent"):
        " The slim artefact must not depend on the bridge adapter — a slim"
        " consumer would end up pulling BOTH adapters.",
    ("reagent", "reagent"):
        " The slim rewrite REPLACES stock Reagent; it does not depend on it."
        " (DIRECT-dep level only — transitive reagent via day8/re-frame2 is"
        " expected until core drops it.)",
}

MISSING_HINT = {
    ("day8", "re-frame2"):
        " NB: `clein pom` SKIPS :local/root coordinates outright, so this is"
        " exactly the pom produced when release.yml's :local/root ->"
        " :mvn/version rewrite did not take effect. Publishing it would ship"
        " an artefact that resolves to nothing on the consumer's machine.",
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

declared = set()
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
        declared.add((gav["groupId"], gav["artifactId"]))

for coord in sorted(EXPECTED - declared):
    errors.append(
        "pom is MISSING the required DIRECT dependency %s/%s.%s"
        % (coord[0], coord[1], MISSING_HINT.get(coord, ""))
    )

for coord in sorted(declared - EXPECTED):
    errors.append(
        "pom declares an UNEXPECTED DIRECT dependency %s/%s.%s"
        " If this dependency is intentional, add it to EXPECTED in"
        " .github/scripts/preflight-reagent-slim-package.sh in the same PR."
        % (coord[0], coord[1], EXTRA_HINT.get(coord, ""))
    )

for message in errors:
    print("::error::preflight: %s" % message)

if errors:
    sys.exit(1)

print("preflight: pom declares exactly the expected dependency set (%s)"
      % ", ".join("%s/%s" % c for c in sorted(EXPECTED)))
PYTHON
then
  errors=$((errors + 1))
fi

if [ "$errors" -gt 0 ]; then
  echo "::error::preflight: reagent-slim published-package verification FAILED ($errors invariant(s) violated) — ABORTING before clein deploy touches Clojars"
  exit 1
fi

echo "preflight: reagent-slim published-package verification PASSED"
echo "  jar ships re_frame/adapter/reagent.cljs and NO reagent_slim entry"
echo "  pom declares exactly {org.clojure/clojure, day8/re-frame2}, each with a complete GAV"
exit 0
