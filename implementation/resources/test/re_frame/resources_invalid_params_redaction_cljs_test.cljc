(ns re-frame.resources-invalid-params-redaction-cljs-test
  "A params-schema validation failure must not leak a `:sensitive?` params slot
  or ride a `:large?` slot raw. Both the raw `:params` and the explainer's
  `:error` can carry conforming sensitive siblings of the field that failed, so
  the shared validation leaf redacts `:params` per slot from the schema props
  and routes the explainer output through `:schemas/redact-validation-tags`.
  The threat is the AI boundary and logs (the thrown ex-data and the trace
  egress); the success path still carries the canonical params."
  (:require
   #?(:clj  [clojure.test :refer [deftest is use-fixtures]]
      :cljs [cljs.test :refer-macros [deftest is use-fixtures]])
   [clojure.string :as str]
   [re-frame.privacy :as rf.privacy]
   [re-frame.resources.registry :as rf.resources.registry]
   [re-frame.resources.mutation-registry :as rf.resources.mutation-registry]
   ;; load-bearing side-effecting requires: the façade registers the
   ;; :resource / :mutation registrar kinds; schemas binds the Malli
   ;; validator + explainer + the shared walker / redaction hooks.
   [re-frame.resources]
   [re-frame.schemas]
   [re-frame.test-support :as rf.test-support]
   #?@(:clj  [[re-frame.substrate.plain-atom :as rf.substrate.plain-atom]]
       :cljs [[re-frame.adapter.reagent :as rf.adapter.reagent]])))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    #?(:clj  {:adapter rf.substrate.plain-atom/adapter}
       :cljs {:adapter rf.adapter.reagent/adapter})))

;; the failure is on the non-sensitive :age, while the raw params and the
;; explainer carry the conforming sensitive :token beside it
(def ^:private sensitive-params-schema
  [:map
   [:token {:sensitive? true} :string]
   [:age :int]])

(def ^:private bad-params {:token "SECRET-TOKEN-42" :age "not-an-int"})

(defn- ex->data
  "Capture the thrown ex-data from `(f)`, or nil when it does not throw."
  [f]
  (try (f) nil
       (catch #?(:clj clojure.lang.ExceptionInfo :cljs cljs.core/ExceptionInfo) e
         (ex-data e))))

(defn- resource-failure [resource-id schema params]
  (ex->data #(rf.resources.registry/validate+canonicalize-params
               resource-id {:params-schema schema} params 'rf/ensure)))

(deftest resource-invalid-params-redacts-sensitive-sibling
  (let [data (resource-failure :report/by-account sensitive-params-schema bad-params)]
    (is (= [:rf.error/resource-invalid-params :report/by-account
            {:token rf.privacy/redacted-sentinel :age "not-an-int"} false]
           [(:rf.error/id data) (:resource-id data) (:params data)
            (str/includes? (pr-str data) "SECRET-TOKEN-42")])
        "the :token slot is redacted, the failing field stays diagnostic, and the secret rides nowhere")))

(deftest resource-invalid-params-elides-large-sibling
  (let [big  (apply str (repeat 500 "x"))
        data (resource-failure :feed/by-cursor [:map [:blob {:large? true} :string] [:age :int]]
                               {:blob big :age "bad"})]
    (is (= [:rf.error/resource-invalid-params true false]
           [(:rf.error/id data) (contains? (get-in data [:params :blob]) :rf.size/large-elided)
            (str/includes? (pr-str (get-in data [:params :blob])) big)])
        "the :large? :blob slot is elided to the size marker")))

(deftest resource-invalid-params-no-marks-rides-verbatim
  (let [data (resource-failure :plain/r [:map [:age :int]] {:age "nope"})]
    (is (= [:rf.error/resource-invalid-params {:age "nope"} true]
           [(:rf.error/id data) (:params data) (some? (:error data))])
        "with no classified slot the params and the explainer output ride as they are")))

(deftest mutation-invalid-params-redacts-sensitive-sibling
  (let [data (ex->data
               #(rf.resources.mutation-registry/validate+canonicalize-params
                  :acct/update {:params-schema sensitive-params-schema} bad-params 'rf.mutation/execute))]
    (is (= [:rf.error/mutation-invalid-params :acct/update
            {:token rf.privacy/redacted-sentinel :age "not-an-int"} false]
           [(:rf.error/id data) (:mutation-id data) (:params data)
            (str/includes? (pr-str data) "SECRET-TOKEN-42")])
        "the mutation arm redacts through the same leaf under its own error descriptor")))
