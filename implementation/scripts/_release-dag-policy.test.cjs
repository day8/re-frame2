#!/usr/bin/env node
/*
 * Release-DAG ordering guard for `.github/workflows/release.yml` (rf2-p4a93).
 *
 * # The defect this exists to catch
 *
 * `implementation/ssr-ring/deps.edn` declares TWO in-repo coordinates in its
 * PUBLISHED `:deps` — `day8/re-frame2` (../core) and `day8/re-frame2-ssr`
 * (../ssr) — and the release workflow rewrites both to `:mvn/version`. So the
 * pom published for `day8/re-frame2-ssr-ring` carries a hard dependency on a
 * `day8/re-frame2-ssr` version that has to EXIST on Clojars.
 *
 * ssr-ring used to be a value of the `deploy-leaf` matrix, alongside `ssr`.
 * That matrix runs `fail-fast: false`, and GitHub Actions cannot express an
 * ordering edge between two values of one matrix — so a red `ssr` value did
 * not stop `ssr-ring` from deploying. Worse, ssr-ring installs ssr into the
 * runner's `~/.m2` before packaging, so it never asks Clojars whether the
 * sibling is there and could not self-detect the miss. The failure was
 * therefore silent AND permanent: Clojars has no yank, so
 * `day8/re-frame2-ssr-ring <VERSION>` would have sat in the public record
 * forever declaring a dependency that resolves to nothing.
 *
 * Zero tags and zero release runs have ever existed, so no CI signal covered
 * this. The workflow's own comments contradicted each other about it (the
 * `fail-fast: false` rationale claimed "the published-pom DAG has no edges
 * between leaves"; sixty lines later another comment described the edge).
 *
 * # The invariant asserted here
 *
 * Generalised past the one leaf, over the workflow's real job graph:
 *
 *   For every artefact this workflow publishes, and for every in-repo
 *   coordinate in that artefact's published `:deps`, the job that publishes
 *   the DEPENDENCY must be a strict transitive `needs:` ancestor of the job
 *   that publishes the DEPENDENT.
 *
 * Two corollaries fall out, and both are the real defect:
 *   - the two artefacts may not be values of the SAME job's matrix (a job
 *     cannot be its own ancestor — which is exactly why intra-matrix
 *     ordering is impossible); and
 *   - `if the ssr leaf does not publish successfully, ssr-ring must not
 *     publish at all` holds structurally, not by convention.
 *
 * The teeth are proved, not asserted: the suite reconstructs the pre-fix shape
 * from the CURRENT model (ssr-ring folded back into the deploy-leaf matrix)
 * and requires the same rule to report the violation.
 *
 * Ground truth for "what does this artefact publish a dependency on" is each
 * artefact's real `deps.edn`, read as EDN structure via scripts/lib/edn.cjs —
 * never the workflow's own matrix axes, which are the thing under test.
 *
 * Standalone node-runnable suite (no external framework, no node_modules),
 * matching the sibling `_*.test.cjs` convention. Wired into
 * `npm run test:script-policy`.
 */

'use strict';

const assert = require('assert/strict');
const fs = require('fs');
const path = require('path');

const {
  parseWorkflowYaml,
  transitiveNeeds,
  matrixInclude,
  needsOf,
} = require('./lib/workflow-yaml.cjs');
const { readEdn, isMap, mapGetKeyword } = require('./lib/edn.cjs');

const IMPL_ROOT = path.resolve(__dirname, '..');
const REPO_ROOT = path.resolve(IMPL_ROOT, '..');
const WORKFLOW_DIR = path.join(REPO_ROOT, '.github', 'workflows');
const RELEASE_YML = path.join(WORKFLOW_DIR, 'release.yml');

const DEPLOY_RUN = 'clojure -M:clein deploy';

const tests = [];
function test(name, fn) {
  tests.push({ name, fn });
}

function toPosix(p) {
  return p.split(path.sep).join('/');
}

