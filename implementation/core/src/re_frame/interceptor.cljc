(ns re-frame.interceptor
  "Interceptor chain runtime. Per Spec 002 (interceptors are part of the
  drain-loop pseudocode) and Spec 001 (the registration grammar).

  An interceptor is a map with optional :before and :after fns:
    {:id     :my-thing
     :before (fn [context] new-context)   ;; runs before handler
     :after  (fn [context] new-context)}  ;; runs after handler

  Built via the `->interceptor` macro (`re-frame.core`, captures the
  definition-site `:source-coord` for jump-to-source per Spec 001) or
  the `->interceptor*` fn (this ns; HoF / programmatic, no coord
  capture). A captured `:source-coord` rides the error-record so
  tooling can jump to the throwing interceptor's source.

  The 'context' is a map with :coeffects (inputs) and :effects (outputs).
  The chain runs :before in order, then the handler, then :after in
  reverse order."
  (:require [re-frame.interop :as interop]))

#?(:clj (set! *warn-on-reflection* true))

(defn ->interceptor*
  "Build an interceptor map from kwargs — the plain, runtime-callable
  fn form of the `->interceptor` macro (per Conventions §`*`-suffix
  naming). HoF / programmatic / REPL callers reach this directly; the
  `rf/->interceptor` macro is the ergonomic surface that ALSO captures
  the definition-site `:source-coord` from `(meta &form)` (so the Xray
  Epoch INTERCEPTOR row can jump to source). This fn captures no
  call-site — pass `:source-coord` explicitly if you have one (the
  macro does exactly that).

  Kwargs:
    :id            keyword name (default `:unnamed`); appears in error
                   traces.
    :before        `(fn [context] new-context)` — runs before the handler.
                   Read inputs via `get-coeffect`; write outputs via
                   `assoc-coeffect` / `assoc-effect`.
    :after         `(fn [context] new-context)` — runs after the handler,
                   in reverse declaration order.
    :source-coord  optional `{:ns :file :line :column}` source-coord map
                   (per Spec 001 §Source-coordinate capture). Stamped by
                   the `->interceptor` macro from `(meta &form)`; rides
                   the error-record so tooling renders a jump-to-source
                   chip when the interceptor throws. Absent on the plain
                   fn path unless the caller supplies one.

  The `context` map carries:
    `:coeffects` — input data: `:db` (current app-db value), `:event`
                   (the dispatched event vector), and the facts the
                   handler declared via `:rf.cofx/requires` (delivered
                   flat).
    `:effects`   — output data the chain accumulates: `:db` (next
                   app-db value), `:fx` (the vector of `[fx-id args]`
                   pairs the runtime walks after the chain).

  See also: `->interceptor` (the macro form, `re-frame.core`),
  `get-coeffect`, `assoc-coeffect`, `get-effect`, `assoc-effect`,
  `:rf.cofx/requires` (the declared-coeffect key), `:rf.interceptor/path`
  (the standard path interceptor, a registered factory referenced as
  `[:rf.interceptor/path <path-vector>]`). An interceptor IS the public
  `context -> context` primitive — full-context work that a
  `reg-event-ctx` handler once expressed lives here (EP-0018)."
  [& {:keys [id before after source-coord] :as opts}]
  (cond-> (assoc opts :id (or id :unnamed))
    before       (assoc :before before)
    after        (assoc :after after)
    source-coord (assoc :source-coord source-coord)))

;; ---- context plumbing -----------------------------------------------------

(defn get-coeffect
  "Read from the context's `:coeffects` map.

  Arities: `(get-coeffect ctx)` returns the whole coeffects map;
  `(get-coeffect ctx k)` returns the value at key `k` (nil if absent);
  `(get-coeffect ctx k not-found)` returns `not-found` when absent.

  See also: `assoc-coeffect`, `update-coeffect`."
  ([context] (:coeffects context))
  ([context k] (get (:coeffects context) k))
  ([context k not-found] (get (:coeffects context) k not-found)))

(defn assoc-coeffect
  "Set the value at `k` in the context's `:coeffects` map. Returns the
  updated context. Use from a `:before` interceptor when injecting a
  coeffect into the chain. See also: `get-coeffect`, `update-coeffect`."
  [context k v]
  (assoc-in context [:coeffects k] v))

(defn update-coeffect
  "Apply `f` (with optional trailing `args`) to the value at `k` in the
  context's `:coeffects` map. Returns the updated context. See also:
  `assoc-coeffect`, `get-coeffect`."
  [context k f & args]
  (apply update-in context [:coeffects k] f args))

(defn get-effect
  "Read from the context's `:effects` map.

  Arities: `(get-effect ctx)` returns the whole effects map;
  `(get-effect ctx k)` returns the value at key `k`;
  `(get-effect ctx k not-found)` returns `not-found` when absent.

  See also: `assoc-effect`."
  ([context] (:effects context))
  ([context k] (get (:effects context) k))
  ([context k not-found] (get (:effects context) k not-found)))

