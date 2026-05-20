(ns day8.re-frame2-causa.test-helpers.sub-reactivity
  "Sub-reactivity test helper for Causa panels (rf2-dhoc9).

  ## Why this exists

  Every Causa bug Mike caught live this session — rf2-70tkv App-db panel
  frozen, rf2-2f8jv Machines empty, rf2-dodq2 Views Group-By stuck,
  rf2-hwuki machine transitions silently dropped — is a sub-chain
  reactivity bug. They show up in Playwright after a serve+navigate
  cycle (minutes per repro). The same bugs are testable in millis via
  CLJS unit tests: spin up the `:rf/causa` frame, install Causa, drive
  a synthetic cascade through the test seams, read the panel's primary
  sub, mutate focus, re-read, assert reactivity.

  This ns is the canonical entry point for that pattern. The
  `setup-causa-frame!` and `seed-cascades!` shapes mirror what the
  rf2-70tkv worker wrote inline in `spine_cljs_test.cljs`; we lift them
  so per-panel reactivity tests stay 50-LoC files instead of recopying
  the fixture rig.

  ## Existing test seams this composes on top of

  - `:rf.causa/sync-trace-buffer` — wholesale overwrite of the
    `:trace-buffer` slot (registry.cljs). The `seed-cascades!` helper
    builds the minimal trace-event shape `group-cascades` needs so
    the `:rf.causa/cascades` sub re-projects to the requested cascade
    vector.

  - `:rf.causa/sync-epoch-history` — wholesale overwrite of the
    `:epoch-history` slot (epoch.cljs). Lets a test hand-construct the
    per-epoch record shape App-db Diff / Views / Machine Inspector
    consume.

  - `:rf.causa/set-epoch-history-for-test` + `:rf.causa/set-focus-
    epoch-id-for-test` — Machine Inspector's surgical test seams
    (machine_inspector.cljs). Equivalent to `sync-epoch-history` for
    the slot write but bypass the schema-frame-tag normalisation that
    `sync-epoch-history` runs.

  - `:rf.causa/focus-cascade` — the canonical focus-mutation event. The
    spine's `focus-cascade-reducer` writes through to both
    `[:focus :dispatch-id]` and `[:focus :epoch-id]`, so any panel
    pivoting on either axis re-fires.

  ## Canonical use

      (deftest app-db-panel-tracks-focus
        (with-causa-frame
          (seed-cascades! [(cascade :c1 :rf/default)
                           (cascade :c2 :rf/default)])
          (seed-epoch-history!
            [(mock-epoch :e1 :c1 {} {:counter 1})
             (mock-epoch :e2 :c2 {:counter 1} {:counter 2})])
          ;; First focus: epoch :e1.
          (rf/with-frame :rf/causa
            (rf/dispatch-sync [:rf.causa/focus-cascade :c1 :rf/default]))
          (let [sig-1 (read-sub :rf.causa/selected-epoch-diff)]
            ;; Mutate focus to :e2.
            (rf/with-frame :rf/causa
              (rf/dispatch-sync [:rf.causa/focus-cascade :c2 :rf/default]))
            (let [sig-2 (read-sub :rf.causa/selected-epoch-diff)]
              (is (not= sig-1 sig-2)
                  \"App-db sub did not track focused-event flip\")))))

  ## What the helper does NOT do

  - It does NOT touch source. Any failing test points at a production
    bug — file a follow-on bead per the rf2-dhoc9 acceptance contract.
  - It does NOT mount the React tree. The point of this layer is to
    exercise the sub-chain reactivity at the data layer; view rendering
    is the next tier up (browser Playwright or hiccup-walk tests)."
  (:require [cljs.test :refer-macros [use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support :as test-support]
            [day8.re-frame2-causa.registry :as registry]
            [day8.re-frame2-causa.test-support :as causa-test-support]
            [day8.re-frame2-causa.trace-bus :as trace-bus]))

;; ---- per-test reset -----------------------------------------------------

(defn causa-init!
  "Reset every Causa idempotency sentinel + clear the trace-bus atom so
  the next register-causa-handlers! call wires a fresh registrar. Test-
  only — never call from production code.

  Mirrors the init-fn the existing app_db_diff_cljs_test.cljs corpus
  uses; lifted into this helper ns so per-panel reactivity tests share
  one canonical reset shape."
  []
  (causa-test-support/reset-all!)
  (trace-bus/clear-buffer!))

(def fixture
  "`use-fixtures :each` value for sub-reactivity tests. Wires
  test-support/reset-runtime-fixture-factory with the plain-atom adapter and
  the Causa init-fn. Usage:

      (use-fixtures :each h/fixture)"
  (test-support/reset-runtime-fixture-factory
    {:adapter plain-atom/adapter
     :init-fn causa-init!}))

;; ---- frame setup --------------------------------------------------------

(defn setup-causa-frame!
  "Register Causa's full handler graph + create the `:rf/causa` frame.
  Idempotent within a single test (the registry's defonce sentinel
  short-circuits the second call; the frame registrar replaces in
  place).

  Also registers the host `:rf/default` frame so panels that resolve
  `:rf.causa/target-frame-db` (App-db diff) find a real frame rather
  than nil."
  []
  (registry/register-causa-handlers!)
  (frame/reg-frame :rf/causa {})
  (frame/reg-frame :rf/default {}))

;; ---- cascade + epoch fixtures -------------------------------------------

(defn cascade
  "Build a minimal cascade shape — enough for `group-cascades`'
  frame-index pairing. Per `re-frame.trace.projection/group-cascades`
  the projector needs at least `:dispatch-id` + `:frame` per trace
  event; the cascade record's shape below is what `seed-cascades!`
  re-projects into when it builds the trace buffer.

  Mirrors `cascade` in `spine_cljs_test.cljs` — lifted here so per-panel
  tests don't reach into a sibling test ns."
  [dispatch-id frame-id]
  {:dispatch-id dispatch-id
   :frame       frame-id
   :event       nil
   :handler     nil
   :fx          nil
   :effects     []
   :subs        []
   :renders     []
   :other       []})

(defn seed-cascades!
  "Seed the `:rf/causa` frame's `:trace-buffer` slot with a minimal
  trace-event vector that re-projects to `cascades-vec` via the
  `:rf.causa/cascades` sub.

  Each entry in `cascades-vec` is a cascade record (typically built via
  `cascade`). The function constructs ONE `:event/dispatched` event per
  cascade with the cascade's `:dispatch-id` and `:frame` tags so
  `projection/group-cascades` pairs the right events.

  Mirrors `seed-cascades!` in `spine_cljs_test.cljs`."
  [cascades-vec]
  (let [events (map-indexed
                 (fn [i {:keys [dispatch-id frame]}]
                   {:id        (inc i)
                    :op-type   :event
                    :operation :event/dispatched
                    :tags      {:dispatch-id dispatch-id
                                :frame       frame
                                :event       [(keyword "evt"
                                                       (str (name dispatch-id)))]}})
                 cascades-vec)]
    (rf/with-frame :rf/causa
      (rf/dispatch-sync [:rf.causa/sync-trace-buffer (vec events)]))))

(defn mock-epoch
  "Build a minimal `:rf/epoch-record` carrying the slots App-db Diff /
  Views / Machine Inspector subs read.

  Args:
    `epoch-id`    — the per-frame primary key
    `dispatch-id` — the cascade-id this epoch settles for
    `db-before`   — `:db-before` slot (the App-db Diff walker reads it)
    `db-after`    — `:db-after` slot (same)

  Optional opts map:
    `:trace-events`  — vector of trace events (Machine Inspector reads
                        `:rf.machine/transition` entries here). Defaults
                        to a single `:event/dispatched` event.
    `:frame`         — the epoch's frame. Defaults to `:rf/default`.
    `:event-id`      — the trigger event id. Defaults to the
                        dispatch-id keyword.
    `:trigger-event` — the trigger event vector. Defaults to
                        `[<event-id>]`."
  ([epoch-id dispatch-id db-before db-after]
   (mock-epoch epoch-id dispatch-id db-before db-after {}))
  ([epoch-id dispatch-id db-before db-after
    {:keys [trace-events frame event-id trigger-event]
     :or   {frame :rf/default}}]
   (let [eid (or event-id dispatch-id)
         ev  (or trigger-event [eid])]
     {:epoch-id      epoch-id
      :dispatch-id   dispatch-id
      :frame         frame
      :committed-at  0
      :event-id      eid
      :trigger-event ev
      :db-before     db-before
      :db-after      db-after
      :trace-events  (or trace-events
                         [{:id        1
                           :op-type   :event
                           :operation :event/dispatched
                           :tags      {:dispatch-id dispatch-id
                                       :frame       frame
                                       :event       ev}}])})))

(defn machine-transition-event
  "Build a `:rf.machine/transition` trace event for `mock-epoch`'s
  `:trace-events` vector. Mirrors the shape `transition-record-from-
  trace` walks in `machine_inspector_helpers.cljc`.

  Args:
    `id`         — monotonic event id (sort-key for cascade order)
    `machine-id` — the registered machine keyword
    `from-state` — the state before the transition
    `to-state`   — the state after the transition
    `event-v`    — the event vector that fired the transition

  Optional opts map:
    `:frame`       — the host frame the transition fired in. Defaults
                      to `:rf/default`. Maps to the rf2-hwuki contract
                      (epoch capture filters by frame tag).
    `:dispatch-id` — the dispatch id the transition belongs to."
  ([id machine-id from-state to-state event-v]
   (machine-transition-event id machine-id from-state to-state event-v {}))
  ([id machine-id from-state to-state event-v
    {:keys [frame dispatch-id] :or {frame :rf/default}}]
   {:id        id
    :op-type   :machine
    :operation :rf.machine/transition
    :tags      (cond-> {:machine-id  machine-id
                        :before      {:state from-state}
                        :after       {:state to-state}
                        :event       event-v
                        :frame       frame}
                 dispatch-id (assoc :dispatch-id dispatch-id))}))

;; ---- direct slot seeders ------------------------------------------------

(defn seed-epoch-history!
  "Seed the `:rf/causa` frame's `:epoch-history` slot. The slot is
  shared with `:rf.causa/sync-epoch-history` (the production path); the
  helper routes through the same event so the test surface stays
  consistent with what `mount.cljs/open!` does at first open."
  [history]
  (rf/with-frame :rf/causa
    (rf/dispatch-sync [:rf.causa/sync-epoch-history (vec history)])))

;; ---- focus mutation -----------------------------------------------------

(defn focus-cascade!
  "Drive the canonical focus-mutation event. Wraps `rf/dispatch-sync`
  in the `:rf/causa` frame so the spine sub picks up the change. The
  spine's `focus-cascade-reducer` writes both `[:focus :dispatch-id]`
  and `[:focus :epoch-id]` via the per-frame `:epoch-history` slot,
  so panels pivoting on either axis re-fire.

  Optional `frame-id` arg defaults to `:rf/default` — matches the
  picker's seed selection."
  ([dispatch-id]
   (focus-cascade! dispatch-id :rf/default))
  ([dispatch-id frame-id]
   (rf/with-frame :rf/causa
     (rf/dispatch-sync [:rf.causa/focus-cascade dispatch-id frame-id]))))

(defn follow-head!
  "Drive the canonical `:rf.causa/follow-head` event — flips focus
  back to LIVE+head-tracking. Pairs with `focus-cascade!` for the
  rf2-70tkv repro shape (pin → follow-head → arrival → auto-track)."
  []
  (rf/with-frame :rf/causa
    (rf/dispatch-sync [:rf.causa/follow-head])))

(defn dispatch-causa!
  "Generic dispatch helper for control-action tests — wraps `rf/
  dispatch-sync` in the `:rf/causa` frame so the helper site stays at
  one indentation level."
  [event-v]
  (rf/with-frame :rf/causa
    (rf/dispatch-sync event-v)))

;; ---- read helpers -------------------------------------------------------

(defn read-sub
  "Subscribe + deref inside `:rf/causa`. Returns the sub's current
  value; the deref forces re-computation if the reactive graph has
  invalidated since the last read.

  Per re-frame's subscribe semantics the returned value is `=` to the
  previous read iff the sub's inputs are unchanged — that's the
  reactivity contract per-panel reactivity tests assert on."
  ([sub-id]
   (rf/with-frame :rf/causa
     @(rf/subscribe [sub-id])))
  ([sub-id & args]
   (rf/with-frame :rf/causa
     @(rf/subscribe (into [sub-id] args)))))
