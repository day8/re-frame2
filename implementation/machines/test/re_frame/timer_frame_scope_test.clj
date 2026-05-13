(ns re-frame.timer-frame-scope-test
  "Per rf2-ysa94: regression test for the frame-scoping of
  `re-frame.machines.timer/after-timers` — the last process-global atom
  in the machines artefact prior to this refactor.

  Pre-rf2-ysa94 the table was a single flat map keyed by
  `[frame-id parent-id invoke-id delay-key]`. Two consequences (the
  rf2-ra1he audit §TM4 motivation):

    1. Frame isolation broken under concurrent fixture runs. A
       `reset-timers!` call from one test fixture cleared sibling test
       fixtures' entries via `(reset! after-timers {})`.

    2. `after-cancel-fx` did a linear scan over the entire atom on every
       state-exit-with-:after — O(timers-all-frames) instead of
       O(timers-this-frame).

  Post-rf2-ysa94 the table is `{<frame-id> {<inner-key> <entry>}}` and
  the public API gains a 1-arity `reset-timers!` / `cancel-all-timers!`
  for per-frame teardown. The destroy-frame hook
  `:machines/on-frame-destroyed!` releases a destroyed frame's timers
  without disturbing siblings.

  The assertions below exercise both the structural invariant (entries
  partition by frame-id) and the behaviour: two frames scheduling timers
  under OVERLAPPING `[parent-id invoke-id delay-key]` tuples observe
  independent lifecycles, and 1-arity reset / frame-destroy clears only
  the targeted frame."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.machines :as machines]
            [re-frame.machines.timer :as timer]
            [re-frame.registrar :as registrar]
            [re-frame.substrate.plain-atom :as plain-atom]))

