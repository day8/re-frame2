'use strict';

const net = require('net');

const DEFAULT_BASE_PORT = 8031;
const DERIVED_PORT_SPAN = 2000;
const MAX_PORT_ATTEMPTS = 200;

function hashString(s) {
  let h = 2166136261;
  for (let i = 0; i < s.length; i += 1) {
    h ^= s.charCodeAt(i);
    h = Math.imul(h, 16777619);
  }
  return h >>> 0;
}

function parseExplicitPort(raw) {
  if (raw == null || String(raw).trim() === '') return null;
  const n = Number(String(raw).trim());
  if (!Number.isInteger(n) || n < 1 || n > 65535) {
    throw new Error(
      `STORY_FEATURE_LOAD_PORT must be an integer in 1..65535; got ${JSON.stringify(raw)}`,
    );
  }
  return n;
}

function preferredPort(repoRoot) {
  const offset = hashString(repoRoot || process.cwd()) % DERIVED_PORT_SPAN;
  return DEFAULT_BASE_PORT + offset;
}

function canListen(port, host = '127.0.0.1') {
  return new Promise((resolve) => {
    const server = net.createServer();
    server.once('error', () => resolve(false));
    server.listen(port, host, () => {
      server.close(() => resolve(true));
    });
  });
}

async function findAvailablePort(startPort, opts = {}) {
  const host = opts.host || '127.0.0.1';
  const attempts = opts.attempts || MAX_PORT_ATTEMPTS;
  for (let i = 0; i < attempts; i += 1) {
    const port = startPort + i;
    if (port > 65535) break;
    if (await canListen(port, host)) return port;
  }
  throw new Error(
    `No free Story feature-load port found from ${startPort} after ${attempts} attempts. ` +
      `Set STORY_FEATURE_LOAD_PORT to an unused port for this worktree.`,
  );
}

async function resolveStoryFeatureLoadPort({ env = process.env, repoRoot = process.cwd() } = {}) {
  const explicit = parseExplicitPort(env.STORY_FEATURE_LOAD_PORT);
  if (explicit != null) {
    if (!(await canListen(explicit))) {
      throw new Error(
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
