(ns re-frame.story.play.browser-test
  "Tests for the browser-tier assertion oracles — visual snapshot, axe-style
  a11y, and structural a11y
  (`tools/story/spec/017-Testing-Story.md` §Visual, a11y, and browser
  checks).

  Every evaluator is PURE data → data (the only impurity, `browser-available?`,
  is false on the JVM — exactly the headless contract under test), so the
  whole suite runs under `clojure -M:test` with no host. The acceptance
  bullets:

  - a visual assertion is `:cannot-run` on every runner: with
    `browser-available?` true (redefined here) it still has no captured
    pixels, and a snapshot identity is a hash of the inputs, not pixels;
  - an axe-style a11y assertion evaluates a scan under the browser runner,
    and is `:cannot-run` where axe never scanned the frame;
  - a structural a11y assertion runs at `:hiccup` (a pure hiccup-tree walk,
    JVM-testable here);
  - a HEADLESS run returns `:cannot-run` for the browser-tier (`:pixels` /
    `:a11y-engine`) assertions — the fail-closed contract.

  The capability/requirement + cannot-run contract for these ids lives in
  `re-frame.story.requirements-test`; this suite covers the EXECUTOR."
  (:require [clojure.test :refer [deftest is testing]]
            [re-frame.story.assertions    :as rf.story.assertions]
            [re-frame.story.requirements  :as rf.story.requirements]
            [re-frame.story.play.browser  :as rf.story.play.browser]))

;; ===========================================================================
;; HEADLESS FAIL-CLOSED — browser-tier assertions return :cannot-run
;; ===========================================================================

(deftest headless-visual-snapshot-cannot-run
  (testing "a headless run returns :cannot-run for :rf.assert/visual-snapshot"
    ;; On the JVM `browser-available?` is false — the headless contract.
    (is (false? (rf.story.play.browser/browser-available?)))
    (let [rec (rf.story.play.browser/eval-visual-snapshot [] {:snapshot-identity {:content-hash "abcd1234"}})]
      (is (= :rf.assert/visual-snapshot (:assertion rec)))
      (is (= :cannot-run (:status rec)) "headless visual snapshot is :cannot-run, never a silent pass")
      (is (true? (:cannot-run? rec)))
      (is (false? (:passed? rec)))
      (is (string? (:reason rec))))))

(deftest headless-axe-a11y-cannot-run
  (testing "a headless run returns :cannot-run for axe-style :rf.assert/a11y"
    (let [rec (rf.story.play.browser/eval-a11y [] {:violations [{:id "label" :impact "critical"}]})]
      (is (= :rf.assert/a11y (:assertion rec)))
      (is (= :cannot-run (:status rec)) "headless axe a11y is :cannot-run, never a silent pass")
      (is (true? (:cannot-run? rec)))
      (is (false? (:passed? rec))))))

;; ===========================================================================
;; IN A BROWSER — a row whose evidence was never produced cannot run
;; ===========================================================================
;;
;; `browser-available?` is false on the JVM, so these redefine it: the
;; question is what the evaluator answers once a browser IS there.

