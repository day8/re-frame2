;; Source guards retain the fenced CLJS storage rewrite's totality, trust and replay promises.

(ns storage-materializer-test
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is run-tests]]))

(def ^:private leaf-md
  (let [f (io/file (-> *file* io/file .getAbsoluteFile .getParentFile .getParentFile)
                   "references" "schemaless-events.md")]
    (delay (slurp f))))

(defn- block-containing
  "Reject duplicates so one recommendation cannot mask another."
  [needle]
  (let [hits (->> (re-seq #"(?s)```clojure\r?\n(.*?)```" @leaf-md)
                  (map second)
                  (filter #(str/includes? % needle)))]
    (when (= 1 (count hits)) (first hits))))

(def ^:private after-block  (delay (block-containing "rf/reg-cofx :session/stored")))

(deftest after-block-is-total
  (let [block @after-block]
    (is (str/includes? block "(.-localStorage js/globalThis)")
        "the chain must start from the localStorage PROPERTY, so some-> can short-circuit")
    (is (not (str/includes? block "js/globalThis.localStorage"))
        "a method call on js/globalThis.localStorage throws on Node/SSR/headless hosts")
    (is (str/includes? block "catch") "the JSON decode must be bounded by a catch")
    (is (not (str/includes? block ":platforms"))
        "a platform-skipped generator produces no fact, so :session/rehydrate would take the missing-required path")))

(deftest trust-and-replay-boundaries-survive
  (let [block @after-block]
    (is (str/includes? block ":recordable? true") "the generator is recordable, closing the replay hole")
    (is (str/includes? block "m/validate Session") "the always-on Malli gate is the trust boundary")
    (is (not (str/includes? block "goog.DEBUG")) "the trust gate must not be dev-elided")))

(let [{:keys [fail error]} (run-tests 'storage-materializer-test)]
  (System/exit (if (zero? (+ fail error)) 0 1)))
