(ns re-frame.story.frames
  "Per-variant frame allocation. Per IMPL-SPEC §5.1 + spec/007
  §Relationship-with-frames.

  Each rendered variant is its own re-frame frame (spec/002), allocated
  fresh on `run-variant` and torn down by the caller via
  `destroy-variant!`. Story does NOT introduce a new frame substrate —
  it consumes `re-frame.core/reg-frame` / `destroy-frame!` / `reset-frame!`.

  ## Frame config

  When the runtime allocates a variant frame, it constructs a
  `reg-frame` config of the shape:

      {:doc          \"Variant frame for <variant-id>.\"
       :preset       :story
       :rf/story?    true
       :rf/variant   <variant-id>
       :fx-overrides {<fx-id> <stub-event-id>}}

  - `:preset :story` (spec/002 §Frame presets) sets `:drain-depth 16`
    + redirects `:rf.http/managed` to its canned stub, which is the
    sensible default for a sandboxed playground frame.
  - `:rf/story?` and `:rf/variant` are Story-stamped metadata so tools
    (10x, the snapshot service, the MCP query layer) can identify a
    variant frame from its `frame-meta`.
  - `:fx-overrides` carries the `:fx-override`-decorator stack's
    `{fx-id → stub-event-id}` map. The runtime registers each stub
    event before `reg-frame`-ing so the override is live before
    `:on-create` runs.

  ## Lifecycle

  - `allocate!` — create the frame (and register fx-override stubs);
    install the lifecycle machine's `:pre-mount` snapshot.
  - `destroy!` — tear down the frame; clear watchers.
  - `reset-frame!` — destroy + re-allocate; the caller then re-runs the
    lifecycle.

  ## Hot-reload

  Stage 4's UI shell observes the registrar's `:rf.registry/handler-*`
  trace events and re-runs `reset-frame!` for any variant whose
  registered body changed. Stage 3 surfaces the entry point; the
  trigger lives in Stage 4."
  (:require [re-frame.core             :as rf]
            [re-frame.story.config     :as config]
            [re-frame.story.decorators :as decorators]
            [re-frame.story.late-bind  :as late-bind]
            [re-frame.story.loaders    :as loaders]
            [re-frame.story.registrar  :as registrar]
            ;; rf2-dg2uh — trace-listener pattern for the teardown
            ;; exception-projection path: re-frame's interceptor chain
            ;; catches handler exceptions internally and emits
            ;; `:rf.error/handler-exception` trace events rather than
            ;; re-throwing (per `runtime/capture-phase-errors` —
            ;; the same listener pattern is used here for phase-teardown).
            [re-frame.trace.tooling    :as trace-tooling]))

;; ---- fx-override-stub registration ---------------------------------------
;;
;; Cross-namespace forward references go through the
;; `re-frame.story.late-bind` hub rather than per-shim atoms. Two hooks:
;;
;;   :tap-stub-event              — fx-stubs binds this; the stub-event
;;                                  handler calls it with `(frame-id
;;                                  fx-id)` so the assertion module's
;;                                  per-frame emitted-fx accumulator
;;                                  records the call. Stage 3-only
;;                                  builds leave it unset and the call
;;                                  no-ops.
;;
;;   :drop-assertion-accumulators — assertions+play bind this; the
;;                                  frame-teardown path calls it with
;;                                  `(frame-id)` so per-frame
;;                                  accumulators evict their entries.
;;
;; `re-frame.story/install-canonical-vocabulary!` is the producer side.

