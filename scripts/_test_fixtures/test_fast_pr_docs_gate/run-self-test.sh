#!/usr/bin/env bash
# Self-test for the changed-surface tiering in scripts/test-fast-pr.sh
# (rf2-r6x1t, extends rf2-lwweq).
#
# It drives the REAL spine in `--plan` mode against disposable git repos —
# NOT a replicated copy of the detection logic (the previous harness copied
# the markdown-diff block inline, which could silently drift from the spine).
# `--plan` classifies the change set and prints one machine-readable line —
#   PLAN docs=<bool> jvm=<bool> node=<bool>
# — then exits without running any gate, so the assertions are fast and
# deterministic.  `--repo-root DIR` points the change-set gathering at a
# disposable fixture repo while the real gate scripts stay put.
#
# Cases:
#   committed docs / staged code / unstaged docs / untracked docs  — the four
#     git states the change-set gathering must handle deterministically;
#   unknown surface                — conservative fallback runs the full runtime;
#   no changes                     — static checks only;
#   no origin/main base            — conservative fallback (indeterminate);
#   --all / RF2_FAST_PR_ALL / --with-docs / --no-docs — the overrides;
#   gate-has-teeth (E/F)           — the doc validators exit non-zero on the
#     bundled broken fixtures;
#   motivating misses (#2232/#2233) — the pymdownx slug shapes that first
#     motivated the doc gate still trip check_doc_slugs.py;
#   coverage-honesty note (Q/R)    — `--plan` must state what the JVM tier
#     actually contains and point at the full JVM sweep (rf2-dgzaf);
#   per-artefact JVM selection (S-V) — a diff under an artefact's tree adds
#     that artefact's suite, a diff elsewhere does not pay for it, a roster
#     entry matches on a path boundary, and a skipped tier selects nothing
#     (rf2-uwszd);
#   mkdocs resolution (W-Y)        — the console script is preferred, an
#     installed-as-a-module mkdocs is still FOUND rather than soft-skipped, and
#     a code-only diff never probes for it (rf2-g7p7l);
#   the spine's own tree (Z-AB)    — a diff touching `scripts/test-fast-pr.sh`
#     or this fixture tree arms the documentation tier and this self-test, and
#     an ordinary `scripts/` change still does not (rf2-fhdd3);
#   hermetic mkdocs (AC-AE)        — a constructed module-only PATH selects
#     `python -m mkdocs` exactly, a console script still wins over a working
#     module launcher, and a host where nothing resolves reports `unresolved`
#     rather than anything that reads as a pass (rf2-03298).
#
# CI wiring: test.yml's always-on `verify-readme-links` job runs this file, so
# breaking the module fallback reddens a REQUIRED check rather than only a local
# run somebody may not have made (rf2-03298).
#
# Run from any cwd:
#   bash scripts/_test_fixtures/test_fast_pr_docs_gate/run-self-test.sh
#
# Exit code:
#   0  all assertions hold
#   1  at least one assertion failed
#   2  setup error

set -u

fixture_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd "$fixture_dir/../../.." && pwd)"
spine="$repo_root/scripts/test-fast-pr.sh"

if [ ! -f "$spine" ]; then
  printf 'setup error: spine script not found at %s\n' "$spine" >&2
  exit 2
fi

fail_count=0
pass_count=0

assert() {
  local label="$1"
  local expected="$2"
  local actual="$3"
  if [ "$expected" = "$actual" ]; then
    printf '  PASS %s\n' "$label"
    pass_count=$((pass_count + 1))
  else
    printf '  FAIL %s: expected %q, got %q\n' "$label" "$expected" "$actual"
    fail_count=$((fail_count + 1))
  fi
}

tmp_root="$(mktemp -d 2>/dev/null || mktemp -d -t 'test-fast-pr-detect')"
trap 'rm -rf "$tmp_root"' EXIT

