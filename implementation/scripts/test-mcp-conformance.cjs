#!/usr/bin/env node
/*
 * Single MCP-conformance entry-point for operator pairs (rf2-gt4pf,
 * rf2-a9l6e).
 *
 * ONE command, TWO explicit profiles. Every terminal verdict this script
 * prints names the profile that actually ran, because the two profiles do
 * not prove the same thing and a bare "green" cannot be told apart from a
 * skip (rf2-a9l6e).
 *
 *   default  (medium)     the six MCP-conformance gates PR CI runs as
 *                         separate jobs in `.github/workflows/test.yml`:
 *
 *     1. JVM tools/story-mcp         (`clojure -M:test`)
 *     2. Node tools/story-mcp        stdio roundtrip (rf2-h8z5l)
 *     3. Node tools/re-frame2-pair-mcp  shadow-cljs :server-test
 *     4. MCP conformance tools/re-frame2-pair-mcp  (SDK Client driver, rf2-cum40)
 *     5. MCP conformance tools/story-mcp           (SDK Client driver, rf2-cum40)
 *     6. MCP conformance wire-vocab  (rf2-j2z7o + rf2-6m8tq + rf2-zvv65)
 *
 *   --live   (expensive)  the same six gates, then the EXISTING hermetic
 *                         live Pair suite
 *                         (`tools/mcp-conformance/scripts/run-re-frame2-pair-live-hermetic-suite.cjs`,
 *                         the `test:re-frame2-pair-live-hermetic-suite`
 *                         entry point) as gate 7.
 *
 * Gates #4 + #5 ride on `tools/mcp-conformance/npm test`, which dispatches
 * via `tools/mcp-conformance/scripts/test-all.cjs`. That orchestrator's
 * `TESTS` array IS the authoritative conformance inventory (exec-safety +
 * runner/hermetic unit gates + re-frame2-pair end-to-end + the SKIP-gated
 * live-* probes + story end-to-end + flag-gates) — this wrapper does not
 * re-enumerate it. The re-frame2-pair conformance harness drives the
 * compiled `out/server.js` Node bundle, so this script also runs
 * `shadow-cljs compile server` from `tools/re-frame2-pair-mcp/`
 * before invoking the conformance suite — matching what
 * `mcp-conformance-re-frame2-pair` does in CI.
 *
 * WHY THE PROFILE MUST BE NAMED. Under the default profile the child
 * orchestrator correctly marks its live-* rows `SKIP` (no
 * `SHADOW_CLJS_NREPL_PORT`, so no live runtime is proven). This runner used
 * to collapse that whole child run to one status row and end with an
 * unqualified `ALL MCP-CONFORMANCE GATES GREEN`, which reads as full MCP
 * compatibility when the live layer was never exercised. The default
 * profile now ends on a verdict that says so, and prints the exact `--live`
 * invocation beside a `NOT RUN` row for the hermetic suite.
 *
 * The live roster is NOT re-listed here: `LIVE_TESTS` from
 * `tools/mcp-conformance/scripts/live-test-inventory.cjs` is the one owner
 * (the hermetic runner derives its own `INNER_TESTS` from the same module),
 * and this script only reads its length for the help/verdict text.
 *
 * Fail-fast: the first non-zero exit code halts the run and the
 * orchestrator forwards it verbatim, so the parent shell sees exactly
 * which gate failed — and so the hermetic suite's own exit-code
 * distinction survives (`1` inner live-conformance failure, `2`
 * orchestration/cleanup failure where the teardown could not be proven
 * clean). The summary at the end attributes pass/fail per gate
 * one-glance, matching the shape of
 * `tools/mcp-conformance/scripts/test-all.cjs`.
 *
 * Out of scope per Mike's minimum-scope direction (rf2-gt4pf), unchanged by
 * rf2-a9l6e:
 *   - Formal runner ns (this is a Node operator-side ergonomic, not a
 *     CI gate — CI keeps the six split jobs for differential surface
 *     attribution)
 *   - Rich output formatting / unified report
 *   - Incremental `--changed-only` mode
 *   - A general profile engine: there are exactly two profiles, and the
 *     live one is a single extra step delegating to an existing runner.
 */
'use strict';

const fs = require('node:fs');
const path = require('node:path');

const HERE = __dirname;
const IMPL_ROOT = path.resolve(HERE, '..');
const REPO_ROOT = path.resolve(IMPL_ROOT, '..');

