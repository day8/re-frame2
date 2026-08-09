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

// rf2-65ajl — this row's exemplar USED to be
// tools/story/test/story_feature_load.cjs, pinned to false. The assertion was
// correct about the behaviour and wrong about the intent: that file is the
// FULL Story feature-load gate's own Playwright spec, and pinning it here read
// as a considered decision that no browser gate needs to run for it. What was
// actually true is narrower — the PR-SMOKE tier must not run for it, because
// neither of the smoke's two commands loads it. The full gate must. So the row
// keeps its claim with an exemplar that really is inert (a JVM unit test), and
// story_feature_load.cjs is now covered POSITIVELY by the story_full_gate rows
// further down, where its own gate is asserted to run.
test('Story test-tree changes do NOT trigger the story_xray_browser PR-smoke tier (rf2-k9ekz)', () => {
  const exemplar = 'tools/story/test/re_frame/story_decorator_chain_test.clj';
  assert.ok(
    fs.existsSync(path.join(REPO_ROOT, exemplar)),
    `${exemplar} must exist — this row's whole claim is "a real JVM unit test"`,
  );
  assert.equal(classify(exemplar).story_xray_browser, 'false');
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

// rf2-1sd8h — the BROWSER half of the same two trees. Story/Xray
// `*_dom_cljs_test.{cljs,cljc}` namespaces are selected by BOTH CLJS builds:
// the consolidated `:node-test` (`cljs-test$` matches a `-dom-cljs-test`
// suffix too) and `:browser-test` (`-dom-cljs-test$`). Only the second one
// gives them a `document`; under Node they self-skip. The classifier fired
// only cljs_node_test for these paths, so `cljs-browser` reported SKIPPED
// while the decisive regression test ran nowhere but the author's laptop
// (#7037's presence-flush proof, and the pre-existing sub-overrides suite
// beside it). These assertions are the teeth on that arm.
//
// The two live namespaces named below are load-bearing, not illustrative:
// they are the files the bead was filed about, and if either is renamed out
// of the `-dom-cljs-test` convention this pins that the arm moved with it.

test('Story DOM test change schedules cljs-browser (rf2-1sd8h)', () => {
  const result = classify(
    'tools/story/test/re_frame/story/play/presence_freehand_dom_cljs_test.cljs',
  );
  assert.equal(result.cljs_browser, 'true');
  // Still the node lane too — the same namespace compiles there and its
  // non-DOM assertions keep firing.
  assert.equal(result.cljs_node_test, 'true');
});

test('the pre-existing Story sub-overrides DOM test schedules cljs-browser (rf2-1sd8h)', () => {
  const result = classify(
    'tools/story/test/re_frame/story/sub_overrides_render_dom_cljs_test.cljs',
  );
  assert.equal(result.cljs_browser, 'true');
});

test('Xray DOM test change schedules cljs-browser (rf2-1sd8h)', () => {
  const result = classify(
    'tools/xray/test/day8/re_frame2_xray/views/view_walker_dom_cljs_test.cljs',
  );
  assert.equal(result.cljs_browser, 'true');
});

test('a .cljc DOM suite would schedule cljs-browser too (rf2-1sd8h)', () => {
  // No `.cljc` DOM suite exists under these trees today; the arm covers the
  // extension so that the first one to land is armed on arrival rather than
  // on the audit that finds it.
  const result = classify('tools/story/test/re_frame/story/hypothetical_dom_cljs_test.cljc');
  assert.equal(result.cljs_browser, 'true');
});

test('Story runtime src change schedules cljs-browser (the DOM suites mount it) (rf2-1sd8h)', () => {
  const result = classify('tools/story/src/re_frame/story/play/presence.cljc');
  assert.equal(result.cljs_browser, 'true');
});

test('Xray runtime src change schedules cljs-browser (rf2-1sd8h)', () => {
  const result = classify('tools/xray/src/day8/re_frame2_xray/core.cljs');
  assert.equal(result.cljs_browser, 'true');
});

test('a JVM-only .clj test under Story does NOT schedule cljs-browser (rf2-1sd8h)', () => {
  const result = classify('tools/story/test/some_test.clj');
  assert.equal(result.cljs_browser, 'false');
  // …and it keeps the JVM fan-out it already had, so this is a narrowing
  // control, not a regression.
  assert.equal(result.tools_jvm, 'true');
});

test('a non-DOM CLJS test under Story does NOT schedule cljs-browser (rf2-1sd8h)', () => {
  const result = classify('tools/story/test/re_frame/story_cljs_test.cljs');
  assert.equal(result.cljs_browser, 'false');
  assert.equal(result.cljs_node_test, 'true');
});

test('Story spec-only .md does NOT schedule cljs-browser (rf2-1sd8h)', () => {
  assert.equal(classify('tools/story/spec/Spec.md').cljs_browser, 'false');
});

test('Xray spec-only .md does NOT schedule cljs-browser (rf2-1sd8h)', () => {
  assert.equal(
    classify('tools/xray/spec/017-Test-Coverage-Matrix.md').cljs_browser,
    'false',
  );
});

test('a Story DOM-test change does NOT fire the Playwright testbed gate (rf2-1sd8h)', () => {
  // The bead asks for the browser UNIT lane, narrowly — not the
  // story-feature-load / xray-feature-gate Playwright runners, which the
  // runtime-path predicate owns and which a test-tree edit cannot affect.
  const result = classify(
    'tools/story/test/re_frame/story/sub_overrides_render_dom_cljs_test.cljs',
  );
  assert.equal(result.story_xray_browser, 'false');
});

test('every Story/Xray DOM suite in the tree is armed by the classifier (rf2-1sd8h)', () => {
  // Read off the tree rather than listed here: a new `*_dom_cljs_test` file
  // that the arm misses makes this red on arrival. Counts drift; the walk
  // does not.
  const roots = [
    path.join(REPO_ROOT, 'tools', 'story', 'test'),
    path.join(REPO_ROOT, 'tools', 'xray', 'test'),
  ];
  const suites = [];
  const walk = (dir) => {
    for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
      const full = path.join(dir, entry.name);
      if (entry.isDirectory()) walk(full);
      else if (/_dom_cljs_test\.clj[sc]$/.test(entry.name)) {
        suites.push(path.relative(REPO_ROOT, full).split(path.sep).join('/'));
      }
    }
  };
  roots.forEach(walk);
  assert.ok(suites.length > 0, 'expected at least one Story/Xray DOM suite');
  for (const suite of suites) {
    assert.equal(
      classify(suite).cljs_browser,
      'true',
      `${suite} declares itself a DOM suite but does not schedule cljs-browser`,
    );
  }
});

// rf2-eyyd2 — the two seams rf2-1sd8h left to the nightly matrix. Both are
// LIVE edges of the suites armed above, so both are now armed directly:
// the shared support helpers those suites require, and the CLJ macro
// namespace that produces the CLJS four of them compile.

const STORY_E2E_HELPER =
  'tools/story/test/re_frame/story/test_helpers/e2e_multi_frame.cljs';
const XRAY_E2E_HELPER =
  'tools/xray/test/day8/re_frame2_xray/test_helpers/e2e_multi_frame.cljs';
const XRAY_COUNTER_FIXTURE =
  'tools/xray/test/day8/re_frame2_xray/test_helpers/host_fixtures/counter.cljs';
const STORY_MACROS = 'tools/story/src/re_frame/story/macros.clj';

test('Story e2e_multi_frame helper schedules cljs-browser (rf2-eyyd2)', () => {
  // Required by share_url_state_popstate_stale_override_dom_cljs_test, which
  // mounts only under `:browser-test`; on Node it finds no `window` and
  // self-skips. Before this arm a helper-only PR ran the Node compile and
  // skipped the suite's only real assertions.
  const result = classify(STORY_E2E_HELPER);
  assert.equal(result.cljs_browser, 'true');
  assert.equal(result.cljs_node_test, 'true');
});

test('Xray e2e_multi_frame helper schedules cljs-browser (rf2-eyyd2)', () => {
  // Required by reactive_data_view_rows_dom_cljs_test directly, AND by the
  // Story helper above (aliased `xray-e2e`), so it sits under both trees'
  // DOM suites at once.
  const result = classify(XRAY_E2E_HELPER);
  assert.equal(result.cljs_browser, 'true');
  assert.equal(result.cljs_node_test, 'true');
});

test('Xray counter host fixture schedules cljs-browser (rf2-eyyd2)', () => {
  const result = classify(XRAY_COUNTER_FIXTURE);
  assert.equal(result.cljs_browser, 'true');
  assert.equal(result.cljs_node_test, 'true');
});

test('the Story test_helpers arm is the directory, not the three files (rf2-eyyd2)', () => {
  // `tools/story/test/story/test_helpers/` is the second helper root in the
  // Story tree. No DOM suite requires it today; the arm covers it so the
  // first one that does is armed on arrival rather than on the next audit.
  assert.equal(
    classify('tools/story/test/story/test_helpers/runtime_shadow.cljc').cljs_browser,
    'true',
  );
});

test('a non-runtime file beside a helper does NOT schedule cljs-browser (rf2-eyyd2)', () => {
  // The extension guard is the same one every other arm in this predicate
  // carries: prose or EDN sitting next to a helper compiles into nothing.
  assert.equal(
    classify('tools/xray/test/day8/re_frame2_xray/test_helpers/README.md').cljs_browser,
    'false',
  );
  assert.equal(
    classify('tools/story/test/re_frame/story/test_helpers/fixtures.edn').cljs_browser,
    'false',
  );
});

test('a support helper does NOT fire the Playwright testbed gate (rf2-eyyd2)', () => {
  // Same narrowing the DOM suites themselves get: the browser UNIT lane,
  // not story-feature-load / xray-feature-gate.
  for (const helper of [STORY_E2E_HELPER, XRAY_E2E_HELPER, XRAY_COUNTER_FIXTURE]) {
    assert.equal(classify(helper).story_xray_browser, 'false', helper);
  }
});

test('Story macros.clj schedules BOTH CLJS lanes (rf2-eyyd2)', () => {
  // re-frame.story delegates every public registration macro to this
  // CLJ-only namespace, so its emitted forms are what a `(story/reg-variant
  // …)` call site compiles to — in the browser build and the node build
  // alike. The `.clj` extension guard on the src arm left it false on both.
  const result = classify(STORY_MACROS);
  assert.equal(result.cljs_browser, 'true');
  assert.equal(result.cljs_node_test, 'true');
});

test('Story macros.clj keeps its existing non-CLJS fan-out (rf2-eyyd2)', () => {
  // The arm widens; it must not narrow. macros.clj is under
  // tools/story/src/**, so the examples_compile roster and the
  // tools_jvm / mcp_conformance / template_expensive fan-out all still fire.
  const result = classify(STORY_MACROS);
  assert.equal(result.examples_compile, 'true');
  assert.equal(result.tools_jvm, 'true');
  assert.equal(result.mcp_conformance, 'true');
  assert.equal(result.template_expensive, 'true');
});

test('an ordinary JVM-only .clj under Story src stays off both CLJS lanes (rf2-eyyd2)', () => {
  // The named exception is macros.clj and nothing else: a hypothetical JVM
  // consumer beside it must keep the general `.clj` exclusion.
  const result = classify('tools/story/src/re_frame/story/jvm_only_helper.clj');
  assert.equal(result.cljs_browser, 'false');
  assert.equal(result.cljs_node_test, 'false');
  assert.equal(result.tools_jvm, 'true');
});

test('an ordinary JVM-only .clj test under Story/Xray stays off both CLJS lanes (rf2-eyyd2)', () => {
  for (const file of [
    'tools/story/test/re_frame/story_test.clj',
    'tools/xray/test/day8/re_frame2_xray/config_test.clj',
  ]) {
    const result = classify(file);
    assert.equal(result.cljs_browser, 'false', file);
    assert.equal(result.cljs_node_test, 'false', file);
    assert.equal(result.tools_jvm, 'true', file);
  }
});

test('every support file a live DOM suite requires is armed by the classifier (rf2-eyyd2)', () => {
  // The teeth. Read the require closure off the tree rather than listing it:
  // the arm is a DIRECTORY convention, so what can drift is a DOM suite
  // reaching for a support file that lives OUTSIDE a `test_helpers/`
  // directory. That makes this red on arrival. Names, not counts.
  const roots = [
    path.join(REPO_ROOT, 'tools', 'story', 'test'),
    path.join(REPO_ROOT, 'tools', 'xray', 'test'),
  ];
  const all = [];
  const walk = (dir) => {
    for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
      const full = path.join(dir, entry.name);
      if (entry.isDirectory()) walk(full);
      else if (/\.clj[sc]?$/.test(entry.name)) {
        all.push(path.relative(REPO_ROOT, full).split(path.sep).join('/'));
      }
    }
  };
  roots.forEach(walk);

  // ns -> file, over the two test trees only.
  const nsOf = new Map();
  const sourceOf = new Map();
  for (const rel of all) {
    const text = fs.readFileSync(path.join(REPO_ROOT, rel), 'utf8');
    sourceOf.set(rel, text);
    const m = /\(ns\s+([A-Za-z0-9_.*+!?<>=-]+)/.exec(text);
    if (m) nsOf.set(m[1], rel);
  }

  // The libspec heads of a file's `(:require …)` form. Balancing from
  // `(:require` keeps docstring prose out of the match.
  const requiresOf = (text) => {
    const heads = new Set();
    let at = text.indexOf('(:require');
    while (at !== -1) {
      let depth = 0;
      let end = at;
      for (; end < text.length; end += 1) {
        if (text[end] === '(') depth += 1;
        else if (text[end] === ')') {
          depth -= 1;
          if (depth === 0) break;
        }
      }
      const form = text.slice(at, end + 1);
      for (const m of form.matchAll(/\[\s*([a-z][A-Za-z0-9_.*+!?<>=-]*)/g)) heads.add(m[1]);
      at = text.indexOf('(:require', end + 1);
    }
    return heads;
  };

  const isSuite = (rel) => /_dom_cljs_test\.clj[sc]$/.test(rel);
  const seen = new Set();
  const stack = all.filter(isSuite);
  assert.ok(stack.length > 0, 'expected at least one Story/Xray DOM suite');
  const support = new Set();
  while (stack.length) {
    const rel = stack.pop();
    if (seen.has(rel)) continue;
    seen.add(rel);
    if (!isSuite(rel)) support.add(rel);
    for (const head of requiresOf(sourceOf.get(rel))) {
      const target = nsOf.get(head);
      if (target && !seen.has(target)) stack.push(target);
    }
  }

  assert.ok(
    support.size > 0,
    'expected the DOM suites to require at least one support file — if this is ' +
      'empty the closure walk has stopped working, not the tree',
  );
  for (const rel of [...support].sort()) {
    assert.equal(
      classify(rel).cljs_browser,
      'true',
      `${rel} is required by a Story/Xray DOM suite but does not schedule cljs-browser`,
    );
  }
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

// rf2-as6bg — tools/testbed-support had NO arm in the classifier at all, so a
// testbed-support-only PR classified as zero changed surfaces and ran zero
// gates: not just the `.clj` suite the bijection gate found (rf2-4hc9p), but
// its three `.cljs` suites too. Its src+test are :source-paths of the
// consolidated :node-test AND :browser-test builds, so those two gates own the
// CLJS half — same shape as machines-viz above. These assertions are the teeth
// on that arm; deleting it makes them red rather than making CI quietly empty.

test('testbed-support src change runs cljs + cljs-browser (rf2-as6bg)', () => {
  const result = classify('tools/testbed-support/src/re_frame/testbed/config.cljs');
  assert.equal(result.cljs_node_test, 'true');
  assert.equal(result.cljs_browser, 'true');
});

test('testbed-support CLJS test-tree change runs cljs + cljs-browser (rf2-as6bg)', () => {
  const result = classify(
    'tools/testbed-support/test/re_frame/testbed/story_host_dom_cljs_test.cljs',
  );
  assert.equal(result.cljs_node_test, 'true');
  assert.equal(result.cljs_browser, 'true');
});

// rf2-wq17m — the `.clj` half (open_in_editor_server_test.clj, which no CLJS
// build can load) now HAS a lane: `jvm-tools-testbed-support`, gated on the
// artefact's own output. This assertion previously pinned `tools_jvm == 'false'`
// as the honest description of a file no job ran, and said in as many words that
// it lands flipped when the job exists — a tripwire rather than a claim of
// correctness. It is now flipped, and it still pins `tools_jvm` false, which is
// the durable half of the original point: that output gates four jvm-tools-*
// jobs (xray / story / story-mcp / mcp-base), none of which runs this artefact.
test('testbed-support .clj fans out to its OWN jvm output, not tools_jvm (rf2-wq17m)', () => {
  const result = classify(
    'tools/testbed-support/test/re_frame/testbed/open_in_editor_server_test.clj',
  );
  assert.equal(result.tools_jvm_testbed_support, 'true');
  assert.equal(result.tools_jvm, 'false');
  assert.equal(result.cljs_node_test, 'true');
});

// rf2-wq17m — the same three-part shape the Freehand probe rows use: the
// classifier must ARM the surface, the job must be gated on that output and run
// the artefact's suite, and the aggregator must depend on the job. Break any one
// and the lane is decorative — which is exactly the state these two artefacts
// were in, with a wired `:test` alias and a slot on test-jvm-tools.sh's roster
// but no PR-time job at all.
const NEW_TOOLS_JVM_LANES = [
  {
    job: 'jvm-tools-machines-viz',
    output: 'tools_jvm_machines_viz',
    dir: 'tools/machines-viz',
    armed: 'tools/machines-viz/src/day8/re_frame2_machines_viz/scxml.cljc',
  },
  {
    job: 'jvm-tools-testbed-support',
    output: 'tools_jvm_testbed_support',
    dir: 'tools/testbed-support',
    armed: 'tools/testbed-support/test/re_frame/testbed/open_in_editor_server_test.clj',
  },
];

test('the new tools JVM jobs are gated on their own output and run their artefact (rf2-wq17m)', () => {
  const workflow = fs.readFileSync(WORKFLOW, 'utf8');
  for (const lane of NEW_TOOLS_JVM_LANES) {
    const block = jobBlock(workflow, lane.job);
    assert.match(block, /needs: detect_changed_surfaces/);
    assert.match(
      block,
      new RegExp(`if: needs\\.detect_changed_surfaces\\.outputs\\.${lane.output} == 'true'`),
      `${lane.job} must be gated on ${lane.output}`,
    );
    assert.match(
      block,
      new RegExp(`working-directory: ${lane.dir}`),
      `${lane.job} must run in ${lane.dir}`,
    );
    assert.match(
      block,
      /run: clojure -M:test/,
      `${lane.job} must invoke the artefact's own :test alias`,
    );
    // The output must also be plumbed out of detect_changed_surfaces, or the
    // `if:` reads an empty string and the job never runs.
    assert.match(
      workflow,
      new RegExp(`${lane.output}: \\$\\{\\{ steps\\.detect\\.outputs\\.${lane.output} \\}\\}`),
      `${lane.output} must be declared as a detect_changed_surfaces output`,
    );
  }
});

test('all-required-passed aggregator needs the new tools JVM jobs (rf2-wq17m)', () => {
  const block = jobBlock(fs.readFileSync(WORKFLOW, 'utf8'), 'all-required-passed');
  for (const lane of NEW_TOOLS_JVM_LANES) {
    assert.ok(
      block.includes(`- ${lane.job}`),
      `aggregator must list ${lane.job} in needs: — a job absent from it is advisory`,
    );
  }
});

test('the new tools JVM lanes arm on their artefact and nowhere else (rf2-wq17m)', () => {
  for (const lane of NEW_TOOLS_JVM_LANES) {
    assert.equal(
      classify(lane.armed)[lane.output],
      'true',
      `${lane.armed} must arm ${lane.output}`,
    );
    for (const other of NEW_TOOLS_JVM_LANES) {
      if (other === lane) continue;
      assert.equal(
        classify(lane.armed)[other.output],
        'false',
        `${lane.armed} must NOT arm ${other.output}`,
      );
    }
    // The control is a surface with NO declared edge to either artefact.
    // spec prose is the honest one: it changes no classpath either job reads.
    // (A core change is deliberately NOT the control any more — see the
    // dependency-edge assertions below, rf2-wq17m's audit reopen.)
    assert.equal(
      classify('spec/006-ReactiveSubstrate.md')[lane.output],
      'false',
      `a spec-prose change must not fire ${lane.output}`,
    );
  }
});

// rf2-8m344 — the viewer PAGE lane, same three-part shape. The `:machines-viz-
// viewer` build was declared in implementation/shadow-cljs.edn and compiled by
// no workflow, npm script or gate, while README.md and spec/API.md documented
// building it as the way a consumer self-hosts the page. "Buildable" was an
// unverified claim of exactly the kind the 404 default host had just been
// removed for.
test('the machines-viz viewer-page job is gated on its own output and runs the recipe (rf2-8m344)', () => {
  const workflow = fs.readFileSync(WORKFLOW, 'utf8');
  const block = jobBlock(workflow, 'machines-viz-viewer-page');
  assert.match(block, /needs: detect_changed_surfaces/);
  assert.match(
    block,
    /if: needs\.detect_changed_surfaces\.outputs\.machines_viz_viewer_page == 'true'/,
    'the job must be gated on machines_viz_viewer_page',
  );
  assert.match(
    block,
    /run: npm run build:machines-viz-viewer/,
    'the job must run the SAME command the README hands a consumer',
  );
  assert.match(
    block,
    /stage-viewer-page\.cjs --self-test/,
    'the coupling assertion must be self-tested, or it can rot into a no-op',
  );
  assert.match(
    workflow,
    /machines_viz_viewer_page: \$\{\{ steps\.detect\.outputs\.machines_viz_viewer_page \}\}/,
    'machines_viz_viewer_page must be declared as a detect_changed_surfaces output',
  );
  assert.ok(
    jobBlock(workflow, 'all-required-passed').includes('- machines-viz-viewer-page'),
    'aggregator must list machines-viz-viewer-page — a job absent from it is advisory',
  );
});

test('the viewer-page lane arms on the page, the HTML and the build that declares it (rf2-8m344)', () => {
  // The page entry, the HTML it loads, and the build config that names the
  // module — the three files a rename can break the recipe from.
  for (const armed of [
    'tools/machines-viz/page/day8/re_frame2_machines_viz/viewer.cljs',
    'tools/machines-viz/public/viewer.html',
    'implementation/shadow-cljs.edn',
  ]) {
    assert.equal(
      classify(armed).machines_viz_viewer_page,
      'true',
      `${armed} must arm machines_viz_viewer_page`,
    );
  }
  // Controls: a machines-viz spec-prose change compiles nothing, and a core
  // change reaches the viewer only through library surface the always-on
  // node-test and browser lanes already compile.
  assert.equal(
    classify('tools/machines-viz/spec/API.md').machines_viz_viewer_page,
    'false',
    'a machines-viz spec-prose change must not fire the page build',
  );
  assert.equal(
    classify('implementation/core/src/re_frame/core.cljc').machines_viz_viewer_page,
    'false',
    'a core change must not fire the page build — its compile is already covered',
  );
});

// rf2-wq17m, audit reopen of #7005 — the DEPENDENCY side. Both new jobs run a
// suite whose subject is code in implementation/, reached over a :local/root
// declared in the artefact's own deps.edn, so a change on the framework side
// must arm them. Before this, classifying implementation/machines/src/... or
// implementation/core/src/... left both outputs false: an engine grammar
// change could merge with the parity ratchet skipped, and a source-coords
// change with the endpoint delegation suite skipped.
//
// The assertion above USED to pin `a core change must not fire ${lane.output}`
// — the honest description of the classifier as #7005 left it, and the
// tripwire that makes this reopen visible rather than silent. It is inverted
// here deliberately, in the same PR that adds the edges.

test('an engine change arms the machines-viz parity ratchet (rf2-wq17m)', () => {
  const result = classify('implementation/machines/src/re_frame/machines.cljc');
  assert.equal(result.tools_jvm_machines_viz, 'true');
  // …and not the sibling lane: testbed-support declares no machines edge.
  assert.equal(result.tools_jvm_testbed_support, 'false');
  // The per-feature fan-out it already had is untouched.
  assert.equal(result.implementation_jvm, 'true');
  assert.equal(result.cljs_node_test, 'true');
});

test('a core change arms BOTH new tools JVM lanes (rf2-wq17m)', () => {
  const result = classify('implementation/core/src/re_frame/core.cljc');
  assert.equal(result.tools_jvm_machines_viz, 'true');
  assert.equal(result.tools_jvm_testbed_support, 'true');
  // tools_jvm still fires from core for its own four jobs — the new outputs
  // are additional, not a replacement.
  assert.equal(result.tools_jvm, 'true');
});

test('the source-coords resolver arms the testbed-support endpoint lane (rf2-wq17m)', () => {
  // open_in_editor_server_test.clj verifies delegation to
  // `re-frame.source-coords`; that file is the concrete subject of the edge.
  const result = classify('implementation/core/src/re_frame/source_coords.cljc');
  assert.equal(result.tools_jvm_testbed_support, 'true');
});

test('a sibling per-feature artefact does NOT arm the machines-viz lane (rf2-wq17m)', () => {
  // The edge is machines-specific: flows/http/routing/ssr declare no
  // :local/root into machines-viz's test classpath, so widening to the whole
  // per-feature group would be a fan-out with no dependency behind it.
  for (const file of [
    'implementation/flows/src/re_frame/flows.cljc',
    'implementation/routing/src/re_frame/routing.cljc',
    'implementation/http/src/re_frame/http.cljc',
  ]) {
    assert.equal(
      classify(file).tools_jvm_machines_viz,
      'false',
      `${file} has no declared edge into tools/machines-viz's test classpath`,
    );
  }
});

// rf2-odlm3 — tools/machines-viz's OWN CLJS lane. The two suites the JVM job
// above exists for (`engine_grammar_parity_test.cljc`,
// `mermaid_public_smoke_test.cljc`) are `.cljc`, so "JVM-only" described the
// LANES, not the files: the consolidated `:node-test` bundle does not contain
// either namespace, because its `cljs-test$` selector never reaches a name
// ending `-test`. `tools/machines-viz/shadow-cljs.edn` declares the artefact's
// own `:machines-viz-node-test` build for them — its own bundle, so arming them
// cannot contaminate the shared run — and declaring it INSIDE the artefact also
// moves the ownership line the test-lane bijection gate reads.

test('a machines-viz change schedules the artefact CLJS lane (rf2-odlm3)', () => {
  const result = classify('tools/machines-viz/src/day8/re_frame2_machines_viz/chart.cljs');
  assert.equal(result.tools_cljs_machines_viz, 'true');
});

test('an engine change schedules BOTH halves of the parity ratchet (rf2-odlm3)', () => {
  const result = classify('implementation/machines/src/re_frame/machines.cljc');
  assert.equal(result.tools_jvm_machines_viz, 'true');
  assert.equal(result.tools_cljs_machines_viz, 'true');
});

test('machines-viz spec-only .md does NOT schedule its CLJS lane (rf2-odlm3)', () => {
  assert.equal(classify('tools/machines-viz/spec/API.md').tools_cljs_machines_viz, 'false');
});

test('an unrelated surface does NOT schedule the machines-viz CLJS lane (rf2-odlm3)', () => {
  assert.equal(classify('docs/core/intro.md').tools_cljs_machines_viz, 'false');
  assert.equal(classify('tools/story/src/re_frame/story.cljc').tools_cljs_machines_viz, 'false');
});

test('the machines-viz CLJS job is gated on its own output and runs the artefact build (rf2-odlm3)', () => {
  const workflow = fs.readFileSync(WORKFLOW, 'utf8');
  const block = jobBlock(workflow, 'cljs-tools-machines-viz');
  assert.match(block, /needs: detect_changed_surfaces/);
  assert.match(
    block,
    /if: needs\.detect_changed_surfaces\.outputs\.tools_cljs_machines_viz == 'true'/,
  );
  assert.match(
    block,
    /npm run test:tools-machines-viz/,
    'the job must invoke the artefact CLJS lane, not the consolidated one',
  );
  assert.match(
    workflow,
    /tools_cljs_machines_viz: \$\{\{ steps\.detect\.outputs\.tools_cljs_machines_viz \}\}/,
    'the output must be plumbed out of detect_changed_surfaces',
  );
  const aggregator = jobBlock(workflow, 'all-required-passed');
  assert.ok(
    aggregator.includes('- cljs-tools-machines-viz'),
    'a job absent from all-required-passed is advisory',
  );
});

test('the machines-viz CLJS lane is really declared where the arm says it is (rf2-odlm3)', () => {
  // The arm and the job are only honest while the build exists in the
  // artefact's own config — which is what moves the bijection gate's ownership
  // line. Read it off the file rather than asserting from memory.
  const config = fs.readFileSync(
    path.join(REPO_ROOT, 'tools', 'machines-viz', 'shadow-cljs.edn'),
    'utf8',
  );
  assert.match(config, /:machines-viz-node-test/);
  assert.match(config, /:target\s+:node-test/);
  // The selector must NOT be the shared bundle's `cljs-test$`: that is the
  // whole point — these suites are armed here WITHOUT a rename that would also
  // arm them inside the consolidated ~11.5k-test run.
  // Compared as a plain string, deliberately: the selector is itself a regex
  // written into EDN, and asserting it with a THIRD layer of escaping is how a
  // pin ends up matching nothing and passing anyway.
  const SELECTOR = String.raw`:ns-regexp "^day8\\.re-frame2-machines-viz\\..*-test$"`;
  assert.ok(
    config.includes(SELECTOR),
    `the artefact lane must select on its own prefix, not on cljs-test$ — expected ${SELECTOR}`,
  );
  assert.ok(
    !config.includes('"cljs-test$"'),
    'selecting on cljs-test$ here would mean the suites were renamed into the shared bundle',
  );
  const pkg = JSON.parse(
    fs.readFileSync(path.join(IMPL_ROOT, 'package.json'), 'utf8'),
  );
  assert.ok(pkg.scripts['test:tools-machines-viz'], 'the named entry point must exist');
  assert.match(pkg.scripts['test:tools-machines-viz'], /machines-viz-node-test/);
});

test('the declared :local/root edges these arms encode are still in the deps.edn files (rf2-wq17m)', () => {
  // The arms above are only honest while the dependency they mirror exists.
  // Read it off the artefact's own deps.edn rather than asserting from
  // memory: drop the dep and this goes red beside the arm it justifies.
  const vizDeps = fs.readFileSync(
    path.join(REPO_ROOT, 'tools', 'machines-viz', 'deps.edn'),
    'utf8',
  );
  assert.match(vizDeps, /"\.\.\/\.\.\/implementation\/machines"/);
  assert.match(vizDeps, /"\.\.\/\.\.\/implementation\/core"/);
  const supportDeps = fs.readFileSync(
    path.join(REPO_ROOT, 'tools', 'testbed-support', 'deps.edn'),
    'utf8',
  );
  assert.match(supportDeps, /"\.\.\/\.\.\/implementation\/core"/);
});

test('machines-viz spec-only .md does NOT fire its JVM lane (rf2-wq17m)', () => {
  // The spec-md guard above the artefact's catch-all must keep holding: prose
  // cannot change what the parity ratchet compares.
  assert.equal(classify('tools/machines-viz/spec/API.md').tools_jvm_machines_viz, 'false');
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
    // rf2-nutll — examples/ui/minimal-counter is a standalone FREEHAND project
    // now, resolved by :local/root, so a Freehand change can break the
    // ui-scaffold-smoke build that examples_compile gates.
    'implementation/freehand/src/re_frame/freehand.cljc',
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

// rf2-03298 — the fast-PR spine's tiering + mkdocs-resolution harness
// (scripts/_test_fixtures/test_fast_pr_docs_gate/run-self-test.sh) has existed
// since rf2-lwweq, and for its whole life NO workflow and NO npm script ran it:
// every assertion in it was local-only, i.e. skippable, i.e. not a gate. That is
// how the module-only mkdocs fallback rf2-g7p7l added could have been deleted
// while every remote check stayed green. #7364 wired the harness into the
// unconditional verify-readme-links job — but that wiring has no pin of its own.
// Deleting the step leaves valid YAML, a green matrix, and a witness that is
// local-only again, which is the original defect in a new shape. Same invariant
// as the adapter-disposition arm above, for the same reason, running in the same
// unconditional job (js-harness-self-tests -> test:script-policy).
test('the fast-PR spine self-test harness is wired into a REQUIRED check (rf2-03298)', () => {
  const workflow = fs.readFileSync(WORKFLOW, 'utf8');
  const readmeLinks = jobBlock(workflow, 'verify-readme-links');
  assert.match(
    readmeLinks,
    /bash scripts\/_test_fixtures\/test_fast_pr_docs_gate\/run-self-test\.sh/,
    'verify-readme-links must run the fast-PR spine self-test harness (unconditional job)',
  );
  // A job absent from all-required-passed's `needs:` is ADVISORY whatever its own
  // gate says (the standing rule this repo pins elsewhere), so the invocation
  // above only gates anything while verify-readme-links stays in that list.
  const aggregator = jobBlock(workflow, 'all-required-passed');
  assert.match(
    aggregator,
    /^\s*- verify-readme-links\s*$/m,
    'all-required-passed must keep verify-readme-links in needs: — otherwise the spine self-test harness is advisory',
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

// rf2-1x32v. The aggregator now reports a CANCELLED required job in
// different words from a FAILED one — "incomplete, re-run" versus "failed" —
// because reporting them identically is what made a transient read as a
// defect, and a check that cries wolf is one that stops being read.
//
// Splitting one blocking step into two introduced a fail-open that did not
// exist before: delete the cancelled arm and a cancelled required job reads
// GREEN, which would let a cancelled `beads-pr-boundary` carry a tracker
// database onto main. So both arms are pinned, each with its own `exit 1`.
// The distinction is presentational ONLY — no signal is not a pass.
test('the aggregator blocks on cancelled AND on failure, in distinct words (rf2-1x32v)', () => {
  const steps = jobBlock(fs.readFileSync(WORKFLOW, 'utf8'), 'all-required-passed')
    .split(/\n {6}- name: /)
    .slice(1);
  const armFor = (result) =>
    steps.filter((s) => new RegExp(`if: \\$\\{\\{ contains\\(needs\\.\\*\\.result, '${result}'\\)`).test(s));

  for (const result of ['failure', 'cancelled']) {
    const arm = armFor(result);
    assert.equal(arm.length, 1, `exactly one aggregator arm must guard on '${result}'`);
    assert.match(
      arm[0],
      /^\s*exit 1\s*$/m,
      `the '${result}' arm must BLOCK — a required job that produced no pass is never green`,
    );
  }
  // Distinct words, so the operator is told which of the two it is.
  assert.match(armFor('failure')[0], /FAILED/);
  assert.match(armFor('cancelled')[0], /INCOMPLETE, not failed/);
  // Failure is adjudicated FIRST, so a run carrying both is never described
  // as merely incomplete.
  assert.ok(
    steps.indexOf(armFor('failure')[0]) < steps.indexOf(armFor('cancelled')[0]),
    'the failure arm must precede the cancelled arm',
  );
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

// rf2-65ajl — this row USED to assert `test:story-feature-load` was absent
// from the PR job outright. That pin is what made the second half of the
// false-green permanent: a PR could change the full gate's own runner and no
// PR-time command would load it, because the only command that does was pinned
// out. The tier split it was defending is real and is kept — the full sweep
// does not belong on every Story/Xray PR — but "not on every PR" is not "on no
// PR". The command is now present and CONDITIONAL, so the claim becomes: it
// runs only under story_full_gate.
// Return the step that RUNS `command`: from its `- name:` header through the
// `run:` line, so the step's own `if:` is inside and a neighbour's is not.
// Anchored on `run: ` deliberately — every one of these commands is also named
// in the surrounding prose, and a `.includes()` over a comment would let a
// step's condition be read off the wrong step (which is how the first draft of
// this row passed against the smoke step's `if:`).
function stepRunning(block, command) {
  const marker = `run: ${command}`;
  const idx = block.indexOf(marker);
  if (idx === -1) return null;
  const start = block.lastIndexOf('\n      - name:', idx);
  return start === -1 ? null : block.slice(start, idx + marker.length);
}

test('PR story-xray-browser job runs the FULL feature-load gate, gated on story_full_gate (rf2-65ajl)', () => {
  const block = storyXrayJobBlock(fs.readFileSync(WORKFLOW, 'utf8'));
  // The command and its condition must be in the SAME step, or the gate is
  // either unconditional (nightly cost on every Story PR) or dead.
  const step = stepRunning(block, 'npm run test:story-feature-load');
  assert.ok(
    step,
    'the PR job must RUN the one command that loads the full-gate runners',
  );
  assert.match(
    step,
    /if:\s*needs\.detect_changed_surfaces\.outputs\.story_full_gate == 'true'/,
    'the full feature-load step must be gated on story_full_gate',
  );
});

// rf2-9n2cv — this row USED to pin `test:story-static` out of the PR job too,
// alongside the non-smoke Xray gate. That was the same forbids-the-fix pin
// rf2-65ajl hit for `test:story-feature-load`, one gate over: the only command
// that loads check-story-static.cjs was asserted absent, so the file could
// never be exercised by the PR that changed it. The story-static half is now
// present and CONDITIONAL (see the row below); the Xray half STAYS, and stays
// deliberately — see the comment on it.
test('PR story-xray-browser job still keeps the rest of the sweep nightly (rf2-wa3oo)', () => {
  const block = storyXrayJobBlock(fs.readFileSync(WORKFLOW, 'utf8'));
  // The non-smoke (full-matrix) Xray gate must not run at PR time. The
  // `:smoke` suffix is intentionally allowed; assert the bare invocation
  // (followed by end-of-line, not `:smoke`) is absent.
  //
  // rf2-9n2cv considered lifting this too and deliberately did NOT. It is not
  // the same defect: `test:xray-feature-gate:smoke` — a command this job
  // already runs — `require`s the whole of
  // tools/xray/testbeds/feature_matrix/scenarios.cjs, so the file IS loaded,
  // parsed and validated at PR time (the launcher even fails loud on an empty
  // smoke set). What is not EXECUTED at PR time is the non-smoke rows, and
  // that is the documented two-tier policy in TESTING.md ("everything else is
  // nightly by default"), not a fail-open hole. Reversing it means running the
  // all-scenarios/all-surfaces sweep on every scenarios.cjs edit — a policy
  // call, filed separately rather than smuggled in here.
  assert.doesNotMatch(block, /npm run test:xray-feature-gate(?!:smoke)/);
});

test('PR story-xray-browser job runs the Story STATIC gate, gated on story_static_gate (rf2-9n2cv)', () => {
  const block = storyXrayJobBlock(fs.readFileSync(WORKFLOW, 'utf8'));
  // Same two-part claim as the feature-load row above: the command and its
  // condition must be in the SAME step, or the gate is either unconditional
  // (nightly cost on every Story PR) or dead.
  const step = stepRunning(block, 'npm run test:story-static');
  assert.ok(
    step,
    'the PR job must RUN the one command that loads check-story-static.cjs',
  );
  assert.match(
    step,
    /if:\s*needs\.detect_changed_surfaces\.outputs\.story_static_gate == 'true'/,
    'the static-export step must be gated on story_static_gate',
  );
});

test('PR story-xray-browser job opens for the static tier too (rf2-9n2cv)', () => {
  // A static-gate-only change leaves both other outputs false, so a job
  // condition that did not name this one would skip the job and the new step
  // with it — the same hole one level up, which is exactly how rf2-65ajl's
  // never-fires mode would have presented.
  const block = storyXrayJobBlock(fs.readFileSync(WORKFLOW, 'utf8'));
  const header = block.slice(0, block.indexOf('steps:'));
  assert.match(header, /outputs\.story_static_gate == 'true'/);
  assert.match(header, /\|\|/, 'the tier conditions must be a disjunction');
});

test('detect_changed_surfaces exports story_static_gate (rf2-9n2cv)', () => {
  // The never-fires mode. The classifier can emit an output and the workflow
  // still never see it: a job reads `needs.<job>.outputs.<name>`, which
  // resolves to the empty string unless the producing job DECLARES it. An
  // undeclared output makes every `== 'true'` false — a gate that can never
  // fire, and silently.
  const block = jobBlock(fs.readFileSync(WORKFLOW, 'utf8'), 'detect_changed_surfaces');
  assert.match(
    block,
    /story_static_gate: \$\{\{ steps\.detect\.outputs\.story_static_gate \}\}/,
  );
});

test('the Story static gate arms on its own definition (rf2-9n2cv)', () => {
  // The classifier half. Before this bead, check-story-static.cjs classified
  // to cljs_node_test + cljs_browser + cljs_prod + bundle_isolation +
  // reagent_slim_bundle — five outputs, not one of which schedules a job that
  // runs `npm run test:story-static`.
  for (const file of [
    'implementation/scripts/check-story-static.cjs',
    'implementation/scripts/story-build.cjs',
  ]) {
    assert.ok(
      fs.existsSync(path.join(REPO_ROOT, file)),
      `${file} must exist — this row pins the routing of a REAL gate file`,
    );
    const result = classify(file);
    assert.equal(result.story_static_gate, 'true', file);
    // WIDENS, NEVER NARROWS. The arm sits above the generic
    // implementation/scripts/* case, so it must re-set everything that case
    // would have set or this bead silently drops five tiers from two files.
    for (const kept of [
      'cljs_node_test',
      'cljs_browser',
      'cljs_prod',
      'bundle_isolation',
      'reagent_slim_bundle',
    ]) {
      assert.equal(result[kept], 'true', `${file} must keep ${kept}`);
    }
    // And it must not drag either sibling Story tier along: neither the smoke
    // commands nor test:story-feature-load loads these two files.
    assert.equal(result.story_xray_browser, 'false', file);
    assert.equal(result.story_full_gate, 'false', file);
  }
});

test('every script the static gate spawns is armed (rf2-9n2cv)', () => {
  // The teeth. Read the roster off the GATE rather than trusting the list
  // above: check-story-static.cjs spawns its build step by name, so a second
  // spawned sibling is armed on arrival rather than on the next audit. Names,
  // not counts.
  const gate = path.join(
    REPO_ROOT,
    'implementation',
    'scripts',
    'check-story-static.cjs',
  );
  const source = fs.readFileSync(gate, 'utf8');
  const spawned = [
    ...source.matchAll(/path\.join\(__dirname,\s*'([^']+\.cjs)'\)/g),
  ].map((m) => m[1]);
  assert.ok(
    spawned.length > 0,
    'expected check-story-static.cjs to spawn at least one sibling script — ' +
      'if the spawn shape changed, this parse has rotted and is no longer teeth',
  );
  for (const name of spawned) {
    const rel = `implementation/scripts/${name}`;
    assert.ok(
      fs.existsSync(path.join(REPO_ROOT, rel)),
      `${rel} is spawned by check-story-static.cjs but does not exist`,
    );
    assert.equal(
      classify(rel).story_static_gate,
      'true',
      `${rel} is executed by npm run test:story-static but does not arm story_static_gate`,
    );
  }
});

test('ordinary implementation/scripts changes do NOT arm the static gate (rf2-9n2cv negative control)', () => {
  // The expensive browser job must stay off scripts that cannot reach the
  // static export. Real files, not hypotheticals: a negative control pinning a
  // path nothing produces any more is permanently, silently green.
  for (const file of [
    'implementation/scripts/run-browser-tests.cjs',
    'implementation/scripts/check-examples-compile.cjs',
    // The two shared harness helpers the gate requires but which are
    // deliberately out of the roster — each is already exercised by PR-time
    // gates and carries its own policy test.
    'implementation/scripts/lib/local-browser-harness.cjs',
    'implementation/scripts/lib/browser-test-report.cjs',
  ]) {
    assert.ok(
      fs.existsSync(path.join(REPO_ROOT, file)),
      `${file} must exist — a negative control on a phantom path is vacuous`,
    );
    assert.equal(classify(file).story_static_gate, 'false', file);
  }
});

test('PR story-xray-browser job opens for EITHER tier (rf2-65ajl)', () => {
  // A full-gate-only change leaves story_xray_browser false, so a job condition
  // reading only that output would skip the job and the new step with it — the
  // same hole one level up.
  const block = storyXrayJobBlock(fs.readFileSync(WORKFLOW, 'utf8'));
  const header = block.slice(0, block.indexOf('steps:'));
  assert.match(header, /outputs\.story_xray_browser == 'true'/);
  assert.match(header, /outputs\.story_full_gate == 'true'/);
  assert.match(header, /\|\|/, 'the two tier conditions must be a disjunction');
});

test('the two PR-smoke steps are gated on story_xray_browser (rf2-65ajl)', () => {
  // The converse of the row above: a full-gate-only PR must not pay for two
  // smoke testbed compiles whose commands its diff cannot reach.
  const block = storyXrayJobBlock(fs.readFileSync(WORKFLOW, 'utf8'));
  for (const command of [
    'npm run test:xray-feature-gate:smoke',
    'npm run test:story-play-scripts',
  ]) {
    const step = stepRunning(block, command);
    assert.ok(step, `${command} must live in a named step`);
    assert.match(
      step,
      /if:\s*needs\.detect_changed_surfaces\.outputs\.story_xray_browser == 'true'/,
      `${command} must be gated on story_xray_browser`,
    );
  }
});

test('detect_changed_surfaces exports story_full_gate (rf2-65ajl)', () => {
  // The classifier can emit an output and the workflow still never see it: a
  // job reads `needs.<job>.outputs.<name>`, which resolves to the empty string
  // unless the producing job DECLARES it. An undeclared output makes every
  // `== 'true'` false — a gate that can never fire, and silently.
  const block = jobBlock(fs.readFileSync(WORKFLOW, 'utf8'), 'detect_changed_surfaces');
  assert.match(
    block,
    /story_full_gate: \$\{\{ steps\.detect\.outputs\.story_full_gate \}\}/,
  );
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

// rf2-65ajl — this roster used to be four files all asserted to fire
// story_xray_browser. Two of them are not on the PR-smoke tier's path at all:
// the smoke runs `test:xray-feature-gate:smoke` + `test:story-play-scripts`,
// and serve-and-run-story-feature-load-tests.cjs / run-story-feature-load-
// tests.cjs are reachable from `npm run test:story-feature-load` and nothing
// else. They move to the story_full_gate roster below, where the output
// schedules the step that actually runs them.
const STORY_SMOKE_GATE_FILES = [
  'examples/scripts/serve-and-run-story-play-scripts.cjs',
  'examples/scripts/story-feature-load-port.cjs',
];
for (const file of STORY_SMOKE_GATE_FILES) {
  test(`${file} fires story_xray_browser (rf2-y9o5e3)`, () => {
    const result = classify(file);
    assert.equal(result.story_xray_browser, 'true');
  });
}

// rf2-65ajl — the FULL Story feature-load gate. Two independent halves had to
// close together: the classifier armed nothing for tools/story/test/**, and the
// job story_xray_browser schedules ran neither command that loads those files.
// These rows pin the first half; the workflow rows above pin the second.

const STORY_FULL_GATE_FILES = [
  'tools/story/test/story_feature_load.cjs',
  'tools/story/test/story_browser_scenarios.cjs',
  'examples/scripts/serve-and-run-story-feature-load-tests.cjs',
  'examples/scripts/run-story-feature-load-tests.cjs',
  'examples/scripts/story-feature-load-port.cjs',
];
for (const file of STORY_FULL_GATE_FILES) {
  test(`${file} fires story_full_gate (rf2-65ajl)`, () => {
    const result = classify(file);
    assert.equal(result.story_full_gate, 'true');
  });
}

test('the full-gate launchers do not fall through to the generic examples fan-out (rf2-65ajl)', () => {
  // Moving these two off the story_xray_browser arm left them with no arm of
  // their own, so a POSIX `case` walked on to the generic `examples/*` case and
  // silently armed cljs_node_test + cljs_browser — two heavy jobs, on two Node
  // launchers that compile no CLJS. They have a dedicated no-op arm now whose
  // only job is to stop the walk; this row is what keeps it there.
  for (const file of [
    'examples/scripts/serve-and-run-story-feature-load-tests.cjs',
    'examples/scripts/run-story-feature-load-tests.cjs',
  ]) {
    const result = classify(file);
    assert.equal(result.story_full_gate, 'true', file);
    assert.equal(result.cljs_browser, 'false', file);
    assert.equal(result.cljs_node_test, 'false', file);
    // The one arm they kept: they are under examples/, so the examples-compile
    // roster still fires, exactly as it did before this bead.
    assert.equal(result.examples_compile, 'true', file);
    // And the smoke tier they were wrongly on: neither smoke command loads
    // them, so it must not fire.
    assert.equal(result.story_xray_browser, 'false', file);
  }
});

test('the play-script launcher stays on the smoke tier only (rf2-65ajl)', () => {
  // The converse control. serve-and-run-story-play-scripts.cjs IS the command
  // the smoke tier runs, so it must keep story_xray_browser and must NOT drag
  // the full gate along.
  const result = classify('examples/scripts/serve-and-run-story-play-scripts.cjs');
  assert.equal(result.story_xray_browser, 'true');
  assert.equal(result.story_full_gate, 'false');
});

test('every spec module the full-gate runner loads is armed (rf2-65ajl)', () => {
  // The teeth. Read the roster off the RUNNER rather than trusting the list
  // above: `ALL_SPEC_FILES` in run-story-feature-load-tests.cjs is what
  // `npm run test:story-feature-load` actually executes, so a third spec added
  // there is armed on arrival rather than on the next audit. Names, not counts.
  const runner = path.join(
    REPO_ROOT,
    'examples',
    'scripts',
    'run-story-feature-load-tests.cjs',
  );
  const source = fs.readFileSync(runner, 'utf8');
  const block = source.slice(
    source.indexOf('const ALL_SPEC_FILES'),
    source.indexOf('];', source.indexOf('const ALL_SPEC_FILES')),
  );
  assert.ok(block, 'ALL_SPEC_FILES not found in the full-gate runner');
  const specs = [...block.matchAll(/path\.join\(REPO_ROOT,\s*([^)]+)\)/g)].map((m) =>
    m[1]
      .split(',')
      .map((part) => part.trim().replace(/^['"]|['"]$/g, ''))
      .filter(Boolean)
      .join('/'),
  );
  assert.ok(specs.length > 0, 'expected the runner to declare at least one spec module');
  for (const spec of specs) {
    assert.ok(
      fs.existsSync(path.join(REPO_ROOT, spec)),
      `${spec} is listed in ALL_SPEC_FILES but does not exist — the parse has rotted`,
    );
    assert.equal(
      classify(spec).story_full_gate,
      'true',
      `${spec} is executed by npm run test:story-feature-load but does not arm story_full_gate`,
    );
  }
});

test('ordinary Story/Xray runtime changes keep the cheap smoke path (rf2-65ajl)', () => {
  // The bead's second criterion. A src/testbed change must NOT drag the full
  // sweep onto the critical path — it gets the smoke tier it already had.
  for (const file of [
    'tools/story/src/re_frame/story.cljc',
    'tools/story/testbeds/counter_with_stories/stories.cljs',
    'tools/xray/src/day8/re_frame2_xray/core.cljs',
    'tools/xray/testbeds/edn_inspector/core.cljs',
    // The Xray gate's own scenario roster is the instructive contrast: unlike
    // the Story runners, `test:xray-feature-gate:smoke` — a command the smoke
    // tier already runs — reads this file, so the cheap tier really does
    // exercise it and it needs no second output.
    'tools/xray/testbeds/feature_matrix/scenarios.cjs',
  ]) {
    assert.ok(
      fs.existsSync(path.join(REPO_ROOT, file)),
      `${file} must exist — this row pins the routing of a REAL runtime file`,
    );
    const result = classify(file);
    assert.equal(result.story_xray_browser, 'true', file);
    assert.equal(result.story_full_gate, 'false', file);
  }
});

test('unrelated JVM / unit-test-only changes arm NEITHER browser tier (rf2-65ajl negative control)', () => {
  // The bead's fourth criterion. The expensive browser job must stay off
  // changes that cannot reach a browser at all.
  for (const file of [
    // Real files, not hypotheticals: a negative control that pins a path
    // nothing produces any more is permanently, silently green.
    'tools/story/test/re_frame/story_decorator_chain_test.clj',
    'tools/story/test/re_frame/story_cljs_test.cljs',
    'tools/xray/test/day8/re_frame2_xray/config_test.clj',
    'implementation/core/src/re_frame/core.cljc',
    'spec/Conventions.md',
  ]) {
    assert.ok(
      fs.existsSync(path.join(REPO_ROOT, file)),
      `${file} must exist — a negative control on a phantom path is vacuous`,
    );
    const result = classify(file);
    assert.equal(result.story_full_gate, 'false', file);
    assert.equal(result.story_xray_browser, 'false', file);
  }
});

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

// implementation/spec-resource is the ONE build-time reader for committed
// spec/ data: the Freehand conformance fixture loader and the api-manifest
// CLJS probe both expand through it. Its own suite is the deterministic
// control for a cold-load race that has shipped twice behind fully green
// lanes — which is exactly why the routing has to be pinned. If the
// classifier leaves every output false, the one job in CI that goes red
// when the racy shape returns simply SKIPS, and the aggregator passes.

for (const file of [
  'implementation/spec-resource/src/re_frame/build/spec_resource.clj',
  'implementation/spec-resource/test/re_frame/build/spec_resource_test.clj',
  'implementation/spec-resource/deps.edn',
  // Artefact-ROOT matching, not an enumeration: a future nested namespace
  // must route too, and the rot would otherwise be silent.
  'implementation/spec-resource/src/re_frame/build/deeply/nested.clj',
]) {
  test(`${file} arms implementation_jvm + cljs_node_test (shared spec/ reader)`, () => {
    const result = classify(file);
    assert.equal(
      result.implementation_jvm,
      'true',
      'the reader change must run its own race control (jvm-spec-resource) and the fixture loader lane (jvm-freehand)',
    );
    assert.equal(
      result.cljs_node_test,
      'true',
      'the consolidated :node-test build is where both consumers macro-expand through this reader',
    );
  });
}

test('jvm-spec-resource is job-level gated on implementation_jvm', () => {
  const block = jobBlock(fs.readFileSync(WORKFLOW, 'utf8'), 'jvm-spec-resource');
  assert.match(block, /needs: detect_changed_surfaces/);
  assert.match(
    block,
    /if: needs\.detect_changed_surfaces\.outputs\.implementation_jvm == 'true'/,
  );
});

test('all-required-passed aggregator needs jvm-spec-resource', () => {
  const block = jobBlock(fs.readFileSync(WORKFLOW, 'utf8'), 'all-required-passed');
  assert.match(
    block,
    /- jvm-spec-resource\r?\n/,
    'the race control must be reachable from the single required context',
  );
});

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
  //
  // This arm is about a DONOR test that stayed, so the path must still name
  // one: the previous fixture pointed at eq_cljs_test.cljc, which the F3a
  // compiler transplant moved to implementation/freehand/test/. The classifier
  // never stats a path, so that rot was silent and permanently green. Unlike
  // the deliberately-not-yet-existing future paths pinned elsewhere in this
  // file, this row's whole claim is "an existing ordinary UI test", so assert
  // it exists.
  const donorTest = 'implementation/ui/test/re_frame/ui/error_roster_cljs_test.cljc';
  assert.ok(
    fs.existsSync(path.join(REPO_ROOT, donorTest)),
    `${donorTest} must exist — this row pins the routing of a REAL donor UI test`,
  );
  const result = classify(donorTest);
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

test('the freehand arm is ARTEFACT-ROOT matching, not an enumeration (rf2-drpa3.61)', () => {
  // The classifier case is `implementation/freehand/*)`. A POSIX `case` glob's
  // `*` spans `/`, so the whole artefact root is covered at any depth. That is
  // the point: an enumeration of today's three files rots on the first nested
  // namespace, and the rot is SILENT — the new file simply classifies as
  // nothing and its required gates skip.
  //
  // These paths do not exist yet. They are the shapes F1b+ will add (nested
  // compiler/emitter namespaces, a testbed, a README), pinned so a future
  // narrowing of the case reds here instead of in production.
  for (const file of [
    'implementation/freehand/src/re_frame/freehand/compiler/analyze/deeply/nested.cljc',
    'implementation/freehand/test/re_frame/freehand/emitters/react/deep_nested_cljs_test.cljs',
    'implementation/freehand/testbed/core.cljs',
    'implementation/freehand/README.md',
  ]) {
    const result = classify(file);
    assert.equal(
      result.implementation_jvm,
      'true',
      `future nested path must arm implementation_jvm: ${file}`,
    );
    assert.equal(
      result.cljs_node_test,
      'true',
      `future nested path must arm cljs_node_test: ${file}`,
    );
  }
});

test('implementation/freehand/** stays OFF the heavy per-feature gates (rf2-drpa3.58)', () => {
  // Scope guard, not an aspiration: bundle_isolation measures the examples
  // set and Freehand mounts no testbed the smokes drive, so those tiers would
  // be pure cost. Widen the classifier case (and this test) when it gains one
  // — implementation/ui/* is the precedent. cljs_browser LEFT this list under
  // rf2-drpa3.70 (the artefact gained mounted-DOM tests) and cljs_prod under
  // rf2-kll2x (it gained an `-elision-prod-test` suite); in both cases the
  // gate stopped being cost and became the only place the tests can run.
  const result = classify('implementation/freehand/src/re_frame/freehand.cljc');
  for (const key of ['bundle_isolation', 'ui_gates', 'ui_smoke']) {
    assert.equal(result[key], 'false', `freehand must not arm ${key} yet`);
  }
});

// ---------------------------------------------------------------------------
// rf2-8a6s — the Hicasso artefact surface.
//
// rf2-hic-001 created implementation/hicasso/ and was fenced out of .github/,
// so the package landed matching NO classifier case: a hicasso-only diff set
// every output false and every job skipped. TESTING.md §Changed-surface
// classifier names the shape — a new artefact directory needs a classifier
// rule AND a workflow gate reading it, and either side missing is a silent
// hole. Both halves are pinned below, and the third test pins the SCOPE, so a
// later widening is a deliberate edit here rather than a drift.
// ---------------------------------------------------------------------------

test('implementation/hicasso/** arms cljs_node_test (rf2-8a6s)', () => {
  for (const file of [
    'implementation/hicasso/src/re_frame/hicasso.cljc',
    'implementation/hicasso/src/re_frame/hicasso/impl/runtime.cljs',
    'implementation/hicasso/test/re_frame/hicasso/smoke_cljs_test.cljs',
    'implementation/hicasso/deps.edn',
    'implementation/hicasso/frozen-sources.edn',
    'implementation/hicasso/scripts/check_freeze.py',
  ]) {
    const result = classify(file);
    assert.equal(
      result.cljs_node_test,
      'true',
      `${file} must arm cljs_node_test — it is the ONLY output whose job runs `
        + 'anything covering this artefact (the :node-test smoke, the freeze '
        + 'gate, the bench-lane compile), and without it a hicasso-only PR '
        + 'runs none of them',
    );
  }
});

test('the hicasso arm is ARTEFACT-ROOT matching, not an enumeration (rf2-8a6s)', () => {
  // Same reasoning as the freehand case above: `implementation/hicasso/*)` is
  // a POSIX `case` glob whose `*` spans `/`, so the artefact root is covered
  // at any depth. rf2-hic-009 carves the runtime into owned modules, which is
  // exactly the change that would rot an enumeration — silently, because a new
  // file that classifies as nothing simply skips its gates.
  //
  // These paths do not exist. They are the shapes the carve-up will add,
  // pinned so a future narrowing of the case reds here instead of in CI.
  for (const file of [
    'implementation/hicasso/src/re_frame/hicasso/impl/commit/deeply/nested.cljs',
    'implementation/hicasso/test/re_frame/hicasso/impl/commit_cljs_test.cljs',
    'implementation/hicasso/README.md',
  ]) {
    const result = classify(file);
    assert.equal(
      result.cljs_node_test,
      'true',
      `future nested path must arm cljs_node_test: ${file}`,
    );
  }
});

test('implementation/hicasso/** stays OFF the gates no hicasso suite reaches (rf2-8a6s)', () => {
  // Scope guard, and each entry has a named release condition — TESTING.md
  // warns that a coarse rule clutters the matrix with skipping entries, and a
  // gate that runs not one line of the changed surface is worse than none
  // because it reads as coverage.
  //
  //   implementation_jvm — the runtime requires React, so every suite the
  //     artefact owns is CLJS; its `:test` alias is a `--probe` classpath
  //     check and it is deliberately off scripts/test-jvm-implementation.sh's
  //     roster. Arm it the same commit a JVM-runnable suite and the roster row
  //     land together (check_jvm_lane_rosters.py fails both ways).
  //   cljs_browser — hicasso IS on the :browser-test classpath, but that build
  //     selects `-dom-cljs-test$` and the package owns no such namespace.
  //   cljs_prod — no `-elision-prod-test$` namespace.
  //   bundle_isolation / ui_gates / ui_smoke — no example resolves the
  //     artefact and it mounts no testbed those smokes drive.
  const result = classify('implementation/hicasso/src/re_frame/hicasso.cljc');
  for (const key of [
    'implementation_jvm',
    'cljs_browser',
    'cljs_prod',
    'bundle_isolation',
    'ui_gates',
    'ui_smoke',
  ]) {
    assert.equal(result[key], 'false', `hicasso must not arm ${key} yet`);
  }
});

test('the cljs job runs BOTH hicasso gates the classifier arm schedules (rf2-8a6s)', () => {
  // The gate half of the classifier rule. The arm above is worthless if the
  // job it lights stops running the artefact's checks, and the freeze gate in
  // particular has no other scheduled home — before rf2-8a6s it ran only by
  // hand.
  const block = jobBlock(fs.readFileSync(WORKFLOW, 'utf8'), 'cljs');
  assert.match(
    block,
    /run: npm run test:hicasso-freeze$/m,
    'the cljs job must run the hicasso freeze gate (FROZEN donor digests + the '
      + 'no-bench-import seal); it runs nowhere else',
  );
  assert.match(
    block,
    /run: npm run test:hicasso-compile$/m,
    'the cljs job must keep running the hicasso bench-lane compile (rf2-2rtt6.73)',
  );
});

// ---------------------------------------------------------------------------
// rf2-kll2x — the production-elision lane.
//
// rf2-3slzz added the first Freehand namespace matching `-elision-prod-test$`,
// so the first Freehand code riding `:browser-test-prod-elision` (`:advanced` +
// `{goog.DEBUG false}`). It was on that build's classpath from the day it
// landed — freehand/test is on the global :source-paths — but cljs_prod stayed
// false on a Freehand-only PR, so `cljs-browser-prod-elision` SKIPPED and only
// the unconditional nightly ever ran it. Same false-green shape rf2-drpa3.58 /
// .61 / .70 closed for the host and browser tiers of this same tree.
// ---------------------------------------------------------------------------

test('the Freehand production-posture surfaces arm cljs_prod (rf2-kll2x)', () => {
  // The suite itself, plus every Freehand file that can invalidate it and is
  // matched by a case SHADOWING the artefact root — a POSIX `case` takes the
  // first match, so those narrower cases must replicate the arm or they
  // silently narrow it. cell.cljc carries `observe!`'s candidate consultation
  // (the check under test); freehand.cljc is the door the suite declares and
  // reads through.
  for (const file of [
    'implementation/freehand/test/re_frame/freehand/reactive_false_check_elision_prod_test.cljs',
    'implementation/freehand/src/re_frame/freehand/cell.cljc',
    'implementation/freehand/src/re_frame/freehand.cljc',
    'implementation/freehand/src/re_frame/freehand/control.cljc',
    'implementation/freehand/src/re_frame/freehand/shell.cljs',
    'implementation/freehand/test/re_frame/freehand/release_app.cljs',
  ]) {
    assert.equal(
      classify(file).cljs_prod,
      'true',
      `${file} can invalidate the production-posture proof — it must schedule cljs-browser-prod-elision`,
    );
  }
});

test('the Freehand prod-elision namespace actually exists (rf2-kll2x)', () => {
  // The classifier never stats a path, so the row above would stay green if
  // the suite were renamed or deleted — routing a lane at nothing. Pin its
  // existence with the routing, and pin the suffix the build's ns-regexp
  // (`-elision-prod-test$`) selects on: rename it and the file compiles into
  // no build at all.
  const file =
    'implementation/freehand/test/re_frame/freehand/reactive_false_check_elision_prod_test.cljs';
  assert.ok(
    fs.existsSync(path.join(REPO_ROOT, file)),
    `${file} must exist — it is the production proof this routing exists to run`,
  );
  assert.match(path.basename(file), /_elision_prod_test\.cljs$/);
});

test('Freehand PROSE does not pay for an :advanced compile (rf2-kll2x)', () => {
  // Same reasoning as the browser arm: the widening is about what a
  // production compile does to Freehand code, and Markdown is not compiled.
  for (const file of [
    'implementation/freehand/README.md',
    'implementation/freehand/doc/design/emitters.md',
  ]) {
    assert.equal(classify(file).cljs_prod, 'false', `${file} must not arm cljs_prod`);
  }
});

// ---------------------------------------------------------------------------
// rf2-drpa3.70 — the React surfaces and the browser lane.
//
// F1c shipped an interpreted React emitter whose real claim is what a BROWSER
// does with its output, and two `*-dom-cljs-test` namespaces that mount
// through `react-dom/client` to prove it. Those files already ride the
// `:browser-test` build (freehand/src + freehand/test are on :source-paths;
// the build's `-dom-cljs-test$` regex selects them) — but `cljs-browser` is
// surface-gated, so a Freehand-only PR skipped the only lane that can execute
// them.
//
// The `cljs` job is not a substitute. The `:node-test` regex matches the very
// same files, where they find no DOM and self-skip. Green, and worth nothing.
// ---------------------------------------------------------------------------

test('the Freehand React surfaces arm cljs_browser (rf2-drpa3.70)', () => {
  // Source, mounted-DOM test, the shared view declarations both emitters
  // render, and a fixture the mounted assertions read — one representative of
  // each transitive semantic input to mounted output.
  for (const file of [
    'implementation/freehand/src/re_frame/freehand/react.cljs',
    'implementation/freehand/test/re_frame/freehand/react_mount_dom_cljs_test.cljs',
    'implementation/freehand/test/re_frame/freehand/route_link_native_dom_cljs_test.cljs',
    'implementation/freehand/test/re_frame/freehand/tree_views.cljc',
    'implementation/freehand/src/re_frame/freehand/conversion.cljc',
    'spec/conformance/freehand/fixtures/fh-struct-007.edn',
    'spec/conformance/freehand/fixtures/fh-routelink-001.edn',
  ]) {
    assert.equal(
      classify(file).cljs_browser,
      'true',
      `${file} can change mounted output — it must schedule the cljs-browser job`,
    );
  }
});

test('the Freehand browser-test namespaces actually exist (rf2-drpa3.70)', () => {
  // The classifier never stats a path, so the row above would stay green if
  // the mounted tests were renamed or deleted — routing a lane at nothing.
  // These two ARE the browser proof; pin their existence with the routing.
  for (const file of [
    'implementation/freehand/test/re_frame/freehand/react_mount_dom_cljs_test.cljs',
    'implementation/freehand/test/re_frame/freehand/route_link_native_dom_cljs_test.cljs',
  ]) {
    assert.ok(
      fs.existsSync(path.join(REPO_ROOT, file)),
      `${file} must exist — it is the mounted proof this routing exists to run`,
    );
    // ...and carry the suffix the :browser-test ns-regexp selects on.
    assert.match(path.basename(file), /_dom_cljs_test\.cljs$/);
  }
});

test('Freehand PROSE does not pay for a Chromium run (rf2-drpa3.70)', () => {
  // The widening is about mounted output. Markdown cannot change it, and the
  // browser lane is the expensive one — so the prose arm keeps its two host
  // suites and stops there.
  for (const file of [
    'implementation/freehand/README.md',
    'implementation/freehand/doc/design/emitters.md',
  ]) {
    const result = classify(file);
    assert.equal(result.cljs_browser, 'false', `${file} must not arm cljs_browser`);
    assert.equal(result.implementation_jvm, 'true', `${file} still arms implementation_jvm`);
    assert.equal(result.cljs_node_test, 'true', `${file} still arms cljs_node_test`);
  }
});

// rf2-drpa3.66 — the fixture corpus is the OTHER half of the Freehand surface.
//
// conformance.cljc reads spec/conformance/freehand/fixtures/*.edn at
// MACRO-EXPANSION time and inlines the value, so both host suites assert
// against those exact bytes. rf2-drpa3.58 armed implementation/freehand/** but
// not the corpus it consumes: a fixture-only PR classified as nothing and BOTH
// newly-required host jobs skipped, unexplained, past `all-required-passed`.
//
// The always-on conformance-index check is not a substitute — it verifies that
// an active row NAMES AN EXISTING FIXTURE, never the fixture's contract VALUES.
// A mutation that preserves the path and the `:fh/id` (e.g. flipping
// FH-CALL-001's `:predicates :view?`) keeps that check green and reds
// descriptor_cljs_test.cljc on both hosts — but only if the hosts RUN.

test('freehand conformance fixtures arm both host suites (rf2-drpa3.66)', () => {
  // Every fixture the corpus ships today, by conformance family.
  for (const file of [
    'spec/conformance/freehand/fixtures/fh-call-001.edn',
    'spec/conformance/freehand/fixtures/fh-props-003.edn',
    'spec/conformance/freehand/fixtures/fh-event-001.edn',
    'spec/conformance/freehand/fixtures/fh-struct-007.edn',
  ]) {
    const result = classify(file);
    assert.equal(
      result.implementation_jvm,
      'true',
      `${file} must arm implementation_jvm or the required jvm-freehand job SKIPS on a fixture-only PR`,
    );
    assert.equal(
      result.cljs_node_test,
      'true',
      `${file} must arm cljs_node_test — the :node-test build inlines this fixture too`,
    );
  }
});

test('the freehand fixture arm is ROOT matching, not an enumeration (rf2-drpa3.66)', () => {
  // Same rot argument as the artefact-root test above: a POSIX `case` glob's
  // `*` spans `/`, so the whole corpus root is covered at any depth. These
  // paths do not exist yet — they are the shapes the corpus takes if the
  // fixtures gain per-family subdirectories — pinned so a future narrowing of
  // the case reds HERE rather than silently skipping two required jobs.
  for (const file of [
    'spec/conformance/freehand/fixtures/props/fh-props-004.edn',
    'spec/conformance/freehand/fixtures/call/deeply/nested/fh-call-999.edn',
  ]) {
    const result = classify(file);
    assert.equal(
      result.implementation_jvm,
      'true',
      `future nested fixture must arm implementation_jvm: ${file}`,
    );
    assert.equal(
      result.cljs_node_test,
      'true',
      `future nested fixture must arm cljs_node_test: ${file}`,
    );
  }
});

test('freehand fixtures stay OFF the heavy gates, like the artefact (rf2-drpa3.66)', () => {
  // Scope guard. The fixtures feed exactly the suites the artefact case arms
  // and nothing else, so this arm must not drift wider than
  // implementation/freehand/* — widen both together or neither. cljs_browser
  // left this list under rf2-drpa3.70, on both cases at once: FH-STRUCT-007
  // and FH-ROUTELINK-001..003 are read by the mounted-DOM tests.
  const result = classify('spec/conformance/freehand/fixtures/fh-call-001.edn');
  for (const key of ['cljs_prod', 'bundle_isolation', 'ui_gates', 'ui_smoke']) {
    assert.equal(result[key], 'false', `freehand fixtures must not arm ${key}`);
  }
});

test('cljs-browser is job-gated on cljs_browser and is REQUIRED (rf2-drpa3.70)', () => {
  // Arming the output only helps if the lane it arms is still surface-gated on
  // that output and still reachable from the single required context. Both
  // halves, pinned together: rf2-drpa3.58 learned the hard way that a lane
  // outside the aggregator is advisory however green it looks.
  const workflow = fs.readFileSync(WORKFLOW, 'utf8');
  const block = jobBlock(workflow, 'cljs-browser');
  assert.match(block, /if: needs\.detect_changed_surfaces\.outputs\.cljs_browser == 'true'/);
  assert.match(block, /run: npm run test:browser/);
  assert.match(
    jobBlock(workflow, 'all-required-passed'),
    /- cljs-browser\r?\n/,
    'aggregator must list cljs-browser in needs: — otherwise the browser lane is advisory',
  );
});

// ---------------------------------------------------------------------------
// rf2-49upn — the conformance INDEX joins its fixtures on the arm.
//
// scripts/check_freehand_conformance_index.py proves the STATIC half of every
// active row: an assertion under implementation/freehand/test/ reaches the
// row's fixture, from a lane serving every (mode, host) cell the row claims.
// The DYNAMIC half — that the assertion PASSES — is the lane exit codes, which
// the census cannot see. freehand-conformance.yml is unfiltered and
// Python-only, so an index-only PR used to certify the claim on a commit where
// jvm-freehand, `cljs` and cljs-browser were all SKIPPED (the shape the #6907
// merged-PR audit flagged). Arming the index binds the two halves to one
// commit under `all-required-passed`.
//
// Both directions are pinned: the index arms the three lanes, its two sibling
// documents under the same root do not.
// ---------------------------------------------------------------------------

test('the freehand conformance INDEX arms the lanes that execute its rows (rf2-49upn)', () => {
  const result = classify('spec/conformance/freehand/conformance-index.md');
  assert.equal(
    result.implementation_jvm,
    'true',
    'the index must arm implementation_jvm — the jvm lane serves every `jvm` and `ssr` cell a row can claim',
  );
  assert.equal(
    result.cljs_node_test,
    'true',
    'the index must arm cljs_node_test — the node lane serves the structural tier of the `browser` column',
  );
  assert.equal(
    result.cljs_browser,
    'true',
    'the index must arm cljs_browser — the Chromium lane serves the mounted tier and every qualified `host:<name>` cell',
  );
});

test('freehand conformance PROSE is not the index — no over-broadening (rf2-drpa3.66, rf2-49upn)', () => {
  // The route is the fixtures root plus the index, NOT
  // spec/conformance/freehand/**. donor-inventory.md is a different ledger: an
  // archive of the withdrawn absorption programme, and check_donor_inventory.py
  // is a snapshot-integrity check reading that one file and nothing else
  // (rf2-lrtwj), so it cannot observe a source change at all. README.md is the
  // document that DEFINES the addressing scheme: it speaks in illustrative ids
  // and is excluded from the census's own citation scan for that reason.
  // Neither can change what a lane proves, so neither pays for three lanes.
  for (const file of [
    'spec/conformance/freehand/README.md',
    'spec/conformance/freehand/donor-inventory.md',
  ]) {
    const result = classify(file);
    assert.equal(result.implementation_jvm, 'false', `${file} must not arm implementation_jvm`);
    assert.equal(result.cljs_node_test, 'false', `${file} must not arm cljs_node_test`);
    assert.equal(result.cljs_browser, 'false', `${file} must not arm cljs_browser`);
  }
});

test('the index arm reaches all three lanes through REQUIRED jobs (rf2-49upn)', () => {
  // Arming an output binds nothing unless the lane it arms is still gated on
  // that output AND still reachable from the single required context. The
  // sibling pins below cover jvm-freehand and cljs-browser; `cljs` is the node
  // lane, and this is the third leg of the same tripod.
  const workflow = fs.readFileSync(WORKFLOW, 'utf8');
  assert.match(
    jobBlock(workflow, 'cljs'),
    /if: needs\.detect_changed_surfaces\.outputs\.cljs_node_test == 'true'/,
    'the `cljs` job must stay gated on cljs_node_test, or arming it schedules nothing',
  );
  const aggregator = jobBlock(workflow, 'all-required-passed');
  for (const job of ['jvm-freehand', 'cljs', 'cljs-browser']) {
    assert.match(
      aggregator,
      new RegExp(`- ${job}\\r?\\n`),
      `aggregator must list ${job} in needs: — otherwise the census's claim rides an advisory lane`,
    );
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

// ---------------------------------------------------------------------------
// rf2-xwa4n — the F4g evidence-elision gate's CI arm.
//
// PR #6880 shipped a real control-build proof (`npm run
// test:freehand-evidence-elision`: `:freehand-release` vs its goog.DEBUG=true
// twin `:freehand-release-control`, the evidence doors ABSENT in one and
// PRESENT in the other) and wired it into NOTHING. `rg
// test:freehand-evidence-elision .github` returned no invocation, so the next
// change to the schema, the dev gate, the mounted commit edge, the release
// entry, the build config or the checker could merge without the proof running.
//
// Same three-part shape as rf2-drpa3.58 above: the classifier must ARM the
// producer surfaces, the job must be gated on that output and run the command,
// and the aggregator must depend on it. Break any one and the proof is
// decorative.
// ---------------------------------------------------------------------------

const FREEHAND_EVIDENCE_PRODUCERS = [
  'implementation/freehand/src/re_frame/freehand/evidence.cljc',
  'implementation/freehand/src/re_frame/freehand/cell.cljc',
  // rf2-xftdv — the dev-only CURRENT-OCCURRENCE index the commit seam writes
  // and `disconnect!` drops from. It is a `defonce` atom on the render path,
  // which is exactly the kind of state that must not ship, and it carries no
  // runtime string literal so it can root no sentinel of its own: its absence
  // follows from cell.cljc's gate. A change here is therefore a change to what
  // the gate is holding back, and worth re-running the proof.
  'implementation/freehand/src/re_frame/freehand/occurrences.cljc',
  // rf2-xwa4n, merged-PR audit of #6888 — the SOLE mounted commit edge
  // (`cell/commit!` in the useLayoutEffect reconcile) is what ROOTS
  // `emit-commit-evidence!` and both positive-control door strings. It was
  // missing from the shipped arm, so a shell change that deleted or redirected
  // that call could strip the control bundle of its sentinels while the
  // required job SKIPPED. The always-armed sole-requirer walk cannot cover it:
  // that law proves require-reachability, and both namespaces stay
  // require-reachable once the CALL is gone.
  'implementation/freehand/src/re_frame/freehand/shell.cljs',
  'implementation/freehand/test/re_frame/freehand/release_app.cljs',
  'implementation/scripts/check-freehand-evidence-elision.cjs',
  'implementation/shadow-cljs.edn',
  'implementation/package.json',
  'implementation/package-lock.json',
];

test('the F4g evidence probe producer surfaces arm freehand_evidence_elision (rf2-xwa4n)', () => {
  for (const file of FREEHAND_EVIDENCE_PRODUCERS) {
    assert.equal(
      classify(file).freehand_evidence_elision,
      'true',
      `${file} can invalidate the F4g control-build proof — it must schedule the gate`,
    );
  }
});

test('the armed producer surfaces all EXIST (rf2-xwa4n)', () => {
  // The classifier never stats a path, so every row above would stay green if a
  // producer were renamed — routing a required lane at nothing. These paths ARE
  // the proof's inputs; pin their existence with the routing.
  for (const file of FREEHAND_EVIDENCE_PRODUCERS) {
    assert.ok(
      fs.existsSync(path.join(REPO_ROOT, file)),
      `${file} must exist — it is a producer surface this routing exists to watch`,
    );
  }
});

// The producer rows that the specific `case` shadows: every producer INSIDE the
// Freehand artefact. The checker and the build-config trio are producers too but
// live elsewhere in the tree, so they are held by their own assertions below —
// that distinction is the only reason this is a filter rather than the whole
// roster. DERIVED, not re-listed: this assertion was written as a hand-copied
// list and went stale twice (it missed `shell.cljs` until the #6888 audit, then
// `occurrences.cljc` until the #6969 audit), each time leaving a shadowed
// producer's host arms unpinned while the roster above looked complete.
const SHADOWED_FREEHAND_PRODUCERS = FREEHAND_EVIDENCE_PRODUCERS.filter((f) =>
  f.startsWith('implementation/freehand/'),
);

test('the Freehand host arms survive the shadowing producer case (rf2-xwa4n)', () => {
  // A POSIX `case` takes the FIRST match, so the producer case shadows
  // `implementation/freehand/*`. It must WIDEN, never narrow: the three host
  // arms rf2-drpa3.58/.70 put on the artefact root have to survive.
  //
  // A filter that stopped matching would make this test pass over an empty
  // list — the vacuity the derivation would otherwise buy at the cost of the
  // hand-written list's one virtue.
  assert.ok(
    SHADOWED_FREEHAND_PRODUCERS.length >= 5,
    'the shadowed-producer filter must still select the Freehand-tree producers',
  );
  for (const file of SHADOWED_FREEHAND_PRODUCERS) {
    const result = classify(file);
    for (const key of ['implementation_jvm', 'cljs_node_test', 'cljs_browser']) {
      assert.equal(
        result[key],
        'true',
        `${file} must keep arming ${key} — the producer case shadows implementation/freehand/*`,
      );
    }
  }
});

test('the checker case keeps the generic implementation/scripts fan-out (rf2-xwa4n)', () => {
  // Same shadowing argument for the other half: the checker's own case sits
  // above `implementation/scripts/*` and must not cost it the generic arms.
  const result = classify('implementation/scripts/check-freehand-evidence-elision.cjs');
  for (const key of [
    'cljs_node_test',
    'cljs_browser',
    'cljs_prod',
    'bundle_isolation',
    'reagent_slim_bundle',
  ]) {
    assert.equal(result[key], 'true', `the checker must keep arming ${key}`);
  }
});

test('freehand_evidence_elision stays OFF unrelated surfaces (rf2-xwa4n)', () => {
  // The ruling was explicitly NOT an every-PR job: two `:advanced` builds are
  // only worth spending on the surfaces that can break the proof. The rest of
  // the Freehand tree is deliberately excluded — the always-armed jvm-freehand
  // walk (asserted below) is what keeps that exclusion honest.
  for (const file of [
    'implementation/freehand/src/re_frame/freehand/compiler/analyze.cljc',
    'implementation/freehand/README.md',
    'implementation/freehand/deps.edn',
    'spec/conformance/freehand/fixtures/fh-call-001.edn',
    'implementation/core/src/re_frame/core.cljc',
    'implementation/scripts/check-elision.cjs',
    'spec/API.md',
  ]) {
    assert.equal(
      classify(file).freehand_evidence_elision,
      'false',
      `${file} must not pay for two :advanced builds`,
    );
  }
});

test('the SOLE-requirer law that keeps the narrow arm honest still exists (rf2-xwa4n)', () => {
  // The classifier arms four Freehand files, not the tree. That is only safe
  // while a NEW schema-touching producer cannot appear silently — and what
  // forbids one is
  // `the-evidence-schema-reaches-the-render-path-only-through-the-dev-gated-seam`
  // in the always-armed jvm-freehand lane. Pin the premise with the routing: if
  // that law is ever relaxed, this reds and the classifier arm must widen.
  //
  // The law is now TWO rows, and the narrow arm leans on BOTH (rf2-lvvl2). The
  // tool-tier read door `re-frame.freehand.tool` mentions the schema without
  // being a producer, because nothing reachable from the public door requires
  // it — an inspector loads a tool tier deliberately, into a dev build. So:
  //
  //   1. `cell` is the sole DOOR-REACHABLE mentioner (the render-path claim);
  //   2. `tool` is asserted NOT door-reachable (what makes (1) safe — a
  //      `tool.cljc` edit cannot put the schema in the release bundle, because
  //      the release bundle does not contain `tool`).
  //
  // Row (2) is the load-bearing one for this classifier: reachability is
  // decided by whoever REQUIRES the tool tier, not by the tool tier itself, so
  // without it a door-side require could ship the schema while the elision gate
  // sat unarmed. Both rows are pinned here.
  const law = fs.readFileSync(
    path.join(
      REPO_ROOT,
      'implementation/freehand/test/re_frame/freehand/evidence_boundary_jvm_test.clj',
    ),
    'utf8',
  );
  assert.match(
    law,
    /'#\{re-frame\.freehand\.cell\}\s+seams/,
    'the boundary test must still pin cell as the sole DOOR-REACHABLE namespace reaching the '
      + 'evidence schema — without it, a new producer file could dodge the narrowly-armed '
      + 'elision gate',
  );
  assert.match(
    law,
    /not\s+\(contains\?\s+reachable\s+'re-frame\.freehand\.tool\)/,
    'the boundary test must still pin the tool-tier read door OFF the render path — that is what '
      + 'makes the door-reachable form of the sole-producer law safe for the narrow arm. If the '
      + 'tool tier becomes door-reachable, the schema has a second path into the release bundle '
      + 'and freehand_evidence_elision must widen to arm on the door and the tool tier too',
  );
});

test('cljs-freehand-evidence-elision is gated on its output and runs the probe (rf2-xwa4n)', () => {
  const workflow = fs.readFileSync(WORKFLOW, 'utf8');
  const block = jobBlock(workflow, 'cljs-freehand-evidence-elision');
  assert.match(block, /needs: detect_changed_surfaces/);
  assert.match(
    block,
    /if: needs\.detect_changed_surfaces\.outputs\.freehand_evidence_elision == 'true'/,
  );
  assert.match(block, /run: npm run test:freehand-evidence-elision/);
  assert.match(block, /working-directory: implementation/);
  // The detect job must publish the output the `if:` reads, or the gate is
  // permanently false and the job never runs at all.
  assert.match(
    jobBlock(workflow, 'detect_changed_surfaces'),
    /freehand_evidence_elision: \$\{\{ steps\.detect\.outputs\.freehand_evidence_elision \}\}/,
    'detect_changed_surfaces must expose freehand_evidence_elision as a job output',
  );
});

test('all-required-passed aggregator needs cljs-freehand-evidence-elision (rf2-xwa4n)', () => {
  const block = jobBlock(fs.readFileSync(WORKFLOW, 'utf8'), 'all-required-passed');
  assert.match(
    block,
    /- cljs-freehand-evidence-elision\r?\n/,
    'aggregator must list cljs-freehand-evidence-elision in needs: — otherwise the lane is advisory',
  );
});

// ---------------------------------------------------------------------------
// rf2-zl8ao — the B5 unused-module reachability gate's CI arm.
//
// PR #6901 shipped the second control-build proof (`npm run
// test:freehand-reachability`: `:freehand-release` vs its strict-superset twin
// `:freehand-release-reachability-control`, the `re-frame.freehand.control`
// doors ABSENT in production and PRESENT in the control) and wired it into
// NOTHING — the same omission rf2-xwa4n fixed for the sibling one file up.
// The positive-control half exists to red when the probe stops probing; a gate
// nobody runs cannot red.
//
// It is NOT the sibling gate under another name. The evidence pair moves
// `goog.DEBUG` and holds the app still (a dev-gated SEAM elides); this pair
// holds the flag still and moves the APP (an unused MODULE elides). The
// controller strings are absent from the goog.DEBUG=true control too, so that
// build cannot prove this claim — and the tests below pin both arms so a later
// tidy-up cannot collapse them into one.
//
// Same three-part shape as the two blocks above: the classifier must ARM the
// producer surfaces, the job must be gated on that output and run the command,
// and the aggregator must depend on it.
// ---------------------------------------------------------------------------

const FREEHAND_REACHABILITY_PRODUCERS = [
  // The two refusal doors the probe greps for.
  'implementation/freehand/src/re_frame/freehand/control.cljc',
  // The facade: the sole requirer of `control`, and the single production
  // call edge (`controller-key` is `def`'d to `control/record-key`).
  'implementation/freehand/src/re_frame/freehand.cljc',
  // The CONTROL entry — the one `v/controller-key` call that validates the
  // oracle.
  'implementation/freehand/test/re_frame/freehand/bench/b5_reachability_control_app.cljs',
  // The PRODUCTION entry both bundles compile.
  'implementation/freehand/test/re_frame/freehand/release_app.cljs',
  'implementation/scripts/check-freehand-reachability.cjs',
  'implementation/shadow-cljs.edn',
  'implementation/package.json',
  'implementation/package-lock.json',
];

test('the B5 reachability producer surfaces arm freehand_reachability (rf2-zl8ao)', () => {
  for (const file of FREEHAND_REACHABILITY_PRODUCERS) {
    assert.equal(
      classify(file).freehand_reachability,
      'true',
      `${file} can invalidate the B5 control-build proof — it must schedule the gate`,
    );
  }
});

test('the armed reachability producers all EXIST (rf2-zl8ao)', () => {
  // The classifier never stats a path, so every row above would stay green if
  // a producer were renamed — routing a required lane at nothing.
  for (const file of FREEHAND_REACHABILITY_PRODUCERS) {
    assert.ok(
      fs.existsSync(path.join(REPO_ROOT, file)),
      `${file} must exist — it is a producer surface this routing exists to watch`,
    );
  }
});

test('the reachability producer cases keep their generic fan-out (rf2-zl8ao)', () => {
  // A POSIX `case` takes the FIRST match, so the Freehand producer case
  // shadows `implementation/freehand/*` and the checker case shadows
  // `implementation/scripts/*`. Both must WIDEN, never narrow.
  for (const file of [
    'implementation/freehand/src/re_frame/freehand/control.cljc',
    'implementation/freehand/src/re_frame/freehand.cljc',
    'implementation/freehand/test/re_frame/freehand/bench/b5_reachability_control_app.cljs',
  ]) {
    const result = classify(file);
    for (const key of ['implementation_jvm', 'cljs_node_test', 'cljs_browser']) {
      assert.equal(
        result[key],
        'true',
        `${file} must keep arming ${key} — the producer case shadows implementation/freehand/*`,
      );
    }
  }
  const checker = classify('implementation/scripts/check-freehand-reachability.cjs');
  for (const key of [
    'cljs_node_test',
    'cljs_browser',
    'cljs_prod',
    'bundle_isolation',
    'reagent_slim_bundle',
  ]) {
    assert.equal(checker[key], 'true', `the checker must keep arming ${key}`);
  }
});

test('the SHARED release entry arms BOTH Freehand control-build gates (rf2-zl8ao)', () => {
  // `:freehand-release` is the production half of both control pairs, and a
  // POSIX `case` runs ONE arm: the release entry is matched by the evidence
  // producer case, so the reachability output has to be set from inside it.
  // Drop that and a change to the shipped app — a controlled input added to a
  // release view is enough to root the controller — skips the reachability
  // gate entirely.
  const result = classify('implementation/freehand/test/re_frame/freehand/release_app.cljs');
  assert.equal(result.freehand_evidence_elision, 'true');
  assert.equal(result.freehand_reachability, 'true');
});

test('freehand_reachability stays OFF unrelated surfaces (rf2-zl8ao)', () => {
  // Two `:advanced` builds are only worth spending on the surfaces that can
  // break the proof; the rest of the Freehand tree is deliberately excluded
  // and the unconditional nightly run is what covers it.
  for (const file of [
    'implementation/freehand/src/re_frame/freehand/compiler/analyze.cljc',
    'implementation/freehand/README.md',
    'implementation/freehand/deps.edn',
    'spec/conformance/freehand/fixtures/fh-call-001.edn',
    'implementation/core/src/re_frame/core.cljc',
    'implementation/scripts/check-elision.cjs',
    'spec/API.md',
  ]) {
    assert.equal(
      classify(file).freehand_reachability,
      'false',
      `${file} must not pay for two :advanced builds`,
    );
  }
});

test('the two Freehand control-build gates stay SEPARATE arms (rf2-zl8ao)', () => {
  // They prove different things, so neither output may become an alias of the
  // other: the evidence doors are not the controller doors, and a merge of the
  // two arms would silently drop one claim's producer coverage.
  const evidenceOnly = classify(
    'implementation/freehand/src/re_frame/freehand/evidence.cljc',
  );
  assert.equal(evidenceOnly.freehand_evidence_elision, 'true');
  assert.equal(
    evidenceOnly.freehand_reachability,
    'false',
    'the evidence schema cannot invalidate the reachability claim — do not alias the outputs',
  );
  const reachabilityOnly = classify(
    'implementation/freehand/src/re_frame/freehand/control.cljc',
  );
  assert.equal(reachabilityOnly.freehand_reachability, 'true');
  assert.equal(
    reachabilityOnly.freehand_evidence_elision,
    'false',
    'the controller doors cannot invalidate the evidence claim — do not alias the outputs',
  );
});

test('the reachability CONTROL build is a controlled comparison (rf2-zl8ao)', () => {
  // The whole proof is "same everything, different entry". If the control ever
  // stops sharing `:advanced` + `goog.DEBUG false` with `:freehand-release`,
  // an absence result stops being attributable to reachability — so pin the
  // pair's declaration, which is the thing the build-config arm above watches.
  const shadow = fs.readFileSync(
    path.join(REPO_ROOT, 'implementation/shadow-cljs.edn'),
    'utf8',
  );
  const control = shadow.slice(shadow.indexOf(':freehand-release-reachability-control'));
  assert.match(control, /:optimizations :advanced/);
  assert.match(control, /:closure-defines \{goog\.DEBUG false\}/);
  assert.match(
    control,
    /:init-fn re-frame\.freehand\.bench\.b5-reachability-control-app\/-main/,
    'the control build must keep the SUPERSET entry — the production entry would prove nothing',
  );
});

test('the SOLE-requirer law that keeps the reachability arm honest still exists (rf2-qimh0)', () => {
  // The classifier arms the controller, the facade, the two build entries, the
  // checker and the build-config trio — not the Freehand tree. That is only
  // safe while a NEW production requirer of `re-frame.freehand.control` cannot
  // appear silently, and what forbids one is
  // `re-frame-freehand-is-the-sole-requirer-of-the-controller` in the
  // always-armed jvm-freehand lane. Pin the premise with the routing: if that
  // law is ever relaxed, this reds and the classifier arm must widen. Exactly
  // the shape of the sibling pin one gate up (rf2-xwa4n).
  const law = fs.readFileSync(
    path.join(
      REPO_ROOT,
      'implementation/freehand/test/re_frame/freehand/control_boundary_jvm_test.clj',
    ),
    'utf8',
  );
  assert.match(
    law,
    /'#\{re-frame\.freehand\}\s+requirers/,
    'the boundary test must still pin re-frame.freehand as the SOLE requirer of the controller — '
      + 'without it, a new production requirer could root the module in the release bundle and '
      + 'dodge the narrowly-armed reachability gate',
  );
});

test('cljs-freehand-reachability is gated on its output and runs the probe (rf2-zl8ao)', () => {
  const workflow = fs.readFileSync(WORKFLOW, 'utf8');
  const block = jobBlock(workflow, 'cljs-freehand-reachability');
  assert.match(block, /needs: detect_changed_surfaces/);
  assert.match(
    block,
    /if: needs\.detect_changed_surfaces\.outputs\.freehand_reachability == 'true'/,
  );
  assert.match(block, /run: npm run test:freehand-reachability/);
  assert.match(block, /working-directory: implementation/);
  // The detect job must publish the output the `if:` reads, or the gate is
  // permanently false and the job never runs at all.
  assert.match(
    jobBlock(workflow, 'detect_changed_surfaces'),
    /freehand_reachability: \$\{\{ steps\.detect\.outputs\.freehand_reachability \}\}/,
    'detect_changed_surfaces must expose freehand_reachability as a job output',
  );
});

test('all-required-passed aggregator needs cljs-freehand-reachability (rf2-zl8ao)', () => {
  const block = jobBlock(fs.readFileSync(WORKFLOW, 'utf8'), 'all-required-passed');
  assert.match(
    block,
    /- cljs-freehand-reachability\r?\n/,
    'aggregator must list cljs-freehand-reachability in needs: — otherwise the lane is advisory',
  );
});

// ---------------------------------------------------------------------------
// rf2-3mh2f — the .beads PR-boundary guard's CI arm.
//
// The classifier, the pre-commit hook and scripts/check-beads-pr-boundary.sh
// all shipped together, but `.github/workflows/**` was fenced, so enforcement
// was LOCAL-HOOK-ONLY: bypassable with `--no-verify`, inert wherever hooks
// were never installed. These tests hold the wiring in place.
//
// The guard's own behaviour — classification, remedy text, branch-point
// selection on diverged history, the mayor no-op — lives in
// scripts/git-hooks/test-pre-commit.sh, which this job self-tests before it
// enforces. Not duplicated here.
// ---------------------------------------------------------------------------

test('beads-pr-boundary self-tests the guard, then enforces it (rf2-3mh2f)', () => {
  const block = jobBlock(fs.readFileSync(WORKFLOW, 'utf8'), 'beads-pr-boundary');
  assert.match(
    block,
    /sh scripts\/git-hooks\/test-pre-commit\.sh/,
    'the guard must be self-tested in CI — its harness runs nowhere else',
  );
  assert.match(block, /sh scripts\/check-beads-pr-boundary\.sh/);
  // rf2-5z20y — the guard diffs from the branch point; a shallow clone
  // frequently lacks the fork commit, and the guard then fails closed.
  assert.match(block, /fetch-depth: 0/);
});

test('beads-pr-boundary is UNCONDITIONAL — no surface gate, no path filter (rf2-3mh2f)', () => {
  const block = jobBlock(fs.readFileSync(WORKFLOW, 'utf8'), 'beads-pr-boundary');
  assert.doesNotMatch(
    block,
    /^\s+needs:/m,
    'the tracker database has no business in ANY PR — this job must not be surface-gated',
  );
  assert.doesNotMatch(
    block,
    /^\s{4}if:/m,
    'a job-level `if:` would let a PR class opt out of the guard',
  );
});

test('beads-pr-boundary passes the BASE BRANCH, not a precomputed base (rf2-3mh2f)', () => {
  // rf2-5z20y put branch-point resolution inside the script so the local
  // pre-flight gets the same correction. This asserts the caller does not
  // undo that by precomputing a base here — and never reaches for
  // `base.sha`, which goes stale as soon as main advances under an open PR.
  // The mayor checkpoints the tracker to main constantly, so either mistake
  // reds branches for a file they never edited.
  const block = jobBlock(fs.readFileSync(WORKFLOW, 'utf8'), 'beads-pr-boundary');
  assert.match(
    block,
    /check-beads-pr-boundary\.sh "origin\/\$\{GITHUB_BASE_REF\}"/,
  );
  // The comment in the job explains why base.sha is wrong, so assert on the
  // EXPRESSION form — using it, not naming it, is what would break.
  assert.doesNotMatch(block, /\$\{\{[^}]*base\.sha/);
  assert.doesNotMatch(
    block,
    /^\s*base="\$\(git merge-base/m,
    'the script owns branch-point resolution (rf2-5z20y) — one home for the rule',
  );
});

test('beads-pr-boundary leaves the MAYOR checkpoint flow alone (rf2-3mh2f)', () => {
  const workflow = fs.readFileSync(WORKFLOW, 'utf8');

  // Belt: a beads-only push to main runs no job in this workflow at all.
  const onBlock = workflow.slice(0, workflow.indexOf('\njobs:'));
  assert.match(
    onBlock,
    /paths-ignore:[\s\S]*?- '\.beads\/\*\*'/,
    "test.yml's push trigger must keep ignoring .beads/** — that IS the mayor checkpoint",
  );

  // Braces: on any push that DOES reach the job, enforcement is skipped by
  // the script's event branch, not by anything this workflow decides.
  const block = jobBlock(workflow, 'beads-pr-boundary');
  assert.match(block, /GITHUB_EVENT_NAME.*!=.*"pull_request"/);
});

test('all-required-passed aggregator needs beads-pr-boundary (rf2-3mh2f)', () => {
  const block = jobBlock(fs.readFileSync(WORKFLOW, 'utf8'), 'all-required-passed');
  assert.match(
    block,
    /- beads-pr-boundary\r?\n/,
    'aggregator must list beads-pr-boundary in needs: — otherwise the guard is advisory',
  );
});

test('check-beads-pr-boundary.sh is committed executable (rf2-3mh2f)', () => {
  // Same failure mode as install-clojure-cli.sh above: Windows git hides the
  // mode, the ubuntu runner invokes it and exits 126. It is invoked via
  // `sh <path>` today, but the mode is the contract — keep it asserted.
  const mode = execFileSync(
    'git',
    ['ls-files', '-s', '--', 'scripts/check-beads-pr-boundary.sh'],
    { cwd: REPO_ROOT, encoding: 'utf8' },
  ).trim();
  assert.match(
    mode,
    /^100755\s/,
    `check-beads-pr-boundary.sh must be committed 100755, got: ${mode}`,
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
