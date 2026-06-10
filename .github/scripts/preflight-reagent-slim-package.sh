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
#   3. generated pom has NO DIRECT day8/re-frame2-reagent dependency
#      (a slim consumer must not pull the bridge adapter).
#   4. generated pom has NO slim-owned DIRECT stock-`reagent` dependency
#      (the slim rewrite REPLACES stock Reagent; it does not depend on
#      it).  ── DIRECT-dep level ONLY: transitive reagent reaching a
#      consumer via day8/re-frame2 (core) stays until core drops it, so
#      a full-transitive-absence assertion would be a FALSE invariant
#      today.
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
# on ubuntu-latest). Pure POSIX sh; the only external tools are clojure
# (already set up by the deploy-leaf job) and `jar` (ships with the JDK
# the job installs). No .ps1 sibling — confirmed Linux-only.
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

# ── 3: no DIRECT day8/re-frame2-reagent dep in the pom ──────────────
# clein emits <dependency><groupId>day8</groupId><artifactId>X</artifactId>
# blocks. A slim consumer must not transitively pull the bridge adapter
# as a DIRECT dep of the slim artefact.
if grep -q "<artifactId>re-frame2-reagent</artifactId>" "$POM"; then
  echo "::error::preflight: pom declares a DIRECT day8/re-frame2-reagent dependency — the slim artefact must not depend on the bridge adapter"
  errors=$((errors + 1))
fi

# ── 4: no slim-owned DIRECT stock-reagent dep in the pom ────────────
# Stock Reagent publishes as reagent/reagent (groupId == artifactId ==
# "reagent"). The slim rewrite REPLACES stock Reagent, so the slim pom
# must carry no direct <artifactId>reagent</artifactId>. DIRECT level
# ONLY — transitive reagent via core (day8/re-frame2) is expected today.
if grep -q "<artifactId>reagent</artifactId>" "$POM"; then
  echo "::error::preflight: pom declares a DIRECT stock-reagent dependency — the slim rewrite replaces (does not depend on) stock Reagent at the direct-dep level"
  errors=$((errors + 1))
fi

if [ "$errors" -gt 0 ]; then
  echo "::error::preflight: reagent-slim published-package verification FAILED ($errors invariant(s) violated) — ABORTING before clein deploy touches Clojars"
  exit 1
fi

echo "preflight: reagent-slim published-package verification PASSED"
echo "  jar ships re_frame/adapter/reagent.cljs and NO reagent_slim entry"
echo "  pom has no DIRECT day8/re-frame2-reagent dep and no DIRECT stock-reagent dep"
exit 0