const TOOLS = path.join(REPO_ROOT, 'tools');
const STORY_MCP = path.join(TOOLS, 'story-mcp');
const PAIR_MCP = path.join(TOOLS, 're-frame2-pair-mcp');
const CONFORMANCE = path.join(TOOLS, 'mcp-conformance');
const WIRE_VOCAB = path.join(CONFORMANCE, 'wire-vocab');

// The single live roster. Both `tools/mcp-conformance/scripts/test-all.cjs`
// and the hermetic runner derive their live rows from this module; we read
// it only to say how many rows the live profile covers, so there is no
// second inventory to drift (rf2-a9l6e).
const { LIVE_TESTS } = require(
  path.join(CONFORMANCE, 'scripts', 'live-test-inventory.cjs'),
);

// The copyable repository-root invocations. `implementation/package.json`
// owns the script name; `--prefix` is what makes it runnable from the repo
// root without a second package-level alias.
const ROOT_COMMAND = 'npm --prefix implementation run test:mcp-conformance';
const LIVE_COMMAND = ROOT_COMMAND + ' -- --live';

const PROFILE_DEFAULT = 'default';
const PROFILE_LIVE = 'live';

const PROFILES = {
  [PROFILE_DEFAULT]: { cost: 'medium' },
  [PROFILE_LIVE]: { cost: 'expensive' },
};

// A usage error is not a gate result: nothing ran, so it must not reuse the
// gate exit codes (1 = a gate/inner-conformance failure, 2 = hermetic
// orchestration/cleanup failure). 64 is the conventional EX_USAGE.
const EXIT_USAGE = 64;

// ---------------------------------------------------------------------
// Profile selection. Pure, side-effect-free, and resolved BEFORE anything
// installs, compiles, or boots a server — `--help` and an unknown option
// must never mutate dependency state (rf2-a9l6e).
function parseArgs(argv) {
  let profile = PROFILE_DEFAULT;
  let help = false;
  for (const arg of argv) {
    if (arg === '--help' || arg === '-h') {
      help = true;
      continue;
    }
    if (arg === '--live') {
      profile = PROFILE_LIVE;
      continue;
    }
    return { help: false, profile: null, error: `unknown option \`${arg}\`` };
  }
  return { help, profile, error: null };
}

function helpText() {
  return `test:mcp-conformance — MCP compatibility profiles for re-frame2's MCP servers

USAGE (from the repository root)
  ${ROOT_COMMAND}
  ${LIVE_COMMAND}
  ${ROOT_COMMAND} -- --help

PROFILES
  default (medium)
    Six gates: the story-mcp JVM suite, the story-mcp stdio roundtrip, the
    re-frame2-pair-mcp shadow-cljs :server-test suite, the SDK-client
    conformance inventory for BOTH servers (degraded pair end-to-end + story
    end-to-end + flag gates), and the wire-vocab JVM suite. Proves MCP
    handshake, tool catalogue, descriptor and result-envelope behaviour.
    It does NOT prove live runtime behaviour: with no nREPL port the
    live-re-frame2-pair-* rows report SKIP.

  --live (expensive)
    The six default gates, then the hermetic live Pair suite
    (tools/mcp-conformance/scripts/run-re-frame2-pair-live-hermetic-suite.cjs).
    That suite boots the pair fixture under shadow-cljs, launches Chromium,
    supplies the nREPL port, and runs all ${LIVE_TESTS.length} live rows registered in
    tools/mcp-conformance/scripts/live-test-inventory.cjs. THIS IS THE ONLY
    PROFILE THAT PROVES LIVE RUNTIME SEMANTICS. It certifies green only when
    every live row reaches its own success sentinel and the hermetic teardown
    grades clean. Budget a couple of extra minutes on a warm cache, more when
    the fixture's dependencies or the CLJS build are cold.

PREREQUISITES
  Both profiles: node + npm, \`clojure\` on PATH and a JVM (gates 1 and 6).
  --live also needs Playwright's Chromium and a free port for nREPL.
  Node dependencies are installed by this runner: \`npm ci\` where the tool
  package commits a lockfile, a skip-if-present \`npm install\` where it does
  not (rf2-vtp2er) — so a repeat run does not re-mutate dependency state.

EXIT CODES
  0   the SELECTED profile passed (the verdict line names which one)
  1   a gate failed; under --live, an inner live-conformance failure
  2   under --live, a hermetic orchestration/cleanup failure — the inner
      contract may have passed but the teardown could not be proven clean,
      so the run is not certified
  ${EXIT_USAGE}  usage error (unknown option); nothing ran

NARROWER NEIGHBOUR
  \`npm test\` inside tools/mcp-conformance is NOT either profile. It is the
  Node conformance inventory alone — exec-safety and runner unit gates, the
  SDK-driven end-to-end workflows, and the flag gates — with the live rows
  reporting SKIP. It runs neither JVM suite and never boots the hermetic
  live suite. Per-suite diagnostic recipes: tools/mcp-conformance/README.md.
`;
}

