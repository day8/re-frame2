#!/usr/bin/env bash
set -euo pipefail

# Fast pre-checkin spine for the canonical agent quality gate.
#
# WHAT IT ACTUALLY RUNS — read this before trusting a green (rf2-dgzaf).  This
# is a SPINE, not a local mirror of CI's PR-gate matrix.  It runs, when the
# changed surface owns the tier:
#
#   always     the cheap repo-wide static/drift checks (each paired with its
#              own self-test where it has one);
#   docs tier  the documentation-content gates, plus `mkdocs build --strict`
#              — a HARD gate wherever mkdocs can be resolved at all, whether as
#              a console script or as `python -m mkdocs` (rf2-g7p7l);
#   JVM tier   `implementation/core` PLUS the suite of every implementation
#              artefact whose own tree the diff touched (rf2-uwszd).  Core runs
#              whenever the tier runs — it is the substrate every artefact sits
#              on, and its suite exercises the schema, machine, route and flow
#              surfaces directly (see implementation/core/deps.edn).  The
#              artefact suites are selected by matching the changed paths
#              against the roster in `scripts/test-jvm-implementation.sh` —
#              the same roster that script runs, read from the file that
#              defines it, so a new artefact is picked up by construction and
#              there is no second map here to drift.  Artefacts the diff did
#              not touch still do NOT run: the PASS line names them, and
#              `scripts/test-jvm-implementation.sh` is the whole set;
#   node tier  the npm/CLJS `:node-test` build, the JS harness self-tests, and
#              the per-namespace isolation gate;
#   spine self this script's own tiering self-test, armed ONLY when this script
#              or its self-test fixture tree is itself in the diff (rf2-fhdd3).
#
# Everything else CI runs is in NO tier of this spine: the browser lanes, the
# production-elision / bundle-isolation / perf-bundle gates, the adapter
# classpath probes (gated on `adapter_diagnostic`, which this script does not
# even consult), the adapter smokes, the Xray feature matrix, mcp-conformance,
# and the tool JVM suites (`scripts/test-jvm-tools.sh`).
#
# So `PASS fast PR spine` means "the spine's own tiers passed" — never "what CI
# will run passed".  The PASS line enumerates every tier and step that was
# skipped, and a step that could not run names the surfaces it leaves ungated,
# so the gap is visible rather than assumed.  `TESTING.md` carries the canonical
# PR/nightly/release matrix.
#
# The paragraph above DESCRIBES the gap in families; the PASS line also NAMES it,
# check by check, from `scripts/check_fast_pr_gap.py --brief` (rf2-13zre).  Prose
# could not carry the whole truth here, and did not: a required check can be a
# STEP INSIDE A JOB THIS SPINE RUNS — the EP-0036 donor-boundary `git grep` is
# the last step of `jvm-freehand`, so it reports as "JVM freehand (clojure
# -M:test)" and is not a test; `npm run test:ui-isolation` is a step of the same
# `cljs` job whose node-test build this spine does run; and thirty-eight of CI's
# required `python scripts/check_*.py` invocations had no local lane at all until
# rf2-ejm7m measured them and gave them one — twenty-nine in the always-on block
# below, nine in the documentation tier.  None of those is a skipped TIER, so
# nothing above can see them.  Run `python scripts/check_fast_pr_gap.py --list`
# for the local command for each of the ones that remain.
#
# EVERY required python checker now has a local lane.  rf2-ejm7m left exactly one
# out on measurement — `check_retired_image_keys.py --verbose` cost 37.7s, more
# than double this spine's entire documentation tier — and rf2-e1xx0 made it
# ~0.9s instead of accepting the hole, so it joined the tier rather than staying
# named in the report.  Nothing here had to be edited to reflect that: the report
# derives the gap by parsing this file's invocations.
#
# What it DOES mirror is CI's *tiering* — which tiers run for a given diff —
# because that decision is delegated wholesale to the classifier CI uses.  Tier
# SELECTION is faithful; tier CONTENTS are the spine's own, narrower set above.
# CI has no per-artefact selection to mirror: `implementation_jvm` arms every
# per-artefact JVM job at once, so the spine's within-tier narrowing is strictly
# a subset of what CI runs, never a divergence from it.
#
# HOW THE TIERING IS DECIDED — no second map.  The runtime tier is delegated
# WHOLESALE to the very classifier CI uses,
# `.github/scripts/report-changed-surfaces.sh`, invoked with the changed-file
# list as explicit paths (it prints `key=value` to stdout when GITHUB_OUTPUT
# is unset).  `implementation_jvm` gates the JVM artefact suites exactly as it
# gates test.yml's per-artefact JVM jobs; `cljs_node_test` gates the npm/CLJS/JS-harness/
# isolation suites exactly as it gates test.yml's cljs job.  Reusing that one
# script — rather than re-deriving a divergent surface map here — is what keeps
# local selection aligned with test.yml by construction.  The documentation
# tier keys on documentation CONTENT (Markdown + the MkDocs config + the
# doc-checker scripts those gates run); it is deliberately separate from the
# runtime classifier because the doc gates validate prose, not code.
#
# One surface is neither: THIS SCRIPT (and the fixture tree of its self-test).
# It is the gate that decides which gates run, so it arms every tier plus its
# own self-test — see `is_doc_surface_path` / `is_spine_self_path` below for
# why, and for why the arming is named path-by-path rather than `scripts/*`.
#
# Usage:
#   scripts/test-fast-pr.sh              classify the diff; run only the
#                                        owning tiers (+ always-on checks)
#   scripts/test-fast-pr.sh --all        run the COMPLETE spine regardless of
#                                        classification (env: RF2_FAST_PR_ALL=1)
#   scripts/test-fast-pr.sh --with-docs  force the documentation gates on
#   scripts/test-fast-pr.sh --no-docs    force the documentation gates off
#   scripts/test-fast-pr.sh --plan       print the tier decision and exit,
#                                        running nothing (deterministic dry-run)
#
# The change set is gathered deterministically from every git state: the
# committed branch diff vs origin/main (three-dot merge-base), the staged +
# unstaged working tree (`git diff HEAD`), and untracked files
# (`git ls-files --others`).  If origin/main cannot be resolved, or the change
# set contains a file no known surface recognises, the spine falls back
# conservatively to the COMPLETE runtime run rather than risk under-testing.
#
# Documentation-gate components (run when a documentation surface changes):
#   1. python scripts/check_readme_links.py            — README anchor + target
#   2. python scripts/check_doc_slugs.py               — docs corpus anchors
#   3. python scripts/check_ep_status_sync.py          — docs/EP index status-sync
#   4. python scripts/check_runtime_subsystem_grading.py — EP-0006 grading table
#   5. python scripts/check_inject_cofx_residue.py       — retired inject-cofx
#   6. python scripts/check_failure_corpus_residue.py    — retired failure spellings
#   7. python scripts/check_retired_composition_vocab.py — retired composition vocab
#   8. python scripts/check_retired_image_keys.py  — retired EP-0026 image keys,
#      BOTH arms: --self-test (the guard has teeth) then the live corpus sweep,
#      which rf2-e1xx0 took from 37.7s to ~0.9s
#   9. mkdocs build --strict (console script, else `python -m mkdocs`)

# The spine's own tree (scripts, gate commands, the shared classifier) is
# always derived from this script's location.  `--repo-root` overrides ONLY
# the tree the change set is gathered from — used by the self-test harness to
# classify disposable fixture repos without moving the real gate scripts.
script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
spine_root="$(cd "$script_dir/.." && pwd)"
diff_root="$spine_root"
classifier="$spine_root/.github/scripts/report-changed-surfaces.sh"

# NAME THE TREE, FIRST LINE, ALWAYS (rf2-g2mxd).  That derivation is why an
# INVOCATION PATH can silently retarget the whole gate: `${BASH_SOURCE[0]}` is
# relative when the invocation is, so `dirname` resolves it against whatever
# cwd the shell actually has — and a `cd <worktree> && sh scripts/…` does not
# always survive backgrounding.  On 2026-08-03 a worker's spine ran end to end
# inside a DIFFERENT live worker's checkout: it took that tree as its spine
# root, its diff root and its classifier input, graded that worker's diff, and
# reported the verdict in this one's PR body.  A complete, internally
# consistent run about the wrong work — the only visible trace was a foreign
# path in an mkdocs INFO line ninety lines down.  One line at the top turns
# that into something a human or an agent sees immediately, and greps for.
# The fix at the call site is to invoke a backgrounded gate by ABSOLUTE path.
printf 'gate root: %s\n' "$spine_root"

with_docs="auto"     # auto | force | skip
run_all=false
plan_only=false
if [ "${RF2_FAST_PR_ALL:-}" = "1" ]; then
  run_all=true
fi

while [ "$#" -gt 0 ]; do
  case "$1" in
    -a|--all)    run_all=true ;;
    --with-docs) with_docs="force" ;;
    --no-docs)   with_docs="skip" ;;
    --plan|--dry-run) plan_only=true ;;
    --repo-root)
      shift
      if [ "$#" -eq 0 ]; then
        printf 'error: --repo-root requires a directory argument\n' >&2
        exit 2
      fi
      diff_root="$(cd "$1" && pwd)"
      ;;
    -h|--help)
      cat <<EOF
fast PR pre-checkin spine (rf2-r6x1t)

Usage:
  $(basename "$0") [--all] [--with-docs|--no-docs] [--plan] [--repo-root DIR]

Runtime tiers are gated on the SAME changed-surface classifier CI uses
(.github/scripts/report-changed-surfaces.sh): the JVM artefact suites run when
implementation_jvm is set, and the npm/CLJS/JS-harness/isolation suites run
when cljs_node_test is set.  The documentation gates run when the diff
touches documentation content.  The cheap static/drift checks always run.

Within the JVM tier the spine runs implementation/core plus the suite of every
artefact whose own tree the diff touched, selected against the roster in
scripts/test-jvm-implementation.sh.  --plan prints that selection.

Flags:
  --all, -a     run the COMPLETE spine regardless of classification
                (also enabled by RF2_FAST_PR_ALL=1) — the explicit override
  --with-docs   force the documentation gates on
  --no-docs     force the documentation gates off
  --plan        print the tier decision (docs/jvm/node) and exit; run nothing
  --repo-root DIR   gather the change set from DIR instead of the spine tree
                    (test hook; does not move the real gate scripts)
  (default)     classify the diff and run only the owning tiers
EOF
      exit 0
      ;;
    *)
      printf 'unknown flag: %s\n' "$1" >&2
      exit 2
      ;;
  esac
  shift
done

