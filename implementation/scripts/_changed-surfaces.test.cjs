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
  // indent. Find it from just after the job header.
  const rest = workflow.slice(start + 1);
  const nextJob = rest.search(/\n {2}[A-Za-z0-9_-]+:\n/);
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

// rf2-t5slp — the framework-testbeds gate was retired after all four
// rf2-tglku migration waves moved every framework + top-level testbed
// Playwright spec.cjs to CLJS/JVM unit tests. The classifier no longer
// emits a `framework_testbeds` output; testbed source diffs only light
// `cljs_browser` (for the transitive CLJS compile coverage).

test('framework_testbeds output is no longer emitted (rf2-t5slp)', () => {
  const result = classify('testbeds/ssr_basic/core.cljs');
  assert.equal(result.framework_testbeds, undefined);
});

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

test('framework-testbeds workflow job is removed (rf2-t5slp)', () => {
  const workflow = fs.readFileSync(WORKFLOW, 'utf8');
  assert.doesNotMatch(workflow, /^\s*framework-testbeds:/m);
  assert.doesNotMatch(workflow, /framework_testbeds/);
});

test('adapter-testbed-smokes workflow remains scoped to EXAMPLES_FILTER=adapters/ (rf2-t5slp)', () => {
  const workflow = fs.readFileSync(WORKFLOW, 'utf8');
  // Find the adapter-testbed-smokes job block and verify it still
  // passes the narrow adapters/ filter — the only Playwright surface
  // under the examples orchestrator after framework-testbeds retired.
  assert.match(
    workflow,
    /adapter-testbed-smokes:[\s\S]*EXAMPLES_FILTER:\s*"adapters\/"/,
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
