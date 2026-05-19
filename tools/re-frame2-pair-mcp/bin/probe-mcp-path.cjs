#!/usr/bin/env node
/**
 * probe-mcp-path.cjs — read-only drift detector for ~/.claude.json
 *
 * Background: the MCP server's source directory was renamed from
 * `tools/pair2-mcp/` to `tools/re-frame2-pair-mcp/` in PR #1504
 * (rf2-e2ufx, 2026-05-19). Operators' `~/.claude.json` mcpServers
 * entries still point at the old path until they manually edit them
 * + restart Claude Code. This probe surfaces that drift with a clear
 * remediation suggestion.
 *
 * Posture (rf2-vsxgz):
 *   - READ ONLY. Never writes ~/.claude.json — that is the user's
 *     file. Remediation is operator-driven.
 *   - Silent on success: zero output if no drift is detected and
 *     ~/.claude.json is absent or has no relevant mcpServers entry.
 *   - Cross-platform: pure Node, no shell dependencies. Runs on
 *     Windows / macOS / Linux from `npm run probe-mcp-path`.
 *   - Resilient: missing file is silent; malformed JSON prints a
 *     one-line note to stderr (exit 0) so it never blocks workflows.
 *
 * Exit codes:
 *   0 — no drift detected (or no relevant config present).
 *   1 — drift detected; remediation printed to stdout.
 *   Reserved: 2 — internal error (should not occur in normal use).
 *
 * Usage:
 *   node tools/re-frame2-pair-mcp/bin/probe-mcp-path.cjs
 *   npm run probe-mcp-path           # from tools/re-frame2-pair-mcp/
 *
 * The exported `probe` function is the unit-testable core; tests
 * pass synthetic config shapes + an override home dir.
 */

'use strict';

const fs = require('fs');
const os = require('os');
const path = require('path');

// The legacy directory name. Any mcpServers entry whose `command`,
// `args`, `cwd`, or `env` value contains this token (followed by
// either `/` or end-of-string) is stale post-#1504.
const LEGACY_PATH_TOKEN = 'tools/pair2-mcp';
const LEGACY_PATH_FRAGMENT = 'tools/pair2-mcp/'; // canonical rendered form

// The current directory name — what stale references should be
// rewritten to.
const CURRENT_PATH_FRAGMENT = 'tools/re-frame2-pair-mcp/';

// Match the legacy token at a directory boundary — followed by `/`
// or end-of-string — so `tools/pair2-mcp-something` does NOT trip
// the probe.
const LEGACY_BOUNDARY_RE = /tools\/pair2-mcp(\/|$)/;

// Normalise path separators for the substring check. mcpServers
// values on Windows may carry either `/` or `\`; we compare against
// forward-slash form so the check is platform-agnostic.
function normaliseSeparators(s) {
  return typeof s === 'string' ? s.replace(/\\/g, '/') : s;
}

// Return `true` iff a single string value (e.g. a command or one
// element of an args array) contains the legacy path token at a
// directory boundary.
function isStaleString(value) {
  if (typeof value !== 'string') return false;
  return LEGACY_BOUNDARY_RE.test(normaliseSeparators(value));
}

// Walk one mcpServers entry. Returns an array of `{kind, value}`
// stale-finding objects for any stale string found, or [] if clean.
//
// Recognised kinds: 'command', 'arg', 'cwd', 'env-value'. Unknown
// keys are not inspected — we only flag the documented MCP-config
// shape (see ~/.claude.json schema).
function findStaleFieldsInEntry(entry) {
  const findings = [];
  if (!entry || typeof entry !== 'object') return findings;

  if (isStaleString(entry.command)) {
    findings.push({ kind: 'command', value: entry.command });
  }

  if (Array.isArray(entry.args)) {
    for (const a of entry.args) {
      if (isStaleString(a)) {
        findings.push({ kind: 'arg', value: a });
      }
    }
  }

  if (isStaleString(entry.cwd)) {
    findings.push({ kind: 'cwd', value: entry.cwd });
  }

  if (entry.env && typeof entry.env === 'object') {
    for (const [k, v] of Object.entries(entry.env)) {
      if (isStaleString(v)) {
        findings.push({ kind: 'env-value', value: `${k}=${v}` });
      }
    }
  }

  return findings;
}

// Walk the full ~/.claude.json shape. mcpServers can appear at two
// scopes: top-level (global, applies to every project) and per-project
// under `projects.<path>.mcpServers`. We scan both.
//
// Returns an array of `{scope, server, findings}` objects, one per
// stale server entry. Empty array means no drift.
function scanConfig(config) {
  const stale = [];
  if (!config || typeof config !== 'object') return stale;

  // Top-level mcpServers (global scope).
  if (config.mcpServers && typeof config.mcpServers === 'object') {
    for (const [serverName, entry] of Object.entries(config.mcpServers)) {
      const findings = findStaleFieldsInEntry(entry);
      if (findings.length > 0) {
        stale.push({ scope: 'global', server: serverName, findings });
      }
    }
  }

  // Per-project mcpServers (scoped to a single project path).
  if (config.projects && typeof config.projects === 'object') {
    for (const [projectPath, projectCfg] of Object.entries(config.projects)) {
      if (!projectCfg || typeof projectCfg !== 'object') continue;
      const ms = projectCfg.mcpServers;
      if (!ms || typeof ms !== 'object') continue;
      for (const [serverName, entry] of Object.entries(ms)) {
        const findings = findStaleFieldsInEntry(entry);
        if (findings.length > 0) {
          stale.push({
            scope: `project:${projectPath}`,
            server: serverName,
            findings,
          });
        }
      }
    }
  }

  return stale;
}