(defn assoc-effect
  "Set the value at `k` in the context's `:effects` map. Returns the
  updated context. Use from a handler-wrapper interceptor (or an
  `:after`) to publish an effect the runtime will walk. The top-level
  effect-map is closed — `#{:db :rf.db/runtime :fx}` are honoured (per
  migration M-8 + EP-0001); app handlers use `:db` / `:fx`, while
  `:rf.db/runtime` is reserved by convention for framework-authority
  writers. See also: `get-effect`, `assoc-coeffect`."
  [context k v]
  (assoc-in context [:effects k] v))

;; ---- chain execution ------------------------------------------------------

(defn- record-error
  "Append `err` to `:rf/interceptor-errors` (preserving prior errors) and
  set `:rf/interceptor-error` to the FIRST error only. The singleton key
  is the original cause — tracing code that reads it gets the root failure
  without surprise. The vector key carries every error in the order they
  occurred so downstream tooling can surface the full chain of failures."
  [context err]
  (let [existing-first (:rf/interceptor-error context)]
    (cond-> (update context :rf/interceptor-errors (fnil conj []) err)
      (nil? existing-first) (assoc :rf/interceptor-error err))))

(defn- error-record
  "Build the per-stage error record stamped into the context by
  `record-error`. Carries `:phase` (`:before` / `:after`), `:id` (the
  interceptor's `:id`), and the raw `:exception`. The router's
  `classify-pipeline-exception` reads `:id` to attribute the throw to the
  event handler (a handler-wrapping `:id`) or a user interceptor.

  No interceptor carries `:rf/cofx-id`: coeffect delivery happens during
  context assembly (`re-frame.cofx`, BEFORE the chain runs), so a
  coeffect-supplier throw is handled there rather than on this record.

  The record carries the throwing interceptor's
  `:source-coord` when one was captured (the `->interceptor` macro
  stamps it from `(meta &form)`). The router threads it onto the
  `:rf.error/interceptor-exception` trace so the Xray Epoch INTERCEPTOR
  row renders a jump-to-source chip (parity with EVENT HANDLER /
  SUBSCRIPTIONS / VIEWS). Absent on the fn-path / framework interceptors
  (`:rf.interceptor/path`) — they have no user definition site to jump to; the
  slot's absence preserves the singleton-vs-vector equality invariant
  (`:rf/interceptor-error` ≡ first of `:rf/interceptor-errors`) for the
  common case."
  [phase interceptor exception]
  (cond-> {:phase phase :id (:id interceptor) :exception exception}
    (:source-coord interceptor) (assoc :source-coord (:source-coord interceptor))))

(defn- invoke-before [context interceptor]
  ;; Short-circuit: once any :before has failed, skip downstream :before
  ;; stages. This mirrors the :rf/skip-handler? pattern (events.cljc /
  ;; cofx.cljc) — partial context shouldn't be propagated to handlers or
  ;; subsequent validation. The :after pass still runs in execute-chain
  ;; so interceptors can perform teardown.
  (if (:rf/interceptor-error context)
    context
    (if-let [f (:before interceptor)]
      (try
        (or (f context) context)
        (catch #?(:clj Throwable :cljs :default) e
          (record-error context (error-record :before interceptor e))))
      context)))

(def ^:private ctx-delta-segments
  "Top-level ctx segments diffed by `compute-ctx-delta`. We compare the
  user-meaningful contents (`:coeffects` and `:effects`) — the only
  slots an interceptor's `:after` legitimately mutates per Spec 002
  §The context map — and skip framework bookkeeping
  (`:rf/interceptor-error` etc.) so the captured delta surfaces what the
  interceptor DID, not the runtime's own scaffolding."
  [:coeffects :effects])

(defn- segment-delta
  "Project changes between `before` and `after` for one segment map.
  Returns `{:added {k v ...} :changed {k {:before _ :after _}} :removed
  {k v ...}}` with each sub-map elided when empty. Returns nil when
  the segment didn't change at all so the caller can drop the slot."
  [before after]
  (let [b   (or before {})
        a   (or after {})
        ks-b (set (keys b))
        ks-a (set (keys a))
        added   (reduce (fn [acc k]
                          (if (contains? ks-b k) acc (assoc acc k (get a k))))
                        {} ks-a)
        removed (reduce (fn [acc k]
                          (if (contains? ks-a k) acc (assoc acc k (get b k))))
                        {} ks-b)
        ;; Keys present in BOTH maps — values are compared via `=`.
        ;; Membership via `contains?`, NOT the set as a predicate: a set
        ;; returns the element, so a key literally `false`/`nil` would test
        ;; falsy and be dropped from `common`, silently omitting a changed
        ;; value under a falsy key from the `:changed` diff.
        common  (filter #(contains? ks-b %) ks-a)
        changed (reduce (fn [acc k]
                          (let [vb (get b k) va (get a k)]
                            (if (= vb va)
                              acc
                              (assoc acc k {:before vb :after va}))))
                        {} common)]
    (when (or (seq added) (seq removed) (seq changed))
      (cond-> {}
        (seq added)   (assoc :added added)
        (seq changed) (assoc :changed changed)
        (seq removed) (assoc :removed removed)))))

(defn- compute-ctx-delta
  "Project the per-`:after`-interceptor mutation surface as data. Pure
  fn over the before/after context snapshots; idempotent and JVM-
  portable. Returns nil when the `:after` was a no-op (returned the
  context unchanged) so the caller can skip stashing the row entirely
  — the Xray AFTER INTERCEPTORS section renders nothing for no-op
  interceptors."
  [before after]
  (let [slots (reduce (fn [acc seg]
                        (if-let [d (segment-delta (get before seg)
                                                  (get after seg))]
                          (assoc acc seg d)
                          acc))
                      {}
                      ctx-delta-segments)]
    (when (seq slots) slots)))

(defn ^:no-doc framework-default-interceptor?
  "True iff `x` is a framework-installed scaffolding interceptor VALUE —
  the framework's own appended handler-wrapper, stamped `:rf/default?
  true` (see `re-frame.events/register-event!`). Two callers share this
  one predicate:

    - `invoke-after` (this ns) skips its ctx-delta capture for these —
      they are framework machinery, not user-meaningful interceptors on
      the Xray AFTER INTERCEPTORS surface.
    - `re-frame.interceptor-registry/resolve-chain` lets this ONE inline
      value pass through a chain untouched (chains are reference-only and
      reject every other inline value). `interceptor-registry` already
      `:require`s this ns, so it calls here rather than keeping a second
      copy.

  No interceptor carries `:rf/cofx-id` (coeffect delivery happens during
  context assembly), so the predicate is the `:rf/default?` check alone.

  The `(map? x)` guard makes the predicate total over a chain entry of
  ANY shape (the registry calls it on raw chain entries that may be
  references, not only resolved maps); pure, no allocation when `x`
  carries no flag."
  [x]
  (and (map? x) (true? (:rf/default? x))))

(defn- invoke-after [context interceptor]
  (if-let [f (:after interceptor)]
    (let [;; Dev-only ctx-delta capture. The snapshot ride
          ;; the same `interop/debug-enabled?` gate as the trace surface
          ;; so production CLJS bundles DCE the capture. Framework
          ;; defaults (`:rf/default?`) are skipped — they are not
          ;; user-meaningful interceptors on the AFTER INTERCEPTORS surface.
          capture? (and interop/debug-enabled?
                        (not (framework-default-interceptor? interceptor)))
          before   (when capture? context)]
      (try
        (let [after (or (f context) context)]
          (if capture?
            (if-let [delta (compute-ctx-delta before after)]
              (update after :rf/icpt-after-deltas (fnil conj [])
                      {:rf.icpt/id        (:id interceptor)
                       :rf.icpt/ctx-delta delta})
              after)
            after))
        (catch #?(:clj Throwable :cljs :default) e
          (record-error context (error-record :after interceptor e)))))
    context))

(defn execute-chain
  "Run the interceptor chain. Returns the final context.

  Order:
    1. :before of each interceptor in declaration order.
    2. The handler (already wrapped as the last :before in the chain by
       the kind-specific factory; see events.cljc / subs.cljc).
    3. :after of each interceptor in REVERSE declaration order.

  Failures during :before or :after are captured in the context:
    - `:rf/interceptor-error`  — the FIRST error (original cause; what
                                 tracing code reads).
    - `:rf/interceptor-errors` — vector of ALL errors in order of
                                 occurrence (preserves later errors that
                                 would otherwise be overwritten).

  Once a :before fails, subsequent :before stages are short-circuited
  (the partial context would be invalid input). The :after pass still
  runs in full so interceptors can do their cleanup. The drain loop
  reads :rf/interceptor-error after the chain ends and emits
  :rf.error/handler-exception (or similar)."
  [interceptors initial-context]
  ;; Apply :before stages in order (short-circuits on first error).
  (let [ctx-after-befores (reduce invoke-before initial-context interceptors)]
    ;; Apply :after stages in reverse order (always runs for teardown).
    ;; Indexed-vec backward walk avoids the per-dispatch lazy-seq allocation
    ;; `(reverse interceptors)` would produce. `interceptors` is the
    ;; declaration-order vector built by the registration factories.
    (loop [i   (dec (count interceptors))
           ctx ctx-after-befores]
      (if (neg? i)
        ctx
        (recur (dec i) (invoke-after ctx (nth interceptors i)))))))
