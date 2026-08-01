#!/usr/bin/env node

'use strict';

/*
 * Hermetic classifier tests for `.github/scripts/resolve-clojure-deps.sh`
 * (rf2-vm036, merged-PR audit of #7221).
 *
 * WHAT IS BEING PINNED. The script retries `clojure -P` when the resolver's
 * output carries a transient TRANSPORT signature, and reports differently
 * when it does not. Two things had to be true and only one was: the retry is
 * bounded (it was), and the verdict after exhaustion is honest about what the
 * signature proves (it was not — it read "INFRASTRUCTURE, not this diff" and
 * "not of any coordinate").
 *
 * A 403, an `ArtifactTransportException`, an `UnknownHostException` — each
 * earns a retry, and none establishes ownership. A repository the change
 * itself added that requires credentials emits exactly these strings. So the
 * exhausted verdict must present the signature as EVIDENCE A RE-RUN MAY HELP
 * and must not tell the author to look away from their own diff.
 *
 * HERMETIC, and that is the point. The audit's finding came from running the
 * real script against a stub resolver; these tests do the same, so they
 * exercise the classifier rather than describing it. `clojure` and `sleep` are
 * both stubbed onto PATH — `sleep` so the 45s of real backoff costs nothing,
 * `clojure` so each attempt's output is scripted. No network, no JVM.
 *
 * Fixtures take a process-scoped lane under the gitignored `.scratch/`
 * (rf2-2i1ay) rather than `os.tmpdir()`, so concurrent runs cannot collide.
 */

const assert = require('assert/strict');
const { spawnSync } = require('child_process');
const fs = require('fs');
const path = require('path');

const { createPolicyTestSuite } = require('./_policy-test-util.cjs');
const { makeScratchDir, cleanupScratchDirs } = require('./lib/scratch-fixtures.cjs');

const IMPL_ROOT = path.resolve(__dirname, '..');
const REPO_ROOT = path.resolve(IMPL_ROOT, '..');
const SCRIPT_REL = '.github/scripts/resolve-clojure-deps.sh';

const { test, run } = createPolicyTestSuite('resolve-clojure-deps-classifier');

function relPosix(abs) {
  return path.relative(REPO_ROOT, abs).split(path.sep).join('/');
}

// Build a PATH sandbox whose `clojure` emits `attempts[i]` on invocation i.
// Each entry is { out, code }: the resolver text and the exit status. The
// last entry repeats if the script attempts more times than were scripted.
// Every invocation appends a line to `calls.log`, which is how attempts are
// counted.
function makeSandbox(attempts) {
  const dir = makeScratchDir(REPO_ROOT, 'rf2-resolve-deps');
  const bin = path.join(dir, 'bin');
  fs.mkdirSync(bin, { recursive: true });

  const script = [
    '#!/usr/bin/env bash',
    `calls="$(dirname "$0")/../calls.log"`,
    'echo call >> "$calls"',
    'n=$(wc -l < "$calls" | tr -d " ")',
    'case "$n" in',
    ...attempts.flatMap(({ out, code }, i) => [
      `  ${i + 1}) cat <<'RESOLVER_EOF'`,
      out,
      'RESOLVER_EOF',
      `     exit ${code} ;;`,
    ]),
    // Anything beyond the scripted list repeats the final entry.
    `  *) cat <<'RESOLVER_EOF'`,
    attempts[attempts.length - 1].out,
    'RESOLVER_EOF',
    `     exit ${attempts[attempts.length - 1].code} ;;`,
    'esac',
    '',
  ].join('\n');

  const clojureStub = path.join(bin, 'clojure');
  fs.writeFileSync(clojureStub, script);
  fs.chmodSync(clojureStub, 0o755);

  // Neutralise the backoff so the matched-path tests are instant.
  const sleepStub = path.join(bin, 'sleep');
  fs.writeFileSync(sleepStub, '#!/usr/bin/env bash\nexit 0\n');
  fs.chmodSync(sleepStub, 0o755);

  fs.writeFileSync(path.join(dir, 'calls.log'), '');
  return { dir, binRel: relPosix(bin) };
}

