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

// Relative POSIX path, resolved against REPO_ROOT as the child's cwd — the same
// form every other suite in this directory uses, and the one that works
// unchanged on Windows, macOS and Linux.
const SURFACES_SCRIPT = './.github/scripts/report-changed-surfaces.sh';

// rf2-fal5 — memo table for classify(). The classifier reads NOTHING but its
// argument list when paths are passed explicitly (the `git diff` discovery
// branch is unreachable in that mode), so for a fixed checkout it is a pure
// function and the same path list can only ever produce the same verdicts.
// Measured on this suite: 447 invocations over 235 distinct argument lists.
const classifyCache = new Map();

/**
 * Classify one path list and return the classifier's key -> "true"/"false" map.
 *
 * rf2-fal5 — TWO spawn economies, and the suite's reliability rests on both.
 *
 * NOT A LOGIN SHELL. This used to build a quoted command string and hand it to
 * `bash -lc`. A login shell sources /etc/profile and every /etc/profile.d/*.sh
 * before it reaches the command, each of which forks children of its own — so
 * one classification cost a whole profile evaluation, and this suite ran
 * hundreds. Measured on the Windows host: 713ms per `bash -lc` against 75ms for
 * spawning the script as bash's own argv, a 9.5x difference in wall clock and a
 * far larger one in PROCESS CREATIONS, which is what actually ran out. Twice,
 * on different diffs and different worktrees, the msys2 fork emulation gave way
 * under concurrent load and printed the mechanism outright —
 * `dofork: child -1 ... exit code 0xC0000142` (STATUS_DLL_INIT_FAILED) then
 * `fork: retry: Resource temporarily unavailable` — surfacing as an exit-127
 * child that never started, on a DIFFERENT case each run, which is how it was
 * told apart from an assertion. Be precise about what is established: a
 * deliberate load test (18k login shells, three concurrent copies of this
 * suite) did NOT recreate that crash, so what this change rests on is the
 * measured cost above, not a reproduction. `_playground-sci-inputs.test.cjs`
 * recorded the same finding one file over (56s -> 3s for 144 classifications)
 * and reached for the same remedy.
 *
 * Passing the script as argv also retires the hand-rolled shell quoting the
 * command string needed: there is no shell to quote for. That is the
 * shell-free posture `_script-spawn-policy.test.cjs` already requires of every
 * launcher in this directory, applied to the suites beside them.
 *
 * MEMOIZED. Distinct cases legitimately re-classify the same exemplar —
 * `spec/006-ReactiveSubstrate.md` and `implementation/core/src/re_frame/
 * core.cljc` are the controls for a dozen arms each — and re-running the
 * classifier for an answer already in hand buys nothing. The cached verdict is
 * frozen so a caller that mutates it fails loudly here rather than poisoning a
 * later case.
 *
 * NEITHER economy drops a case, weakens an assertion or merges two path lists:
 * every call still gets the classifier's real verdict for its own arguments.
 */