// ---------------------------------------------------------------------
// Exec-safety: no shell dispatch, no bare-name spawns (rf2-1irs7).
//
// The original form spawned every step with
// `spawnSync(bareCommandString, { shell: true, cwd })`. On Windows that
// is the rf2-33vvc command-hijack accident class: a shell-enabled spawn +
// a bare exe name (`npm`/`npx`/`clojure`) + a repo-controlled `cwd`
// resolves against the cwd ahead of PATH, so a checkout that ever
// carried a `npm.cmd` / `npx.cmd` / `clojure.cmd` in one of these dirs
// (a fixture dir, anywhere in PATHEXT order) would silently execute it.
//
// The mcp-conformance slice already SOLVED this for its inner spawn
// sites (`tools/mcp-conformance/test/end-to-end-story.cjs`,
// `scripts/run-live-...-hermetic.cjs`): resolve each tool name to a
// single trusted absolute path OUTSIDE the workspace via
// `resolveTrustedExe`, then spawn with an args ARRAY and no shell. We
// reuse that exact primitive here rather than hand-roll a second one.
const { resolveTrustedExe } = require(
  path.join(CONFORMANCE, 'lib', 'exec-safety.cjs'),
);

// `cross-spawn` (the slice's own spawn helper) is the cross-platform
// piece: given the trusted absolute path `resolveTrustedExe` returns —
// on Windows that is typically the extensionless `npm`/`npx` shim under
// the Node install dir, NOT the `.cmd` — Node's built-in `spawnSync`
// with no shell fails (ENOENT on the shim; EINVAL on a `.cmd` since the
// CVE-2024-27980 fix). `cross-spawn` reads the shebang / dispatches the
// `.cmd` correctly without re-introducing a shell, so `resolveTrustedExe`
// + `cross-spawn` is the only combination that is both hardened and
// cross-platform (Windows / macOS / Linux).
// rf2-ocfiq — `cross-spawn` is an `implementation/` devDependency added
// by rf2-1irs7. A STALE `implementation/node_modules` (one that predates
// the 1irs7 install — e.g. an operator who pulled the change but didn't
// re-run `npm install`) would otherwise crash with a raw loader stack
// (`Error: Cannot find module 'cross-spawn'`) that gives NO hint the fix
// is a one-time `npm install`. The script's own prep STEPS install deps
// for the TOOLS packages, not for `implementation/` itself, so they
// can't cover this. Fail LOUD with an actionable hint instead.
// rf2-a9l6e — loaded lazily, on the first spawn only, so `--help` and the
// unknown-option refusal answer correctly on a checkout whose
// implementation/node_modules is absent, and so the profile-selection
// regression test can require this module without touching npm state.
let crossSpawn = null;
function loadCrossSpawn() {
  if (crossSpawn) return crossSpawn;
  try {
    crossSpawn = require('cross-spawn');
  } catch (err) {
    if (err && err.code === 'MODULE_NOT_FOUND') {
      process.stderr.write(
        "\ntest:mcp-conformance: 'cross-spawn' is not installed.\n" +
          '  This entry-point requires the implementation/ devDependencies.\n' +
          '  Fix: run `npm install` in implementation/ first ' +
          '(rf2-1irs7 added cross-spawn as a devDependency).\n\n',
      );
      process.exit(1);
    }
    throw err;
  }
  return crossSpawn;
}

// Cache one resolution per tool name; the lookup walks PATH + PATHEXT
// and is identical for every step that uses the same tool.
const TRUSTED_EXE_CACHE = new Map();
function trustedExe(name) {
  if (TRUSTED_EXE_CACHE.has(name)) return TRUSTED_EXE_CACHE.get(name);
  const resolved = resolveTrustedExe(name, { workspaceRoot: REPO_ROOT });
  TRUSTED_EXE_CACHE.set(name, resolved);
  return resolved;
}

