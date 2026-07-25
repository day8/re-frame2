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
  # rf2-vxgfnd.137 — `--no-renames` is load-bearing, not cosmetic. Git rename
  # detection collapses a rename to its DESTINATION path only, so a pure rename
  # OUT of a classified surface (e.g. implementation/ui/** -> docs/**) would
  # report only the unclassified destination and leave every gate for the
  # DELETED endpoint false — a CI false-green (the deleted production UI code
  # runs no browser/UI/JVM/node gate). Disabling rename detection makes Git emit
  # both endpoints (old path as a deletion, new path as an addition), so the
  # classifier below arms the gates for BOTH surfaces. Every rename is thus
  # classified as delete + add; ordinary add/modify/delete are unaffected.
  files="$(git diff --no-renames --name-only "origin/${GITHUB_BASE_REF}...HEAD")"
else
  files="$(git diff --no-renames --name-only HEAD^ HEAD 2>/dev/null || git diff --no-renames --name-only HEAD)"
fi

implementation_jvm=false
cljs_node_test=false
ui_gates=false
adapter_diagnostic=false
cljs_browser=false
examples_compile=false
cljs_prod=false
bundle_isolation=false
reagent_slim_bundle=false
freehand_evidence_elision=false
freehand_reachability=false
adapter_testbed_smokes=false
ui_smoke=false
tools_jvm=false
# rf2-wq17m — two artefacts with a wired `:test` alias and a slot on
# scripts/test-jvm-tools.sh's roster, but no PR-time CI lane until now. They get
# their OWN outputs rather than joining `tools_jvm`: that output gates FOUR
# jvm-tools-* jobs (xray / story / story-mcp / mcp-base), none of which runs
# either artefact, so setting it would fire four unrelated probes and STILL skip
# the files these outputs exist to reach.
tools_jvm_machines_viz=false
tools_jvm_testbed_support=false
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
  ui_gates=true
  adapter_diagnostic=true
  cljs_browser=true
  examples_compile=true
  cljs_prod=true
  bundle_isolation=true
  reagent_slim_bundle=true
  freehand_evidence_elision=true
  freehand_reachability=true
  adapter_testbed_smokes=true
  ui_smoke=true
  tools_jvm=true
  tools_jvm_machines_viz=true
  tools_jvm_testbed_support=true
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

    # rf2-gzavkm — compile every standalone example build only when the
    # changed surface can alter one of those builds. This is deliberately
    # separate from `cljs_browser`: core still gets its focused browser/node/
    # production gates at PR time, while the full example-build sweep moves
    # to the nightly safety net for core-only changes. Story/Xray and
    # machines-viz remain here because example builds load the Xray preload
    # and Story hosts, whose compiled closure includes machines-viz.
    case "$file" in
      examples/*|implementation/adapters/*|implementation/epoch/*|implementation/schemas/*|implementation/machines/*|implementation/routing/*|implementation/flows/*|implementation/http/*|implementation/ssr/*|implementation/ssr-ring/*|implementation/resources/*|implementation/security/*|implementation/ui/*|implementation/deps.edn|implementation/shadow-cljs.edn|implementation/package.json|implementation/package-lock.json|implementation/scripts/check-examples-compile.cjs)
        examples_compile=true
        ;;
      tools/story/src/*|tools/story/testbeds/*|tools/story/deps.edn|tools/xray/src/*|tools/xray/testbeds/*|tools/xray/deps.edn|tools/machines-viz/src/*|tools/machines-viz/deps.edn)
        examples_compile=true
        ;;
      # rf2-k8yl5f — the re-frame2-pair skill ships a dev-only preload
      # (skills/re-frame2-pair/preload/re_frame2_pair/{runtime.cljs,pure.cljc})
      # that shadow-cljs.edn adds as a :source-path
      # (../skills/re-frame2-pair/preload) and injects into ~28 :examples/*
      # dev builds via `:devtools :preloads [re-frame2-pair.runtime ...]`. A
      # `compile` (unlike `release`) HONOURS :devtools/preloads, so the
      # examples-compile coverage gate (npm run test:examples-compile →
      # shadow-cljs compile) actually compiles this preload for every build
      # that wires it — a preload-only change can therefore break that gate.
      # But the classifier previously armed ONLY skills_structural on this path
      # (the skills/re-frame2-pair/* case below), leaving examples_compile=false
      # so a preload break surfaced only in the unconditional nightly
      # examples-compile net (rf2-gzavkm follow-up), never at PR time. Arm
      # examples_compile so the PR-time gate recompiles the example dev builds
      # that inject it. (skills_structural still fires via the later
      # skills/re-frame2-pair/* case; this widens coverage, it does not narrow
      # it.)
      skills/re-frame2-pair/preload/*)
        examples_compile=true
        ;;
    esac

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
        # rf2-vxgfnd.209 — G-13 (cljs-ui-g13) is the end-to-end MOUNTED
        # falsifier for re-frame.ui push economics (05 §3), and it traverses
        # core dispatch/drain, the router, frame scheduling, the observation
        # port, ViewCell enrolment, uSES, compiled bodies, and the React
        # commit. A change to any implementation/core/* runtime source (the
        # observation port, router drain, the frame scheduler, …) can introduce
        # V-wide fan-out or split the write/read batching G-13 exists to catch,
        # yet the gate ran only under ui_gates — false for a core-only PR — so
        # the required-check aggregator accepted the skipped job. Arm ui_gates
        # for the whole core surface (a conservative superset; a narrower
        # explicit dependency list would be brittle as core evolves) so
        # cljs-ui-g13 runs for any core runtime change. Docs/spec/tool-only PRs
        # never reach this case and keep their existing skip.
        ui_gates=true
        # rf2-tzy13 — the docs/cljs live-cell SCI bundle BAKES IN re-frame2
        # core. The bundle is no longer committed (it is generated in CI and on
        # docs deploy), so there is no committed snapshot to go stale — but the
        # tools-playground job is now the ONLY PR-time proof that current core
        # source still compiles into a working SCI bundle and renders live under
        # headless Chromium. Fire it for any core change. (Before this ruling
        # the cost trade-off ran the other way: the job was scoped out of core
        # PRs precisely because a core change forced a bundle REBUILD + RECOMMIT,
        # which is exactly the write lock this bead retired.)
        playground=true
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
        # rf2-tzy13 — reagent-slim is the substrate baked into the docs/cljs SCI
        # bundle (re-frame.adapter.reagent-slim + reagent2.*), so an adapter
        # source change must prove the bundle still builds + renders. Scoped to
        # the adapter source only: the examples/ counter and the bundle-isolation
        # script above share this arm but are NOT baked in, and must not drag a
        # heavy JVM+Playwright job onto their PRs.
        case "$file" in
          implementation/adapters/reagent-slim/*) playground=true ;;
        esac
        ;;
      implementation/adapters/scripts/*)
        # The adapter-smoke harness (orchestrator + runner + shared
        # manifest) lives with the adapters it drives. A harness-script
        # edit drives the adapter-testbed-smokes job AND the ui-smoke job
        # (both run `npm run test:adapter-smokes` over the shared
        # ADAPTER_SMOKES manifest — rf2-nojiwy) but does NOT change adapter
        # or substrate source, so it fires ONLY those gates — not the full
        # adapter-source fan-out the broad implementation/adapters/* case
        # below triggers. This mirrors the dedicated harness-script case
        # the examples tree used before the harness moved here.
        # (spec-helpers.cjs / examples-port.cjs / examples-staging.cjs
        # still live under examples/scripts/ and have their own cases
        # there, since the example dev runner and the Story launchers
        # share them.)
        adapter_testbed_smokes=true
        ui_smoke=true
        ;;
      implementation/adapters/*)
        # rf2-bxdk8 + rf2-cjp0i — the adapter-testbed-smokes gate is
        # scoped to the 2 adapter smokes at
        # implementation/adapters/<reagent|uix>/testbed/. Adapter
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
        # ui_smoke fires too (rf2-nojiwy): the re-frame.ui smoke rides the
        # same orchestrator, so a break in these helpers breaks it
        # identically.
        adapter_testbed_smokes=true
        ui_smoke=true
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
        # affects every examples/scripts browser gate, so it fires the
        # adapter-testbed-smokes, ui-smoke (rf2-nojiwy — same
        # orchestrator), and story-xray-browser gates.
        adapter_testbed_smokes=true
        ui_smoke=true
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
        # fan-out is warranted.) ui_smoke fires too (rf2-nojiwy): the
        # re-frame.ui smoke's staging rides the same helper.
        adapter_testbed_smokes=true
        ui_smoke=true
        story_xray_browser=true
        ;;
      examples/scripts/examples-asset-manifest.cjs)
        # rf2-78th1g — false-green fix, mirroring the examples-staging.cjs
        # case above. examples-asset-manifest.cjs is the SINGLE side-effect-free
        # owner of every examples external-asset EXCEPTION (rf2-phpbo8): what
        # extra static asset each departing example stages and which _shared
        # asset it may omit. The staging helper examples-staging.cjs require's
        # its `stagedAssetsByBuild` projection to decide what to STAGE before
        # the browser gates run — and examples-staging.cjs itself fires BOTH
        # browser-gate families (adapter_testbed_smokes via the adapter-smoke
        # orchestrator, story_xray_browser via the two Story launchers). So a
        # regression in the manifest data or its projection can break the files
        # those gates serve, yet a PR touching only the manifest used to fall
        # through to the generic examples/* case below (cljs_browser +
        # cljs_node_test), skipping both Playwright gates it underpins — a CI
        # false-green for this slice. Fire BOTH gates, exactly like the shared
        # examples-staging.cjs case, so editing the manifest runs the browser
        # gates that depend on it. (The static asset scanner
        # check-examples-assets.cjs also require's `pageExemptions`, but it is
        # not a CI gate, so no extra fan-out is warranted.) ui_smoke fires
        # too (rf2-nojiwy): the re-frame.ui smoke's staged output rides the
        # same manifest-driven staging.
        adapter_testbed_smokes=true
        ui_smoke=true
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
        # rf2-2h1yhk / rf2-tzy13 — the docs/cljs live-cell SCI bundle BAKES IN
        # the machines and flows artefacts (`re-frame.machines` +
        # `re-frame.flows` are :require'd at bundle init, and the bundle carries
        # the reserved :rf.machine/* lifecycle keywords). rf2-tzy13 retired the
        # COMMITTED bundle — it is generated in CI and on docs deploy now — so
        # this arm no longer guards a snapshot against staleness. What it guards
        # instead is composition: a reserved-keyword rename or a late-bind hook
        # change here can make the bundle fail to build, or build and then throw
        # at render, and tools-playground is the PR-time proof that it still
        # builds + renders live under headless Chromium. flows was omitted
        # before (rf2-nyjml finding) even though it is in the require graph and
        # in the digest roster; it fires now.
        case "$file" in
          implementation/machines/*|implementation/flows/*) playground=true ;;
        esac
        ;;
      implementation/ui/*)
        # rf2-vxgfnd.6 — the re-frame.ui compiled-view substrate (epic
        # rf2-vxgfnd). Before this case a ui-only PR left every output
        # false: the jvm-ui suite, the consolidated :node-test ui
        # surface (incl. the S1f parity corpus) AND the G-1/G-14 gates
        # all skipped — a false-green hole for the whole artefact.
        # implementation_jvm fires jvm-ui (canonical trees + N
        # invariants + G-14 compile budget); cljs_node_test fires the
        # consolidated node suite (the live cross-emitter parity
        # corpus rides it); ui_gates fires the cljs-ui-g1 bench gate
        # (test.yml).
        #
        # rf2-vxgfnd.90 — false-green fix. re-frame.ui now ships REAL DOM
        # tests: the `*-dom-cljs-test.{cljs,cljc}` namespaces (the S1c/S2
        # mount + reactivity + frame-scope keystone fixtures) opt into the
        # `:browser-test` build (headless Chromium) and are the ONLY place
        # React act discipline, real react-dom/client roots, and live
        # ViewCell teardown are exercised — none of which the JVM/node
        # suites can validate. The stale "no production build :requires
        # re-frame.ui.* yet, so no cljs_browser" reasoning conflated a
        # PRODUCTION-bundle surface (still absent) with a browser-TEST
        # surface (now present): a test-only UI PR (e.g. #5767, which
        # changed exactly one `*-dom-cljs-test`) merged GREEN while its
        # only relevant browser test reported SKIPPED. Fire cljs_browser
        # for EVERY implementation/ui/** source or test change — UI runtime
        # changes affect those DOM tests transitively, and the conservative
        # direction is to trigger the gate MORE (worst case: slower CI),
        # never to skip it (worst case: a false-green).
        #
        # rf2-vxgfnd.12.2 — the mounted ViewCell now consumes the Story
        # override React carriage, and its zero-production-residue contract is
        # exercised by a generated/mounted `:advanced` prod-elision test.
        # re-frame.ui therefore HAS a release-probe-covered production surface:
        # every UI change must run cljs_prod.  It also ships as its own artifact,
        # so keep the generic bundle-boundary checks armed alongside the focused
        # ui adapter isolation step.
        #
        # rf2-nojiwy — the four-suites rule's new-UI smoke. The re-frame.ui
        # testbed (implementation/ui/testbed/) and the substrate runtime it
        # mounts both live under this tree, so any implementation/ui/**
        # change fires the ui-smoke browser gate — the direct-source-change
        # trigger discipline the adapter smokes use (adapter source →
        # adapter_testbed_smokes). Core changes deliberately do NOT fire it,
        # per the same rf2-8jz9t reasoning: the smoke catches
        # substrate-mount bugs, not core regressions.
        implementation_jvm=true
        cljs_node_test=true
        ui_gates=true
        cljs_browser=true
        cljs_prod=true
        bundle_isolation=true
        ui_smoke=true
        ;;
      implementation/freehand/src/re_frame/freehand/evidence.cljc|implementation/freehand/src/re_frame/freehand/cell.cljc|implementation/freehand/src/re_frame/freehand/occurrences.cljc|implementation/freehand/src/re_frame/freehand/shell.cljs|implementation/freehand/test/re_frame/freehand/release_app.cljs)
        # rf2-xwa4n — the F4g evidence-elision gate's Freehand PRODUCER
        # surfaces. `npm run test:freehand-evidence-elision` (rf2-drpa3.166)
        # builds `:freehand-release` and its goog.DEBUG=true control twin
        # `:freehand-release-control` and proves the dev-gated occurrence-record
        # seam — and the `re-frame.freehand.evidence` schema it reaches — is
        # ABSENT from the release bundle and PRESENT in the control. It shipped
        # as a local-only command: `rg test:freehand-evidence-elision .github`
        # found no workflow invocation, so the next change to the schema, the
        # seam, or the release entry could merge without running the proof.
        #
        # These five files are the proof's bounded PRIMARY INPUTS — not every
        # file that could affect Closure reachability, but the ones that
        # directly constitute the probe:
        #   - evidence.cljc  — the doors carrying the two DEV_ONLY_SENTINELS; a
        #     rename of either refusal string makes the probe vacuous (it would
        #     go absent in the CONTROL too, which is what the positive control
        #     catches — but only if the gate RUNS).
        #   - cell.cljc      — `emit-commit-evidence!`, the sole dev gate. Move
        #     it out from behind `interop/debug-enabled?` and the schema ships.
        #     It also carries the third sentinel (the containment console line)
        #     and both call sites into the occurrence index.
        #   - occurrences.cljc — the dev-only CURRENT-OCCURRENCE index
        #     (rf2-xftdv): a `defonce` atom holding one row per connected
        #     occurrence. It carries no runtime string literal, so it can root
        #     no sentinel of its own; its production absence follows from
        #     cell.cljc's gate, which is exactly why a change to the index is
        #     worth re-running the proof that the gate really does fold away.
        #   - shell.cljs     — the SOLE mounted commit edge: `cell/commit!` in
        #     the useLayoutEffect reconcile (shell.cljs:165). That call is what
        #     ROOTS `emit-commit-evidence!`, and through it BOTH positive-control
        #     door strings; remove or redirect it and the control bundle loses
        #     the very sentinels the probe reads.
        #   - release_app.cljs — the entry BOTH bundles compile, and the home of
        #     the PROD_SURVIVING sentinel (the non-vacuity floor).
        #
        # Deliberately NOT the whole `implementation/freehand/**` tree: two
        # `:advanced` builds on every PR of a large programme is cost the ruling
        # (rf2-xwa4n) declined. Plenty of TRANSITIVE changes — in the compiler,
        # in core interop, in a Closure bump — could theoretically stop the
        # release app reaching a mounted commit; the unconditional nightly
        # (expensive-tests.yml) is the honest superset for those. This list is
        # the direct inputs only.
        #
        # The narrow list stays honest because the ALWAYS-armed jvm-freehand
        # lane carries
        # `the-evidence-schema-reaches-the-render-path-only-through-the-dev-gated-seam`
        # (evidence_boundary_jvm_test.clj), which asserts `cell` is the sole
        # DOOR-REACHABLE Freehand namespace mentioning the schema — and, beside
        # it, that the tool-tier read door `re-frame.freehand.tool` (which
        # mentions the schema and is deliberately NOT reachable from the public
        # door) stays off the render path (rf2-lvvl2). Those two rows together
        # are the premise: a new schema-touching PRODUCER cannot appear without
        # reddening that walk, so it cannot slip past this list unnoticed.
        # Reachability is decided by whoever REQUIRES the tool tier rather than
        # by the tier itself, which is why the off-path row — not the tool file —
        # is what this list leans on. Widen both together if either row is ever
        # relaxed. What that walk canNOT see is a DELETED CALL edge: it proves
        # require-reachability, and both namespaces stay require-reachable after
        # `cell/commit!` is removed from the reconcile. That is precisely why
        # shell.cljs is armed here directly rather than leaned on the law
        # (rf2-xwa4n, merged-PR audit of #6888).
        #
        # The three host arms below are replicated from the artefact-root case:
        # a POSIX `case` takes the FIRST match, so this case SHADOWS
        # `implementation/freehand/*` — it must widen, never narrow.
        implementation_jvm=true
        cljs_node_test=true
        cljs_browser=true
        freehand_evidence_elision=true
        # rf2-zl8ao — the release entry is SHARED with the B5 reachability
        # probe: `:freehand-release` is the production half of both control
        # pairs, so a change to this app can root `re-frame.freehand.control`
        # (a controlled input added to a release view) and break reachability
        # without touching anything the evidence gate watches. A POSIX `case`
        # takes the FIRST match, so this file can never reach the reachability
        # case below — arm it here instead. Scoped to the entry: `evidence` and
        # `cell` are the evidence gate's producers and not this one's.
        case "$file" in
          implementation/freehand/test/re_frame/freehand/release_app.cljs)
            freehand_reachability=true ;;
        esac
        ;;
      implementation/freehand/*.md)
        # rf2-drpa3.70 — prose under the artefact root. The two host suites
        # still fire (a README documents contracts those suites assert, and
        # they are cheap), but Markdown cannot change what React puts on a
        # page, so it does not pay for a Chromium run. This arm exists only to
        # keep the browser widening below off documentation-only PRs, and it
        # MUST stay above the artefact-root arm: a POSIX `case` takes the
        # first match, and its `*` spans `/`, so this covers prose at any
        # depth under the artefact.
        implementation_jvm=true
        cljs_node_test=true
        ;;
      implementation/freehand/src/re_frame/freehand/control.cljc|implementation/freehand/src/re_frame/freehand.cljc|implementation/freehand/test/re_frame/freehand/bench/b5_reachability_control_app.cljs)
        # rf2-zl8ao — the B5 REACHABILITY gate's Freehand producer surfaces.
        # `npm run test:freehand-reachability` (rf2-drpa3.52 acceptance 1)
        # builds `:freehand-release` and its strict-superset twin
        # `:freehand-release-reachability-control` and proves the semantic
        # controller runtime (`re-frame.freehand.control`) is ABSENT from the
        # production bundle and PRESENT in the control — 8,691 chars an
        # unusing page does not ship. It landed as a local-only command with
        # no workflow invocation, the same way its sibling did.
        #
        # DIFFERENT CLAIM from the evidence-elision gate above, not a
        # duplicate: that pair moves `goog.DEBUG` and holds the app still
        # (a DEV-GATED SEAM elides); this pair holds the flag still and moves
        # the APP (an UNUSED MODULE elides). The controller strings are absent
        # from the goog.DEBUG=true control too, so that build cannot prove
        # this one. Hence two arms and two jobs.
        #
        # These are what can invalidate it:
        #   - control.cljc  — the two refusal doors whose exact strings the
        #     probe greps (`record-key`'s absent-`:control` and nil-`:control`
        #     messages). Reword either and the grep goes vacuous — which the
        #     positive-control half catches, but only if the gate RUNS.
        #   - freehand.cljc — the facade, and the ONLY namespace requiring
        #     `re-frame.freehand.control` (`controller-key` is `def`'d to
        #     `control/record-key`). It owns the single production call edge:
        #     root the door from a paved path here and the module ships.
        #   - b5_reachability_control_app.cljs — the CONTROL entry. Lose the
        #     `v/controller-key` call and the oracle stops being validated.
        # The release entry `release_app.cljs` — the production half both
        # bundles compile — is armed in the shared case above, which shadows
        # this one.
        #
        # Deliberately NOT the whole `implementation/freehand/**` tree: two
        # `:advanced` builds on every PR of a large programme is cost the
        # sibling ruling (rf2-xwa4n) declined, and this gate shares one of
        # those two builds with it. Unlike the evidence gate there is no
        # sole-requirer JVM law pinning the narrowness yet (rf2-drpa3.52's
        # boundary walk covers the evidence schema, not `control`), so the
        # backstop for a NEW production edge appearing outside these files is
        # the unconditional nightly run in expensive-tests.yml.
        #
        # The three host arms below are replicated from the artefact-root
        # case: a POSIX `case` takes the FIRST match, so this case SHADOWS
        # `implementation/freehand/*` — it must widen, never narrow.
        implementation_jvm=true
        cljs_node_test=true
        cljs_browser=true
        freehand_reachability=true
        ;;
      implementation/freehand/*)
        # rf2-drpa3.58 — the Freehand view substrate artefact (EP-0036).
        # Its JVM lane shipped as a standalone workflow with its own
        # `paths:` trigger, so the classifier never had to know about the
        # tree. Folding that lane into test.yml as `jvm-freehand` makes the
        # classifier load-bearing: with no case here every output stays
        # false on a freehand-only PR, so the newly-required job would
        # SKIP exactly when it matters — strictly worse than the advisory
        # standalone workflow it replaces.
        #
        # implementation_jvm fires the `jvm-freehand` job (the artefact's
        # `:test` alias plus the donor-boundary law). cljs_node_test fires
        # the consolidated `:node-test` build, which carries `freehand/src`
        # + `freehand/test` (implementation/shadow-cljs.edn) and matches
        # `re-frame.freehand.*-cljs-test` — the deleted workflow's header
        # asserted that arm was covered by an "always-on `cljs` job", but
        # `cljs` is surface-gated on cljs_node_test, so before this case
        # the CLJS arm skipped on a freehand-only PR too.
        #
        # rf2-drpa3.70 — cljs_browser, the widening rf2-drpa3.58 said to make
        # "the moment it gains a `*-dom-cljs-test` namespace". F1c shipped the
        # interpreted React emitter and with it two such namespaces
        # (react_mount_dom_cljs_test.cljs, route_link_native_dom_cljs_test.cljs)
        # that mount through `react-dom/client` and read assertions back off
        # `document`. They already RIDE the `:browser-test` build — freehand/src
        # and freehand/test are on :source-paths and that build's
        # `-dom-cljs-test$` ns-regexp selects them — but the `cljs-browser` job
        # is surface-gated on this output, so a Freehand-only PR skipped the
        # only lane where they can execute.
        #
        # A green `cljs` job is not a substitute: the `:node-test` regex
        # matches the same two files, where they find no DOM and say so rather
        # than mounting. That is the same false-green shape rf2-drpa3.58/.61
        # closed for the host suites, one tier up.
        #
        # Still NOT cljs_prod / bundle_isolation / ui_gates / ui_smoke: no
        # bundle those gates measure requires Freehand (cljs_prod builds the
        # `elision-probe` pair, bundle_isolation the examples set), and Freehand
        # mounts no testbed those smokes drive. Widen each the moment that
        # changes — the `implementation/ui/*` case above is the worked
        # precedent.
        #
        # rf2-xwa4n — Freehand DOES now have `:advanced` release builds of its
        # own (`:freehand-release` and its control twin), so the older reading
        # of this note as "Freehand ships nothing under :advanced" no longer
        # holds. Those builds are covered by the dedicated
        # freehand_evidence_elision output, armed narrowly on the probe's
        # primary inputs in the case above — not by these four.
        implementation_jvm=true
        cljs_node_test=true
        cljs_browser=true
        ;;
      implementation/scripts/run-ui-bench.cjs)
        # rf2-vxgfnd.6 — false-green fix, mirroring the launcher cases
        # above: run-ui-bench.cjs IS the executable orchestration for
        # `npm run test:ui-g1` (the cljs-ui-g1 PR job). A break in the
        # launcher (shadow runner resolution, the emitted-JS golden
        # regexes, env wiring) can break the very gate it drives, so
        # editing it must run that gate. The generic
        # implementation/scripts/* surfaces stay armed too — this case
        # widens coverage, it does not narrow it.
        cljs_node_test=true
        cljs_browser=true
        cljs_prod=true
        bundle_isolation=true
        reagent_slim_bundle=true
        ui_gates=true
        ;;
      implementation/scripts/run-ui-g13.cjs)
        # rf2-vxgfnd.12.3 — this is the complete G-13 compile/serve/browser/
        # advanced-elision orchestrator. A launcher-only change must run the
        # ui_gates job it owns; retain the generic scripts fan-out too.
        cljs_node_test=true
        cljs_browser=true
        cljs_prod=true
        bundle_isolation=true
        reagent_slim_bundle=true
        ui_gates=true
        ;;
      implementation/scripts/run-ui-g8.cjs)
        # rf2-vxgfnd.95.10 — the complete G-8 compile/serve/dual-engine
        # (Chromium + WebKit) controlled-input orchestrator. A launcher-only
        # change must run the ui_gates job it owns (cljs-ui-g8); retain the
        # generic scripts fan-out too.
        cljs_node_test=true
        cljs_browser=true
        cljs_prod=true
        bundle_isolation=true
        reagent_slim_bundle=true
        ui_gates=true
        ;;
      implementation/scripts/check-ui-adapter-isolation.cjs)
        # The checker IS the focused re-frame.ui dependency-closure gate.
        # A checker-only PR must start the consolidated CLJS job that owns
        # that focused step AND satisfy the step's ui_gates condition;
        # otherwise the gate can silently edit itself out of CI.
        cljs_node_test=true
        ui_gates=true
        ;;
      implementation/scripts/check-ui-facade-isolation.cjs)
        # rf2-kxork — the checker IS the G-18 library-facade-isolation gate
        # (cljs-ui-facade-isolation, gated on ui_gates). A checker-only PR must
        # fire the job it implements, otherwise the gate could silently edit
        # itself out of CI — the same self-protection the G-1/G-13/G-8
        # launchers and the two sibling ui checkers above already carry.
        # cljs_node_test stays armed so the checker keeps the generic
        # implementation/scripts/* coverage too; this widens, never narrows.
        # No cljs_browser/bundle_isolation fan-out: G-18 is a shadow-cljs
        # release plus a bundle string inspection — it drives no browser and
        # no packaging boundary beyond its own two proof-pack builds.
        cljs_node_test=true
        ui_gates=true
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
      implementation/spec-resource/*)
        # day8/re-frame2-spec-resource is the ONE build-time reader for
        # committed spec/ data — the Freehand conformance fixture loader
        # and the api-manifest CLJS probe both expand through it, so a
        # change here can break either macro's compile.
        #
        # implementation_jvm fires this artefact's own `:test` alias, which
        # is the deterministic control for the cold-load race the reader
        # exists to close (jvm-spec-resource), plus jvm-freehand, whose
        # `:test` alias carries the fixture loader. cljs_node_test fires
        # the consolidated `:node-test` build, where the reader is actually
        # exercised — that is the lane whose macro expansion reaches
        # shadow-cljs's recording read, in parallel, from both consumers at
        # once. cljs_browser for the same reason the freehand case gives:
        # the `-dom-cljs-test$` suites inline fixtures too.
        #
        # No production bundle requires any of this (build-time only), so
        # cljs_prod / bundle_isolation stay off.
        implementation_jvm=true
        cljs_node_test=true
        cljs_browser=true
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
      spec/conformance/S3-view-conformance-profile.md|spec/conformance/S4-view-conformance-profile.md|spec/conformance/S5-view-conformance-profile.md)
        # rf2-vxgfnd.97.3 — the S3/S4/S5 view-conformance PROFILE docs are the
        # human catalogues bound ROW BY ROW by the executable drift guards
        # (implementation/ui/test/re_frame/ui/s{3,4,5}_conformance_profile_jvm_test.clj),
        # which run inside the jvm-ui job. Those guards gate on
        # implementation_jvm, but a profile-DOC-only edit previously matched no
        # case here — every output false — so the guard the profile CLAIMS to be
        # held by did not run: a PR could delete a row, hollow one out, swap a
        # proof home between rows, or flip the conformance declaration with the
        # drift guard silently SKIPPED. Fire implementation_jvm so the jvm-ui job
        # re-runs the S3-S5 profile guards on any profile-doc-only edit. No new
        # dedicated job — the guards ride the existing jvm-ui, exactly as the S3
        # gate (#6182) and S4 gate (#6320) established (they added none either).
        implementation_jvm=true
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
      spec/conformance/freehand/fixtures/*|spec/conformance/freehand/conformance-index.md)
        # rf2-drpa3.66 — false-green fix, the Freehand-corpus counterpart of
        # the shared spec/conformance/fixtures/* case above.
        #
        # Every fixture under this root is a LIVE INPUT to both Freehand host
        # suites: implementation/freehand/test/re_frame/freehand/conformance.cljc
        # reads it at MACRO-EXPANSION time and inlines the value, so the JVM
        # `:test` alias (the required `jvm-freehand` job, gated on
        # implementation_jvm) and the consolidated `:node-test` build (the
        # `cljs` job, gated on cljs_node_test) assert against the same bytes.
        # rf2-drpa3.58 armed those two outputs for implementation/freehand/**
        # but not for the corpus the suites consume, so a fixture-only PR left
        # every output false and BOTH newly-required host jobs SKIPPED —
        # accepted unexplained by `all-required-passed`.
        #
        # The always-on freehand-conformance.yml is not a substitute:
        # check_freehand_conformance_index.py verifies that an active index row
        # NAMES AN EXISTING FIXTURE, not that the fixture's contract VALUES hold.
        # Flipping FH-CALL-001's `:predicates :view?` from true to false keeps
        # the path, the `:fh/id` and the index relationship intact — valid EDN,
        # green index check — while descriptor_cljs_test.cljc fails on both
        # hosts once actually run. Arming the two host outputs is what makes it
        # run.
        #
        # Scoped exactly like the implementation/freehand/* case, which is why
        # rf2-drpa3.70's browser widening lands on both together. This corpus
        # is a live input to the MOUNTED tests too: FH-STRUCT-007 is the DOM
        # table react_mount_dom_cljs_test.cljs reads back off `document`, and
        # FH-ROUTELINK-001..003 drive route_link_native_dom_cljs_test.cljs. A
        # fixture edit can therefore change mounted output, so it must schedule
        # the browser lane. The arm stays the whole fixtures root rather than a
        # family prefix: two unrelated families already feed browser tests, and
        # a prefix list would rot silently on the third.
        #
        # rf2-49upn — conformance-index.md joins the fixtures here, because the
        # index is the CLAIM the fixtures are evidence for and the two are one
        # ledger. The census in check_freehand_conformance_index.py proves the
        # STATIC half of a row: that an assertion under
        # implementation/freehand/test/ REACHES the row's fixture, from a lane
        # that serves every (mode, host) cell the row's applicability names.
        # The DYNAMIC half — that the assertion PASSES — is the lane exit
        # codes, which the census cannot see from where it runs.
        #
        # freehand-conformance.yml carries the census, is deliberately
        # unfiltered, and is Python-only. So before this arm an index-only PR
        # got the census green with jvm-freehand, `cljs` and cljs-browser all
        # SKIPPED: the row's claim certified on a commit where nothing executed
        # it. That is the shape the #6907 merged-PR audit flagged ("freehand-
        # conformance passed, while JVM freehand, CLJS node, and browser lanes
        # were all skipped"). Widening an applicability cell — `common jvm` to
        # `interpreted browser`, say — is exactly an index-only edit, and it is
        # the edit that most needs the lanes it newly claims.
        #
        # The marginal CI cost is ~zero on the common path: a row lands with
        # its proof, and a proof lives under implementation/freehand/test/** or
        # under the fixtures root, both of which already arm these same three
        # outputs. The lanes are added only on an index-only PR — precisely the
        # false-green shape. Binding is at COMMIT granularity, held by
        # `all-required-passed`, not by one job re-running three required lanes.
        #
        # Only the index. The two sibling documents under this root stay off:
        # donor-inventory.md is a different ledger (check_donor_inventory.py
        # censuses live re-frame.ui consumers across the working tree, which no
        # Freehand suite asserts on), and README.md is the document that DEFINES
        # the addressing scheme — it speaks in illustrative ids and is excluded
        # from the census's own citation scan for that reason.
        #
        # Still NOT cljs_prod / bundle_isolation — no bundle those two gates
        # measure requires Freehand (the `elision-probe` pair and the examples
        # set respectively). Widen both cases together when that changes.
        # rf2-xwa4n — Freehand's own `:advanced` pair (`:freehand-release` +
        # control) is covered by freehand_evidence_elision instead, and these
        # fixtures are not among that probe's primary inputs.
        implementation_jvm=true
        cljs_node_test=true
        cljs_browser=true
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
      implementation/scripts/check-freehand-evidence-elision.cjs)
        # rf2-xwa4n — self-protection, mirroring the launcher/checker cases
        # above. This script IS the F4g evidence-elision gate: the sentinel sets,
        # the non-vacuity floor and the two bundle reads all live in it. The
        # generic `implementation/scripts/*` case below never arms
        # freehand_evidence_elision, so without this arm a PR could edit the
        # gate's own teeth — soften a sentinel, drop the positive control —
        # while avoiding the job that runs it. The remaining static-script
        # surfaces it shares with the generic case stay armed too; this case
        # widens coverage, it does not narrow it.
        cljs_node_test=true
        cljs_browser=true
        cljs_prod=true
        bundle_isolation=true
        reagent_slim_bundle=true
        freehand_evidence_elision=true
        ;;
      implementation/scripts/check-freehand-reachability.cjs)
        # rf2-zl8ao — self-protection, exactly as for the sibling checker
        # above. This script IS the B5 reachability gate: the two controller
        # door sentinels, the non-vacuity survivor, the empty-bundle refusal
        # and the oracle-before-result ordering all live in it. The generic
        # `implementation/scripts/*` case below never arms
        # freehand_reachability, so without this arm a PR could soften the
        # gate's own teeth while avoiding the job that runs it. The remaining
        # static-script surfaces it shares with the generic case stay armed
        # too; this case widens coverage, it does not narrow it.
        cljs_node_test=true
        cljs_browser=true
        cljs_prod=true
        bundle_isolation=true
        reagent_slim_bundle=true
        freehand_reachability=true
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
        # rf2-vxgfnd.6 — the G-1 bench gate compiles the :ui-bench
        # release build straight off shadow-cljs.edn and resolves
        # shadow-cljs + react from the npm pins, so build-config /
        # npm-pin changes must re-run it (a broken :ui-bench build or
        # a React bump shifting the measured ratio otherwise merges
        # green and fails on main). Scoped to the three build-config
        # files; implementation/scripts/* stays off ui_gates (the one
        # script that drives the gate has its own case above).
        case "$file" in
          implementation/shadow-cljs.edn|implementation/package.json|implementation/package-lock.json)
            ui_gates=true ;;
        esac
        # rf2-xwa4n — the F4g evidence-elision gate is DEFINED by this trio:
        # shadow-cljs.edn declares BOTH halves of the probe pair (`:freehand-
        # release` and its `:closure-defines {goog.DEBUG true}` control twin
        # `:freehand-release-control`) — drop the closure-define and the control
        # silently stops being a control — and package.json carries the
        # `test:freehand-evidence-elision` script that builds them and invokes
        # the checker. The lockfile pins the shadow-cljs (hence Closure)
        # version whose DCE the whole claim rests on. Scoped to the three
        # build-config files exactly like the ui_gates arm above;
        # implementation/scripts/* stays off (the one script that drives the
        # gate has its own case above).
        case "$file" in
          implementation/shadow-cljs.edn|implementation/package.json|implementation/package-lock.json)
            freehand_evidence_elision=true ;;
        esac
        # rf2-zl8ao — the same trio DEFINES the B5 reachability gate too, and
        # for its own reasons: shadow-cljs.edn declares the reachability
        # CONTROL build (`:freehand-release-reachability-control`), whose
        # `:init-fn` is the superset entry and whose `goog.DEBUG false` /
        # `:advanced` settings must stay IDENTICAL to `:freehand-release` —
        # let them drift and the pair stops being a controlled comparison.
        # package.json carries the `test:freehand-reachability` script that
        # builds both and invokes the checker, and the lockfile pins the
        # shadow-cljs (hence Closure) version whose DCE the absence claim
        # rests on. Scoped to the three build-config files, like the arms
        # above; `implementation/scripts/*` stays off (the checker has its
        # own case above).
        case "$file" in
          implementation/shadow-cljs.edn|implementation/package.json|implementation/package-lock.json)
            freehand_reachability=true ;;
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
        # export egress is verified). No mcp_conformance /
        # template_expensive fan-out: machines-viz is not consumed by an MCP
        # wrapper and is not part of the deps-new template's generated app.
        #
        # rf2-as6bg — this arm used to claim machines-viz "has no JVM suite".
        # It does: a wired `:test` alias, on `scripts/test-jvm-tools.sh`'s
        # roster, 632 tests / 2537 assertions. 29 of its 31 suites are
        # `*_cljs_test.*` and ride the two CLJS gates above, but
        # `engine_grammar_parity_test.cljc` (the engine<->viz grammar drift
        # ratchet) and `mermaid_public_smoke_test.cljc` match neither
        # `cljs-test$` (`:node-test`) nor `-dom-cljs-test$` (`:browser-test`),
        # so they run in the JVM lane ONLY.
        #
        # rf2-wq17m — that lane now has a CI job, `jvm-tools-machines-viz`,
        # gated on the dedicated output below. `tools_jvm` is still deliberately
        # NOT set: it gates four jvm-tools-* jobs (xray / story / story-mcp /
        # mcp-base), none of which runs this artefact, so it would fire four
        # unrelated probes and still skip these two files.
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
            # *-dom-cljs-test export/chart-DOM suites. The JVM lane joins them
            # on the same conservative footing (rf2-wq17m): `deps.edn` moves the
            # test classpath and the parity suite mirrors engine grammar, so
            # narrowing to `src/**` would skip the very inputs it watches. The
            # whole suite is seconds.
            cljs_node_test=true
            cljs_browser=true
            tools_jvm_machines_viz=true
            ;;
        esac
        ;;
      tools/testbed-support/*)
        # rf2-as6bg — this tree had NO arm at all, so a testbed-support-only
        # PR classified as zero changed surfaces and ran zero gates. Not just
        # the JVM suite the bijection gate found (rf2-4hc9p): its three CLJS
        # suites were skipped too. `implementation/shadow-cljs.edn` says the
        # slice "additionally rides the always-on `:node-test` gate, so the
        # slice is covered on every PR" — that was true of the BUILD's
        # source-paths and false of CI, because the `cljs` job is gated on
        # cljs_node_test, which nothing here set.
        #
        # src+test are :source-paths of the consolidated :node-test AND
        # :browser-test builds, exactly like machines-viz above, so the same
        # two gates own them: `config_cljs_test.cljs` +
        # `story_host_cljs_test.cljs` under node, and
        # `story_host_dom_cljs_test.cljs` (matching `-dom-cljs-test$`) under
        # headless Chromium for its real React-root handoff assertions.
        #
        # The `.clj` half — `open_in_editor_server_test.clj`, 32 tests / 153
        # assertions, which no CLJS build can load — now has a lane too
        # (rf2-wq17m): `jvm-tools-testbed-support`, gated on the dedicated
        # output below. `tools_jvm` stays deliberately unset for the same reason
        # as machines-viz above: none of the four jobs `tools_jvm` gates (xray /
        # story / story-mcp / mcp-base) runs this artefact, so it would fire four
        # unrelated probes and still skip the file.
        cljs_node_test=true
        cljs_browser=true
        tools_jvm_testbed_support=true
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
      skills/re-frame2-pair/preload/*)
        # rf2-11yjq — the shipped re-frame2-pair preload
        # (skills/re-frame2-pair/preload/re_frame2_pair/{runtime.cljs,pure.cljc},
        # and any nested source) is dev-only RUNTIME whose stateful wrapper has
        # TWO owning behavioral gates:
        #   - cljs-browser (gated on cljs_browser) discovers
        #     re-frame.pair-dispatch-and-settle-dom-cljs-test, which imports
        #     re-frame2-pair.runtime and drives its real React/epoch settle
        #     behavior under headless Chromium.
        #   - mcp-conformance-re-frame2-pair (gated on mcp_live) boots the
        #     hermetic fixture with THIS exact preload and exercises live Pair
        #     operations across the MCP bridge.
        # rf2-k8yl5f armed examples_compile (the earlier case block) so the
        # preload COMPILES into the ~28 example dev builds that inject it, and
        # the generic skills/re-frame2-pair/* case below armed skills_structural
        # (source-shape + pure-core node fixture). But NEITHER of those executes
        # the stateful runtime wrapper or its live wire behavior — so a
        # preload-only regression (runtime.cljs / pure.cljc / a nested preload
        # path) merged with BOTH owning behavioral gates SKIPPED, caught only by
        # the nightly net (a PR-time false-green). Arm cljs_browser + mcp_live so
        # the two runtime consumers run at PR time. skills_structural is set here
        # too because this more-specific case shadows the generic
        # skills/re-frame2-pair/* case below (widening coverage, not narrowing
        # it); examples_compile still fires via the earlier case block. Scoped to
        # the preload subtree only — non-preload skill docs/references keep their
        # structural-only classification and never arm these expensive gates.
        skills_structural=true
        cljs_browser=true
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
      docs/tools/playground/*|docs/cljs/playground.js|docs/cljs/playground.css|scripts/playground-sci-input-digest.mjs)
        # rf2-ee38b.22 — the docs/cljs live-cell playground (CM6 + Scittle
        # bootstrap + the re-frame2 SCI bundle). The tools-playground job
        # rebuilds both bundles, runs the headless-Chromium smoke against
        # them, and `git diff --exit-code`s the committed
        # docs/cljs/playground.{js,css} so a stale vendored bootstrap bundle
        # fails the PR.
        #
        # rf2-tzy13 — docs/cljs/playground-rf2.js is GONE from this arm: the
        # SCI bundle is no longer tracked (it is generated in CI and on docs
        # deploy, and is .gitignored), so the path can never appear in a diff.
        # scripts/playground-sci-input-digest.mjs joins the arm instead — it
        # computes the provenance digest copy-bundle.mjs stamps into the
        # generated bundle, so an edit to it must exercise the build (rf2-nyjml
        # finding: the digest + copy-bundle scripts were not themselves
        # classifier inputs; copy-bundle.mjs is already covered under
        # docs/tools/playground/*).
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
emit ui_gates "$ui_gates"
emit adapter_diagnostic "$adapter_diagnostic"
emit cljs_browser "$cljs_browser"
emit examples_compile "$examples_compile"
emit cljs_prod "$cljs_prod"
emit bundle_isolation "$bundle_isolation"
emit reagent_slim_bundle "$reagent_slim_bundle"
emit freehand_evidence_elision "$freehand_evidence_elision"
emit freehand_reachability "$freehand_reachability"
emit adapter_testbed_smokes "$adapter_testbed_smokes"
emit ui_smoke "$ui_smoke"
emit tools_jvm "$tools_jvm"
emit tools_jvm_machines_viz "$tools_jvm_machines_viz"
emit tools_jvm_testbed_support "$tools_jvm_testbed_support"
emit template_expensive "$template_expensive"
emit mcp_conformance "$mcp_conformance"
emit mcp_live "$mcp_live"
emit story_xray_browser "$story_xray_browser"
emit tenant_switcher_smoke "$tenant_switcher_smoke"
emit skills_structural "$skills_structural"
emit playground "$playground"
