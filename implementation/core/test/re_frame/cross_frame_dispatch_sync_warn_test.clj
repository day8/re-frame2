(ns re-frame.cross-frame-dispatch-sync-warn-test
  "A `dispatch-sync!` into frame B while a different frame A is mid-drain is
  NOT rejected — frames are independent (Spec 002 §Rules rule 1) — but emits
  `:rf.warning/cross-frame-dispatch-sync-during-drain` (Spec 002 §Cross-frame
  `dispatch-sync` during a sibling drain). The same-frame case stays the
  `:rf.error/dispatch-sync-in-handler` rejection pinned by `drain_test.clj`.

  ## Posture split

  The dispatch proceeding is production behaviour and is asserted unguarded.
  The warning is a dev-only trace, so every assertion about it — negatives
  included, which over the gate's empty stream would pass for free — sits in a
  `(when rf.interop/debug-enabled? …)` arm."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as rf.frame]
            [re-frame.interop :as rf.interop]
            [re-frame.registrar :as rf.registrar]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.trace.tooling :as rf.trace.tooling]))

(defn reset-runtime [test-fn]
  (rf.registrar/clear-all!)
  (reset! rf.frame/frames {})
  (rf.trace.tooling/clear-listeners!)
  (rf/init! rf.substrate.plain-atom/adapter)
  (test-fn))

(use-fixtures :each reset-runtime)

(defn- record-traces! [listener-id]
  (let [a (atom [])]
    (rf/register-listener! :trace listener-id (fn [ev] (swap! a conj ev)))
    a))

(defn- cross-frame-warnings [recorded]
  (filterv (fn [ev]
             (and (= :warning (:op-type ev))
                  (= :rf.warning/cross-frame-dispatch-sync-during-drain
                     (:operation ev))))
           @recorded))

(defn- frames-with-b-leaf!
  "Frames A and B, and a `:b/leaf` handler on B that marks B's app-db."
  []
  (rf/make-frame {:id :cfx.test/a})
  (rf/make-frame {:id :cfx.test/b})
  (rf/reg-event :b/leaf {:frame :cfx.test/b}
    (fn [{:keys [db]} _] {:db (assoc db :b-ran? true)})))

(deftest fires-on-cross-frame-dispatch-sync-during-drain
  (testing "frame A mid-drain calling dispatch-sync on frame B warns once, and B's handler runs"
    (frames-with-b-leaf!)
    (rf/reg-event :a/cross
      {:frame :cfx.test/a}
      (fn [_ _]
        (rf/dispatch-sync [:b/leaf] {:frame :cfx.test/b})
        {}))
    (let [recorded (record-traces! ::cfx)]
      (rf/dispatch-sync [:a/cross] {:frame :cfx.test/a})
      (is (true? (:b-ran? (rf/app-db-value :cfx.test/b)))
          "the dispatch proceeded — cross-frame reentry is not refused")
      (when rf.interop/debug-enabled?
        (is (= [{:caller-frame :cfx.test/a
                 :target-frame :cfx.test/b
                 :other-frame  :cfx.test/a
                 :event        [:b/leaf]
                 :reason?      true
                 :recovery     :no-recovery}]
               (mapv (fn [{:keys [tags recovery]}]
                       (-> (select-keys tags [:caller-frame :target-frame :other-frame :event])
                           (assoc :reason? (string? (:reason tags)) :recovery recovery)))
                     (cross-frame-warnings recorded))))))))

(deftest fires-on-cross-frame-dispatch-sync-during-async-drain
  (testing "frame A mid an ASYNC drain calling dispatch-sync on frame B still warns"
    ;; Running the captured `next-tick` thunk makes A's drain a genuine async
    ;; one: `:in-drain?` set, `:in-sync-drain?` never, so only the `:in-drain?`
    ;; arm of `find-other-draining-frame-id` sees A mid-drain. A dispatch-sync
    ;; outer drain sets both flags and cannot tell the arms apart.
    (frames-with-b-leaf!)
    (let [a-flags (atom nil)
          ticks   (atom [])]
      (rf/reg-event :a/async-cross
        {:frame :cfx.test/a}
        (fn [_ _]
          (reset! a-flags (select-keys @(:router (rf.frame/frame :cfx.test/a))
                                       [:in-drain? :in-sync-drain?]))
          (rf/dispatch-sync [:b/leaf] {:frame :cfx.test/b})
          {}))
      (let [recorded (record-traces! ::async-drain)]
        (with-redefs [rf.interop/next-tick (fn [f] (swap! ticks conj f) nil)]
          (rf/dispatch [:a/async-cross] {:frame :cfx.test/a})
          (doseq [f @ticks] (f)))
        (is (= [true false true]
               [(some? (:in-drain? @a-flags))
                (boolean (:in-sync-drain? @a-flags))
                (true? (:b-ran? (rf/app-db-value :cfx.test/b)))])
            "A's drain was async, and B's handler ran")
        (when rf.interop/debug-enabled?
          (is (= [[:cfx.test/a :cfx.test/b]]
                 (mapv (comp (juxt :other-frame :target-frame) :tags)
                       (cross-frame-warnings recorded)))))))))

(deftest no-warning-when-no-other-frame-is-mid-drain
  (testing "a cross-frame dispatch-sync outside any drain does not warn"
    (frames-with-b-leaf!)
    (let [recorded (record-traces! ::no-drain-no-warn)]
      (rf/dispatch-sync [:b/leaf] {:frame :cfx.test/b})
      (is (true? (:b-ran? (rf/app-db-value :cfx.test/b))))
      (when rf.interop/debug-enabled?
        (is (empty? (cross-frame-warnings recorded)))))))