# The spine's PLAN line for a disposable repo (extra args pass through to it).
plan() {
  local root="$1"; shift
  bash "$spine" --plan --repo-root "$root" "$@" 2>/dev/null | grep '^PLAN ' || printf 'PLAN <none>\n'
}

# Disposable repo with origin/main pinned at an initial commit, so the
# committed-branch diff (origin/main...HEAD) and the working-tree/untracked
# signals are all exercisable.
mkrepo() {
  local r="$1"
  mkdir -p "$r"
  git -C "$r" init -q -b main 2>/dev/null
  git -C "$r" config user.email "self-test@local"
  git -C "$r" config user.name "self-test"
  # Silence CRLF auto-conversion warnings on Windows Git Bash.
  git -C "$r" config core.autocrlf false
  git -C "$r" config core.safecrlf false
  mkdir -p "$r/seed"
  printf 'x\n' > "$r/seed/f.txt"
  git -C "$r" add seed/f.txt
  git -C "$r" commit -q -m 'init'
  git -C "$r" update-ref refs/remotes/origin/main HEAD
}

# ---- Case A: committed docs-only diff vs origin/main → docs only ----
r="$tmp_root/committed-docs"; mkrepo "$r"
mkdir -p "$r/docs/core"; printf '# h\n' > "$r/docs/core/x.md"
git -C "$r" add docs/core/x.md; git -C "$r" commit -q -m docs
assert "A committed docs-only → docs only" "PLAN docs=true jvm=false node=false" "$(plan "$r")"

# ---- Case B: staged code diff → runtime suites ----
r="$tmp_root/staged-code"; mkrepo "$r"
mkdir -p "$r/implementation/core/src/re_frame"
printf 'x\n' > "$r/implementation/core/src/re_frame/core.cljc"
git -C "$r" add implementation/core/src/re_frame/core.cljc   # staged, not committed
assert "B staged core code → runtime" "PLAN docs=false jvm=true node=true" "$(plan "$r")"

# ---- Case C: unstaged docs change (tracked, modified, not staged) → docs ----
r="$tmp_root/unstaged-docs"; mkrepo "$r"
mkdir -p "$r/spec"; printf '# a\n' > "$r/spec/006.md"
git -C "$r" add spec/006.md; git -C "$r" commit -q -m addmd
git -C "$r" update-ref refs/remotes/origin/main HEAD
printf '# a\nmore\n' > "$r/spec/006.md"                       # unstaged modify
assert "C unstaged docs → docs only" "PLAN docs=true jvm=false node=false" "$(plan "$r")"

# ---- Case D: untracked docs file → docs ----
r="$tmp_root/untracked-docs"; mkrepo "$r"
printf '# u\n' > "$r/NOTES.md"                                # untracked, never added
assert "D untracked docs → docs only" "PLAN docs=true jvm=false node=false" "$(plan "$r")"

# ---- Case E: unknown surface → conservative full runtime ----
r="$tmp_root/unknown"; mkrepo "$r"
printf 'x\n' > "$r/weird.xyz"; git -C "$r" add weird.xyz
assert "E unknown surface → conservative runtime" "PLAN docs=false jvm=true node=true" "$(plan "$r")"

# ---- Case F: no changes vs origin/main, clean tree → static only ----
r="$tmp_root/clean"; mkrepo "$r"
assert "F no changes → static only" "PLAN docs=false jvm=false node=false" "$(plan "$r")"

# ---- Case G: no origin/main base → conservative fallback ----
r="$tmp_root/nobase"; mkdir -p "$r"
git -C "$r" init -q -b main 2>/dev/null
git -C "$r" config user.email "self-test@local"; git -C "$r" config user.name "self-test"
git -C "$r" config core.autocrlf false; git -C "$r" config core.safecrlf false
printf 'x\n' > "$r/f.txt"; git -C "$r" add -A; git -C "$r" commit -q -m init
assert "G no origin/main → conservative runtime" "PLAN docs=false jvm=true node=true" "$(plan "$r")"

