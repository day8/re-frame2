(ns re-frame.resources-keyed-root-invalid-params-cljs-test
  "A `:params-schema` whose marked slot sits below a `:map-of` at its root
  names no slot a declared path can reach: the walker writes the mark without
  the key above it, and a path rides a map key only once its first named
  segment has matched. So an invalid-params failure redacts the whole `:params`
  slot, in the resource arm and the mutation arm alike. The same `:map-of`
  under a named slot keeps per-slot redaction."
  (:require
   #?(:clj  [clojure.test :refer [deftest is use-fixtures]]
      :cljs [cljs.test :refer-macros [deftest is use-fixtures]])
   [clojure.string :as str]
   [re-frame.privacy :as rf.privacy]
   [re-frame.resources.registry :as rf.resources.registry]
   [re-frame.resources.mutation-registry :as rf.resources.mutation-registry]
   ;; load-bearing side-effecting requires: the facade registers the
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

(def ^:private account [:map [:acct {:sensitive? true} :string] [:limit :int]])

;; the failure is on "b"'s non-sensitive :limit, while "a" carries a
;; conforming sensitive :acct beside it
(def ^:private by-id {"a" {:acct "SECRET-ACCT-A" :limit 1}
                      "b" {:acct "SECRET-ACCT-B" :limit "not-an-int"}})

(defn- ex->data
  "Capture the thrown ex-data from `(f)`, or nil when it does not throw."
  [f]
  (try (f) nil
       (catch #?(:clj clojure.lang.ExceptionInfo :cljs cljs.core/ExceptionInfo) e
         (ex-data e))))

(defn- failures [schema params]
  [(ex->data #(rf.resources.registry/validate+canonicalize-params
                :report/by-account {:params-schema schema} params 'rf/ensure))
   (ex->data #(rf.resources.mutation-registry/validate+canonicalize-params
                :acct/update {:params-schema schema} params 'rf.mutation/execute))])

(deftest a-root-map-of-params-schema-redacts-the-whole-params-slot
  (let [[rdata mdata] (failures [:map-of :string account] by-id)]
    (is (= [:rf.error/resource-invalid-params rf.privacy/redacted-sentinel false]
           [(:rf.error/id rdata) (:params rdata) (str/includes? (pr-str rdata) "SECRET-ACCT")])
        "the resource arm")
    (is (= [:rf.error/mutation-invalid-params rf.privacy/redacted-sentinel false]
           [(:rf.error/id mdata) (:params mdata) (str/includes? (pr-str mdata) "SECRET-ACCT")])
        "the mutation arm")))

(deftest a-map-of-under-a-named-slot-redacts-per-slot
  (let [[rdata mdata] (failures [:map [:accounts [:map-of :string account]]] {:accounts by-id})
        redacted      {:accounts {"a" {:acct rf.privacy/redacted-sentinel :limit 1}
                                  "b" {:acct rf.privacy/redacted-sentinel :limit "not-an-int"}}}]
    (is (= [redacted redacted false]
           [(:params rdata) (:params mdata) (str/includes? (pr-str [rdata mdata]) "SECRET-ACCT")])
        "the marked slot redacts under every key and the failing field stays diagnostic")))
