(ns re-frame.freehand.check-jvm-test
  "The read-only compile checker, proven where it runs: the JVM.

  Three things are asserted, and the first two are asserted WHOLE. A
  checker whose report was pinned key by key would agree with a report
  that grew a key nobody decided on, and the report is a tool contract —
  so the expectations here are complete maps, not `select-keys` of them.

    1. the FH-DIAG-001 conformance table — a declaration in, a whole
       report out, across the eligible case and six refusals;
    2. the file workflow — point `check-file` at a real source file and
       read one whole report per declaration in it;
    3. read-only — that file is byte-identical after the run.

  Head classification is resolution, so the fixture table's bodies
  resolve against THIS namespace: `map` and `for` are core here, and
  `person-item` / `nope-not-a-thing` are nothing at all."
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [re-frame.freehand.check-fixture-views]
            [re-frame.freehand.compiler.check :as check]
            [re-frame.freehand.compiler.grammar :as grammar]
            [re-frame.freehand.conformance :as conformance]))

;; ---------------------------------------------------------------------------
;; FH-DIAG-001 — the report, whole, per declaration
;; ---------------------------------------------------------------------------

(def ^:private diag-001 (conformance/fixture :FH-DIAG-001))

(deftest fh-diag-001-the-checker-report
  (let [{:keys [params cases]} diag-001]
    (is (= 7 (count cases)) "the fixture table is populated")
    (doseq [{:keys [note view-id source compiled? body report]} cases]
      (testing note
        (is (= report
               (check/check-declaration
                 {:view-id         view-id
                  :vname           (symbol (name view-id))
                  :ns-sym          (ns-name *ns*)
                  :params          params
                  :body            body
                  :compiled?       compiled?
                  :children-policy :optional
                  :source          source}))
            (str view-id " — the whole report"))))))

(deftest every-finding-carries-a-usable-ladder
  (testing "a recovery list is the payload, so it is never empty, always ends
            in the rung that is always available, and every rung names a
            sentence in the closed roster"
    (doseq [{:keys [report]} (:cases diag-001)
            {:keys [id recovery]} (:findings report)]
      (is (seq recovery) (str id " offers at least one recovery"))
      (is (= :keep-interpreted (last recovery))
          (str id " ends in the rung that is always available"))
      (doseq [rung recovery]
        (is (string? (get grammar/recoveries rung))
            (str id " — " rung " is a rung of the closed roster"))))))

;; ---------------------------------------------------------------------------
;; The file workflow, and the read-only law
;; ---------------------------------------------------------------------------

(def ^:private fixture-views-path
  "The checker's subject: a real file of ordinary interpreted
  declarations, located through the classpath so no path is written down."
  (.getPath (io/file (io/resource "re_frame/freehand/check_fixture_views.cljc"))))

(defn- report-for [view-id reports]
  (first (filter #(= view-id (:view-id %)) reports)))

(deftest check-file-reports-one-view-at-a-time
  (let [reports (check/check-file fixture-views-path)]
    (is (= [:re-frame.freehand.check-fixture-views/row
            :re-frame.freehand.check-fixture-views/roster]
           (mapv :view-id reports))
        "one report per declaration, in declaration order, and the plain
         `defn` helper beside them is not a declaration")

    (testing "a view already inside the grammar is eligible, with nothing to say"
      (is (= {:view-id           :re-frame.freehand.check-fixture-views/row
              :source            {:file fixture-views-path :line 26 :column 1}
              :current-lowering  :interpreted
              :target-grammar    :re-frame.freehand/v1
              :compile-eligible? true
              :findings          []}
             (report-for :re-frame.freehand.check-fixture-views/row reports))))

    (testing "a view whose markup a helper manufactures is not, and says why
              and what to do — located at the helper call, not the declaration"
      (is (= {:view-id           :re-frame.freehand.check-fixture-views/roster
              :source            {:file fixture-views-path :line 31 :column 1}
              :current-lowering  :interpreted
              :target-grammar    :re-frame.freehand/v1
              :compile-eligible? false
              :findings          [{:id       :rf.ui.compile/markup-returning-map
                                   :source   {:line 35 :column 4}
                                   :form     '(map person-item people)
                                   :reason   :markup-hidden-from-analyzer
                                   :recovery [:make-template-visible
                                              :extract-declared-child
                                              :keep-interpreted]}]}
             (report-for :re-frame.freehand.check-fixture-views/roster reports))))))

(deftest the-checker-never-writes
  (testing "the source it was pointed at is byte-identical after a run —
            changing the declaration is the author's final step, never the
            checker's discovery mechanism"
    (let [before (java.nio.file.Files/readAllBytes (.toPath (io/file fixture-views-path)))
          _      (check/check-file fixture-views-path)
          after  (java.nio.file.Files/readAllBytes (.toPath (io/file fixture-views-path)))]
      (is (pos? (alength before)) "the fixture file is not empty")
      (is (java.util.Arrays/equals ^bytes before ^bytes after)))))

;; ---------------------------------------------------------------------------
;; The two ways to be pointed at something that cannot be checked
;; ---------------------------------------------------------------------------

(deftest a-file-that-cannot-be-checked-says-so
  (testing "no (ns …) form means no namespace to resolve heads against"
    (let [tmp (java.io.File/createTempFile "fh-check" ".cljc")]
      (try
        (spit tmp "(def x 1)\n")
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"does not begin with an \(ns"
                              (check/check-file (.getPath tmp))))
        (finally (.delete tmp)))))

  (testing "an unloaded namespace is refused rather than reported as a wall of
            unresolved heads — the failure would be about the checker"
    (let [tmp (java.io.File/createTempFile "fh-check" ".cljc")]
      (try
        (spit tmp "(ns re-frame.freehand.check-no-such-namespace)\n")
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"is not loaded"
                              (check/check-file (.getPath tmp))))
        (finally (.delete tmp))))))
