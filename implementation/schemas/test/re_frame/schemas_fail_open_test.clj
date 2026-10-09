(ns re-frame.schemas-fail-open-test
  "A validator or explainer throw fails CLOSED. Malli checks schema forms
  lazily, so a childless `[:vector]` registers and then throws on first use:
  each surface emits `:rf.error/malformed-schema` and runs its own recovery,
  where a propagated throw would reach the runtime's defensive catch and read
  as a pass. A throwing explainer degrades to a nil explanation and keeps the
  false verdict."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.schemas :as rf.schemas]
            [re-frame.schemas.test-fixture :as rf.schemas.test-fixture]
            [re-frame.test-support :refer [with-trace-recorder!]]))

(use-fixtures :each rf.schemas.test-fixture/reset-runtime)

(defn- traces-of
  "Run `body-fn` and return the recorded traces of operation `op`."
  [op body-fn]
  (with-trace-recorder! [traces]
    (body-fn)
    (filterv #(= op (:operation %)) @traces)))

(defn- malformed
  "The surface, schema and recovery of each malformed-schema trace `body-fn`
  emits, plus any value-bearing slot, which must never appear."
  [body-fn]
  (mapv #(assoc (select-keys (:tags %) [:where :schema :received :value])
                :recovery (:recovery %))
        (traces-of :rf.error/malformed-schema body-fn)))

(defn- failures
  "The `:where` and `:explain` of each validation-failure trace `body-fn` emits."
  [body-fn]
  (mapv (comp (juxt :where :explain) :tags)
        (traces-of :rf.error/schema-validation-failure body-fn)))

(deftest event-malformed-schema-fails-closed
  (let [calls (atom 0)]
    (rf/reg-event :ev/malformed {:schema [:vector]}
      (fn [{:keys [db]} _] (swap! calls inc) {:db db}))
    (is (= [{:where :event :schema [:vector] :recovery :no-recovery}]
           (malformed #(rf/dispatch-sync [:ev/malformed :anything]))))
    (is (= 0 @calls))))

(deftest recordable-cofx-malformed-schema-fails-closed
  ;; The recordable path folds a validator throw into its own hard error.
  (rf/reg-cofx :cf/malformed {:recordable? true :provided? true :schema [:vector]})
  (let [calls (atom 0)]
    (rf/reg-event :use/malformed-cofx {:rf.cofx/requires [:cf/malformed]}
      (fn [_ _] (swap! calls inc) {}))
    (is (= [:no-recovery]
           (mapv :recovery
                 (traces-of :rf.error/cofx-value-invalid
                            #(try (rf/dispatch-sync [:use/malformed-cofx]
                                                    {:rf.cofx {:cf/malformed :whatever}})
                                  (catch clojure.lang.ExceptionInfo _))))))
    (is (= 0 @calls))))

(deftest fx-malformed-schema-fails-closed-skipping-only-offender
  (let [calls (atom [])]
    (rf/reg-fx :fx/malformed {:schema [:vector]} (fn [_ _] (swap! calls conj :malformed)))
    (rf/reg-fx :fx/sibling (fn [_ _] (swap! calls conj :sibling)))
    (rf/reg-event :emit/both
      (fn [_ _] {:fx [[:fx/malformed {:any :thing}] [:fx/sibling :ok]]}))
    (is (= [{:where :fx-args :schema [:vector] :recovery :skipped}]
           (malformed #(rf/dispatch-sync [:emit/both]))))
    (is (= [:sibling] @calls))))

(deftest sub-malformed-schema-fails-closed-replaced-with-default
  (rf/reg-event :sub/init (fn [_ _] {:db {:items [1 2 3]}}))
  (rf/reg-sub :sub/malformed {:schema [:vector]} (fn [db _] (:items db)))
  (rf/dispatch-sync [:sub/init])
  (let [value (atom ::unread)]
    (is (= [{:where :sub-return :schema [:vector] :recovery :replaced-with-default}]
           (distinct (malformed #(reset! value (rf/subscribe-once [:sub/malformed]))))))
    (is (nil? @value))))

(deftest boundary-seam-malformed-schema-returns-false
  (is (= [false true false]
         [(rf.schemas/validate-with-registered-fn [:vector] [:anything])
          (rf.schemas/validate-with-registered-fn [:cat [:= :ok] :int] [:ok 1])
          (rf.schemas/validate-with-registered-fn [:cat [:= :ok] :int] [:ok "no"])])))

(def ^:private throwing-explainer
  (fn [_schema _value] (throw (ex-info "explainer boom" {}))))

(deftest app-db-explainer-throw-preserves-failure
  (rf.schemas/set-schema-fns! {:validate (fn [_ _] false) :explain throwing-explainer})
  (rf/reg-app-schema [:n] [:int])
  (let [verdict (atom nil)]
    (is (= [[:app-db nil]]
           (failures #(reset! verdict (rf.schemas/validate-app-schema! {:n "bad"} :n/bad)))))
    (is (false? @verdict))))

(deftest sub-return-explainer-throw-preserves-failure
  (rf.schemas/set-schema-fns! {:validate (fn [_ _] false) :explain throwing-explainer})
  (let [verdict (atom nil)]
    (is (= [[:sub-return nil]]
           (failures #(reset! verdict (rf.schemas/validate-sub! :s/strict [:s/strict] [1 2 3]
                                                                 {:schema [:vector :string]})))))
    (is (false? @verdict))))

(deftest boundary-explain-seam-isolates-explainer-throw
  (rf.schemas/set-schema-fns! {:explain throwing-explainer})
  (is (nil? (rf.schemas/explain-with-registered-fn [:int] "bad"))))
