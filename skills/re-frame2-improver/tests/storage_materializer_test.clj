;;;; tests/storage_materializer_test.clj — the canonical storage materializer
;;;; in references/schemaless-events.md must be TOTAL. Its `:doc` promises nil
;;;; when storage is absent or unusable, but the direct
;;;; `(some-> (.getItem js/globalThis.localStorage "session") …)` throws on a
;;;; host with no localStorage before `some->` tests anything, and a corrupt
;;;; entry throws out of `js/JSON.parse`. The framework turns a recordable
;;;; generator's throw into `:rf.error/coeffect-exception` and skips the
;;;; handler, so the boot step silently never runs.
;;;;
;;;; No gate executes fenced CLJS, so this pins the shape of the snippet an
;;;; agent copies; the Before block is the non-vacuity control.
;;;;
;;;; Run: bb tests/storage_materializer_test.clj  (from skills/re-frame2-improver/)

(ns storage-materializer-test
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing run-tests]]))

(def ^:private leaf-md
  (let [f (io/file (-> *file* io/file .getAbsoluteFile .getParentFile .getParentFile)
                   "references" "schemaless-events.md")]
    (delay (slurp f))))

(defn- block-containing
  "The single ```clojure block containing `needle`; nil when absent or
   duplicated, so a duplicate cannot pass a check its other copy fails."
  [needle]
  (let [hits (->> (re-seq #"(?s)```clojure\r?\n(.*?)```" @leaf-md)
                  (map second)
                  (filter #(str/includes? % needle)))]
    (when (= 1 (count hits)) (first hits))))

;; Both blocks register :session/rehydrate; each is found by what only it carries.
(def ^:private after-block  (delay (block-containing "rf/reg-cofx :session/stored")))
(def ^:private before-block (delay (block-containing "untrusted body read")))

(deftest after-block-is-total
  (let [block @after-block]
    (testing "ABSENT storage"
      (is (str/includes? block "(.-localStorage js/globalThis)")
          "the chain must start from the localStorage PROPERTY, so some-> can short-circuit")
      (is (not (str/includes? block "js/globalThis.localStorage"))
          "a method call on js/globalThis.localStorage throws on Node/SSR/headless hosts"))
    (testing "UNUSABLE storage"
      (is (str/includes? block "catch") "the JSON decode must be bounded by a catch"))
    (testing ":platforms is no substitute on a REQUIRED recordable cofx"
      (is (not (str/includes? block ":platforms"))
          "a platform-skipped generator produces no fact, so :session/rehydrate would take the missing-required path"))))

(deftest trust-and-replay-boundaries-survive
  (let [block @after-block]
    (is (str/includes? block ":recordable? true") "the generator is recordable, closing the replay hole")
    (is (str/includes? block "m/validate Session") "the always-on Malli gate is the trust boundary")
    (is (not (str/includes? block "goog.DEBUG")) "the trust gate must not be dev-elided")))

(deftest before-block-still-demonstrates-the-antipattern
  (is (str/includes? @before-block "js/globalThis.localStorage")
      "the Before block demonstrates the unguarded body read; 'fixing' it would erase the lesson"))

(deftest prose-distinguishes-absent-from-unusable
  (let [md @leaf-md]
    (is (re-find #"(?i)\*\*Absent\*\*" md) "the absent case must be named")
    (is (re-find #"(?i)\*\*Unusable\*\*" md) "the unusable case must be named")
    (is (str/includes? md ":rf/skip-handler?") "the consequence of a supplier throw must be stated")
    (is (str/includes? md ":rf.error/coeffect-exception") "the error the framework emits instead must be named")))

(let [{:keys [fail error]} (run-tests 'storage-materializer-test)]
  (System/exit (if (zero? (+ fail error)) 0 1)))
