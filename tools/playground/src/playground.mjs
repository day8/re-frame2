/*
 * re-frame2 docs/cljs playground.
 *   - rf2-y99zt Phase 1; rf2-j06sy Phase 1b cutover (plain-CLJS cells).
 *   - rf2-bujlr Phase 2 (live reagent/re-frame component cells).
 *   - rf2-00zvt Phase 3 (live re-frame2 component cells — re-frame2's OWN API).
 *
 * A roll-your-own, instant-nav-safe live-CLJS-cell bootstrap — the production
 * renderer for the `docs/cljs` page. It is the production successor to the
 * Phase 0 spike (rf2-qk3sh) and replaced Klipse outright as of Phase 1b: the
 * page's ```cljs cells (`.language-cljs`) are now rendered here, and the
 * vendored ~7 MB Klipse plugin + its bootstrap have been deleted.
 *
 * Three cell kinds:
 *   - ```cljs        -> plain-eval cell. Evaluates the source and pr-str's the
 *                       last form's value (Phase 1). No reagent/re-frame loaded.
 *   - ```cljs-render -> render cell. Evaluates the source and MOUNTS the last
 *                       form's value as a reagent component into the result div
 *                       (Phase 2). STOCK reagent + re-frame are available; the
 *                       cell may `require` reagent.core / reagent.dom /
 *                       re-frame.core and reg-event/reg-sub/dispatch/subscribe.
 *   - ```cljs-rf2    -> re-frame2 render cell. Same as cljs-render but evaluated
 *                       against re-frame2's OWN public API (re-frame.core v2)
 *                       rendered via reagent2 (Phase 3). The cell may `require`
 *                       re-frame.core / reagent2.core and call re-frame2's
 *                       reg-event-db / reg-sub / dispatch / subscribe. Backed by
 *                       a self-contained SCI bundle (cljs/playground-rf2.js,
 *                       built by tools/playground/sci) — NOT Scittle: there is
 *                       no published scittle.core artefact to build a plugin
 *                       against, and Scittle ships STOCK libs. The bundle
 *                       bundles re-frame2 core + reagent2 + React 19 (React 19
 *                       dropped its UMD build, so the global-React CDN trick
 *                       Phase 2 uses is unavailable) into one self-contained
 *                       file, loaded on demand only on pages with a cljs-rf2 cell.
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
import { HighlightStyle, syntaxHighlighting } from "@codemirror/language";
import { tags as t } from "@lezer/highlight";
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

// The re-frame2 SCI bundle (Phase 3) — a self-contained shadow-cljs build
// (tools/playground/sci) that bundles re-frame2 core + reagent2 + React 19 and
// installs window.rf2sci.{evalString,renderLast}. Sibling of this file under
// docs/cljs/, so it is resolved relative to this file's own URL (see selfUrl
// below) for /re-frame2/ sub-path safety. Loaded as a classic <script> on
// demand, only on pages that contain a ```cljs-rf2 cell.
const RF2_BUNDLE_NAME = "playground-rf2.js";

// The fence classes pymdownx.superfences emits (see mkdocs.yml custom_fences).
//   ```cljs        -> pre.language-cljs        -> plain-eval cell
//   ```cljs-render -> pre.language-cljs-render  -> stock reagent/re-frame render cell
//   ```cljs-rf2    -> pre.language-cljs-rf2     -> re-frame2 (v2) render cell
const EVAL_SELECTOR = "pre.language-cljs:not([data-cljs-mounted])";
const RENDER_SELECTOR = "pre.language-cljs-render:not([data-cljs-mounted])";
const RF2_SELECTOR = "pre.language-cljs-rf2:not([data-cljs-mounted])";
const ANY_CELL_SELECTOR = `${EVAL_SELECTOR}, ${RENDER_SELECTOR}, ${RF2_SELECTOR}`;

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

// re-frame2 render-cell eval (Phase 3). Evaluates the source against
// re-frame2's OWN public API via the self-contained SCI bundle
// (window.rf2sci, installed by playground-rf2.js) and mounts the last form's
// value as a reagent2 component into `targetEl`. window.rf2sci.renderLast owns
// the SCI eval + the reagent2 React-19 root, including re-render-in-place on
// re-eval, so the bootstrap just hands it the source + target.
function renderComponentRf2(src, targetEl) {
  const rf2 = window.rf2sci;
  if (!rf2 || !rf2.renderLast) {
    throw new Error(
      "re-frame2 SCI bundle not loaded (window.rf2sci.renderLast missing)"
    );
  }
  rf2.renderLast(src, targetEl);
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
// `kind` selects the cell behaviour:
//   "eval"   -> pr-str the last form's value (Phase 1)
//   "render" -> mount last form as a STOCK reagent component (Phase 2)
//   "rf2"    -> mount last form as a re-frame2/reagent2 component (Phase 3)
function runCell(kind, src, resultEl) {
  if (kind === "render") {
    resultEl.classList.remove("cljs-result--err");
    renderComponentCljs(src, resultEl);
  } else if (kind === "rf2") {
    resultEl.classList.remove("cljs-result--err");
    renderComponentRf2(src, resultEl);
  } else {
    renderResult(resultEl, evalCljs(src));
  }
}

function evalKeymap(getResultEl, kind) {
  return Prec.highest(
    keymap.of([
      {
        key: "Mod-Enter",
        preventDefault: true,
        run: (view) => {
          const src = view.state.doc.toString();
          const resultEl = getResultEl();
          try {
            runCell(kind, src, resultEl);
          } catch (e) {
            renderError(resultEl, e);
          }
          return true; // handled — prevents newline insertion
        },
      },
    ])
  );
}

// --- Syntax highlighting ---------------------------------------------------
//
// clojure-mode parses + Lezer-tags every token (its `style_tags` map assigns
// @lezer/highlight tags: Keyword/Boolean -> atom, NS/Operator/DefLike ->
// keyword, strings -> string, LineComment/Discard! -> comment, Number ->
// number, VarName -> definition(variableName), Nil -> null, RegExp -> regexp,
// DocString -> emphasis), but `default_extensions` ships NO HighlightStyle and
// no syntaxHighlighting() extension — so the tags were never painted and cells
// rendered as plain monospace (rf2-wj623).
//
// We map each tag the grammar emits to a CSS custom property rather than a
// literal colour, so a SINGLE HighlightStyle reads well under BOTH Material
// schemes: the per-scheme palette is supplied by `--rf2-cm-*` vars defined in
// the editor `EditorView.theme` block below, keyed off `[data-md-color-scheme]`
// (default = light, slate = dark). The fallbacks in each var() keep tokens
// legible even outside a Material page (e.g. the standalone smoke harness).
const highlightStyle = HighlightStyle.define([
  // Clojure :keywords + booleans (the grammar tags both as `atom`).
  { tag: t.atom, color: "var(--rf2-cm-atom)" },
  // Defining symbols (def/defn names): `(definition (variableName))`.
  { tag: t.definition(t.variableName), color: "var(--rf2-cm-def)" },
  { tag: t.variableName, color: "var(--rf2-cm-var)" },
  // ns / def-like heads / operator symbols are tagged `keyword`.
  { tag: t.keyword, color: "var(--rf2-cm-keyword)" },
  { tag: t.string, color: "var(--rf2-cm-string)" },
  { tag: t.number, color: "var(--rf2-cm-number)" },
  { tag: t.regexp, color: "var(--rf2-cm-regexp)" },
  { tag: t.null, color: "var(--rf2-cm-atom)" },
  { tag: [t.lineComment, t.comment], color: "var(--rf2-cm-comment)", fontStyle: "italic" },
  // DocStrings are tagged `emphasis`.
  { tag: t.emphasis, color: "var(--rf2-cm-string)", fontStyle: "italic" },
  // Bracket matching (paredit/match_brackets) — keep delimiters readable.
  { tag: t.bracket, color: "var(--rf2-cm-bracket)" },
]);

// Per-scheme palette + token-span colour protection. Two concerns:
//   1. Supply the `--rf2-cm-*` vars per Material scheme. The selectors below
//      target an ancestor `[data-md-color-scheme]` of the editor so the var
//      cascade resolves to the ACTIVE scheme (Material swaps that attribute on
//      <html> when the reader toggles light/dark).
//   2. Material's typeset CSS styles `code`/`pre` spans; CM6 token spans live
//      inside `.cm-content` and could inherit. Pin token spans to `inherit`
//      bg + the var colour so the highlight wins (extra specificity via the
//      `.cm-content` scope), without touching playground.css (sibling owns it).
const editorTheme = EditorView.theme({
  "&": {
    fontSize: "14px",
    border: "1px solid var(--md-default-fg-color--lightest, #ccc)",
    borderRadius: "4px",
    // Light/default scheme palette (also the no-Material fallback).
    "--rf2-cm-keyword": "#7c3aed",
    "--rf2-cm-atom": "#0b7285",
    "--rf2-cm-def": "#1864ab",
    "--rf2-cm-var": "var(--md-typeset-color, #24292e)",
    "--rf2-cm-string": "#2b8a3e",
    "--rf2-cm-number": "#e8590c",
    "--rf2-cm-regexp": "#c2255c",
    "--rf2-cm-comment": "#868e96",
    "--rf2-cm-bracket": "var(--md-default-fg-color--light, #5c6370)",
  },
  ".cm-content": { fontFamily: "var(--md-code-font, monospace)" },
  // Keep CM token spans from inheriting Material typeset colours.
  ".cm-content span": { backgroundColor: "transparent" },
});

// Slate (dark) palette. EditorView.theme scopes rules under the editor's
// generated class, so a bare `[data-md-color-scheme="slate"]` selector would
// not match an ANCESTOR. Define the dark palette as a plain document-level
// stylesheet, injected once, that re-points the vars on any `.cm-editor`
// living inside a slate-scheme subtree.
let darkPaletteInjected = false;
function ensureDarkPalette() {
  if (darkPaletteInjected) return;
  darkPaletteInjected = true;
  const style = document.createElement("style");
  style.id = "rf2-cm-dark-palette";
  style.textContent =
    '[data-md-color-scheme="slate"] .cm-editor {' +
    "  --rf2-cm-keyword: #c792ea;" +
    "  --rf2-cm-atom: #56d4dd;" +
    "  --rf2-cm-def: #82aaff;" +
    "  --rf2-cm-var: var(--md-typeset-color, #d6deeb);" +
    "  --rf2-cm-string: #addb67;" +
    "  --rf2-cm-number: #f78c6c;" +
    "  --rf2-cm-regexp: #f78c6c;" +
    "  --rf2-cm-comment: #7f95a3;" +
    "  --rf2-cm-bracket: var(--md-default-fg-color--light, #93a1ad);" +
    "}";
  document.head.appendChild(style);
}

// --- Cell mount ------------------------------------------------------------

function cellKind(preEl) {
  if (preEl.classList.contains("language-cljs-rf2")) return "rf2";
  if (preEl.classList.contains("language-cljs-render")) return "render";
  return "eval";
}

function mountCell(preEl) {
  if (preEl.dataset.cljsMounted) return;
  const source = preEl.textContent.replace(/\n+$/, "");
  const kind = cellKind(preEl);
  const isMount = kind === "render" || kind === "rf2";

  const wrap = document.createElement("div");
  wrap.className = "cljs-cell";
  if (kind === "render") wrap.classList.add("cljs-cell--render");
  if (kind === "rf2") wrap.classList.add("cljs-cell--render", "cljs-cell--rf2");
  const editorHost = document.createElement("div");
  editorHost.className = "cljs-editor";
  const resultEl = document.createElement("div");
  resultEl.className = isMount ? "cljs-result cljs-mount" : "cljs-result";
  wrap.appendChild(editorHost);
  wrap.appendChild(resultEl);

  // Replace the static <pre> with the live cell, but mark BOTH so re-scans on
  // instant nav skip an already-mounted cell (the wrap is what survives).
  preEl.dataset.cljsMounted = "1";
  preEl.replaceWith(wrap);
  wrap.dataset.cljsMounted = "1";

  // Inject the dark-scheme palette stylesheet once (no-op if already present).
  ensureDarkPalette();

  const state = EditorState.create({
    doc: source,
    extensions: [
      lineNumbers(),
      history(),
      ...default_extensions, // clojure-mode: lezer syntax, close/match brackets, paredit
      // Paint the Lezer tags clojure-mode emits. default_extensions parses +
      // tags but ships NO HighlightStyle — without this the cells render as
      // plain monospace (rf2-wj623).
      syntaxHighlighting(highlightStyle),
      keymap.of([...complete_keymap, ...defaultKeymap, ...historyKeymap]),
      evalKeymap(() => resultEl, kind),
      editorTheme,
    ],
  });

  new EditorView({ state, parent: editorHost });

  // Render cells (stock + re-frame2) auto-mount on load so the live component
  // is visible without interaction (the demo is the point). Plain eval cells
  // stay Mod-Enter-only — they print a value, which the reader triggers
  // deliberately. Editing + Mod-Enter re-renders any kind.
  if (isMount) {
    try {
      runCell(kind, source, resultEl);
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
  return (
    document.querySelector(
      "pre.language-cljs, pre.language-cljs-render, pre.language-cljs-rf2"
    ) !== null
  );
}

function hasRenderCells() {
  return document.querySelector("pre.language-cljs-render") !== null;
}

function hasRf2Cells() {
  return document.querySelector("pre.language-cljs-rf2") !== null;
}

// Resolve a sibling-asset URL (same dir as this file) for /re-frame2/
// sub-path safety. selfUrl is this module's own src (set below).
function siblingUrl(name) {
  try {
    return new URL(name, selfUrl).href;
  } catch (_e) {
    return name;
  }
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

// Load the self-contained re-frame2 SCI bundle (Phase 3). It installs
// window.rf2sci.{evalString,renderLast} and bundles its own React 19 — no
// external React, no Scittle. Idempotent across instant navs (keyed by id);
// "ready" = window.rf2sci present.
function rf2Ready() {
  return !!(window.rf2sci && window.rf2sci.renderLast);
}

function ensureRf2(onReady) {
  ensureScript("cljs-rf2-js", siblingUrl(RF2_BUNDLE_NAME), rf2Ready, onReady);
}

function loadPlayground() {
  if (!hasCells()) return;
  // The re-frame2 bundle is independent of Scittle (it carries its own SCI +
  // React). Plain ```cljs and stock ```cljs-render cells still need Scittle.
  const needsScittle =
    document.querySelector("pre.language-cljs, pre.language-cljs-render") !==
    null;
  const finishScittlePath = () => {
    if (hasRf2Cells()) ensureRf2(mountAll);
    else mountAll();
  };
  if (needsScittle) {
    ensureScittle(() => {
      if (hasRenderCells()) ensureReagentStack(finishScittlePath);
      else finishScittlePath();
    });
  } else if (hasRf2Cells()) {
    // re-frame2-only page: skip Scittle + the reagent CDN stack entirely.
    ensureRf2(mountAll);
  }
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
window.__rf2PlaygroundRenderRf2 = renderComponentRf2;
