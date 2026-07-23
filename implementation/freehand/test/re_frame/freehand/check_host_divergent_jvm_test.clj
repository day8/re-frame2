(ns re-frame.freehand.check-host-divergent-jvm-test
  "The checker must answer for the branch the BROWSER build compiles.

  A `.cljc` view whose `#?(:clj … :cljs …)` branches diverge compiles a
  different branch per target, and the JVM reader elides to `:clj` — so a
  checker that read only the JVM's branch would report a view eligible
  from a branch nobody ships to the browser while the `:cljs` branch it
  DOES ship is outside the grammar. That is a false-green in a tool whose
  entire job is to answer eligibility before an author edits, and this
  suite pins that it cannot happen:

    1. `host-divergent` — `:clj` branch inside the grammar, `:cljs` branch
       a dynamic head — is reported NOT eligible, with the finding naming
       the CLJS-branch form (proof the `:cljs` branch was actually
       analyzed, not the `:clj` one the reader would have surfaced);
    2. `host-uniform` — both branches literal elements, read differently —
       stays eligible, so reading both branches cannot manufacture a false
       NO;
    3. the fixture is byte-identical after a run (the read-only law holds
       for the preserve-and-select read path too)."
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [re-frame.freehand.check-host-divergent-views]
            [re-frame.freehand.compiler.check :as check]))

(def ^:private fixture-path
  (.getPath (io/file (io/resource "re_frame/freehand/check_host_divergent_views.cljc"))))

(defn- report-for [view-id reports]
  (first (filter #(= view-id (:view-id %)) reports)))

(deftest the-cljs-branch-is-what-is-checked
  (let [reports (check/check-file fixture-path)]
    (is (= [:re-frame.freehand.check-host-divergent-views/host-divergent
            :re-frame.freehand.check-host-divergent-views/host-uniform]
           (mapv :view-id reports))
        "one report per declaration, in declaration order")

    (testing "the :cljs branch's dynamic head makes the view ineligible, and
              the finding names the CLJS-branch form the JVM reader elides —
              so the report can only be right by having analyzed that branch"
      (is (= {:view-id           :re-frame.freehand.check-host-divergent-views/host-divergent
              :source            {:file fixture-path :line 25 :column 1}
              :current-lowering  :interpreted
              :target-grammar    :re-frame.freehand/v1
              :compile-eligible? false
              :findings          [{:id       :rf.ui.compile/dynamic-head
                                   :source   {:line 25 :column 1}
                                   :form     '[tag {} "the CLJS branch has a dynamic head - ineligible"]
                                   :reason   :head-is-a-runtime-value
                                   :recovery [:use-a-literal-head
                                              :extract-declared-child
                                              :keep-interpreted]}]}
             (report-for :re-frame.freehand.check-host-divergent-views/host-divergent reports))))

    (testing "a view whose branches are BOTH inside the grammar stays
              eligible, even though the two branches are not textually equal —
              reading both branches never invents a refusal"
      (is (= {:view-id           :re-frame.freehand.check-host-divergent-views/host-uniform
              :source            {:file fixture-path :line 32 :column 1}
              :current-lowering  :interpreted
              :target-grammar    :re-frame.freehand/v1
              :compile-eligible? true
              :findings          []}
             (report-for :re-frame.freehand.check-host-divergent-views/host-uniform reports))))))

(deftest the-finding-is-the-cljs-form-not-the-clj-one
  (testing "non-vacuity: the offending form is the :cljs branch's template,
            never the :clj branch's `[:div …]` — a checker that had analyzed
            the elided-to `:clj` branch (the pre-fix behaviour) would report
            this view eligible with no findings at all"
    (let [report (report-for :re-frame.freehand.check-host-divergent-views/host-divergent
                             (check/check-file fixture-path))
          form   (-> report :findings first :form)]
      (is (false? (:compile-eligible? report)))
      (is (= '[tag {} "the CLJS branch has a dynamic head - ineligible"] form))
      (is (not= :div (first form))
          "the reported head is the CLJS dynamic head, not the CLJ literal element"))))

(deftest the-checker-never-writes
  (testing "the preserve-and-select read path is still read-only — the source
            it was pointed at is byte-identical after a run"
    (let [before (java.nio.file.Files/readAllBytes (.toPath (io/file fixture-path)))
          _      (check/check-file fixture-path)
          after  (java.nio.file.Files/readAllBytes (.toPath (io/file fixture-path)))]
      (is (pos? (alength before)) "the fixture file is not empty")
      (is (java.util.Arrays/equals ^bytes before ^bytes after)))))
