// Spike rf2-qk3sh — Phase 0 proof: one CM6 cell evaluating plain CLJS via Scittle.
//
// This is the entry source. It is bundled with esbuild into `cell.bundle.js`
// (an IIFE) which is what index.html loads. Scittle itself is loaded as a
// separate classic <script> (CDN/local) that installs the `scittle` global —
// Scittle is a shadow-cljs :browser build, NOT an ES module, so we do NOT
// import it; we read `window.scittle.core.eval_string` at eval time.
//
// Deps (resolved): @nextjournal/clojure-mode 0.3.3, @codemirror/state 6.6.0,
//   @codemirror/view 6.43.0, @codemirror/commands 6.x, scittle 0.8.31 (CDN/global).

import { EditorState, Prec } from "@codemirror/state";
import { EditorView, keymap, lineNumbers } from "@codemirror/view";
import { defaultKeymap, history, historyKeymap } from "@codemirror/commands";
import {
  default_extensions,
  complete_keymap,
} from "@nextjournal/clojure-mode";

// --- Eval wiring -----------------------------------------------------------

// Capture printed output (*out*) by wrapping the source so that Scittle's
// with-out-str collects anything the form prints, and we still return the
// value. We eval TWO things: the printed output and the result value.
//
// scittle.core.eval_string(srcString) -> the value of the LAST form in srcString
// (a JS-visible representation of the CLJS value; SCI returns the actual value).
// It throws a JS error if the form is invalid / eval fails.
//
// To also capture printed side-effects (println etc.) AND pretty-print the
// result the way the docs cells want, we eval a small wrapper expression.
function evalCljs(src) {
  const scittle = window.scittle;
  if (!scittle || !scittle.core || !scittle.core.eval_string) {
    throw new Error("Scittle global not loaded (window.scittle.core.eval_string missing)");
  }
  // SCI is a JS interpreter — NO JVM classes (java.io.StringWriter is absent).
  // Capture printed output (*out*) with clojure.core/with-out-str, and pr-str
  // the result value.
  //
  // GOTCHA: a CLJS vector returned from eval_string is a *PersistentVector
  // object*, NOT a JS array — out[0]/out[1] index access does NOT work. Wrap
  // the result in (clj->js ...) so Scittle hands JS a real Array.
  //
  // We must NOT print the body's value via with-out-str (that only captures
  // explicit prints). So: capture prints with with-out-str around the body,
  // stash the body's return value in an atom, then pr-str it separately.
  const wrapped =
    "(clj->js" +
    "  (let [r# (atom nil)" +
    "        o# (with-out-str (reset! r# (do " + src + "\n)))]" +
    "    [o# (pr-str (deref r#))]))";
  const out = scittle.core.eval_string(wrapped); // -> JS array [printed, resultStr]
  return { printed: out[0] || "", result: out[1] };
}

function renderResult(targetEl, { printed, result }) {
  targetEl.classList.remove("err");
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
  targetEl.classList.add("err");
  targetEl.innerHTML = "";
  const msg = (err && (err.message || err.toString())) || "unknown error";
  targetEl.textContent = "ERROR: " + msg;
}

// Custom eval keymap: Mod-Enter evaluates the whole cell.
// Wrapped in Prec.highest so it runs BEFORE any default/clojure-mode handler
// that might also bind Mod-Enter and swallow the event.
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

  preEl.replaceWith(wrap);

  const state = EditorState.create({
    doc: source,
    extensions: [
      lineNumbers(),
      history(),
      ...default_extensions, // clojure-mode: lezer syntax, close/match brackets, etc.
      keymap.of([...complete_keymap, ...defaultKeymap, ...historyKeymap]),
      evalKeymap(() => resultEl),
      EditorView.theme({
        "&": { fontSize: "14px", border: "1px solid #ccc", borderRadius: "4px" },
        ".cm-content": { fontFamily: "monospace" },
      }),
    ],
  });

  const view = new EditorView({ state, parent: editorHost });
  wrap.dataset.cljsMounted = "1";
  return view;
}

function mountAll() {
  document
    .querySelectorAll("pre.language-cljs:not([data-cljs-mounted])")
    .forEach(mountCell);
}

// Expose for the test harness + run on load.
window.__rf2SpikeMountAll = mountAll;
window.__rf2SpikeEvalCljs = evalCljs;
if (document.readyState === "loading") {
  document.addEventListener("DOMContentLoaded", mountAll);
} else {
  mountAll();
}
