#!/usr/bin/env bash
set -euo pipefail

# Conservative changed-surface classifier for PR CI tiering.
# Usage:
#   .github/scripts/report-changed-surfaces.sh [--all] [path ...]
#
# With explicit paths, classify those paths. Without paths, derive the changed
# file list from the GitHub Actions event, or from HEAD^ locally.

force_all=false
declare -a explicit_paths=()
for arg in "$@"; do
  case "$arg" in
    --all) force_all=true ;;
    *) explicit_paths+=("$arg") ;;
  esac
done

if [ "$force_all" = true ]; then
  files="__ALL__"
elif [ "${#explicit_paths[@]}" -gt 0 ]; then
  files="$(printf '%s\n' "${explicit_paths[@]}")"
elif [ "${GITHUB_EVENT_NAME:-}" = "pull_request" ] && [ -n "${GITHUB_BASE_REF:-}" ]; then
  git fetch --no-tags --depth=100 origin "${GITHUB_BASE_REF}" >/dev/null 2>&1 || true
  files="$(git diff --name-only "origin/${GITHUB_BASE_REF}...HEAD")"
else
  files="$(git diff --name-only HEAD^ HEAD 2>/dev/null || git diff --name-only HEAD)"
fi

implementation_jvm=false
adapter_diagnostic=false
cljs_browser=false
cljs_prod=false
bundle_isolation=false
reagent_slim_bundle=false
adapter_testbed_smokes=false
framework_testbeds=false
tools_jvm=false
template_expensive=false
mcp_conformance=false
mcp_live=false
story_causa_browser=false
skills_structural=false

mark_all() {
  implementation_jvm=true
  adapter_diagnostic=true
  cljs_browser=true
  cljs_prod=true
  bundle_isolation=true
  reagent_slim_bundle=true
  adapter_testbed_smokes=true
  framework_testbeds=true
  tools_jvm=true
  template_expensive=true
  mcp_conformance=true
  mcp_live=true
  story_causa_browser=true
  skills_structural=true
}

