(ns re-frame.interceptor
  "Interceptor chain runtime. Per Spec 002 (interceptors are part of the
  drain-loop pseudocode) and Spec 001 (the registration grammar).

  An interceptor is a map with optional :before and :after fns:
    {:id     :my-thing
     :before (fn [context] new-context)   ;; runs before handler
     :after  (fn [context] new-context)}  ;; runs after handler

  The 'context' is a map with :coeffects (inputs) and :effects (outputs).
  The chain runs :before in order, then the handler, then :after in
  reverse order.")

#?(:clj (set! *warn-on-reflection* true))

(defn ->interceptor
  "Build an interceptor map from kwargs. The primitive entry point for
  custom interceptors.

  Kwargs:
    :id      keyword name (default `:unnamed`); appears in error traces.
    :before  `(fn [context] new-context)` — runs before the handler.
             Read inputs via `get-coeffect`; write outputs via
             `assoc-coeffect` / `assoc-effect`.
    :after   `(fn [context] new-context)` — runs after the handler,
             in reverse declaration order.

  The `context` map carries:
    `:coeffects` — input data: `:db` (current app-db value), `:event`
                   (the dispatched event vector), and any cofx injected
                   via `inject-cofx`.
    `:effects`   — output data the chain accumulates: `:db` (next
                   app-db value), `:fx` (the vector of `[fx-id args]`
                   pairs the runtime walks after the chain).

  Example:

      (def log-event
        (rf/->interceptor
          :id     :log-event
          :before (fn [ctx]
                    (println \"event:\" (rf/get-coeffect ctx :event))
                    ctx)))

      (rf/reg-event-db :foo
        [log-event]
        (fn [db _] db))

  See also: `get-coeffect`, `assoc-coeffect`, `get-effect`,
  `assoc-effect`, `inject-cofx`, `path` / `unwrap` (the std interceptors
  v2 ships), `reg-event-ctx` (full-context handler)."
  [& {:keys [id before after]}]
  (cond-> {:id (or id :unnamed)}
    before (assoc :before before)
    after  (assoc :after after)))

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
  effect-map is closed: only `:db` and `:fx` are honoured per migration
  M-8. See also: `get-effect`, `assoc-coeffect`."
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
          (record-error context
                        {:phase :before :id (:id interceptor) :exception e})))
      context)))

(defn- invoke-after [context interceptor]
  (if-let [f (:after interceptor)]
    (try
      (or (f context) context)
      (catch #?(:clj Throwable :cljs :default) e
        (record-error context
                      {:phase :after :id (:id interceptor) :exception e})))
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
