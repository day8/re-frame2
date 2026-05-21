(ns re-frame.story.story-causa-wiring-cljs-test
  "End-to-end wiring contract: variant selection seeds BOTH Causa's
  trace-buffer AND its target-frame in one selection edge.

  ## Why this file exists alongside the two #1822 unit tests

  PR #1822 fixed the empty-Causa-on-Story-RHS bug with two cooperating
  changes, each pinned by its own unit test:

  - Causa side (`panels-mount-cljs-test`) — mounting a panel routes
    through `mount/ensure-causa-frame!`, so the first-mount hook table
    runs and lifts the pre-mount trace-bus into `:trace-buffer` +
    seeds `:target-frame`. That test drives the seam by pushing
    SYNTHETIC events onto the trace-bus by hand, then mounting a panel
    — it pins the hook, not a real host cascade.

  - Story side (`causa-target-frame-dispatch-e2e-cljs-test`) — the
    shell's selection-watcher dispatches `:rf.causa/set-target-frame`
    on every selection edge. That test drives `select-variant!` against
    variants whose `:events` are EMPTY, so it asserts only that
    `:target-frame` flips — never that the variant's events land in
    Causa's buffer.

  Neither test exercises the FULL wiring on one edge: a variant that
  actually dispatches `:events` → the user selects it → Causa observes
  BOTH (a) the variant's cascade in its trace-buffer AND (b) its
  target-frame re-oriented to the variant + epoch-history hydrated.
  That conjunction IS the empty-Causa contract — the bug was that a
  selection produced an empty buffer AND a stale target-frame
  simultaneously. This file pins the conjunction.

  ## What it drives

  Real production code path, no DOM / no React / no Playwright:

    1. Install Story canonical vocab + Causa (`:rf/causa`) via the
       multi-frame harness. The harness wires `register-trace-collector!`
       so REAL variant dispatches fan out through the trace-bus exactly
       as the production preload does.
    2. Register a counter story whose variant carries a non-empty
       `:events` slot (`[:counter/initialise]` — the canonical counter
       cascade) plus the event handler the slot dispatches.
    3. Install the production `selection-watcher` and select the
       variant — the same atom-write the sidebar click does. The watcher
       runs `ensure-variant-frame!` (→ `run-variant` dispatches the
       variant's `:events` into its frame, traced into the bus) AND
       dispatches `:rf.causa/set-target-frame <variant-id>`.
    4. Mirror the bus into Causa's `:trace-buffer` slot synchronously
       (the production path coalesces this on `next-tick`; node-test
       drives it inline so the slot is observable on the same tick).
    5. Assert BOTH halves of the contract.

  Sub-second; node CLJS."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support :as test-support]
            [re-frame.story :as story]
            [re-frame.story.ui.shell :as shell]
            [re-frame.story.test-helpers.e2e-multi-frame :as e2e]
            [day8.re-frame2-causa.test-helpers.e2e-multi-frame :as causa-e2e]))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture {:adapter plain-atom/adapter}))

;; ---- helpers -------------------------------------------------------------

