(ns re-frame.timer-frame-scope-test
  "Frame-scoping of `re-frame.machines.timer/after-timers`.

  The table is `{<frame-id> {<inner-key> <entry>}}` — entries partition by
  frame-id, so frame isolation holds under concurrent fixture runs (a
  `reset-timers!` on one frame leaves siblings untouched) and
  `after-cancel-fx` scans only the active frame's entries
  (O(timers-this-frame), not O(timers-all-frames)). The public API offers a
  1-arity `reset-timers!` / `cancel-all-timers!` for per-frame teardown, and
  the destroy-frame! hook `:machines/on-frame-destroyed!` releases a
  destroyed frame's timers without disturbing siblings.

  The assertions below exercise both the structural invariant (entries
  partition by frame-id) and the behaviour: two frames scheduling timers
  under OVERLAPPING `[parent-id invoke-id delay-key]` tuples keep separate
  entries, and 1-arity reset / frame-destroy clears only the targeted
  frame."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.machines :as rf.machines]
            [re-frame.machines.test-support :as rf.machines.test-support]
            [re-frame.machines.timer :as rf.machines.timer]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]))

(use-fixtures :each
  (rf.machines.test-support/make-reset-runtime-fixture {:adapter rf.substrate.plain-atom/adapter}))

;; ---- the machine under test -----------------------------------------------
;;
;; The same machine spec is registered once and dispatched against two
;; sibling frames. The :loading state carries a long-delay :after that
;; the host clock will not fire during the test — the entry simply
;; lingers in the timer table so we can read it back. The synchronous
;; pure-side transition emits the :rf.machine/after-schedule fx; the fx
;; handler in `re-frame.machines.timer` installs the host-clock handle
;; under [<frame-id> {:parent <parent-id> :spawn <invoke-id-vec> :delay <delay-key>}].

(def ^:private spec
  {:initial :idle
   :data    {}                          ;; :rf/after-epoch is runtime-managed (per-decl-path map)
   :states
   {:idle    {:on {:fetch :loading}}
    :loading {:after {3600000 :timeout}
              :on    {:loaded :ready}}
    :timeout {}
    :ready   {}}})

;; ---- regression: destroy-frame! clears just the destroyed frame's timers --

(deftest destroy-frame-clears-only-the-destroyed-frames-timers
  (testing "the :machines/on-frame-destroyed! late-bind hook releases just the destroyed frame's entries"
    (rf/reg-machine :ds/m spec)
    (rf/make-frame {:id :ds/keep :doc "survives"})
    (rf/make-frame {:id :ds/discard :doc "destroyed"})
    (rf/dispatch-sync [:ds/m [:fetch]] {:frame :ds/keep})
    (rf/dispatch-sync [:ds/m [:fetch]] {:frame :ds/discard})
    (is (and (contains? @rf.machines.timer/after-timers :ds/keep)
             (contains? @rf.machines.timer/after-timers :ds/discard))
        "preconditions: both frames have entries")
    (rf/destroy-frame! :ds/discard)
    (is (not (contains? @rf.machines.timer/after-timers :ds/discard))
        ":machines/on-frame-destroyed! hook clears the destroyed frame's entries")
    (is (contains? @rf.machines.timer/after-timers :ds/keep)
        "destroy-frame! on the discarded frame must not touch the survivor's entries")))

;; ---- regression: 0-arity reset clears everything --------------------------

(deftest zero-arity-reset-timers-clears-every-frame
  (testing "0-arity reset-timers! clears every frame's timers"
    (rf/reg-machine :iso0/m spec)
    (rf/make-frame {:id :iso0/a})
    (rf/make-frame {:id :iso0/b})
    (rf/dispatch-sync [:iso0/m [:fetch]] {:frame :iso0/a})
    (rf/dispatch-sync [:iso0/m [:fetch]] {:frame :iso0/b})
    (is (seq @rf.machines.timer/after-timers) "preconditions: both frames have entries")
    (rf.machines/reset-timers!)
    (is (= {} @rf.machines.timer/after-timers)
        "0-arity clears the whole table — the fixture-teardown shape")))

;; ---- regression: after-cancel-fx is scoped to the active frame -----------

(deftest after-cancel-fx-scoped-to-active-frame
  (testing "exiting an :after-bearing state in one frame must not cancel sibling frames' timers"
    (rf/reg-machine :sc/m spec)
    (rf/make-frame {:id :sc/A})
    (rf/make-frame {:id :sc/B})
    (rf/dispatch-sync [:sc/m [:fetch]] {:frame :sc/A})
    (rf/dispatch-sync [:sc/m [:fetch]] {:frame :sc/B})
    (is (and (contains? @rf.machines.timer/after-timers :sc/A)
             (contains? @rf.machines.timer/after-timers :sc/B))
        "both frames installed a timer entry on :loading entry")
    ;; Exiting :loading (via :loaded → :ready) on frame :sc/A emits
    ;; :rf.machine/after-cancel; after-cancel-fx must clear only the
    ;; active frame's matching entries.
    (rf/dispatch-sync [:sc/m [:loaded]] {:frame :sc/A})
    (is (not (contains? @rf.machines.timer/after-timers :sc/A))
        "frame A's timer table is empty after the state exit")
    (is (contains? @rf.machines.timer/after-timers :sc/B)
        "frame B's table is untouched by frame A's :rf.machine/after-cancel")))
