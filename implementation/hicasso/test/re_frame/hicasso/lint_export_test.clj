(ns re-frame.hicasso.lint-export-test
  "THE LINT EXPORT'S WITNESS (rf2-hic-022).

  A lint rule with no test is a rumour. Every check the export ships is
  asserted here twice:

  - a POSITIVE case in `lint-fixtures/positive.cljs` that must trip it;
  - a NEGATIVE case in `lint-fixtures/negative.cljs` — correct code that
    RESEMBLES the mistake — which must not.

  The negative half is the one that decides whether the layer is worth
  shipping, so it is asserted as a whole-file claim rather than per rule:
  the negative fixture must produce NO finding of ANY kind, including
  kondo's own. It has already earned that: it caught the element checks
  reading the event vector `[:a]` at `:on-click` as an unnamed anchor.

  ## What this runs

  clj-kondo itself, in process, over the SHIPPED export directory as its
  `:config-dir` — which is exactly how a consumer's copied config is
  loaded. There is no second description of the rules here and no mock:
  a rule that works in this suite is the rule the artefact publishes.

  `:cache false` matters. clj-kondo otherwise writes an analysis cache
  into whatever directory `:config-dir` names, which here is the shipped
  artefact — generated files inside the thing a consumer copies."
  (:require [clj-kondo.core :as kondo]
            [clojure.java.io :as io]
            [clojure.set :as set]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]))

;; ---------------------------------------------------------------------------
;; Running the real linter over the real export
;; ---------------------------------------------------------------------------

(def ^:private artefact-root
  "This artefact's root, found from the classpath rather than from the
  process's working directory: `clojure -M:test` runs from
  `implementation/hicasso`, but a rigorous sweep or an editor may not."
  (-> (io/resource "re_frame/hicasso.cljc")
      io/file .getParentFile .getParentFile .getParentFile))

(defn- artefact-path
  [& segments]
  (str (apply io/file artefact-root segments)))

(def ^:private export-dir
  (artefact-path "resources" "clj-kondo.exports" "day8" "re-frame2-hicasso"))

(defn- lint
  "Every finding clj-kondo reports for `path`, under the shipped export."
  [path]
  (:findings
    (kondo/run! {:lint       [path]
                 :config-dir export-dir
                 :cache      false})))

(defn- findings-by-type
  [path]
  (reduce (fn [acc {:keys [type row]}]
            (update acc type (fnil conj #{}) row))
          {}
          (lint path)))

(def ^:private hicasso-checks
  "Every check the export ships. The roster is asserted against the
  config's own `:linters` map below, so a check added there without a
  fixture cannot ride in unwitnessed."
  #{:re-frame.hicasso/merge-not-a-map
    :re-frame.hicasso/deferred-read
    :re-frame.hicasso/function-in-head-position
    :re-frame.hicasso/parked-read
    :re-frame.hicasso/unkeyed-mapped-child
    :re-frame.hicasso/nameless-interactive-element})

;; ---------------------------------------------------------------------------
;; The export is a real, loadable clj-kondo export
;; ---------------------------------------------------------------------------

(deftest export-packaging-test
  (testing "the export sits where clj-kondo's --copy-configs expects it"
    (is (.isFile (io/file export-dir "config.edn")))
    (is (.isFile (io/file export-dir "hooks" "re_frame" "hicasso.clj")))
    (is (.isFile (io/file export-dir "README.md"))))

  (testing "resources/ is on the artefact's :paths, or nothing is exported"
    ;; The export reaches a consumer over the CLASSPATH. An export directory
    ;; that is not on `:paths` is invisible to `--copy-configs`, and the
    ;; failure mode is silence rather than an error.
    (let [deps (read-string (slurp (artefact-path "deps.edn")))]
      (is (contains? (set (:paths deps)) "resources")
          "implementation/hicasso/deps.edn must put resources/ on :paths")))

  (testing "every configured check has a fixture in this suite"
    (let [configured (-> (io/file export-dir "config.edn") slurp read-string
                         :linters keys set)]
      (is (= hicasso-checks configured)
          (str "the export's :linters roster and this suite's roster have "
               "diverged; a check with no fixture is a rumour")))))

;; ---------------------------------------------------------------------------
;; Positive — each check fires on the form it was written for
;; ---------------------------------------------------------------------------

(deftest positive-fixtures-test
  (let [by-type (findings-by-type (artefact-path "lint-fixtures" "positive.cljs"))]

    (testing "every check fires at least once"
      (is (= hicasso-checks
             (set/intersection hicasso-checks (set (keys by-type))))
          (str "checks that did not fire: "
               (pr-str (set/difference hicasso-checks (set (keys by-type)))))))

    (testing ":& carrying a literal non-map"
      (is (= #{23 26 29} (by-type :re-frame.hicasso/merge-not-a-map))
          "a vector, a string and a keyword"))

    (testing "a read inside the one callback form"
      (is (= #{36 41} (by-type :re-frame.hicasso/deferred-read))
          "h/sub and h/use-subs alike"))

    (testing "a read parked in a mutable reference (rf2-djxr)"
      (is (= #{51 55 59} (by-type :re-frame.hicasso/parked-read))
          "reset! of a delay, reset! of a closure, vreset! of a delay"))

    (testing "mapped children with no :key"
      (is (= #{67 72 75 78} (by-type :re-frame.hicasso/unkeyed-mapped-child))
          "for with props, for provably without props, map and mapv"))

    (testing "an interactive element with nothing to name it"
      (is (= #{85 88 91} (by-type :re-frame.hicasso/nameless-interactive-element))
          "a button, an anchor, and a button wearing a selector"))

    (testing "a function literal where a head belongs"
      (is (= #{98 101 104} (by-type :re-frame.hicasso/function-in-head-position))
          "(fn …), #(…) and (hfn …)"))))

;; ---------------------------------------------------------------------------
;; Negative — correct code that resembles each mistake stays silent
;; ---------------------------------------------------------------------------

(deftest negative-fixtures-test
  (let [findings (lint (artefact-path "lint-fixtures" "negative.cljs"))]
    (testing "correct Hicasso produces NO finding of any kind"
      ;; Not merely none of ours. The shape rewrites are part of the export's
      ;; job, so an `Unresolved symbol` here would mean a view's props stopped
      ;; resolving — a regression in the half of the export nobody thinks to
      ;; test.
      (is (empty? findings)
          (str "the negative fixture must be silent; got:\n"
               (str/join "\n" (map #(str "  " (:row %) ":" (:col %) " "
                                         (:type %) " " (:message %))
                                   findings)))))))

;; ---------------------------------------------------------------------------
;; The corpus — the acceptance claim, on code nobody wrote to pass
;; ---------------------------------------------------------------------------

(deftest real-corpus-is-quiet-test
  (testing "the export stays silent on the artefact's own testbeds"
    ;; The bead's acceptance names "the tiny consumer app". Until one exists
    ;; (rf2-hic-008), the artefact's testbeds are the closest real thing: an
    ;; ordinary Hicasso application, written before any of these checks did,
    ;; by somebody who was not trying to pass them.
    (let [ours (into #{}
                     (comp (map :type) (filter hicasso-checks))
                     (lint (artefact-path "testbed")))]
      (is (empty? ours)
          (str "the lint layer fires on the artefact's own testbed code: "
               (pr-str ours))))))