(defn- install-selection-watcher!
  "Install the shell's production `selection-watcher` via `var` so the
  test drives a selection edge through the SAME callback `mount-shell!`
  installs during boot — `ensure-variant-frame!` (which dispatches the
  variant's `:events`) and the `:rf.causa/set-target-frame` dispatch
  both fire from here."
  []
  ((deref #'shell/selection-watcher)))

(defn- remove-selection-watcher!
  []
  ((deref #'shell/remove-selection-watcher!)))

(defn- register-counter-host!
  "Register the counter event the variant's `:events` slot dispatches.
  Story variants dispatch their `:events` into the variant frame via
  `run-variant`; the handler must be registered for the dispatch to
  mutate app-db (and so produce a non-trivial epoch + cascade). Matches
  the canonical counter example: `:counter/initialise` seeds the slot
  at 5."
  []
  (rf/reg-event-db :counter/initialise
    (fn [_db _event] {:counter/value 5})))

;; Variant-id IS the frame-id per `re-frame.story.frames`.
(def ^:private variant-id :story.counter/loaded)

(defn- register-counter-story! []
  (register-counter-host!)
  (story/reg-story :story.counter
    {:doc "Counter parent story for the Story-Causa wiring contract test."})
  (story/reg-variant variant-id
    {:doc    "Counter seeded at 5 — its :events cascade must land in
              Causa's trace-buffer on selection."
     :events [[:counter/initialise]]}))

;; ---- the conjunction contract -------------------------------------------

(deftest variant-selection-seeds-trace-buffer-and-target-frame
  (testing "Selecting a variant whose `:events` actually dispatch
            (`:counter/initialise`) seeds BOTH halves of the Causa
            observation contract in ONE selection edge:

              (a) `:rf.causa/target-frame` re-orients to the variant-id
                  (the Story-side #1822 fix), AND
              (b) the variant's `:counter/initialise` cascade lands in
                  Causa's trace-buffer / cascades (the trace-bus →
                  buffer wiring the Causa-side #1822 fix restored).

            Pre-fix the RHS rendered empty Event + App-DB panels because
            the buffer was empty AND the target-frame was stale at the
            same time. This pins the conjunction the two #1822 unit
            tests cover only separately."
    (e2e/with-story-and-causa-frames
      {:register-stories register-counter-story!}
      (fn []
        (install-selection-watcher!)
        (try
          ;; The selection edge: same atom-write the sidebar click does.
          ;; Fires `ensure-variant-frame!` → `run-variant` dispatches
          ;; `[:counter/initialise]` into the variant frame (traced into
          ;; the bus) AND `:rf.causa/set-target-frame <variant-id>`.
          (e2e/select-variant! variant-id)
          ;; Mirror the bus into Causa's `:trace-buffer` slot — production
          ;; coalesces this on next-tick; drive it inline so the slot is
          ;; observable on this tick (matches the harness convention).
          (causa-e2e/sync-causa-trace-mirror!)

          ;; (a) target-frame re-oriented to the selected variant.
          (rf/with-frame :rf/causa
            (is (= variant-id @(rf/subscribe [:rf.causa/target-frame]))
                "Causa's :target-frame reflects the selected variant
                 after the selection-watcher's set-target-frame dispatch."))

          ;; (b) the variant's :events cascade is observable in Causa.
          (let [cascades (causa-e2e/causa-cascades)
                events   (into #{} (map :event) cascades)]
            (is (pos? (count cascades))
                "Causa's trace-buffer carries at least the variant's
                 cascade — the bus → :trace-buffer wiring fired.")
            (is (contains? events [:counter/initialise])
                "the variant's `[:counter/initialise]` event surfaces as
                 a cascade in Causa — proving the variant's :events
                 dispatched through the real trace-bus pipeline and not
                 just the target-frame slot flipping."))

          ;; (c) epoch-history hydrated in lockstep — `set-target-frame`
          ;; re-reads the variant frame's epoch ring, which now carries
          ;; the `:counter/initialise` write. A populated history is what
          ;; the App-DB Diff / Views panels render against; an empty one
          ;; is the empty-RHS symptom.
          (rf/with-frame :rf/causa
            (is (pos? (count @(rf/subscribe [:rf.causa/epoch-history])))
                ":epoch-history re-seeds from the variant frame's epoch
                 ring (now non-empty after the :counter/initialise write)
                 — the slot the App-DB / Views panels render against."))
          (finally
            (remove-selection-watcher!)))))))

(deftest switching-variants-re-seeds-both-slots
  (testing "Switching from one variant to another re-seeds BOTH the
            trace-buffer view (cascades now include the second variant's
            event) AND the target-frame — the empty-RHS bug would
            reappear on every switch if either half failed to re-fire."
    (e2e/with-story-and-causa-frames
      {:register-stories
       (fn []
         (rf/reg-event-db :counter/initialise
           (fn [_db _event] {:counter/value 5}))
         (rf/reg-event-db :counter/seed-ten
           (fn [_db _event] {:counter/value 10}))
         (story/reg-story :story.counter {})
         (story/reg-variant :story.counter/loaded
           {:events [[:counter/initialise]]})
         (story/reg-variant :story.counter/ten
           {:events [[:counter/seed-ten]]}))}
      (fn []
        (install-selection-watcher!)
        (try
          (e2e/select-variant! :story.counter/loaded)
          (causa-e2e/sync-causa-trace-mirror!)
          (rf/with-frame :rf/causa
            (is (= :story.counter/loaded
                   @(rf/subscribe [:rf.causa/target-frame]))
                "first selection orients Causa on :story.counter/loaded"))

          (e2e/select-variant! :story.counter/ten)
          (causa-e2e/sync-causa-trace-mirror!)
          (rf/with-frame :rf/causa
            (is (= :story.counter/ten
                   @(rf/subscribe [:rf.causa/target-frame]))
                "switch re-orients target-frame to :story.counter/ten"))
          (let [events (into #{} (map :event) (causa-e2e/causa-cascades))]
            (is (contains? events [:counter/seed-ten])
                "the second variant's `[:counter/seed-ten]` cascade is
                 observable in Causa after the switch — the buffer
                 re-seeds on every selection edge, not just the first."))
          (finally
            (remove-selection-watcher!)))))))
