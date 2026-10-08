(ns re-frame.story.play.browser-test
  "The browser-tier assertion executors — visual snapshot, axe-style a11y
  and structural a11y (`tools/story/spec/017-Testing-Story.md` §Visual,
  a11y, and browser checks). Each is pure data → data; `browser-available?`
  is false on the JVM, which is the headless contract, and the in-a-browser
  tests redefine it. The capability and requirement contract for these ids
  is `re-frame.story.requirements-test`'s."
  (:require [clojure.test :refer [deftest is testing]]
            [re-frame.story.play.browser  :as rf.story.play.browser]))

(def ^:private verdict (juxt :status :passed? :missing-evidence))

;; ===========================================================================
;; HEADLESS FAIL-CLOSED — browser-tier assertions return :cannot-run
;; ===========================================================================

(deftest headless-axe-a11y-cannot-run
  (is (= [:rf.assert/a11y :cannot-run true false]
         ((juxt :assertion :status :cannot-run? :passed?)
          (rf.story.play.browser/eval-a11y [] {:violations [{:id "label" :impact "critical"}]})))))

;; ===========================================================================
;; IN A BROWSER — a row whose evidence was never produced cannot run
;; ===========================================================================

(deftest in-a-browser-a11y-with-no-scan-cannot-run
  (let [saved @rf.story.play.browser/a11y-reader]
    (try
      (with-redefs [rf.story.play.browser/browser-available? (constantly true)]
        (testing "axe never scanned the frame: an absent scan is not a clean one"
          (reset! rf.story.play.browser/a11y-reader (fn [_frame-id] nil))
          (is (= [:cannot-run false #{:a11y}]
                 (verdict (rf.story.play.browser/eval-a11y [] {:frame-id :story.a11y/never-scanned})))))
        (testing "no reader registered at all"
          (reset! rf.story.play.browser/a11y-reader nil)
          (is (= :cannot-run (:status (rf.story.play.browser/eval-a11y [] {:frame-id :story.a11y/x})))))
        (testing "a scan that ran and found nothing passes; one that found a violation fails"
          (is (= :pass (:status (rf.story.play.browser/eval-a11y [] {:violations []}))))
          (reset! rf.story.play.browser/a11y-reader (fn [_frame-id] []))
          (is (= :pass (:status (rf.story.play.browser/eval-a11y [] {:frame-id :story.a11y/clean})))
              "a clean scan read through the panel's reader")
          (is (= :fail (:status (rf.story.play.browser/eval-a11y
                                  [] {:violations [{:id "image-alt" :impact "critical"}]}))))))
      (finally (reset! rf.story.play.browser/a11y-reader saved)))))

(deftest in-a-browser-visual-snapshot-without-pixels-cannot-run
  ;; A snapshot identity hashes the variant's inputs, not captured pixels, so
  ;; even a matching baseline proves nothing. The record keeps the atom's
  ;; payload like any other assertion record.
  (with-redefs [rf.story.play.browser/browser-available? (constantly true)]
    (let [rec (rf.story.play.browser/eval-visual-snapshot
                [{:opt 1}]
                {:snapshot-identity {:content-hash "abc"}
                 :baseline          {:content-hash "abc"}})]
      (is (= [:cannot-run false #{:pixels}] (verdict rec)))
      (is (= [{:opt 1}] (:payload rec))))))

;; ===========================================================================
;; STRUCTURAL A11Y — runs at :hiccup (pure hiccup-tree walk, JVM-testable)
;; ===========================================================================

(defn- structural [tree]
  (rf.story.play.browser/eval-structural-a11y [] {:hiccup tree}))

(deftest structural-a11y-clean-tree-passes
  (is (= [:rf.assert/a11y-structural :pass true 0 []]
         ((juxt :assertion :status :passed? :count (comp vec :actual))
          (structural [:div
                       [:img {:alt "a kitten"}]
                       [:button "Submit"]
                       [:a {:href "/x"} "home"]
                       [:input {:type "text" :aria-label "name"}]])))))

(deftest structural-a11y-detects-unlabeled-input
  (let [rec (structural [:div [:input {:type "text"}]])]
    (is (= [:fail :control-missing-name :input]
           [(:status rec) (-> rec :actual first :rule) (-> rec :actual first :tag)])))
  (is (= :fail (:status (structural [:div [:input {}]])))
      "a :type-less :input defaults to text")
  ;; aria-label and title each supply the name
  (is (= :pass (:status (structural [:div [:input {:type "text" :aria-label "name"}]]))))
  (is (= :pass (:status (structural [:div [:input {:type :email :title "email"}]])))))

(deftest structural-a11y-name-exempt-input-types-pass
  ;; not rendered, or named via :value / :alt
  (doseq [t ["hidden" :submit]]
    (is (= :pass (:status (structural [:div [:input {:type t}]])))
        (str "input :type " (pr-str t) " should not require a name"))))

(deftest structural-a11y-detects-positive-tabindex
  (let [rec (structural [:div {:tabIndex 3} "x"])]
    (is (= [:fail :positive-tabindex] [(:status rec) (-> rec :actual first :rule)])))
  (is (= :fail (:status (structural [:div {:tab-index 5} "y"]))) ":tab-index spelling too")
  (is (= :pass (:status (structural [:div {:tabIndex 0} "x"])))))

(deftest structural-a11y-walks-nested-and-tag-suffix
  ;; the .class / #id suffix on a tag does not hide the element kind
  (is (= #{:img-missing-alt :control-missing-name}
         (set (map :rule (:actual (structural [:section
                                               [:div.row
                                                [:img.thumb {:src "/a.png"}]
                                                [:button.btn#go]]])))))))

;; ===========================================================================
;; AXE FINDING SELECTOR RECOVERY — :nodes → :target source link
;; ===========================================================================
;;
;; `eval-a11y` is :cannot-run headless, so the selector recovery spec/021
;; §4 + §5 requires is tested on its private helpers.

(deftest violation-targets-recovers-node-selectors
  (let [violation-targets @#'rf.story.play.browser/violation-targets]
    (is (= ["#a" "#b"]
           (violation-targets {:nodes [{:target ["#a"]} {:target ["#b" "#a"]}]}))
        "multi-node targets flatten, order-preserving and deduped")
    (is (= [] (violation-targets {:id "page-rule"})))))

(deftest axe-finding-threads-selector-onto-the-record
  (let [axe-finding @#'rf.story.play.browser/axe-finding]
    (is (= {:id "image-alt" :impact "critical" :help "Images must have alt"
            :selector "main > img" :targets ["main > img"]}
           (axe-finding {:id     "image-alt" :impact "critical"
                         :help   "Images must have alt"
                         :nodes  [{:target ["main > img"]}]})))
    (is (= {:id "page-rule" :impact "minor" :help "h"}
           (axe-finding {:id "page-rule" :impact "minor" :help "h"}))
        "no fabricated selector without a node target")))
