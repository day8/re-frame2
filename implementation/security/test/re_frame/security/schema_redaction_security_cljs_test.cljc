(ns re-frame.security.schema-redaction-security-cljs-test
  "Schema-validation traces redact sensitive values and collection path segments."
  (:require #?(:clj  [clojure.test :refer [deftest is use-fixtures]]
               :cljs [cljs.test :refer-macros [deftest is use-fixtures]])
            [re-frame.core :as rf]
            [re-frame.frame :as rf.frame]
            ;; Publish the validator and explainer hooks.
            [re-frame.schemas.malli]
            [re-frame.schemas :as rf.schemas]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            #?(:clj  [re-frame.test-support :as rf.test-support :refer [with-trace-recorder!]]
               :cljs [re-frame.test-support :as rf.test-support :refer-macros [with-trace-recorder!]])
            [re-frame.security.gen :as rf.security.gen]))

 ;; Direct app-db validation uses frame-local registrations, against a live
 ;; frame: the failure trace is projected through that frame's elision
 ;; registry, and a frame the walker cannot resolve redacts the whole value.
(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture {:adapter rf.substrate.plain-atom/adapter})
  (fn [test-fn]
    (binding [rf.frame/*current-frame* :rf/default]
      (test-fn))))

(def ^:private sentinel "S3CR3T-rf2-3cfvt-DO-NOT-LEAK")

(defn- contains-sentinel?
  "Scan all trace slots, including stringified data, for the sentinel."
  [x]
  (rf.security.gen/contains-string? x sentinel))

(def ^:private gen-nested-sensitive
  "Generate invalid sensitive leaves under one to six schema layers."
  (rf.security.gen/nested-sensitive-generator
    sentinel
    [:map :vector :sequential :map-of :tuple :map-of-key :set :and :or :multi :orn]
    6))

(defn- failure-trace
  [schema db]
  (rf/reg-app-schema [:root] schema)
  (with-trace-recorder! [traces]
    (rf.schemas/validate-app-schema! {:root db} :root/bad)
    (first (filter #(= :rf.error/schema-validation-failure (:operation %))
                   @traces))))

(deftest sensitive-sentinel-never-leaks-at-arbitrary-nesting
  (let [result (rf.security.gen/for-all
                 gen-nested-sensitive 300 11
                 (fn [[schema db]]
                   (let [v (failure-trace schema db)]
                     (and (true? (:sensitive? v))
                          (not (contains-sentinel? v))))))]
    (is (nil? result)
        (str "the sensitive sentinel leaked (or no redaction stamp) for a "
             "generated nesting: "
             (pr-str (when result (dissoc result :threw)))))))

(defn- stamp-redaction-and-leak
  "Read sensitivity, value, explanation, and sentinel presence."
  [v]
  [(:sensitive? v) (-> v :tags :value) (-> v :tags :explain) (contains-sentinel? v)])

;; A set path includes the non-sensitive sibling in the entire reported element.
(deftest set-path-tag-carries-no-secret
  (let [v (failure-trace
            [:set [:map [:token {:sensitive? true} :string] [:ssn :string]]]
            #{{:token [sentinel] :ssn (str "ssn-" sentinel)}})]
    (is (= [true :rf/redacted :rf/redacted false] (stamp-redaction-and-leak v))
        (pr-str (:tags v)))))

(deftest map-of-sensitive-key-path-tag-carries-no-secret
  (let [v (failure-trace
            [:map-of [:string {:sensitive? true}] [:map [:age :int]]]
            {sentinel {:age [sentinel]}})]
    (is (= [true :rf/redacted :rf/redacted false] (stamp-redaction-and-leak v))
        (pr-str (:tags v)))
    (is (= [:root :rf/redacted :age] (-> v :tags :path)))))

;; Generated leaves are sensitive themselves; these unmarked leaves rely on the ancestor.
(deftest ancestor-sensitive-wrapper-corpus-all-redacted
  (doseq [[label schema db]
          [["and-ancestor"
            [:map [:s {:sensitive? true} [:and [:map [:k :int]]]]]
            {:s {:k sentinel}}]
           ["or-ancestor"
            [:map [:s {:sensitive? true} [:or [:map [:k :int]]]]]
            {:s {:k sentinel}}]
           ["multi-ancestor"
            [:map [:s {:sensitive? true}
                   [:multi {:dispatch :t} [:a [:map [:t :keyword] [:k :int]]]]]]
            {:s {:t :a :k sentinel}}]
           ["orn-ancestor"
            [:map [:s {:sensitive? true} [:orn [:a [:map [:k :int]]]]]]
            {:s {:k sentinel}}]]]
    (let [v (failure-trace schema db)]
      (is (= [true :rf/redacted false]
             [(:sensitive? v) (-> v :tags :value) (contains-sentinel? v)])
          label))))

(deftest non-sensitive-collection-failure-not-over-redacted
  (let [v (failure-trace [:vector [:map [:name :string]]] [{:name 99}])]
    (is (= [false 99] [(contains? v :sensitive?) (-> v :tags :value)])
        "no stamp, and :value rides verbatim")))
