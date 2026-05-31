(ns re-frame.story.play.browser-test
  "Tests for the browser-tier assertion oracles — visual snapshot, axe-style
  a11y, and structural a11y (NewTestStory rf2-5x1wt.28,
  `tools/story/spec/017-Testing-Story.md` §Visual, a11y, and browser
  checks).

  Every evaluator is PURE data → data (the only impurity, `browser-available?`,
  is false on the JVM — exactly the headless contract under test), so the
  whole suite runs under `clojure -M:test` with no host. The §C4 acceptance
  bullets:

  - a visual assertion runs when the browser runner is selected/requested
    (modeled here: with `browser-available?` true it evaluates the snapshot
    identity; on the JVM it is `:cannot-run`);
  - an axe-style a11y assertion runs under the browser runner (likewise);
  - a structural a11y assertion runs at `:hiccup` (a pure hiccup-tree walk,
    JVM-testable here);
  - a HEADLESS run returns `:cannot-run` for the browser-tier (`:pixels` /
    `:a11y-engine`) assertions — the fail-closed contract.

  The capability/requirement + cannot-run contract for these ids lives in
  `re-frame.story.requirements-test`; this suite covers the EXECUTOR."
  (:require [clojure.test :refer [deftest is testing]]
            [re-frame.story.assertions    :as assertions]
            [re-frame.story.requirements  :as req]
            [re-frame.story.play.browser  :as browser]))

;; ===========================================================================
;; HEADLESS FAIL-CLOSED — browser-tier assertions return :cannot-run
;; ===========================================================================

(deftest headless-visual-snapshot-cannot-run
  (testing "a headless run returns :cannot-run for :rf.assert/visual-snapshot"
    ;; On the JVM `browser-available?` is false — the headless contract.
    (is (false? (browser/browser-available?)))
    (let [rec (browser/eval-visual-snapshot [] {:snapshot-identity {:content-hash "abcd1234"}})]
      (is (= :rf.assert/visual-snapshot (:assertion rec)))
      (is (= :cannot-run (:status rec)) "headless visual snapshot is :cannot-run, never a silent pass")
      (is (true? (:cannot-run? rec)))
      (is (false? (:passed? rec)))
      (is (string? (:reason rec))))))

(deftest headless-axe-a11y-cannot-run
  (testing "a headless run returns :cannot-run for axe-style :rf.assert/a11y"
    (let [rec (browser/eval-a11y [] {:violations [{:id "label" :impact "critical"}]})]
      (is (= :rf.assert/a11y (:assertion rec)))
      (is (= :cannot-run (:status rec)) "headless axe a11y is :cannot-run, never a silent pass")
      (is (true? (:cannot-run? rec)))
      (is (false? (:passed? rec))))))

(deftest cannot-run-finding-rides-the-assertion-record-shape
  (testing ":cannot-run findings carry the ONE assertion-record shape"
    (let [rec (browser/eval-visual-snapshot [{:opt 1}] {})]
      ;; same slots every other assertion record carries (.19 shape)
      (is (contains? rec :assertion))
      (is (contains? rec :payload))
      (is (contains? rec :passed?))
      (is (contains? rec :status))
      (is (= [{:opt 1}] (:payload rec)) "payload is preserved on the record"))))

;; ===========================================================================
;; STRUCTURAL A11Y — runs at :hiccup (pure hiccup-tree walk, JVM-testable)
;; ===========================================================================

(deftest structural-a11y-clean-tree-passes
  (testing "a structurally-clean hiccup tree passes :rf.assert/a11y-structural"
    (let [tree [:div
                [:img {:alt "a kitten"}]
                [:button "Submit"]
                [:a {:href "/x"} "home"]
                [:input {:type "text" :aria-label "name"}]]
          rec  (browser/eval-structural-a11y [] {:hiccup tree})]
      (is (= :rf.assert/a11y-structural (:assertion rec)))
      (is (= :pass (:status rec)))
      (is (true? (:passed? rec)))
      (is (= 0 (:count rec)))
      (is (empty? (:actual rec))))))

(deftest structural-a11y-detects-img-missing-alt
  (testing "an :img with no :alt is a structural issue"
    (let [rec (browser/eval-structural-a11y [] {:hiccup [:div [:img {:src "/k.png"}]]})]
      (is (= :fail (:status rec)))
      (is (false? (:passed? rec)))
      (is (= 1 (:count rec)))
      (is (= :img-missing-alt (-> rec :actual first :rule))))))

(deftest structural-a11y-detects-control-missing-name
  (testing "an interactive control with no accessible name is a structural issue"
    (let [rec (browser/eval-structural-a11y [] {:hiccup [:div [:button {:class "x"}]]})]
      (is (= :fail (:status rec)))
      (is (= :control-missing-name (-> rec :actual first :rule)))))
  (testing "a control with aria-label is fine"
    (let [rec (browser/eval-structural-a11y
                [] {:hiccup [:div [:button {:aria-label "close"}]]})]
      (is (= :pass (:status rec))))))