# ---- Case H: mixed docs + code → both tiers ----
r="$tmp_root/mixed"; mkrepo "$r"
mkdir -p "$r/docs" "$r/implementation/core/src/re_frame"
printf '# h\n' > "$r/docs/x.md"
printf 'x\n' > "$r/implementation/core/src/re_frame/core.cljc"
git -C "$r" add -A; git -C "$r" commit -q -m mix
assert "H mixed docs+code → both tiers" "PLAN docs=true jvm=true node=true" "$(plan "$r")"

# ---- Case I: --all override runs the complete spine on a docs-only diff ----
r="$tmp_root/override-all"; mkrepo "$r"
mkdir -p "$r/docs"; printf '# h\n' > "$r/docs/x.md"; git -C "$r" add -A; git -C "$r" commit -q -m d
assert "I --all override → everything" "PLAN docs=true jvm=true node=true" "$(plan "$r" --all)"

# ---- Case J: RF2_FAST_PR_ALL=1 override runs the complete spine ----
assert "J RF2_FAST_PR_ALL=1 override → everything" "PLAN docs=true jvm=true node=true" \
  "$(RF2_FAST_PR_ALL=1 bash "$spine" --plan --repo-root "$r" 2>/dev/null | grep '^PLAN ')"

# ---- Case K: --with-docs forces docs on for a code-only diff ----
r="$tmp_root/with-docs"; mkrepo "$r"
mkdir -p "$r/implementation/core/src/re_frame"
printf 'x\n' > "$r/implementation/core/src/re_frame/core.cljc"; git -C "$r" add -A; git -C "$r" commit -q -m c
assert "K --with-docs on code diff → docs forced on" "PLAN docs=true jvm=true node=true" "$(plan "$r" --with-docs)"

# ---- Case L: --no-docs forces docs off for a docs diff ----
r="$tmp_root/no-docs"; mkrepo "$r"
mkdir -p "$r/docs"; printf '# h\n' > "$r/docs/x.md"; git -C "$r" add -A; git -C "$r" commit -q -m d
assert "L --no-docs on docs diff → docs forced off" "PLAN docs=false jvm=false node=false" "$(plan "$r" --no-docs)"

# ---------------------------------------------------------------------------
# Gate-has-teeth tests.  When the tiering decides "run the doc gates", the
# validators must actually catch the motivating regressions.
# ---------------------------------------------------------------------------
slugs_script="$repo_root/scripts/check_doc_slugs.py"
readme_script="$repo_root/scripts/check_readme_links.py"

broken_anchor_fixture="$repo_root/scripts/_test_fixtures/check_doc_slugs/broken_anchor"
if [ -d "$broken_anchor_fixture" ]; then
  python "$slugs_script" --repo-root "$broken_anchor_fixture" >/dev/null 2>&1
  assert "M check_doc_slugs catches broken anchor" "1" "$?"
else
  printf '  SKIP M: fixture %s not found\n' "$broken_anchor_fixture"
fi

broken_readme_fixture="$repo_root/scripts/_test_fixtures/check_readme_links/broken_internal_link"
if [ -d "$broken_readme_fixture" ]; then
  python "$readme_script" --repo-root "$broken_readme_fixture" --ci >/dev/null 2>&1
  assert "N check_readme_links catches broken target" "1" "$?"
else
  printf '  SKIP N: fixture %s not found\n' "$broken_readme_fixture"
fi

# ---------------------------------------------------------------------------
# Motivating-miss verification.  Reproduce #2232 (pymdownx `_1` disambiguation
# vs GitHub `-1`) and #2233 (anchor missing the `-rf2-XXX` heading suffix); both
# must trip check_doc_slugs.py.
# ---------------------------------------------------------------------------
case_2232="$tmp_root/case-2232"; mkdir -p "$case_2232/docs"
cat > "$case_2232/mkdocs.yml" <<'EOF'
site_name: test
EOF
cat > "$case_2232/docs/index.md" <<'EOF'
# Index

