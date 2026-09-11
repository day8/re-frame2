(ns day8.re-frame2-xray.views.resizable-table-persistence-dom-cljs-test
  "Browser-lane half of the resizable-table column-widths persistence
  tests (rf2-xzg1y), promoted out of
  `day8.re-frame2-xray.views.resizable-table-persistence-cljs-test`
  under rf2-r51p.

  WHY A SEPARATE NAMESPACE. The sibling keeps the `->edn` / `<-edn`
  algebra, the clamp arithmetic and the pre-registration hydrate
  short-circuit, none of which touches a host. The `save!` / `load`
  round-trip, the per-instance storage-key isolation, the tick-does-not-
  persist / commit-does-persist split (rf2-xm1jy) and the hydrate lift
  need a real `window.localStorage`, and only a namespace ending
  `-dom-cljs-test` is ever loaded by the `:browser-test` build, whose
  `:ns-regexp` is `.*-dom-cljs-test$`. Sitting in the sibling file these
  rows executed in NEITHER lane: skipped under `:node-test` for want of
  storage (no jsdom in any dependency list), and never loaded by
  `:browser-test` at all. The sibling ran 15 test vars containing 11
  assertions — FEWER assertions than vars, because whole deftests
  contributed nothing. The file's LOCATION was the defect. The guard was
  not.

  THE GUARD STAYS, BECAUSE THIS FILE RUNS ON BOTH LANES. `:node-test`'s
  `:ns-regexp` is `cljs-test$` — a bare SUFFIX match, which
  `-dom-cljs-test` satisfies exactly as `-cljs-test` does, so the node
  build loads this namespace too and the overlap is deliberate (see the
  comment above `:browser-test` in `implementation/shadow-cljs.edn`).
  Moving a row here ADDS the browser lane; it does not take the row off
  the node one. `ls/available?` is what keeps the node run inert.

  ONE ROW DID NOT COME WITH THE OTHERS, BECAUSE THE CHOICE IS PER
  PROPERTY. `resize-pair-tick-clamps-sub-floor-width` asserts only over
  app-db — it never reads storage — so its guard was incidental rather
  than load-bearing. That row stayed in the sibling with the guard
  REMOVED, which makes it run on node for the first time. Moving it here
  would have bought it nothing and cost it the node lane's speed.

  THE SKIP BRANCH ASSERTS RATHER THAN VANISHING, so the node lane never
  holds a deftest with zero assertions — the hollow shape rf2-r51p
  exists to remove.

  These assertions had never executed in ANY lane before this namespace
  existed. A failure here is evidence arriving for the first time, not a
  regression introduced by the move."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [day8.re-frame2-xray.local-storage :as ls]
            [day8.re-frame2-xray.registry :as registry]
            [day8.re-frame2-xray.test-support :as xray-test-support]
            [day8.re-frame2-xray.views.resizable-table :as rt]))

;; ---- fixture ------------------------------------------------------------

(use-fixtures :each
  ;; Mirrors the sibling's fixture. The slate matters more here than on
  ;; node: the browser lane runs every namespace on ONE page, so leftover
  ;; column widths would be in storage when the next namespace hydrates.
  (xray-test-support/make-xray-runtime-fixture
    {:post-reset (fn []
                   (rt/clear!)
                   (rt/set-storage-key! nil))}))

(defn- xray-setup!
  "Register Xray handlers + the :rf/xray frame, then re-run the
  column-widths hydration so any localStorage value lifts into the slot.
  Mirrors the sibling's `xray-setup!`."
  []
  (registry/register-xray-handlers!)
  (rf/make-frame {:id :rf/xray})
  (rt/hydrate!))

(defn- frame-sub [q]
  (rf/with-frame :rf/xray
    @(rf/subscribe q)))

(defn- frame-dispatch [ev]
  (rf/with-frame :rf/xray
    (rf/dispatch-sync ev)))

;; ---- save! / load round-trip -------------------------------------------

(deftest save-and-load-round-trip
  (if-not (ls/available?)
    (is true "skipped: no localStorage (node lane — see ns docstring)")
    (let [widths {:rf.xray.epoch/subscriptions {:sub 300 :inputs 150 :value 200}}]
      (rt/clear!)
      (rt/save! widths)
      (is (= widths (rt/load))
          "browser-backed round-trip preserves the {table-id {col-id px}} shape"))))

;; ---- Storage-key override (per-instance isolation) ----------------------

(deftest custom-storage-key-isolates-per-instance
  (if-not (ls/available?)
    (is true "skipped: no localStorage (node lane — see ns docstring)")
    (testing "story testbeds set distinct keys so two Xray instances
              do not stomp on each other's column-widths state"
      (rt/set-storage-key! "story.testbed.a.column-widths")
      (rt/save! {:t1 {:a 100}})
      ;; Precondition, not decoration: the `(= {} (load))` below passes
      ;; on a silently no-op storage, where nothing was ever written and
      ;; every slot reads empty. Proving A's write landed is what makes
      ;; B's empty read discriminating.
      (is (= {:t1 {:a 100}} (rt/load))
          "precondition: instance A's write really did persist")
      (rt/set-storage-key! "story.testbed.b.column-widths")
      (is (= {} (rt/load))
          "instance B's slot is independent of instance A")
      (rt/save! {:t2 {:b 200}})
      (rt/set-storage-key! "story.testbed.a.column-widths")
      (is (= {:t1 {:a 100}} (rt/load))
          "instance A's slot survived instance B's write"))))