;; Per-frame stub-call log. Per spec/002 the `:fx-overrides` map
;; redirects fx-id → fx-id, and re-frame's `reg-fx` handlers receive
;; only their arg payload (no cofx, no `:db`). So the stub fx handler
;; can't write into the variant frame's app-db atomically. Instead it
;; appends to a process-level atom keyed by frame-id; the assertion
;; module reads this for `:rf.assert/effect-emitted` semantics.
(defonce
  ^{:doc "frame-id → vector of stub-call entries
         `{:stub-id ... :fx-id ... :payload ...}`. Populated by the
         registered stub-fx handler; read by the assertions module and
         the fx-stubs module's `observed-fx-ids` helper."}
  stub-call-log
  (atom {}))

(defn stub-call-log-for
  "Return the per-frame stub-call log entries, or `[]`."
  [frame-id]
  (get @stub-call-log frame-id []))

(defn clear-stub-call-log!
  "Drop the per-frame stub-call log entry. Called on frame teardown."
  [frame-id]
  (swap! stub-call-log dissoc frame-id)
  nil)

(defn- ensure-stub-event!
  "Register a `reg-fx` handler under `stub-id` that handles a
  redirected fx call. Idempotent — re-registration replaces the slot
  atomically (Spec 001 hot-reload semantics).

  Per spec/002 §Per-frame and per-call overrides the `:fx-overrides`
  map redirects `fx-id → fx-id`. So the stub MUST be a registered
  `:fx` handler, not an event handler. The stub:

  1. Taps `late-bind/get-fn :tap-stub-event` with `(frame fx-id)` so the assertion
     module's emitted-fx accumulator records the call (so
     `:rf.assert/effect-emitted` can observe it).
  2. Appends the call to the per-frame `stub-call-log` atom for
     inspection / dev-tool surfacing.

  The fx handler runs synchronously inside re-frame's `do-fx` walk
  with signature `(ctx args)` per Spec 002 §Effect handlers; `ctx`
  carries `:frame` and (optionally) `:event`. All bookkeeping lives
  in process-level atoms rather than the frame's app-db. Stage 5
  (rf2-h8et)."
  [stub-id fx-id response]
  (rf/reg-fx
    stub-id
    (fn [{:keys [frame] :as _ctx} fx-payload]
      (when-let [tap (late-bind/get-fn :tap-stub-event)]
        (try (tap frame fx-id) (catch #?(:clj Throwable :cljs :default) _ nil)))
      (swap! stub-call-log update frame (fnil conj [])
             {:stub-id  stub-id
              :fx-id    fx-id
              :payload  fx-payload
              :response response})
      nil)))

(defn- register-fx-overrides!
  "Walk the `:registrations` vector from `decorators/fx-overrides-map`
  and register each stub fx. Returns the `:overrides` map suitable
  for the variant frame's `:fx-overrides` config slot."
  [{:keys [overrides registrations]}]
  (doseq [{:keys [stub-id response fx-id]} registrations]
    (ensure-stub-event! stub-id fx-id response))
  overrides)

;; ---- :frame-setup decorator application ---------------------------------

(defn- apply-frame-setup!
  "Walk the resolved `:frame-setup` decorators and execute their
  `:init` events + `:app-db-patch` against the freshly-allocated frame.
  Per IMPL-SPEC §5.3 these fire before `:loaders` — so before phase 1
  in the lifecycle.

  Each decorator's `:init` events are dispatched synchronously; the
  `:app-db-patch` (a `path → value` map) is merged into the frame's
  app-db via a registered helper event."
  [frame-id frame-setup-decorators]
  (doseq [r frame-setup-decorators]
    (let [body          (:body r)
          init-events   (:init body)
          patch         (:app-db-patch body)]
      (when patch
        (rf/dispatch-sync [::apply-app-db-patch patch] {:frame frame-id}))
      (doseq [ev init-events]
        (rf/dispatch-sync ev {:frame frame-id})))))

;; ---- :frame-setup decorator teardown -------------------------------------

(defn- teardown-exception-record
  "Build the `:rf.error/exception` assertion record projected when a
  teardown event throws. Per `002-Runtime.md` §Error projection and
  §Loader teardown contract — the record lands in `:rf.story/assertions`
  so the variant's last result-map surfaces the failure (the play /
  variant pane renders it inline).

  `phase` is one of `:phase-loaders-teardown` (variant body's
  `:loaders-teardown` events — rf2-lqs0b) or `:phase-teardown`
  (`:frame-setup` decorator `:teardown` events — rf2-dg2uh)."
  [variant-id phase event err]
  {:assertion  :rf.error/exception
   :variant-id variant-id
   :phase      phase
   :event      event
   :error      {:message #?(:clj  (.getMessage ^Throwable err)
                            :cljs (str err))
                :stack   #?(:clj  (with-out-str (.printStackTrace ^Throwable err))
                            :cljs (.-stack err))
                :data    (when (instance? #?(:clj clojure.lang.ExceptionInfo
                                             :cljs ExceptionInfo) err)
                           (ex-data err))}
   :passed?    false})

