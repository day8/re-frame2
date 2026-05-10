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

  Public macros (consumed by `reg-view`'s expansion):

    classify-form-body — return a keyword form-tag for the body. Pure
                         compile-time helper; no runtime cost.

    form-tagged-fn     — emit `(fn ~argv ~@body)` with the form-tag
                         attached as meta on the fn-form so the
                         runtime can read it.

  No CLJ-side runtime code lives here; only the compile-time helpers
  consumed by CLJS build sites via `:require-macros`. The classification
  logic is pure-data; both helpers are usable from `re-frame.views-macros`
  (or any other macro that wants to amortise the runtime detection)."
  (:refer-clojure :exclude [fn?]))

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

(defn tag-form-meta
  "Return a metadata map carrying the form-tag for runtime consumption.
  Stamped onto wrapper fns so `wrap-render` can short-circuit the
  classification cond on the hot path."
  [form-tag]
  {:reagent2/form form-tag})