function classify(...files) {
  const key = JSON.stringify(files);
  if (classifyCache.has(key)) return classifyCache.get(key);
  const env = { ...process.env };
  delete env.GITHUB_OUTPUT;
  const out = execFileSync('bash', [SURFACES_SCRIPT, ...files], {
    cwd: REPO_ROOT,
    env,
    encoding: 'utf8',
  });
  const result = Object.freeze(
    Object.fromEntries(
      out
        .trim()
        .split(/\r?\n/)
        .filter(Boolean)
        .map((line) => line.split('=')),
    ),
  );
  classifyCache.set(key, result);
  return result;
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

// rf2-kttom — the `.html` hole. The browser runners COPY each testbed's
// hand-written index.html into the served output dir and navigate to it
// (`stageTestbedHtml` in serve-and-run-story-play-scripts.cjs and its
// feature-load sibling), so the served document is a testbed SOURCE file:
// break its `<script src>` or its `#app` node and every play in that deck
// fails. It was not in the runtime-extension predicate, so an HTML-only
// regression classified as no-surface and skipped the browser gate.
test('Story testbed .html changes trigger story_xray_browser (the runner serves it — rf2-kttom)', () => {
  const exemplar = 'tools/story/testbeds/hicasso_counter/index.html';
  assert.ok(
    fs.existsSync(path.join(REPO_ROOT, exemplar)),
    `${exemplar} must exist — this row's whole claim is "a real served testbed document"`,
  );
  assert.equal(classify(exemplar).story_xray_browser, 'true');
});

test('Xray testbed .html changes trigger story_xray_browser (rf2-kttom)', () => {
  const result = classify('tools/xray/testbeds/feature_matrix/index.html');
  assert.equal(result.story_xray_browser, 'true');
});

// The predicate widened by ONE extension and only under the two testbed /
// src trees. An .html elsewhere under tools/story — the spec tree, say —
// is still inert, which is what keeps the widening a fix rather than a
// blanket arm.
test('Story spec-tree .html changes do NOT trigger story_xray_browser (rf2-kttom)', () => {
  const result = classify('tools/story/spec/diagrams/overview.html');
  assert.equal(result.story_xray_browser, 'false');
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

// rf2-f79t8 (b) — a tools/story/spec/**.md MARKDOWN change is a pure doc
// change: it cannot affect any runtime, JVM unit test, or MCP wire surface,
// so it must NOT fan out to the JVM/MCP probes (tools_jvm, mcp_conformance).
// docs.yml + the nightly full matrix cover docs.
//
// THE XRAY HALF OF THIS PIN WAS DELETED (rf2-6ng7), because its premise was
// false. Two suites under `tools/xray/test/` read the Xray spec markdown as
// their expected value — `coverage_matrix_metadata_test.clj` (jvm-tools-xray)
// and `panel_enum_spec_refs.clj`, whose macro compiles into the :node-test
// build (cljs). The assertion below used to name
// `017-Test-Coverage-Matrix.md` specifically, which is one of the two files
// the first of those suites slurps: the pin held the classifier to exactly
// the hole. What replaces it is the derived roster at the foot of this file,
// which reads both suites' own path lists rather than restating them, plus
// the surviving Story control here.

test('Story spec-only .md does NOT fan out to tools_jvm / mcp_conformance (rf2-f79t8)', () => {
  const result = classify('tools/story/spec/Spec.md');
  assert.equal(result.tools_jvm, 'false');
  assert.equal(result.mcp_conformance, 'false');
  assert.equal(result.cljs_node_test, 'false');
});

test('Xray spec-only .md still does NOT fan out to mcp_conformance / template_expensive (rf2-f79t8, rf2-6ng7)', () => {
  // The narrowing rf2-6ng7 made is on the PATH axis, not a repeal: markdown
  // still cannot change an MCP wire surface or the generated app's compile,
  // and no suite in either lane reads these files.
  const result = classify('tools/xray/spec/017-Test-Coverage-Matrix.md');
  assert.equal(result.mcp_conformance, 'false');
  assert.equal(result.template_expensive, 'false');
  assert.equal(result.story_xray_browser, 'false');
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

// rf2-61ar SUPERSEDES the spec half of the case below, and the control it used
// is the reason why. `spec/006-ReactiveSubstrate.md` was picked here (and in a
// dozen other cases) as an inert stand-in for "some spec document" — but
// implementation/ui/test/re_frame/ui/slice_memo_lifetime_census_jvm_test.clj
// SLURPS that exact file and asserts on its text, inside jvm-ui, which gates on
// implementation_jvm. So the negative control was itself a false-green witness:
// the classifier's own mirror asserted that editing a pinned document must skip
// the lane pinning it. The claim that survives is the one about the CLJS tier —
// Markdown compiles into nothing, so `cljs_node_test` stays false for spec
// prose, with `spec/Spec-Schemas.md` the single measured exception (a JVM macro
// extracts it into the `:node-test` build; it has its own case below).
test('Spec-only .md change fires implementation_jvm but NOT cljs (rf2-f79t8, rf2-61ar)', () => {
  const result = classify('spec/006-ReactiveSubstrate.md');
  assert.equal(
    result.implementation_jvm,
    'true',
    'spec prose arms the JVM tier — jvm-ui slurps this very file (rf2-61ar)',
  );
  assert.equal(
    result.cljs_node_test,
    'false',
    'spec prose compiles into nothing, so the consolidated node build stays out',
  );
});

test('Docs prose with NO pinning suite still skips jvm-core + cljs (rf2-f79t8, rf2-61ar)', () => {
  // rf2-61ar armed the docs trees a test.yml suite reads (docs/machines, two
  // docs/api pages, one docs/design page, and since rf2-8arzr.6
  // docs/ssr/concepts.md) and left every other docs page
  // classifying exactly as it did before. That asymmetry IS the bead's
  // narrowing, so this case keeps its original control — a docs/core page
  // reaches none of the armed trees. Since rf2-7v5vx that holds for the WHOLE
  // of docs/core rather than for the part outside docs/core/freehand: the one
  // arm that lit any of it went with the guide it pinned.
  const result = classify('docs/core/intro.md');
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

// rf2-cujx — these three roots read `implementation_jvm` FALSE, and that is a
// DELIBERATE, covered false rather than the hole it used to be. Four suites in
// `implementation/core/test/` are repo-wide source WALKS that read these trees
// (`no-rf-default-floor-lint-test` walks `tools/**/src` by name; the egress and
// error-catalogue conformance pins walk every `src` root under
// `implementation/` recursively since rf2-2cu7f; `warn-once-clear-governance`
// file-seqs `implementation/`). All four ran only in `jvm-core`, which this
// output gates — so a violation introduced here merged GREEN and the red landed
// on main for the next unrelated implementation PR (the rf2-61ar shape).
//
// THE REPAIR IS NOT AN ARM, so these assertions pin false on purpose. Arming
// the roots costs ~10 more PRs through a 22-job / ~21-minute tier and STILL
// leaves the class partly open, because the walks keep widening — rf2-2cu7f
// made the corpus depth-independent by construction, so a future artefact is
// walked with nobody maintaining a list, while an arming predicate would have
// to be re-widened by hand every time. Instead `test.yml`'s UNCONDITIONAL
// `jvm-repo-source-walks` job runs those namespaces on every PR — seven of
// them now, the roster being `REPO_SOURCE_WALK_NAMESPACES` below rather than
// this paragraph — one step each so that a namespace which stops selecting
// reds on the runner's own test-count floor rather than hiding inside a
// combined total.
//
// So do NOT "fix" these to true: that re-incurs the tax and re-opens the
// maintenance hole. If the unconditional job is ever deleted, THIS is the
// coverage that goes with it.
test('tools/ src reads implementation_jvm false — covered by the unconditional walk lane (rf2-cujx)', () => {
  for (const p of [
    'tools/xray/src/day8/re_frame2_xray/core.cljs',
    'tools/story/src/foo.cljs',
    'tools/mcp-base/src/foo.clj',
  ]) {
    assert.equal(classify(p).implementation_jvm, 'false', p);
  }
});

test('hicasso + ssr-node src read implementation_jvm false — same lane covers them (rf2-cujx)', () => {
  for (const p of [
    'implementation/hicasso/src/foo.cljs',
    'implementation/ssr-node/src/foo.cljs',
  ]) {
    assert.equal(classify(p).implementation_jvm, 'false', p);
  }
});

// The control the census turns on: if core/src ever reads false the classifier
// is broken in a different way and the reasoning above does not apply.
test('implementation/core/src still arms implementation_jvm (rf2-cujx control)', () => {
  assert.equal(classify('implementation/core/src/foo.clj').implementation_jvm, 'true');
});

// rf2-n4a2b — THE FIFTH WALK, and the assertion the other four never had.
//
// `prod-gate-naming-drift-test` became this shape the same day rf2-cujx landed:
// rf2-sk5hf widened it from a depth-1 `.listFiles` over one artefact's test
// tree to a recursive walk from the implementation root, and it too ran only in
// `jvm-core`. Its census needs DIFFERENT probes from the four above, because
// its domain is every artefact's `test/` tree and not `src/` — so the src rows
// above say nothing about it either way.
//
// AND THE COVERAGE CLAIM ITSELF IS NOW PINNED. The census above excuses a false
// arm by naming the unconditional lane, which is only true while the lane
// actually runs the namespace. Nothing checked that: delete a step and every
// assertion up there goes on passing, asserting a coverage that has gone. This
// is the rf2-6ng7 codicil applied to a repair whose "arming" is a workflow step
// rather than a classifier output.
//
// rf2-6ng7 — THE SIXTH AND SEVENTH, found by this bead's bounded audit rather
// than by tripping over them. Neither namespace was selected by any workflow or
// script (measured: `git grep` over `.github/**` and `scripts/**` returns the
// source file and nothing else), so both ran only inside `jvm-core`.
//
// `late-bind-drift-test` walks `implementation/**/src` — the SAME corpus as the
// four src-walkers above, so the hicasso / ssr-node rows already asserted here
// are its hole verbatim. `observation-render-law-drift-test` is wider than any
// of them and needs its own probe: its census is `git ls-files`, so its domain
// is the whole tracked prose corpus, and rf2-61ar armed `implementation_jvm`
// for only the pinned SLICE of that prose. `docs/design/hicasso/**` is outside
// the slice, which the row below measures.
const REPO_SOURCE_WALK_NAMESPACES = Object.freeze([
  're-frame.no-rf-default-floor-lint-test',
  're-frame.egress-chokepoint-conformance-test',
  're-frame.error-catalogue-channel-conformance-test',
  're-frame.warn-once-clear-governance-test',
  're-frame.prod-gate-naming-drift-test',
  're-frame.late-bind-drift-test',
  're-frame.observation-render-law-drift-test',
]);

test('hicasso + ssr-node TEST trees read implementation_jvm false — the naming-drift walk reads them anyway (rf2-n4a2b)', () => {
  for (const p of [
    'implementation/hicasso/test/foo_prod_gate_test.clj',
    'implementation/ssr-node/test/foo_prod_gate_test.clj',
  ]) {
    assert.equal(classify(p).implementation_jvm, 'false', p);
  }
  // The control that makes the two rows above mean something: an artefact test
  // tree that DOES arm. Without it a classifier returning false for everything
  // would read as this census passing.
  assert.equal(
    classify('implementation/epoch/test/re_frame/foo_prod_gate_test.clj').implementation_jvm,
    'true',
  );
});

test('unpinned PROSE reads implementation_jvm false — the render-law census reads it anyway (rf2-6ng7)', () => {
  // The seventh walk's domain is `git ls-files`, not a directory: every tracked
  // `.md` / `.clj` / `.cljc` / `.cljs` in the repo. rf2-61ar armed
  // `implementation_jvm` for the prose a test.yml suite PINS — nearly all of
  // `spec/*`, `docs/machines/*`, four named pages — and deliberately left the
  // rest of `docs/` arming nothing. So the census outruns the arm, and a
  // retired render-law claim landing on an unpinned page merged green.
  for (const p of [
    'docs/design/hicasso/product/foo.md',
    'docs/core/hicasso/foo.md',
  ]) {
    assert.equal(classify(p).implementation_jvm, 'false', p);
  }
  // The control: prose that rf2-61ar DOES arm. Without it a classifier
  // returning false for every `.md` would read as this census passing.
  assert.equal(classify('docs/machines/concepts.md').implementation_jvm, 'true');
});

test('the unconditional walk lane runs every namespace whose false arm it excuses (rf2-n4a2b)', () => {
  const workflow = fs.readFileSync(WORKFLOW, 'utf8');
  const block = jobBlock(workflow, 'jvm-repo-source-walks');

  assert.doesNotMatch(
    block,
    /^\s+if:/m,
    'jvm-repo-source-walks must stay UNCONDITIONAL — a condition here is a ' +
      'read-set model of what the walks walk, which is the defect the lane exists ' +
      'to avoid rather than relocate',
  );
  assert.match(
    workflow,
    /^\s+- jvm-repo-source-walks$/m,
    'jvm-repo-source-walks must stay in all-required-passed needs: — a job absent ' +
      'from that list is advisory whatever its own gate says',
  );

  for (const ns of REPO_SOURCE_WALK_NAMESPACES) {
    assert.match(
      block,
      new RegExp(`clojure -M:test -n ${ns.replace(/[.]/g, '[.]')}\\s*$`, 'm'),
      `${ns} is a repo-wide source walk parked in implementation/core/test/, and ` +
        'this lane is the only scheduling it has that does not depend on a read-set ' +
        'model. It must be a step here.',
    );
  }

  // ONE STEP PER NAMESPACE, which is not decoration: a namespace missing from a
  // multi-`-n` selector is SILENT (exit 0, runs the rest, no warning), so a
  // combined step would rest entirely on a total test-count floor across suites
  // of 1 / 3 / 27 / 1 / 2 tests. Per-namespace steps each take the runner's own
  // default floor of 1 and red alone.
  const runs = block.match(/^\s+run: clojure -M:test[^\n]*/gm) || [];
  assert.equal(
    runs.length,
    REPO_SOURCE_WALK_NAMESPACES.length,
    `expected one clojure step per walked namespace, found ${runs.length}`,
  );
  for (const run of runs) {
    assert.equal(
      (run.match(/ -n /g) || []).length,
      1,
      `"${run.trim()}" selects more than one namespace in a single step`,
    );
  }
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
// The live namespace named below is load-bearing, not illustrative: it is one
// of the two files the bead was filed about, and if it is renamed out of the
// `-dom-cljs-test` convention this pins that the arm moved with it.
//
// The bead's OTHER file was `play/presence_freehand_dom_cljs_test.cljs`, the
// presence-flush proof. It retired with Freehand and Story's presence bridge
// (rf2-5gka, rf2-0yp7w). Its assertion is not re-pointed at an arbitrary
// substitute: the classifier arm is a property of the PATH CONVENTION, which
// the surviving case exercises identically, so a second case naming a
// different tree would add coverage of nothing.

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
  // tools_jvm / mcp_conformance fan-out all still fire.
  const result = classify(STORY_MACROS);
  assert.equal(result.examples_compile, 'true');
  assert.equal(result.tools_jvm, 'true');
  assert.equal(result.mcp_conformance, 'true');
  // template_expensive is NOT in that roster (rf2-6r9j.108): the reduced
  // scaffold compiles against no Story surface, macros.clj included.
  assert.equal(result.template_expensive, 'false');
});

// rf2-uqf5q — the THIRD predicate. rf2-eyyd2 armed macros.clj on the two CLJS
// unit lanes above and left `is_story_xray_runtime_path` — the one that owns
// story_xray_browser, i.e. the Playwright decks — still reading its `.clj`
// extension guard. So a Story MACRO change fired NO browser gate at all:
// story_xray_browser false skipped the PR-smoke tier, and story_full_gate arms
// only on the feature-load gate's own spec modules, so the full tier skipped
// with it. Commit e1cbd089c4 (rf2-3xq1v) went through that hole — it changed
// the source-coord the Story pane renders, by way of this very macro
// namespace, and no browser saw it until two unrelated PRs armed everything.

test('Story macros.clj fires the Playwright browser gate (rf2-uqf5q)', () => {
  // The macro namespace is the compile-time producer for every
  // `(story/reg-story …)` / `(story/reg-variant …)` call site the testbed
  // decks contain, so its emitted forms ARE what the deck renders.
  const result = classify(STORY_MACROS);
  assert.equal(
    result.story_xray_browser,
    'true',
    'a Story macro change must schedule the Story/Xray browser gate: the decks ' +
      'render what this namespace emits',
  );
});

test('Story macros.clj now arms every lane its expansion reaches (rf2-uqf5q)', () => {
  // The three predicates together, pinned in one place so a future narrowing
  // of any one of them is visible as a narrowing rather than as a lane that
  // quietly stopped running.
  const result = classify(STORY_MACROS);
  for (const lane of ['cljs_node_test', 'cljs_browser', 'story_xray_browser']) {
    assert.equal(result[lane], 'true', `macros.clj must arm ${lane}`);
  }
});

test('the browser-gate arm is macros.clj by NAME, not a `.clj` widening (rf2-uqf5q)', () => {
  // The ruling was to complete an existing pattern on ONE predicate, not to
  // widen the browser matrix. A hypothetical JVM consumer beside macros.clj,
  // and an ordinary `.clj` under the Xray src tree, both keep the general
  // exclusion — on the Playwright gate as on the two CLJS lanes.
  for (const file of [
    'tools/story/src/re_frame/story/jvm_only_helper.clj',
    'tools/xray/src/day8/re_frame2_xray/jvm_only_helper.clj',
  ]) {
    assert.equal(classify(file).story_xray_browser, 'false', file);
  }
});

test('macros.clj does not drag the FULL Story feature-load tier along (rf2-uqf5q)', () => {
  // story_full_gate stays what rf2-65ajl made it: the feature-load runner's
  // own spec modules and orchestration. A macro change belongs on the smoke
  // tier, which is the tier that mounts the decks.
  assert.equal(classify(STORY_MACROS).story_full_gate, 'false');
});

test('an ordinary JVM-only .clj under Story src stays off both CLJS lanes (rf2-eyyd2)', () => {
  // The named exception is macros.clj and nothing else: a hypothetical JVM
  // consumer beside it must keep the general `.clj` exclusion.
  const result = classify('tools/story/src/re_frame/story/jvm_only_helper.clj');
  assert.equal(result.cljs_browser, 'false');
  assert.equal(result.cljs_node_test, 'false');
  assert.equal(result.tools_jvm, 'true');
});

test('macros.clj is still the ONLY .clj under either src tree (rf2-uqf5q)', () => {
  // The teeth on the "named, not globbed" choice. Three predicates now name
  // this one path; a SECOND macro namespace appearing beside it would be
  // armed on none of them and would repeat rf2-3xq1v exactly. Read the tree
  // rather than trusting the claim, and name what was found.
  const found = [];
  for (const tool of ['story', 'xray']) {
    const root = path.join(REPO_ROOT, 'tools', tool, 'src');
    const walk = (dir) => {
      for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
        const full = path.join(dir, entry.name);
        if (entry.isDirectory()) walk(full);
        else if (entry.name.endsWith('.clj')) {
          found.push(path.relative(REPO_ROOT, full).split(path.sep).join('/'));
        }
      }
    };
    if (fs.existsSync(root)) walk(root);
  }
  assert.deepEqual(
    found.sort(),
    [STORY_MACROS],
    'a new .clj under tools/{story,xray}/src is armed by NO classifier arm; ' +
      'name it in all three predicates or explain why it is a JVM consumer',
  );
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
  const result = classify('tools/testbed-support/src/re_frame/testbed/story_host.cljs');
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

// rf2-2rtt6.143 — the Reagent `[:>]` → Hicasso codemod lane, the same
// three-part shape one tree over. Its own block rather than a row in
// NEW_TOOLS_JVM_LANES above because the artefact is not under `tools/`: it is
// the first `migration/` path to reach test.yml at all. Before it, a
// codemod-only diff classified to NOTHING — `migration/**` reaches docs.yml,
// which stages the tree into the site and executes none of it — so 22 tests
// and the golden corpus that IS the tool's spec ran in no lane anywhere.

const CODEMOD_LANE = {
  job: 'jvm-migration-hicasso-codemod',
  output: 'migration_hicasso_codemod',
  dir: 'migration/reagent-to-hicasso/codemod',
  armed: 'migration/reagent-to-hicasso/codemod/src/re_frame/migration/hicasso/rewrite.clj',
  // The cross-tree `:paths` edge: the codemod puts
  // `../../../implementation/hicasso/src` on its classpath so it and the
  // runtime door share ONE slot rule (rf2-ani6y), and shared_rule_test.clj
  // pins the two `identical?`.
  //
  // rf2-r4j91 moved it. rf2-ani6y extracted the rule while the runtime still
  // lived in the bench tree, so the path used to be
  // `implementation/freehand/test/re_frame/bench/hicasso/front/slot.cljc`;
  // rf2-hic-001 moved the runtime into the package and frozen-sources.edn
  // pins the two files byte-for-byte, so BOTH answer identically today and
  // only one survives Freehand's retirement. `twinSharedRule` below is the
  // other one, kept so the negative can be asserted rather than assumed.
  sharedRule: 'implementation/hicasso/src/re_frame/hicasso/impl/slot.cljc',
  // rf2-0yp7w P0 re-homed the harness, so the twin moved with it — this was
  // `implementation/freehand/test/re_frame/bench/hicasso/front/slot.cljc`
  // when rf2-r4j91 wrote it, and rf2-6c12m.1 then moved the whole harness
  // out of the package to bench/hicasso/. Pointing a live assertion at a
  // deleted file is the failure mode this constant was extracted to avoid,
  // so it follows the file.
  twinSharedRule: 'bench/hicasso/src/re_frame/bench/hicasso/front/slot.cljc',
  // rf2-erjv — the SECOND cross-tree edge, and a different mechanism. The one
  // above is a classpath entry; this one is source TEXT. shared_rule_test.clj's
  // `the-callback-contracts-are-the-doors` (rf2-vi11) slurps the door's own
  // `.cljs` with a relative `io/file` and asserts the roster the codemod prints
  // into its `defhost` sketch equals the door's `callback-contracts`.
  door: 'implementation/hicasso/src/re_frame/hicasso/impl/codec.cljs',
};

test('the codemod JVM job is gated on its own output and runs the artefact (rf2-2rtt6.143)', () => {
  const workflow = fs.readFileSync(WORKFLOW, 'utf8');
  const block = jobBlock(workflow, CODEMOD_LANE.job);
  assert.match(block, /needs: detect_changed_surfaces/);
  assert.match(
    block,
    new RegExp(`if: needs\\.detect_changed_surfaces\\.outputs\\.${CODEMOD_LANE.output} == 'true'`),
    `${CODEMOD_LANE.job} must be gated on ${CODEMOD_LANE.output}`,
  );
  assert.match(
    block,
    new RegExp(`working-directory: ${CODEMOD_LANE.dir}`),
    `${CODEMOD_LANE.job} must run in ${CODEMOD_LANE.dir}`,
  );
  assert.match(
    block,
    /run: clojure -M:test/,
    `${CODEMOD_LANE.job} must invoke the artefact's own :test alias`,
  );
  // Plumbed out of detect_changed_surfaces, or the `if:` reads an empty
  // string and the job never runs.
  assert.match(
    workflow,
    new RegExp(
      `${CODEMOD_LANE.output}: \\$\\{\\{ steps\\.detect\\.outputs\\.${CODEMOD_LANE.output} \\}\\}`,
    ),
    `${CODEMOD_LANE.output} must be declared as a detect_changed_surfaces output`,
  );
  // Required, not advisory. A job absent from the aggregator's needs: is a
  // job a merge can skip past, which is the state this artefact was already in.
  assert.ok(
    jobBlock(workflow, 'all-required-passed').includes(`- ${CODEMOD_LANE.job}`),
    `aggregator must list ${CODEMOD_LANE.job} in needs:`,
  );
});

test('the codemod lane arms on its own tree and on the shared slot rule (rf2-2rtt6.143)', () => {
  assert.equal(
    classify(CODEMOD_LANE.armed)[CODEMOD_LANE.output],
    'true',
    `${CODEMOD_LANE.armed} must arm ${CODEMOD_LANE.output}`,
  );
  // The reverse edge: break the shared rule and shared_rule_test.clj is the
  // assertion that catches it, so its lane has to run.
  assert.equal(
    classify(CODEMOD_LANE.sharedRule)[CODEMOD_LANE.output],
    'true',
    `${CODEMOD_LANE.sharedRule} is on the codemod's classpath and must arm its lane`,
  );
  // …and that file keeps every output it already had. The arm is shared with
  // the package's own, so a narrowing here would be silent.
  const shared = classify(CODEMOD_LANE.sharedRule);
  for (const output of ['cljs_node_test', 'cljs_browser', 'hicasso_controlled']) {
    assert.equal(shared[output], 'true', `${CODEMOD_LANE.sharedRule} must still arm ${output}`);
  }
});

test('the TWIN shared rule is not what the codemod loads (rf2-r4j91, rf2-0yp7w)', () => {
  // The negative half of rf2-r4j91's move, asserted rather than assumed. A
  // byte-identical twin of the slot rule exists — frozen-sources.edn pins it —
  // so reading the wrong one would look harmless and be a lie about which tree
  // the codemod reads. Worse, before rf2-r4j91 it made a file inside a tree
  // scheduled for deletion the thing that catches a broken slot rule.
  //
  // WHAT THIS TEST COULD ASSERT CHANGED UNDER rf2-0yp7w P0, AND THE REASON IS
  // WORTH READING RATHER THAN THE ASSERTION BEING QUIETLY WEAKENED.
  //
  // rf2-r4j91 wrote the negative as "the twin's path must NOT arm
  // migration_hicasso_codemod", which held while the twin lived under
  // `implementation/freehand/*`. P0 re-homed the harness into
  // `implementation/hicasso/test/`, and the whole `implementation/hicasso/*`
  // arm sets that output — not for the classpath edge this test is about, but
  // for rf2-erjv's SOURCE-TEXT edge on `impl/codec.cljs`. So the twin does arm
  // the lane now, correctly and for an unrelated reason, and re-pointing the
  // old assertion at the new path would fail for a reason that is not a
  // regression. Over-classifying a seconds-long pure JVM suite is the cheaper
  // error and TESTING.md says to prefer it.
  //
  // The load-bearing negative therefore lives where it can still be stated
  // exactly: `shared_rule_test.clj` resolves the rule through `io/resource`
  // and refuses a path containing `re_frame/bench/` — by NAMESPACE, so it
  // names the twin rather than its address and survives the next relocation.
  // What remains checkable HERE is that the twin is not on the codemod's
  // classpath root, which is the property the arm is derived from.
  const deps = fs.readFileSync(
    path.join(REPO_ROOT, CODEMOD_LANE.dir, 'deps.edn'),
    'utf8',
  );
  const entries = (deps.match(/"\.\.\/[^"]*"/g) || []).map((s) => s.slice(1, -1));
  const root = path
    .relative(REPO_ROOT, path.resolve(REPO_ROOT, CODEMOD_LANE.dir, entries[0]))
    .replace(/\\/g, '/');
  assert.ok(
    !CODEMOD_LANE.twinSharedRule.startsWith(root + '/'),
    `the codemod's classpath root is ${root}, which contains the TWIN `
      + `${CODEMOD_LANE.twinSharedRule} — the tool would load the prototype's copy`,
  );
  // Non-vacuity: the twin must actually exist, or this asserts nothing. It is
  // the file `frozen-sources.edn` pins, and it outlives Freehand because
  // rf2-0yp7w P0 moved it out of that tree.
  assert.ok(
    fs.existsSync(path.join(REPO_ROOT, CODEMOD_LANE.twinSharedRule)),
    `${CODEMOD_LANE.twinSharedRule} does not exist, so this test asserts nothing`,
  );
});

test('the shared rule the codemod loads is the file the arm names (rf2-r4j91)', () => {
  // NON-VACUITY for the CLASSPATH edge, the same shape rf2-erjv gave the
  // source-text edge below. Arming a lane off a path is worth nothing if the
  // artefact's classpath points somewhere else, so this does not assert the
  // edge in prose: it lifts the cross-tree `:paths` entry out of deps.edn,
  // resolves it the way the JVM does — relative to the codemod's working
  // directory, which is what `working-directory:` sets in the job — and
  // requires the file this block arms on to live under it.
  const deps = fs.readFileSync(
    path.join(REPO_ROOT, CODEMOD_LANE.dir, 'deps.edn'),
    'utf8',
  );
  const entries = (deps.match(/"\.\.\/[^"]*"/g) || []).map((s) => s.slice(1, -1));
  assert.equal(
    entries.length,
    1,
    'the codemod declares exactly ONE cross-tree :paths entry; found ' + JSON.stringify(entries),
  );
  const root = path
    .relative(REPO_ROOT, path.resolve(REPO_ROOT, CODEMOD_LANE.dir, entries[0]))
    .replace(/\\/g, '/');
  assert.ok(
    CODEMOD_LANE.sharedRule.startsWith(root + '/'),
    `the codemod's classpath root is ${root}, so ${CODEMOD_LANE.sharedRule} is not on it`,
  );
  assert.ok(
    fs.existsSync(path.join(REPO_ROOT, CODEMOD_LANE.sharedRule)),
    `${CODEMOD_LANE.sharedRule} must exist — shared_rule_test.clj pins its path and reds on a move`,
  );
});

test('the codemod lane arms on the DOOR the roster pin reads (rf2-erjv)', () => {
  // The second reverse edge, and the one that was missing. PR #7762 put the
  // roster pin in the codemod's JVM lane while the classifier armed that lane
  // from `implementation/freehand/*` only — so a fourth contract at the door,
  // or a renamed one, ran no suite that pins it and reds later on an unrelated
  // PR that happens to arm the lane.
  assert.equal(
    classify(CODEMOD_LANE.door)[CODEMOD_LANE.output],
    'true',
    `${CODEMOD_LANE.door} is read by shared_rule_test.clj and must arm ${CODEMOD_LANE.output}`,
  );
  // …and the arm WIDENS the hicasso case rather than replacing it. The arm is
  // shared, `cljs_browser` and `hicasso_controlled` joined it only recently,
  // and trading one output for another here would close this hole by opening
  // others — the same constraint rf2-8a6s pinned one block down.
  const door = classify(CODEMOD_LANE.door);
  for (const output of ['cljs_node_test', 'cljs_browser', 'hicasso_controlled']) {
    assert.equal(door[output], 'true', `${CODEMOD_LANE.door} must still arm ${output}`);
  }
});

test('the door the codemod pin reads is the file the arm names (rf2-erjv)', () => {
  // NON-VACUITY. Arming a lane off a path is worth nothing if the suite in that
  // lane reads a DIFFERENT path, so this does not assert the edge in prose: it
  // lifts the `io/file` segments out of the pin itself, resolves them the way
  // the JVM does — relative to the codemod's working directory, which is what
  // `working-directory:` sets in the job — and requires the answer to be the
  // file this block arms on. Move the door, or repoint the pin, and the arm
  // becomes a lie; this row is what says so.
  const pin = fs.readFileSync(
    path.join(REPO_ROOT, CODEMOD_LANE.dir, 'test/re_frame/migration/hicasso/shared_rule_test.clj'),
    'utf8',
  );
  const form = /\(io\/file\s+((?:"[^"]*"\s*)+)\)/.exec(pin);
  assert.notEqual(form, null, 'shared_rule_test.clj must locate the door with an (io/file ...) form');
  const segments = form[1].match(/"([^"]*)"/g).map((s) => s.slice(1, -1));
  const resolved = path
    .relative(REPO_ROOT, path.resolve(REPO_ROOT, CODEMOD_LANE.dir, ...segments))
    .replace(/\\/g, '/');
  assert.equal(
    resolved,
    CODEMOD_LANE.door,
    `the pin reads ${resolved}, so that is the path the classifier must arm on`,
  );
  assert.ok(
    fs.existsSync(path.join(REPO_ROOT, resolved)),
    `${resolved} must exist — the pin asserts it does and reds on a move`,
  );
});

test('the codemod lane stays dark for surfaces it does not depend on (rf2-2rtt6.143)', () => {
  // A rule that matches everything is as useless as one that matches nothing.
  for (const file of [
    'spec/006-ReactiveSubstrate.md',
    'implementation/core/src/re_frame/core.cljc',
    'tools/xray/src/day8/re_frame2_xray/core.cljs',
    'migration/from-re-frame-v1/codemod/deps.edn',
  ]) {
    assert.equal(
      classify(file)[CODEMOD_LANE.output],
      'false',
      `${file} has no declared edge into the codemod's classpath`,
    );
  }
});

test('a codemod change does NOT fire the rest of the matrix (rf2-2rtt6.143)', () => {
  // The artefact loads no re-frame2 runtime — rewrite-clj over source text —
  // so arming implementation_jvm or the CLJS tiers would be fan-out with no
  // dependency behind it, and would still not have run this suite.
  const result = classify(CODEMOD_LANE.armed);
  assert.equal(result[CODEMOD_LANE.output], 'true');
  for (const output of ['implementation_jvm', 'cljs_node_test', 'cljs_browser', 'tools_jvm']) {
    assert.equal(result[output], 'false', `a codemod-only diff must not arm ${output}`);
  }
});

// rf2-0qzh — the v1 `reg-event-db/-fx/-ctx` → `reg-event` codemod (EP-0018
// Slice E): the identical hole one tree over, and the same four-sided fix.
// Measured on main, `from-re-frame-v1` appeared ZERO times in the classifier,
// in test.yml and on scripts/test-jvm-tools.sh, while deps.edn carried a
// working `:test` alias — 45 tests, 158 assertions — that nothing ran.

const V1_CODEMOD_LANE = {
  job: 'jvm-migration-v1-codemod',
  output: 'migration_v1_codemod',
  dir: 'migration/from-re-frame-v1/codemod',
  armed: 'migration/from-re-frame-v1/codemod/src/re_frame/migration/reg_event_codemod.clj',
};

test('the v1 codemod JVM job is gated on its own output and runs the artefact (rf2-0qzh)', () => {
  const workflow = fs.readFileSync(WORKFLOW, 'utf8');
  const block = jobBlock(workflow, V1_CODEMOD_LANE.job);
  assert.match(block, /needs: detect_changed_surfaces/);
  assert.match(
    block,
    /if: needs\.detect_changed_surfaces\.outputs\.migration_v1_codemod == 'true'/,
    `${V1_CODEMOD_LANE.job} must be gated on ${V1_CODEMOD_LANE.output}`,
  );
  assert.match(
    block,
    /working-directory: migration\/from-re-frame-v1\/codemod/,
    `${V1_CODEMOD_LANE.job} must run in ${V1_CODEMOD_LANE.dir}`,
  );
  assert.match(
    block,
    /run: clojure -M:test/,
    `${V1_CODEMOD_LANE.job} must invoke the artefact's own :test alias`,
  );
  // Plumbed out of detect_changed_surfaces, or the `if:` reads an empty
  // string and the job never runs.
  assert.match(
    workflow,
    /migration_v1_codemod: \$\{\{ steps\.detect\.outputs\.migration_v1_codemod \}\}/,
    `${V1_CODEMOD_LANE.output} must be declared as a detect_changed_surfaces output`,
  );
  // Required, not advisory. A job absent from the aggregator's needs: is a
  // job a merge can skip past, which is where this artefact already was.
  assert.ok(
    jobBlock(workflow, 'all-required-passed').includes(`- ${V1_CODEMOD_LANE.job}`),
    `aggregator must list ${V1_CODEMOD_LANE.job} in needs:`,
  );
});

test('the v1 codemod lane arms on its own tree (rf2-0qzh)', () => {
  for (const file of [
    V1_CODEMOD_LANE.armed,
    'migration/from-re-frame-v1/codemod/deps.edn',
    'migration/from-re-frame-v1/codemod/test/re_frame/migration/reg_event_codemod_test.clj',
  ]) {
    assert.equal(
      classify(file)[V1_CODEMOD_LANE.output],
      'true',
      `${file} must arm ${V1_CODEMOD_LANE.output}`,
    );
  }
});

// rf2-fk5jy — the REVERSE edge, and the reason the dark list below no longer
// names core. PR #8857 (rf2-36u96) wired `clojure -M:integration` into this
// job as a second step, and that alias declares
// `day8/re-frame2-core {:local/root "../../../implementation/core"}` — the
// codemod's emitted output is evaluated against the REAL v2 `reg-event`
// contract at namespace load. So core IS on this lane's classpath now, while
// the arm still fired on the codemod subtree alone: a core-only diff left the
// lane SKIPPED on exactly the commit that could break it, which is the
// surface-armed gate that does not run on the breaking push while the rollup
// reads green throughout.
//
// The edge is ONE-WAY, and the test after the dark list pins the other side:
// core changes arm the codemod lane, but a codemod-only diff must still not
// arm `implementation_jvm`. The codemod is downstream of core, not upstream,
// so widening in that direction would be fan-out with no dependency behind it.
//
// Spelled as ONE boolean on an already-declared :local/root edge, the way every
// other reverse edge in the classifier is — the `implementation/core/*` arm
// already carries three of them under rf2-wq17m. Not a dependency-graph engine,
// and not mark_all.
//
// SCOPED TO core, not to `implementation/*`: implementation/core/deps.edn puts
// only `:paths ["src"]` and two mvn coordinates on the base classpath — its own
// cross-artefact :local/root edges all sit under ALIASES, and a :local/root
// dependency never activates its target's aliases. Core's own tree is therefore
// the whole of what this lane reaches.

test('a core change arms the v1 codemod lane over the :integration edge (rf2-fk5jy)', () => {
  for (const file of [
    'implementation/core/src/re_frame/core.cljc',
    'implementation/core/src/re_frame/events.cljc',
    'implementation/core/deps.edn',
  ]) {
    assert.equal(
      classify(file)[V1_CODEMOD_LANE.output],
      'true',
      `${file} is on the codemod's :integration classpath and must arm ${V1_CODEMOD_LANE.output}`,
    );
  }
  // The fan-out core already had is untouched — the new output is additional,
  // not a replacement.
  const result = classify('implementation/core/src/re_frame/core.cljc');
  assert.equal(result.implementation_jvm, 'true');
  assert.equal(result.tools_jvm_machines_viz, 'true');
});

test('the v1 codemod lane stays dark for surfaces it does not depend on (rf2-0qzh)', () => {
  // A rule that matches everything is as useless as one that matches nothing.
  // The prose siblings matter most: `migration/from-re-frame-v1/` also holds
  // five hand-written migration guides, and the arm is the codemod SUBTREE,
  // not the parent — a guide edit must not queue a JVM lane.
  //
  // rf2-fk5jy — implementation/core USED to sit in this list, and its
  // removal is the tripwire that makes that reopen visible rather than
  // silent: core is now on the lane's :integration classpath, pinned
  // positively by the test above. A per-feature sibling stands in its place
  // so the list still proves the edge is core-SPECIFIC — implementation/flows
  // is reached by no alias of the codemod's deps.edn, so 'any implementation/
  // change arms this lane' would fail here.
  for (const file of [
    'migration/from-re-frame-v1/README.md',
    'migration/from-re-frame-v1/http-fx-to-managed-http.md',
    'migration/reagent-to-hicasso/codemod/deps.edn',
    'implementation/flows/src/re_frame/flows.cljc',
    'spec/006-ReactiveSubstrate.md',
  ]) {
    assert.equal(
      classify(file)[V1_CODEMOD_LANE.output],
      'false',
      `${file} has no edge into the v1 codemod's classpath`,
    );
  }
});

test('a v1 codemod change does NOT fire the rest of the matrix (rf2-0qzh)', () => {
  // The DOWNSTREAM half of the rf2-fk5jy edge. The artefact's base classpath
  // is `:paths ["src"]` plus clojure and rewrite-clj, and its one cross-tree
  // edge — `:integration`'s :local/root onto implementation/core — runs the
  // OTHER way: core is this lane's dependency, not its dependent. So this lane
  // is still the only thing a codemod-only diff should queue.
  const result = classify(V1_CODEMOD_LANE.armed);
  assert.equal(result[V1_CODEMOD_LANE.output], 'true');
  for (const output of [
    'implementation_jvm',
    'cljs_node_test',
    'cljs_browser',
    'tools_jvm',
    'migration_hicasso_codemod',
  ]) {
    assert.equal(result[output], 'false', `a v1 codemod-only diff must not arm ${output}`);
  }
});

// rf2-n8vp — the ssr-node package's lane, the same four-sided shape one tree
// over. PR #8028 landed implementation/ssr-node/ — 26 files, 73 test rows
// across 7 suites, runnable as `node implementation/ssr-node/test/run.cjs` —
// and the string `ssr-node` then appeared in neither
// implementation/package.json nor any file under .github/workflows/. So the
// package classified to nothing, ran nowhere, and the five bounded guarantees
// its README documents were claims rather than gates.
//
// The half that makes an arm real is a regression that fails when the arm goes
// wrong, in BOTH directions: a lane that never fires is the same defect as no
// lane, and a lane that fires on everything is the cost this bead exists to
// avoid.

const SSR_NODE_LANE = {
  job: 'node-ssr-node',
  output: 'ssr_node',
  script: 'test:ssr-node',
  dir: 'implementation/ssr-node',
  // The runner the gate invokes, the sibling it spawns, and a fixture — one
  // exemplar from each of the three shapes the package is made of.
  runner: 'implementation/ssr-node/test/run.cjs',
  src: 'implementation/ssr-node/src/service.cjs',
  fixture: 'implementation/ssr-node/test/fixtures/bad-no-allowlist.cjs',
};

// THE JOB IS UNGATED, AND THIS ROW NOW PINS THAT — rf2-8arzr.9, reversing the
// half of the shape above that turned out to be wrong for this lane.
//
// The four-sided shape is right for a lane whose suite tests the tree the
// classifier arms on. This one's does not: `absence.test.cjs` walks
// `git ls-files` across implementation/, examples/, tools/, scripts/ and
// .github/ and asserts that nothing out there can load this package or spells
// its refusal codes. Arming that on `implementation/ssr-node/**` made a
// whole-repo control blind to every tree it polices — and it was measured
// blind: slice E broke two rows in five files outside the package, the job was
// skipped on that PR and on every branch after it, and main was red for days
// with the alert channel silent.
//
// So the direction of this row flips. What must never come back is the `if:`,
// because re-adding it restores exactly the blindness, and it would look like
// tidying. The rest of the shape is unchanged and still load-bearing.
test('the ssr-node job is UNGATED and runs the suite (rf2-8arzr.9, was rf2-n8vp)', () => {
  const workflow = fs.readFileSync(WORKFLOW, 'utf8');
  const block = jobBlock(workflow, SSR_NODE_LANE.job);
  assert.doesNotMatch(
    block,
    /^\s+if:/m,
    `${SSR_NODE_LANE.job} must carry no if: — its suite polices the whole repo, `
      + 'so any surface can break it and every surface must run it',
  );
  assert.doesNotMatch(
    block,
    /^\s+needs:/m,
    `${SSR_NODE_LANE.job} must not need detect_changed_surfaces — it reads no `
      + 'output, and needing it would let a failed detector skip this job',
  );
  assert.match(
    block,
    /working-directory: implementation/,
    `${SSR_NODE_LANE.job} must run from implementation/, where the script is defined`,
  );
  // It must EXECUTE the gate, not merely mention it. A job that references a
  // command it never runs is the fail-open these rows exist to close.
  assert.ok(
    stepRunning(block, `npm run ${SSR_NODE_LANE.script}`),
    `the job must run \`npm run ${SSR_NODE_LANE.script}\` as a step`,
  );
  // The output survives this job losing its `if:`, because `jvm-node-crossing`
  // arms on it — the sidecar is half of that crossing. So it must still be
  // plumbed out of detect_changed_surfaces, or THAT job's `if:` reads an empty
  // string and can never fire, silently.
  assert.match(
    workflow,
    /ssr_node: \$\{\{ steps\.detect\.outputs\.ssr_node \}\}/,
    `${SSR_NODE_LANE.output} must be declared as a detect_changed_surfaces output`,
  );
  assert.match(
    jobBlock(workflow, 'jvm-node-crossing'),
    /needs\.detect_changed_surfaces\.outputs\.ssr_node == 'true'/,
    'jvm-node-crossing is what keeps the ssr_node output live — the classifier '
      + 'rows below guard it, not this job',
  );
  // Required, not advisory. A job absent from the aggregator's needs: is a job
  // a merge can skip past, which is where this artefact already was.
  assert.ok(
    jobBlock(workflow, 'all-required-passed').includes(`- ${SSR_NODE_LANE.job}`),
    `aggregator must list ${SSR_NODE_LANE.job} in needs:`,
  );
});

test('the ssr-node gate command exists and points at the package runner (rf2-n8vp)', () => {
  // NON-VACUITY. Gating a job on an npm script is worth nothing if the script
  // is absent or runs something else: `npm run` on a missing script exits
  // non-zero, so the job would red for a reason unrelated to the package, and a
  // repointed one would go green having run somebody else's suite. Resolve the
  // command's path argument the way the job does — relative to
  // `working-directory: implementation` — and require the answer to be the
  // runner this lane names.
  const pkg = JSON.parse(
    fs.readFileSync(path.join(IMPL_ROOT, 'package.json'), 'utf8'),
  );
  const command = pkg.scripts[SSR_NODE_LANE.script];
  assert.ok(command, `implementation/package.json must define ${SSR_NODE_LANE.script}`);
  const arg = /^node\s+(\S+)$/.exec(command.trim());
  assert.notEqual(arg, null, `${SSR_NODE_LANE.script} must be a bare \`node <runner>\` invocation`);
  const resolved = path
    .relative(REPO_ROOT, path.resolve(IMPL_ROOT, arg[1]))
    .replace(/\\/g, '/');
  assert.equal(
    resolved,
    SSR_NODE_LANE.runner,
    `the script runs ${resolved}, so that is the file this lane must arm on`,
  );
  assert.ok(
    fs.existsSync(path.join(REPO_ROOT, resolved)),
    `${resolved} must exist — the job invokes it and reds on a move`,
  );
});

test('the ssr-node lane arms on its own tree (rf2-n8vp)', () => {
  for (const file of [
    SSR_NODE_LANE.src,
    SSR_NODE_LANE.runner,
    SSR_NODE_LANE.fixture,
    'implementation/ssr-node/README.md',
    // The gate is DEFINED here and nowhere else, so an edit that renamed or
    // emptied the script must run it.
    'implementation/package.json',
  ]) {
    assert.equal(
      classify(file)[SSR_NODE_LANE.output],
      'true',
      `${file} must arm ${SSR_NODE_LANE.output}`,
    );
  }
});

test('the ssr-node lane stays dark for surfaces it does not depend on (rf2-n8vp)', () => {
  // A rule that matches everything is as useless as one that matches nothing,
  // and the two SIBLINGS matter most: `implementation/ssr/` and
  // `implementation/ssr-ring/` are different artefacts one character apart, and
  // a `implementation/ssr*` pattern would swallow all three. shadow-cljs.edn
  // and the lockfile are here for the other half of the scoping: no build id
  // reaches this package and it declares no npm dependency, so neither can
  // change what the runner executes.
  for (const file of [
    'implementation/ssr/src/re_frame/ssr.cljc',
    'implementation/ssr-ring/deps.edn',
    'implementation/core/src/re_frame/core.cljc',
    'implementation/shadow-cljs.edn',
    'implementation/package-lock.json',
    'spec/006-ReactiveSubstrate.md',
  ]) {
    assert.equal(
      classify(file)[SSR_NODE_LANE.output],
      'false',
      `${file} has no edge into the ssr-node package's runner`,
    );
  }
});

test('an ssr-node change fires that lane and NOTHING else (rf2-n8vp)', () => {
  // THE WHOLE REASON THIS OUTPUT IS ITS OWN. One entry in
  // implementation/package.json arms eleven expensive lanes — the Hicasso
  // browser matrix among them — none of which this package's files have any
  // edge into. Folding ssr-node into an existing output, or letting it reach
  // the generic build-config arm, would make every future edit here pay for
  // that matrix and STILL not run the 73 rows. So the assertion is exact: an
  // ssr-node-only diff arms `ssr_node` and leaves every other output false.
  const result = classify(SSR_NODE_LANE.src);
  assert.equal(result[SSR_NODE_LANE.output], 'true');
  for (const [output, value] of Object.entries(result)) {
    if (output === SSR_NODE_LANE.output) continue;
    assert.equal(
      value,
      'false',
      `an ssr-node-only diff must not arm ${output} — the package is plain `
        + 'CommonJS on node builtins, with no build, classpath or npm edge',
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

// rf2-6r9j.108 — template_expensive is armed by the generated app's ACTUAL
// inputs and nothing else. rf2-jdj17.1 armed it for tools/xray, tools/story
// and implementation/schemas because the then-current scaffold compiled
// against all three: `:devtools/preloads [day8.re-frame2-xray.preload]` in
// every emitted shadow-cljs.edn, a `:with-story` variant requiring
// re-frame.story, and an events.cljs/schema.cljs pair calling reg-app-schema.
// rf2-zq34m (18b9486664) deleted all of that. The current twelve-file
// emission requires core, the selected adapter and the view library only —
// and tools/template's own `template_test.clj` FORBIDS the three coordinates
// and their marker strings in anything emitted. So none of the three can
// break the generated `:app` compile, and arming the ~30-minute
// jvm-tools-template job (npm ci + Playwright Chromium provisioning) for a
// schemas-only or Story/Xray-only PR was pure cost.
//
// These are the NEGATIVE CONTROLS that replaced the three positive
// assertions. Each surface keeps its own independent lanes, asserted here
// alongside so a mistaken "narrow everything" edit reds instead of passing:
// re-add a template_expensive arm only with a real emitted dependency and a
// fixture that compiles it.

test('Xray src change does NOT arm template_expensive — not in the reduced scaffold (rf2-6r9j.108)', () => {
  const result = classify('tools/xray/src/day8/re_frame2_xray/preload.cljs');
  assert.equal(result.template_expensive, 'false');
  // ...but Xray's own lanes are untouched.
  assert.equal(result.tools_jvm, 'true');
  assert.equal(result.mcp_conformance, 'true');
  assert.equal(result.story_xray_browser, 'true');
});

test('Story src change does NOT arm template_expensive — the with-story variant is gone (rf2-6r9j.108)', () => {
  const result = classify('tools/story/src/re_frame/story.cljs');
  assert.equal(result.template_expensive, 'false');
  // ...but Story's own lanes are untouched.
  assert.equal(result.tools_jvm, 'true');
  assert.equal(result.mcp_conformance, 'true');
  assert.equal(result.story_xray_browser, 'true');
});

test('Schemas change does NOT arm template_expensive — the scaffold registers no schema (rf2-6r9j.108)', () => {
  const result = classify('implementation/schemas/src/re_frame/schemas.cljc');
  assert.equal(result.template_expensive, 'false');
  // ...but schemas keeps every per-feature lane of its own.
  assert.equal(result.implementation_jvm, 'true');
  assert.equal(result.cljs_node_test, 'true');
  assert.equal(result.cljs_browser, 'true');
  assert.equal(result.cljs_prod, 'true');
  assert.equal(result.bundle_isolation, 'true');
});

test('Story spec-md-only change does NOT arm template_expensive (rf2-jdj17.1, rf2-6r9j.108)', () => {
  const result = classify('tools/story/spec/002-Runtime.md');
  assert.equal(result.template_expensive, 'false');
});

test('Xray spec-md-only change does NOT arm template_expensive (rf2-jdj17.1, rf2-6r9j.108)', () => {
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
    'implementation/epoch/src/re_frame/epoch.cljc',
    'implementation/schemas/src/re_frame/schemas.cljc',
    'implementation/machines/src/re_frame/machines.cljc',
    'implementation/routing/src/re_frame/routing.cljc',
    'implementation/flows/src/re_frame/flows.cljc',
    'implementation/http/src/re_frame/http.cljc',
    'implementation/ssr/src/re_frame/ssr.cljc',
    'implementation/ssr-ring/src/re_frame/ssr/ring.clj',
    'implementation/resources/src/re_frame/resources.cljc',
    // rf2-qxg24 — `implementation/security/src/re_frame/security.cljc` is GONE
    // from this list. It never existed: the security partition is deliberately
    // src-less, so this row asserted that an imaginary file could change a
    // compiled example closure. It passed anyway, and would have gone on
    // passing forever, because these arms are pure path patterns — a phantom
    // path classifies exactly like a real one. The tier's real routing is
    // pinned below, from tracked paths.
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

  // rf2-in6c4 — `testbeds/tenant_switcher/core.cljs` left this list when the
  // gate widened its derivation from `:examples/*` to `:examples/* +
  // :testbeds/*`. A testbed source CAN now change a swept build, because the
  // build it belongs to is one of them. The scope discipline the case was
  // making — that not everything under testbeds/ queues a ~10-minute compile —
  // did not go away; it moved to the extension narrowing, pinned below.
  for (const file of [
    'implementation/core/src/re_frame/core.cljc',
    'implementation/scripts/check-elision.cjs',
    'tools/xray/test/day8/re_frame2_xray/core_test.clj',
    'tools/xray/spec/017-Test-Coverage-Matrix.md',
    'spec/006-ReactiveSubstrate.md',
  ]) {
    assert.equal(
      classify(file).examples_compile,
      'false',
      `${file} cannot change a standalone swept build`,
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

// rf2-g1m2q — `skills/re-frame2-pair-retro/**` and `skills/reagent-migration/**`
// armed NOTHING AT ALL. `skills_structural` had four case arms
// (skills/re-frame2-pair/tests/fixture/*, skills/re-frame2-pair/preload/*,
// skills/re-frame2-pair/* with skills/shared/*, skills/re-frame2-setup/*) and
// the main case carries no default arm, so a diff confined to either tree
// classified to ZERO outputs — not merely skills_structural=false. Measured
// with a passing control first, because an instrument that can answer "nothing
// here" answers it the same way when misused: skills/re-frame2-setup/SKILL.md
// returned skills_structural=true while both trees below returned 0 of 28.
//
// Both trees carry tests that the skills_structural tier is what schedules:
//   - skills/re-frame2-pair-retro/tests/*_test.clj, looped by the pair-retro
//     step in the `skills-structural` job (rf2-qad4l).
//   - skills/reagent-migration/tests/fixture/, whose MIG-23 SSR cold-start
//     :node-test build runs in `reagent-migration-fixture-cold-start`
//     (rf2-vpdrf / rf2-bbe91).
// So each tree's own gate was skipped on exactly the push that could break it.
//
// THE PAIR / PAIR-RETRO BOUNDARY IS THE TRAP, and it is why the retro tree
// cannot inherit an existing arm: the `skills/re-frame2-pair/*` pattern needs a
// `/` straight after `pair`, and `skills/re-frame2-pair-retro/...` has a `-`
// there, so it never matches. The negative assertions pin that boundary from
// the other side — retro paths must not arm the PAIR tree's expensive gates.
const SKILLS_STRUCTURAL_ONLY_FILES = [
  'skills/re-frame2-pair-retro/SKILL.md',
  'skills/re-frame2-pair-retro/tests/duplicate_search_test.clj',
  'skills/re-frame2-pair-retro/references/known-frictions.md',
  'skills/reagent-migration/SKILL.md',
  'skills/reagent-migration/references/procedure.md',
  'skills/reagent-migration/tests/fixture/test/reagent_migration/mig23_cold_start_test.cljs',
  'skills/reagent-migration/tests/fixture/shadow-cljs.edn',
];
for (const file of SKILLS_STRUCTURAL_ONLY_FILES) {
  test(`${file} arms skills_structural (rf2-g1m2q)`, () => {
    const result = classify(file);
    assert.equal(
      result.skills_structural,
      'true',
      `${file} is scheduled by the skills_structural tier; before rf2-g1m2q this tree armed nothing at all`,
    );
    // Scope discipline: structural ONLY. Neither tree drives a runtime, a live
    // Pair op, an example build or an emitted-scaffold compile, so neither may
    // queue the expensive lanes the pair/setup trees legitimately arm.
    for (const key of [
      'cljs_browser',
      'mcp_live',
      'examples_compile',
      'template_expensive',
    ]) {
      assert.equal(
        result[key],
        'false',
        `${file} is prose plus a self-contained fixture; it must NOT arm ${key}`,
      );
    }
  });
}

// rf2-bbe91 (audit reopen of PR #8868) — the REVERSE edge into the MIG-23 SSR
// cold-start fixture. rf2-g1m2q above armed `skills_structural` from the SKILL
// trees, which fires `reagent-migration-fixture-cold-start` on a change to the
// RECIPE. It left the other direction open: the fixture pins the recipe against
// four in-repo artefacts it resolves as `:local/root`, and a change to any of
// THOSE classified `skills_structural=false`, so the only cross-artefact
// cold-start witness was skipped on exactly the substrate change that could
// break it. A skipped job is an accepted result, so the aggregator stayed green.
//
// The roster below IS `skills/reagent-migration/tests/fixture/deps.edn`'s
// `:deps` map, and the same four `deps.edn` files the job's cache key hashes.
//
// EVERY PATH HERE IS TRACKED, checked with `git ls-files`, not transcribed from
// prose. The extensions are the trap: the Hicasso server door and the stock
// Reagent adapter are `.cljs`, not `.cljc`, and the reopening audit's own note
// spelled both `.cljc`. Nothing would have caught it — these arms are pure path
// patterns, so a phantom file classifies identically to a real one and the
// assertion passes while pinning a route no diff can ever take. That is the
// same defect rf2-qxg24 removes from the examples_compile fixture list in this
// file, and it is why the negative half below uses tracked paths too.
const MIG23_FIXTURE_LOCAL_ROOTS = [
  'implementation/core/src/re_frame/core.cljc',
  'implementation/ssr/src/re_frame/ssr.cljc',
  'implementation/hicasso/src/re_frame/hicasso/server.cljs',
  'implementation/adapters/reagent/src/re_frame/adapter/reagent.cljs',
];
for (const file of MIG23_FIXTURE_LOCAL_ROOTS) {
  test(`${file} arms skills_structural — MIG-23 cold-start reverse edge (rf2-bbe91)`, () => {
    const result = classify(file);
    assert.equal(
      result.skills_structural,
      'true',
      `${file} is on the MIG-23 cold-start fixture's :local/root classpath; a change to it ` +
        'must schedule reagent-migration-fixture-cold-start, the only cross-artefact witness ' +
        'that the documented SSR cold start still works',
    );
  });
}

// rf2-f9f3p — the SECOND fixture's half of the same reverse edge.
// `re-frame2-pair-fixture-pure` is gated solely on `skills_structural` too, so
// rf2-bbe91 armed it INCIDENTALLY for the two roots the fixtures share (core
// and the stock Reagent adapter). It shares only two:
// `skills/re-frame2-pair/tests/fixture/deps.edn` resolves FIVE in-repo
// artefacts, because the shipped preload `:require`s `re-frame.epoch`,
// `re-frame.schemas` and `re-frame.machines` directly. Those three classified
// `skills_structural=false` at rf2-bbe91's tip, for `src/*` and `deps.edn`
// alike — so the one job that compiles and tests that shipped source was
// accepted as SKIPPED on a change to three fifths of its own in-repo
// classpath, and a skipped required job is an accepted result.
//
// THE ROSTER IS THE FIXTURE'S OWN `:deps` MAP, all five roots, both entry
// shapes. The two already covered by rf2-bbe91 are pinned here as well rather
// than assumed: their coverage is a side effect of the OTHER fixture's roster,
// so a future narrowing of that roster would silently unarm this job, and
// nothing else in this file would notice.
//
// EVERY PATH IS TRACKED, checked with `git ls-files` rather than transcribed —
// the same trap rf2-bbe91's own note records, and it bites identically here:
// these arms are pure path patterns, so a phantom file classifies exactly like
// a real one and the assertion passes while pinning a route no diff can take.
// The extensions are again the tell — the three added roots are `.cljc` and the
// Reagent adapter is `.cljs`.
const PAIR_FIXTURE_LOCAL_ROOTS = [
  'implementation/core/src/re_frame/core.cljc',
  'implementation/core/deps.edn',
  'implementation/adapters/reagent/src/re_frame/adapter/reagent.cljs',
  'implementation/adapters/reagent/deps.edn',
  'implementation/epoch/src/re_frame/epoch.cljc',
  'implementation/epoch/deps.edn',
  'implementation/schemas/src/re_frame/schemas.cljc',
  'implementation/schemas/deps.edn',
  'implementation/machines/src/re_frame/machines.cljc',
  'implementation/machines/deps.edn',
];
for (const file of PAIR_FIXTURE_LOCAL_ROOTS) {
  test(`${file} arms skills_structural — Pair fixture reverse edge (rf2-f9f3p)`, () => {
    assert.equal(
      classify(file).skills_structural,
      'true',
      `${file} is on the re-frame2-pair fixture's :local/root classpath; a change to it must ` +
        'schedule re-frame2-pair-fixture-pure, the only job that compiles and tests the ' +
        'shipped preload against the tree it ships beside',
    );
  });
}

// The dispatch only ever SETS `skills_structural`, so it cannot narrow the
// three newly-armed artefacts' existing routing — each of which owns lanes the
// other two do not. Pinned per artefact rather than over the intersection,
// because the intersection is exactly what a refactor into an arm of the big
// first-match `case` would leave standing while it dropped the rest.
for (const [file, keys] of [
  [
    'implementation/epoch/src/re_frame/epoch.cljc',
    ['implementation_jvm', 'cljs_node_test', 'cljs_browser', 'examples_compile', 'cljs_prod', 'bundle_isolation', 'mcp_conformance', 'mcp_live'],
  ],
  [
    // template_expensive is deliberately absent from this roster
    // (rf2-6r9j.108): the reduced scaffold registers no schema, so schemas
    // never armed it on merit. The negative control lives above.
    'implementation/schemas/src/re_frame/schemas.cljc',
    ['implementation_jvm', 'cljs_node_test', 'cljs_browser', 'examples_compile', 'cljs_prod', 'bundle_isolation'],
  ],
  [
    'implementation/machines/src/re_frame/machines.cljc',
    ['implementation_jvm', 'cljs_node_test', 'cljs_browser', 'examples_compile', 'cljs_prod', 'bundle_isolation', 'tools_jvm_machines_viz', 'tools_cljs_machines_viz', 'playground'],
  ],
]) {
  test(`the Pair reverse edge does not narrow ${file}'s production routing (rf2-f9f3p)`, () => {
    const result = classify(file);
    for (const key of keys) {
      assert.equal(
        result[key],
        'true',
        `${file} must retain ${key}; the Pair fixture reverse edge widens, it never narrows`,
      );
    }
  });
}

test('re-frame2-pair-fixture-pure is job-level gated on skills_structural (rf2-f9f3p)', () => {
  const block = jobBlock(fs.readFileSync(WORKFLOW, 'utf8'), 're-frame2-pair-fixture-pure');
  assert.match(block, /needs: detect_changed_surfaces/);
  assert.match(
    block,
    /if: needs\.detect_changed_surfaces\.outputs\.skills_structural == 'true'/,
  );
});

// The negative half, now covering BOTH fixtures. The edge is the two fixtures'
// CLASSPATHS, not "anything under implementation/", so it must not become a
// default arm by drift. A `:local/root` contributes the artefact's `:paths`
// plus its dependency declaration — an artefact's own `test/` tree is not on
// either fixture's classpath — and the sibling per-feature artefacts are on
// neither.
//
// rf2-f9f3p moved one entry: `implementation/schemas/src/re_frame/schemas.cljc`
// stood here as a negative under rf2-bbe91 and is now a POSITIVE above, because
// it IS on the Pair fixture's classpath even though it is not on MIG-23's. That
// is the whole reason this pair of files is one-toucher — the roster and the
// assertions that pin it cannot be true in separate commits. Its slot is taken
// by `implementation/schemas/test/*`, which is the sharper boundary anyway: it
// pins the `src/*`-plus-`deps.edn` scope on a newly-armed artefact, from the
// side the new arms could most plausibly over-reach.
for (const file of [
  'implementation/core/test/re_frame/adapter/routing_arity_cljs_test.cljc',
  'implementation/schemas/test/re_frame/late_bind_missing_test.clj',
  'implementation/epoch/test/re_frame/epoch_attribution_test.clj',
  'implementation/adapters/uix/src/re_frame/adapter/uix.cljs',
  'implementation/flows/src/re_frame/flows.cljc',
]) {
  test(`${file} does NOT arm skills_structural (reverse edge stays scoped, rf2-bbe91 / rf2-f9f3p)`, () => {
    assert.equal(
      classify(file).skills_structural,
      'false',
      `${file} is on neither fixture's :local/root classpath; it must not queue the ` +
        'skills_structural fixture jobs',
    );
  });
}

// The dispatch only ever SETS `skills_structural`, so it cannot narrow the four
// artefacts' existing routing. Pinned because a future refactor into an arm of
// the big first-match `case` WOULD narrow it, silently and in exactly one
// direction — the failure the dispatch's own comment exists to prevent.
test('the MIG-23 reverse edge does not narrow core/ssr/reagent production routing (rf2-bbe91)', () => {
  for (const file of [
    'implementation/core/src/re_frame/core.cljc',
    'implementation/ssr/src/re_frame/ssr.cljc',
    'implementation/adapters/reagent/src/re_frame/adapter/reagent.cljs',
  ]) {
    const result = classify(file);
    for (const key of ['implementation_jvm', 'cljs_node_test', 'cljs_browser', 'cljs_prod', 'bundle_isolation']) {
      assert.equal(
        result[key],
        'true',
        `${file} must retain ${key}; the cold-start reverse edge widens, it never narrows`,
      );
    }
  }
});

test('reagent-migration-fixture-cold-start is job-level gated on skills_structural (rf2-bbe91)', () => {
  const block = jobBlock(
    fs.readFileSync(WORKFLOW, 'utf8'),
    'reagent-migration-fixture-cold-start',
  );
  assert.match(block, /needs: detect_changed_surfaces/);
  assert.match(
    block,
    /if: needs\.detect_changed_surfaces\.outputs\.skills_structural == 'true'/,
  );
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

// The CLJS job provisions the Clojure CLI for the steps that shell out to it.
// (Its original subject, G-12 Arm 2's `clojure -Stree` behind
// `test:ui-isolation`, retired with re-frame.ui — rf2-0yp7w.4. The provision
// itself stays asserted, because the shared-installer discipline below is what
// keeps it reproducible.)
test('cljs job provisions the Clojure CLI through the shared installer (rf2-3kewru, rf2-e7ja9)', () => {
  const block = jobBlock(fs.readFileSync(WORKFLOW, 'utf8'), 'cljs');
  const setup = block.indexOf('name: Set up Clojure CLI');
  assert.notEqual(setup, -1, 'cljs job must install the Clojure CLI');
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
    'cljs job must install the Clojure CLI through the shared installer (rf2-e7ja9)',
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
  // whole download+install attempts, curl retry, bounded backoff, sudo
  // install, and a final `clojure --version` failure boundary.
  assert.match(src, /^set -euo pipefail$/m, 'installer must run under set -euo pipefail');
  // rf2-xsfr widened the envelope from three attempts / 10s-20s-30s backoff —
  // about two minutes — because github.com 5xx storms outlasted it four times
  // in one session (#8007, #8009, #8017, #8019). #8017 logged eighteen 503s,
  // i.e. EVERY request the old script was willing to make, and still lost. The
  // floor is stated as an inequality, not as the literal `attempts=6`: a later
  // widening is the intended direction of travel and must not have to come
  // here, while a narrowing back under the observed storm must.
  const attemptsPin = src.match(/^attempts=(\d+)$/m);
  assert.ok(attemptsPin, 'installer must declare its attempt count as `attempts=<n>`');
  assert.ok(
    Number(attemptsPin[1]) >= 6,
    `installer must make at least 6 whole attempts (found ${attemptsPin[1]}) — three did not ` +
      'outlast the observed 503 storms (rf2-xsfr)',
  );
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
  // Bounded backoff, now with a jitter term: ~59 jobs install this CLI
  // concurrently, and without jitter they retry in lockstep and arrive on the
  // struggling endpoint as one herd every round (rf2-xsfr).
  assert.match(
    src,
    /delay=\$\(\(attempt \* \d+ \+ RANDOM % \d+\)\)/,
    'installer must keep the bounded backoff, with a jitter term (rf2-xsfr)',
  );
  assert.match(src, /sleep "\$delay"/, 'installer must sleep the computed backoff');
  // The half of rf2-xsfr that is NOT about surviving the storm: when the
  // attempts ARE exhausted the step must say so as infrastructure. Falling
  // through to `clojure --version` on a runner with no CLI dies `command not
  // found`, exit 127 — a red that reads exactly like the gate this job exists
  // to run having failed, when in fact no gate ran at all. Distinguishing those
  // two by hand, from the raw log, is the cost rf2-xsfr was filed to delete, so
  // a regression here is silent and expensive: assert the annotation.
  assert.match(
    src,
    /echo "::error title=[^"]*::/,
    'installer must fail exhaustion as an explicit ::error annotation, so a job that never ran ' +
      'its gate is distinguishable from a gate that failed WITHOUT reading the raw log (rf2-xsfr)',
  );
  assert.match(
    src,
    /^\s*exit 1$/m,
    'installer must exit nonzero on exhaustion rather than falling through to `clojure --version` ' +
      "— the old fall-through's exit 127 is the masking failure (rf2-xsfr)",
  );
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
// (ACTIVE_AUTHORITIES: EP-0030, implementation/README.md, implementation/adapters/
// reagent/README.md, skills/…), not the diff. Conditional execution keyed to a diff classifier is
// a category mismatch for a guard that scans a fixed inventory — a PR editing a
// roster file may not fire the guard that pins it (the same inventory<->trigger
// bug class as rf2-d9v3n / rf2-rf7gu). The ruled fix (option (e)) moves the
// guard out of the surface-gated synthesis-docs job into the UNCONDITIONAL
// verify-readme-links job so it runs on every PR, dissolving the roster<->
// classifier sync problem with zero machinery. This arm pins the wiring:
// verify-readme-links must carry BOTH guard invocations. A future PR un-moving
// or gutting the wiring fails here (this file runs under the unconditional
// js-harness-self-tests job's test:scripts).
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
// unconditional job (js-harness-self-tests -> test:scripts).
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

test('top-level testbed .cljs change fires cljs_browser, not the adapter smokes (rf2-t5slp)', () => {
  const result = classify('testbeds/ssr_basic/core.cljs');
  assert.equal(result.adapter_testbed_smokes, 'false');
  assert.equal(result.cljs_browser, 'true');
});

// rf2-in6c4 — THE ARMING PIN FOR THE TESTBED COMPILE GATE, per the rf2-6ng7
// codicil that a coverage fix pins its own arming in the same patch.
//
// WHAT WAS DARK. `cljs_browser` above is the only lane the generic
// `testbeds/*` case ever armed, and it does not compile a single one of these
// builds: the top-level `testbeds/` tree holds 13 `.cljs` files, 0 test files
// and 0 `.clj`, and no test in the armed lane `:require`s a testbed namespace
// (the Xray e2e suites that read as though they do use their own
// `host-fixtures` copies). shadow compiles what is required of it, so a
// compile break confined to a top-level testbed was armed by nothing and
// caught only by the nightly Xray FULL feature gate. `check-examples-compile.
// cjs` now derives `:testbeds/*` alongside `:examples/*`; this is the half
// that makes the schedule real.
test('top-level testbed .cljs change fires examples_compile — the only lane that compiles it (rf2-in6c4)', () => {
  const result = classify('testbeds/ssr_basic/core.cljs');
  assert.equal(
    result.examples_compile,
    'true',
    'check-examples-compile.cjs is the only PR-time job that compiles a ' +
      'top-level :testbeds/* build; without this arm a compile break there ' +
      'ships green',
  );
});

// The extension narrowing is the arm's only subtlety, so it is pinned in both
// directions. A ~10-minute compile job must not queue for a README, and the
// two non-CLJS files in this tree have owners of their own — spec-helpers.cjs
// belongs to the Xray smoke tier (asserted above), and tenant_switcher/spec.cjs
// to the tenant-switcher smoke.
test('a testbed README does NOT fire examples_compile (extension narrowing, rf2-in6c4)', () => {
  const result = classify('testbeds/README.md');
  assert.equal(
    result.examples_compile,
    'false',
    'no markdown file can change what shadow-cljs compile produces',
  );
});

test('a testbed .cjs helper does NOT fire examples_compile (extension narrowing, rf2-in6c4)', () => {
  for (const file of ['testbeds/spec-helpers.cjs', 'testbeds/tenant_switcher/spec.cjs']) {
    assert.equal(
      classify(file).examples_compile,
      'false',
      `${file} is a Playwright-side module, not shadow-cljs input`,
    );
  }
});

// A NEW testbed build still arms the gate whatever its own files look like,
// because DECLARING one edits implementation/shadow-cljs.edn — which is on the
// examples_compile roster in its own right. That is what keeps the narrowing
// above from being a hole: the roster is derived from the build config, and
// the build config is armed.
test('declaring a build in shadow-cljs.edn arms examples_compile (rf2-in6c4 backstop)', () => {
  const result = classify('implementation/shadow-cljs.edn');
  assert.equal(result.examples_compile, 'true');
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
// .test.cjs coverage under test:scripts and drive no browser gate.

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

// rf2-6ng7 class — spec-helpers.cjs is the shared Playwright assertion-matcher
// module with FOUR live gate-family consumers: the adapter/ui smoke specs, the
// Story/Xray PR-smoke tier (serve-and-run-story-play-scripts.cjs and
// tools/xray/testbeds/feature_matrix/scenarios.cjs both require it), and the
// tenant-switcher smoke (testbeds/tenant_switcher/spec.cjs). Its case used to
// arm only the smoke pair, so a break confined to the Story/Xray-only exports
// (navigate, reloadPage, …) or the tenant spec's matchers merged green.
test('examples/scripts/spec-helpers.cjs (shared matchers) fires every gate that loads it (rf2-6ng7 class)', () => {
  const result = classify('examples/scripts/spec-helpers.cjs');
  assert.equal(result.adapter_testbed_smokes, 'true');
  assert.equal(
    result.story_xray_browser,
    'true',
    'spec-helpers.cjs is require\'d by serve-and-run-story-play-scripts.cjs and the Xray feature-matrix scenarios; it must fire story_xray_browser',
  );
  assert.equal(
    result.tenant_switcher_smoke,
    'true',
    'testbeds/tenant_switcher/spec.cjs requires spec-helpers.cjs; it must fire tenant_switcher_smoke',
  );
});

test('examples/scripts/examples-port.cjs stays adapter-smoke-scoped after the spec-helpers split (rf2-6ng7 class)', () => {
  const result = classify('examples/scripts/examples-port.cjs');
  assert.equal(result.adapter_testbed_smokes, 'true');
  assert.equal(
    result.story_xray_browser,
    'false',
    'examples-port.cjs has no Story/Xray consumer (the Story launchers use story-feature-load-port.cjs); the split must not over-arm it',
  );
  assert.equal(result.tenant_switcher_smoke, 'false');
});

// rf2-6ng7 class — testbeds/spec-helpers.cjs is require'd ONLY by
// tools/xray/testbeds/feature_matrix/scenarios.cjs, which both Xray
// feature-gate tiers load. The generic testbeds/* fall-through armed only
// cljs_browser — a CLJS lane that never loads a .cjs — so an edit breaking it
// red-ded no armed PR job and was caught only by the nightly full gate.
test('testbeds/spec-helpers.cjs fires the Xray smoke tier, not the CLJS browser lane (rf2-6ng7 class)', () => {
  const result = classify('testbeds/spec-helpers.cjs');
  assert.equal(
    result.story_xray_browser,
    'true',
    'the Xray feature-matrix scenarios module requires testbeds/spec-helpers.cjs; it must fire story_xray_browser',
  );
  assert.equal(
    result.cljs_browser,
    'false',
    'no CLJS source is reachable from testbeds/spec-helpers.cjs; the CLJS browser lane buys nothing for it',
  );
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
// adapter-smoke orchestrator under its own classifier output and
// its own CI job (ui-smoke, ADAPTER_SMOKE_FILTER=ui/testbed). The trigger
// discipline mirrors adapter_testbed_smokes: direct substrate-source +
// smoke-harness changes fire it; core / adapter-source / generic
// build-config changes do not (nightly runs the unfiltered sweep).

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

// rf2-qxg24 — the security tier is the FOURTH source-less partition and now
// takes the same route as the three above. It had been the stated precedent for
// that route since rf2-dxndhc while two earlier executable arms classified it
// as a shipped production feature, arming `cljs_browser`, `cljs_prod`,
// `bundle_isolation` and `examples_compile` on every security-only edit.
//
// NONE of those four gates can observe an edit here. `implementation/security`
// is `:paths []` / `:deps {}` with no `src/` tree and no published artefact;
// `:browser-test` selects only `*-dom-cljs-test` namespaces and every namespace
// in this tree is `-security-cljs-test`; the production and bundle-isolation
// builds do not require the test tree; and the all-examples compiler has no
// edge to it. The toll was not theoretical — security-only commit d1fa5ff493
// made the ~10-minute all-examples job its critical path.
//
// Paths are TRACKED files, checked with `git ls-files`. That is the whole point
// of this bead: the row this replaces asserted from
// `implementation/security/src/re_frame/security.cljc`, which has never
// existed.
const SECURITY_TIER_FILES = [
  'implementation/security/deps.edn',
  'implementation/security/test/re_frame/security/mcp_egress_security_cljs_test.cljc',
  'implementation/security/test/re_frame/security/schema_redaction_security_cljs_test.cljc',
  'implementation/security/test/re_frame/security/gen.cljc',
];
for (const file of SECURITY_TIER_FILES) {
  test(`${file} arms implementation_jvm + cljs_node_test ONLY (src-less security tier, rf2-qxg24)`, () => {
    const result = classify(file);
    assert.equal(
      result.implementation_jvm,
      'true',
      'a security-tier change must still run the jvm-security suite (its own :test alias)',
    );
    assert.equal(
      result.cljs_node_test,
      'true',
      'a security-tier change must still run the consolidated :node-test build',
    );
    // The four gates this bead removes, locked so the broad production arm
    // cannot silently return.
    for (const key of ['examples_compile', 'cljs_browser', 'cljs_prod', 'bundle_isolation']) {
      assert.equal(
        result[key],
        'false',
        `${file} is a source-less test partition; ${key} cannot observe it and must not be armed`,
      );
    }
  });
}

test('jvm-security remains gated on implementation_jvm and in the aggregator (rf2-qxg24 criterion 4)', () => {
  const workflow = fs.readFileSync(WORKFLOW, 'utf8');
  const block = jobBlock(workflow, 'jvm-security');
  assert.match(block, /needs: detect_changed_surfaces/);
  assert.match(
    block,
    /if: needs\.detect_changed_surfaces\.outputs\.implementation_jvm == 'true'/,
  );
  assert.match(
    block,
    /implementation\/security/,
    'jvm-security must still run from implementation/security',
  );
  assert.match(
    jobBlock(workflow, 'all-required-passed'),
    /- jvm-security\r?\n/,
    'narrowing the security tier must not drop its required JVM job from the aggregator',
  );
});

test('narrowing the security tier leaves the production per-feature fan-out intact (rf2-qxg24 criterion 5)', () => {
  // The arms security LEFT still route every src-bearing artefact exactly as
  // before. This is the regression that matters: the edit removed one entry
  // from two shared patterns, and a fat-fingered pattern would take a sibling
  // with it — silently, since fewer gates always passes.
  for (const file of [
    'implementation/schemas/src/re_frame/schemas.cljc',
    'implementation/machines/src/re_frame/machines.cljc',
    'implementation/routing/src/re_frame/routing.cljc',
    'implementation/flows/src/re_frame/flows.cljc',
    'implementation/http/src/re_frame/http.cljc',
    'implementation/ssr/src/re_frame/ssr.cljc',
    'implementation/resources/src/re_frame/resources.cljc',
  ]) {
    const result = classify(file);
    for (const key of ['implementation_jvm', 'cljs_node_test', 'cljs_browser', 'cljs_prod', 'bundle_isolation', 'examples_compile']) {
      assert.equal(
        result[key],
        'true',
        `${file} is a src-bearing published artefact; it must retain ${key}`,
      );
    }
  }
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
// spec/ data, and the api-manifest CLJS probe is the ONE consumer that
// expands through it. Its own suite is the deterministic control for a
// cold-load race that has shipped twice behind fully green lanes — which
// is exactly why the routing has to be pinned. If the classifier leaves
// every output false, the one job in CI that goes red when the racy shape
// returns simply SKIPS, and the aggregator passes.
//
// The cljs_browser assertion pins the arm in the OTHER direction, and it
// is here because its absence is what let a stale lane survive (rf2-7b1ti).
// The arm carried cljs_browser=true for the Freehand -dom-cljs-test
// fixture suites; those retired with rf2-0yp7w and nothing browser-side
// replaced them, but because this block asserted only the two outputs
// above, the dead lane was invisible to the suite that supposedly pinned
// the arm. re-frame.build.spec-resource is macro-side .clj, so a CLJS
// build reaches it only via a macro require, and the one such path is
// re-frame.api-manifest.cljs-publics — whose only two consumers are plain
// -cljs-test namespaces that nothing else requires, while :browser-test
// selects .*-dom-cljs-test$. Asserting 'false' costs nothing today and
// means a future re-add has to argue for itself here first.

for (const file of [
  'implementation/spec-resource/src/re_frame/build/spec_resource.clj',
  'implementation/spec-resource/test/re_frame/build/spec_resource_test.clj',
  'implementation/spec-resource/deps.edn',
  // Artefact-ROOT matching, not an enumeration: a future nested namespace
  // must route too, and the rot would otherwise be silent.
  'implementation/spec-resource/src/re_frame/build/deeply/nested.clj',
]) {
  test(`${file} arms implementation_jvm + cljs_node_test, and NOT cljs_browser (shared spec/ reader)`, () => {
    const result = classify(file);
    assert.equal(
      result.implementation_jvm,
      'true',
      'the reader change must run its own race control (jvm-spec-resource)',
    );
    assert.equal(
      result.cljs_node_test,
      'true',
      'the consolidated :node-test build is where the api-manifest probe macro-expands through this reader',
    );
    assert.equal(
      result.cljs_browser,
      'false',
      'no browser namespace reaches this reader: :browser-test selects .*-dom-cljs-test$, and the one macro path in (re-frame.api-manifest.cljs-publics) has only plain -cljs-test consumers — re-add cljs_browser here only with a namespace that shows otherwise',
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
  // Scoped to src/test/deps.edn under test-quiet; a generic docs change stays
  // off the implementation gates entirely.
  //
  // rf2-61ar — the control moved off `spec/006-ReactiveSubstrate.md`. Not
  // because this case's claim changed, but because that file stopped being an
  // inert control: jvm-ui slurps it (slice_memo_lifetime_census_jvm_test.clj),
  // so spec prose now arms implementation_jvm by design and could no longer
  // distinguish a test-quiet over-broadening from the spec arm doing its job.
  // `docs/guide/getting-started.md` is prose no suite reads, which is what this
  // case always needed.
  const result = classify('docs/guide/getting-started.md');
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

// rf2-ga8m — the Hicasso three-engine controlled-input gate (rf2-hic-016),
// scheduled at last. It landed green and ran NOWHERE: the PR that built it was
// fenced out of .github/workflows/** while rf2-8a6s held that surface, so it
// declared itself a known hole in scripts/check_gate_scheduling.py instead of
// going quietly unrun. These rows are the other half of closing that hole.

const HICASSO_CONTROLLED = {
  job: 'cljs-hicasso-controlled',
  output: 'hicasso_controlled',
  // The gate's own launcher: it owns the check floor and the cross-engine
  // comparator, so a diff can soften the verdict logic itself.
  launcher: 'implementation/scripts/serve-and-run-hicasso-controlled-testbed.cjs',
  spec: 'implementation/hicasso/testbed/spec.cjs',
  // The gate's actual subject: the element-path converge.
  src: 'implementation/hicasso/src/re_frame/hicasso/impl/controlled.cljs',
};

test('the hicasso controlled-input job is gated on its own output and runs the gate (rf2-ga8m)', () => {
  const workflow = fs.readFileSync(WORKFLOW, 'utf8');
  const block = jobBlock(workflow, HICASSO_CONTROLLED.job);
  assert.match(block, /needs: detect_changed_surfaces/);
  assert.match(
    block,
    /if: needs\.detect_changed_surfaces\.outputs\.hicasso_controlled == 'true'/,
    `${HICASSO_CONTROLLED.job} must be gated on ${HICASSO_CONTROLLED.output}`,
  );
  assert.match(block, /npm run test:hicasso-controlled/);
  // Plumbed out of detect_changed_surfaces, or the `if:` reads an empty
  // string and the job never runs.
  assert.match(
    workflow,
    /hicasso_controlled: \$\{\{ steps\.detect\.outputs\.hicasso_controlled \}\}/,
    `${HICASSO_CONTROLLED.output} must be declared as a detect_changed_surfaces output`,
  );
  // Required, not advisory: this is the only lane that witnesses I15's caret
  // and composition clauses.
  assert.ok(
    jobBlock(workflow, 'all-required-passed').includes(`- ${HICASSO_CONTROLLED.job}`),
    `aggregator must list ${HICASSO_CONTROLLED.job} in needs:`,
  );
});

test('the hicasso controlled-input job installs the PINNED three engines (rf2-ga8m)', () => {
  const block = jobBlock(fs.readFileSync(WORKFLOW, 'utf8'), HICASSO_CONTROLLED.job);

  // All three, by name. Dropping one leaves a gate that still passes and no
  // longer tests what it is for — and the cross-engine comparator in the
  // runner is inert below two engines, so a single-engine run would go green
  // having checked nothing about divergence.
  assert.match(
    block,
    /playwright install --with-deps chromium firefox webkit/,
    'the gate must install Chromium, Firefox AND WebKit',
  );

  // THE PIN IS STRUCTURAL, and these two assertions are the whole of it.
  // `npx playwright` resolves implementation/node_modules/.bin/playwright —
  // the version package.json pins — only because the job runs in
  // `implementation` with `npm ci` already done. Move the step to the repo
  // root or ahead of `npm ci` and npx resolves a NEWER Playwright from its own
  // cache, fetches that release's browser revisions, and prunes the pinned
  // WebKit out of the shared browser cache: a green job that never launched
  // the engine it claims to. Measured while wiring this lane — 1.59.1 inside
  // implementation/, 1.62.1 one directory up — so `--no-install` is no
  // defence; only the working directory is.
  assert.match(
    block,
    /working-directory: implementation/,
    'the pin depends on the job running in implementation/',
  );
  const npmCi = block.indexOf('run: npm ci');
  const install = block.indexOf('playwright install');
  assert.ok(npmCi !== -1, 'the job must run npm ci');
  assert.ok(
    npmCi < install,
    'npm ci must precede the playwright install, or npx resolves an unpinned Playwright',
  );
});

test('the hicasso controlled-input lane arms on its tree, its launcher and the build config (rf2-ga8m)', () => {
  for (const file of [
    HICASSO_CONTROLLED.spec,
    HICASSO_CONTROLLED.src,
    HICASSO_CONTROLLED.launcher,
    // The trio: shadow-cljs.edn declares the `:hicasso/testbed` build the gate
    // compiles, and the playwright pin in package.json / the lockfile IS the
    // three engine revisions under test.
    'implementation/shadow-cljs.edn',
    'implementation/package.json',
    'implementation/package-lock.json',
  ]) {
    assert.equal(
      classify(file)[HICASSO_CONTROLLED.output],
      'true',
      `${file} must arm ${HICASSO_CONTROLLED.output}`,
    );
  }
  // The launcher case is placed before the generic implementation/scripts/*
  // case, so it must not narrow what that case already gave the file.
  const launcher = classify(HICASSO_CONTROLLED.launcher);
  for (const output of [
    'cljs_node_test',
    'cljs_browser',
    'cljs_prod',
    'bundle_isolation',
    'reagent_slim_bundle',
  ]) {
    assert.equal(
      launcher[output],
      'true',
      `${HICASSO_CONTROLLED.launcher} must still arm ${output}`,
    );
  }
});

test('the hicasso controlled-input lane stays dark for unrelated surfaces (rf2-ga8m)', () => {
  // A rule that matches everything is as useless as one that matches nothing.
  // `implementation/core` matters most here: it fans out to almost every other
  // output, and arming three browser launches from it would put this gate on
  // nearly every PR in the repo.
  for (const file of [
    'implementation/core/src/re_frame/core.cljc',
    'implementation/ui/src/re_frame/ui.cljs',
    'spec/006-ReactiveSubstrate.md',
    'tools/xray/src/day8/re_frame2_xray/core.cljs',
    'migration/reagent-to-hicasso/codemod/deps.edn',
  ]) {
    assert.equal(
      classify(file)[HICASSO_CONTROLLED.output],
      'false',
      `${file} has no edge into the hicasso controlled-input gate`,
    );
  }
});

test('a hicasso package change does NOT fire the JVM or prod tiers (rf2-ga8m)', () => {
  // The hicasso arm stays narrow everywhere it has no suite: the runtime
  // requires React so every suite it owns is CLJS, no `-elision-prod-test$`
  // namespace exists, and it mounts no testbed the ui gates drive.
  const result = classify(HICASSO_CONTROLLED.spec);
  assert.equal(result.cljs_node_test, 'true');
  assert.equal(result[HICASSO_CONTROLLED.output], 'true');
  for (const output of ['implementation_jvm', 'cljs_prod', 'bundle_isolation']) {
    assert.equal(result[output], 'false', `a hicasso-only diff must not arm ${output}`);
  }
});

// rf2-hic-015 — the Hicasso HMR gate, the repo's LAST declared scheduling
// hole, closed. It landed under rf2-vsgq green on three engines and ran
// nowhere: no workflow invoked `npm run test:hicasso-hmr`, so it declared
// itself `unscheduled` in scripts/check_gate_scheduling.py rather than let the
// absence go unrecorded. These rows are the half that makes the schedule real
// — an arm with no regression is the same fail-open, one level down.

const HICASSO_HMR = {
  job: 'cljs-hicasso-hmr',
  output: 'hicasso_hmr',
  // The gate's own launcher. It owns the `shadow-cljs watch`, the HOT-LINE
  // rewriter that makes a save a save, and the structural coverage floor plus
  // the cross-engine comparator — every one of them softenable by a diff.
  launcher: 'implementation/scripts/serve-and-run-hicasso-hmr-testbed.cjs',
  spec: 'implementation/hicasso/testbed/hmr_spec.cjs',
  // The file the gate REWRITES, and the page shadow's own :dev-http serves.
  hotFile: 'implementation/hicasso/testbed/hicasso_hmr_testbed/views.cljs',
  page: 'implementation/hicasso/testbed/hmr/index.html',
};

test('the hicasso HMR job is gated on its own output and runs the gate (rf2-hic-015)', () => {
  const workflow = fs.readFileSync(WORKFLOW, 'utf8');
  const block = jobBlock(workflow, HICASSO_HMR.job);
  assert.match(block, /needs: detect_changed_surfaces/);
  assert.match(
    block,
    /if: needs\.detect_changed_surfaces\.outputs\.hicasso_hmr == 'true'/,
    `${HICASSO_HMR.job} must be gated on ${HICASSO_HMR.output}`,
  );
  // It must EXECUTE the gate, not merely mention it. A job that references a
  // command it never runs is the fail-open this bead exists to close.
  assert.ok(
    stepRunning(block, 'npm run test:hicasso-hmr'),
    'the job must run `npm run test:hicasso-hmr` as a step',
  );
  // Plumbed out of detect_changed_surfaces, or the `if:` reads an empty string
  // and the job can never run — silently.
  assert.match(
    workflow,
    /hicasso_hmr: \$\{\{ steps\.detect\.outputs\.hicasso_hmr \}\}/,
    `${HICASSO_HMR.output} must be declared as a detect_changed_surfaces output`,
  );
  // Required, not advisory: a job absent from the aggregator is advisory
  // whatever its own gate says, and this is the only lane that drives a real
  // hot reload.
  assert.ok(
    jobBlock(workflow, 'all-required-passed').includes(`- ${HICASSO_HMR.job}`),
    `aggregator must list ${HICASSO_HMR.job} in needs:`,
  );
});

test('the hicasso HMR job installs the PINNED three engines and narrows none (rf2-hic-015)', () => {
  const block = jobBlock(fs.readFileSync(WORKFLOW, 'utf8'), HICASSO_HMR.job);

  assert.match(
    block,
    /playwright install --with-deps chromium firefox webkit/,
    'the gate must install Chromium, Firefox AND WebKit',
  );

  // THE QUIET WAY TO KEEP THE NAME AND DROP THE CLAIM. Unlike its sibling this
  // runner takes an engine-narrowing env knob, `HICASSO_HMR_ENGINES`, and its
  // cross-engine comparator is inert below two engines — so a job that set it
  // would still print a PASS having checked nothing about divergence. The job
  // must pass no engine narrowing at all.
  //
  // The runner now refuses the full verdict to ANY narrowing, not just one
  // below the comparator's floor (rf2-l92i), so a job that set this would be
  // caught in its own log too. That is a second line of defence and not a
  // reason to relax this one: the runner's honesty is about the reader of a
  // log, and this row is about the job never getting into that state. Note in
  // particular that a TWO-engine narrowing does get compared — so "the
  // comparator is inert" is no longer the whole reason to forbid the knob
  // here; the whole reason is that this job's name promises three engines.
  //
  // Read the job's EXECUTABLE text, not its prose. The first cut of this row
  // grepped the whole block and reddened on the YAML COMMENT that explains the
  // knob — an assertion that forbids naming the hazard is an assertion that
  // punishes documenting it, and it would have been "fixed" by deleting the
  // explanation. Comments out, then look for an assignment.
  const executable = block
    .split(/\r?\n/)
    .filter((line) => !/^\s*#/.test(line))
    .join('\n');
  assert.ok(
    !/HICASSO_HMR_ENGINES/.test(executable),
    'the CI job must not narrow the engine set — the cross-engine comparator '
      + 'is inert below two engines, so a narrowed run passes having checked '
      + 'nothing it exists to check',
  );

  // THE PIN IS STRUCTURAL, exactly as for the controlled-input lane. `npx`
  // resolves the pinned playwright only because the job runs in
  // `implementation` with `npm ci` already done; from the repo root or ahead
  // of `npm ci` it resolves a newer release and prunes the pinned WebKit out
  // of the shared cache, leaving a green job that never launched one of the
  // three engines.
  assert.match(
    block,
    /working-directory: implementation/,
    'the pin depends on the job running in implementation/',
  );
  const npmCi = block.indexOf('run: npm ci');
  const install = block.indexOf('playwright install');
  assert.ok(npmCi !== -1, 'the job must run npm ci');
  assert.ok(
    npmCi < install,
    'npm ci must precede the playwright install, or npx resolves an unpinned Playwright',
  );

  // The watch is a real shadow-cljs process. Without a JDK the gate cannot
  // start at all, and this is the one browser lane that needs one.
  assert.match(
    block,
    /actions\/setup-java@/,
    'the gate starts a real `shadow-cljs watch`, which needs a JDK',
  );
});

test('the hicasso HMR lane arms on its tree, its launcher and the build config (rf2-hic-015)', () => {
  for (const file of [
    HICASSO_HMR.spec,
    HICASSO_HMR.hotFile,
    HICASSO_HMR.page,
    HICASSO_HMR.launcher,
    // The trio: shadow-cljs.edn declares BOTH the `:hicasso/hmr-testbed` build
    // and the `:dev-http` on 8061 that serves it — this gate is served by
    // shadow's own dev server so the document and the devtools websocket share
    // an origin — and the playwright pin in package.json / the lockfile IS the
    // three engine revisions under test.
    'implementation/shadow-cljs.edn',
    'implementation/package.json',
    'implementation/package-lock.json',
  ]) {
    assert.ok(
      fs.existsSync(path.join(REPO_ROOT, file)),
      `${file} must exist — a row pinning a phantom path is vacuous`,
    );
    assert.equal(
      classify(file)[HICASSO_HMR.output],
      'true',
      `${file} must arm ${HICASSO_HMR.output}`,
    );
  }
  // The launcher case sits above the generic implementation/scripts/* case, so
  // it must not narrow what that case already gave the file.
  const launcher = classify(HICASSO_HMR.launcher);
  for (const output of [
    'cljs_node_test',
    'cljs_browser',
    'cljs_prod',
    'bundle_isolation',
    'reagent_slim_bundle',
  ]) {
    assert.equal(
      launcher[output],
      'true',
      `${HICASSO_HMR.launcher} must still arm ${output}`,
    );
  }
});

test('a hicasso RUNTIME diff arms the HMR lane (rf2-hic-015)', () => {
  // The point of the arm. A reload re-evaluates the package's own namespaces,
  // so the files whose behaviour the contract is ABOUT must reach the only
  // lane that witnesses a real reload — not just the testbed that demonstrates
  // it. Real paths, read off the tree.
  for (const file of [
    'implementation/hicasso/src/re_frame/hicasso.cljc',
    'implementation/hicasso/test/re_frame/hicasso/hmr_remount_cljs_test.cljs',
    'implementation/hicasso/test/re_frame/hicasso/hmr_registry_cljs_test.cljs',
  ]) {
    assert.ok(
      fs.existsSync(path.join(REPO_ROOT, file)),
      `${file} must exist — a row pinning a phantom path is vacuous`,
    );
    assert.equal(classify(file)[HICASSO_HMR.output], 'true', file);
  }
});

test('the hicasso HMR lane stays dark for unrelated surfaces (rf2-hic-015)', () => {
  // A rule that matches everything is as useless as one that matches nothing,
  // and this gate is the most expensive one in the matrix: a watch plus three
  // engine sessions. `implementation/core` matters most — it fans out to
  // almost every other output, and an edge from it would put a 40-minute
  // reload gate on nearly every PR in the repo.
  for (const file of [
    'implementation/core/src/re_frame/core.cljc',
    'implementation/ui/src/re_frame/ui.cljs',
    'spec/006-ReactiveSubstrate.md',
    'tools/xray/src/day8/re_frame2_xray/core.cljs',
    'migration/reagent-to-hicasso/codemod/deps.edn',
  ]) {
    assert.equal(
      classify(file)[HICASSO_HMR.output],
      'false',
      `${file} has no edge into the hicasso HMR gate`,
    );
  }
  // …and the two hicasso browser lanes are DISTINCT tiers, not aliases: the
  // controlled-input launcher must not drag the reload gate along with it.
  assert.equal(
    classify('implementation/scripts/serve-and-run-hicasso-controlled-testbed.cjs')[
      HICASSO_HMR.output
    ],
    'false',
    'the controlled-input launcher must not arm the HMR gate',
  );
  assert.equal(
    classify(HICASSO_HMR.launcher).hicasso_controlled,
    'false',
    'the HMR launcher must not arm the controlled-input gate',
  );
});

// rf2-8a6s — the regression that would have caught this arm going stale.
//
// rf2-8a6s originally set `cljs_node_test` for `implementation/hicasso/*` and
// deliberately NOT `cljs_browser`, on a premise that was true when written:
// the package owned no `-dom-cljs-test$` namespace, so the browser lane would
// have run not one line of it. That premise EXPIRED when rf2-hic-010 and
// rf2-hic-012 landed DOM suites, and nothing noticed — a rule with no
// regression is exactly how a narrowing goes stale in silence.
//
// The failure mode was worse than a skipped job. `:browser-test` selected
// `^(?!re-frame\.freehand\.bench\.).*-dom-cljs-test$` at the time — it is
// plain `.*-dom-cljs-test$` now that tree has gone (rf2-0yp7w) — so these
// namespaces were already in the browser lane; the lane simply never ran on
// a diff that touched them, while the consolidated node build compiled the
// same namespaces and reported each DOM row as a STATED GREEN SKIP. The
// surface passed having executed none of its DOM assertions.

const HICASSO_DOM_TESTS = [
  'implementation/hicasso/test/re_frame/hicasso/kernel_commit_owns_dom_cljs_test.cljs',
  'implementation/hicasso/test/re_frame/hicasso/roots_frames_hydration_dom_cljs_test.cljs',
  'implementation/hicasso/test/re_frame/hicasso/roots_frames_isolation_dom_cljs_test.cljs',
];

test('a hicasso DOM-test diff lights the browser job (rf2-8a6s)', () => {
  for (const file of HICASSO_DOM_TESTS) {
    assert.equal(
      classify(file).cljs_browser,
      'true',
      `${file} is selected by the :browser-test build and must arm cljs_browser`,
    );
  }
});

test('widening hicasso to cljs_browser did not cost it the node lane (rf2-8a6s)', () => {
  // The reviewer's constraint, pinned: cljs_browser is IN ADDITION TO
  // cljs_node_test, not instead of it. `cljs_node_test` is the only output
  // that schedules the package smoke and the freeze gate, and the browser
  // lane runs neither, so trading one for the other would close this hole by
  // opening two.
  for (const file of [...HICASSO_DOM_TESTS, HICASSO_CONTROLLED.spec, HICASSO_CONTROLLED.src]) {
    const result = classify(file);
    assert.equal(result.cljs_node_test, 'true', `${file} must still arm cljs_node_test`);
    assert.equal(result.cljs_browser, 'true', `${file} must arm cljs_browser`);
  }
});

test('the hicasso DOM suites really are in the browser lane (rf2-8a6s)', () => {
  // NON-VACUITY. Arming cljs_browser is worth nothing unless the job it
  // schedules actually selects these namespaces, so this does not assert that
  // in prose: it lifts the SELECTOR out of shadow-cljs.edn and runs it against
  // the namespaces derived from the files themselves. Narrow the selector, or
  // rename a suite out of the pattern, and the classifier arm becomes a lie —
  // this row is what says so.
  const shadow = fs.readFileSync(path.join(IMPL_ROOT, 'shadow-cljs.edn'), 'utf8');
  const header = /\n {2}:browser-test\r?\n/.exec(shadow);
  assert.notEqual(header, null, ':browser-test build not found in shadow-cljs.edn');
  const rest = shadow.slice(header.index + 1);
  const nextBuild = rest.search(/\n {2}:[A-Za-z]/);
  const build = nextBuild === -1 ? rest : rest.slice(0, nextBuild);

  const m = /:ns-regexp\s+"((?:[^"\\]|\\.)*)"/.exec(build);
  assert.notEqual(m, null, ':browser-test must declare an :ns-regexp');
  // EDN string escaping: `\\.` in the file is one backslash + a dot.
  const selector = new RegExp(m[1].replace(/\\\\/g, '\\'));

  for (const file of HICASSO_DOM_TESTS) {
    assert.ok(fs.existsSync(path.join(REPO_ROOT, file)), `${file} must exist`);
    const ns = file
      .replace('implementation/hicasso/test/', '')
      .replace(/\.cljs$/, '')
      .replace(/_/g, '-')
      .replace(/\//g, '.');
    assert.ok(
      selector.test(ns),
      `${ns} must be selected by :browser-test's ${selector} for cljs_browser to mean anything`,
    );
  }
});

// rf2-kxork — G-18 library facade isolation promoted into the required matrix.
// The checker was donated RED (#6182) and parked outside CI; #6195 repaired the
// DCE mechanism and it now passes, so it becomes a standing regression net.
// These three tests are the wiring's own proof: the classifier must arm the
// gate, the job must be surface-gated and actually run it, and the required
// aggregator must depend on it. Remove any one of those and a test reds.

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

// The same file classify() spawns, as an absolute path: this branch READS the
// script's bytes to pipe into a scratch repo rather than executing it in place.
// Derived from the one constant so the two cannot drift apart.
const SCRIPT_PATH = path.join(REPO_ROOT, SURFACES_SCRIPT);

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

// ─── rf2-34yg — THE LAUNCHER'S ENV TRANSPORT ────────────────────────────────
//
// The three environment values the git-discovery tests below CONTROL. Every one
// of them is read by report-changed-surfaces.sh's base-resolution block, and a
// test that sets one and has it arrive empty does not fail — it silently
// exercises the HEAD^ fallback instead, which looks exactly like correct
// behaviour.
const LAUNCHER_TRANSPORTED_ENV = Object.freeze([
  'GITHUB_EVENT_NAME',
  'GITHUB_BASE_REF',
  'CHANGED_SURFACES_BASE_REF',
]);

/**
 * The environment to hand `execFileSync('bash', ...)` so the child actually
 * SEES the values above.
 *
 * WHY THIS EXISTS. On Windows, `bash` is resolved by the OS from PATH, and
 * which bash.exe answers is not this suite's choice: `C:\Windows\System32\
 * bash.exe` (the WSL launcher) ships with Windows and sits in System32, ahead
 * of `C:\Program Files\Git\bin\bash.exe` on a default PowerShell PATH — on the
 * host this was found, Git Bash is not on that PATH at all. WSL does NOT
 * inherit the Windows environment: it imports only the variables NAMED in
 * WSLENV. So `env:` values handed to the child crossed into Git Bash and into
 * Linux CI, and vanished into WSL. Measured directly, before this fix: all
 * three read empty in the child, the classifier fell back to HEAD^ exactly as
 * it is designed to when handed no base, and two of the rf2-34yg push cases
 * went red while the other three went green FOR THE WRONG REASON — they assert
 * `false`, which is also what a dropped base produces. The identical suite
 * passed all 354 cases under Git Bash. A gate whose verdict depends on which
 * bash.exe the OS finds first is not a gate, and the failing direction is the
 * quiet one.
 *
 * WHY WSLENV AND NOT A WRAPPER. The audit offered two shapes — extend WSLENV,
 * or wrap the script in an argv/stdin `export` preamble. WSLENV was measured
 * sufficient (all three arrive with their exact supplied values), so the
 * wrapper is not built: it would need shell quoting for values that reach the
 * child today without any, which is the hand-rolled quoting `classify()` above
 * was deliberately retired to avoid.
 *
 * PRESERVES GIT BASH AND LINUX. The WSLENV entry is added only on win32, and
 * even there it is inert for a Git Bash child (verified: the values cross with
 * or without it, WSLENV being meaningless to msys2). On Linux — every CI
 * runner — the env is returned untouched.
 *
 * NAMES ONLY WHAT IS SET, which is not a micro-optimisation. WSLENV naming a
 * variable ABSENT from the Windows environment makes WSL export it as the
 * EMPTY STRING rather than leaving it unset (verified). The callers below
 * `delete` GITHUB_EVENT_NAME and GITHUB_BASE_REF precisely to make the child
 * see them unset, so naming them unconditionally would hand WSL a different
 * environment from the one Git Bash and Linux get. Filtering to the values
 * actually present keeps all three launchers byte-equivalent for this script.
 *
 * `platform` is a parameter, not a read of `process.platform`, so the win32
 * branch is unit-testable from a Linux runner — which is the only reason CI
 * can protect a Windows-only code path at all.
 */
function launcherEnv(env, platform = process.platform) {
  if (platform !== 'win32') return env;
  const present = LAUNCHER_TRANSPORTED_ENV.filter((name) => env[name] !== undefined);
  if (present.length === 0) return env;
  // A WSLENV entry is `NAME` or `NAME/flags`; the flags request path
  // translation, which none of these want (a SHA, a branch name and an event
  // name are not paths). Existing entries are kept — the ambient WSLENV is the
  // terminal's, and dropping it would change unrelated behaviour.
  const entries = String(env.WSLENV || '')
    .split(':')
    .filter(Boolean);
  const named = new Set(entries.map((entry) => entry.split('/')[0]));
  for (const name of present) {
    if (!named.has(name)) entries.push(name);
  }
  return { ...env, WSLENV: entries.join(':') };
}

// Build a real two-commit repo via `buildHistory`, then invoke the script in
// its Git-derived (local, HEAD^ HEAD) discovery mode with NO explicit paths and
// return the parsed classifier outputs. GITHUB_* is cleared so the script takes
// the local branch and prints to stdout.
// `envFor` (rf2-34yg) is an OPTIONAL callback run after the history is built
// and before the script is invoked; it returns extra environment for the run.
// It is a callback rather than a plain object because the interesting variable
// — the push's accepted base — is a SHA that does not exist until
// `buildHistory` has made the commits.
function classifyViaGitDiscovery(buildHistory, envFor) {
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
    // Applied LAST, so a test can set what the deletions above cleared —
    // notably GITHUB_EVENT_NAME=push plus the accepted base (rf2-34yg).
    if (envFor) {
      Object.assign(
        env,
        envFor({ root: tmp, git: (...args) => gitIn(tmp, ...args) }),
      );
    }
    const out = execFileSync('bash', ['-s'], {
      cwd: tmp,
      // rf2-34yg — `launcherEnv`, not `env`. On a WSL launcher the three values
      // above do not cross into the child unless WSLENV names them, and a base
      // that arrives empty silently demotes this to the HEAD^ fallback.
      env: launcherEnv(env),
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

// A CLASSIFIED first-party source, used purely as the subject of the
// git-DISCOVERY tests below (rename splitting, push-base resolution). What
// matters is only that the path is classified and its gate keys fire — the
// artefact behind it is incidental, so it was repointed from the retired
// re-frame.ui tree to the Reagent adapter (rf2-0yp7w.4) without changing what
// either test proves.
const CLASSIFIED_SOURCE = 'implementation/adapters/reagent/src/re_frame/adapter/reagent.cljs';
const DISCOVERY_GATE_KEYS = ['implementation_jvm', 'cljs_node_test', 'cljs_browser', 'cljs_prod'];

test('DISCOVERY: docs->docs rename does NOT arm UI gates (no spurious firing) (rf2-vxgfnd.137)', () => {
  // Both endpoints are unclassified — --no-renames must not manufacture a UI
  // classification (guards against over-firing / mis-splitting a rename).
  const result = renameViaGitDiscovery('docs/a.md', 'docs/b.md');
  for (const key of DISCOVERY_GATE_KEYS) {
    assert.equal(result[key], 'false', `a docs->docs rename must NOT arm ${key}`);
  }
});

// Ordinary add / modify / delete via the SAME Git-derived discovery mode stay
// classified exactly as before — --no-renames only changes how renames surface.

// A three-commit "push": a classified file lands in commit 1 and is untouched
// by commits 2 and 3, so it is present on both sides of a HEAD^ diff and
// escapes — exactly the shape the two measured merges had.
function pushHistory({ write, commit }) {
  write('README.md', '# scratch\n');
  commit('base — the tip main pointed at BEFORE the push');
  write(CLASSIFIED_SOURCE, '(ns re-frame.adapter.reagent)\n');
  commit('push commit 1 — the classified change');
  write('docs/a.md', '# a\n');
  commit('push commit 2 — docs only');
  write('docs/b.md', '# b\n');
  commit('push commit 3 — the TIP, docs only');
}

test('PUSH: a multi-commit push classifies over the WHOLE push, not the tip (rf2-34yg)', () => {
  const result = classifyViaGitDiscovery(pushHistory, ({ git }) => ({
    GITHUB_EVENT_NAME: 'push',
    // The accepted base: the tip main pointed at before the push, i.e.
    // `github.event.before`. Three commits back from the tip.
    CHANGED_SURFACES_BASE_REF: git('rev-parse', 'HEAD~3').toString().trim(),
  }));
  for (const key of DISCOVERY_GATE_KEYS) {
    assert.equal(
      result[key],
      'true',
      `a UI change in commit 1 of a 3-commit push must arm ${key} on the ` +
        'push run — reverting the base to HEAD^ makes this fail',
    );
  }
});

test('PUSH: the CONTROL — HEAD^ alone misses that same change (rf2-34yg)', () => {
  // The defect itself, pinned. Same history, no accepted base, so the script
  // takes its HEAD^ default and sees only the docs tip. If this ever starts
  // arming the UI gates the test above has stopped proving anything.
  const result = classifyViaGitDiscovery(pushHistory);
  for (const key of DISCOVERY_GATE_KEYS) {
    assert.equal(
      result[key],
      'false',
      `HEAD^ sees only the docs tip, so ${key} stays false — this is the ` +
        'defect rf2-34yg fixes, kept as the control for the test above',
    );
  }
});

test('PUSH: the all-zeros sentinel folds back to HEAD^ (first push to a ref) (rf2-34yg)', () => {
  // A first push to a fresh ref carries an all-zeros `before`. There is no
  // earlier state to have missed, so HEAD^ loses nothing — but it must not
  // be passed to `git diff` as a literal ref. post-merge-workflow-sanity.yml
  // folds it the same way.
  const result = classifyViaGitDiscovery(pushHistory, () => ({
    GITHUB_EVENT_NAME: 'push',
    CHANGED_SURFACES_BASE_REF: '0'.repeat(40),
  }));
  for (const key of DISCOVERY_GATE_KEYS) {
    assert.equal(result[key], 'false', `all-zeros must fold to HEAD^, not fail (${key})`);
  }
});

test('PUSH: an UNRESOLVABLE base arms everything rather than skipping (rf2-34yg)', () => {
  // The force-push case: `before` is the DISCARDED tip, reachable from no ref,
  // so even a full clone need not hold it. A classifier's failure mode is a
  // false GREEN, so its fail-closed is `mark_all` — never a silent return to
  // HEAD^, which would reproduce the defect above.
  const result = classifyViaGitDiscovery(pushHistory, () => ({
    GITHUB_EVENT_NAME: 'push',
    CHANGED_SURFACES_BASE_REF: 'dead0000'.repeat(5),
  }));
  for (const key of DISCOVERY_GATE_KEYS) {
    assert.equal(
      result[key],
      'true',
      `an unresolvable base must arm ${key} — a base it cannot see must not ` +
        'let it skip gates',
    );
  }
  // Not merely the UI subset: this is mark_all, so every output is true.
  const falses = Object.entries(result).filter(([, v]) => v !== 'true');
  assert.deepEqual(falses, [], 'an unresolvable base must arm the FULL matrix');
});

test('PUSH: a pull_request is unaffected — base...HEAD still wins (rf2-34yg)', () => {
  // The PR branch was always correct and is deliberately untouched. Even with
  // a base ref present in the environment, a pull_request event must not take
  // the push branch. GITHUB_BASE_REF is absent here, so the PR branch's own
  // guard sends this to the HEAD^ default rather than to the push branch.
  const result = classifyViaGitDiscovery(pushHistory, () => ({
    GITHUB_EVENT_NAME: 'pull_request',
  }));
  for (const key of DISCOVERY_GATE_KEYS) {
    assert.equal(result[key], 'false', `a pull_request must not take the push branch (${key})`);
  }
});

// ─── rf2-34yg — THE TRANSPORT ITSELF, PINNED ────────────────────────────────
//
// Every push case above SUPPLIES a base and then reads the classifier's
// verdict. That is an indirect measurement: if the base never reaches the
// child, the classifier does the right thing with the nothing it was given and
// falls back to HEAD^ — so three of the five cases stay green while proving
// nothing at all. These four pin the transport DIRECTLY, and positively: the
// child is asked what it received, and must say the exact value handed to it.

// Ask the child shell what it can see. Reports the shell family too, because
// WSL and Git Bash disagree about this and the disagreement IS the defect.
const LAUNCHER_PROBE = ['printf "uname=%s\\n" "$(uname -s)"']
  .concat(LAUNCHER_TRANSPORTED_ENV.map((name) => `printf "${name}=%s\\n" "\${${name}:-}"`))
  .join('\n');

function probeLauncher(env) {
  const out = execFileSync('bash', ['-s'], { env, encoding: 'utf8', input: `${LAUNCHER_PROBE}\n` });
  return Object.fromEntries(
    out
      .trim()
      .split(/\r?\n/)
      .filter(Boolean)
      .map((line) => {
        const at = line.indexOf('=');
        return [line.slice(0, at), line.slice(at + 1)];
      }),
  );
}

const PROBE_BASE = 'feedfacefeedfacefeedfacefeedfacefeedface';

test('LAUNCHER: the child actually RECEIVES the supplied base (rf2-34yg — the pin)', () => {
  // THE POSITIVE ASSERTION the push cases cannot make. Not "the outputs
  // changed", but "the child echoed back the exact 40 hex characters we handed
  // it". Red under WSL before the WSLENV extension, green under Git Bash and
  // Linux with or without it.
  const seen = probeLauncher(
    launcherEnv({
      ...process.env,
      GITHUB_EVENT_NAME: 'push',
      GITHUB_BASE_REF: 'main',
      CHANGED_SURFACES_BASE_REF: PROBE_BASE,
    }),
  );
  assert.equal(
    seen.CHANGED_SURFACES_BASE_REF,
    PROBE_BASE,
    `the child shell (${seen.uname}) must see the supplied base — an empty ` +
      'value here means every push case above is silently exercising the HEAD^ ' +
      'fallback instead of the branch it names',
  );
  assert.equal(seen.GITHUB_EVENT_NAME, 'push', 'the event name must cross too, or the push branch is never taken');
  assert.equal(seen.GITHUB_BASE_REF, 'main', 'the third transported value must cross');
});

test('LAUNCHER: the CONTROL — with the transport removed, WSL drops the base (rf2-34yg)', () => {
  // The property removed. `launcherEnv` is bypassed AND the ambient WSLENV is
  // cleared, so this is "no transport at all" rather than "whatever this
  // terminal happens to export". Both branches assert something real: the fix
  // is necessary on exactly one of the three launchers, and this says which.
  const raw = {
    ...process.env,
    GITHUB_EVENT_NAME: 'push',
    GITHUB_BASE_REF: 'main',
    CHANGED_SURFACES_BASE_REF: PROBE_BASE,
  };
  delete raw.WSLENV;
  const seen = probeLauncher(raw);
  const isWsl = process.platform === 'win32' && seen.uname === 'Linux';
  if (isWsl) {
    assert.equal(
      seen.CHANGED_SURFACES_BASE_REF,
      '',
      'a WSL launcher imports ONLY what WSLENV names — if the base crosses ' +
        'without it, the pin above has stopped measuring anything',
    );
  } else {
    assert.equal(
      seen.CHANGED_SURFACES_BASE_REF,
      PROBE_BASE,
      `a ${seen.uname} launcher inherits the environment directly and needs no ` +
        'transport — the WSLENV extension must stay inert here, not become a ' +
        'dependency',
    );
  }
});

test('LAUNCHER: launcherEnv names every transported value in WSLENV on win32 (rf2-34yg)', () => {
  // THE MECHANISM, unit-tested through the `platform` parameter so a Linux CI
  // runner — which never spawns a WSL launcher and would pass the two cases
  // above no matter what — still protects the Windows-only branch.
  const win = launcherEnv(
    {
      WSLENV: 'WT_SESSION:WT_PROFILE_ID:',
      GITHUB_EVENT_NAME: 'push',
      GITHUB_BASE_REF: 'main',
      CHANGED_SURFACES_BASE_REF: PROBE_BASE,
    },
    'win32',
  );
  const named = win.WSLENV.split(':').filter(Boolean);
  for (const name of LAUNCHER_TRANSPORTED_ENV) {
    assert.ok(
      named.includes(name),
      `${name} must be named in WSLENV verbatim — bare, because a /p flag ` +
        'would path-translate a SHA, a branch name or an event name',
    );
  }
  assert.ok(named.includes('WT_SESSION'), 'the ambient WSLENV must be preserved, not replaced');

  // Idempotent: the suite builds this env once per spawn, but a nested call
  // must not grow WSLENV by re-naming what is already there.
  assert.deepEqual(launcherEnv(win, 'win32').WSLENV, win.WSLENV, 'launcherEnv must be idempotent');

  // An ABSENT value is not named, and that is load-bearing: WSLENV exports a
  // named-but-unset variable as the EMPTY STRING, where Git Bash and Linux
  // leave it unset. classifyViaGitDiscovery `delete`s GITHUB_EVENT_NAME and
  // GITHUB_BASE_REF precisely so the child sees them unset.
  const partial = launcherEnv({ CHANGED_SURFACES_BASE_REF: PROBE_BASE }, 'win32');
  assert.deepEqual(
    partial.WSLENV.split(':').filter(Boolean),
    ['CHANGED_SURFACES_BASE_REF'],
    'only values actually present may be named, or a deleted variable comes ' +
      'back as an empty string on WSL and the three launchers stop agreeing',
  );

  // Off win32 the env is returned by IDENTITY — every CI runner is Linux and
  // must be byte-identical to what it saw before this fix.
  const posix = { GITHUB_EVENT_NAME: 'push', CHANGED_SURFACES_BASE_REF: PROBE_BASE };
  assert.equal(launcherEnv(posix, 'linux'), posix, 'non-win32 platforms must be untouched');
  assert.equal(launcherEnv(posix, 'darwin'), posix, 'non-win32 platforms must be untouched');
});

test('LAUNCHER: the discovery launcher goes THROUGH launcherEnv (rf2-34yg — the caller half)', () => {
  // The caller half, for the same reason the test.yml `env:` case below exists:
  // reverting `env: launcherEnv(env)` to `env` restores the defect with every
  // Linux CI case still green, because on Linux the two are the same value.
  // Reading the function's own source is the only assertion that survives that.
  assert.match(
    classifyViaGitDiscovery.toString(),
    /env:\s*launcherEnv\(/,
    'classifyViaGitDiscovery must hand its child the launcherEnv-wrapped ' +
      'environment, or the base silently stops crossing on a WSL launcher',
  );
});

test('test.yml hands the classifier the accepted base via env: (rf2-34yg)', () => {
  // THE CALLER HALF. The script cannot read `github.event.before` on its own —
  // Actions exports no such variable — so the fix is inert unless test.yml
  // passes it. Dropping this `env:` would silently restore tip-only
  // classification with every test above still green, because they supply the
  // base themselves.
  const block = jobBlock(fs.readFileSync(WORKFLOW, 'utf8'), 'detect_changed_surfaces');
  assert.match(
    block,
    /CHANGED_SURFACES_BASE_REF:\s*\$\{\{\s*github\.event\.before\s*\}\}/,
    'detect_changed_surfaces must pass github.event.before as ' +
      'CHANGED_SURFACES_BASE_REF, or a multi-commit push is classified on its ' +
      'tip alone (rf2-34yg)',
  );
  // Via `env:`, never interpolated into the run body — portability.yml's rule
  // for the same context value, so the step is injection-safe.
  assert.doesNotMatch(
    block,
    /run:[\s\S]*\$\{\{\s*github\.event\.before/,
    'the base must arrive through env:, not interpolated into the script body',
  );
  // The base can be arbitrarily deep; a shallow checkout would not hold it.
  assert.match(
    block,
    /fetch-depth:\s*0/,
    'the accepted base can be arbitrarily deep — this job needs full history',
  );
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
        + 'anything covering this artefact (the :node-test smoke, the '
        + 'invariants gate, the modules compile), and without it a '
        + 'hicasso-only PR runs none of them',
    );
  }
});

test('the hicasso arm is ARTEFACT-ROOT matching, not an enumeration (rf2-8a6s)', () => {
  // Same reasoning the retired freehand case once carried:
  // `implementation/hicasso/*)` is
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
  //   implementation_jvm — NOT because the artefact has no JVM lane. It has
  //     one: rf2-ipx7h put `implementation/hicasso` on
  //     scripts/test-jvm-implementation.sh and added the required
  //     `jvm-hicasso` job, and its `:test` alias dropped `--probe` and took
  //     the test-count floor. The reason this row survives is that the job is
  //     UNCONDITIONAL, so it needs no arm — and arming this root would be
  //     actively wrong: 22 OTHER jobs read `implementation_jvm`, so every
  //     hicasso-only diff would schedule all of them to run one five-second
  //     one-namespace lane. The release condition that used to be written here
  //     ("arm it the same commit a JVM-runnable suite lands") is therefore
  //     RETIRED rather than pending; the pin that replaces it is the
  //     `jvm-hicasso is UNCONDITIONAL` test below.
  //   cljs_prod — no `-elision-prod-test$` namespace.
  //   bundle_isolation — no example resolves the
  //     artefact and it mounts no testbed those smokes drive.
  //
  // `cljs_browser` USED TO BE ON THIS LIST, and its removal is the point of
  // the rf2-8a6s widening. The entry read "hicasso IS on the :browser-test
  // classpath, but that build selects `-dom-cljs-test$` and the package owns
  // no such namespace" — true when written, and false from the moment
  // rf2-hic-010 and rf2-hic-012 landed three such namespaces. This row is
  // where the stale premise was pinned, so this row is where the correction
  // belongs; the arm is now asserted positively by the rf2-8a6s block above.
  const result = classify('implementation/hicasso/src/re_frame/hicasso.cljc');
  for (const key of [
    'implementation_jvm',
    'cljs_prod',
    'bundle_isolation',
  ]) {
    assert.equal(result[key], 'false', `hicasso must not arm ${key}`);
  }
});

// ---------------------------------------------------------------------------
// rf2-ipx7h — the hicasso JVM lane, and why it carries no surface gate.
//
// `implementation/hicasso/test/re_frame/hicasso/slot_cljs_test.cljc`
// is the `.cljc` EQUIVALENCE PIN for the canonical slot rule (rf2-ani6y): one
// corpus asserted twice against ONE implementation, once by `npm run test:cljs`
// in Node and once by `clojure -M:test` on the JVM. Both arms or no mechanism —
// a reader conditional inside the rule, or the JVM's locale-sensitive
// `str/upper-case`, is invisible to either host alone.
//
// The three rows below are the MEASUREMENT that decided against gating this
// job on `implementation_jvm`. Each is a file on the lane's JVM classpath that
// does not arm that output, so each is a PR shape that would have skipped the
// job — and `deps.edn` is the sharpest, because it is the file that decides
// whether the pin is discovered AT ALL. They must NOT be "fixed" by widening
// `implementation_jvm` for the artefact root: 22 other jobs read it, and the
// scope guard above says so. The repair is the unconditional job asserted
// underneath.
// ---------------------------------------------------------------------------

test('the hicasso JVM lane has classpath inputs that arm NO jvm tier (rf2-ipx7h)', () => {
  for (const file of [
    // the `:test` alias itself — `:extra-paths`, `:extra-deps`, `:main-opts`
    'implementation/hicasso/deps.edn',
    // also on `:extra-paths`, so also scanned for discovery
    'implementation/hicasso/test_kit/src/re_frame/hicasso/test.cljs',
  ]) {
    const result = classify(file);
    assert.equal(
      result.implementation_jvm,
      'false',
      `${file} is on the hicasso JVM lane's classpath and arms no jvm tier — `
        + 'which is why jvm-hicasso is unconditional rather than gated on '
        + 'implementation_jvm',
    );
  }
});

test('the slot pin arms implementation_jvm only INCIDENTALLY (rf2-ipx7h)', () => {
  // The pin DOES measure true — but through `is_route_path_census_input`, a
  // predicate that exists for the routing route-path census and matches
  // `implementation/hicasso/test/*` `.cljs`/`.cljc`. The `.clj` control below
  // is what makes that legible: same tree, same artefact, FALSE, because the
  // census filters on the extensions IT cares about. So the arm belongs to
  // another gate's roster and could narrow with it — a second reason this
  // job takes no gate at all. (The pin's SUBJECT, `impl/slot.cljc`, sits
  // under `src/` and measures false like the rest of the package — the
  // rf2-8a6s block above pins that; since rf2-6c12m.1 the pin requires the
  // package rule directly rather than the bench tree's twin.)
  assert.equal(
    classify('implementation/hicasso/test/re_frame/hicasso/slot_cljs_test.cljc').implementation_jvm,
    'true',
  );
  assert.equal(
    classify('implementation/hicasso/test/re_frame/hicasso/expansion_probe.clj')
      .implementation_jvm,
    'false',
    'the .clj control must measure false — the arm is the census predicate, '
      + 'not a hicasso JVM arm',
  );
});

test('a bench-lane diff is CLASSIFIED to no gate, not left unclassified (rf2-6c12m.1)', () => {
  // The Hicasso bench lane is a hand-run shadow-cljs project off every per-PR
  // lane by ruling: its suites exercise LOCAL COPIES of the runtime, so
  // running them per PR could not catch a regression in the shipped one. The
  // classifier carries an explicit `bench/*` arm that sets NOTHING, so the
  // silence is stated rather than a hole (TESTING.md §Changed-surface
  // classifier). This pins every output false for the shapes a bench-only
  // diff takes; the tree's own gate is `npm run check` from bench/hicasso/.
  for (const file of [
    'bench/hicasso/src/re_frame/bench/hicasso/lane.cljs',
    'bench/hicasso/src/re_frame/bench/hicasso/run.cjs',
    'bench/hicasso/src/re_frame/bench/hicasso/data/alloc-c4hhk/run01.json',
    'bench/hicasso/shadow-cljs.edn',
  ]) {
    const result = classify(file);
    assert.ok(Object.keys(result).length > 0, `${file} produced no outputs at all`);
    for (const [key, value] of Object.entries(result)) {
      assert.equal(value, 'false', `${file} must arm nothing, but ${key} read ${value}`);
    }
  }
});

test('jvm-hicasso is UNCONDITIONAL, rostered and required (rf2-ipx7h)', () => {
  const workflow = fs.readFileSync(WORKFLOW, 'utf8');
  const block = jobBlock(workflow, 'jvm-hicasso');
  // Job-level keys sit at exactly four spaces; anchoring there keeps a prose
  // comment mentioning `needs:` from reading as the key itself.
  assert.doesNotMatch(
    block,
    /^ {4}needs:/m,
    'jvm-hicasso must not depend on detect_changed_surfaces — the lane\'s own '
      + 'deps.edn arms no classifier output',
  );
  assert.doesNotMatch(
    block,
    /^ {4}if:/m,
    'jvm-hicasso must carry no surface gate; implementation_jvm does not cover '
      + 'this lane\'s inputs and arming it would schedule 22 other jobs',
  );
  assert.match(
    block,
    /^ {8}working-directory: implementation\/hicasso$/m,
    'the job must run in the artefact directory',
  );
  assert.match(block, /run: clojure -M:test$/m, 'the job must run the JVM lane');
  // Required, not advisory: a job absent from the aggregator's `needs:` is
  // advisory whatever its own gate says, and check_jvm_lane_rosters.py R1
  // refuses the roster entry without this line.
  assert.match(
    jobBlock(workflow, 'all-required-passed'),
    /^ {6}- jvm-hicasso$/m,
    'jvm-hicasso must be in all-required-passed\'s needs',
  );
  // The local half of the same bijection. R1/R2 check this too, but a reader
  // of THIS file should not have to run a Python gate to learn that the lane
  // has a local lane as well as a hosted one.
  assert.match(
    fs.readFileSync(path.join(REPO_ROOT, 'scripts', 'test-jvm-implementation.sh'), 'utf8'),
    /^ {2}implementation\/hicasso$/m,
    'implementation/hicasso must be on the local JVM roster',
  );
});

test('the cljs job runs BOTH hicasso gates the classifier arm schedules (rf2-8a6s)', () => {
  // The gate half of the classifier rule. The arm above is worthless if the
  // job it lights stops running the artefact's checks, and the invariants
  // gate in particular has no other scheduled home — before rf2-8a6s it ran
  // only by hand.
  const block = jobBlock(fs.readFileSync(WORKFLOW, 'utf8'), 'cljs');
  assert.match(
    block,
    /run: npm run test:hicasso-invariants$/m,
    'the cljs job must run the hicasso invariants gate (optional-module '
      + 'reachability with its no-bench-import row and the other static reads '
      + 'chained there); it runs nowhere else',
  );
  assert.match(
    block,
    /run: npm run test:hicasso-compile$/m,
    'the cljs job must keep running the hicasso modules compile (rf2-2rtt6.73, '
      + 're-homed into the package by rf2-6c12m.1)',
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

// ---------------------------------------------------------------------------
// rf2-drpa3.70 — the browser lane is gated AND required.
//
// The original subject was Freehand's interpreted React emitter and its two
// mounted `*-dom-cljs-test` namespaces, which rode `:browser-test` while
// `cljs-browser` stayed surface-gated — so a Freehand-only PR skipped the only
// lane that could execute them. That subject retired with the tree
// (rf2-0yp7w.6), but the INVARIANT it established did not: arming an output
// helps only if the lane it arms is still gated on that output and still
// reachable from the single required context. The mounted hicasso DOM
// witnesses now ride `:browser-test` on exactly that basis, so the two pins
// below stay.
// ---------------------------------------------------------------------------

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
test('the node lane reaches its jobs through REQUIRED jobs (rf2-49upn)', () => {
  // Arming an output binds nothing unless the lane it arms is still gated on
  // that output AND still reachable from the single required context. The
  // sibling pin above covers cljs-browser; `cljs` is the node lane, and this
  // is the other leg of the same tripod. (Named for the freehand conformance
  // INDEX arm that first needed it; that arm retired with the corpus in
  // rf2-0yp7w.6, but every other armed output still depends on these two
  // facts, so the pin outlives its original caller.)
  const workflow = fs.readFileSync(WORKFLOW, 'utf8');
  assert.match(
    jobBlock(workflow, 'cljs'),
    /if: needs\.detect_changed_surfaces\.outputs\.cljs_node_test == 'true'/,
    'the `cljs` job must stay gated on cljs_node_test, or arming it schedules nothing',
  );
  const aggregator = jobBlock(workflow, 'all-required-passed');
  for (const job of ['cljs', 'cljs-browser']) {
    assert.match(
      aggregator,
      new RegExp(`- ${job}\\r?\\n`),
      `aggregator must list ${job} in needs: — otherwise the census's claim rides an advisory lane`,
    );
  }
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

// ─── rf2-61ar — PROSE THAT A test.yml SUITE PINS ────────────────────────────
//
// The hole: a docs/spec-only diff classified to NOTHING while a growing family
// of suites inside test.yml jobs `slurp` repo prose and assert on its text. PR
// #8068 (the machines-guide rewrite, 13 files, docs-only) skipped jvm-machines
// — the lane pinning the pages it rewrote — and left five assertions across
// four deftests red on `main` until an unrelated PR happened to arm
// implementation_jvm.
//
// The cases below are the two directions the arm has to hold in, and they are
// deliberately written as one-way claims rather than as a frozen census: a
// positive case asserts a pinned path DOES arm, a negative case asserts an
// UNPINNED path does not. Neither shape has to be revisited when the next
// pinned document lands — only when one is deliberately unarmed.

// The measured roster, path -> the suite that reads it -> the job that runs it.
// This is documentation for the reader; the assertions consume the paths only.
const PROSE_PINS_ARMING_JVM = [
  ['docs/machines/parallel-states.md', 'parallel_states_guide_truth_jvm_test.clj', 'jvm-machines'],
  ['docs/machines/concepts.md', 'transition_geometry_terminology_jvm_test.clj', 'jvm-machines'],
  ['docs/api/re-frame.adapter.uix.md', 'scope_ensure_authority_test.clj', 'jvm-core'],
  ['docs/api/re-frame.ssr.md', 'ssr_doc_example_projector_test.clj', 'jvm-ssr'],
  ['docs/ssr/concepts.md', 'ssr_doc_example_node_build_id_test.clj', 'jvm-ssr'],
  ['docs/design/hicasso/product/async-routing-recipes.md', 'recipes/async_nav_doc_test.clj', 'jvm-routing'],
  ['spec/000-Vision.md', 'scope_ensure_authority_test.clj', 'jvm-core'],
  ['spec/005-StateMachines.md', 'transition_geometry_terminology_jvm_test.clj', 'jvm-machines'],
  ['spec/006-ReactiveSubstrate.md', 'slice_memo_lifetime_census_jvm_test.clj', 'jvm-ui'],
  ['spec/009-Instrumentation.md', 'error_catalogue_channel_conformance_test.clj', 'jvm-core'],
  ['spec/012-Routing.md', 'scope_ensure_authority_test.clj', 'jvm-core'],
  ['spec/013-Flows.md', 'scope_ensure_authority_test.clj', 'jvm-core'],
  ['spec/Cross-Spec-Interactions.md', 'destroyed_reason_channel_conformance_test.clj', 'jvm-machines'],
  ['spec/Pattern-FormAction.md', 'ssr_doc_example_form_action_test.clj', 'jvm-ssr'],
  ['spec/Security.md', 'spec_elision_registry_tense_conformance_test.clj', 'jvm-core'],
  ['spec/Spec-Schemas.md', 'six suites in five artefacts', 'jvm-core/-epoch/-machines/-ui'],
  ['spec/Tool-Pair.md', 'spec_elision_registry_tense_conformance_test.clj', 'jvm-core'],
];

test('every measured prose pin arms the JVM tier that runs its suite (rf2-61ar)', () => {
  for (const [file, suite, job] of PROSE_PINS_ARMING_JVM) {
    assert.equal(
      classify(file).implementation_jvm,
      'true',
      `${file} is slurped by ${suite}, which runs in ${job} — it must arm implementation_jvm`,
    );
  }
});

test('prose no suite reads still arms NOTHING — the narrowing (rf2-61ar)', () => {
  // The other direction, and the whole reason this bead did not simply arm the
  // JVM tier on `docs/**`. A lane that fires on everything costs what a lane
  // that never fires costs, one tier over.
  for (const file of [
    'docs/index.md',
    'docs/guide/getting-started.md',
    'docs/hicasso/concepts.md',
    'docs/core/intro.md',
    'docs/api/re-frame.core.md', // 23 of the 25 docs/api pages carry no JVM pin
    // The same narrowing one tree over (rf2-8arzr.6): `concepts.md` carries
    // the Node recipe whose build-id literals ssr_doc_example_node_build_id_
    // test.clj holds together, and the other nine pages in docs/ssr/ carry no
    // JVM pin at all. The arm is the PAGE, not the tree — this is the verdict
    // that says so.
    'docs/ssr/testing.md',
    // A docs/design/** exemplar, which is what the count beside it measures --
    // so this one does NOT follow the guide to docs/core/hicasso/. The chapter
    // it used to name left the tree under rf2-0yp7w; REWRITE-NOTES.md is the
    // file that stayed, and it is unpinned like the rest (rf2-2ein1).
    'docs/design/hicasso/draft-guide/REWRITE-NOTES.md', // 144 docs/design md files, one pinned
    'migration/from-re-frame-v1/README.md',
    'README.md',
  ]) {
    const result = classify(file);
    for (const [key, value] of Object.entries(result)) {
      assert.equal(value, 'false', `${file} must arm nothing, but ${key} fired`);
    }
  }
});

test('prose arms the JVM tier and NO browser/prod/Playwright tier (rf2-61ar)', () => {
  // Markdown cannot change what React puts on a page — the line rf2-drpa3.70
  // drew for `implementation/freehand/*.md`, held here for every prose arm.
  // `spec/Spec-Schemas.md` is the ONE exception and only for cljs_node_test: a
  // JVM macro extracts its schema forms into the `:node-test` build at
  // COMPILE time, so its own case below states that separately.
  const forbidden = ['cljs_browser', 'cljs_prod', 'bundle_isolation',
    'adapter_testbed_smokes', 'story_xray_browser', 'hicasso_controlled', 'playground'];
  for (const [file] of PROSE_PINS_ARMING_JVM) {
    const result = classify(file);
    for (const key of forbidden) {
      assert.equal(result[key], 'false', `${file} must not arm ${key}`);
    }
    assert.equal(result.cljs_node_test, 'false', `${file} must not arm cljs_node_test`);
  }
});

test('spec/Spec-Schemas.md arms the JVM suites and NO CLJS output (rf2-61ar / rf2-63t1i)', () => {
  // It armed `cljs_node_test` until 2026-08-21, on ONE compile-time edge:
  // implementation/core/test/re_frame/observation_schema_extract.clj was a JVM
  // MACRO namespace parsing the ObservationOnChangeFailedTags def form out of
  // this Markdown, and observation_port_cljs_test.cljc pulled it in through
  // `:require-macros`. Both namespaces and the schema went with the internal
  // observation port (rf2-63t1i), so nothing reads this file at
  // macro-expansion time now — every remaining reader is a JVM suite that
  // slurps it at run time.
  const result = classify('spec/Spec-Schemas.md');
  assert.equal(result.implementation_jvm, 'true');
  assert.equal(result.cljs_node_test, 'false', 'no macro-expansion edge reads this file');
  assert.equal(result.cljs_browser, 'false', 'no mounted surface reads this file');
});

test('the docs/machines arm covers the whole TREE (rf2-61ar)', () => {
  // Wider than the two pages named in the roster above, and a judgement rather
  // than a mechanism — the incident's own shape: the red came from a 13-file
  // tree-wide rewrite, concepts.md and parallel-states.md are the terminology
  // spine every other page restates, and unlike docs/api this tree has no
  // other gate at all (docs.yml stages it into the site and executes not one
  // line of the suites that read it).
  //
  // It was TWO trees until rf2-7v5vx. `docs/core/freehand/*.md` was the other,
  // and it was the mechanical case rather than a judgement: the pin WAS the
  // tree, because samples_coverage_jvm_test.clj `file-seq`d the directory and
  // digest-pinned every fenced block on every page. rf2-0yp7w deleted the
  // guide and that roster together, so the arm and these two rows went with
  // them — see the `docs/core` entry in DECLARED_NO_SURFACE_OUTPUT below for
  // where that tree's coverage lives now.
  for (const file of [
    'docs/machines/glossary.md',
    'docs/machines/history.md',
  ]) {
    assert.equal(
      classify(file).implementation_jvm,
      'true',
      `${file} rides its tree's arm — see the case comment for which reason`,
    );
  }
});

test('the spec/* catch-all does not shadow the narrower spec arms (rf2-61ar)', () => {
  // A POSIX `case` takes the FIRST match and `*` spans `/`, so the catch-all
  // would swallow every narrower spec/ case if it were ever moved above them.
  // These are the verdicts that prove it has not been.
  assert.equal(classify('spec/API.md').implementation_jvm, 'false',
    'spec/API.md keeps its cljs_node_test-only classification (rf2-4ka7c2.1)');
  assert.equal(classify('spec/API.md').cljs_node_test, 'true');
  assert.equal(classify('spec/api-manifest.edn').cljs_node_test, 'true');
  assert.equal(classify('spec/conformance/fixtures/dispatch.edn').cljs_browser, 'true',
    'the shared conformance corpus keeps all four outputs (rf2-qmiiz)');
});

test('the prose arms reach lanes that are still gated on implementation_jvm (rf2-61ar)', () => {
  // Arming an output binds nothing unless the jobs it arms are still gated on
  // it — the same third leg rf2-49upn's index case asserts. These are the
  // jobs the roster's suites actually run in.
  const workflow = fs.readFileSync(WORKFLOW, 'utf8');
  for (const job of ['jvm-machines', 'jvm-core']) {
    assert.match(
      jobBlock(workflow, job),
      /if: needs\.detect_changed_surfaces\.outputs\.implementation_jvm == 'true'/,
      `${job} must stay gated on implementation_jvm, or arming it schedules nothing`,
    );
  }
});

// rf2-w9ip — THE ROUTE-PATH CENSUS IS ARMED BY EVERY TREE IT READS.
//
// `implementation/routing/test/re_frame/routing_path_census_test.clj` is a JVM
// suite that reads three app trees named in its own `app-roots`. It runs only
// in `jvm-routing`, which only `implementation_jvm` gates — and before this
// bead none of the three roots armed that output, so the census could not fire
// on a single edit it exists to police.
//
// The roster is DERIVED from the census source rather than restated here. That
// is the whole point: a second hand-maintained list would be a fresh instance
// of the defect (rf2-6ng7's class — a gate's inputs and its arming in two
// files with nothing holding them in step), and fixing one instance by minting
// another is not a fix. Add a root over there and this reds until the
// classifier catches up.

const CENSUS_REL = 'implementation/routing/test/re_frame/routing_path_census_test.clj';

/**
 * The string literals of the census's `app-roots` vector, read out of its
 * source.
 *
 * Scans forward from `(def app-roots` for the first `[` that is not inside a
 * string or a line comment — the docstring in between is long, quotes
 * namespaces and paths, and must not be mistaken for the vector.
 */
function censusAppRoots(source) {
  const start = source.indexOf('(def app-roots');
  assert.notEqual(start, -1, `${CENSUS_REL} must still define app-roots`);

  let i = start;
  let inString = false;
  let inComment = false;
  for (; i < source.length; i += 1) {
    const ch = source[i];
    if (inString) {
      if (ch === '\\') i += 1;
      else if (ch === '"') inString = false;
      continue;
    }
    if (inComment) {
      if (ch === '\n') inComment = false;
      continue;
    }
    if (ch === '"') inString = true;
    else if (ch === ';') inComment = true;
    else if (ch === '[') break;
  }
  assert.ok(i < source.length, `${CENSUS_REL}: no app-roots vector found`);

  const end = source.indexOf(']', i);
  assert.notEqual(end, -1, `${CENSUS_REL}: unterminated app-roots vector`);

  const roots = [...source.slice(i, end).matchAll(/"([^"\\]*)"/g)].map((m) => m[1]);
  // Cannot pass vacuously: a parse that found nothing would assert every root
  // in an empty list and report green.
  assert.ok(
    roots.length >= 3,
    `${CENSUS_REL}: expected at least 3 app-roots, parsed ${roots.length} (${roots.join(', ')})`,
  );
  return roots;
}

test('every route-path census app-root arms implementation_jvm (rf2-w9ip)', () => {
  const source = fs.readFileSync(path.join(REPO_ROOT, CENSUS_REL), 'utf8');

  // The census reads `.cljs` / `.cljc` and nothing else, and the classifier's
  // predicate mirrors that filter. Pin the filter itself: widening it there
  // without widening the arm would re-open the hole in a shape no root list
  // can show.
  assert.ok(
    source.includes('#"\\.clj[sc]$"'),
    `${CENSUS_REL}: source-file filter changed — re-check is_route_path_census_input's extensions`,
  );

  for (const root of censusAppRoots(source)) {
    for (const ext of ['cljs', 'cljc']) {
      const probe = `${root}/rf2_w9ip_probe.${ext}`;
      assert.equal(
        classify(probe).implementation_jvm,
        'true',
        `${probe} must arm implementation_jvm: the route-path census reads ${root}, and jvm-routing is the only job that runs it`,
      );
    }
  }
});

test('jvm-routing stays gated on implementation_jvm, or the census arm schedules nothing (rf2-w9ip)', () => {
  // The third leg the arming tests above cannot supply: arming an output binds
  // nothing unless the job running the suite is still gated on that output.
  assert.match(
    jobBlock(fs.readFileSync(WORKFLOW, 'utf8'), 'jvm-routing'),
    /if: needs\.detect_changed_surfaces\.outputs\.implementation_jvm == 'true'/,
    'jvm-routing must stay gated on implementation_jvm',
  );
});

// ---------------------------------------------------------------------------
// rf2-6ng7 — NON-LOCAL INPUT EDGES ARM THE JOB THAT READS THEM.
//
// The bounded audit this bead ruled found three more gates in the rf2-w9ip
// shape above: a suite whose expected value IS a file somewhere else in the
// repository, scheduled in a job that the file's own classification never
// armed. Each is pinned here the way the census is — the roster READ OUT OF
// the gate's own source, never restated. A restated list is a second thing to
// keep in step, which is the defect these pins exist to catch.
//
// Every one asserts three legs, because two are not enough: the path arms the
// output, the output still gates the job, and the parse that produced the
// roster found something (a roster read as empty asserts nothing and reports
// green).
// ---------------------------------------------------------------------------

/**
 * The string literals of the first vector following `(def ^:private <name>`,
 * read out of Clojure source.
 *
 * Same scan as `censusAppRoots` above and for the same reason: the docstring
 * between the name and the vector quotes paths and namespaces, so the first
 * `[` that is not inside a string or a line comment is the vector's.
 */
function defVectorStrings(source, defName, label) {
  const start = source.indexOf(`(def ^:private ${defName}`);
  assert.notEqual(start, -1, `${label} must still define ${defName}`);

  let i = start;
  let inString = false;
  let inComment = false;
  for (; i < source.length; i += 1) {
    const ch = source[i];
    if (inString) {
      if (ch === '\\') i += 1;
      else if (ch === '"') inString = false;
      continue;
    }
    if (inComment) {
      if (ch === '\n') inComment = false;
      continue;
    }
    if (ch === '"') inString = true;
    else if (ch === ';') inComment = true;
    else if (ch === '[') break;
  }
  assert.ok(i < source.length, `${label}: no ${defName} vector found`);

  const end = source.indexOf(']', i);
  assert.notEqual(end, -1, `${label}: unterminated ${defName} vector`);

  return [...source.slice(i, end).matchAll(/"([^"\\]*)"/g)].map((m) => m[1]);
}

// --- the Xray spec markdown two suites read --------------------------------
//
// The classifier's spec-md guard (rf2-f79t8) excused
// `tools/{story,xray}/spec/**.md` from every probe on the premise that spec
// prose "cannot affect any JVM unit test". Two suites under `tools/xray/test/`
// read exactly that prose as their expected value, so for the Xray half the
// premise was false.

const XRAY_PANEL_REFS_REL =
  'tools/xray/test/day8/re_frame2_xray/panel_enum_spec_refs.clj';
const XRAY_MATRIX_REL =
  'tools/xray/test/day8/re_frame2_xray/coverage_matrix_metadata_test.clj';

test('the Xray spec files the panel-enum guard reads arm cljs_node_test (rf2-6ng7)', () => {
  const source = fs.readFileSync(path.join(REPO_ROOT, XRAY_PANEL_REFS_REL), 'utf8');
  const specFiles = defVectorStrings(source, 'spec-files', XRAY_PANEL_REFS_REL);
  assert.ok(
    specFiles.length >= 2,
    `${XRAY_PANEL_REFS_REL}: expected at least 2 spec-files, parsed ${specFiles.length} (${specFiles.join(', ')})`,
  );
  for (const rel of specFiles) {
    assert.equal(
      classify(rel).cljs_node_test,
      'true',
      `${rel} must arm cljs_node_test: panel-enum-spec-refs slurps it at macro-expansion time into panel_enum_guard_cljs_test.cljs, which the consolidated :node-test build compiles`,
    );
  }
});

test('the Xray spec files the coverage-matrix suite reads arm tools_jvm (rf2-6ng7)', () => {
  const source = fs.readFileSync(path.join(REPO_ROOT, XRAY_MATRIX_REL), 'utf8');
  // Segment vectors — `["tools" "xray" "spec" "<file>.md"]` — joined the way
  // `(apply io/file (find-repo-root) rel)` resolves them.
  const rels = ['matrix-spec-rel', 'insight-spec-rel'].map((defName) => {
    const segs = defVectorStrings(source, defName, XRAY_MATRIX_REL);
    assert.ok(
      segs.length >= 2,
      `${XRAY_MATRIX_REL}: ${defName} parsed ${segs.length} segments (${segs.join(', ')})`,
    );
    return segs.join('/');
  });
  for (const rel of rels) {
    assert.equal(
      classify(rel).tools_jvm,
      'true',
      `${rel} must arm tools_jvm: coverage-matrix-metadata-test slurps it, and jvm-tools-xray is the only job that runs it`,
    );
  }
});

test('Story spec markdown stays cheap (rf2-6ng7 negative control)', () => {
  // The guard the Xray arm narrows is still doing its job on the other half:
  // Story's spec prose has no counterpart reader inside test.yml, so it must
  // still classify to nothing at all.
  const verdicts = classify('tools/story/spec/API.md');
  for (const output of ['tools_jvm', 'cljs_node_test', 'mcp_conformance', 'template_expensive']) {
    assert.equal(
      verdicts[output],
      'false',
      `tools/story/spec/API.md must not arm ${output} — no test.yml suite reads Story's spec markdown`,
    );
  }
});

// --- the setup skill's reference snippets ----------------------------------
//
// `setup-skill-scaffold-compiles-test` materialises a whole scaffold out of
// the fenced blocks in one directory and compiles it. That suite runs in
// `jvm-tools-template` under `template_expensive`, and the directory armed
// only `skills_structural` — a shape guard, which cannot tell whether the
// snippets still compile.

const TEMPLATE_EMITTED_REL =
  'tools/template/test/day8/re_frame2_template/emitted_test_run_test.clj';

test('the setup skill reference snippets arm template_expensive (rf2-6ng7)', () => {
  const source = fs.readFileSync(path.join(REPO_ROOT, TEMPLATE_EMITTED_REL), 'utf8');
  const m = source.match(
    /\(def\s+\^:private\s+skill-setup-refs[\s\S]{0,400}?\(io\/file\s+\(repo-root\)\s+"([^"]+)"\)/,
  );
  assert.ok(
    m,
    `${TEMPLATE_EMITTED_REL} must still resolve skill-setup-refs from a repo-relative literal`,
  );
  const dir = m[1];
  assert.ok(
    dir.startsWith('skills/'),
    `${TEMPLATE_EMITTED_REL}: skill-setup-refs resolved to "${dir}", which is not a skills path`,
  );
  for (const probe of [`${dir}/rf2_6ng7_probe.md`, `${dir}/nested/rf2_6ng7_probe.md`]) {
    assert.equal(
      classify(probe).template_expensive,
      'true',
      `${probe} must arm template_expensive: setup-skill-scaffold-compiles-test materialises the scaffold from ${dir} and compiles it, and jvm-tools-template is the only job that runs that suite`,
    );
  }
});

test('the rest of the setup skill stays off template_expensive (rf2-6ng7 negative control)', () => {
  assert.equal(
    classify('skills/re-frame2-setup/SKILL.md').template_expensive,
    'false',
    'skills/re-frame2-setup/SKILL.md is not materialised into the scaffold — it must not queue the emitted-app compile',
  );
});

test('the jobs these arms reach are still gated on the armed outputs (rf2-6ng7)', () => {
  // The third leg, the one no arming test can supply: arming an output binds
  // nothing unless the job running the suite is still gated on that output.
  // (`jvm-ui` / `implementation_jvm` is already pinned by the rf2-61ar test
  // above, so it is not restated here.)
  const workflow = fs.readFileSync(WORKFLOW, 'utf8');
  for (const [job, output] of [
    ['jvm-tools-xray', 'tools_jvm'],
    ['cljs', 'cljs_node_test'],
    ['jvm-tools-template', 'template_expensive'],
  ]) {
    assert.match(
      jobBlock(workflow, job),
      new RegExp(`if: needs\\.detect_changed_surfaces\\.outputs\\.${output} == 'true'`),
      `${job} must stay gated on ${output}, or arming it schedules nothing`,
    );
  }
});

// ===========================================================================
// rf2-skvce — THE TREE-CLAIM META-CHECK.
//
// Every test above this line pins ONE arm that somebody already thought to
// write. The recurring incident in this repo is the arm nobody thought to
// write: a NEW directory lands, classifies to nothing, and is gated by
// nothing until an audit goes looking. Five recorded instances —
// implementation/hicasso (rf2-hic-001), both codemod trees,
// implementation/ssr-node (rf2-n8vp, which landed in PR #8028 classifying to
// nothing at all), and the Story feature-load gate (rf2-65ajl) — plus the
// twelve top-level testbed builds this same patch closes under rf2-in6c4.
//
// WHAT THIS ASSERTS, precisely: every tracked tree either arms at least one
// output of the classifier, or carries an entry in DECLARED_NO_SURFACE_OUTPUT
// below. Nothing more. It does not model any gate's INPUTS — that is the
// second hand-maintained model rf2-6ng7 rejected, and it would need per-gate
// maintenance forever. Tree CLAIM is a far cheaper invariant with none: the
// only thing that changes it is a tree appearing or disappearing.
//
// WHAT IT DELIBERATELY LETS THROUGH. A tree that arms SOMETHING passes, even
// if the something is the wrong output, and even if a FILE inside it arms
// nothing — `.github/scripts/nightly_failure_alert.py` classifies to zero on
// its own (rf2-skvce finding N4) and this check will never say so, because
// `.github/scripts` as a tree is lit by its siblings. Going file-level would
// buy that one case and cost a nag on every README in the repository, which
// is the trade the anti-over-engineering posture settles against. The
// alerter's exposure is bounded by its own in-run --self-test at
// expensive-tests.yml:648; it stays a declared file-level hole.
//
// THAT HOLE WAS RE-PUT AND RE-DECLARED (rf2-skvce, second pass), because "we
// chose not to" invites re-litigation while a measurement does not. Three
// findings, all re-checked at source rather than carried forward:
//
//   1. The cheap PR-time repair costs a MECHANISM, which is the signal
//      rf2-6ng7's (c)-narrow ruling names for leaving a hole declared. The
//      right semantic home is `verify-skill-mcp-drift` ("Repo invariant
//      checks"), the always-on pure-stdlib spine that took
//      check_gate_scheduling.py for this very reason — and every step in it is
//      audited by scripts/check_ci_reproduce_commands.py, which requires a
//      single-line `run: python scripts/check_<name>.py …` with a matching
//      `check_*` id. The alerter is `.github/scripts/nightly_failure_alert.py`
//      and is not a `check_*`, so a step there means teaching that guard a new
//      path shape. The other always-on job, `verify-readme-links`, would take
//      it without a fight and is a markdown-slug job; a checker parked in an
//      unrelated job is how the next reader loses it.
//
//   2. THE EXPOSURE IS SMALLER THAN THE FINDING SAID. A broken alerter does
//      not fail quiet. Its self-test runs `continue-on-error` precisely so the
//      live arm still tries, and the re-raise step at the bottom of that job
//      still ends the run RED — same night, in the same run list the nightly
//      is read from. What is actually at risk is one night's tracking-ISSUE
//      edit, not the signal that something failed.
//
//   3. Going file-level is the rejected option (a) in a new costume: a second
//      hand-maintained model, of files this time, nagging on every README,
//      .gitignore and image in the repository.
//
// The self-test itself was re-measured rather than assumed: 0.19s, exit 0, pure
// stdlib. It is cheap; it is the HOME that is not free.
//
// "AT LEAST ONE OUTPUT" IS NOT "COVERED", and the declared list is where that
// distinction is kept honest. Several trees are properly gated at PR time by
// jobs that are ALWAYS-ON — `beads-pr-boundary`, `verify-skill-mcp-drift`,
// `verify-readme-links` — and an always-on job by construction arms no
// surface output. Others are gated by a DIFFERENT workflow with its own
// classifier (docs.yml's `docs_surface`, lint.yml's lint surface). So an
// entry below is not an apology; it is a statement of where the tree's
// coverage actually lives. Two entries say the coverage is MISSING, and name
// the bead.
//
// THE REASONS ARE CHECKED, NOT BELIEVED — the lesson `scripts/
// check_gate_scheduling.py` states outright for its own DISPOSITIONS ("a
// reason is a claim about the world, and claims rot"). Every path named in a
// `coveredBy` must exist. That is a weak check and an honest one: it cannot
// tell that a gate still READS the tree, but it does catch the reference that
// outlived the gate it names, which is the way these rot in practice.
// ===========================================================================

// The two reasons that recur across a dozen trees, named once. Sharing the
// object is deliberate: these trees are covered by the SAME mechanism, and
// spelling that out twelve times invites twelve slightly different stories.
const DOCS_YML = {
  why: "documentation staged into the MkDocs site; docs.yml's own docs_surface classifier arms on docs/*, and its build job runs mkdocs --strict over the corpus. Markdown link + anchor validation is no longer part of THAT job: rf2-v7fui moved check_doc_slugs.py to test.yml's unconditional verify-readme-links job, so these trees are slug-validated on every PR rather than only on a docs-classified one",
  coveredBy: ['.github/workflows/docs.yml', 'scripts/check_doc_slugs.py'],
};

const SKILLS_ALWAYS_ON = {
  why: "prose skill trees, reached at PR time by two ALWAYS-ON jobs — and an always-on job arms no surface output by construction. verify-skill-mcp-drift runs check_skill_mcp_drift.py (allowed-tools front-matter held in step with the MCP catalogues) and check_inject_cofx_residue.py (skills/ markdown scanned for retired API spellings); verify-readme-links runs check_doc_slugs.py over its full roster — docs, spec, SKILLS, migration. That second job is what closes the gap this entry used to record for rf2-v7fui: the slug gate listed skills in DEFAULT_ROOTS but ran only inside docs.yml's build job, whose docs_surface does not match skills/*, so slug and anchor validation of these files fired nowhere at PR time.",
  coveredBy: [
    'scripts/check_skill_mcp_drift.py',
    'scripts/check_inject_cofx_residue.py',
    'scripts/check_doc_slugs.py',
  ],
};

const DECLARED_NO_SURFACE_OUTPUT = {
  '.beads': {
    why: 'the tracker database export and its config; the boundary that keeps it out of a PR is enforced by the always-on beads-pr-boundary job',
    coveredBy: ['scripts/check-beads-pr-boundary.sh'],
  },
  '.beads/hooks': {
    why: "bd's own hooks, installed into a developer checkout; the always-on beads-pr-boundary job self-tests the guards they wrap before enforcing",
    coveredBy: ['scripts/git-hooks/test-pre-commit.sh'],
  },
  '.claude': {
    why: 'a single settings.json for the agent harness — local configuration, not shipped code and not read by any gate',
    coveredBy: [],
  },
  '.clj-kondo': {
    why: "linter configuration; lint.yml's own surface classifier lists .clj-kondo/* explicitly",
    coveredBy: ['.github/workflows/lint.yml'],
  },
  '.clj-kondo/hooks': {
    why: 'clj-kondo macro hooks — real Clojure, but consumed only by the linter, and armed by the same lint.yml surface as the config beside them',
    coveredBy: ['.github/workflows/lint.yml'],
  },
  // rf2-6c12m.1. The Hicasso bench lane — the measurement harness the
  // programme's numbers were taken on — left implementation/hicasso/test for
  // its own hand-run shadow project here, off every per-PR lane BY RULING:
  // its suites exercise LOCAL COPIES of the runtime, so running its 574
  // deftests per PR could not catch a regression in the shipped one, and its
  // 82 MB of committed run records are evidence rather than inputs. The
  // classifier carries an explicit `bench/*` arm that sets nothing, so the
  // silence is stated in the script as well as declared here. The tree's own
  // gate is `npm run check` from bench/hicasso/ (every namespace compiled
  // warnings-fatal plus the harness self-tests), which the bench README
  // requires before a bench change is published. Two always-on PR jobs still
  // reach the tree without arming anything: verify-readme-links validates
  // bench/hicasso/README.md, and js-harness-self-tests runs
  // lane_cache_wiring.test.cjs, which scans the drivers as text for the
  // cache-clear rule (rf2-d19nf) across implementation/ AND bench/hicasso/.
  'bench/hicasso': {
    why: "the Hicasso bench lane, a hand-run shadow-cljs project kept off every per-PR lane by ruling (rf2-6c12m.1): its suites run against local copies of the runtime, so no PR gate could learn anything from them. Its gate is `npm run check` from bench/hicasso/; two always-on jobs still read it — verify-readme-links (check_readme_links.py over its README) and js-harness-self-tests (lane_cache_wiring.test.cjs over its drivers)",
    coveredBy: [
      'bench/hicasso/package.json',
      'scripts/check_readme_links.py',
      'implementation/core/test/re_frame/bench/lane_cache_wiring.test.cjs',
    ],
  },
  docs: DOCS_YML,
  'docs/EP': DOCS_YML,
  'docs/async': DOCS_YML,
  // rf2-7cuns. This tree needed no entry until 2026-08-14 because ONE arm lit
  // it: `docs/core/freehand/*.md` armed implementation_jvm, because
  // samples_coverage_jvm_test.clj `file-seq`d that directory. rf2-0yp7w
  // deleted the Freehand guide and that roster in the same commit, which
  // removed the last tracked file matching the arm and left the whole tree
  // dark. rf2-7v5vx then deleted the arm itself: a POSIX `case` over path
  // strings cannot know its directory is gone, so it went on returning
  // implementation_jvm=true for a path no diff can produce — green, and
  // pointing at a suite that no longer exists.
  //
  // Declared rather than re-armed, and that half was REVIEWED under rf2-7v5vx
  // rather than inherited: the surviving guide is docs/core/hicasso/**, and
  // rf2-r5iy7 already measured and REJECTED arming it, because the only output
  // that would reach its checker is cljs_node_test — the ~10-minute node
  // build, scheduled on a prose typo. Every gate named below was re-read at
  // its source and holds. The guide-samples gate pins nothing any more: since
  // rf2-6c12m.9 it checks only that every hicasso verb a sample names resolves.
  'docs/core': {
    why: "the human guide. Four PR-time gates read it and none arms a surface output, which is the always-on shape this list exists to record. docs.yml's own docs_surface classifier stages it into the site and runs mkdocs --strict; check_doc_slugs.py validates its links and heading anchors on EVERY PR from test.yml's unconditional verify-readme-links job (rf2-v7fui); and lint.yml runs api-manifest doc-guide-check over docs/core/** minus docs/core/api/**, reconciling every call-position `(rf/<var>` reference against the manifest behind a non-vacuous floor. The Hicasso guide's fenced samples are covered by the unconditional hicasso-guide-samples job (rf2-r5iy7; since rf2-6c12m.9 it checks only that every hicasso verb a sample names resolves to a public def), which is unconditional PRECISELY so that a guide-only PR runs it.",
    coveredBy: [
      '.github/workflows/docs.yml',
      'scripts/check_doc_slugs.py',
      'implementation/scripts/api-manifest/src/re_frame/api_manifest/doc_guide_check.clj',
      'implementation/hicasso/scripts/check_guide_samples.py',
    ],
  },
  'docs/images': DOCS_YML,
  'docs/resources': DOCS_YML,
  'docs/routing': DOCS_YML,
  'docs/scripts': DOCS_YML,
  'docs/skills': DOCS_YML,
  // `docs/ssr` is DELIBERATELY ABSENT since rf2-8arzr.6, and the ratchet below
  // is what makes that a requirement rather than a tidy-up: the tree used to
  // be DOCS_YML, and then `docs/ssr/concepts.md` gained a test.yml pin
  // (ssr_doc_example_node_build_id_test.clj, jvm-ssr) and an arm to schedule
  // it. One armed file arms the tree, so the declaration became stale and had
  // to go — leaving it would have told the next reader this tree is ungated
  // months after it stopped being. The other nine pages are still covered by
  // docs.yml + check_doc_slugs.py exactly as DOCS_YML says; that is a
  // statement about pages, and this table is keyed by tree.
  'docs/story': DOCS_YML,
  'docs/stylesheets': DOCS_YML,
  'docs/the-mayor-method': DOCS_YML,
  'docs/xray': DOCS_YML,
  'migration/from-clj-new-template': {
    why: "a migration note; docs.yml's docs_surface classifier lists migration/*, and its slug/anchor validation comes from check_doc_slugs.py in test.yml's unconditional verify-readme-links job (rf2-v7fui moved it out of the docs build)",
    coveredBy: ['.github/workflows/docs.yml', 'scripts/check_doc_slugs.py'],
  },
  'scripts/_test_fixtures': {
    why: 'per-gate fixture corpora, read only by the gates\' own --self-test runs; each fixture tree is armed with its gate rather than as a surface (several are named individually on docs.yml\'s docs_surface list and in the fast-PR spine roster)',
    coveredBy: ['scripts/test-fast-pr.sh', '.github/workflows/docs.yml'],
  },
  'scripts/git-hooks': {
    why: 'the repository git hooks; the always-on beads-pr-boundary job runs their self-test before enforcing the boundary',
    coveredBy: ['scripts/git-hooks/test-pre-commit.sh'],
  },
  skills: SKILLS_ALWAYS_ON,
  'skills/re-frame-migration': SKILLS_ALWAYS_ON,
  'skills/re-frame2-implementor': SKILLS_ALWAYS_ON,
  'skills/re-frame2-improver': SKILLS_ALWAYS_ON,
  // rf2-g1m2q — `skills/re-frame2-pair-retro` and `skills/reagent-migration`
  // are DELIBERATELY ABSENT from this table now. Both used to sit here as
  // SKILLS_ALWAYS_ON, which was true of them as pure prose trees; rf2-qad4l and
  // rf2-vpdrf / rf2-bbe91 then gave each an executable half gated on
  // `skills_structural` (the pair-retro bb step in `skills-structural`, and
  // `reagent-migration-fixture-cold-start`), and this bead armed the two case
  // arms that schedule them. A tree that arms an output must not stay declared:
  // the `staleDeclarations` half of the check below fails on exactly that, so
  // re-adding either entry reds this suite rather than passing quietly.
  'skills/re-frame2-xray': SKILLS_ALWAYS_ON,
  tools: {
    why: "A DECLARED HOLE, and since rf2-i2uoc a MEASURED one rather than an open question. The tree is four files. tools/README.md IS covered — the always-on verify-readme-links job walks it (measured: it is in check_readme_links.py's _iter_scanned set). tools/.gitignore is config no gate reads. The two build coordinators have no CI consumer AT ALL, and arming them was refused on evidence rather than guessed: no workflow runs from tools/ (every working-directory in .github/workflows is tools/<artefact>, never the bare root); scripts/test-jvm-tools.sh iterates per-tool directories and never the aggregate :test alias; tools/deps.edn says so itself, in its own comment — 'Production CI runs each tool's :test alias separately (per-tool gates)'; CI compiles the pair-mcp server from tools/re-frame2-pair-mcp/shadow-cljs.edn, not the tools/ mirror of it; and verify-version-lockstep.sh reads the per-artefact deps.edn files, not this coordinator. So NO existing output would exercise either file, and arming one — tools_jvm was the candidate — would schedule four probes that never read the edited file, which the tools_jvm_machines_viz note beside it calls worse than nothing. They are developer-convenience aggregates whose breakage surfaces on the next `cd tools && clojure -M:test`, to the developer who caused it. Delete this entry if either coordinator ever gains a real CI consumer.",
    coveredBy: ['scripts/check_readme_links.py'],
  },
};

/**
 * Partition every tracked file into DISJOINT trees: repo-root files under
 * `.`, a top-level directory's own files under `<dir>`, and everything deeper
 * under `<dir>/<subdir>`.
 *
 * DISJOINT IS THE POINT. Checking `implementation` AND `implementation/core`
 * as overlapping sets would let a healthy child vouch for a dark parent — the
 * `tools` entry above is exactly that case, and it is only visible because
 * `tools` here means the four files directly under `tools/` and not the seven
 * well-gated artefacts below them. Two levels is where the repo's own
 * ownership boundaries sit; a third would start reporting `src` and `test`.
 */
function trackedTrees(repoRoot) {
  const out = execFileSync('git', ['ls-files'], {
    cwd: repoRoot,
    encoding: 'utf8',
    maxBuffer: 64 * 1024 * 1024,
  });
  const groups = new Map();
  for (const file of out.split('\n').filter(Boolean)) {
    const seg = file.split('/');
    const key =
      seg.length === 1 ? '.' : seg.length === 2 ? seg[0] : `${seg[0]}/${seg[1]}`;
    if (!groups.has(key)) groups.set(key, []);
    groups.get(key).push(file);
  }
  return groups;
}

/**
 * Does any file in `files` arm any output? Classified in CHUNKS with an early
 * exit, which is what keeps this affordable: a healthy tree answers on its
 * first chunk, and only a genuinely dark one pays for all of them. The
 * classifier ORs its outputs across the paths it is given, so a chunk's
 * verdict is exactly "did any of these arm anything".
 */
function treeArmsAnything(files) {
  const CHUNK = 60;
  for (let i = 0; i < files.length; i += CHUNK) {
    const result = classify(...files.slice(i, i + CHUNK));
    if (Object.values(result).some((v) => v === 'true')) return true;
  }
  return false;
}

test('every tracked tree arms an output or is DECLARED (rf2-skvce)', () => {
  const groups = trackedTrees(REPO_ROOT);
  assert.ok(
    groups.size > 50,
    `precondition: git ls-files must yield the real tree set, got ${groups.size}`,
  );

  const undeclaredDark = [];
  const staleDeclarations = [];

  for (const [tree, files] of groups) {
    const armed = treeArmsAnything(files);
    const declared = Object.prototype.hasOwnProperty.call(
      DECLARED_NO_SURFACE_OUTPUT,
      tree,
    );
    if (!armed && !declared) undeclaredDark.push(`${tree} (${files.length} files)`);
    if (armed && declared) staleDeclarations.push(tree);
  }

  assert.deepEqual(
    undeclaredDark,
    [],
    'these tracked trees arm NO classifier output and are not declared. A ' +
      'tree that arms nothing is gated by nothing at PR time. Either add an ' +
      'arm in .github/scripts/report-changed-surfaces.sh (and pin it above), ' +
      'or add an entry to DECLARED_NO_SURFACE_OUTPUT saying where the ' +
      "tree's coverage really lives:\n  " +
      undeclaredDark.join('\n  '),
  );

  // THE LIST IS A RATCHET, NOT A DUMPING GROUND. Without this half a
  // declaration outlives the hole it declared, and the next reader trusts a
  // note saying a tree is ungated when it has been gated for months — the
  // rot _rigorous-local-inventory.test.cjs measured at six weeks. Arming a
  // declared tree is meant to cost exactly one deletion here.
  assert.deepEqual(
    staleDeclarations,
    [],
    'these trees are declared as arming no output, but they now arm one. ' +
      'Delete their DECLARED_NO_SURFACE_OUTPUT entries:\n  ' +
      staleDeclarations.join('\n  '),
  );
});

test('every DECLARED tree still exists, and its named coverage does (rf2-skvce)', () => {
  const groups = trackedTrees(REPO_ROOT);
  const problems = [];
  for (const [tree, { why, coveredBy }] of Object.entries(
    DECLARED_NO_SURFACE_OUTPUT,
  )) {
    if (!groups.has(tree)) {
      problems.push(`${tree}: declared but no longer a tracked tree`);
    }
    if (!why || why.length < 20) {
      problems.push(`${tree}: needs a real reason, not a placeholder`);
    }
    for (const p of coveredBy) {
      if (!fs.existsSync(path.join(REPO_ROOT, p))) {
        problems.push(
          `${tree}: names ${p} as its coverage, and that path does not exist`,
        );
      }
    }
  }
  assert.deepEqual(problems, [], problems.join('\n  '));
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
