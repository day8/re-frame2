(ns re-frame.story.loaders
  "Four-phase loader lifecycle. Per IMPL-SPEC §5.4 + spec/007 §Loaders.

  The lifecycle moves through five discrete states:

      :pre-mount  →  :mounting  →  :loading  →  :ready
                                        │
                                        └────►  :error

  Phases (mapped to states):

  1. **`:pre-mount`** — runs before the variant frame is created.
     Examples: prefetch HTTP, set initial fixtures (the runtime stages
     events before the frame exists).
  2. **`:mounting`** — frame allocated; `:frame-setup` decorators run
     their `:init` events and `:app-db-patch` is applied.
  3. **`:loading`** — `:loaders` events dispatch in declared order. The
     `:loaders-complete-when` predicate evaluates after each loader's
     drain settles. Default: 'complete when no further loader-tagged
     events are in flight'. The variant overrides this for long-lived
     fx (e.g. `:websocket`) per IMPL-SPEC §2.4.
  4. **`:ready`** — `:events` have run; render allowed.
  5. **`:error`** — terminal error projection per IMPL-SPEC §5.5.

  ## Substrate

  Per the user-feedback rule + IMPL-SPEC §5.4, the lifecycle is
  expressed as a `re-frame.machines` machine — NOT ad-hoc state
  tracking. The machine is registered at Story-boot under the id
  `:rf.story.lifecycle/machine`. Each variant frame holds its own
  snapshot at `[:rf/machines :rf.story.lifecycle/machine]` and mirrors
  the discrete state at `[:rf.story/lifecycle]` for direct read access.

  The transitions are driven by the runtime dispatching synthetic
  events into the variant frame:

  - `[:rf.story.lifecycle/machine [:rf.story.lifecycle/mount]]`
  - `[:rf.story.lifecycle/machine [:rf.story.lifecycle/loaders-started]]`
  - `[:rf.story.lifecycle/machine [:rf.story.lifecycle/loaders-complete]]`
  - `[:rf.story.lifecycle/machine [:rf.story.lifecycle/events-complete]]`
  - `[:rf.story.lifecycle/machine [:rf.story.lifecycle/errored err]]`

  ## Watchers

  Per IMPL-SPEC §3.2 `watch-variant` subscribes to lifecycle
  transitions. Stage 3 implements the watcher table here; the trace
  bus fires `:rf.story.lifecycle/transition` on every state change.

  ## Elision

  The machine registration sits behind the
  `re-frame.story.config/enabled?` gate (per IMPL-SPEC §6). Production
  CLJS builds with the flag false never register the machine; the
  Story runtime entry points read `(rf/handler-meta :event
  :rf.story.lifecycle/machine)` and find nothing, returning early."
  (:require [re-frame.story.config    :as config]
            #?(:clj  [re-frame.machines :as machines]
               :cljs [re-frame.machines :as machines])
            [re-frame.core            :as rf]))

;; ---- canonical ids --------------------------------------------------------

(def lifecycle-machine-id
  "Stable id for Story's lifecycle machine. One registration; per-
  variant snapshots live at `[:rf/machines :rf.story.lifecycle/machine]`
  inside each variant frame's app-db."
  :rf.story.lifecycle/machine)

(def state-mirror-path
  "Side-mirror path. After every transition the lifecycle's `:action`
  copies the new discrete state to `[:rf.story/lifecycle]` so callers
  can read it without the `:rf/machines` indirection."
  [:rf.story/lifecycle])

;; ---- transition vocabulary -----------------------------------------------

(def event-mount             :rf.story.lifecycle/mount)
(def event-loaders-started   :rf.story.lifecycle/loaders-started)
(def event-loaders-complete  :rf.story.lifecycle/loaders-complete)
(def event-events-complete   :rf.story.lifecycle/events-complete)
(def event-errored           :rf.story.lifecycle/errored)

;; ---- machine spec ---------------------------------------------------------
;;
;; Each state node is a named def so the machine literal reads as
;; assembly rather than a 50-line nested map. The transition table
;; below is the canonical reference; the machine reference at the end
;; of this section composes the nodes into the spec/005 machine shape.

