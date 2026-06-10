#!/usr/bin/env sh
# transform-reagent-slim-ns.sh (rf2-olo8rc; extracted from the inline
# release.yml "Rename adapter ns at publication (reagent-slim only)" step
# per rf2-zvjme).
#
# # What this does
#
# The day8/reagent-slim adapter ships IN-TREE at the namespace
# `re-frame.adapter.reagent-slim`, because the monorepo shadow-cljs build
# puts BOTH the bridge adapter (`re-frame.adapter.reagent`) and the slim
# adapter source trees on the same classpath — two namespaces with the
# same name would clash. At PUBLICATION time, a downstream app depends on
# EXACTLY ONE of {day8/re-frame2-reagent, day8/reagent-slim}, so the
# adapter ns is single-source per app. Per the slim IMPL-SPEC §1.8 / §13.1
# (and confirmed by the rf2-tba5e smoke-test findings), the slim Maven
# artefact ships its adapter at the canonical `re-frame.adapter.reagent`
# ns — a downstream app's import line is
# `(:require [re-frame.adapter.reagent :as ra])` regardless of which
# adapter coord their deps.edn pins.
#
# This script performs that rename on the throwaway runner checkout BEFORE
# clein packages the jar:
#   1. mv  src/re_frame/adapter/reagent_slim.cljs → reagent.cljs
#   2. rewrite `(ns re-frame.adapter.reagent-slim …` →
#      `(ns re-frame.adapter.reagent …` in that file.
#
# # Fail-loud invariants
#
# The script aborts (non-zero exit, GitHub-Actions `::error::` line) if:
#   - the source file is missing               → exit 2
#   - the destination file already exists       → exit 3 (in-tree ns clash)
#   - the `(ns re-frame.adapter.reagent-slim` declaration is absent → exit 4
# Better a release abort than a published artefact with a stale ns.
#
# # Runner / portability
#
# Linux-runner-only by design: the only caller is release.yml's
# deploy-leaf job, which runs on ubuntu-latest. Pure POSIX sh + sed (no
# bashisms, no python) so it runs under dash as well as bash. No .ps1
# sibling is provided — confirmed Linux-only per the rf2-olo8rc ruling.
#
# # Usage
#
#   ./.github/scripts/transform-reagent-slim-ns.sh [ADAPTER_DIR]
#
# ADAPTER_DIR defaults to the current working directory (release.yml runs
# it with working-directory: implementation/adapters/reagent-slim). The
# argument exists so the unit test can point it at a throwaway fixture.

set -eu

ADAPTER_DIR="${1:-.}"

src="${ADAPTER_DIR}/src/re_frame/adapter/reagent_slim.cljs"
dst="${ADAPTER_DIR}/src/re_frame/adapter/reagent.cljs"

if [ ! -f "$src" ]; then
  echo "::error::expected source file '$src' not found"
  exit 2
fi

if [ -e "$dst" ]; then
  echo "::error::destination '$dst' already exists — in-tree ns clash detected"
  exit 3
fi

if ! grep -qF "(ns re-frame.adapter.reagent-slim" "$src"; then
  echo "::error::expected '(ns re-frame.adapter.reagent-slim' declaration not found in $src"
  exit 4
fi

mv "$src" "$dst"

# Rewrite ONLY the ns-declaration token. Anchoring on the literal
# `(ns re-frame.adapter.reagent-slim` substring (not a bare
# `reagent-slim` match) keeps docstrings / require lines that legitimately
# mention the slim artefact untouched.
tmp="${dst}.tmp.$$"
sed 's/(ns re-frame\.adapter\.reagent-slim/(ns re-frame.adapter.reagent/' "$dst" > "$tmp"
mv "$tmp" "$dst"

# Post-condition: the rewritten file must now carry the canonical ns and
# must NOT carry the slim ns declaration.
if ! grep -qF "(ns re-frame.adapter.reagent" "$dst"; then
  echo "::error::post-rewrite assertion failed — '(ns re-frame.adapter.reagent' not present in $dst"
  exit 5
fi
if grep -qF "(ns re-frame.adapter.reagent-slim" "$dst"; then
  echo "::error::post-rewrite assertion failed — stale '(ns re-frame.adapter.reagent-slim' still present in $dst"
  exit 5
fi

echo "Renamed reagent_slim.cljs → reagent.cljs; ns is now re-frame.adapter.reagent"
grep -n "^(ns " "$dst"
