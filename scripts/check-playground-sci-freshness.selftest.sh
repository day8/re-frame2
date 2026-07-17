#!/usr/bin/env sh
# scripts/check-playground-sci-freshness.selftest.sh
#
# Self-test for the committed SCI-bundle freshness gate (rf2-i3e3q).
#
# Proves the gate is MECHANICALLY HONEST: a committed bundle that has drifted out
# of sync with ANY declared baked-in input class is caught. For each input class
# the bundle bakes in, it perturbs ONE representative tracked file in the WORKING
# TREE (so the recomputed input digest no longer matches the marker in the
# committed bundle), asserts `check-playground-sci-freshness.sh` goes RED, then
# restores the file byte-for-byte and asserts the tree is clean again. Finally it
# asserts the clean tree is GREEN.
#
# This is NOT wired into the normal gate (it mutates the working tree); run it on
# demand from the repo root:
#   sh scripts/check-playground-sci-freshness.selftest.sh
#
# Precondition: the committed bundle at HEAD carries a current digest marker
# (i.e. it was built + committed by `npm run build` in docs/tools/playground).
# Exit: 0 = all assertions held; 1 = a mutation was NOT caught (gate dishonest)
# or a control failed.

set -eu

GATE="scripts/check-playground-sci-freshness.sh"

# One representative tracked file per declared input class (see ROSTER in
# scripts/playground-sci-input-digest.mjs).
CLASS_core="implementation/core/src/re_frame/subs.cljc"
CLASS_reagent_slim="implementation/adapters/reagent-slim/src/reagent2/core.cljs"
CLASS_machines="implementation/machines/src/re_frame/machines/transition.cljc"
CLASS_flows="implementation/flows/src/re_frame/flows.cljc"
CLASS_sci_source="docs/tools/playground/sci/src/rf2_playground/sci.cljs"
CLASS_build_config="docs/tools/playground/sci/shadow-cljs.edn"
CLASS_dep_lock="docs/tools/playground/sci/package-lock.json"

CLASSES="core reagent_slim machines flows sci_source build_config dep_lock"

fail=0

green() {
  # Run the gate; succeed (0) expected.
  if sh "$GATE" >/dev/null 2>&1; then
    return 0
  fi
  return 1
}

# --- control 1: clean tree is GREEN -----------------------------------------
printf 'control: clean tree ... '
if green; then
  printf 'GREEN (ok)\n'
else
  printf 'RED (UNEXPECTED)\n'
  printf '  The committed bundle is not fresh vs current inputs. Rebuild it first:\n'
  printf '    cd docs/tools/playground && npm run build\n'
  fail=1
fi

# --- per-class: mutate one input -> gate must go RED ------------------------
for cls in $CLASSES; do
  eval "file=\$CLASS_${cls}"
  if [ ! -f "$file" ]; then
    printf '%-14s SKIP (missing: %s)\n' "$cls" "$file"
    fail=1
    continue
  fi

  backup=$(mktemp)
  cp "$file" "$backup"
  # Perturb: append a byte so the working-tree content (and thus its git
  # blob sha, and thus the input digest) differs from the committed bundle's
  # recorded inputs. Byte-only change; restored immediately below.
  printf '\n' >> "$file"

  printf '%-14s mutate %-64s ' "$cls" "$file"
  if green; then
    printf 'GREEN (NOT CAUGHT — gate dishonest for this class)\n'
    fail=1
  else
    printf 'RED (caught)\n'
  fi

  # Restore byte-for-byte and verify the tree is clean for this path.
  cp "$backup" "$file"
  rm -f "$backup"
  if [ -n "$(git status --porcelain -- "$file")" ]; then
    printf '  ERROR: %s not restored cleanly.\n' "$file"
    fail=1
  fi
done

# --- control 2: clean tree is GREEN again -----------------------------------
printf 'control: restored tree ... '
if green; then
  printf 'GREEN (ok)\n'
else
  printf 'RED (UNEXPECTED — a mutation leaked)\n'
  fail=1
fi

if [ "$fail" -ne 0 ]; then
  printf '\nSELF-TEST FAILED.\n' >&2
  exit 1
fi
printf '\nSELF-TEST OK: every input class is covered by the freshness digest.\n'
exit 0
