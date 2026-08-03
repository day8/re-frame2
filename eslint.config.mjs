// Repo-wide JS lint config (rf2-2rtt6.78).
//
// WHAT THIS IS FOR, AND WHAT IT DELIBERATELY IS NOT.
//
// Before this file the repo had ~205 tracked `.cjs` / `.mjs` / `.js` files —
// bench drivers, gate runners, browser harnesses, MCP conformance suites — and
// NOTHING checked any of them. A syntax error or a typo'd identifier was
// discovered the first time somebody ran the file, which for a nightly bench
// driver can be days. clj-kondo and Splint cover the Clojure trees; this closes
// the JS hole with the same shape: a BUG-CLASS linter, not a style linter.
//
// The rule set below is hand-picked and deliberately small. It is NOT
// `js.configs.recommended`: measured over this corpus, `recommended` reports
// 148 findings across 60 files, and the great majority are hygiene opinions
// (`no-empty`, `preserve-caught-error`, `no-useless-escape`,
// `no-useless-assignment`) that would have to be silenced with per-file
// disable comments to land green. A gate that ships with a suppression file is
// worse than no gate, because people stop reading it. The curated set below
// reported 24 findings across 17 files — every one of them dead code — all of
// which were deleted in the landing PR.
//
// SUPPRESSIONS IN THE TREE: exactly one, and it is load-bearing.
// `tools/story/test/story_feature_load.cjs` wraps a skipped scenario's body in
// `/* eslint-disable no-unreachable */` … `/* eslint-enable no-unreachable */`.
// That probe returns early behind a `console.warn` while its assertions wait
// for a CLJS port; delete the pair and `no-unreachable` errors on them, so it
// goes only when the code it guards goes. This header used to claim ZERO
// suppressions anywhere in the tree, which was wrong the day it was written:
// the landing PR deleted two dead directives from that very file and kept this
// live one. Keep the count here honest — a suppression that no comment admits
// to is how a gate quietly stops meaning what it says (rf2-o4ohg).
//
// EVERY RULE HERE ANSWERS "WHAT BUG DOES THIS CATCH?":
//
//   parse errors           a file that does not parse (free; no rule needed)
//   no-undef               an undefined variable — the misspelled identifier
//   no-unused-vars         a dead or typo'd `require`, an orphaned binding
//   no-unreachable         code after `return` / `throw` that never runs
//   no-const-assign        assignment to a `const` (a runtime TypeError)
//   no-class-assign        assignment to a class binding
//   no-func-assign         assignment over a function declaration
//   no-dupe-keys           a duplicated object key silently drops the first
//   no-dupe-args           a duplicated parameter name
//   no-dupe-else-if        a copy-pasted `else if` condition that can't fire
//   no-dupe-class-members  a duplicated method silently drops the first
//   no-duplicate-case      a duplicated `case` that can never be reached
//   no-obj-calls           calling a non-callable global (`Math()`)
//   no-sparse-arrays       `[a, , b]` — nearly always a stray comma
//   no-unsafe-negation     `!k in o` / `!o instanceof C` — precedence bug
//   no-unsafe-finally      a `return`/`throw` in `finally` swallowing an error
//   no-self-assign         `x = x`
//   no-self-compare        `x === x`
//   no-compare-neg-zero    `x === -0`, which is never what was meant
//   no-constant-binary-expression  `x === undefined || null` and friends
//   use-isnan              `x === NaN`, which is always false
//   valid-typeof           `typeof x === 'strng'` — a pure typo catcher
//   no-cond-assign         `if (x = 1)` where `==` was meant
//   no-invalid-regexp      a `new RegExp` literal that cannot compile
//   no-empty-character-class  `/[]/`, which matches nothing
//   no-ex-assign           clobbering the caught error binding
//   no-global-assign       assigning over a read-only global
//   no-shadow-restricted-names  shadowing `undefined` / `NaN` / `Infinity`
//   no-this-before-super   `this` before `super()` — a runtime ReferenceError
//   no-setter-return       a `return` with a value from a setter
//   no-unsafe-optional-chaining  `(a?.b)()` throwing on undefined
//   no-async-promise-executor    `new Promise(async …)` swallowing rejections
//   no-useless-backreference     a backreference that can never match
//   no-import-assign       assignment to an ES-module import binding
//
// EXPLICITLY OUT OF SCOPE, and must stay that way: quoting, semicolons,
// spacing, import ordering, `no-console`, `no-empty`, any formatter, and any
// type-aware configuration. If a rule's failure mode is "this reads oddly"
// rather than "this is a bug", it does not belong here.
//
// WHAT THIS GATE DOES NOT CATCH — stated because a linter that is assumed to
// cover more than it does is worse than one whose edges are known. Both limits
// below were confirmed by mutating a real driver and watching the gate stay
// green:
//
//   - A MISSPELLED PROPERTY OR METHOD on an object that does exist.
//     `console.errr(…)` passes. `no-undef` resolves free identifiers, and
//     `console` resolves fine; knowing that `.errr` is not a member of it
//     needs type information, which is deliberately out of scope. A
//     misspelled *variable* or *function* reference — `lenght(xs)`, or `pth`
//     where `path` was bound — IS caught, and that is the common shape.
//
//   - A `require()` PATH THAT DOES NOT RESOLVE, when the binding it produces
//     is used. `require('node:pathh')` passes if something reads the result.
//     Core ESLint has no module-resolution rule; catching this needs a plugin
//     (`eslint-plugin-n`'s `n/no-missing-require`, or `import/no-unresolved`
//     with a resolver), which is a second toolchain for a defect Node itself
//     reports on the first line of the first run. Note the useful partial: a
//     bad `require` whose binding is NEVER read is caught, by `no-unused-vars`
//     — which is the shape a dead import actually takes in practice.
//
// Warnings never fail the run — the same shape as the clj-kondo
// (`--fail-level error`) and Splint (`--fail-on-errors`) jobs alongside it in
// `.github/workflows/lint.yml`. Everything above is an error, so in practice
// the only warnings are ESLint's own unused-disable-directive reports, which
// are informational triage rather than a merge blocker.

