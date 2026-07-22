#!/usr/bin/env node

'use strict';

const assert = require('assert/strict');
const { execFileSync } = require('child_process');
const fs = require('fs');
const os = require('os');
const path = require('path');

const IMPL_ROOT = path.resolve(__dirname, '..');
const REPO_ROOT = path.resolve(IMPL_ROOT, '..');
const WORKFLOW = path.join(REPO_ROOT, '.github', 'workflows', 'test.yml');

const tests = [];

function test(name, fn) {
  tests.push({ name, fn });
}

function classify(...files) {
  const quote = (s) => `'${String(s).replace(/'/g, `'\\''`)}'`;
  const command = ['./.github/scripts/report-changed-surfaces.sh', ...files.map(quote)].join(' ');
  const env = { ...process.env };
  delete env.GITHUB_OUTPUT;
  const out = execFileSync('bash', ['-lc', command], {
    cwd: REPO_ROOT,
    env,
    encoding: 'utf8',
  });
  return Object.fromEntries(
    out
      .trim()
      .split(/\r?\n/)
      .filter(Boolean)
      .map((line) => line.split('=')),
  );
}

// rf2-k9ekz — Story/Xray browser Playwright gate trigger is narrowed
// to runtime-source changes under tools/{story,xray}/{src,testbeds}/**
// AND a runtime-extension (.cljs/.cljc/.js/.cjs/.css/.scss). Spec /
// test / EDN / deps / Markdown changes do not fire it.

test('Story src .cljs changes trigger story_xray_browser', () => {
  const result = classify('tools/story/src/foo.cljs');
  assert.equal(result.story_xray_browser, 'true');
});

test('Xray src .cljs changes trigger story_xray_browser', () => {
  const result = classify('tools/xray/src/foo.cljs');
  assert.equal(result.story_xray_browser, 'true');
});

test('Story testbed .cljs changes trigger story_xray_browser (gate runs the testbed)', () => {
  const result = classify('tools/story/testbeds/counter_with_stories/stories.cljs');
  assert.equal(result.story_xray_browser, 'true');
});

test('Xray feature_matrix testbed .cljs changes trigger story_xray_browser', () => {
  const result = classify('tools/xray/testbeds/feature_matrix/core.cljs');
  assert.equal(result.story_xray_browser, 'true');
});

test('Story spec-only .md changes do NOT trigger story_xray_browser (rf2-k9ekz)', () => {
  const result = classify('tools/story/spec/Spec.md');
  assert.equal(result.story_xray_browser, 'false');
});

test('Xray spec-only .md changes do NOT trigger story_xray_browser (rf2-k9ekz)', () => {
  const result = classify('tools/xray/spec/017-Test-Coverage-Matrix.md');
  assert.equal(result.story_xray_browser, 'false');
});

test('Story test-only changes do NOT trigger story_xray_browser (rf2-k9ekz)', () => {
  const result = classify('tools/story/test/story_feature_load.cjs');
  assert.equal(result.story_xray_browser, 'false');
});

test('Xray test-only changes do NOT trigger story_xray_browser (rf2-k9ekz)', () => {
  const result = classify('tools/xray/test/some_test.clj');
  assert.equal(result.story_xray_browser, 'false');
});

test('Story deps.edn changes do NOT trigger story_xray_browser (rf2-k9ekz)', () => {
  const result = classify('tools/story/deps.edn');
  assert.equal(result.story_xray_browser, 'false');
});

test('Story README.md changes do NOT trigger story_xray_browser (rf2-k9ekz)', () => {
  const result = classify('tools/story/README.md');
  assert.equal(result.story_xray_browser, 'false');
});

test('Mixed Story src + spec change DOES trigger story_xray_browser (rf2-k9ekz)', () => {
  const result = classify('tools/story/src/foo.cljs', 'tools/story/spec/bar.md');
  assert.equal(result.story_xray_browser, 'true');
});

// rf2-f79t8 (b) — a tools/{story,xray}/spec/**.md MARKDOWN change is a
// pure doc change: it cannot affect any runtime, JVM unit test, or MCP
// wire surface, so it must NOT fan out to the JVM/MCP probes (tools_jvm,
// mcp_conformance). docs.yml + the nightly full matrix cover docs.

test('Story spec-only .md does NOT fan out to tools_jvm / mcp_conformance (rf2-f79t8)', () => {
  const result = classify('tools/story/spec/Spec.md');
  assert.equal(result.tools_jvm, 'false');
  assert.equal(result.mcp_conformance, 'false');
  assert.equal(result.cljs_node_test, 'false');
});

test('Xray spec-only .md does NOT fan out to tools_jvm / mcp_conformance (rf2-f79t8)', () => {
  const result = classify('tools/xray/spec/017-Test-Coverage-Matrix.md');
  assert.equal(result.tools_jvm, 'false');
  assert.equal(result.mcp_conformance, 'false');
  assert.equal(result.cljs_node_test, 'false');
});

test('Story NON-spec change (JVM .clj test) STILL fans out to tools_jvm / mcp_conformance (rf2-f79t8)', () => {
  const result = classify('tools/story/test/some_test.clj');
  assert.equal(result.tools_jvm, 'true');
  assert.equal(result.mcp_conformance, 'true');
  // A JVM-only .clj does not compile into the consolidated :node-test build.
  assert.equal(result.cljs_node_test, 'false');
});

test('Story deps.edn change STILL fans out to tools_jvm / mcp_conformance (rf2-f79t8)', () => {
  const result = classify('tools/story/deps.edn');
  assert.equal(result.tools_jvm, 'true');
  assert.equal(result.mcp_conformance, 'true');
});

test('Mixed Story spec .md + JVM .clj DOES fan out to tools_jvm (rf2-f79t8)', () => {
  const result = classify('tools/story/spec/bar.md', 'tools/story/test/some_test.clj');
  assert.equal(result.tools_jvm, 'true');
  assert.equal(result.mcp_conformance, 'true');
});

// rf2-f79t8 (a) — jvm-core + cljs (the consolidated :node-test build) are
// job-level gated so a spec/docs-only PR skips the two heavy always-on
// suites. The classifier drives the `if:` via implementation_jvm
// (jvm-core) and cljs_node_test (cljs). The pull_request trigger stays
// UNFILTERED — gating is job-side, NOT a trigger path filter.

test('Spec-only .md change skips jvm-core + cljs (implementation_jvm + cljs_node_test false) (rf2-f79t8)', () => {
  const result = classify('spec/006-ReactiveSubstrate.md');
  assert.equal(result.implementation_jvm, 'false');
  assert.equal(result.cljs_node_test, 'false');
});

test('Docs-only change skips jvm-core + cljs (rf2-f79t8)', () => {
  const result = classify('docs/core/intro.md');
  assert.equal(result.implementation_jvm, 'false');
  assert.equal(result.cljs_node_test, 'false');
});

test('Core change runs jvm-core + cljs (implementation_jvm + cljs_node_test true) (rf2-f79t8)', () => {
  const result = classify('implementation/core/src/re_frame/core.cljc');
  assert.equal(result.implementation_jvm, 'true');
  assert.equal(result.cljs_node_test, 'true');
});

// rf2-vxgfnd.209 — G-13 (cljs-ui-g13, gated on ui_gates) is the end-to-end
// MOUNTED falsifier for re-frame.ui push economics; it traverses core
// dispatch/drain, the router, frame scheduling, and the observation port. Any
// core runtime change must schedule it, or a V-wide fan-out / split-batching
// regression reachable only through core merges with the one gate that catches
// it skipped. Docs/spec/tool-only changes keep their existing skip.
test('Core observation-port change schedules G-13 (ui_gates true) (rf2-vxgfnd.209)', () => {
  const result = classify('implementation/core/src/re_frame/substrate/observation.cljc');
  assert.equal(result.ui_gates, 'true');
});

test('Core router/drain change schedules G-13 (ui_gates true) (rf2-vxgfnd.209)', () => {
  const result = classify('implementation/core/src/re_frame/router.cljc');
  assert.equal(result.ui_gates, 'true');
});

test('Core frame/scheduler change schedules G-13 (ui_gates true) (rf2-vxgfnd.209)', () => {
  const result = classify('implementation/core/src/re_frame/frame.cljc');
  assert.equal(result.ui_gates, 'true');
});

test('Core facade change schedules G-13 (ui_gates true) (rf2-vxgfnd.209)', () => {
  const result = classify('implementation/core/src/re_frame/core.cljc');
  assert.equal(result.ui_gates, 'true');
});

test('Docs-only change does NOT schedule G-13 (ui_gates false) (rf2-vxgfnd.209)', () => {
  const result = classify('docs/core/intro.md');
  assert.equal(result.ui_gates, 'false');
});

test('Spec-only .md change does NOT schedule G-13 (ui_gates false) (rf2-vxgfnd.209)', () => {
  const result = classify('spec/006-ReactiveSubstrate.md');
  assert.equal(result.ui_gates, 'false');
});

test('Conformance fixture change runs cljs (CLJS corpus runner is in node-test) (rf2-f79t8)', () => {
  const result = classify('spec/conformance/fixtures/dispatch.edn');
  assert.equal(result.implementation_jvm, 'true');
  assert.equal(result.cljs_node_test, 'true');
});

// rf2-vxgfnd.97.3 — the S3/S4/S5 view-conformance PROFILE docs are bound row by
// row by the executable drift guards (s{3,4,5}_conformance_profile_jvm_test.clj,
// run by the jvm-ui job, which gates on implementation_jvm). A profile-DOC-only
// edit previously matched no classifier case — every output false — so the guard
// the profile claims to be held by did NOT run and a row could be deleted,
// hollowed, or swapped with the drift guard skipped. Each profile doc must fire
// implementation_jvm so jvm-ui re-runs its guard.
test('S5 view-conformance profile-doc edit runs jvm-ui (implementation_jvm true) (rf2-vxgfnd.97.3)', () => {
  const result = classify('spec/conformance/S5-view-conformance-profile.md');
  assert.equal(result.implementation_jvm, 'true');
});

test('S4 view-conformance profile-doc edit runs jvm-ui (implementation_jvm true) (rf2-vxgfnd.97.3)', () => {
  const result = classify('spec/conformance/S4-view-conformance-profile.md');
  assert.equal(result.implementation_jvm, 'true');
});

test('S3 view-conformance profile-doc edit runs jvm-ui (implementation_jvm true) (rf2-vxgfnd.97.3)', () => {
  const result = classify('spec/conformance/S3-view-conformance-profile.md');
  assert.equal(result.implementation_jvm, 'true');
});

test('a NON-profile spec .md does NOT fire implementation_jvm — no over-broadening (rf2-vxgfnd.97.3)', () => {
  const result = classify('spec/006-ReactiveSubstrate.md');
  assert.equal(result.implementation_jvm, 'false');
});

test('shadow-cljs.edn change runs cljs (it defines the node-test build) (rf2-f79t8)', () => {
  const result = classify('implementation/shadow-cljs.edn');
  assert.equal(result.cljs_node_test, 'true');
});

test('Story CLJS test-tree change runs cljs (node-test compiles tools/story/test) (rf2-f79t8)', () => {
  const result = classify('tools/story/test/re_frame/story_cljs_test.cljs');
  assert.equal(result.cljs_node_test, 'true');
});

test('Xray CLJS src change runs cljs (node-test compiles tools/xray) (rf2-f79t8)', () => {
  const result = classify('tools/xray/src/day8/re_frame2_xray/core.cljs');
  assert.equal(result.cljs_node_test, 'true');
});

// rf2-z0cw6s — tools/machines-viz is a CLJS-only tool (day8/re-frame2-machines-viz):
// its src+test are :source-paths of the consolidated :node-test AND :browser-test
// builds, so a CLJS change fires cljs (node-test) + cljs-browser, and nothing else
// (no JVM suite, no MCP wrapper, not in the deps-new template's :app).

test('machines-viz src .cljs change runs cljs + cljs-browser (rf2-z0cw6s)', () => {
  const result = classify('tools/machines-viz/src/day8/re_frame2_machines_viz/chart.cljs');
  assert.equal(result.cljs_node_test, 'true');
  assert.equal(result.cljs_browser, 'true');
});

test('machines-viz src .cljc change runs cljs + cljs-browser (rf2-z0cw6s)', () => {
  const result = classify('tools/machines-viz/src/day8/re_frame2_machines_viz/scxml.cljc');
  assert.equal(result.cljs_node_test, 'true');
  assert.equal(result.cljs_browser, 'true');
});

test('machines-viz test-tree change runs cljs + cljs-browser (rf2-z0cw6s)', () => {
  const result = classify('tools/machines-viz/test/day8/re_frame2_machines_viz/export_dom_cljs_test.cljs');
  assert.equal(result.cljs_node_test, 'true');
  assert.equal(result.cljs_browser, 'true');
});

test('machines-viz does NOT fan out to tools_jvm / mcp_conformance / template_expensive (rf2-z0cw6s)', () => {
  const result = classify('tools/machines-viz/src/day8/re_frame2_machines_viz/chart.cljs');
  assert.equal(result.tools_jvm, 'false');
  assert.equal(result.mcp_conformance, 'false');
  assert.equal(result.template_expensive, 'false');
});

test('machines-viz spec-only .md change fires nothing runtime (rf2-z0cw6s)', () => {
  const result = classify('tools/machines-viz/spec/API.md');
  assert.equal(result.cljs_node_test, 'false');
  assert.equal(result.cljs_browser, 'false');
  assert.equal(result.tools_jvm, 'false');
});

test('machines-viz deps.edn change fires cljs + cljs-browser (rf2-z0cw6s)', () => {
  const result = classify('tools/machines-viz/deps.edn');
  assert.equal(result.cljs_node_test, 'true');
  assert.equal(result.cljs_browser, 'true');
});

// The CLJS-only
// adapter / Xray / pair-MCP public surfaces live in the sidecar
// (spec/api-manifest-metadata.edn) under :cljs-only and are carried into
// spec/api-manifest.edn verbatim. Their ONLY live runtime verifier is the
// CLJS enumeration probe in the consolidated :node-test build, gated on
// cljs_node_test. A sidecar / generated-manifest / API.md change must
// therefore light cljs_node_test so the probe reconciles those rows. lint.yml
// runs the JVM generator and projection checks, not the CLJS probe.

test('API-manifest sidecar change lights cljs_node_test (CLJS probe routing) (rf2-4ka7c2)', () => {
  const result = classify('spec/api-manifest-metadata.edn');
  assert.equal(
    result.cljs_node_test,
    'true',
    'a sidecar edit must run the CLJS manifest probe (its only runtime verifier)',
  );
});

test('Generated api-manifest.edn change lights cljs_node_test (rf2-4ka7c2)', () => {
  const result = classify('spec/api-manifest.edn');
  assert.equal(result.cljs_node_test, 'true');
});

test('spec/API.md change lights cljs_node_test (rf2-4ka7c2)', () => {
  const result = classify('spec/API.md');
  assert.equal(result.cljs_node_test, 'true');
});

test('Other spec/*.md change does NOT light cljs_node_test (scope discipline) (rf2-4ka7c2)', () => {
  // The routing is scoped to the THREE manifest surfaces — an unrelated spec
  // doc must not drag the consolidated :node-test build into a docs PR.
  const result = classify('spec/006-ReactiveSubstrate.md');
  assert.equal(result.cljs_node_test, 'false');
});

// rf2-jdj17.1 — template_expensive false-green fix. The template's
// generated `:app` build transitively compiles against tools/xray
// (:devtools/preloads [day8.re-frame2-xray.preload]),
// implementation/schemas (events.cljs side-loads re-frame.schemas;
// schema.cljs calls reg-app-schema), and tools/story (the with-story
// scaffold requires re-frame.story). The ONLY PR-time gate that compiles
// the emitted `:app` (emitted_test_run_test, gated on template_expensive)
// must therefore RUN when any of those three surfaces changes — else a
// breaking change merges GREEN at PR time and surfaces only in the
// nightly cron. These assertions lock the classifier OR-ing
// template_expensive into the xray/story/schemas cases.

test('Xray src change arms template_expensive (generated :app preloads xray) (rf2-jdj17.1)', () => {
  const result = classify('tools/xray/src/day8/re_frame2_xray/preload.cljs');
  assert.equal(result.template_expensive, 'true');
});

test('Story src change arms template_expensive (with-story scaffold requires re-frame.story) (rf2-jdj17.1)', () => {
  const result = classify('tools/story/src/re_frame/story.cljs');
  assert.equal(result.template_expensive, 'true');
});

test('Schemas change arms template_expensive (generated events/schema compile against re-frame.schemas) (rf2-jdj17.1)', () => {
  const result = classify('implementation/schemas/src/re_frame/schemas.cljc');
  assert.equal(result.template_expensive, 'true');
});

test('Story spec-md-only change does NOT arm template_expensive (cannot break :app compile) (rf2-jdj17.1)', () => {
  const result = classify('tools/story/spec/002-Runtime.md');
  assert.equal(result.template_expensive, 'false');
});

test('Xray spec-md-only change does NOT arm template_expensive (rf2-jdj17.1)', () => {
  const result = classify('tools/xray/spec/017-Test-Coverage-Matrix.md');
  assert.equal(result.template_expensive, 'false');
});

test('Other per-feature artefact (machines) does NOT arm template_expensive — not in scaffold (rf2-jdj17.1)', () => {
  const result = classify('implementation/machines/src/re_frame/machines.cljc');
  assert.equal(result.template_expensive, 'false');
});

// rf2-ribu5a — epoch live-MCP-redaction routing false-green fix. The
// re-frame2-pair live fixture resolves day8/re-frame2-epoch as a
// :local/root, and the hermetic mcp-conformance-re-frame2-pair job's
// INNER_TESTS include live-re-frame2-pair-redaction.cjs (the
// egress-protection regression for the pull-mode epoch tools
// trace-window / watch-epochs). That job is gated on mcp_live='true'.
// Folded into the generic per-feature bucket, an
// implementation/epoch/src/... change set NEITHER mcp_live NOR
// mcp_conformance — so a PR breaking the epoch egress/redaction contract
// merged GREEN at PR time (a false-green on a data-leak guard). These
// assertions lock the dedicated epoch case arming the live gate, while
// keeping the OTHER per-feature artefacts off it (they have no live MCP
// dependency) so the scope stays disciplined.

test('implementation/epoch/src change arms mcp_live + mcp_conformance (live redaction gate) (rf2-ribu5a)', () => {
  const result = classify('implementation/epoch/src/re_frame/epoch.cljc');
  assert.equal(
    result.mcp_live,
    'true',
    'an epoch source change must run the live re-frame2-pair redaction gate (its only PR-time epoch egress verifier)',
  );
  assert.equal(result.mcp_conformance, 'true');
});

test('implementation/epoch change still arms the generic per-feature gates (regression) (rf2-ribu5a)', () => {
  const result = classify('implementation/epoch/src/re_frame/epoch.cljc');
  assert.equal(result.implementation_jvm, 'true');
  assert.equal(result.cljs_node_test, 'true');
  assert.equal(result.cljs_browser, 'true');
  assert.equal(result.cljs_prod, 'true');
  assert.equal(result.bundle_isolation, 'true');
});

test('implementation/epoch is NOT in the scaffold — does NOT arm template_expensive (rf2-ribu5a)', () => {
  const result = classify('implementation/epoch/src/re_frame/epoch.cljc');
  assert.equal(result.template_expensive, 'false');
});

test('Other per-feature artefacts (machines/flows/ssr) do NOT arm mcp_live — no live MCP dep (rf2-ribu5a scope)', () => {
  for (const file of [
    'implementation/machines/src/re_frame/machines.cljc',
    'implementation/flows/src/re_frame/flows.cljc',
    'implementation/ssr/src/re_frame/ssr.cljc',
  ]) {
    const result = classify(file);
    assert.equal(
      result.mcp_live,
      'false',
      `${file} has no live MCP fixture dependency; it must NOT arm mcp_live`,
    );
    assert.equal(result.mcp_conformance, 'false', `${file} must NOT arm mcp_conformance`);
  }
});

test('Epoch live-redaction job (mcp-conformance-re-frame2-pair) is gated on mcp_live (rf2-ribu5a)', () => {
  // Workflow-shape pin: the job that runs live-re-frame2-pair-redaction.cjs
  // must be job-level gated on the mcp_live output the classifier now
  // arms for epoch source changes. If the gate output is renamed, this
  // and the classifier routing must move together.
  const block = jobBlock(fs.readFileSync(WORKFLOW, 'utf8'), 'mcp-conformance-re-frame2-pair');
  assert.match(block, /needs: detect_changed_surfaces/);
  assert.match(
    block,
    /if: needs\.detect_changed_surfaces\.outputs\.mcp_live == 'true'/,
  );
});

test('tools/template change still arms template_expensive (regression) (rf2-jdj17.1)', () => {
  const result = classify('tools/template/src/day8/re_frame2_template/hooks.clj');
  assert.equal(result.template_expensive, 'true');
});

test('Core change still arms template_expensive (regression) (rf2-jdj17.1)', () => {
  const result = classify('implementation/core/src/re_frame/core.cljc');
  assert.equal(result.template_expensive, 'true');
});

test('Adapter change still arms template_expensive (regression) (rf2-jdj17.1)', () => {
  const result = classify('implementation/adapters/reagent/src/re_frame/adapter/reagent.cljs');
  assert.equal(result.template_expensive, 'true');
});

// rf2-6yuzo4 — template npm-pin lockstep false-green. hooks.clj pins
// :shadow-version + :react-version; version_lockstep_test asserts those
// emitted pins match implementation/package.json's react / react-dom /
// shadow-cljs entries (the source of truth), and the emitted-app smoke
// symlinks implementation/node_modules (populated from
// implementation/package-lock.json). Before the fix, a PR bumping those
// package.json pins or the lockfile classified template_expensive=false
// — so jvm-tools-template (the ONLY PR gate running version_lockstep_test
// + the emitted-app smoke) was skipped and the template could keep
// emitting a stale npm pin GREEN. These assertions lock the classifier
// arming template_expensive on the npm source-of-truth + its lockfile,
// while keeping shadow-cljs.edn / implementation/scripts/* off it (they
// carry no emitted npm pin).

test('implementation/package.json arms template_expensive (npm-pin lockstep) (rf2-6yuzo4)', () => {
  const result = classify('implementation/package.json');
  assert.equal(result.template_expensive, 'true');
});

test('implementation/package-lock.json arms template_expensive (emitted smoke links node_modules) (rf2-6yuzo4)', () => {
  const result = classify('implementation/package-lock.json');
  assert.equal(result.template_expensive, 'true');
});

test('implementation/shadow-cljs.edn does NOT arm template_expensive (no emitted npm pin) (rf2-6yuzo4)', () => {
  const result = classify('implementation/shadow-cljs.edn');
  assert.equal(result.template_expensive, 'false');
});

test('implementation/scripts/* does NOT arm template_expensive (no emitted npm pin) (rf2-6yuzo4)', () => {
  const result = classify('implementation/scripts/build-foo.cjs');
  assert.equal(result.template_expensive, 'false');
});

// serve-and-run-xray-feature-gate.cjs implements the Xray smoke command used by
// the story-xray-browser PR job. Editing the launcher must arm that job while
// retaining the generic implementation/scripts gates.

test('implementation/scripts/serve-and-run-xray-feature-gate.cjs fires story_xray_browser', () => {
  const result = classify('implementation/scripts/serve-and-run-xray-feature-gate.cjs');
  assert.equal(
    result.story_xray_browser,
    'true',
    'editing the Xray PR-smoke launcher must run the story-xray-browser gate it drives',
  );
});

test('implementation/scripts/serve-and-run-xray-feature-gate.cjs still arms the generic static-script gates', () => {
  const result = classify('implementation/scripts/serve-and-run-xray-feature-gate.cjs');
  assert.equal(result.cljs_node_test, 'true');
  assert.equal(result.cljs_browser, 'true');
  assert.equal(result.cljs_prod, 'true');
  assert.equal(result.bundle_isolation, 'true');
  assert.equal(result.reagent_slim_bundle, 'true');
});

test('an unrelated implementation/scripts/* file does not fire story_xray_browser', () => {
  // Only the Xray launcher drives the Xray browser gate; a generic
  // implementation/scripts/* edit must not be broadened onto it.
  const result = classify('implementation/scripts/build-foo.cjs');
  assert.equal(
    result.story_xray_browser,
    'false',
    'a generic implementation/scripts/* edit drives no browser gate; it must NOT fire story_xray_browser',
  );
});

// rf2-5v0dg7 — reagent-slim CLIENT-RUNTIME smoke routing. The
// serve-and-run-reagent-slim-smoke.cjs launcher IS the executable
// orchestration for `npm run test:reagent-slim:smoke`, the command the
// cljs-reagent-slim-bundle-isolation PR job now runs. Editing it (or its
// policy test) must fire the reagent_slim_bundle gate it drives — else a PR
// can break the launcher while avoiding the very smoke gate it orchestrates
// (the generic implementation/scripts/* case never fires reagent_slim_bundle).

test('implementation/scripts/serve-and-run-reagent-slim-smoke.cjs fires reagent_slim_bundle (rf2-5v0dg7)', () => {
  const result = classify('implementation/scripts/serve-and-run-reagent-slim-smoke.cjs');
  assert.equal(
    result.reagent_slim_bundle,
    'true',
    'editing the slim smoke launcher must run the reagent-slim gate it drives',
  );
});

test('implementation/scripts/_reagent-slim-smoke-policy.test.cjs fires reagent_slim_bundle (rf2-5v0dg7)', () => {
  const result = classify('implementation/scripts/_reagent-slim-smoke-policy.test.cjs');
  assert.equal(result.reagent_slim_bundle, 'true');
});

test('implementation/scripts/serve-and-run-reagent-slim-smoke.cjs still arms the generic static-script gates (regression) (rf2-5v0dg7)', () => {
  const result = classify('implementation/scripts/serve-and-run-reagent-slim-smoke.cjs');
  assert.equal(result.cljs_node_test, 'true');
  assert.equal(result.cljs_browser, 'true');
  assert.equal(result.cljs_prod, 'true');
  assert.equal(result.bundle_isolation, 'true');
});

test('the reagent-slim adapter/testbed surface fires reagent_slim_bundle (canonical trigger) (rf2-5v0dg7)', () => {
  // The smoke testbed lives under implementation/adapters/reagent-slim/; a
  // change there must arm the slim gate (now incl. the smoke) directly.
  const result = classify('implementation/adapters/reagent-slim/testbed/smoke.cjs');
  assert.equal(result.reagent_slim_bundle, 'true');
});

test('implementation/package.json still arms cljs_node_test (regression — it defines node deps) (rf2-6yuzo4)', () => {
  const result = classify('implementation/package.json');
  assert.equal(result.cljs_node_test, 'true');
});

// rf2-gzavkm — the standalone example-build compiler is intentionally NOT
// coupled to the broad browser surface. Core changes keep their ordinary
// browser proofs but defer the expensive all-examples compile to the nightly
// safety net; surfaces that can directly change an example build still run it
// at PR time.
test('example compilation has a dedicated changed-surface output (rf2-gzavkm)', () => {
  const directSurfaces = [
    'examples/core/counter/core.cljs',
    'implementation/shadow-cljs.edn',
    'implementation/package.json',
    'implementation/package-lock.json',
    'implementation/deps.edn',
    'implementation/adapters/uix/src/re_frame/adapter/uix.cljs',
    'implementation/ui/src/re_frame/ui/rules.cljc',
    'implementation/epoch/src/re_frame/epoch.cljc',
    'implementation/schemas/src/re_frame/schemas.cljc',
    'implementation/machines/src/re_frame/machines.cljc',
    'implementation/routing/src/re_frame/routing.cljc',
    'implementation/flows/src/re_frame/flows.cljc',
    'implementation/http/src/re_frame/http.cljc',
    'implementation/ssr/src/re_frame/ssr.cljc',
    'implementation/ssr-ring/src/re_frame/ssr/ring.clj',
    'implementation/resources/src/re_frame/resources.cljc',
    'implementation/security/src/re_frame/security.cljc',
    'implementation/scripts/check-examples-compile.cjs',
    'tools/story/src/re_frame/story.cljs',
    'tools/xray/src/day8/re_frame2_xray/preload.cljs',
    'tools/machines-viz/src/day8/re_frame2_machines_viz/chart.cljs',
  ];
  for (const file of directSurfaces) {
    assert.equal(
      classify(file).examples_compile,
      'true',
      `${file} can change the compiled example closure`,
    );
  }

  for (const file of [
    'implementation/core/src/re_frame/core.cljc',
    'implementation/scripts/check-elision.cjs',
    'testbeds/tenant_switcher/core.cljs',
    'tools/xray/test/day8/re_frame2_xray/core_test.clj',
    'tools/xray/spec/017-Test-Coverage-Matrix.md',
    'spec/006-ReactiveSubstrate.md',
  ]) {
    assert.equal(
      classify(file).examples_compile,
      'false',
      `${file} cannot change a standalone example build`,
    );
  }
});

test('cljs-examples-compile job uses the dedicated output and nightly retains full coverage (rf2-gzavkm)', () => {
  const prBlock = jobBlock(fs.readFileSync(WORKFLOW, 'utf8'), 'cljs-examples-compile');
  assert.match(
    prBlock,
    /if: needs\.detect_changed_surfaces\.outputs\.examples_compile == 'true'/,
  );
  const nightly = fs.readFileSync(EXPENSIVE_WORKFLOW, 'utf8');
  const nightlyBlock = jobBlock(nightly, 'browser-bundle-and-story-gates');
  const compile = nightlyBlock.indexOf('npm run test:examples-compile');
  const playwright = nightlyBlock.indexOf('npx playwright install --with-deps chromium');
  assert.notEqual(compile, -1, 'nightly must retain the all-examples compile');
  assert.notEqual(playwright, -1, 'nightly browser gate must install Chromium');
  assert.ok(compile < playwright, 'example compilation should fail before the browser download');
});

// rf2-k8yl5f — the re-frame2-pair skill ships a dev-only preload
// (skills/re-frame2-pair/preload/re_frame2_pair/{runtime.cljs,pure.cljc}).
// shadow-cljs.edn adds ../skills/re-frame2-pair/preload as a :source-path and
// wires re-frame2-pair.runtime into ~28 :examples/* dev builds via
// `:devtools :preloads`. `shadow-cljs compile` (the examples-compile coverage
// gate — unlike `release`) HONOURS :devtools/preloads, so a preload-only
// change compiles into every example dev build that injects it and can break
// that gate. Before this bead the classifier armed ONLY skills_structural on
// the preload path, so examples_compile=false and a preload break surfaced
// only in the unconditional nightly examples-compile net (rf2-gzavkm), never
// at PR time. These assertions lock the examples_compile arming while keeping
// skills_structural (the preload is still skill material) and holding scope:
// non-preload skill files must NOT drag in the heavy example-compile sweep.
test('re-frame2-pair PRELOAD change arms examples_compile (injected into ~28 example dev builds) (rf2-k8yl5f)', () => {
  const result = classify('skills/re-frame2-pair/preload/re_frame2_pair/runtime.cljs');
  assert.equal(
    result.examples_compile,
    'true',
    'a preload change compiles into every :examples/* dev build that injects re-frame2-pair.runtime; it must run the examples-compile gate',
  );
});

test('re-frame2-pair PRELOAD .cljc change also arms examples_compile (rf2-k8yl5f)', () => {
  const result = classify('skills/re-frame2-pair/preload/re_frame2_pair/pure.cljc');
  assert.equal(result.examples_compile, 'true');
});

test('re-frame2-pair PRELOAD change STILL arms skills_structural (regression — it is skill material) (rf2-k8yl5f)', () => {
  const result = classify('skills/re-frame2-pair/preload/re_frame2_pair/runtime.cljs');
  assert.equal(
    result.skills_structural,
    'true',
    'the preload lives under skills/re-frame2-pair/ so the structural skill gate must still fire; examples_compile widens coverage, it does not replace it',
  );
});

test('NON-preload re-frame2-pair skill file does NOT arm examples_compile (scope discipline) (rf2-k8yl5f)', () => {
  // Only the shipped preload compiles into the example builds. Other skill
  // material (SKILL.md, references, the redaction guides) must NOT drag the
  // heavy all-examples compile sweep into an ordinary skill-doc PR — it keeps
  // its existing skills_structural-only classification.
  const result = classify('skills/re-frame2-pair/SKILL.md');
  assert.equal(
    result.examples_compile,
    'false',
    'a non-preload skill file cannot change an example build; it must NOT arm examples_compile',
  );
  assert.equal(result.skills_structural, 'true');
});

// rf2-11yjq — the shipped re-frame2-pair preload is dev-only RUNTIME. Its
// stateful wrapper has TWO owning behavioral gates that examples_compile
// (compile-only, rf2-k8yl5f) and skills_structural (source-shape + pure-core
// node fixture) do NOT exercise:
//   - cljs-browser (gated on cljs_browser) discovers
//     re-frame.pair-dispatch-and-settle-dom-cljs-test, which imports
//     re-frame2-pair.runtime and drives its real React/epoch settle behavior
//     under headless Chromium.
//   - mcp-conformance-re-frame2-pair (gated on mcp_live) boots the hermetic
//     fixture with THIS exact preload and exercises live Pair operations across
//     the MCP bridge.
// Before this bead a preload-only change left both false — merging with both
// owning behavioral gates SKIPPED (caught only by the nightly net, a PR-time
// false-green). These assertions pin all four positive outputs (incl. a nested
// path), the non-preload negative, and the two job-level gate wirings.

const PRELOAD_RUNTIME_FILES = [
  'skills/re-frame2-pair/preload/re_frame2_pair/runtime.cljs',
  'skills/re-frame2-pair/preload/re_frame2_pair/pure.cljc',
  'skills/re-frame2-pair/preload/re_frame2_pair/nested/deep.cljs',
];
for (const file of PRELOAD_RUNTIME_FILES) {
  test(`${file} arms cljs_browser + mcp_live (owning behavioral gates) (rf2-11yjq)`, () => {
    const result = classify(file);
    assert.equal(
      result.cljs_browser,
      'true',
      'the preload runtime is exercised by re-frame.pair-dispatch-and-settle-dom-cljs-test under cljs-browser',
    );
    assert.equal(
      result.mcp_live,
      'true',
      'the preload boots the hermetic fixture under mcp-conformance-re-frame2-pair (gated on mcp_live)',
    );
    // regression: the rf2-k8yl5f coverage stays armed — this widens, not narrows
    assert.equal(result.examples_compile, 'true');
    assert.equal(result.skills_structural, 'true');
  });
}

test('NON-preload re-frame2-pair skill file does NOT arm cljs_browser / mcp_live (scope discipline) (rf2-11yjq)', () => {
  // Only the shipped preload RUNTIME arms the two expensive behavioral gates.
  // Ordinary skill material (SKILL.md, references, redaction guides) drives no
  // runtime + no live Pair op, so it keeps its structural-only classification.
  const result = classify('skills/re-frame2-pair/SKILL.md');
  assert.equal(
    result.cljs_browser,
    'false',
    'a non-preload skill file drives no runtime; it must NOT arm cljs_browser',
  );
  assert.equal(
    result.mcp_live,
    'false',
    'a non-preload skill file drives no live Pair op; it must NOT arm mcp_live',
  );
  assert.equal(result.skills_structural, 'true');
});

test('cljs-browser job is job-level gated on cljs_browser (browser consumer, rf2-11yjq)', () => {
  const block = jobBlock(fs.readFileSync(WORKFLOW, 'utf8'), 'cljs-browser');
  assert.match(block, /needs: detect_changed_surfaces/);
  assert.match(
    block,
    /if: needs\.detect_changed_surfaces\.outputs\.cljs_browser == 'true'/,
  );
});

test('mcp-conformance-re-frame2-pair job is job-level gated on mcp_live (live Pair consumer, rf2-11yjq)', () => {
  const block = jobBlock(
    fs.readFileSync(WORKFLOW, 'utf8'),
    'mcp-conformance-re-frame2-pair',
  );
  assert.match(block, /needs: detect_changed_surfaces/);
  assert.match(
    block,
    /if: needs\.detect_changed_surfaces\.outputs\.mcp_live == 'true'/,
  );
});

// rf2-11yjq — acceptance criterion 2: delete + both rename endpoints of the
// preload retain the same classification through the REAL Git-derived
// discovery path (the --no-renames machinery emits BOTH endpoints of a rename,
// rf2-vxgfnd.137). The preload case is pure path-pattern matching, so it arms
// the behavioral gates whether the preload endpoint arrives as an add, a
// modify, a delete, or either endpoint of a rename. (classifyViaGitDiscovery /
// renameViaGitDiscovery are defined further down; both are hoisted function
// declarations and only invoked when the test loop runs at end-of-file.)
const PRELOAD_SOURCE = 'skills/re-frame2-pair/preload/re_frame2_pair/runtime.cljs';
const PRELOAD_GATE_KEYS = ['examples_compile', 'skills_structural', 'cljs_browser', 'mcp_live'];

test('DISCOVERY: ordinary delete of a preload file arms the behavioral gates (rf2-11yjq)', () => {
  const result = classifyViaGitDiscovery(({ write, git, commit }) => {
    write(PRELOAD_SOURCE, '(ns re-frame2-pair.runtime)\n');
    write('README.md', '# scratch\n');
    commit('seed');
    git('rm', '-q', PRELOAD_SOURCE);
    commit('delete preload file');
  });
  for (const key of PRELOAD_GATE_KEYS) {
    assert.equal(result[key], 'true', `a preload delete must arm ${key} (deleted runtime endpoint)`);
  }
});

test('DISCOVERY: rename OUT of the preload subtree arms the gates for the deleted endpoint (rf2-11yjq)', () => {
  const result = renameViaGitDiscovery(PRELOAD_SOURCE, 'docs/moved-out-of-preload.cljs');
  for (const key of PRELOAD_GATE_KEYS) {
    assert.equal(
      result[key],
      'true',
      `renaming the preload OUT must still arm ${key} (the --no-renames deleted endpoint)`,
    );
  }
});

test('DISCOVERY: rename INTO the preload subtree arms the gates for the added endpoint (rf2-11yjq)', () => {
  const result = renameViaGitDiscovery('docs/incoming.cljs', PRELOAD_SOURCE);
  for (const key of PRELOAD_GATE_KEYS) {
    assert.equal(result[key], 'true', `renaming a file INTO the preload must arm ${key}`);
  }
});

// rf2-3kewru — G-12 Arm 2 shells out to `clojure -Stree`. The CLJS job
// that owns `test:ui-isolation` must therefore provision the Clojure CLI;
// Arm 1 alone cannot detect a declared-but-currently-unused adapter dep.
test('cljs job provisions Clojure CLI before the G-12 isolation gate (rf2-3kewru)', () => {
  const block = jobBlock(fs.readFileSync(WORKFLOW, 'utf8'), 'cljs');
  const setup = block.indexOf('name: Set up Clojure CLI');
  const gate = block.indexOf('npm run test:ui-isolation');
  assert.notEqual(setup, -1, 'cljs job must install Clojure CLI for G-12 Arm 2');
  assert.notEqual(gate, -1, 'cljs job must run the UI isolation gate');
  assert.ok(setup < gate, 'Clojure CLI setup must precede the G-12 isolation gate');
  // rf2-9sgj8 — the install runs the official linux-install.sh with retry
  // (resilient against the transient curl-35 / socket-hang-up that reds setup)
  // instead of DeLaGuardo/setup-clojure. rf2-e7ja9 — that body now lives in the
  // one shared script rather than inline here. It is still a real Clojure CLI
  // provision, which is all Arm 2's `clojure -Stree` needs — assert the step
  // calls the shared installer rather than pinning the (now-removed) action SHA
  // or a copied installer body.
  assert.match(
    block,
    /\.github\/scripts\/install-clojure-cli\.sh/,
    'cljs job must install the Clojure CLI for Arm 2 `clojure -Stree` (rf2-e7ja9 shared installer)',
  );
});

// rf2-e7ja9 — the resilient Clojure CLI install (rf2-9sgj8) was copied into 59
// jobs across eight workflows. It now lives in ONE script,
// `.github/scripts/install-clojure-cli.sh`, which every one of those jobs
// calls. These assertions are the structural proof that it STAYS that way: a
// reintroduced inline installer body, a resurrected setup-clojure action, or a
// relative call path all fail here rather than silently re-forking the policy.
const CLOJURE_INSTALLER = path.join(REPO_ROOT, '.github', 'scripts', 'install-clojure-cli.sh');
const WORKFLOW_DIR = path.join(REPO_ROOT, '.github', 'workflows');
const allWorkflows = () =>
  fs
    .readdirSync(WORKFLOW_DIR)
    .filter((f) => f.endsWith('.yml'))
    .map((f) => ({ name: f, text: fs.readFileSync(path.join(WORKFLOW_DIR, f), 'utf8') }));

test('the shared Clojure CLI installer exists and keeps its failure boundary (rf2-e7ja9)', () => {
  assert.ok(fs.existsSync(CLOJURE_INSTALLER), '.github/scripts/install-clojure-cli.sh must exist');
  // The workflows execute it directly (`run: "$GITHUB_WORKSPACE/..."`), so the
  // mode recorded in the index must be 100755 — a 100644 would fail all 59
  // jobs with "Permission denied". A Windows checkout has core.filemode=false
  // and will happily stage 100644, so assert git's mode rather than the
  // filesystem's (which is meaningless there).
  const mode = execFileSync('git', ['ls-files', '-s', '--', '.github/scripts/install-clojure-cli.sh'], {
    cwd: REPO_ROOT,
    encoding: 'utf8',
  }).trim();
  assert.match(
    mode,
    /^100755\s/,
    'install-clojure-cli.sh must be committed executable (100755) — the workflows run it directly',
  );
  const src = fs.readFileSync(CLOJURE_INSTALLER, 'utf8');
  // The semantics rf2-9sgj8 established, now owned in exactly one place:
  // three whole download+install attempts, curl retry, bounded backoff, sudo
  // install, and a final `clojure --version` failure boundary.
  assert.match(src, /^set -euo pipefail$/m, 'installer must run under set -euo pipefail');
  assert.match(src, /for attempt in 1 2 3; do/, 'installer must make three whole attempts');
  assert.match(
    src,
    /curl -fsSL --retry 5 --retry-all-errors --retry-delay 3/,
    'installer must keep the curl retry policy',
  );
  assert.match(
    src,
    /brew-install\/releases\/latest\/download\/linux-install\.sh/,
    'installer must fetch the official linux-install.sh',
  );
  assert.match(src, /sudo bash \/tmp\/linux-install\.sh/, 'installer must install with sudo');
  assert.match(src, /sleep "\$\(\(attempt \* 10\)\)"/, 'installer must keep the bounded backoff');
  assert.match(
    src,
    /clojure --version\s*$/,
    'installer must end on `clojure --version` — the failure boundary that stops a caller ' +
      'proceeding with a half-installed toolchain',
  );
});

test('no workflow carries an inline Clojure installer body or setup-clojure (rf2-e7ja9)', () => {
  for (const { name, text } of allWorkflows()) {
    assert.doesNotMatch(
      text,
      /brew-install\/releases\/latest\/download\/linux-install\.sh/,
      `${name} must call .github/scripts/install-clojure-cli.sh, not copy the installer body ` +
        '(rf2-e7ja9 — 59 copies of this policy is what the extraction deleted)',
    );
    assert.doesNotMatch(
      text,
      /uses:\s*\S*setup-clojure@/,
      `${name} must not use the setup-clojure action — its un-retried curl is the ` +
        'transient curl-35 / socket-hang-up flake rf2-9sgj8 removed',
    );
  }
});

test('every Clojure CLI step calls the shared installer by absolute path (rf2-e7ja9)', () => {
  let callSites = 0;
  for (const { name, text } of allWorkflows()) {
    const lines = text.split(/\r?\n/);
    lines.forEach((line, i) => {
      if (!/^\s*-\s+name:.*Set up Clojure CLI/.test(line)) return;
      callSites++;
      // The step's `run:` is the next non-blank line — the extraction leaves a
      // two-line step, so anything else means a body crept back in.
      const next = lines[i + 1] ?? '';
      assert.match(
        next,
        /^\s*run: "\$GITHUB_WORKSPACE\/\.github\/scripts\/install-clojure-cli\.sh"$/,
        `${name}:${i + 2} — a "Set up Clojure CLI" step must be exactly a call to the shared ` +
          'installer. Use the $GITHUB_WORKSPACE-absolute form: several jobs set a ' +
          '`defaults.run.working-directory` (e.g. `implementation`) under which a relative ' +
          './.github/scripts/... would not resolve.',
      );
    });
  }
  // Non-vacuity: if the steps were renamed away wholesale this test would
  // otherwise pass by iterating nothing.
  assert.ok(
    callSites >= 50,
    `expected the ~59 Clojure CLI call sites to still be present, found ${callSites}`,
  );
});

// rf2-2718r — the adapter-disposition guard scans a FIXED cross-repo roster
// (ACTIVE_AUTHORITIES: EP-0030, spec/004-Views.md, implementation/README.md,
// skills/…), not the diff. Conditional execution keyed to a diff classifier is
// a category mismatch for a guard that scans a fixed inventory — a PR editing a
// roster file may not fire the guard that pins it (the same inventory<->trigger
// bug class as rf2-d9v3n / rf2-rf7gu). The ruled fix (option (e)) moves the
// guard out of the surface-gated synthesis-docs job into the UNCONDITIONAL
// verify-readme-links job so it runs on every PR, dissolving the roster<->
// classifier sync problem with zero machinery. This arm pins the wiring:
// verify-readme-links must carry BOTH guard invocations. A future PR un-moving
// or gutting the wiring fails here (this file runs under the unconditional
// js-harness-self-tests job's test:script-policy).
test('adapter-disposition guard runs UNCONDITIONALLY in verify-readme-links (rf2-2718r)', () => {
  const workflow = fs.readFileSync(WORKFLOW, 'utf8');
  const readmeLinks = jobBlock(workflow, 'verify-readme-links');
  assert.match(
    readmeLinks,
    /python scripts\/check_adapter_disposition\.py --self-test --verbose/,
    'verify-readme-links must self-test the adapter-disposition guard (unconditional job)',
  );
  assert.match(
    readmeLinks,
    /python scripts\/check_adapter_disposition\.py --verbose --ci/,
    'verify-readme-links must run the adapter-disposition guard (unconditional job)',
  );
});

// rf2-f79t8 (a) — workflow-level shape: jvm-core + cljs must be
// job-level gated (needs + if), NOT trigger-filtered, and the
// pull_request trigger must stay unfiltered so the aggregator is always
// present.

function jobBlock(workflow, jobName) {
  // Tolerate CRLF: match the job header at 2-space indent followed by a
  // line break (\r?\n), mirroring storyXrayJobBlock's indexOf approach
  // but anchored on the exact job name.
  const header = new RegExp(`\\n {2}${jobName}:\\r?\\n`);
  const m = header.exec(workflow);
  assert.notEqual(m, null, `${jobName} job not found in test.yml`);
  const rest = workflow.slice(m.index + 1);
  const nextJob = rest.search(/\n {2}[A-Za-z0-9_-]+:\r?\n/);
  return nextJob === -1 ? rest : rest.slice(0, nextJob);
}

test('jvm-core is job-level gated on implementation_jvm (rf2-f79t8)', () => {
  const block = jobBlock(fs.readFileSync(WORKFLOW, 'utf8'), 'jvm-core');
  assert.match(block, /needs: detect_changed_surfaces/);
  assert.match(
    block,
    /if: needs\.detect_changed_surfaces\.outputs\.implementation_jvm == 'true'/,
  );
});

test('cljs (node-test) is job-level gated on cljs_node_test (rf2-f79t8)', () => {
  const block = jobBlock(fs.readFileSync(WORKFLOW, 'utf8'), 'cljs');
  assert.match(block, /needs: detect_changed_surfaces/);
  assert.match(
    block,
    /if: needs\.detect_changed_surfaces\.outputs\.cljs_node_test == 'true'/,
  );
});

test('test.yml pull_request trigger stays UNFILTERED (no PR-level paths filter) (rf2-f79t8)', () => {
  const workflow = fs.readFileSync(WORKFLOW, 'utf8');
  // The `on:` block must carry a `pull_request:` with `branches: [main]`
  // and NO `paths:`/`paths-ignore:` under it — a path-filtered PR trigger
  // would make the required aggregator absent on filtered PRs.
  const onBlock = workflow.slice(0, workflow.indexOf('\njobs:'));
  const prStart = onBlock.indexOf('  pull_request:');
  assert.notEqual(prStart, -1, 'pull_request trigger not found');
  const prBlock = onBlock.slice(prStart);
  assert.doesNotMatch(prBlock, /^\s+paths:/m);
  assert.doesNotMatch(prBlock, /^\s+paths-ignore:/m);
});

test('All required checks passed aggregator still present + needs jvm-core + cljs (rf2-f79t8)', () => {
  const workflow = fs.readFileSync(WORKFLOW, 'utf8');
  assert.match(workflow, /name: All required checks passed/);
  const block = jobBlock(workflow, 'all-required-passed');
  assert.match(block, /if: \$\{\{ always\(\) \}\}/);
  assert.match(block, /- jvm-core\r?\n/);
  // `- cljs` followed directly by a line break (not `- cljs-browser` etc.)
  assert.match(block, /- cljs\r?\n/);
});

// rf2-wa3oo — the story-xray-browser PR job now runs the PR-SMOKE
// tier, not the full sweep. It runs the Xray gate in --smoke mode and
// the single-testbed Story :play-script gate (which renders the
// assertion-strip, keeping rf2-5lw9w covered per-PR). The full sweep —
// test:story-feature-load, the non-smoke test:xray-feature-gate, and
// test:story-static — moved to the nightly expensive-tests.yml workflow.
const EXPENSIVE_WORKFLOW = path.join(
  REPO_ROOT,
  '.github',
  'workflows',
  'expensive-tests.yml',
);

// Narrow the workflow text to the story-xray-browser job block so the
// per-tier assertions can't accidentally match a step in a sibling job.
function storyXrayJobBlock(workflow) {
  const start = workflow.indexOf('\n  story-xray-browser:');
  assert.notEqual(start, -1, 'story-xray-browser job not found in test.yml');
  // The next top-level job starts at the next `\n  <name>:` at 2-space
  // indent. Find it from just after the job header. The workflow file is
  // CRLF, so the line break after the next job header is `\r\n` — match
  // `\r?\n` (mirroring jobBlock) or the search never matches and this
  // returns the whole rest-of-file, letting a later job's step satisfy a
  // story-xray-browser assertion (rf2-8ng3e1).
  const rest = workflow.slice(start + 1);
  const nextJob = rest.search(/\n {2}[A-Za-z0-9_-]+:\r?\n/);
  return nextJob === -1 ? rest : rest.slice(0, nextJob);
}

test('PR story-xray-browser job runs the Xray --smoke gate (rf2-wa3oo)', () => {
  const block = storyXrayJobBlock(fs.readFileSync(WORKFLOW, 'utf8'));
  assert.match(block, /npm run test:xray-feature-gate:smoke/);
});

test('PR story-xray-browser job keeps the Story :play-script gate (assertion-strip cover, rf2-5lw9w)', () => {
  const block = storyXrayJobBlock(fs.readFileSync(WORKFLOW, 'utf8'));
  assert.match(block, /npm run test:story-play-scripts/);
});

test('PR story-xray-browser job does NOT run the full sweep (moved to nightly, rf2-wa3oo)', () => {
  const block = storyXrayJobBlock(fs.readFileSync(WORKFLOW, 'utf8'));
  assert.doesNotMatch(block, /npm run test:story-feature-load/);
  assert.doesNotMatch(block, /npm run test:story-static/);
  // The non-smoke (full-matrix) Xray gate must not run at PR time. The
  // `:smoke` suffix is intentionally allowed; assert the bare invocation
  // (followed by end-of-line, not `:smoke`) is absent.
  assert.doesNotMatch(block, /npm run test:xray-feature-gate(?!:smoke)/);
});

test('Nightly expensive workflow runs the full Story/Xray sweep (rf2-wa3oo)', () => {
  const workflow = fs.readFileSync(EXPENSIVE_WORKFLOW, 'utf8');
  assert.match(workflow, /npm run test:story-feature-load/);
  assert.match(workflow, /npm run test:xray-feature-gate\b/);
  assert.match(workflow, /npm run test:story-static/);
  assert.match(workflow, /npm run test:story-play-scripts/);
});

test('PR + nightly Story/Xray jobs cache the shadow-cljs compile output (rf2-og36y)', () => {
  const prBlock = storyXrayJobBlock(fs.readFileSync(WORKFLOW, 'utf8'));
  assert.match(prBlock, /implementation\/\.shadow-cljs/);
  assert.match(prBlock, /story-xray-shadow-/);
  const nightly = fs.readFileSync(EXPENSIVE_WORKFLOW, 'utf8');
  assert.match(nightly, /implementation\/\.shadow-cljs/);
  assert.match(nightly, /story-xray-shadow-/);
});

// Testbed Playwright specs were migrated to CLJS/JVM unit tests
// (rf2-tglku waves), so a testbed source diff only needs the transitive
// CLJS compile coverage: top-level testbeds light `cljs_browser`, Xray
// testbeds light `story_xray_browser`, and only adapter testbeds (which
// keep a live Playwright smoke) light `adapter_testbed_smokes`.

test('top-level testbed .cljs change fires cljs_browser only (rf2-t5slp)', () => {
  const result = classify('testbeds/ssr_basic/core.cljs');
  assert.equal(result.adapter_testbed_smokes, 'false');
  assert.equal(result.cljs_browser, 'true');
});

test('Xray testbed .cljs change fires story_xray_browser only (rf2-t5slp)', () => {
  const result = classify('tools/xray/testbeds/feature_matrix/core.cljs');
  assert.equal(result.adapter_testbed_smokes, 'false');
  assert.equal(result.story_xray_browser, 'true');
});

test('Adapter source change fires adapter_testbed_smokes (rf2-t5slp regression guard)', () => {
  const result = classify('implementation/adapters/reagent/testbed/core.cljs');
  assert.equal(result.adapter_testbed_smokes, 'true');
});

// The adapter-smoke harness (orchestrator + runner + shared manifest) lives
// under implementation/adapters/scripts/ — with the adapters it drives. A
// harness-script edit fires ONLY adapter_testbed_smokes (its dedicated case),
// not the broad adapter-source fan-out the rest of implementation/adapters/*
// triggers.
const ADAPTER_HARNESS_FILES = [
  'implementation/adapters/scripts/serve-and-run-adapter-smokes.cjs',
  'implementation/adapters/scripts/run-adapter-smokes.cjs',
  'implementation/adapters/scripts/adapter-smoke-filter.cjs',
];
for (const file of ADAPTER_HARNESS_FILES) {
  test(`${file} (adapter-smoke harness) fires adapter_testbed_smokes`, () => {
    const result = classify(file);
    assert.equal(result.adapter_testbed_smokes, 'true');
  });
}

// A harness-script edit must NOT trip the full adapter-SOURCE fan-out
// (implementation_jvm / tools_jvm / mcp_conformance / template_expensive),
// which the broad implementation/adapters/* case fires for an actual adapter
// source change. The dedicated harness case keeps the tier tight.
test('adapter-smoke harness edit fires ONLY adapter_testbed_smokes, not the adapter-source fan-out', () => {
  const result = classify('implementation/adapters/scripts/serve-and-run-adapter-smokes.cjs');
  assert.equal(result.adapter_testbed_smokes, 'true');
  assert.notEqual(result.implementation_jvm, 'true', 'harness edit must not fire implementation_jvm');
  assert.notEqual(result.tools_jvm, 'true', 'harness edit must not fire tools_jvm');
  assert.notEqual(result.mcp_conformance, 'true', 'harness edit must not fire mcp_conformance');
});

// rf2-y9o5e3 — every EXECUTABLE examples/scripts gate file must fire the
// browser gate it drives, so a PR breaking a launcher / shared port
// resolver can't avoid the gate it can break. The two adapter-smoke helpers
// that STAY under examples/scripts/ (the example dev runner + Story launchers
// share them) — spec-helpers.cjs (the Playwright assertion matchers) and
// examples-port.cjs (the port resolver) — fire adapter_testbed_smokes; the
// Story launchers + their dedicated port resolver fire story_xray_browser;
// the SHARED port-resolver.cjs fires BOTH. Static-only scanners
// (check-examples-assets.cjs, check-reagent-slim-boundary.cjs) stay on the
// always-on JS harness path (cljs_browser only) — they have always-on
// .test.cjs coverage under test:script-policy and drive no browser gate.

const ADAPTER_SMOKE_GATE_FILES = [
  'examples/scripts/spec-helpers.cjs',
  'examples/scripts/examples-port.cjs',
];
for (const file of ADAPTER_SMOKE_GATE_FILES) {
  test(`${file} fires adapter_testbed_smokes (rf2-y9o5e3)`, () => {
    const result = classify(file);
    assert.equal(result.adapter_testbed_smokes, 'true');
  });
}

const STORY_GATE_FILES = [
  'examples/scripts/serve-and-run-story-feature-load-tests.cjs',
  'examples/scripts/run-story-feature-load-tests.cjs',
  'examples/scripts/serve-and-run-story-play-scripts.cjs',
  'examples/scripts/story-feature-load-port.cjs',
];
for (const file of STORY_GATE_FILES) {
  test(`${file} fires story_xray_browser (rf2-y9o5e3)`, () => {
    const result = classify(file);
    assert.equal(result.story_xray_browser, 'true');
  });
}

test('examples/scripts/port-resolver.cjs (shared resolver) fires BOTH browser gates (rf2-y9o5e3)', () => {
  const result = classify('examples/scripts/port-resolver.cjs');
  assert.equal(result.adapter_testbed_smokes, 'true');
  assert.equal(result.story_xray_browser, 'true');
});

// rf2-eqjxya — examples-staging.cjs is the SHARED staging/cleaning helper
// (stageShared / cleanStageDirs / stageExample) require'd by the adapter-smoke
// orchestrator (serve-and-run-adapter-smokes.cjs → adapter_testbed_smokes) AND
// both Story launchers (serve-and-run-story-{feature-load-tests,play-scripts}.cjs
// → story_xray_browser). Like the shared port-resolver.cjs above, it must fire
// BOTH browser gates so a regression in the staging code can't ship green by
// skipping the gates that serve its staged output.
test('examples/scripts/examples-staging.cjs (shared staging helper) fires BOTH browser gates (rf2-eqjxya)', () => {
  const result = classify('examples/scripts/examples-staging.cjs');
  assert.equal(
    result.adapter_testbed_smokes,
    'true',
    'examples-staging.cjs is imported by the adapter-smoke orchestrator; it must fire adapter_testbed_smokes',
  );
  assert.equal(
    result.story_xray_browser,
    'true',
    'examples-staging.cjs is imported by both Story launchers; it must fire story_xray_browser',
  );
});

// rf2-78th1g — examples-asset-manifest.cjs is the single side-effect-free
// OWNER of every examples external-asset EXCEPTION (rf2-phpbo8). Its
// stagedAssetsByBuild projection is require'd by examples-staging.cjs — the
// shared staging helper that itself fires BOTH browser gates (rf2-eqjxya) — so
// a regression in the manifest data or its projection can break the staged
// output those gates serve before they run. Before this bead a manifest-only
// PR fell through to the generic examples/* case (cljs_browser + cljs_node_test),
// skipping both Playwright gates it underpins. Mirror the examples-staging.cjs
// case: fire BOTH browser gates.
test('examples/scripts/examples-asset-manifest.cjs (staging asset manifest) fires BOTH browser gates (rf2-78th1g)', () => {
  const result = classify('examples/scripts/examples-asset-manifest.cjs');
  assert.equal(
    result.adapter_testbed_smokes,
    'true',
    'the manifest underpins examples-staging.cjs (imported by the adapter-smoke orchestrator); it must fire adapter_testbed_smokes',
  );
  assert.equal(
    result.story_xray_browser,
    'true',
    'the manifest underpins examples-staging.cjs (imported by both Story launchers); it must fire story_xray_browser',
  );
});

test('examples/scripts static-only scanners stay on the always-on JS harness path, no browser gate (rf2-y9o5e3)', () => {
  for (const file of [
    'examples/scripts/check-examples-assets.cjs',
    'examples/scripts/check-reagent-slim-boundary.cjs',
  ]) {
    const result = classify(file);
    assert.equal(
      result.adapter_testbed_smokes,
      'false',
      `${file} is a static scanner with always-on .test.cjs coverage; it must NOT fire adapter_testbed_smokes`,
    );
    assert.equal(
      result.story_xray_browser,
      'false',
      `${file} is a static scanner; it must NOT fire story_xray_browser`,
    );
  }
});

test('adapter-testbed-smokes workflow remains scoped to ADAPTER_SMOKE_FILTER=adapters/ (rf2-t5slp)', () => {
  const workflow = fs.readFileSync(WORKFLOW, 'utf8');
  // Verify the adapter-testbed-smokes job still passes the narrow
  // adapters/ filter — the re-frame.ui substrate smoke has its OWN job
  // (ui-smoke, ADAPTER_SMOKE_FILTER=ui/testbed; rf2-nojiwy), so the
  // adapter job must never widen onto it.
  assert.match(
    workflow,
    /adapter-testbed-smokes:[\s\S]*ADAPTER_SMOKE_FILTER:\s*"adapters\/"/,
  );
});

// rf2-nojiwy — the four-suites rule's new-UI smoke. The re-frame.ui
// substrate testbed (implementation/ui/testbed/) rides the shared
// adapter-smoke orchestrator under its own classifier output (ui_smoke) and
// its own CI job (ui-smoke, ADAPTER_SMOKE_FILTER=ui/testbed). The trigger
// discipline mirrors adapter_testbed_smokes: direct substrate-source +
// smoke-harness changes fire it; core / adapter-source / generic
// build-config changes do not (nightly runs the unfiltered sweep).

test('implementation/ui source change fires ui_smoke (rf2-nojiwy)', () => {
  const result = classify('implementation/ui/src/re_frame/ui/runtime.cljs');
  assert.equal(result.ui_smoke, 'true');
});

test('implementation/ui testbed change fires ui_smoke (rf2-nojiwy)', () => {
  for (const file of [
    'implementation/ui/testbed/ui_testbed/core.cljs',
    'implementation/ui/testbed/spec.cjs',
    'implementation/ui/testbed/index.html',
  ]) {
    const result = classify(file);
    assert.equal(result.ui_smoke, 'true', `${file} must fire ui_smoke`);
  }
});

test('adapter-smoke harness files fire ui_smoke too — the ui smoke rides the same orchestrator (rf2-nojiwy)', () => {
  for (const file of ADAPTER_HARNESS_FILES) {
    const result = classify(file);
    assert.equal(result.ui_smoke, 'true', `${file} must fire ui_smoke`);
  }
});

test('shared examples/scripts helpers fire ui_smoke alongside adapter_testbed_smokes (rf2-nojiwy)', () => {
  for (const file of [
    'examples/scripts/spec-helpers.cjs',
    'examples/scripts/examples-port.cjs',
    'examples/scripts/port-resolver.cjs',
    'examples/scripts/examples-staging.cjs',
    'examples/scripts/examples-asset-manifest.cjs',
  ]) {
    const result = classify(file);
    assert.equal(result.ui_smoke, 'true', `${file} must fire ui_smoke`);
  }
});

test('core / adapter-source / build-config changes do NOT fire ui_smoke (rf2-nojiwy)', () => {
  for (const file of [
    'implementation/core/src/re_frame/core.cljc',
    'implementation/adapters/reagent/src/re_frame/adapter/reagent.cljs',
    'implementation/adapters/reagent/testbed/adapter_testbed_reagent/core.cljs',
    'implementation/shadow-cljs.edn',
    'implementation/scripts/run-ui-bench.cjs',
  ]) {
    const result = classify(file);
    assert.equal(result.ui_smoke, 'false', `${file} must NOT fire ui_smoke`);
  }
});

test('adapter source change still fires adapter_testbed_smokes without ui_smoke; ui source is the mirror image (rf2-nojiwy)', () => {
  const adapter = classify('implementation/adapters/uix/testbed/adapter_testbed_uix/core.cljs');
  assert.equal(adapter.adapter_testbed_smokes, 'true');
  assert.equal(adapter.ui_smoke, 'false');
  const ui = classify('implementation/ui/testbed/spec.cjs');
  assert.equal(ui.ui_smoke, 'true');
  assert.equal(ui.adapter_testbed_smokes, 'false');
});

test('ui-smoke job is gated on ui_smoke and scoped to ADAPTER_SMOKE_FILTER=ui/testbed (rf2-nojiwy)', () => {
  const workflow = fs.readFileSync(WORKFLOW, 'utf8');
  const block = jobBlock(workflow, 'ui-smoke');
  assert.match(block, /needs: detect_changed_surfaces/);
  assert.match(
    block,
    /if: needs\.detect_changed_surfaces\.outputs\.ui_smoke == 'true'/,
  );
  assert.match(block, /ADAPTER_SMOKE_FILTER:\s*"ui\/testbed"/);
  assert.match(block, /npm run test:adapter-smokes/);
});

test('all-required-passed aggregator needs ui-smoke (rf2-nojiwy)', () => {
  const workflow = fs.readFileSync(WORKFLOW, 'utf8');
  const block = jobBlock(workflow, 'all-required-passed');
  assert.match(block, /- ui-smoke\r?\n/);
});

test('detect_changed_surfaces publishes the ui_smoke output (rf2-nojiwy)', () => {
  const workflow = fs.readFileSync(WORKFLOW, 'utf8');
  const block = jobBlock(workflow, 'detect_changed_surfaces');
  assert.match(
    block,
    /ui_smoke: \$\{\{ steps\.detect\.outputs\.ui_smoke \}\}/,
  );
});

// rf2-dxndhc — resources + cross-conformance tier routing false-green
// fix (review wave rf2-ks67un). The resources artefact and the three
// EP cross-conformance tiers (reply / derivation / event) are live
// implementation test surfaces on the root CLJS/test classpath
// (implementation/deps.edn + shadow-cljs.edn), but the classifier had
// NO case for them — a PR touching only one left every output false, so
// the aggregator could pass with the relevant JVM + consolidated
// node-test gates skipped. These assertions lock the new routing.

test('implementation/resources/* arms implementation_jvm + the CLJS surfaces (rf2-dxndhc)', () => {
  const result = classify('implementation/resources/src/re_frame/resources.cljc');
  assert.equal(
    result.implementation_jvm,
    'true',
    'a resources source change must run the jvm-resources suite (its own :test alias)',
  );
  assert.equal(result.cljs_node_test, 'true');
  assert.equal(result.cljs_browser, 'true');
  assert.equal(result.cljs_prod, 'true');
  assert.equal(result.bundle_isolation, 'true');
});

test('implementation/resources/deps.edn arms implementation_jvm + cljs_node_test (rf2-dxndhc)', () => {
  const result = classify('implementation/resources/deps.edn');
  assert.equal(result.implementation_jvm, 'true');
  assert.equal(result.cljs_node_test, 'true');
});

const CONFORMANCE_TIERS = [
  'implementation/reply-conformance/test/re_frame/reply_vocabulary_conformance_cljs_test.cljc',
  'implementation/derivation-conformance/test/re_frame/derivation_algebra_conformance_cljs_test.cljc',
  'implementation/event-conformance/test/re_frame/event_model_conformance_cljs_test.cljc',
];
for (const file of CONFORMANCE_TIERS) {
  test(`${file} arms implementation_jvm + cljs_node_test (cross-conformance tier, rf2-dxndhc)`, () => {
    const result = classify(file);
    assert.equal(
      result.implementation_jvm,
      'true',
      'a conformance-tier change must run the JVM :clj-arm job for that tier',
    );
    assert.equal(
      result.cljs_node_test,
      'true',
      'a conformance-tier change must run the consolidated :node-test :cljs arm',
    );
  });
}

test('a src-less conformance tier does NOT widen production bundles (no bundle_isolation/cljs_browser) (rf2-dxndhc scope)', () => {
  // The tiers ship no production src (no Maven artefact), so — like the
  // security tier — they must NOT fire the bundle/browser/prod gates.
  const result = classify('implementation/reply-conformance/test/foo.cljc');
  assert.equal(result.bundle_isolation, 'false');
  assert.equal(result.cljs_browser, 'false');
  assert.equal(result.cljs_prod, 'false');
});

test('jvm-resources is job-level gated on implementation_jvm (rf2-dxndhc)', () => {
  const block = jobBlock(fs.readFileSync(WORKFLOW, 'utf8'), 'jvm-resources');
  assert.match(block, /needs: detect_changed_surfaces/);
  assert.match(
    block,
    /if: needs\.detect_changed_surfaces\.outputs\.implementation_jvm == 'true'/,
  );
});

for (const job of [
  'jvm-reply-conformance',
  'jvm-derivation-conformance',
  'jvm-event-conformance',
]) {
  test(`${job} is job-level gated on implementation_jvm (rf2-dxndhc)`, () => {
    const block = jobBlock(fs.readFileSync(WORKFLOW, 'utf8'), job);
    assert.match(block, /needs: detect_changed_surfaces/);
    assert.match(
      block,
      /if: needs\.detect_changed_surfaces\.outputs\.implementation_jvm == 'true'/,
    );
  });
}

test('all-required-passed aggregator needs the four new implementation_jvm jobs (rf2-dxndhc)', () => {
  const block = jobBlock(fs.readFileSync(WORKFLOW, 'utf8'), 'all-required-passed');
  for (const job of [
    'jvm-resources',
    'jvm-reply-conformance',
    'jvm-derivation-conformance',
    'jvm-event-conformance',
  ]) {
    assert.match(
      block,
      new RegExp(`- ${job}\\r?\\n`),
      `aggregator must list ${job} in needs:`,
    );
  }
});

// rf2-am7grp — quiet-reporter routing false-green fix (review wave
// rf2-ks67un). implementation/test-quiet is the test-runtime
// quiet-reporter artefact: the JVM runner (runner.clj, the :main-opts of
// every per-artefact :test alias) AND the CLJS shadow-node runner (the
// :node-test build's :main). The classifier had NO test-quiet/** case,
// so a PR changing the reporter implementation, its runners, its
// deps.edn, or its contract tests left every output false — both the JVM
// quiet-runner contract and the CLJS node-test quiet reporter contract
// skipped, with js-harness-self-tests (JS script policy/helper tests
// only) the sole insufficient verifier. These assertions lock the new
// routing onto implementation_jvm + cljs_node_test.

const TEST_QUIET_FILES = [
  'implementation/test-quiet/src/re_frame/test_quiet/runner.clj',
  'implementation/test-quiet/src/re_frame/test_quiet/shadow_node.cljs',
  'implementation/test-quiet/deps.edn',
  'implementation/test-quiet/test/re_frame/test_quiet_runner_contract_test.clj',
  'implementation/test-quiet/test/re_frame/test_quiet_shadow_node_cljs_test.cljs',
];
for (const file of TEST_QUIET_FILES) {
  test(`${file} arms implementation_jvm + cljs_node_test (quiet reporter, rf2-am7grp)`, () => {
    const result = classify(file);
    assert.equal(
      result.implementation_jvm,
      'true',
      'a quiet-reporter change must re-run the per-artefact :test aliases (which route through the JVM quiet runner)',
    );
    assert.equal(
      result.cljs_node_test,
      'true',
      'a quiet-reporter change must run the consolidated :node-test build (whose :main is the CLJS quiet runner)',
    );
  });
}

test('test-quiet routing does NOT broaden docs/spec-only or unrelated surfaces (rf2-am7grp scope)', () => {
  // Scoped to src/test/deps.edn under test-quiet; a generic spec/docs
  // change stays off the implementation gates entirely.
  const result = classify('spec/006-ReactiveSubstrate.md');
  assert.equal(result.implementation_jvm, 'false');
  assert.equal(result.cljs_node_test, 'false');
});

// rf2-h5e3v7 — tenant-switcher testbed smoke routing. The runner
// serve-and-run-tenant-switcher-testbed.cjs IS the executable
// orchestration for `npm run test:testbed-tenant-switcher`, the command
// the new tenant-switcher-testbed-smoke PR job runs (test.yml). It is the
// ONLY Playwright smoke that exercises the tenant-switcher browser
// scenario. Before this bead the npm script was defined in package.json
// but invoked by NO workflow, and the classifier routed both the runner
// (generic implementation/scripts/*) and the testbed (generic testbeds/*)
// only to always-on CLJS surfaces — so a regression in the runner, its
// colocated spec, or the testbed could ship green. These assertions lock
// the new tenant_switcher_smoke routing onto the runner + the testbed,
// while keeping unrelated implementation/scripts/* + testbeds/* off it.

test('serve-and-run-tenant-switcher-testbed.cjs fires tenant_switcher_smoke (rf2-h5e3v7)', () => {
  const result = classify(
    'implementation/scripts/serve-and-run-tenant-switcher-testbed.cjs',
  );
  assert.equal(
    result.tenant_switcher_smoke,
    'true',
    'editing the tenant-switcher smoke launcher must run the gate it drives',
  );
});

test('serve-and-run-tenant-switcher-testbed.cjs still arms the generic static-script gates (regression) (rf2-h5e3v7)', () => {
  const result = classify(
    'implementation/scripts/serve-and-run-tenant-switcher-testbed.cjs',
  );
  assert.equal(result.cljs_node_test, 'true');
  assert.equal(result.cljs_browser, 'true');
  assert.equal(result.cljs_prod, 'true');
  assert.equal(result.bundle_isolation, 'true');
});

const TENANT_SWITCHER_TESTBED_FILES = [
  'testbeds/tenant_switcher/spec.cjs',
  'testbeds/tenant_switcher/core.cljs',
  'testbeds/tenant_switcher/index.html',
];
for (const file of TENANT_SWITCHER_TESTBED_FILES) {
  test(`${file} fires tenant_switcher_smoke + cljs_browser (rf2-h5e3v7)`, () => {
    const result = classify(file);
    assert.equal(
      result.tenant_switcher_smoke,
      'true',
      'a tenant-switcher testbed change must run its colocated browser smoke',
    );
    assert.equal(result.cljs_browser, 'true');
  });
}

test('an UNRELATED implementation/scripts/* file does NOT fire tenant_switcher_smoke (scope discipline) (rf2-h5e3v7)', () => {
  const result = classify('implementation/scripts/build-foo.cjs');
  assert.equal(
    result.tenant_switcher_smoke,
    'false',
    'a generic implementation/scripts/* edit drives no tenant-switcher gate',
  );
});

test('an UNRELATED top-level testbed does NOT fire tenant_switcher_smoke (scope discipline) (rf2-h5e3v7)', () => {
  const result = classify('testbeds/ssr_basic/core.cljs');
  assert.equal(
    result.tenant_switcher_smoke,
    'false',
    'only the tenant-switcher testbed carries a colocated Playwright smoke',
  );
  // It still gets the always-on transitive CLJS coverage.
  assert.equal(result.cljs_browser, 'true');
});

test('tenant-switcher-testbed-smoke job is job-level gated on tenant_switcher_smoke (rf2-h5e3v7)', () => {
  const block = jobBlock(
    fs.readFileSync(WORKFLOW, 'utf8'),
    'tenant-switcher-testbed-smoke',
  );
  assert.match(block, /needs: detect_changed_surfaces/);
  assert.match(
    block,
    /if: needs\.detect_changed_surfaces\.outputs\.tenant_switcher_smoke == 'true'/,
  );
  // The job must actually RUN the npm script the gate exists to drive —
  // pinning the wiring so the script cannot drift out of CI again.
  assert.match(block, /npm run test:testbed-tenant-switcher/);
});

test('all-required-passed aggregator needs tenant-switcher-testbed-smoke (rf2-h5e3v7)', () => {
  const block = jobBlock(fs.readFileSync(WORKFLOW, 'utf8'), 'all-required-passed');
  assert.match(
    block,
    /- tenant-switcher-testbed-smoke\r?\n/,
    'aggregator must list tenant-switcher-testbed-smoke in needs:',
  );
});

// rf2-vxgfnd.6 — the re-frame.ui compiled-view substrate's surfaces.
// Before this case a ui-only PR left every output false (the whole
// artefact's suites + the S1f parity corpus + the G-1/G-14 gates all
// skipped — a false-green hole).

test('implementation/ui source change arms jvm + node-test + ui_gates (rf2-vxgfnd.6)', () => {
  const result = classify('implementation/ui/src/re_frame/ui/rules.cljc');
  assert.equal(result.implementation_jvm, 'true');
  assert.equal(result.cljs_node_test, 'true');
  assert.equal(result.ui_gates, 'true');
  assert.notEqual(result.story_xray_browser, 'true');
  assert.notEqual(result.adapter_testbed_smokes, 'true');
});

test('implementation/ui bench/test changes arm the same trio (rf2-vxgfnd.6)', () => {
  const result = classify('implementation/ui/bench/re_frame/ui/bench/main.cljs');
  assert.equal(result.ui_gates, 'true');
  const result2 = classify('implementation/ui/test/re_frame/ui/parity_fixtures.cljc');
  assert.equal(result2.implementation_jvm, 'true');
  assert.equal(result2.cljs_node_test, 'true');
});

test('build-config trio arms ui_gates; generic scripts do not (rf2-vxgfnd.6)', () => {
  assert.equal(classify('implementation/shadow-cljs.edn').ui_gates, 'true');
  assert.equal(classify('implementation/package.json').ui_gates, 'true');
  assert.equal(classify('implementation/package-lock.json').ui_gates, 'true');
  assert.notEqual(classify('implementation/scripts/check-elision.cjs').ui_gates, 'true');
});

test('the G-1 launcher script fires the gate it drives (rf2-vxgfnd.6)', () => {
  const result = classify('implementation/scripts/run-ui-bench.cjs');
  assert.equal(result.ui_gates, 'true');
  // widens, never narrows: the generic scripts surfaces stay armed
  assert.equal(result.cljs_node_test, 'true');
  assert.equal(result.bundle_isolation, 'true');
});

test('the G-13 launcher script fires the gate it drives (rf2-vxgfnd.12.3)', () => {
  const result = classify('implementation/scripts/run-ui-g13.cjs');
  assert.equal(result.ui_gates, 'true');
  assert.equal(result.cljs_node_test, 'true');
  assert.equal(result.cljs_browser, 'true');
  assert.equal(result.bundle_isolation, 'true');
});

test('the UI isolation checker fires the focused gate it implements', () => {
  const result = classify('implementation/scripts/check-ui-adapter-isolation.cjs');
  assert.equal(
    result.cljs_node_test,
    'true',
    'the checker-only PR must start the cljs job that owns the focused step',
  );
  assert.equal(
    result.ui_gates,
    'true',
    'the checker-only PR must satisfy the focused step condition',
  );

  const block = jobBlock(fs.readFileSync(WORKFLOW, 'utf8'), 'cljs');
  assert.match(block, /name: re-frame\.ui focused adapter-artifact isolation/);
  assert.match(
    block,
    /if: needs\.detect_changed_surfaces\.outputs\.ui_gates == 'true'/,
  );
  assert.match(block, /npm run test:ui-isolation/);
});

test('spec-only changes do not arm ui_gates (rf2-vxgfnd.6)', () => {
  assert.notEqual(classify('spec/Conventions.md').ui_gates, 'true');
});

test('jvm-ui is job-level gated on implementation_jvm (rf2-vxgfnd.6)', () => {
  const block = jobBlock(fs.readFileSync(WORKFLOW, 'utf8'), 'jvm-ui');
  assert.match(block, /needs: detect_changed_surfaces/);
  assert.match(
    block,
    /if: needs\.detect_changed_surfaces\.outputs\.implementation_jvm == 'true'/,
  );
  assert.match(block, /working-directory: implementation\/ui/);
});

test('cljs-ui-g1 is job-level gated on ui_gates and runs the gate script (rf2-vxgfnd.6)', () => {
  const block = jobBlock(fs.readFileSync(WORKFLOW, 'utf8'), 'cljs-ui-g1');
  assert.match(block, /needs: detect_changed_surfaces/);
  assert.match(
    block,
    /if: needs\.detect_changed_surfaces\.outputs\.ui_gates == 'true'/,
  );
  assert.match(block, /npm run test:ui-g1/);
});

test('cljs-ui-g13 is job-level gated and runs the exact-count browser gate', () => {
  const block = jobBlock(fs.readFileSync(WORKFLOW, 'utf8'), 'cljs-ui-g13');
  assert.match(block, /needs: detect_changed_surfaces/);
  assert.match(
    block,
    /if: needs\.detect_changed_surfaces\.outputs\.ui_gates == 'true'/,
  );
  assert.match(block, /npm run test:ui-g13/);
  assert.match(block, /playwright install --with-deps chromium/);
  assert.match(block, /if: \$\{\{ always\(\) \}\}/);
  assert.match(block, /implementation\/out\/ui-g13\.json/);
});

test('all-required-passed aggregator needs jvm-ui + cljs-ui-g1 (rf2-vxgfnd.6)', () => {
  const block = jobBlock(fs.readFileSync(WORKFLOW, 'utf8'), 'all-required-passed');
  assert.match(block, /- jvm-ui\r?\n/, 'aggregator must list jvm-ui in needs:');
  assert.match(block, /- cljs-ui-g1\r?\n/, 'aggregator must list cljs-ui-g1 in needs:');
  assert.match(block, /- cljs-ui-g13\r?\n/, 'aggregator must list cljs-ui-g13 in needs:');
});

test('cljs-ui-g8 is job-level gated and runs the dual-engine controlled-input gate (rf2-vxgfnd.95.10)', () => {
  const block = jobBlock(fs.readFileSync(WORKFLOW, 'utf8'), 'cljs-ui-g8');
  assert.match(block, /needs: detect_changed_surfaces/);
  assert.match(
    block,
    /if: needs\.detect_changed_surfaces\.outputs\.ui_gates == 'true'/,
  );
  assert.match(block, /npm run test:ui-g8/);
  // G-8 is the only gate that MUST install BOTH real engines.
  assert.match(block, /playwright install --with-deps chromium webkit/);
  assert.match(block, /implementation\/out\/ui-g8\.json/);
});

test('all-required-passed aggregator needs cljs-ui-g8 (rf2-vxgfnd.95.10)', () => {
  const block = jobBlock(fs.readFileSync(WORKFLOW, 'utf8'), 'all-required-passed');
  assert.match(block, /- cljs-ui-g8\r?\n/, 'aggregator must list cljs-ui-g8 in needs:');
});

test('run-ui-g8.cjs launcher change arms ui_gates (rf2-vxgfnd.95.10)', () => {
  assert.equal(classify('implementation/scripts/run-ui-g8.cjs').ui_gates, 'true');
});

// rf2-kxork — G-18 library facade isolation promoted into the required matrix.
// The checker was donated RED (#6182) and parked outside CI; #6195 repaired the
// DCE mechanism and it now passes, so it becomes a standing regression net.
// These three tests are the wiring's own proof: the classifier must arm the
// gate, the job must be surface-gated and actually run it, and the required
// aggregator must depend on it. Remove any one of those and a test reds.

test('the G-18 facade-isolation checker fires the gate it implements (rf2-kxork)', () => {
  const result = classify('implementation/scripts/check-ui-facade-isolation.cjs');
  assert.equal(
    result.ui_gates,
    'true',
    'a checker-only PR must satisfy the cljs-ui-facade-isolation job condition',
  );
  // widens, never narrows: the generic scripts surface stays armed
  assert.equal(result.cljs_node_test, 'true');
});

test('implementation/ui proof-pack + DCE surface arms G-18 (rf2-kxork)', () => {
  // The library the gate imports from, and the compiler/runtime surface whose
  // DCE behaviour it measures, both live under implementation/ui/**.
  assert.equal(
    classify('implementation/ui/proof-pack/re_frame/ui/proof_pack/library.cljs').ui_gates,
    'true',
  );
  assert.equal(
    classify('implementation/ui/src/re_frame/ui/compiler/emit_cljs.cljc').ui_gates,
    'true',
  );
  assert.equal(
    classify('implementation/ui/src/re_frame/ui/runtime.cljs').ui_gates,
    'true',
  );
  // the two proof-pack builds are declared in the build-config trio
  assert.equal(classify('implementation/shadow-cljs.edn').ui_gates, 'true');
});

test('cljs-ui-facade-isolation is surface-gated and runs the G-18 gate (rf2-kxork)', () => {
  const block = jobBlock(fs.readFileSync(WORKFLOW, 'utf8'), 'cljs-ui-facade-isolation');
  assert.match(block, /needs: detect_changed_surfaces/);
  assert.match(
    block,
    /if: needs\.detect_changed_surfaces\.outputs\.ui_gates == 'true'/,
  );
  assert.match(block, /npm run test:ui-facade-isolation/);
});

test('all-required-passed aggregator needs cljs-ui-facade-isolation (rf2-kxork)', () => {
  const block = jobBlock(fs.readFileSync(WORKFLOW, 'utf8'), 'all-required-passed');
  assert.match(
    block,
    /- cljs-ui-facade-isolation\r?\n/,
    'aggregator must list cljs-ui-facade-isolation in needs:',
  );
});

// rf2-vxgfnd.90 — re-frame.ui now ships REAL DOM tests
// (`*-dom-cljs-test.{cljs,cljc}`) in the `:browser-test` build (the S1c/S2
// mount + reactivity + frame-scope keystone fixtures — the ONLY place
// React act discipline / real react-dom/client roots / live ViewCell
// teardown are exercised). The `implementation/ui/*` case previously left
// cljs_browser=false on a stale "no production build :requires
// re-frame.ui.* yet" comment, so a test-only UI PR (e.g. #5767, which
// changed exactly one `*-dom-cljs-test`) merged GREEN while its only
// relevant browser test reported SKIPPED — a false-green hole. The gate
// now fires cljs_browser for EVERY implementation/ui/** source or test
// change (conservative: trigger the browser gate MORE, never less).
// rf2-vxgfnd.12.2 adds a mounted generated-view :advanced production control
// for the override/provider carriage, so cljs_prod and bundle_isolation are now
// part of every UI change's proof surface too.

test('implementation/ui SOURCE change fires cljs_browser (transitive DOM-test coverage) (rf2-vxgfnd.90)', () => {
  const result = classify('implementation/ui/src/re_frame/ui/reactive.cljc');
  assert.equal(
    result.cljs_browser,
    'true',
    'a UI runtime source change affects the *-dom-cljs-test namespaces transitively; it must run the browser gate',
  );
  // Regression: the rf2-vxgfnd.6 trio stays armed alongside the new browser gate.
  assert.equal(result.implementation_jvm, 'true');
  assert.equal(result.cljs_node_test, 'true');
  assert.equal(result.ui_gates, 'true');
});

test('the frame-scope keystone DOM test fires cljs_browser (the #5767 false-green path) (rf2-vxgfnd.90)', () => {
  const result = classify(
    'implementation/ui/test/re_frame/ui/frame_scope_resolve_dom_cljs_test.cljs',
  );
  assert.equal(
    result.cljs_browser,
    'true',
    'a *-dom-cljs-test change must run the :browser-test gate instead of reporting SKIPPED (the exact #5767 regression)',
  );
});

test('representative other *-dom-cljs-test paths fire cljs_browser (rf2-vxgfnd.90)', () => {
  for (const file of [
    'implementation/ui/test/re_frame/ui/root_mount_dom_cljs_test.cljs',
    'implementation/ui/test/re_frame/ui/root_teardown_dom_cljs_test.cljs',
    'implementation/ui/test/re_frame/ui/reactive_tear_browser_dom_cljs_test.cljs',
    'implementation/ui/test/re_frame/ui/preflight_frame_wiring_dom_cljs_test.cljs',
  ]) {
    const result = classify(file);
    assert.equal(
      result.cljs_browser,
      'true',
      `${file} is a browser-only DOM test; it must fire cljs_browser`,
    );
  }
});

test('an ordinary UI cljs test (non-DOM) also fires cljs_browser + node-test (rf2-vxgfnd.90)', () => {
  // The whole implementation/ui/** tree fires the browser gate — an
  // ordinary *_cljs_test.cljc under ui/test is not exempted (conservative
  // routing: UI test changes can move a shared fixture a DOM test :requires).
  const result = classify('implementation/ui/test/re_frame/ui/eq_cljs_test.cljc');
  assert.equal(result.cljs_browser, 'true');
  assert.equal(result.cljs_node_test, 'true');
});

test('implementation/ui/* arms mounted advanced-prod + bundle isolation (rf2-vxgfnd.12.2)', () => {
  // The generated ViewCell/provider production control lives in the advanced
  // prod build; a UI-only PR must never skip the only gate that runs it.
  const result = classify('implementation/ui/src/re_frame/ui/reactive.cljc');
  assert.equal(result.cljs_prod, 'true');
  assert.equal(result.bundle_isolation, 'true');
});

test('a docs/spec-only change does NOT arm cljs_browser (negative — scope discipline) (rf2-vxgfnd.90)', () => {
  assert.equal(classify('spec/006-ReactiveSubstrate.md').cljs_browser, 'false');
  assert.equal(classify('docs/core/intro.md').cljs_browser, 'false');
});

// rf2-vxgfnd.137 — Git-DERIVED discovery mode (the real CI path), not the
// explicit-path classifier the matrix above exercises. The classify() helper
// passes paths straight to the script, bypassing the `git diff` that PR/local
// CI actually runs. Git rename detection collapses a pure rename to its
// DESTINATION path only, so a rename OUT of a classified surface (e.g.
// implementation/ui/** -> docs/**) reported just the unclassified destination
// and left every gate for the DELETED production endpoint false — a CI
// false-green (rf2-vxgfnd.90's guarantee that every first-party UI change runs
// the browser/UI/JVM/node gates, silently violated). The fix runs
// `git diff --no-renames` so BOTH endpoints (old = deletion, new = addition)
// reach the classifier. These tests build REAL two-commit temporary repos and
// invoke the script's local discovery branch (HEAD^ HEAD) with no explicit
// paths, so they exercise the exact Git-derived path the matrix cannot.

const SCRIPT_PATH = path.join(
  REPO_ROOT,
  '.github',
  'scripts',
  'report-changed-surfaces.sh',
);

// Run a git command against a scratch repo, with GIT_* inherited from a hook
// context stripped so the temp repo is never confused with the real worktree.
function gitIn(cwd, ...args) {
  const env = { ...process.env };
  for (const key of Object.keys(env)) {
    if (key.startsWith('GIT_')) delete env[key];
  }
  return execFileSync('git', args, { cwd, env, stdio: ['ignore', 'pipe', 'pipe'] });
}

function writeFileP(root, relPath, contents) {
  const abs = path.join(root, relPath);
  fs.mkdirSync(path.dirname(abs), { recursive: true });
  fs.writeFileSync(abs, contents);
}

// Build a real two-commit repo via `buildHistory`, then invoke the script in
// its Git-derived (local, HEAD^ HEAD) discovery mode with NO explicit paths and
// return the parsed classifier outputs. GITHUB_* is cleared so the script takes
// the local branch and prints to stdout.
function classifyViaGitDiscovery(buildHistory) {
  const tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'rf2-changed-surfaces-'));
  try {
    gitIn(tmp, 'init', '-q');
    gitIn(tmp, 'config', 'user.email', 'ci@example.com');
    gitIn(tmp, 'config', 'user.name', 'CI');
    gitIn(tmp, 'config', 'commit.gpgsign', 'false');
    gitIn(tmp, 'config', 'core.autocrlf', 'false');

    buildHistory({
      root: tmp,
      write: (relPath, contents) => writeFileP(tmp, relPath, contents),
      git: (...args) => gitIn(tmp, ...args),
      commit: (message) => {
        gitIn(tmp, 'add', '-A');
        gitIn(tmp, 'commit', '-q', '-m', message);
      },
    });

    const env = { ...process.env };
    delete env.GITHUB_OUTPUT;
    delete env.GITHUB_EVENT_NAME;
    delete env.GITHUB_BASE_REF;
    for (const key of Object.keys(env)) {
      if (key.startsWith('GIT_')) delete env[key];
    }
    const out = execFileSync('bash', ['-s'], {
      cwd: tmp,
      env,
      encoding: 'utf8',
      input: fs.readFileSync(SCRIPT_PATH),
    });
    return Object.fromEntries(
      out
        .trim()
        .split(/\r?\n/)
        .filter(Boolean)
        .map((line) => line.split('=')),
    );
  } finally {
    try {
      fs.rmSync(tmp, { recursive: true, force: true });
    } catch {
      // Windows can transiently hold a lock on the scratch .git; the OS temp
      // dir is reclaimed anyway. A cleanup failure must not fail the test.
    }
  }
}

// A pure rename (identical content) between two committed paths. Git detects it
// at 100% similarity, so WITHOUT --no-renames only the destination is reported.
function renameViaGitDiscovery(fromPath, toPath) {
  return classifyViaGitDiscovery(({ root, write, git, commit }) => {
    // A keeper file guarantees a non-empty first commit even when `fromPath`
    // is the only other content, and keeps HEAD^ well-defined.
    write('README.md', '# scratch\n');
    write(fromPath, '(ns example.moved)\n;; identical content across the rename\n');
    commit('seed');
    // `git mv` does not create the destination directory; pre-create it.
    fs.mkdirSync(path.dirname(path.join(root, toPath)), { recursive: true });
    git('mv', fromPath, toPath);
    commit('rename');
  });
}

const UI_SOURCE = 'implementation/ui/src/re_frame/ui/reactive.cljc';
const UI_GATE_KEYS = ['implementation_jvm', 'cljs_node_test', 'ui_gates', 'cljs_browser'];

test('DISCOVERY: pure rename OUT of implementation/ui/** arms the full UI gate set (rf2-vxgfnd.137)', () => {
  // The demonstrated false-green: renaming production UI code to an
  // unclassified path. WITHOUT --no-renames git reports only the docs
  // destination and every UI gate stays false; the deleted UI endpoint must
  // still arm implementation_jvm / cljs_node_test / ui_gates / cljs_browser.
  const result = renameViaGitDiscovery(UI_SOURCE, 'docs/moved-out-of-ui.cljc');
  for (const key of UI_GATE_KEYS) {
    assert.equal(
      result[key],
      'true',
      `renaming ${UI_SOURCE} -> docs/** must arm ${key} (deleted UI endpoint); ` +
        'restoring rename detection makes this fail',
    );
  }
});

test('DISCOVERY: reverse rename INTO implementation/ui/** arms the full UI gate set (rf2-vxgfnd.137)', () => {
  const result = renameViaGitDiscovery('docs/incoming.cljc', UI_SOURCE);
  for (const key of UI_GATE_KEYS) {
    assert.equal(result[key], 'true', `renaming docs/** -> ${UI_SOURCE} must arm ${key}`);
  }
});

test('DISCOVERY: within-UI rename arms the full UI gate set (rf2-vxgfnd.137)', () => {
  const result = renameViaGitDiscovery(
    UI_SOURCE,
    'implementation/ui/src/re_frame/ui/reactive_renamed.cljc',
  );
  for (const key of UI_GATE_KEYS) {
    assert.equal(result[key], 'true', `a within-UI rename must arm ${key}`);
  }
});

test('DISCOVERY: docs->docs rename does NOT arm UI gates (no spurious firing) (rf2-vxgfnd.137)', () => {
  // Both endpoints are unclassified — --no-renames must not manufacture a UI
  // classification (guards against over-firing / mis-splitting a rename).
  const result = renameViaGitDiscovery('docs/a.md', 'docs/b.md');
  for (const key of UI_GATE_KEYS) {
    assert.equal(result[key], 'false', `a docs->docs rename must NOT arm ${key}`);
  }
});

// Ordinary add / modify / delete via the SAME Git-derived discovery mode stay
// classified exactly as before — --no-renames only changes how renames surface.

test('DISCOVERY: ordinary add of a UI file arms the UI gates (unchanged) (rf2-vxgfnd.137)', () => {
  const result = classifyViaGitDiscovery(({ write, commit }) => {
    write('README.md', '# scratch\n');
    commit('seed');
    write(UI_SOURCE, '(ns re-frame.ui.reactive)\n');
    commit('add ui file');
  });
  for (const key of UI_GATE_KEYS) {
    assert.equal(result[key], 'true', `an ordinary UI add must arm ${key}`);
  }
});

test('DISCOVERY: ordinary modify of a UI file arms the UI gates (unchanged) (rf2-vxgfnd.137)', () => {
  const result = classifyViaGitDiscovery(({ write, commit }) => {
    write(UI_SOURCE, '(ns re-frame.ui.reactive)\n;; v1\n');
    write('README.md', '# scratch\n');
    commit('seed');
    write(UI_SOURCE, '(ns re-frame.ui.reactive)\n;; v2 — edited\n');
    commit('modify ui file');
  });
  for (const key of UI_GATE_KEYS) {
    assert.equal(result[key], 'true', `an ordinary UI modify must arm ${key}`);
  }
});

test('DISCOVERY: ordinary delete of a UI file arms the UI gates (unchanged) (rf2-vxgfnd.137)', () => {
  const result = classifyViaGitDiscovery(({ write, git, commit }) => {
    write(UI_SOURCE, '(ns re-frame.ui.reactive)\n');
    write('README.md', '# scratch\n');
    commit('seed');
    git('rm', '-q', UI_SOURCE);
    commit('delete ui file');
  });
  for (const key of UI_GATE_KEYS) {
    assert.equal(result[key], 'true', `an ordinary UI delete must arm ${key}`);
  }
});

test('DISCOVERY: ordinary docs-only modify does NOT arm UI gates (scope, unchanged) (rf2-vxgfnd.137)', () => {
  const result = classifyViaGitDiscovery(({ write, commit }) => {
    write('docs/core/intro.md', '# intro v1\n');
    commit('seed');
    write('docs/core/intro.md', '# intro v2\n');
    commit('modify docs');
  });
  for (const key of UI_GATE_KEYS) {
    assert.equal(result[key], 'false', `a docs-only modify must NOT arm ${key}`);
  }
});

// ---------------------------------------------------------------------------
// rf2-drpa3.58 — the Freehand JVM lane folded into the REQUIRED matrix.
//
// F1a shipped it as .github/workflows/freehand-artefact.yml because the
// workflow file was hot-zone. `needs:` cannot span workflow files, so that
// job could never reach `all-required-passed` — the branch ruleset's single
// required context — and a red freehand suite did not block a merge.
//
// Three things have to hold together, and each has a test below: the
// classifier must ARM the surface (a standalone workflow had its own `paths:`
// trigger and needed no classifier case; a surface-gated job does), the job
// must be gated on that output and actually run the suite + the donor law,
// and the aggregator must depend on it. Break any one and the lane silently
// reverts to advisory.
// ---------------------------------------------------------------------------

test('implementation/freehand/** arms the freehand JVM + node-test surfaces (rf2-drpa3.58)', () => {
  for (const file of [
    'implementation/freehand/src/re_frame/freehand.cljc',
    'implementation/freehand/test/re_frame/freehand/skeleton_cljs_test.cljc',
    'implementation/freehand/deps.edn',
  ]) {
    const result = classify(file);
    assert.equal(
      result.implementation_jvm,
      'true',
      `${file} must arm implementation_jvm or the required jvm-freehand job SKIPS`,
    );
    assert.equal(
      result.cljs_node_test,
      'true',
      `${file} must arm cljs_node_test — freehand/src + freehand/test are on the :node-test classpath`,
    );
  }
});

test('implementation/freehand/** stays OFF the heavy per-feature gates (rf2-drpa3.58)', () => {
  // Scope guard, not an aspiration: Freehand ships no public surface, no
  // *-dom-cljs-test namespace and no production-bundle requirer yet, so the
  // browser/prod/isolation tier would be pure cost. Widen the classifier case
  // (and this test) when it gains one — implementation/ui/* is the precedent.
  const result = classify('implementation/freehand/src/re_frame/freehand.cljc');
  for (const key of ['cljs_browser', 'cljs_prod', 'bundle_isolation', 'ui_gates', 'ui_smoke']) {
    assert.equal(result[key], 'false', `freehand must not arm ${key} yet`);
  }
});

test('jvm-freehand is job-level gated and runs the suite + the donor law (rf2-drpa3.58)', () => {
  const block = jobBlock(fs.readFileSync(WORKFLOW, 'utf8'), 'jvm-freehand');
  assert.match(block, /needs: detect_changed_surfaces/);
  assert.match(
    block,
    /if: needs\.detect_changed_surfaces\.outputs\.implementation_jvm == 'true'/,
  );
  assert.match(block, /working-directory: implementation\/freehand/);
  assert.match(block, /Run JVM tests \(freehand artefact\)/);
  // The EP-0036 donor boundary is a LAW, gated not reviewed. It moved here
  // from the deleted standalone workflow; this job is now its only home.
  assert.match(block, /git grep -n -e 're-frame\\\.ui'/);
  assert.match(block, /-- implementation\/freehand/);
});

test('all-required-passed aggregator needs jvm-freehand (rf2-drpa3.58)', () => {
  const block = jobBlock(fs.readFileSync(WORKFLOW, 'utf8'), 'all-required-passed');
  assert.match(
    block,
    /- jvm-freehand\r?\n/,
    'aggregator must list jvm-freehand in needs: — otherwise the lane is advisory',
  );
});

test('the standalone freehand-artefact workflow is gone (one owner, not two) (rf2-drpa3.58)', () => {
  assert.equal(
    fs.existsSync(path.join(WORKFLOW_DIR, 'freehand-artefact.yml')),
    false,
    'freehand-artefact.yml must not coexist with the folded-in jvm-freehand job: ' +
      'two half-owners of one lane drift, and the standalone copy can never be required',
  );
});

let failed = 0;
for (const { name, fn } of tests) {
  try {
    fn();
  } catch (err) {
    failed += 1;
    console.error(`FAIL ${name}`);
    console.error(err && err.stack ? err.stack : err);
  }
}

if (failed > 0) {
  console.error(`changed-surfaces tests: ${failed} failed.`);
  process.exit(1);
}

console.log(`changed-surfaces tests: ${tests.length} passed.`);
