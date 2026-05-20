(ns re-frame.subs.memo
  "Memo wrappers + the trace/perf/validate/recover bracket that brackets
  a user sub body. Extracted from `re-frame.subs` per rf2-0ytl4 Phase-2
  seam S-B — pure cohesion split, public surface unchanged.

  Per Spec 006 §No-op via value equality (rf2-719e). Reagent's auto-run
  reaction unconditionally invokes the compute fn on any source-watch
  fire, then dedups *downstream notification* by `=`. That's one level
  too late for the spec — the body fn itself must NOT re-run when the
  resolved input value is `=` to the last-seen. The memo wrappers
  compare the inputs against the previous invocation and short-circuit
  to the memoised return value when equal. Reagent's dependency
  tracking still observes every `deref` because the wrapper *is* the
  compute fn — only the user's body (and the trace+validate+perf+
  recovery layer that brackets it) is suppressed.

  The layer-1 path is specialised to a fixed-arity-1 wrapper that
  compares the db value directly. This skips the varargs-seq allocation
  a `(fn [& in-vals])` form would force on every recompute, and
  replaces the seq-vs-seq `=` walk with a direct value compare. Every
  layer-1 sub × every dispatch that touches it pays this — the hottest
  allocation in the artefact.

  Layer-2 with a single `:<-` input gets the same fixed-arity-1
  treatment (rf2-0y2bp). The adapter's `make-derived-value` already
  specialises its recompute closure to `(compute-fn @s0)` for the
  1-source case (rf2-v1nu0) — but the layer-N memo wrapper was
  varargs (`(fn [& in-vals])`), which forces a one-element ArraySeq
  allocation on every recompute. The dominant layer-2 shape is
  1-input, so we mirror the layer-1 specialisation here: fixed-arity-1
  wrapper, direct scalar compare against the last-seen input value.
  The ≥2-input path keeps the original varargs shape.

  Per-recompute hot path is the closure body (in-process) — unaffected
  by the ns boundary. Per-miss constructor call (from
  `re-frame.subs/compute-and-cache!`) crosses the ns boundary once."
  (:require [re-frame.interop :as interop]
            [re-frame.late-bind :as late-bind]
            [re-frame.performance :as performance
             #?@(:cljs [:include-macros true])]
            [re-frame.trace :as trace
             #?@(:cljs [:include-macros true])]))

#?(:clj (set! *warn-on-reflection* true))

(defn maybe-validate-sub!
  "Per Spec 010 §Validation order step 6 (rf2-wcam) — after a sub
  recomputes, validate its return value against any :schema on the sub
  meta. On failure, emit :rf.error/schema-validation-failure and
  return nil per :replaced-with-default recovery; otherwise return
  the value unchanged.

  Looked up lazily through the late-bind registry so this namespace
  stays free of a hard re-frame.schemas dep (avoids load-order
  surprises)."
  [value query-v sub-id sub-meta]
  (if (and sub-meta (:schema sub-meta))
    ;; Sticky hook (rf2-f72pd) — fires per-sub recompute.
    (if-let [validate (late-bind/get-fn-cached :schemas/validate-sub!)]
      (if (try (validate sub-id query-v value sub-meta)
               (catch #?(:clj Throwable :cljs :default) _ true))
        value
        nil)
      value)
    value))

(defn validate-and-trace
  "Run the user's sub body fn once and project the result through the
  trace + performance + validate + error-recovery layer. Called by the
  memo wrapper (`make-layer-1-memoised-body` for layer-1 subs,
  `make-layer-n-memoised-body` for layer-2+) on a true recompute —
  the memo path skips this entire function when input is `=` to
  last-seen.

  Concerns folded in here, in order:

  1. Spec 009 §:op-type vocabulary — emit :sub/run for the recompute.
     The memo-hit path does NOT emit (per Spec 006 §No-op via value
     equality).
  2. Spec 009 §Performance instrumentation (rf2-du3i) — bracket the
     body call in performance marks so prod builds with the perf flag
     enabled produce a `rf:sub:<sub-id>` measure entry. Default-off;
     under `:advanced` + `re-frame.performance/enabled?=false` the
     bracket DCEs.
  3. Spec 010 §Validation order step 6 (rf2-wcam) — validate the body's
     return value against the sub's `:schema` meta. Failures emit
     :rf.error/schema-validation-failure and yield nil (recovery
     :replaced-with-default).
  4. Spec 009 §Error contract — `try/catch` around (1)+(2)+(3). On
     exception emit :rf.error/sub-exception and yield nil (recovery
     :replaced-with-default)."
  [body-fn in-vals query-id query-v frame-id input-signals sub-meta]
  ;; Publish the sub's HandlerScope for the duration of `:sub/run` emit
  ;; + body-fn invocation + validation. Per Spec 009 §:rf.trace/
  ;; trigger-handler the sub's source-coord rides every emit (`:sub/run`
  ;; success, `:rf.error/sub-exception` / schema-validation / transitive
  ;; sub-miss errors). The emit MUST sit inside the scope.
  (trace/with-handler-scope
    (trace/handler-scope-from-meta :sub query-id sub-meta)
    (trace/emit! :sub/run :sub/run
                 {:sub-id  query-id
                  :query-v query-v
                  :frame   frame-id})
    (try
      (let [computed (performance/mark-and-measure :sub query-id
                      (if (empty? input-signals)
                        (body-fn (first in-vals) query-v)
                        ;; Layer-2+: deliver inputs as a coll if many,
                        ;; or singleton when only one chain entry.
                        (if (= 1 (count input-signals))
                          (body-fn (first in-vals) query-v)
                          (body-fn (vec in-vals) query-v))))]
        (maybe-validate-sub! computed query-v query-id sub-meta))
      (catch #?(:clj Throwable :cljs :default) e
        (let [msg #?(:clj (.getMessage e) :cljs (.-message e))]
          (trace/emit-error!
            :rf.error/sub-exception
            {:failing-id        query-id
             :sub-id            query-id
             :sub-query         query-v
             :exception         e
             :exception-message msg
             :reason            (str "Subscription `" query-id
                                     "` threw while computing: "
                                     msg ". Returning nil.")
             :recovery          :replaced-with-default}))
        nil))))

;; ---- memoisation wrappers ------------------------------------------------

(defn make-layer-1-memoised-body
  "Specialised memo wrapper for layer-1 subs (which read app-db
  directly). Fixed-arity-1 — avoids the varargs-seq allocation that a
  `(fn [& in-vals])` form would force on every reaction recompute, and
  compares the db value to the last-seen scalar (no seq-vs-seq walk).

  Returns a `(fn [db])`. When `body-fn` is nil (the unknown-sub path
  — see `re-frame.subs/compute-and-cache!`) the wrapper yields nil on
  every call without touching the memo cells.

  The `::unset` sentinel guarantees the first invocation always
  recomputes (the sentinel is never `=` to any db value)."
  [body-fn query-id query-v frame-id sub-meta]
  (let [last-db     (volatile! ::unset)
        last-result (volatile! nil)]
    (fn [db]
      (when body-fn
        (if (= @last-db db)
          ;; Memo hit — input value-equal to last-seen, the user body
          ;; does NOT re-run (rf2-719e). Emit `:rf.sub/skip` so tools
          ;; can show the "considered, no recompute" branch of the
          ;; reactive cascade DAG (rf2-931pm). Outer
          ;; `interop/debug-enabled?` gate elides the tag-map
          ;; construction + emit in CLJS production (Closure DCE under
          ;; `:advanced` + `goog.DEBUG=false`).
          (do
            (when interop/debug-enabled?
              (trace/with-handler-scope
                (trace/handler-scope-from-meta :sub query-id sub-meta)
                (trace/emit! :sub/skip :rf.sub/skip
                             {:frame                  frame-id
                              :sub-id                 query-id
                              :query-v                query-v
                              :reason                 :input-value-equal
                              :input-paths-unchanged  []})))
            @last-result)
          (let [computed (validate-and-trace
                           body-fn (list db) query-id query-v
                           frame-id [] sub-meta)]
            (vreset! last-db db)
            (vreset! last-result computed)
            computed))))))

(defn make-layer-n-1-memoised-body
  "Specialised memo wrapper for layer-2 subs with a single `:<-` input
  (the dominant layer-2 shape — see rf2-v1nu0 perf-sweep finding).
  Fixed-arity-1 — avoids the varargs-seq allocation that a
  `(fn [& in-vals])` form would force on every reaction recompute, and
  compares the upstream value to the last-seen scalar (no seq-vs-seq
  walk). Parity with `make-layer-1-memoised-body`.

  Returns a `(fn [v0])`. When `body-fn` is nil (the unknown-sub path
  — see `re-frame.subs/compute-and-cache!`) the wrapper yields nil on
  every call without touching the memo cells.

  The `::unset` sentinel guarantees the first invocation always
  recomputes (the sentinel is never `=` to any input value).

  `validate-and-trace` receives `in-vals` as a singleton list — the
  same shape the varargs wrapper would have produced for arity-1 —
  preserving the `(body-fn (first in-vals) query-v)` invocation path
  inside the validate/trace bracket (rf2-0y2bp)."
  [body-fn query-id query-v frame-id input-signals sub-meta]
  (let [last-v0     (volatile! ::unset)
        last-result (volatile! nil)]
    (fn [v0]
      (when body-fn
        (if (= @last-v0 v0)
          ;; Memo hit — see `make-layer-1-memoised-body` for the
          ;; `:rf.sub/skip` rationale (rf2-931pm). `:input-paths-unchanged`
          ;; carries the upstream sub query-vector(s) whose values were
          ;; stable; layer-2+ subs name their inputs by `[query-id args]`
          ;; rather than db-paths.
          (do
            (when interop/debug-enabled?
              (trace/with-handler-scope
                (trace/handler-scope-from-meta :sub query-id sub-meta)
                (trace/emit! :sub/skip :rf.sub/skip
                             {:frame                  frame-id
                              :sub-id                 query-id
                              :query-v                query-v
                              :reason                 :input-value-equal
                              :input-paths-unchanged  (vec input-signals)})))
            @last-result)
          (let [computed (validate-and-trace
                           body-fn (list v0) query-id query-v
                           frame-id input-signals sub-meta)]
            (vreset! last-v0 v0)
            (vreset! last-result computed)
            computed))))))

(defn make-layer-n-memoised-body
  "Memo wrapper for layer-2+ subs with two or more `:<-` inputs.
  Varargs — the input arity matches the count of `:<-` entries on the
  registration, and the wrapper compares the seq of input values
  against the last-seen seq.

  Returns a `(fn [& in-vals])`. When `body-fn` is nil the wrapper
  yields nil on every call without touching the memo cells.

  See `make-layer-1-memoised-body` for the layer-1 specialisation and
  `make-layer-n-1-memoised-body` for the layer-2 single-input
  specialisation (rf2-0y2bp)."
  [body-fn query-id query-v frame-id input-signals sub-meta]
  (let [last-in-vals (volatile! ::unset)
        last-result  (volatile! nil)]
    (fn [& in-vals]
      (when body-fn
        (if (= @last-in-vals in-vals)
          ;; Memo hit — see `make-layer-1-memoised-body` for the
          ;; `:rf.sub/skip` rationale (rf2-931pm). `:input-paths-unchanged`
          ;; carries every upstream `:<-` query-vector whose value was
          ;; stable (the varargs path has ≥2 inputs and the memo
          ;; compare is whole-seq `=`, so every input was stable).
          (do
            (when interop/debug-enabled?
              (trace/with-handler-scope
                (trace/handler-scope-from-meta :sub query-id sub-meta)
                (trace/emit! :sub/skip :rf.sub/skip
                             {:frame                  frame-id
                              :sub-id                 query-id
                              :query-v                query-v
                              :reason                 :input-value-equal
                              :input-paths-unchanged  (vec input-signals)})))
            @last-result)
          (let [computed (validate-and-trace
                           body-fn in-vals query-id query-v
                           frame-id input-signals sub-meta)]
            (vreset! last-in-vals in-vals)
            (vreset! last-result computed)
            computed))))))