(deftest in-a-browser-a11y-with-no-scan-cannot-run
  (let [saved @rf.story.play.browser/a11y-reader]
    (try
      (with-redefs [rf.story.play.browser/browser-available? (constantly true)]
        (testing "the a11y panel's reader is registered and axe never scanned the
                  frame: the row cannot run, naming the missing evidence"
          (reset! rf.story.play.browser/a11y-reader (fn [_frame-id] nil))
          (let [rec (rf.story.play.browser/eval-a11y [] {:frame-id :story.a11y/never-scanned})]
            (is (= :cannot-run (:status rec)) "an absent scan is not a clean one")
            (is (false? (:passed? rec)))
            (is (= #{:a11y} (:missing-evidence rec)))
            (is (re-find #"no axe-core scan" (:reason rec)))))
        (testing "no reader registered at all: the row cannot run"
          (reset! rf.story.play.browser/a11y-reader nil)
          (is (= :cannot-run (:status (rf.story.play.browser/eval-a11y [] {:frame-id :story.a11y/x})))))
        (testing "CONTROL — a scan that ran and found nothing passes; one that
                  found a violation fails"
          (is (= :pass (:status (rf.story.play.browser/eval-a11y [] {:violations []}))))
          (reset! rf.story.play.browser/a11y-reader (fn [_frame-id] []))
          (is (= :pass (:status (rf.story.play.browser/eval-a11y [] {:frame-id :story.a11y/clean})))
              "a clean scan read through the panel's reader passes")
          (is (= :fail (:status (rf.story.play.browser/eval-a11y
                                  [] {:violations [{:id "image-alt" :impact "critical"}]}))))))
      (finally (reset! rf.story.play.browser/a11y-reader saved)))))

(deftest in-a-browser-visual-snapshot-without-pixels-cannot-run
  (with-redefs [rf.story.play.browser/browser-available? (constantly true)]
    (testing "a snapshot identity is a hash of the variant's inputs, not
              captured pixels: with no baseline, with a matching baseline, and
              with nothing at all, the row cannot run"
      (doseq [ctx [{:snapshot-identity {:content-hash "abc"}}
                   {:snapshot-identity {:content-hash "abc"}
                    :baseline          {:content-hash "abc"}}
                   {:frame-id :story.visual/v}]]
        (let [rec (rf.story.play.browser/eval-visual-snapshot [] ctx)]
          (is (= :cannot-run (:status rec)) (str "no captured pixels: " (pr-str ctx)))
          (is (false? (:passed? rec)))
          (is (= #{:pixels} (:missing-evidence rec))))))))

(deftest cannot-run-finding-rides-the-assertion-record-shape
  (testing ":cannot-run findings carry the ONE assertion-record shape"
    (let [rec (rf.story.play.browser/eval-visual-snapshot [{:opt 1}] {})]
      ;; same slots every other assertion record carries
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
          rec  (rf.story.play.browser/eval-structural-a11y [] {:hiccup tree})]
      (is (= :rf.assert/a11y-structural (:assertion rec)))
      (is (= :pass (:status rec)))
      (is (true? (:passed? rec)))
      (is (= 0 (:count rec)))
      (is (empty? (:actual rec))))))

(deftest structural-a11y-detects-img-missing-alt
  (testing "an :img with no :alt is a structural issue"
    (let [rec (rf.story.play.browser/eval-structural-a11y [] {:hiccup [:div [:img {:src "/k.png"}]]})]
      (is (= :fail (:status rec)))
      (is (false? (:passed? rec)))
      (is (= 1 (:count rec)))
      (is (= :img-missing-alt (-> rec :actual first :rule))))))

(deftest structural-a11y-detects-control-missing-name
  (testing "an interactive control with no accessible name is a structural issue"
    (let [rec (rf.story.play.browser/eval-structural-a11y [] {:hiccup [:div [:button {:class "x"}]]})]
      (is (= :fail (:status rec)))
      (is (= :control-missing-name (-> rec :actual first :rule)))))
  (testing "a control with aria-label is fine"
    (let [rec (rf.story.play.browser/eval-structural-a11y
                [] {:hiccup [:div [:button {:aria-label "close"}]]})]
      (is (= :pass (:status rec))))))

(deftest structural-a11y-detects-unlabeled-input
  ;; An interactive <input> with no accessible name is a genuine structural
  ;; a11y issue, and the structural floor flags it.
  (testing "an :input with no accessible name and a named type is flagged"
    (let [rec (rf.story.play.browser/eval-structural-a11y [] {:hiccup [:div [:input {:type "text"}]]})]
      (is (= :fail (:status rec)))
      (is (= :control-missing-name (-> rec :actual first :rule)))
      (is (= :input (-> rec :actual first :tag)))))
  (testing "a :type-less :input defaults to text and is flagged when unnamed"
    (let [rec (rf.story.play.browser/eval-structural-a11y [] {:hiccup [:div [:input {}]]})]
      (is (= :fail (:status rec)))
      (is (= :control-missing-name (-> rec :actual first :rule)))))
  (testing "a labeled :input is clean (aria-label / title supply the name)"
    (is (= :pass (:status (rf.story.play.browser/eval-structural-a11y
                            [] {:hiccup [:div [:input {:type "text" :aria-label "name"}]]}))))
    (is (= :pass (:status (rf.story.play.browser/eval-structural-a11y
                            [] {:hiccup [:div [:input {:type :email :title "email"}]]}))))))

(deftest structural-a11y-name-exempt-input-types-pass
  ;; Hidden / submit / reset / button / image inputs do NOT need
  ;; an explicit accessible name (not rendered, or named via :value / :alt).
  (testing "name-exempt input types are clean even without an accessible name"
    (doseq [t ["hidden" "submit" "reset" "button" "image" :hidden :submit]]
      (let [rec (rf.story.play.browser/eval-structural-a11y [] {:hiccup [:div [:input {:type t}]]})]
        (is (= :pass (:status rec))
            (str "input :type " (pr-str t) " should not require a name"))))))

(deftest structural-a11y-detects-positive-tabindex
  (testing "a positive :tabIndex is a structural issue (both spellings)"
    (let [rec  (rf.story.play.browser/eval-structural-a11y [] {:hiccup [:div {:tabIndex 3} "x"]})
          rec2 (rf.story.play.browser/eval-structural-a11y [] {:hiccup [:div {:tab-index 5} "y"]})]
      (is (= :fail (:status rec)))
      (is (= :positive-tabindex (-> rec :actual first :rule)))
      (is (= :fail (:status rec2)) ":tab-index spelling is also caught")))
  (testing "tabIndex 0 / -1 are NOT issues"
    (is (= :pass (:status (rf.story.play.browser/eval-structural-a11y [] {:hiccup [:div {:tabIndex 0} "x"]}))))
    (is (= :pass (:status (rf.story.play.browser/eval-structural-a11y [] {:hiccup [:div {:tabIndex -1} "x"]}))))))

(deftest structural-a11y-walks-nested-and-tag-suffix
  (testing "the walk recurses into children and strips #id/.class tag suffixes"
    (let [tree [:section
                [:div.row
                 [:img.thumb {:src "/a.png"}]    ; missing alt
                 [:button.btn#go]]]              ; missing name
          rec  (rf.story.play.browser/eval-structural-a11y [] {:hiccup tree})]
      (is (= :fail (:status rec)))
      (is (= #{:img-missing-alt :control-missing-name}
             (set (map :rule (:actual rec))))
          "the .class/#id suffix on the tag does not hide the element kind"))))

(deftest structural-a11y-runs-at-hiccup-tier
  (testing ":rf.assert/a11y-structural requires only :hiccup-structure"
    (is (= #{:hiccup-structure}
           (rf.story.requirements/assertion-tokens [:rf.assert/a11y-structural])))
    (is (= :hiccup (rf.story.requirements/cheapest-runner #{:hiccup-structure}))
        "the cheapest runner proving structural a11y is :hiccup, NOT :browser")
    ;; a hiccup runner satisfies it; headless does not.
    (is (rf.story.requirements/runner-satisfies? (rf.story.requirements/runner-provides :hiccup) #{:hiccup-structure}))
    (is (not (rf.story.requirements/runner-satisfies? (rf.story.requirements/runner-provides :headless) #{:hiccup-structure})))))

;; ===========================================================================
;; LIVE a11y READER SEAM — the inverted, late-bound hook
;; ===========================================================================

(deftest a11y-reader-seam-is-reusable-and-nil-safe
  (testing "live-a11y-violations reads through the registered reader; nil-safe when absent"
    (let [saved @rf.story.play.browser/a11y-reader]
      (try
        (reset! rf.story.play.browser/a11y-reader nil)
        (is (nil? (rf.story.play.browser/live-a11y-violations :frame/x)) "no reader → nil")
        (rf.story.play.browser/register-a11y-reader!
          (fn [fid] (when (= :frame/x fid) [{:id "color-contrast" :impact "serious"}])))
        (is (= [{:id "color-contrast" :impact "serious"}]
               (rf.story.play.browser/live-a11y-violations :frame/x)))
        (is (nil? (rf.story.play.browser/live-a11y-violations :frame/other)))
        (finally (reset! rf.story.play.browser/a11y-reader saved))))))

;; ===========================================================================
;; AXE FINDING SELECTOR RECOVERY — :nodes → :target source link
;; ===========================================================================
;;
;; `eval-a11y` is :cannot-run headless (browser-available? false on the JVM),
;; so the selector-recovery projection is unit-tested on its pure helpers
;; (`violation-targets` / `axe-finding`, private — deref'd via the var). A
;; bare `select-keys [:id :impact :help]` would DISCARD the axe `:nodes`
;; that carry the offending-element CSS selectors; these pin the recovery
;; so the source-link MUST (spec/021 §4 + §5) is met.

(deftest violation-targets-recovers-node-selectors
  (let [violation-targets @#'rf.story.play.browser/violation-targets]
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
  (let [axe-finding @#'rf.story.play.browser/axe-finding]
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
    (is (rf.story.play.browser/browser-assertion? [:rf.assert/visual-snapshot]))
    (is (rf.story.play.browser/browser-assertion? [:rf.assert/a11y]))
    (is (rf.story.play.browser/browser-assertion? [:rf.assert/a11y-structural]))
    (is (not (rf.story.play.browser/browser-assertion? [:rf.assert/path-equals [:n] 1])))
    (is (not (rf.story.play.browser/browser-assertion? [:rf.assert/dom-visible "[x]"])))))

(deftest eval-browser-assertion-routes-by-id
  (testing "eval-browser-assertion routes each oracle atom to its evaluator"
    ;; structural runs JVM-side (hiccup tree)
    (let [rec (rf.story.play.browser/eval-browser-assertion
                [:rf.assert/a11y-structural] {:hiccup [:div [:img]]})]
      (is (= :rf.assert/a11y-structural (:assertion rec)))
      (is (= :fail (:status rec))))
    ;; visual / a11y are :cannot-run headless
    (is (= :cannot-run (:status (rf.story.play.browser/eval-browser-assertion
                                  [:rf.assert/visual-snapshot] {}))))
    (is (= :cannot-run (:status (rf.story.play.browser/eval-browser-assertion
                                  [:rf.assert/a11y] {}))))
    ;; a non-oracle atom is not handled here
    (is (nil? (rf.story.play.browser/eval-browser-assertion [:rf.assert/path-equals [:n] 1] {})))))

;; ===========================================================================
;; ID + KNOWN-SET INTEGRATION — the ids are recognised by the vocabulary
;; ===========================================================================

(deftest browser-tier-ids-are-known-assertions
  (testing "the browser-tier ids are in the recognised vocabulary"
    (is (contains? rf.story.assertions/browser-assertion-ids :rf.assert/visual-snapshot))
    (is (contains? rf.story.assertions/browser-assertion-ids :rf.assert/a11y))
    (is (contains? rf.story.assertions/browser-assertion-ids :rf.assert/a11y-structural))
    ;; plan construction accepts them (assertion-id-known?)
    (is (rf.story.assertions/assertion-id-known? :rf.assert/visual-snapshot))
    (is (rf.story.assertions/assertion-id-known? :rf.assert/a11y))
    (is (rf.story.assertions/assertion-id-known? :rf.assert/a11y-structural))))