function shQuote(s) {
  return `'${String(s).replace(/'/g, `'\\''`)}'`;
}

// Run the real script with the sandbox ahead of PATH. Paths are repo-relative
// and drive-letter-free, then made absolute inside the shell against a cwd of
// REPO_ROOT — the only form every supported Bash flavour accepts (rf2-6m7pn4).
function runScript(sandbox, args = []) {
  const command =
    `PATH="$PWD/${sandbox.binRel}:$PATH" ` +
    [`./${SCRIPT_REL}`, ...args.map(shQuote)].join(' ');
  const res = spawnSync('bash', ['-lc', command], {
    cwd: REPO_ROOT,
    encoding: 'utf8',
  });
  const callsFile = path.join(sandbox.dir, 'calls.log');
  const calls = fs.existsSync(callsFile)
    ? fs.readFileSync(callsFile, 'utf8').split('\n').filter(Boolean).length
    : 0;
  const output = `${res.stdout || ''}${res.stderr || ''}`;
  // The script `tee`s the resolver's own text into the CI log live, so the
  // full output legitimately contains every attempt's resolver text. The
  // VERDICT is only the `::error` annotation, and assertions about what the
  // verdict claims must read that alone.
  const annotation = output
    .split(/\r?\n/)
    .filter((l) => l.startsWith('::error'))
    .join('\n');
  return { ...res, calls, output, annotation };
}

const THROTTLE_403 = [
  'Downloading: org/jsoup/jsoup/1.15.2/jsoup-1.15.2.pom from central',
  'Error building classpath. Failed to read artifact descriptor for org.jsoup:jsoup:jar:1.15.2',
  'ArtifactTransportException: Could not transfer artifact org.jsoup:jsoup:pom:1.15.2 from/to central',
  '(https://repo1.maven.org/maven2/): status code: 403, reason phrase: Forbidden',
].join('\n');

const BAD_COORDINATE = [
  'Error building classpath. Failed to read artifact descriptor for no.such:thing:jar:9.9.9',
  'Could not find artifact no.such:thing:pom:9.9.9 in central (https://repo1.maven.org/maven2/)',
].join('\n');

const SERVER_5XX = [
  'ArtifactTransportException: Could not transfer artifact from/to central',
  '(https://repo1.maven.org/maven2/): status code: 503, reason phrase: Service Unavailable',
].join('\n');

// A fault the DIFF owns that nevertheless emits transport signatures — the
// audit's counter-example, and the reason the verdict must stay cause-neutral.
const DIFF_OWNED_AUTH_403 = [
  'ArtifactTransportException: authentication failed for repository configured by this change',
  '(https://private.example.invalid/maven): status code: 403, reason phrase: Forbidden',
].join('\n');

// ── success ─────────────────────────────────────────────────────────────

test('a clean resolve exits 0 on the FIRST attempt and annotates nothing', () => {
  const sandbox = makeSandbox([{ out: 'Downloading: ok', code: 0 }]);
  try {
    const res = runScript(sandbox, ['-M:test']);
    assert.equal(res.status, 0, `expected exit 0, got ${res.status}\n${res.output}`);
    assert.equal(res.calls, 1, 'a successful resolve must not be retried');
    assert.doesNotMatch(res.output, /::error/, 'a success must emit no ::error annotation');
  } finally {
    cleanupScratchDirs();
  }
});

test('a transient failure that then succeeds exits 0 without any verdict', () => {
  const sandbox = makeSandbox([
    { out: THROTTLE_403, code: 1 },
    { out: 'Downloading: ok', code: 0 },
  ]);
  try {
    const res = runScript(sandbox, ['-M:test']);
    assert.equal(res.status, 0, `expected exit 0 after the retry, got ${res.status}\n${res.output}`);
    assert.equal(res.calls, 2, 'the transient attempt must be retried exactly once here');
    assert.doesNotMatch(res.output, /::error/, 'a recovered resolve must emit no ::error annotation');
  } finally {
    cleanupScratchDirs();
  }
});

// ── unmatched: no retry, no ownership claim ─────────────────────────────

test('an UNMATCHED failure is not retried and claims no cause', () => {
  const sandbox = makeSandbox([{ out: BAD_COORDINATE, code: 1 }]);
  try {
    const res = runScript(sandbox, ['-M:test']);
    assert.notEqual(res.status, 0, 'a resolution failure must exit nonzero');
    assert.equal(
      res.calls,
      1,
      'a failure with no transient transport signature must NOT be retried — '
        + 'a bad coordinate resolves exactly as badly the third time',
    );
    assert.match(
      res.output,
      /no transient transport signature/,
      'the annotation must name the classification that was reached',
    );
    assert.doesNotMatch(
      res.output,
      /INFRASTRUCTURE/,
      'an unmatched failure must never be labelled infrastructure',
    );
  } finally {
    cleanupScratchDirs();
  }
});

// ── matched + exhausted: retried, but cause-neutral ─────────────────────

test('a MATCHED failure is retried to the bound, then reports WITHOUT blaming infrastructure (rf2-vm036 audit)', () => {
  const sandbox = makeSandbox([{ out: THROTTLE_403, code: 1 }]);
  try {
    const res = runScript(sandbox, ['-M:test']);
    assert.notEqual(res.status, 0, 'an exhausted resolve must exit nonzero');
    assert.equal(res.calls, 3, 'the retry must be bounded at three attempts');
    // It must still quote the signature — the reader has to be able to check
    // the reasoning rather than trust a label.
    assert.match(
      res.output,
      /status code: 403/,
      'the verdict must quote the signature that earned the retry',
    );
    assert.match(
      res.output,
      /re-run may help|RE-RUN MAY HELP/,
      'the verdict must present the signature as evidence a re-run may help',
    );
    // The categorical claims this bead was reopened to remove.
    assert.doesNotMatch(
      res.output,
      /INFRASTRUCTURE, not this diff/,
      'the verdict must not categorically absolve the diff (rf2-vm036 audit of #7221)',
    );
    assert.doesNotMatch(
      res.output,
      /not of any coordinate/,
      'the verdict must not claim no coordinate is at fault — it cannot know that',
    );
  } finally {
    cleanupScratchDirs();
  }
});

test('a diff-owned auth 403 gets the SAME cause-neutral treatment, not an absolution', () => {
  // The audit's hermetic counter-example: the diff added a repository that
  // needs credentials. It matches the transport signatures, so it is retried
  // — that is acceptable — but it must not be told it is not its own fault.
  const sandbox = makeSandbox([{ out: DIFF_OWNED_AUTH_403, code: 1 }]);
  try {
    const res = runScript(sandbox, ['-M:test']);
    assert.notEqual(res.status, 0, 'must exit nonzero');
    assert.doesNotMatch(
      res.output,
      /INFRASTRUCTURE, not this diff|not of any coordinate/,
      'a diff-owned failure wearing a transport signature must not be absolved',
    );
    assert.match(
      res.output,
      /check any repository or coordinate this change touched/,
      'the verdict must point the reader back at the diff as a live possibility',
    );
  } finally {
    cleanupScratchDirs();
  }
});

// ── changing signature across attempts ──────────────────────────────────

test('the classification is re-evaluated per attempt, not latched from the first', () => {
  // Attempt 1 looks transient (503) so it is retried; attempt 2 is a bad
  // coordinate. The script must stop THERE with the unmatched verdict rather
  // than carrying the first attempt's signature to an infrastructure claim.
  const sandbox = makeSandbox([
    { out: SERVER_5XX, code: 1 },
    { out: BAD_COORDINATE, code: 1 },
  ]);
  try {
    const res = runScript(sandbox, ['-M:test']);
    assert.notEqual(res.status, 0, 'must exit nonzero');
    assert.equal(
      res.calls,
      2,
      'the run must stop at the attempt whose output carries no transient signature',
    );
    assert.match(
      res.annotation,
      /no transient transport signature/,
      'the FINAL attempt decides the classification',
    );
    assert.doesNotMatch(
      res.annotation,
      /status code: 503/,
      'a stale signature from an earlier attempt must not reach the verdict',
    );
    assert.doesNotMatch(
      res.annotation,
      /INFRASTRUCTURE/,
      'an earlier transient attempt must not promote the verdict to infrastructure',
    );
  } finally {
    cleanupScratchDirs();
  }
});

run();