See [trace events](target.md#trace-events-1) for the second occurrence.
EOF
cat > "$case_2232/docs/target.md" <<'EOF'
# Target

## Trace events

First occurrence — slug is `trace-events`.

## Trace events

Second occurrence — pymdownx slug is `trace-events_1` (underscore N).
EOF
python "$slugs_script" --repo-root "$case_2232" >/dev/null 2>&1
assert "O #2232 (trace-events-1 vs trace-events_1)" "1" "$?"

case_2233="$tmp_root/case-2233"; mkdir -p "$case_2233/docs"
cat > "$case_2233/mkdocs.yml" <<'EOF'
site_name: test
EOF
cat > "$case_2233/docs/index.md" <<'EOF'
# Index

See [section](target.md#cljs-reference-helix-as-alternative-substrate) for details.
EOF
cat > "$case_2233/docs/target.md" <<'EOF'
# Target

## CLJS reference: Helix as alternative substrate (rf2-2qit)

Body — heading slug is `cljs-reference-helix-as-alternative-substrate-rf2-2qit`,
NOT `cljs-reference-helix-as-alternative-substrate`.
EOF
python "$slugs_script" --repo-root "$case_2233" >/dev/null 2>&1
assert "P #2233 (anchor missing -rf2-XXX suffix)" "1" "$?"

# ---------------------------------------------------------------------------
# Coverage-honesty note (rf2-dgzaf, rf2-uwszd).  The spine's JVM tier is a
# SUBSET of the per-artefact suites `implementation_jvm` arms in CI, so `--plan`
# must SAY which artefacts it selected — a tier line reading `JVM tier: run` is
# otherwise read as a coverage claim.  Pinned because prose that nobody checks
# is exactly how the header came to overstate what the spine ran.
# ---------------------------------------------------------------------------
r="$tmp_root/coverage-note"; mkrepo "$r"
mkdir -p "$r/implementation/core/src/re_frame"
printf 'x\n' > "$r/implementation/core/src/re_frame/core.cljc"
git -C "$r" add -A; git -C "$r" commit -q -m c
plan_out="$(bash "$spine" --plan --repo-root "$r" 2>/dev/null)"
case "$plan_out" in
  *"implementation/core PLUS the artefacts the diff touched"*) note_scope=yes ;;
  *)                                                           note_scope=no ;;
esac
assert "Q --plan names the JVM tier as core plus what changed" "yes" "$note_scope"
case "$plan_out" in
  *"test-jvm-implementation.sh"*) note_points_at_full=yes ;;
  *)                              note_points_at_full=no ;;
esac
assert "R --plan points at the full JVM sweep" "yes" "$note_points_at_full"

# ---------------------------------------------------------------------------
# Per-artefact JVM selection (rf2-uwszd).  `PLAN-JVM` is the machine-readable
# list of artefact suites the run will execute.  The pins are both directions:
# a diff under an artefact's tree must ADD that suite, and a diff elsewhere
# must NOT pay for it.  `implementation/core` is on every list while the tier
# runs — it is the substrate the others sit on.
# ---------------------------------------------------------------------------
plan_jvm() {
  bash "$spine" --plan --repo-root "$1" 2>/dev/null | grep '^PLAN-JVM' || printf 'PLAN-JVM <none>\n'
}

r="$tmp_root/jvm-routing"; mkrepo "$r"
mkdir -p "$r/implementation/routing/src/re_frame"
printf 'x\n' > "$r/implementation/routing/src/re_frame/routing.cljc"
git -C "$r" add -A; git -C "$r" commit -q -m routing
assert "S routing source → routing's own suite is added" \
  "PLAN-JVM implementation/core implementation/routing" "$(plan_jvm "$r")"

