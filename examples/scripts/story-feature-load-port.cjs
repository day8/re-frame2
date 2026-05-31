/*
 * Worktree-aware port resolver for the Story feature-load gate. The
 * deterministic hash of the worktree path derives a stable port offset
 * so parallel worktrees (rf2-* worker checkouts) never collide on 8031.
 * Honours STORY_FEATURE_LOAD_PORT if set; otherwise scans forward from
 * the derived preference until an unused port is found.
 *
 * The bind probe / forward scan / strict-parse mechanism is shared with
 * the adapter-smoke resolver via port-resolver.cjs; this file supplies
 * only the Story-specific POLICY (worktree-hashed preferred port +
 * wording). Errors are plain (non-actionable) — the Story orchestrators
 * print full errors, unlike the examples orchestrator.
 */

'use strict';

const {
  MAX_PORT_ATTEMPTS,
  canListen,
  findAvailablePort: findAvailablePortShared,
  makeParseExplicitPort,
  portError,
} = require('./port-resolver.cjs');

const DEFAULT_BASE_PORT = 8031;
const DERIVED_PORT_SPAN = 2000;

function hashString(s) {
  let h = 2166136261;
  for (let i = 0; i < s.length; i += 1) {
    h ^= s.charCodeAt(i);
    h = Math.imul(h, 16777619);
  }
  return h >>> 0;
}

const parseExplicitPort = makeParseExplicitPort('STORY_FEATURE_LOAD_PORT');

function preferredPort(repoRoot) {
  const offset = hashString(repoRoot || process.cwd()) % DERIVED_PORT_SPAN;
  return DEFAULT_BASE_PORT + offset;
}

// Wrap the shared scanner with the Story-specific exhausted-port wording
// so the public signature (and the per-worktree hint) is unchanged.
function findAvailablePort(startPort, opts = {}) {
  return findAvailablePortShared(startPort, {
    ...opts,
    exhausted: (start, attempts) =>
      portError(
        `No free Story feature-load port found from ${start} after ${attempts} attempts. ` +
          `Set STORY_FEATURE_LOAD_PORT to an unused port for this worktree.`,
      ),
  });
}

async function resolveStoryFeatureLoadPort({ env = process.env, repoRoot = process.cwd() } = {}) {
  const explicit = parseExplicitPort(env.STORY_FEATURE_LOAD_PORT);
  if (explicit != null) {
    if (!(await canListen(explicit))) {
      throw portError(
        `STORY_FEATURE_LOAD_PORT=${explicit} is already in use. ` +
          `Choose a unique port for this worktree.`,
      );
    }
    return explicit;
  }
  return findAvailablePort(preferredPort(repoRoot));
}

module.exports = {
  DEFAULT_BASE_PORT,
  DERIVED_PORT_SPAN,
  MAX_PORT_ATTEMPTS,
  canListen,
  findAvailablePort,
  hashString,
  parseExplicitPort,
  preferredPort,
  resolveStoryFeatureLoadPort,
};
