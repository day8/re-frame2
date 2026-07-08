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
tenant_switcher_smoke=false
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
  tenant_switcher_smoke=true
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
      spec/api-manifest-metadata.edn|spec/api-manifest.edn|spec/API.md)
        # rf2-4ka7c2.1 — false-green fix. The CLJS-only adapter / Xray /
        # pair-MCP public surfaces cannot be `require`d on the JVM, so their
        # rows live in the sidecar (spec/api-manifest-metadata.edn) under
        # :cljs-only and the JVM generator carries them through VERBATIM into
        # spec/api-manifest.edn. The ONLY live runtime verifier for those rows
        # is the CLJS enumeration probe
        # (implementation/scripts/api-manifest/probe/, wired into the
        # consolidated :node-test build), which runs only when
        # cljs_node_test=true. A PR editing the sidecar / generated manifest /
        # API.md without touching implementation/, tools/, or shadow-cljs.edn
        # previously left cljs_node_test=false — so a stale/missing CLJS-only
        # row could ship with green CI (lint.yml runs only the JVM generator +
        # projection checks, not the CLJS probe). Fire cljs_node_test so the
        # probe reconciles the :cljs-only rows against the live CLJS public
        # vars on any sidecar/generated-manifest/API.md change.
        cljs_node_test=true
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
      implementation/adapters/reagent-slim/*|examples/substrates/reagent_slim/counter/*|implementation/scripts/check-reagent-slim-bundle-isolation.cjs)
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
      implementation/adapters/scripts/*)
        # The adapter-smoke harness (orchestrator + runner + shared
        # manifest) lives with the adapters it drives. A harness-script
        # edit drives the adapter-testbed-smokes job (via
        # `npm run test:adapter-smokes`) but does NOT change adapter source,
        # so it fires ONLY that gate — not the full adapter-source fan-out
        # the broad implementation/adapters/* case below triggers. This
        # mirrors the dedicated harness-script case the examples tree used
        # before the harness moved here. (spec-helpers.cjs / examples-port.cjs
        # / examples-staging.cjs still live under examples/scripts/ and have
        # their own cases there, since the example dev runner and the Story
        # launchers share them.)
        adapter_testbed_smokes=true
        ;;
      implementation/adapters/*)
        # rf2-bxdk8 + rf2-cjp0i — the adapter-testbed-smokes gate is
        # scoped to the 3 adapter smokes at
        # implementation/adapters/<reagent|uix|helix>/testbed/. Adapter
        # source changes are the canonical trigger; harness-script
        # changes are caught by the dedicated case above.
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
      examples/scripts/spec-helpers.cjs|examples/scripts/examples-port.cjs)
        # rf2-bxdk8 + rf2-cjp0i — the adapter-smoke harness moved to
        # implementation/adapters/scripts/ (its own case above), but two
        # of the helpers it imports still live under examples/scripts/
        # because the example dev runner and the Story launchers share
        # them: spec-helpers.cjs (the Playwright assertion matchers the
        # adapter specs use) and examples-port.cjs (rf2-y9o5e3 — the port
        # resolver the orchestrator's main() calls before any compile/serve;
        # a break there false-greens the adapter smoke gate). A change to
        # either still drives the adapter-testbed-smokes job. The rest of
        # examples/** is test-free per rf2-8cevm. (port-resolver.cjs is
        # shared with the Story launchers and is handled in its own case
        # below so it fires BOTH gates; examples-staging.cjs likewise.)
        adapter_testbed_smokes=true
        ;;
      examples/scripts/serve-and-run-story-feature-load-tests.cjs|examples/scripts/run-story-feature-load-tests.cjs|examples/scripts/serve-and-run-story-play-scripts.cjs|examples/scripts/story-feature-load-port.cjs)
        # rf2-y9o5e3 — the Story CI-as-test launchers + their dedicated
        # port resolver under examples/scripts/ are the executable
        # orchestration for `npm run test:story-feature-load` and
        # `npm run test:story-play-scripts`, both of which run under the
        # story-xray-browser PR job. A break in one of these launchers
        # (compile step, server staging, port resolution, runner spawn)
        # can break the Story browser gate, so editing one must fire that
        # gate — closing the false-green hole where the launcher could
        # break and still avoid the gate it drives.
        story_xray_browser=true
        ;;
      examples/scripts/port-resolver.cjs)
        # rf2-y9o5e3 — port-resolver.cjs is the shared free-port resolver
        # imported by BOTH examples-port.cjs (adapter smoke orchestrator)
        # and story-feature-load-port.cjs (Story launchers). A break here
        # affects every examples/scripts browser gate, so it fires BOTH
        # the adapter-testbed-smokes and the story-xray-browser gates.
        adapter_testbed_smokes=true
        story_xray_browser=true
        ;;
      examples/scripts/examples-staging.cjs)
        # rf2-eqjxya — false-green fix, mirroring port-resolver.cjs above.
        # examples-staging.cjs is the SHARED staging/cleaning helper (it owns
        # stageShared, cleanStageDirs, stageExample) require'd by BOTH browser
        # gate families: the adapter-smoke orchestrator
        # serve-and-run-adapter-smokes.cjs (`npm run test:adapter-smokes`,
        # adapter_testbed_smokes) imports stageShared + cleanStageDirs, AND the
        # two Story launchers — serve-and-run-story-feature-load-tests.cjs
        # (`npm run test:story-feature-load`) and
        # serve-and-run-story-play-scripts.cjs (`npm run test:story-play-scripts`),
        # both under story_xray_browser — import cleanStageDirs. A regression in
        # the staging/cleaning code (e.g. cleanStageDirs or the _shared fan-out)
        # can break the files those gates serve before they run, yet a PR
        # touching only this helper used to fall through to the generic
        # examples/* case below (cljs_browser only), skipping both Playwright
        # gates it underpins — a CI false-green for this slice. Fire BOTH gates,
        # exactly like the shared port-resolver.cjs case, so editing the shared
        # helper runs the browser gates that depend on it. (The dev runner
        # serve-example.cjs also imports it but is not a CI gate, so no extra
        # fan-out is warranted.)
        adapter_testbed_smokes=true
        story_xray_browser=true
        ;;
      implementation/epoch/*)
        # rf2-ribu5a — false-green fix. Epoch is the ONLY per-feature
        # artefact wired into a LIVE MCP conformance gate: the
        # re-frame2-pair live fixture
        # (skills/re-frame2-pair/tests/fixture/deps.edn) resolves
        # `day8/re-frame2-epoch` as a :local/root, and the hermetic
        # `mcp-conformance-re-frame2-pair` job's INNER_TESTS include
        # `live-re-frame2-pair-redaction.cjs` — the egress-protection
        # regression net for the pull-mode epoch tools (trace-window /
        # watch-epochs) that asserts a declared-sensitive epoch is
        # WHOLE-DROPPED gate-OFF / shipped gate-ON across the MCP wire
        # (rf2-q4o83 / rf2-5613h). That job is gated on mcp_live='true'.
        # Folded into the generic per-feature bucket below, an
        # `implementation/epoch/src/...` change set NEITHER mcp_live NOR
        # mcp_conformance — so a PR that broke the epoch egress/redaction
        # contract merged GREEN at PR time (a conformance false-green on
        # a DATA-LEAK guard), surfacing only in the nightly cron. Split
        # out here so epoch source changes arm the live redaction gate.
        # All the generic per-feature gates still fire (epoch is not in
        # the scaffold, so no template_expensive; not baked into the SCI
        # bundle, so no playground).
        implementation_jvm=true
        cljs_node_test=true
        cljs_browser=true
        cljs_prod=true
        bundle_isolation=true
        mcp_conformance=true
        mcp_live=true
        ;;
      implementation/schemas/*|implementation/machines/*|implementation/routing/*|implementation/flows/*|implementation/http/*|implementation/ssr/*|implementation/ssr-ring/*|implementation/resources/*|implementation/security/*|implementation/deps.edn)
        # rf2-8jz9t — adapter_testbed_smokes NOT fired here. Per-feature
        # artefact changes are covered by their own JVM + CLJS unit
        # suites (implementation_jvm, cljs_browser, cljs_prod) and by
        # bundle_isolation; the adapter smokes under
        # adapter-testbed-smokes only catch adapter-mount-specific bugs
        # (createRoot lifecycle, hydration, real concurrent scheduling).
        # Nightly + post-merge gate runs the full matrix.
        #
        # rf2-dxndhc — implementation/resources/* (the EP-0003
        # day8/re-frame2-resources artefact, on the root CLJS/test
        # classpath via implementation/deps.edn + shadow-cljs.edn) was
        # NOT routed here before: a PR touching only resources/* left
        # every output false, so the aggregator could pass with the
        # jvm-resources suite + the consolidated :node-test resources
        # surface both skipped. It is a published, src-carrying
        # per-feature artefact exactly like schemas/ssr/…, so it gets the
        # full per-feature treatment (implementation_jvm fires the
        # jvm-resources job added in test.yml; cljs_node_test/cljs_browser/
        # cljs_prod/bundle_isolation cover its CLJS surface + isolation).
        implementation_jvm=true
        cljs_node_test=true
        cljs_browser=true
        cljs_prod=true
        bundle_isolation=true
        # rf2-jdj17.1 — false-green fix. The template's generated `:app`
        # build compiles `day8/re-frame2-schemas` (events.cljs side-loads
        # re-frame.schemas + re-frame.schemas.malli; schema.cljs calls
        # rf/reg-app-schema). The ONLY PR-time gate that compiles the
        # emitted `:app` (emitted_test_run_test, RF2_TEMPLATE_RUN_EMITTED_TESTS)
        # fires only under template_expensive. So a PR breaking the
        # re-frame.schemas / reg-app-schema surface used to merge GREEN at
        # PR time and surface only in the nightly cron. Fire
        # template_expensive for an implementation/schemas/* change so
        # jvm-tools-template runs the emitted-app compile at PR time.
        # (The other per-feature artefacts here — machines/routing/flows/
        # http/ssr/ssr-ring — are NOT pulled into today's scaffold,
        # so they do not arm template_expensive; add them here if a future
        # scaffold flag wires one in. epoch has its own case above,
        # rf2-ribu5a.)
        case "$file" in
          implementation/schemas/*) template_expensive=true ;;
        esac
        # rf2-2h1yhk — SCI-bundle freshness. The docs/cljs live-cell
        # playground vendors a prebuilt shadow-cljs bundle
        # (docs/cljs/playground-rf2.js) that BAKES IN the machines artefact
        # (re-frame.machines is :require'd by the SCI build, and the bundle
        # carries the reserved :rf.machine/* lifecycle keywords). That bundle
        # is committed + deployed verbatim, never rebuilt by docs.yml — so a
        # rename of a reserved machine keyword in implementation/machines/**
        # (e.g. :rf.machine/bootstrap -> :rf.machine/start) leaves the
        # deployed bundle silently stale, with NO surface here firing the
        # playground gate. Fire `playground` for any implementation/machines/*
        # change so the tools-playground job runs the freshness guard
        # (scripts/check-playground-sci-freshness.sh) against the committed
        # bundle and fails the PR if it is out of sync. (Core / reagent-slim
        # are also baked in but are not scoped here: the guard's contract is
        # the machines lifecycle marker, and firing playground on every core
        # change would add a heavy JVM+Playwright job to most PRs. The nightly
        # full matrix covers the broader drift.)
        case "$file" in
          implementation/machines/*) playground=true ;;
        esac
        ;;
      implementation/reply-conformance/*|implementation/derivation-conformance/*|implementation/event-conformance/*)
        # rf2-dxndhc — the three EP cross-conformance tiers
        # (reply-conformance / derivation-conformance / event-conformance)
        # are src-less `.cljc` TEST surfaces on the root test classpath
        # (implementation/deps.edn + shadow-cljs.edn list them). They run
        # in BOTH runtimes: the always-on consolidated `:node-test` gate
        # (the `cljs` job, gated on cljs_node_test) runs the `:cljs` arms,
        # and each tier's own `:test` alias runs the SAME `.cljc`
        # namespaces under the JVM to exercise the `:clj` reader-conditional
        # arms (the JVM jobs added in test.yml, gated on implementation_jvm;
        # mirrors the security tier). Before rf2-dxndhc a PR touching only
        # one of these tiers left every output false, so the aggregator
        # could pass with both the JVM `:clj`-arm job AND the consolidated
        # node-test surface skipped. They ship NO production src (no Maven
        # artefact), so they do NOT widen any production bundle — hence no
        # cljs_browser / cljs_prod / bundle_isolation here (the security
        # tier, the precedent, is likewise off those).
        implementation_jvm=true
        cljs_node_test=true
        ;;
      implementation/test-quiet/src/*|implementation/test-quiet/test/*|implementation/test-quiet/deps.edn)
        # rf2-am7grp — implementation/test-quiet is the test-runtime
        # quiet-reporter artefact (day8/re-frame2-test-quiet, rf2-try1x):
        # the JVM runner (re-frame.test-quiet.runner, runner.clj — the
        # `:main-opts` of EVERY per-artefact `:test` alias) AND the CLJS
        # shadow-node runner (re-frame.test-quiet.shadow-node, the
        # `:node-test` build's `:main` in implementation/shadow-cljs.edn).
        # Before this case the classifier had NO rule for test-quiet/**,
        # so a PR changing the quiet reporter implementation, its runners,
        # its deps.edn, or its contract tests left every output false — the
        # JVM quiet-runner contract (test_quiet_runner_contract_test.clj,
        # test_quiet_pin*_test.clj) and the CLJS node-test quiet reporter
        # contract (test_quiet_shadow_node_cljs_test.cljs, the green/red
        # fixture cljs tests) BOTH skipped, leaving js-harness-self-tests
        # (which only runs the JS script policy/helper tests, never the
        # quiet reporter's own :test or the consolidated :node-test pins)
        # as the sole — insufficient — verifier.
        #
        # Fire implementation_jvm so the per-artefact JVM `:test` aliases
        # (which route through the quiet runner) re-run the runner contract
        # at PR time, and cljs_node_test so the consolidated `:node-test`
        # build (whose `:main` IS the CLJS quiet runner) exercises the
        # shadow-node reporter + its contract tests. Scoped to src/test/
        # deps.edn only; no broadening onto unrelated docs/spec paths and
        # no reliance on js-harness-self-tests as the sole verifier.
        implementation_jvm=true
        cljs_node_test=true
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
      implementation/scripts/serve-and-run-reagent-slim-smoke.cjs|implementation/scripts/_reagent-slim-smoke-policy.test.cjs)
        # rf2-5v0dg7 — false-green fix, mirroring the xray-feature-gate
        # launcher case below. serve-and-run-reagent-slim-smoke.cjs IS the
        # executable orchestration for `npm run test:reagent-slim:smoke`,
        # the command the cljs-reagent-slim-bundle-isolation PR job now runs
        # (.github/workflows/test.yml). A break in this launcher (compile,
        # staging, port resolution, ownership-token readiness, the browser
        # drive) can break the slim client-runtime smoke gate — yet the
        # generic implementation/scripts/* case below routes only to the
        # always-on JS/CLJS surfaces and NEVER to reagent_slim_bundle, so a
        # PR editing this launcher (or its policy test) could break the very
        # gate it drives while avoiding it (a false-green hole). Fire
        # reagent_slim_bundle so editing the launcher runs the gate it
        # orchestrates. The remaining static-script surfaces this file shares
        # with the generic case (cljs_node_test / cljs_browser / cljs_prod /
        # bundle_isolation) stay armed too — this case widens coverage, it
        # does not narrow it.
        cljs_node_test=true
        cljs_browser=true
        cljs_prod=true
        bundle_isolation=true
        reagent_slim_bundle=true
        ;;
      implementation/scripts/serve-and-run-xray-feature-gate.cjs)
        # rf2-rcepku — false-green fix, mirroring rf2-y9o5e3 for the
        # examples/scripts launchers. This launcher IS the executable
        # orchestration for `npm run test:xray-feature-gate:smoke`, the
        # command the story-xray-browser PR job runs (.github/workflows/
        # test.yml). A break in this launcher (compile step, surface
        # staging, port resolution, server staging, the external-base-URL
        # readiness probe) can break the Xray PR-smoke gate — yet the
        # generic implementation/scripts/* case below routes only to the
        # always-on JS/CLJS surfaces and NEVER to story_xray_browser, so a
        # PR editing this launcher could break the very gate it drives
        # while avoiding it (a false-green hole). Fire story_xray_browser
        # so editing the launcher runs the gate it orchestrates. The
        # remaining static-script surfaces this file shares with the
        # generic case (cljs_node_test / cljs_browser / cljs_prod /
        # bundle_isolation / reagent_slim_bundle) stay armed too — this
        # case widens coverage, it does not narrow it.
        cljs_node_test=true
        cljs_browser=true
        cljs_prod=true
        bundle_isolation=true
        reagent_slim_bundle=true
        story_xray_browser=true
        ;;
      implementation/scripts/serve-and-run-tenant-switcher-testbed.cjs)
        # rf2-h5e3v7 — false-green fix, mirroring the xray-feature-gate +
        # reagent-slim-smoke launcher cases above. This launcher IS the
        # executable orchestration for `npm run test:testbed-tenant-switcher`,
        # the command the tenant-switcher-testbed-smoke PR job runs
        # (.github/workflows/test.yml). It is the ONLY Playwright smoke that
        # exercises the tenant-switcher browser scenario (compile / index.html
        # staging / ownership-token server readiness / pageerror handling). A
        # break in this launcher — or in the colocated testbed it drives —
        # could ship green, since the generic implementation/scripts/* case
        # below routes only to the always-on JS/CLJS surfaces and never to
        # tenant_switcher_smoke. Fire tenant_switcher_smoke so editing the
        # launcher runs the gate it orchestrates. The remaining static-script
        # surfaces this file shares with the generic case (cljs_node_test /
        # cljs_browser / cljs_prod / bundle_isolation / reagent_slim_bundle)
        # stay armed too — this case widens coverage, it does not narrow it.
        cljs_node_test=true
        cljs_browser=true
        cljs_prod=true
        bundle_isolation=true
        reagent_slim_bundle=true
        tenant_switcher_smoke=true
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
        # rf2-6yuzo4 — template npm-pin lockstep. The template's hooks.clj
        # pins :shadow-version + :react-version, and version_lockstep_test
        # asserts those emitted pins match implementation/package.json's
        # react / react-dom / shadow-cljs entries (the source of truth).
        # The emitted-app smoke (emitted_test_run_test, the ONLY PR-time
        # gate that compiles + runs the generated project) symlinks
        # implementation/node_modules — populated from
        # implementation/package-lock.json — into the scaffold so React
        # resolves. So a PR that bumps those package.json pins, or the
        # lockfile the smoke links against, must run jvm-tools-template:
        # otherwise the template keeps emitting a stale pin (or links a
        # drifted node_modules) and merges GREEN while breaking the
        # lockstep contract + the advertised smoke-tested combination.
        # Scoped to package.json + the lockfile; shadow-cljs.edn and
        # implementation/scripts/* don't carry the emitted npm pins, so
        # they stay off template_expensive (the nightly full matrix
        # covers any build-config drift they can introduce).
        case "$file" in
          implementation/package.json|implementation/package-lock.json)
            template_expensive=true ;;
        esac
        ;;
      examples/*)
        # rf2-bxdk8 + rf2-cjp0i + rf2-8cevm — examples/** is test-free.
        # adapter_testbed_smokes is NOT fired by generic examples/**
        # paths; only the orchestrator scripts under examples/scripts/
        # (matched above) fire it. The cljs_browser gate covers
        # CLJS-source regressions touched by examples/.
        #
        # rf2-8ckcf2 — false-green fix (ROOT CAUSE of the #5353 main-red).
        # examples/ carries no tests of its own, BUT example PRODUCTION
        # source sits on the consolidated :node-test classpath
        # (implementation/shadow-cljs.edn lists ../examples/core,
        # ../examples/real-apps, ../examples/capabilities/*, … as
        # :source-paths), and framework/adapter test namespaces may
        # `:require` it. The reagent-adapter test
        # re-frame.realworld-resources-cljs-test
        # (implementation/adapters/reagent/test/re_frame/realworld_resources_cljs_test.cljs
        # — its ns matches the :node-test build's `cljs-test$` ns-regexp)
        # `:require`s realworld-resources.core + realworld-resources.scope
        # from examples/real-apps/realworld_resources/. So an examples-only
        # change can break a :node-test test, yet firing only cljs_browser
        # left the `cljs` job (gated on cljs_node_test) SKIPPED: PR #5353
        # (an examples/real-apps editor fix) merged green and turned main
        # red undetected. Fire cljs_node_test on ANY examples change so the
        # consolidated node-test suite re-runs the coupled tests. Broad
        # (whole examples/ tree) rather than examples/real-apps-only — the
        # classifier is deliberately conservative, other example-coupled
        # tests may exist or be added, node-test is the fast default gate,
        # and an examples change that no test `:require`s simply recompiles
        # nothing new (harmless). (examples/scripts/*.cjs orchestration
        # helpers are matched by their own earlier cases and stay scoped to
        # the browser gates — they are not CLJS source any node-test
        # `:require`s.)
        cljs_browser=true
        cljs_node_test=true
        ;;
      testbeds/tenant_switcher/*)
        # rf2-h5e3v7 — the tenant-switcher testbed is the ONE top-level
        # testbed that legitimately keeps its own colocated Playwright
        # spec.cjs (a cross-cutting framework smoke per CLAUDE.md
        # "framework testbeds carry their own non-adapter spec.cjs"). Its
        # runner serve-and-run-tenant-switcher-testbed.cjs drives
        # testbeds/tenant_switcher/spec.cjs against the compiled
        # :testbeds/tenant-switcher build + staged index.html. So a change
        # to the testbed's core.cljs / spec.cjs / index.html must fire the
        # tenant-switcher-testbed-smoke gate (else the runner's only live
        # browser coverage can be avoided). cljs_browser stays lit too for
        # the transitive CLJS-source coverage every other testbed gets.
        cljs_browser=true
        tenant_switcher_smoke=true
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
            : # spec doc only — no runtime/JVM/MCP/CLJS/template fan-out
            ;;
          *)
            tools_jvm=true
            mcp_conformance=true
            # rf2-jdj17.1 — false-green fix. The template's generated
            # `:app` build compiles against Story + Xray: the with-story
            # scaffold's core requires re-frame.story and calls
            # story/mount-shell! / unmount-shell!, and EVERY scaffold wires
            # day8.re-frame2-xray.preload via :devtools/preloads in
            # shadow-cljs.edn. The ONLY PR-time gate that compiles the
            # emitted `:app` (emitted_test_run_test,
            # RF2_TEMPLATE_RUN_EMITTED_TESTS) fires only under
            # template_expensive — so a PR breaking the re-frame.story
            # shell API or renaming/removing the xray preload ns used to
            # merge GREEN at PR time and surface only in the nightly cron
            # (generated apps then fail their first `shadow-cljs watch app`
            # / `release app`). Fire template_expensive for any non-spec-md
            # Story/Xray change so jvm-tools-template runs the emitted-app
            # compile at PR time. Scoped exactly like tools_jvm/mcp above:
            # a pure spec-md change can't break the generated `:app`
            # compile, so it stays excluded.
            template_expensive=true
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
      tools/machines-viz/*)
        # rf2-z0cw6s — tools/machines-viz ships day8/re-frame2-machines-viz
        # (the MachineChart component + read-only viewer + Mermaid/SCXML/
        # PNG/SVG/share-URL export surfaces, rf2-o9arp). It is a CLJS-only
        # tool (no JVM unit tests, no MCP wrapper): its src+test are listed
        # as :source-paths of the consolidated :node-test AND :browser-test
        # builds (implementation/shadow-cljs.edn). So a CLJS/CLJC change
        # must fire BOTH the `cljs` (node-test) job and the `cljs-browser`
        # job — the latter runs the `*-dom-cljs-test` export/redaction +
        # chart DOM suites under headless Chromium (where EP-0015 image-
        # export egress is verified). No tools_jvm / mcp_conformance /
        # template_expensive fan-out: machines-viz has no JVM suite, is not
        # consumed by an MCP wrapper, and is not part of the deps-new
        # template's generated app.
        #
        # spec-md guard (mirrors story/xray above): a pure documentation
        # change under tools/machines-viz/spec/**.md cannot affect any
        # compiled output, so it fires nothing runtime — covered by
        # docs.yml + the nightly full matrix.
        case "$file" in
          tools/machines-viz/spec/*.md)
            : # spec doc only — no runtime/CLJS fan-out
            ;;
          *)
            # Every non-spec-md change (src/test .cljs/.cljc, deps.edn,
            # public/viewer.html, README, EDN) conservatively fires the
            # node-test + browser gates. The node-test build picks up the
            # *_cljs_test suites; the browser build picks up the
            # *-dom-cljs-test export/chart-DOM suites.
            cljs_node_test=true
            cljs_browser=true
            ;;
        esac
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
      skills/re-frame2/references/tooling/story-mcp-loop.md)
        # rf2-jbwraa — code↔skill drift ratchet routing. The
        # tools/story-mcp JVM test `skill-leaf-tool-names-match-registry`
        # (tools_test.clj) PINS this leaf's prose catalogue against the
        # live story-mcp tool registry (slurps ../../skills/re-frame2/
        # references/tooling/story-mcp-loop.md). That gate fires only under
        # `jvm-tools-story-mcp` (gated by tools_jvm) — which previously ran
        # ONLY when tools/story-mcp/** changed, NOT when this cross-checked
        # leaf changed. So a leaf edit that desynced the ratchet merged
        # green and left main latently RED (rf2-r2xswa/#3637). Route a
        # change to this specific leaf to tools_jvm + mcp_conformance,
        # mirroring the tools/story-mcp/* case, so the ratchet runs at PR
        # time. Scoped to the single pinned path — no broader skills/
        # re-frame2/** fan-out.
        tools_jvm=true
        mcp_conformance=true
        ;;
      skills/re-frame2-pair/tests/fixture/*)
        skills_structural=true
        mcp_conformance=true
        mcp_live=true
        ;;
      skills/re-frame2-pair/*|skills/shared/*)
        skills_structural=true
        ;;
      skills/re-frame2-setup/*)
        # rf2-agi57x — the re-frame2-setup skill carries a structural drift
        # guard (tests/setup_drift_test.clj) gated under skills-structural.
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
emit tenant_switcher_smoke "$tenant_switcher_smoke"
emit skills_structural "$skills_structural"
emit playground "$playground"