assert "T a core-only diff does not pay for routing" \
  "PLAN-JVM implementation/core" "$(plan_jvm "$tmp_root/coverage-note")"

# A roster entry must match on a path BOUNDARY: `implementation/adapters/reagent`
# is a prefix of `implementation/adapters/reagent-slim` as a string, and arming
# the wrong artefact from a sibling's diff would be silent over-testing.
r="$tmp_root/jvm-slim"; mkrepo "$r"
mkdir -p "$r/implementation/adapters/reagent-slim/src"
printf 'x\n' > "$r/implementation/adapters/reagent-slim/src/a.cljs"
git -C "$r" add -A; git -C "$r" commit -q -m slim
assert "U reagent-slim does not arm reagent (path boundary)" \
  "PLAN-JVM implementation/core implementation/adapters/reagent-slim" "$(plan_jvm "$r")"

r="$tmp_root/jvm-none"; mkrepo "$r"
mkdir -p "$r/docs"; printf '# h\n' > "$r/docs/x.md"
git -C "$r" add -A; git -C "$r" commit -q -m d
assert "V tier skipped → no artefact suite at all" "PLAN-JVM" "$(plan_jvm "$r")"

# ---------------------------------------------------------------------------
# mkdocs RESOLUTION (rf2-g7p7l).  The spine probed only for a bare `mkdocs`
# console script, so on a checkout where mkdocs is installed as a module —
# `pip install --user`, console script off PATH — the strict site build
# soft-skipped on EVERY run and the spine still printed PASS.  `PLAN-MKDOCS`
# makes the resolution observable without paying for an 18-second build, and
# these cases pin it: the console script wins when present, the module is
# found when it is not, and a code-only diff never pays to look.
# ---------------------------------------------------------------------------
plan_mkdocs() {
  bash "$spine" --plan --repo-root "$1" 2>/dev/null | grep '^PLAN-MKDOCS' \
    || printf 'PLAN-MKDOCS <none>\n'
}

r="$tmp_root/mkdocs-docs"; mkrepo "$r"
mkdir -p "$r/docs"; printf '# h\n' > "$r/docs/x.md"
git -C "$r" add -A; git -C "$r" commit -q -m d

# W — the console script is preferred when it is on PATH.  A stub suffices:
# resolution must not depend on the real tool being installed.
stub_bin="$tmp_root/stub-bin"; mkdir -p "$stub_bin"
printf '#!/bin/sh\nexit 0\n' > "$stub_bin/mkdocs"
chmod +x "$stub_bin/mkdocs"
if PATH="$stub_bin:$PATH" command -v mkdocs >/dev/null 2>&1; then
  assert "W console script on PATH → resolved as mkdocs" "PLAN-MKDOCS mkdocs" \
    "$(PATH="$stub_bin:$PATH" plan_mkdocs "$r")"
else
  printf '  SKIP W: this shell cannot make a stub executable discoverable\n'
fi

# X — THE HOST SMOKE.  Wherever mkdocs is genuinely installed, the spine must
# resolve it; `unresolved` there is the fail-open this case exists to catch.  On
# CI (console script on PATH) it resolves to `mkdocs`; on a module-only
# checkout, to `python -m mkdocs`.  Either is a pass; `unresolved` is not.
#
# This case CONSULTS THE HOST by design — it is the one assertion made against
# the real tool — so it can only ever prove what this host happens to have.  The
# hermetic cases below (AC-AE) are what pin the module fallback itself; do not
# read a green X as covering it.  The precondition tries all three launchers the
# spine tries, so a host with only `python3` or `py` runs the case instead of
# skipping it (rf2-03298).
mkdocs_on_host=false
if command -v mkdocs >/dev/null 2>&1; then
  mkdocs_on_host=true
