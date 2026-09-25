// Shared, pure env-construction for the duplicate-`done` drift-check
// waiver.
//
// serve-and-run-browser-tests.cjs forwards a declaration to its runner
// child ONLY when the ORCHESTRATOR's own `--duplicate-done-drift-unverifiable`
// CLI flag is present — never inferred, never inherited from an ambient
// environment. It lives in its own module so the
// rule is unit-testable in isolation: requiring
// scripts/serve-and-run-browser-tests.cjs directly runs its whole top-level
// (CLI parsing off real process.argv, path-policy enforcement, signal-handler
// installation), none of which a fast policy test wants to trigger.
//
// WHY A SPREAD IS NOT ENOUGH. `{ ...baseEnv, ...(cond ? {K: v} : {}) }` only ever
// ADDS the key — it never REMOVES one `baseEnv` already carried. An ambient
// `RF2_DUPLICATE_DONE_DRIFT_UNVERIFIABLE=1` (a parent shell export, or left
// behind by an earlier `--duplicate-done-drift-unverifiable` run in the same
// terminal) would ride straight through to the UNFLAGGED default `test:browser`
// lane's runner child, which would then take the waiver branch in
// run-browser-tests.cjs and skip the fail-closed drift verdict entirely —
// defeating the guarantee `_impl-browser-runners-verdict-policy.test.cjs`
// pins ("the default `test:browser` lane CARRIES the drift check").
//
// So the env is built explicitly, pinning BOTH directions dynamically:
// `driftUnverifiable: true` always forwards '1' regardless of what `baseEnv`
// already held; `driftUnverifiable: false` always REMOVES the var, regardless
// of what `baseEnv` already held. The declaration comes from THIS process's
// own command line every time, never from what happened to be exported
// around it.

'use strict';

const DRIFT_UNVERIFIABLE_ENV_VAR = 'RF2_DUPLICATE_DONE_DRIFT_UNVERIFIABLE';
const DRIFT_UNVERIFIABLE_FLAG = '--duplicate-done-drift-unverifiable';

function computeRunnerEnv(baseEnv, { driftUnverifiable, browserTestUrl }) {
  const runnerEnv = { ...baseEnv, BROWSER_TEST_URL: browserTestUrl };
  if (driftUnverifiable) {
    runnerEnv[DRIFT_UNVERIFIABLE_ENV_VAR] = '1';
  } else {
    delete runnerEnv[DRIFT_UNVERIFIABLE_ENV_VAR];
  }
  return runnerEnv;
}

module.exports = {
  computeRunnerEnv,
  DRIFT_UNVERIFIABLE_ENV_VAR,
  DRIFT_UNVERIFIABLE_FLAG,
};
