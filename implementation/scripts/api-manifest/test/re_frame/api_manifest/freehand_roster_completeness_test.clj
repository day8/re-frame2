(ns re-frame.api-manifest.freehand-roster-completeness-test
  "The negative control for the Freehand roster-completeness gate (rf2-o8xev).

  THE HOLE THE GATE CLOSES. `gen/jvm-namespaces` is an EXPLICIT roster, and
  `doc_api_check` derives ITS namespace roster from the manifest rows that
  roster produces. A namespace absent from it is therefore not UNCLASSIFIED —
  it is UNSCANNED: `gen --check` passes, no documentation-coverage check
  reaches it, and every public var in it is invisible to every manifest-derived
  gate at once. `re-frame.freehand.splitter` shipped that way with fourteen
  public names (rf2-h0b0l) and `re-frame.freehand.collection` with seven
  (rf2-cfhuv), each on a green board.

  WHY THESE TESTS EXIST IN THIS SHAPE. Both of those were repaired by ADDING
  the missing namespace — a point-in-time sweep that left the roster exactly as
  unable to notice the next one. So the thing under test here is not the
  roster's current contents (which the positive control checks almost
  incidentally); it is whether the gate BITES. A completeness gate that has
  never been watched failing is decoration, and the specific failure mode it
  must not have is the one its predecessors had: passing because it looked at
  nothing. Hence the non-vacuity assertions, the missing-root assertion, and a
  synthetic namespace driven all the way through `build-manifest` rather than
  through the pure reconciler alone."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [re-frame.api-manifest.gen :as gen])
  (:import [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

;; ---------------------------------------------------------------------------
;; A synthetic source tree.
;; ---------------------------------------------------------------------------

(defn- temp-source-tree
  "Create a throwaway source root containing `rel-paths` (each a `/`-joined
   path relative to the root) and return it as a File. Every file is empty —
   the scanner reads PATHS, never contents, so a body would be noise."
  ^java.io.File [rel-paths]
  (let [root (.toFile (Files/createTempDirectory "fh-roster" (into-array FileAttribute [])))]
    (.deleteOnExit root)
    (doseq [p rel-paths
            :let [f (io/file root p)]]
      (io/make-parents f)
      (spit f "")
      (.deleteOnExit f))
    root))

(def ^:private synthetic-ns
  "The namespace a newly shipped, unclassified Freehand sibling presents."
  're-frame.freehand.synthetic-probe)

;; ---------------------------------------------------------------------------
;; Non-vacuity — the assertions below must be about something.
;; ---------------------------------------------------------------------------

(deftest the-scanner-finds-the-real-freehand-tree
  (testing "the live scan resolves and returns a substantial namespace set —
            without this, every reconciliation below would be a set difference
            against the empty set, which is the exact way the predecessor gates
            stayed green"
    (let [present (gen/namespaces-under @gen/freehand-source-root)]
      (is (< 50 (count present))
          "the Freehand source tree carries dozens of namespaces")
      (is (contains? present 're-frame.freehand)
          "the ONE public door, from implementation/freehand/src/re_frame/freehand.cljc")
      (is (contains? present 're-frame.freehand.compiler.analyze)
          "a nested internal, proving the walk descends past the first level")
      (is (contains? present 're-frame.freehand.presence-runtime)
          "an underscored filename, proving the `_` -> `-` munge is reversed"))))

(deftest a-missing-source-root-is-loud-not-green
  (testing "a source root that does not exist THROWS rather than yielding an
            empty set — a completeness gate that quietly finds nothing is the
            defect it exists to prevent"
    (is (thrown-with-msg?
          clojure.lang.ExceptionInfo
          #"Freehand source root not found"
          (gen/namespaces-under (io/file "no" "such" "tree"))))))

(deftest the-scanner-maps-files-to-namespaces
  (testing "each source extension yields its namespace symbol, and non-source
            files are ignored"
    (let [root (temp-source-tree ["re_frame/freehand.cljc"
                                  "re_frame/freehand/synthetic_probe.cljc"
                                  "re_frame/freehand/compiler/emit_jvm.clj"
                                  "re_frame/freehand/root.cljs"
                                  "re_frame/freehand/README.md"
                                  "re_frame/freehand/notes.txt"])]
      (is (= '#{re-frame.freehand
                re-frame.freehand.synthetic-probe
                re-frame.freehand.compiler.emit-jvm
                re-frame.freehand.root}
             (set (gen/namespaces-under root)))))))

;; ---------------------------------------------------------------------------
;; The positive control — the committed rosters account for the live tree.
;; ---------------------------------------------------------------------------

(deftest the-live-tree-is-fully-accounted-for
  (testing "every Freehand source namespace is named by exactly one roster:
            enrolled in `jvm-namespaces` (supported public surface) or recorded
            in `freehand-internal-namespaces` (plumbing). This is the assertion
            the two point-in-time repairs each satisfied once and then stopped
            checking"
    (let [{:keys [unaccounted stale contradictory]}
          (gen/freehand-roster-drift (gen/namespaces-under @gen/freehand-source-root))]
      (is (= [] unaccounted) "no source namespace is unclassified")
      (is (= [] stale) "no internal-roster entry names a namespace that is gone")
      (is (= [] contradictory) "no namespace is claimed as both public and internal"))))

(deftest the-enrolled-freehand-roster-matches-the-conventions-roster
  (testing "the generator's enrolled Freehand namespaces are exactly the door
            plus the six sanctioned siblings that spec/Conventions.md §Freehand
            holds as the roster of record. A sibling landing in code without
            reaching that prose is precisely what rf2-h0b0l and rf2-cfhuv were"
    (is (= '#{re-frame.freehand
              re-frame.freehand.test
              re-frame.freehand.tool
              re-frame.freehand.form
              re-frame.freehand.controls
              re-frame.freehand.splitter
              re-frame.freehand.collection}
           (into #{}
                 (filter #(str/starts-with? (name %) "re-frame.freehand"))
                 gen/jvm-namespaces)))))

;; ---------------------------------------------------------------------------
;; The negative controls — all three arms.
;; ---------------------------------------------------------------------------

(deftest an-unclassified-namespace-turns-the-gate-red
  (testing "a Freehand source namespace named by neither roster fails, names
            itself, and offers both remediation paths"
    (let [present (conj (gen/namespaces-under @gen/freehand-source-root) synthetic-ns)]
      (is (thrown-with-msg?
            clojure.lang.ExceptionInfo
            #"Unaccounted Freehand source namespace"
            (gen/assert-freehand-roster-complete! present)))
      (try
        (gen/assert-freehand-roster-complete! present)
        (is false "expected assert-freehand-roster-complete! to throw")
        (catch clojure.lang.ExceptionInfo e
          (is (= [synthetic-ns] (:unaccounted (ex-data e)))
              "the diagnostic carries the namespace, not just a count")
          (is (str/includes? (.getMessage e) (str synthetic-ns))
              "and NAMES it in the human message")
          (is (str/includes? (.getMessage e) "jvm-namespaces")
              "remediation (a): enrol it as a supported sibling")
          (is (str/includes? (.getMessage e) "freehand-internal-namespaces")
              "remediation (b): record it as internal"))))))

(deftest a-deleted-internal-namespace-turns-the-gate-red
  (testing "the internal roster rots the same way the sidecar does — an entry
            whose source file is gone must be removed, not left to accumulate"
    (let [present (disj (gen/namespaces-under @gen/freehand-source-root)
                        're-frame.freehand.descriptor)]
      (is (thrown-with-msg?
            clojure.lang.ExceptionInfo
            #"Stale `freehand-internal-namespaces` entries"
            (gen/assert-freehand-roster-complete! present)))
      (try
        (gen/assert-freehand-roster-complete! present)
        (is false "expected assert-freehand-roster-complete! to throw")
        (catch clojure.lang.ExceptionInfo e
          (is (= ['re-frame.freehand.descriptor] (:stale (ex-data e)))))))))

(deftest a-namespace-in-both-rosters-turns-the-gate-red
  (testing "claiming a namespace as public AND internal is a contradiction
            rather than a classification, so it fails rather than resolving
            silently in favour of one side"
    (with-redefs [gen/freehand-internal-namespaces
                  (conj gen/freehand-internal-namespaces 're-frame.freehand.form)]
      (let [present (gen/namespaces-under @gen/freehand-source-root)]
        (is (thrown-with-msg?
              clojure.lang.ExceptionInfo
              #"Contradictory Freehand classification"
              (gen/assert-freehand-roster-complete! present)))))))

;; ---------------------------------------------------------------------------
;; End to end — the gate is wired into the artefact that CI runs.
;; ---------------------------------------------------------------------------

(deftest build-manifest-refuses-an-unclassified-namespace
  (testing "the reconciler is not merely correct in isolation: `build-manifest`
            — the function `gen --check`, `generate!` and every downstream
            projection check run through — refuses before it produces a single
            row. A synthetic source tree carrying one unclassified namespace is
            enough to red the whole manifest lane"
    (let [root (temp-source-tree ["re_frame/freehand.cljc"
                                  "re_frame/freehand/synthetic_probe.cljc"])]
      (with-redefs [gen/freehand-source-root (delay root)]
        (is (thrown-with-msg?
              clojure.lang.ExceptionInfo
              #"Unaccounted Freehand source namespace"
              (gen/build-manifest (gen/read-sidecar))))))))

(deftest build-manifest-passes-on-the-live-tree
  (testing "POSITIVE CONTROL: with the real source tree and the committed
            rosters, `build-manifest` still produces the manifest — so the reds
            above are the mutation talking rather than a standing failure"
    (is (some? (gen/build-manifest (gen/read-sidecar))))))
