(ns re-frame.story.ui.test-mode.visual-a11y-pure-cljs-test
  "JVM + CLJS pure-data tests for the visual + a11y check-RESULTS projection
  (tools/story/spec/021-Story-UI-Test-And-Evidence.md §4).

  The substantive UI is CLJS, but the projection the dedicated visual/a11y
  results component (`re-frame.story.ui.test-mode.visual-a11y-view`)
  consumes is `.cljc` + pure (`rf.story.ui.test-mode.pure/browser-result-rows` and friends), so
  the JVM test corpus pins the contract without booting Reagent.

  The browser-tier oracle records under test are exactly the shapes the run
  path produces via
  `re-frame.story.play.browser/eval-browser-assertion`:

  - `:rf.assert/a11y-structural` — `:actual` is the `{:rule :tag :detail}`
    issue vector from `browser/structural-issues`;
  - `:rf.assert/a11y` — `:actual` is the `{:id :impact :help}` axe vector;
  - `:rf.assert/visual-snapshot` — `:actual` is the content-hash snapshot
    identity, `:expected` the baseline;
  - any of the three can be `:cannot-run` (the headless / hiccup refusal)."
  (:require [clojure.test :refer [deftest is]]
            [re-frame.story.ui.test-mode.pure :as rf.story.ui.test-mode.pure]))

;; ---- axe-a11y-findings ---------------------------------------------------

;; A violation with no node target falls the locus back to the rule id, so
;; the finding still reads, and fabricates no selector.
(deftest axe-findings-project-id-impact-help
  (is (= [{:finding  "Elements must have sufficient colour contrast"
           :locus    "color-contrast"
           :selector nil
           :targets  []
           :impact   "serious"
           :rule     "color-contrast"}]
         (rf.story.ui.test-mode.pure/axe-a11y-findings
           {:actual [{:id "color-contrast" :impact "serious"
                      :help "Elements must have sufficient colour contrast"}]}))))

;; ---- browser-result-rows: selection + kinds -----------------------------

(deftest browser-rows-select-only-browser-tier-records
  (let [result {:assertions
                [{:assertion :rf.assert/path-equals :status :pass}
                 {:assertion :rf.assert/a11y-structural :status :pass
                  :count 0 :actual []}
                 {:assertion :rf.assert/no-warnings :status :pass}
                 {:assertion :rf.assert/a11y :status :cannot-run
                  :reason "axe needs a real browser"}
                 {:assertion :rf.assert/visual-snapshot :status :cannot-run
                  :reason "visual needs :pixels"}]}]
    (is (= [:a11y-structural :a11y :visual]
           (mapv :kind (rf.story.ui.test-mode.pure/browser-result-rows result))))))

;; ---- browser-result-rows: structural-a11y fail --------------------------

;; The structural tier walks an in-memory hiccup tree, not a DOM, so its
;; locus is the hiccup tag and it carries no selector.
(deftest browser-rows-structural-fail-carries-readable-findings
  (is (= {:assertion :rf.assert/a11y-structural
          :kind      :a11y-structural
          :status    :fail
          :reason    "1 structural a11y issue(s) in the rendered hiccup tree: img-missing-alt"
          :count     1
          :findings  [{:finding "image missing alt text"
                       :locus   "<img>"
                       :detail  "img element has no :alt attribute"
                       :rule    :img-missing-alt}]}
         (first (rf.story.ui.test-mode.pure/browser-result-rows
                  {:assertions
                   [{:assertion :rf.assert/a11y-structural
                     :status    :fail
                     :count     1
                     :reason    "1 structural a11y issue(s) in the rendered hiccup tree: img-missing-alt"
                     :actual    [{:rule :img-missing-alt :tag :img
                                  :detail "img element has no :alt attribute"}]}]})))))

;; ---- browser-result-rows: axe fail carries the selector source link -----

;; spec/021 §4 + §5: the selector recovered from the violation's
;; :nodes → :target is the finding's SOURCE LINK, so it is the locus.
(deftest browser-rows-axe-fail-carries-selector-source-link
  (is (= {:assertion :rf.assert/a11y
          :kind      :a11y
          :status    :fail
          :reason    "1 axe-core a11y violation(s)"
          :count     1
          :findings  [{:finding  "Images must have alternate text"
                       :locus    "main > img:nth-child(2)"
                       :selector "main > img:nth-child(2)"
                       :targets  ["main > img:nth-child(2)"]
                       :impact   "critical"
                       :rule     "image-alt"}]}
         (first (rf.story.ui.test-mode.pure/browser-result-rows
                  {:assertions
                   [{:assertion :rf.assert/a11y
                     :status    :fail
                     :count     1
                     :reason    "1 axe-core a11y violation(s)"
                     :actual    [{:id       "image-alt"
                                  :impact   "critical"
                                  :help     "Images must have alternate text"
                                  :selector "main > img:nth-child(2)"
                                  :targets  ["main > img:nth-child(2)"]}]}]})))))

;; ---- browser-result-rows: honest :cannot-run ----------------------------

;; spec/017 §:cannot-run — a check the runner could not attempt carries its
;; reason and is never a pass.
(deftest browser-rows-cannot-run-shows-reason-not-a-false-pass
  (is (= [{:assertion :rf.assert/a11y
           :kind      :a11y
           :status    :cannot-run
           :reason    "axe-style a11y requires a real browser (:a11y-engine)"
           :count     nil
           :findings  []}
          {:assertion :rf.assert/visual-snapshot
           :kind      :visual
           :status    :cannot-run
           :reason    "visual snapshot requires a real browser (:pixels)"
           :snapshot  nil
           :baseline  nil}]
         (rf.story.ui.test-mode.pure/browser-result-rows
           {:assertions
            [{:assertion   :rf.assert/a11y
              :status      :cannot-run
              :cannot-run? true
              :passed?     false
              :reason      "axe-style a11y requires a real browser (:a11y-engine)"}
             {:assertion   :rf.assert/visual-snapshot
              :status      :cannot-run
              :cannot-run? true
              :passed?     false
              :reason      "visual snapshot requires a real browser (:pixels)"}]}))))

;; ---- browser-result-rows: visual snapshot presentation ------------------

;; A keyword :expected (:rf.story/any-snapshot) is not a baseline; a
;; baseline-diffing snapshot presents both hashes.
(deftest browser-rows-visual-snapshot-presents-identity-and-baseline
  (is (= [["abc123" nil] ["new-hash" "base-hash"]]
         (mapv (juxt :snapshot :baseline)
               (rf.story.ui.test-mode.pure/browser-result-rows
                 {:assertions [{:assertion :rf.assert/visual-snapshot
                                :status    :pass
                                :actual    "abc123"
                                :expected  :rf.story/any-snapshot}
                               {:assertion :rf.assert/visual-snapshot
                                :status    :fail
                                :actual    "new-hash"
                                :expected  "base-hash"}]})))))

;; ---- browser-result-rows: :passed? fallback -----------------------------

(deftest browser-rows-fall-back-to-passed-when-status-unstamped
  (is (= :pass (:status (first (rf.story.ui.test-mode.pure/browser-result-rows
                                 {:assertions [{:assertion :rf.assert/a11y-structural
                                                :passed? true :actual []}]}))))))
