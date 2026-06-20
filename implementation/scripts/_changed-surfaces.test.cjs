#!/usr/bin/env node

'use strict';

const assert = require('assert/strict');
const { execFileSync } = require('child_process');
const fs = require('fs');
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
  const result = classify('docs/guide/intro.md');
  assert.equal(result.implementation_jvm, 'false');
  assert.equal(result.cljs_node_test, 'false');
});

test('Core change runs jvm-core + cljs (implementation_jvm + cljs_node_test true) (rf2-f79t8)', () => {
  const result = classify('implementation/core/src/re_frame/core.cljc');
  assert.equal(result.implementation_jvm, 'true');
  assert.equal(result.cljs_node_test, 'true');
});

test('Conformance fixture change runs cljs (CLJS corpus runner is in node-test) (rf2-f79t8)', () => {
  const result = classify('spec/conformance/fixtures/dispatch.edn');
  assert.equal(result.implementation_jvm, 'true');
  assert.equal(result.cljs_node_test, 'true');
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

// rf2-4ka7c2.1 — API-manifest probe routing false-green fix. The CLJS-only
// adapter / Xray / pair-MCP public surfaces live in the sidecar
// (spec/api-manifest-metadata.edn) under :cljs-only and are carried into
// spec/api-manifest.edn verbatim. Their ONLY live runtime verifier is the
// CLJS enumeration probe in the consolidated :node-test build, gated on
// cljs_node_test. A sidecar / generated-manifest / API.md change must
// therefore light cljs_node_test so the probe reconciles those rows — else a
// stale/missing CLJS-only row ships with green CI (lint.yml runs only the JVM
// generator + projection checks, never the CLJS probe).

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

// rf2-rcepku — Xray PR-smoke launcher false-green fix, mirroring the
// rf2-y9o5e3 examples/scripts launcher routing. The
// serve-and-run-xray-feature-gate.cjs launcher IS the executable
// orchestration for `npm run test:xray-feature-gate:smoke`, the command
// the story-xray-browser PR job runs (test.yml). Editing it must fire the
// story_xray_browser gate it drives — else a PR can break the launcher
// while avoiding the very PR-smoke job it orchestrates. The static-script
// surfaces it shares with the generic implementation/scripts/* case stay
// armed (this widens coverage; it does not narrow it).

test('implementation/scripts/serve-and-run-xray-feature-gate.cjs fires story_xray_browser (rf2-rcepku)', () => {
  const result = classify('implementation/scripts/serve-and-run-xray-feature-gate.cjs');
  assert.equal(
    result.story_xray_browser,
    'true',
    'editing the Xray PR-smoke launcher must run the story-xray-browser gate it drives',
  );
});

test('implementation/scripts/serve-and-run-xray-feature-gate.cjs still arms the generic static-script gates (regression) (rf2-rcepku)', () => {
  const result = classify('implementation/scripts/serve-and-run-xray-feature-gate.cjs');
  assert.equal(result.cljs_node_test, 'true');
  assert.equal(result.cljs_browser, 'true');
  assert.equal(result.cljs_prod, 'true');
  assert.equal(result.bundle_isolation, 'true');
  assert.equal(result.reagent_slim_bundle, 'true');
});

test('an UNRELATED implementation/scripts/* file does NOT fire story_xray_browser (scope discipline) (rf2-rcepku)', () => {
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

test('examples/scripts/examples-filter.cjs (shared manifest) fires adapter_testbed_smokes (rf2-l72e2)', () => {
  const result = classify('examples/scripts/examples-filter.cjs');
  assert.equal(result.adapter_testbed_smokes, 'true');
});

// rf2-y9o5e3 — every EXECUTABLE examples/scripts gate file must fire the
// browser gate it drives, so a PR breaking a launcher / shared port
// resolver can't avoid the gate it can break. The adapter-smoke
// orchestrator + runner + helpers (incl. examples-port.cjs) fire
// adapter_testbed_smokes; the Story launchers + their dedicated port
// resolver fire story_xray_browser; the SHARED port-resolver.cjs fires
// BOTH. Static-only scanners (check-examples-assets.cjs,
// check-reagent-slim-boundary.cjs) stay on the always-on JS harness path
// (cljs_browser only) — they have always-on .test.cjs coverage under
// test:script-policy and drive no browser gate.

const ADAPTER_SMOKE_GATE_FILES = [
  'examples/scripts/serve-and-run-examples-tests.cjs',
  'examples/scripts/run-examples-tests.cjs',
  'examples/scripts/spec-helpers.cjs',
  'examples/scripts/examples-filter.cjs',
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
// orchestrator (serve-and-run-examples-tests.cjs → adapter_testbed_smokes) AND
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

test('adapter-testbed-smokes workflow remains scoped to EXAMPLES_FILTER=adapters/ (rf2-t5slp)', () => {
  const workflow = fs.readFileSync(WORKFLOW, 'utf8');
  // Verify the adapter-testbed-smokes job still passes the narrow
  // adapters/ filter — adapter testbeds are the only Playwright surface
  // under the examples orchestrator.
  assert.match(
    workflow,
    /adapter-testbed-smokes:[\s\S]*EXAMPLES_FILTER:\s*"adapters\/"/,
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
