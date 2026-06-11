(ns re-frame.http-privacy-body-test
  "Unit tests for `re-frame.http-privacy-body` — HTTP response-body
  classification (EP-0015 §8, ruled issue 5; rf2-ppkh3v).

  Pins the contract that a managed HTTP response body is a registration-
  owned transient payload classified per-slot via `:sensitive?` / `:large?`
  props on the request's `:decode` SCHEMA (the EP-0005 mechanism reused):

    1. a Malli-schema `:decode` is a schema (carries marks); the keyword
       decode modes and a custom fn are NOT;
    2. per-slot `:sensitive?` marks on the decode schema redact the decoded
       body's marked slots irrespective of the per-call `:sensitive?` flag;
    3. a root-level (`[]`) `:sensitive?` mark redacts the WHOLE body;
    4. an unschematized body fails CLOSED off-box (omitted), while a
       schema-classified body rides classified.

  The schemas artefact is a test-only dep here, so requiring it binds the
  shared walker hooks (`:schemas/extract-sensitive-paths-from-schema` etc.)."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.http-privacy-body :as body]
            [re-frame.registrar :as registrar]
            ;; load-bearing: binds the shared schema walker hooks.
            [re-frame.schemas]))

(defn- reset-runtime [t]
  (registrar/clear-all!)
  (t))

(use-fixtures :each reset-runtime)

;; ---- 1. schema-decode? ----------------------------------------------------

(deftest schema-decode?-distinguishes-schemas-from-modes-and-fns
  (testing "a Malli-schema :decode is a schema"
    (is (body/schema-decode? [:map [:token :string]]))
    (is (body/schema-decode? [:map [:a :int]])))
  (testing "keyword decode modes are NOT schemas"
    (is (not (body/schema-decode? :auto)))
    (is (not (body/schema-decode? :json)))
    (is (not (body/schema-decode? :text)))
    (is (not (body/schema-decode? :blob)))
    (is (not (body/schema-decode? :array-buffer)))
    (is (not (body/schema-decode? :form-data))))
  (testing "a custom decoder fn is NOT a schema"
    (is (not (body/schema-decode? (fn [_text _headers] {}))))
    (is (not (body/schema-decode? identity))))
  (testing "nil :decode (= :auto) is NOT a schema"
    (is (not (body/schema-decode? nil)))))

;; ---- 2. per-slot marks → classify-decoded ---------------------------------

(deftest classify-decoded-redacts-marked-slot
  (testing "a :decode schema marking [:token] sensitive redacts that slot
            of the decoded body, leaving siblings intact"
    (let [schema  [:map
                   [:token {:sensitive? true} :string]
                   [:user-id :int]]
          decoded {:token "bearer-secret" :user-id 42}
          out     (body/classify-decoded decoded schema)]
      (is (= :rf/redacted (:token out)))
      (is (= 42 (:user-id out)) "non-sensitive sibling rides verbatim"))))

(deftest classify-decoded-nested-slot
  (testing "a nested :sensitive? slot redacts at depth"
    (let [schema  [:map
                   [:auth [:map [:refresh-token {:sensitive? true} :string]]]
                   [:profile [:map [:name :string]]]]
          decoded {:auth {:refresh-token "rt-secret"}
                   :profile {:name "Ada"}}
          out     (body/classify-decoded decoded schema)]
      (is (= :rf/redacted (get-in out [:auth :refresh-token])))
      (is (= "Ada" (get-in out [:profile :name]))))))

(deftest classify-decoded-no-marks-passes-through
  (testing "a schema with no :sensitive? marks leaves the body unchanged"
    (let [schema  [:map [:a :int] [:b :string]]
          decoded {:a 1 :b "x"}]
      (is (= decoded (body/classify-decoded decoded schema))))))

(deftest classify-decoded-non-schema-is-noop
  (testing "a keyword / fn / nil :decode is a no-op (body governed by the
            per-call flag / off-box disposition, not per-slot marks)"
    (let [decoded {:token "secret"}]
      (is (= decoded (body/classify-decoded decoded :json)))
      (is (= decoded (body/classify-decoded decoded :auto)))
      (is (= decoded (body/classify-decoded decoded nil)))
      (is (= decoded (body/classify-decoded decoded (fn [_ _] {})))))))

;; ---- 3. whole-body root prop ----------------------------------------------

(deftest whole-body-root-sensitive-redacts-everything
  (testing "a root-level [] :sensitive? prop on the decode schema marks the
            WHOLE body sensitive (the opaque-token-response case)"
    (let [schema  [:string {:sensitive? true}]
          decoded "opaque-token-value"]
      (is (body/whole-body-sensitive-mark? schema))
      (is (= :rf/redacted (body/classify-decoded decoded schema))))))

(deftest whole-body-mark-absent-for-non-root-marks
  (testing "a per-slot (non-root) mark is NOT a whole-body mark"
    (is (not (body/whole-body-sensitive-mark? [:map [:token {:sensitive? true} :string]])))
    (is (not (body/whole-body-sensitive-mark? :json)))))

;; ---- 4. off-box disposition (fail-closed) ---------------------------------

(deftest off-box-disposition-classifies-schema-bodies
  (testing "a schema-:decode body is :classify off-box"
    (is (= :classify (body/off-box-body-disposition [:map [:a :int]])))
    (is (= :classify (body/off-box-body-disposition [:string {:sensitive? true}])))))

(deftest off-box-disposition-omits-unschematized-bodies
  (testing "an UNSCHEMATIZED body (keyword mode / custom fn / nil) is :omit
            off-box — whole-sensitive, fail-closed (EP-0015 issue 5)"
    (is (= :omit (body/off-box-body-disposition :auto)))
    (is (= :omit (body/off-box-body-disposition :json)))
    (is (= :omit (body/off-box-body-disposition :text)))
    (is (= :omit (body/off-box-body-disposition :blob)))
    (is (= :omit (body/off-box-body-disposition nil)))
    (is (= :omit (body/off-box-body-disposition (fn [_ _] {}))))))