else
  for _launcher in python python3 py; do
    if command -v "$_launcher" >/dev/null 2>&1 &&
       "$_launcher" -m mkdocs --version >/dev/null 2>&1; then
      mkdocs_on_host=true
      break
    fi
  done
fi
if [ "$mkdocs_on_host" = true ]; then
  case "$(plan_mkdocs "$r")" in
    "PLAN-MKDOCS unresolved"|"PLAN-MKDOCS <none>") mkdocs_found=no ;;
    *)                                             mkdocs_found=yes ;;
  esac
  assert "X mkdocs installed → the spine resolves it (not soft-skipped)" "yes" "$mkdocs_found"
else
  printf '  SKIP X: mkdocs is installed neither as a script nor as a module here\n'
fi

# Y — the common case pays nothing: a code-only diff never probes for mkdocs.
assert "Y code-only diff → mkdocs never probed" "PLAN-MKDOCS (docs tier skipped)" \
  "$(plan_mkdocs "$tmp_root/coverage-note")"

# ---------------------------------------------------------------------------
# THE SPINE'S OWN TREE (rf2-fhdd3).  A diff touching only `scripts/test-fast-pr.sh`
# matched no runtime surface and no documentation surface, so it fell into the
# `unknown surface` fallback: JVM and node ran, and `run_docs` — keyed on the
# documentation-content predicate the runner never matched — stayed FALSE.  The
# gate that decides which gates run could not gate a change to its own
# documentation gate; every rf2-g7p7l verification run needed `--with-docs` by
# hand, and someone BREAKING the docs tier would have got a green spine.
#
# Z/AA pin the arming in both directions of the bead's finding (the runner, and
# the fixture tree this file lives in).  AB is the counterweight the bead asked
# for by name: the arming is path-by-path, so an ordinary `scripts/` change
# still does not pay for the documentation tier.
# ---------------------------------------------------------------------------
plan_selftest() {
  bash "$spine" --plan --repo-root "$1" 2>/dev/null | grep '^PLAN-SELFTEST' \
    || printf 'PLAN-SELFTEST <none>\n'
}

r="$tmp_root/spine-self"; mkrepo "$r"
mkdir -p "$r/scripts"
printf '#!/usr/bin/env bash\n' > "$r/scripts/test-fast-pr.sh"
git -C "$r" add -A; git -C "$r" commit -q -m spine
assert "Z spine-only diff → docs tier armed (was docs=false)" \
  "PLAN docs=true jvm=true node=true" "$(plan "$r")"
assert "Z1 spine-only diff → the spine's own self-test is armed" \
  "PLAN-SELFTEST run" "$(plan_selftest "$r")"

r="$tmp_root/spine-fixture"; mkrepo "$r"
mkdir -p "$r/scripts/_test_fixtures/test_fast_pr_docs_gate"
printf 'x\n' > "$r/scripts/_test_fixtures/test_fast_pr_docs_gate/run-self-test.sh"
git -C "$r" add -A; git -C "$r" commit -q -m fixture
assert "AA doc-gate fixture tree → docs tier armed" \
  "PLAN docs=true jvm=true node=true" "$(plan "$r")"
assert "AA1 doc-gate fixture tree → the spine's own self-test is armed" \
  "PLAN-SELFTEST run" "$(plan_selftest "$r")"

# AB — the NOT-widened pin.  `scripts/check_skill_mcp_drift.py` is an ordinary
# always-on gate script: no doc gate reads it, so arming the documentation tier
# for it would buy nothing and cost an mkdocs build on every such diff.
r="$tmp_root/ordinary-script"; mkrepo "$r"
mkdir -p "$r/scripts"; printf 'x\n' > "$r/scripts/check_skill_mcp_drift.py"
git -C "$r" add -A; git -C "$r" commit -q -m ordinary
assert "AB an ordinary scripts/ change does NOT arm the docs tier" \
  "PLAN docs=false jvm=true node=true" "$(plan "$r")"