# rf2-k9ekz — predicate: does `$1` look like a Story/Causa runtime
# source file (CLJS/CLJC/JS/CSS extension under tools/{story,causa}/src/**
# or tools/{story,causa}/testbeds/**)? Returns 0 (yes) / 1 (no). The
# Story/Causa browser gate only fires on a runtime-relevant extension
# under one of those two trees — Markdown specs, EDN config, and JVM
# unit tests under tools/{story,causa}/{spec,test,bench}/** do not
# affect chrome and so do not fire the gate. Testbeds under
# tools/{story,causa}/testbeds/** legitimately drive the
# story-feature-load + causa-feature-gate Playwright runners (their
# variant graph IS what the gate exercises), so a runtime-extension
# change there does fire the gate. The runtime-extension AND
# allow-listed-subdir rule is symmetric with the framework-testbeds
# split below — both gates filter by `.cljs|.cljc|.js|.cjs|.css|.scss`
# under explicitly named subdirs, never by anything that grep-matches
# the parent tool dir.
is_story_causa_runtime_path() {
  case "$1" in
    tools/story/src/*|tools/causa/src/*|tools/story/testbeds/*|tools/causa/testbeds/*)
      case "$1" in
        *.cljs|*.cljc|*.js|*.cjs|*.css|*.scss)
          return 0 ;;
        *)
          return 1 ;;
      esac
      ;;
    *)
      return 1 ;;
  esac
}

# rf2-9grp6 — predicate: does `$1` look like a framework-testbed
# runtime source file (CLJS/CLJC/JS/CSS extension under
# tools/causa/testbeds/** or top-level testbeds/**)? Returns 0 / 1.
# The split between this gate and `adapter_testbed_smokes` is intentional
# (rf2-cjp0i + this bead): adapter source changes fire the 3 adapter
# smokes, framework-testbed source changes fire the 11 framework +
# top-level testbeds; spec/Markdown changes fire neither. Story's own
# testbeds (tools/story/testbeds/**) drive the Story/Causa browser gate
# above, not this one — Causa-owned framework testbeds live under
# tools/causa/testbeds/** plus the cross-cutting top-level testbeds/**
# (SSR + framework-behaviour).
is_framework_testbed_path() {
  case "$1" in
    tools/causa/testbeds/*|testbeds/*)
      case "$1" in
        *.cljs|*.cljc|*.js|*.cjs|*.css|*.scss|*.html|*.json)
          return 0 ;;
        *)
          return 1 ;;
      esac
      ;;
    *)
      return 1 ;;
  esac
}

if [ "$files" = "__ALL__" ]; then
  mark_all
else
  while IFS= read -r file; do
    [ -z "$file" ] && continue
    case "$file" in
      .github/workflows/test.yml|.github/workflows/expensive-tests.yml|.github/scripts/report-changed-surfaces.sh|TESTING.md)
        mark_all
        ;;
      implementation/core/*)
        # rf2-8jz9t + rf2-k9ekz + rf2-9grp6 — adapter_testbed_smokes,
        # framework_testbeds, and story_causa_browser are NOT fired
        # here. The Playwright gates exist to catch surface-specific
        # browser bugs (adapter mount lifecycle, Story variant boot,
        # Causa panel layout, multi-frame isolation) — none of which
        # are core regressions. Core renames are caught by node-test
        # (consolidated CLJS unit + browser-test) which exercises every
        # public re-frame.core fn, and by the always-on JVM core suite.
        # A core rename that breaks an adapter mount, Story variant
        # boot, or framework-testbed assertion silently is caught by
        # the nightly cron + post-merge gate (both run the full matrix
        # on main).
        implementation_jvm=true
        adapter_diagnostic=true
        cljs_browser=true
        cljs_prod=true
        bundle_isolation=true
        tools_jvm=true
        template_expensive=true
        mcp_conformance=true
        mcp_live=true
        ;;
      implementation/adapters/reagent-slim/*|examples/reagent/counter_slim_and_fast/*|implementation/scripts/check-reagent-slim-bundle-isolation.cjs)
        # rf2-8cevm — the examples/ tree is test-free. counter_slim_and_fast
        # used to ship a paired spec.cjs but the bundle-isolation contract
        # at scripts/check-reagent-slim-bundle-isolation.cjs is the
        # canonical gate; adapter_testbed_smokes is no longer fired here.
        implementation_jvm=true
        adapter_diagnostic=true
        cljs_browser=true
        cljs_prod=true
        reagent_slim_bundle=true
        ;;
      implementation/adapters/*)
        # rf2-bxdk8 + rf2-cjp0i — the adapter-testbed-smokes gate is
        # scoped to the 3 adapter smokes at
        # implementation/adapters/<reagent|uix|helix>/testbed/. Adapter
        # source changes are the canonical trigger; orchestrator-script
        # changes are caught by the harness-script case below.
        implementation_jvm=true
        adapter_diagnostic=true
        cljs_browser=true
        cljs_prod=true
        bundle_isolation=true
        adapter_testbed_smokes=true
        tools_jvm=true
        template_expensive=true
        mcp_conformance=true
        mcp_live=true
        ;;
      examples/scripts/serve-and-run-examples-tests.cjs|examples/scripts/run-examples-tests.cjs|examples/scripts/spec-helpers.cjs)
        # rf2-bxdk8 + rf2-cjp0i — the orchestrator + runner + helpers
        # under examples/scripts/ drive the adapter-testbed-smokes job
        # (via `npm run test:examples`). They are the *only* paths
        # under examples/ that fire this gate; the rest of examples/**
        # is test-free per rf2-8cevm.
        adapter_testbed_smokes=true
        ;;
      implementation/schemas/*|implementation/machines/*|implementation/routing/*|implementation/flows/*|implementation/http/*|implementation/ssr/*|implementation/ssr-ring/*|implementation/epoch/*|implementation/deps.edn)
        # rf2-8jz9t — adapter_testbed_smokes NOT fired here. Per-feature
        # artefact changes are covered by their own JVM + CLJS unit
        # suites (implementation_jvm, cljs_browser, cljs_prod) and by
        # bundle_isolation; the adapter smokes under
        # adapter-testbed-smokes only catch adapter-mount-specific bugs
        # (createRoot lifecycle, hydration, real concurrent scheduling).
        # Nightly + post-merge gate runs the full matrix.
        implementation_jvm=true
        cljs_browser=true
        cljs_prod=true
        bundle_isolation=true
        ;;
      spec/conformance/fixtures/*)
        # rf2-qmiiz — Fixtures under spec/conformance/fixtures/*.edn
        # are consumed by:
        #   - implementation/core/test/re_frame/conformance_test.clj
        #     (JVM core job, always-on)
        #   - implementation/core/test/re_frame/conformance_corpus_cljs_test.cljs
        #     (cljs job, always-on)
        #   - per-artefact _conformance_test.clj under
        #     implementation/{flows,ssr,machines,schemas,routing}/test/
        #     (each gated behind implementation_jvm='true')
        # A fixture-only PR (no impl/test change) would skip every
        # per-artefact _conformance_test.clj. Fire implementation_jvm
        # so the per-artefact corpus runners pick up new fixtures, and
        # fire the CLJS surfaces so the cross-platform corpus runner
        # in core does too.
        implementation_jvm=true
        cljs_browser=true
        cljs_prod=true
        ;;
      implementation/shadow-cljs.edn|implementation/package.json|implementation/package-lock.json|implementation/scripts/*)
        # rf2-8jz9t + rf2-bxdk8 + rf2-cjp0i + rf2-k9ekz + rf2-9grp6 —
        # adapter_testbed_smokes, framework_testbeds, and
        # story_causa_browser are NOT fired here. The Playwright gates
        # are now triggered ONLY by direct source-tree changes (adapter
        # source for adapter-testbed-smokes; tools/causa/testbeds/** +
        # top-level testbeds/** for framework-testbeds;
        # tools/{story,causa}/{src,testbeds}/** for the Story/Causa
        # browser gate). A shadow-cljs.edn or implementation/scripts/
        # change that breaks the build is caught by the nightly cron +
        # post-merge gate (both run the full matrix on main).
        cljs_browser=true
        cljs_prod=true
        bundle_isolation=true
        reagent_slim_bundle=true
        ;;
      examples/*)
        # rf2-bxdk8 + rf2-cjp0i + rf2-8cevm — examples/** is test-free.
        # adapter_testbed_smokes is NOT fired by generic examples/**
        # paths; only the orchestrator scripts under examples/scripts/
        # (matched above) fire it. The cljs_browser gate covers
        # CLJS-source regressions touched by examples/.
        cljs_browser=true
        ;;
      testbeds/*)
        # rf2-7vsfm + rf2-bxdk8 + rf2-cjp0i + rf2-9grp6 — Top-level
        # testbeds/* are compiled + staged by the framework-testbeds
        # orchestrator. Per rf2-cjp0i the adapter-testbed-smokes gate is
        # scoped to the 3 adapter smokes themselves; per rf2-9grp6 the
        # framework + top-level testbed Playwright surfaces are now
        # covered by the split-out `framework-testbeds` gate, which
        # fires on runtime-extension changes (.cljs/.cljc/.js/.cjs/.css/
        # .scss/.html/.json) under this tree. Markdown/EDN-only diffs
        # still skip the gate entirely. cljs_browser stays lit for CLJS-
        # source regressions in shared core/feature artefacts that the
        # testbed compiles transitively pull in.
        cljs_browser=true
        if is_framework_testbed_path "$file"; then
          framework_testbeds=true
        fi
        ;;
      tools/template/*)
        # rf2-os0c1 — tools/template is a clj-new template that scaffolds
        # new projects; it does not share runtime with causa/story/story-mcp/
        # mcp-base. The template_expensive gate fires jvm-tools-template
        # (its only PR-time job); tools_jvm would unnecessarily fire the
        # four sibling jvm-tools-* probes.
        template_expensive=true
        ;;
      tools/story/*|tools/causa/*)
        # rf2-os0c1 + rf2-k9ekz + rf2-9grp6 — Story / Causa changes
        # legitimately fan out to tools_jvm (per-artefact JVM unit tests
        # + sibling story-mcp consumer) and mcp_conformance (the MCP
        # wrappers consume these artefacts). story_causa_browser is now
        # narrowed (rf2-k9ekz): it fires ONLY when the changed path is
        # under tools/{story,causa}/{src,testbeds}/** AND the file has a
        # runtime extension (.cljs/.cljc/.js/.cjs/.css/.scss). Markdown
        # specs under tools/{story,causa}/spec/**, JVM unit tests under
        # tools/{story,causa}/test/**, deps.edn, README.md, and *.txt
        # do NOT fire it — they cannot affect chrome and so cannot
        # invalidate the Playwright gate. Per rf2-9grp6 the Causa-owned
        # framework testbeds under tools/causa/testbeds/** also fire
        # framework_testbeds (the split-out Playwright gate for the SSR
        # + framework-behaviour surfaces). Story's own testbeds drive
        # the story_causa_browser gate, not framework_testbeds.
        tools_jvm=true
        mcp_conformance=true
        if is_story_causa_runtime_path "$file"; then
          story_causa_browser=true
        fi
        if is_framework_testbed_path "$file"; then
          framework_testbeds=true
        fi
        ;;
      tools/story-mcp/*)
        # rf2-os0c1 — MCP wrappers don't run in a browser; story-causa-browser
        # exercises the Story/Causa CLJS runtimes via Playwright and is
        # noise for an MCP-wrapper-only diff. tools_jvm + mcp_conformance
        # cover the actual JVM probes (jvm-tools-story-mcp / wire-vocab) and
        # node integration tests.
        tools_jvm=true
        mcp_conformance=true
        ;;
      tools/re-frame2-pair-mcp/*|tools/mcp-base/*)
        # rf2-os0c1 — mcp-base is .cljc shared by every MCP server (rf2-vw4sq),
        # and re-frame2-pair-mcp ships as a Node binary plus a JVM mcp-base consumer.
        # tools_jvm picks up jvm-tools-mcp-base; mcp_conformance fires the
        # node + wire-vocab gates; mcp_live fires the re-frame2-pair live conformance.
        tools_jvm=true
        mcp_conformance=true
        mcp_live=true
        ;;
      tools/mcp-conformance/*)
        # rf2-os0c1 — mcp-conformance is JS test scripts plus the JVM
        # wire-vocab subdir. The wire-vocab JVM tests already run under
        # mcp-conformance-wire-vocab, which is gated by mcp_conformance.
        # Setting tools_jvm here would needlessly fire four unrelated
        # jvm-tools-* probes (causa/story/story-mcp/mcp-base).
        mcp_conformance=true
        mcp_live=true
        ;;
      skills/re-frame2-pair/tests/fixture/*)
        skills_structural=true
        mcp_conformance=true
        mcp_live=true
        ;;
      skills/re-frame2-pair/*|skills/shared/*)
        skills_structural=true
        ;;
    esac
  done <<< "$files"
fi

emit() {
  local key="$1"
  local value="$2"
  if [ -n "${GITHUB_OUTPUT:-}" ]; then
    printf '%s=%s\n' "$key" "$value" >> "$GITHUB_OUTPUT"
  else
    printf '%s=%s\n' "$key" "$value"
  fi
}

emit implementation_jvm "$implementation_jvm"
emit adapter_diagnostic "$adapter_diagnostic"
emit cljs_browser "$cljs_browser"
emit cljs_prod "$cljs_prod"
emit bundle_isolation "$bundle_isolation"
emit reagent_slim_bundle "$reagent_slim_bundle"
emit adapter_testbed_smokes "$adapter_testbed_smokes"
emit framework_testbeds "$framework_testbeds"
emit tools_jvm "$tools_jvm"
emit template_expensive "$template_expensive"
emit mcp_conformance "$mcp_conformance"
emit mcp_live "$mcp_live"
emit story_causa_browser "$story_causa_browser"
emit skills_structural "$skills_structural"
