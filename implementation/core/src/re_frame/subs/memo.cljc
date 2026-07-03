(ns re-frame.subs.memo
  "Memo wrappers + the trace/perf/validate/recover bracket that brackets
  a user sub body.

  Per Spec 006 §No-op via value equality. Reagent's auto-run
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
  treatment. The adapter's `make-derived-value` specialises its
  recompute closure to `(compute-fn @s0)` for the 1-source case, and the
  memo wrapper mirrors the layer-1 specialisation: a fixed-arity-1
  wrapper with a direct scalar compare against the last-seen input value,
  avoiding the one-element ArraySeq allocation a varargs
  (`(fn [& in-vals])`) form would force on every recompute. The dominant
  layer-2 shape is 1-input. The ≥2-input path uses the varargs shape.

  Per-recompute hot path is the closure body (in-process) — unaffected
  by the ns boundary. Per-miss constructor call (from
  `re-frame.subs/compute-and-cache!`) crosses the ns boundary once."
  (:require [re-frame.error :as error]
            [re-frame.interop :as interop]
            [re-frame.late-bind :as late-bind]
            [re-frame.performance :as performance
             #?@(:cljs [:include-macros true])]
            [re-frame.trace :as trace
             #?@(:cljs [:include-macros true])]))

#?(:clj (set! *warn-on-reflection* true))

;; ---- recompute attribution sentinel + cause-sub resolution ---------------
;;
;; The `:sub/run` trace carries value-change + cascade
;; attribution so Xray's Reactive panel can populate its "SUBS WHOSE
;; VALUE CHANGED" and "SUBS THAT CASCADED" sections. The memo wrapper
;; already holds the prior computed value and prior input value(s) in its
;; volatile cells (it must, to dedup `=`-equal recomputes per Spec 006
;; §No-op via value equality),
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
  "Per Spec 010 §Validation order step 6 — after a sub
  recomputes, validate its return value against any :schema on the sub
  meta. On failure, emit :rf.error/schema-validation-failure and
  return nil per :replaced-with-default recovery; otherwise return
  the value unchanged.

  Looked up lazily through the late-bind registry so this namespace
  stays free of a hard re-frame.schemas dep (avoids load-order
  surprises).

  `frame-id` (the reaction's frame) is passed through to
  `validate-sub!` so the `:where :sub-return` failure trace carries
  `:frame` and lands in the per-frame epoch `:trace-events` (epoch
  capture buffers only frame-tagged traces). Mirrors the `:where
  :app-db` / `:where :event` traces."
  [value query-v sub-id sub-meta frame-id]
  (if (and sub-meta (:schema sub-meta))
    ;; Sticky hook — fires per-sub recompute.
    (if-let [validate (late-bind/get-fn-cached :schemas/validate-sub!)]
      (if (try (validate sub-id query-v value sub-meta frame-id)
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

  1. Spec 009 §Performance instrumentation — bracket the
     body call in performance marks so prod builds with the perf flag
     enabled produce a `rf:sub:<sub-id>` measure entry. Default-off;
     under `:advanced` + `re-frame.performance/enabled?=false` the
     bracket DCEs.
  2. Spec 010 §Validation order step 6 — validate the body's
     return value against the sub's `:schema` meta. Failures emit
     :rf.error/schema-validation-failure and yield nil (recovery
     :replaced-with-default).
  3. Spec 009 §:op-type vocabulary — emit :sub/run for the recompute.
     The memo-hit path does NOT emit (per Spec 006 §No-op via value
     equality). The emit fires AFTER (1)+(2) so the trace can carry the
     COMPUTED value and value-change / cascade attribution (see
     §Recompute attribution below). It MUST stay inside
     `with-handler-scope` so the sub's source-coord rides the tag.
  4. Spec 009 §Error contract — `try/catch` around (1)+(2)+(3). On
     exception emit :rf.error/sub-exception and yield nil (recovery
     :replaced-with-default).

  ## Recompute attribution (dev-only)

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
                       renderer) can pick `← was X` vs
                       `:added` chrome without inferring from
                       `:prev-value nil`. Not wire-sensitive. Per
                       Spec 009 §`:rf.sub/run`.
    :cascade?        — SENSE (rf2-p4cd9c): reactive-graph propagation, NOT
                       the event-pipeline-run sense — kept per the glw1bh
                       sense-guard (and it is public Spec 009 wire vocab:
                       `:rf.sub/cascade?`). `true` when this is a layer-2+
                       sub (an upstream SUB drove the recompute); `false`
                       for a layer-1 sub (an app-db path change drove it).
    :cause-sub       — for a cascade, the upstream `:<-` query-vector
                       whose value changed (`changed-cause-sub`); nil
                       for a layer-1 sub OR a layer-2+ first recompute
                       (no prior input to diff against).
    :cause-event-id  — the dispatching run's event-id (the head of
                       the event vector that kicked off the in-flight
                       drain). Names which event invalidated this sub's
                       reactive input. Sourced from the in-flight
                       run buffer via the `:epoch/run-cause`
                       late-bind hook — same source the views path uses
                       for `:rf.view/cause-event-id`.
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
  `re-frame.classification/project-sub-tags` chokepoint that `re-frame.trace/
  build-event` already runs for every `:sub/run` event. That chokepoint
  resolves the sub's sensitive/large state from process-scoped marks +
  the sub-output propagation table — NEVER by reading the frame's app-db
  container.

  This is deliberate and load-bearing: calling the schema-first
  `elision/elide-wire-value` walker here would `deref` one of the frame's
  container projections (to read the `[:rf.runtime/elision ...]` registry
  from the runtime-db partition) INSIDE the reaction's compute fn. On a
  Reagent substrate that registers a spurious reactive dependency on a
  frame container for every layer-2+ sub — breaking the glitch-free
  `db → layer-1 → layer-2` layering (the sub would recompute on ANY
  matching container change, not just its own input's). The
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
   prev-value prev-in-vals vector-inputs?]
  ;; Publish the sub's HandlerScope for the duration of body-fn
  ;; invocation + validation + the `:sub/run` emit. Per Spec 009
  ;; §:rf.trace/trigger-handler the sub's source-coord rides every emit
  ;; (`:sub/run` success, `:rf.error/sub-exception` / schema-validation /
  ;; transitive sub-miss errors). The emit MUST sit inside the scope.
  ;;
  ;; `vector-inputs?`: when true, the body ALWAYS receives the
  ;; resolved inputs as a VECTOR (in producer order) — the contract for a
  ;; PARAMETRIC `input-fn` sub, whose computation fn destructures `[[a] q]`
  ;; even for a single input (Spec 006 §Subscription input producers
  ;; §Single input). When false (the static `:<-` path) the v1
  ;; convention holds: a single `:<-` input is delivered as the bare value,
  ;; ≥2 inputs as a vector. The layer-1 / single-`:<-` wrappers pass false.
  (trace/with-handler-scope
    (trace/handler-scope-from-meta :sub query-id sub-meta)
    (try
      ;; Wall-clock the sub body (dev-only) so `:rf.sub/run`
      ;; carries `:rf.sub/elapsed-ms` — the per-op duration the Trace
      ;; panel's DURATION column reads. The `now-ms` brackets ride
      ;; `interop/debug-enabled?` (nil t0 in prod), so Closure DCEs them
      ;; under :advanced + `goog.DEBUG=false` — zero prod cost. Distinct
      ;; from the `performance/mark-and-measure` perf surface (default-off,
      ;; browser-only, NOT on the trace stream).
      (let [t0        (when interop/debug-enabled? (interop/now-ms))
            computed (performance/mark-and-measure :sub query-id
                      (cond
                        ;; Parametric sub — always a vector of input values
                        ;; (producer order), even for one input.
                        vector-inputs?
                        (body-fn (vec in-vals) query-v)

                        (empty? input-signals)
                        (body-fn (first in-vals) query-v)

                        ;; Static `:<-`: single input → bare value;
                        ;; ≥2 inputs → vector (the v1 convention).
                        (= 1 (count input-signals))
                        (body-fn (first in-vals) query-v)

                        :else
                        (body-fn (vec in-vals) query-v)))
            elapsed-ms (when interop/debug-enabled? (- (interop/now-ms) t0))
            validated (maybe-validate-sub! computed query-v query-id sub-meta frame-id)]
        ;; Emit AFTER compute+validate so the trace carries the computed
        ;; value + attribution. The base tag is unconditional
        ;; (op-type vocabulary parity with the prod path); the attribution
        ;; slots ride the dev gate so Closure DCEs the enriched tag map
        ;; under :advanced. `:prev-value` / `:value` are emitted RAW —
        ;; the existing `re-frame.classification/project-sub-tags` chokepoint
        ;; (run by `trace/build-event`) redacts them from process-scoped
        ;; marks without a reactive container deref. See the ns docstring
        ;; §Privacy for why we MUST NOT elide here.
        (if interop/debug-enabled?
          ;; `cascade?` here = REACTIVE-GRAPH propagation (kept sense,
          ;; rf2-p4cd9c): true iff this sub has upstream SUB inputs (layer-2+).
          ;; NOT the event-pipeline-run sense; public wire key :rf.sub/cascade?.
          (let [cascade?  (boolean (seq input-signals))
                cause-sub (changed-cause-sub prev-in-vals in-vals input-signals)
                ;; The render-key of the view whose render is
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
                ;; `:rf.sub/cause-event-id` names the
                ;; dispatching run whose handler-body invalidated
                ;; this sub's reactive input. Same source the views path
                ;; uses for `:rf.view/cause-event-id`: the
                ;; in-flight run buffer published by re-frame.epoch
                ;; under the `:epoch/run-cause` late-bind hook. The
                ;; hook walks the frame's per-run buffer and returns
                ;; the FIRST `:rf.event/run-start` event-id it sees —
                ;; i.e. the run's dispatching event vector head
                ;; (`(first event)`). Resolved through late-bind so this
                ;; .cljc subs layer stays free of a static require on
                ;; the optional epoch artefact. Returns nil when the
                ;; epoch artefact is absent OR the sub runs outside any
                ;; in-flight run (e.g. a `:rf.sub/run` driven by a
                ;; post-settle reactive flush against no live drain);
                ;; the tag is OMITTED in those cases so consumers
                ;; (Xray's Epoch panel SUBSCRIPTIONS section) read it
                ;; only when meaningful. Inside the
                ;; `interop/debug-enabled?` gate so the lookup + lift
                ;; DCE under :advanced + goog.DEBUG=false.
                cause-fn        (late-bind/get-fn-cached :epoch/run-cause)
                run-cause       (when cause-fn (cause-fn frame-id))
                cause-event-id  (:cause-event-id run-cause)]
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
                                  ;; The REALIZED input
                                  ;; query-vectors for this concrete cache
                                  ;; entry (the literal `:<-` list for
                                  ;; `:static`, the `(input-fn query-v)`
                                  ;; result for `:parametric`, `[]` for
                                  ;; layer-1). `input-signals` is the
                                  ;; per-entry realized edge set (Spec 006
                                  ;; §Subscription input producers). Lets the
                                  ;; Xray live-cascade view render REALIZED
                                  ;; parametric edges without fabricating
                                  ;; un-materialized ones. Query-vectors,
                                  ;; not values — rides raw like
                                  ;; `:rf.sub/query-v` / `:rf.sub/cause-sub`
                                  ;; (only computed values are redacted).
                                  :rf.sub/inputs         (vec input-signals)
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
        (let [msg    (error/ex-message-safe e) ; nil-safe (a thrown non-Error value has no message)
              reason (str "Subscription `" query-id
                          "` threw while computing: "
                          msg ". Returning nil.")
              ;; Shared `:tags` body — consumed by BOTH the always-on
              ;; error-emit policy-event (below) and the dev-only trace.
              ;; Frame-attribute the sub-exception (the reactive
              ;; sub-run knows its `frame-id`, used by the success emit
              ;; above). Without `:frame` the error is dropped by
              ;; `re-frame.epoch.capture/capture-event!` (frame-tagged only)
              ;; so the Xray Issues lens misses it, and the SSR error-
              ;; projection listener cannot map the `:rf.error/sub-exception`
              ;; category to a per-frame 5xx.
              tags   {:failing-id        query-id
                      :rf.sub/id         query-id
                      :sub-query         query-v
                      :frame             frame-id
                      :exception         e
                      :exception-message msg
                      :reason            reason
                      :recovery          :replaced-with-default}]
          ;; Route the reactive `:rf.error/sub-exception`
          ;; through the ALWAYS-ON `error-emit/dispatch-on-error!`
          ;; substrate (mirroring router.cljc's handler-exception and
          ;; fx.cljc's reserved-fx typed-throw paths) so it survives
          ;; `interop/debug-enabled? = false` (CLJS `:advanced` +
          ;; `goog.DEBUG=false`; JVM `-Dre-frame.debug=false`). Under that
          ;; production-hardening posture (the one Spec 011 §Substrate
          ;; mandates SSR run in) the dev-only `trace/emit-error!` below
          ;; is elided — so a subscription that throws mid-`render-to-
          ;; string` would otherwise recover to nil here, yielding a
          ;; silent HTTP 200 with broken HTML and no 5xx projection. The
          ;; always-on path lets the SSR `error-emit-projection-listener`
          ;; stamp the (elided) 500 — the projector's PUBLIC shape never
          ;; leaks `:exception` / message; that detail rides the trace +
          ;; off-box listener record only (Spec 011 §Internal trace
          ;; events are not leaked). A reactive sub has no triggering
          ;; event vector, but the failing sub's `query-v`
          ;; / `query-id` ride `:event` / `:event-id` (mirroring the
          ;; sub-input-fn path) so the kind-aware error-emit lookup
          ;; resolves the sub's `:source-coord` under `[:sub query-id]`
          ;; for off-box shippers — `:frame` already carries `frame-id`.
          ;;
          ;; Reached via the late-bind hook `:error-emit/dispatch-on-
          ;; error` — this subs layer cannot statically require
          ;; `re-frame.error-emit` (would form a load cycle, same as
          ;; fx.cljc). Always invoked (NOT under `interop/debug-enabled?`)
          ;; — that is the whole point: it is the production-survivable
          ;; status source of truth.
          ;;
          ;; `:rf.error/sub-exception` recovery is the framework's built-in
          ;; 'return nil' — there is no app-steering recovery policy.
          ;; The SSR fail-closed posture rides the
          ;; always-on listener record the `error-emit-projection-listener`
          ;; buffers — which preserves the SSR 500 projection.
          ;; Both channels via the shared helper: axis 1 the
          ;; always-on listener (survives prod elision), axis 2 the dev trace —
          ;; preserved for the trace surface + retain-N buffer + dev-side
          ;; projection (carries the same rich internal detail; DCEs under
          ;; `:advanced` + `goog.DEBUG=false`). Reached via the
          ;; `:error-emit/emit-error-both` hook (this subs layer cannot
          ;; static-require error-emit — load cycle). `elapsed-ms 0`.
          (when-let [emit-error-both!
                     (late-bind/get-fn-cached :error-emit/emit-error-both)]
            (emit-error-both!
              :rf.error/sub-exception
              query-v                             ;; failing query-vector (as :event)
              query-id                            ;; sub-id (as :event-id) — drives [:sub …] coord lookup
              frame-id
              e
              0                                   ;; elapsed-ms
              (interop/now-ms)                    ;; time
              tags)))
        nil))))

;; ---- the shared memo-hit `:rf.sub/skip` emit -----------------------------
;;
;; All three memo wrappers below emit a byte-identical `:rf.sub/skip` trace on a
;; memo hit (input value-equal to last-seen, the user body does NOT re-run)
;; so tools can show the "considered, no recompute" branch of the
;; reactive cascade DAG. The only thing that varies is
;; `:rf.sub/input-paths-unchanged` (`[]` for layer-1 which has no upstream sub
;; inputs; `(vec input-signals)` for the layer-n forms). The skip-emit BODY is
;; deduped here; the three wrappers stay separate — their per-recompute hot
;; closures are perf-justified (fixed-arity-1 vs single-input vs varargs) and
;; only the cold skip-emit body is shared.

(defn- emit-sub-skip!
  "Emit the memo-hit `:rf.sub/skip` trace for a sub that was reactively
  considered but did NOT recompute (input value-equal).
  `input-paths-unchanged` is the inputs-stable set (`[]` for layer-1, the
  realized `:<-` query-vectors for layer-n). Outer `interop/debug-enabled?`
  gate elides the tag-map construction + emit in CLJS production (Closure DCE
  under `:advanced` + `goog.DEBUG=false`)."
  [query-id query-v frame-id sub-meta input-paths-unchanged]
  (when interop/debug-enabled?
    (trace/with-handler-scope
      (trace/handler-scope-from-meta :sub query-id sub-meta)
      (trace/emit! :rf.sub :rf.sub/skip
                   {:frame                        frame-id
                    :rf.sub/id                    query-id
                    :rf.sub/query-v               query-v
                    :rf.sub/reason                :input-value-equal
                    :rf.sub/input-paths-unchanged input-paths-unchanged}))))

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
          ;; does NOT re-run. Emit `:rf.sub/skip` so tools
          ;; can show the "considered, no recompute" branch of the
          ;; reactive cascade DAG. Layer-1 has no upstream
          ;; sub inputs, so `:input-paths-unchanged` is `[]`.
          (do
            (emit-sub-skip! query-id query-v frame-id sub-meta [])
            @last-result)
          ;; Capture the prior cells BEFORE the recompute so the
          ;; `:sub/run` attribution can report value-change
          ;; against the last computed value. Layer-1 has no upstream
          ;; sub inputs, so `input-signals` is `[]` and `prev-in-vals`
          ;; is irrelevant to cause-sub resolution (a layer-1 recompute
          ;; is driven by an app-db path change, never a sub cascade).
          ;;
          ;; Pass `unset` for `prev-value` on the run that
          ;; allocated the cache slot (the input cell `last-db` is still
          ;; the `::unset` sentinel here — pre-vreset). `last-result`
          ;; starts at `nil` (not `::unset`) so it cannot serve as the
          ;; first-run discriminator alone; keying on `last-db` keeps
          ;; the `::unset → unset` projection well-defined even when the
          ;; first cached value happens to be `nil`. The
          ;; `validate-and-trace` emit then stamps `:rf.sub/first-run?`
          ;; from `(= unset prev-value)`.
          (let [prev-result (if (= ::unset @last-db) unset @last-result)
                computed    (validate-and-trace
                              body-fn (list db) query-id query-v
                              frame-id [] sub-meta prev-result unset false)]
            (vreset! last-db db)
            (vreset! last-result computed)
            computed))))))

(defn make-layer-n-single-input-memoised-body
  "Specialised memo wrapper for layer-2 subs with a single `:<-` input
  (the dominant layer-2 shape).
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
  inside the validate/trace bracket."
  [body-fn query-id query-v frame-id input-signals sub-meta]
  (let [last-v0     (volatile! ::unset)
        last-result (volatile! nil)]
    (fn [v0]
      (when body-fn
        (if (= @last-v0 v0)
          ;; Memo hit — see `make-layer-1-memoised-body` for the
          ;; `:rf.sub/skip` rationale. `:input-paths-unchanged`
          ;; carries the upstream sub query-vector(s) whose values were
          ;; stable; layer-2+ subs name their inputs by `[query-id args]`
          ;; rather than db-paths.
          (do
            (emit-sub-skip! query-id query-v frame-id sub-meta (vec input-signals))
            @last-result)
          ;; Capture prior cells BEFORE the recompute for the `:sub/run`
          ;; attribution. `prev-in-vals` is the last-seen
          ;; single input value in singleton-list shape (matching the
          ;; `(list v0)` `in-vals` form) so `changed-cause-sub` can diff
          ;; it positionally against `input-signals`; the `::unset`
          ;; sentinel on first recompute leaves `:cause-sub` nil.
          ;;
          ;; Pass `unset` for `prev-value` on the run that
          ;; allocated the cache slot (the input cell `last-v0` is still
          ;; the `::unset` sentinel here). `last-result` is `nil` then
          ;; too but keying on `last-v0` keeps the discriminator well-
          ;; defined regardless of the first cached value's shape.
          (let [prev-result  (if (= ::unset @last-v0) unset @last-result)
                prev-v0      @last-v0
                prev-in-vals (if (= unset prev-v0)
                               unset
                               (list prev-v0))
                computed     (validate-and-trace
                               body-fn (list v0) query-id query-v
                               frame-id input-signals sub-meta
                               prev-result prev-in-vals false)]
            (vreset! last-v0 v0)
            (vreset! last-result computed)
            computed))))))

(defn make-layer-n-memoised-body
  "Memo wrapper for layer-2+ subs with two or more inputs — and for
  PARAMETRIC subs of ANY input count (including one or zero realized
  inputs). Varargs — the input arity matches the count of input
  query-vectors, and the wrapper compares the seq of input values
  against the last-seen seq.

  Returns a `(fn [& in-vals])`. When `body-fn` is nil the wrapper
  yields nil on every call without touching the memo cells.

  `vector-inputs?` (optional — defaults false): forwarded to
  `validate-and-trace`. True for a parametric `input-fn` sub so the body
  always receives a VECTOR of input values (producer order) even at one
  input — the Spec 006 §Single input contract (`(fn [[a] q] ...)`). False
  (or omitted) for a static `:<-` multi-input sub so the v1
  convention holds.

  See `make-layer-1-memoised-body` for the layer-1 specialisation and
  `make-layer-n-single-input-memoised-body` for the static single-`:<-`
  specialisation."
  ([body-fn query-id query-v frame-id input-signals sub-meta]
   (make-layer-n-memoised-body
     body-fn query-id query-v frame-id input-signals sub-meta false))
  ([body-fn query-id query-v frame-id input-signals sub-meta vector-inputs?]
  (let [last-in-vals (volatile! ::unset)
        last-result  (volatile! nil)]
    (fn [& in-vals]
      (when body-fn
        (if (= @last-in-vals in-vals)
          ;; Memo hit — see `make-layer-1-memoised-body` for the
          ;; `:rf.sub/skip` rationale. `:input-paths-unchanged`
          ;; carries every upstream `:<-` query-vector whose value was
          ;; stable (the varargs path has ≥2 inputs and the memo
          ;; compare is whole-seq `=`, so every input was stable).
          (do
            (emit-sub-skip! query-id query-v frame-id sub-meta (vec input-signals))
            @last-result)
          ;; Capture prior cells BEFORE the recompute for the `:sub/run`
          ;; attribution. `prev-in-vals` is the last-seen
          ;; input-value seq (parallel to `input-signals`), which
          ;; `changed-cause-sub` diffs positionally to name the upstream
          ;; sub that cascaded. `::unset` on first recompute → nil cause.
          ;;
          ;; Pass `unset` for `prev-value` on the run that
          ;; allocated the cache slot (the input cell `last-in-vals` is
          ;; still the `::unset` sentinel here). Keying on `last-in-vals`
          ;; rather than `last-result` so a sub whose first cached value
          ;; is `nil` still flags as `:first-run?` true.
          (let [prev-result  (if (= ::unset @last-in-vals) unset @last-result)
                prev-in-vals @last-in-vals
                computed     (validate-and-trace
                               body-fn in-vals query-id query-v
                               frame-id input-signals sub-meta
                               prev-result prev-in-vals vector-inputs?)]
            (vreset! last-in-vals in-vals)
            (vreset! last-result computed)
            computed)))))))
