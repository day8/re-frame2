(ns re-frame.story.frames
  "Per-variant frame allocation. Per `002-Runtime.md` §Per-variant frame allocation + /spec/007-Stories.md
  §Relationship with frames.

  Each rendered variant is its own re-frame frame (spec/002), allocated
  fresh on `run-variant` and torn down by the caller via
  `destroy-variant!`. Story does NOT introduce a new frame substrate —
  it consumes `re-frame.core/make-frame` / `destroy-frame!`. (The framework's
  own `reset-frame!` was retired in rf2-lxwpob — a full replace is now
  `destroy-frame!` + `reg-frame`/`make-frame` composed by the caller; this
  ns's OWN `reset-frame!` below, defined the same way, predates and already
  matches that shape — it is a Story-local convenience wrapper, not a call
  onto the removed framework verb.)

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
    `:initial-events` run.

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
            ;; `reset-state!` reaches the raw frame-state
            ;; write boundary (`frame/replace-frame-state!`) directly so an
            ;; in-place fresh-run reset preserves frame IDENTITY + sub-cache
            ;; + the app-db/runtime-db projection reactions. The epoch-backed
            ;; facade surface (`rf/replace-frame-state!`) is the WRONG seam
            ;; here: it throws `:rf.error/epoch-artefact-missing` when the
            ;; epoch artefact is absent (a published Story jar carries no
            ;; epoch dep), whereas the raw boundary is a pure container write
            ;; with no epoch coupling — exactly what the epoch surface itself
            ;; calls under the hood. Story's `test_support` already reaches
            ;; `re-frame.frame` directly; this is the same boundary.
            [re-frame.frame            :as frame]
            ;; EP-0025: a variant's durable app-db `:sensitive` / `:large`
            ;; classification is applied as commit-plane classification
            ;; effects (the frame annotation is removed). Story reaches the
            ;; pure registry-write seam directly (same artefact as
            ;; `re-frame.frame` above; bundle-isolated from production).
            [re-frame.elision          :as elision]
            ;; EP-0025 fail-loud: a variant's lowered classification effects are
            ;; validated through the SAME pure validator the router's
            ;; commit-plane boundary uses (`elision/classification-effect-defect`)
            ;; and a defect is raised via the canonical thrown-error builder, so
            ;; a malformed declaration FAILS LOUD pre-commit rather than crashing
            ;; inside `apply-classification-effects` / `allocate!`.
            [re-frame.error            :as rf-error]
            [re-frame.story.config     :as config]
            [re-frame.story.decorators :as decorators]
            [re-frame.story.error      :as story-error]
            [re-frame.story.late-bind  :as late-bind]
            [re-frame.story.loaders    :as loaders]
            [re-frame.story.predicates :as pred]
            [re-frame.story.registrar  :as registrar]
            [re-frame.story.runtime-image :as runtime-image]
            ;; Trace-listener pattern for the teardown
            ;; exception-projection path: re-frame's interceptor chain
            ;; catches handler exceptions internally and emits
            ;; `:rf.error/handler-exception` trace events rather than
            ;; re-throwing (per `runtime/capture-phase-errors` —
            ;; the same listener pattern is used here for phase-teardown).
            [re-frame.trace.tooling    :as trace-tooling]))

