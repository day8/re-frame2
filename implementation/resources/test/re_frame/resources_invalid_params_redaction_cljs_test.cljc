(ns re-frame.resources-invalid-params-redaction-cljs-test
  "Resource / mutation params-schema validation failure data must not leak a
  `:sensitive?` params slot or ride a `:large?` slot raw.

  `validate+canonicalize-params` (resource registry + mutation registry) runs
  the pluggable Malli validator against the resource / mutation `:params-schema`
  and, on a conformance failure, throws `:rf.error/resource-invalid-params` /
  `:rf.error/mutation-invalid-params`. Both the raw `:params` and the explainer's
  `:error` can contain conforming sensitive siblings of the field that failed.

  The validation-failure projector reads schema props for per-slot `:params`
  redaction and routes explainer output through the shared schemas seam
  (`:schemas/redact-validation-tags`) for the explainer `:error`. The schema
  therefore owns both projections of its validation-failure record. Durable
  SSR/wire classification is a separate projection-relative owner declaration.

  Threat model is the AI-boundary + logs (the thrown ex-data is public error
  data + the trace egress) — NOT in-process correctness; the canonical
  (non-sensitive) params still flow normally on the success path.

  CLJC so the JVM run (`clojure -M:test`, the load-bearing gate) exercises it;
  the schemas artefact is a test-only dep, so the Malli validator + the shared
  walker / redaction hooks are bound here by requiring `re-frame.schemas`."
  (:require
   #?(:clj  [clojure.test :refer [deftest is testing use-fixtures]]
      :cljs [cljs.test :refer-macros [deftest is testing use-fixtures]])
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

;; A params schema with a CONFORMING sensitive sibling (`:token`) and a FAILING
;; non-sensitive field (`:age` declared `:int`, supplied a string). The failure
;; is on `:age`, but the raw params + explainer carry the conforming sensitive
;; `:token` alongside it — the sibling-leak shape this suite prevents.
(def ^:private sensitive-params-schema
  [:map
   [:token {:sensitive? true} :string]
   [:age :int]])

;; A params schema marking a sibling `:large?`; the failing field is again the
;; non-sensitive `:age`; the large sibling should elide consistently.
(def ^:private large-params-schema
  [:map
   [:blob {:large? true} :string]
   [:age :int]])

(def ^:private bad-params {:token "SECRET-TOKEN-42" :age "not-an-int"})

(defn- ex->data
  "Capture the thrown ex-data from `(f)`, or nil when it does not throw."
  [f]
  (try (f) nil
       (catch #?(:clj clojure.lang.ExceptionInfo :cljs cljs.core/ExceptionInfo) e
         (ex-data e))))

;; ===========================================================================
;; Resource params-schema validation failure — no sensitive leak
;; ===========================================================================

(deftest resource-invalid-params-redacts-sensitive-sibling
  (testing "rf2-99j4e4: a resource params-schema failure on a NON-sensitive
            field must NOT leak a conforming `:sensitive?` sibling param in the
            thrown ex-data — neither under `:params` nor under the explainer
            `:error`"
    (let [spec {:params-schema sensitive-params-schema}
          data (ex->data
                 #(rf.resources.registry/validate+canonicalize-params
                    :report/by-account spec bad-params 'rf/ensure))]
      (is (some? data) "the failure throws ex-info")
      (is (= :rf.error/resource-invalid-params (:rf.error/id data))
          "the canonical invalid-params error id is preserved")
      ;; the structural slots stay so the failure stays locatable
      (is (= :report/by-account (:resource-id data)) "resource-id preserved")
      ;; the sensitive param slot is redacted in :params
      (is (= rf.privacy/redacted-sentinel (get-in data [:params :token]))
          "the `:sensitive?` :token param slot is redacted in :params")
      (is (= "not-an-int" (get-in data [:params :age]))
          "the non-sensitive failing field rides verbatim (it must, to be diagnostic)")
      ;; the raw secret appears NOWHERE in the whole ex-data (params + error)
      (is (not (str/includes? (pr-str data) "SECRET-TOKEN-42"))
          "the raw sensitive param value does not ride ANYWHERE in the ex-data"))))

(deftest resource-invalid-params-elides-large-sibling
  (testing "rf2-99j4e4: a resource params-schema failure elides a `:large?`
            sibling param consistently with resource params egress"
    (let [big  (apply str (repeat 500 "x"))
          spec {:params-schema large-params-schema}
          data (ex->data
                 #(rf.resources.registry/validate+canonicalize-params
                    :feed/by-cursor spec {:blob big :age "bad"} 'rf/ensure))]
      (is (= :rf.error/resource-invalid-params (:rf.error/id data)))
      (is (contains? (get-in data [:params :blob]) :rf.size/large-elided)
          "the `:large?` :blob param slot is elided to the size marker")
      (is (not (str/includes? (pr-str (get-in data [:params :blob])) big))
          "the raw large param value does not ride in :params"))))

(deftest resource-invalid-params-no-marks-rides-verbatim
  (testing "rf2-99j4e4: with NO classified params slot the error data is
            unchanged — the canonicalization / nil-vs-missing behavior for
            ordinary invalid params is preserved (the raw failing value rides
            so the failure stays diagnostic)"
    (let [spec {:params-schema [:map [:age :int]]}
          data (ex->data
                 #(rf.resources.registry/validate+canonicalize-params
                    :plain/r spec {:age "nope"} 'rf/ensure))]
      (is (= :rf.error/resource-invalid-params (:rf.error/id data)))
      (is (= {:age "nope"} (:params data))
          "the raw (unclassified) params ride verbatim — diagnostic, no over-redaction")
      (is (some? (:error data))
          "the explainer output is present (no classified slot to redact against)"))))

;; ===========================================================================
;; Mutation params-schema validation failure — no sensitive leak
;; ===========================================================================

(deftest mutation-invalid-params-redacts-sensitive-sibling
  (testing "rf2-99j4e4: the analogous mutation params-schema failure must NOT
            leak a conforming `:sensitive?` sibling param in the thrown ex-data"
    (let [spec {:params-schema sensitive-params-schema}
          data (ex->data
                 #(rf.resources.mutation-registry/validate+canonicalize-params
                    :acct/update spec bad-params 'rf.mutation/execute))]
      (is (some? data) "the failure throws ex-info")
      (is (= :rf.error/mutation-invalid-params (:rf.error/id data))
          "the canonical mutation invalid-params error id is preserved")
      (is (= :acct/update (:mutation-id data)) "mutation-id preserved")
      (is (= rf.privacy/redacted-sentinel (get-in data [:params :token]))
          "the `:sensitive?` :token param slot is redacted in :params")
      (is (= "not-an-int" (get-in data [:params :age]))
          "the non-sensitive failing field rides verbatim")
      (is (not (str/includes? (pr-str data) "SECRET-TOKEN-42"))
          "the raw sensitive param value does not ride ANYWHERE in the ex-data"))))

(deftest mutation-invalid-params-no-marks-rides-verbatim
  (testing "rf2-99j4e4: an unclassified mutation params failure rides verbatim
            (canonicalization preserved for ordinary invalid params)"
    (let [spec {:params-schema [:map [:age :int]]}
          data (ex->data
                 #(rf.resources.mutation-registry/validate+canonicalize-params
                    :plain/m spec {:age "nope"} 'rf.mutation/execute))]
      (is (= :rf.error/mutation-invalid-params (:rf.error/id data)))
      (is (= {:age "nope"} (:params data))
          "the raw (unclassified) params ride verbatim")
      (is (some? (:error data)) "the explainer output is present"))))