(def ^:private pre-mount-node
  "Initial state. Accepts :mount (→ :mounting) and :errored (→ :error)."
  {:on {event-mount             :mounting
        event-errored           :error}})

(def ^:private mounting-node
  "Frame allocated, awaiting loader phase. Accepts :loaders-started
  (→ :loading) and :errored (→ :error)."
  {:on {event-loaders-started   :loading
        event-errored           :error}})

(def ^:private loading-node
  "Loaders draining. Accepts :loaders-complete (→ :ready) and :errored
  (→ :error)."
  {:on {event-loaders-complete  :ready
        event-errored           :error}})

(def ^:private ready-node
  "Render allowed. Only transition is :errored (→ :error)."
  {:on {event-errored           :error}})

(def ^:private error-node
  "Terminal — no outgoing transitions."
  {})

(def lifecycle-machine
  "The lifecycle machine. Per Spec 005's machine grammar.

  States:
    :pre-mount  (initial)
    :mounting
    :loading
    :ready
    :error

  Transitions:
    :pre-mount  --mount-->            :mounting
    :pre-mount  --errored-->          :error
    :mounting   --loaders-started-->  :loading
    :mounting   --errored-->          :error
    :loading    --loaders-complete--> :ready
    :loading    --errored-->          :error
    :ready      --errored-->          :error
    :error      (terminal)

  Every transition's `:action` mirrors the new discrete state to
  `[:rf.story/lifecycle]` via the standard machine actions surface.
  Actions in re-frame.machines are 2-arity fns `(fn [data event] data')`
  receiving the snapshot's `:data` and the inbound event; they return
  the new `:data`. The lifecycle machine carries no `:data` of its own;
  the actions update a `:rf.story/last-event` debug slot that surfaces
  through the snapshot's `:data`.

  The discrete-state mirror to `[:rf.story/lifecycle]` is performed by
  the runtime AFTER every dispatch (see `dispatch-lifecycle-event!`)
  rather than via an action — actions in re-frame.machines mutate the
  machine's `:data`, not the surrounding app-db. The runtime owns the
  surrounding-app-db write."
  {:doc      "re-frame2-story lifecycle machine."
   :initial  :pre-mount
   :data     {}
   :states   {:pre-mount pre-mount-node
              :mounting  mounting-node
              :loading   loading-node
              :ready     ready-node
              :error     error-node}})

;; ---- registration ---------------------------------------------------------

(defn install!
  "Register the lifecycle machine. Idempotent — calling twice replaces
  the registration in place per Spec 001 hot-reload semantics. The
  Story runtime calls this from `re-frame.story/install-canonical-vocabulary!`
  at boot.

  The registration sits behind the `enabled?` gate so production
  builds (with the flag false) skip it — the side-table and machine
  registry stay empty, and `run-variant` short-circuits."
  []
  (when config/enabled?
    (machines/reg-machine* lifecycle-machine-id lifecycle-machine)))

;; ---- per-frame snapshot reads --------------------------------------------

(defn snapshot
  "Read the lifecycle machine's snapshot from a frame's app-db. Returns
  `{:state <state-kw> :data {...}}` or nil if the machine hasn't fired
  yet on this frame.

  `rf/get-frame-db` returns the value-form app-db map (per Spec 002
  §Public registrar query API); we then `get-in` to the machine's
  snapshot slot."
  [frame-id]
  (let [db (rf/get-frame-db frame-id)]
    (when db
      (get-in db [:rf/machines lifecycle-machine-id]))))

(defn current-state
  "Return the lifecycle's current discrete state (`:pre-mount`,
  `:mounting`, `:loading`, `:ready`, `:error`) for the variant frame.
  Returns `:pre-mount` if the machine has not yet been driven."
  [frame-id]
  (or (:state (snapshot frame-id))
      :pre-mount))

;; ---- watcher table -------------------------------------------------------

