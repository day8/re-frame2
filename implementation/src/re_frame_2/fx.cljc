(ns re-frame-2.fx
  "Effect interpreter (do-fx) and reserved fx-id table.

  Per Spec 002 §`:fx` ordering and atomicity guarantees:
    1. :db commits first, atomically.
    2. :fx entries process in source order.
    3. Each fx-handler runs synchronously before the next entry begins.
    4. Subscriptions observe the post-:db state.

  Reserved fx-ids (per Conventions §Reserved fx-ids):
    :dispatch         — runtime, intra-frame dispatch (back of router queue)
    :dispatch-later   — runtime, delayed dispatch
    :raise            — machine-internal (machine handler routes locally)
    :spawn            — machine-internal: declarative :invoke desugar
    :destroy-machine  — machine-internal: counterpart to :spawn on state exit
    :rf.fx/reg-flow   — runtime, register a flow (Spec 013)
    :rf.fx/clear-flow — runtime, clear a flow"
  (:require [re-frame-2.registrar :as registrar]
            [re-frame-2.interop :as interop]
            [re-frame-2.trace :as trace]))

;; ---- registration ---------------------------------------------------------

(defn reg-fx
  "Register an fx handler.

  metadata may contain:
    :doc        one-sentence what-and-why
    :spec       Malli schema for the args (per Spec 010)
    :platforms  set of #{:client :server}; default #{:client :server}"
  [id metadata-or-handler & maybe-handler]
  (let [[meta handler-fn]
        (if (map? metadata-or-handler)
          [metadata-or-handler (first maybe-handler)]
          [{} metadata-or-handler])]
    (registrar/register! :fx id (assoc meta :handler-fn handler-fn))
    id))

(defn unregister-fx [id]
  (registrar/unregister! :fx id))

;; ---- the platform predicate -----------------------------------------------

(defn- fx-runs-on-platform? [meta active-platform]
  (let [platforms (:platforms meta #{:client :server})]
    (contains? platforms active-platform)))

;; ---- do-fx ----------------------------------------------------------------

(declare dispatch-fx-handler)

(defn- resolve-fx-with-overrides
  "Apply fx-id overrides per Spec 002 §Per-frame and per-call overrides.
  If the override target is registered, use it. If not, emit
  :rf.error/override-fallthrough and fall back to the original fx-id.
  Returns the resolved fx-id."
  [original-fx-id overrides]
  (if-let [override-target (get overrides original-fx-id)]
    (if (registrar/lookup :fx override-target)
      (do
        (trace/emit! :fx :rf.fx/override-applied
                     {:from original-fx-id :to override-target})
        override-target)
      (do
        (trace/emit-error! :rf.error/override-fallthrough
                           {:failing-id     original-fx-id
                            :overrides-map  overrides
                            :looked-up-id   override-target
                            :reason         (str "Override redirected `"
                                                 original-fx-id
                                                 "` to `"
                                                 override-target
                                                 "`, which is not registered. Using the registered `"
                                                 original-fx-id
                                                 "` instead.")
                            :recovery       :replaced-with-default})
        original-fx-id))
    original-fx-id))

(defn- handle-one-fx
  "Process one [fx-id args] pair. Falls into one of three buckets:
   1. Reserved fx-id with runtime handling (:dispatch, :dispatch-later, :rf.fx/...).
   2. User-registered fx looked up via registrar.
   3. Unknown fx-id — emit :rf.error/no-such-fx and continue."
  [frame-id [original-fx-id args] active-platform overrides]
  (let [fx-id (resolve-fx-with-overrides original-fx-id overrides)]
   (case fx-id
    :dispatch
    ;; Append to back of the frame's router queue.
    (when-let [dispatch! (resolve 're-frame-2.router/dispatch!)]
      ((deref dispatch!) args {:frame frame-id}))

    :dispatch-later
    (let [{:keys [ms event]} args]
      (interop/set-timeout!
        (fn []
          (when-let [dispatch! (resolve 're-frame-2.router/dispatch!)]
            ((deref dispatch!) event {:frame frame-id})))
        ms))

    :rf.fx/reg-flow
    (when-let [reg-flow! (resolve 're-frame-2.flows/reg-flow-fx!)]
      ((deref reg-flow!) args))

    :rf.fx/clear-flow
    (when-let [clear-flow! (resolve 're-frame-2.flows/clear-flow-fx!)]
      ((deref clear-flow!) args))

    :spawn
    ;; Per Spec 005 §Declarative :invoke (sugar over spawn): emitted by
    ;; the machine when entering an :invoke-bearing state. The runtime
    ;; layer here just emits a trace so observers see the spawn intent;
    ;; full actor lifecycle (auto-start, message routing, supervision)
    ;; is the user's responsibility for now.
    (trace/emit! :machine :rf.machine/spawned
                 {:frame frame-id
                  :machine-id (:machine-id args)
                  :id-prefix  (:id-prefix args)
                  :start      (:start args)
                  :on-spawn   (:on-spawn args)})

    :destroy-machine
    ;; Per Spec 005: emitted on exit from an :invoke-bearing state.
    (trace/emit! :machine :rf.machine/destroyed
                 {:frame    frame-id
                  :actor-id args})

    ;; Default: user-registered fx.
    (if-let [meta (registrar/lookup :fx fx-id)]
      (if (fx-runs-on-platform? meta active-platform)
        (try
          ((:handler-fn meta) {:frame frame-id} args)
          (catch #?(:clj Throwable :cljs :default) e
            (let [msg (#?(:clj .getMessage :cljs .-message) e)]
              (trace/emit-error! :rf.error/fx-handler-exception
                                 {:failing-id        fx-id
                                  :fx-id             fx-id
                                  :fx-args           args
                                  :frame             frame-id
                                  :exception         e
                                  :exception-message msg
                                  :reason            (str "Effect handler `" fx-id "` threw: " msg ".")
                                  :recovery          :no-recovery}))))
        (trace/emit! :warning :rf.fx/skipped-on-platform
                     {:fx-id                fx-id
                      :frame                frame-id
                      :platform             active-platform
                      :registered-platforms (:platforms meta)
                      :recovery             :skipped}))
      (trace/emit-error! :rf.error/no-such-fx
                         {:fx-id fx-id :frame frame-id
                          :recovery :no-recovery})))))

(defn do-fx
  "Walk the :fx vector in source order. Per Spec 002 §`:fx` ordering rule 3:
  each entry's handler returns synchronously before the next begins.
  Errors trace independently and the walk continues (rule 4: one bad
  fx does not halt the rest).

  Per Spec 002 §Per-frame and per-call overrides: an fx-id override map
  may be provided. Each [fx-id args] is rewritten through that map
  before lookup."
  ([frame-id fx-vec active-platform]
   (do-fx frame-id fx-vec active-platform {}))
  ([frame-id fx-vec active-platform overrides]
   (doseq [pair fx-vec]
     (when (and (vector? pair) (seq pair))
       (handle-one-fx frame-id pair active-platform overrides)))
   (trace/emit! :event :event/do-fx {:frame frame-id})))