;; ---- fx-override-stub registration ---------------------------------------
;;
;; Cross-namespace forward references go through the
;; `re-frame.story.late-bind` hub rather than per-shim atoms. One hook:
;;
;;   :drop-assertion-accumulators — play binds this; the frame-teardown
;;                                  path calls it with `(frame-id)` so the
;;                                  per-frame `pending-exceptions` slot
;;                                  evicts its entry.
;;
;; The stub-call log below is the SSOT for stub-redirected fx-ids.
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
  `:fx` handler, not an event handler. The stub appends the call to the
  per-frame `stub-call-log` atom — the SSOT `observed-fx-ids` reads so
  `:rf.assert/effect-emitted` can observe the original fx-id was emitted
  (a stubbed fx lands on the epoch tape under its rewritten stub id, not
  its original id).

  The fx handler runs synchronously inside re-frame's `do-fx` walk
  with signature `(ctx args)` per Spec 002 §Effect handlers; `ctx`
  carries `:frame` and (optionally) `:event`. All bookkeeping lives
  in process-level atoms rather than the frame's app-db."
  [stub-id fx-id response]
  (rf/reg-fx
    stub-id
    (fn [{:keys [frame] :as _ctx} fx-payload]
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

;; ---- shared phase-exception record builder ------------------------------

(defn- phase-exception-record
  "Build the `:rf.error/exception` assertion record projected when a
  lifecycle phase event throws. Per `002-Runtime.md` §Error projection
  and §Loader teardown contract — the record lands in
  `:rf.story/assertions` so the variant's last result-map surfaces the
  failure (the play / variant pane renders it inline).

  `phase` is the originating phase: `:phase-0-setup` (`:frame-setup`
  decorator `:init` events), `:phase-loaders-teardown`
  (variant body's `:loaders-teardown` events), or
  `:phase-teardown` (`:frame-setup` decorator `:teardown` events).

  Thin wrapper over the shared `re-frame.story.error/exception-record`
  — the Throwable→`{:message :stack :data}` projection + the
  `:rf.error/exception` shape are the ONE canonical form. `opts`
  (optional) is threaded through so a drained pipeline-exception trace
  event preserves its originating `:operation` / `:failing-id`."
  ([variant-id phase event err] (phase-exception-record variant-id phase event err nil))
  ([variant-id phase event err opts]
   (story-error/exception-record variant-id phase event err opts)))

(defn- pipeline-event-opts
  "Pull the `:operation` / `:failing-id` attribution off a captured
  pipeline-exception trace event for `phase-exception-record`."
  [tev]
  {:operation  (:operation tev)
   :failing-id (get-in tev [:tags :failing-id])})

;; ---- :frame-setup decorator application ---------------------------------

(defonce ^:private setup-capture-counter (atom 0))

(defn- with-setup-trace-listener
  "Register `listener` against a fresh capture id, run `body-fn` (a
  0-arg thunk), then remove the listener in a `finally`. Returns
  `body-fn`'s return value. Mirrors `with-teardown-trace-listener`;
  used to bracket the `:frame-setup` `:init` dispatch walk so a
  setup-phase pipeline exception is captured."
  [listener body-fn]
  (let [cb-id (keyword "re-frame.story.frames"
                       (str "setup-capture-" (swap! setup-capture-counter inc)))]
    (trace-tooling/register-listener! cb-id listener)
    (try (body-fn)
      (finally (trace-tooling/unregister-listener! cb-id)))))

(defn- apply-frame-setup!
  "Walk the resolved `:frame-setup` decorators and execute their
  `:init` events + `:app-db-patch` against the freshly-allocated frame.
  Per `002-Runtime.md` §Decorator composition order these fire before `:loaders` — so before phase 1
  in the lifecycle.

  Each decorator's `:init` events are dispatched synchronously; the
  `:app-db-patch` (a `path → value` map) is merged into the frame's
  app-db via a registered helper event.

  Exception handling. The walk runs inside its OWN scoped trace listener
  (symmetric with `apply-frame-teardown!`): a pipeline exception (handler
  / coeffect / interceptor) targeting the frame is captured and
  projected onto `[:rf.story/assertions]` as a `:rf.error/exception`
  record with `:phase :phase-0-setup`, so the run aggregates as failed —
  a throwing `:init` handler whose declared preconditions never installed
  surfaces rather than reporting a false green. Synchronous throws from
  outside the interceptor chain are caught directly. The walk continues
  so every setup failure is recorded."
  [frame-id frame-setup-decorators]
  (when (seq frame-setup-decorators)
    (let [pending  (atom [])
          drain!   (fn []
                     (when-let [evs (seq @pending)]
                       (reset! pending [])
                       (doseq [tev evs]
                         (let [orig-event (get-in tev [:tags :event])
                               err        (get-in tev [:tags :exception])
                               record     (phase-exception-record
                                            frame-id :phase-0-setup
                                            orig-event err
                                            (pipeline-event-opts tev))]
                           (try
                             (rf/dispatch-sync [::append-teardown-assertion record]
                                               {:frame frame-id})
                             (catch #?(:clj Throwable :cljs :default) _ nil))))))
          listener (fn [ev]
                     (when (story-error/pipeline-exception-event? frame-id ev)
                       (swap! pending conj ev)))]
      (with-setup-trace-listener
        listener
        (fn []
          (doseq [r frame-setup-decorators]
            (let [body        (:body r)
                  init-events (:init body)
                  patch       (:app-db-patch body)]
              (when patch
                (try
                  (rf/dispatch-sync [::apply-app-db-patch patch] {:frame frame-id})
                  (catch #?(:clj Throwable :cljs :default) err
                    (let [record (phase-exception-record
                                   frame-id :phase-0-setup
                                   [::apply-app-db-patch patch] err)]
                      (try
                        (rf/dispatch-sync [::append-teardown-assertion record]
                                          {:frame frame-id})
                        (catch #?(:clj Throwable :cljs :default) _ nil)))))
                (drain!))
              (doseq [ev init-events]
                (try
                  (rf/dispatch-sync ev {:frame frame-id})
                  (catch #?(:clj Throwable :cljs :default) err
                    (let [record (phase-exception-record
                                   frame-id :phase-0-setup ev err)]
                      (try
                        (rf/dispatch-sync [::append-teardown-assertion record]
                                          {:frame frame-id})
                        (catch #?(:clj Throwable :cljs :default) _ nil)))))
                (drain!))))))
      ;; Final drain — catch any trace events delivered after the last
      ;; dispatch inside the listener-bound region (belt-and-braces).
      (let [collected @pending]
        (reset! pending [])
        (doseq [tev collected]
          (let [orig-event (get-in tev [:tags :event])
                err        (get-in tev [:tags :exception])
                record     (phase-exception-record
                             frame-id :phase-0-setup orig-event err
                             (pipeline-event-opts tev))]
            (try
              (rf/dispatch-sync [::append-teardown-assertion record]
                                {:frame frame-id})
              (catch #?(:clj Throwable :cljs :default) _ nil))))))))

;; ---- :frame-setup decorator teardown -------------------------------------

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
                             record     (phase-exception-record
                                          variant-id :phase-teardown
                                          orig-event err
                                          (pipeline-event-opts tev))]
                         (try
                           (rf/dispatch-sync [::append-teardown-assertion record]
                                             {:frame variant-id})
                           (catch #?(:clj Throwable :cljs :default) _ nil))))))
        listener (fn [ev]
                   ;; Capture every pipeline exception
                   ;; (handler / coeffect / interceptor), not just
                   ;; handler-exception: a teardown event whose cofx
                   ;; injector or user interceptor throws is a first-class
                   ;; failure too.
                   (when (story-error/pipeline-exception-event? variant-id ev)
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
                  (let [record (phase-exception-record
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
              record     (phase-exception-record
                           variant-id :phase-teardown orig-event err
                           (pipeline-event-opts tev))]
          (try
            (rf/dispatch-sync [::append-teardown-assertion record]
                              {:frame variant-id})
            (catch #?(:clj Throwable :cljs :default) _ nil)))))))

;; ---- variant body :loaders-teardown walk --------------------------------
;;
;; Symmetric counterpart of `:loaders` on the variant body. A
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
                             record     (phase-exception-record
                                          variant-id :phase-loaders-teardown
                                          orig-event err
                                          (pipeline-event-opts tev))]
                         (try
                           (rf/dispatch-sync [::append-teardown-assertion record]
                                             {:frame variant-id})
                           (catch #?(:clj Throwable :cljs :default) _ nil))))))
        listener (fn [ev]
                   ;; Capture every pipeline exception
                   ;; (handler / coeffect / interceptor), not just
                   ;; handler-exception (a loaders-teardown event whose
                   ;; cofx injector / user interceptor throws is a
                   ;; first-class failure too).
                   (when (story-error/pipeline-exception-event? variant-id ev)
                     (swap! pending conj ev)))]
    (with-teardown-trace-listener
      listener
      (fn []
        (doseq [ev loaders-teardown-events]
          (try
            (rf/dispatch-sync ev {:frame variant-id})
            (catch #?(:clj Throwable :cljs :default) err
              (let [record (phase-exception-record
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
              record     (phase-exception-record
                           variant-id :phase-loaders-teardown
                           orig-event err)]
          (try
            (rf/dispatch-sync [::append-teardown-assertion record]
                              {:frame variant-id})
            (catch #?(:clj Throwable :cljs :default) _ nil)))))))

(defn install-canonical-frame-events!
  "Register Story-internal helper events: `::apply-app-db-patch` for
  `:frame-setup` decorators' `:app-db-patch` slot, and
  `::append-teardown-assertion` for the `:teardown` exception
  projection path. Idempotent.

  Mirrors the `install-canonical-<X>!` shape used by every sibling
  installer in `canonical/canonical-installers` (`install-canonical-tags!`,
  `install-canonical-assertions!`, `install-canonical-fx-stubs!`, …); the
  name states what's installed."
  []
  (when config/enabled?
    (rf/reg-event
      ::apply-app-db-patch
      (fn [{:keys [db]} [_ patch]]
        {:db (reduce-kv
               (fn [d path v]
                 (assoc-in d (if (vector? path) path [path]) v))
               db
               patch)}))
    (rf/reg-event
      ::append-teardown-assertion
      (fn [{:keys [db]} [_ record]]
        {:db (update db :rf.story/assertions (fnil conj []) record)}))))

;; ---- allocation -----------------------------------------------------------

(defn- variant-frame-config
  "Construct the `reg-frame` config map for a variant.

  Per spec/002 §reg-frame the framework recognises:
    :preset :story
    :fx-overrides {...}
    plus arbitrary user-stamped keys.

  Per `002-Runtime.md` §Per-variant frame allocation we stamp `:rf/story?` and `:rf/variant` so tools
  can recognise variant frames from their `frame-meta`.

  EP-0025: a variant's durable app-db `:sensitive` / `:large` classification
  is NO LONGER threaded onto the `reg-frame` config — the frame annotation is
  removed. It is applied as commit-plane classification effects into the
  frame's elision registry right after `make-frame`, before the lifecycle /
  init events run (see `apply-variant-classification!`)."
  [variant-id fx-overrides {:keys [image-ids]}]
  (cond-> {:doc        (str "Variant frame for " variant-id ".")
           :preset     :story
           :rf/story?  true
           :rf/variant variant-id}
    (seq fx-overrides) (assoc :fx-overrides fx-overrides)
    ;; EP-0023 §Stories — report the IMAGE ids this behaviour-variant frame
    ;; resolves behaviour against, on frame-meta, so Test mode / MCP / Xray can
    ;; surface which behaviour set ran. The actual image
    ;; GENERATION rides the frame's record (the `:generation` slot), written by
    ;; `rf/make-frame` in `allocate!` (EP-0024); this slot carries
    ;; the authored ids for tooling read-back.
    (seq image-ids)    (assoc :rf/images (vec image-ids))))

(defn- image-id
  "The id of an `rf/image` value — its `:rf.image/id` slot, or nil for an
  anonymous image (valid for local stories per EP-0023 §Public API)."
  [image]
  (:rf.image/id image))

(defn- story-images
  "The parent story's declared APP image(s) for `variant-id`, or `[]`. A Story
  declares its application image ONCE on the story body's `:images` slot; every
  variant under it inherits that image (the variant may LAYER its own `:images`
  on top — see `compose-variant-images`). Resolved off the parent story body via
  the variant→story id derivation (`pred/parent-story-id`), mirroring how
  `re-frame.story.args/resolve-args` folds the parent-story `:args` layer."
  [variant-id]
  (let [story-id   (pred/parent-story-id variant-id)
        story-body (when story-id (registrar/handler-meta :story story-id))]
    (vec (:images story-body))))

(defn- compose-variant-images
  "Build the FULL `:images` vector a variant frame is created with (EP-0026
  §Layered Resolution — the later image WINS). The composition is:

      [<story-images…> <variant-images…> runtime-image]

  - `story-images`   — the parent story's `:images` (the app image declared once
                       on the story; inherited by every variant). See
                       `story-images`.
  - `variant-images` — the variant body's own `:images`, layered on top of the
                       story image (a BEHAVIOUR variant overriding specific
                       `[kind id]`s with its own image).
  - `runtime-image`  — the canonical Story RUNTIME IMAGE, composed LAST so the
                       Story machinery (lifecycle machine, `:rf.assert/*`
                       handlers, fx-stub redirects, runtime helpers, toolbar
                       cofx) is always live in the frame and never shadowed out
                       by an app image (`re-frame.story.runtime-image`).

  When NO application image resolves anywhere — none on the variant, none on its
  parent story — the composition is `nil`, the absence-is-DEFAULT signal:
  `allocate!` then creates the frame with NO `:images`, so `rf/make-frame`
  resolves the EP-0026 DEFAULT image (the whole-store projection) for the
  convenient single-app / standalone-testbed case. (A colliding MULTI-app
  testbed scopes itself by declaring a story `:images` selecting its own app
  namespace.) `runtime-image` is composed ONLY when an app image is present —
  the default-image path already projects the whole store, which includes the
  Story runtime, so the runtime image would be redundant (and an `:images`
  vector carrying ONLY the runtime image would scope the frame to the Story
  machinery alone, hiding the app)."
  [variant-id variant-images]
  (let [story-imgs (story-images variant-id)
        app-imgs   (into (vec story-imgs) variant-images)]
    (when (seq app-imgs)
      (conj app-imgs (runtime-image/runtime-image)))))

(defn- ->classification-effects
  "Convert a variant body's `:sensitive` / `:large` classification declaration
  (the durable app-db form `{:app-db [[path]…]}`) into the flat EP-0025
  commit-plane effect form `{:sensitive [[path]…] :large [[path]…]}`. The
  variant `:sensitive` / `:large` slots carry only `:app-db` paths — EP-0025
  retired the frame `:sensitive {:http}` carrier block (HTTP carriers moved
  onto the `:rf.http/managed` `reg-fx` registration's `:carriers` block), so
  only the `:app-db` paths are lowered here. Returns nil when no app-db paths
  are declared."
  [{:keys [sensitive large]}]
  (let [sens (:app-db sensitive)
        lrg  (:app-db large)]
    (cond-> {}
      (seq sens) (assoc :sensitive (vec sens))
      (seq lrg)  (assoc :large (vec lrg)))))

(defn- apply-variant-classification!
  "Apply a variant/plan's durable app-db `:sensitive` / `:large`
  classification as EP-0025 commit-plane classification effects into
  `subject-id`'s elision registry (`:source :effect`), the SAME registry
  write a `reg-event` returning those effects performs. Runs right after
  the frame exists (`make-frame` for `allocate!`, `rf/reg-frame` for
  `allocate-inline!`) and BEFORE the lifecycle / init events, so a
  classified path is already redacted in any trace the run's setup emits.
  No-op when `v-body` declares no app-db classification. Shared by both
  `allocate!` (a registered variant's own body) and `allocate-inline!`
  (rf2-cmjly3 finding 12 — an inline plan's `[:world :sensitive]` /
  `[:world :large]`, threaded through by the caller since an inline run
  has no registered variant body for this fn to read).

  EP-0025 fail-loud: the lowered effects are validated through the SAME
  pure validator the router's FINAL-effects commit-plane boundary uses
  (`elision/classification-effect-defect`) BEFORE `apply-classification-effects`
  touches the registry. A malformed declaration (a non-vector `:app-db`, or a
  path that is not a valid concrete `:rf/path`) raises
  `:rf.error/classification-effect-shape` pre-commit — no partial registry
  write — rather than crashing later inside `apply-classification-effects`.
  This mirrors the router's pre-commit abort
  (`router/emit-classification-effect-shape!`) so the variant-declaration and
  effect-return paths reject malformed classification identically.

  Called AFTER the frame is registered (both callers), not before: for
  `allocate-inline!` this means a malformed declaration can leave an
  orphan anonymous frame behind (`run-inline-plan`'s failure-path teardown
  only fires once `allocate-inline!` has RETURNED normally). That was
  deliberately chosen over validating pre-registration — doing so left the
  throw with NO live frame for `record-error!`'s `::append-assertion`
  dispatch to land in, so the malformed-classification failure was
  silently swallowed as a vacuous `:status :pass` with zero assertions —
  strictly worse than an orphaned dev-tool frame. It is also no NEW risk
  class: every other throw site between `rf/reg-frame` and the
  `allocated?` flip (`loaders/mount!` / `apply-frame-setup!`'s `:init`
  events) already shares it."
  [subject-id v-body]
  (let [effects (->classification-effects v-body)]
    (when (seq effects)
      (when-let [defect (elision/classification-effect-defect effects)]
        (rf-error/throw-error!
          :rf.error/classification-effect-shape
          'rf.story/apply-variant-classification!
          (str "re-frame2-story: " subject-id
               " declares a malformed `:sensitive` / `:large` classification — "
               (:reason defect)
               ". Each axis is a carrier-keyed map whose `:app-db` value is a "
               "vector of concrete `:rf/path`s "
               "(e.g. `:sensitive {:app-db [[:auth :token]]}`).")
          {:recovery :fix-the-variant-classification-shape
           :extra    (assoc defect :variant-id subject-id)}))
      (frame/swap-runtime-db! subject-id
        (fn [rt] (elision/apply-classification-effects rt effects))))))

(defn allocate!
  "Create the variant's frame, register fx-override stubs, run
  `:frame-setup` decorators' init events, and drive the lifecycle
  machine forward. Returns the variant-id (which doubles as the
  frame-id).

  `decorator-stack` is the result of `decorators/resolve-decorators`;
  the runtime computes it upstream.

  Lifecycle dispatch:

  - **Events-only variants** (no `:loaders`, no `:frame-setup`
    decorators, no `:loaders-complete-when`) take the fast-path:
    `:pre-mount → :ready` in one transition via `mount-ready!`. The
    canvas's loading skeleton never engages for these
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
    (install-canonical-frame-events!)
    (let [fx-stack       (decorators/fx-overrides-map (:fx-override decorator-stack))
          fx-overrides   (register-fx-overrides! fx-stack)
          ;; Inline the variant-body lookup (`variant-body` is defined
          ;; lower in this ns) — go through the registrar directly so
          ;; the events-only classification doesn't depend
          ;; on the file's declaration order.
          v-body         (registrar/handler-meta :variant variant-id)
          ;; EP-0026 §Default Image / §Layered Resolution — the FULL `:images`
          ;; vector this variant frame is created with:
          ;;
          ;;   [<story-images…> <variant-images…> runtime-image]   (later wins)
          ;;
          ;; A Story declares its APP image ONCE on the parent story body's
          ;; `:images`; every variant inherits it and MAY layer its own
          ;; `:images` (a BEHAVIOUR variant overriding specific `[kind id]`s).
          ;; The canonical Story RUNTIME IMAGE is composed LAST so the Story
          ;; machinery (lifecycle machine, `:rf.assert/*` handlers, fx-stub
          ;; redirects, runtime helpers, toolbar cofx) is always live in the
          ;; frame and never shadowed out by an app image. See
          ;; `compose-variant-images`.
          ;;
          ;; `nil` when NO app image resolves anywhere (none on the variant,
          ;; none on its story): the absence-is-DEFAULT signal — the frame is
          ;; created with NO `:images`, so `rf/make-frame` resolves the EP-0026
          ;; DEFAULT image (the whole-store projection) for the convenient
          ;; single-app / standalone-testbed case.
          composed-imgs  (compose-variant-images variant-id (:images v-body))
          ;; The variant's own behaviour-variant image ids (for frame-meta
          ;; tooling read-back) — the AUTHORED app images, NOT the
          ;; library-composed runtime image (a library contract, not an
          ;; author-declared behaviour set).
          author-images  (into (vec (story-images variant-id))
                                (:images v-body))
          ;; Thread the variant's EP-0015 frame-owned
          ;; `:sensitive` / `:large` classification onto the reg-frame config,
          ;; plus the image ids (for frame-meta tooling read-back).
          config-map     (variant-frame-config
                           variant-id fx-overrides
                           (assoc (select-keys v-body [:sensitive :large])
                                  :image-ids (keep image-id author-images)))
          events-only?   (loaders/events-only-variant? v-body decorator-stack)]
      ;; EP-0023 §Stories / §Frame-derived live registration resolution
      ;; — the frame carries the resolved, sealed image GENERATION so
      ;; `process-event!`'s `call-with-frame-resolution` routes the whole cascade
      ;; (event-handler lookup, cofx, fx) for any `{:frame variant-id}` dispatch
      ;; through THIS frame's image generation. The runtime image keeps the Story
      ;; machinery live in that generation; an app image scopes the frame to its
      ;; own registrations so two variants reuse the SAME global id with
      ;; DIFFERENT meanings ("behavior variant -> image", EP-0023 §Stories). When
      ;; NO app image resolves, the frame resolves the EP-0026 DEFAULT image (the
      ;; whole-store projection) — absence-is-default. The framework's `rf/image`
      ;; / assembly own all validation — a malformed image or a zero-match
      ;; `:select-ns :include` glob FAILS LOUDLY here at frame creation.
      ;;
      ;; EP-0024 — ONE `make-frame` call. The unified constructor accepts
      ;; BOTH the image-selection opts AND the full record-config in one
      ;; call over the one registry. `:images` is supplied only when an app
      ;; image resolves (absent ⇒ the default image generation).
      (rf/make-frame (cond-> {:id variant-id}
                       (seq composed-imgs) (assoc :images composed-imgs)
                       :always             (merge config-map)))
      ;; EP-0025: apply the variant's durable app-db `:sensitive` / `:large`
      ;; classification as commit-plane classification effects into the frame's
      ;; elision registry NOW — after the container exists, BEFORE the
      ;; lifecycle / init / frame-setup events run, so a classified path is
      ;; already redacted in any trace the variant's setup emits. (The frame
      ;; annotation that previously rode `reg-frame` is removed.)
      (apply-variant-classification! variant-id v-body)
      ;; Drive the lifecycle by variant shape. Events-only
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

;; ---- inline-plan allocation ----------------------------------------------
;;
;; An inline plan (spec/017 §Inline plan) is an executable plan map that is
;; NOT registered as a Story variant — so `allocate!`'s side-table reads
;; (the `variant-body` lookup for the events-only classification, the
;; decorator-stack resolution from the variant/story `:decorators` slots)
;; have nothing to read. `allocate-inline!` is the registry-free twin:
;; everything it needs is already on the compiled plan + the supplied
;; decorator stack (resolved from the plan's `[:world :decorators]` refs via
;; `decorators/resolve-decorator-refs`). The allocated frame is stamped
;; `:rf/inline? true` so tools / navigation can tell an inline run's frame
;; apart from a registered variant's frame — an inline plan MUST NOT appear
;; in Story navigation.

(defn- inline-frame-config
  "Construct the `reg-frame` config map for an inline-plan frame. Mirrors
  `variant-frame-config` but stamps `:rf/inline? true` (the inline run's
  anonymous frame is never a navigable variant)."
  [frame-id fx-overrides]
  (cond-> {:doc        (str "Inline-plan frame for " frame-id ".")
           :preset     :story
           :rf/story?  true
           :rf/inline? true
           :rf/variant frame-id}
    (seq fx-overrides) (assoc :fx-overrides fx-overrides)))

(defn allocate-inline!
  "Allocate an anonymous frame for an inline plan run (spec/017
  §Inline plan). Registry-free twin of `allocate!`:

  - `frame-id` — the anonymous frame id the runtime minted (NOT a
    registered variant id);
  - `decorator-stack` — `decorators/resolve-decorator-refs` over the plan's
    `[:world :decorators]` refs (the runtime resolves it upstream);
  - `plan-fx-overrides` — the plan's `[:world :frame :fx-overrides]` lowered
    map (e.g. the managed-HTTP stub the compiler folded `:network` into);
  - `events-only?` — whether the plan drives no loaders / frame-setup, so
    the lifecycle takes the `:pre-mount → :ready` fast-path;
  - `classification` (optional, 5-arity; rf2-cmjly3 finding 12) — the
    plan's `(select-keys (:world plan) [:sensitive :large])` map (the
    caller, `run-inline-phase-0!`, threads this from the compiled plan —
    `plan.cljc`'s `context-keys` now carries `:sensitive`/`:large` through
    to `[:world :sensitive]` / `[:world :large]`). An inline plan run has
    no registered variant body for `apply-variant-classification!` to read
    (the way `allocate!` does via `v-body`), so without this arg a
    `:sensitive`/`:large` declaration on an inline plan map compiled +
    ran with NO error and NO redaction — a value marked sensitive silently
    rode into the wire trace unredacted. Defaults to nil (no
    classification), which `apply-variant-classification!` already
    no-ops on.

  Registers the `:fx-override`-decorator stubs, applies the plan's
  `:sensitive`/`:large` classification into the frame's elision registry
  (mirrors `allocate!`'s `apply-variant-classification!` exactly — same
  ordering: AFTER `rf/reg-frame`, BEFORE the lifecycle/init events run),
  drives the lifecycle by the supplied shape, applies any `:frame-setup`
  decorators' `:init` events, and returns the frame-id. The frame's
  effective `:fx-overrides` is the decorator-stack's materialised stubs
  UNDER the plan's lowered map (the plan map wins — it already merged the
  author/network overrides). Does NOT register anything in the Story
  side-table.

  rf2-cmjly3 finding 12 follow-on: classification is validated AFTER
  `rf/reg-frame` (NOT before), even though that means a malformed
  declaration can leave an anonymous inline frame registered with nothing
  to tear it down (`run-inline-plan`'s failure path only tears down once
  `allocate-inline!` has RETURNED normally). That risk is accepted, not
  overlooked: validating BEFORE `rf/reg-frame` was tried and reverted — it
  left the frame-less throw with NO live frame for `record-error!`'s
  `::append-assertion` dispatch to land in, so the malformed-classification
  failure was silently swallowed (`:status :pass`, zero assertions — a
  privacy-relevant defect reported as a vacuous green, strictly worse than
  an orphaned dev-tool frame). Validating after `rf/reg-frame` — the SAME
  position `allocate!` already uses for the registered path — keeps
  fail-loud reporting correct and is no NEW risk class: every other
  throw site between `rf/reg-frame` and the `allocated?` flip
  (`loaders/mount!` / `apply-frame-setup!`'s `:init` events) already shares
  it."
  ([frame-id decorator-stack plan-fx-overrides events-only?]
   (allocate-inline! frame-id decorator-stack plan-fx-overrides events-only? nil))
  ([frame-id decorator-stack plan-fx-overrides events-only? classification]
   (when config/enabled?
     (install-canonical-frame-events!)
     (let [fx-stack     (decorators/fx-overrides-map (:fx-override decorator-stack))
           decor-fx     (register-fx-overrides! fx-stack)
           fx-overrides (merge decor-fx plan-fx-overrides)
           config-map   (inline-frame-config frame-id fx-overrides)]
       (rf/reg-frame frame-id config-map)
       (apply-variant-classification! frame-id classification)
       (if events-only?
         (loaders/mount-ready! frame-id)
         (loaders/mount!       frame-id))
       (apply-frame-setup! frame-id (:frame-setup decorator-stack))
       frame-id))))

(defn destroy-inline!
  "Tear down an inline-plan frame. The registry-free twin of
  `destroy!`: an inline frame has no registered body, so the side-table
  reads `destroy!` makes (the variant `:loaders-teardown`, the decorator
  re-resolution) have nothing to read. `decorator-stack` is the SAME stack
  `allocate-inline!` was handed; `loaders-teardown` is the plan's
  `[:world :loaders-teardown]` events (empty for the common headless case).

  Walks `:loaders-teardown` then the `:frame-setup` decorators' `:teardown`
  events (reverse-declaration order), then `destroy-frame!`. Exceptions are
  caught and projected as `:rf.error/exception` records the same way
  `destroy!` does; the walk never aborts."
  [frame-id decorator-stack loaders-teardown]
  (when config/enabled?
    (loaders/clear-watchers! frame-id)
    (clear-stub-call-log! frame-id)
    (when-let [drop (late-bind/get-fn :drop-assertion-accumulators)]
      (try (drop frame-id) (catch #?(:clj Throwable :cljs :default) _ nil)))
    ;; Evict the play-runner's per-frame run-state on teardown
    ;; (symmetric with `destroy!`); an inline frame that drove a script
    ;; leaves no stale run-state behind once it is torn down.
    (when-let [drop (late-bind/get-fn :drop-run-state)]
      (try (drop frame-id) (catch #?(:clj Throwable :cljs :default) _ nil)))
    (when (seq loaders-teardown)
      (try (apply-loaders-teardown! frame-id loaders-teardown)
        (catch #?(:clj Throwable :cljs :default) _ nil)))
    (try (apply-frame-teardown! frame-id (:frame-setup decorator-stack))
      (catch #?(:clj Throwable :cljs :default) _ nil))
    (rf/destroy-frame! frame-id)
    nil))

;; ---- destruction ----------------------------------------------------------

(defn- run-teardown-walks!
  "Run the variant-frame teardown bookkeeping + dispatch walks that
  precede the actual frame disposal (steps 1-4 of the `destroy!`
  contract):

  1. Clear lifecycle watchers + per-frame stub-call log; drop the
     per-variant assertion accumulators + the play-runner's per-frame
     run-state via the late-bind hooks.
  2. Dispatch-sync the variant body's `:loaders-teardown` events
     (declared order) — cleans up loader-installed narrower
     state. BEFORE the decorator teardown.
  3. Dispatch-sync the `:frame-setup` decorator `:teardown` events
     (reverse-declaration order) — cleans up decorator-installed wider
     state.

  Shared by `destroy!` (which follows with `rf/destroy-frame!`) and the
  in-place `reset-state!` (which follows with a frame-state
  reset instead) so both paths run the SAME symmetric external-resource
  cleanup. Exceptions in each walk are caught + projected onto the
  variant's `[:rf.story/assertions]`; the walks never abort."
  [variant-id]
  (loaders/clear-watchers! variant-id)
  (clear-stub-call-log! variant-id)
  (when-let [drop (late-bind/get-fn :drop-assertion-accumulators)]
    (try (drop variant-id) (catch #?(:clj Throwable :cljs :default) _ nil)))
  ;; Evict the play-runner's per-frame run-state on teardown
  ;; (run-state / runs-by-play / active-play / step-boundaries) so a
  ;; torn-down frame leaves no stale terminal play status behind. Routed
  ;; through the `:drop-run-state` late-bind hook (frames cannot :require
  ;; runner-events — cycle).
  (when-let [drop (late-bind/get-fn :drop-run-state)]
    (try (drop variant-id) (catch #?(:clj Throwable :cljs :default) _ nil)))
  ;; Step 3 — variant body :loaders-teardown. Runs BEFORE
  ;; decorator teardown so loader-installed narrower state is cleaned
  ;; up before decorator-installed wider state.
  (try
    (let [v-body (registrar/handler-meta :variant variant-id)
          evs    (:loaders-teardown v-body)]
      (when (seq evs)
        (apply-loaders-teardown! variant-id evs)))
    (catch #?(:clj Throwable :cljs :default) _ nil))
  ;; Step 4 — :frame-setup decorator :teardown events. Resolve the
  ;; decorator stack here (rather than carrying it through the caller
  ;; signature) so the caller surface stays unchanged.
  (try
    (let [stack (decorators/resolve-decorators variant-id)]
      (apply-frame-teardown! variant-id (:frame-setup stack)))
    (catch #?(:clj Throwable :cljs :default) _ nil))
  nil)

(defn destroy!
  "Tear down a variant frame. Per `002-Runtime.md` §Loader teardown
  contract — §What the runtime guarantees the destroy walk is:

  1. Drop per-variant assertion accumulators (`drop-assertion-accumulators`
     late-bind shim) + per-frame stub-call log.
  2. Clear lifecycle watchers (`loaders/clear-watchers!`).
  3. Dispatch-sync the variant body's `:loaders-teardown` events in
     declared order. Exceptions are caught and projected
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
    (run-teardown-walks! variant-id)
    (rf/destroy-frame! variant-id)
    nil))

;; ---- in-place fresh-run reset --------------------------------------------

(defn reset-state!
  "Reset an EXISTING variant frame to fresh state IN PLACE — preserving
  the frame's IDENTITY, sub-cache, and app-db/runtime-db projection
  reactions — instead of `destroy!` + re-`allocate!`.

  Why this exists. `run-variant` must enforce a fresh-run boundary so a
  second run on the same id does NOT inherit the first run's app-db and
  so loader variants re-run their loaders (the lifecycle machine must
  leave `:ready`). A `destroy!` + `allocate!` would achieve fresh state
  but DESTROY the frame: `destroy-frame!` tears down the sub-cache and
  the partition projections. When the canvas has ALREADY mounted the
  variant view under `frame-provider {:frame variant-id}` (its
  `@(subscribe …)` reactions wired to THIS frame's sub-cache) before
  `:component-did-mount` → `run-variant` runs, a destroy would ORPHAN
  every live reaction: a subsequent `:counter/set 42` would update the
  freshly re-allocated frame's app-db, but the stale reaction would never
  observe it, so the DOM would never re-render. This is invisible to the
  JVM (no React reconciliation).

  Instead we reset in place. We run the SAME teardown walks `destroy!`
  runs (so loader/decorator-installed external resources are cleaned up
  symmetrically), then overwrite BOTH frame-state partitions with `{}`
  via the raw `frame/replace-frame-state!` write boundary — the same
  atomic single-container install the epoch surface uses, but WITHOUT the
  epoch coupling. Because the physical container, its projection
  reactions, and the sub-cache all survive, the live mounted view's
  reactions recompute over the reset value and re-render. A fresh frame
  starts with app-db `{}` + runtime-db `{}` (Spec 002 §Frames always
  start with app-db = {}); resetting runtime-db to `{}` drops the
  lifecycle machine snapshot, so `loaders/current-state` reports
  `:pre-mount` and the re-run's loaders fire (no `:ready` short-circuit).

  No-op (returns nil) when the frame is not registered — the caller
  (`run-phase-0!`) only resets when a frame already exists; the fresh
  `allocate!` that follows builds the clean frame for the never-seen
  case. Returns nil."
  [variant-id]
  (when config/enabled?
    (run-teardown-walks! variant-id)
    ;; Overwrite both partitions with the fresh-frame empty state through
    ;; the one physical frame-state container — preserves frame identity,
    ;; sub-cache, and projection reactions (live view reactions survive).
    (frame/replace-frame-state!
      variant-id
      {frame/app-partition-key     {}
       frame/runtime-partition-key {}})
    nil))

;; ---- destroy + re-allocate -----------------------------------------------

(defn reset-frame!
  "Destroy the variant frame and re-allocate it. Named for the framework's
  `*-frame!` convention (Story-local — the framework's own `re-frame.core/
  reset-frame!` was retired in rf2-lxwpob; this predates and already matches
  its replacement shape, `destroy-frame!` + re-`reg-frame`/`make-frame`
  composed by the caller, so no change was needed here). The caller
  is responsible for re-running the four-phase lifecycle (loaders →
  events → render → play) after.

  NOTE the in-place `reset-state!` is the path
  `run-variant`'s fresh-run boundary takes when a frame already exists —
  it preserves live view reactions. This destroy + re-allocate helper is
  the explicit full-teardown variant retained for callers that genuinely
  want the frame torn down and rebuilt."
  [variant-id decorator-stack]
  (destroy! variant-id)
  (allocate! variant-id decorator-stack))

;; ---- frame-meta introspection --------------------------------------------

(defn variant-frame?
  "True iff `frame-id` is a NAVIGABLE variant frame (was allocated via
  `allocate!`). Reads the frame's frame-meta (per Spec-Schemas
  §`:rf/frame-meta` — flat shape): true iff it carries `:rf/story?` AND is
  NOT stamped `:rf/inline?`.

  An inline-plan frame (allocated via `allocate-inline!`) shares the
  `:rf/story?` stamp — it IS a Story-managed frame — but is stamped
  `:rf/inline? true` and MUST NOT appear in Story navigation (spec/017
  §Inline plan, UNCONDITIONALLY — not merely post-teardown). Excluding it
  here makes `:rf/inline?` load-bearing: it is the discriminator that keeps
  an in-flight inline frame out of the live-frame enumeration during the
  window between `allocate-inline!` and `destroy-inline!`, closing the
  transient gap that side-table absence alone (no registration) does not
  cover."
  [frame-id]
  (let [m (rf/frame-meta frame-id)]
    (and (true? (:rf/story? m))
         (not (:rf/inline? m)))))

(defn variant-frames
  "Return every registered variant frame id. Stage 4's UI shell uses
  this to lay out the active variant pane."
  []
  (->> (rf/frame-ids)
       (filter variant-frame?)
       set))

(defn variant-image-ids
  "Return the vector of behaviour-variant IMAGE ids a variant frame resolves
  behaviour against (EP-0023 §Stories), or nil for an ordinary
  state variant that resolves against the shared default registrar. Reads the
  `:rf/images` slot `allocate!` stamps onto frame-meta. The companion read for
  Test mode / MCP / Xray to surface WHICH behaviour set ran."
  [frame-id]
  (:rf/images (rf/frame-meta frame-id)))

;; ---- variant-body lookup convenience -------------------------------------

(defn variant-body
  "Return the registered variant body, or nil if unregistered."
  [variant-id]
  (registrar/handler-meta :variant variant-id))