import globals from 'globals';

// The corpus is Node scripts that frequently also carry `page.evaluate`
// bodies destined for a browser, so both global sets are in scope
// everywhere. Splitting them per-directory would buy a sharper `no-undef`
// at the cost of a path map to maintain; the sharpness is not worth it.
const runtimeGlobals = { ...globals.node, ...globals.browser };

// Bug-class rules only — see the header for what each one catches.
const bugRules = {
  'no-undef': 'error',
  // `args: 'none'` — an unused parameter is usually positional necessity in a
  // callback signature, not a defect. `caughtErrors: 'none'` — an unused
  // `catch (err)` binding is idiomatic. Unused *variables* stay on: that is
  // the arm that catches a dead `require`.
  'no-unused-vars': ['error', { args: 'none', caughtErrors: 'none' }],
  'no-unreachable': 'error',
  'no-const-assign': 'error',
  'no-class-assign': 'error',
  'no-func-assign': 'error',
  'no-dupe-keys': 'error',
  'no-dupe-args': 'error',
  'no-dupe-else-if': 'error',
  'no-dupe-class-members': 'error',
  'no-duplicate-case': 'error',
  'no-obj-calls': 'error',
  'no-sparse-arrays': 'error',
  'no-unsafe-negation': 'error',
  'no-unsafe-finally': 'error',
  'no-self-assign': 'error',
  'no-self-compare': 'error',
  'no-compare-neg-zero': 'error',
  'no-constant-binary-expression': 'error',
  'use-isnan': 'error',
  'valid-typeof': 'error',
  'no-cond-assign': 'error',
  'no-invalid-regexp': 'error',
  'no-empty-character-class': 'error',
  'no-ex-assign': 'error',
  'no-global-assign': 'error',
  'no-shadow-restricted-names': 'error',
  'no-this-before-super': 'error',
  'no-setter-return': 'error',
  'no-unsafe-optional-chaining': 'error',
  'no-async-promise-executor': 'error',
  'no-useless-backreference': 'error',
  'no-import-assign': 'error',
};

