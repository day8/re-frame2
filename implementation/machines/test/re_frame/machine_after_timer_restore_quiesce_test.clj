(ns re-frame.machine-after-timer-restore-quiesce-test
  "Epoch-restore host-transient quiesce for machine `:after` timers.

  Epoch restore installs the captured durable frame-state (the machine
  snapshots in runtime-db) WHOLESALE, so timer LIVENESS reverts atomically —
  a pre-restore timer that later fires carries a stale per-path epoch and is
  silently dropped via `:rf.machine.timer/stale-after` (Spec 005 §Hierarchy
  interaction). But the host-clock HANDLE is NOT frame-state: it stays armed,
  attached to the pre-restore timeline, leaking a wall-clock timer that would
  still fire (and dispatch a doomed-stale synthetic event) unless released.

  `cancel-frame-timers-on-restore!` (published as the
  `:machines/on-frame-restored!` late-bind hook the epoch restore boundary
  consults) is the restore counterpart of the `:on-frame-destroy` cleanup —
  it releases the restored frame's orphaned `:after` host handles eagerly and
  emits one `:rf.machine.timer/cancelled` trace per entry with
  `:reason :on-restore` (Managed-Effects §restore: \"epoch restore MUST NOT
  revive host work\")."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.late-bind :as late-bind]
            [re-frame.machines :as machines]
            [re-frame.machines.test-support :as mtest]
            [re-frame.machines.timer :as timer]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.trace.tooling :as trace-tooling]))

(use-fixtures :each
  (mtest/make-reset-runtime-fixture {:adapter plain-atom/adapter}))

;; A :loading state with a long-delay :after — the host clock will not fire it
;; during the test; it lingers in the timer table so we can assert the handle
;; is released by the restore quiesce.
(def ^:private spec
  {:initial :idle
   :data    {}
   :states
   {:idle    {:on {:fetch :loading}}
    :loading {:after {3600000 :timeout}
              :on    {:loaded :ready}}
    :timeout {}
    :ready   {}}})

(deftest hook-published
  (testing "the :machines/on-frame-restored! late-bind hook is published"
    (is (some? (late-bind/get-fn :machines/on-frame-restored!)))))

(deftest cancel-frame-timers-on-restore-releases-the-frames-handles
  (testing "rf2-u5kmf8 — cancel-frame-timers-on-restore! drops the frame's
            armed :after host-clock handles (and only that frame's)"
    (rf/reg-machine :rq/m spec)
    (rf/reg-frame :rq/restored {:doc "the frame being restored"})
    (rf/reg-frame :rq/sibling  {:doc "an unrelated concurrent frame"})
    (rf/dispatch-sync [:rq/m [:fetch]] {:frame :rq/restored})
    (rf/dispatch-sync [:rq/m [:fetch]] {:frame :rq/sibling})
    (is (and (contains? @timer/after-timers :rq/restored)
             (contains? @timer/after-timers :rq/sibling))
        "precondition: both frames armed an :after timer")
    (timer/cancel-frame-timers-on-restore! :rq/restored)
    (is (not (contains? @timer/after-timers :rq/restored))
        "the restored frame's armed timer handles are GONE after the quiesce")
    (is (contains? @timer/after-timers :rq/sibling)
        "a sibling frame's timers are untouched (frame-scoped quiesce)")))

(deftest cancel-frame-timers-on-restore-emits-on-restore-reason
  (testing "rf2-u5kmf8 — each released timer emits one
            :rf.machine.timer/cancelled trace with :reason :on-restore"
    (rf/reg-machine :rq2/m spec)
    (rf/reg-frame :rq2/f {:doc "restore-reason trace frame"})
    (rf/dispatch-sync [:rq2/m [:fetch]] {:frame :rq2/f})
    (is (seq (get @timer/after-timers :rq2/f)) "precondition: a timer is armed")
    (let [seen (atom [])
          k    ::on-restore-recorder]
      (trace-tooling/register-listener!
        k (fn [ev] (when (= :rf.machine.timer/cancelled (:operation ev))
                     (swap! seen conj ev))))
      (try
        (timer/cancel-frame-timers-on-restore! :rq2/f)
        (finally (trace-tooling/unregister-listener! k)))
      (is (seq @seen) "a :rf.machine.timer/cancelled trace fired")
      (is (every? #(= :on-restore (:reason (:tags %))) @seen)
          "every cancelled row carries :reason :on-restore")
      (is (some #(= :rq2/f (:frame (:tags %))) @seen)
          "the cancelled row names the restored frame"))))

(deftest cancel-frame-timers-on-restore-noop-on-unarmed-frame
  (testing "rf2-u5kmf8 — quiescing a frame with no armed timers is a no-op"
    (is (nil? (timer/cancel-frame-timers-on-restore! :rq/never-armed)))))

(deftest restore-quiesce-hook-clears-the-restored-frames-timers-end-to-end
  (testing "rf2-u5kmf8 — driving the published :machines/on-frame-restored!
            hook (the path the epoch boundary uses) clears the frame's timers"
    (rf/reg-machine :rq3/m spec)
    (rf/reg-frame :rq3/f {:doc "end-to-end hook frame"})
    (rf/dispatch-sync [:rq3/m [:fetch]] {:frame :rq3/f})
    (is (contains? @timer/after-timers :rq3/f) "precondition: a timer is armed")
    ((late-bind/get-fn :machines/on-frame-restored!) :rq3/f)
    (is (not (contains? @timer/after-timers :rq3/f))
        "the published restore hook released the frame's armed timer handles")))
