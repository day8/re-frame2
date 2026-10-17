(ns re-frame.after-timer-announce-claim-race-test
  "A `:rf.machine.timer/scheduled` listener that claims the attempt its row
  announces closes that row at once, before anything the listener goes on to
  announce.

  `schedule-after-timer!` reserves the slot ABOVE the `/scheduled` emit, so an
  announced attempt is cancellable from the instant it is visible, and the
  claimant's `/cancelled` follows the row it closes. A listener runs on the
  row's own stack only outside a drain — inside one, delivery waits for the
  drain to end — so the test arms through the hydration seam directly."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as rf.frame]
            [re-frame.interop :as rf.interop]
            [re-frame.machines]
            [re-frame.machines.hydrate :as rf.machines.hydrate]
            [re-frame.machines.test-support :as rf.machines.test-support]
            [re-frame.machines.timer :as rf.machines.timer]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.trace.tooling :as rf.trace.tooling]))

(use-fixtures :each
  (rf.machines.test-support/make-reset-runtime-fixture {:adapter rf.substrate.plain-atom/adapter})
  rf.machines.test-support/trace-capture-fixture)

(def ^:private frame-counter (atom 0))

(defn- fresh-frame!
  "A frame of the given platform under an id no other test in this process has used."
  [platform]
  (let [fid (keyword "rf.announce" (str (name platform) (swap! frame-counter inc)))]
    (rf/make-frame {:id fid :platform platform})
    fid))

(defn- inner
  "`frame-id`'s inner `:after` timer table, or `{}` when it holds none."
  [frame-id]
  (get @rf.machines.timer/after-timers frame-id {}))

(defn- server-runtime-db
  "Run `machine-id` into its `:after`-bearing state on a real SERVER frame and
  return the resulting runtime-db value — a genuine hydration slice."
  [machine-id]
  (let [sfid (fresh-frame! :server)]
    (rf/dispatch-sync [machine-id [:go]] {:frame sfid})
    (rf.frame/frame-runtime-db-value sfid)))

(defn- install!
  "Replace `frame-id`'s runtime-db and run the machines hydration seam — what
  `:rf/hydrate` does once its runtime-db effect has committed."
  [frame-id runtime-db]
  (rf.frame/replace-runtime-db! frame-id runtime-db)
  (rf.machines.hydrate/rearm-after-timers! frame-id))

;; One literal-delay `:after`: the hydration arm is exactly one attempt.
(def ^:private literal-machine
  {:initial :idle
   :data    {}
   :states  {:idle    {:on {:go :waiting}}
             :waiting {:after {5000 {:target :timeout}}}
             :timeout {}}})

(defn- timer-ops
  "The captured `/scheduled` and `/cancelled` rows, oldest first."
  []
  (filterv (comp #{:rf.machine.timer/scheduled :rf.machine.timer/cancelled}
                 :operation)
           (rf.machines.test-support/captured-events)))

(deftest a-same-thread-claim-from-the-rows-own-listener-closes-it-before-a-successor-announces
  (testing "a `/scheduled` listener destroys A, publishes same-id B and
            hydrates the SAME snapshot into B — re-arming the same key. A's
            closure must land before B's announcement, or a consumer pairing
            on `(actor-id, state, epoch)` reads B's live timer as cancelled"
    (rf/reg-machine :announce/succ literal-machine)
    (let [rt     (server-runtime-db :announce/succ)
          cfid   (fresh-frame! :client)
          fired? (atom false)]
      (with-redefs [rf.interop/schedule-after!   (fn [_thunk _ms] ::handle)
                    rf.interop/cancel-scheduled! (fn [_h] nil)]
        (rf.machines.test-support/reset-captured!)
        (rf.trace.tooling/register-listener!
          ::succ
          (fn [ev]
            (when (and (= :rf.machine.timer/scheduled (:operation ev))
                       (compare-and-set! fired? false true))
              (rf.frame/destroy-frame! cfid)
              (rf/make-frame {:id cfid :platform :client})
              (install! cfid rt))))
        (try
          (install! cfid rt)
          (finally (rf.trace.tooling/unregister-listener! ::succ))))
      (is (= [[:rf.machine.timer/scheduled nil]
              [:rf.machine.timer/cancelled :on-frame-destroy]
              [:rf.machine.timer/scheduled nil]]
             (mapv (juxt :operation (comp :reason :tags)) (timer-ops)))
          (str "A's row, A's closure by the destroy sweep, THEN B's row: the "
               "sweep claimed A on the row's own stack, where the row is "
               "already out, so it emits at once"))
      (is (= 1 (count (inner cfid)))
          "B's own timer is armed and survives A's late publish, which finds B's token in the slot"))))