// Suggested replacement string: swap the legacy token for the
// current path's matching token. We do NOT apply this to the file —
// printed only. Preserves a trailing `/` if the input had one;
// preserves no trailing character if it didn't (e.g. a `cwd` that
// ended exactly at the directory boundary).
function suggestedReplacement(value) {
  return normaliseSeparators(value).replace(
    LEGACY_BOUNDARY_RE,
    (_match, tail) =>
      `tools/re-frame2-pair-mcp${tail}`,
  );
}

// Render the human-readable remediation message for an array of
// stale entries. Returns a string; caller prints it.
function renderRemediation(staleEntries) {
  const lines = [];
  lines.push('');
  lines.push('re-frame2-pair-mcp: stale path detected in ~/.claude.json');
  lines.push('');
  lines.push(
    'PR #1504 (rf2-e2ufx) renamed the MCP source dir from',
  );
  lines.push(
    `  ${LEGACY_PATH_FRAGMENT}  ->  ${CURRENT_PATH_FRAGMENT}`,
  );
  lines.push(
    'but your ~/.claude.json still points at the old path. The MCP server',
  );
  lines.push('will fail to start until the references are updated.');
  lines.push('');
  lines.push('Stale references:');

  for (const { scope, server, findings } of staleEntries) {
    lines.push(`  [${scope}] mcpServers.${server}`);
    for (const f of findings) {
      lines.push(`    - ${f.kind}: ${f.value}`);
      lines.push(`      suggested: ${suggestedReplacement(f.value)}`);
    }
  }

  lines.push('');
  lines.push(
    'Remediation: open ~/.claude.json in an editor, replace each',
  );
  lines.push(
    `'${LEGACY_PATH_FRAGMENT}' fragment with '${CURRENT_PATH_FRAGMENT}',`,
  );
  lines.push(
    'save, then restart Claude Code. This probe never writes the file;',
  );
  lines.push('your editor is the source of truth.');
  lines.push('');

  return lines.join('\n');
}

// Core probe function — pure, no I/O side effects beyond the read.
// Returns `{status, message, stale}`:
//   status: 'clean' | 'stale' | 'absent' | 'malformed'
//   message: string to print (may be empty for 'clean' / 'absent')
//   stale: array of stale-entry findings (may be empty)
//
// Optional opts.homeDir overrides `os.homedir()` for tests.
// Optional opts.configPath overrides the full path (takes precedence).
function probe(opts) {
  opts = opts || {};
  const configPath =
    opts.configPath ||
    path.join(opts.homeDir || os.homedir(), '.claude.json');

  let raw;
  try {
    raw = fs.readFileSync(configPath, 'utf8');
  } catch (err) {
    if (err && err.code === 'ENOENT') {
      return { status: 'absent', message: '', stale: [] };
    }
    // Permission denied or other I/O error — surface a quiet note
    // on stderr in the caller but do not block. We treat it as
    // 'absent' for exit-code purposes; tests can distinguish via
    // the status field.
    return {
      status: 'absent',
      message: `probe-mcp-path: could not read ${configPath}: ${err.message}`,
      stale: [],
    };
  }

  let config;
  try {
    config = JSON.parse(raw);
  } catch (err) {
    return {
      status: 'malformed',
      message:
        `probe-mcp-path: ${configPath} is not valid JSON ` +
        `(${err.message}); skipping drift check.`,
      stale: [],
    };
  }

  const stale = scanConfig(config);
  if (stale.length === 0) {
    return { status: 'clean', message: '', stale: [] };
  }

  return {
    status: 'stale',
    message: renderRemediation(stale),
    stale,
  };
}

// CLI entry point. Only runs when invoked as a script, not when
// required as a module (the tests require this file).
function main() {
  const result = probe();
  switch (result.status) {
    case 'clean':
    case 'absent':
      // Silent success. If the absent-branch carried a permission
      // note, print it to stderr; otherwise emit nothing.
      if (result.message) {
        process.stderr.write(result.message + '\n');
      }
      process.exit(0);
      return;
    case 'malformed':
      process.stderr.write(result.message + '\n');
      process.exit(0); // non-blocking — operator workflows continue
      return;
    case 'stale':
      process.stdout.write(result.message);
      process.exit(1);
      return;
    default:
      // Should not occur; defensive.
      process.stderr.write(
        `probe-mcp-path: unknown status '${result.status}'\n`,
      );
      process.exit(2);
  }
}

module.exports = {
  probe,
  scanConfig,
  findStaleFieldsInEntry,
  isStaleString,
  suggestedReplacement,
  renderRemediation,
  LEGACY_PATH_FRAGMENT,
  CURRENT_PATH_FRAGMENT,
};

if (require.main === module) {
  main();
}