(deftest structural-a11y-detects-unlabeled-input
  ;; rf2-81ud8 — an interactive <input> with no accessible name is a genuine
  ;; structural a11y issue; the structural floor now flags it (the docstring
  ;; no longer claims a call-site check that did not exist).
  (testing "an :input with no accessible name and a named type is flagged"
    (let [rec (browser/eval-structural-a11y [] {:hiccup [:div [:input {:type "text"}]]})]
      (is (= :fail (:status rec)))
      (is (= :control-missing-name (-> rec :actual first :rule)))
      (is (= :input (-> rec :actual first :tag)))))
  (testing "a :type-less :input defaults to text and is flagged when unnamed"
    (let [rec (browser/eval-structural-a11y [] {:hiccup [:div [:input {}]]})]
      (is (= :fail (:status rec)))
      (is (= :control-missing-name (-> rec :actual first :rule)))))
  (testing "a labeled :input is clean (aria-label / title supply the name)"
    (is (= :pass (:status (browser/eval-structural-a11y
                            [] {:hiccup [:div [:input {:type "text" :aria-label "name"}]]}))))
    (is (= :pass (:status (browser/eval-structural-a11y
                            [] {:hiccup [:div [:input {:type :email :title "email"}]]}))))))

(deftest structural-a11y-name-exempt-input-types-pass
  ;; rf2-81ud8 — hidden / submit / reset / button / image inputs do NOT need
  ;; an explicit accessible name (not rendered, or named via :value / :alt).
  (testing "name-exempt input types are clean even without an accessible name"
    (doseq [t ["hidden" "submit" "reset" "button" "image" :hidden :submit]]
      (let [rec (browser/eval-structural-a11y [] {:hiccup [:div [:input {:type t}]]})]
        (is (= :pass (:status rec))
            (str "input :type " (pr-str t) " should not require a name"))))))

(deftest structural-a11y-detects-positive-tabindex
  (testing "a positive :tabIndex is a structural issue (both spellings)"
    (let [rec  (browser/eval-structural-a11y [] {:hiccup [:div {:tabIndex 3} "x"]})
          rec2 (browser/eval-structural-a11y [] {:hiccup [:div {:tab-index 5} "y"]})]
      (is (= :fail (:status rec)))
      (is (= :positive-tabindex (-> rec :actual first :rule)))
      (is (= :fail (:status rec2)) ":tab-index spelling is also caught")))
  (testing "tabIndex 0 / -1 are NOT issues"
    (is (= :pass (:status (browser/eval-structural-a11y [] {:hiccup [:div {:tabIndex 0} "x"]}))))
    (is (= :pass (:status (browser/eval-structural-a11y [] {:hiccup [:div {:tabIndex -1} "x"]}))))))

