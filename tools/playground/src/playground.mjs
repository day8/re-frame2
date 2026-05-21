/*
 * re-frame2 docs/cljs playground.
 *   - rf2-y99zt Phase 1; rf2-j06sy Phase 1b cutover (plain-CLJS cells).
 *   - rf2-bujlr Phase 2 (live reagent/re-frame component cells).
 *
 * A roll-your-own, instant-nav-safe live-CLJS-cell bootstrap — the production
 * renderer for the `docs/cljs` page. It is the production successor to the
 * Phase 0 spike (rf2-qk3sh) and replaced Klipse outright as of Phase 1b: the
 * page's ```cljs cells (`.language-cljs`) are now rendered here, and the
 * vendored ~7 MB Klipse plugin + its bootstrap have been deleted.
 *
 * Two cell kinds:
 *   - ```cljs        -> plain-eval cell. Evaluates the source and pr-str's the
 *                       last form's value (Phase 1). No reagent/re-frame loaded.
 *   - ```cljs-render -> render cell. Evaluates the source and MOUNTS the last
 *                       form's value as a reagent component into the result div
 *                       (Phase 2). Stock reagent + re-frame are available; the
 *                       cell may `require` reagent.core / reagent.dom /
 *                       re-frame.core and reg-event/reg-sub/dispatch/subscribe.
 *
 * Stack:
 *   - CodeMirror 6 (@codemirror/{state,view,commands}) — the editor.
 *   - @nextjournal/clojure-mode — Lezer-grammar CLJS mode: syntax,
 *     bracket-match/close, paredit (default_extensions) + complete_keymap.
 *   - Scittle (SCI in a <script> global) — the eval engine. Scittle is a
 *     classic <script>, NOT an ES module, so it is NOT imported here; this
 *     bootstrap injects its <script> tag and reads window.scittle at eval time.
 *   - Scittle reagent + re-frame plugins (Phase 2) — loaded ONLY on pages that
 *     have a ```cljs-render cell. They are classic <script> globals too, and
 *     they require React + ReactDOM globals to be present FIRST (the Scittle
 *     reagent plugin references window.React / window.ReactDOM), so the loader
 *     injects react@18 + react-dom@18 UMD builds ahead of them. Plain-CLJS
 *     pages never pay this cost.
 *
 * This module is bundled by esbuild into an IIFE at docs/cljs/playground.js
 * and wired via mkdocs `extra_javascript`. See tools/playground/README.md.
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
const SCITTLE_VERSION = "0.8.31";
const SCITTLE_BASE = `https://cdn.jsdelivr.net/npm/scittle@${SCITTLE_VERSION}/dist`;
const SCITTLE_SRC = `${SCITTLE_BASE}/scittle.js`;
const SCITTLE_REAGENT_SRC = `${SCITTLE_BASE}/scittle.reagent.js`;
const SCITTLE_REFRAME_SRC = `${SCITTLE_BASE}/scittle.re-frame.js`;

// The Scittle reagent plugin references window.React / window.ReactDOM (it is a
// shadow-cljs build of stock Reagent, which expects React on the global). Pin
// React 18 UMD — Scittle 0.8.31's Reagent targets React 18.
const REACT_SRC =
  "https://cdn.jsdelivr.net/npm/react@18/umd/react.production.min.js";
const REACT_DOM_SRC =
  "https://cdn.jsdelivr.net/npm/react-dom@18/umd/react-dom.production.min.js";

// The fence classes pymdownx.superfences emits (see mkdocs.yml custom_fences).
//   ```cljs        -> pre.language-cljs        -> plain-eval cell
//   ```cljs-render -> pre.language-cljs-render  -> reagent/re-frame render cell
const EVAL_SELECTOR = "pre.language-cljs:not([data-cljs-mounted])";
const RENDER_SELECTOR = "pre.language-cljs-render:not([data-cljs-mounted])";
const ANY_CELL_SELECTOR = `${EVAL_SELECTOR}, ${RENDER_SELECTOR}`;

// --- Eval wiring (validated in spike rf2-qk3sh) ----------------------------

// scittle.core.eval_string(src) returns the value of the LAST form (SCI returns
// the real CLJS value; numbers are JS numbers) and THROWS a JS Error on failure.
//
// To also capture *out* (println etc.) AND pr-str the result, we eval a small
// wrapper. Four gotchas are honoured here:
//   1. SCI has no JVM classes — java.io.StringWriter is absent. Capture *out*
//      with clojure.core/with-out-str.
//   2. with-out-str only captures explicit prints, NOT the body's return value.
//      Stash the return in an atom inside the body, pr-str it after.
//   3. A CLJS vector returned to JS is a PersistentVector OBJECT, not a JS
//      Array — index access returns garbage. Wrap the return in (clj->js ...)
//      so Scittle hands JS a real Array.
//   4. A top-level (def x ...)/(defn ...) returns the VAR, so a bare REPL would
//      print `#'user/x` — confusing for the non-Clojurian audience the docs/cljs
//      page targets, and a fidelity regression vs Klipse (which showed the bound
//      value). When the last form's value is a var, deref it and pr-str the
//      bound value instead, matching Klipse. Other values pass through unchanged.
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
    "\n)))" +
    "        v# (deref r#)" +
    "        v# (if (var? v#) (deref v#) v#)]" +
    "    [o# (pr-str v#)]))";
  const out = scittle.core.eval_string(wrapped); // -> JS array [printed, resultStr]
  return { printed: out[0] || "", result: out[1] };
}

// Render-cell eval (Phase 2). Mounts the last form's value as a reagent
// component into `targetEl` via reagent.dom/render.
//
// Unlike evalCljs, the source is NOT wrapped in (do ...): a render cell's
// source typically opens with `(require '[reagent.core :as r] ...)`, and SCI
// only propagates a require's aliases to *sibling top-level* forms — wrapping
// the body in a single (do ...) form makes `r/...`/`rf/...` unresolvable. So we
// eval the user's source at the top level (eval_string returns the LAST form's
// value), then render that value. The last form must be a reagent renderable —
// a hiccup vector (e.g. `[:div ...]`) or a component vector (e.g. `[counter]`).
function renderComponentCljs(src, targetEl) {
  const scittle = window.scittle;
  if (!scittle || !scittle.core || !scittle.core.eval_string) {
    throw new Error(
      "Scittle global not loaded (window.scittle.core.eval_string missing)"
    );
  }
  // reagent.dom is provided by the reagent plugin; require it defensively in
  // case the user's source did not.
  scittle.core.eval_string("(require '[reagent.dom])");
  // Eval the user's source top-level; the returned value is the last form.
  const component = scittle.core.eval_string(src);
  // Hand the component + target to reagent via a single eval so reagent owns
  // the render call. Stash both on window (the only JS<->SCI bridge available
  // to a classic-script global).
  window.__rf2RenderComp = component;
  window.__rf2RenderTarget = targetEl;
  scittle.core.eval_string(
    "(reagent.dom/render (.-__rf2RenderComp js/window)" +
      " (.-__rf2RenderTarget js/window))"
  );
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
//
// `render` selects the cell behaviour: render cells mount a reagent component
// into the result div; plain cells pr-str the value.
function evalKeymap(getResultEl, render) {
  return Prec.highest(
    keymap.of([
      {
        key: "Mod-Enter",
        preventDefault: true,
        run: (view) => {
          const src = view.state.doc.toString();
          const resultEl = getResultEl();
          try {
            if (render) {
              resultEl.classList.remove("cljs-result--err");
              renderComponentCljs(src, resultEl);
            } else {
              renderResult(resultEl, evalCljs(src));
            }
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
  const isRender = preEl.classList.contains("language-cljs-render");

  const wrap = document.createElement("div");
  wrap.className = "cljs-cell";
  if (isRender) wrap.classList.add("cljs-cell--render");
  const editorHost = document.createElement("div");
  editorHost.className = "cljs-editor";
  const resultEl = document.createElement("div");
  resultEl.className = isRender ? "cljs-result cljs-mount" : "cljs-result";
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
      evalKeymap(() => resultEl, isRender),
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

  // Render cells auto-mount on load so the live component is visible without
  // interaction (the demo is the point). Plain eval cells stay Mod-Enter-only
  // — they print a value, which the reader triggers deliberately. Editing +
  // Mod-Enter re-renders either kind.
  if (isRender) {
    try {
      renderComponentCljs(source, resultEl);
    } catch (e) {
      renderError(resultEl, e);
    }
  }
  return wrap;
}

function mountAll() {
  document.querySelectorAll(ANY_CELL_SELECTOR).forEach(mountCell);
}

// --- Scittle loader + instant-nav bootstrap --------------------------------
//
// Originally ported from the (now-deleted) Klipse bootstrap. Material's
// `navigation.instant` swaps page <main> via fetch and does NOT re-execute
// inline page <script>s, but it DOES re-run every `extra_javascript` module
// on each instant nav.
// So this module fires on initial load and every instant page swap. It is:
//   - Guarded: it does nothing on pages with no ```cljs cells (Scittle's ~183 KB
//     gz only loads on the page that actually has live cells).
//   - Idempotent: injects the Scittle <script> at most once per document;
//     on later instant navs it just re-scans the freshly swapped DOM.

// Resolve sibling-asset URLs relative to THIS file's own location, so the
// /re-frame2/ GitHub Pages sub-path and the domain root both work.
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
  return document.querySelector("pre.language-cljs, pre.language-cljs-render") !== null;
}

function hasRenderCells() {
  return document.querySelector("pre.language-cljs-render") !== null;
}

// Inject a classic <script> at most once per document (keyed by `id`) and call
// `onReady` when it has loaded (or immediately, if `already()` is already true).
// Handles the instant-nav re-entry case: an earlier nav may have injected the
// tag but it has not finished loading yet, so we attach another load listener.
function ensureScript(id, src, already, onReady) {
  if (already()) {
    onReady();
    return;
  }
  let script = document.getElementById(id);
  if (!script) {
    script = document.createElement("script");
    script.id = id;
    script.src = src;
    script.addEventListener("load", onReady);
    document.body.appendChild(script);
  } else {
    script.addEventListener("load", onReady);
  }
}

function scittleReady() {
  return !!(
    window.scittle &&
    window.scittle.core &&
    window.scittle.core.eval_string
  );
}

function ensureScittle(onReady) {
  ensureScript("cljs-scittle-js", SCITTLE_SRC, scittleReady, onReady);
}

// Load the reagent + re-frame plugins (Phase 2). They are classic <script>
// globals that EXTEND the already-loaded window.scittle SCI environment, and
// they require window.React / window.ReactDOM to exist first. Chain the loads:
// React -> ReactDOM -> scittle.reagent -> scittle.re-frame. Each is idempotent
// across instant navs (keyed by element id). The reagent plugin installs the
// `reagent.dom` namespace into Scittle; we treat its presence as "ready".
function reagentPluginReady() {
  // No clean JS-visible flag for "plugin installed"; the re-frame.js tag's
  // presence + completed load is our gate. Re-evaluate cheaply by id.
  const el = document.getElementById("cljs-scittle-reframe-js");
  return !!(el && el.dataset.loaded === "1");
}

function ensureReagentStack(onReady) {
  if (reagentPluginReady()) {
    onReady();
    return;
  }
  ensureScript(
    "cljs-react-js",
    REACT_SRC,
    () => typeof window.React !== "undefined",
    () =>
      ensureScript(
        "cljs-react-dom-js",
        REACT_DOM_SRC,
        () => typeof window.ReactDOM !== "undefined",
        () =>
          ensureScript(
            "cljs-scittle-reagent-js",
            SCITTLE_REAGENT_SRC,
            () => false, // no JS flag; load drives readiness
            () =>
              ensureScript(
                "cljs-scittle-reframe-js",
                SCITTLE_REFRAME_SRC,
                () => false,
                () => {
                  const el = document.getElementById("cljs-scittle-reframe-js");
                  if (el) el.dataset.loaded = "1";
                  onReady();
                }
              )
          )
      )
  );
}

function loadPlayground() {
  if (!hasCells()) return;
  ensureScittle(() => {
    if (hasRenderCells()) {
      ensureReagentStack(mountAll);
    } else {
      mountAll();
    }
  });
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
window.__rf2PlaygroundRenderCljs = renderComponentCljs;
