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

// THE ROSTER GUARD. The classifier arms on directory-prefix globs, so a pin on
// a path that does not exist classifies exactly like the real file beside it:
// the row passes and reports coverage it never reached. Declaring a roster
// through `pinnedRoster` IS arming an existence check for every path in it.
//
// A GLOB PROBE — a synthetic path whose point is the SHAPE the classifier
// matches (a depth, an extension, a sibling tree) — is legitimately untracked,
// so probes are named at their use site and never folded into a roster.
function pinnedRoster(name, files) {
  test(`${name}: every pinned path exists (roster guard, rf2-e30e)`, () => {
    for (const file of files) {
      assert.ok(
        fs.existsSync(path.join(REPO_ROOT, file)),
        `${name} pins ${file}, which does not exist. A pin on a phantom path ` +
          'cannot fail, so it reports coverage the suite never reached. ' +
          'Correct the path — or, if it is a ' +
          'synthetic glob probe, name it at its use site instead of in a roster.',
      );
    }
  });
  return files;
}

const SURFACES_SCRIPT = './.github/scripts/report-changed-surfaces.sh';

const classifyCache = new Map();

/**
 * Classify one path list and return the classifier's key -> "true"/"false" map.
 *
 * The script is spawned as bash's own argv, never through `bash -lc`: a login
 * shell evaluates the whole profile on every call, and across the hundreds of
 * calls here the extra process creations exhaust msys2's fork emulation on
 * Windows (`dofork: child -1 ... 0xC0000142`, an exit-127 child on a different
 * case each run). Argv also needs no shell quoting.
 *
 * Memoized: with explicit paths the classifier reads nothing but its argument
 * list, so one path list always yields the same verdicts. The cached verdict
 * is frozen so a caller that mutates it fails loudly.
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

// The outputs a classification arms, for whole-value assertions.
function armedOutputs(result) {
  return Object.keys(result).filter((key) => result[key] === 'true');
}

test('Conformance fixture change runs the JVM, cljs and cljs-browser lanes (rf2-f79t8)', () => {
  // The JVM corpus runners, the CLJS corpus runner in :node-test, and the
  // browser lane all read spec/conformance/fixtures/*.
  const result = classify('spec/conformance/fixtures/dispatch.edn');
  assert.equal(result.implementation_jvm, 'true');
  assert.equal(result.cljs_node_test, 'true');
  assert.equal(result.cljs_browser, 'true');
});

// Story/Xray src compiles into :node-test, and the DOM suites the browser lane
// mounts reach it transitively.
test('Story/Xray CLJS src runs cljs + cljs-browser (rf2-f79t8, rf2-1sd8h)', () => {
  for (const file of [
    'tools/story/src/re_frame/story/play/presence.cljc',
    'tools/xray/src/day8/re_frame2_xray/core.cljs',
  ]) {
    const result = classify(file);
    assert.equal(result.cljs_node_test, 'true', file);
    assert.equal(result.cljs_browser, 'true', file);
  }
});

// Repo-wide source walks parked in implementation/core/test/. Their domains —
// tools/**/src, every implementation src and test tree, all tracked prose —
// outrun any classifier arm, so test.yml's UNCONDITIONAL jvm-repo-source-walks
// job runs each of them on every PR, one step per namespace.
const REPO_SOURCE_WALK_NAMESPACES = Object.freeze([
  're-frame.no-rf-default-floor-lint-test',
  're-frame.egress-chokepoint-conformance-test',
  're-frame.error-catalogue-channel-conformance-test',
  're-frame.warn-once-clear-governance-test',
  're-frame.prod-gate-naming-drift-test',
  're-frame.late-bind-drift-test',
  're-frame.observation-render-law-drift-test',
]);

