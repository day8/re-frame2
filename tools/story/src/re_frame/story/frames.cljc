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
  (:require [re-frame.core            :as rf]
            [re-frame.story.config    :as config]
            [re-frame.story.decorators :as decorators]
            [re-frame.story.loaders   :as loaders]
            [re-frame.story.registrar :as registrar]))

;; ---- fx-override-stub registration ---------------------------------------

(defn- ensure-stub-event!
  "Register a `reg-event-fx` handler under `stub-id` that returns a
  canned response. Idempotent — re-registration replaces the slot
  atomically (Spec 001 hot-reload semantics).

  The response shape is the data the user supplied on the decorator's
  `:response` slot. We wrap it in a no-op handler so the override
  re-targets the fx call to a synchronous data emission. The default
  emits the response as a `:db` patch under `[:rf.story.fx-stub/last-call]`
  for inspection, plus mirrors the fx call into `[:rf.story.fx-stub/log]`
  for assertion-runtime hooks (Stage 5)."
  [stub-id response]
  (rf/reg-event-fx
    stub-id
    (fn [{:keys [db]} [_ fx-payload]]
      {:db (-> db
               (update :rf.story.fx-stub/log (fnil conj [])
                       {:stub-id stub-id :payload fx-payload})
               (assoc-in [:rf.story.fx-stub/last-call stub-id]
                         {:payload  fx-payload
                          :response response}))})))

(defn- register-fx-overrides!
  "Walk the `:registrations` vector from `decorators/fx-overrides-map`
  and register each stub event. Returns the `:overrides` map suitable
  for the variant frame's `:fx-overrides` config slot."
  [{:keys [overrides registrations]}]
  (doseq [{:keys [stub-id response]} registrations]
    (ensure-stub-event! stub-id response))
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
  the frame, then calls `rf/destroy-frame`. Returns nil."
  [variant-id]
  (when config/enabled?
    (loaders/clear-watchers! variant-id)
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
  `allocate!`). Reads the frame's `:config :rf/story?` slot."
  [frame-id]
  (true? (get-in (rf/frame-meta frame-id) [:config :rf/story?])))

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
