(ns re-frame.schemas-check-seam-test
  "The `:schemas/check!` seam and the core surfaces that report through it
  (Spec 010 §The validation seam).

  The seam answers nil on a conforming value, and otherwise the failure's
  category and its redacted tags — `:failing-id`, `:frame` and, in
  development, `:explain-humanized` among them — emitting the trace itself
  unless the caller asks for the tags only. A validator that throws reports
  `:rf.error/malformed-schema` and carries no value. The `:boundary? true`
  check, a Story sub override and a recordable coeffect value each report
  that one shape."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.late-bind :as rf.late-bind]
            [re-frame.privacy :as rf.privacy]
            [re-frame.schemas :as rf.schemas]
            [re-frame.schemas.test-fixture :as rf.schemas.test-fixture]
            [re-frame.spec :as rf.spec]
            [re-frame.subs.override-schema :as rf.subs.override-schema]
            [re-frame.test-support :refer [with-trace-recorder!]]))

(use-fixtures :each rf.schemas.test-fixture/reset-runtime)

(defn- check! [request]
  ((rf.late-bind/get-fn :schemas/check!) request))

(defn- traces-of
  "Run `body-fn`; return the traces it emitted under `op`."
  [op body-fn]
  (with-trace-recorder! [traces]
    (body-fn)
    (filterv #(= op (:operation %)) @traces)))

(defn- throwing-validator!
  "Install a validator that throws, as Malli does on a malformed schema form."
  []
  (rf.schemas/set-schema-fns! {:validate (fn [_ _] (throw (ex-info "schema boom" {})))}))

;; The trace surface lifts `:recovery` out of the tags onto the event.
(def ^:private seam-slots [:where :failing-id :frame])

;; ---- the seam ---------------------------------------------------------------

(deftest check-answers-nil-on-conform-and-with-no-validator
  (is (nil? (check! {:where :event :id :e/ok :schema :int :value 1})))
  (rf.schemas/set-schema-fns! {:validate nil})
  (is (nil? (check! {:where :event :id :e/ok :schema :int :value "x"}))
      "a nil :validate disables validation"))

(deftest check-reports-a-failure-in-one-shape
  (let [result (atom nil)
        [ev :as evs] (traces-of :rf.error/schema-validation-failure
                       #(reset! result (check! {:where      :event
                                                :id         :e/bad
                                                :schema     [:map [:n :int]]
                                                :value      {:n "x"}
                                                :frame      :rf/default
                                                :extra-tags {:event-id :e/bad}
                                                :reason     (fn [] "the reason")})))
        tags (:tags @result)]
    (is (= :rf.error/schema-validation-failure (:category @result)))
    (is (= {:where :event :failing-id :e/bad :frame :rf/default :recovery :no-recovery
            :event-id :e/bad :value {:n "x"} :received {:n "x"} :reason "the reason"
            :explain-humanized {:n ["should be an integer"]}}
           (dissoc tags :explain)))
    (is (some? (:explain tags)))
    (is (= [(dissoc tags :recovery)] (mapv :tags evs)) "the seam emitted the tags it returned")
    (is (= 1 (count evs)))
    (is (= :no-recovery (:recovery ev)))))

(deftest check-tags-only-emits-nothing
  (let [result (atom nil)
        evs    (traces-of :rf.error/schema-validation-failure
                 #(reset! result (check! {:id :e/bad :schema :int :value "x" :tags-only? true})))]
    (is (= [] evs))
    (is (= [:rf.error/schema-validation-failure "x"]
           [(:category @result) (get-in @result [:tags :value])]))))

(deftest check-redacts-against-the-schema
  (let [{:keys [tags]} (check! {:where :event :id :e/login
                                :schema [:map [:password {:sensitive? true} :string] [:age :int]]
                                :value {:password "SECRET-PW" :age "x"}
                                :tags-only? true})]
    (is (= [true rf.privacy/redacted-sentinel rf.privacy/redacted-sentinel
            rf.privacy/redacted-sentinel]
           ((juxt :sensitive? :value :explain :explain-humanized) tags)))
    (is (not (str/includes? (pr-str tags) "SECRET-PW")))))

(deftest check-reports-a-validator-throw-as-a-malformed-schema
  (throwing-validator!)
  (let [result (atom nil)
        evs    (traces-of :rf.error/malformed-schema
                 #(reset! result (check! {:where :sub-return :id :s/x :schema [:vector]
                                          :value "SECRET-VALUE" :frame :rf/default
                                          :extra-tags {:rf.sub/query-v [:s/x "SECRET-ARG"]}})))]
    (is (= :rf.error/malformed-schema (:category @result)))
    (is (= {:where :sub-return :failing-id :s/x :frame :rf/default :schema [:vector]
            :recovery :no-recovery}
           (dissoc (:tags @result) :reason)))
    (is (str/includes? (:reason (:tags @result)) "is malformed"))
    (is (= [(dissoc (:tags @result) :recovery)] (mapv :tags evs)))
    (is (not (str/includes? (pr-str evs) "SECRET-")) "no value-bearing slot rides")))

