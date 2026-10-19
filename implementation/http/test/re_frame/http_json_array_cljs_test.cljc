(ns re-frame.http-json-array-cljs-test
  "Host-symmetric (JVM + CLJS) contract: `re-frame.http.json/json-parse`
  decodes a JSON array to a vector at every depth on both hosts, so a
  `[:vector …]` schema accepts the same body wherever it is decoded."
  (:require #?(:clj  [clojure.test :refer [deftest is]]
               :cljs [cljs.test :refer-macros [deftest is]])
            [re-frame.http.json :as rf.http.json]))

(deftest json-arrays-decode-to-vectors-at-every-depth
  (let [decoded (rf.http.json/json-parse "[{\"ids\":[1,2],\"tags\":[[\"a\"]]}]")]
    (is (= [{:ids [1 2] :tags [["a"]]}] decoded))
    (is (vector? decoded) "a top-level array")
    (is (vector? (:ids (first decoded))) "an array inside a map inside an array")
    (is (vector? (first (:tags (first decoded)))) "an array nested in an array")))
