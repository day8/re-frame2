(ns re-frame.story.frames
  "Per-variant frame allocation. Per `002-Runtime.md` §Per-variant frame allocation + /spec/007-Stories.md
  §Relationship with frames.

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
            ;; rf2-294yq5 — `reset-state!` reaches the raw frame-state
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
            [re-frame.story.config     :as config]
            [re-frame.story.decorators :as decorators]
            [re-frame.story.error      :as story-error]
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
;; `re-frame.story.late-bind` hub rather than per-shim atoms. One hook:
;;
;;   :drop-assertion-accumulators — play binds this; the frame-teardown
;;                                  path calls it with `(frame-id)` so the
;;                                  per-frame `pending-exceptions` slot
;;                                  evicts its entry.
;;
;; (rf2-luzky removed the `:tap-stub-event` hook — the stub-call log below
;; is already the SSOT for stub-redirected fx-ids, so the dev-mirror tap it
;; drove is gone.)
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
  in process-level atoms rather than the frame's app-db. Stage 5
  (rf2-h8et)."
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
  decorator `:init` events — rf2-294yq5.1), `:phase-loaders-teardown`
  (variant body's `:loaders-teardown` events — rf2-lqs0b), or
  `:phase-teardown` (`:frame-setup` decorator `:teardown` events —
  rf2-dg2uh).

  Thin wrapper over the shared `re-frame.story.error/exception-record`
  (rf2-9kpsq) — the Throwable→`{:message :stack :data}` projection + the
  `:rf.error/exception` shape are the ONE canonical form. `opts`
  (optional) is threaded through so a drained pipeline-exception trace
  event preserves its originating `:operation` / `:failing-id`
  (rf2-294yq5.2)."
  ([variant-id phase event err] (phase-exception-record variant-id phase event err nil))
  ([variant-id phase event err opts]
   (story-error/exception-record variant-id phase event err opts)))

