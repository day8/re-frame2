;;;; tests/storage_materializer_test.clj — the canonical storage materializer
;;;; in references/schemaless-events.md must be TOTAL (rf2-1lv3).
;;;;
;;;; The leaf's "After" is a copyable canonical fix. Its `:doc` promises the
;;;; supplier yields nil when storage is absent or its contents unusable, but
;;;; the shape it shipped threw in both cases:
;;;;
;;;;   (some-> (.getItem js/globalThis.localStorage "session") ...)
;;;;
;;;; `some->` can only short-circuit AFTER its first expression returns, so on
;;;; a host with no `localStorage` (Node, SSR, headless CI) the method call
;;;; throws before any nil test happens; a corrupt entry likewise throws out of
;;;; `js/JSON.parse` before `m/validate` runs. Neither is the advertised nil.
;;;; The framework catches a recordable generator's throw, emits
;;;; `:rf.error/coeffect-exception` and sets `:rf/skip-handler?`, so the
;;;; declaring `:session/rehydrate` never runs — a boot step that silently did
;;;; not happen, which is harder to diagnose than the nil the handler already
;;;; handles. Hence: total materializer, property lookup first, decode bounded
;;;; by a catch.
;;;;
;;;; This is a structural fixture over the DOCUMENT, not a CLJS runtime test:
;;;; no gate in this repository executes fenced CLJS, so what is pinned here is
;;;; the shape of the snippet an agent copies. Restoring the direct
;;;; `.getItem js/globalThis.localStorage` form in the "After" block fails
;;;; `after-block-is-total` while the Before-block control keeps passing (the
;;;; non-vacuity criterion).
;;;;
;;;; Run: bb tests/storage_materializer_test.clj  (from skills/re-frame2-improver/)
;;;; Exit: 0 = pass, non-zero = fail.

(ns storage-materializer-test
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing run-tests]]))

;; ---------------------------------------------------------------------------
;; Filesystem helpers
;; ---------------------------------------------------------------------------

(def ^:private skill-root
  (-> *file*
      (io/file)
      (.getAbsoluteFile)
      (.getParentFile)   ;; tests/
      (.getParentFile))) ;; skills/re-frame2-improver/

(def ^:private leaf-md
  (delay (slurp (io/file skill-root "references" "schemaless-events.md"))))

;; ---------------------------------------------------------------------------
;; Fenced-block extraction
;; ---------------------------------------------------------------------------
;;
;; Both the Before and the After block register `:session/rehydrate`, so the
;; blocks are told apart by what is UNIQUE to each: the After block registers
;; the `:session/stored` cofx; the Before block carries the body-read marker.

(defn- clojure-blocks
  "Every ```clojure fenced block in `md`, fence lines excluded."
  [md]
  (->> (re-seq #"(?s)```clojure\r?\n(.*?)```" md)
       (map second)))

(defn- block-containing
  "The single fenced block containing `needle`. Nil when absent, and
  deliberately nil when ambiguous — a silently-picked first match would let a
  duplicated block pass a check the other copy fails."
  [needle]
  (let [hits (filter #(str/includes? % needle) (clojure-blocks @leaf-md))]
    (when (= 1 (count hits)) (first hits))))

(def ^:private after-block  (delay (block-containing "rf/reg-cofx :session/stored")))
(def ^:private before-block (delay (block-containing "untrusted body read")))

;; ---------------------------------------------------------------------------
;; The materializer is total over absent AND unusable storage
;; ---------------------------------------------------------------------------

(deftest after-block-is-found
  (testing "exactly one fenced block registers the :session/stored cofx"
    (is (some? @after-block)
        "the canonical After block is missing, renamed, or duplicated")))

(deftest after-block-is-total
  (let [block @after-block]
    (testing "ABSENT storage: the property lookup is the first link, so some-> can short-circuit"
      (is (str/includes? block "(.-localStorage js/globalThis)")
          "the chain must start from the localStorage PROPERTY, not a method call on it"))
    (testing "ABSENT storage: the direct-method form is gone from the canonical fix"
      (is (not (str/includes? block "js/globalThis.localStorage"))
          (str "`.getItem js/globalThis.localStorage` throws on Node/SSR/headless hosts "
               "before some-> can test anything — it is the defect rf2-1lv3 records")))
    (testing "UNUSABLE storage: JSON decoding is bounded, so a corrupt entry cannot escape as a supplier throw"
      (is (str/includes? block "catch")
          "js/JSON.parse throws on a corrupt entry; the decode boundary must catch it"))))

;; ---------------------------------------------------------------------------
;; Totality must not have been bought by weakening the leaf's two lessons
;; ---------------------------------------------------------------------------

(deftest trust-and-replay-boundaries-survive
  (let [block @after-block]
    (testing "replay boundary: the generator is still recordable"
      (is (str/includes? block ":recordable? true")
          "a durable write folds a RECORDED fact; ambient would reintroduce the replay hole"))
    (testing "trust boundary: validation is still always-on"
      (is (str/includes? block "m/validate Session")
          "the always-on Malli gate is the trust boundary")
      (is (not (str/includes? block "goog.DEBUG"))
          "the trust gate must not be dev-elided — that is the anti-pattern this leaf exists to flag"))))

(deftest platforms-is-not-offered-as-the-guard
  (testing ":platforms is not a substitute for a total materializer on a REQUIRED recordable cofx"
    (is (not (str/includes? @after-block ":platforms"))
        (str "a platform-skipped generator produces no fact at all, so a universally-dispatched "
             ":session/rehydrate would follow the missing-required path rather than the promised nil path"))))

;; ---------------------------------------------------------------------------
;; Non-vacuity control — the Before block must still exhibit the anti-pattern
;; ---------------------------------------------------------------------------

(deftest before-block-still-demonstrates-the-antipattern
  (testing "the Before example still reads localStorage mid-body (it is the finding being taught)"
    (is (some? @before-block)
        "the Before block is missing or duplicated")
    (is (str/includes? @before-block "js/globalThis.localStorage")
        "the Before block demonstrates the unguarded body read; 'fixing' it would erase the lesson")))

;; ---------------------------------------------------------------------------
;; The prose settles absent vs unusable rather than collapsing them
;; ---------------------------------------------------------------------------

(deftest prose-distinguishes-absent-from-unusable
  (let [md @leaf-md]
    (testing "both conditions are named and given their own reason"
      (is (re-find #"(?i)\*\*Absent\*\*" md)   "the absent case must be named")
      (is (re-find #"(?i)\*\*Unusable\*\*" md) "the unusable case must be named"))
    (testing "the prose says why a supplier throw is not the way to fail loudly"
      (is (str/includes? md ":rf/skip-handler?")
          "the consequence of a supplier throw — the handler never runs — must be stated")
      (is (str/includes? md ":rf.error/coeffect-exception")
          "the emitted error the framework substitutes for the promised nil must be named"))))

;; ---------------------------------------------------------------------------
;; Run
;; ---------------------------------------------------------------------------

(let [{:keys [fail error]} (run-tests 'storage-materializer-test)]
  (System/exit (if (and (zero? fail) (zero? error)) 0 1)))
