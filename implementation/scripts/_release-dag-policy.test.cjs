#!/usr/bin/env node
/*
 * Release-DAG ordering guard for `.github/workflows/release.yml`, which runs only
 * on a version-tag push, so no PR CI exercises it otherwise.
 *
 * For every artefact the workflow publishes, and every in-repo coordinate in that
 * artefact's published `:deps` (read from its real deps.edn), the job publishing
 * the dependency must be a strict transitive `needs:` ancestor of the job
 * publishing the dependent. Two values of one matrix cannot be ordered and that
 * matrix runs `fail-fast: false`, so otherwise a dependent (ssr-ring, fresco ->
 * ssr) could publish beside a red sibling, and Clojars cannot take the
 * unresolvable pom back. The TEETH cases rebuild the violating shapes from the
 * current model and require the rule to reject them.
 * Discovered by `npm run test:scripts`.
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

// A job publishes <dir> when it runs `clojure -M:clein deploy` with
// `working-directory: <dir>`; a matrix expression expands over the job's
// `include:` values.
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

// The `:local/root` coordinates in an artefact's top-level `:deps` (aliases are
// never published), as repo-relative posix dirs.
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

// Human-readable violations; empty means every published edge is ordered.
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

test('every published in-repo dependency is ordered by the job graph (rf2-p4a93)', () => {
  const violations = orderingViolations(releaseModel);
  assert.deepEqual(violations, [], `release DAG ordering violations:\n  ${violations.join('\n  ')}`);
});

test('ACCEPTANCE: if the ssr leaf does not publish, ssr-ring cannot publish', () => {
  const jobs = releaseModel.jobs;
  const publishers = discoverPublishers(releaseModel);
  const ssr = publishers.get('implementation/ssr');
  const ssrRing = publishers.get('implementation/ssr-ring');
  // A needs edge onto a matrix job waits for every value of it to succeed.
  assert.ok(
    transitiveNeeds(jobs, ssrRing.job).has(ssr.job),
    `${ssrRing.job} must transitively need ${ssr.job}; needs = `
      + `${JSON.stringify(needsOf(jobs[ssrRing.job]))}`,
  );
});

test('ACCEPTANCE: if the ssr leaf does not publish, fresco cannot publish', () => {
  // Fresco carries the same published edge onto ssr, ordered by its own needs:
  // block; a shared loop over "the dependent leaves" would pass vacuously were the
  // set emptied by a refactor.
  const jobs = releaseModel.jobs;
  const publishers = discoverPublishers(releaseModel);
  const ssr = publishers.get('implementation/ssr');
  const fresco = publishers.get('implementation/fresco');
  assert.ok(
    transitiveNeeds(jobs, fresco.job).has(ssr.job),
    `${fresco.job} must transitively need ${ssr.job}; needs = `
      + `${JSON.stringify(needsOf(jobs[fresco.job]))}`,
  );
});

test('TEETH: the pre-fix shape (ssr-ring inside the deploy-leaf matrix) is rejected', () => {
  // Fold deploy-ssr-ring's matrix value into deploy-leaf and drop the job.
  const regressed = JSON.parse(JSON.stringify(releaseModel));
  const hoisted = matrixInclude(regressed.jobs['deploy-ssr-ring']);
  regressed.jobs['deploy-leaf'].strategy.matrix.include.push(hoisted[0]);
  delete regressed.jobs['deploy-ssr-ring'];
  regressed.jobs['github-release'].needs = regressed.jobs['github-release'].needs.filter(
    (n) => n !== 'deploy-ssr-ring',
  );

  const violations = orderingViolations(regressed);
  assert.ok(
    violations.length === 1 &&
      /ssr-ring publishes a dependency on day8\/re-frame2-ssr/.test(violations[0]) &&
      /'deploy-leaf' also publishes/.test(violations[0]),
    `expected exactly the folded-matrix violation, got:\n  ${violations.join('\n  ')}`,
  );
});

test('TEETH: dropping the needs: edge is rejected', () => {
  const regressed = JSON.parse(JSON.stringify(releaseModel));
  regressed.jobs['deploy-ssr-ring'].needs = ['deploy-core'];
  const violations = orderingViolations(regressed);
  assert.ok(
    violations.length === 1 && /does not transitively require 'deploy-leaf'/.test(violations[0]),
    `expected exactly the missing-needs violation, got:\n  ${violations.join('\n  ')}`,
  );
});

test('github-release cuts only after every publishing job succeeded', () => {
  const jobs = releaseModel.jobs;
  const closure = transitiveNeeds(jobs, 'github-release');
  const publisherJobs = new Set([...discoverPublishers(releaseModel).values()].map((p) => p.job));
  const unordered = [...publisherJobs].filter((jobId) => !closure.has(jobId));
  // A skipped deploy must not still cut a Release announcing the artefact.
  assert.deepEqual(unordered, [], 'github-release must transitively need every publishing job');
});

test('each leaf rewrites exactly the :local/root coords its deps.edn publishes', () => {
  // A leaf that gains an in-repo coord without the matching rewrite axis would
  // publish a raw :local/root, so the axes are bound to deps.edn both ways.
  const mismatches = [];
  for (const [jobId, job] of Object.entries(releaseModel.jobs)) {
    for (const value of matrixInclude(job)) {
      if (!value.directory) continue;
      const declared = [value['local-root'], value['extra-local-root']]
        .filter((v) => typeof v === 'string' && v.length > 0)
        .sort();
      const actual = publishedInRepoDeps(value.directory)
        .map((d) => d.localRoot)
        .sort();
      if (JSON.stringify(declared) !== JSON.stringify(actual)) {
        mismatches.push(`${jobId} '${value.leaf}': axes ${JSON.stringify(declared)}, deps.edn ${JSON.stringify(actual)}`);
      }
    }
  }
  assert.deepEqual(mismatches, []);
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