if [ ! -x "$classifier" ] && [ ! -f "$classifier" ]; then
  printf 'error: changed-surface classifier not found at %s\n' "$classifier" >&2
  exit 2
fi

# Every tier or step this run did NOT execute, named in the final PASS line
# (rf2-dgzaf).  `PASS fast PR spine` on its own was compatible with the docs
# build never having been attempted and with seventeen JVM artefact suites
# never running; a gate that honestly says what it covered is worth more than
# one that overstates and is believed.  Bash 3.2-compatible array (macOS).
skipped=()
note_skipped() {
  skipped+=("$1")
}

run() {
  local surface="$1"
  local repro="$2"
  shift 2
  printf '==> %s\n' "$surface"
  if ! "$@"; then
    printf '\nFAIL %s\nrepro: %s\n' "$surface" "$repro" >&2
    return 1
  fi
}

# ---------------------------------------------------------------------------
# MKDOCS RESOLUTION (rf2-g7p7l).  `command -v mkdocs` is not how mkdocs is
# necessarily installed.  A `pip install --user` puts the package on sys.path
# while the console script lands in a per-user Scripts/ directory that is
# routinely absent from PATH — the state of this project's own Windows
# checkout, where `command -v mkdocs` fails and `python -m mkdocs --version`
# prints 1.6.1.  The previous probe asked only for the console script, so the
# strict docs build soft-skipped on EVERY local run while the spine still
# printed `PASS ... SKIP ... (local soft-skip OK)`: a gate reporting success
# without having examined anything.
#
# So resolve mkdocs the way it is actually installed — console script first,
# then the module under each Python launcher.  `-m mkdocs --version` is the
# probe rather than a bare import because it also proves the entry point runs.
# It costs ~0.25s and is paid ONLY when the documentation tier runs, so a
# code-only diff (the common case) pays nothing.
#
# The resolved command is invoked through `bash -c`, NOT `bash -lc`: a login
# shell re-derives PATH from the profile, so probing here and running there
# could disagree about which mkdocs — or which python — is meant.
# ---------------------------------------------------------------------------
mkdocs_cmd=""
resolve_mkdocs() {
  if [ -n "$mkdocs_cmd" ]; then
    return 0
  fi
  if command -v mkdocs >/dev/null 2>&1; then
    mkdocs_cmd="mkdocs"
    return 0
  fi
  local launcher
  for launcher in python python3 py; do
    if command -v "$launcher" >/dev/null 2>&1 &&
       "$launcher" -m mkdocs --version >/dev/null 2>&1; then
      mkdocs_cmd="$launcher -m mkdocs"
      return 0
    fi
  done
  return 1
}

# ---------------------------------------------------------------------------
# Change-set gathering — deterministic across every git state.
#
#   committed branch : git diff --no-renames origin/main...HEAD  (merge-base)
#   staged + unstaged: git diff --no-renames HEAD                (worktree vs HEAD)
#   untracked        : git ls-files --others --exclude-standard
#
# `--no-renames` matches the CI classifier: a pure rename emits BOTH endpoints
# (a deletion of the old surface + an addition of the new), so a rename OUT of
# a runtime surface still arms that surface's gate.
# ---------------------------------------------------------------------------
base_resolved=false
if git -C "$diff_root" rev-parse --verify origin/main >/dev/null 2>&1; then
  base_resolved=true
fi

gather_changed_files() {
  {
    if [ "$base_resolved" = true ]; then
      git -C "$diff_root" diff --no-renames --name-only origin/main...HEAD 2>/dev/null || true
    fi
    git -C "$diff_root" diff --no-renames --name-only HEAD 2>/dev/null || true
    git -C "$diff_root" ls-files --others --exclude-standard 2>/dev/null || true
  } | sed '/^[[:space:]]*$/d' | sort -u
}

changed_files="$(gather_changed_files)"

# Documentation-surface predicate.  A gate that validates documentation CONTENT
# runs when the diff touches Markdown, the MkDocs toolchain config, or one of
# the doc-checker scripts / fixtures the gates themselves execute.  This mirrors
# how the classifier arms a gate whenever its own gate-script changes, and how
# docs.yml keys the MkDocs build on documentation paths.
#
# THE SPINE'S OWN TREE IS ON THIS SURFACE (rf2-fhdd3) — `scripts/test-fast-pr.sh`,
# this very file, and the fixture tree of its self-test, delegated to
# `is_spine_self_path`.  Until they were listed, a diff touching only the spine matched no
# runtime surface AND no documentation surface: the runtime classifier does not
# know the runner (it classifies runtime source), and `run_docs` keyed on the
# predicate above, which the runner never matched.  So A CHANGE TO THE SPINE'S
# OWN DOCUMENTATION GATE — including one that BROKE it — got a green spine that
# never ran the documentation tier, and every verification run during rf2-g7p7l
# had to pass `--with-docs` by hand.  The runner is where the docs tier and the
# mkdocs resolution are DEFINED; editing them has to run them.  CI already
# agrees: `scripts/test-fast-pr.sh` is on docs.yml's documentation surface (both
# its `push.paths` and its PR-side `detect` classifier), so this also closes a
# local/CI divergence rather than inventing a local rule.
#
# NAMED EXACTLY, never `scripts/*`.  Widening to every script would make an
# ordinary `scripts/` change pay for the whole documentation tier — trading a
# fail-open for a slow common case.  `scripts/test-jvm-implementation.sh`,
# `scripts/check_skill_mcp_drift.py` and their neighbours stay off this surface,
# and the self-test pins that.
is_doc_surface_path() {
  case "$1" in
    *.md) return 0 ;;
    mkdocs.yml|mkdocs_hooks.py|requirements.txt) return 0 ;;
    scripts/check_readme_links.py|scripts/check_doc_slugs.py) return 0 ;;
    scripts/check_provenance_pins.py) return 0 ;;
    scripts/check_ep_status_sync.py|scripts/check_runtime_subsystem_grading.py) return 0 ;;
    scripts/_test_fixtures/check_readme_links/*|scripts/_test_fixtures/check_doc_slugs/*) return 0 ;;
    # The residue sweeps added to this tier by rf2-ejm7m, same rule as their
    # neighbours above: a gate whose own script changed has to run.  The first
    # three are named IDENTICALLY in docs.yml's `detect` classifier, so this is
    # mirroring CI, not inventing a local surface.  `check_retired_image_keys.py`
    # is the one CI's classifier does not list — added anyway because this spine
    # executes both of its arms, and a gate that runs here should arm on its own
    # source; the direction is the safe one (over-arming a ~1s check).
    scripts/check_inject_cofx_residue.py|scripts/check_failure_corpus_residue.py) return 0 ;;
    scripts/check_retired_composition_vocab.py|scripts/check_retired_image_keys.py) return 0 ;;
    scripts/_test_fixtures/check_inject_cofx_residue/*) return 0 ;;
    scripts/_test_fixtures/check_failure_corpus_residue/*) return 0 ;;
    scripts/_test_fixtures/check_retired_composition_vocab/*) return 0 ;;
    scripts/_test_fixtures/check_retired_image_keys/*) return 0 ;;
  esac
  # The spine's own tree is on this surface too — delegated rather than
  # re-listed, so the two predicates cannot drift apart.
  is_spine_self_path "$1"
}

# The spine's own tree — the runner plus the fixture tree of its self-test.
# Its own predicate because it is load-bearing TWICE over: it puts these paths
# on the documentation surface above, and it arms the spine's own self-test,
# which is the only thing that proves the tiering still routes.
is_spine_self_path() {
  case "$1" in
    scripts/test-fast-pr.sh) return 0 ;;
    scripts/_test_fixtures/test_fast_pr_docs_gate/*) return 0 ;;
    *) return 1 ;;
  esac
}

doc_surface=false
spine_self_surface=false
if [ -n "$changed_files" ]; then
  while IFS= read -r file; do
    [ -z "$file" ] && continue
    if is_doc_surface_path "$file"; then
      doc_surface=true
    fi
    if is_spine_self_path "$file"; then
      spine_self_surface=true
    fi
    # Both flags are decided — nothing later in the change set can change them.
    if [ "$doc_surface" = true ] && [ "$spine_self_surface" = true ]; then
      break
    fi
  done <<< "$changed_files"
fi

# ---- runtime tier, delegated to the CI classifier ----
# Feed the changed-file list to report-changed-surfaces.sh as explicit paths;
# it prints `key=value` for every surface when GITHUB_OUTPUT is unset.  We read
# `implementation_jvm` (core JVM) and `cljs_node_test` (npm/CLJS/JS-harness/
# isolation), and note whether ANY surface was recognised at all.
impl_jvm=false
cljs_node=false
recognised_surface=false
classifier_failed=false

if [ -n "$changed_files" ]; then
  # Build the argv array portably (avoid `mapfile` — bash 4 only; macOS ships
  # bash 3.2).  One path per line; the change set never contains blank lines.
  _changed_arr=()
  while IFS= read -r _f; do
    [ -n "$_f" ] && _changed_arr+=("$_f")
  done <<< "$changed_files"
  if classify_out="$(GITHUB_OUTPUT='' bash "$classifier" "${_changed_arr[@]}" 2>/dev/null)"; then
    while IFS='=' read -r key value; do
      case "$key" in
        implementation_jvm) impl_jvm="$value" ;;
        cljs_node_test)     cljs_node="$value" ;;
      esac
    done <<< "$classify_out"
    if printf '%s' "$classify_out" | grep -q '=true'; then
      recognised_surface=true
    fi
  else
    classifier_failed=true
  fi
fi

# ---- lint surface, delegated to the runner that owns the lint gate ----
# A SEPARATE classifier because lint.yml has a separate one: the clj-kondo job
# is armed by its own `detect` job, not by report-changed-surfaces.sh, and the
# two populations genuinely differ (`examples/`, `testbeds/`, `tools/*` and
# `.clj-kondo/` arm the linter and no runtime tier).  `lint_kondo.py --classify`
# answers from the `--lint` roots it reads out of lint.yml, so a root added
# there arms this lane with no edit here — the same "read the roster, never
# restate it" discipline the JVM artefact selection below uses.
kondo_surface=false
kondo_classify_failed=false
if [ -n "$changed_files" ]; then
  _kondo_arr=()
  while IFS= read -r _f; do
    [ -n "$_f" ] && _kondo_arr+=("$_f")
  done <<< "$changed_files"
  if kondo_out="$(python "$spine_root/scripts/lint_kondo.py" --classify \
                    "${_kondo_arr[@]}" 2>/dev/null)"; then
    case "$kondo_out" in
      *kondo_surface=true*) kondo_surface=true ;;
    esac
  else
    kondo_classify_failed=true
  fi
fi

# ---- resolve the tiers, honouring overrides + conservative fallback ----
run_jvm=false
run_node=false
run_docs=false
plan_reason="classified"

if [ "$run_all" = true ]; then
  run_jvm=true
  run_node=true
  run_docs=true
  plan_reason="override (--all)"
elif [ "$base_resolved" != true ] || [ "$classifier_failed" = true ]; then
  # Indeterminate: no origin/main base to diff against, or the classifier
  # itself failed.  Fall back to the complete runtime run rather than risk
  # under-testing.  (Documentation gates still key on the doc surface.)
  run_jvm=true
  run_node=true
  run_docs="$doc_surface"
  if [ "$base_resolved" != true ]; then
    plan_reason="conservative fallback (origin/main unresolved)"
  else
    plan_reason="conservative fallback (classifier error)"
  fi
elif [ -z "$changed_files" ]; then
  # Nothing changed vs origin/main and a clean working tree — only the cheap
  # always-on static checks are meaningful.
  plan_reason="no changes (static checks only)"
elif [ "$spine_self_surface" = true ]; then
  # The spine's own tree changed (rf2-fhdd3): EVERY tier runs.
  #
  # The documentation tier because the runner is where the doc gates and the
  # mkdocs resolution live — that is the whole bead.  The JVM and node tiers
  # because they already ran: before this branch existed, a spine-only diff
  # matched no surface at all and fell into the `unknown surface` fallback
  # below, which arms both.  Now that the runner is ON the documentation
  # surface that fallback no longer fires for it, and without this branch the
  # fix would have SILENTLY NARROWED the runtime coverage of every spine edit
  # — a repair that quietly removes a gate is the same defect in a new coat.
  # They also earn their place: the spine INVOKES those suites, so running
  # them is what proves a reworked invocation still works.
  #
  # The fixture tree rides the same branch.  It could be argued into
  # docs-tier-only (it cannot affect runtime code), but the two paths change a
  # handful of times a year between them, and over-testing the gate that
  # decides which gates run is the cheaper mistake by a wide margin.
  run_jvm=true
  run_node=true
  run_docs=true
  plan_reason="spine self-change (every tier)"
elif [ "$recognised_surface" != true ] && [ "$doc_surface" != true ]; then
  # Unknown surface: the change set is non-empty but matches no runtime surface
  # AND no documentation surface.  Conservatively run the full runtime spine.
  run_jvm=true
  run_node=true
  plan_reason="conservative fallback (unknown surface)"
else
  [ "$impl_jvm" = true ] && run_jvm=true
  [ "$cljs_node" = true ] && run_node=true
  run_docs="$doc_surface"
fi

# Documentation-gate overrides apply last, on top of any decision above.
case "$with_docs" in
  force) run_docs=true ;;
  skip)  run_docs=false ;;
esac

# The clj-kondo lane decides on its OWN surface, not on the tiers above — it
# reads trees no runtime tier owns.  `--all` runs it; an unresolvable base or a
# classifier that itself failed runs it, because an indeterminate change set is
# not evidence of absence.
run_kondo=false
if [ "$run_all" = true ]; then
  run_kondo=true
elif [ "$base_resolved" != true ] || [ "$kondo_classify_failed" = true ]; then
  run_kondo=true
else
  run_kondo="$kondo_surface"
fi

# ---------------------------------------------------------------------------
# WHICH JVM ARTEFACT SUITES — the roster is READ, never listed here (rf2-uwszd).
#
# `scripts/test-jvm-implementation.sh` already carries the canonical roster of
# implementation JVM artefacts, and the spine's header already points workers at
# it.  Parsing that array is therefore not a second surface map: it is the same
# roster, from the file that owns it, so an artefact added there is armed here
# without a second edit.  The alternative the bead sketched — one new classifier
# key per artefact — would have to be kept in step with test.yml's per-artefact
# jobs by hand, which is the staleness class this whole pair of beads is about.
#
# `implementation/core` runs whenever the tier runs even if the diff never
# touched it: it is the substrate every other artefact sits on, and its suite
# reaches the schema / machine / route / flow surfaces directly.  Everything
# else is armed only by a change under its own tree.
# ---------------------------------------------------------------------------
jvm_artefact_roster() {
  awk '
    /^artefacts=\(/ { inside = 1; next }
    inside && /^\)/  { inside = 0 }
    inside {
      sub(/#.*/, "")
      gsub(/[ \t\r]/, "")
      if (length($0)) print
    }' "$spine_root/scripts/test-jvm-implementation.sh"
}