(deftest structural-a11y-walks-nested-and-tag-suffix
  (testing "the walk recurses into children and strips #id/.class tag suffixes"
    (let [tree [:section
                [:div.row
                 [:img.thumb {:src "/a.png"}]    ; missing alt
                 [:button.btn#go]]]              ; missing name
          rec  (browser/eval-structural-a11y [] {:hiccup tree})]
      (is (= :fail (:status rec)))
      (is (= #{:img-missing-alt :control-missing-name}
             (set (map :rule (:actual rec))))
          "the .class/#id suffix on the tag does not hide the element kind"))))

(deftest structural-a11y-runs-at-hiccup-tier
  (testing ":rf.assert/a11y-structural requires only :hiccup-structure"
    (is (= #{:hiccup-structure}
           (req/assertion-tokens [:rf.assert/a11y-structural])))
    (is (= :hiccup (req/cheapest-runner #{:hiccup-structure}))
        "the cheapest runner proving structural a11y is :hiccup, NOT :browser")
    ;; a hiccup runner satisfies it; headless does not.
    (is (req/runner-satisfies? (req/runner-provides :hiccup) #{:hiccup-structure}))
    (is (not (req/runner-satisfies? (req/runner-provides :headless) #{:hiccup-structure})))))

;; ===========================================================================
;; LIVE a11y READER SEAM — the inverted, late-bound hook
;; ===========================================================================

(deftest a11y-reader-seam-is-reusable-and-nil-safe
  (testing "live-a11y-violations reads through the registered reader; nil-safe when absent"
    (let [saved @browser/a11y-reader]
      (try
        (reset! browser/a11y-reader nil)
        (is (nil? (browser/live-a11y-violations :frame/x)) "no reader → nil")
        (browser/register-a11y-reader!
          (fn [fid] (when (= :frame/x fid) [{:id "color-contrast" :impact "serious"}])))
        (is (= [{:id "color-contrast" :impact "serious"}]
               (browser/live-a11y-violations :frame/x)))
        (is (nil? (browser/live-a11y-violations :frame/other)))
        (finally (reset! browser/a11y-reader saved))))))

;; ===========================================================================
;; AXE FINDING SELECTOR RECOVERY — :nodes → :target source link (rf2-ffu8t)
;; ===========================================================================
;;
;; `eval-a11y` is :cannot-run headless (browser-available? false on the JVM),
;; so the selector-recovery projection is unit-tested on its pure helpers
;; (`violation-targets` / `axe-finding`, private — deref'd via the var). The
;; executor formerly did `select-keys [:id :impact :help]`, DISCARDING the
;; axe `:nodes` that carry the offending-element CSS selectors; these pin the
;; recovery so the source-link MUST (spec/021 §4 + §5) is met.

(deftest violation-targets-recovers-node-selectors
  (let [violation-targets @#'browser/violation-targets]
    (testing "the CSS selectors are recovered from :nodes → :target, deduped"
      (is (= ["main > img:nth-child(2)"]
             (violation-targets
               {:id "image-alt"
                :nodes [{:target ["main > img:nth-child(2)"]}]})))
      (is (= ["#a" "#b"]
             (violation-targets
               {:nodes [{:target ["#a"]} {:target ["#b" "#a"]}]}))
          "multi-node targets flatten, order-preserving + deduped"))
    (testing "a violation with no :nodes / :target recovers no selector"
      (is (= [] (violation-targets {:id "page-rule"})))
      (is (= [] (violation-targets {:nodes []})))
      (is (= [] (violation-targets {:nodes [{:target []}]}))))))

(deftest axe-finding-threads-selector-onto-the-record
  (let [axe-finding @#'browser/axe-finding]
    (testing "the axe finding carries :id/:impact/:help PLUS the recovered
              :selector (first target) and :targets (all)"
      (is (= {:id "image-alt" :impact "critical" :help "Images must have alt"
              :selector "main > img" :targets ["main > img"]}
             (axe-finding {:id     "image-alt" :impact "critical"
                           :help   "Images must have alt"
                           :nodes  [{:target ["main > img"]}]}))))
    (testing "a node-target-less violation projects WITHOUT a fabricated selector"
      (let [f (axe-finding {:id "page-rule" :impact "minor" :help "h"})]
        (is (= {:id "page-rule" :impact "minor" :help "h"} f))
        (is (nil? (:selector f)) "no selector slot when there is no node target")))))

;; ===========================================================================
;; DISPATCH ENTRY — one router per browser-tier atom
;; ===========================================================================

(deftest browser-assertion-predicate
  (testing "browser-assertion? recognises the three oracle ids only"
    (is (browser/browser-assertion? [:rf.assert/visual-snapshot]))
    (is (browser/browser-assertion? [:rf.assert/a11y]))
    (is (browser/browser-assertion? [:rf.assert/a11y-structural]))
    (is (not (browser/browser-assertion? [:rf.assert/path-equals [:n] 1])))
    (is (not (browser/browser-assertion? [:rf.assert/dom-visible "[x]"])))))

(deftest eval-browser-assertion-routes-by-id
  (testing "eval-browser-assertion routes each oracle atom to its evaluator"
    ;; structural runs JVM-side (hiccup tree)
    (let [rec (browser/eval-browser-assertion
                [:rf.assert/a11y-structural] {:hiccup [:div [:img]]})]
      (is (= :rf.assert/a11y-structural (:assertion rec)))
      (is (= :fail (:status rec))))
    ;; visual / a11y are :cannot-run headless
    (is (= :cannot-run (:status (browser/eval-browser-assertion
                                  [:rf.assert/visual-snapshot] {}))))
    (is (= :cannot-run (:status (browser/eval-browser-assertion
                                  [:rf.assert/a11y] {}))))
    ;; a non-oracle atom is not handled here
    (is (nil? (browser/eval-browser-assertion [:rf.assert/path-equals [:n] 1] {})))))

;; ===========================================================================
;; ID + KNOWN-SET INTEGRATION — the new id is recognised by the vocabulary
;; ===========================================================================

(deftest browser-tier-ids-are-known-assertions
  (testing "the browser-tier ids are in the recognised vocabulary"
    (is (contains? assertions/browser-assertion-ids :rf.assert/visual-snapshot))
    (is (contains? assertions/browser-assertion-ids :rf.assert/a11y))
    (is (contains? assertions/browser-assertion-ids :rf.assert/a11y-structural))
    ;; plan construction accepts them (assertion-id-known?)
    (is (assertions/assertion-id-known? :rf.assert/visual-snapshot))
    (is (assertions/assertion-id-known? :rf.assert/a11y))
    (is (assertions/assertion-id-known? :rf.assert/a11y-structural))))
