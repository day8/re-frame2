/*
 * Live ClojureScript cells for the docs site.
 *
 * Turns fenced code blocks into CodeMirror 6 editors whose code runs in the
 * browser. Two cell kinds, keyed off the class pymdownx.superfences emits:
 *
 *   ```cljs      pre.language-cljs      Evaluates the source on Scittle and
 *                                       prints the last form's value. Runs
 *                                       on Mod-Enter.
 *   ```cljs-rf2  pre.language-cljs-rf2  Evaluates the source against
 *                                       re-frame2's public API and mounts the
 *                                       last form as a component, through
 *                                       reagent2 or Fresco. Runs on load and
 *                                       on Mod-Enter.
 *
 * Each engine is a classic <script> injected only on pages that have its
 * cell kind: Scittle from jsDelivr, and cljs/playground-rf2.js (built from
 * ../sci) for re-frame2 cells.
 *
 * esbuild bundles this module into the IIFE docs/cljs/playground.js, which
 * mkdocs loads through `extra_javascript`. See ../README.md.
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

const SCITTLE_VERSION = "0.8.31";
const SCITTLE_SRC = `https://cdn.jsdelivr.net/npm/scittle@${SCITTLE_VERSION}/dist/scittle.js`;

// This bundle's deployed file name, used to find our own <script> when
// document.currentScript is unavailable.
const SELF_BUNDLE_NAME = "playground.js";

// The re-frame2 engine, a sibling of this file under docs/cljs/. It installs
// window.rf2sci.
const RF2_BUNDLE_NAME = "playground-rf2.js";

const EVAL_CELL = "pre.language-cljs";
const RF2_CELL = "pre.language-cljs-rf2";
const UNMOUNTED_CELLS = `${EVAL_CELL}:not([data-cljs-mounted]), ${RF2_CELL}:not([data-cljs-mounted])`;

// --- Eval ------------------------------------------------------------------

// Evaluates a plain cell on Scittle and returns what it printed plus the
// pr-str of its last value. The wrapper:
//   - captures *out* with with-out-str (SCI has no java.io.StringWriter);
//   - stashes the body's value in an atom, because with-out-str returns only
//     the printed text;
//   - derefs a var, so `(def x 1)` shows `1` rather than `#'user/x`;
//   - returns through clj->js, because a CLJS vector is not a JS Array.
// Wrapping the body in one `(do ...)` means a plain cell cannot `require`:
// SCI applies a require's aliases only to sibling top-level forms.
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
  const out = scittle.core.eval_string(wrapped);
  return { printed: out[0] || "", result: out[1] };
}

// Evaluates a re-frame2 cell and mounts its last form into `targetEl`.
// rf2sci.renderLast owns the SCI eval and the React root, and re-renders in
// place on a second call for the same element.
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

function runCell(kind, src, resultEl) {
  try {
    if (kind === "rf2") {
      resultEl.classList.remove("cljs-result--err");
      renderComponentRf2(src, resultEl);
    } else {
      renderResult(resultEl, evalCljs(src));
    }
  } catch (e) {
    // Unmount the cell's React root first, so the error text never replaces
    // DOM that React still owns.
    if (kind === "rf2" && window.rf2sci && window.rf2sci.release) {
      window.rf2sci.release(resultEl);
    }
    renderError(resultEl, e);
  }
}

// Prec.highest, because clojure-mode's keymap also binds Mod-Enter and would
// otherwise handle the key first.
function evalKeymap(resultEl, kind) {
  return Prec.highest(
    keymap.of([
      {
        key: "Mod-Enter",
        preventDefault: true,
        run: (view) => {
          runCell(kind, view.state.doc.toString(), resultEl);
          return true;
        },
      },
    ])
  );
}

// --- Syntax highlighting ---------------------------------------------------
//
// clojure-mode tags tokens but ships no HighlightStyle, so without this the
// cells render as plain monospace. Each tag maps to a `--rf2-cm-*` variable;
// playground.css defines the light and slate palettes.
const highlightStyle = HighlightStyle.define([
  // Keywords and booleans are both tagged `atom`.
  { tag: t.atom, color: "var(--rf2-cm-atom)" },
  { tag: t.definition(t.variableName), color: "var(--rf2-cm-def)" },
  { tag: t.variableName, color: "var(--rf2-cm-var)" },
  // ns, def-like heads and operator symbols are tagged `keyword`.
  { tag: t.keyword, color: "var(--rf2-cm-keyword)" },
  { tag: t.string, color: "var(--rf2-cm-string)" },
  { tag: t.number, color: "var(--rf2-cm-number)" },
  { tag: t.regexp, color: "var(--rf2-cm-regexp)" },
  { tag: t.null, color: "var(--rf2-cm-atom)" },
  { tag: [t.lineComment, t.comment], color: "var(--rf2-cm-comment)", fontStyle: "italic" },
  // Docstrings are tagged `emphasis`.
  { tag: t.emphasis, color: "var(--rf2-cm-string)", fontStyle: "italic" },
  { tag: t.bracket, color: "var(--rf2-cm-bracket)" },
]);

const editorTheme = EditorView.theme({
  "&": {
    fontSize: "14px",
    border: "1px solid var(--md-default-fg-color--lightest, #ccc)",
    borderRadius: "4px",
  },
  ".cm-content": { fontFamily: "var(--md-code-font, monospace)" },
  // Material styles code spans; keep its backgrounds off the token spans.
  ".cm-content span": { backgroundColor: "transparent" },
});

// --- Links inside a cell ----------------------------------------------------
//
// A link in a cell's result area whose href is a fragment (`href="#"`), or
// which has no href, belongs to the cell's app, so the docs page must not act
// on it. Material's instant navigation listens for clicks on document.body and
// ignores preventDefault. On a freshly loaded page it re-fetches the page for
// a fragment link, which re-runs every cell, and an anchor with no href throws
// inside it and turns instant navigation off. The browser's own fragment
// navigation fires popstate, which instant navigation treats the same way. So
// such a click stops at the cell, after the cell's own handlers have run, and
// does not navigate. A link to another page still navigates instantly.
function keepFragmentLinksInCell(wrap, resultEl) {
  wrap.addEventListener("click", (ev) => {
    const a = ev.target instanceof Element ? ev.target.closest("a") : null;
    if (!a || !resultEl.contains(a)) return;
    const href = a.getAttribute("href");
    if (href === null || href.startsWith("#")) {
      ev.preventDefault();
      ev.stopPropagation();
    }
  });
}

// --- Cell mount ------------------------------------------------------------

const cellSource = (preEl) => preEl.textContent.replace(/\n+$/, "");
const cellKind = (preEl) => (preEl.matches(RF2_CELL) ? "rf2" : "eval");

// The page whose cells are mounted, and its cells in document order.
let mounted = { pathname: null, cells: [] };

function mountCell(preEl) {
  if (preEl.dataset.cljsMounted) return;
  const source = cellSource(preEl);
  const kind = cellKind(preEl);

  const wrap = document.createElement("div");
  wrap.className = "cljs-cell";
  if (kind === "rf2") wrap.classList.add("cljs-cell--render", "cljs-cell--rf2");
  const editorHost = document.createElement("div");
  editorHost.className = "cljs-editor";
  const resultEl = document.createElement("div");
  resultEl.className = kind === "rf2" ? "cljs-result cljs-mount" : "cljs-result";
  wrap.appendChild(editorHost);
  wrap.appendChild(resultEl);
  keepFragmentLinksInCell(wrap, resultEl);

  // Mark both elements so a re-scan after instant navigation skips this cell.
  preEl.dataset.cljsMounted = "1";
  preEl.replaceWith(wrap);
  wrap.dataset.cljsMounted = "1";

  const state = EditorState.create({
    doc: source,
    extensions: [
      lineNumbers(),
      history(),
      ...default_extensions,
      syntaxHighlighting(highlightStyle),
      keymap.of([...complete_keymap, ...defaultKeymap, ...historyKeymap]),
      evalKeymap(resultEl, kind),
      editorTheme,
    ],
  });

  new EditorView({ state, parent: editorHost });

  // A re-frame2 cell is a demo, so it renders straight away. A plain cell
  // waits for the reader to press Mod-Enter.
  if (kind === "rf2") runCell(kind, source, resultEl);
  mounted.cells.push({ wrap, source, kind });
  return wrap;
}

function mountAll() {
  document.querySelectorAll(UNMOUNTED_CELLS).forEach(mountCell);
}

// --- Same-page re-fetch ------------------------------------------------------
//
// Material's instant navigation starts with no current location, so the first
// navigation it sees after a full page load counts as a new page even when
// only the fragment changes. A table-of-contents entry, a heading permalink, a
// `[text](#anchor)`, or Back and Forward between fragments re-fetches the page
// and injects it again. Re-mounting would re-run every cell and lose the
// reader's edits. When the injected page is the one already mounted, its cells
// are the same cells, so the mounted ones move into it in place of the fresh
// <pre>s, and edits, output and app state all stay. Material scrolled before
// they moved back, against the shorter page, so the scroll is redone by
// Material's own rule: a saved offset, else the fragment's target.
//
// Stopping the re-fetch instead is not open to the bootstrap: Material hears
// popstate on window ahead of any listener this script adds, and its scroll to
// the first fragment clicked comes from the re-fetch itself.
function carryCellsOver() {
  const { pathname, cells } = mounted;
  if (pathname !== location.pathname || cells.length === 0) return false;
  if (cells.some(({ wrap }) => wrap.isConnected)) return false;
  const pres = [...document.querySelectorAll(UNMOUNTED_CELLS)];
  if (pres.length !== cells.length) return false;
  const same = pres.every((pre, i) => cellSource(pre) === cells[i].source && cellKind(pre) === cells[i].kind);
  if (!same) return false;

  pres.forEach((pre, i) => pre.replaceWith(cells[i].wrap));
  // `window.history`: a bare `history` here is CodeMirror's history extension.
  const state = window.history.state;
  if (state !== null || !location.hash) {
    window.scrollTo(0, (state && state.y) || 0);
  } else {
    const target = document.getElementById(decodeURIComponent(location.hash.slice(1)));
    if (target) target.scrollIntoView();
  }
  return true;
}

// --- Engine loading and instant navigation ----------------------------------

// Resolve siblings against this file's own URL so the site works both at the
// domain root and under the /re-frame2/ sub-path.
const selfUrl =
  (document.currentScript && document.currentScript.src) ||
  (function () {
    const scripts = document.getElementsByTagName("script");
    for (let i = scripts.length - 1; i >= 0; i--) {
      if (scripts[i].src && scripts[i].src.indexOf(SELF_BUNDLE_NAME) !== -1) {
        return scripts[i].src;
      }
    }
    return "";
  })();

function siblingUrl(name) {
  try {
    return new URL(name, selfUrl).href;
  } catch (_e) {
    return name;
  }
}

// Injects a classic <script> at most once per document (keyed by `id`) and
// resolves once `ready()` holds. A tag injected by an earlier navigation may
// still be loading, so an existing tag gets another listener.
function loadScript(id, src, ready) {
  if (ready()) return Promise.resolve();
  let script = document.getElementById(id);
  if (!script) {
    script = document.createElement("script");
    script.id = id;
    script.src = src;
    document.body.appendChild(script);
  }
  return new Promise((resolve, reject) => {
    script.addEventListener("load", resolve);
    script.addEventListener("error", () => reject(new Error("failed to load " + src)));
  });
}

const scittleReady = () =>
  !!(window.scittle && window.scittle.core && window.scittle.core.eval_string);
const rf2Ready = () => !!(window.rf2sci && window.rf2sci.renderLast);

async function loadPlayground() {
  if (carryCellsOver()) return;
  // Instant navigation has already discarded the outgoing page's cells.
  // Release their React roots, frames and registrations before this page's
  // cells mount, including when this page has no cells at all.
  if (window.rf2sci && window.rf2sci.disposePage) {
    window.rf2sci.disposePage();
  }
  mounted = { pathname: location.pathname, cells: [] };
  const engines = [];
  if (document.querySelector(EVAL_CELL)) {
    engines.push(loadScript("cljs-scittle-js", SCITTLE_SRC, scittleReady));
  }
  if (document.querySelector(RF2_CELL)) {
    engines.push(loadScript("cljs-rf2-js", siblingUrl(RF2_BUNDLE_NAME), rf2Ready));
  }
  if (engines.length === 0) return;
  // Mount even if an engine failed to load: the editors still work, and a
  // cell that needs the missing engine shows the error when it runs.
  await Promise.allSettled(engines);
  mountAll();
}

// Material's `document$` emits on the first load and on every instant
// navigation. Subscribe once, even if Material re-executes this script.
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

// Hooks for test/smoke.test.mjs. `__rf2PlaygroundLoad` is the entry point the
// smoke calls after swapping the cell DOM, as Material does on navigation.
window.__rf2PlaygroundMountAll = mountAll;
window.__rf2PlaygroundEvalCljs = evalCljs;
window.__rf2PlaygroundRenderRf2 = renderComponentRf2;
window.__rf2PlaygroundLoad = loadPlayground;
