(ns re-frame.security.validation-redaction-invariant-security-cljs-test
  "Schema-aware redaction across validation traces and coeffect failures."
  (:require #?(:clj  [clojure.test :refer [deftest is use-fixtures]]
               :cljs [cljs.test :refer-macros [deftest is use-fixtures]])
            [re-frame.core :as rf]
            [re-frame.frame :as rf.frame]
            ;; Publish the validator and explainer hooks.
            [re-frame.schemas.malli]
            [re-frame.schemas :as rf.schemas]
            [re-frame.machines.data-validation :as rf.machines.data-validation]
            #?(:cljs [re-frame.late-bind :as rf.late-bind])
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            #?(:clj  [re-frame.test-support :as rf.test-support :refer [with-trace-recorder!]]
               :cljs [re-frame.test-support :as rf.test-support :refer-macros [with-trace-recorder!]])
            [re-frame.security.gen :as rf.security.gen]))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture)
  (fn [test-fn]
    (binding [rf.frame/*current-frame* :rf/default]
      (test-fn))))

(def ^:private sentinel "S3CR3T-rf2-o69h5-VALIDATION-DO-NOT-LEAK")

(defn- contains-sentinel?
  [x]
  (rf.security.gen/contains-string? x sentinel))

(defn- capture-op
  "Run f and return the first trace with the requested operation."
  [op f]
  (with-trace-recorder! [traces]
    (f)
    (first (filter #(= op (:operation %)) @traces))))

(defn- capture-failure
  [f]
  (capture-op :rf.error/schema-validation-failure f))

(defn- redaction
  "Read sensitivity, sentinel presence, and the requested tag slots."
  [trace & slots]
  (into [(:sensitive? trace) (contains-sentinel? trace)]
        (map #(get-in trace [:tags %]) slots)))

(def ^:private sensitive-map-schema
  [:map [:secret {:sensitive? true} :string]])

(def ^:private failing-sensitive-value
  {:secret [sentinel]})

;; A conforming secret still appears in whole-payload error slots.
(def ^:private sibling-sensitive-map-schema
  [:map
   [:jwt   {:sensitive? true} :string]
   [:count :int]])

(def ^:private failing-sibling-value
  {:jwt sentinel :count "not-an-int"})

(deftest event-validation-redacts-conforming-sensitive-sibling
  (let [trace (capture-failure
                #(rf.schemas/validate-event!
                   :auth/profile [:auth/profile {:password sentinel :age "old"}]
                   {:schema [:cat [:= :auth/profile]
                             [:map [:password {:sensitive? true} :string] [:age :int]]]}))]
    (is (= [true false :rf/redacted :rf/redacted :rf/redacted]
           (redaction trace :received :value :explain)))))

(deftest app-db-validation-redacts-conforming-sensitive-sibling
  ;; A live frame: the trace is also projected through its classification policy,
  ;; and a frame the walker cannot resolve redacts the whole value.
  (rf/init! rf.substrate.plain-atom/adapter)
  (rf.frame/ensure-default-frame!)
  (rf/reg-app-schema [:root] sibling-sensitive-map-schema)
  (let [trace (capture-failure
                #(rf.schemas/validate-app-schema!
                   {:root failing-sibling-value} :root/bad))]
    (is (= [true false :rf/redacted :rf/redacted]
           (redaction trace :value :explain)))))

(deftest machine-data-validation-redacts-conforming-sensitive-sibling
  (let [trace (capture-failure
                #(rf.machines.data-validation/validate-snapshot-data!
                   :my/machine {:data failing-sibling-value}
                   sibling-sensitive-map-schema :macrostep))]
    (is (= [true false :rf/redacted :rf/redacted]
           (redaction trace :value :explain)))))

(defn- with-runtime*
  "Install the runtime required by dispatch and subscribe; the fixture tears it down."
  [thunk]
  (rf/init! rf.substrate.plain-atom/adapter)
  (rf.frame/ensure-default-frame!)
  (thunk))

(defn- thrown-by
  "Return the ExceptionInfo thrown by f, or nil."
  [f]
  (try
    (f)
    nil
    (catch #?(:clj clojure.lang.ExceptionInfo :cljs cljs.core/ExceptionInfo) e
      e)))

(deftest recordable-cofx-schema-failure-production-path-redacts-sensitive
  (with-runtime*
    (fn []
      (rf/reg-cofx :cofx/recordable-secret
        {:recordable? true
         :schema      [:map [:token {:sensitive? true} :string]]}
        (fn [] {:token "ignored-supplied-wins"}))
      (rf/reg-event :cofx/uses-recordable-secret
        {:rf.cofx/requires [:cofx/recordable-secret]}
        (fn [_ _] {}))
      (let [thrown (atom nil)
            trace  (capture-op
                     :rf.error/cofx-value-invalid
                     #(reset! thrown (thrown-by
                                       (fn []
                                         (rf/dispatch-sync
                                           [:cofx/uses-recordable-secret]
                                           {:rf.cofx {:cofx/recordable-secret {:token [sentinel]}}})))))
            d      (ex-data @thrown)]
        (is (= [true false :rf/redacted :rf/redacted]
               (redaction trace :value :explain)))
        (is (= [:rf.error/cofx-value-invalid true :rf/redacted :rf/redacted false]
               [(:rf.error/id d) (:sensitive? d) (:value d) (:explain d) (contains-sentinel? d)]))))))

;; Validate generated coeffects before the generated-value trace can expose secrets.
(deftest generated-recordable-cofx-schema-failure-no-pre-validation-leak
  (with-runtime*
    (fn []
      (rf/reg-cofx :cofx/generated-secret
        {:recordable? true
         :schema      [:map [:token {:sensitive? true} :string]]}
        (fn [] {:token [sentinel]}))
      (rf/reg-event :cofx/uses-generated-secret
        {:rf.cofx/requires [:cofx/generated-secret]}
        (fn [_ _] {}))
      (with-trace-recorder! [all]
        (let [d   (ex-data (thrown-by #(rf/dispatch-sync [:cofx/uses-generated-secret])))
              inv (first (filter #(= :rf.error/cofx-value-invalid (:operation %)) @all))]
          (is (= [true :rf/redacted] [(:sensitive? inv) (-> inv :tags :value)]))
          (is (= [:rf.error/cofx-value-invalid :rf/redacted] [(:rf.error/id d) (:value d)]))
          (is (not (contains-sentinel? [@all d]))
              "the generated sentinel appears nowhere in the trace stream or the throw"))))))

#?(:cljs
   ;; Sub overrides validate through subscribe, independently of validate-sub!.
   (deftest sub-override-production-path-redacts-sensitive
     (with-runtime*
       (fn []
         (rf/reg-sub :sub/secret {:schema sensitive-map-schema}
                     (fn [_db _] {:secret "ok"}))
         (rf.late-bind/set-fn! :subs/resolve-sub-override
           (fn [query-v]
             (when (= query-v [:sub/secret])
               [failing-sensitive-value])))
         (try
           (let [trace (capture-failure #(deref (rf/subscribe [:sub/secret])))]
             (is (= [true false :sub-override :rf/redacted :rf/redacted :rf/redacted]
                    (redaction trace :where :value :received :rf.sub/query-v))))
           (finally
             (rf.late-bind/set-fn! :subs/resolve-sub-override nil)))))))

(def ^:private gen-nested-sensitive
  (rf.security.gen/nested-sensitive-generator
    sentinel
    [:map :vector :sequential :map-of :map-of-key :tuple :set :and :or :multi :orn]
    5))

(defn- all-kinds-redact?
  "Check every callable validation kind for a stamp and no leaked sentinel."
  [[schema value]]
  (every? (fn [validate!]
            (let [trace (capture-failure validate!)]
              (and (true? (:sensitive? trace))
                   (not (contains-sentinel? trace)))))
          [#(rf.schemas/validate-event! :gen/id [:gen/id value] {:schema [:cat [:= :gen/id] schema]})
           #(rf.schemas/validate-fx! :gen/fx :gen/ev value {:schema schema})
           #(rf.schemas/validate-sub! :gen/sub [:gen/sub sentinel] value {:schema schema})
           #(rf.machines.data-validation/validate-snapshot-data!
              :gen/machine {:data value} schema :macrostep)]))

(deftest every-kind-redacts-at-arbitrary-nesting
  (let [result (rf.security.gen/for-all gen-nested-sensitive 120 17 all-kinds-redact?)]
    (is (nil? result)
        (str "a validation kind leaked the sensitive sentinel (or missed the "
             ":sensitive? stamp) for a generated nesting: "
             (pr-str (when result (dissoc result :threw)))))))

(deftest non-sensitive-failure-not-over-redacted
  (let [plain-schema  [:map [:n :int]]
        plain-value   {:n "not-an-int"}
        event-trace   (capture-failure
                        #(rf.schemas/validate-event!
                           :plain/ev [:plain/ev plain-value]
                           {:schema [:cat [:= :plain/ev] plain-schema]}))
        machine-trace (capture-failure
                        #(rf.machines.data-validation/validate-snapshot-data!
                           :plain/machine {:data plain-value} plain-schema :macrostep))]
    (is (= [false [:plain/ev plain-value] plain-value]
           [(contains? event-trace :sensitive?)
            (-> event-trace :tags :value)
            (-> machine-trace :tags :value)]))))
