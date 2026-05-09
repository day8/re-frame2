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

(defn- invoke-before [context interceptor]
  (if-let [f (:before interceptor)]
    (try
      (or (f context) context)
      (catch #?(:clj Throwable :cljs :default) e
        (assoc context :rf/interceptor-error
               {:phase :before :id (:id interceptor) :exception e})))
    context))

(defn- invoke-after [context interceptor]
  (if-let [f (:after interceptor)]
    (try
      (or (f context) context)
      (catch #?(:clj Throwable :cljs :default) e
        (assoc context :rf/interceptor-error
               {:phase :after :id (:id interceptor) :exception e})))
    context))

(defn execute-chain
  "Run the interceptor chain. Returns the final context.

  Order:
    1. :before of each interceptor in declaration order.
    2. The handler (already wrapped as the last :before in the chain by
       the kind-specific factory; see events.cljc / subs.cljc).
    3. :after of each interceptor in REVERSE declaration order.

  Failures during :before or :after are captured in the context under
  :rf/interceptor-error and the chain continues — so :after stages can
  do their cleanup. The drain loop reads :rf/interceptor-error after
  the chain ends and emits :rf.error/handler-exception (or similar)."
  [interceptors initial-context]
  (let [;; Apply :before stages in order.
        after-before (reduce invoke-before initial-context interceptors)
        ;; Apply :after stages in reverse order.
        after-after  (reduce invoke-after after-before (reverse interceptors))]
    after-after))
