(ns re-frame.trace.projection-test
  "Tests for `re-frame.trace.projection/group-cascades` +
  `domino-bucket`. Pure-data — no fixture, no frame, no router; JVM and
  CLJS run the same suite.

  Per rf2-wvzgd: the projection was lifted from Story's trace panel.
  The original Story version used synthetic op-types (`:event/do-fx`,
  `:fx`, `:view/render` as op-type rather than operation) and the
  invented `:event/run` operation; this rewrite tracks the
  framework's actual trace surface per Spec 009 §`:op-type`
  vocabulary."
  (:require [clojure.test :refer [deftest is testing]]
            [re-frame.trace.projection :as p]))

;; ---- domino-bucket --------------------------------------------------------

(deftest domino-bucket-classifies-event-ops
  (testing ":event/dispatched buckets as :event (cascade root)"
    (is (= :event
           (p/domino-bucket {:op-type :event :operation :event/dispatched}))))
  (testing ":event :event buckets as :handler (the interceptor chain ran)"
    (is (= :handler
           (p/domino-bucket {:op-type :event :operation :event}))))
  (testing ":event :event/do-fx buckets as :fx (effects map computed)"
    (is (= :fx
           (p/domino-bucket {:op-type :event :operation :event/do-fx}))))
  (testing ":event :event/db-changed lands in :other (not a six-domino slot)"
    (is (= :other
           (p/domino-bucket {:op-type :event :operation :event/db-changed})))))

(deftest domino-bucket-classifies-fx-as-effects
  (testing "every :op-type :fx event — :rf.fx/handled, override-applied, etc. — buckets as :effect"
    (is (= :effect (p/domino-bucket {:op-type :fx :operation :rf.fx/handled})))
    (is (= :effect (p/domino-bucket {:op-type :fx :operation :rf.fx/override-applied})))
    (is (= :effect (p/domino-bucket {:op-type :fx :operation :rf.fx/skipped-on-platform})))))

(deftest domino-bucket-classifies-sub-ops
  (testing "both :sub/run and :sub/create bucket as :sub"
    (is (= :sub (p/domino-bucket {:op-type :sub/run    :operation :sub/run})))
    (is (= :sub (p/domino-bucket {:op-type :sub/create :operation :sub/create})))))

(deftest domino-bucket-classifies-view-render
  (testing ":op-type :view + :operation :view/render buckets as :render"
    (is (= :render
           (p/domino-bucket {:op-type :view :operation :view/render})))))

(deftest domino-bucket-non-domino-events-fall-through-to-other
  (testing "events outside the six-domino vocabulary land in :other"
    (is (= :other (p/domino-bucket {:op-type :error :operation :rf.error/no-such-handler})))
    (is (= :other (p/domino-bucket {:op-type :warning :operation :rf.warning/interceptors-in-metadata-map})))
    (is (= :other (p/domino-bucket {:op-type :machine :operation :rf.machine/transition})))
    (is (= :other (p/domino-bucket {:op-type :frame :operation :frame/created})))
    (is (= :other (p/domino-bucket {:op-type :flow :operation :rf.flow/computed})))
    (is (= :other (p/domino-bucket {:op-type :registry :operation :rf.registry/handler-registered})))))

(deftest domino-bucket-total-on-arbitrary-shapes
  (testing "the classification is total; an unknown op-type/operation pair returns :other"
    (is (= :other (p/domino-bucket {:op-type :totally-made-up :operation :nope})))
    (is (= :other (p/domino-bucket {})))))

;; ---- group-cascades -------------------------------------------------------

(defn- cascade-evs
  "Produce a representative one-cascade event stream."
  ([dispatch-id event-vec]
   (cascade-evs dispatch-id event-vec :rf/default))
  ([dispatch-id event-vec frame-id]
   [{:id 1 :op-type :event    :operation :event/dispatched
     :tags {:dispatch-id dispatch-id :event event-vec :frame frame-id}}
    {:id 2 :op-type :event    :operation :event
     :tags {:dispatch-id dispatch-id :phase :run-start :frame frame-id}}
    {:id 3 :op-type :event    :operation :event
     :tags {:dispatch-id dispatch-id :phase :run-end :frame frame-id}}
    {:id 4 :op-type :event    :operation :event/do-fx
     :tags {:dispatch-id dispatch-id :frame frame-id}}
    {:id 5 :op-type :fx       :operation :rf.fx/handled
     :tags {:dispatch-id dispatch-id :fx-id :db :frame frame-id}}
    {:id 6 :op-type :fx       :operation :rf.fx/handled
     :tags {:dispatch-id dispatch-id :fx-id :dispatch :frame frame-id}}
    {:id 7 :op-type :sub/run  :operation :sub/run
     :tags {:dispatch-id dispatch-id :sub-id :sub/foo :frame frame-id}}
    {:id 8 :op-type :view     :operation :view/render
     :tags {:dispatch-id dispatch-id :render-key [:app/root nil] :frame frame-id}}]))