// `node` steps use `process.execPath` — always an absolute path,
// always the currently-running Node, always outside the workspace by
// construction — so they skip the PATH walk entirely (the same posture
// the slice's `scripts/test-all.cjs` uses for its own node sub-tests).
// Reproducible-install posture (rf2-vtp2er). A bare `npm install` mutates
// dependency state on every run — it can rewrite node_modules and (for a
// package without a committed lockfile) resolve semver ranges against
// whatever the registry happens to publish at run time. That makes a local
// `npm run test:mcp-conformance` not a pure verification command and leaves
// dirty-worktree noise for workers. We pin each tool package to the most
// reproducible install its on-disk state allows:
//
//   - A package WITH a committed package-lock.json (tools/mcp-conformance)
//     installs via `npm ci`: a clean, lockfile-exact install that FAILS if
//     package.json and the lock have drifted, rather than silently
//     rewriting the lock. (`npm ci` requires the lock, so it can only be
//     used where one is committed.)
//   - A package WITHOUT a committed lockfile (tools/re-frame2-pair-mcp is a
//     PUBLISHED npm package that deliberately .gitignores its lock — see
//     tools/re-frame2-pair-mcp/.gitignore) falls back to `npm install`, but
//     ONLY when its node_modules is absent. A present node_modules is
//     treated as already-bootstrapped and the install is SKIPPED, so a
//     repeated verification run does not re-mutate dependency state.
//     Whether this package SHOULD carry a committed lockfile is a separate
//     architecture decision flagged to the operator (rf2-vtp2er follow-up);
//     this runner does not decide it.
//
// `resolveInstallStep` turns the declarative `install` marker into the
// concrete exe/args (or a skip) at run time, against the live on-disk
// lockfile / node_modules state.
function hasCommittedLockfile(pkgDir) {
  return fs.existsSync(path.join(pkgDir, 'package-lock.json'));
}

function nodeModulesPresent(pkgDir) {
  return fs.existsSync(path.join(pkgDir, 'node_modules'));
}

function resolveInstallStep(step) {
  const pkgDir = step.cwd;
  if (hasCommittedLockfile(pkgDir)) {
    // Lockfile-exact, fails on drift, never rewrites the lock.
    return { ...step, exe: 'npm', args: ['ci'], skip: false };
  }
  if (nodeModulesPresent(pkgDir)) {
    // Already bootstrapped + no committed lock to install against —
    // skip rather than re-mutate. The compile/test steps that follow
    // surface any genuinely-missing dep with an actionable error.
    return { ...step, skip: true };
  }
  // No lock and no node_modules — first-run bootstrap. `npm install` is
  // the only option here (npm ci requires a lock); it is reproducible
  // enough for a first bootstrap and won't run again once node_modules
  // exists.
  return { ...step, exe: 'npm', args: ['install'], skip: false };
}

// ---- prerequisites (not gates: excluded from the gate summary) ----
const PREP_STEPS = [
  {
    name: 'install tools/re-frame2-pair-mcp deps',
    install: true,
    cwd: PAIR_MCP,
    prep: true,
  },
  {
    name: 'compile re-frame2-pair-mcp server bundle (shadow-cljs :server)',
    exe: 'npx',
    args: ['shadow-cljs', 'compile', 'server'],
    cwd: PAIR_MCP,
    prep: true,
  },
  {
    name: 'install tools/mcp-conformance deps',
    install: true,
    cwd: CONFORMANCE,
    prep: true,
  },
];

// ---- the six default-profile gates, run as five steps ----
// Names carry NO `[n/6]` prefix: the position and the total are rendered at
// print time from the PLANNED gate list, so the live profile reads `[7/7]`
// rather than claiming six gates while running seven. `covers` is how many
// of CI's split gates a step accounts for — one each, except the
// conformance orchestrator, which is CI gates 4 AND 5 in a single pass.
const DEFAULT_GATES = [
  {
    name: 'JVM tools/story-mcp (clojure -M:test)',
    exe: 'clojure',
    args: ['-M:test'],
    cwd: STORY_MCP,
  },
  {
    name: 'Node tools/story-mcp stdio roundtrip (rf2-h8z5l)',
    node: true,
    args: ['test/stdio-roundtrip.js'],
    cwd: STORY_MCP,
  },
  {
    name: 'Node tools/re-frame2-pair-mcp (shadow-cljs :server-test)',
    exe: 'npm',
    args: ['test'],
    cwd: PAIR_MCP,
  },
  {
    name: 'MCP conformance tools/re-frame2-pair-mcp + tools/story-mcp (rf2-cum40)',
    node: true,
    args: ['scripts/test-all.cjs'],
    cwd: CONFORMANCE,
    covers: 2,
    // tools/mcp-conformance/npm test == node scripts/test-all.cjs;
    // it covers BOTH the re-frame2-pair-mcp end-to-end + live-* SKIP rows
    // and the story-mcp end-to-end + flag-gates in one orchestrator pass,
    // alongside the exec-safety unit tests. The upstream orchestrator
    // already prints its own per-test summary (see
    // `tools/mcp-conformance/scripts/test-all.cjs`), so we don't
    // artificially split its output.
  },
  {
    name: 'MCP conformance wire-vocab (rf2-j2z7o + rf2-6m8tq + rf2-zvv65)',
    exe: 'clojure',
    args: ['-M:test'],
    cwd: WIRE_VOCAB,
  },
];

