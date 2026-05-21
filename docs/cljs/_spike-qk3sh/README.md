# Phase 0 spike — CM6 + clojure-mode + Scittle eval cell (rf2-qk3sh)

**Throwaway reference.** Phase 1 (rf2-y99zt, the real `tools/` shadow-cljs
artefact + instant-nav bootstrap) supersedes this. Kept only as a validated,
copy-pasteable wiring reference. Not built by CI, not loaded by mkdocs.

Proves: one CodeMirror 6 `EditorView` over a `<pre>`, using
`@nextjournal/clojure-mode`'s `default_extensions` + `complete_keymap` plus a
custom **Mod-Enter** eval keymap that calls Scittle's
`scittle.core.eval_string` and renders the result/printed-output/errors into a
sibling `<div>`.

## Run it

```bash
npm install          # CM6 + clojure-mode + esbuild + playwright
npm run browsers     # one-time: playwright install chromium
npm run build        # esbuild bundles cell.src.mjs -> cell.bundle.js (IIFE)
npm run smoke        # headless Chromium: drives 3 cells, asserts output
# or open index.html in a browser and Ctrl/Cmd-Enter a cell.
```

## Validated recipe

- **Scittle is a classic `<script>`, NOT an ES module.** Load it via
  `<script src=".../scittle.js">` (CDN: `cdn.jsdelivr.net/npm/scittle@0.8.31/dist/scittle.js`).
  It installs the `window.scittle` global; read `window.scittle.core.eval_string`
  at eval time. Do NOT `import` it.
- **CM6 + clojure-mode ARE ES modules** — bundle them with esbuild (`--format=iife`).
  `@nextjournal/clojure-mode` exports `default_extensions` (an array) and
  `complete_keymap` (an array of keybindings = paredit + builtin).
- **Eval keymap MUST be `Prec.highest(keymap.of([{key:"Mod-Enter", run, preventDefault:true}]))`.**
  Without `Prec.highest` the keypress can be swallowed before the handler runs.
- **`eval_string` returns the actual CLJS value** (numbers as JS numbers, etc.),
  and **throws a real JS `Error`** (with `.message`) on eval failure.
- **Capture `*out*` + result together** by evaluating a wrapper that returns a
  `(clj->js [...])` array (see `evalCljs` in `cell.src.mjs`).

## GOTCHAS for Phase 1

1. **No JVM classes in SCI.** `java.io.StringWriter` does NOT resolve. Use
   `(with-out-str ...)` to capture printed output, not a StringWriter.
2. **A CLJS vector returned to JS is a PersistentVector object, NOT a JS array.**
   `out[0]` / `out[1]` index access returns internal fields (garbage), not
   elements. Wrap the return value in `(clj->js ...)` to get a real JS `Array`
   (verified: `clj->js` and `into-array` both yield real arrays).
3. **`with-out-str` only captures explicit prints, not the body's return value.**
   Stash the return value in an `(atom)` inside the with-out-str body, then
   `pr-str` it after. The validated wrapper:
   ```clojure
   (clj->js
     (let [r# (atom nil)
           o# (with-out-str (reset! r# (do <USER-SRC>)))]
       [o# (pr-str (deref r#))]))
   ```
4. **Scittle logs eval errors to `console.error` AND throws.** The throw is what
   you catch+render; the console.error noise is expected, not a real page error.
   Don't treat Scittle's console diagnostics as test failures.
5. **`pr-str` of maps/sets renders cleanly** (e.g. `{:a 1, :b [1 2], :c #{:x :y}}`)
   — good enough for the docs cells. For larger structures Phase 1 may want
   `scittle.pprint.js` (ships in the Scittle dist) for pretty multi-line output.
6. **esbuild local-binary gotcha:** run the project-local `node_modules/.bin/esbuild`
   (or `npm run build`). A bare `npx esbuild` pulls a fresh global esbuild that
   can't resolve the locally-installed `@codemirror/*` packages.

## Resolved dep versions (this spike)

| dep | resolved |
|---|---|
| `@nextjournal/clojure-mode` | 0.3.3 |
| `@nextjournal/lezer-clojure` | 1.0.0 (transitive) |
| `@codemirror/state` | 6.6.0 |
| `@codemirror/view` | 6.43.0 |
| `@codemirror/commands` | 6.10.3 |
| `@codemirror/language` | 6.12.3 (transitive) |
| `@codemirror/autocomplete` | 6.20.2 (transitive) |
| `scittle` (CDN global) | 0.8.31 |
| esbuild | 0.28.0 |
| playwright | 1.60.0 |

Bundle size: CM6 + clojure-mode unminified IIFE ≈ **866 KB** (`cell.bundle.js`);
`--minify` shrinks it substantially. Scittle core ≈ 876 KB raw / 183 KB gzip
(loaded separately from CDN). Matches the findings doc estimates.
