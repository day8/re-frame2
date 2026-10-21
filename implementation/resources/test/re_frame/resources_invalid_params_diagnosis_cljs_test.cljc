(ns re-frame.resources-invalid-params-diagnosis-cljs-test
  "A resource or mutation params check reports through the `:schemas/check!`
  seam (Spec 010 §The validation seam), so its thrown invalid-params error
  carries the seam's diagnosis: in development the seam's redacted
  `:explain-humanized` (Spec 010 §Humanize-hook), and, when the validator
  throws, the malformed-schema distinction (Spec 010 §Per-step recovery) —
  `:reason :malformed-schema` on the domain error, the params withheld because
  the validator decided nothing about them, and the `:rf.error/malformed-schema`
  trace every validating surface emits, at the family's own `:where`. The trace
  and the humanized slot are development-only; the thrown error holds in every
  build."
  (:require
   #?(:clj  [clojure.test :refer [deftest is use-fixtures]]
      :cljs [cljs.test :refer-macros [deftest is use-fixtures]])
   [clojure.string :as str]
   [re-frame.interop :as rf.interop]
   [re-frame.privacy :as rf.privacy]
   [re-frame.resources.registry :as rf.resources.registry]
   [re-frame.resources.mutation-registry :as rf.resources.mutation-registry]
   [re-frame.trace.tooling :as rf.trace.tooling]
   ;; load-bearing side-effecting requires: the façade registers the
   ;; :resource / :mutation registrar kinds; schemas binds the validator and
   ;; publishes the `:schemas/check!` seam.
   [re-frame.resources]
   [re-frame.schemas :as rf.schemas]
   [re-frame.test-support :as rf.test-support]
   #?@(:clj  [[re-frame.substrate.plain-atom :as rf.substrate.plain-atom]]
       :cljs [[re-frame.adapter.reagent :as rf.adapter.reagent]])))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    #?(:clj  {:adapter rf.substrate.plain-atom/adapter}
       :cljs {:adapter rf.adapter.reagent/adapter})))

(def ^:private params-schema [:map [:n :int]])

(def ^:private families
  "Each family's public validate+canonicalize, its error id, its id slot and the
  `:where` its malformed-schema trace carries."
  {:resource {:check    #(rf.resources.registry/validate+canonicalize-params
                           :diag/resource {:params-schema params-schema} % 'rf/ensure)
              :error-id :rf.error/resource-invalid-params
              :id-slot  [:resource-id :diag/resource]
              :where    :resource-params}
   :mutation {:check    #(rf.resources.mutation-registry/validate+canonicalize-params
                           :diag/mutation {:params-schema params-schema} % 'rf.mutation/execute)
              :error-id :rf.error/mutation-invalid-params
              :id-slot  [:mutation-id :diag/mutation]
              :where    :mutation-params}})

(defn- with-schema-fns
  "Run `f` with `fns` merged into the installed validator port, restoring the
  port afterwards."
  [fns f]
  (let [installed (rf.schemas/schema-fns)]
    (try
      (rf.schemas/set-schema-fns! fns)
      (f)
      (finally (rf.schemas/set-schema-fns! installed)))))

(defn- outcome
  "`(f)`'s result as `{:value v}`, or `{:data ex-data :message ex-message}`
  when it throws, with the `:rf.error/malformed-schema` traces it emitted."
  [f]
  (let [seen (atom [])
        k    ::malformed-schema-recorder]
    (rf.trace.tooling/register-listener!
      k (fn [ev] (when (= :rf.error/malformed-schema (:operation ev))
                   (swap! seen conj ev))))
    (try
      (assoc (try {:value (f)}
                  (catch #?(:clj clojure.lang.ExceptionInfo :cljs cljs.core/ExceptionInfo) e
                    {:data (ex-data e) :message (ex-message e)}))
             :malformed-traces @seen)
      (finally (rf.trace.tooling/unregister-listener! k)))))

(deftest an-ordinary-failure-carries-the-seams-humanized-explanation
  (with-schema-fns {:humanize (fn [_] {:n ["HUMANIZED"]})}
    (fn []
      (doseq [[family {:keys [check error-id id-slot]}] families]
        (let [{:keys [data malformed-traces]} (outcome #(check {:n "x"}))]
          (is (= [error-id (second id-slot) {:n "x"} true
                  (when rf.interop/debug-enabled? {:n ["HUMANIZED"]})]
                 [(:rf.error/id data) (get data (first id-slot)) (:params data)
                  (some? (:error data)) (:explain-humanized data)])
              (str family ": the params, the explanation and, in development, its humanized form"))
          (is (string? (:reason data)) (str family ": an ordinary failure keeps its sentence"))
          (is (empty? malformed-traces) (str family ": an ordinary failure is not a malformed schema")))))))

(deftest a-throwing-validator-reports-a-malformed-schema
  (with-schema-fns {:validate (fn [_ _] (throw (ex-info "schema boom" {})))}
    (fn []
      (doseq [[family {:keys [check error-id id-slot where]}] families]
        (let [{:keys [data message malformed-traces]} (outcome #(check {:n "SECRET-N"}))]
          (is (= [error-id (second id-slot) :malformed-schema rf.privacy/redacted-sentinel true false]
                 [(:rf.error/id data) (get data (first id-slot)) (:reason data) (:params data)
                  (:sensitive? data) (contains? data :error)])
              (str family ": the domain error reports the distinction and withholds the undecided params"))
          (is (str/includes? (str message) "malformed")
              (str family ": the message names a broken schema, not a rejected input"))
          (is (= (if rf.interop/debug-enabled?
                   [{:where where :failing-id (second id-slot) :schema params-schema}]
                   [])
                 (mapv #(select-keys (:tags %) [:where :failing-id :schema]) malformed-traces))
              (str family ": the malformed-schema trace names its surface, the id and the schema to fix"))
          (is (not (str/includes? (pr-str [data malformed-traces]) "SECRET-N"))
              (str family ": no value the validator never decided is reattached")))))))

(deftest a-conforming-value-passes
  (doseq [[family {:keys [check]}] families]
    (let [{:keys [value data malformed-traces]} (outcome #(check {:n 1}))]
      (is (= [{:n 1} nil []] [value data malformed-traces])
          (str family ": the canonical params, no error, no trace")))))
