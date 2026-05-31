(ns re-frame.story.ui.test-mode.visual-a11y-pure-cljs-test
  "JVM + CLJS pure-data tests for the visual + a11y check-RESULTS projection
  (rf2-ba86n.15, tools/story/spec/021-Story-UI-Test-And-Evidence.md §4).

  The substantive UI is CLJS, but the projection the dedicated visual/a11y
  results component (`re-frame.story.ui.test-mode.visual-a11y-view`)
  consumes is `.cljc` + pure (`pure/browser-result-rows` and friends), so
  the JVM test corpus pins the contract without booting Reagent.

  The browser-tier oracle records under test are exactly the shapes the run
  path (post-#2484) produces via
  `re-frame.story.play.browser/eval-browser-assertion`:

  - `:rf.assert/a11y-structural` — `:actual` is the `{:rule :tag :detail}`
    issue vector from `browser/structural-issues`;
  - `:rf.assert/a11y` — `:actual` is the `{:id :impact :help}` axe vector;
  - `:rf.assert/visual-snapshot` — `:actual` is the content-hash snapshot
    identity, `:expected` the baseline;
  - any of the three can be `:cannot-run` (the headless / hiccup refusal)."
  (:require [clojure.test :refer [deftest is testing]]
            [re-frame.story.ui.test-mode.pure :as pure]))

;; ---- structural-a11y-findings -------------------------------------------

(deftest structural-findings-readable-finding-and-locus
  (testing "each issue projects to a {:finding :locus :detail :rule} row"
    (let [rec  {:assertion :rf.assert/a11y-structural
                :status    :fail
                :count     2
                :actual    [{:rule :img-missing-alt :tag :img
                             :detail "img element has no :alt attribute"}
                            {:rule :control-missing-name :tag :button
                             :detail "button control has no accessible name"}]}
          rows (pure/structural-a11y-findings rec)]
      (is (= 2 (count rows)))
      (is (= "image missing alt text" (:finding (first rows))))
      (is (= "<img>" (:locus (first rows))))
      (is (= :img-missing-alt (:rule (first rows))))
      (is (= "interactive control has no accessible name" (:finding (second rows))))
      (is (= "<button>" (:locus (second rows)))))))

(deftest structural-findings-unknown-rule-falls-back-readably
  (testing "an unknown rule kebab-spells rather than dropping the finding"
    (let [rows (pure/structural-a11y-findings
                 {:actual [{:rule :some-future-rule :tag :div :detail "d"}]})]
      (is (= "some future rule" (:finding (first rows))))
      (is (= "<div>" (:locus (first rows)))))))

(deftest structural-findings-empty-on-no-issues
  (is (= [] (pure/structural-a11y-findings {:actual []})))
  (is (= [] (pure/structural-a11y-findings {}))))

;; ---- axe-a11y-findings ---------------------------------------------------

(deftest axe-findings-project-id-impact-help
  (let [rows (pure/axe-a11y-findings
               {:actual [{:id "color-contrast" :impact "serious"
                          :help "Elements must have sufficient colour contrast"}]})]
    (is (= 1 (count rows)))
    (is (= "Elements must have sufficient colour contrast" (:finding (first rows))))
    ;; rf2-ffu8t — a violation with no node target falls the locus back to the
    ;; rule id so the finding still reads; no fabricated selector.
    (is (= "color-contrast" (:locus (first rows))))
    (is (nil? (:selector (first rows))) "no selector fabricated when axe carried no node target")
    (is (= "serious" (:impact (first rows))))))

(deftest axe-findings-surface-selector-source-link
  (testing "rf2-ffu8t — the axe finding's recovered :selector is the SOURCE
            LINK (spec/021 §4 + §5): :locus is the selector, not the rule id,
            and :selector / :targets carry the offending-element coordinates"
    (let [rows (pure/axe-a11y-findings
                 {:actual [{:id       "image-alt"
                            :impact   "critical"
                            :help     "Images must have alternate text"
                            :selector "main > img:nth-child(2)"
                            :targets  ["main > img:nth-child(2)"]}]})
          row  (first rows)]
      (is (= "main > img:nth-child(2)" (:locus row))
          "the selector is the source-link locus, NOT the rule id")
      (is (= "main > img:nth-child(2)" (:selector row)))
      (is (= ["main > img:nth-child(2)"] (:targets row)))
      (is (= "image-alt" (:rule row)) "the rule id is still carried for keying")))
  (testing "a multi-node violation carries every target selector"
    (let [row (first (pure/axe-a11y-findings
                       {:actual [{:id       "label"
                                  :selector "#a"
                                  :targets  ["#a" "#b"]}]}))]
      (is (= "#a" (:selector row)) "the primary source link is the first target")
      (is (= ["#a" "#b"] (:targets row))))))

;; ---- browser-result-rows: selection + kinds -----------------------------

(deftest browser-rows-select-only-browser-tier-records
  (testing "the projection reads ONLY the three browser-tier ids off the
            unified :assertions slot (the same source Test mode projects)"
    (let [result {:assertions
                  [{:assertion :rf.assert/path-equals :status :pass}
                   {:assertion :rf.assert/a11y-structural :status :pass
                    :count 0 :actual []}
                   {:assertion :rf.assert/no-warnings :status :pass}
                   {:assertion :rf.assert/a11y :status :cannot-run
                    :reason "axe needs a real browser"}
                   {:assertion :rf.assert/visual-snapshot :status :cannot-run
                    :reason "visual needs :pixels"}]}
          rows   (pure/browser-result-rows result)]
      (is (= 3 (count rows)) "only the 3 browser-tier records surface")
      (is (= [:a11y-structural :a11y :visual] (mapv :kind rows))))))

(deftest browser-rows-empty-when-headless-run-has-no-browser-checks
  (testing "the common headless case projects to no rows — an honest empty
            state, never a fabricated card"
    (is (= [] (pure/browser-result-rows
                {:assertions [{:assertion :rf.assert/path-equals :status :pass}]})))
    (is (= [] (pure/browser-result-rows {:assertions []})))
    (is (= [] (pure/browser-result-rows {})))))

;; ---- browser-result-rows: structural-a11y fail --------------------------

(deftest browser-rows-structural-fail-carries-readable-findings
  (let [result {:assertions
                [{:assertion :rf.assert/a11y-structural
                  :status    :fail
                  :count     1
                  :reason    "1 structural a11y issue(s) in the rendered hiccup tree: img-missing-alt"
                  :actual    [{:rule :img-missing-alt :tag :img
                               :detail "img element has no :alt attribute"}]}]}
        row    (first (pure/browser-result-rows result))]
    (is (= :a11y-structural (:kind row)))
    (is (= :fail (:status row)))
    (is (= 1 (:count row)))
    (is (= 1 (count (:findings row))))
    (is (= "image missing alt text" (:finding (first (:findings row)))))
    (is (= "<img>" (:locus (first (:findings row)))))
    ;; rf2-ffu8t — the structural tier has NO real source coord (it walks an
    ;; in-memory hiccup tree, not a DOM); it surfaces the hiccup-tag locus and
    ;; offers no selector, rather than fabricating one.
    (is (nil? (:selector (first (:findings row))))
        "structural findings carry no selector — honest, not fabricated")
    (is (string? (:reason row)) "the diagnostic reason is threaded through")))

;; ---- browser-result-rows: axe fail carries the selector source link -----

(deftest browser-rows-axe-fail-carries-selector-source-link
  (testing "rf2-ffu8t — an axe :rf.assert/a11y fail surfaces the recovered
            selector (the violation's :nodes → :target) end-to-end as the
            finding's SOURCE LINK (spec/021 §4 + §5 MUST)"
    (let [result {:assertions
                  [{:assertion :rf.assert/a11y
                    :status    :fail
                    :count     1
                    :reason    "1 axe-core a11y violation(s)"
                    :actual    [{:id       "image-alt"
                                 :impact   "critical"
                                 :help     "Images must have alternate text"
                                 :selector "main > img:nth-child(2)"
                                 :targets  ["main > img:nth-child(2)"]}]}]}
          row    (first (pure/browser-result-rows result))
          finding (first (:findings row))]
      (is (= :a11y (:kind row)))
      (is (= :fail (:status row)))
      (is (= "main > img:nth-child(2)" (:locus finding))
          "the selector is the source-link locus surfaced by the projection")
      (is (= "main > img:nth-child(2)" (:selector finding)))
      (is (= "critical" (:impact finding))))))

;; ---- browser-result-rows: honest :cannot-run ----------------------------

(deftest browser-rows-cannot-run-shows-reason-not-a-false-pass
  (testing "spec/017 §:cannot-run — a browser-tier check the runner could
            not attempt carries its reason and is NOT a pass; no findings"
    (let [result {:assertions
                  [{:assertion   :rf.assert/a11y
                    :status      :cannot-run
                    :cannot-run? true
                    :passed?     false
                    :reason      "axe-style a11y requires a real browser (:a11y-engine)"}
                   {:assertion   :rf.assert/visual-snapshot
                    :status      :cannot-run
                    :cannot-run? true
                    :passed?     false
                    :reason      "visual snapshot requires a real browser (:pixels)"}]}
          rows   (pure/browser-result-rows result)
          [axe vis] rows]
      (is (= :cannot-run (:status axe)))
      (is (not= :pass (:status axe)))
      (is (= [] (:findings axe)) "no findings fabricated on cannot-run")
      (is (re-find #"real browser" (:reason axe)))
      (is (= :cannot-run (:status vis)))
      (is (nil? (:snapshot vis)) "no snapshot identity fabricated on cannot-run")
      (is (re-find #":pixels" (:reason vis))))))

;; ---- browser-result-rows: visual snapshot presentation ------------------

(deftest browser-rows-visual-snapshot-presents-identity-and-baseline
  (testing "a captured visual snapshot presents the content-hash identity;
            a keyword :expected (:rf.story/any-snapshot) is not a baseline"
    (let [captured {:assertions [{:assertion :rf.assert/visual-snapshot
                                  :status :pass
                                  :actual "abc123"
                                  :expected :rf.story/any-snapshot}]}
          row      (first (pure/browser-result-rows captured))]
      (is (= :visual (:kind row)))
      (is (= "abc123" (:snapshot row)))
      (is (nil? (:baseline row)) ":rf.story/any-snapshot is no baseline")))
  (testing "a baseline-diffing visual snapshot presents both hashes"
    (let [diffed {:assertions [{:assertion :rf.assert/visual-snapshot
                                :status :fail
                                :actual "new-hash"
                                :expected "base-hash"}]}
          row    (first (pure/browser-result-rows diffed))]
      (is (= "new-hash" (:snapshot row)))
      (is (= "base-hash" (:baseline row))))))

;; ---- browser-result-rows: legacy :passed? fallback ----------------------

(deftest browser-rows-fall-back-to-passed-when-status-unstamped
  (testing "a record minted by a status-unaware path still reads pass/fail
            from :passed? (the legacy fallback, not the primary)"
    (let [result {:assertions [{:assertion :rf.assert/a11y-structural
                                :passed? true :actual []}]}
          row    (first (pure/browser-result-rows result))]
      (is (= :pass (:status row))))))