// ---- the one extra gate the --live profile adds ----
// It is a plain delegation to the EXISTING hermetic entry point (the
// `test:re-frame2-pair-live-hermetic-suite` script of
// tools/mcp-conformance/package.json), spawned under `process.execPath`
// so its exit code — 1 inner, 2 orchestration/cleanup — reaches the
// parent shell verbatim.
const LIVE_GATE = {
  name:
    'hermetic live Pair conformance suite (shadow-cljs fixture + Chromium; ' +
    `${LIVE_TESTS.length} live rows)`,
  node: true,
  args: ['scripts/run-re-frame2-pair-live-hermetic-suite.cjs'],
  cwd: CONFORMANCE,
  live: true,
};

// The whole of "profile" as a mechanism: which gate list runs.
function planRun(profile) {
  const gates =
    profile === PROFILE_LIVE ? [...DEFAULT_GATES, LIVE_GATE] : [...DEFAULT_GATES];
  return { profile, prep: PREP_STEPS, gates };
}

// CI splits the default profile into SIX jobs but one step covers two of
// them, so the `[n/N]` label counts CI gates rather than spawns: the fourth
// step reads `[4+5/6]`, and the live profile's extra step is `[7/7]`.
function gateCount(gates) {
  return gates.reduce((sum, gate) => sum + (gate.covers || 1), 0);
}

function gatePositions(gates) {
  let next = 1;
  return gates.map((gate) => {
    const span = gate.covers || 1;
    const label = Array.from({ length: span }, (_, k) => next + k).join('+');
    next += span;
    return label;
  });
}

const SEP = '─'.repeat(72);

function banner(line) {
  process.stdout.write('\n' + SEP + '\n' + line + '\n' + SEP + '\n');
}

// The terminal verdict. EVERY branch names the profile that ran, so a
// green can never be mistaken for the other profile's green, nor for a
// skip (rf2-a9l6e).
function verdict(profile, gates, firstFailure) {
  const total = gateCount(gates);
  if (firstFailure) {
    const signal = firstFailure.signal ? ` (signal ${firstFailure.signal})` : '';
    return (
      `MCP-CONFORMANCE ${profile.toUpperCase()} PROFILE FAILED — ` +
      `${firstFailure.step} exited ${firstFailure.status}${signal}`
    );
  }
  if (profile === PROFILE_LIVE) {
    return (
      `MCP-CONFORMANCE LIVE PROFILE GREEN — ${total}/${total} gates, ` +
      `hermetic live Pair suite INCLUDED (${LIVE_TESTS.length} live rows)`
    );
  }
  return (
    `MCP-CONFORMANCE DEFAULT PROFILE GREEN — ${total}/${total} gates, ` +
    'hermetic live Pair suite NOT RUN'
  );
}

// The summary block, verdict included. Pure: takes the planned gates and
// whatever results the run produced, returns the text. The default
// profile always carries an explicit NOT RUN row for the hermetic suite
// with the exact opt-in command beside it.
function renderReport({ profile, gates, results, firstFailure }) {
  const total = gateCount(gates);
  const positions = gatePositions(gates);
  const lines = [
    '',
    SEP,
    `test:mcp-conformance summary — profile: ${profile} (${PROFILES[profile].cost})`,
    SEP,
  ];
  gates.forEach((gate, i) => {
    const label = `[${positions[i]}/${total}] ${gate.name}`;
    const result = results[i];
    if (!result) {
      lines.push(`  [NOT RUN ] ${label} (halted by an earlier failure)`);
      return;
    }
    lines.push(
      `  [${result.status === 0 ? 'OK      ' : 'FAIL    '}] ${label} (exit=${result.status})`,
    );
  });
  if (profile === PROFILE_DEFAULT) {
    lines.push(`  [NOT RUN ] ${LIVE_GATE.name}`);
    lines.push('             — not part of the default profile. Opt in with:');
    lines.push(`             ${LIVE_COMMAND}`);
  }
  lines.push('');
  lines.push(verdict(profile, gates, firstFailure));
  if (!firstFailure && profile === PROFILE_DEFAULT) {
    lines.push(`  Live runtime behaviour is unproven here. Run: ${LIVE_COMMAND}`);
  }
  lines.push('');
  return lines.join('\n');
}