(defn- pipeline-event-opts
  "Pull the `:operation` / `:failing-id` attribution off a captured
  pipeline-exception trace event for `phase-exception-record`
  (rf2-294yq5.2)."
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
  setup-phase pipeline exception is captured (rf2-294yq5.1)."
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

  Exception handling (rf2-294yq5.1). Previously the `:init` dispatch
  walk ran INSIDE `allocate!` before the runtime's per-frame trace
  listener existed, so re-frame's interceptor chain captured a
  throwing `:init` handler into a trace event that nothing observed —
  `run-variant` reported `:pass` / `:ready` against a frame whose
  declared preconditions never installed (a false green). We now
  bracket the walk in its OWN scoped trace listener (symmetric with
  `apply-frame-teardown!`): a pipeline exception (handler / coeffect /
  interceptor — rf2-294yq5.2) targeting the frame is captured and
  projected onto `[:rf.story/assertions]` as a `:rf.error/exception`
  record with `:phase :phase-0-setup`, so the run aggregates as failed.
  Synchronous throws from outside the interceptor chain are caught
  directly. The walk continues so every setup failure is recorded."
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
                   ;; rf2-294yq5.2 — capture every pipeline exception
                   ;; (handler / coeffect / interceptor), not just
                   ;; handler-exception: a teardown event whose cofx
                   ;; injector or user interceptor throws was a false-green.
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
                             record     (phase-exception-record
                                          variant-id :phase-loaders-teardown
                                          orig-event err
                                          (pipeline-event-opts tev))]
                         (try
                           (rf/dispatch-sync [::append-teardown-assertion record]
                                             {:frame variant-id})
                           (catch #?(:clj Throwable :cljs :default) _ nil))))))
        listener (fn [ev]
                   ;; rf2-294yq5.2 — capture every pipeline exception
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
  `install-canonical-assertions!`, `install-canonical-fx-stubs!`, …). Was
  the vague `install-helpers!` (rf2-152jq inline rename) — the new name
  states what's installed."
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
    :sensitive / :large  (EP-0015 frame-owned classification)
    plus arbitrary user-stamped keys.

  Per `002-Runtime.md` §Per-variant frame allocation we stamp `:rf/story?` and `:rf/variant` so tools
  can recognise variant frames from their `frame-meta`.

  rf2-bsk1d9 — a variant's EP-0015 frame-owned `:sensitive` / `:large`
  declarations (the owner model that REPLACES the removed public
  `add-marks` / `set-marks` surface) are threaded straight onto the
  `reg-frame` config so the framework's `re-frame.frame-classification`
  validates + installs them atomically as part of frame creation, BEFORE
  `:on-create`. Story does not fork the classification validation; a
  malformed declaration FAILS LOUDLY at `reg-frame` time."
  [variant-id fx-overrides {:keys [sensitive large image-ids]}]
  (cond-> {:doc        (str "Variant frame for " variant-id ".")
           :preset     :story
           :rf/story?  true
           :rf/variant variant-id}
    (seq fx-overrides) (assoc :fx-overrides fx-overrides)
    (some? sensitive)  (assoc :sensitive sensitive)
    (some? large)      (assoc :large large)
    ;; EP-0023 §Stories — report the IMAGE ids this behaviour-variant frame
    ;; resolves behaviour against, on frame-meta, so Test mode / MCP / Xray can
    ;; surface which behaviour set ran (rf2-fpr0b5 item 4). The actual image
    ;; GENERATION is attached to the live frame object by `rf/make-frame` in
    ;; `allocate!`; this slot carries the authored ids for tooling read-back.
    (seq image-ids)    (assoc :rf/images (vec image-ids))))

(defn- image-id
  "The id of an `rf/image` value — its `:rf.image/id` slot, or nil for an
  anonymous image (valid for local stories per EP-0023 §Public API)."
  [image]
  (:rf.image/id image))

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
    (install-canonical-frame-events!)
    (let [fx-stack       (decorators/fx-overrides-map (:fx-override decorator-stack))
          fx-overrides   (register-fx-overrides! fx-stack)
          ;; Inline the variant-body lookup (`variant-body` is defined
          ;; lower in this ns) — go through the registrar directly so
          ;; the events-only classification (rf2-043cm) doesn't depend
          ;; on the file's declaration order.
          v-body         (registrar/handler-meta :variant variant-id)
          ;; EP-0023 §Stories — the variant's behaviour-variant IMAGES (the
          ;; `rf/image` registration-set values its frame resolves behaviour
          ;; against). A vector of image VALUES; nil/empty for an ordinary
          ;; state variant (which resolves against the shared default
          ;; registrar). rf2-fpr0b5.
          images         (:images v-body)
          ;; rf2-bsk1d9 — thread the variant's EP-0015 frame-owned
          ;; `:sensitive` / `:large` classification onto the reg-frame config.
          ;; rf2-fpr0b5 — plus the image ids (for frame-meta tooling read-back).
          config-map     (variant-frame-config
                           variant-id fx-overrides
                           (assoc (select-keys v-body [:sensitive :large])
                                  :image-ids (keep image-id images)))
          events-only?   (loaders/events-only-variant? v-body decorator-stack)]
      ;; EP-0023 §Stories / §Frame-derived live registration resolution
      ;; (rf2-fpr0b5 item 1) — when the variant declares behaviour-variant
      ;; `:images`, register a live frame OBJECT under the SAME id carrying the
      ;; resolved, sealed image GENERATION. `process-event!`'s
      ;; `call-with-frame-resolution` then routes the whole cascade
      ;; (event-handler lookup, cofx, fx) for any `{:frame variant-id}`
      ;; dispatch through THIS frame's image generation rather than the global
      ;; registrar — so two variants reuse the SAME global id with DIFFERENT
      ;; meanings ("behavior variant -> image", EP-0023 §Stories). Image-less
      ;; variants skip this and resolve against the shared default registrar
      ;; exactly as before (absence-is-default). The framework's `rf/image` /
      ;; assembly own all validation — a malformed image or a zero-match
      ;; `:include-ns` glob FAILS LOUDLY here at frame creation.
      ;;
      ;; ORDER: `make-frame` FIRST — it creates the backing runnable record
      ;; (with an empty config) under `variant-id`. The `reg-frame` below then
      ;; SURGICAL-UPDATES that record's config with the full Story config
      ;; (preset / fx-overrides / classification / `:rf/images`) while
      ;; preserving the freshly-made app-db. Doing `reg-frame` first then
      ;; `make-frame` would let `make-frame`'s backing `reg-frame …{}` clobber
      ;; the Story config back to empty. The live-frame OBJECT (carrying the
      ;; generation) lives in a SEPARATE registry, untouched by `reg-frame`.
      (when (seq images)
        (rf/make-frame {:id variant-id :images (vec images)}))
      ;; Allocate / configure the frame (the EP-0013 runnable record carrying
      ;; app-db / sub-cache / queue). Surgical-update when `make-frame` already
      ;; created the backing record above.
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

;; ---- inline-plan allocation (rf2-5x1wt.20) -------------------------------
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
  "Allocate an anonymous frame for an inline plan run (rf2-5x1wt.20,
  spec/017 §Inline plan). Registry-free twin of `allocate!`:

  - `frame-id` — the anonymous frame id the runtime minted (NOT a
    registered variant id);
  - `decorator-stack` — `decorators/resolve-decorator-refs` over the plan's
    `[:world :decorators]` refs (the runtime resolves it upstream);
  - `plan-fx-overrides` — the plan's `[:world :frame :fx-overrides]` lowered
    map (e.g. the managed-HTTP stub the compiler folded `:network` into);
  - `events-only?` — whether the plan drives no loaders / frame-setup, so
    the lifecycle takes the `:pre-mount → :ready` fast-path (rf2-043cm).

  Registers the `:fx-override`-decorator stubs, drives the lifecycle by the
  supplied shape, applies any `:frame-setup` decorators' `:init` events, and
  returns the frame-id. The frame's effective `:fx-overrides` is the
  decorator-stack's materialised stubs UNDER the plan's lowered map (the
  plan map wins — it already merged the author/network overrides). Does NOT
  register anything in the Story side-table."
  [frame-id decorator-stack plan-fx-overrides events-only?]
  (when config/enabled?
    (install-canonical-frame-events!)
    (let [fx-stack     (decorators/fx-overrides-map (:fx-override decorator-stack))
          decor-fx     (register-fx-overrides! fx-stack)
          fx-overrides (merge decor-fx plan-fx-overrides)
          config-map   (inline-frame-config frame-id fx-overrides)]
      (rf/reg-frame frame-id config-map)
      (if events-only?
        (loaders/mount-ready! frame-id)
        (loaders/mount!       frame-id))
      (apply-frame-setup! frame-id (:frame-setup decorator-stack))
      frame-id)))