jvm_run_list=""
jvm_skipped_list=""
while IFS= read -r artefact; do
  [ -z "$artefact" ] && continue
  armed=false
  if [ "$artefact" = "implementation/core" ]; then
    armed=true
  elif [ -n "$changed_files" ]; then
    while IFS= read -r file; do
      case "$file" in
        "$artefact"/*) armed=true; break ;;
      esac
    done <<< "$changed_files"
  fi
  if [ "$armed" = true ]; then
    jvm_run_list="$jvm_run_list $artefact"
  else
    jvm_skipped_list="$jvm_skipped_list $artefact"
  fi
done <<< "$(jvm_artefact_roster)"

if [ -z "$jvm_run_list" ]; then
  printf 'error: no JVM artefact roster found in scripts/test-jvm-implementation.sh\n' >&2
  exit 2
fi

if [ "$plan_only" = true ]; then
  # Report how mkdocs resolved, so the docs gate's availability is observable
  # without running an 18-second site build — and so the self-test can pin it
  # (rf2-g7p7l).  Resolved ONLY when the documentation tier would run, so a
  # code-only `--plan` stays free.
  mkdocs_plan="(docs tier skipped)"
  if [ "$run_docs" = true ]; then
    if resolve_mkdocs; then
      mkdocs_plan="$mkdocs_cmd"
    else
      mkdocs_plan="unresolved"
    fi
  fi
  printf 'fast-pr plan\n'
  printf '  documentation gates: %s\n' "$([ "$run_docs" = true ] && echo run || echo skip)"
  printf '  spine self-test:     %s\n' \
    "$([ "$spine_self_surface" = true ] && echo run || echo 'skip (spine unchanged)')"
  printf '  mkdocs --strict:     %s\n' "$mkdocs_plan"
  printf '  JVM tier:            %s\n' "$([ "$run_jvm" = true ] && echo run || echo skip)"
  printf '  node/CLJS suite:     %s\n' "$([ "$run_node" = true ] && echo run || echo skip)"
  printf '  clj-kondo (pinned):  %s\n' "$([ "$run_kondo" = true ] && echo run || echo skip)"
  printf '  reason:              %s\n' "$plan_reason"
  printf '  JVM artefact suites: %s\n' \
    "$([ "$run_jvm" = true ] && echo "${jvm_run_list# }" || echo '(tier skipped)')"
  # The tier NAMES understate their contents; say so here too, so `--plan` is
  # not read as a coverage claim (rf2-dgzaf, rf2-uwszd).
  printf '  note:                the JVM tier is implementation/core PLUS the'
  printf ' artefacts the diff touched —\n'
  printf '                       every other artefact suite runs in CI, not'
  printf ' here (scripts/test-jvm-implementation.sh).\n'
  printf '                       No browser, bundle, adapter, Xray, MCP or'
  printf ' tool-JVM gate is in any tier.\n'
  # `PLAN` keeps its three fields (rf2-x1mz): the self-test asserts that line
  # verbatim in fourteen cases, and a fourth field would rewrite every one of
  # them to say nothing new.  The kondo lane gets its own machine-readable
  # line, as `PLAN-JVM` / `PLAN-MKDOCS` / `PLAN-SELFTEST` already do.
  printf 'PLAN docs=%s jvm=%s node=%s\n' "$run_docs" "$run_jvm" "$run_node"
  printf 'PLAN-KONDO %s\n' "$([ "$run_kondo" = true ] && echo run || echo skip)"
  printf 'PLAN-JVM%s\n' "$([ "$run_jvm" = true ] && printf '%s' "$jvm_run_list")"
  printf 'PLAN-MKDOCS %s\n' "$mkdocs_plan"
  printf 'PLAN-SELFTEST %s\n' "$([ "$spine_self_surface" = true ] && echo run || echo skip)"
  exit 0
fi

# ---------------------------------------------------------------------------
# ONE LIVE RUN PER TREE — a run that outlives its invocation is REAPED, not
# inherited (rf2-ketqy).
#
# THE OBSERVED DEFECT, twice in one day.  A worker starts this spine in the
# foreground, the agent harness hits its cap, and the tool call returns — but
# the run does not stop.  Told the gate "timed out", the worker relaunches it
# in the same tree, and two live spines then share that tree.  Two kinds of
# damage follow, both seen on real runs:
#
#   1. TWO WRITERS, ONE FILE.  When both runs were observed, AGENTS.md had
#      every worker redirect this script to a path fixed per tree, so the two
#      interleaved into it and produced NUL-riddled output in which no line
#      could be trusted.  AGENTS.md now names gate artefacts for the ATTEMPT as
#      well as the worktree (`gate-fastpr-<worktree>-1.log`, bumped on each
#      re-run), which sends the survivor's writes to a file nobody quotes.
#      That NARROWS this damage; it does not remove the need for the reap.  The
#      rule binds a worker who remembers to bump, the survivor keeps burning a
#      tree's CPU either way, and damage 2 below is untouched by any naming.
#   2. A SPURIOUS RED IN THE GATE'S OWN ENVIRONMENT CLASS.  The older run's
#      `npm run test:cljs` unlinks out/node-test.js underneath the younger
#      run's live per-namespace isolation sweep, which then fails with "the
#      bundle changed on disk during the sweep" and MODULE_NOT_FOUND.  The
#      sweep is RIGHT — something did mutate the bundle — but the something is
#      a dead run, and at the moment a worker meets it that is
#      indistinguishable from an isolation regression in their own diff.
#
# WHY THE REAP RUNS HERE, AT THE START OF THE NEXT RUN, AND NOT ONLY FROM A
# SIGNAL TRAP.  The obvious remedy is to trap INT/TERM and reap on the way out.
# One is installed below and it earns its place, but MEASURED — not assumed —
# it cannot be the whole fix:
#
#   * The harness cap does not signal anything.  It BACKGROUNDS the call: the
#     tool returns while the process tree keeps running.  A probe script with
#     traps on INT/TERM/HUP/QUIT/EXIT recorded no trap firing at all and its
#     child went on ticking; this spine, capped mid-run, advanced two more
#     stages and grew its log by 7.4 KB after the tool call had returned.
#   * The harness's explicit stop path behaves the same way — it reports
#     success and the tree keeps running.
#   * Even when a signal IS delivered, bash defers a trapped signal until the
#     current FOREGROUND child returns.  Measured: SIGTERM at t+3s to a script
#     sitting in `bash -c 'sleep 25'` ran the handler at t+25s.  Every
#     expensive step of this spine is exactly such a child, so a trap-only
#     remedy would reap minutes after the damage window had opened.
#
# The one moment a reap can always run is the start of the NEXT run — which is
# also the moment it matters, because that is the run whose log and bundle are
# about to be corrupted.  Both workers who hit this recovered only because they
# thought to hunt for the orphaned tree by hand; that hunt is what the block
# below automates.
#
# NOT A LOCK.  A second run never waits and never refuses — it clears the dead
# one and proceeds.  Refusing would be worse than the defect it fixes: the cap
# fires on most full runs of this spine, so a refusal would block the relaunch
# every single time.
# ---------------------------------------------------------------------------
spine_run_dir="$spine_root/.scratch"
spine_pidfile="$spine_run_dir/test-fast-pr.run"

# WINDOWS NEEDS A SECOND MECHANISM, and this too was measured rather than
# assumed.  MSYS `ps` tracks what a shell here spawned, native binaries
# included — `clojure.exe` and `node.exe` show up with a usable PPID — but
# NOTHING BENEATH THEM DOES.  Measured on a live `bash -lc "... clojure -M ..."`
# gate exactly like the ones below: the `java.exe` clojure spawns is absent
# from `ps -ef` and from `ps`, and `ps -ef` reports the shell as having no
# children at all.  java is the shadow-cljs compile, i.e. precisely the process
# that unlinks out/node-test.js, so on Windows the POSIX walk cannot even name
# the process that has to die.  Windows itself knows the link
# (Win32_Process.ParentProcessId) and `taskkill /T` walks it: on the same tree
# it terminated clojure, the java beneath it and java's own child, all three.
#
# THE ENV VARS ARE LOAD-BEARING, not decoration.  Git Bash rewrites an argument
# that looks like a POSIX path, so a bare `taskkill /F /T /PID n` arrives as
# `taskkill F:/ ...` and dies with "Invalid argument/option - 'F:/'" — which,
# behind the `>/dev/null 2>&1 || true` this call needs, is a SILENT no-op.
# `MSYS_NO_PATHCONV=1` is Git-for-Windows' switch and `MSYS2_ARG_CONV_EXCL='*'`
# is MSYS2's; Cygwin does no such rewriting and ignores both.  Setting the pair
# is what makes one spelling work on all three.
#
# Both arms are guarded, so a host with neither reaps nothing rather than
# failing the run.
spine_is_windows=false
case "$(uname -s 2>/dev/null)" in
  MINGW*|MSYS*|CYGWIN*)
    if command -v taskkill >/dev/null 2>&1; then
      spine_is_windows=true
    fi
    ;;
esac

# `ps -ef` is the ONE listing whose PID and PPID sit in the same columns ($2,
# $3) on Git Bash, macOS and Linux alike.  The tidier spellings are not
# portable here: MSYS `ps` rejects both `-o` and `-A`, and ships no `pgrep`.
# No `exit` in any awk program below, deliberately: exiting early would SIGPIPE
# `ps`, and under this script's `set -o pipefail` that turns a lookup into a
# failed pipeline and `set -e` would take the whole run down with it.
spine_child_pids() {
  { ps -ef 2>/dev/null || true; } |
    awk -v parent="$1" '$3 == parent && $2 != parent { print $2 }'
}

spine_descendant_pids() {
  local kid
  for kid in $(spine_child_pids "$1"); do
    spine_descendant_pids "$kid"
    printf '%s\n' "$kid"
  done
}

spine_winpid() {
  { ps 2>/dev/null || true; } |
    awk -v pid="$1" '$1 == pid && !seen { print $4; seen = 1 }'
}

spine_taskkill_tree() {
  local winpid
  if [ "$spine_is_windows" != true ]; then
    return 0
  fi
  winpid="$(spine_winpid "$1")"
  if [ -n "$winpid" ]; then
    MSYS2_ARG_CONV_EXCL='*' MSYS_NO_PATHCONV=1 \
      taskkill /F /T /PID "$winpid" >/dev/null 2>&1 || true
  fi
  return 0
}

# Everything BELOW pid, deepest first, TERM then KILL.  Never touches pid.
spine_kill_descendants() {
  local root="$1" kid pid pids
  for kid in $(spine_child_pids "$root"); do
    spine_taskkill_tree "$kid"
  done
  pids="$(spine_descendant_pids "$root")"
  for pid in $pids; do
    kill -TERM "$pid" 2>/dev/null || true
  done
  if [ -n "$pids" ]; then
    sleep 1
    for pid in $pids; do
      kill -KILL "$pid" 2>/dev/null || true
    done
  fi
  return 0
}

# Two guards stand between "the recorded pid is alive" and "kill it", because a
# wrong answer there kills a stranger's process tree.
#
# FIRST, the pid must still BE this gate.  Pids are recycled, and `ps -ef`
# prints the command with its arguments on all three platforms, so the cheapest
# sound check is to read it back.  If the listing does not name this script the
# predecessor is not reaped at all — the run degrades to the old behaviour
# rather than killing something it cannot identify.
spine_pid_is_this_gate() {
  local line
  line="$({ ps -ef 2>/dev/null || true; } |
    awk -v pid="$1" '$2 == pid && !seen { print; seen = 1 }')"
  case "$line" in
    *test-fast-pr*) return 0 ;;
  esac
  return 1
}

# SECOND, it must not be one of OUR ancestors.  `scripts/test-rigorous-local.sh`
# invokes this spine as a child, so an ancestor pid in the file would be a run
# killing whatever started it.
spine_is_own_ancestor() {
  local target="$1" cur="$$" hops=0
  while [ -n "$cur" ] && [ "$cur" != 0 ] && [ "$cur" != 1 ] && [ "$hops" -lt 64 ]; do
    if [ "$cur" = "$target" ]; then
      return 0
    fi
    cur="$({ ps -ef 2>/dev/null || true; } |
      awk -v pid="$cur" '$2 == pid && !seen { print $3; seen = 1 }')"
    hops=$((hops + 1))
  done
  return 1
}

spine_release_run() {
  local held=""
  if [ -f "$spine_pidfile" ]; then
    held="$(tr -dc '0-9' < "$spine_pidfile" 2>/dev/null || true)"
    if [ "$held" = "$$" ]; then
      rm -f "$spine_pidfile" 2>/dev/null || true
    fi
  fi
  return 0
}

spine_claim_run() {
  local prev=""
  mkdir -p "$spine_run_dir" 2>/dev/null || true
  if [ -f "$spine_pidfile" ]; then
    prev="$(tr -dc '0-9' < "$spine_pidfile" 2>/dev/null || true)"
  fi
  if [ -n "$prev" ] && [ "$prev" != "$$" ] && kill -0 "$prev" 2>/dev/null &&
     spine_pid_is_this_gate "$prev" && ! spine_is_own_ancestor "$prev"; then
    printf 'REAPING A PREVIOUS RUN: pid %s is still live in this tree.\n' "$prev"
    printf '  It outlived the tool call that started it.  Left alone it would\n'
    printf '  interleave into this log and mutate out/node-test.js underneath\n'
    printf '  the isolation sweep, which reads as a regression in your own diff\n'
    printf '  (rf2-ketqy).  Killing it and its children before starting.\n'
    spine_taskkill_tree "$prev"
    spine_kill_descendants "$prev"
    kill -TERM "$prev" 2>/dev/null || true
    sleep 1
    kill -KILL "$prev" 2>/dev/null || true
    if kill -0 "$prev" 2>/dev/null; then
      printf '  WARNING: pid %s survived the reap.  Kill it by hand before you\n' "$prev"
      printf '  trust anything this run reports.\n'
    else
      printf '  reaped.\n'
    fi
  fi
  printf '%s\n' "$$" > "$spine_pidfile" 2>/dev/null || true
  printf 'run pid: %s\n' "$$"
}

# The trap half of the remedy, for the paths that DO signal: an interactive
# Ctrl-C, `timeout(1)`, a CI job cancellation, a plain `kill`.  Ctrl-C is where
# it earns its keep on Windows — SIGINT reaches the foreground process group,
# so `clojure.exe` dies with the shell while the `java.exe` beneath it does
# not, and this is what collects that.  The handler clears the traps first, so
# a second Ctrl-C is not queued behind anything.
#
# It deliberately does NOT kill by process group.  Measured here: every child
# of this script shares the CALLER's process group, so `kill -- -$PGID` would
# take the harness or terminal that invoked the gate down with it.
spine_on_signal() {
  trap - INT TERM HUP QUIT EXIT
  printf '\n%s received — killing the child processes of this run before exiting (rf2-ketqy)\n' \
    "$1" >&2
  spine_kill_descendants "$$"
  spine_release_run
  exit "$2"
}

trap 'spine_on_signal SIGINT 130'  INT
trap 'spine_on_signal SIGTERM 143' TERM
trap 'spine_on_signal SIGHUP 129'  HUP
trap 'spine_on_signal SIGQUIT 131' QUIT

# EXIT does not reap — on a normal or a failing exit every gate has already
# returned.  It only releases the claim, and it must not disturb the exit
# status: AGENTS.md has every worker echo this script's `$?` into a file, so
# the handler runs nothing that can fail and never calls `exit` itself.
trap 'spine_release_run' EXIT

spine_claim_run

printf '=== fast PR spine: docs=%s jvm=%s node=%s (%s) ===\n' \
  "$run_docs" "$run_jvm" "$run_node" "$plan_reason"

# ---------------------------------------------------------------------------
# ALWAYS-ON static / drift checks.
#
# Cheap, repo-wide, pure-Python (plus the version-lockstep shell guard).  These
# mirror CI's always-on jobs (verify-version-lockstep, the repo-invariant
# python job, verify-readme-links) and run on every diff regardless of tier —
# the drift they catch can be introduced by a directory add/remove or a source
# rename that never touches the tier that owns it.
# ---------------------------------------------------------------------------
run "lockstep version drift" "./.github/scripts/verify-version-lockstep.sh" \
  bash -lc "tmp='$spine_root/.github/scripts/.verify-version-lockstep.tmp.'\$\$; trap 'rm -f \"\$tmp\"' EXIT; tr -d '\r' < '$spine_root/.github/scripts/verify-version-lockstep.sh' > \"\$tmp\"; bash \"\$tmp\""

run "skill/MCP allowed-tools drift" "python scripts/check_skill_mcp_drift.py --verbose --ci" \
  python "$spine_root/scripts/check_skill_mcp_drift.py" --verbose --ci

# Cite-integrity guard (rf2-1nb8k): every M-id the re-frame-migration skill
# cites must name a consistent rule in MIGRATION.md.  Catches the id-collision /
# phantom-cite class (skill cited M-66/M-67 for rules MIGRATION.md assigned to
# History-states / Xray-static-mode).  Runs unconditionally — fast pure-Python,
# and the drift can be introduced by a MIGRATION.md *heading* edit that the
# documentation-surface gate below also sees but doesn't semantically check.
run "skill migration cite-integrity" "python scripts/check_skill_migration_cites.py --verbose --ci" \
  python "$spine_root/scripts/check_skill_migration_cites.py" --verbose --ci

# Foundation-order guard (rf2-708nm): the re-frame2-implementor skill must keep
# Spec 015 (Data Classification — v1-required) inside the core-complete gate.
# Catches the drift where an entry point reads "001 -> ... -> 009 -> optional"
# (gate BEFORE 015), which would let a fresh session declare v1-core-complete
# without the required privacy/large-payload elision surface.  Self-test first
# (proves the guard fires on the drift shapes), then the live scan.
run "implementor order-guard self-test" "python scripts/check_skill_implementor_order.py --self-test" \
  python "$spine_root/scripts/check_skill_implementor_order.py" --self-test

run "implementor foundation-order" "python scripts/check_skill_implementor_order.py --verbose --ci" \
  python "$spine_root/scripts/check_skill_implementor_order.py" --verbose --ci

# Eval-docs drift guard (rf2-r2xswa; generalised rf2-xw7ra9): each packaged
# skill's eval harness README carries a hand-maintained coverage table, total
# count, and per-axis breakdowns that silently fell behind evals.json (the
# re-frame2 harness: a 7th eval while the README still said "Six evals …"; the
# re-frame2-improver harness: 26/10 while prose still said "9 behavioural").
# The gate is multi-target — it scans the re-frame2 AND re-frame2-improver
# harnesses.  Self-test first (proves it fires on count/name/tally drift across
# both README shapes), then the live scan asserts each README agrees with its
# JSON.
run "eval-docs guard self-test" "python scripts/check_skill_eval_docs.py --self-test" \
  python "$spine_root/scripts/check_skill_eval_docs.py" --self-test

run "skill eval-docs match evals.json" "python scripts/check_skill_eval_docs.py --verbose --ci" \
  python "$spine_root/scripts/check_skill_eval_docs.py" --verbose --ci

# README inventory ratchet (rf2-198k3): layout-map<->disk bijection +
# 'N <noun>' count-numeral claims.  Runs unconditionally (not gated on a
# documentation diff) because the drift it catches can be triggered by a *dir*
# add/remove that never touches an .md file.  Fast + pure-Python.
run "README inventory ratchet" "python scripts/check_readme_inventories.py" \
  python "$spine_root/scripts/check_readme_inventories.py"

# EP-0007 §Enforcement retired-spelling gate (rf2-ziak6w): a retired spelling
# reappearing in repo source is a CI failure, not a doc note.  THREE retired
# spellings are lintable — the bare `:frame` event-context coeffect read
# (retired by rf2-1m6rf1 for `:rf.frame/id`), the `:url` / `:to`
# redirect-target key on an SSR redirect fx (retired by rf2-vngir for
# `:location`), and route metadata `:query-retain` (retired by EP-0037 R5 /
# rf2-jlmgt with no alias).  Scoped precisely to the retired SHAPES so it never
# fires on the sanctioned public `:frame` opt, the trace `:frame` tag, the
# fx-handler ctx `:frame`, or client-navigation `:url`.  Runs unconditionally,
# and over EVERY Clojure source tree — not just implementation/ (rf2-kqxe6.25:
# the `examples/` + `skills/` corpus a reader copies from was the one place the
# ratchet could not see).  The roster lives in the script, so this invocation
# and test.yml's cannot widen apart.  Self-test first (proves the gate fires on
# each retired shape and stays green on every sanctioned counterpart), then the
# live scan asserts the corpus is clean.  See
# scripts/check_retired_spellings.py.
run "retired-spelling gate self-test" "python scripts/check_retired_spellings.py --self-test" \
  python "$spine_root/scripts/check_retired_spellings.py" --self-test

run "retired-spelling gate (EP-0007)" "python scripts/check_retired_spellings.py --verbose" \
  python "$spine_root/scripts/check_retired_spellings.py" --verbose

# Thrown-error human-message corpus gate (rf2-6bb3pg, Spec 009 §The thrown-error
# shape / rf2-vvixub): a framework `(ex-info …)` whose MESSAGE is a bare
# `:rf.*` discriminator keyword is the keyword-only shape rf2-vvixub abolished —
# a CI failure, not a doc note. Replaces the curated allow-list conformance
# test (thrown_error_message_conformance_cljs_test) with a CORPUS sweep so a
# new/never-converted keyword-only throw-site cannot ride back in invisibly.
# Self-test first (proves it FIRES on each bare-keyword shape + stays GREEN on
# the conformant counterparts), then the live scan asserts framework source is
# clean.  See scripts/check_thrown_error_messages.py.
run "thrown-error message gate self-test" "python scripts/check_thrown_error_messages.py --self-test" \
  python "$spine_root/scripts/check_thrown_error_messages.py" --self-test

run "thrown-error message gate (rf2-vvixub)" "python scripts/check_thrown_error_messages.py --verbose" \
  python "$spine_root/scripts/check_thrown_error_messages.py" --verbose

# re-frame.ui Root lifecycle projection guard (rf2-vxgfnd.291): the exact
# failed-first-mount rollback ordering, three-state settlement law, three
# tearing-down diagnostics, WeakRef capability boundary, and their 004C/006/009
# + API/guide projections are one atomic contract. Literal anchors are
# intentional (not a general prose parser). Self-test first mutates every tooth
# independently, then the live scan catches code/spec/doc drift.
run "UI Root lifecycle drift self-test" "python scripts/check_ui_root_lifecycle_drift.py --self-test" \
  python "$spine_root/scripts/check_ui_root_lifecycle_drift.py" --self-test

run "UI Root lifecycle drift" "python scripts/check_ui_root_lifecycle_drift.py --ci" \
  python "$spine_root/scripts/check_ui_root_lifecycle_drift.py" --ci

# EP-0010 §Validation/Conformance ambient-durable-read gate (rf2-f2t151): a
# direct ambient host read (clock / RNG / browser fact) written into a DURABLE
# frame-state field inside a durable-write namespace (resource reducers,
# work-ledger writers, reply handlers, mutation handlers, restore/hydration
# installers, machine snapshot writers) is a CI failure — durable state must be
# a function of prior frame-state plus explicit causal tokens (EP-0010).  Scoped
# precisely to the violating SHAPE (a durable field key whose value is an
# ambient read) so it never fires on the sanctioned sites: trace/perf code,
# effect interpreters before they dispatch a reply token, timer scheduling,
# host-transient side tables, diagnostics, and effect-side crypto.  Self-test
# first (proves it FLAGS each planted ambient durable read + PASSES every
# sanctioned counterpart), then the live scan asserts the durable-write
# namespaces are clean.  See scripts/check_ambient_durable_reads.py.
run "ambient-durable-read gate self-test" "python scripts/check_ambient_durable_reads.py --self-test" \
  python "$spine_root/scripts/check_ambient_durable_reads.py" --self-test

run "ambient-durable-read gate (EP-0010)" "python scripts/check_ambient_durable_reads.py --verbose" \
  python "$spine_root/scripts/check_ambient_durable_reads.py" --verbose

# Test-lane bijection (rf2-4hc9p, follows the rf2-qqzmf per-lane floor).  The
# floor makes a lane that ran ZERO tests red; it cannot see a lane that ran
# SOME of what it should — nine `.cljc` suites once sat on the `:node-test`
# classpath with namespaces the `cljs-test$` selector could not match, and the
# lane still reported thousands of passing tests (rf2-ezbzvm).  This gate reads
# every lane from the file that DEFINES it (each shadow-cljs.edn's test builds,
# each artefact's `deps.edn` `:test` alias via the two JVM rosters) and asserts
# the bijection both ways: every file defining a top-level `deftest` is reached
# by some selector, and every selector reaches something.  Runs unconditionally
# — the drift it catches is introduced by a RENAME or a directory move that need
# not touch the tier that owns it.  Self-test first (proves each rule fires on
# its own defect and stays green on a clean tree), then the live scan.
run "test-lane bijection self-test" "python scripts/check_test_lane_bijection.py --self-test" \
  python "$spine_root/scripts/check_test_lane_bijection.py" --self-test

run "test-lane bijection (rf2-4hc9p)" "python scripts/check_test_lane_bijection.py" \
  python "$spine_root/scripts/check_test_lane_bijection.py" --repo-root "$spine_root"

# JVM roster <-> CI required-job bijection (rf2-as6bg).  The gate above READS
# the two JVM rosters to discover the JVM lanes, so an artefact missing from a
# roster is not a violation to it — it is simply not a lane.  That blind spot
# is how `tools/machines-viz` (632 tests) and `tools/testbed-support` (32) sat
# on the local roster with no CI job at all, and how `tools/template` had a CI
# job and no local lane.  This gate asserts the two agree in BOTH directions:
# every rostered artefact is run by a job in `all-required-passed`'s `needs:`,
# and every required job running `clojure -M:test` is on a roster.  Self-test
# first, then the live scan.
run "JVM roster/CI bijection self-test" "python scripts/check_jvm_lane_rosters.py --self-test" \
  python "$spine_root/scripts/check_jvm_lane_rosters.py" --self-test

run "JVM roster <-> CI bijection (rf2-as6bg)" "python scripts/check_jvm_lane_rosters.py" \
  python "$spine_root/scripts/check_jvm_lane_rosters.py"

# Fast-PR gap map (rf2-13zre).  The two gates above assert that a JVM lane
# exists in both places; NOTHING asserted anything about the required checks that
# have no local lane at all — and on 2026-08-04 three workers in a row shipped a
# green spine into a red required check, each a different one.  The nastiest was
# not a job this spine skips but a STEP INSIDE a job it runs: the EP-0036
# donor-boundary `git grep`, which is the last step of `jvm-freehand` and so
# reports under the display name "JVM freehand (clojure -M:test)" — a test-suite
# name for something that is not a test.  The skipped-tier enumeration at the
# bottom of this script cannot see that class by construction: the tier is not
# skipped and the step is not a suite.
#
# `check_fast_pr_gap.py` derives the required set from the three aggregator jobs
# whose display names ARE the branch ruleset's required contexts, derives each
# one's gate steps, and reports every step no lane of this spine runs — with the
# local command read from the step itself.  Self-test first (proves the
# step-granularity class fires and that a stale lane signature goes red), then
# the live audit.  The `--brief` digest is printed with the PASS line below.
run "fast-PR gap map self-test" "python scripts/check_fast_pr_gap.py --self-test" \
  python "$spine_root/scripts/check_fast_pr_gap.py" --self-test

run "fast-PR gap map (rf2-13zre)" "python scripts/check_fast_pr_gap.py --verbose" \
  python "$spine_root/scripts/check_fast_pr_gap.py" --check

# ---------------------------------------------------------------------------
# THE REST OF CI'S ALWAYS-ON INVARIANT CHECKERS (rf2-ejm7m).
#
# The gate above NAMED these; this block RUNS them.  Every line below is a
# required check from a job CI runs UNCONDITIONALLY — `verify-skill-mcp-drift`
# and `verify-readme-links` both sit in `all-required-passed`'s `needs:` with no
# `if:` and no `needs: detect`, so no diff can fail to reach them.  Always-on
# here is therefore the faithful mirror, not a local choice.
#
# MEASURED BEFORE ADDING, because "cheap" was an assumption worth checking
# (rf2-ejm7m ruling).  The first 29 invocations below, timed individually from a
# clean checkout: 4.46s cold / 4.58s warm as a batch.  The largest single one is
# `check_keyword_catalogue_drift --verbose` at ~1.0s; twenty-five of the
# remaining twenty-eight are under 0.15s, which is barely more than the ~0.02s
# interpreter start each one pays.  Against a 14.3s always-on block that is
# ~30%, for thirteen live scans and sixteen self-test arms that previously had
# NO way to be run locally at all.
#
# The `check_gate_scheduling` pair at the end was measured the same way before
# being added (rf2-k78o2): 0.75s / 0.86s / 0.93s for the pair over three warm
# runs on Windows, the two arms within ~50ms of each other.  Both read the same
# two inputs — implementation/package.json and every file in
# .github/workflows/ — so neither can grow with the size of the tree.
#
# WRITTEN OUT ONE PER LINE, deliberately.  A loop over a script-name variable
# would be shorter and would BREAK THE RATCHET: `check_fast_pr_gap.py` derives
# this spine's coverage by reading THIS FILE's source for the literal shape
# `python "$spine_root/scripts/check_<name>.py"`, matching (script, mode) pairs
# against CI's.  Interpolate the name and the derivation sees nothing, every CI
# checker is re-reported as unrun, and the gap map goes from derived to
# fictional.  The literal form is what keeps the map true with no second list to
# maintain — add a line here and the checker leaves the report by construction.
#
# Each pairs CI's self-test arm with CI's live arm, in CI's own order and with
# CI's own flags, so this block and the `verify-skill-mcp-drift` job log read
# the same way.  The rationale for each individual gate lives where it was
# written — in that job's step comments in `.github/workflows/test.yml`.
run "MCP/skill title-safety self-test" "python scripts/check_skill_mcp_drift.py --self-test --ci" \
  python "$spine_root/scripts/check_skill_mcp_drift.py" --self-test --ci

run "skill migration cite-integrity self-test" "python scripts/check_skill_migration_cites.py --self-test" \
  python "$spine_root/scripts/check_skill_migration_cites.py" --self-test

run "migration contract-drift self-test" "python scripts/check_skill_migration_contract_drift.py --self-test" \
  python "$spine_root/scripts/check_skill_migration_contract_drift.py" --self-test

run "migration contract drift (M-11/M-13)" "python scripts/check_skill_migration_contract_drift.py --verbose --ci" \
  python "$spine_root/scripts/check_skill_migration_contract_drift.py" --verbose --ci

run "migration O-16/O-17 router self-test" "python scripts/check_skill_migration_o1617_router_drift.py --self-test" \
  python "$spine_root/scripts/check_skill_migration_o1617_router_drift.py" --self-test

run "migration O-16/O-17 router drift" "python scripts/check_skill_migration_o1617_router_drift.py --verbose --ci" \
  python "$spine_root/scripts/check_skill_migration_o1617_router_drift.py" --verbose --ci

run "implementor partition self-test" "python scripts/check_skill_implementor_partition_drift.py --self-test" \
  python "$spine_root/scripts/check_skill_implementor_partition_drift.py" --self-test

run "implementor partition drift" "python scripts/check_skill_implementor_partition_drift.py --verbose --ci" \
  python "$spine_root/scripts/check_skill_implementor_partition_drift.py" --verbose --ci

run "re-frame2 skill drift self-test" "python scripts/check_skill_re_frame2_drift.py --self-test" \
  python "$spine_root/scripts/check_skill_re_frame2_drift.py" --self-test

run "re-frame2 skill drift" "python scripts/check_skill_re_frame2_drift.py --verbose --ci" \
  python "$spine_root/scripts/check_skill_re_frame2_drift.py" --verbose --ci

run "skill package-refs self-test" "python scripts/check_skill_package_refs.py --self-test" \
  python "$spine_root/scripts/check_skill_package_refs.py" --self-test

run "skill package refs" "python scripts/check_skill_package_refs.py --verbose --ci" \
  python "$spine_root/scripts/check_skill_package_refs.py" --verbose --ci

run "pair-authoring drift self-test" "python scripts/check_skill_pair_authoring_drift.py --self-test" \
  python "$spine_root/scripts/check_skill_pair_authoring_drift.py" --self-test

run "pair-authoring drift" "python scripts/check_skill_pair_authoring_drift.py --verbose --ci" \
  python "$spine_root/scripts/check_skill_pair_authoring_drift.py" --verbose --ci

run "setup-counter drift self-test" "python scripts/check_skill_setup_counter_drift.py --self-test" \
  python "$spine_root/scripts/check_skill_setup_counter_drift.py" --self-test

run "setup-counter drift" "python scripts/check_skill_setup_counter_drift.py --verbose --ci" \
  python "$spine_root/scripts/check_skill_setup_counter_drift.py" --verbose --ci

run "eval-packaging self-test" "python scripts/check_skill_eval_packaging.py --self-test --ci" \
  python "$spine_root/scripts/check_skill_eval_packaging.py" --self-test --ci

run "skill eval packaging" "python scripts/check_skill_eval_packaging.py --verbose --ci" \
  python "$spine_root/scripts/check_skill_eval_packaging.py" --verbose --ci

run "Xray tab-inventory self-test" "python scripts/check_skill_xray_tab_inventory_drift.py --self-test" \
  python "$spine_root/scripts/check_skill_xray_tab_inventory_drift.py" --self-test

run "Xray tab-inventory drift" "python scripts/check_skill_xray_tab_inventory_drift.py --verbose --ci" \
  python "$spine_root/scripts/check_skill_xray_tab_inventory_drift.py" --verbose --ci

run "skill redirect anchors" "python scripts/check_skill_redirect_anchors.py" \
  python "$spine_root/scripts/check_skill_redirect_anchors.py"

run "keyword-catalogue drift self-test" "python scripts/check_keyword_catalogue_drift.py --self-test --verbose" \
  python "$spine_root/scripts/check_keyword_catalogue_drift.py" --self-test --verbose

run "keyword-catalogue drift" "python scripts/check_keyword_catalogue_drift.py --verbose" \
  python "$spine_root/scripts/check_keyword_catalogue_drift.py" --verbose

run "CI reproduce-commands self-test" "python scripts/check_ci_reproduce_commands.py --self-test --verbose" \
  python "$spine_root/scripts/check_ci_reproduce_commands.py" --self-test --verbose

run "CI reproduce-commands" "python scripts/check_ci_reproduce_commands.py --check --verbose" \
  python "$spine_root/scripts/check_ci_reproduce_commands.py" --check --verbose

run "gate-scheduling audit self-test" "python scripts/check_gate_scheduling.py --self-test --verbose" \
  python "$spine_root/scripts/check_gate_scheduling.py" --self-test --verbose

run "gate-scheduling audit (rf2-6ckzl)" "python scripts/check_gate_scheduling.py --verbose" \
  python "$spine_root/scripts/check_gate_scheduling.py" --verbose

# The last three are `verify-readme-links`', not the invariant job's.  Note that
# CI runs `check_readme_links --ci` ALWAYS-ON while this spine runs it in the
# documentation tier; only the self-test arm is added here, so the live scan
# keeps its existing tier and nothing gets slower for a code-only diff.
run "README link validator self-test" "python scripts/check_readme_links.py --self-test --verbose" \
  python "$spine_root/scripts/check_readme_links.py" --self-test --verbose

run "README inventory ratchet self-test" "python scripts/check_readme_inventories.py --self-test --verbose" \
  python "$spine_root/scripts/check_readme_inventories.py" --self-test --verbose

run "adapter-disposition self-test" "python scripts/check_adapter_disposition.py --self-test --verbose" \
  python "$spine_root/scripts/check_adapter_disposition.py" --self-test --verbose

run "adapter disposition" "python scripts/check_adapter_disposition.py --verbose --ci" \
  python "$spine_root/scripts/check_adapter_disposition.py" --verbose --ci

# The lockstep gate's OWN self-test (rf2-ejm7m).  The live scan above is the
# spine's oldest always-on gate; its self-test arm — the thing that proves the
# lockstep checkers still fire — is a separate required step of the same job and
# had no lane.  ~1.7s.  Same CRLF-stripping dance as the live invocation: this
# checkout is Windows, and the script is `bash`, not `python`.
run "lockstep gate self-test" "./.github/scripts/verify-version-lockstep.sh --self-test" \
  bash -lc "tmp='$spine_root/.github/scripts/.verify-version-lockstep.selftest.tmp.'\$\$; trap 'rm -f \"\$tmp\"' EXIT; tr -d '\r' < '$spine_root/.github/scripts/verify-version-lockstep.sh' > \"\$tmp\"; bash \"\$tmp\" --self-test"

# ---------------------------------------------------------------------------
# The spine's OWN self-test — armed only when the spine's own tree changed
# (rf2-fhdd3).  Every other gate above pairs a self-test with a live scan; the
# runner had neither.  `run-self-test.sh` drives this script in `--plan` mode
# against disposable fixture repos and asserts the tier decision and the mkdocs
# resolution, so it is the only thing that catches a reworked classifier that
# routes wrongly — including one that disarms the documentation tier again.
#
# No recursion: the self-test invokes `--plan`, which exits above this line.
#
# NOT added to `skipped` when it does not run.  The other entries name coverage
# a reader might otherwise assume — surfaces this diff could plausibly have
# reached.  This one cannot: if the spine did not change, its self-test has
# nothing to say, and listing it on every PASS would pad the honest-gap tally
# with a vacuous line.  The skip is printed to the log instead.
# ---------------------------------------------------------------------------
spine_self_test="$spine_root/scripts/_test_fixtures/test_fast_pr_docs_gate/run-self-test.sh"
if [ "$spine_self_surface" = true ]; then
  printf '\n--- the spine itself changed → running its own tiering self-test ---\n'
  run "fast-PR spine tiering self-test" \
    "bash scripts/_test_fixtures/test_fast_pr_docs_gate/run-self-test.sh" \
    bash "$spine_self_test"
else
  printf '\n--- spine unchanged → skipping its own tiering self-test ---\n'
fi

# ---------------------------------------------------------------------------
# Documentation gates (run when a documentation surface changes).
#
# The local mirror of CI's verify-readme-links (always-on) + docs.yml
# (MkDocs / link / status-sync / grading) — validators of documentation
# CONTENT.  Skipped on a code-only diff; forced on/off with --with-docs /
# --no-docs.
# ---------------------------------------------------------------------------
if [ "$run_docs" = true ]; then
  printf '\n--- documentation surface changed → running link/anchor/status gates ---\n'

  run "README link/anchor validator" "python scripts/check_readme_links.py --ci" \
    python "$spine_root/scripts/check_readme_links.py" --ci

  run "docs corpus anchor self-test" "python scripts/check_doc_slugs.py --self-test --verbose" \
    python "$spine_root/scripts/check_doc_slugs.py" --self-test --verbose

  run "docs corpus anchor validator" "python scripts/check_doc_slugs.py" \
    python "$spine_root/scripts/check_doc_slugs.py"

  # Provenance pins (rf2-kqac1).  This repo rebase-merges, so a Hicasso page
  # that pins a measurement to its own authored SHA is stranded the moment its
  # PR lands — the object is reachable from no ref and absent from a fresh
  # clone.  The rule is accompaniment: a cited authored head must share its
  # block with a SHA that IS an ancestor of origin/main.
  run "provenance pin self-test" \
    "python scripts/check_provenance_pins.py --self-test --verbose" \
    python "$spine_root/scripts/check_provenance_pins.py" --self-test --verbose

  # The BLOCKING arm is scoped to pages this branch touches.  The corpus still
  # carries the stranded pins rf2-owq6p catalogued and deliberately left for a
  # human to judge one at a time, so a full-corpus gate would be red on main
  # from the day it landed and disabled the week after.  Scoped, it holds every
  # new and edited page to the rule immediately.  It needs origin/main to say
  # what "landed" means, so it is skipped — announced, not silently — when the
  # base is unresolved.
  if [ "$base_resolved" = true ]; then
    run "provenance pins on changed pages" \
      "python scripts/check_provenance_pins.py --changed-since origin/main --verbose" \
      python "$spine_root/scripts/check_provenance_pins.py" \
        --changed-since origin/main --verbose
  else
    printf 'SKIP provenance pins on changed pages (origin/main unresolved — no baseline for "landed")\n'
  fi

  # CI's docs-tier residue sweeps (rf2-ejm7m).  These four live in docs.yml's
  # `build` job, which is gated on `docs_surface` — so the documentation tier is
  # where they belong locally, and a code-only diff pays nothing for them.
  #
  # MEASURED: eight of the nine invocations cost 1.93s together, against a docs
  # tier already ~28s (mkdocs --strict dominates).  The NINTH is excluded and
  # named below.
  #
  # They are not subject to the trap PR #7496 found in the mkdocs build — that
  # gate reads `docs/`, and docs.yml stages `cp -r spec docs/spec` first, so a
  # bare local run exits 0 having read nothing of a spec edit.  Each of these
  # scans the TRACKED `spec/` tree and explicitly excludes the staged
  # `docs/spec/` mirror (`_EXCLUDE_REL_PREFIXES`), so running them here reads
  # exactly what CI reads, with no staging step to forget.
  run "inject-cofx residue self-test" "python scripts/check_inject_cofx_residue.py --self-test --verbose" \
    python "$spine_root/scripts/check_inject_cofx_residue.py" --self-test --verbose

  run "inject-cofx residue" "python scripts/check_inject_cofx_residue.py --verbose" \
    python "$spine_root/scripts/check_inject_cofx_residue.py" --verbose

  run "failure-corpus residue self-test" "python scripts/check_failure_corpus_residue.py --self-test --verbose" \
    python "$spine_root/scripts/check_failure_corpus_residue.py" --self-test --verbose

  run "failure-corpus residue" "python scripts/check_failure_corpus_residue.py --verbose" \
    python "$spine_root/scripts/check_failure_corpus_residue.py" --verbose

  run "retired composition-vocab self-test" "python scripts/check_retired_composition_vocab.py --self-test --verbose" \
    python "$spine_root/scripts/check_retired_composition_vocab.py" --self-test --verbose

  run "retired composition vocab" "python scripts/check_retired_composition_vocab.py --verbose" \
    python "$spine_root/scripts/check_retired_composition_vocab.py" --verbose

  # THE ONE THAT WAS NOT CHEAP — and now is (rf2-ejm7m measured it, rf2-e1xx0
  # fixed it).
  #
  # `check_retired_image_keys.py --verbose` was 37.7s cold / ~42s warm: 95% of
  # that job's whole checker batch, more than this entire documentation tier, and
  # the ONE required checker rf2-ejm7m deliberately left without a local lane.  A
  # token pre-filter in front of the regex battery, a one-sweep string mask, and a
  # pruned directory walk took it to ~0.9s on the same checkout (0.85s on one with
  # `node_modules` populated) with byte-identical findings, so it has a lane here
  # now and the `note_skipped` beside it is gone.
  #
  # Both arms run, and they answer different questions.  The live scan is the
  # gate; the SELF-TEST is what proves the gate still has teeth — it plants one
  # retired token per fixture and asserts the EXACT kind set, so a detector that
  # dies reds BY NAME rather than being covered for by a sibling finding.  A green
  # live scan over a corpus that contains no retired spellings cannot tell you the
  # difference between "clean" and "blind".
  run "retired image-keys self-test" "python scripts/check_retired_image_keys.py --self-test --verbose" \
    python "$spine_root/scripts/check_retired_image_keys.py" --self-test --verbose

  run "retired image keys" "python scripts/check_retired_image_keys.py --verbose" \
    python "$spine_root/scripts/check_retired_image_keys.py" --verbose

  # EP index status-sync guard (rf2-8cw3m7): docs/EP/README.md restates each
  # EP's Status: line in its index table; the two drift by hand (EP-0001 sat at
  # `accepted` while the index still said `proposal`).  Self-test first (proves
  # the guard fires on mismatch / missing-row / orphan-row), then the live scan.
  run "EP status-sync self-test" "python scripts/check_ep_status_sync.py --self-test" \
    python "$spine_root/scripts/check_ep_status_sync.py" --self-test

  run "EP status-sync" "python scripts/check_ep_status_sync.py" \
    python "$spine_root/scripts/check_ep_status_sync.py"

  # Runtime-subsystem grading drift guard (rf2-ba5acq, EP-0006): every reserved
  # `:rf.runtime/*` key (spec/Conventions.md §Reserved runtime-db keys) MUST
  # have a complete five-clause grading subsection in spec/Runtime-Subsystems.md
  # §Grading table.  The two surfaces drift by hand (PR #3817 had to hand-add
  # the `:rf.runtime/mutations` row after it was reserved without a grading
  # subsection).  Self-test first (proves the guard fires on missing-row /
  # extra-row / missing-clause / ungraded-clause / clause-order), then the live
  # scan.  See scripts/check_runtime_subsystem_grading.py.
  run "runtime-subsystem grading self-test" "python scripts/check_runtime_subsystem_grading.py --self-test" \
    python "$spine_root/scripts/check_runtime_subsystem_grading.py" --self-test

  run "runtime-subsystem grading drift" "python scripts/check_runtime_subsystem_grading.py" \
    python "$spine_root/scripts/check_runtime_subsystem_grading.py"

  # The strict site build.  A HARD gate wherever mkdocs resolves at all; where
  # it does not, the step says what it did not check and — measured, not
  # assumed — which surfaces that leaves with no local gate (rf2-g7p7l).
  #
  # What ONLY this build covers locally: `mkdocs.yml` itself (nav integrity,
  # `exclude_docs`, the theme and markdown-extension pipeline), the
  # `mkdocs_hooks.py` rewrites, `requirements.txt`, and link resolution in the
  # STAGED tree — the build copies `spec/` and `migration/` under `docs/` and
  # rewrites their cross-references, so a link correct in the source tree can
  # still be broken in the site.  No other spine gate reads any of these.
  #
  # What it does NOT uniquely cover, so the skip must not claim it does: link
  # targets and heading anchors across `docs/` (including all of `docs/EP/**`),
  # `spec/`, `skills/`, `migration/` and `tools/*/spec/`.  `check_doc_slugs.py`
  # above scans those roots and is the STRONGER gate there — mkdocs 1.6 grades
  # a missing anchor INFO, so `--strict` does not fail on one.
  if resolve_mkdocs; then
    run "mkdocs --strict build" "$mkdocs_cmd build --strict" \
      bash -c "cd '$spine_root' && $mkdocs_cmd build --strict"
  else
    printf '    NOT CHECKED: mkdocs --strict build — mkdocs resolves neither as a\n'
    printf '      console script nor as `python -m mkdocs` (tried: mkdocs, python,\n'
    printf '      python3, py).  This is NOT a pass.  Left with NO local gate:\n'
    printf '      mkdocs.yml (nav, exclude_docs, theme, markdown extensions),\n'
    printf '      mkdocs_hooks.py, requirements.txt, and link resolution in the\n'
    printf '      staged docs/spec + docs/migration copies the build creates.\n'
    printf '      Install it (pip install -r requirements.txt) and re-run, or\n'
    printf '      rely on CI docs.yml — which does run it, and can fail there.\n'
    note_skipped "mkdocs --strict build — UNRESOLVED, so mkdocs.yml/mkdocs_hooks.py/requirements.txt and staged-tree link resolution had NO local gate this run"
  fi
else
  printf '\n--- documentation surface unchanged → skipping doc gates (override with --with-docs) ---\n'
  note_skipped "documentation gates (surface unchanged; --with-docs forces)"
fi

# ---------------------------------------------------------------------------
# clj-kondo, AT THE VERSION CI PINS, OVER THE PATHS CI LINTS (rf2-x1mz).
#
# Before this lane the repo had no local gate that could catch a source error
# lint.yml fails on, for two independent reasons.  The only local lane that ran
# clj-kondo at all was the Hicasso fixture witness below, and it (a) took
# whatever binary was on PATH — 2025.10.23 here, which reports `errors: 0` on
# the exact line CI fails at 2026.04.15 — and (b) lints two fixture files and
# `hicasso/testbed`, never `hicasso/src/`.  An `(aset f "displayName" …)` pair
# went out green locally and red in CI, and no amount of care locally could
# have found it.
#
# It runs BEFORE the JVM and node tiers because it is the cheapest of the three
# and the one whose red is most often a one-line fix: ~70s warm over the whole
# corpus, against minutes for either tier below.
#
# `lint_kondo.py` provisions the pinned binary once per machine and runs the
# workflow's OWN command; nothing about the gate is restated in this file.  If
# the pin cannot be provisioned it exits 2 WITHOUT LINTING, and that becomes a
# loud skip rather than a pass — a green from another version is the false
# assurance this lane exists to remove, not a fallback.
# ---------------------------------------------------------------------------
if [ "$run_kondo" = true ]; then
  kondo_status=0
  python "$spine_root/scripts/lint_kondo.py" --print-binary >/dev/null 2>&1 \
    || kondo_status=$?
  if [ "$kondo_status" -eq 2 ]; then
    printf '\n    NOT CHECKED: clj-kondo — the version lint.yml pins could not be\n'
    printf '      provisioned on this host (no network, or no published build for\n'
    printf '      this platform).  This is NOT a pass: a differently-versioned\n'
    printf '      binary disagrees about which findings are errors, so running one\n'
    printf '      would report a verdict it has not earned.  Left with NO local\n'
    printf '      gate: every .clj/.cljs/.cljc file under the roots lint.yml lists.\n'
    note_skipped "clj-kondo (pinned) — the pinned binary could not be provisioned, so no local lane linted implementation/, examples/, testbeds/ or tools/ this run"
  else
    run "clj-kondo (pinned, CI's own command)" "python scripts/lint_kondo.py" \
      bash -lc "cd '$spine_root' && python scripts/lint_kondo.py"
  fi
else
  printf '\n--- no clj-kondo lint surface in the diff → skipping the lint lane ---\n'
  note_skipped "clj-kondo (pinned) — no file under a --lint root, .clj-kondo/ or lint.yml changed"
fi

# ---------------------------------------------------------------------------
# JVM artefact suites — gated on implementation_jvm (test.yml's per-artefact
# jobs).  Contents: core, plus every artefact the diff touched (rf2-uwszd).
# ---------------------------------------------------------------------------
if [ "$run_jvm" = true ]; then
  for artefact in $jvm_run_list; do
    run "JVM $artefact" "cd $artefact && clojure -M:test" \
      bash -lc "cd '$spine_root/$artefact' && clojure -M:test"
  done
  # Named even on the path that RUNS the tier: `implementation_jvm` arms every
  # per-artefact CI job at once and this spine ran a subset.  A worker whose
  # diff reached an artefact indirectly — through core, through a shared
  # fixture — must see that the artefact's own suite was not run here.
  if [ -n "$jvm_skipped_list" ]; then
    note_skipped "JVM artefact suites the diff did not touch:${jvm_skipped_list} (scripts/test-jvm-implementation.sh runs all)"
  fi
else
  printf '\n--- implementation_jvm surface unchanged → skipping JVM artefact suites (override with --all) ---\n'
  note_skipped "all JVM artefact suites (implementation_jvm surface unchanged; --all forces)"
fi

# ---------------------------------------------------------------------------
# npm / CLJS / JS-harness / per-namespace isolation — gated on cljs_node_test
# (test.yml's cljs job + the always-on js-harness-self-tests job).  CI keeps
# the JS harness self-tests always-on as its safety net; locally we group them
# with the node tier so a documentation- or code-elsewhere diff does not pay to
# spin up node/npm, per the rf2-r6x1t DESIGN (JS suites run only when their
# owning scripts / build config change — exactly the cljs_node_test surface).
# ---------------------------------------------------------------------------
if [ "$run_node" = true ]; then
  run "implementation JS harness helpers" "cd implementation && npm run test:script-policy && npm run test:script-helpers" \
    bash -lc "cd '$spine_root/implementation' && npm run test:script-policy && npm run test:script-helpers"

  run "CLJS node integration" "cd implementation && npm run test:cljs" \
    bash -lc "cd '$spine_root/implementation' && npm run test:cljs"

  # Per-namespace test-isolation gate (rf2-32siq3.44).  The consolidated
  # node-test bundle shares ONE runtime, so a test ns whose fixture forgets to
  # install its own substrate adapter is masked: it passes whenever a sibling
  # left an adapter installed (suite ORDERING hides a self-incomplete fixture).
  # This gate re-runs the curated EP-0023 image/frame runtime-construction
  # namespaces EACH ALONE; a fixture leaning on bundle pollution goes red
  # standalone.  Self-test first (proves the red/green classification), then the
  # live run (reuses the bundle test:cljs just compiled).
  run "per-ns isolation self-test" "cd implementation && node scripts/check-per-ns-isolation.cjs --self-test" \
    bash -lc "cd '$spine_root/implementation' && node scripts/check-per-ns-isolation.cjs --self-test"

  run "per-ns test isolation" "cd implementation && node scripts/check-per-ns-isolation.cjs" \
    bash -lc "cd '$spine_root/implementation' && node scripts/check-per-ns-isolation.cjs"

  # Hicasso invariants gate (rf2-8a6s).  implementation/hicasso/ IS the measured
  # prototype, moved — `frozen-sources.edn` pins every donor file in the bench
  # tree by digest (FROZEN), the gate RECONSTRUCTS each package file from its
  # donor and requires the file on disk to equal it (MOVED), and no package
  # file may import a benchmark-tree namespace (SEALED).  All three stop
  # holding SILENTLY, and until rf2-8a6s the gate ran only by hand.  MOVED
  # arrived later still: until rf2-hic-001's reopen the gate hashed the DONORS
  # and merely checked that each package path EXISTED, so a package body could
  # diverge arbitrarily and stay green while this spine advertised the gate as
  # proof that it had not.  Sub-second, pure Python stdlib.  It runs in the
  # `cljs` job in CI for the same reason it sits in this tier: its two input
  # surfaces — implementation/hicasso/** and the freehand bench tree it was
  # copied from — both arm `cljs_node_test`.
  #
  # FREEZE IS ONE OF THREE (rf2-ibje, hence the name).  The same npm script also
  # runs the optional-module reachability check and the complaint-catalogue
  # round trip — sibling static reads of the same artefact, each with its own
  # `--self-test`, sharing this lane because they share its input surface.
  run "hicasso invariants gate" "cd implementation && npm run test:hicasso-invariants" \
    bash -lc "cd '$spine_root/implementation' && npm run test:hicasso-invariants"

  # Hicasso lint export gate (rf2-hic-022).  The artefact publishes six
  # clj-kondo checks from `resources/clj-kondo.exports/`, and this is their
  # witness: each check must fire at its exact rows in `lint-fixtures/
  # positive.cljs`, `negative.cljs` must produce NO finding of ANY kind, and
  # the artefact's own testbeds must stay quiet.  It runs the real clj-kondo
  # at the version lint.yml pins, with the SHIPPED export as its --config-dir,
  # so a rule that passes here is the rule a consumer gets.
  #
  # THIS IS THE ONLY LANE IT HAS.  A `deftest` witness would run in no lane at
  # all: a JVM lane exists only through the artefact rosters
  # (`check_test_lane_bijection.py`) and a roster entry is only legal with a
  # matching `test.yml` job (`check_jvm_lane_rosters.py`), which is hot-zone.
  # So the export is gated in CI by lint.yml's required `clj-kondo` job (the
  # repo's `:config-paths` points at it), and the FIXTURES are gated here.
  # Pairing a `jvm-hicasso` job with a roster entry would let the witness
  # follow; until then, this line is what makes it run.
  run "hicasso lint export gate" "cd implementation && npm run test:hicasso-lint" \
    bash -lc "cd '$spine_root/implementation' && npm run test:hicasso-lint"

  # Hicasso bench-lane compile coverage (rf2-2rtt6.73).  NO PR gate compiled
  # this lane: `:node-test` selects `cljs-test$` and `:browser-test` selects
  # `-dom-cljs-test$`, and nothing test-shaped requires the arms, so
  # out/node-test.js carried zero occurrences of `walk_profile_app` and
  # out/browser-test/ had no such module.  The only compiler that ever saw an
  # arm was `:hicasso-bench`, driven BY HAND — a broken arm could not go red,
  # and a worker mutation-proving a change through the lane proved nothing.
  # The arms are deliberately LOCAL COPIES of shipping code (the rf2-2rtt6.32
  # call-convention discipline), so they drift by construction and a compile
  # is the cheapest thing that notices.  ~45s: one dev-mode `shadow-cljs
  # compile` of all 100 lane namespaces, warnings treated as failures.
  run "hicasso bench-lane compile" "cd implementation && npm run test:hicasso-compile" \
    bash -lc "cd '$spine_root/implementation' && npm run test:hicasso-compile"
