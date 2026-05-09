(ns re-frame.fx
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
  (:require [re-frame.registrar :as registrar]
            [re-frame.interop :as interop]
            [re-frame.late-bind :as late-bind]
            [re-frame.source-coords :as source-coords]
            [re-frame.trace :as trace]))

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
    (registrar/register! :fx id (assoc (source-coords/merge-coords meta)
                                       :handler-fn handler-fn))
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

(defn- emit-handled!
  "Emit a `:rf.fx/handled` success trace for a dispatched fx. Per Spec-Schemas
  §`:rf/epoch-record` `:effects` projection: every dispatched fx surfaces
  one entry, with `:outcome :ok` for the success path. The epoch projection
  consumes this trace; pair tools route off it without re-folding the raw
  trace stream."
  [fx-id args frame-id]
  (trace/emit! :fx :rf.fx/handled
               {:fx-id   fx-id
                :fx-args args
                :frame   frame-id}))

(defn- handle-one-fx
  "Process one [fx-id args] pair. Falls into one of three buckets:
   1. Reserved fx-id with runtime handling (:dispatch, :dispatch-later, :rf.fx/...).
   2. User-registered fx looked up via registrar.
   3. Unknown fx-id — emit :rf.error/no-such-fx and continue.

  Successful dispatches emit `:rf.fx/handled` so the epoch `:effects`
  projection records one entry per dispatched fx (per Spec-Schemas
  §`:rf/epoch-record`). Warning and error paths emit their existing
  traces (`:rf.fx/skipped-on-platform`, `:rf.error/fx-handler-exception`,
  `:rf.error/no-such-fx`) and do NOT additionally emit `:rf.fx/handled`,
  so the projection stays one-entry-per-fx.

  `origin-event` (when supplied) is the originating event vector, threaded
  through to the user-registered fx handler's ctx so handlers like
  `:rf.http/managed` (Spec 014 §Reply addressing) can address replies back
  to the originator without a separate cofx-injection step."
  [frame-id [original-fx-id args] active-platform overrides origin-event]
  (let [fx-id (resolve-fx-with-overrides original-fx-id overrides)]
   (case fx-id
    :dispatch
    (do
      ;; Append to back of the frame's router queue.
      (when-let [dispatch! (late-bind/get-fn :router/dispatch!)]
        (dispatch! args {:frame frame-id}))
      (emit-handled! fx-id args frame-id))

    :dispatch-later
    (let [{:keys [ms event]} args]
      (interop/set-timeout!
        (fn []
          (when-let [dispatch! (late-bind/get-fn :router/dispatch!)]
            (dispatch! event {:frame frame-id})))
        ms)
      (emit-handled! fx-id args frame-id))

    :rf.fx/reg-flow
    ;; Per Spec 013 — flows are frame-scoped. The flow registers against
    ;; the dispatching frame.
    (do
      (when-let [reg-flow! (late-bind/get-fn :flows/reg-flow-fx!)]
        (reg-flow! args {:frame frame-id}))
      (emit-handled! fx-id args frame-id))

    :rf.fx/clear-flow
    (do
      (when-let [clear-flow! (late-bind/get-fn :flows/clear-flow-fx!)]
        (clear-flow! args {:frame frame-id}))
      (emit-handled! fx-id args frame-id))

    :spawn
    ;; Per Spec 005 §Declarative :invoke (sugar over spawn): emitted by
    ;; the machine when entering an :invoke-bearing state. The runtime
    ;; layer here just emits a trace so observers see the spawn intent;
    ;; full actor lifecycle (auto-start, message routing, supervision)
    ;; is the user's responsibility for now.
    (do
      (trace/emit! :machine :rf.machine/spawned
                   {:frame frame-id
                    :machine-id (:machine-id args)
                    :id-prefix  (:id-prefix args)
                    :start      (:start args)
                    :on-spawn   (:on-spawn args)})
      (emit-handled! fx-id args frame-id))

    :destroy-machine
    ;; Per Spec 005: emitted on exit from an :invoke-bearing state.
    (do
      (trace/emit! :machine :rf.machine/destroyed
                   {:frame    frame-id
                    :actor-id args})
      (emit-handled! fx-id args frame-id))

    ;; Default: user-registered fx.
    (if-let [meta (registrar/lookup :fx fx-id)]
      (if (fx-runs-on-platform? meta active-platform)
        (let [ok? (try
                    ((:handler-fn meta) (cond-> {:frame frame-id}
                                          origin-event (assoc :event origin-event))
                                        args)
                    true
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
                                            :recovery          :no-recovery}))
                      false))]
          (when ok?
            (emit-handled! fx-id args frame-id)))
        (trace/emit! :warning :rf.fx/skipped-on-platform
                     {:fx-id                fx-id
                      :frame                frame-id
                      :fx-args              args
                      :platform             active-platform
                      :registered-platforms (:platforms meta)
                      :recovery             :skipped}))
      (trace/emit-error! :rf.error/no-such-fx
                         {:fx-id    fx-id
                          :fx-args  args
                          :frame    frame-id
                          :recovery :no-recovery})))))

(defn do-fx
  "Walk the :fx vector in source order. Per Spec 002 §`:fx` ordering rule 3:
  each entry's handler returns synchronously before the next begins.
  Errors trace independently and the walk continues (rule 4: one bad
  fx does not halt the rest).

  Per Spec 002 §Per-frame and per-call overrides: an fx-id override map
  may be provided. Each [fx-id args] is rewritten through that map
  before lookup.

  The optional 5-arity passes the originating event vector through to
  user-registered fx handlers as `:event` on their ctx — needed by Spec
  014 §Reply addressing (the fx captures the originating event-id from
  the dispatch envelope's cofx)."
  ([frame-id fx-vec active-platform]
   (do-fx frame-id fx-vec active-platform {} nil))
  ([frame-id fx-vec active-platform overrides]
   (do-fx frame-id fx-vec active-platform overrides nil))
  ([frame-id fx-vec active-platform overrides origin-event]
   (doseq [pair fx-vec]
     (when (and (vector? pair) (seq pair))
       (handle-one-fx frame-id pair active-platform overrides origin-event)))
   (trace/emit! :event :event/do-fx {:frame frame-id})))