(defonce
  ^{:doc "Per-frame watcher registry. `frame-id → vec-of-callbacks`.
         Each callback is invoked with `{:frame-id ... :from <state>
         :to <state> :event <event>}` on every transition. Per IMPL-
         SPEC §3.2 `watch-variant` populates this; Stage 5 (play +
         assertions) reads it for assertion-runtime hooks."}
  watchers
  (atom {}))

(defn add-watcher!
  "Register a callback fired on every lifecycle transition for
  `frame-id`. Returns a 0-arity unsubscribe fn. The callback signature:

      (fn [{:keys [frame-id from to event]}] ...)"
  [frame-id callback]
  (swap! watchers update frame-id (fnil conj []) callback)
  (fn unsubscribe []
    (swap! watchers update frame-id
           (fn [cbs] (vec (remove #(= % callback) cbs))))
    nil))

(defn clear-watchers!
  "Remove every watcher for `frame-id` (or all watchers when no arg).
  Used by test fixtures + frame teardown."
  ([] (reset! watchers {}) nil)
  ([frame-id] (swap! watchers dissoc frame-id) nil))

(defn- fire-watchers!
  [frame-id from to event]
  (when-let [cbs (get @watchers frame-id)]
    (doseq [cb cbs]
      (try
        (cb {:frame-id frame-id :from from :to to :event event})
        (catch #?(:clj Throwable :cljs :default) _ nil)))))

;; ---- transition driver ---------------------------------------------------

(defn dispatch-lifecycle-event!
  "Drive the lifecycle machine via a synthetic event dispatched into
  `frame-id`. Mirrors the new discrete state to `[:rf.story/lifecycle]`
  via a follow-up dispatch-sync, and fires every registered watcher.

  Returns the new discrete state.

  Stage 3's runtime calls this after each phase transition:

    (dispatch-lifecycle-event! frame-id [:rf.story.lifecycle/mount])
    (dispatch-lifecycle-event! frame-id [:rf.story.lifecycle/loaders-started])
    (dispatch-lifecycle-event! frame-id [:rf.story.lifecycle/loaders-complete])
    (dispatch-lifecycle-event! frame-id [:rf.story.lifecycle/errored err])"
  [frame-id inner-event]
  (when config/enabled?
    (let [from (current-state frame-id)
          ;; Machine handler convention per Spec 005: outer event is
          ;; [<machine-id> <inner-event>]. The machine reads inner from
          ;; event[1] for its :on lookup.
          envelope [lifecycle-machine-id inner-event]]
      (rf/dispatch-sync envelope {:frame frame-id})
      (let [to (current-state frame-id)]
        ;; Mirror to the friendly path.
        (rf/dispatch-sync [::set-lifecycle-state-mirror to] {:frame frame-id})
        (fire-watchers! frame-id from to inner-event)
        to))))

;; The mirror-state writer is a normal event-db handler. It writes the
;; current discrete state to `[:rf.story/lifecycle]` so callers can
;; read the state without going through the machine snapshot path.
(defn install-mirror-writer!
  "Register the `::set-lifecycle-state-mirror` event-db handler.
  Idempotent."
  []
  (when config/enabled?
    (rf/reg-event-db
      ::set-lifecycle-state-mirror
      (fn [db [_ state]]
        (assoc db :rf.story/lifecycle state)))))

;; ---- public driver helpers -----------------------------------------------
;;
;; Each helper is a thin wrapper around `dispatch-lifecycle-event!` that
;; pins the inner event-id. Per-name fns (rather than a single
;; `transition!`) keep the call sites greppable for each lifecycle
;; phase.

(defn- transition!
  "Drive the lifecycle machine for `frame-id` via the canonical inner
  event vector `[event-id & args]`. Returns the post-transition state."
  [frame-id event-id & args]
  (dispatch-lifecycle-event! frame-id (into [event-id] args)))

(defn mount!          [frame-id]     (transition! frame-id event-mount))
(defn start-loaders!  [frame-id]     (transition! frame-id event-loaders-started))
(defn finish-loaders! [frame-id]     (transition! frame-id event-loaders-complete))
(defn finish-events!  [frame-id]     (transition! frame-id event-events-complete))
(defn error!          [frame-id err] (transition! frame-id event-errored err))

;; ---- loaders-complete-when evaluation -----------------------------------

(defn loaders-default-complete?
  "Default predicate for `:loaders-complete-when`. Per IMPL-SPEC §2.4 +
  §5.4: 'loaders are complete when no further loader-tagged events are
  in flight'. For the simple synchronous case (the 99% path),
  re-frame's run-to-completion drain settles before `dispatch-sync`
  returns; the loaders queue is empty when the predicate is called.

  Returns true unconditionally — Stage 3 relies on `dispatch-sync`
  having drained the queue. Variants that fire long-lived fx
  (websocket / interval) override the predicate per IMPL-SPEC §2.4."
  [_frame-id _variant-body]
  true)

(defn- predicate-event?
  "Per IMPL-SPEC §2.4 — a registered predicate-event handler is a plain
  re-frame event whose `:db` effect returns a value-form map containing
  `[:rf.story/loaders-complete? <bool>]`. Stage 5 (rf2-h8et) accepts
  this shape by dispatching the event then reading the slot from the
  frame's app-db.

  The event handler signature:

      (rf/reg-event-db :my.fixture/ready?
        (fn [db _]
          (assoc db :rf.story/loaders-complete?
                 (boolean (:loaded? db)))))

  Authors set the slot to `true` when their custom condition is
  satisfied. The Story runtime polls by dispatching then reading."
  [pred]
  (keyword? pred))

(defn- dispatched-events-set
  "Read the dispatched-events set for `frame-id`. Lazily resolved to
  avoid a circular require with the assertions module."
  [frame-id]
  (try
    (let [resolve-fn #?(:clj  (requiring-resolve
                                're-frame.story.assertions/frame-dispatched)
                       :cljs ((fn []
                                ;; CLJS: the assertions ns is loaded by
                                ;; the runtime/install-helpers! path.
                                (some-> (find-ns 're-frame.story.assertions)
                                        (ns-resolve 'frame-dispatched)))))]
      (when resolve-fn
        (let [evs (resolve-fn frame-id)]
          (set evs))))
    (catch #?(:clj Throwable :cljs :default) _ #{})))

(defn- vector-of-events-satisfied?
  "Per IMPL-SPEC §2.4 — a vector of event vectors form is interpreted
  as 'loaders complete when ALL listed events have fired against the
  variant's frame'. We consult the assertions module's per-frame
  dispatched-events accumulator (populated by the play-runner's trace
  listener and the runtime's own phase-1/2 trace listener — Stage 5
  reaches across)."
  [frame-id events-required]
  (let [observed (or (dispatched-events-set frame-id) #{})]
    (every? (fn [needle] (contains? observed needle)) events-required)))

(defn evaluate-complete-when
  "Evaluate the variant's `:loaders-complete-when` predicate against
  `frame-id`'s current app-db. Returns truthy when the loader phase
  should advance to `:ready`.

  Forms accepted (per the Stage 2 schema; Stage 5 finalises non-default
  semantics):

  - nil — use the default predicate (`loaders-default-complete?`).
  - a registered event id — `:my.fixture/ready?`. The runtime
    dispatch-syncs the event into the variant's frame, then reads the
    slot `[:rf.story/loaders-complete?]` from the post-dispatch app-db.
    Handlers set this to `true` when their custom condition is met.
  - a vector of event vectors — `[[:fixture/loaded] [:auth/ready]]`.
    Interpreted as 'loaders complete when ALL listed events have fired
    against the variant's frame.' The runtime checks the assertions
    module's dispatched-events accumulator (which Story populates from
    the trace bus).

  Stage 5 (rf2-h8et)."
  [frame-id variant-body]
  (let [pred (:loaders-complete-when variant-body)]
    (cond
      (nil? pred)
      (loaders-default-complete? frame-id variant-body)

      (fn? pred)
      ;; A literal function predicate. Authors usually pass a registered
      ;; event-id (a keyword) but a fn is a legal Clojure value at this
      ;; layer; the runtime threads the frame's app-db through.
      (boolean (pred (rf/get-frame-db frame-id)))

      (predicate-event? pred)
      (do
        (try
          (rf/dispatch-sync [pred] {:frame frame-id})
          (catch #?(:clj Throwable :cljs :default) _ nil))
        (boolean (:rf.story/loaders-complete? (rf/get-frame-db frame-id))))

      (vector? pred)
      (vector-of-events-satisfied? frame-id pred)

      :else
      ;; Unknown shape — be permissive (per IMPL-SPEC §2.3 record-not-throw
      ;; spirit) and let the variant proceed.
      true)))
