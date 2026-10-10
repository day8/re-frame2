#!/usr/bin/env bash
# Self-test for the changed-surface tiering in scripts/test-fast-pr.sh.
#
# It drives the REAL spine in `--plan` mode against disposable git repos, so no
# copy of the detection logic can drift from it.  `--plan` classifies the change
# set, prints machine-readable `PLAN…` lines and exits without running any gate;
# `--repo-root DIR` points the change-set gathering at a fixture repo while the
# real gate scripts stay put.
#
# Cases:
#   A-D          — the git states the change-set gathering reads: committed,
#                  staged, unstaged, untracked (C1: a spec page arms the JVM tier
#                  without the node tier);
#   F, G, H      — no changes, no origin/main base, a mixed docs+code diff;
#   I-L          — the --all / RF2_FAST_PR_ALL / --with-docs / --no-docs overrides;
#   M, N         — the doc validators' main() exits 1 on a bundled broken fixture;
#   U            — the JVM tier adds a touched artefact, matched on a path boundary;
#   Z, AB        — the spine's own file arms every tier; an ordinary script takes
#                  the unknown-surface fallback without the docs tier;
#   AC2, AD, AE  — hermetic mkdocs resolution: the module fallback through the
#                  last launcher, a console script preferred, `unresolved`;
#   AF1-AF5      — the pinned clj-kondo lane's arming.
#
# CI: test.yml's always-on `verify-readme-links` job runs this file.
#
# Run from any cwd:
#   bash scripts/_test_fixtures/test_fast_pr_docs_gate/run-self-test.sh
#
# Exit code: 0 all assertions hold; 1 at least one failed; 2 setup error.

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

