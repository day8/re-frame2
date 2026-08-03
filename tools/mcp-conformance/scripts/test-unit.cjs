#!/usr/bin/env node
/* Node-only CI gate. Derives every `node --test` row from test-all.cjs
 * and runs the files together without booting either MCP server. */
'use strict';

const { spawnSync } = require('node:child_process');

const { TESTS, ROOT } = require('./test-all.cjs');

// Plain `node <script>` rows are integration tests and stay excluded.
const UNIT_FILES = TESTS.filter((t) => t.argv[0] === '--test').map(
  (t) => t.argv[t.argv.length - 1],
);

if (UNIT_FILES.length === 0) {
  process.stderr.write(
    'test-unit: no `node --test` rows found in the authoritative inventory — ' +
      'refusing to report green with an empty gate set.\n',
  );
  process.exit(1);
}

const sep = '─'.repeat(72);
process.stdout.write(
  '\n' +
    sep +
    '\n▶ mcp-conformance Node unit cluster\n  node --test ' +
    UNIT_FILES.join(' ') +
    '\n' +
    sep +
    '\n',
);

// ROOT keeps the inventory's relative paths independent of caller cwd.
const child = spawnSync(process.execPath, ['--test', ...UNIT_FILES], {
  cwd: ROOT,
  stdio: 'inherit',
  env: process.env,
});

if (child.status === null) {
  process.stderr.write(
    `\ntest-unit: node --test terminated by signal ${child.signal}\n`,
  );
  process.exit(1);
}

if (child.status === 0) {
  process.stdout.write('\nMCP-CONFORMANCE NODE UNIT CLUSTER GREEN\n');
}
process.exit(child.status);
