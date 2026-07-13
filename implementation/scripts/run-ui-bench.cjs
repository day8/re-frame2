#!/usr/bin/env node
// G-1 direct-render parity gate orchestrator (rf2-vxgfnd.6; 07 §5).
//
// 1. `shadow-cljs release ui-bench` — the :advanced :node-script bench
//    bundle (compiled views + hand-written jsx-runtime baselines +
//    the revised estimator harness).
// 2. EMITTED-JS GOLDEN over the bundle — pins S-1's landmine: a
//    CLJS-var-bound jsx fn silently reintroduces IFn-protocol dispatch
//    at every element call site under :advanced (~5-8%, S-1 measured —
//    a regression the 10% budget could absorb without ever failing a
//    test). Two rename-robust assertions:
//      (a) direct-call presence — the bundle carries >= MIN_DIRECT
//          literal-tag jsx calls (`X.jsx("div", ...)` / aliased
//          `Y("div", {...})` still matches via the `("tag"` shape on
//          the known bench tag set);
//      (b) compiler-direct presence — generated sites carry the unbound
//          module-property form `(0,X.jsx)("div", ...)`; wrapper bodies and
//          the hand baseline cannot satisfy this assertion;
//      (c) IFn-trap absence — zero `.call(null,"div",...)` shapes.
//      (d) digest-carrier elision — the dev sentinel, bd1 literal and carrier
//          namespace are all absent from the :advanced production bundle.
// 3. Run the bundle under NODE_ENV=production — the estimator +
//    byte-equality precheck + budget enforcement live in CLJS
//    (re-frame.ui.bench.main); its exit code is the gate.
//
// Exit non-zero on any step failing.
'use strict';

const spawnSync = require('cross-spawn').sync;
const fs = require('fs');
const path = require('path');

const IMPL = path.join(__dirname, '..');
const BUNDLE = path.join(IMPL, 'out', 'ui-bench.js');

// The DOM tags the bench components emit — the literal-tag call shapes
// below are scoped to these so generic runtime code cannot false-hit.
const TAGS = [
  'div', 'h1', 'h2', 'p', 'em', 'ul', 'li', 'a', 'footer', 'button',
  'input', 'span', 'section', 'strong', 'hr',
];

function run(cmd, args, opts = {}) {
  const r = spawnSync(cmd, args, {
    cwd: IMPL,
    stdio: 'inherit',
    ...opts,
  });
  if (r.status !== 0) {
    console.error(`FAIL: ${cmd} ${args.join(' ')} exited ${r.status}`);
    process.exit(r.status || 1);
  }
}

function countMatches(src, re) {
  const m = src.match(re);
  return m ? m.length : 0;
}

function emittedJsGolden() {
  const src = fs.readFileSync(BUNDLE, 'utf8');
  const tagAlt = TAGS.join('|');

  // (a) direct literal-tag jsx calls survive :advanced. Closure may
  // flatten the module-property access (`X.jsx("div"`) into an aliased
  // var call (`Y("div"`), so match the argument shape, not the callee
  // spelling: an identifier call whose first argument is a literal
  // bench tag.
  const direct = countMatches(
    src,
    new RegExp(`[$\\w]+(?:\\.[$\\w]+)*\\("(?:${tagAlt})"\\s*,`, 'g'),
  );

  // (b) compiler-authored direct module-property calls. The `(0, property)`
  // spelling explicitly discards the receiver (jsx/jsxs do not use `this`)
  // and therefore cannot degrade to Closure's `f.call(module, ...)` alias.
  const compilerDirect = countMatches(
    src,
    new RegExp(
      `\\(0,[$\\w]+(?:\\.[$\\w]+)*\\)\\("(?:${tagAlt})"\\s*,`,
      'g',
    ),
  );

  // (c) the CLJS-var IFn fallback on a literal tag.
  const trap = countMatches(
    src,
    new RegExp(`\\.call\\(null,\\s*"(?:${tagAlt})"`, 'g'),
  );

  // The measured keyed row is the compile-known literal-props case. Pin the
  // exact advanced call site so a generic `js-obj` assignment IIFE cannot
  // return unnoticed while calls elsewhere continue to satisfy the counts.
  const todoLiteralProps = countMatches(
    src,
    /\(0,[$\w]+(?:\.[$\w]+)*\.jsxs\)\("li",\{className:"todo-item"/g,
  );
  const todoPropsIife = countMatches(
    src,
    /function\(\)\{return\{className:"todo-item"/g,
  );

  const MIN_DIRECT = 10;
  const MIN_COMPILER_DIRECT = 20;
  console.log(
    `emitted-JS golden: ${direct} direct literal-tag jsx calls, ` +
      `${compilerDirect} compiler-direct module-property calls, ` +
      `${trap} IFn-fallback call sites, ` +
      `${todoLiteralProps} direct todo literal / ${todoPropsIife} todo props IIFEs`,
  );
  if (direct < MIN_DIRECT) {
    console.error(
      `FAIL: expected >= ${MIN_DIRECT} direct literal-tag jsx calls in the ` +
        ':advanced bundle — the emitter (or the fixed-arity jsx wrappers) ' +
        'stopped compiling to direct calls (S-1 landmine shape).',
    );
    process.exit(1);
  }
  if (compilerDirect < MIN_COMPILER_DIRECT) {
    console.error(
      `FAIL: expected >= ${MIN_COMPILER_DIRECT} compiler-authored direct ` +
        'jsx-runtime module-property calls; wrapper bodies and hand JSX do ' +
        'not carry the `(0,module.jsx)("tag",...)` signature.',
    );
    process.exit(1);
  }
  if (trap !== 0) {
    console.error(
      'FAIL: IFn-protocol dispatch fallback found on the jsx path — a ' +
        'var-bound jsx fn reintroduced dynamic dispatch under :advanced.',
    );
    process.exit(1);
  }
  if (todoLiteralProps < 1 || todoPropsIife !== 0) {
    console.error(
      'FAIL: the compiled todo row is not passing its compile-known props as ' +
        'one ordered object literal directly to jsxs.',
    );
    process.exit(1);
  }

  const forbiddenDigestFragments = [
    '__RF2_UI_DIGEST_XX__',
    'bd1-',
    'digest_carrier',
  ];
  const retained = forbiddenDigestFragments.filter((fragment) =>
    src.includes(fragment),
  );
  if (retained.length !== 0) {
    console.error(
      'FAIL: dev-only whole-build digest machinery survived :advanced: ' +
        retained.join(', ') +
        '. The carrier/sentinel/bd1 path must cost zero production bytes.',
    );
    process.exit(1);
  }
  console.log('emitted-JS golden: PASS');
}

function main() {
  // repo spawn posture (rf2-wn4o1): resolve the tool's JS entry-point
  // and run it under THIS node binary — no bare npx/shell lookup
  let shadowRunner;
  try {
    shadowRunner = require.resolve('shadow-cljs/cli/runner.js', {
      paths: [IMPL],
    });
  } catch (_e) {
    console.error(
      'FAIL: shadow-cljs is not installed — run `npm ci` in implementation/ first.',
    );
    process.exit(1);
  }
  console.log('> shadow-cljs release ui-bench');
  run(process.execPath, [shadowRunner, 'release', 'ui-bench']);
  if (!fs.existsSync(BUNDLE)) {
    console.error(`FAIL: bench bundle missing at ${BUNDLE}`);
    process.exit(1);
  }
  emittedJsGolden();
  run(process.execPath, [BUNDLE], {
    env: { ...process.env, NODE_ENV: 'production' },
  });
}

main();
