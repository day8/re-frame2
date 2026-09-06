(ns re-frame.flows-settle-ordering-test
  "Spec 013 §Sequencing — the settle runs BEFORE the continuations the same
  handler queued (rf2-kh73v, audit of PR #8893).

  `re-frame.flows-settle-on-dispatch-test` pins the settle BOUNDARY: the
  derived slot is correct by the time the originating dispatch returns. That
  is necessary and not sufficient. The settle used to be appended to the BACK
  of the frame's router queue, so a `:dispatch` effect emitted by the SAME
  handler was already ahead of it in FIFO order. Those child handlers ran
  inside the originating run-to-completion pass while `app-db` still reflected
  the pre-registration / pre-clear state — so a continuation after a register
  could not read the new output, and a continuation after a clear read the
  stale one. Each could persist a wrong decision into `app-db` that the later
  settle, repairing only the derived slot, would not undo.

  The two deftests below are exactly that shape: one handler emitting a
  lifecycle effect AND a `:dispatch`, where the dispatched handler reads the
  derived slot and records what it saw. They are the CONTROL for the ordering
  fix — under the back-of-queue settle they read `nil` (register arm) and the
  stale value (clear arm), while every other flows test stays green, which is
  why this had to be found by hand rather than by the existing lane."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            ;; Loading `re-frame.flows` is what publishes the `:flows/reg-flow`
            ;; / `:flows/clear-flow` late-bind hooks. Without it the reserved fx
            ;; find no hook and NO-OP silently, which reads exactly like the
            ;; lagging runtime — every assertion below goes red for the wrong
            ;; reason. Required for effect, not for a var.
            [re-frame.flows]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.test-support :as rf.test-support]))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture {:adapter rf.substrate.plain-atom/adapter}))

(def ^:private sum-flow
  "1 + 2 = 3 at `[:derived]` — the audit probe's own shape."
  [:sum
   {:inputs      [[:wizard :foo] [:wizard :bar]]
    :output-path [:derived]}
   (fn [foo bar] (+ foo bar))])

(deftest settle-precedes-continuations-queued-by-the-registering-handler
  (testing "a :dispatch emitted alongside :rf.fx/reg-flow reads the flow's
            output, not the pre-registration app-db"
    (rf/reg-event :init (fn [_ _] {:db {:wizard {:foo 1 :bar 2}}}))
    ;; The continuation. It reads the derived slot and PERSISTS what it saw —
    ;; the durable wrong decision the later settle cannot undo.
    (rf/reg-event :read-after-register
      (fn [{:keys [db]} _]
        {:db (assoc db :seen-after-register (:derived db))}))
    (rf/reg-event :enter
      (fn [_ _]
        {:fx [[:rf.fx/reg-flow sum-flow]
              [:dispatch [:read-after-register]]]}))

    (rf/dispatch-sync [:init])
    (rf/dispatch-sync [:enter])

    (let [db (rf/app-db-value :rf/default)]
      (is (= 3 (:derived db))
          (str "precondition — the settle boundary itself still holds. Row " db))
      ;; THE CONTROL, register arm. Red under the back-of-queue settle:
      ;; `{:seen-after-register nil, :derived 3}`.
      (is (= 3 (:seen-after-register db))
          (str "the continuation ran AFTER the settle and read the new output. Row " db)))))

(deftest settle-precedes-continuations-queued-by-the-clearing-handler
  (testing "a :dispatch emitted alongside :rf.fx/clear-flow reads the vacated
            slot, not the flow's stale output"
    (rf/reg-event :init (fn [_ _] {:db {:wizard {:foo 1 :bar 2}}}))
    (rf/reg-event :enter (fn [_ _] {:fx [[:rf.fx/reg-flow sum-flow]]}))
    (rf/reg-event :read-after-clear
      (fn [{:keys [db]} _]
        {:db (assoc db :seen-after-clear (:derived db))}))
    (rf/reg-event :leave
      (fn [_ _]
        {:fx [[:rf.fx/clear-flow :sum]
              [:dispatch [:read-after-clear]]]}))

    (rf/dispatch-sync [:init])
    (rf/dispatch-sync [:enter])
    (is (= 3 (:derived (rf/app-db-value :rf/default)))
        "precondition — the flow is registered and its output materialised")

    (rf/dispatch-sync [:leave])

    (let [db (rf/app-db-value :rf/default)]
      (is (not (contains? db :derived))
          (str "precondition — the settle boundary itself still holds. Row " db))
      ;; THE CONTROL, clear arm. Red under the back-of-queue settle:
      ;; `{:seen-after-clear 3}` with `:derived` already removed.
      (is (nil? (:seen-after-clear db))
          (str "the continuation ran AFTER the settle and read the vacated slot. Row " db)))))

(deftest settle-precedes-a-whole-run-of-queued-continuations
  (testing "one settle, ahead of EVERY continuation the handler queued, and the
            continuations keep their own source order"
    (let [seen (atom [])]
      (rf/reg-event :init (fn [_ _] {:db {:wizard {:foo 1 :bar 2}}}))
      (rf/reg-event :read-a
        (fn [{:keys [db]} _] (swap! seen conj [:a (:derived db)]) nil))
      (rf/reg-event :read-b
        (fn [{:keys [db]} _] (swap! seen conj [:b (:derived db)]) nil))
      (rf/reg-event :enter
        (fn [_ _]
          {:fx [[:dispatch [:read-a]]
                [:rf.fx/reg-flow sum-flow]
                [:dispatch [:read-b]]]}))

      (rf/dispatch-sync [:init])
      (rf/dispatch-sync [:enter])

      ;; Both continuations see the settled value — including `:read-a`, queued
      ;; BEFORE the lifecycle effect ran. The settle is enqueued once, at the
      ;; end of the walk, ahead of the whole queued run.
      (is (= [[:a 3] [:b 3]] @seen)
          (str "settled value in both, source order preserved. Row " @seen)))))
