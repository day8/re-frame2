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
cljs_node_test=false
adapter_diagnostic=false
cljs_browser=false
cljs_prod=false
bundle_isolation=false
reagent_slim_bundle=false
adapter_testbed_smokes=false
tools_jvm=false
template_expensive=false
mcp_conformance=false
mcp_live=false
story_xray_browser=false
skills_structural=false
playground=false

mark_all() {
  implementation_jvm=true
  cljs_node_test=true
  adapter_diagnostic=true
  cljs_browser=true
  cljs_prod=true
  bundle_isolation=true
  reagent_slim_bundle=true
  adapter_testbed_smokes=true
  tools_jvm=true
  template_expensive=true
  mcp_conformance=true
  mcp_live=true
  story_xray_browser=true
  skills_structural=true
  playground=true
}

# rf2-k9ekz — predicate: does `$1` look like a Story/Xray runtime
# source file (CLJS/CLJC/JS/CSS extension under tools/{story,xray}/src/**
# or tools/{story,xray}/testbeds/**)? Returns 0 (yes) / 1 (no). The
# Story/Xray browser gate only fires on a runtime-relevant extension
# under one of those two trees — Markdown specs, EDN config, and JVM
# unit tests under tools/{story,xray}/{spec,test,bench}/** do not
# affect chrome and so do not fire the gate. Testbeds under
# tools/{story,xray}/testbeds/** legitimately drive the
# story-feature-load + xray-feature-gate Playwright runners (their
# variant graph IS what the gate exercises), so a runtime-extension
# change there does fire the gate.
is_story_xray_runtime_path() {
  case "$1" in
    tools/story/src/*|tools/xray/src/*|tools/story/testbeds/*|tools/xray/testbeds/*)
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

# rf2-f79t8 — predicate: does `$1` compile into the consolidated
# `:node-test` build? shadow-cljs.edn lists tools/{story,xray}/{src,test}
# as :source-paths, so a CLJS/CLJC change under those trees changes the
# node-test output and MUST fire the `cljs` job (which is gated on
# cljs_node_test). Markdown specs (tools/{story,xray}/spec/**), EDN
# config, and JVM-only `.clj` files there do NOT compile into node-test
# and so must not fire it — the same spec-md-exclusion philosophy the
# runtime-extension guard applies to the Playwright gate.
is_story_xray_node_test_path() {
  case "$1" in
    tools/story/src/*|tools/xray/src/*|tools/story/test/*|tools/xray/test/*|tools/story/testbeds/*|tools/xray/testbeds/*)
      case "$1" in
        *.cljs|*.cljc)
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
        # rf2-8jz9t + rf2-k9ekz + rf2-t5slp — adapter_testbed_smokes
        # and story_xray_browser are NOT fired here. The Playwright
        # gates exist to catch surface-specific browser bugs (adapter
        # mount lifecycle, Story variant boot, Xray panel layout) —
        # none of which are core regressions. Core renames are caught
        # by node-test (consolidated CLJS unit + browser-test) which
        # exercises every public re-frame.core fn, and by the always-on
        # JVM core suite. A core rename that breaks an adapter mount or
        # Story variant boot silently is caught by the nightly cron +
        # post-merge gate (both run the full matrix on main).
        implementation_jvm=true
        cljs_node_test=true
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
        cljs_node_test=true
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
        cljs_node_test=true
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
        cljs_node_test=true
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
        # in core does too. The CLJS corpus runner
        # (conformance_corpus_cljs_test.cljs) is in the consolidated
        # :node-test build, so cljs_node_test fires too.
        implementation_jvm=true
        cljs_node_test=true
        cljs_browser=true
        cljs_prod=true
        ;;
      implementation/shadow-cljs.edn|implementation/package.json|implementation/package-lock.json|implementation/scripts/*)
        # rf2-8jz9t + rf2-bxdk8 + rf2-cjp0i + rf2-k9ekz + rf2-t5slp —
        # adapter_testbed_smokes and story_xray_browser are NOT fired
        # here. The Playwright gates are triggered ONLY by direct
        # source-tree changes (adapter source for adapter-testbed-
        # smokes; tools/{story,xray}/{src,testbeds}/** for the Story/
        # Xray browser gate). A shadow-cljs.edn or implementation/
        # scripts/ change that breaks the build is caught by the
        # nightly cron + post-merge gate (both run the full matrix on
        # main). shadow-cljs.edn + package-lock.json directly determine
        # the :node-test build, so cljs_node_test fires here too.
        cljs_node_test=true
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
        # rf2-7vsfm + rf2-t5slp — Top-level testbeds/* surfaces are
        # retained as Xray observation targets but no longer have a
        # paired Playwright spec.cjs; all framework + top-level testbed
        # specs migrated to CLJS/JVM unit tests under the four
        # rf2-tglku waves and the split-out `framework-testbeds` gate
        # was retired (rf2-t5slp). cljs_browser stays lit for CLJS-
        # source regressions in shared core/feature artefacts that the
        # testbed compiles transitively pull in.
        cljs_browser=true
        ;;
      tools/template/*)
        # rf2-os0c1 + rf2-40vmd — tools/template is a deps-new template
        # that scaffolds new projects (migrated from clj-new in rf2-dolpf
        # §2); it does not share runtime with xray/story/story-mcp/
        # mcp-base. The template_expensive gate fires jvm-tools-template
        # (its only PR-time job); tools_jvm would unnecessarily fire the
        # four sibling jvm-tools-* probes.
        template_expensive=true
        ;;
      tools/story/*|tools/xray/*)
        # rf2-os0c1 + rf2-k9ekz + rf2-t5slp + rf2-f79t8 — Story / Xray
        # changes legitimately fan out to tools_jvm (per-artefact JVM
        # unit tests + sibling story-mcp consumer) and mcp_conformance
        # (the MCP wrappers consume these artefacts).
        #
        # rf2-f79t8 — spec-md guard. A pure documentation change under
        # tools/{story,xray}/spec/**.md cannot affect any runtime, any
        # JVM unit test, or any MCP wire surface, so it must NOT fan out
        # to the JVM/MCP probes (jvm-tools-{xray,story,story-mcp},
        # node-test-tools-story-mcp, mcp-conformance-*). It is covered by
        # docs.yml + the nightly full matrix. Mirrors the
        # runtime-extension guard already applied to story_xray_browser
        # below (rf2-k9ekz). All NON-spec-md changes (src/test .clj/.cljs,
        # deps.edn, README, EDN, …) still fire the probes conservatively.
        case "$file" in
          tools/story/spec/*.md|tools/xray/spec/*.md)
            : # spec doc only — no runtime/JVM/MCP/CLJS fan-out
            ;;
          *)
            tools_jvm=true
            mcp_conformance=true
            ;;
        esac
        # story_xray_browser is narrowed (rf2-k9ekz): it fires ONLY when
        # the changed path is under tools/{story,xray}/{src,testbeds}/**
        # AND the file has a runtime extension
        # (.cljs/.cljc/.js/.cjs/.css/.scss). Markdown specs, JVM unit
        # tests under tools/{story,xray}/test/**, deps.edn, README.md,
        # and *.txt do NOT fire it. The split-out framework-testbeds
        # gate (formerly rf2-9grp6) was retired in rf2-t5slp.
        if is_story_xray_runtime_path "$file"; then
          story_xray_browser=true
        fi
        # rf2-f79t8 — the consolidated :node-test build lists
        # tools/{story,xray}/{src,test} as :source-paths (shadow-cljs.edn),
        # so a CLJS/CLJC change under those trees changes node-test output
        # and must fire the `cljs` job (gated on cljs_node_test). A
        # markdown / EDN / JVM-only `.clj` change does not compile into
        # node-test and so does not fire it.
        if is_story_xray_node_test_path "$file"; then
          cljs_node_test=true
        fi
        ;;
      tools/story-mcp/*)
        # rf2-os0c1 — MCP wrappers don't run in a browser; story-xray-browser
        # exercises the Story/Xray CLJS runtimes via Playwright and is
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
        # jvm-tools-* probes (xray/story/story-mcp/mcp-base).
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
      docs/tools/playground/*|docs/cljs/playground.js|docs/cljs/playground.css|docs/cljs/playground-rf2.js)
        # rf2-ee38b.22 — the docs/cljs live-cell playground (CM6 + Scittle
        # bootstrap + the re-frame2 SCI bundle). The tools-playground job
        # rebuilds both bundles, runs the headless-Chromium smoke against
        # them, and `git diff --exit-code`s the committed
        # docs/cljs/playground*.{js,css} so a stale vendored bundle (the
        # deploy artefact is committed verbatim, never rebuilt by docs.yml)
        # fails the PR. Fired by any docs/tools/playground/** change OR by
        # a hand-edit of one of the three committed bundles.
        playground=true
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
emit cljs_node_test "$cljs_node_test"
emit adapter_diagnostic "$adapter_diagnostic"
emit cljs_browser "$cljs_browser"
emit cljs_prod "$cljs_prod"
emit bundle_isolation "$bundle_isolation"
emit reagent_slim_bundle "$reagent_slim_bundle"
emit adapter_testbed_smokes "$adapter_testbed_smokes"
emit tools_jvm "$tools_jvm"
emit template_expensive "$template_expensive"
emit mcp_conformance "$mcp_conformance"
emit mcp_live "$mcp_live"
emit story_xray_browser "$story_xray_browser"
emit skills_structural "$skills_structural"
emit playground "$playground"
