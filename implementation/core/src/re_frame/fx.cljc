(ns re-frame.fx
  "Effect registration, the `:fx` walk (`do-fx`) and the reserved fx-ids
  `:dispatch` and `:dispatch-later` (Spec 002 §`:fx` ordering and atomicity
  guarantees; Conventions §Reserved fx-ids). The machine fx-ids are
  registered by `re-frame.machines` through `reg-fx`, so an app without that
  artefact carries none of their code."
  (:require [re-frame.registrar :as rf.registrar]
            [re-frame.error :as rf.error]
            [re-frame.frame :as rf.frame]
            [re-frame.interop :as rf.interop]
            [re-frame.late-bind :as rf.late-bind]
            [re-frame.reg-meta :as rf.reg-meta]
            [re-frame.classification :as rf.classification]
            [re-frame.performance :as rf.performance
             #?@(:cljs [:include-macros true])]
            [re-frame.source-coords :as rf.source-coords]
            [re-frame.trace :as rf.trace
             #?@(:cljs [:include-macros true])]))

#?(:clj (set! *warn-on-reflection* true))

;; ---- registration ---------------------------------------------------------

(defn validate-registration-meta!
  "The registration-time metadata checks `reg-fx` and `reg-cofx` share, also
  run by their inline image lowerings so an inline registration accepts
  exactly what the registrar does. Validates the metadata keys and any
  `:sensitive` / `:large` classification, both fail-loud. Writes nothing;
  returns `meta`."
  [kind id meta]
  (rf.reg-meta/validate-registration-metadata!
    kind (case kind :fx 'rf/reg-fx :cofx 'rf/reg-cofx (symbol "rf" (str "reg-" (name kind)))) id meta)
  (rf.classification/validate-classification! kind meta)
  meta)

(defn register-with-classification!
  "Shared registration tail for `reg-fx` and `reg-cofx`: validate the
  metadata fail-loud, then write the registrar entry (source coords,
  `:handler-fn`, and `extra-slots`, the cofx grade flags or nil).
  Classification is derived from the registrar meta when read, never stashed
  here."
  [kind id meta handler-fn extra-slots]
  (validate-registration-meta! kind id meta)
  (rf.registrar/register! kind id (merge (assoc (rf.source-coords/merge-coords meta)
                                             :handler-fn handler-fn)
                                      extra-slots)))

(defn- emit-fx-registration-invalid!
  "Emit and throw `:rf.error/fx-registration-invalid`. A `reg-fx` without a
  handler fn would otherwise register and fail late, at fire time, as a
  misleading `:rf.error/fx-handler-exception` (Spec 009 §Error event
  catalogue)."
  [id reason]
  (rf.trace/emit-error! :rf.error/fx-registration-invalid
                     {:rf.fx/id id
                      :reason   reason
                      :recovery :no-recovery})
  (rf.error/throw-error! :rf.error/fx-registration-invalid 'rf/reg-fx reason
                      {:extra {:rf.fx/id id}}))

(defn- validate-fx-handler!
  "Refuse a `reg-fx` whose `handler-fn` is not callable, such as the
  metadata-only `(reg-fx :id {…})`. Shared by `reg-fx` and
  `lower-inline-fx`."
  [id handler-fn]
  (when-not (ifn? handler-fn)
    (emit-fx-registration-invalid!
      id
      (str "`reg-fx` id `" id "` was registered with no handler fn. A "
           "`reg-fx` needs a `(fn [ctx args] …)` handler as its final "
           "argument — the metadata-only form `(reg-fx " id " {…})` supplies "
           "metadata but omits the handler, so nothing would run when the "
           "effect fires (it would fail LATE at fire-time as a misleading "
           "`:rf.error/fx-handler-exception`). Supply the handler fn."))))

(defn reg-fx
  "Register an effect handler under `id`; it runs for each `[id args]` entry
  in an event's `:fx` vector. Returns `id` (Spec 001 §Registration grammar).

      (reg-fx :id (fn [ctx args] ...))
      (reg-fx :id {:doc \"...\" :platforms #{:client}} (fn [ctx args] ...))

  The handler takes two arguments, where re-frame v1 passes one. `ctx`
  carries `:frame`, the originating `:event`, so a reply can be addressed
  back (Spec 014 §Reply addressing), and the parent `:envelope`; `args` is
  the emitted value. Metadata: `:doc`; `:schema` for `args` (Spec 010 §On
  every `reg-*`); and `:platforms`, default `#{:client :server}`, outside
  which the fx is skipped with a warning. Throws
  `:rf.error/fx-registration-invalid` when no handler fn is given."
  [id metadata-or-handler & maybe-handler]
  (let [[meta handler-fn]
        (if (map? metadata-or-handler)
          [metadata-or-handler (first maybe-handler)]
          [{} metadata-or-handler])]
    (validate-fx-handler! id handler-fn)
    (register-with-classification! :fx id meta handler-fn nil)
    id))

;; No `clear-fx`: `:fx` owns no teardown, so `(rf/clear :fx id)` calls
;; `rf.registrar/unregister!` directly.

;; ---- inline-registration lowering -----------------------------------------
;;
;; An image's inline `:reg-fx` entry must resolve to the descriptor shape
;; `reg-fx` installs, with the metadata at the top level where every runtime
;; reader looks. Published via late-bind, because image assembly cannot
;; require this namespace.

(defn lower-inline-fx
  "Lower an inline `:reg-fx` entry to the registrar shape `reg-fx` installs:
  `meta` at the top level plus `impl` as `:handler-fn`. Runs `reg-fx`'s
  registration checks; without top-level metadata an inline
  `{:platforms #{:client}}` fx would run on `:server`. `id` is the authored
  id, for diagnostics. Writes nothing."
  [id meta impl]
  (validate-fx-handler! id impl)
  (validate-registration-meta! :fx id meta)
  (assoc meta :handler-fn impl))

(rf.late-bind/set-fn! :image/lower-inline-fx lower-inline-fx)

;; ---- the platform predicate -----------------------------------------------

(defn runs-on-platform?
  "True when the `:platforms` metadata permits `active-platform`; the default
  is `#{:client :server}`. The one definition for `reg-fx` and `reg-cofx`
  alike (Spec 011 §`:platforms` metadata on `reg-fx`)."
  [meta active-platform]
  (let [platforms (:platforms meta #{:client :server})]
    (contains? platforms active-platform)))

(defn platform-for-frame-record
  "The active platform for a frame record: its `:config :platform`, else the
  host default (`:client` on CLJS, `:server` on the JVM). Shared by the
  router's `:fx` walk and the cofx injector."
  [frame-record]
  (or (-> frame-record :config :platform)
      (rf.interop/active-platform)))

;; ---- :dispatch-later host-timer side table --------------------------------
;;
;; Each armed timer's host handle is retained so `destroy-frame!` can cancel a
;; timer whose frame is torn down first; otherwise the timer and its closure
;; leak until it fires, into a dead frame. Transient host state keyed by
;; frame, never runtime-db, so neither restore nor the SSR wire sees it; it
;; mirrors `re-frame.resources.timers/timer-table`. A fired timer drops its
;; own entry. A timer armed with an `:id` is debounced by it: re-arming cancels
;; the pending one, and the slot key also carries a fresh counter, so a
;; replaced timer's already-started callback finds its slot gone.

(defonce ^:private dispatch-later-counter
  ;; Mints a unique slot id per armed timer, so two timers in one frame never
  ;; collide.
  (atom 0))

(defonce
  ^{:private true
    :doc "Pending `:dispatch-later` timers, keyed by `[frame-id timer-id]`, where
   `timer-id` is a counter value or `[::id <id> <counter value>]`. A slot holds
   `arming-sentinel` while reserved but not yet published, then the host
   handle."}
  dispatch-later-timers
  (atom {}))

(def ^:private arming-sentinel
  "Held in a reserved slot until `set-timeout!` returns the real handle.
  Distinct from every host handle, so cleanup can tell an in-flight arming
  from an installed handle and never passes it to `clear-timeout!`; the
  arming caller cancels the real handle when it finds the reservation gone."
  ::arming)

(defn- id-slot?
  "True when side-table key `k` names a timer armed in `frame-id` under `id`."
  [frame-id id [fid tid]]
  (and (= fid frame-id)
       (vector? tid)
       (= ::id (nth tid 0))
       (= id (nth tid 1))))

(defn- id-slots
  "The `[k handle]` entries of `m` armed in `frame-id` under `id`."
  [m frame-id id]
  (filter (fn [[k _]] (id-slot? frame-id id k)) m))

(defn- without-id-slots
  "`m` less every timer armed in `frame-id` under `id`; `m` itself when `id`
  is nil. Pure, so it is safe inside a retriable swap."
  [m frame-id id]
  (if (some? id)
    (reduce (fn [acc [k _]] (dissoc acc k)) m (id-slots m frame-id id))
    m))

(defn- cancel-id-slots!
  "Cancel the host handles of `slots` — the entries a swap just removed — and,
  when there were any, emit one `:rf.fx/dispatch-later-cancelled` trace with
  `reason` (`:on-replace` / `:on-cancel`). An `arming-sentinel` slot is
  dropped without `clear-timeout!`: its arming caller cancels the real handle
  when it finds the reservation gone."
  [slots frame-id id reason]
  (when (seq slots)
    (doseq [[_ handle] slots
            :when (not (= handle arming-sentinel))]
      (rf.interop/clear-timeout! handle))
    (rf.trace/emit! :rf.fx :rf.fx/dispatch-later-cancelled
                    {:frame frame-id :id id :reason reason}))
  nil)

(defn- cancel-dispatch-later!
  "Cancel the pending `:dispatch-later` timer armed in `frame-id` under `id`.
  A no-op, with no trace, when none is pending. Returns nil."
  [frame-id id]
  (let [[old _] (swap-vals! dispatch-later-timers without-id-slots frame-id id)]
    (cancel-id-slots! (id-slots old frame-id id) frame-id id :on-cancel)))

(defn- arm-dispatch-later!
  "Arm a host timer that dispatches `event` with `opts` after `ms`, retaining
  its handle so `release-frame!` can cancel it. A non-nil `id` replaces any
  timer pending under it in the frame. Returns nil.

  The slot is also the callback's dispatch authority: the thunk dispatches
  only if it removes a slot still present, so a cleanup that won the race,
  even mid-arming, suppresses it. Publication is two-phase because a JVM
  executor can run the thunk before `set-timeout!` returns: reserve the slot
  with `arming-sentinel` first, then publish the handle only if the
  reservation survived, else cancel it. Host side effects read `swap-vals!`
  snapshots and never run inside a retriable swap."
  ([frame-id ms event opts]
   (arm-dispatch-later! frame-id ms event opts nil))
  ([frame-id ms event opts id]
   (let [n (swap! dispatch-later-counter inc)
         k [frame-id (if (some? id) [::id id n] n)]]
     ;; Phase 1: reserve the slot before scheduling, removing any timer still
     ;; pending under the same `id`.
     (let [[old _] (swap-vals! dispatch-later-timers
                               (fn [m] (assoc (without-id-slots m frame-id id)
                                              k arming-sentinel)))]
       (when (some? id)
         (cancel-id-slots! (id-slots old frame-id id) frame-id id :on-replace)))
     (let [handle (rf.interop/set-timeout!
                    (fn []
                      ;; Whoever removes the slot holds the dispatch authority.
                      ;; Dispatch only if it was still present: host
                      ;; cancellation cannot un-run a started thunk, so this
                      ;; check is the suppression.
                      (let [[old _] (swap-vals! dispatch-later-timers dissoc k)]
                        (when (contains? old k)
                          (when-let [dispatch! (rf.late-bind/get-fn :router/dispatch!)]
                            (dispatch! event opts)))))
                    ms)
           ;; Phase 2: publish the handle only if the reservation survived.
           [old _] (swap-vals! dispatch-later-timers
                               (fn [m] (if (contains? m k) (assoc m k handle) m)))]
       (when-not (contains? old k)
         ;; The thunk or a destroy already removed the reservation: cancel
         ;; rather than reinsert a spent handle.
         (rf.interop/clear-timeout! handle)))
     nil)))

(defn release-frame!
  "Cancel and drop every pending `:dispatch-later` timer of a destroyed
  `frame-id`, through the `:fx/on-frame-destroyed!` hook. Idempotent; returns
  nil. An `arming-sentinel` slot is dropped without `clear-timeout!`, as its
  arming caller cancels the real handle."
  [frame-id]
  (let [[old _] (swap-vals! dispatch-later-timers
                            (fn [m]
                              (into {} (remove (fn [[[fid _tid] _]] (= fid frame-id))) m)))]
    (doseq [[[fid _tid] handle] old
            :when (and (= fid frame-id) (not (= handle arming-sentinel)))]
      (rf.interop/clear-timeout! handle)))
  nil)

(defn reset-dispatch-later-timers!
  "Cancel and drop every frame's pending `:dispatch-later` timers, for test
  isolation: the runtime reset does not touch this host state, so a stale
  timer could fire mid-next-test. Returns nil."
  []
  (let [[old _] (reset-vals! dispatch-later-timers {})]
    (doseq [[_ handle] old
            :when (not (= handle arming-sentinel))]
      (rf.interop/clear-timeout! handle)))
  nil)

;; `re-frame.frame` cannot require this namespace without inverting the load
;; order, so these hooks are the seam.
(rf.late-bind/set-fn! :fx/on-frame-destroyed!           release-frame!)
(rf.late-bind/set-fn! :fx/reset-dispatch-later-timers!  reset-dispatch-later-timers!)

;; ---- reserved fx-id table -------------------------------------------------
;;
;; `:dispatch` and `:dispatch-later` (Conventions §Reserved fx-ids) are body
;; fns `(fn [frame-id parent-envelope args])` in `reserved-fx-handlers`, so a
;; new reserved fx-id is a data edit. `handle-one-fx` emits `:rf.fx/handled`
;; for them uniformly.

(defn classify-fx-override
  "Classify `overrides`[`fx-id`] with one registrar lookup, so a concurrent
  registration cannot change the disposition between check and execution.
  Returns one of:

    {:disposition :noop}                         no override, or nil / false
    {:disposition :applied-fn :override f}       f runs in place (CLJS convenience)
    {:disposition :applied-redirect :target id}  a registered fx runs in place
    {:disposition :fallthrough :reason :unregistered :target id}
    {:disposition :fallthrough :reason :malformed :value v}

  A fallthrough means: emit `:rf.error/override-fallthrough`, then run the
  original fx. Pure; callers own any emit."
  [overrides fx-id]
  (let [v (get overrides fx-id)]
    (cond
      (or (nil? v) (false? v)) {:disposition :noop}
      (fn? v)                  {:disposition :applied-fn :override v}
      (keyword? v)
      (if (rf.registrar/lookup :fx v)
        {:disposition :applied-redirect :target v}
        {:disposition :fallthrough :reason :unregistered :target v})
      :else                    {:disposition :fallthrough :reason :malformed :value v})))

;; Copied from the parent envelope onto a `:dispatch` / `:dispatch-later`
;; child (Spec 002 §Run propagation). Deliberately absent: `:rf.cofx`, since a
;; child is a distinct causal token stamped fresh, and `:source`, which names
;; the child's own trigger. `:rf.cofx/mint-policy` is inherited because a
;; strict replay's discipline must hold across the whole cascade; it is on the
;; envelope only when supplied per call, so the frame-config tier is untouched.
(def ^:private inheritable-envelope-keys
  [:frame :fx-overrides :interceptor-overrides :trace-id :origin
   :rf.cofx/mint-policy])

(defn- child-dispatch-opts
  "The parent envelope's inheritable keys, as opts for a child dispatch, or
  `{:frame frame-id}` when there is no parent envelope. The router places the
  child by where the dispatch is made, not by anything here."
  [frame-id parent-envelope]
  (if parent-envelope
    (select-keys parent-envelope inheritable-envelope-keys)
    {:frame frame-id}))

(defn child-dispatch!
  "Queue a child dispatch with the reserved-dispatch guarantees: parent
  envelope inheritance (Spec 002 §Run propagation) and, for a numeric `:ms`,
  the frame-owned timer table, cancelled on frame destroy. The `:dispatch` and
  `:dispatch-later` bodies use it, and so may a feature artefact, rather than
  duplicate those semantics. A nil `parent-envelope` falls back to
  `{:frame frame-id}`.

  `extras`: `:source`, the child's own trigger, never inherited; `:ms`, a
  number to delay by, else dispatch now; `:id`, read only with `:ms`, which
  replaces any timer pending under it; `:source-detail`, for the dispatched
  trace; `:rf.flow/settle?`, set only by `settle-flows-after-walk!`; and
  `:rf.cofx`, merged verbatim so an owner-qualified fact is recorded and
  re-presented on strict replay."
  [frame-id parent-envelope event
   {:keys [source ms id source-detail] rf-cofx :rf.cofx flow-settle? :rf.flow/settle?}]
  (let [opts (cond-> (assoc (child-dispatch-opts frame-id parent-envelope) :source source)
               (some? source-detail) (assoc :source-detail source-detail)
               (some? rf-cofx)       (assoc :rf.cofx rf-cofx)
               (true? flow-settle?)  (assoc :rf.flow/settle? true))]
    (if (number? ms)
      ;; A timer callback, dispatched after the emitting event returned, so it
      ;; joins the external lane even at 0 ms.
      (arm-dispatch-later! frame-id ms event opts id)
      (when-let [f (rf.late-bind/get-fn :router/dispatch!)]
        (f event opts)))))

;; ---- flow settle after a state-writing walk (Spec 013 §Sequencing) --------

(defn- walk-changed-flowed-frame-state?
  "True when a completed `:fx` walk changed `frame-id`'s frame-state container
  and the frame runs a flow: the settle's trigger.

  The flow pass, the router's outermost `:after`, has already run when the
  walk starts, so a write during the walk (a machine lifecycle effect, a
  resource write, any user fx) would leave flows publishing the pre-write
  value. Keyed on the container rather than a list of writers, which would be
  easy to leave incomplete. The flows guard keeps this free where it does not
  apply: without it, a frame with no flows would enqueue a settle that
  recomputes nothing after every writing walk."
  [frame-id state-at-walk-start]
  (and (not (identical? state-at-walk-start (rf.frame/frame-state-value frame-id)))
       (if-let [has-flows? (rf.late-bind/get-fn :flows/frame-has-flows?)]
         (boolean (has-flows? frame-id))
         false)))

(defn- settle-flows-after-walk!
  "Enqueue one settle event when `walk-changed-flowed-frame-state?`, at the
  end of the walk so one pass settles everything the walk wrote (Spec 013
  §Sequencing — settling on the dispatching frame). It goes through
  `child-dispatch!` to inherit the parent's overrides and frame, with
  `:source :fx-dispatch`, since `:source` is a closed enum and the walk is its
  trigger. `:rf.flow/settle?` puts it at the head of the internal lane, ahead
  of the handler's own continuations (see `router/insert-envelope`).

  The event id is a literal rather than `re-frame.events/settle-flows-event-id`
  because `events` requires `cofx`, which requires this namespace. A mismatch
  would raise `:rf.error/no-such-handler` on every settle, which the settle
  tests assert against."
  [frame-id parent-envelope state-at-walk-start]
  (when (walk-changed-flowed-frame-state? frame-id state-at-walk-start)
    (child-dispatch! frame-id parent-envelope
                     [:rf/settle-flows]
                     {:source            :fx-dispatch
                      :rf.flow/settle?  true}))
  nil)

(def ^:private reserved-fx-handlers
  "Reserved fx-id → body fn `(fn [frame-id parent-envelope args])`, driven by
  `handle-one-fx`, which emits each one's `:rf.fx/handled`. Both bodies
  dispatch through `child-dispatch!`, which copies the parent envelope's
  inheritable keys onto the child (Spec 002 §Run propagation)."
  {:dispatch
   ;; The child joins the internal lane, as the walk runs inside the in-flight
   ;; event. `:source` names the `:dispatch` fx as the trigger, or
   ;; `:machine-action` when a machine emitted it; that mark is trace
   ;; provenance only.
   (fn [frame-id parent-envelope args]
     (child-dispatch! frame-id parent-envelope args
                      {:source (if (::machine-emitter? parent-envelope)
                                 :machine-action
                                 :fx-dispatch)}))

   :dispatch-later
   ;; `:source-detail {:ms ms}` lets the Epoch panel show the scheduled delay.
   ;; A missing or non-numeric `:ms` is refused rather than read as "dispatch
   ;; now", which would turn a typo'd delay into an immediate dispatch; the
   ;; typed throw reaches the always-on channel through `handle-one-fx`, and
   ;; the walk continues. `{:id k :cancel? true}` cancels; a cancel without an
   ;; `:id` is refused the same way.
   (fn [frame-id parent-envelope {:keys [ms event id cancel?]}]
     (if cancel?
       (do
         (when (nil? id)
           (rf.error/throw-error!
             :rf.error/fx-handler-exception 're-frame.fx
             (str "`:dispatch-later` with `:cancel? true` needs the `:id` of the "
                  "timer to cancel; got none. Nothing was cancelled. Pass "
                  "`{:id <id> :cancel? true}`.")
             {:extra {:id id}}))
         (cancel-dispatch-later! frame-id id))
       (do
         (when-not (number? ms)
           (rf.error/throw-error!
             :rf.error/fx-handler-exception 're-frame.fx
             (str "`:dispatch-later` needs a number of milliseconds under `:ms`; got "
                  (if (nil? ms) "none" (pr-str ms))
                  ". The event was not queued. Pass `{:ms <n> :event <event-vec>}`.")
             {:extra {:ms ms}}))
         (child-dispatch! frame-id parent-envelope event
                          {:source        (if (::machine-emitter? parent-envelope)
                                            :machine-action
                                            :fx-dispatch-later)
                           :ms            ms
                           :id            id
                           :source-detail {:ms ms}}))))})

(defn- emit-fx-error!
  "Emit a runtime fx error category on the always-on channel and the dev
  trace. These are production-reachable (Spec 009 §Error event catalogue),
  and the SSR projector fail-closes `:rf.error/fx-handler-exception` to 500
  off the always-on stream (Spec 011 §Server error projection).
  `trace-payload` is the dev-trace map; `record-attrs` passes through to the
  always-on record, so every `:rf.error/effect-map-shape` case carries
  `:offending-key`. Late-bound, since requiring `re-frame.error-emit` here
  would be a load cycle."
  ([category event event-id frame-id exception trace-payload]
   (emit-fx-error! category event event-id frame-id exception trace-payload nil))
  ([category event event-id frame-id exception trace-payload record-attrs]
   (when-let [emit-error-both! (rf.late-bind/get-fn :error-emit/emit-error-both)]
     (emit-error-both! category event event-id frame-id exception 0
                       (rf.interop/now-ms) trace-payload record-attrs))))

(defn emit-override-fallthrough!
  "Emit `:rf.error/override-fallthrough` for a `:fallthrough` disposition from
  `classify-fx-override`, on both channels, since override misconfiguration is
  production-reachable; a no-op for any other disposition. The recovery is
  `:replaced-with-default`: the registered `original-fx-id` runs."
  [{:keys [disposition reason target value]}
   original-fx-id overrides frame-id origin-event origin-event-id]
  (case disposition
    :fallthrough
    (if (= reason :unregistered)
      (emit-fx-error! :rf.error/override-fallthrough
                      origin-event origin-event-id frame-id nil
                      {:failing-id    original-fx-id
                       :overrides-map overrides
                       :looked-up-id  target
                       :frame         frame-id
                       :reason        (str "Override redirected `" original-fx-id
                                           "` to `" target
                                           "`, which is not registered. Using the registered `"
                                           original-fx-id "` instead.")
                       :recovery      :replaced-with-default})
      ;; :malformed — any value that is not a keyword / fn / nil / false.
      (emit-fx-error! :rf.error/override-fallthrough
                      origin-event origin-event-id frame-id nil
                      {:failing-id    original-fx-id
                       :overrides-map overrides
                       :override      value
                       :frame         frame-id
                       :reason        (str "Override for `" original-fx-id
                                           "` is `" (pr-str value)
                                           "`, which is not a valid `:fx-overrides` "
                                           "value — it must be an fx-id keyword "
                                           "(id-redirect), a function (override body), "
                                           "or nil/false (noop). Using the registered `"
                                           original-fx-id "` instead.")
                       :recovery      :replaced-with-default}))
    nil))

(defn resolve-fx-with-overrides
  "The fx-id to run for `original-fx-id` under `overrides` (Spec 002
  §`:fx-overrides` — replace fx handlers): a registered redirect target, or
  `original-fx-id` when there is no override, a fallthrough (after emitting
  `:rf.error/override-fallthrough`) or a fn value. A reserved fx-id is a
  valid redirect target. For a fn value, `handle-one-fx` emits
  `:rf.fx/override-applied` when the fn actually runs, so the trace never
  claims an override while the original ran."
  [original-fx-id overrides frame-id origin-event origin-event-id]
  (let [disposition (classify-fx-override overrides original-fx-id)]
    (case (:disposition disposition)
      ;; A fn override of a reserved fx-id must pre-empt the reserved body,
      ;; so `handle-one-fx` emits the applied trace when the fn runs.
      :applied-fn       original-fx-id

      :applied-redirect (do
                          (rf.trace/emit! :rf.fx :rf.fx/override-applied
                                       {:rf.fx/from original-fx-id
                                        :rf.fx/to   (:target disposition)})
                          (:target disposition))

      :fallthrough
      (do
        (emit-override-fallthrough! disposition original-fx-id overrides
                                    frame-id origin-event origin-event-id)
        original-fx-id)

      ;; `:noop` — missing key / nil / false. Silent on purpose.
      original-fx-id)))

(defn- resolved-fx-meta
  "The fx-handler meta to invoke: a synthesised one carrying a fn-value
  override's fn, else the registrar entry for `resolved-fx-id`; nil when none
  resolves."
  [original-fx-id resolved-fx-id overrides]
  (let [override (get overrides original-fx-id)]
    (if (and (contains? overrides original-fx-id)
             (fn? override))
      ;; Both platforms: the override is a test or story affordance, and
      ;; gating it by platform would surprise its author.
      {:handler-fn override
       :platforms  #{:client :server}}
      (rf.registrar/lookup :fx resolved-fx-id))))

(defn- emit-handled!
  "Emit the `:rf.fx/handled` success trace, one per dispatched fx, which the
  epoch `:effects` projection consumes (Spec-Schemas §`:rf/epoch-record`).
  Inside a user fx's handler scope the trace names the `reg-fx` site; a
  reserved fx-id has none, so the event handler's scope names it."
  ([fx-id args frame-id]
   (emit-handled! fx-id args frame-id nil))
  ([fx-id args frame-id elapsed-ms]
   (emit-handled! fx-id args frame-id elapsed-ms nil))
  ([fx-id args frame-id elapsed-ms from-id]
   ;; `elapsed-ms` is dev-only, nil in production. `from-id` is a redirect's
   ;; original id, so the classification projector composes the original
   ;; registration's classification over the target's.
   (rf.trace/emit! :rf.fx :rf.fx/handled
                (cond-> {:rf.fx/id   fx-id
                         :rf.fx/args args
                         :frame      frame-id}
                   (some? elapsed-ms) (assoc :rf.fx/elapsed-ms elapsed-ms)
                   (some? from-id)    (assoc :rf.fx/from from-id)))))

(defn handle-one-fx
  "Process one `[fx-id args]` entry: a reserved fx-id runs its table body, a
  registered fx its handler, and an unknown one emits `:rf.error/no-such-fx`;
  the walk continues either way. Only a successful run emits
  `:rf.fx/handled`, so the epoch `:effects` projection holds one entry per
  fx. `origin-event` and `parent-envelope` reach the handler's ctx as
  `:event` and `:envelope`. Public so an fx wrapper such as
  `:rf.route/with-nav-token` (Spec 012 §Navigation tokens — stale-result
  suppression) can route one inner entry through the same machinery without
  re-emitting the `:rf.fx/do-fx` marker."
  ([frame-id pair active-platform overrides origin-event]
   (handle-one-fx frame-id pair active-platform overrides origin-event nil))
  ([frame-id [original-fx-id args] active-platform overrides origin-event parent-envelope]
   (let [origin-event-id (when (vector? origin-event) (first origin-event))
        fx-id (resolve-fx-with-overrides original-fx-id overrides
                                          frame-id origin-event origin-event-id)
        ;; A frame made without `:images` throws
        ;; `:rf.error/ambiguous-registration` from the lookup when more than
        ;; one namespace registers `fx-id`; that fx is reported and skipped
        ;; like an unknown one, and the walk continues.
        ambiguity     (volatile! nil)
        resolved-meta (try
                         (resolved-fx-meta original-fx-id fx-id overrides)
                         (catch #?(:clj Throwable :cljs :default) e
                           (if (rf.registrar/ambiguous-registration? e)
                             (do (vreset! ambiguity e) nil)
                             (throw e))))
        ;; A fn override runs in place of the fx, reserved ids included (Spec
        ;; 002 §`:fx-overrides` — replace fx handlers). `fx-id` is still the
        ;; reserved keyword here, so without this check the reserved body
        ;; would run and silently ignore the override.
        fn-value-override? (and (contains? overrides original-fx-id)
                                (fn? (get overrides original-fx-id)))
        ;; A redirect's original id, stamped as `:rf.fx/from` so the
        ;; classification projector composes the original registration's
        ;; classification over the target's: a `:sensitive?` request
        ;; redirected to a test stub must not ride raw.
        redirected-from (when (not= original-fx-id fx-id) original-fx-id)]
   ;; At the top, so reserved fx-ids get their `rf:fx:<id>` measure too
   ;; (Spec 009 §Performance instrumentation).
   (rf.performance/mark-and-measure :fx fx-id
    (if-let [reserved-body (and (not fn-value-override?)
                              (get reserved-fx-handlers fx-id))]
    ;; A typed throw, one carrying `:rf.error/id`, goes to the always-on
    ;; channel with its ex-data kept, so a reserved fx's error keywords live
    ;; with the fx that throws them. An untyped throw is rethrown, crash-loud.
    (try
      (let [t0 (when rf.interop/debug-enabled? (rf.interop/now-ms))]
        (reserved-body frame-id parent-envelope args)
        (emit-handled! fx-id args frame-id
                       (when rf.interop/debug-enabled?
                         (- (rf.interop/now-ms) t0))))
      (catch #?(:clj Throwable :cljs :default) e
        (let [ex-data-map (ex-data e)
              category    (:rf.error/id ex-data-map)]
          (if (keyword? category)
          ;; Nil-safe: a thrown non-Error value has no message.
          (let [msg (rf.error/ex-message-safe e)]
            (emit-fx-error! category origin-event origin-event-id frame-id e
                            (merge {:failing-id        fx-id
                                    :rf.fx/id          fx-id
                                    :rf.fx/args        args
                                    :frame             frame-id
                                    :exception         e
                                    :exception-message msg
                                    :recovery          :no-recovery}
                                   (dissoc ex-data-map :rf.error/id))))
            (throw e)))))
    ;; A registered fx, or the synthesised meta of a fn override.
    (if-let [meta resolved-meta]
    (if (runs-on-platform? meta active-platform)
      ;; Validate `args` against the fx's `:schema` (Spec 010 §Validation
      ;; order on event processing). Key presence, not truthiness: a present
      ;; nil or false `:schema` is a declaration the validator interprets. A
      ;; failed fx is skipped and the walk continues.
      (let [validate-fx! (rf.late-bind/get-fn :schemas/validate-fx!)
            fx-ok?       (if (and validate-fx!
                                  (contains? meta :schema))
                           (try
                             ;; The frame lets the failure trace land in the
                             ;; frame's epoch.
                             (validate-fx! fx-id origin-event-id args meta frame-id)
                             (catch #?(:clj Throwable :cljs :default) _
                               true))
                           true)]
      (cond
        (not fx-ok?)
        ;; `validate-fx!` already emitted the failure, which is the warning;
        ;; no `:rf.fx/handled`, since the fx did not run.
        nil

        :else
        ;; The fx's own scope, so its errors and its `:rf.fx/handled` trace
        ;; name its `reg-fx` site rather than the event handler's.
        (rf.trace/with-handler-scope
          (rf.trace/handler-scope-from-meta :fx fx-id meta)
          ;; Emitted where the fn override actually fires, so it never claims
          ;; an override on a path where the original ran.
          (when fn-value-override?
            (rf.trace/emit! :rf.fx :rf.fx/override-applied
                         {:rf.fx/from original-fx-id :rf.fx/to ::fn-value}))
          (let [t0  (when rf.interop/debug-enabled? (rf.interop/now-ms))
              ok? (try
                    ((:handler-fn meta) (cond-> {:frame frame-id}
                                          origin-event   (assoc :event origin-event)
                                          parent-envelope (assoc :envelope parent-envelope))
                                        args)
                    true
                    (catch #?(:clj Throwable :cljs :default) e
                      ;; Nil-safe: a thrown non-Error value has no message.
                      (let [msg (rf.error/ex-message-safe e)]
                      ;; Always-on, so SSR fails closed to 500 and off-box
                      ;; shippers see it. The fx is skipped, its siblings
                      ;; still run, and app-db is not rolled back.
                      (emit-fx-error!
                        :rf.error/fx-handler-exception
                        origin-event
                        origin-event-id
                        frame-id
                        e
                        (cond-> {:failing-id        fx-id
                                 :rf.fx/id          fx-id
                                 :rf.fx/args        args
                                 :frame             frame-id
                                 :exception         e
                                 :exception-message msg
                                 :reason            (str "Effect handler `" fx-id "` threw: " msg ".")
                                 :recovery          :no-recovery}
                          ;; The projector must reach the original id's
                          ;; classification when a redirected stub throws.
                           redirected-from (assoc :rf.fx/from redirected-from)))
                        false)))]
            (cond
              ok?
              (emit-handled! fx-id args frame-id
                             (when rf.interop/debug-enabled?
                               (- (rf.interop/now-ms) t0))
                             redirected-from)

              :else nil)))))
      (rf.trace/emit! :warning :rf.fx/skipped-on-platform
                   (cond-> {:rf.fx/id                   fx-id
                            :frame                      frame-id
                            :rf.fx/args                 args
                            :rf.fx/platform             active-platform
                            :rf.fx/registered-platforms (:platforms meta)
                            :recovery                   :skipped}
                     ;; Same provenance on the skip trace: it
                     ;; stamps the same [:rf.fx/id :rf.fx/args] pair.
                     redirected-from (assoc :rf.fx/from redirected-from))))
    ;; Always-on, so a load-order or missing-artefact mistake shows in
    ;; production. `:failing-id` makes the record name which fx was missing:
    ;; the fx is a component distinct from the dispatching event, so the lift
    ;; applies (Spec 009 §Observability channels and the promotion criterion).
    ;; It is a bare code identifier; `:rf.fx/args` stays on the dev trace.
    (if-let [ambiguous @ambiguity]
      (let [{:keys [registration namespaces reason]} (ex-data ambiguous)]
        (emit-fx-error! :rf.error/ambiguous-registration
                        origin-event
                        origin-event-id
                        frame-id
                        nil
                        {:failing-id   fx-id
                         :rf.fx/id     fx-id
                         :rf.fx/args   args
                         :frame        frame-id
                         :registration registration
                         :namespaces   namespaces
                         :reason       reason
                         :recovery     :clear-the-id-and-re-evaluate-its-owner}
                        {:registration registration
                         :namespaces   namespaces}))
      (emit-fx-error! :rf.error/no-such-fx
                      origin-event
                      origin-event-id
                      frame-id
                      nil
                      {:failing-id fx-id
                       :rf.fx/id   fx-id
                       :rf.fx/args args
                       :frame      frame-id
                       :recovery   :no-recovery}))))))))

(def framework-coeffect-keys
  "Coeffect keys the runtime supplies itself, filtered out of the
  `:rf.event/coeffects` run-end stamp so tools show only handler-declared
  coeffects."
  #{:db :event :source :trace-id :rf.db/runtime :rf.frame/id :rf.cofx
    :rf.cofx/mint-policy})

(defn user-injected-coeffects
  "`coeffects` without `framework-coeffect-keys`, or nil when nothing is left,
  so the caller omits the stamp: an absent slot means no user coeffects."
  [coeffects]
  (when (map? coeffects)
    (let [projected (reduce-kv (fn [acc k v]
                                 (if (contains? framework-coeffect-keys k)
                                   acc
                                   (assoc acc k v)))
                               {}
                               coeffects)]
      (when (seq projected)
        projected))))

;; ---- per-entry :fx-shape policing -----------------------------------------
;;
;; One level below `events/effect-map-defect`, which refuses the whole event
;; pre-commit on a malformed `:fx` value. An entry is already on the
;; post-commit, best-effort plane, so a malformed one is dropped with a
;; diagnostic and its siblings still run. Policed loudly because a lax guard
;; would drop a bare keyword silently, report a non-keyword head as
;; `:rf.error/no-such-fx`, and truncate a third element without a word
;; (Spec-Schemas §`:rf/effect-map`).

(defn- fx-entry-ok?
  "True when `pair` should be walked: a 1- or 2-element vector headed by a
  keyword. nil and empty collections are the conditional-fx no-op idiom:
  false, with no trace. Anything else emits `:rf.error/effect-map-shape` with
  recovery `:logged-and-skipped` and returns false, dropping only that entry.
  `event` names the handler in the diagnostic."
  [pair frame-id event]
  (cond
    ;; Legal no-op: nil or empty — the conditional-fx idiom. Skip, no trace.
    (nil? pair)               false
    (and (vector? pair)
         (#{1 2} (count pair))
         (keyword? (first pair))) true     ;; [fx-id] / [fx-id args] — walk it
    (and (coll? pair)
         (empty? pair))       false        ;; empty [] / () — legal no-op, no trace
    :else
    ;; Three sub-cases, told apart only for `:reason`.
    (let [event-id (when (vector? event) (first event))
          reason   (cond
                     (not (vector? pair))
                     (str "Effect-map for `" event-id "` returned an `:fx` entry of type `"
                          (pr-str (type pair))
                          "` (value `" (pr-str pair) "`); each `:fx` entry must be a "
                          "`[fx-id args]` vector (e.g. `[:dispatch [:saved]]`)"
                          " — did you forget the inner vector?")

                     (not (keyword? (first pair)))
                     (str "Effect-map for `" event-id "` returned an `:fx` entry `"
                          (pr-str pair) "` whose fx-id (the first element) is `"
                          (pr-str (first pair)) "` of type `" (pr-str (type (first pair)))
                          "`; each `:fx` entry must be a `[fx-id args]` vector whose "
                          "fx-id is a keyword (e.g. `[:dispatch [:saved]]`).")

                     :else ;; arity ≥ 3 (the empty-vector arity-0 case is handled above)
                     (str "Effect-map for `" event-id "` returned an `:fx` entry `"
                          (pr-str pair) "` with " (count pair) " elements; each `:fx` "
                          "entry must be a `[fx-id args]` vector of at most two elements "
                          "(e.g. `[:dispatch [:saved]]` or the no-args `[:fx-id]`)"
                          " — the surplus element(s) would be silently discarded."))]
      ;; Always-on, like the envelope cases. `:failing-id` equals `:event-id`,
      ;; so the lift does not fire and the interpolating `:reason` stays on the
      ;; dev trace.
      (emit-fx-error! :rf.error/effect-map-shape
                      event event-id frame-id nil
                      {:failing-id        event-id
                       :rf.trace/event-id event-id
                       :rf.event/v        event
                       :frame             frame-id
                       :offending-key     :fx
                       :value             pair
                       :reason            reason
                       :recovery          :logged-and-skipped}
                      ;; Axis 1 — one record shape for the whole category.
                      {:offending-key :fx})
      false)))

;; ---- dry-run effect sink --------------------------------------------------

(def ^:dynamic *effect-sink*
  "Dry-run sink. Bound to an atom holding a vector, `do-fx` records every
  well-shaped `[fx-id args]` entry into it and executes nothing: no override
  resolution, no reserved body, no handler. Intercepting at the one effect
  executor, before per-fx resolution, makes a dry run structurally unable to
  fire any fx, inline and machine fx included. The re-frame2-pair dry run
  binds it around a `dispatch-sync`, then rolls `:db` back with
  `restore-epoch!`. Internal; not a `re-frame.core` export."
  nil)

(defn do-fx
  "Walk `fx-vec` in source order, each handler returning before the next
  begins; a failing entry is reported and the walk continues (Spec 002 §`:fx`
  ordering and atomicity guarantees). Malformed entries are dropped by
  `fx-entry-ok?`. With `*effect-sink*` bound, entries are recorded, not run.
  When the walk wrote the state of a frame running a flow, one settle is
  enqueued at the end.

  `opts`: `:overrides`, the effective `:fx-overrides`; `:origin-event` and
  `:parent-envelope`, passed to handlers as `:event` and `:envelope`; and
  `:effects`, the handler's effects map, used only to stamp `:fx` and
  `:db-present?` on the terminal `:rf.fx/do-fx` marker. The 3-arity is the
  bare walk machine exit cascades use."
  ([frame-id fx-vec active-platform]
   (do-fx frame-id fx-vec active-platform nil))
  ([frame-id fx-vec active-platform
    {:keys [overrides origin-event parent-envelope effects]}]
   ;; The settle's baseline: the frame state after this event's flow pass and
   ;; install, compared by identity when the walk ends.
   (let [state-at-walk-start (rf.frame/frame-state-value frame-id)]
     (loop [pairs (seq fx-vec)]
       (cond
         (nil? pairs)
         (do
           (rf.trace/emit! :rf.fx :rf.fx/do-fx
                        (cond-> {:frame frame-id}
                          (some? effects)
                          (assoc :rf.event/fx          (:fx effects)
                                 :rf.event/db-present? (contains? effects :db))))
           ;; After the terminal marker, so the settle's dispatch trace reads as
           ;; a consequence of the walk rather than part of it.
           (settle-flows-after-walk! frame-id parent-envelope
                                     state-at-walk-start))

         :else
         (let [pair (first pairs)]
           (when (fx-entry-ok? pair frame-id origin-event)
             (if-let [sink *effect-sink*]
               ;; Dry-run records but never executes the entry.
               (swap! sink conj [(first pair) (second pair)])
               (handle-one-fx frame-id pair active-platform
                              (or overrides {}) origin-event
                              parent-envelope)))
           (recur (next pairs))))))))