assert "AB1 an ordinary scripts/ change does NOT arm the spine self-test" \
  "PLAN-SELFTEST skip" "$(plan_selftest "$r")"

# ---------------------------------------------------------------------------
# HERMETIC mkdocs RESOLUTION (rf2-03298).  Case X above consults the HOST, and
# on the host that matters most — GitHub CI, which installs requirements.txt —
# a bare `mkdocs` console script is always on PATH.  X therefore takes the
# console-script branch there and NO module fallback is ever executed: deleting
# `python -m mkdocs` from resolve_mkdocs would stay green on every remote run
# and reopen the exact fail-open rf2-g7p7l closed, on exactly the checkouts that
# motivated it (`pip install --user`, console script off PATH).
#
# These cases ask the host nothing.  They CONSTRUCT the module-only state — a
# PATH with every directory that provides a bare `mkdocs` removed, plus a stub
# directory that shadows all three launchers and lets exactly one of them answer
# `-m mkdocs --version` — and assert the EXACT command the real spine selected.
# One case per supported launcher (AC/AC1/AC2), because `resolve_mkdocs` tries
# `python python3 py` in order and a witness for `python` alone cannot tell a
# working three-entry loop from a one-entry one.  resolve_mkdocs is not copied
# here: `--plan` runs the real one.
# ---------------------------------------------------------------------------

# The docs-armed disposable repo cases W-Y already built: the mkdocs resolution
# is only reached when the documentation tier would run.
r_docs_for_mkdocs="$tmp_root/mkdocs-docs"

# $PATH with every entry that provides a bare `mkdocs` removed.  Globbing is
# disabled across the split so a PATH entry containing a glob character cannot
# expand into something else.
mkdocs_free_path() {
  local out="" entry oldifs restore_glob=no
  oldifs="$IFS"
  case "$-" in *f*) ;; *) restore_glob=yes ;; esac
  set -f
  IFS=:
  for entry in $PATH; do
    [ -z "$entry" ] && continue
    if [ -x "$entry/mkdocs" ] || [ -x "$entry/mkdocs.exe" ] ||
       [ -f "$entry/mkdocs.bat" ] || [ -f "$entry/mkdocs.cmd" ]; then
      continue
    fi
    out="${out:+$out:}$entry"
  done
  IFS="$oldifs"
  if [ "$restore_glob" = yes ]; then set +f; fi
  printf '%s' "$out"
}

mkdocs_free="$(mkdocs_free_path)"

# A stub launcher directory.  It SHADOWS all three launchers `resolve_mkdocs`
# tries and lets exactly one of them — `$2`, or none at all for `none` — answer
# `-m mkdocs --version`; every other invocation of every stub fails.  So a green
# case proves the spine took the module branch, via that launcher and no other.
#
# Shadowing the other two is what makes the per-launcher cases hermetic
# (rf2-03298).  The sanitised PATH only has bare `mkdocs` removed; the host's
# REAL interpreters are still on it, and `resolve_mkdocs` tries `python` first.
# A `python3`-only witness with no `python` stub in front of it would therefore
# be satisfied on this runner by the host's own `python`, and would stay green
# through exactly the regression it exists to catch.
make_launcher_bin() {
  local dir="$1" working="$2" l
  mkdir -p "$dir"
  for l in python python3 py; do
    if [ "$l" = "$working" ]; then
      cat > "$dir/$l" <<'STUB'
#!/bin/sh
if [ "${1:-}" = "-m" ] && [ "${2:-}" = "mkdocs" ] && [ "${3:-}" = "--version" ]; then
  printf 'mkdocs, version 1.6.1 (hermetic self-test stub)\n'
  exit 0
fi
exit 1
STUB
    else
      printf '#!/bin/sh\nexit 1\n' > "$dir/$l"
    fi
    chmod +x "$dir/$l"
  done
}