test('the unconditional walk lane runs every namespace whose false arm it excuses (rf2-n4a2b)', () => {
  const workflow = fs.readFileSync(WORKFLOW, 'utf8');
  const block = jobBlock(workflow, 'jvm-repo-source-walks');

  // Job properties sit at four spaces, step properties at eight. A job-level
  // `if:` would be a read-set model of what the walks walk.
  assert.doesNotMatch(
    block,
    /^ {4}if:/m,
    'jvm-repo-source-walks must stay UNCONDITIONAL — a job-level condition here is ' +
      'a read-set model of what the walks walk',
  );

  // Every walk step carries exactly the setup-gated failure-independence guard:
  // `!cancelled()` keeps one failing walk from skipping the walks behind it, and
  // the `steps.setup` conjunct keeps a failed setup from running all seven
  // against a missing toolchain (a status function REPLACES the implicit
  // `success()`). `\r` is stripped because jobBlock preserves CRLF.
  const stepConditions = (block.match(/^ {8}if:[^\n]*/gm) || []).map((s) =>
    s.replace(/\r$/, ''),
  );
  for (const cond of stepConditions) {
    assert.match(
      cond,
      /^ {8}if: \$\{\{ !cancelled\(\) && steps\.setup\.outcome == 'success' \}\}$/,
      `"${cond.trim()}" is a step condition other than the permitted ` +
        'setup-gated failure-independence guard',
    );
  }
  assert.equal(
    stepConditions.length,
    REPO_SOURCE_WALK_NAMESPACES.length,
    "every walk step must carry `if: ${{ !cancelled() && steps.setup.outcome == " +
      "'success' }}`; the setup steps carry no guard of their own",
  );

  // The `setup` marker: a missing id reads null, so every walk would SKIP on a
  // green job. It carries no condition, so it reaches `success` only when the
  // checkout, JDK and Clojure CLI install all succeeded.
  assert.match(
    block,
    /^ {6}- name: Essential setup complete\r?\n {8}id: setup\r?\n {8}run:[^\n]*\r?\n/m,
    'the `setup` marker step must sit after the Clojure CLI install and carry ' +
      'NO condition of its own',
  );
  assert.ok(
    block.indexOf('id: setup') < block.indexOf('        if: ${{ !cancelled()'),
    'the `setup` marker must be declared BEFORE the walks that gate on it',
  );
  assert.match(
    workflow,
    /^\s+- jvm-repo-source-walks$/m,
    'jvm-repo-source-walks must stay in all-required-passed needs:',
  );

  for (const ns of REPO_SOURCE_WALK_NAMESPACES) {
    assert.match(
      block,
      new RegExp(`clojure -M:test -n ${ns.replace(/[.]/g, '[.]')}\\s*$`, 'm'),
      `${ns} is a repo-wide source walk and must be a step in this lane`,
    );
  }

  // One step per namespace: a namespace missing from a multi-`-n` selector is
  // silent, while a per-namespace step reds on the runner's own floor of 1.
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

// Story/Xray `*_dom_cljs_test` namespaces compile under :node-test too, where
// they find no `document` and self-skip; only the browser lane mounts them.

test('a non-DOM Story test runs the node lane only; a JVM .clj test runs neither CLJS lane (rf2-1sd8h, rf2-eyyd2)', () => {
  const cljs = classify('tools/story/test/re_frame/story_cljs_test.cljs');
  assert.equal(cljs.cljs_browser, 'false');
  assert.equal(cljs.cljs_node_test, 'true');
  const clj = classify('tools/story/test/re_frame/story_test.clj');
  assert.equal(clj.cljs_browser, 'false');
  assert.equal(clj.cljs_node_test, 'false');
  assert.equal(clj.tools_jvm, 'true');
});

test('every Story/Xray DOM suite in the tree is armed by the classifier (rf2-1sd8h)', () => {
  // Read off the tree, so a new `*_dom_cljs_test` the arm misses reds on arrival.
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

test('a non-runtime file beside a helper does NOT schedule cljs-browser (rf2-eyyd2)', () => {
  // The test_helpers/ arm is extension-guarded: prose beside a helper compiles
  // into nothing.
  assert.equal(
    classify('tools/xray/test/day8/re_frame2_xray/test_helpers/README.md').cljs_browser,
    'false',
  );
});

// re-frame.story delegates every public registration macro to macros.clj, a
// CLJ-only namespace, so its emitted forms are what a `(story/reg-variant …)`
// call site compiles to. The `.clj` extension guards would leave it on no CLJS
// lane and no browser gate, so all three predicates name it.
const STORY_MACROS = 'tools/story/src/re_frame/story/macros.clj';

test('Story macros.clj now arms every lane its expansion reaches (rf2-uqf5q)', () => {
  const result = classify(STORY_MACROS);
  for (const lane of ['cljs_node_test', 'cljs_browser', 'story_xray_browser']) {
    assert.equal(result[lane], 'true', `macros.clj must arm ${lane}`);
  }
});

test('an ordinary JVM-only .clj under Story src stays off the CLJS lanes and the browser gate (rf2-eyyd2, rf2-uqf5q)', () => {
  // The named exception is macros.clj and nothing else.
  const result = classify('tools/story/src/re_frame/story/jvm_only_helper.clj');
  assert.equal(result.cljs_browser, 'false');
  assert.equal(result.cljs_node_test, 'false');
  assert.equal(result.story_xray_browser, 'false');
  assert.equal(result.tools_jvm, 'true');
});

test('macros.clj is still the ONLY .clj under either src tree (rf2-uqf5q)', () => {
  // A second macro namespace beside it would be armed by none of the three
  // predicates that name macros.clj, and its expansion would reach no browser.
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

test('every support file a live DOM suite requires is armed by the classifier (rf2-eyyd2)', () => {
  // The test_helpers/ arm is a DIRECTORY convention, so what can drift is a DOM
  // suite reaching for a support file outside one. Read the require closure off
  // the tree so that reds on arrival.
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

// tools/machines-viz and tools/testbed-support put src+test on the
// consolidated :node-test AND :browser-test builds, and each has a JVM lane of
// its own; machines-viz also has its own CLJS lane and the viewer-page build.

test('machines-viz src runs cljs + cljs-browser and its own JVM, CLJS and viewer-page lanes (rf2-z0cw6s)', () => {
  const result = classify('tools/machines-viz/src/day8/re_frame2_machines_viz/chart.cljs');
  for (const output of [
    'cljs_node_test',
    'cljs_browser',
    'tools_jvm_machines_viz',
    'tools_cljs_machines_viz',
    'machines_viz_viewer_page',
  ]) {
    assert.equal(result[output], 'true', output);
  }
});

test('machines-viz spec-only .md arms nothing (rf2-z0cw6s)', () => {
  assert.deepEqual(armedOutputs(classify('tools/machines-viz/spec/API.md')), []);
});

test('testbed-support runs cljs + cljs-browser and its OWN jvm output (rf2-as6bg, rf2-wq17m)', () => {
  const result = classify(
    'tools/testbed-support/test/re_frame/testbed/open_in_editor_server_test.clj',
  );
  assert.equal(result.cljs_node_test, 'true');
  assert.equal(result.cljs_browser, 'true');
  assert.equal(result.tools_jvm_testbed_support, 'true');
});

// A lane is three parts: the classifier arms the output, the job is gated on
// it and runs the artefact's suite, and the aggregator depends on the job.
const NEW_TOOLS_JVM_LANES = [
  { job: 'jvm-tools-machines-viz', output: 'tools_jvm_machines_viz', dir: 'tools/machines-viz' },
  { job: 'jvm-tools-testbed-support', output: 'tools_jvm_testbed_support', dir: 'tools/testbed-support' },
];

pinnedRoster('NEW_TOOLS_JVM_LANES', NEW_TOOLS_JVM_LANES.map((lane) => lane.dir));

test('the new tools JVM jobs are gated on their own output, run their artefact and are required (rf2-wq17m)', () => {
  const workflow = fs.readFileSync(WORKFLOW, 'utf8');
  for (const lane of NEW_TOOLS_JVM_LANES) {
    const block = jobBlock(workflow, lane.job);
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
    // An undeclared output reads as an empty string, so the job never runs.
    assert.match(
      workflow,
      new RegExp(`${lane.output}: \\$\\{\\{ steps\\.detect\\.outputs\\.${lane.output} \\}\\}`),
      `${lane.output} must be declared as a detect_changed_surfaces output`,
    );
  }
  const aggregator = jobBlock(workflow, 'all-required-passed');
  for (const lane of NEW_TOOLS_JVM_LANES) {
    assert.ok(
      aggregator.includes(`- ${lane.job}`),
      `aggregator must list ${lane.job} in needs: — a job absent from it is advisory`,
    );
  }
});

// The Reagent `[:>]` → Fresco codemod lane: a standalone JVM tool under
// migration/, so no other output's job runs it.

const CODEMOD_LANE = {
  job: 'jvm-migration-fresco-codemod',
  output: 'migration_fresco_codemod',
  dir: 'migration/reagent-to-fresco/codemod',
  armed: 'migration/reagent-to-fresco/codemod/src/re_frame/migration/fresco/rewrite.clj',
  // The cross-tree `:paths` edge: the codemod puts
  // `../../../implementation/fresco/src` on its classpath so it and the
  // runtime door share ONE slot rule, and shared_rule_test.clj pins the two
  // `identical?`.
  //
  // frozen-sources.edn pins this file and a byte-identical twin in the bench
  // harness, so BOTH answer identically; the classpath-root row below is what
  // keeps the codemod on this one.
  sharedRule: 'implementation/fresco/src/re_frame/fresco/impl/slot.cljc',
  // The SECOND cross-tree edge, and a different mechanism. The one
  // above is a classpath entry; this one is source TEXT. shared_rule_test.clj's
  // `the-callback-contracts-are-the-doors` slurps the door's own
  // `.cljs` with a relative `io/file` and asserts the roster the codemod prints
  // into its `defhost` sketch equals the door's `callback-contracts`.
  door: 'implementation/fresco/src/re_frame/fresco/impl/codec.cljs',
};

test('the codemod JVM job is gated on its own output and runs the artefact (rf2-2rtt6.143)', () => {
  const workflow = fs.readFileSync(WORKFLOW, 'utf8');
  const block = jobBlock(workflow, CODEMOD_LANE.job);
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
  assert.match(
    workflow,
    new RegExp(
      `${CODEMOD_LANE.output}: \\$\\{\\{ steps\\.detect\\.outputs\\.${CODEMOD_LANE.output} \\}\\}`,
    ),
    `${CODEMOD_LANE.output} must be declared as a detect_changed_surfaces output`,
  );
  assert.ok(
    jobBlock(workflow, 'all-required-passed').includes(`- ${CODEMOD_LANE.job}`),
    `aggregator must list ${CODEMOD_LANE.job} in needs:`,
  );
});

test('the codemod lane arms on its own tree, the shared slot rule and the door (rf2-2rtt6.143, rf2-erjv)', () => {
  // The rule and the door sit under implementation/fresco, and
  // shared_rule_test.clj — which runs in the codemod lane — reads both.
  for (const file of [CODEMOD_LANE.armed, CODEMOD_LANE.sharedRule, CODEMOD_LANE.door]) {
    assert.equal(classify(file)[CODEMOD_LANE.output], 'true', `${file} must arm ${CODEMOD_LANE.output}`);
  }
});

test('the shared rule the codemod loads is the file the arm names (rf2-r4j91)', () => {
  // NON-VACUITY for the classpath edge: lift the cross-tree `:paths` entry out
  // of deps.edn, resolve it from the job's working directory, and require the
  // armed file to live under it.
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

test('the door the codemod pin reads is the file the arm names (rf2-erjv)', () => {
  // NON-VACUITY for the source-text edge: lift the `io/file` segments out of
  // the pin, resolve them from the job's working directory, and require the
  // answer to be the file the arm names.
  const pin = fs.readFileSync(
    path.join(REPO_ROOT, CODEMOD_LANE.dir, 'test/re_frame/migration/fresco/shared_rule_test.clj'),
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

// The v1 `reg-event-db/-fx/-ctx` → `reg-event` codemod lane, the same shape one
// tree over.
const V1_CODEMOD_LANE = {
  job: 'jvm-migration-v1-codemod',
  output: 'migration_v1_codemod',
  dir: 'migration/from-re-frame-v1/codemod',
  armed: 'migration/from-re-frame-v1/codemod/src/re_frame/migration/reg_event_codemod.clj',
};

test('the v1 codemod JVM job is gated on its own output and runs the artefact (rf2-0qzh)', () => {
  const workflow = fs.readFileSync(WORKFLOW, 'utf8');
  const block = jobBlock(workflow, V1_CODEMOD_LANE.job);
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
  assert.match(
    workflow,
    /migration_v1_codemod: \$\{\{ steps\.detect\.outputs\.migration_v1_codemod \}\}/,
    `${V1_CODEMOD_LANE.output} must be declared as a detect_changed_surfaces output`,
  );
  assert.ok(
    jobBlock(workflow, 'all-required-passed').includes(`- ${V1_CODEMOD_LANE.job}`),
    `aggregator must list ${V1_CODEMOD_LANE.job} in needs:`,
  );
});

test('the v1 codemod lane arms on its own tree and does not arm implementation_jvm (rf2-0qzh)', () => {
  // ONE-WAY: core is on the codemod's :integration classpath (the core test
  // below), but the codemod is downstream of core, not upstream.
  const result = classify(V1_CODEMOD_LANE.armed);
  assert.equal(result[V1_CODEMOD_LANE.output], 'true');
  assert.equal(result.implementation_jvm, 'false');
});

// Core's own fan-out plus every reverse edge into it: the tools JVM and CLJS
// lanes whose suites exercise core over a :local/root, the v1 codemod's
// :integration alias, the template's emitted app, the adapter JVM probes, the
// docs playground bundle and the two skill fixtures.
test('implementation/core arms its fan-out and every reverse-edge lane (rf2-fk5jy, rf2-wq17m, rf2-jdj17.1)', () => {
  const result = classify('implementation/core/src/re_frame/core.cljc');
  for (const output of [
    'implementation_jvm',
    'cljs_node_test',
    'adapter_diagnostic',
    'cljs_browser',
    'cljs_prod',
    'bundle_isolation',
    'tools_jvm',
    'template_expensive',
    'mcp_conformance',
    'mcp_live',
    'playground',
    'tools_jvm_machines_viz',
    'tools_jvm_testbed_support',
    'tools_cljs_machines_viz',
    'migration_v1_codemod',
    'skills_structural',
  ]) {
    assert.equal(result[output], 'true', `a core change must arm ${output}`);
  }
});

// The ssr-node package's lane. Its suites run as
// `node implementation/ssr-node/test/run.cjs`, plain CommonJS that no other
// output's job reaches.
const SSR_NODE_LANE = {
  job: 'node-ssr-node',
  output: 'ssr_node',
  script: 'test:ssr-node',
  runner: 'implementation/ssr-node/test/run.cjs',
  src: 'implementation/ssr-node/src/service.cjs',
};

// THE JOB IS UNGATED. `absence.test.cjs` polices the WHOLE repo (nothing out
// there may load this package), so gating it on the package's own tree would
// blind it to every tree it polices. Re-adding the `if:` would look like
// tidying and restore exactly that blindness.
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
  // The output is live without this job's `if:`, because `jvm-node-crossing`
  // arms on it — the sidecar is half of that crossing. So it must be
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
  // a merge can skip past.
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
  // implementation/package.json, which DEFINES the gate, arms it too — see the
  // build-config row below.
  assert.equal(classify(SSR_NODE_LANE.src)[SSR_NODE_LANE.output], 'true');
});

// The viewer PAGE lane. README.md and spec/API.md document building the
// `:machines-viz-viewer` bundle as the way a consumer self-hosts the page, so
// this lane compiles it.
test('the machines-viz viewer-page job is gated on its own output and runs the recipe (rf2-8m344)', () => {
  const workflow = fs.readFileSync(WORKFLOW, 'utf8');
  const block = jobBlock(workflow, 'machines-viz-viewer-page');
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

// tools/machines-viz's OWN CLJS lane. `engine_grammar_parity_test.cljc` and
// `mermaid_public_smoke_test.cljc` end `-test`, so the consolidated
// `cljs-test$` selector never reaches them; the artefact's own
// `:machines-viz-node-test` build does.

test('the machines-viz CLJS job is gated on its own output and runs the artefact build (rf2-odlm3)', () => {
  const workflow = fs.readFileSync(WORKFLOW, 'utf8');
  const block = jobBlock(workflow, 'cljs-tools-machines-viz');
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
  const config = fs.readFileSync(
    path.join(REPO_ROOT, 'tools', 'machines-viz', 'shadow-cljs.edn'),
    'utf8',
  );
  assert.match(config, /:machines-viz-node-test/);
  assert.match(config, /:target\s+:node-test/);
  // The selector must be the artefact's own prefix, NOT the shared bundle's
  // `cljs-test$`. Compared as a plain string: the selector is a regex written
  // into EDN, and a third layer of escaping is how a pin ends up matching
  // nothing and passing anyway.
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
  assert.match(pkg.scripts['test:tools-machines-viz'], /machines-viz-node-test/);
});

// template_expensive is armed by the generated app's ACTUAL inputs: its `:dev`
// build compiles Story and Xray through their declared deps, and both declare
// `:paths ["src"]`, so `src/**` plus each `deps.edn` is the whole of it.
test('Story/Xray src and deps.edn arm template_expensive; the rest of their trees does not (rf2-3x7nj.37.1)', () => {
  for (const file of [
    'tools/story/src/re_frame/story.cljc',
    'tools/story/deps.edn',
    'tools/xray/src/day8/re_frame2_xray/preload.cljs',
    'tools/xray/deps.edn',
  ]) {
    assert.equal(classify(file).template_expensive, 'true', file);
  }
  // ...and the tool's own lanes stay armed alongside.
  const src = classify('tools/story/src/re_frame/story.cljc');
  assert.equal(src.tools_jvm, 'true');
  assert.equal(src.mcp_conformance, 'true');
  const outside = classify('tools/story/test/re_frame/story/golden_test.cljc');
  assert.equal(outside.template_expensive, 'false');
  assert.equal(outside.tools_jvm, 'true');
});

// The wire-vocab JVM suites (tools/mcp-conformance/wire-vocab) READ HTTP,
// machines and resources source AS TEXT through a slurp, with no classpath edge
// for anything to notice, and run only in mcp-conformance-wire-vocab. The arm
// is each tree's `src/*` rather than the suites' rostered files, which trail the
// emitters; test/ and deps.edn are not read.
test('the wire-vocab source trees arm mcp_conformance, src text only (rf2-01dix, rf2-17a0v)', () => {
  for (const file of [
    'implementation/http/src/re_frame/http/transport.cljc',
    'implementation/machines/src/re_frame/machines/transition.cljc',
    'implementation/resources/src/re_frame/resources/events.cljc',
  ]) {
    assert.equal(
      classify(file).mcp_conformance,
      'true',
      `${file} is read as text by the wire-vocab conformance suites`,
    );
  }
  assert.equal(
    classify('implementation/http/test/re_frame/http_decode_test.clj').mcp_conformance,
    'false',
  );
});

// The docs' live-cell SCI bundle bakes in fresco and the optional artefacts.
// For these six, source and deps arm the playground job; their tests are not
// baked in.
test('fresco, http, resources, routing, epoch and ssr arm playground from src and deps.edn only', () => {
  for (const file of [
    'implementation/fresco/src/re_frame/fresco.cljc',
    'implementation/fresco/deps.edn',
    'implementation/http/src/re_frame/http/managed.cljc',
    'implementation/http/deps.edn',
    'implementation/resources/src/re_frame/resources.cljc',
    'implementation/resources/deps.edn',
    'implementation/routing/src/re_frame/routing.cljc',
    'implementation/routing/deps.edn',
    'implementation/epoch/src/re_frame/epoch.cljc',
    'implementation/epoch/deps.edn',
    'implementation/ssr/src/re_frame/ssr.cljc',
    'implementation/ssr/deps.edn',
  ]) {
    assert.equal(classify(file).playground, 'true', `${file} is baked into the playground bundle`);
  }
  assert.equal(
    classify('implementation/fresco/test/re_frame/fresco/intent_cljs_test.cljs').playground,
    'false',
  );
});

// tools/template arms its own jvm-tools-template lane, and adapter_diagnostic
// for the reverse edge: `uix_consumer_deps_recipe_test.clj` (jvm-uix) slurps a
// template file as its version source, so a template-only diff can red jvm-uix.
test('tools/template arms template_expensive and adapter_diagnostic (rf2-jdj17.1, rf2-q4s8)', () => {
  const result = classify('tools/template/src/day8/re_frame2_template/hooks.clj');
  assert.equal(result.template_expensive, 'true');
  assert.equal(result.adapter_diagnostic, 'true');
});

const UIX_RECIPE_TEST_REL =
  'implementation/adapters/uix/test/re_frame/adapter/uix_consumer_deps_recipe_test.clj';

test('the UIx recipe test still reads a tools/template path that arms adapter_diagnostic (rf2-q4s8)', () => {
  const source = fs.readFileSync(path.join(REPO_ROOT, UIX_RECIPE_TEST_REL), 'utf8');
  const m = source.match(/"(tools\/template\/[^"]+)"/);
  assert.ok(
    m,
    `${UIX_RECIPE_TEST_REL} no longer reads any tools/template path. If that cross-tree read is genuinely gone, the tools/template -> adapter_diagnostic arm in report-changed-surfaces.sh has lost its justification and should be retired rather than left firing three unrelated adapter probes.`,
  );
  assert.equal(
    classify(m[1]).adapter_diagnostic,
    'true',
    `${UIX_RECIPE_TEST_REL} reads ${m[1]}, so that path must arm adapter_diagnostic`,
  );
});

// The build-config trio DEFINES several gates: shadow-cljs.edn declares the
// fresco testbed builds, the viewer-page build and the static export build;
// package.json carries the gate scripts, the emitted template's npm pins and
// the playwright pin that IS the engine revisions under test; the lockfile
// fixes them. The generic implementation/scripts/* arm sets none of the
// single-gate outputs.
test('the build-config files arm every gate they define (rf2-6yuzo4, rf2-ga8m, rf2-hic-015, rf2-8m344, rf2-xurxw, rf2-n8vp)', () => {
  for (const [file, outputs] of [
    [
      'implementation/shadow-cljs.edn',
      ['cljs_node_test', 'examples_compile', 'fresco_controlled', 'fresco_hmr', 'machines_viz_viewer_page', 'story_static_gate'],
    ],
    [
      'implementation/package.json',
      ['examples_compile', 'template_expensive', 'fresco_controlled', 'fresco_hmr', 'ssr_node'],
    ],
    [
      'implementation/package-lock.json',
      ['examples_compile', 'template_expensive', 'fresco_controlled', 'fresco_hmr'],
    ],
  ]) {
    const result = classify(file);
    for (const output of outputs) {
      assert.equal(result[output], 'true', `${file} must arm ${output}`);
    }
  }
  const generic = classify('implementation/scripts/build-foo.cjs');
  for (const output of ['template_expensive', 'story_static_gate']) {
    assert.equal(generic[output], 'false', `a generic implementation/scripts/* edit must not arm ${output}`);
  }
});

// Each gate's own launcher has an arm above the generic implementation/scripts/*
// one, so a PR editing a gate's teeth runs that gate. Each arm WIDENS: it
// re-sets every output the generic arm would have set.
const GENERIC_SCRIPT_OUTPUTS = [
  'cljs_node_test',
  'cljs_browser',
  'cljs_prod',
  'bundle_isolation',
  'reagent_slim_bundle',
];

test('each gate launcher arms its own gate and keeps the generic static-script outputs (rf2-5v0dg7, rf2-h5e3v7, rf2-ga8m, rf2-hic-015, rf2-9n2cv)', () => {
  for (const [file, gate] of [
    ['implementation/scripts/serve-and-run-xray-feature-gate.cjs', 'story_xray_browser'],
    ['implementation/scripts/serve-and-run-reagent-slim-smoke.cjs', 'reagent_slim_bundle'],
    ['implementation/scripts/serve-and-run-tenant-switcher-testbed.cjs', 'tenant_switcher_smoke'],
    ['implementation/scripts/serve-and-run-fresco-controlled-testbed.cjs', 'fresco_controlled'],
    ['implementation/scripts/serve-and-run-fresco-hmr-testbed.cjs', 'fresco_hmr'],
    ['implementation/scripts/check-story-static.cjs', 'story_static_gate'],
    ['implementation/scripts/story-build.cjs', 'story_static_gate'],
  ]) {
    const result = classify(file);
    for (const output of new Set([gate, ...GENERIC_SCRIPT_OUTPUTS])) {
      assert.equal(result[output], 'true', `${file} must arm ${output}`);
    }
  }
});

test('the reagent-slim adapter/testbed surface fires reagent_slim_bundle (canonical trigger) (rf2-5v0dg7)', () => {
  // The smoke testbed lives under implementation/adapters/reagent-slim/; a
  // change there must arm the slim gate (smoke included) directly.
  const result = classify('implementation/adapters/reagent-slim/testbed/smoke.cjs');
  assert.equal(result.reagent_slim_bundle, 'true');
});

// The standalone example-build compiler has its own output. Core changes defer
// the expensive all-examples compile to the nightly safety net; surfaces that
// can directly change an example build run it at PR time.
test('example compilation has a dedicated changed-surface output (rf2-gzavkm)', () => {
  const directSurfaces = [
    'examples/core/counter/core.cljs',
    'implementation/deps.edn',
    'implementation/adapters/uix/src/re_frame/adapter/uix.cljs',
    'implementation/epoch/src/re_frame/epoch.cljc',
    'implementation/schemas/src/re_frame/schemas.cljc',
    'implementation/machines/src/re_frame/machines.cljc',
    'implementation/routing/src/re_frame/routing.cljc',
    'implementation/flows/src/re_frame/flows.cljc',
    'implementation/http/src/re_frame/http/managed.cljc',
    'implementation/ssr/src/re_frame/ssr.cljc',
    'implementation/ssr-ring/src/re_frame/ssr/ring.clj',
    'implementation/resources/src/re_frame/resources.cljc',
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
  assert.equal(
    classify('implementation/core/src/re_frame/core.cljc').examples_compile,
    'false',
    'core defers the all-examples compile to the nightly',
  );
});

test('cljs-examples-compile job uses the dedicated output and nightly retains full coverage (rf2-gzavkm)', () => {
  const prBlock = jobBlock(fs.readFileSync(WORKFLOW, 'utf8'), 'cljs-examples-compile');
  assert.match(
    prBlock,
    /if: needs\.detect_changed_surfaces\.outputs\.examples_compile == 'true'/,
  );
  const nightly = fs.readFileSync(EXPENSIVE_WORKFLOW, 'utf8');
  const nightlyBlock = jobBlock(nightly, 'browser-bundle-and-story-gates');
  assert.notEqual(
    nightlyBlock.indexOf('npm run test:examples-compile'),
    -1,
    'nightly must retain the all-examples compile',
  );
});

// The re-frame2-pair skill ships a dev-only preload
// (skills/re-frame2-pair/preload/) that shadow-cljs.edn injects into the
// example dev builds. Its stateful runtime has two owning behavioral gates —
// cljs-browser (re-frame.pair-dispatch-and-settle-dom-cljs-test) and
// mcp-conformance-re-frame2-pair (mcp_live) — and the wire-vocab suite reads
// runtime.cljs as text (mcp_conformance). The rest of the skill tree is prose.
test('NON-preload re-frame2-pair skill file stays structural-only (scope discipline) (rf2-k8yl5f, rf2-11yjq)', () => {
  const result = classify('skills/re-frame2-pair/SKILL.md');
  assert.equal(result.skills_structural, 'true');
  for (const output of ['examples_compile', 'cljs_browser', 'mcp_live', 'mcp_conformance']) {
    assert.equal(result[output], 'false', `a non-preload skill file must NOT arm ${output}`);
  }
});

test('the shipped preload arms its behavioral gates, its wire-vocab reader, compile and structural (rf2-11yjq)', () => {
  const result = classify('skills/re-frame2-pair/preload/re_frame2_pair/runtime.cljs');
  for (const output of ['cljs_browser', 'mcp_live', 'mcp_conformance', 'examples_compile', 'skills_structural']) {
    assert.equal(result[output], 'true', `the preload runtime must arm ${output}`);
  }
});

// The retro, migration and improver skill trees each need an arm of their own:
// the main case has no default arm, so a tree no arm names classifies to ZERO
// outputs. The pair-retro tree cannot inherit the pair arm — the pattern
// `skills/re-frame2-pair/*` needs a `/` straight after `pair` — and the loop
// below pins that boundary from the other side: retro paths must not arm the
// pair tree's expensive gates. One file per tree.
const SKILLS_STRUCTURAL_ONLY_FILES = pinnedRoster('SKILLS_STRUCTURAL_ONLY_FILES', [
  'skills/re-frame2-pair-retro/tests/duplicate_search_test.clj',
  'skills/reagent-fresco-migration/tests/fixture/test/reagent_fresco_migration/mig23_cold_start_test.cljs',
  'skills/re-frame2-improver/tests/storage_materializer_test.clj',
]);
for (const file of SKILLS_STRUCTURAL_ONLY_FILES) {
  test(`${file} arms skills_structural (rf2-g1m2q)`, () => {
    const result = classify(file);
    assert.equal(
      result.skills_structural,
      'true',
      `${file} is scheduled by the skills_structural tier, so its tree needs an arm of its own`,
    );
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

// The improver arm's other boundary: `skills/re-frame2-implementor/` shares the
// prefix `skills/re-frame2-imp`, carries no tests and has no arm, so a pattern
// loosened to `skills/re-frame2-imp*` would schedule a job with nothing to run.
test('skills/re-frame2-implementor/ does not inherit the improver arm (rf2-z65e)', () => {
  const result = classify('skills/re-frame2-implementor/SKILL.md');
  assert.equal(
    result.skills_structural,
    'false',
    'the implementor tree shares the prefix `skills/re-frame2-imp` with the ' +
      'improver tree but carries no tests; an arm matching it would schedule ' +
      'the skills-structural job for a tree with nothing to run.',
  );
});

// The REVERSE edge into the two skill fixtures, both gated on
// skills_structural. The MIG-23 SSR cold-start fixture and the re-frame2-pair
// fixture resolve these in-repo artefacts as `:local/root` — the union of the
// two `:deps` maps, `src/*` plus `deps.edn` — so a substrate change has to run
// the fixture that pins it. Core is pinned by the core fan-out test above. An
// artefact's own test/ tree is on neither classpath.
test('every skill-fixture :local/root arms skills_structural, and only src + deps.edn (rf2-bbe91, rf2-f9f3p)', () => {
  for (const file of [
    'implementation/core/deps.edn',
    'implementation/ssr/src/re_frame/ssr.cljc',
    'implementation/fresco/src/re_frame/fresco/server.cljs',
    'implementation/adapters/reagent/src/re_frame/adapter/reagent.cljs',
    'implementation/adapters/reagent/deps.edn',
    'implementation/epoch/src/re_frame/epoch.cljc',
    'implementation/epoch/deps.edn',
    'implementation/schemas/src/re_frame/schemas.cljc',
    'implementation/schemas/deps.edn',
    'implementation/machines/src/re_frame/machines.cljc',
    'implementation/machines/deps.edn',
  ]) {
    assert.equal(
      classify(file).skills_structural,
      'true',
      `${file} is on a skill fixture's :local/root classpath and must arm skills_structural`,
    );
  }
  assert.equal(
    classify('implementation/schemas/test/re_frame/late_bind_missing_test.clj').skills_structural,
    'false',
    'an artefact test/ tree is on no fixture classpath',
  );
});

// The reverse-edge dispatches above only ever SET their outputs, so they cannot
// narrow a per-feature artefact's production routing. Pinned per artefact,
// because a refactor into an arm of the big first-match `case` would keep the
// common outputs and silently drop the rest.
test('every per-feature artefact keeps its production fan-out (rf2-qxg24, rf2-f9f3p, rf2-bbe91)', () => {
  const PRODUCTION = [
    'implementation_jvm',
    'cljs_node_test',
    'cljs_browser',
    'examples_compile',
    'cljs_prod',
    'bundle_isolation',
  ];
  for (const [file, extra] of [
    ['implementation/epoch/src/re_frame/epoch.cljc', ['mcp_conformance', 'mcp_live']],
    [
      'implementation/machines/src/re_frame/machines.cljc',
      ['tools_jvm_machines_viz', 'tools_cljs_machines_viz', 'playground'],
    ],
    ['implementation/schemas/src/re_frame/schemas.cljc', []],
    ['implementation/routing/src/re_frame/routing.cljc', []],
    ['implementation/flows/src/re_frame/flows.cljc', []],
    ['implementation/http/src/re_frame/http/managed.cljc', []],
    ['implementation/ssr/src/re_frame/ssr.cljc', []],
    ['implementation/resources/src/re_frame/resources.cljc', []],
  ]) {
    const result = classify(file);
    for (const key of [...PRODUCTION, ...extra]) {
      assert.equal(result[key], 'true', `${file} must retain ${key}`);
    }
  }
});

// Through the REAL Git-derived discovery path, `--no-renames` emits BOTH
// endpoints of a rename, so a rename OUT of the preload still arms the gates
// for the deleted endpoint. (renameViaGitDiscovery is a hoisted declaration
// further down.)
const PRELOAD_SOURCE = 'skills/re-frame2-pair/preload/re_frame2_pair/runtime.cljs';
const PRELOAD_GATE_KEYS = ['examples_compile', 'skills_structural', 'cljs_browser', 'mcp_live'];

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

// The resilient Clojure CLI install lives in ONE script,
// `.github/scripts/install-clojure-cli.sh`, which every job needing the CLI
// calls. These pin that it stays one script, and keeps its resilience.
const CLOJURE_INSTALLER = path.join(REPO_ROOT, '.github', 'scripts', 'install-clojure-cli.sh');
const WORKFLOW_DIR = path.join(REPO_ROOT, '.github', 'workflows');
const allWorkflows = () =>
  fs
    .readdirSync(WORKFLOW_DIR)
    .filter((f) => f.endsWith('.yml'))
    .map((f) => ({ name: f, text: fs.readFileSync(path.join(WORKFLOW_DIR, f), 'utf8') }));

test('the shared Clojure CLI installer exists and keeps its failure boundary (rf2-e7ja9)', () => {
  const src = fs.readFileSync(CLOJURE_INSTALLER, 'utf8');
  assert.match(src, /^set -euo pipefail$/m, 'installer must run under set -euo pipefail');
  // At least six whole attempts: github.com 5xx storms outlast a three-attempt
  // envelope. Stated as a floor, so widening never has to come here.
  const attemptsPin = src.match(/^attempts=(\d+)$/m);
  assert.ok(
    Number(attemptsPin[1]) >= 6,
    `installer must make at least 6 whole attempts (found ${attemptsPin[1]}) — three do not ` +
      'outlast the observed 503 storms',
  );
  assert.match(
    src,
    /curl -fsSL --retry 5 --retry-all-errors --retry-delay 3/,
    'installer must keep the curl retry policy',
  );
  // Bounded backoff with jitter: ~59 jobs install concurrently, and without
  // jitter they retry in lockstep.
  assert.match(
    src,
    /delay=\$\(\(attempt \* \d+ \+ RANDOM % \d+\)\)/,
    'installer must keep the bounded backoff, with a jitter term',
  );
  assert.match(src, /sleep "\$delay"/, 'installer must sleep the computed backoff');
  // On exhaustion the step must say so as infrastructure: falling through to
  // `clojure --version` dies exit 127, a red that reads exactly like the gate
  // having failed when no gate ran.
  assert.match(
    src,
    /echo "::error title=[^"]*::/,
    'installer must fail exhaustion as an explicit ::error annotation, so a job that never ran ' +
      'its gate is distinguishable from a gate that failed WITHOUT reading the raw log',
  );
  assert.match(
    src,
    /^\s*exit 1$/m,
    'installer must exit nonzero on exhaustion rather than falling through to `clojure --version` ' +
      "— a fall-through's exit 127 is the masking failure",
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
        '(the policy lives in one script, not in a copy per job)',
    );
    assert.doesNotMatch(
      text,
      /uses:\s*\S*setup-clojure@/,
      `${name} must not use the setup-clojure action — its un-retried curl is the ` +
        'transient curl-35 / socket-hang-up flake the shared installer retries through',
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

// The adapter-disposition guard scans a FIXED cross-repo roster, not the diff,
// so it runs in the UNCONDITIONAL verify-readme-links job rather than behind a
// surface gate that a PR editing a roster file might not fire.
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

// The fast-PR spine's tiering + mkdocs-resolution harness runs in the same
// unconditional job; run by no workflow, every assertion in it would be
// local-only. Deleting the step leaves valid YAML and a green matrix.
test('the fast-PR spine self-test harness is wired into a REQUIRED check (rf2-03298)', () => {
  const workflow = fs.readFileSync(WORKFLOW, 'utf8');
  const readmeLinks = jobBlock(workflow, 'verify-readme-links');
  assert.match(
    readmeLinks,
    /bash scripts\/_test_fixtures\/test_fast_pr_docs_gate\/run-self-test\.sh/,
    'verify-readme-links must run the fast-PR spine self-test harness (unconditional job)',
  );
  const aggregator = jobBlock(workflow, 'all-required-passed');
  assert.match(
    aggregator,
    /^\s*- verify-readme-links\s*$/m,
    'all-required-passed must keep verify-readme-links in needs: — otherwise the spine self-test harness is advisory',
  );
});

// One job's block of a workflow: from its 2-space-indented header to the next.
// CRLF-tolerant.
function jobBlock(workflow, jobName) {
  const header = new RegExp(`\\n {2}${jobName}:\\r?\\n`);
  const m = header.exec(workflow);
  assert.notEqual(m, null, `${jobName} job not found in test.yml`);
  const rest = workflow.slice(m.index + 1);
  const nextJob = rest.search(/\n {2}[A-Za-z0-9_-]+:\r?\n/);
  return nextJob === -1 ? rest : rest.slice(0, nextJob);
}

// Arming an output binds nothing unless the job that runs the suites is still
// gated on it. (An `if:` reading `needs.detect_changed_surfaces` without the
// matching `needs:` fails workflow validation, so `needs:` is not pinned.)
test('each surface-gated job reads the output that arms its suites', () => {
  const workflow = fs.readFileSync(WORKFLOW, 'utf8');
  for (const [job, output, runs] of [
    ['jvm-core', 'implementation_jvm'],
    ['jvm-machines', 'implementation_jvm'],
    ['jvm-routing', 'implementation_jvm'],
    ['jvm-ssr', 'implementation_jvm'],
    ['jvm-schemas', 'implementation_jvm'],
    ['jvm-security', 'implementation_jvm', /implementation\/security/],
    ['jvm-spec-resource', 'implementation_jvm'],
    ['jvm-resources', 'implementation_jvm'],
    ['jvm-reply-conformance', 'implementation_jvm'],
    ['jvm-derivation-conformance', 'implementation_jvm'],
    ['jvm-event-conformance', 'implementation_jvm'],
    ['cljs', 'cljs_node_test'],
    ['jvm-tools-xray', 'tools_jvm'],
    ['jvm-tools-template', 'template_expensive'],
    ['mcp-conformance-wire-vocab', 'mcp_conformance'],
    ['mcp-conformance-re-frame2-pair', 'mcp_live'],
    ['re-frame2-pair-fixture-pure', 'skills_structural'],
    ['reagent-fresco-migration-fixture-cold-start', 'skills_structural'],
    ['tenant-switcher-testbed-smoke', 'tenant_switcher_smoke', /npm run test:testbed-tenant-switcher/],
  ]) {
    const block = jobBlock(workflow, job);
    assert.match(
      block,
      new RegExp(`if: needs\\.detect_changed_surfaces\\.outputs\\.${output} == 'true'`),
      `${job} must be gated on ${output}, or arming it schedules nothing`,
    );
    if (runs) assert.match(block, runs, `${job} must run its suite`);
  }
});

test('the All required checks passed aggregator always runs and needs every required job (rf2-f79t8)', () => {
  const workflow = fs.readFileSync(WORKFLOW, 'utf8');
  assert.match(workflow, /name: All required checks passed/);
  const block = jobBlock(workflow, 'all-required-passed');
  assert.match(block, /if: \$\{\{ always\(\) \}\}/);
  for (const job of [
    'jvm-core',
    'cljs',
    'jvm-security',
    'jvm-resources',
    'jvm-reply-conformance',
    'jvm-derivation-conformance',
    'jvm-event-conformance',
    'jvm-spec-resource',
    'tenant-switcher-testbed-smoke',
    'beads-pr-boundary',
  ]) {
    // `- cljs` followed directly by a line break, not `- cljs-browser`.
    assert.match(
      block,
      new RegExp(`- ${job}\\r?\\n`),
      `aggregator must list ${job} in needs: — a job absent from it is advisory`,
    );
  }
});

// The aggregator BLOCKS on a cancelled required job as well as a failed one —
// with the cancelled arm gone, a cancelled required job reads GREEN — and
// says which it is, so a transient does not read as a defect.
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

// The story-xray-browser PR job runs three tiers, each as its own step under
// its own output: the PR-smoke tier (the Xray gate in --smoke mode and the
// Story :play-script gate), the FULL feature-load gate and the static-export
// gate. The full sweep also runs nightly in expensive-tests.yml.
const EXPENSIVE_WORKFLOW = path.join(
  REPO_ROOT,
  '.github',
  'workflows',
  'expensive-tests.yml',
);

// The step that RUNS `command`: from its `- name:` header through the `run:`
// line, so the step's own `if:` is inside and a neighbour's is not. Anchored on
// `run: ` because every command is also named in the surrounding prose.
function stepRunning(block, command) {
  const marker = `run: ${command}`;
  const idx = block.indexOf(marker);
  if (idx === -1) return null;
  const start = block.lastIndexOf('\n      - name:', idx);
  return start === -1 ? null : block.slice(start, idx + marker.length);
}

test('each story-xray-browser tier runs as a step gated on its own output (rf2-65ajl, rf2-9n2cv)', () => {
  // The command and its condition in the SAME step, or the gate is either
  // unconditional or dead.
  const block = jobBlock(fs.readFileSync(WORKFLOW, 'utf8'), 'story-xray-browser');
  for (const [command, output] of [
    ['npm run test:story-feature-load', 'story_full_gate'],
    ['npm run test:story-static', 'story_static_gate'],
    ['npm run test:xray-feature-gate:smoke', 'story_xray_browser'],
    ['npm run test:story-play-scripts', 'story_xray_browser'],
  ]) {
    const step = stepRunning(block, command);
    assert.ok(step, `the PR job must RUN ${command} in a named step`);
    assert.match(
      step,
      new RegExp(`if:\\s*needs\\.detect_changed_surfaces\\.outputs\\.${output} == 'true'`),
      `${command} must be gated on ${output}`,
    );
  }
});

// The never-fires mode. A job reads `needs.<job>.outputs.<name>`, which
// resolves to the empty string unless the producing job DECLARES it, so an
// undeclared output makes every `== 'true'` false — a gate that can never fire,
// silently.
test('detect_changed_surfaces exports the narrow outputs the story and test-react jobs read (rf2-9n2cv, rf2-65ajl, rf2-6r9j.87)', () => {
  const block = jobBlock(fs.readFileSync(WORKFLOW, 'utf8'), 'detect_changed_surfaces');
  for (const output of ['story_static_gate', 'story_full_gate', 'test_react_jvm']) {
    assert.match(
      block,
      new RegExp(`${output}: \\$\\{\\{ steps\\.detect\\.outputs\\.${output} \\}\\}`),
      `${output} must be declared as a detect_changed_surfaces output`,
    );
  }
});

test('every script the static gate spawns is armed (rf2-9n2cv)', () => {
  // The teeth. Read the roster off the GATE rather than trusting the launcher
  // table: check-story-static.cjs spawns its build step by name, so a second
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

// The runtime-path predicate arms the smoke tier AND the static gate: the
// static export's release closure is tools/story/src/** plus tools/xray/src/**
// plus its testbed entry, and arming the job is not arming the step. One row
// per glob alternative and runtime extension, plus the named macros.clj.
test('Story/Xray runtime source arms the smoke tier and the static gate (rf2-xurxw, rf2-65ajl)', () => {
  for (const file of [
    'tools/xray/src/day8/re_frame2_xray/core.cljs',
    'tools/story/src/re_frame/story/ui/shell.cljs',
    'tools/story/src/re_frame/story/macros.clj',
    'tools/story/testbeds/counter_with_stories/story_static.cljs',
    'tools/story/testbeds/counter_with_stories/story_static.index.html',
    'tools/xray/testbeds/feature_matrix/scenarios.cjs',
  ]) {
    const result = classify(file);
    assert.equal(result.story_xray_browser, 'true', file);
    assert.equal(result.story_static_gate, 'true', file);
  }
});

test('a runtime-extension file outside src/ and testbeds/ arms neither browser tier (rf2-xurxw negative control)', () => {
  const result = classify('tools/xray/test/day8/re_frame2_xray/registry_cljs_test.cljs');
  assert.equal(result.story_static_gate, 'false');
  assert.equal(result.story_xray_browser, 'false');
});

test('PR story-xray-browser job opens for any of its three tiers (rf2-65ajl, rf2-9n2cv)', () => {
  // A full-gate-only or static-gate-only change leaves story_xray_browser
  // false, so a job condition that did not name every tier would skip the job.
  const block = jobBlock(fs.readFileSync(WORKFLOW, 'utf8'), 'story-xray-browser');
  const header = block.slice(0, block.indexOf('steps:'));
  assert.match(header, /outputs\.story_xray_browser == 'true'/);
  assert.match(header, /outputs\.story_full_gate == 'true'/);
  assert.match(header, /outputs\.story_static_gate == 'true'/);
  assert.match(header, /\|\|/, 'the tier conditions must be a disjunction');
});

test('Nightly expensive workflow runs the full Story/Xray sweep (rf2-wa3oo)', () => {
  const workflow = fs.readFileSync(EXPENSIVE_WORKFLOW, 'utf8');
  assert.match(workflow, /npm run test:story-feature-load/);
  assert.match(workflow, /npm run test:xray-feature-gate\b/);
  assert.match(workflow, /npm run test:story-static/);
  assert.match(workflow, /npm run test:story-play-scripts/);
});

// A top-level testbed's assertions live in CLJS/JVM unit tests, so its source
// needs the transitive CLJS coverage of cljs_browser, and examples_compile:
// check-examples-compile.cjs derives `:testbeds/*` alongside `:examples/*`
// and is the only PR-time job that compiles those builds.
test('a top-level testbed .cljs arms cljs_browser and examples_compile (rf2-t5slp, rf2-in6c4)', () => {
  const result = classify('testbeds/ssr_basic/core.cljs');
  assert.equal(result.cljs_browser, 'true');
  assert.equal(
    result.examples_compile,
    'true',
    'check-examples-compile.cjs is the only PR-time job that compiles a ' +
      'top-level :testbeds/* build; without this arm a compile break there ' +
      'ships green',
  );
});

// The examples_compile arm is extension-narrowed to shadow's own `.cljs` /
// `.cljc`; a new testbed build arms it through implementation/shadow-cljs.edn.
test('a testbed README does NOT fire examples_compile (extension narrowing, rf2-in6c4)', () => {
  const result = classify('testbeds/README.md');
  assert.equal(
    result.examples_compile,
    'false',
    'no markdown file can change what shadow-cljs compile produces',
  );
});

// The adapter-smoke harness lives with the adapters it drives, under
// implementation/adapters/scripts/, with an arm of its own above the broad
// adapter arm.
test('adapter-smoke harness edit fires adapter_testbed_smokes', () => {
  const result = classify('implementation/adapters/scripts/serve-and-run-adapter-smokes.cjs');
  assert.equal(result.adapter_testbed_smokes, 'true');
});

// Test-React is a local-only CLJC test fixture: no Maven coordinate, no
// production or example consumer, no browser testbed. Exactly two lanes reach
// it — `jvm-adapters-test-react` (test_react_jvm) and Shadow's consolidated
// `:node-test` — so it is carved out of BOTH case statements a
// implementation/adapters/* path reaches, and each file arms exactly the lanes
// that consume it.
test('Test-React arms exactly the lanes that consume each file (rf2-6r9j.87)', () => {
  for (const [file, armed] of [
    ['implementation/adapters/test-react/src/re_frame/adapter/test_react.cljc', ['cljs_node_test', 'test_react_jvm']],
    ['implementation/adapters/test-react/test/re_frame/adapter/test_react_cljs_test.cljc', ['cljs_node_test', 'test_react_jvm']],
    // deps.edn feeds the JVM lane only: shadow reads its source paths and
    // dependencies from implementation/shadow-cljs.edn.
    ['implementation/adapters/test-react/deps.edn', ['test_react_jvm']],
    // Prose opens no lane, and must not reach the examples_compile alternation
    // in the FIRST case statement either.
    ['implementation/adapters/test-react/README.md', []],
  ]) {
    assert.deepEqual(armedOutputs(classify(file)), armed, file);
  }
});

// The outputs the published adapters fire and Test-React does not.
const TEST_REACT_RETIRED_OUTPUTS = [
  'implementation_jvm',
  'adapter_diagnostic',
  'cljs_browser',
  'examples_compile',
  'cljs_prod',
  'bundle_isolation',
  'adapter_testbed_smokes',
  'tools_jvm',
  'template_expensive',
  'mcp_conformance',
  'mcp_live',
];

// `case` is first-match, so a carve-out written with a loose glob would swallow
// its siblings silently.
test('published adapters keep the full fan-out after the Test-React carve-out (rf2-6r9j.87)', () => {
  const file = 'implementation/adapters/uix/src/re_frame/adapter/uix.cljs';
  const result = classify(file);
  for (const output of TEST_REACT_RETIRED_OUTPUTS) {
    assert.equal(
      result[output],
      'true',
      `${file} must still fire ${output} — only Test-React was narrowed`,
    );
  }
  assert.equal(result.cljs_node_test, 'true');
});

// The core reverse edge rides adapter_diagnostic; the narrow forward edge is
// test_react_jvm. The job reads the disjunction.
test('jvm-adapters-test-react is gated on the disjunction (rf2-6r9j.87)', () => {
  const block = jobBlock(fs.readFileSync(WORKFLOW, 'utf8'), 'jvm-adapters-test-react');
  const header = block.slice(0, block.indexOf('steps:'));
  assert.match(header, /outputs\.adapter_diagnostic == 'true'/, 'core reverse edge');
  assert.match(header, /outputs\.test_react_jvm == 'true'/, 'narrow forward edge');
  assert.match(header, /\|\|/, 'the two edges must be a disjunction');
});

// mark_all() has to stay TOTAL. `force_all` and the workflow-file arms rely on
// it, so a signal missing there makes a forced full run SKIP the job — the
// narrowing's own fail-open.
test('a forced full run still arms test_react_jvm (rf2-6r9j.87)', () => {
  assert.equal(classify('--all').test_react_jvm, 'true');
  assert.equal(classify('.github/workflows/test.yml').test_react_jvm, 'true');
});

// The same control one level down. scripts/test-fast-pr.sh gates its whole
// JVM tier on the classifier's output, and the Test-React arm does not set
// `implementation_jvm`, so unless the spine reads the narrow signal too, a
// Test-React-only LOCAL diff skips its own JVM suite silently.
test('the local spine consumes test_react_jvm for its JVM tier (rf2-6r9j.87)', () => {
  const spine = fs.readFileSync(path.join(REPO_ROOT, 'scripts', 'test-fast-pr.sh'), 'utf8');
  assert.match(
    spine,
    /test_react_jvm\)\s+test_react_jvm="\$value" ;;/,
    'the spine must read test_react_jvm out of the classifier output',
  );
  assert.match(
    spine,
    /\[ "\$test_react_jvm" = true \][^\n]*run_jvm=true/,
    'the spine must arm its JVM tier on test_react_jvm',
  );
});

// Every EXECUTABLE examples/scripts gate file fires every browser gate that
// loads it, so a PR breaking a launcher or a shared helper cannot avoid the
// gate it can break. Each has a named arm, which also stops the walk before
// the generic `examples/*` fan-out.
test('every examples/scripts gate helper and launcher fires every gate that loads it (rf2-y9o5e3, rf2-6ng7, rf2-eqjxya, rf2-78th1g, rf2-65ajl)', () => {
  for (const [file, outputs] of [
    // The shared Playwright matchers: the adapter smokes, the Story/Xray
    // PR-smoke tier and the tenant-switcher spec all require it.
    ['examples/scripts/spec-helpers.cjs', ['adapter_testbed_smokes', 'story_xray_browser', 'tenant_switcher_smoke']],
    ['examples/scripts/examples-port.cjs', ['adapter_testbed_smokes']],
    // Shared by the adapter-smoke orchestrator and the Story launchers.
    ['examples/scripts/port-resolver.cjs', ['adapter_testbed_smokes', 'story_xray_browser']],
    ['examples/scripts/examples-staging.cjs', ['adapter_testbed_smokes', 'story_xray_browser']],
    ['examples/scripts/examples-asset-manifest.cjs', ['adapter_testbed_smokes', 'story_xray_browser']],
    // The PR-smoke tier's command and the port resolver both tiers call.
    ['examples/scripts/serve-and-run-story-play-scripts.cjs', ['story_xray_browser']],
    ['examples/scripts/story-feature-load-port.cjs', ['story_xray_browser', 'story_full_gate']],
    // Reachable from `npm run test:story-feature-load` and nothing else.
    ['examples/scripts/serve-and-run-story-feature-load-tests.cjs', ['story_full_gate']],
    ['examples/scripts/run-story-feature-load-tests.cjs', ['story_full_gate']],
    // Required only by the Xray feature-matrix scenarios module.
    ['testbeds/spec-helpers.cjs', ['story_xray_browser']],
  ]) {
    const result = classify(file);
    for (const output of outputs) {
      assert.equal(result[output], 'true', `${file} must arm ${output}`);
    }
  }
});

test('every spec module the full-gate runner loads is armed (rf2-65ajl)', () => {
  // Read the roster off the RUNNER: `ALL_SPEC_FILES` is what
  // `npm run test:story-feature-load` executes, so a third spec added there is
  // armed on arrival.
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

test('adapter-testbed-smokes workflow remains scoped to ADAPTER_SMOKE_FILTER=adapters/ (rf2-t5slp)', () => {
  const workflow = fs.readFileSync(WORKFLOW, 'utf8');
  // Verify the adapter-testbed-smokes job passes the narrow adapters/ filter
  // (a substring OR-match against the orchestrator's shadow-cljs build ids),
  // so it runs exactly the adapter smokes and never widens onto another
  // testbed family.
  assert.match(
    workflow,
    /adapter-testbed-smokes:[\s\S]*ADAPTER_SMOKE_FILTER:\s*"adapters\/"/,
  );
});

// The source-less test tiers — the reply / derivation / event
// cross-conformance tiers and the security partition (`:paths []`, no `src/`,
// no Maven artefact) — the test-quiet reporter artefact, and the build-time
// spec-resource reader each run in their own JVM job (implementation_jvm) and
// in the consolidated :node-test build (cljs_node_test), and in nothing else:
// they ship no production source, so no browser, production, bundle or
// examples gate can observe them. spec-resource stays off cljs_browser too: its
// one macro path in is consumed only by plain `-cljs-test` namespaces, and
// :browser-test selects `-dom-cljs-test$`.
test('the test-tier artefacts arm exactly implementation_jvm + cljs_node_test (rf2-dxndhc, rf2-qxg24, rf2-am7grp)', () => {
  for (const file of [
    'implementation/reply-conformance/test/re_frame/reply_vocab_conformance_cljs_test.cljc',
    'implementation/derivation-conformance/test/re_frame/derivation_algebra_conformance_cljs_test.cljc',
    'implementation/event-conformance/test/re_frame/event_model_conformance_cljs_test.cljc',
    'implementation/security/test/re_frame/security/mcp_egress_security_cljs_test.cljc',
    'implementation/test-quiet/src/re_frame/test_quiet/runner.clj',
    'implementation/test-quiet/test/re_frame/test_quiet_runner_contract_test.clj',
    'implementation/test-quiet/deps.edn',
    'implementation/spec-resource/src/re_frame/build/spec_resource.clj',
  ]) {
    assert.deepEqual(
      armedOutputs(classify(file)),
      ['implementation_jvm', 'cljs_node_test'],
      file,
    );
  }
});

// The tenant-switcher testbed keeps its own colocated Playwright spec.cjs and
// its own smoke job; its launcher's arm is pinned by the launcher table above.
test('the tenant-switcher testbed fires tenant_switcher_smoke + cljs_browser (rf2-h5e3v7)', () => {
  const result = classify('testbeds/tenant_switcher/spec.cjs');
  assert.equal(
    result.tenant_switcher_smoke,
    'true',
    'a tenant-switcher testbed change must run its colocated browser smoke',
  );
  assert.equal(result.cljs_browser, 'true');
});

// The Fresco three-engine controlled-input gate.
const FRESCO_CONTROLLED = {
  job: 'cljs-fresco-controlled',
  output: 'fresco_controlled',
};

test('the fresco controlled-input job is gated on its own output and runs the gate (rf2-ga8m)', () => {
  const workflow = fs.readFileSync(WORKFLOW, 'utf8');
  const block = jobBlock(workflow, FRESCO_CONTROLLED.job);
  assert.match(
    block,
    /if: needs\.detect_changed_surfaces\.outputs\.fresco_controlled == 'true'/,
    `${FRESCO_CONTROLLED.job} must be gated on ${FRESCO_CONTROLLED.output}`,
  );
  assert.match(block, /npm run test:fresco-controlled/);
  assert.match(
    workflow,
    /fresco_controlled: \$\{\{ steps\.detect\.outputs\.fresco_controlled \}\}/,
    `${FRESCO_CONTROLLED.output} must be declared as a detect_changed_surfaces output`,
  );
  // Required: the only lane that witnesses I15's caret and composition clauses.
  assert.ok(
    jobBlock(workflow, 'all-required-passed').includes(`- ${FRESCO_CONTROLLED.job}`),
    `aggregator must list ${FRESCO_CONTROLLED.job} in needs:`,
  );
});

test('the fresco controlled-input job installs the PINNED three engines (rf2-ga8m)', () => {
  const block = jobBlock(fs.readFileSync(WORKFLOW, 'utf8'), FRESCO_CONTROLLED.job);

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
  // the engine it claims to. The two resolve differently — 1.59.1 inside
  // implementation/ against 1.62.1 one directory up, when measured — so
  // `--no-install` is no defence; only the working directory is.
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

// The Fresco package arm. cljs_node_test schedules the package smoke and the
// invariants gate; cljs_browser mounts the package's `-dom-cljs-test` suites,
// which the node build would report as stated green skips; and both browser
// gates compile their testbeds off this tree and witness what no Node suite
// can (the caret under three engines, a real hot reload).
test('implementation/fresco/** arms node, browser and both fresco browser gates (rf2-8a6s, rf2-ga8m, rf2-hic-015)', () => {
  const result = classify('implementation/fresco/src/re_frame/fresco.cljc');
  for (const output of ['cljs_node_test', 'cljs_browser', 'fresco_controlled', 'fresco_hmr']) {
    assert.equal(result[output], 'true', `a fresco change must arm ${output}`);
  }
});

// The Fresco HMR gate: the only lane that drives a real hot reload.
const FRESCO_HMR = {
  job: 'cljs-fresco-hmr',
  output: 'fresco_hmr',
};

test('the fresco HMR job is gated on its own output and runs the gate (rf2-hic-015)', () => {
  const workflow = fs.readFileSync(WORKFLOW, 'utf8');
  const block = jobBlock(workflow, FRESCO_HMR.job);
  assert.match(
    block,
    /if: needs\.detect_changed_surfaces\.outputs\.fresco_hmr == 'true'/,
    `${FRESCO_HMR.job} must be gated on ${FRESCO_HMR.output}`,
  );
  // It must EXECUTE the gate, not merely mention it.
  assert.ok(
    stepRunning(block, 'npm run test:fresco-hmr'),
    'the job must run `npm run test:fresco-hmr` as a step',
  );
  assert.match(
    workflow,
    /fresco_hmr: \$\{\{ steps\.detect\.outputs\.fresco_hmr \}\}/,
    `${FRESCO_HMR.output} must be declared as a detect_changed_surfaces output`,
  );
  assert.ok(
    jobBlock(workflow, 'all-required-passed').includes(`- ${FRESCO_HMR.job}`),
    `aggregator must list ${FRESCO_HMR.job} in needs:`,
  );
});

test('the fresco HMR job installs the PINNED three engines and narrows none (rf2-hic-015)', () => {
  const block = jobBlock(fs.readFileSync(WORKFLOW, 'utf8'), FRESCO_HMR.job);

  assert.match(
    block,
    /playwright install --with-deps chromium firefox webkit/,
    'the gate must install Chromium, Firefox AND WebKit',
  );

  // THE QUIET WAY TO KEEP THE NAME AND DROP THE CLAIM. Unlike its sibling this
  // runner takes an engine-narrowing env knob, `FRESCO_HMR_ENGINES`, and its
  // cross-engine comparator is inert below two engines — so a job that set it
  // would still print a PASS having checked nothing about divergence. The job
  // must pass no engine narrowing at all.
  //
  // The runner refuses the full verdict to ANY narrowing, not just one below
  // the comparator's floor, so a job that set this would be caught in its own
  // log too. That is a second line of defence and not a reason to relax this
  // one: the runner's honesty is about the reader of a log, and this row is
  // about the job never getting into that state. Note in particular that a
  // TWO-engine narrowing does get compared — so "the comparator is inert" is
  // not the whole reason to forbid the knob here; the whole reason is that
  // this job's name promises three engines.
  //
  // Read the job's EXECUTABLE text, not its prose. Grepping the whole block
  // would red on the YAML COMMENT that explains the knob — an assertion that
  // forbids naming the hazard is an assertion that punishes documenting it,
  // and it would get "fixed" by deleting the explanation. Comments out, then
  // look for an assignment.
  const executable = block
    .split(/\r?\n/)
    .filter((line) => !/^\s*#/.test(line))
    .join('\n');
  assert.ok(
    !/FRESCO_HMR_ENGINES/.test(executable),
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

// The DOM suites the fresco arm's cljs_browser exists for.
const FRESCO_DOM_TESTS = pinnedRoster('FRESCO_DOM_TESTS', [
  'implementation/fresco/test/re_frame/fresco/kernel_commit_owns_dom_cljs_test.cljs',
  'implementation/fresco/test/re_frame/fresco/roots_frames_hydration_dom_cljs_test.cljs',
  'implementation/fresco/test/re_frame/fresco/roots_frames_isolation_dom_cljs_test.cljs',
]);

test('the fresco DOM suites really are in the browser lane (rf2-8a6s)', () => {
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

  for (const file of FRESCO_DOM_TESTS) {
    assert.ok(fs.existsSync(path.join(REPO_ROOT, file)), `${file} must exist`);
    const ns = file
      .replace('implementation/fresco/test/', '')
      .replace(/\.cljs$/, '')
      .replace(/_/g, '-')
      .replace(/\//g, '.');
    assert.ok(
      selector.test(ns),
      `${ns} must be selected by :browser-test's ${selector} for cljs_browser to mean anything`,
    );
  }
});

// Git-DERIVED discovery mode — the real CI path, which classify() bypasses by
// passing paths explicitly. These build REAL scratch repos and run the
// script's own bytes with no explicit paths.
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

// The environment values the discovery tests control; the script's
// base-resolution block reads all three.
const LAUNCHER_TRANSPORTED_ENV = Object.freeze([
  'GITHUB_EVENT_NAME',
  'GITHUB_BASE_REF',
  'CHANGED_SURFACES_BASE_REF',
]);

/**
 * The environment to hand `execFileSync('bash', ...)` so the child actually
 * SEES the values above. On Windows the `bash` on PATH may be the WSL launcher,
 * which imports only the variables WSLENV names — a base that arrives empty
 * silently demotes a push case to the HEAD^ fallback. Inert for Git Bash, and
 * the identity off win32. Names only the values actually present: WSL exports a
 * named-but-unset variable as the empty string.
 */
function launcherEnv(env, platform = process.platform) {
  if (platform !== 'win32') return env;
  const present = LAUNCHER_TRANSPORTED_ENV.filter((name) => env[name] !== undefined);
  if (present.length === 0) return env;
  // Bare names: a `/p` flag would path-translate a SHA, a branch or an event
  // name. The ambient WSLENV entries are kept.
  const entries = String(env.WSLENV || '')
    .split(':')
    .filter(Boolean);
  const named = new Set(entries.map((entry) => entry.split('/')[0]));
  for (const name of present) {
    if (!named.has(name)) entries.push(name);
  }
  return { ...env, WSLENV: entries.join(':') };
}

// Build a real repo via `buildHistory`, then run the script in its Git-derived
// discovery mode with NO explicit paths and return the parsed outputs.
// GITHUB_* is cleared so the script takes the local branch. `envFor` runs after
// the history exists, because the interesting value — the push's accepted
// base — is a SHA that does not exist until then.
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
    // Applied LAST, so a test can set what the deletions above cleared.
    if (envFor) {
      Object.assign(
        env,
        envFor({ root: tmp, git: (...args) => gitIn(tmp, ...args) }),
      );
    }
    const out = execFileSync('bash', ['-s'], {
      cwd: tmp,
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
    // A keeper file guarantees a non-empty first commit and keeps HEAD^
    // well-defined.
    write('README.md', '# scratch\n');
    write(fromPath, '(ns example.moved)\n;; identical content across the rename\n');
    commit('seed');
    // `git mv` does not create the destination directory; pre-create it.
    fs.mkdirSync(path.dirname(path.join(root, toPath)), { recursive: true });
    git('mv', fromPath, toPath);
    commit('rename');
  });
}

// A classified source, used purely as the subject of the push-base tests.
const CLASSIFIED_SOURCE = 'implementation/adapters/reagent/src/re_frame/adapter/reagent.cljs';
const DISCOVERY_GATE_KEYS = ['implementation_jvm', 'cljs_node_test', 'cljs_browser', 'cljs_prod'];

// A three-commit "push": a classified file lands in commit 1 and is untouched
// by commits 2 and 3, so a HEAD^ diff misses it.
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
    // The accepted base, `github.event.before`: three commits back from the tip.
    CHANGED_SURFACES_BASE_REF: git('rev-parse', 'HEAD~3').toString().trim(),
  }));
  for (const key of DISCOVERY_GATE_KEYS) {
    assert.equal(
      result[key],
      'true',
      `a classified change in commit 1 of a 3-commit push must arm ${key} on the ` +
        'push run — reverting the base to HEAD^ makes this fail',
    );
  }
});

test('PUSH: the CONTROL — HEAD^ alone misses that same change (rf2-34yg)', () => {
  // Same history, no accepted base: the HEAD^ default sees only the docs tip.
  // If this ever arms the gates, the test above has stopped proving anything.
  const result = classifyViaGitDiscovery(pushHistory);
  for (const key of DISCOVERY_GATE_KEYS) {
    assert.equal(
      result[key],
      'false',
      `HEAD^ sees only the docs tip, so ${key} stays false — this is the ` +
        'failure the accepted base avoids, kept as the control for the test above',
    );
  }
});

test('PUSH: the all-zeros sentinel folds back to HEAD^ (first push to a ref) (rf2-34yg)', () => {
  // A first push to a fresh ref carries an all-zeros `before`, which must not
  // reach `git diff` as a literal ref.
  const result = classifyViaGitDiscovery(pushHistory, () => ({
    GITHUB_EVENT_NAME: 'push',
    CHANGED_SURFACES_BASE_REF: '0'.repeat(40),
  }));
  for (const key of DISCOVERY_GATE_KEYS) {
    assert.equal(result[key], 'false', `all-zeros must fold to HEAD^, not fail (${key})`);
  }
});

test('PUSH: an UNRESOLVABLE base arms everything rather than skipping (rf2-34yg)', () => {
  // The force-push case: `before` is the discarded tip, reachable from no ref.
  // A classifier's failure mode is a false GREEN, so its fail-closed is
  // mark_all, never a silent return to HEAD^.
  const result = classifyViaGitDiscovery(pushHistory, () => ({
    GITHUB_EVENT_NAME: 'push',
    CHANGED_SURFACES_BASE_REF: 'dead0000'.repeat(5),
  }));
  const falses = Object.entries(result).filter(([, v]) => v !== 'true');
  assert.deepEqual(falses, [], 'an unresolvable base must arm the FULL matrix');
});

test('test.yml hands the classifier the accepted base via env: (rf2-34yg)', () => {
  // The script cannot read `github.event.before` on its own, so the push
  // branch is inert unless test.yml passes it.
  const block = jobBlock(fs.readFileSync(WORKFLOW, 'utf8'), 'detect_changed_surfaces');
  assert.match(
    block,
    /CHANGED_SURFACES_BASE_REF:\s*\$\{\{\s*github\.event\.before\s*\}\}/,
    'detect_changed_surfaces must pass github.event.before as ' +
      'CHANGED_SURFACES_BASE_REF, or a multi-commit push is classified on its ' +
      'tip alone',
  );
  // Via `env:`, never interpolated into the run body, so the step is
  // injection-safe.
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

// The Fresco artefact. Its package arm is pinned above; these pin its SCOPE.
test('implementation/fresco/** stays OFF the gates no fresco suite reaches (rf2-8a6s)', () => {
  // Scope guard, and each entry has a named release condition — TESTING.md
  // warns that a coarse rule clutters the matrix with skipping entries, and a
  // gate that runs not one line of the changed surface is worse than none
  // because it reads as coverage.
  //
  //   implementation_jvm — NOT because the artefact has no JVM lane. It has
  //     one: `implementation/fresco` is on scripts/test-jvm-implementation.sh
  //     and runs in the required `jvm-fresco` job, whose `:test` alias carries
  //     the test-count floor. The job is UNCONDITIONAL, so it needs no arm —
  //     and arming this root would be actively wrong: 22 OTHER jobs read
  //     `implementation_jvm`, so every fresco-only diff would schedule all of
  //     them to run one five-second one-namespace lane. The
  //     `jvm-fresco is UNCONDITIONAL` test below pins that.
  //   cljs_prod — no `-elision-prod-test$` namespace.
  //   bundle_isolation — no example resolves the
  //     artefact and it mounts no testbed those smokes drive.
  //
  // `cljs_browser` is NOT on this list: the package owns `-dom-cljs-test$`
  // namespaces the :browser-test build selects, and that arm is asserted
  // positively by the DOM-suite block above.
  const result = classify('implementation/fresco/src/re_frame/fresco.cljc');
  for (const key of [
    'implementation_jvm',
    'cljs_prod',
    'bundle_isolation',
  ]) {
    assert.equal(result[key], 'false', `fresco must not arm ${key}`);
  }
});

// ---------------------------------------------------------------------------
// The fresco JVM lane, and why it carries no surface gate.
//
// `implementation/fresco/test/re_frame/fresco/slot_cljs_test.cljc`
// is the `.cljc` EQUIVALENCE PIN for the canonical slot rule: one
// corpus asserted twice against ONE implementation, once by `npm run test:cljs`
// in Node and once by `clojure -M:test` on the JVM. Both arms or no mechanism —
// a reader conditional inside the rule, or the JVM's locale-sensitive
// `str/upper-case`, is invisible to either host alone.
//
// The rows below are why this job is not gated on `implementation_jvm`. Each
// is a file on the lane's JVM classpath that does not arm that output, so
// each is a PR shape that would skip a gated job — and `deps.edn` is the
// sharpest, because it is the file that decides
// whether the pin is discovered AT ALL. They must NOT be "fixed" by widening
// `implementation_jvm` for the artefact root: 22 other jobs read it, and the
// scope guard above says so. The repair is the unconditional job asserted
// underneath.
// ---------------------------------------------------------------------------

test('the fresco JVM lane has classpath inputs that arm NO jvm tier (rf2-ipx7h)', () => {
  for (const file of [
    // the `:test` alias itself — `:extra-paths`, `:extra-deps`, `:main-opts`
    'implementation/fresco/deps.edn',
    // also on `:extra-paths`, so also scanned for discovery
    'implementation/fresco/test_kit/src/re_frame/fresco/test.cljs',
  ]) {
    const result = classify(file);
    assert.equal(
      result.implementation_jvm,
      'false',
      `${file} is on the fresco JVM lane's classpath and arms no jvm tier — `
        + 'which is why jvm-fresco is unconditional rather than gated on '
        + 'implementation_jvm',
    );
  }
});

test('the slot pin arms implementation_jvm only INCIDENTALLY (rf2-ipx7h)', () => {
  // The pin DOES measure true — but through `is_route_path_census_input`, a
  // predicate that exists for the routing route-path census and matches
  // `implementation/fresco/test/*` `.cljs`/`.cljc`. The `.clj` control below
  // is what makes that legible: same tree, same artefact, FALSE, because the
  // census filters on the extensions IT cares about. So the arm belongs to
  // another gate's roster and could narrow with it — a second reason this
  // job takes no gate at all. (The pin's SUBJECT, `impl/slot.cljc`, sits
  // under `src/` and measures false like the rest of the package — the scope
  // guard above pins that; the pin requires the package rule directly rather
  // than the bench tree's twin.)
  assert.equal(
    classify('implementation/fresco/test/re_frame/fresco/slot_cljs_test.cljc').implementation_jvm,
    'true',
  );
  assert.equal(
    classify('implementation/fresco/test/re_frame/fresco/expansion_probe.clj')
      .implementation_jvm,
    'false',
    'the .clj control must measure false — the arm is the census predicate, '
      + 'not a fresco JVM arm',
  );
});

test('a bench-lane diff is CLASSIFIED to no gate, not left unclassified (rf2-6c12m.1)', () => {
  // The Fresco bench lane is a hand-run shadow-cljs project kept off every
  // per-PR lane: its suites exercise LOCAL COPIES of the runtime, so a PR run
  // could catch nothing in the shipped one. The classifier's explicit `bench/*`
  // arm sets nothing; the tree's own gate is `npm run check` from bench/fresco/.
  const result = classify('bench/fresco/src/re_frame/bench/fresco/lane.cljs');
  assert.deepEqual(new Set(Object.values(result)), new Set(['false']));
});

test('jvm-fresco is UNCONDITIONAL, rostered and required (rf2-ipx7h)', () => {
  const workflow = fs.readFileSync(WORKFLOW, 'utf8');
  const block = jobBlock(workflow, 'jvm-fresco');
  // Job-level keys sit at exactly four spaces; anchoring there keeps a prose
  // comment mentioning `needs:` from reading as the key itself.
  assert.doesNotMatch(
    block,
    /^ {4}needs:/m,
    'jvm-fresco must not depend on detect_changed_surfaces — the lane\'s own '
      + 'deps.edn arms no classifier output',
  );
  assert.doesNotMatch(
    block,
    /^ {4}if:/m,
    'jvm-fresco must carry no surface gate; implementation_jvm does not cover '
      + 'this lane\'s inputs and arming it would schedule 22 other jobs',
  );
  assert.match(
    block,
    /^ {8}working-directory: implementation\/fresco$/m,
    'the job must run in the artefact directory',
  );
  assert.match(block, /run: clojure -M:test$/m, 'the job must run the JVM lane');
  // Required, not advisory: a job absent from the aggregator's `needs:` is
  // advisory whatever its own gate says, and check_jvm_lane_rosters.py R1
  // refuses the roster entry without this line.
  assert.match(
    jobBlock(workflow, 'all-required-passed'),
    /^ {6}- jvm-fresco$/m,
    'jvm-fresco must be in all-required-passed\'s needs',
  );
  // The local half of the same bijection. R1/R2 check this too, but a reader
  // of THIS file should not have to run a Python gate to learn that the lane
  // has a local lane as well as a hosted one.
  assert.match(
    fs.readFileSync(path.join(REPO_ROOT, 'scripts', 'test-jvm-implementation.sh'), 'utf8'),
    /^ {2}implementation\/fresco$/m,
    'implementation/fresco must be on the local JVM roster',
  );
});

test('the cljs job runs BOTH fresco gates the classifier arm schedules (rf2-8a6s)', () => {
  // The gate half of the classifier rule. The arm above is worthless if the
  // job it lights stops running the artefact's checks, and the invariants
  // gate in particular has no other scheduled home.
  const block = jobBlock(fs.readFileSync(WORKFLOW, 'utf8'), 'cljs');
  assert.match(
    block,
    /run: npm run test:fresco-invariants$/m,
    'the cljs job must run the fresco invariants gate (optional-module '
      + 'reachability with its no-bench-import row and the other static reads '
      + 'chained there); it runs nowhere else',
  );
  assert.match(
    block,
    /run: npm run test:fresco-compile$/m,
    'the cljs job must keep running the fresco modules compile '
      + '(the package-owned modules compile gate)',
  );
});

// ---------------------------------------------------------------------------
// The browser lane is gated AND required.
//
// Arming an output helps only if the lane it arms is gated on that output and
// reachable from the single required context. The mounted fresco DOM
// witnesses ride `:browser-test` on exactly that basis.
// ---------------------------------------------------------------------------

test('cljs-browser is job-gated on cljs_browser and is REQUIRED (rf2-drpa3.70)', () => {
  // Arming the output only helps if the lane it arms is still surface-gated on
  // that output and still reachable from the single required context. Both
  // halves, pinned together: a lane outside the aggregator is advisory however
  // green it looks.
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

// The .beads PR-boundary guard's CI arm. Enforced only by the local pre-commit
// hook, the guard would be bypassable with `--no-verify`. Its own behaviour is
// tested by scripts/git-hooks/test-pre-commit.sh, which this job self-tests
// before it enforces.
test('beads-pr-boundary self-tests the guard, then enforces it on every PR against the base branch (rf2-3mh2f)', () => {
  const block = jobBlock(fs.readFileSync(WORKFLOW, 'utf8'), 'beads-pr-boundary');
  assert.match(
    block,
    /sh scripts\/git-hooks\/test-pre-commit\.sh/,
    'the guard must be self-tested in CI — its harness runs nowhere else',
  );
  assert.match(block, /sh scripts\/check-beads-pr-boundary\.sh/);
  // The guard diffs from the branch point, which a shallow clone often lacks.
  assert.match(block, /fetch-depth: 0/);
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
  // Branch-point resolution lives in the script, so the local pre-flight gets
  // the same answer; `base.sha` goes stale as soon as main advances.
  assert.match(
    block,
    /check-beads-pr-boundary\.sh "origin\/\$\{GITHUB_BASE_REF\}"/,
  );
  assert.doesNotMatch(block, /\$\{\{[^}]*base\.sha/);
  assert.doesNotMatch(
    block,
    /^\s*base="\$\(git merge-base/m,
    'the script owns branch-point resolution — one home for the rule',
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

// ─── PROSE THAT A test.yml SUITE PINS ────────────────────────────
//
// Suites inside test.yml jobs slurp repo prose and assert on its text, so the
// prose each suite reads arms that suite's lane, and prose no suite reads arms
// nothing. One row per arm: a glob arm (`docs/machines/*.md`, the `spec/*`
// catch-all) by one page, a named-page arm by each page it names.
//
// path -> the suite that reads it -> the job that runs it. Documentation for
// the reader; the assertions consume the paths only.
const PROSE_PINS_ARMING_JVM = [
  ['docs/machines/concepts.md', 'transition_geometry_terminology_jvm_test.clj', 'jvm-machines'],
  ['docs/api/re-frame.adapter.uix.md', 'scope_ensure_authority_test.clj', 'jvm-core'],
  ['docs/api/re-frame.ssr.md', 'ssr_doc_example_projector_test.clj', 'jvm-ssr'],
  ['docs/ssr/concepts.md', 'ssr_doc_example_node_build_id_test.clj', 'jvm-ssr'],
  ['docs/design/fresco/product/async-routing-recipes.md', 'recipes/async_nav_doc_test.clj', 'jvm-routing'],
  ['spec/005-StateMachines.md', 'transition_geometry_terminology_jvm_test.clj', 'jvm-machines'],
  ['spec/009-Instrumentation.md', 'error_catalogue_channel_conformance_test.clj', 'jvm-core'],
  ['spec/Spec-Schemas.md', 'four suites in three artefacts', 'jvm-core/-epoch/-machines'],
];

pinnedRoster(
  'PROSE_PINS_ARMING_JVM',
  PROSE_PINS_ARMING_JVM.map(([file]) => file),
);

// The readers whose job gates on an output OTHER than the JVM tier: path ->
// suite -> job -> the output that job's `if:` reads. The `examples/` and
// `tools/` rows are nested inside their own tree's arm.
const PROSE_PINS_ARMING_OTHER_LANES = [
  ['docs/core/testing/views.md', 'uix_component_recipe_docs_pin_test.clj', 'jvm-uix', 'adapter_diagnostic'],
  ['docs/core/how-to/use-uix-or-slim.md', 'uix_consumer_deps_recipe_test.clj', 'jvm-uix', 'adapter_diagnostic'],
  ['docs/skills/re-frame2-setup.md', 'setup_drift_test.clj', 'skills-structural', 'skills_structural'],
  ['spec/Conventions.md', 'uix_consumer_deps_recipe_test.clj', 'jvm-uix', 'adapter_diagnostic'],
  ['spec/009-Instrumentation.md', 'infinite_trace_ops_test.clj', 'mcp-conformance-wire-vocab', 'mcp_conformance'],
  ['examples/substrates/uix/counter/core.cljs', 'uix_consumer_deps_recipe_test.clj', 'jvm-uix', 'adapter_diagnostic'],
  ['examples/substrates/uix/login/core.cljs', 'uix_consumer_deps_recipe_test.clj', 'jvm-uix', 'adapter_diagnostic'],
  ['examples/substrates/uix/dashboard/core.cljs', 'uix_consumer_deps_recipe_test.clj', 'jvm-uix', 'adapter_diagnostic'],
  ['examples/substrates/uix/counter/README.md', 'uix_consumer_deps_recipe_test.clj', 'jvm-uix', 'adapter_diagnostic'],
  ['examples/substrates/uix/login/README.md', 'uix_consumer_deps_recipe_test.clj', 'jvm-uix', 'adapter_diagnostic'],
  ['examples/substrates/uix/dashboard/README.md', 'uix_consumer_deps_recipe_test.clj', 'jvm-uix', 'adapter_diagnostic'],
  ['tools/xray/spec/024-Resources-Panel.md', 'infinite_trace_ops_test.clj', 'mcp-conformance-wire-vocab', 'mcp_conformance'],
];

pinnedRoster(
  'PROSE_PINS_ARMING_OTHER_LANES',
  PROSE_PINS_ARMING_OTHER_LANES.map(([file]) => file),
);

test('every measured prose pin arms the JVM tier that runs its suite (rf2-61ar)', () => {
  for (const [file, suite, job] of PROSE_PINS_ARMING_JVM) {
    assert.equal(
      classify(file).implementation_jvm,
      'true',
      `${file} is slurped by ${suite}, which runs in ${job} — it must arm implementation_jvm`,
    );
  }
});

test('every prose pin outside the JVM tier arms the output its job is gated on', () => {
  const workflow = fs.readFileSync(WORKFLOW, 'utf8');
  const gatedOn = new Map();
  for (const [file, suite, job, output] of PROSE_PINS_ARMING_OTHER_LANES) {
    assert.equal(
      classify(file)[output],
      'true',
      `${file} is read by ${suite}, which runs in ${job} — it must arm ${output}`,
    );
    gatedOn.set(job, output);
  }
  for (const [job, output] of gatedOn) {
    assert.match(
      jobBlock(workflow, job),
      new RegExp(`if: needs\\.detect_changed_surfaces\\.outputs\\.${output} == 'true'`),
      `${job} must be gated on ${output}, or arming it schedules nothing`,
    );
  }
});

test('prose no suite reads still arms NOTHING — the narrowing (rf2-61ar)', () => {
  // The page beside each named-page arm, and the prose sibling of the v1
  // codemod subtree: each arm is the PAGE, not its tree.
  for (const file of [
    'docs/core/intro.md',
    'docs/api/re-frame.core.md',
    'docs/ssr/testing.md',
    'docs/design/fresco/draft-guide/REWRITE-NOTES.md',
    'docs/skills/re-frame2-pair.md',
    'migration/from-re-frame-v1/README.md',
  ]) {
    assert.deepEqual(armedOutputs(classify(file)), [], `${file} must arm nothing`);
  }
});

test('the spec/* catch-all does not shadow the narrower spec arms (rf2-61ar)', () => {
  // A POSIX `case` takes the FIRST match and `*` spans `/`, so the catch-all
  // would swallow every narrower spec/ case if it were moved above them.
  const api = classify('spec/API.md');
  assert.equal(api.implementation_jvm, 'false',
    'spec/API.md keeps its cljs_node_test-only classification');
  assert.equal(api.cljs_node_test, 'true');
});

// ─── SKILL RECIPES A JVM SUITE EXTRACTS AND RUNS ────
//
// Two JVM suites slurp a skill page, pull the forms out of its fences and
// `eval` them, so the page IS the suite's input:
//   ssr_skill_form_action_test.clj   -> skills/re-frame2/patterns/form-action.md  (jvm-ssr)
//   improver_article_schema_test.clj -> skills/re-frame2-improver/references/schemaless-events.md  (jvm-schemas)
// Both jobs gate on implementation_jvm, the whole JVM tier, so the narrowing is
// bought on the PATH axis: one named leaf each.

test('the extracted FormAction recipe arms the SSR JVM lane (rf2-8btol)', () => {
  // implementation_jvm alone: markdown cannot change what React renders, and
  // the extracted view is rendered by the JVM SSR projector.
  assert.deepEqual(
    armedOutputs(classify('skills/re-frame2/patterns/form-action.md')),
    ['implementation_jvm'],
  );
  // A sibling recipe has no JVM reader, so a widening to the directory reds.
  assert.equal(classify('skills/re-frame2/patterns/forms.md').implementation_jvm, 'false');
  // The OTHER `skills/re-frame2/` leaf with a JVM reader, whose arm sits
  // directly above, routes to a DIFFERENT lane and must keep doing so.
  const mcpLeaf = 'skills/re-frame2/references/tooling/story-mcp-loop.md';
  const neighbour = classify(mcpLeaf);
  assert.equal(neighbour.tools_jvm, 'true', mcpLeaf);
  assert.equal(neighbour.mcp_conformance, 'true', mcpLeaf);
  assert.equal(neighbour.implementation_jvm, 'false', mcpLeaf);
});

test('the extracted improver reference arms the schemas JVM lane (rf2-g9at9)', () => {
  // The arm is a case NESTED inside `skills/re-frame2-improver/*`, so the
  // enclosing structural arm still runs; a top-level arm ahead of it would
  // shadow it and take `skills_structural` away.
  assert.deepEqual(
    armedOutputs(classify('skills/re-frame2-improver/references/schemaless-events.md')),
    ['implementation_jvm', 'skills_structural'],
  );
  // The sibling controls: hoisting implementation_jvm to the enclosing arm
  // would queue the JVM tier for every prose file in the tree.
  for (const file of [
    'skills/re-frame2-improver/references/imperative-effects.md',
    'skills/re-frame2-improver/SKILL.md',
  ]) {
    const result = classify(file);
    assert.equal(result.skills_structural, 'true', file);
    assert.equal(result.implementation_jvm, 'false', file);
  }
});

// THE ROUTE-PATH CENSUS IS ARMED BY EVERY TREE IT READS.
//
// `implementation/routing/test/re_frame/routing_path_census_test.clj` is a JVM
// suite that reads three app trees named in its own `app-roots`. It runs only
// in `jvm-routing`, which only `implementation_jvm` gates — so unless every
// root arms that output, the census cannot fire on an edit it exists to
// police.
//
// The roster is DERIVED from the census source rather than restated here. That
// is the whole point: a second hand-maintained list would be a fresh instance
// of the defect (a gate's inputs and its arming in two files with nothing
// holding them in step). Add a root over there and this reds until the
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

// ---------------------------------------------------------------------------
// NON-LOCAL INPUT EDGES ARM THE JOB THAT READS THEM.
//
// Three more gates have the census shape above: a suite whose expected value
// IS a file somewhere else in the repository, scheduled in a job that the
// file's own classification would not otherwise arm. Each is pinned here the
// way the census is — the roster READ OUT OF
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
// The classifier's spec-md guard excuses `tools/{story,xray}/spec/**.md` from
// the probes on the premise that spec prose "cannot affect any JVM unit
// test". Two suites under `tools/xray/test/` read exactly that prose as their
// expected value, so for the Xray files they read the premise does not hold,
// and those files arm the lanes that run the suites.

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
  // Story's spec prose has no counterpart reader inside test.yml, so it
  // classifies to nothing at all.
  assert.deepEqual(armedOutputs(classify('tools/story/spec/API.md')), []);
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

// ===========================================================================
// THE TREE-CLAIM META-CHECK.
//
// The tests above pin arms somebody thought to write. This guards against the
// arm nobody wrote: a NEW directory lands, classifies to nothing, and is gated
// by nothing. It asserts only that every tracked tree arms at least one output
// or carries an entry in DECLARED_NO_SURFACE_OUTPUT — tree CLAIM, which
// changes only when a tree appears or disappears, rather than a model of any
// gate's inputs.
//
// It lets through a tree that arms the wrong output, and a FILE that arms
// nothing inside a tree its siblings light: `.github/scripts/
// nightly_failure_alert.py` is such a declared file-level hole, bounded by its
// own in-run --self-test step in expensive-tests.yml. Going file-level would
// nag on every README in the repository.
//
// "At least one output" is not "covered", and the declared list says where
// each dark tree's coverage really lives — an always-on job (which arms no
// surface output by construction) or another workflow's own classifier. Every
// path a `coveredBy` names must exist: a weak check, but it catches the
// reference that outlived the gate it names.
// ===========================================================================

// The two reasons that recur across a dozen trees, named once.
const DOCS_YML = {
  why: "documentation staged into the MkDocs site; docs.yml's own docs_surface classifier arms on docs/*, and its build job runs mkdocs --strict over the corpus. Markdown link + anchor validation is not part of THAT job: check_doc_slugs.py runs in test.yml's unconditional verify-readme-links job, so these trees are slug-validated on every PR rather than only on a docs-classified one",
  coveredBy: ['.github/workflows/docs.yml', 'scripts/check_doc_slugs.py'],
};

const SKILLS_ALWAYS_ON = {
  why: "prose skill trees, reached at PR time by two ALWAYS-ON jobs — and an always-on job arms no surface output by construction. verify-skill-mcp-drift runs check_skill_mcp_drift.py (allowed-tools front-matter held in step with the MCP catalogues) and check_inject_cofx_residue.py (skills/ markdown scanned for retired API spellings); verify-readme-links runs check_doc_slugs.py over its full roster — docs, spec, SKILLS, migration. That second job is the one that slug- and anchor-validates these files at PR time: docs.yml's docs_surface does not match skills/*, so the slug gate would never fire on them from inside docs.yml's build job.",
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
  // The Fresco bench lane — the measurement harness — is its own hand-run
  // shadow project here, off every per-PR lane deliberately:
  // its suites exercise LOCAL COPIES of the runtime, so running its
  // deftests per PR could not catch a regression in the shipped one, and its
  // committed run records are evidence rather than inputs. The
  // classifier carries an explicit `bench/*` arm that sets nothing, so the
  // silence is stated in the script as well as declared here. The tree's own
  // gate is `npm run check` from bench/fresco/ (every namespace compiled
  // warnings-fatal plus the harness self-tests), which the bench README
  // requires before a bench change is published. Two always-on PR jobs
  // reach the tree without arming anything: verify-readme-links validates
  // bench/fresco/README.md, and js-harness-self-tests runs
  // lane_cache_wiring.test.cjs, which scans the drivers as text for the
  // cache-clear rule across implementation/ AND bench/fresco/.
  'bench/fresco': {
    why: "the Fresco bench lane, a hand-run shadow-cljs project kept off every per-PR lane deliberately: its suites run against local copies of the runtime, so no PR gate could learn anything from them. Its gate is `npm run check` from bench/fresco/; two always-on jobs read it — verify-readme-links (check_readme_links.py over its README) and js-harness-self-tests (lane_cache_wiring.test.cjs over its drivers)",
    coveredBy: [
      'bench/fresco/package.json',
      'scripts/check_readme_links.py',
      'implementation/core/test/re_frame/bench/lane_cache_wiring.test.cjs',
    ],
  },
  docs: DOCS_YML,
  'docs/EP': DOCS_YML,
  'docs/async': DOCS_YML,
  // `docs/core` is DELIBERATELY ABSENT, for the reason `docs/ssr` is below:
  // two of its pages carry a test.yml reader — testing/views.md and
  // how-to/use-uix-or-slim.md, both read by jvm-uix suites — and an arm
  // setting `adapter_diagnostic` to schedule them, so one armed file arms the
  // tree. Its other pages arm no output, and the gates that read them are
  // always-on or lint.yml's own: docs.yml stages the tree and runs
  // mkdocs --strict; check_doc_slugs.py validates its links and anchors from
  // the unconditional verify-readme-links job; lint.yml runs api-manifest
  // doc-guide-check over docs/core/**; and the unconditional
  // fresco-guide-samples job checks that every fresco verb a Fresco guide
  // sample names resolves. That job is unconditional precisely so that a
  // guide-only PR runs it: the only output that would otherwise reach its
  // checker is cljs_node_test, the ~10-minute node build.
  'docs/images': DOCS_YML,
  'docs/resources': DOCS_YML,
  'docs/routing': DOCS_YML,
  'docs/scripts': DOCS_YML,
  // `docs/skills` is DELIBERATELY ABSENT as well: re-frame2-setup.md is read
  // by the setup skill's setup_drift_test.clj in the `skills-structural` job,
  // and its arm sets `skills_structural` to schedule it.
  // `docs/ssr` is DELIBERATELY ABSENT, and the ratchet below is what makes
  // that a requirement rather than a tidy-up: `docs/ssr/concepts.md` carries a
  // test.yml pin (ssr_doc_example_node_build_id_test.clj, jvm-ssr) and an arm
  // to schedule it. One armed file arms the tree, so a DOCS_YML entry here
  // would be a stale declaration telling the next reader this tree is
  // ungated. Its other pages are covered by
  // docs.yml + check_doc_slugs.py exactly as DOCS_YML says; that is a
  // statement about pages, and this table is keyed by tree.
  'docs/story': DOCS_YML,
  'docs/stylesheets': DOCS_YML,
  'docs/the-mayor-method': DOCS_YML,
  'docs/xray': DOCS_YML,
  'migration/from-clj-new-template': {
    why: "a migration note; docs.yml's docs_surface classifier lists migration/*, and its slug/anchor validation comes from check_doc_slugs.py in test.yml's unconditional verify-readme-links job",
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
  // `skills/re-frame2-improver` is DELIBERATELY ABSENT for the same
  // reason as the two trees named below: the tree has an executable half
  // (tests/storage_materializer_test.clj, looped by the improver step in the
  // `skills-structural` job) and an arm to schedule it, so a declaration here
  // would be stale. Its neighbour one line up,
  // `skills/re-frame2-implementor`, STAYS — that tree really does arm nothing,
  // and the two names share a
  // `skills/re-frame2-imp` prefix.
  //
  // `skills/re-frame2-pair-retro` and `skills/reagent-fresco-migration`
  // are DELIBERATELY ABSENT from this table. Each has an executable half gated
  // on `skills_structural` (the pair-retro bb step in `skills-structural`, and
  // `reagent-fresco-migration-fixture-cold-start`) and a case arm that schedules it.
  // A tree that arms an output must not stay declared:
  // the `staleDeclarations` half of the check below fails on exactly that, so
  // adding either entry reds this suite rather than passing quietly.
  'skills/re-frame2-xray': SKILLS_ALWAYS_ON,
  tools: {
    why: "A DECLARED HOLE, and a MEASURED one rather than an open question. The tree is three files. tools/README.md IS covered — the always-on verify-readme-links job walks it (measured: it is in check_readme_links.py's _iter_scanned set). tools/.gitignore is config no gate reads. tools/deps.edn — the tool tier’s classpath coordinator, and the ONLY build file directly under tools/ — has no CI consumer AT ALL, and is left unarmed on evidence: no workflow runs from tools/ (every working-directory in .github/workflows is tools/<artefact>, never the bare root); scripts/test-jvm-tools.sh iterates per-tool directories; CI compiles the pair-mcp server from tools/re-frame2-pair-mcp/shadow-cljs.edn; and verify-version-lockstep.sh reads the per-artefact deps.edn files, not this coordinator. So NO existing output would exercise it, and arming one — tools_jvm was the candidate — would schedule four probes that never read the edited file. There is no aggregate :test alias here and no tools/shadow-cljs.edn: the per-tool configs are the ones CI runs. tools/deps.edn is a classpath declaration whose breakage surfaces on the next `cd tools && clojure -Spath`, to the developer who caused it. Delete this entry if tools/deps.edn ever gains a real CI consumer.",
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
 * `tools` here means the three files directly under `tools/` and not the
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
  // note saying a tree is ungated when it has been gated for months. Arming a
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

// ---------------------------------------------------------------------------
// THE GUARD'S OWN GUARD.
//
// `pinnedRoster` covers the rosters declared through it. It cannot, by itself,
// cover the NEXT one: a roster added by someone who has never read that comment
// and declared as a bare array literal would carry a phantom pin silently, and
// a guard that depends on an author remembering it guards nothing.
//
// So this test reads THIS FILE'S OWN SOURCE and refuses a path-shaped roster
// that never reached the guard. Parsing a roster out of source to keep it
// honest is an idiom this suite already uses one tier over — `every spec module
// the full-gate runner loads is armed` parses `ALL_SPEC_FILES` out of the story
// runner for exactly this reason. What differs here is that the file
// being parsed is this one, which is the only way to reach a roster that has
// not been written yet.
const ROSTERS_EXEMPT_FROM_PATH_PINNING = new Set([
  // Empty, and that is the intended steady state. An entry belongs here only
  // when a roster holds path-SHAPED strings that are not repo paths — glob
  // patterns, or synthetic probes that must stay untracked — and it carries a
  // reason on the line. Never add one to silence a red you have not understood:
  // the red normally means a pin has gone phantom, which is the bug itself
  // rather than the guard misfiring.
]);

test('every path-shaped roster in this file is declared through pinnedRoster (rf2-e30e)', () => {
  const source = fs.readFileSync(__filename, 'utf8');
  const isPathShaped = (s) => /^[A-Za-z0-9_.@-]+(?:\/[A-Za-z0-9_.@*-]+)+$/.test(s);
  const problems = [];
  const declaration = /(?:^|\n)const ([A-Z][A-Z0-9_]*) = \[/g;
  let scanned = 0;
  let match;

  while ((match = declaration.exec(source)) !== null) {
    const name = match[1];
    scanned += 1;

    // Quote-aware bracket matching: a roster entry may itself contain a
    // bracket, and a naive search for the next `]` would truncate the array.
    const open = source.indexOf('[', match.index);
    let depth = 0;
    let end = -1;
    let quote = null;
    for (let i = open; i < source.length; i += 1) {
      const character = source[i];
      if (quote !== null) {
        if (character === '\\') { i += 1; continue; }
        if (character === quote) quote = null;
        continue;
      }
      if (character === "'" || character === '"' || character === '`') { quote = character; continue; }
      if (character === '[') depth += 1;
      else if (character === ']') {
        depth -= 1;
        if (depth === 0) { end = i; break; }
      }
    }
    assert.ok(end > 0, `${name}: the self-parse could not find the end of the array literal`);

    const entries = [...source.slice(open + 1, end).matchAll(/'([^'\n]*)'|"([^"\n]*)"/g)]
      .map((entry) => (entry[1] === undefined ? entry[2] : entry[1]));
    const paths = entries.filter(isPathShaped);

    // A roster counts as guarded however it reaches `pinnedRoster` — wrapped at
    // its declaration, or (where the rows are tuples or objects rather than
    // bare paths) passed as a projected column afterwards.
    //
    // Tolerate whitespace between the call and its first argument, because a
    // formatter will break a long call across lines and a contiguous-substring
    // search cannot see across one: written as
    // `source.includes("pinnedRoster('" + name + "'")`, this check would report
    // PROSE_PINS_ARMING_JVM unguarded when it is guarded a few lines below,
    // purely because prettier wraps the argument onto its own line. A detector
    // that answers "not found" for a formatting reason is the same fail-open
    // shape as the phantom pin it is here to catch.
    const guarded = new RegExp(`pinnedRoster\\(\\s*'${name}'`).test(source);
    if (paths.length > 0 && !guarded && !ROSTERS_EXEMPT_FROM_PATH_PINNING.has(name)) {
      problems.push(
        `${name} holds ${paths.length} path-shaped entry/entries (e.g. ${paths[0]}) ` +
          'but is declared as a bare array, so a phantom path in it would be inert ' +
          `rather than red. Declare it as \`const ${name} = pinnedRoster('${name}', [...])\`, ` +
          'or pass its path column to pinnedRoster if the rows are tuples.',
      );
    }
  }

  // The parse itself is an instrument that can answer "nothing here" when it is
  // simply broken, so pin that it still finds the bare declarations it should.
  assert.ok(
    scanned > 0,
    'the self-parse matched no bare roster declarations at all — the parse has rotted, ' +
      'and a rotted parse reports a clean file',
  );
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