(defonce ^:private teardown-capture-counter (atom 0))

(defn- with-teardown-trace-listener
  "Register `listener` against a fresh capture id, run `body-fn` (a
  0-arg thunk), then remove the listener in a `finally`. Returns
  `body-fn`'s return value.

  Mirrors `re-frame.story.runtime/with-trace-listener` — that helper is
  private to runtime.cljc; replicating it here keeps the
  `frames → runtime` arrow unidirectional (frames is leaf-level)."
  [listener body-fn]
  (let [cb-id (keyword "re-frame.story.frames"
                       (str "teardown-capture-"
                            (swap! teardown-capture-counter inc)))]
    (trace-tooling/register-listener! cb-id listener)
    (try (body-fn)
      (finally (trace-tooling/unregister-listener! cb-id)))))

(defn- apply-frame-teardown!
  "Walk the resolved `:frame-setup` decorators IN REVERSE-DECLARATION
  ORDER and dispatch-sync each decorator's `:teardown` events against
  the variant's frame. Per `001-Authoring.md` §`:teardown` — symmetric
  counterpart of `:init` and `002-Runtime.md` §Loader teardown contract
  step 3.

  Composition order. Innermost (variant-level) teardowns fire BEFORE
  outermost (story-level / global) teardowns — mirrors function-scope
  cleanup conventions. The resolver concatenates
  `(global → story → variant)` in declared order; reversing the stack
  walks innermost → outermost.

  Inside each decorator the declared `:teardown` events fire in
  declared order — symmetric to `:init` (which fires events in
  declared order too).

  Exception handling. re-frame's interceptor chain catches handler
  exceptions internally and emits a `:rf.error/handler-exception`
  trace event rather than re-throwing (per spec/009 §Error trace).
  We register a trace listener for the duration of the walk that
  collects each captured exception, then project them onto the
  variant frame's `[:rf.story/assertions]` as `:rf.error/exception`
  records with `:phase :phase-teardown`. The walk continues —
  teardown never aborts `destroy-frame!`. Synchronous throws (from
  outside the interceptor chain — rare) are also caught directly via
  the wrapping `try/catch`."
  [variant-id frame-setup-decorators]
  (let [pending  (atom [])
        drain!   (fn []
                   ;; Drain pending captured exceptions into the
                   ;; variant frame's [:rf.story/assertions] BEFORE the
                   ;; next teardown dispatch — so a later teardown event
                   ;; (e.g. an outer decorator's :teardown) reading the
                   ;; assertions slot sees earlier failures already
                   ;; projected. Mirrors the spec's "land in the
                   ;; variant's last :rf.story/assertions record"
                   ;; contract for in-order observability.
                   (when-let [evs (seq @pending)]
                     (reset! pending [])
                     (doseq [tev evs]
                       (let [orig-event (get-in tev [:tags :event])
                             err        (get-in tev [:tags :exception])
                             record     (teardown-exception-record
                                          variant-id :phase-teardown
                                          orig-event err)]
                         (try
                           (rf/dispatch-sync [::append-teardown-assertion record]
                                             {:frame variant-id})
                           (catch #?(:clj Throwable :cljs :default) _ nil))))))
        listener (fn [ev]
                   (when (and (= :rf.error/handler-exception (:operation ev))
                              (= variant-id (get-in ev [:tags :frame])))
                     (swap! pending conj ev)))]
    (with-teardown-trace-listener
      listener
      (fn []
        (doseq [r (reverse frame-setup-decorators)]
          (let [body            (:body r)
                teardown-events (:teardown body)]
            (doseq [ev teardown-events]
              (try
                (rf/dispatch-sync ev {:frame variant-id})
                (catch #?(:clj Throwable :cljs :default) err
                  ;; Synchronous throw (outside the interceptor chain).
                  ;; Project directly. The trace-listener path covers
                  ;; in-handler throws that the interceptor chain catches.
                  (let [record (teardown-exception-record
                                 variant-id :phase-teardown ev err)]
                    (try
                      (rf/dispatch-sync [::append-teardown-assertion record]
                                        {:frame variant-id})
                      (catch #?(:clj Throwable :cljs :default) _ nil)))))
              ;; Drain after every teardown dispatch so the next
              ;; teardown event sees the projected record.
              (drain!))))))
    ;; Final drain — catch any trace events that arrived after the last
    ;; dispatch inside the listener-bound region. (Shouldn't happen for
    ;; dispatch-sync, but the belt-and-braces drain costs nothing.)
    (let [collected @pending]
      (reset! pending [])
      (doseq [tev collected]
        (let [orig-event (get-in tev [:tags :event])
              err        (get-in tev [:tags :exception])
              record     (teardown-exception-record
                           variant-id :phase-teardown orig-event err)]
          (try
            (rf/dispatch-sync [::append-teardown-assertion record]
                              {:frame variant-id})
            (catch #?(:clj Throwable :cljs :default) _ nil)))))))

;; ---- variant body :loaders-teardown walk --------------------------------
;;
;; rf2-lqs0b — symmetric counterpart of `:loaders` on the variant body. A
;; long-lived fx (websocket subscription, polling interval, geolocation
;; watch) opened by a `:loaders` event needs a matching cancel-event at
;; variant teardown. `:loaders-teardown` is the lightweight path for
;; variants whose cleanup is too small to justify a spec/005 machine
;; actor `:exit` or a `:frame-setup` decorator wrapping; the events
;; dispatch-sync into the variant frame BEFORE the decorator `:teardown`
;; walk (so decorator-installed wider state survives the cleanup of
;; loader-installed narrower state).
;;
;; Composition with `:frame-setup` decorator `:teardown`:
;;
;;   destroy!  ─►  variant body :loaders-teardown
;;             ─►  decorator :teardown (reverse-declaration order)
;;             ─►  spec/005 machine :rf.machine/destroy
;;             ─►  rf/destroy-frame!
;;
;; The intuition: variant-body `:loaders-teardown` cleans up what
;; variant-body `:loaders` opened — innermost in resource-scope terms.
;; Decorator `:teardown` cleans up what decorator `:init` opened —
;; outermost. So we walk loaders-teardown first, then decorator teardown
;; in reverse-declaration order. Matches the rule:
;; "narrower-than-the-decorator stack tears down first".

(defn- apply-loaders-teardown!
  "Walk the variant body's `:loaders-teardown` vector and dispatch-sync
  each event into the variant's frame in declared order. Per `002-
  Runtime.md` §Loader teardown contract.

  Exception handling is identical to `apply-frame-teardown!`: re-frame's
  interceptor chain catches handler throws and emits
  `:rf.error/handler-exception` trace events; a per-walk trace listener
  collects them and projects each onto `[:rf.story/assertions]` as
  `:rf.error/exception` records with `:phase :phase-loaders-teardown`.
  Synchronous throws from outside the interceptor chain are caught
  directly. The walk never aborts `destroy-frame!`."
  [variant-id loaders-teardown-events]
  (let [pending  (atom [])
        drain!   (fn []
                   (when-let [evs (seq @pending)]
                     (reset! pending [])
                     (doseq [tev evs]
                       (let [orig-event (get-in tev [:tags :event])
                             err        (get-in tev [:tags :exception])
                             record     (teardown-exception-record
                                          variant-id :phase-loaders-teardown
                                          orig-event err)]
                         (try
                           (rf/dispatch-sync [::append-teardown-assertion record]
                                             {:frame variant-id})
                           (catch #?(:clj Throwable :cljs :default) _ nil))))))
        listener (fn [ev]
                   (when (and (= :rf.error/handler-exception (:operation ev))
                              (= variant-id (get-in ev [:tags :frame])))
                     (swap! pending conj ev)))]
    (with-teardown-trace-listener
      listener
      (fn []
        (doseq [ev loaders-teardown-events]
          (try
            (rf/dispatch-sync ev {:frame variant-id})
            (catch #?(:clj Throwable :cljs :default) err
              (let [record (teardown-exception-record
                             variant-id :phase-loaders-teardown ev err)]
                (try
                  (rf/dispatch-sync [::append-teardown-assertion record]
                                    {:frame variant-id})
                  (catch #?(:clj Throwable :cljs :default) _ nil)))))
          (drain!))))
    ;; Final drain after the listener unbinds.
    (let [collected @pending]
      (reset! pending [])
      (doseq [tev collected]
        (let [orig-event (get-in tev [:tags :event])
              err        (get-in tev [:tags :exception])
              record     (teardown-exception-record
                           variant-id :phase-loaders-teardown
                           orig-event err)]
          (try
            (rf/dispatch-sync [::append-teardown-assertion record]
                              {:frame variant-id})
            (catch #?(:clj Throwable :cljs :default) _ nil)))))))

(defn install-helpers!
  "Register Story-internal helper events: `::apply-app-db-patch` for
  `:frame-setup` decorators' `:app-db-patch` slot, and
  `::append-teardown-assertion` for the `:teardown` exception
  projection path. Idempotent."
  []
  (when config/enabled?
    (rf/reg-event-db
      ::apply-app-db-patch
      (fn [db [_ patch]]
        (reduce-kv
          (fn [d path v]
            (assoc-in d (if (vector? path) path [path]) v))
          db
          patch)))
    (rf/reg-event-db
      ::append-teardown-assertion
      (fn [db [_ record]]
        (update db :rf.story/assertions (fnil conj []) record)))))

;; ---- allocation -----------------------------------------------------------

(defn- variant-frame-config
  "Construct the `reg-frame` config map for a variant.

  Per spec/002 §reg-frame the framework recognises:
    :preset :story
    :fx-overrides {...}
    plus arbitrary user-stamped keys.

  Per IMPL-SPEC §5.1 we stamp `:rf/story?` and `:rf/variant` so tools
  can recognise variant frames from their `frame-meta`."
  [variant-id fx-overrides]
  (cond-> {:doc        (str "Variant frame for " variant-id ".")
           :preset     :story
           :rf/story?  true
           :rf/variant variant-id}
    (seq fx-overrides) (assoc :fx-overrides fx-overrides)))

(defn allocate!
  "Create the variant's frame, register fx-override stubs, run
  `:frame-setup` decorators' init events, and drive the lifecycle
  machine forward. Returns the variant-id (which doubles as the
  frame-id).

  `decorator-stack` is the result of `decorators/resolve-decorators`;
  the runtime computes it upstream.

  Lifecycle dispatch (rf2-043cm):

  - **Events-only variants** (no `:loaders`, no `:frame-setup`
    decorators, no `:loaders-complete-when`) take the fast-path:
    `:pre-mount → :ready` in one transition via `mount-ready!`. The
    canvas's loading skeleton (rf2-0s4p1) never engages for these
    variants because the lifecycle never reports a loading phase.
  - **All other variants** take the classical four-phase path:
    `mount!` drives `:pre-mount → :mounting`; the runtime then
    advances through `:loading → :ready` via `run-loaders!`.

  If a frame is already registered under `variant-id` (hot-reload
  case), the runtime should `destroy!` first then call `allocate!`.
  Calling `allocate!` against an existing frame goes through
  `reg-frame`'s surgical-update path — config is replaced but the
  app-db / sub-cache are preserved. That's the wrong shape for a
  story-runtime which expects a fresh app-db; the caller (`runtime/
  reset-variant`) destroys first."
  [variant-id decorator-stack]
  (when config/enabled?
    (install-helpers!)
    (let [fx-stack       (decorators/fx-overrides-map (:fx-override decorator-stack))
          fx-overrides   (register-fx-overrides! fx-stack)
          config-map     (variant-frame-config variant-id fx-overrides)
          ;; Inline the variant-body lookup (`variant-body` is defined
          ;; lower in this ns) — go through the registrar directly so
          ;; the events-only classification (rf2-043cm) doesn't depend
          ;; on the file's declaration order.
          v-body         (registrar/handler-meta :variant variant-id)
          events-only?   (loaders/events-only-variant? v-body decorator-stack)]
      ;; Allocate the frame.
      (rf/reg-frame variant-id config-map)
      ;; rf2-043cm — drive the lifecycle by variant shape. Events-only
      ;; variants jump straight to :ready (no skeleton ever shows);
      ;; everything else takes the classical four-phase route through
      ;; :mounting → :loading → :ready.
      (if events-only?
        (loaders/mount-ready! variant-id)
        (loaders/mount!       variant-id))
      ;; Apply :frame-setup decorators (their :init events + :app-db-patch).
      ;; By construction the events-only path has no :frame-setup
      ;; decorators (that's part of the events-only? predicate), so this
      ;; is a no-op on the fast-path branch.
      (apply-frame-setup! variant-id (:frame-setup decorator-stack))
      variant-id)))

;; ---- destruction ----------------------------------------------------------

(defn destroy!
  "Tear down a variant frame. Per `002-Runtime.md` §Loader teardown
  contract — §What the runtime guarantees the destroy walk is:

  1. Drop per-variant assertion accumulators (`drop-assertion-accumulators`
     late-bind shim) + per-frame stub-call log.
  2. Clear lifecycle watchers (`loaders/clear-watchers!`).
  3. Dispatch-sync the variant body's `:loaders-teardown` events in
     declared order (rf2-lqs0b). Exceptions are caught and projected
     into the variant frame's `:rf.story/assertions` as
     `:rf.error/exception` records with
     `:phase :phase-loaders-teardown`. The walk never aborts.
  4. Dispatch-sync the variant's `:frame-setup` decorator `:teardown`
     events in reverse-declaration order. Exceptions are caught and
     projected into the variant frame's `:rf.story/assertions` as
     `:rf.error/exception` records with `:phase :phase-teardown`. The
     walk never aborts.
  5. Machines spawned into the variant frame receive
     `:rf.machine/destroy` (existing spec/005 contract, executed
     inside `rf/destroy-frame!`).
  6. `rf/destroy-frame!` runs the frame's own teardown walk.

  Step 3 fires BEFORE step 4: the variant body's `:loaders-teardown`
  cleans up what `:loaders` opened (innermost in resource-scope terms);
  decorator `:teardown` cleans up what decorator `:init` opened
  (outermost). Matches the rule 'narrower-than-the-decorator stack
  tears down first'.

  Returns nil."
  [variant-id]
  (when config/enabled?
    (loaders/clear-watchers! variant-id)
    (clear-stub-call-log! variant-id)
    (when-let [drop (late-bind/get-fn :drop-assertion-accumulators)]
      (try (drop variant-id) (catch #?(:clj Throwable :cljs :default) _ nil)))
    ;; Step 3 (rf2-lqs0b) — variant body :loaders-teardown. Runs BEFORE
    ;; decorator teardown so loader-installed narrower state is cleaned
    ;; up before decorator-installed wider state.
    (try
      (let [v-body (registrar/handler-meta :variant variant-id)
            evs    (:loaders-teardown v-body)]
        (when (seq evs)
          (apply-loaders-teardown! variant-id evs)))
      (catch #?(:clj Throwable :cljs :default) _ nil))
    ;; Step 4 — :frame-setup decorator :teardown events. Resolve the
    ;; decorator stack here (rather than carrying it through the
    ;; destroy! signature) so the caller surface stays unchanged —
    ;; `destroy-variant!` takes only a variant-id.
    (try
      (let [stack (decorators/resolve-decorators variant-id)]
        (apply-frame-teardown! variant-id (:frame-setup stack)))
      (catch #?(:clj Throwable :cljs :default) _ nil))
    (rf/destroy-frame! variant-id)
    nil))

;; ---- destroy + re-allocate -----------------------------------------------

(defn reset-frame!
  "Destroy the variant frame and re-allocate it. Mirrors the framework's
  `re-frame.core/reset-frame!` naming (rf2-noizc — aligning Story's
  frame helpers with the framework `*-frame!` convention). The caller
  is responsible for re-running the four-phase lifecycle (loaders →
  events → render → play) after."
  [variant-id decorator-stack]
  (destroy! variant-id)
  (allocate! variant-id decorator-stack))

;; ---- frame-meta introspection --------------------------------------------

(defn variant-frame?
  "True iff `frame-id` is a variant frame (was allocated via
  `allocate!`). Reads the frame's `:rf/story?` slot on its frame-meta
  (per Spec-Schemas §`:rf/frame-meta` — flat shape)."
  [frame-id]
  (true? (:rf/story? (rf/frame-meta frame-id))))

(defn variant-frames
  "Return every registered variant frame id. Stage 4's UI shell uses
  this to lay out the active variant pane."
  []
  (->> (rf/frame-ids)
       (filter variant-frame?)
       set))

;; ---- variant-body lookup convenience -------------------------------------

(defn variant-body
  "Return the registered variant body, or nil if unregistered."
  [variant-id]
  (registrar/handler-meta :variant variant-id))