;; ---- the `:boundary? true` check ------------------------------------------------

(defn- boundary-traces [op event]
  (traces-of op #(with-redefs [rf.spec/dev-mode? (constantly false)]
                   (rf/dispatch-sync event))))

(deftest boundary-failure-carries-the-seam-tags
  (rf/reg-event :api/strict {:schema [:cat [:= :api/strict] :int] :boundary? true}
    (fn [_ _] {}))
  (let [[ev :as evs] (boundary-traces :rf.error/schema-validation-failure [:api/strict "x"])]
    (is (= 1 (count evs)))
    (is (= [{:where :event :failing-id :api/strict :frame :rf/default} :no-recovery]
           [(select-keys (:tags ev) seam-slots) (:recovery ev)]))
    (is (= :boundary (get-in ev [:tags :source])))
    (is (some? (get-in ev [:tags :explain-humanized])))))

(deftest boundary-validator-throw-is-a-malformed-schema-and-skips-the-handler
  (let [calls (atom 0)]
    (rf/reg-event :api/throws {:schema [:cat [:= :api/throws] :int] :boundary? true}
      (fn [_ _] (swap! calls inc) {}))
    (throwing-validator!)
    (let [[ev :as evs] (boundary-traces :rf.error/malformed-schema [:api/throws 1])]
      (is (= 1 (count evs)))
      (is (= [{:where :event :failing-id :api/throws :frame :rf/default} :no-recovery]
             [(select-keys (:tags ev) seam-slots) (:recovery ev)]))
      (is (= :boundary (get-in ev [:tags :source])))
      (is (zero? @calls) "fail closed: the handler does not run"))))

;; ---- a Story sub override -------------------------------------------------------

(defn- override! [value sub-meta]
  (rf.subs.override-schema/validate-sub-override! value [:s/count 7] sub-meta :rf/default))

(deftest sub-override-failure-carries-the-seam-tags
  (let [returned (atom nil)
        [ev :as evs] (traces-of :rf.error/schema-validation-failure
                       #(reset! returned (override! "x" {:schema :int})))]
    (is (= "x" @returned) "the pinned value renders anyway")
    (is (= 1 (count evs)))
    (is (= [{:where :sub-override :failing-id :s/count :frame :rf/default} :no-recovery]
           [(select-keys (:tags ev) seam-slots) (:recovery ev)]))
    (is (= [:s/count 7] (get-in ev [:tags :rf.sub/query-v])))
    (is (= ["should be an integer"] (get-in ev [:tags :explain-humanized])))))

(deftest sub-override-validator-throw-is-a-malformed-schema
  (throwing-validator!)
  (let [returned (atom nil)
        evs      (traces-of :rf.error/malformed-schema
                   #(reset! returned (override! "x" {:schema :int})))]
    (is (= "x" @returned))
    (is (= [{:where :sub-override :failing-id :s/count :frame :rf/default}]
           (mapv #(select-keys (:tags %) seam-slots) evs)))))

;; ---- a recordable coeffect value -------------------------------------------------

(defn- dispatch-graded! [value]
  (rf/reg-cofx :cf/graded {:recordable? true :provided? true :schema [:enum :a :b]})
  (rf/reg-event :cf/reads {:rf.cofx/requires [:cf/graded]} (fn [_ _] {}))
  (try (rf/dispatch-sync [:cf/reads] {:rf.cofx {:cf/graded value}})
       nil
       (catch clojure.lang.ExceptionInfo e (ex-data e))))

(deftest cofx-value-failure-carries-the-seam-tags
  (let [thrown (atom nil)
        [ev :as evs] (traces-of :rf.error/cofx-value-invalid
                       #(reset! thrown (dispatch-graded! :zz)))]
    (is (= :rf.error/cofx-value-invalid (:rf.error/id @thrown)))
    (is (= 1 (count evs)))
    (is (= {:failing-id :cf/reads :frame :rf/default :rf.cofx/id :cf/graded :value :zz}
           (select-keys (:tags ev) [:failing-id :frame :rf.cofx/id :value])))
    (is (some? (get-in ev [:tags :explain-humanized])))
    (is (some? (:explain-humanized @thrown)) "the thrown ex-data carries it too")))

(deftest cofx-validator-throw-is-a-malformed-schema-and-still-a-hard-error
  (throwing-validator!)
  (let [thrown (atom nil)
        evs    (traces-of :rf.error/malformed-schema #(reset! thrown (dispatch-graded! :a)))]
    (is (= :rf.error/cofx-value-invalid (:rf.error/id @thrown)) "the injection still fails")
    (is (= [{:where :cofx :failing-id :cf/reads :frame :rf/default :rf.cofx/id :cf/graded}]
           (mapv #(select-keys (:tags %) [:where :failing-id :frame :rf.cofx/id]) evs)))
    (is (not (contains? @thrown :value)) "the validator decided nothing, so no value rides")))
