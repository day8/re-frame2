(ns re-frame.after-timer-announce-claim-race-test
  "A cleanup that claims an `:after` attempt's timer-table sentinel BEFORE the
  attempt's `:rf.machine.timer/scheduled` row is out must not put its
  `:rf.machine.timer/cancelled` in front of that row.

  `schedule-after-timer!` reserves the slot ABOVE the `/scheduled` emit, so an
  announced attempt is cancellable from the instant it is visible. Between the
  reservation and the emit the sentinel is claimable but the row is not yet
  out, and on the JVM a concurrent cleanup can claim it there. A claimant that
  emitted `/cancelled` on its own stack would put the closure first and leave
  the `/scheduled` that follows as an orphan — the pairing contract Spec 005
  §Trace event catalogue states on `(actor-id, state, epoch)`, inverted.

  The interleaving is driven, not raced: `rf.trace/emit!` is wrapped for one
  hydration, and on the attempt's own `/scheduled` row the cleanup runs to
  completion on a second thread before the real emit proceeds. A raw `Thread`,
  not a `future`: `future` conveys the caller's dynamic bindings, and the
  runtime marks the announcing THREAD through one, so a conveyed cleanup would
  read as a synchronous listener on the row.

  The second test is the other side of that thread mark: a claim made ON the
  announcing thread comes from a listener on the row itself, so the row is
  already out and the `/cancelled` must follow it at once — before anything
  the listener goes on to announce.

  JVM only (`.clj`): the window exists only where a second thread can run
  between two instructions of the arm."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as rf.frame]
            [re-frame.interop :as rf.interop]
            [re-frame.machines]
            [re-frame.machines.hydrate :as rf.machines.hydrate]
            [re-frame.machines.test-support :as rf.machines.test-support]
            [re-frame.machines.timer :as rf.machines.timer]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.trace :as rf.trace]
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

(def ^:private pair-keys
  "The slots `:rf.machine.timer/cancelled` mirrors from `/scheduled`."
  [:actor-id :state :delay :epoch :frame])

(defn- timer-ops
  "The captured `/scheduled` and `/cancelled` rows, oldest first."
  []
  (filterv (comp #{:rf.machine.timer/scheduled :rf.machine.timer/cancelled}
                 :operation)
           (rf.machines.test-support/captured-events)))

(defn- on-another-thread!
  "Run `f` to completion on a fresh raw `Thread` and join it, rethrowing
  anything it threw. NOT a `future` — see the ns docstring."
  [f]
  (let [failure (atom nil)
        t       (Thread. ^Runnable (fn [] (try (f)
                                               (catch Throwable e
                                                 (reset! failure e)))))]
    (.start t)
    (.join t)
    (when-let [e @failure] (throw e))))

(defn- hydrate-with-cleanup-in-the-window!
  "Hydrate `frame-id` with `runtime-db`, and at the reserve-to-emit boundary of
  its one arm run `(cleanup! frame-id k)` to completion on a second thread.
  Returns how many host clocks were armed."
  [frame-id runtime-db cleanup!]
  (let [orig-emit! rf.trace/emit!
        seen?      (atom false)
        arms       (atom 0)]
    (with-redefs [rf.interop/schedule-after!   (fn [_thunk _ms] (swap! arms inc) ::handle)
                  rf.interop/cancel-scheduled! (fn [_h] nil)
                  rf.trace/emit!
                  (fn [op operation tags]
                    (when (and (= :rf.machine.timer/scheduled operation)
                               (compare-and-set! seen? false true))
                      ;; The sentinel is reserved; its row is not yet delivered.
                      (when-let [[k _entry] (first (inner frame-id))]
                        (on-another-thread! #(cleanup! frame-id k))))
                    (orig-emit! op operation tags))]
      (install! frame-id runtime-db))
    @arms))

(deftest a-claim-before-the-row-is-out-closes-the-row-rather-than-preceding-it
  ;; Every cleanup claims through the same `claim-cancel-and-release!`, so one
  ;; claimant drives the window; each cleanup's own claim of a sentinel is
  ;; pinned in after_timer_arm_publish_race_cljs_test.
  (rf/reg-machine :announce/exit literal-machine)
  (let [rt   (server-runtime-db :announce/exit)
        cfid (fresh-frame! :client)]
    (rf.machines.test-support/reset-captured!)
    (let [arms (hydrate-with-cleanup-in-the-window!
                 cfid rt
                 (fn [frame k] (rf.machines.timer/after-cancel-fx
                                 {:frame frame}
                                 {:rf/parent-id (:parent k) :rf/invoke-id (:spawn k)})))
          [scheduled cancelled :as ops] (timer-ops)]
      (is (= [:rf.machine.timer/scheduled :rf.machine.timer/cancelled]
             (mapv :operation ops))
          (str "the announcement first, its closure second — never a "
               "`/cancelled` ahead of the row it closes, leaving that row an "
               "orphan nothing downstream retracts"))
      (is (= (assoc (select-keys (:tags scheduled) pair-keys) :reason :on-exit)
             (select-keys (:tags cancelled) (conj pair-keys :reason)))
          "the closure pairs with the row and carries the CLAIMANT's own reason")
      (is (= [{} 0] [(inner cfid) arms])
          "the claimed attempt was neither published nor armed on the host"))))

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
          "B's own timer is armed and survives A's token-exact abort"))))