(defn destroy-inline!
  "Tear down an inline-plan frame (rf2-5x1wt.20). The registry-free twin of
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
    ;; rf2-booyu — evict the play-runner's per-frame run-state on teardown
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
     run-state (rf2-booyu) via the late-bind hooks.
  2. Dispatch-sync the variant body's `:loaders-teardown` events
     (declared order, rf2-lqs0b) — cleans up loader-installed narrower
     state. BEFORE the decorator teardown.
  3. Dispatch-sync the `:frame-setup` decorator `:teardown` events
     (reverse-declaration order) — cleans up decorator-installed wider
     state.

  Shared by `destroy!` (which follows with `rf/destroy-frame!`) and the
  in-place `reset-state!` (rf2-294yq5, which follows with a frame-state
  reset instead) so both paths run the SAME symmetric external-resource
  cleanup. Exceptions in each walk are caught + projected onto the
  variant's `[:rf.story/assertions]`; the walks never abort."
  [variant-id]
  (loaders/clear-watchers! variant-id)
  (clear-stub-call-log! variant-id)
  (when-let [drop (late-bind/get-fn :drop-assertion-accumulators)]
    (try (drop variant-id) (catch #?(:clj Throwable :cljs :default) _ nil)))
  ;; rf2-booyu — evict the play-runner's per-frame run-state on teardown
  ;; (run-state / runs-by-play / active-play / step-boundaries) so a
  ;; torn-down frame leaves no stale terminal play status behind. Routed
  ;; through the `:drop-run-state` late-bind hook (frames cannot :require
  ;; runner-events — cycle).
  (when-let [drop (late-bind/get-fn :drop-run-state)]
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
    (run-teardown-walks! variant-id)
    (rf/destroy-frame! variant-id)
    nil))

;; ---- in-place fresh-run reset (rf2-294yq5) --------------------------------

(defn reset-state!
  "Reset an EXISTING variant frame to fresh state IN PLACE — preserving
  the frame's IDENTITY, sub-cache, and app-db/runtime-db projection
  reactions — instead of `destroy!` + re-`allocate!`.

  Why this exists (rf2-294yq5, the PR #3672 last-red fix). `run-variant`
  must enforce a fresh-run boundary so a second run on the same id does
  NOT inherit the first run's app-db and so loader variants re-run their
  loaders (the lifecycle machine must leave `:ready`). The first cut
  (`ensure-fresh-frame!` → `destroy!` + `allocate!`) achieved fresh state
  but DESTROYED the frame: `destroy-frame!` tears down the sub-cache and
  the partition projections. When the canvas has ALREADY mounted the
  variant view under `frame-provider {:frame variant-id}` (its
  `@(subscribe …)` reactions wired to THIS frame's sub-cache) before
  `:component-did-mount` → `run-variant` runs, that destroy ORPHANS every
  live reaction: a subsequent `:counter/set 42` updates the freshly
  re-allocated frame's app-db, but the stale reaction never observes it,
  so the DOM never re-renders (count-display stuck at \"0\"). Invisible to
  the JVM (no React reconciliation), which is why a JVM-validated fix
  missed it.

  The fix: reset in place. We run the SAME teardown walks `destroy!`
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
  "Destroy the variant frame and re-allocate it. Mirrors the framework's
  `re-frame.core/reset-frame!` naming (rf2-noizc — aligning Story's
  frame helpers with the framework `*-frame!` convention). The caller
  is responsible for re-running the four-phase lifecycle (loaders →
  events → render → play) after.

  NOTE the in-place `reset-state!` (rf2-294yq5) is the path
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
  cover (rf2-r16iv)."
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
  behaviour against (EP-0023 §Stories, rf2-fpr0b5), or nil for an ordinary
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