// ── Publisher discovery, from the workflow's object model ──────────────────
// A job publishes artefact <dir> when it runs `clojure -M:clein deploy` with
// `working-directory: <dir>`. When that directory is a matrix expression it is
// expanded over the job's `include:` values, so the discovery follows the
// matrix instead of a hand-copied list of leaves.
function discoverPublishers(model) {
  const jobs = model.jobs || {};
  const publishers = new Map(); // repo-relative posix dir -> { job, leaf }
  for (const [jobId, job] of Object.entries(jobs)) {
    for (const step of job.steps || []) {
      if (typeof step.run !== 'string' || step.run.trim() !== DEPLOY_RUN) continue;
      const dir = step['working-directory'];
      assert.ok(dir, `${jobId}: deploy step has no working-directory`);
      if (dir === '${{ matrix.directory }}') {
        const values = matrixInclude(job);
        assert.ok(
          values.length > 0,
          `${jobId}: deploys \${{ matrix.directory }} but declares no matrix include values`,
        );
        for (const value of values) {
          assert.ok(value.directory, `${jobId}: matrix value ${value.leaf} has no directory`);
          publishers.set(value.directory, { job: jobId, leaf: value.leaf || value.directory });
        }
      } else {
        assert.ok(
          !dir.includes('${{'),
          `${jobId}: unexpanded expression in deploy working-directory: ${dir}`,
        );
        publishers.set(dir, { job: jobId, leaf: jobId });
      }
    }
  }
  return publishers;
}

// ── Ground truth: the in-repo coordinates an artefact actually PUBLISHES ───
// The top-level `:deps` map only (aliases are test-time and never published),
// read as EDN structure. Returns each `:local/root` value resolved to a
// repo-relative posix directory.
function publishedInRepoDeps(artefactDir) {
  const abs = path.join(REPO_ROOT, artefactDir, 'deps.edn');
  assert.ok(fs.existsSync(abs), `${artefactDir}/deps.edn missing`);
  const top = readEdn(fs.readFileSync(abs, 'utf8'));
  assert.ok(isMap(top), `${artefactDir}/deps.edn top-level form is not a map`);
  const deps = mapGetKeyword(top, 'deps');
  if (deps === undefined || deps === null) return [];
  assert.ok(isMap(deps), `${artefactDir}/deps.edn :deps is not a map`);
  const out = [];
  for (const [coord, spec] of deps.entries) {
    if (!isMap(spec)) continue;
    const localRoot = mapGetKeyword(spec, 'local/root');
    // edn.cjs models an EDN string as { edn: 'string', value }.
    if (!localRoot || localRoot.edn !== 'string') continue;
    out.push({
      coordinate: coord && coord.name ? coord.name : String(coord),
      localRoot: localRoot.value,
      dir: toPosix(path.normalize(path.join(artefactDir, localRoot.value))),
    });
  }
  return out;
}

// ── The rule ──────────────────────────────────────────────────────────────
// Returns a list of human-readable violations; empty means the DAG orders
// every published dependency edge.
function orderingViolations(model) {
  const jobs = model.jobs || {};
  const publishers = discoverPublishers(model);
  const closures = new Map();
  const closureOf = (jobId) => {
    if (!closures.has(jobId)) closures.set(jobId, transitiveNeeds(jobs, jobId));
    return closures.get(jobId);
  };

  const violations = [];
  for (const [artefactDir, { job: dependentJob, leaf }] of publishers) {
    for (const dep of publishedInRepoDeps(artefactDir)) {
      const publisher = publishers.get(dep.dir);
      if (publisher === undefined) {
        violations.push(
          `${leaf} (${artefactDir}) publishes a dependency on ${dep.coordinate} `
            + `(:local/root "${dep.localRoot}" -> ${dep.dir}), but no job in this workflow `
            + `publishes ${dep.dir} — the pom would ship an edge to a coordinate this `
            + 'release never uploads.',
        );
        continue;
      }
      if (publisher.job === dependentJob) {
        violations.push(
          `${leaf} publishes a dependency on ${dep.coordinate}, which job `
            + `'${dependentJob}' also publishes. GitHub Actions cannot order two values `
            + 'of one matrix, and that matrix runs fail-fast: false, so '
            + `${leaf} can deploy while ${publisher.leaf} is red — publishing an `
            + 'unresolvable coordinate that Clojars cannot take back. Move '
            + `${leaf} into its own job with a needs: edge onto '${publisher.job}'.`,
        );
        continue;
      }
      if (!closureOf(dependentJob).has(publisher.job)) {
        violations.push(
          `${leaf} publishes a dependency on ${dep.coordinate} (published by job `
            + `'${publisher.job}'), but '${dependentJob}' does not transitively require `
            + `'${publisher.job}' to have succeeded (needs: `
            + `${JSON.stringify(needsOf(jobs[dependentJob]))}). ${leaf} could publish `
            + 'an unresolvable coordinate.',
        );
      }
    }
  }
  return violations;
}

const releaseText = fs.readFileSync(RELEASE_YML, 'utf8');
const releaseModel = parseWorkflowYaml(releaseText);

// ── Parser adequacy ───────────────────────────────────────────────────────

