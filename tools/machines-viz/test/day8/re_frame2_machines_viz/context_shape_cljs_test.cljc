(ns day8.re-frame2-machines-viz.context-shape-cljs-test
  "Declared-over-inferred static Context shape (EP-0005 · EP-0029 A3): a
  `[:schemas :data]` schema that is or wraps a Malli `:map` is authoritative
  (`:inferred? false`); otherwise the shape is inferred from one sample of the
  initial `:data` (`:inferred? true`)."
  (:require [clojure.test :refer [deftest is testing]]
            [day8.re-frame2-machines-viz.context-shape :as cs]))

(deftest inferred-from-the-data-sample-without-a-map-schema
  (testing "no schema, or one that validates :data as a whole, infers each key's type"
    (doseq [schema [nil [:and :int [:fn 'pos?]]]]
      (is (= {:shape     {:opened-count "number" :held-open? "boolean" :trail "vector"
                          :note "string" :tag "keyword" :nested "map" :many "set"}
              :inferred? true}
             (cs/static-context-shape
               {:initial :idle
                :data    {:opened-count 0 :held-open? false :trail []
                          :note "x" :tag :a :nested {} :many #{}}
                :schemas {:data schema}
                :states  {:idle {}}}))
          (pr-str schema))))
  (is (nil? (cs/static-context-shape {:initial :a :states {:a {}}}))
      "neither a :map schema nor a map :data hides the Context panel"))

(deftest declared-map-schema-is-authoritative
  (testing "the schema's entries win over a partial :data sample, captioned in the
            inferred vocabulary; per-entry props are skipped"
    (is (= {:shape     {:retries "number" :token "string?" :roles "vector" :active "boolean"
                        :bag "set" :lookup "map" :kind "keyword" :count "number" :label "string"}
            :inferred? false}
           (cs/static-context-shape
             {:initial :s
              :data    {:retries nil}
              :schemas {:data [:map
                               [:retries :int]
                               [:token {:optional true} [:maybe :string]]
                               [:roles [:vector :keyword]]
                               [:active :boolean]
                               [:bag [:set :keyword]]
                               [:lookup [:map-of :keyword :int]]
                               [:kind [:enum :a :b :c]]
                               [:count 'pos-int?]
                               [:label 'string?]]}
              :states  {:s {}}}))))
  (testing "a declared-but-empty or wrapped :map stays authoritative: undeclared
            sample keys are never inferred"
    (doseq [[schema shape] [[[:map] {}]
                            [[:map {:closed true}] {}]
                            [[:and [:map [:n :int]] [:fn 'some?]] {:n "number"}]]]
      (is (= {:shape shape :inferred? false}
             (cs/static-context-shape {:initial :s
                                       :data    {:n 1 :secret 99}
                                       :schemas {:data schema}
                                       :states  {:s {}}}))
          (pr-str schema)))))