else
  printf '\n--- cljs_node_test surface unchanged → skipping npm/CLJS/isolation (override with --all) ---\n'
  note_skipped "npm/CLJS/isolation (cljs_node_test surface unchanged; --all forces)"
fi

# The spine's browser/bundle/adapter/tooling gap is unconditional — no tier of
# this script runs those — so it is named on every PASS, not just when a tier
# was skipped by classification.
note_skipped "browser lanes, prod-elision/bundle-isolation/perf gates, adapter probes + smokes, Xray feature matrix, mcp-conformance, tool JVM suites (CI only; see TESTING.md)"

# ---------------------------------------------------------------------------
# Honest exit line: PASS names what was NOT run (rf2-dgzaf).
# ---------------------------------------------------------------------------
printf 'PASS fast PR spine\n'
if [ "${#skipped[@]}" -gt 0 ]; then
  printf 'NOT RUN by this spine (%d):\n' "${#skipped[@]}"
  for item in "${skipped[@]}"; do
    printf '  - %s\n' "$item"
  done
fi

# ...and, NAMED rather than described, the required checks no tier of this spine
# runs at any classification (rf2-13zre).  The list above is about what THIS RUN
# skipped; this one is about what this script never runs at all — including the
# steps buried inside jobs it does run, which is the class that ambushed three
# workers in a day.  Derived at print time from the workflows, so it cannot go
# stale; `--list` swaps the digest for a local command per check.
python "$spine_root/scripts/check_fast_pr_gap.py" --brief || true