test('every workflow in .github/workflows/ parses into an object model', () => {
  const files = fs
    .readdirSync(WORKFLOW_DIR)
    .filter((n) => n.endsWith('.yml') || n.endsWith('.yaml'))
    .sort();
  // Guard the false-green trap: an empty listing would vacuously pass.
  assert.ok(files.length >= 13, `expected >= 13 workflow files, found ${files.length}`);
  for (const file of files) {
    const model = parseWorkflowYaml(fs.readFileSync(path.join(WORKFLOW_DIR, file), 'utf8'));
    assert.ok(
      model && typeof model === 'object' && model.jobs && Object.keys(model.jobs).length > 0,
      `${file}: parsed model carries no jobs`,
    );
    for (const [jobId, job] of Object.entries(model.jobs)) {
      assert.ok(
        Array.isArray(job.steps) || typeof job.uses === 'string',
        `${file}: job ${jobId} parsed with neither steps nor uses`,
      );
    }
  }
});

test('the reader models needs:, matrices and block scalars the way the rule reads them', () => {
  const model = parseWorkflowYaml(
    [
      'name: fixture',
      'on:',
      '  push:',
      '    tags:',
      '      - "v*"',
      'jobs:',
      '  a:',
      '    runs-on: ubuntu-latest   # trailing comment',
      '    steps:',
      '      - run: |',
      '          # a comment INSIDE a block scalar is content',
      '          echo one',
      '        working-directory: dir/a',
      '  b:',
      '    needs: a',
      '    strategy:',
      '      fail-fast: false',
      '      matrix:',
      '        include:',
      '          - leaf: x',
      '            directory: dir/x',
      '          - leaf: y',
      '            directory: dir/y',
      '    steps:',
      '      - run: echo hi',
      '  c:',
      '    needs: [a, b]',
      '    steps:',
      '      - run: echo bye',
      '',
    ].join('\n'),
  );
  assert.deepEqual(Object.keys(model.jobs), ['a', 'b', 'c']);
  assert.equal(model.jobs.a['runs-on'], 'ubuntu-latest');
  assert.match(model.jobs.a.steps[0].run, /# a comment INSIDE a block scalar is content/);
  assert.equal(model.jobs.a.steps[0]['working-directory'], 'dir/a');
  assert.equal(model.jobs.b.strategy['fail-fast'], 'false');
  assert.deepEqual(
    matrixInclude(model.jobs.b).map((v) => v.directory),
    ['dir/x', 'dir/y'],
  );
  assert.deepEqual(needsOf(model.jobs.b), ['a']);
  assert.deepEqual(needsOf(model.jobs.c), ['a', 'b']);
  assert.deepEqual([...transitiveNeeds(model.jobs, 'c')].sort(), ['a', 'b']);
  assert.deepEqual([...transitiveNeeds(model.jobs, 'a')], []);
});

// ── The live release DAG ──────────────────────────────────────────────────

test('release.yml publishes 13 artefacts and the model finds all of them', () => {
  const publishers = discoverPublishers(releaseModel);
  // Fail loudly rather than pass vacuously if the deploy shape changes: the
  // whole rule below is a no-op over an empty publisher set. 13 = core
  // (deploy-core) + 11 deploy-leaf matrix values + ssr-ring (deploy-ssr-ring).
  assert.equal(
    publishers.size,
    13,
    `expected 13 published artefacts, got ${publishers.size}: `
      + `${[...publishers.keys()].join(', ')}`,
  );
  assert.deepEqual(publishers.get('implementation/core'), {
    job: 'deploy-core',
    leaf: 'deploy-core',
  });
  assert.deepEqual(publishers.get('implementation/ssr-ring'), {
    job: 'deploy-ssr-ring',
    leaf: 'ssr-ring',
  });
  assert.equal(publishers.get('implementation/ssr').job, 'deploy-leaf');
});

test('every published in-repo dependency is ordered by the job graph (rf2-p4a93)', () => {
  const violations = orderingViolations(releaseModel);
  assert.deepEqual(violations, [], `release DAG ordering violations:\n  ${violations.join('\n  ')}`);
});

test('ACCEPTANCE: if the ssr leaf does not publish, ssr-ring cannot publish', () => {
  const jobs = releaseModel.jobs;
  const publishers = discoverPublishers(releaseModel);
  const ssr = publishers.get('implementation/ssr');
  const ssrRing = publishers.get('implementation/ssr-ring');
  assert.ok(ssr && ssrRing, 'ssr and ssr-ring must both be published by release.yml');
  assert.notEqual(
    ssr.job,
    ssrRing.job,
    'ssr and ssr-ring must not share a job: GHA cannot order matrix values',
  );
  // A `needs` edge onto a matrix job waits for EVERY value of it to succeed,
  // so requiring the ssr publisher in ssr-ring's transitive closure is exactly
  // the acceptance property.
  assert.ok(
    transitiveNeeds(jobs, ssrRing.job).has(ssr.job),
    `${ssrRing.job} must transitively need ${ssr.job}; needs = `
      + `${JSON.stringify(needsOf(jobs[ssrRing.job]))}`,
  );
});

test('TEETH: the pre-fix shape (ssr-ring inside the deploy-leaf matrix) is rejected', () => {
  // Reconstruct the defect from the CURRENT model rather than a text fixture,
  // so the negative control cannot rot away from the file under test: fold
  // deploy-ssr-ring's matrix value back into deploy-leaf and drop the job.
  const regressed = JSON.parse(JSON.stringify(releaseModel));
  const hoisted = matrixInclude(regressed.jobs['deploy-ssr-ring']);
  assert.equal(hoisted.length, 1, 'deploy-ssr-ring should carry exactly one matrix value');
  regressed.jobs['deploy-leaf'].strategy.matrix.include.push(hoisted[0]);
  delete regressed.jobs['deploy-ssr-ring'];
  regressed.jobs['github-release'].needs = regressed.jobs['github-release'].needs.filter(
    (n) => n !== 'deploy-ssr-ring',
  );

  const violations = orderingViolations(regressed);
  assert.equal(
    violations.length,
    1,
    `expected exactly one violation for the pre-fix shape, got ${violations.length}:\n  `
      + violations.join('\n  '),
  );
  assert.match(violations[0], /ssr-ring publishes a dependency on day8\/re-frame2-ssr/);
  assert.match(violations[0], /'deploy-leaf' also publishes/);
});

test('TEETH: dropping the needs: edge is rejected', () => {
  const regressed = JSON.parse(JSON.stringify(releaseModel));
  regressed.jobs['deploy-ssr-ring'].needs = ['deploy-core'];
  const violations = orderingViolations(regressed);
  assert.equal(violations.length, 1, `expected one violation, got:\n  ${violations.join('\n  ')}`);
  assert.match(violations[0], /does not transitively require 'deploy-leaf'/);
});

test('github-release cuts only after every publishing job succeeded', () => {
  const jobs = releaseModel.jobs;
  const closure = transitiveNeeds(jobs, 'github-release');
  const publisherJobs = new Set([...discoverPublishers(releaseModel).values()].map((p) => p.job));
  for (const jobId of publisherJobs) {
    assert.ok(
      closure.has(jobId),
      `github-release must transitively need '${jobId}', else a SKIPPED deploy still `
        + 'cuts a Release announcing the artefact',
    );
  }
});

test('each leaf rewrites exactly the :local/root coords its deps.edn publishes', () => {
  // The matrix axes tell the Rewrite step which `:local/root` values to turn
  // into `:mvn/version`. A leaf that gains a second in-repo coord in deps.edn
  // without gaining the axis would publish a pom containing a raw
  // `:local/root` — so bind the axes to the deps.edn, in both directions.
  const jobs = releaseModel.jobs;
  for (const [jobId, job] of Object.entries(jobs)) {
    for (const value of matrixInclude(job)) {
      if (!value.directory) continue;
      const declared = [value['local-root'], value['extra-local-root']]
        .filter((v) => typeof v === 'string' && v.length > 0)
        .sort();
      const actual = publishedInRepoDeps(value.directory)
        .map((d) => d.localRoot)
        .sort();
      assert.deepEqual(
        declared,
        actual,
        `${jobId} value '${value.leaf}': matrix declares rewrite roots `
          + `${JSON.stringify(declared)} but ${value.directory}/deps.edn publishes `
          + `${JSON.stringify(actual)}`,
      );
    }
  }
});

test('the retracted fail-fast justification does not come back (rf2-p4a93)', () => {
  // Text-level, deliberately: the claim lived in a COMMENT beside
  // `fail-fast: false`, and a wrong comment next to a safety-critical setting
  // is how this defect survived review. The claim was false for exactly one
  // leaf while another comment in the same file described that leaf's edge.
  assert.doesNotMatch(
    releaseText,
    /no edges between leaves/,
    'release.yml must not re-assert that the published-pom DAG has no edges between '
      + 'leaves — ssr-ring -> ssr is such an edge (rf2-p4a93)',
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
  console.error(`release-dag-policy tests: ${failed} failed.`);
  process.exit(1);
}

console.log(`release-dag-policy tests: ${tests.length} passed.`);