# One `KEY …` line of the spine's --plan output: plan KEY ROOT [spine args...].
plan() {
  local key="$1" root="$2"; shift 2
  bash "$spine" --plan --repo-root "$root" "$@" 2>/dev/null | grep "^$key " \
    || printf '%s <none>\n' "$key"
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

# ---- A: committed docs-only diff (reused below as THE docs-only diff) ----
docs_repo="$tmp_root/committed-docs"; mkrepo "$docs_repo"
mkdir -p "$docs_repo/docs/core"; printf '# h\n' > "$docs_repo/docs/core/x.md"
git -C "$docs_repo" add docs/core/x.md; git -C "$docs_repo" commit -q -m docs
assert "A committed docs-only → docs only" "PLAN docs=true jvm=false node=false" "$(plan PLAN "$docs_repo")"

# ---- B: staged code diff (reused below as THE code-only diff) ----
code_repo="$tmp_root/staged-code"; mkrepo "$code_repo"
mkdir -p "$code_repo/implementation/core/src/re_frame"
printf 'x\n' > "$code_repo/implementation/core/src/re_frame/core.cljc"
git -C "$code_repo" add implementation/core/src/re_frame/core.cljc   # staged, not committed
assert "B staged core code → runtime" "PLAN docs=false jvm=true node=true" "$(plan PLAN "$code_repo")"

# ---- C: unstaged edit to a tracked file ----
# The page must be prose no suite reads, so that `jvm=false` speaks to the git
# state: a `spec/*` page arms the JVM tier (C1).
r="$tmp_root/unstaged-docs"; mkrepo "$r"
mkdir -p "$r/docs/guide"; printf '# a\n' > "$r/docs/guide/unstaged.md"
git -C "$r" add docs/guide/unstaged.md; git -C "$r" commit -q -m addmd
git -C "$r" update-ref refs/remotes/origin/main HEAD
printf '# a\nmore\n' > "$r/docs/guide/unstaged.md"            # unstaged modify
assert "C unstaged docs → docs only" "PLAN docs=true jvm=false node=false" "$(plan PLAN "$r")"

# ---- C1: a spec page arms the JVM tier as CI's `implementation_jvm` does —
# the one case where the JVM and node tiers disagree. ----
r="$tmp_root/unstaged-pinned-prose"; mkrepo "$r"
mkdir -p "$r/spec"; printf '# a\n' > "$r/spec/006-ReactiveSubstrate.md"
git -C "$r" add spec/006-ReactiveSubstrate.md; git -C "$r" commit -q -m addmd
git -C "$r" update-ref refs/remotes/origin/main HEAD
printf '# a\nmore\n' > "$r/spec/006-ReactiveSubstrate.md"     # unstaged modify
assert "C1 unstaged pinned spec prose → docs + JVM tier" \
  "PLAN docs=true jvm=true node=false" "$(plan PLAN "$r")"

# ---- D: untracked docs file ----
r="$tmp_root/untracked-docs"; mkrepo "$r"
printf '# u\n' > "$r/NOTES.md"                                # untracked, never added
assert "D untracked docs → docs only" "PLAN docs=true jvm=false node=false" "$(plan PLAN "$r")"

# ---- F: no changes vs origin/main, clean tree ----
r="$tmp_root/clean"; mkrepo "$r"
assert "F no changes → static only" "PLAN docs=false jvm=false node=false" "$(plan PLAN "$r")"

# ---- G: no origin/main base (reused by AF5) ----
nobase_repo="$tmp_root/nobase"; mkdir -p "$nobase_repo"
git -C "$nobase_repo" init -q -b main 2>/dev/null
git -C "$nobase_repo" config user.email "self-test@local"; git -C "$nobase_repo" config user.name "self-test"
git -C "$nobase_repo" config core.autocrlf false; git -C "$nobase_repo" config core.safecrlf false
printf 'x\n' > "$nobase_repo/f.txt"; git -C "$nobase_repo" add -A; git -C "$nobase_repo" commit -q -m init
assert "G no origin/main → conservative runtime" "PLAN docs=false jvm=true node=true" "$(plan PLAN "$nobase_repo")"

# ---- H: mixed docs + code — the one multi-file change set ----
r="$tmp_root/mixed"; mkrepo "$r"
mkdir -p "$r/docs" "$r/implementation/core/src/re_frame"
printf '# h\n' > "$r/docs/x.md"
printf 'x\n' > "$r/implementation/core/src/re_frame/core.cljc"
git -C "$r" add -A; git -C "$r" commit -q -m mix
assert "H mixed docs+code → both tiers" "PLAN docs=true jvm=true node=true" "$(plan PLAN "$r")"

# ---- I-L: the overrides ----
assert "I --all override → everything" "PLAN docs=true jvm=true node=true" "$(plan PLAN "$docs_repo" --all)"
assert "J RF2_FAST_PR_ALL=1 override → everything" "PLAN docs=true jvm=true node=true" \
  "$(RF2_FAST_PR_ALL=1 plan PLAN "$docs_repo")"
assert "K --with-docs on code diff → docs forced on" "PLAN docs=true jvm=true node=true" \
  "$(plan PLAN "$code_repo" --with-docs)"
assert "L --no-docs on docs diff → docs forced off" "PLAN docs=false jvm=false node=false" \
  "$(plan PLAN "$docs_repo" --no-docs)"

# ---- M, N: the doc validators' main() exits 1 on a bundled broken fixture ----
python "$repo_root/scripts/check_doc_slugs.py" \
  --repo-root "$repo_root/scripts/_test_fixtures/check_doc_slugs/broken_anchor" >/dev/null 2>&1
assert "M check_doc_slugs catches broken anchor" "1" "$?"

python "$repo_root/scripts/check_readme_links.py" --ci \
  --repo-root "$repo_root/scripts/_test_fixtures/check_readme_links/broken_internal_link" >/dev/null 2>&1
assert "N check_readme_links catches broken target" "1" "$?"

# ---- U: `PLAN-JVM` is core plus each touched artefact.  The roster entry
# `implementation/adapters/reagent` is a string prefix of `…/reagent-slim`, so
# the match must be on a path boundary. ----
r="$tmp_root/jvm-slim"; mkrepo "$r"
mkdir -p "$r/implementation/adapters/reagent-slim/src"
printf 'x\n' > "$r/implementation/adapters/reagent-slim/src/a.cljs"
git -C "$r" add -A; git -C "$r" commit -q -m slim
assert "U reagent-slim does not arm reagent (path boundary)" \
  "PLAN-JVM implementation/core implementation/adapters/reagent-slim" "$(plan PLAN-JVM "$r")"

# ---- Z: the spine's own file matches no runtime and no documentation-content
# surface, so without its own arm an edit that broke the docs tier would never
# run it. ----
r="$tmp_root/spine-self"; mkrepo "$r"
mkdir -p "$r/scripts"
printf '#!/usr/bin/env bash\n' > "$r/scripts/test-fast-pr.sh"
git -C "$r" add -A; git -C "$r" commit -q -m spine
assert "Z spine-only diff → every tier armed" "PLAN docs=true jvm=true node=true" "$(plan PLAN "$r")"

# ---- AB: an ordinary gate script.  The spine's arm is path-by-path, never
# `scripts/*`, so no docs tier; the classifier knows no surface in it, so the
# unknown-surface fallback runs the runtime tiers. ----
r="$tmp_root/ordinary-script"; mkrepo "$r"
mkdir -p "$r/scripts"; printf 'x\n' > "$r/scripts/check_skill_mcp_drift.py"
git -C "$r" add -A; git -C "$r" commit -q -m ordinary
assert "AB an ordinary scripts/ change does NOT arm the docs tier" \
  "PLAN docs=false jvm=true node=true" "$(plan PLAN "$r")"

# ---------------------------------------------------------------------------
# HERMETIC mkdocs RESOLUTION.  CI has a bare `mkdocs` console script on PATH, so
# a host-consulting case would never execute the `python -m mkdocs` fallback
# there.  These cases ask the host nothing: they CONSTRUCT a PATH with every
# bare `mkdocs` removed plus a stub directory that shadows all three launchers
# `resolve_mkdocs` tries and lets at most one answer `-m mkdocs --version`, then
# assert the EXACT command the real spine selected.
# ---------------------------------------------------------------------------

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

# A stub directory shadowing all three launchers; only `$2` (or none, for
# `none`) answers `-m mkdocs --version`.  The shadowing is what makes a case
# hermetic: the host's real interpreters are still on the sanitised PATH, and
# `resolve_mkdocs` tries `python` first.
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

# `py` is the LAST launcher tried, so its witness also proves the loop walks
# past the two that fail.
module_bin_py="$tmp_root/module-only-py"; make_launcher_bin "$module_bin_py" py
none_bin="$tmp_root/no-mkdocs-bin";       make_launcher_bin "$none_bin" none
# A console script beside a WORKING module launcher, so "console script wins"
# is asserted against a live alternative.
both_bin="$tmp_root/console-wins-bin";    make_launcher_bin "$both_bin" python
printf '#!/bin/sh\nexit 0\n' > "$both_bin/mkdocs"
chmod +x "$both_bin/mkdocs"

# The sandbox must still carry the tools the spine shells out to, and must hide
# every bare mkdocs; otherwise the cases below would fail for the wrong reason,
# so say so loudly instead of skipping.
hermetic_ready=yes
missing_tool=""
for _tool in bash git awk sed sort grep; do
  if ! ( PATH="$none_bin:$mkdocs_free"; export PATH; command -v "$_tool" >/dev/null 2>&1 ); then
    hermetic_ready=no
    missing_tool="$_tool"
    break
  fi
done
if ( PATH="$none_bin:$mkdocs_free"; export PATH; command -v mkdocs >/dev/null 2>&1 ); then
  hermetic_ready=no
  missing_tool="(a bare mkdocs is STILL discoverable after sanitising PATH)"
fi

if [ "$hermetic_ready" = yes ]; then
  assert "AC2 only py can run mkdocs → 'py -m mkdocs' selected" \
    "PLAN-MKDOCS py -m mkdocs" \
    "$(PATH="$module_bin_py:$mkdocs_free" plan PLAN-MKDOCS "$docs_repo")"

  assert "AD console script wins over a WORKING module launcher" \
    "PLAN-MKDOCS mkdocs" \
    "$(PATH="$both_bin:$mkdocs_free" plan PLAN-MKDOCS "$docs_repo")"

  assert "AE nothing resolves → 'unresolved' (never a silent pass)" \
    "PLAN-MKDOCS unresolved" \
    "$(PATH="$none_bin:$mkdocs_free" plan PLAN-MKDOCS "$docs_repo")"
else
  printf '  FAIL AC2-AE: cannot construct a module-only PATH on this host — %s\n' "$missing_tool"
  fail_count=$((fail_count + 3))
fi

# ---------------------------------------------------------------------------
# THE PINNED clj-kondo LANE arms on its own surface — lint.yml's `--lint` roots
# plus the config and workflow the lane reads — not on the tiers above.
# ---------------------------------------------------------------------------

# AF1 — `examples/` is a LATER `--lint` root, so the whole list is read.
r="$tmp_root/kondo-examples"; mkrepo "$r"
mkdir -p "$r/examples/capabilities/resources/linearlite"
printf 'x\n' > "$r/examples/capabilities/resources/linearlite/core.cljs"
git -C "$r" add -A; git -C "$r" commit -q -m example
assert "AF1 examples/, a later --lint root → armed" "PLAN-KONDO run" "$(plan PLAN-KONDO "$r")"

# AF3 — the workflow carries the pin and the target list: an exact path, not a tree.
r="$tmp_root/kondo-workflow"; mkrepo "$r"
mkdir -p "$r/.github/workflows"; printf 'name: lint\n' > "$r/.github/workflows/lint.yml"
git -C "$r" add -A; git -C "$r" commit -q -m workflow
assert "AF3 lint.yml itself → armed" "PLAN-KONDO run" "$(plan PLAN-KONDO "$r")"

assert "AF4 a docs-only diff does NOT arm the kondo lane" "PLAN-KONDO skip" "$(plan PLAN-KONDO "$docs_repo")"

# AF5 — an unresolvable base is an indeterminate change set, not an empty one.
assert "AF5 no origin/main base → armed conservatively" "PLAN-KONDO run" "$(plan PLAN-KONDO "$nobase_repo")"

# ---- Summary ----
total=$((pass_count + fail_count))
printf '\n%s/%s self-test cases passed.\n' "$pass_count" "$total"
if [ "$fail_count" -gt 0 ]; then
  printf '%s FAILED.\n' "$fail_count" >&2
  exit 1
fi
exit 0
