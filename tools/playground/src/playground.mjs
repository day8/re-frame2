/*
 * re-frame2 docs/cljs playground — Phase 1 MVP (rf2-y99zt).
 *
 * A roll-your-own, instant-nav-safe live-CLJS-cell bootstrap that replaces
 * Klipse for the `docs/cljs` page. It is the production successor to the
 * Phase 0 spike (rf2-qk3sh, docs/cljs/_spike-qk3sh/) — same validated wiring,
 * now wrapped in the same `extra_javascript` + `document$` instant-nav loader
 * the Klipse bootstrap uses (docs/klipse/klipse-bootstrap.js).
 *
 * Stack:
 *   - CodeMirror 6 (@codemirror/{state,view,commands}) — the editor.
 *   - @nextjournal/clojure-mode — Lezer-grammar CLJS mode: syntax,
 *     bracket-match/close, paredit (default_extensions) + complete_keymap.
 *   - Scittle (SCI in a <script> global) — the eval engine. Scittle is a
 *     classic <script>, NOT an ES module, so it is NOT imported here; this
 *     bootstrap injects its <script> tag and reads window.scittle at eval time.
 *
 * This module is bundled by esbuild into an IIFE at docs/cljs/playground.js
 * and wired via mkdocs `extra_javascript`. See tools/playground/README.md.
 *
 * Phase 1 deliberately uses a NEW fence class (`language-cljs`) so it coexists
 * with Klipse (`language-klipse`). The cutover (flip the fence, delete Klipse)
 * is Phase 1b (rf2-j06sy) — NOT this bead.
 */

import { EditorState, Prec } from "@codemirror/state";
import { EditorView, keymap, lineNumbers } from "@codemirror/view";
import { defaultKeymap, history, historyKeymap } from "@codemirror/commands";
import {
  default_extensions,
  complete_keymap,
} from "@nextjournal/clojure-mode";

// Pinned Scittle version (matches the validated spike rf2-qk3sh). Loaded as a
// classic <script> global from jsDelivr; installs window.scittle.core.eval_string.
const SCITTLE_SRC =
  "https://cdn.jsdelivr.net/npm/scittle@0.8.31/dist/scittle.js";

// The fence class pymdownx.superfences emits for ```cljs cells (see mkdocs.yml
// custom_fences). NEW class so this coexists with Klipse's .language-klipse.
const CELL_SELECTOR = "pre.language-cljs:not([data-cljs-mounted])";

// --- Eval wiring (validated in spike rf2-qk3sh) ----------------------------

// scittle.core.eval_string(src) returns the value of the LAST form (SCI returns
// the real CLJS value; numbers are JS numbers) and THROWS a JS Error on failure.
//
// To also capture *out* (println etc.) AND pr-str the result, we eval a small
// wrapper. Three gotchas the spike found are honoured here:
//   1. SCI has no JVM classes — java.io.StringWriter is absent. Capture *out*
//      with clojure.core/with-out-str.
//   2. with-out-str only captures explicit prints, NOT the body's return value.
//      Stash the return in an atom inside the body, pr-str it after.
//   3. A CLJS vector returned to JS is a PersistentVector OBJECT, not a JS
//      Array — index access returns garbage. Wrap the return in (clj->js ...)
//      so Scittle hands JS a real Array.
function evalCljs(src) {
  const scittle = window.scittle;
  if (!scittle || !scittle.core || !scittle.core.eval_string) {
    throw new Error(
      "Scittle global not loaded (window.scittle.core.eval_string missing)"
    );
  }
  const wrapped =
    "(clj->js" +
    "  (let [r# (atom nil)" +
    "        o# (with-out-str (reset! r# (do " +
    src +
    "\n)))]" +
    "    [o# (pr-str (deref r#))]))";
  const out = scittle.core.eval_string(wrapped); // -> JS array [printed, resultStr]
  return { printed: out[0] || "", result: out[1] };
}

function renderResult(targetEl, { printed, result }) {
  targetEl.classList.remove("cljs-result--err");
  targetEl.innerHTML = "";
  if (printed && printed.length) {
    const pre = document.createElement("div");
    pre.className = "cljs-out";
    pre.textContent = printed;
    targetEl.appendChild(pre);
  }
  const val = document.createElement("div");
  val.className = "cljs-val";
  val.textContent = "=> " + result;
  targetEl.appendChild(val);
}

function renderError(targetEl, err) {
  targetEl.classList.add("cljs-result--err");
  targetEl.innerHTML = "";
  const msg = (err && (err.message || err.toString())) || "unknown error";
  targetEl.textContent = "ERROR: " + msg;
}

