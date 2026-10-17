(ns re-frame.machine-destroy-tail-incarnation-fence-test
  "A teardown write that outlives the frame incarnation A that started it
  leaves a same-id successor B untouched: an `:after` timer release cancels
  only its own handle."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as rf.frame]
            [re-frame.interop :as rf.interop]
            [re-frame.machines :as rf.machines]
            [re-frame.machines.test-support :as rf.machines.test-support]
            [re-frame.machines.timer :as rf.machines.timer]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.trace.tooling :as rf.trace.tooling]))

;; Touch the artefact so the machines registration hooks are wired even when
;; this ns runs in isolation.
(def ^:private _artefact rf.machines/machine-transition)

(use-fixtures :each
  (rf.machines.test-support/make-reset-runtime-fixture {:adapter rf.substrate.plain-atom/adapter}))

(def ^:private actor-id (keyword "rf2-i4aj9c" "child#1"))

;; Releasing an `:after` timer cancels the entry's own host handle. Once a
;; cancelled-trace listener has destroyed A and a same-id B has armed the same
;; key, A's release must leave B's entry alone; with no successor it cancels
;; A's handle and the slot is emptied.
(deftest timer-release-cancels-only-its-own-handle
  (doseq [lose-a? [true false]]
    (let [frame-a   (keyword "rf2-i4aj9c" (str "release-" lose-a?))
          k         {:parent actor-id :spawn [] :delay 5000}
          a-handle  (Object.)
          b-entry   {:handle (Object.) :resolved-ms 5000 :epoch 0 :state :running
                     :region nil :delay-source :literal :token ::b-token}
          cancelled (atom [])
          fired?    (atom false)]
      (rf/make-frame {:id frame-a})
      (swap! rf.machines.timer/after-timers assoc-in [frame-a k]
             {:handle a-handle :resolved-ms 5000 :epoch 0 :state :running
              :region nil :delay-source :literal :token ::a-token})
      (rf.trace.tooling/register-listener!
        ::release-fence
        (fn [ev]
          (when (and lose-a?
                     (= :rf.machine.timer/cancelled (:operation ev))
                     (compare-and-set! fired? false true))
            (rf.frame/destroy-frame! frame-a)
            (rf/make-frame {:id frame-a})
            (swap! rf.machines.timer/after-timers assoc-in [frame-a k] b-entry))))
      (try
        (with-redefs [rf.interop/cancel-scheduled! (fn [h] (swap! cancelled conj h) nil)]
          (rf.machines.timer/cancel-actor-timers! frame-a actor-id))
        (is (= [(when lose-a? b-entry) [a-handle]]
               [(get-in @rf.machines.timer/after-timers [frame-a k]) @cancelled])
            (if lose-a?
              "A's release cancels A's handle and leaves B's entry"
              "with no successor, A's release cancels its handle and empties the slot"))
        (finally
          (rf.trace.tooling/unregister-listener! ::release-fence)
          (swap! rf.machines.timer/after-timers dissoc frame-a))))))