(deftest group-cascades-one-cascade-six-buckets
  (testing "a representative cascade reduces to one record with the six
            domino slots populated"
    (let [evs (cascade-evs 100 [:user/login {:id 42}])
          [c & more] (p/group-cascades evs)]
      (is (empty? more) "single cascade yields one record")
      (is (= 100 (:dispatch-id c)))
      (is (= :rf/default (:frame c)))
      (is (= [:user/login {:id 42}] (:event c)) ":event slot is the dispatched event vector")
      (is (some? (:handler c)) ":handler slot is populated by the :run-* emit")
      (is (some? (:fx c))      ":fx slot is the :event/do-fx emit")
      (is (= 2 (count (:effects c))) "both :rf.fx/handled events land in :effects")
      (is (= 1 (count (:subs c))))
      (is (= 1 (count (:renders c))))
      (is (= [] (:other c))    ":other is empty for a clean cascade"))))

(deftest group-cascades-multiple-cascades-sorted-by-first-id
  (testing "with two cascades interleaved, group-cascades emits them in
            emission order (lowest :id first)"
    (let [a (cascade-evs 200 [:a])
          b (mapv #(update % :id + 100) (cascade-evs 300 [:b]))
          ;; interleave: ids in :a are 1-8; :b are 101-108
          evs (interleave a b)
          cs  (p/group-cascades evs)]
      (is (= 2 (count cs)))
      (is (= 200 (:dispatch-id (first cs))))
      (is (= 300 (:dispatch-id (second cs)))))))

(deftest group-cascades-keeps-same-dispatch-id-separate-by-frame
  (testing "dispatch-id is frame-scoped, so same id in two frames yields two records"
    (let [a  (cascade-evs 10 [:counter/a-inc] :counter/a)
          b  (mapv #(update % :id + 100)
                   (cascade-evs 10 [:counter/b-inc] :counter/b))
          cs (p/group-cascades (concat a b))]
      (is (= 2 (count cs)))
      (is (= [[:counter/a 10] [:counter/b 10]]
             (mapv (juxt :frame :dispatch-id) cs)))
      (is (= [[:counter/a-inc] [:counter/b-inc]]
             (mapv :event cs))))))

(deftest group-cascades-events-without-dispatch-id-land-in-ungrouped
  (testing "events emitted outside any drain (registry-time, frame
            lifecycle) carry no :dispatch-id and group under :ungrouped"
    (let [evs [{:id 1 :op-type :frame :operation :frame/created :tags {:frame :app}}
               {:id 2 :op-type :registry :operation :rf.registry/handler-registered
                :tags {:kind :event :id :user/login}}]
          cs (p/group-cascades evs)]
      (is (= 1 (count cs)))
      (is (= :ungrouped (:dispatch-id (first cs))))
      (is (= 2 (count (:other (first cs))))
          "frame and registry events both flow through :other"))))

(deftest group-cascades-error-and-warning-events-ride-along-in-other
  (testing "an error fired inside a cascade lands in that cascade's
            :other slot, not as its own cascade (per rf2-g6ih4
            :dispatch-id rides every event)"
    (let [evs (conj (cascade-evs 400 [:foo])
                    {:id 100 :op-type :error :operation :rf.error/handler-exception
                     :tags {:dispatch-id 400 :event-id :foo}})
          [c] (p/group-cascades evs)]
      (is (= 400 (:dispatch-id c)))
      (is (= 1 (count (:other c)))
          "the error event lands in :other, not a sibling :ungrouped cascade"))))

(deftest group-cascades-handler-slot-takes-last-event-emit
  (testing "when the chain emits :run-start + :run-end the :handler slot
            ends up as the :run-end event (reduce overwrites)"
    (let [evs (cascade-evs 500 [:foo])
          [c] (p/group-cascades evs)]
      (is (= :run-end (get-in c [:handler :tags :phase]))))))

(deftest group-cascades-empty-input-yields-empty-output
  (is (= [] (p/group-cascades [])))
  (is (= [] (p/group-cascades nil))))

(deftest group-cascades-shape-is-stable
  (testing "every cascade record carries the documented keys with
            collection-shaped defaults"
    (let [[c] (p/group-cascades [{:id 1 :op-type :event :operation :event/dispatched
                                  :tags {:dispatch-id 1 :event [:e]}}])]
      (is (= #{:dispatch-id :frame :event :handler :fx :effects :subs :renders :other}
             (set (keys c))))
      (is (vector? (:effects c)))
      (is (vector? (:subs c)))
      (is (vector? (:renders c)))
      (is (vector? (:other c))))))