// Mod-Enter eval keymap. MUST be wrapped in Prec.highest so it runs BEFORE any
// default / clojure-mode handler that might also bind Mod-Enter and swallow the
// event (the bug that made the spike cell render nothing without Prec.highest).
function evalKeymap(getResultEl) {
  return Prec.highest(
    keymap.of([
      {
        key: "Mod-Enter",
        preventDefault: true,
        run: (view) => {
          const src = view.state.doc.toString();
          const resultEl = getResultEl();
          try {
            renderResult(resultEl, evalCljs(src));
          } catch (e) {
            renderError(resultEl, e);
          }
          return true; // handled — prevents newline insertion
        },
      },
    ])
  );
}

// --- Cell mount ------------------------------------------------------------

function mountCell(preEl) {
  if (preEl.dataset.cljsMounted) return;
  const source = preEl.textContent.replace(/\n+$/, "");

  const wrap = document.createElement("div");
  wrap.className = "cljs-cell";
  const editorHost = document.createElement("div");
  editorHost.className = "cljs-editor";
  const resultEl = document.createElement("div");
  resultEl.className = "cljs-result";
  wrap.appendChild(editorHost);
  wrap.appendChild(resultEl);

  // Replace the static <pre> with the live cell, but mark BOTH so re-scans on
  // instant nav skip an already-mounted cell (the wrap is what survives).
  preEl.dataset.cljsMounted = "1";
  preEl.replaceWith(wrap);
  wrap.dataset.cljsMounted = "1";

  const state = EditorState.create({
    doc: source,
    extensions: [
      lineNumbers(),
      history(),
      ...default_extensions, // clojure-mode: lezer syntax, close/match brackets, paredit
      keymap.of([...complete_keymap, ...defaultKeymap, ...historyKeymap]),
      evalKeymap(() => resultEl),
      EditorView.theme({
        "&": {
          fontSize: "14px",
          border: "1px solid var(--md-default-fg-color--lightest, #ccc)",
          borderRadius: "4px",
        },
        ".cm-content": { fontFamily: "var(--md-code-font, monospace)" },
      }),
    ],
  });

  new EditorView({ state, parent: editorHost });
  return wrap;
}

function mountAll() {
  document.querySelectorAll(CELL_SELECTOR).forEach(mountCell);
}

// --- Scittle loader + instant-nav bootstrap --------------------------------
//
// Ported from docs/klipse/klipse-bootstrap.js. Material's `navigation.instant`
// swaps page <main> via fetch and does NOT re-execute inline page <script>s,
// but it DOES re-run every `extra_javascript` module on each instant nav.
// So this module fires on initial load and every instant page swap. It is:
//   - Guarded: it does nothing on pages with no ```cljs cells (Scittle's ~183 KB
//     gz only loads on the page that actually has live cells).
//   - Idempotent: injects the Scittle <script> at most once per document;
//     on later instant navs it just re-scans the freshly swapped DOM.

// Resolve sibling-asset URLs relative to THIS file's own location, so the
// /re-frame2/ GitHub Pages sub-path and the domain root both work. Identical
// trick to the Klipse bootstrap.
const selfUrl =
  (document.currentScript && document.currentScript.src) ||
  (function () {
    const scripts = document.getElementsByTagName("script");
    for (let i = scripts.length - 1; i >= 0; i--) {
      if (scripts[i].src && scripts[i].src.indexOf("playground.js") !== -1) {
        return scripts[i].src;
      }
    }
    return "";
  })();

function hasCells() {
  return document.querySelector("pre.language-cljs") !== null;
}

function ensureScittle(onReady) {
  if (window.scittle && window.scittle.core && window.scittle.core.eval_string) {
    onReady();
    return;
  }
  let script = document.getElementById("cljs-scittle-js");
  if (!script) {
    script = document.createElement("script");
    script.id = "cljs-scittle-js";
    script.src = SCITTLE_SRC;
    script.addEventListener("load", onReady);
    document.body.appendChild(script);
  } else {
    // Injected by an earlier nav but not yet ready — wait for it.
    script.addEventListener("load", onReady);
  }
}

function loadPlayground() {
  if (!hasCells()) return;
  ensureScittle(mountAll);
}

// Subscribe ONCE to Material's document$ (emits on initial load AND every
// instant nav). A global guard prevents stacking duplicate subscribers if
// Material re-executes this module. Fall back to DOMContentLoaded when
// instant-nav (document$) is absent.
if (window.document$ && typeof window.document$.subscribe === "function") {
  if (!window.__rf2PlaygroundSubscribed) {
    window.__rf2PlaygroundSubscribed = true;
    window.document$.subscribe(loadPlayground);
  }
} else if (document.readyState !== "loading") {
  loadPlayground();
} else {
  document.addEventListener("DOMContentLoaded", loadPlayground);
}

// Expose for the test harness.
window.__rf2PlaygroundMountAll = mountAll;
window.__rf2PlaygroundEvalCljs = evalCljs;
