(ns re-frame.schemas.digest-parity-test
  "JVM half of the app-schemas-digest cross-runtime parity: the same fixtures
  and literals as `re-frame.schemas.digest-parity-cljs-test`."
  (:require [clojure.test :refer [deftest is testing]]
            [re-frame.schemas.digest-parity-fixtures :as fixtures]
            [re-frame.schemas.validator :as rf.schemas.validator]))

(deftest jvm-digest-matches-canonical-literal
  (doseq [{:keys [label input expected]} fixtures/all-fixtures]
    (is (= expected (fixtures/compute-digest input)) label)))

(deftest jvm-fn-token-is-the-class-name
  (testing "the token is the class name, fixed by the compiled artefact, so no
            per-process address reaches the digest bytes"
    (is (= "[:map [:n \"#fn[clojure.core$pos_int_QMARK_]\"]]"
           (rf.schemas.validator/run-printer fixtures/fn-bearing-schema)))))

(deftest jvm-printer-limits-never-reach-digest-bytes
  (testing "an ambient *print-length* / *print-level* neither reaches the
            digest nor leaves truncated bytes in the print memo"
    ;; Collection-valued map keys and set members also send the bound through
    ;; the canonicaliser's pr-str key comparator, where it would merge them.
    (let [schema   [:map {:doc-keys {[:k 1] :x [:k 2] :y} :doc-set #{[:m 1] [:m 2]}}
                    [:a :int]]
          digest   (fn []
                     (rf.schemas.validator/clear-edn-print-cache!)
                     (fixtures/compute-digest {[:s] schema}))
          baseline (digest)]
      (doseq [[length level] [[1 nil] [nil 1]]]
        (is (= baseline (binding [*print-length* length
                                  *print-level*  level]
                          (digest)))
            (pr-str [length level])))
      (rf.schemas.validator/clear-edn-print-cache!))))