;; ---- resize-pair tick + commit (rf2-xm1jy split) ------------------------

(deftest resize-pair-tick-writes-slot-without-persisting
  (if-not (ls/available?)
    (is true "skipped: no localStorage (node lane — see ns docstring)")
    (testing "rf2-xm1jy — the pointermove-cadence event writes the slot
              in app-db but does NOT touch localStorage (per-pixel
              persistence flooded the main thread on lower-end devices)."
      ;; Sentinel precondition. The payload assertion of this test is a
      ;; NEGATIVE one — `(= {} (load))` — which passes trivially against
      ;; a storage that silently swallows every write. Writing a sentinel
      ;; and reading it back proves the storage is live, so the empty
      ;; read afterwards is evidence the TICK withheld the write rather
      ;; than evidence the host has no storage.
      ;; The sentinel width must sit ABOVE the 24px floor. The first cut
      ;; of this row used `{:col 1}` and the browser lane read back
      ;; `{:col 24}` — the load path clamping a sub-floor width exactly
      ;; as `resize-pair-tick-clamps-sub-floor-width` says it should.
      ;; That was this test picking an illegal value, not the product
      ;; misbehaving; the round-trip itself worked.
      (rt/save! {:sentinel {:col 150}})
      (is (= {:sentinel {:col 150}} (rt/load))
          "precondition: storage is live and round-trips")
      (rt/clear!)
      (xray-setup!)
      (frame-dispatch [:rf.xray.column-widths/resize-pair-tick
                       :rf.xray.epoch/subscriptions
                       :sub 250 :inputs 170])
      (is (= {:sub 250 :inputs 170}
             (frame-sub [:rf.xray.column-widths/for-table
                         :rf.xray.epoch/subscriptions]))
          "app-db slot reflects the tick")
      (is (= {} (rt/load))
          "localStorage is NOT written by the tick event"))))

(deftest resize-pair-commit-persists-current-slot
  (if-not (ls/available?)
    (is true "skipped: no localStorage (node lane — see ns docstring)")
    (testing "rf2-xm1jy — pointerup dispatches the commit, which writes
              whatever the app-db slot currently holds to localStorage
              exactly once. Round-trip: N ticks then one commit ==
              steady-state widths in localStorage."
      (xray-setup!)
      (rt/clear!)
      (frame-dispatch [:rf.xray.column-widths/resize-pair-tick
                       :rf.xray.epoch/subscriptions
                       :sub 100 :inputs 100])
      (frame-dispatch [:rf.xray.column-widths/resize-pair-tick
                       :rf.xray.epoch/subscriptions
                       :sub 200 :inputs 200])
      (frame-dispatch [:rf.xray.column-widths/resize-pair-tick
                       :rf.xray.epoch/subscriptions
                       :sub 250 :inputs 170])
      (is (= {} (rt/load))
          "ticks accumulate in app-db only; localStorage untouched")
      (frame-dispatch [:rf.xray.column-widths/resize-pair-commit])
      (is (= {:rf.xray.epoch/subscriptions {:sub 250 :inputs 170}}
             (rt/load))
          "commit writes the final settled widths to localStorage
           exactly once"))))

;; ---- reset clears one table AND persists --------------------------------

(deftest reset-clears-table-and-persists
  (if-not (ls/available?)
    (is true "skipped: no localStorage (node lane — see ns docstring)")
    (do
      (xray-setup!)
      (rt/clear!)
      (frame-dispatch [:rf.xray.column-widths/resize-pair-tick
                       :t1 :a 100 :b 200])
      (frame-dispatch [:rf.xray.column-widths/resize-pair-commit])
      (frame-dispatch [:rf.xray.column-widths/resize-pair-tick
                       :t2 :a 50 :b 70])
      (frame-dispatch [:rf.xray.column-widths/resize-pair-commit])
      (frame-dispatch [:rf.xray.column-widths/reset :t1])
      (is (nil? (frame-sub [:rf.xray.column-widths/for-table :t1]))
          "t1's overrides cleared")
      (is (= {:a 50 :b 70}
             (frame-sub [:rf.xray.column-widths/for-table :t2]))
          "t2's overrides untouched")
      (is (= {:t2 {:a 50 :b 70}} (rt/load))
          "localStorage reflects the reset"))))

;; ---- hydrate! lifts persisted slot into app-db --------------------------

(deftest hydrate-lifts-persisted-widths
  (if-not (ls/available?)
    (is true "skipped: no localStorage (node lane — see ns docstring)")
    (do
      ;; Seed localStorage BEFORE setup so hydrate sees it.
      (rt/clear!)
      (rt/save! {:rf.xray.epoch/views {:view 180 :subs 220}})
      (xray-setup!)
      (is (= {:view 180 :subs 220}
             (frame-sub [:rf.xray.column-widths/for-table
                         :rf.xray.epoch/views]))
          "hydrate! ran in xray-setup! and lifted the slot into app-db"))))