export default [
  {
    // Generated / vendored trees. NOTHING GENERATED IS LINTED: a machine
    // wrote it, no human will fix it, and a finding in it is never a defect
    // report.
    //
    // Flat config ignores exactly two paths of its own accord — `**/node_modules/`
    // and `.git/` (`eslint/lib/config/default-config.js`). Dot-directories in
    // general are NOT among them; that was the old `.eslintrc` behaviour, and
    // assuming it carried over is what left `.shadow-cljs/` linted (rf2-d4bqy).
    // After any local `:advanced` build, `implementation/.shadow-cljs/builds/
    // */release/externs.shadow.js` — a Closure externs file, all declarations,
    // none of them used — reported several hundred `no-unused-vars` /
    // `no-shadow-restricted-names` errors and failed the gate. CI never saw it,
    // because a fresh checkout has no build output. So the gate was red locally
    // and green in CI, which is the way round that teaches a developer to
    // disbelieve their own lint run.
    //
    // The list below is this repo's build outputs as its own fourteen
    // `.gitignore` files declare them, narrowed to the trees that can carry
    // `.js` / `.cjs` / `.mjs`. `.cpcache/`, `__pycache__/`, `.dolt/`,
    // `.scratch/` and the `docs/{spec,migration}/` staging copies are
    // generated too and are deliberately absent: they hold classpath caches,
    // bytecode, database pages, EDN/CLJS fixtures and markdown respectively. A
    // fully built checkout here carries 34 `.cpcache/` directories with not one
    // JS file among them. Add a tree here when it is generated AND can carry
    // JS — not when a file in it happens to have complained.
    ignores: [
      '**/node_modules/**',
      '**/out/**',
      '**/target/**',
      '**/classes/**',
      // shadow-cljs compile output: `release/externs.shadow.js` plus the
      // `{dev,release}/{goog-js,shadow-js}/` trees. 8811 JS files in a fully
      // built checkout of this repo, against 215 tracked JS files in the
      // whole tree.
      '**/.shadow-cljs/**',
      // shadow-cljs `:output-dir`s. Empty in a fresh checkout, which is the
      // only reason neither surfaced as a symptom alongside the externs file;
      // both are declared generated by the `.gitignore` sitting beside them
      // (`examples/ui/minimal-counter/`, the `tools/template/` scaffold, and
      // `skills/re-frame2-pair/`).
      '**/resources/public/js/**',
      '**/public/js/compiled/**',
      // Local-only trees whose contents this repo does not author. An agent
      // worktree under `.claude/worktrees/` is a whole second copy of the
      // source tree — it would lint every file twice and red this gate on
      // somebody else's work in progress. `examples/.edge-cdp/` is a Chrome
      // user-data-dir from the examples' CDP runs, and what a browser writes
      // into its own profile is Chrome's business, not a lint surface.
      '.claude/worktrees/**',
      'examples/.edge-cdp/**',
      // `docs/cljs/` holds the minified ClojureScript playground bundle — a
      // single 400 KB line of machine output that no human edits.
      'site/**',
      'ai/**',
      'docs/cljs/**',
    ],
  },
  {
    // `.cjs` is CommonJS by extension. So, as it happens, is every bare `.js`
    // file left in the tree: the four MCP stdio/live drivers under
    // `tools/*-mcp/test/` all open with `require(…)` and sit under a
    // `package.json` with no `"type"` field. Should an ESM `.js` ever land
    // (the one package declaring `"type": "module"` is
    // `docs/tools/playground/`, which today ships only `.mjs`), it wants its
    // own block rather than a change here.
    files: ['**/*.cjs', '**/*.js'],
    languageOptions: {
      ecmaVersion: 'latest',
      sourceType: 'commonjs',
      globals: runtimeGlobals,
    },
    rules: bugRules,
  },
  {
    // `.mjs` is ESM by extension, regardless of any enclosing package.json.
    files: ['**/*.mjs'],
    languageOptions: {
      ecmaVersion: 'latest',
      sourceType: 'module',
      globals: runtimeGlobals,
    },
    rules: bugRules,
  },
];
