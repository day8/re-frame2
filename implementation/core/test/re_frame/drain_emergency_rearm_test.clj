(ns re-frame.drain-emergency-rearm-test
  "A hard per-event error escaping a drain (`:rf.error/missing-required-cofx`,
  `:rf.error/unregistered-cofx`, …) still throws — to the `dispatch-sync`
  caller, or to the host for an async drain — and must not strand the events
  queued behind it. Per Spec 002 §Drain scheduling those run in a freshly
  scheduled drain with no further dispatch; the failing event is not retried.
  And the frame must stay usable: `ensure-drain-scheduled!` arms a drain only
  when it flips `:scheduled?`, so a flag left set strands every later event.

  `rf.interop/next-tick` is replaced by a recorder and the recorded drains are
  run by hand, so no executor timing is involved."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as rf.frame]
            [re-frame.interop :as rf.interop]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.test-support :as rf.test-support]))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    {:adapter rf.substrate.plain-atom/adapter}))

(def ^:private fid :rearm/f)

(defn- router-state []
  (let [f (rf.frame/frame fid)]
    {:queue      (mapv :event (:queue @(:router f)))
     :scheduled? (boolean (:scheduled? @(:router f)))
     :drain-lock @(:drain-lock f)}))

(defn- throw-id
  "Run `thunk`; return the `:rf.error/id` of the ExceptionInfo it throws, or
  `::no-throw`."
  [thunk]
  (try (thunk) ::no-throw
       (catch clojure.lang.ExceptionInfo e (:rf.error/id (ex-data e)))))

(defn- run-ticks!
  "Run every recorded `next-tick` callback, including any a callback records,
  until none remain."
  [ticks]
  (loop []
    (when-let [f (first @ticks)]
      (swap! ticks subvec 1)
      (f)
      (recur))))

(defn- register-cascade!
  "`:rearm/parent` queues `bad-child` then `:rearm/good`. `bad-child` requires
  `cofx-id`, which it cannot get, so assembling its context throws."
  [log bad-child cofx-id]
  (rf/reg-event bad-child
    {:rf.cofx/requires [cofx-id]}
    (fn [_ _] (swap! log conj :bad) {}))
  (rf/reg-event :rearm/good
    (fn [_ _] (swap! log conj :good) {}))
  (rf/reg-event :rearm/parent
    (fn [_ _]
      (swap! log conj :parent)
      {:fx [[:dispatch [bad-child]] [:dispatch [:rearm/good]]]})))

(deftest sync-hard-cofx-error-throws-and-still-drains-the-queued-tail
  (let [log   (atom [])
        ticks (atom [])]
    (rf/make-frame {:id fid})
    ;; A child `:dispatch` never inherits the parent's `:rf.cofx`, so a
    ;; `:provided?` cofx leaves the child's context incomplete.
    (rf/reg-cofx :rearm/token {:recordable? true :provided? true})
    (register-cascade! log :rearm/bad :rearm/token)
    (with-redefs [rf.interop/next-tick (fn [f] (swap! ticks conj f) nil)]
      (is (= :rf.error/missing-required-cofx
             (throw-id #(rf/dispatch-sync [:rearm/parent] {:frame fid}))))
      (is (= [:parent] @log) "the tail waits for a fresh drain")
      (run-ticks! ticks)
      (is (= [:parent :good] @log)
          "the queued tail ran with no further dispatch"))))

(deftest async-hard-cofx-error-throws-and-still-drains-the-queued-tail
  (let [log   (atom [])
        ticks (atom [])]
    (rf/make-frame {:id fid})
    ;; Registration accepts a never-registered cofx id; processing throws.
    (register-cascade! log :rearm/typo :rearm/no-such-cofx)
    (with-redefs [rf.interop/next-tick (fn [f] (swap! ticks conj f) nil)]
      (rf/dispatch [:rearm/parent] {:frame fid})
      (let [first-drain (first @ticks)]
        (swap! ticks subvec 1)
        (is (= :rf.error/unregistered-cofx (throw-id first-drain))
            "the async drain still throws the hard error to the host"))
      (is (= [:parent] @log))
      (run-ticks! ticks)
      (is (= [:parent :good] @log)
          "the queued tail ran with no further dispatch"))))

(deftest a-lone-hard-error-leaves-the-frame-unscheduled-and-unlocked
  (rf/make-frame {:id fid})
  (rf/reg-cofx :rearm/token {:recordable? true :provided? true})
  (rf/reg-event :rearm/bad {:rf.cofx/requires [:rearm/token]} (fn [_ _] {}))
  (rf/reg-event :rearm/lone-parent (fn [_ _] {:fx [[:dispatch [:rearm/bad]]]}))
  (with-redefs [rf.interop/next-tick (fn [_] nil)]
    (is (= :rf.error/missing-required-cofx
           (throw-id #(rf/dispatch-sync [:rearm/lone-parent] {:frame fid}))))
    (is (= {:queue [] :scheduled? false :drain-lock false} (router-state))
        "nothing queued behind the failure: no drain armed, nothing left held")))
