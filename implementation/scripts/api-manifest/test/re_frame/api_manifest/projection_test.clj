(ns re-frame.api-manifest.projection-test
  "Regression tests for the projection non-vacuous floors (rf2-utvst).

  The vacuity class: a projection check reports OK whenever its problem
  list is empty — even if it reconciled ZERO references. A docs/skills
  directory move, a markdown table-shape change, an alias-convention
  change, or parser drift can silently drop the extracted-reference count
  toward 0 and turn the gate into a vacuous green: stale public-API
  references would no longer be reconciled against the manifest.

  These tests pin (1) `vacuity-floor-problem` synthesises a problem when
  `checked` is below the floor and nil otherwise, (2) `report-with-floor!`
  goes RED on a sub-floor count even with an empty problem list, and (3)
  `require-markdown-files` throws (rather than returning nil) for a missing
  directory so a moved/renamed surface fails loudly."
  (:require [clojure.test :refer [deftest is testing]]
            [re-frame.api-manifest.projection :as proj]))

(deftest vacuity-floor-problem-trips-below-floor
  (testing "checked below the floor synthesises a floor-violation problem"
    (let [p (proj/vacuity-floor-problem "skills/" 0 100)]
      (is (some? p))
      (is (= "(extractor)" (:file p)))
      (is (re-find #"below the non-vacuous floor of 100" (:detail p)))))
  (testing "checked just below the floor still trips"
    (is (some? (proj/vacuity-floor-problem "skills/" 99 100))))
  (testing "checked AT or ABOVE the floor does not trip"
    (is (nil? (proj/vacuity-floor-problem "skills/" 100 100)))
    (is (nil? (proj/vacuity-floor-problem "skills/" 505 100)))))

(deftest report-with-floor-goes-red-on-vacuous-empty
  (testing "an empty problem list with a sub-floor count is RED (the bug)"
    ;; The vacuous-green case: zero problems found because zero references
    ;; were extracted. Must be a FALSE verdict, not a vacuous OK.
    (is (false? (proj/report-with-floor! "skills/" 0 100 []))))
  (testing "an empty problem list ABOVE the floor is GREEN"
    (is (true? (proj/report-with-floor! "skills/" 505 100 []))))
  (testing "a real drift problem is RED regardless of the floor"
    (is (false? (proj/report-with-floor!
                  "skills/" 505 100
                  [{:file "skills/x.md" :line 1 :raw "rf/gone" :detail "no row"}])))))

(deftest require-markdown-files-throws-on-missing-dir
  (testing "a missing/renamed surface dir throws (does NOT return nil)"
    ;; An absolute path under the repo root, as real callers pass via
    ;; repo-file — a moved/renamed surface dir.
    (let [absent (proj/repo-file (str "definitely-missing-surface-"
                                      (System/currentTimeMillis)))]
      (is (thrown-with-msg?
            clojure.lang.ExceptionInfo
            #"expected directory is missing"
            (proj/require-markdown-files "skills/" absent)))))
  (testing "an existing dir returns its markdown files (delegates to markdown-files)"
    ;; The committed skills/ surface exists and carries many *.md files.
    (let [files (proj/require-markdown-files "skills/" (proj/repo-file "skills"))]
      (is (pos? (count files))))))
