(ns re-frame.interceptor
  "Interceptor chain runtime. Per Spec 002 (interceptors are part of the
  drain-loop pseudocode) and Spec 001 (the registration grammar).

  An interceptor is a map with optional :before and :after fns:
    {:id     :my-thing
     :before (fn [context] new-context)   ;; runs before handler
     :after  (fn [context] new-context)}  ;; runs after handler

  The 'context' is a map with :coeffects (inputs) and :effects (outputs).
  The chain runs :before in order, then the handler, then :after in
  reverse order (the standard interceptor pattern from Pedestal / re-frame v1).")

(defn ->interceptor
  "Build an interceptor map from kwargs. The primitive — users compose
  custom before/after work via this rather than via stdlib helpers
  (which v2 has trimmed)."
  [& {:keys [id before after]}]
  (cond-> {:id (or id :unnamed)}
    before (assoc :before before)
    after  (assoc :after after)))

;; ---- context plumbing -----------------------------------------------------

(defn get-coeffect
  ([context] (:coeffects context))
  ([context k] (get (:coeffects context) k))
  ([context k not-found] (get (:coeffects context) k not-found)))

(defn assoc-coeffect [context k v]
  (assoc-in context [:coeffects k] v))

(defn update-coeffect [context k f & args]
  (apply update-in context [:coeffects k] f args))

(defn get-effect
  ([context] (:effects context))
  ([context k] (get (:effects context) k))
  ([context k not-found] (get (:effects context) k not-found)))

(defn assoc-effect [context k v]
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
  (let [;; Apply :before stages in order (short-circuits on first error).
        ctx-after-befores (reduce invoke-before initial-context interceptors)
        ;; Apply :after stages in reverse order (always runs for teardown).
        ctx-after-afters  (reduce invoke-after ctx-after-befores (reverse interceptors))]
    ctx-after-afters))
