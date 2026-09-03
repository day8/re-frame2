(ns reagent2.impl.component
  "Compile-time component-shape classification for the day8/reagent-slim
  artefact (rf2-6hyy Stage 4-C).

  Per IMPL-SPEC §5.2 + §14.1 (rf2-yfbx decision): the runtime detection
  in `reagent2.impl.component/wrap-render` is the load-bearing
  correctness mechanism. The compile-time fold is additive — `reg-view`'s
  expansion classifies the body shape as Form-1 vs Form-2 at expansion
  time and stamps the wrapper with `^{:reagent2/form ...}` meta so the
  runtime path can skip the cond on the hot path. NO separate `defview`
  macro is shipped — `reg-view` is the single canonical view-registration
  surface (per the rf2-yfbx decision).

  Public fn (the one compile-time helper, consumed by `reg-view`'s
  expansion):

    classify-form-body — return a keyword form-tag for the body.

  It runs at macroexpansion time (CLJ), not at runtime — the macro
  invoking it folds the result into the emitted CLJS. It is a plain
  `defn` (the classification logic is pure-data), which is how
  `re-frame.core/expand-reg-view` can reach it through
  `requiring-resolve` without core carrying a static reagent-slim
  dep. The `{:reagent2/form ...}` metadata map itself is built at
  the macro's own expansion site, not here. No CLJS-side runtime
  code lives in this ns.")

;; ---------------------------------------------------------------------------
;; Compile-time form classification
;;
;; Form-1: render-fn body produces hiccup directly. Detected by exclusion:
;;         "anything that's not Form-2".
;;
;; Form-2: render-fn returns `(fn [args] hiccup)` — i.e. the LAST
;;         expression in the body is a literal `(fn ...)` form. The
;;         outer fn runs once at mount; the inner fn runs each render.
;;
;; Form-3: explicit `(create-class spec-map)` — a function call at the
;;         user's site, not inferable from a defn-shape body. Form-3
;;         registrations use `reg-view*` / `defn` rather than the
;;         `reg-view` macro, so this classifier never sees them.
;;
;; The classifier is purely structural — we don't try to do dataflow
;; analysis. A body like `(when foo (fn [x] [:p x]))` is conservatively
;; classified as Form-1 (the runtime detection in `wrap-render` handles
;; it correctly via the runtime fn? check).
;; ---------------------------------------------------------------------------

(defn classify-form-body
  "Return the form-tag for `body` (a seq of body forms from a defn-shape
  reg-view).

  Returns:
    :reagent2/form-2  when the last body form is a literal `(fn ...)`
                      or `(fn* ...)`.
    :reagent2/form-1  otherwise.

  Pure compile-time helper; no runtime cost. The classification is
  conservative — anything we can't structurally prove is Form-2 stays
  Form-1, with the runtime detection in `wrap-render` handling the
  non-literal cases correctly."
  [body]
  (let [last-form (last body)]
    (if (and (seq? last-form)
             (symbol? (first last-form))
             (let [n (name (first last-form))]
               (or (= "fn" n) (= "fn*" n))))
      :reagent2/form-2
      :reagent2/form-1)))
