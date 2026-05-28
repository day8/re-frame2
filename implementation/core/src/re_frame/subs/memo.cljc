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

;; ---- recompute attribution sentinel + cause-sub resolution ---------------
;;
;; Per rf2-l1jz8 — the `:sub/run` trace carries value-change + cascade
;; attribution so Xray's Reactive panel can populate its "SUBS WHOSE
;; VALUE CHANGED" and "SUBS THAT CASCADED" sections. The memo wrapper
;; already holds the prior computed value and prior input value(s) in its
;; volatile cells (it must, to dedup `=`-equal recomputes per rf2-719e),
;; so the attribution is computed from data already on hand — no extra
;; cache read, no second `=` walk on the hot recompute path beyond the one
;; the memo wrapper already performed.

(def ^{:doc "First-recompute sentinel. Never `=` to any computed value or
  input value, so the very first recompute is always reported as a value
  change with no resolvable cause."}
  unset ::unset)

(defn changed-cause-sub
  "Resolve the upstream sub whose value-change drove this layer-2+
  recompute. `prev-in-vals` is the memo wrapper's last-seen input value(s)
  (the `::unset` sentinel on the first recompute, otherwise a seq parallel
  to `input-signals`); `in-vals` is the freshly-resolved input value(s);
  `input-signals` is the vector of upstream `:<-` query-vectors in
  registration order. Returns the FIRST input-signal query-vector whose
  value differs from its prior, or nil when none differ / no prior exists
  (first recompute → `::unset` sentinel, cause unknown).

  Pure; called only inside the dev-gated emit branch."
  [prev-in-vals in-vals input-signals]
  ;; NB: `=` not `identical?` — CLJS keywords are NOT guaranteed
  ;; reference-identical across construction sites (unlike Clojure JVM
  ;; interning), so `(identical? unset ::unset-literal)` can be false in
  ;; CLJS. `unset` is a unique namespaced sentinel no real input value
  ;; can `=`, so value-equality is the correct, portable guard.
  (when (and (seq input-signals)
             (not= unset prev-in-vals))
    (let [prev (vec prev-in-vals)
          curr (vec in-vals)]
      (loop [i 0]
        (when (< i (count input-signals))
          (if (not= (nth prev i ::missing) (nth curr i ::missing))
            (nth input-signals i)
            (recur (inc i))))))))

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

  1. Spec 009 §Performance instrumentation (rf2-du3i) — bracket the
     body call in performance marks so prod builds with the perf flag
     enabled produce a `rf:sub:<sub-id>` measure entry. Default-off;
     under `:advanced` + `re-frame.performance/enabled?=false` the
     bracket DCEs.
  2. Spec 010 §Validation order step 6 (rf2-wcam) — validate the body's
     return value against the sub's `:schema` meta. Failures emit
     :rf.error/schema-validation-failure and yield nil (recovery
     :replaced-with-default).
  3. Spec 009 §:op-type vocabulary — emit :sub/run for the recompute.
     The memo-hit path does NOT emit (per Spec 006 §No-op via value
     equality). The emit fires AFTER (1)+(2) so the trace can carry the
     COMPUTED value and value-change / cascade attribution (rf2-l1jz8 —
     see §Recompute attribution below). It MUST stay inside
     `with-handler-scope` so the sub's source-coord rides the tag.
  4. Spec 009 §Error contract — `try/catch` around (1)+(2)+(3). On
     exception emit :rf.error/sub-exception and yield nil (recovery
     :replaced-with-default).

  ## Recompute attribution (rf2-l1jz8, dev-only)

  When `interop/debug-enabled?` the `:sub/run` tag carries:

    :value-changed?  — `(not= prev-value computed)`. The `::unset`
                       sentinel on the first recompute reports `true`.
                       NOT wire-sensitive (a boolean).
    :prev-value      — the prior computed value (`nil` on the first
                       recompute). Wire-value-sensitive app data.
    :value           — the freshly-computed value. Wire-value-sensitive.
    :first-run?      — `true` on the run that CREATED the cache entry
                       (`prev-value` was the `::unset` sentinel);
                       `false` on every subsequent recompute. Disambiguates
                       a value-change row from a freshly-created sub row
                       so consumers (Xray's SUBSCRIPTIONS leaf-scalar
                       renderer per rf2-fyd8u) can pick `← was X` vs
                       `:added` chrome without inferring from
                       `:prev-value nil`. Not wire-sensitive. Per
                       Spec 009 §`:rf.sub/run`.
    :cascade?        — `true` when this is a layer-2+ sub (an upstream
                       SUB drove the recompute); `false` for a layer-1
                       sub (an app-db path change drove it).
    :cause-sub       — for a cascade, the upstream `:<-` query-vector
                       whose value changed (`changed-cause-sub`); nil
                       for a layer-1 sub OR a layer-2+ first recompute
                       (no prior input to diff against).
    :cause-event-id  — the dispatching cascade's event-id (the head of
                       the event vector that kicked off the in-flight
                       drain). Names which event invalidated this sub's
                       reactive input. Sourced from the in-flight
                       cascade buffer via the `:epoch/cascade-cause`
                       late-bind hook — same source the views path uses
                       for `:rf.view/cause-event-id` (rf2-25zo2 / rf2-okz1u).
                       OMITTED (key absent, not nil) when the sub runs
                       outside any cascade (a post-settle reactive flush
                       against no live drain) or when the epoch artefact
                       is not on the classpath. The Xray Epoch panel's
                       SUBSCRIPTIONS section reads it to credit each
                       sub-run to the right epoch row even when the
                       physical reactive flush deferred into a chained
                       sibling event's drain. Not wire-sensitive (an
                       event-id keyword). Per Spec 009 §`:rf.sub/run`.

  ### Privacy — handled at the trace chokepoint, NOT here

  `:prev-value` and `:value` are wire-value-sensitive app data, but they
  are emitted RAW here and redacted DOWNSTREAM by the existing
  `re-frame.marks/project-sub-tags` chokepoint that `re-frame.trace/
  build-event` already runs for every `:sub/run` event. That chokepoint
  resolves the sub's sensitive/large state from process-scoped marks +
  the sub-output propagation table — NEVER by reading the frame's app-db
  container.

  This is deliberate and load-bearing: calling the schema-first
  `elision/elide-wire-value` walker here would `deref` the frame's
  app-db container (to read the `[:rf/runtime :elision ...]` registry) INSIDE the
  reaction's compute fn. On a Reagent substrate that registers a
  spurious reactive dependency on app-db for every layer-2+ sub —
  breaking the glitch-free `db → layer-1 → layer-2` layering (the sub
  would recompute on ANY app-db change, not just its own input's). The
  marks chokepoint reads only process-scoped atoms, so it is
  reaction-safe. A schema-`:sensitive?` sub egresses `:prev-value` /
  `:value` as `:rf/redacted`; `:value-changed?` stays a plain boolean.

  The whole attribution branch (the enriched tag map) sits inside
  `(if interop/debug-enabled? ...)` so Closure DCE folds it out under
  `:advanced` + `goog.DEBUG=false`; the unattributed base tag is emitted
  on the production path so the op-type vocabulary is unchanged there.
  `prev-value`/`prev-in-vals` arrive pre-resolved from the memo
  wrapper's volatile cells — no extra cache read.

  Callers (the memo wrappers) pass `prev-value` (the wrapper's
  `last-result` cell, `::unset` on first recompute) and `prev-in-vals`
  (the wrapper's last-seen input value(s), `::unset` on first recompute,
  else a coll parallel to `input-signals`)."
  [body-fn in-vals query-id query-v frame-id input-signals sub-meta
   prev-value prev-in-vals]
  ;; Publish the sub's HandlerScope for the duration of body-fn
  ;; invocation + validation + the `:sub/run` emit. Per Spec 009
  ;; §:rf.trace/trigger-handler the sub's source-coord rides every emit
  ;; (`:sub/run` success, `:rf.error/sub-exception` / schema-validation /
  ;; transitive sub-miss errors). The emit MUST sit inside the scope.
  (trace/with-handler-scope
    (trace/handler-scope-from-meta :sub query-id sub-meta)
    (try
      ;; rf2-hhh92: wall-clock the sub body (dev-only) so `:rf.sub/run`
      ;; carries `:rf.sub/elapsed-ms` — the per-op duration the Trace
      ;; panel's DURATION column reads. The `now-ms` brackets ride
      ;; `interop/debug-enabled?` (nil t0 in prod), so Closure DCEs them
      ;; under :advanced + `goog.DEBUG=false` — zero prod cost. Distinct
      ;; from the `performance/mark-and-measure` perf surface (default-off,
      ;; browser-only, NOT on the trace stream).
      (let [t0        (when interop/debug-enabled? (interop/now-ms))
            computed (performance/mark-and-measure :sub query-id
                      (if (empty? input-signals)
                        (body-fn (first in-vals) query-v)
                        ;; Layer-2+: deliver inputs as a coll if many,
                        ;; or singleton when only one chain entry.
                        (if (= 1 (count input-signals))
                          (body-fn (first in-vals) query-v)
                          (body-fn (vec in-vals) query-v))))
            elapsed-ms (when interop/debug-enabled? (- (interop/now-ms) t0))
            validated (maybe-validate-sub! computed query-v query-id sub-meta)]
        ;; Emit AFTER compute+validate so the trace carries the computed
        ;; value + attribution (rf2-l1jz8). The base tag is unconditional
        ;; (op-type vocabulary parity with the prod path); the attribution
        ;; slots ride the dev gate so Closure DCEs the enriched tag map
        ;; under :advanced. `:prev-value` / `:value` are emitted RAW —
        ;; the existing `re-frame.marks/project-sub-tags` chokepoint
        ;; (run by `trace/build-event`) redacts them from process-scoped
        ;; marks without a reactive container deref. See the ns docstring
        ;; §Privacy for why we MUST NOT elide here.
        (if interop/debug-enabled?
          (let [cascade?  (boolean (seq input-signals))
                cause-sub (changed-cause-sub prev-in-vals in-vals input-signals)
                ;; rf2-vh1k3 — the render-key of the view whose render is
                ;; deref-ing this reaction (nil outside a view render). The
                ;; epoch back-fill reads this off the target epoch's
                ;; `:sub-runs` so a late-arriving render is attributed to
                ;; the epoch where the rendering view's OWN inputs changed,
                ;; not whatever cascade is settling when a mount-burst tail
                ;; commits. Resolved through late-bind so this .cljc subs
                ;; layer stays free of a require on the CLJS-only views ns.
                reader-rk (when-let [f (late-bind/get-fn-cached
                                         :views/reading-render-key)]
                            (f))
                ;; rf2-okz1u — `:rf.sub/cause-event-id` names the
                ;; dispatching cascade whose handler-body invalidated
                ;; this sub's reactive input. Same source the views path
                ;; uses for `:rf.view/cause-event-id` (rf2-25zo2): the
                ;; in-flight cascade buffer published by re-frame.epoch
                ;; under the `:epoch/cascade-cause` late-bind hook. The
                ;; hook walks the frame's per-cascade buffer and returns
                ;; the FIRST `:rf.event/run-start` event-id it sees —
                ;; i.e. the cascade's dispatching event vector head
                ;; (`(first event)`). Resolved through late-bind so this
                ;; .cljc subs layer stays free of a static require on
                ;; the optional epoch artefact. Returns nil when the
                ;; epoch artefact is absent OR the sub runs outside any
                ;; in-flight cascade (e.g. a `:rf.sub/run` driven by a
                ;; post-settle reactive flush against no live drain);
                ;; the tag is OMITTED in those cases so consumers
                ;; (Xray's Epoch panel SUBSCRIPTIONS section) read it
                ;; only when meaningful. Inside the
                ;; `interop/debug-enabled?` gate so the lookup + lift
                ;; DCE under :advanced + goog.DEBUG=false.
                cause-fn        (late-bind/get-fn-cached :epoch/cascade-cause)
                cascade-cause   (when cause-fn (cause-fn frame-id))
                cause-event-id  (:cause-event-id cascade-cause)]
            (trace/emit! :rf.sub :rf.sub/run
                         (cond-> {:rf.sub/id             query-id
                                  :rf.sub/query-v        query-v
                                  :frame                 frame-id
                                  :rf.sub/value-changed? (not= prev-value validated)
                                  :rf.sub/first-run?     (= unset prev-value)
                                  :rf.sub/prev-value     (when-not (= unset prev-value)
                                                           prev-value)
                                  :rf.sub/value          validated
                                  :rf.sub/cascade?       cascade?
                                  :rf.sub/cause-sub      cause-sub}
                           (some? elapsed-ms)
                           (assoc :rf.sub/elapsed-ms elapsed-ms)
                           reader-rk (assoc :rf.sub/reader-render-key reader-rk)
                           (some? cause-event-id)
                           (assoc :rf.sub/cause-event-id cause-event-id))))
          (trace/emit! :rf.sub :rf.sub/run
                       {:rf.sub/id      query-id
                        :rf.sub/query-v query-v
                        :frame          frame-id}))
        validated)
      (catch #?(:clj Throwable :cljs :default) e
        (let [msg #?(:clj (.getMessage e) :cljs (.-message e))]
          (trace/emit-error!
            :rf.error/sub-exception
            {:failing-id        query-id
             :rf.sub/id         query-id
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
                (trace/emit! :rf.sub :rf.sub/skip
                             {:frame                        frame-id
                              :rf.sub/id                    query-id
                              :rf.sub/query-v               query-v
                              :rf.sub/reason                :input-value-equal
                              :rf.sub/input-paths-unchanged []})))
            @last-result)
          ;; Capture the prior cells BEFORE the recompute so the
          ;; `:sub/run` attribution (rf2-l1jz8) can report value-change
          ;; against the last computed value. Layer-1 has no upstream
          ;; sub inputs, so `input-signals` is `[]` and `prev-in-vals`
          ;; is irrelevant to cause-sub resolution (a layer-1 recompute
          ;; is driven by an app-db path change, never a sub cascade).
          ;;
          ;; rf2-fyd8u — pass `unset` for `prev-value` on the run that
          ;; allocated the cache slot (the input cell `last-db` is still
          ;; the `::unset` sentinel here — pre-vreset). `last-result`
          ;; starts at `nil` (not `::unset`) so it cannot serve as the
          ;; first-run discriminator alone; keying on `last-db` keeps
          ;; the `::unset → unset` projection well-defined even when the
          ;; first cached value happens to be `nil`. The
          ;; `validate-and-trace` emit then stamps `:rf.sub/first-run?`
          ;; from `(= unset prev-value)`.
          (let [first-run?  (= ::unset @last-db)
                prev-result (if first-run? unset @last-result)
                computed    (validate-and-trace
                              body-fn (list db) query-id query-v
                              frame-id [] sub-meta prev-result unset)]
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
                (trace/emit! :rf.sub :rf.sub/skip
                             {:frame                        frame-id
                              :rf.sub/id                    query-id
                              :rf.sub/query-v               query-v
                              :rf.sub/reason                :input-value-equal
                              :rf.sub/input-paths-unchanged (vec input-signals)})))
            @last-result)
          ;; Capture prior cells BEFORE the recompute for the `:sub/run`
          ;; attribution (rf2-l1jz8). `prev-in-vals` is the last-seen
          ;; single input value in singleton-list shape (matching the
          ;; `(list v0)` `in-vals` form) so `changed-cause-sub` can diff
          ;; it positionally against `input-signals`; the `::unset`
          ;; sentinel on first recompute leaves `:cause-sub` nil.
          ;;
          ;; rf2-fyd8u — pass `unset` for `prev-value` on the run that
          ;; allocated the cache slot (the input cell `last-v0` is still
          ;; the `::unset` sentinel here). `last-result` is `nil` then
          ;; too but keying on `last-v0` keeps the discriminator well-
          ;; defined regardless of the first cached value's shape.
          (let [first-run?   (= ::unset @last-v0)
                prev-result  (if first-run? unset @last-result)
                prev-v0      @last-v0
                prev-in-vals (if (= unset prev-v0)
                               unset
                               (list prev-v0))
                computed     (validate-and-trace
                               body-fn (list v0) query-id query-v
                               frame-id input-signals sub-meta
                               prev-result prev-in-vals)]
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
                (trace/emit! :rf.sub :rf.sub/skip
                             {:frame                        frame-id
                              :rf.sub/id                    query-id
                              :rf.sub/query-v               query-v
                              :rf.sub/reason                :input-value-equal
                              :rf.sub/input-paths-unchanged (vec input-signals)})))
            @last-result)
          ;; Capture prior cells BEFORE the recompute for the `:sub/run`
          ;; attribution (rf2-l1jz8). `prev-in-vals` is the last-seen
          ;; input-value seq (parallel to `input-signals`), which
          ;; `changed-cause-sub` diffs positionally to name the upstream
          ;; sub that cascaded. `::unset` on first recompute → nil cause.
          ;;
          ;; rf2-fyd8u — pass `unset` for `prev-value` on the run that
          ;; allocated the cache slot (the input cell `last-in-vals` is
          ;; still the `::unset` sentinel here). Keying on `last-in-vals`
          ;; rather than `last-result` so a sub whose first cached value
          ;; is `nil` still flags as `:first-run?` true.
          (let [first-run?   (= ::unset @last-in-vals)
                prev-result  (if first-run? unset @last-result)
                prev-in-vals @last-in-vals
                computed     (validate-and-trace
                               body-fn in-vals query-id query-v
                               frame-id input-signals sub-meta
                               prev-result prev-in-vals)]
            (vreset! last-in-vals in-vals)
            (vreset! last-result computed)
            computed))))))
