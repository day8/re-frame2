(ns re-frame.machine-timer-incarnation-fence-cljs-test
  "Machine `:after` timer cancellation batches and the cancel→reschedule
  continuation stay bound to the frame incarnation that started them.

  A batch cancels each entry by the attempt token it SNAPSHOTTED, never by
  re-reading the slot's current occupant, so a same-id successor B re-armed at
  a batch key — by a `:rf.machine.timer/cancelled` listener, or by a JVM
  thread race between snapshot and claim — fails the atomic claim and
  survives. `on-sub-changed!` rechecks ownership after its callback-bearing
  `:on-resolution` cancel, so a listener that replaced A with B gets no
  A-derived reschedule or supersede. The successor is published on the
  cancellation callback's own stack, so every fixture is deterministic on both
  runtimes. Per Spec 005 §Delayed `:after` transitions."
  (:require #?(:clj  [clojure.test :refer [deftest is testing use-fixtures]]
               :cljs [cljs.test :refer-macros [deftest is testing use-fixtures]])
            [re-frame.core :as rf]
            [re-frame.frame :as rf.frame]
            [re-frame.interop :as rf.interop]
            [re-frame.machines :as rf.machines]
            [re-frame.machines.paths :as rf.machines.paths]
            [re-frame.machines.test-support :as rf.machines.test-support]
            [re-frame.machines.timer :as rf.machines.timer]
            [re-frame.subs :as rf.subs]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.trace.tooling :as rf.trace.tooling]))

;; Touch the artefact so the machines registration hooks (incl. the
;; `:machines/on-frame-destroyed!` timer cleanup) are wired even in isolation.
(def ^:private _artefact rf.machines/machine-transition)

(use-fixtures :each
  (rf.machines.test-support/make-reset-runtime-fixture {:adapter rf.substrate.plain-atom/adapter}))

(defn- fresh-handle
  "A process-unique opaque host handle, distinguishable by `identical?`."
  []
  #?(:clj (Object.) :cljs #js {}))

(def ^:private parent-id :rf2-ijlhj/actor)

(defn- literal-entry
  "A hand-built literal-delay timer-table entry (nil sub slots)."
  [token handle]
  {:handle          handle
   :reaction        nil
   :sub-watcher-key nil
   :resolved-ms     3600000
   :epoch           0
   :state           :waiting
   :region          nil
   :delay-source    :literal
   :token           token})

(defn- k-for [delay] {:parent parent-id :spawn [] :delay delay})

(defn- install-entry! [frame-id k entry]
  (swap! rf.machines.timer/after-timers assoc-in [frame-id k] entry))

(deftest capture-read-batch-claims-only-the-snapshotted-attempt
  (testing "B re-arms k2 under a fresh token after the batch snapshot, with the
            frame still live (no incarnation short-circuit): the batch claims
            only its snapshotted a2-token, so B survives"
    (let [frame-id :rf2-ijlhj/cr-frame
          k2       (k-for 2000)
          b-entry  (literal-entry ::successor-token nil)
          fired?   (atom false)]
      (rf/make-frame {:id frame-id})
      (install-entry! frame-id (k-for 1000) (literal-entry ::a1-token nil))
      (install-entry! frame-id k2 (literal-entry ::a2-token nil))
      (rf.trace.tooling/register-listener!
        ::cr
        (fn [ev]
          ;; Once only: re-installing B on a later cancellation would mask a
          ;; batch that wrongly claimed it.
          (when (and (= :rf.machine.timer/cancelled (:operation ev))
                     (compare-and-set! fired? false true))
            (install-entry! frame-id k2 b-entry))))
      (try
        (rf.machines.timer/after-cancel-fx {:frame frame-id}
                                           {:rf/parent-id parent-id :rf/invoke-id []})
        (finally (rf.trace.tooling/unregister-listener! ::cr)))
      (is (= b-entry (get-in @rf.machines.timer/after-timers [frame-id k2]))))))

(deftest on-resolution-reschedule-is-fenced-to-the-owning-incarnation
  (testing "a sub-value change fires on-sub-changed!; its :on-resolution cancel
            destroys A, publishes same-id B and re-arms B/k. No A-derived
            reschedule or supersede runs against B"
    (let [frame-id  :rf2-ijlhj/res-frame
          delay-key [:rf2-ijlhj/dyn]
          k         (k-for delay-key)
          reaction  (atom 5000)
          b-entry   (assoc (literal-entry ::b-token (fresh-handle))
                           :resolved-ms 7000 :delay-source :sub)
          reasons   (atom [])
          fired?    (atom false)]
      (rf/make-frame {:id frame-id})
      (rf.trace.tooling/register-listener!
        ::res
        (fn [ev]
          (when (= :rf.machine.timer/cancelled (:operation ev))
            (swap! reasons conj (:reason (:tags ev)))
            (when (compare-and-set! fired? false true)
              ;; Seed B's snapshot too, so an unfenced reschedule would find the
              ;; actor still present and supersede B.
              (rf.frame/destroy-frame! frame-id)
              (rf/make-frame {:id frame-id})
              (rf.frame/swap-runtime-db!
                frame-id
                (fn [rt] (assoc-in rt (rf.machines.paths/snapshot-path parent-id)
                                   {:state :waiting :data {}})))
              (install-entry! frame-id k b-entry)))))
      (try
        (with-redefs [rf.subs/subscribe          (fn ([_] reaction) ([_ _] reaction))
                      rf.subs/unsubscribe        (fn ([_] nil) ([_ _] nil))
                      rf.interop/schedule-after! (fn [_thunk _ms] (fresh-handle))]
          ;; Arm a real sub-vec `:after` timer: installs A's entry and attaches
          ;; the `on-sub-changed!` watcher to `reaction`.
          (rf.machines.timer/after-schedule-fx
            {:frame frame-id}
            {:rf/parent-id parent-id :rf/invoke-id [] :state :waiting
             :delay-key delay-key :epoch 0 :server? false})
          (reset! reaction 6000))
        (finally (rf.trace.tooling/unregister-listener! ::res)))
      (is (= [b-entry [:on-resolution]]
             [(get-in @rf.machines.timer/after-timers [frame-id k]) @reasons])
          "B's entry survives and the single :on-resolution cancel is the only cancellation"))))

(deftest live-owner-batch-cancels-every-entry
  (testing "control: with no successor the batch cancels every snapshotted
            entry — the fence is scoped to owner loss"
    (let [frame-id  :rf2-ijlhj/live-frame
          hA1       (fresh-handle)
          hA2       (fresh-handle)
          cancelled (atom [])
          reasons   (atom [])]
      (rf/make-frame {:id frame-id})
      (install-entry! frame-id (k-for 1000) (literal-entry ::a1-token hA1))
      (install-entry! frame-id (k-for 2000) (literal-entry ::a2-token hA2))
      (rf.trace.tooling/register-listener!
        ::live
        (fn [ev] (when (= :rf.machine.timer/cancelled (:operation ev))
                   (swap! reasons conj (:reason (:tags ev))))))
      (try
        (with-redefs [rf.interop/cancel-scheduled! (fn [h] (swap! cancelled conj h) nil)]
          (rf.machines.timer/after-cancel-fx {:frame frame-id}
                                             {:rf/parent-id parent-id :rf/invoke-id []}))
        (finally (rf.trace.tooling/unregister-listener! ::live)))
      (is (= [nil #{hA1 hA2} [:on-exit :on-exit]]
             [(not-empty (get @rf.machines.timer/after-timers frame-id))
              (set @cancelled)
              @reasons])))))