function runStep(step) {
  // Resolve the executable up-front: `node` steps use the absolute
  // `process.execPath`; native-tool steps resolve their bare name to a
  // trusted absolute path outside the workspace (rf2-1irs7 / rf2-33vvc).
  const exe = step.node ? process.execPath : trustedExe(step.exe);
  banner(
    '▶ ' + step.name +
      '\n  cwd: ' + step.cwd +
      '\n  exe: ' + exe +
      '\n  args: ' + step.args.join(' '),
  );
  // `cross-spawn` + args ARRAY + no shell: the resolved absolute path
  // is the only thing the OS interprets, so a workspace-local
  // `npm.cmd` / `npx.cmd` / `clojure.cmd` can no longer hijack the
  // invocation (rf2-1irs7). cross-spawn handles the Windows `.cmd` /
  // extensionless-shim dispatch that built-in `spawnSync` can't without
  // one.
  return loadCrossSpawn().sync(exe, step.args, {
    cwd: step.cwd,
    stdio: 'inherit',
    env: process.env,
  });
}

function main(argv) {
  const selection = parseArgs(argv);

  if (selection.error) {
    // Before ANY install, compile, or server boot.
    process.stderr.write(
      `\ntest:mcp-conformance: ${selection.error} — nothing ran.\n` +
        `  Profiles: (no flag) = default/medium, \`--live\` = live/expensive.\n` +
        `  See \`${ROOT_COMMAND} -- --help\`.\n\n`,
    );
    return EXIT_USAGE;
  }

  if (selection.help) {
    process.stdout.write(helpText());
    return 0;
  }

  const { profile, prep, gates } = planRun(selection.profile);
  banner(
    `test:mcp-conformance — profile: ${profile} (${PROFILES[profile].cost})` +
      `\n  gates: ${gateCount(gates)} (in ${gates.length} steps)` +
      (profile === PROFILE_DEFAULT
        ? `\n  hermetic live Pair suite: NOT RUN (opt in: ${LIVE_COMMAND})`
        : `\n  hermetic live Pair suite: INCLUDED (${LIVE_TESTS.length} live rows)`),
  );

  const results = [];
  let firstFailure = null;

  for (const rawStep of [...prep, ...gates]) {
    // Install steps resolve their concrete command (npm ci / npm install /
    // skip) from the live on-disk lockfile + node_modules state (rf2-vtp2er).
    const step = rawStep.install ? resolveInstallStep(rawStep) : rawStep;

    if (step.skip) {
      banner(
        '▷ ' + step.name +
          '\n  cwd: ' + step.cwd +
          '\n  SKIPPED: node_modules already present and no committed ' +
          'lockfile to install against (reproducible-verify; rf2-vtp2er).',
      );
      continue;
    }

    const child = runStep(step);
    const status = child.status === null ? 'signal:' + child.signal : child.status;
    // Prep steps are prerequisites, not gates — keep them out of the
    // gate summary so the `[n/N]` positions stay honest.
    if (!step.prep) {
      results.push({ name: step.name, status });
    }
    if (child.status !== 0) {
      firstFailure = {
        step: step.name,
        status: child.status,
        signal: child.signal,
      };
      break;
    }
  }

  process.stdout.write(renderReport({ profile, gates, results, firstFailure }));

  if (firstFailure) {
    // Forward the child's own code so the hermetic suite's 1-vs-2
    // distinction survives to the parent shell.
    return firstFailure.status === null ? 1 : firstFailure.status;
  }
  return 0;
}

module.exports = {
  parseArgs,
  planRun,
  helpText,
  renderReport,
  verdict,
  gateCount,
  PROFILE_DEFAULT,
  PROFILE_LIVE,
  ROOT_COMMAND,
  LIVE_COMMAND,
  EXIT_USAGE,
  DEFAULT_GATES,
  LIVE_GATE,
};

if (require.main === module) {
  process.exit(main(process.argv.slice(2)));
}
