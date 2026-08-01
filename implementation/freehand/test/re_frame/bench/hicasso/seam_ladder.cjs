#!/usr/bin/env node
// THE SEAM AGAINST LOAD — the load half (rf2-cvvb7).
//
//   node .../seam_ladder.cjs --load 12 --label heavy-A --json out/heavy-A.json
//   node .../seam_ladder.cjs --spin 30000          (the load generator alone)
//
// `the-candidates-clock.md` §6 refuses three rows, and one of its grounds
// is that the cross-segment floor seam read 34% on one run and 3.8% on
// another "with nothing changed but how busy the machine was". Two points
// are a line through two dots. This runs the clock at a STATED number of
// competing busy cores so the relationship can be measured instead of
// asserted.
//
// ## THE LOAD IS BOUNDED BY CONSTRUCTION, AND THAT IS NOT A COURTESY
//
// A benchmark box is shared. A 32-way load generator run on this one for a
// legitimate reason timed out unrelated work across the machine, so every
// spinner here carries its own deadline (`--spin <ms>`) and exits on it
// whether or not the parent is alive to kill it. The parent kills them in a
// `finally`, and the deadline is what covers the case where the parent
// itself dies. A load generator that can outlive its window is a hazard
// left lying around, not an instrument.
//
// `--load` is capped at `hardware-concurrency - 4` so the box keeps enough
// cores to stay answerable. The cap is reported when it bites, because a
// rung that silently became a different rung is worse than a missing one.
//
// ## WHAT THE SPINNER IS, AND WHY NOT `while (true) {}`
//
// A bare empty loop is optimised into something that touches no memory, and
// what a busy neighbour actually costs a benchmark is cache and memory
// bandwidth as much as cycles. Each spinner walks a 4 MB typed array with a
// data-dependent stride, which keeps it resident in cache and out of the
// optimiser's reach, and reads a value at the end so nothing is dead code.

'use strict';

const os = require('node:os');
const path = require('node:path');
const { spawn, spawnSync } = require('node:child_process');

const argv = process.argv.slice(2);
const flag = (name, dflt) => {
  const i = argv.indexOf(`--${name}`);
  return i >= 0 && argv[i + 1] !== undefined ? argv[i + 1] : dflt;
};

// --- the spinner child -----------------------------------------------------

const SPIN_MS = Number(flag('spin', 0));
if (SPIN_MS > 0) {
  const N = 1 << 20; // 4 MB of Int32
  const buf = new Int32Array(N);
  for (let i = 0; i < N; i++) buf[i] = (i * 2654435761) | 0;
  const deadline = Date.now() + SPIN_MS;
  let acc = 0;
  let i = 0;
  // The deadline is checked once per 2^16 steps: often enough that the
  // spinner cannot overrun its window by anything a human would notice,
  // rarely enough that `Date.now` is not what is being benchmarked.
  for (;;) {
    for (let k = 0; k < 65536; k++) {
      i = (i + (buf[i & (N - 1)] & 1023) + 1) & (N - 1);
      acc = (acc + buf[i]) | 0;
      buf[i] = (buf[i] * 1103515245 + 12345) | 0;
    }
    if (Date.now() >= deadline) break;
  }
  process.stdout.write(String(acc & 1));
  process.exit(0);
}

// --- the parent ------------------------------------------------------------

const HERE = __dirname;
const IMPL = path.resolve(HERE, '../../../../..');
const CORES = os.cpus().length;
const CAP = Math.max(0, CORES - 4);

const wanted = Number(flag('load', 0));
const load = Math.min(wanted, CAP);
const label = flag('label', `load${load}`);
const json = flag('json', '');
const budgetMs = Number(flag('budget', 20 * 60 * 1000));
const only = flag('only', 'bulk300');

if (wanted > CAP) {
  console.error(
    `[ladder] --load ${wanted} CAPPED to ${CAP} — this box reports ${CORES} cores and the ` +
      `harness keeps 4 of them free so the machine stays answerable`
  );
}

const children = [];
function startLoad() {
  for (let i = 0; i < load; i++) {
    const c = spawn(process.execPath, [__filename, '--spin', String(budgetMs)], {
      stdio: ['ignore', 'ignore', 'ignore'],
      detached: false,
    });
    children.push(c);
  }
}
function stopLoad() {
  for (const c of children) {
    try {
      c.kill('SIGKILL');
    } catch {
      /* already gone; the spinner's own deadline covers it */
    }
  }
}

process.on('exit', stopLoad);
process.on('SIGINT', () => {
  stopLoad();
  process.exit(130);
});

console.error(
  `[ladder] label=${label} load=${load}/${CORES} cores, spinner deadline ${budgetMs} ms, row(s) ${only}`
);
startLoad();

// Let the spinners reach steady state before the clock starts, so the run
// is not measuring the ramp.
const settleUntil = Date.now() + (load > 0 ? 3000 : 0);
while (Date.now() < settleUntil) {
  /* deliberate spin: this parent has nothing else to do and a sleep here
     would need a timer the synchronous spawnSync below cannot yield to */
}

const started = Date.now();
const r = spawnSync(
  process.execPath,
  [path.join(HERE, 'clock_run.cjs'), '--no-build'],
  {
    cwd: IMPL,
    stdio: ['ignore', 'inherit', 'inherit'],
    env: {
      ...process.env,
      HCLOCK_ONLY: only,
      HCLOCK_LABEL: label,
      HCLOCK_JSON: json,
      HCLOCK_LOAD: String(load),
    },
    timeout: budgetMs,
  }
);
const elapsed = Date.now() - started;
stopLoad();
console.error(`[ladder] ${label}: clock exited ${r.status} after ${(elapsed / 1000).toFixed(1)} s, load released`);
process.exit(r.status === null ? 1 : r.status);
