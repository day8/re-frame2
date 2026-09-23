(ns re-frame.drain-emergency-rearm-test
  "rf2-3x7nj.1.1 — a hard per-event error that escapes the drain must not
  strand the events queued behind it.

  A hard coeffect or chain-assembly error (`:rf.error/missing-required-cofx`,
  `:rf.error/unregistered-cofx`, an interceptor-override error …) is thrown out
  of `process-event!` and out of the drain: to the `dispatch-sync` caller, or
  uncaught on the host for an async drain. That throw is the pinned, loud
  contract and it stays. What must not happen is the drain's panic path
  (`drain-emergency-release!`) clearing `:scheduled?` with work still queued,
  because `ensure-drain-scheduled!` arms a drain only when it flips that flag —
  so the tail (the failing event's own `:fx` siblings included) would wait for
  some unrelated later dispatch, which in an idle app is never.

  Per Spec 002 §Drain scheduling: the throw ends that drain; events still
  queued on the live frame run in a freshly scheduled drain (next task, fresh
  depth budget); the failing event is not retried.

  Deterministic: `rf.interop/next-tick` is replaced by a recorder and the
  recorded drain callbacks are run by hand, so no executor timing is involved."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
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
  until none remain. Returns the number run."
  [ticks]
  (loop [n 0]
    (if-let [f (first @ticks)]
      (do (swap! ticks subvec 1)
          (f)
          (recur (inc n)))
      n)))

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
    ;; A `:provided?` boundary fact: a child `:dispatch` never inherits the
    ;; parent's `:rf.cofx`, so the child's context is incomplete.
    (rf/reg-cofx :rearm/token {:recordable? true :provided? true})
    (register-cascade! log :rearm/bad :rearm/token)
    (with-redefs [rf.interop/next-tick (fn [f] (swap! ticks conj f) nil)]
      (is (= :rf.error/missing-required-cofx
             (throw-id #(rf/dispatch-sync [:rearm/parent] {:frame fid})))
          "the hard error still THROWS to the dispatch-sync caller")
      (is (= [:parent] @log)
          "the parent committed; the failing child never ran; the tail has not run yet")
      (is (= {:queue [[:rearm/good]] :scheduled? true :drain-lock false}
             (router-state))
          "the tail is queued with a drain ARMED (not stranded with :scheduled? false)")
      (is (= 1 (count @ticks)) "exactly one fresh drain was scheduled")
      (run-ticks! ticks)
      (is (= [:parent :good] @log)
          "the queued tail ran with no further dispatch; the failing event was not retried")
      (is (= {:queue [] :scheduled? false :drain-lock false} (router-state))
          "the re-armed drain settled cleanly"))))

(deftest async-hard-cofx-error-throws-and-still-drains-the-queued-tail
  (let [log   (atom [])
        ticks (atom [])]
    (rf/make-frame {:id fid})
    ;; A typo'd / never-registered cofx id: registration accepts it, the first
    ;; processing throws `:rf.error/unregistered-cofx`.
    (register-cascade! log :rearm/typo :rearm/no-such-cofx)
    (with-redefs [rf.interop/next-tick (fn [f] (swap! ticks conj f) nil)]
      (rf/dispatch [:rearm/parent] {:frame fid})
      (is (= 1 (count @ticks)) "the dispatch armed one drain")
      (let [first-drain (first @ticks)]
        (swap! ticks subvec 1)
        (is (= :rf.error/unregistered-cofx (throw-id first-drain))
            "the async drain still throws the hard error to the host"))
      (is (= [:parent] @log))
      (is (= {:queue [[:rearm/good]] :scheduled? true :drain-lock false}
             (router-state))
          "the tail is queued with a drain armed")
      (is (= 1 (count @ticks)) "the panic path scheduled exactly one fresh drain")
      (run-ticks! ticks)
      (is (= [:parent :good] @log)
          "the queued tail ran with no further dispatch")
      (is (= {:queue [] :scheduled? false :drain-lock false} (router-state))))))

(deftest controls-a-clean-drain-and-a-lone-failure-schedule-nothing-extra
  (testing "a clean drain ends settled, with no re-armed drain"
    (let [log   (atom [])
          ticks (atom [])]
      (rf/make-frame {:id fid})
      (rf/reg-event :rearm/good (fn [_ _] (swap! log conj :good) {}))
      (rf/reg-event :rearm/clean-parent
        (fn [_ _] (swap! log conj :parent) {:fx [[:dispatch [:rearm/good]]]}))
      (with-redefs [rf.interop/next-tick (fn [f] (swap! ticks conj f) nil)]
        (rf/dispatch-sync [:rearm/clean-parent] {:frame fid})
        (is (= [:parent :good] @log))
        (is (empty? @ticks) "nothing scheduled after a clean sync drain")
        (is (= {:queue [] :scheduled? false :drain-lock false} (router-state))))))
  (testing "a failing event with nothing queued behind it throws and schedules nothing"
    (let [log   (atom [])
          ticks (atom [])]
      (rf/make-frame {:id :rearm/lone})
      (rf/reg-cofx :rearm/token {:recordable? true :provided? true})
      (rf/reg-event :rearm/bad {:rf.cofx/requires [:rearm/token]}
        (fn [_ _] (swap! log conj :bad) {}))
      (rf/reg-event :rearm/lone-parent
        (fn [_ _] (swap! log conj :parent) {:fx [[:dispatch [:rearm/bad]]]}))
      (with-redefs [rf.interop/next-tick (fn [f] (swap! ticks conj f) nil)]
        (is (= :rf.error/missing-required-cofx
               (throw-id #(rf/dispatch-sync [:rearm/lone-parent] {:frame :rearm/lone}))))
        (is (= [:parent] @log))
        (is (empty? @ticks) "an empty queue re-arms nothing")
        (let [f (rf.frame/frame :rearm/lone)]
          (is (false? (boolean (:scheduled? @(:router f)))))
          (is (false? @(:drain-lock f))))))))