# One sandbox per supported launcher.  `resolve_mkdocs` iterates
# `python python3 py`; narrowing that list back to `python` alone leaves every
# required check green today, and breaks precisely the python3-only and py-only
# checkouts this Bead was filed for.  Three witnesses, one per iteration.
module_bin="$tmp_root/module-only-bin";       make_launcher_bin "$module_bin" python
module_bin_py3="$tmp_root/module-only-py3";   make_launcher_bin "$module_bin_py3" python3
module_bin_py="$tmp_root/module-only-py";     make_launcher_bin "$module_bin_py" py

# Every launcher present and none of them able to run mkdocs — the honest-skip
# path, which must report `unresolved` rather than anything that reads as a pass.
none_bin="$tmp_root/no-mkdocs-bin";           make_launcher_bin "$none_bin" none

# A console-script stub for the preference case, alongside a working module
# launcher — so "console script wins" is asserted against a live alternative.
both_bin="$tmp_root/console-wins-bin"; mkdir -p "$both_bin"
cp "$module_bin/python" "$both_bin/python"
printf '#!/bin/sh\nexit 0\n' > "$both_bin/mkdocs"
chmod +x "$both_bin/mkdocs"

# The sandbox must still carry the tools the spine itself shells out to.  If
# stripping the mkdocs directories took one of them, the assertions below would
# fail for the wrong reason — so say so loudly instead of skipping quietly: a
# witness that goes quiet on an unexpected host is the fail-open family these
# cases exist to close.
hermetic_ready=yes
missing_tool=""
for _tool in bash git awk sed sort grep; do
  if ! ( PATH="$module_bin:$mkdocs_free"; export PATH; command -v "$_tool" >/dev/null 2>&1 ); then
    hermetic_ready=no
    missing_tool="$_tool"
    break
  fi
done
if ( PATH="$module_bin:$mkdocs_free"; export PATH; command -v mkdocs >/dev/null 2>&1 ); then
  hermetic_ready=no
  missing_tool="(a bare mkdocs is STILL discoverable after sanitising PATH)"
fi

if [ "$hermetic_ready" = yes ]; then
  assert "AC hermetic module-only host → 'python -m mkdocs' selected" \
    "PLAN-MKDOCS python -m mkdocs" \
    "$(PATH="$module_bin:$mkdocs_free" plan_mkdocs "$r_docs_for_mkdocs")"

  assert "AC1 only python3 can run mkdocs → 'python3 -m mkdocs' selected" \
    "PLAN-MKDOCS python3 -m mkdocs" \
    "$(PATH="$module_bin_py3:$mkdocs_free" plan_mkdocs "$r_docs_for_mkdocs")"

  assert "AC2 only py can run mkdocs → 'py -m mkdocs' selected" \
    "PLAN-MKDOCS py -m mkdocs" \
    "$(PATH="$module_bin_py:$mkdocs_free" plan_mkdocs "$r_docs_for_mkdocs")"

  assert "AD console script wins over a WORKING module launcher" \
    "PLAN-MKDOCS mkdocs" \
    "$(PATH="$both_bin:$mkdocs_free" plan_mkdocs "$r_docs_for_mkdocs")"

  assert "AE nothing resolves → 'unresolved' (never a silent pass)" \
    "PLAN-MKDOCS unresolved" \
    "$(PATH="$none_bin:$mkdocs_free" plan_mkdocs "$r_docs_for_mkdocs")"
else
  printf '  FAIL AC-AE: cannot construct a module-only PATH on this host — %s\n' "$missing_tool"
  printf '        The module-only fallback in resolve_mkdocs was NOT exercised.\n'
  fail_count=$((fail_count + 5))
fi

# ---- Summary ----
total=$((pass_count + fail_count))
printf '\n%s/%s self-test cases passed.\n' "$pass_count" "$total"
if [ "$fail_count" -gt 0 ]; then
  printf '%s FAILED.\n' "$fail_count" >&2
  exit 1
fi
exit 0
