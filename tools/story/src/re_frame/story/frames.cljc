(ns re-frame.story.frames
  "Per-variant frame allocation. Per IMPL-SPEC §5.1 + spec/007
  §Relationship-with-frames.

  Each rendered variant is its own re-frame frame (spec/002), allocated
  fresh on `run-variant` and torn down by the caller via
  `destroy-variant!`. Story does NOT introduce a new frame substrate —
  it consumes `re-frame.core/reg-frame` / `destroy-frame` / `reset-frame!`.

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
  - `reset!*` — destroy + re-allocate; the caller then re-runs the
    lifecycle.

  ## Hot-reload

  Stage 4's UI shell observes the registrar's `:rf.registry/handler-*`
  trace events and re-runs `reset!*` for any variant whose registered
  body changed. Stage 3 surfaces the entry point; the trigger lives in
  Stage 4."
  (:require [re-frame.core             :as rf]
            [re-frame.story.config     :as config]
            [re-frame.story.decorators :as decorators]
            [re-frame.story.late-bind  :as late-bind]
            [re-frame.story.loaders    :as loaders]
            [re-frame.story.registrar  :as registrar]))

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

(defn install-helpers!
  "Register Story-internal helper events: `::apply-app-db-patch` for
  `:frame-setup` decorators' `:app-db-patch` slot. Idempotent."
  []
  (when config/enabled?
    (rf/reg-event-db
      ::apply-app-db-patch
      (fn [db [_ patch]]
        (reduce-kv
          (fn [d path v]
            (assoc-in d (if (vector? path) path [path]) v))
          db
          patch)))))

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
  machine to `:mounting`. Returns the variant-id (which doubles as the
  frame-id).

  `decorator-stack` is the result of `decorators/resolve-decorators`;
  the runtime computes it upstream.

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
          config-map     (variant-frame-config variant-id fx-overrides)]
      ;; Allocate the frame.
      (rf/reg-frame variant-id config-map)
      ;; Drive lifecycle to :mounting.
      (loaders/mount! variant-id)
      ;; Apply :frame-setup decorators (their :init events + :app-db-patch).
      (apply-frame-setup! variant-id (:frame-setup decorator-stack))
      variant-id)))

;; ---- destruction ----------------------------------------------------------

(defn destroy!
  "Tear down a variant frame. Clears the lifecycle watcher table for
  the frame, drops per-frame assertion + stub accumulators, then
  calls `rf/destroy-frame`. Returns nil."
  [variant-id]
  (when config/enabled?
    (loaders/clear-watchers! variant-id)
    (clear-stub-call-log! variant-id)
    (when-let [drop (late-bind/get-fn :drop-assertion-accumulators)]
      (try (drop variant-id) (catch #?(:clj Throwable :cljs :default) _ nil)))
    (rf/destroy-frame variant-id)
    nil))

;; ---- destroy + re-allocate -----------------------------------------------

(defn reset!*
  "Destroy the variant frame and re-allocate it. Used by
  `runtime/reset-variant`. The caller is responsible for re-running
  the four-phase lifecycle (loaders → events → render → play) after."
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
