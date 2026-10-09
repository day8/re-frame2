(ns re-frame.flows-t2-trace-test
  "`:rf.event/db-pending-post-flow` (t2), per Spec 009 §Canonical per-event
  trace sequence and Spec 013 §Drain integration: when flows changed the
  pending `:db`, t2 carries the flow-augmented value, after the last
  `:rf.flow/computed` and before `:rf.event/db-changed`; when no flow changed
  it, t2 is omitted, because it would repeat t1. Loading `re-frame.flows` wires
  the `:flows/run-flows-on-db` late-bind hook the post-flow path runs through."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.flows]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.test-support :as rf.test-support]))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture {:adapter rf.substrate.plain-atom/adapter}))

(defn- traces-of
  "Every trace event a `dispatch-sync` of `event` emits."
  [event]
  (let [acc (atom [])]
    (rf/register-listener! :trace ::t2 (fn [ev] (swap! acc conj ev)))
    (try (rf/dispatch-sync event)
         @acc
         (finally (rf/unregister-listener! :trace ::t2)))))

(defn- of-op [op evs] (filterv #(= op (:operation %)) evs))

(deftest t2-emits-when-flow-changes-db
  ;; t1 carries what the handler returned; t2 what the flows made of it.
  (rf/reg-flow :len {:inputs [[:items]] :output-path [:item-count]} (fn [items] (count items)))
  (rf/reg-event :t2/add-items (fn [_ _] {:db {:items [:a :b :c]}}))
  (let [evs (traces-of [:t2/add-items])
        ops (mapv :operation evs)
        idx #(.indexOf ^java.util.List ops %)]
    (is (= [[{:items [:a :b :c]}]
            [[:rf.event {:items [:a :b :c] :item-count 3} :rf/default]]]
           [(mapv (comp :rf.event/db :tags) (of-op :rf.event/db-pending evs))
            (mapv (juxt :op-type (comp :rf.event/db :tags) (comp :frame :tags))
                  (of-op :rf.event/db-pending-post-flow evs))]))
    (is (apply < (map idx [:rf.event/db-pending :rf.flow/computed
                           :rf.event/db-pending-post-flow :rf.event/db-changed])))))

(deftest t2-suppressed-when-flow-makes-no-change
  ;; The second same-input dispatch skips the flow, so the post-flow db is
  ;; identical to t1. The t1 count shows the capture saw the dispatch.
  (rf/reg-flow :doubled {:inputs [[:n]] :output-path [:doubled]} (fn [n] (* 2 n)))
  (rf/reg-event :t2/set-n (fn [{:keys [db]} _] {:db (assoc db :n 5)}))
  (rf/dispatch-sync [:t2/set-n])
  (let [evs (traces-of [:t2/set-n])]
    (is (= [1 []] [(count (of-op :rf.event/db-pending evs))
                   (of-op :rf.event/db-pending-post-flow evs)]))))