(defn- reset-runtime [test-fn]
  (registrar/clear-all!)
  (reset! frame/frames {})
  (rf/init! plain-atom/adapter)
  (require 're-frame.machines :reload)
  (machines/reset-timers!)
  (test-fn))

(use-fixtures :each reset-runtime)

;; ---- the machine under test -----------------------------------------------
;;
;; The same machine spec is registered once and dispatched against two
;; sibling frames. The :loading state carries a long-delay :after that
;; the host clock will not fire during the test — the entry simply
;; lingers in the timer table so we can read it back. The synchronous
;; pure-side transition emits the :rf.machine/after-schedule fx; the fx
;; handler in `re-frame.machines.timer` installs the host-clock handle
;; under [<frame-id> [<parent-id> <invoke-id-vec> <delay-key>]].

(def ^:private spec
  {:initial :idle
   :data    {:rf/after-epoch 0}
   :states
   {:idle    {:on {:fetch :loading}}
    :loading {:after {3600000 :timeout}
              :on    {:loaded :ready}}
    :timeout {}
    :ready   {}}})

;; ---- regression: two frames' timers stay disjoint ------------------------

(deftest two-frames-with-overlapping-timer-ids-stay-isolated
  (testing "schedule + cancellation on one frame must not perturb the other's table"
    (rf/reg-machine :iso/m spec)
    (rf/reg-frame :iso/left  {:doc "left frame — timer-isolation regression"})
    (rf/reg-frame :iso/right {:doc "right frame — timer-isolation regression"})

    ;; Drive each frame into :loading; the pure side emits one
    ;; `:rf.machine/after-schedule` fx and the timer fx handler installs
    ;; one inner-table entry per frame.
    (rf/dispatch-sync [:iso/m [:fetch]] {:frame :iso/left})
    (rf/dispatch-sync [:iso/m [:fetch]] {:frame :iso/right})

    (let [tt @timer/after-timers]
      (is (contains? tt :iso/left)
          "the timer table partitions entries under the left frame")
      (is (contains? tt :iso/right)
          "the timer table partitions entries under the right frame")
      ;; Inner keys: [<parent-id> <invoke-id-vec> <delay-key>]. Because the
      ;; machine spec is identical the inner keys collide across frames —
      ;; pre-rf2-ysa94 the keying included the frame-id; post-rf2-ysa94
      ;; the frame-id is the OUTER key and the inner keys legitimately
      ;; coincide.
      (let [left-inner-keys  (set (keys (get tt :iso/left)))
            right-inner-keys (set (keys (get tt :iso/right)))]
        (is (= left-inner-keys right-inner-keys)
            (str "inner keys are identical across frames — frame-id is "
                 "the partitioning axis, not a discriminator inside the "
                 "inner key"))
        (is (every? (fn [k]
                      (and (= :iso/m (nth k 0))
                           (vector? (nth k 1))
                           (= 3600000 (nth k 2))))
                    left-inner-keys)
            "inner-key shape is [parent-id invoke-id-vec delay-key]")))

    ;; 1-arity reset clears only the targeted frame.
    (machines/reset-timers! :iso/left)
    (let [tt @timer/after-timers]
      (is (not (contains? tt :iso/left))
          "1-arity reset-timers! drops the left frame's entire inner table")
      (is (contains? tt :iso/right)
          "the right frame's table survives a sibling-frame's reset"))))

;; ---- regression: destroy-frame clears just the destroyed frame's timers --

(deftest destroy-frame-clears-only-the-destroyed-frames-timers
  (testing "the :machines/on-frame-destroyed! late-bind hook releases just the destroyed frame's entries"
    (rf/reg-machine :ds/m spec)
    (rf/reg-frame :ds/keep    {:doc "survives"})
    (rf/reg-frame :ds/discard {:doc "destroyed"})
    (rf/dispatch-sync [:ds/m [:fetch]] {:frame :ds/keep})
    (rf/dispatch-sync [:ds/m [:fetch]] {:frame :ds/discard})
    (is (and (contains? @timer/after-timers :ds/keep)
             (contains? @timer/after-timers :ds/discard))
        "preconditions: both frames have entries")
    (rf/destroy-frame :ds/discard)
    (is (not (contains? @timer/after-timers :ds/discard))
        ":machines/on-frame-destroyed! hook clears the destroyed frame's entries")
    (is (contains? @timer/after-timers :ds/keep)
        "destroy-frame on the discarded frame must not touch the survivor's entries")))

;; ---- regression: 0-arity reset still clears everything --------------------

(deftest zero-arity-reset-timers-clears-every-frame
  (testing "0-arity reset-timers! preserves its pre-rf2-ysa94 contract"
    (rf/reg-machine :iso0/m spec)
    (rf/reg-frame :iso0/a {})
    (rf/reg-frame :iso0/b {})
    (rf/dispatch-sync [:iso0/m [:fetch]] {:frame :iso0/a})
    (rf/dispatch-sync [:iso0/m [:fetch]] {:frame :iso0/b})
    (is (seq @timer/after-timers) "preconditions: both frames have entries")
    (machines/reset-timers!)
    (is (= {} @timer/after-timers)
        "0-arity clears the whole table — the fixture-teardown shape")))

;; ---- regression: after-cancel-fx no longer scans across frames -----------

(deftest after-cancel-fx-scoped-to-active-frame
  (testing "exiting an :after-bearing state in one frame must not cancel sibling frames' timers"
    (rf/reg-machine :sc/m spec)
    (rf/reg-frame :sc/A {})
    (rf/reg-frame :sc/B {})
    (rf/dispatch-sync [:sc/m [:fetch]] {:frame :sc/A})
    (rf/dispatch-sync [:sc/m [:fetch]] {:frame :sc/B})
    (is (and (contains? @timer/after-timers :sc/A)
             (contains? @timer/after-timers :sc/B))
        "both frames installed a timer entry on :loading entry")
    ;; Exiting :loading (via :loaded → :ready) on frame :sc/A emits
    ;; :rf.machine/after-cancel; after-cancel-fx must clear only the
    ;; active frame's matching entries.
    (rf/dispatch-sync [:sc/m [:loaded]] {:frame :sc/A})
    (is (not (contains? @timer/after-timers :sc/A))
        "frame A's timer table is empty after the state exit")
    (is (contains? @timer/after-timers :sc/B)
        "frame B's table is untouched by frame A's :rf.machine/after-cancel")))
