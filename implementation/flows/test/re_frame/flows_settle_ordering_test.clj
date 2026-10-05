(ns re-frame.flows-settle-ordering-test
  "Spec 013 §Sequencing — the settle runs BEFORE the continuations the same
  handler queued.

  `re-frame.flows-settle-on-dispatch-test` pins the settle BOUNDARY: the
  derived slot is correct by the time the originating dispatch returns. That
  is necessary and not sufficient. Were the settle appended to the BACK
  of the frame's router queue, a `:dispatch` effect emitted by the SAME
  handler would already be ahead of it in FIFO order. Those child handlers would
  run inside the originating run-to-completion pass while `app-db` still reflected
  the pre-registration / pre-clear state — so a continuation after a register
  could not read the new output, and a continuation after a clear would read
  the stale one. Each could persist a wrong decision into `app-db` that the later
  settle, repairing only the derived slot, would not undo.

  The deftest below is exactly that shape: one handler emitting a lifecycle
  effect between two `:dispatch`es, where each dispatched handler reads the
  derived slot and records what it saw. It is the CONTROL for the ordering —
  under a back-of-queue settle its continuations would read `nil` while every
  other flows test stays green. Both lifecycle effects request their settle
  through the same `:fx`-walk request and router head-insert, so the register
  arm stands for the clear arm."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            ;; Loading `re-frame.flows` is what publishes the `:flows/reg-flow`
            ;; / `:flows/clear-flow` late-bind hooks. Without it the reserved fx
            ;; find no hook and NO-OP silently, which reads exactly like a
            ;; lagging runtime — every assertion below goes red for the wrong
            ;; reason. Required for effect, not for a var.
            [re-frame.flows]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.test-support :as rf.test-support]))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture {:adapter rf.substrate.plain-atom/adapter}))

(def ^:private sum-flow
  "1 + 2 = 3 at `[:derived]`."
  [:sum
   {:inputs      [[:wizard :foo] [:wizard :bar]]
    :output-path [:derived]}
   (fn [foo bar] (+ foo bar))])

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
