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

;; ---------------------------------------------------------------------------
;; EP-0017 keyword-drift guard (rf2-tawage).
;;
;; The projection checks scope to public-VAR references and exclude the
;; `:rf/*` keyword namespace, so a surface could reintroduce stale EP-0017
;; keyword vocabulary (`:rf.world/inputs`) and stay green. The keyword guard
;; flags a `:rf.world/inputs` mention NOT accompanied on its line by a
;; retirement/rename/migration marker.
;; ---------------------------------------------------------------------------

(deftest keyword-drift-flags-bare-stale-mention
  (testing "a fresh :rf.world/inputs mention with no retirement marker is flagged"
    (let [probs (proj/ep0017-keyword-drift-problems
                  "docs/guide/x.md"
                  [[10 "Supply recordable facts under :rf.world/inputs on dispatch."]])]
      (is (= 1 (count probs)))
      (is (= 10 (:line (first probs))))
      (is (= "docs/guide/x.md" (:file (first probs))))
      (is (re-find #"renamed to the flat :rf.cofx" (:detail (first probs)))))))

(deftest keyword-drift-allows-rename-mention-naming-replacement
  (testing "a :rf.world/inputs line that names the :rf.cofx replacement is approved"
    (is (empty? (proj/ep0017-keyword-drift-problems
                  "spec/Spec-Schemas.md"
                  [[5 "The EP-0010 :rf.world/inputs field is renamed to :rf.cofx (EP-0017)."]])))))

(deftest keyword-drift-allows-error-id-and-prose-markers
  (testing "the :rf.error/world-inputs-renamed error id marks a legitimate mention"
    (is (empty? (proj/ep0017-keyword-drift-problems
                  "spec/002-Frames.md"
                  [[1 "Supplying :rf.world/inputs is a hard error :rf.error/world-inputs-renamed."]]))))
  (testing "the prose words retired/renamed/migrat each mark a legitimate mention"
    (is (empty? (proj/ep0017-keyword-drift-problems
                  "x.md"
                  [[1 "The :rf.world/inputs key is retired."]
                   [2 "A migration mention of :rf.world/inputs."]])))))

(deftest keyword-drift-ignores-lines-without-the-keyword
  (testing "lines that do not mention :rf.world/inputs produce no problems"
    (is (empty? (proj/ep0017-keyword-drift-problems
                  "x.md"
                  [[1 "Declare coeffects via :rf.cofx/requires; values arrive flat."]
                   [2 "The :rf.cofx envelope map is the one nested record."]])))))

(deftest keyword-drift-over-files-tags-repo-relative-file
  (testing "the file-driver scans committed surfaces and flags zero on the
            live tree (every committed :rf.world/inputs mention is a
            retirement/rename reference)"
    ;; The committed docs/guide/ + skills/ surfaces carry only
    ;; retirement/rename mentions, so the live scan must be clean — a
    ;; regression catches a fresh reintroduction.
    (let [guide-files (proj/markdown-files (proj/repo-file "docs" "guide"))]
      (is (pos? (count guide-files)))
      (is (empty? (proj/keyword-drift-problems-over-files guide-files))
          "live drift: a docs/guide file reintroduced stale :rf.world/inputs"))))
