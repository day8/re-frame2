(ns re-frame.story.ui.chrome-visibility-test
  "JVM-portable regression net for the chrome-visibility transitions
  + per-pane visibility resolution (rf2-p3i0t / rf2-g8l8x / rf2-pucku).

  Surface covered:

  - `chrome-visibility-defaults` — canonical shape
  - `chrome-visibility`          — merge-over-defaults read helper
  - `toggle-chrome-visibility`   — boolean flip per slot
  - `set-chrome-visibility`      — explicit set per slot
  - `chrome-pane-visible?`       — embed > full-screen > per-pane
                                    precedence

  Pure data → data; no DOM / Reagent dependency."
  (:require [clojure.test :refer [deftest is testing]]
            [re-frame.story.ui.state.transitions :as rf.story.ui.state.transitions]))

;; ---- defaults + read -----------------------------------------------------

(deftest chrome-visibility-defaults-shape
  (testing "canonical defaults"
    (is (= {:full-screen? false
            :sidebar?     true
            :rhs?         true
            :toolbar?     true
            :embed?       false}
           rf.story.ui.state.transitions/chrome-visibility-defaults))))

(deftest chrome-visibility-merge-read
  (testing "missing slot → fall back to defaults"
    (is (= rf.story.ui.state.transitions/chrome-visibility-defaults
           (rf.story.ui.state.transitions/chrome-visibility {}))))
  (testing "partial map → merge over defaults"
    (is (= (assoc rf.story.ui.state.transitions/chrome-visibility-defaults :full-screen? true)
           (rf.story.ui.state.transitions/chrome-visibility
             {:chrome-visibility {:full-screen? true}})))))

;; ---- toggle / set --------------------------------------------------------

(deftest toggle-chrome-visibility-shape
  (testing "default false slot → flip to true"
    (let [out (rf.story.ui.state.transitions/toggle-chrome-visibility {} :full-screen?)]
      (is (= true (get-in out [:chrome-visibility :full-screen?])))))
  (testing "default true slot → flip to false"
    (let [out (rf.story.ui.state.transitions/toggle-chrome-visibility {} :sidebar?)]
      (is (= false (get-in out [:chrome-visibility :sidebar?])))))
  (testing "double toggle round-trips"
    (let [a (rf.story.ui.state.transitions/toggle-chrome-visibility {} :rhs?)
          b (rf.story.ui.state.transitions/toggle-chrome-visibility a :rhs?)]
      (is (= true (get-in b [:chrome-visibility :rhs?]))))))

(deftest set-chrome-visibility-shape
  (testing "set true / set false"
    (let [a (rf.story.ui.state.transitions/set-chrome-visibility {} :sidebar? false)]
      (is (= false (get-in a [:chrome-visibility :sidebar?]))))
    (let [b (rf.story.ui.state.transitions/set-chrome-visibility {} :full-screen? true)]
      (is (= true (get-in b [:chrome-visibility :full-screen?]))))))

;; ---- chrome-pane-visible? — precedence -----------------------------------

(deftest chrome-pane-visible-defaults
  (testing "default state: every pane visible"
    (is (true? (rf.story.ui.state.transitions/chrome-pane-visible? :sidebar {})))
    (is (true? (rf.story.ui.state.transitions/chrome-pane-visible? :rhs     {})))
    (is (true? (rf.story.ui.state.transitions/chrome-pane-visible? :toolbar {})))))

(deftest chrome-pane-visible-embed-wins-absolute
  (testing "embed mode hides every chrome pane"
    (let [s {:chrome-visibility {:embed? true}}]
      (is (false? (rf.story.ui.state.transitions/chrome-pane-visible? :sidebar s)))
      (is (false? (rf.story.ui.state.transitions/chrome-pane-visible? :rhs     s)))
      (is (false? (rf.story.ui.state.transitions/chrome-pane-visible? :toolbar s)))))

  (testing "embed wins over a per-pane true"
    (let [s {:chrome-visibility {:embed? true :sidebar? true :rhs? true}}]
      (is (false? (rf.story.ui.state.transitions/chrome-pane-visible? :sidebar s)))
      (is (false? (rf.story.ui.state.transitions/chrome-pane-visible? :rhs     s))))))

(deftest chrome-pane-visible-full-screen-hides
  (testing "full-screen hides every chrome pane"
    (let [s {:chrome-visibility {:full-screen? true}}]
      (is (false? (rf.story.ui.state.transitions/chrome-pane-visible? :sidebar s)))
      (is (false? (rf.story.ui.state.transitions/chrome-pane-visible? :rhs     s)))
      (is (false? (rf.story.ui.state.transitions/chrome-pane-visible? :toolbar s))))))

(deftest chrome-pane-visible-per-pane-toggle
  (testing "per-pane false hides only that pane"
    (let [s {:chrome-visibility {:sidebar? false}}]
      (is (false? (rf.story.ui.state.transitions/chrome-pane-visible? :sidebar s)))
      (is (true?  (rf.story.ui.state.transitions/chrome-pane-visible? :rhs     s)))
      (is (true?  (rf.story.ui.state.transitions/chrome-pane-visible? :toolbar s))))))

(deftest chrome-pane-visible-precedence-embed-over-fullscreen
  (testing "embed and full-screen both true → still hidden (both project hidden)"
    (let [s {:chrome-visibility {:embed? true :full-screen? true}}]
      (is (false? (rf.story.ui.state.transitions/chrome-pane-visible? :sidebar s))))))

(deftest chrome-pane-visible-unknown-pane
  (testing "unknown pane kw → defaults visible (forward-compat)"
    (is (true? (rf.story.ui.state.transitions/chrome-pane-visible? :unknown {})))))

;; ---- Xray-embed collapse (rf2-ba86n.19) ---------------------------------
;;
;; Lazy Xray-diff mounting: the `:xray-embed-collapsed?` slot defers the
;; panel mount + its diff compute until expanded. Pure transition coverage
;; here; the render-path proof (no panel-host in the tree while collapsed)
;; lives in the embed e2e CLJS test.

(deftest xray-embed-collapsed-default-expanded
  (testing "unset slot reads as expanded (false)"
    (is (false? (rf.story.ui.state.transitions/xray-embed-collapsed? {})))
    (is (false? (rf.story.ui.state.transitions/xray-embed-collapsed? {:xray-embed-collapsed? nil}))))
  (testing "explicit slot is read through"
    (is (true?  (rf.story.ui.state.transitions/xray-embed-collapsed? {:xray-embed-collapsed? true})))
    (is (false? (rf.story.ui.state.transitions/xray-embed-collapsed? {:xray-embed-collapsed? false})))))

(deftest toggle-xray-embed-collapsed-flip
  (testing "first toggle from default collapses (expanded → collapsed)"
    (is (true? (rf.story.ui.state.transitions/xray-embed-collapsed?
                 (rf.story.ui.state.transitions/toggle-xray-embed-collapsed {})))))
  (testing "double toggle round-trips back to expanded"
    (let [a (rf.story.ui.state.transitions/toggle-xray-embed-collapsed {})
          b (rf.story.ui.state.transitions/toggle-xray-embed-collapsed a)]
      (is (false? (rf.story.ui.state.transitions/xray-embed-collapsed? b))))))

(deftest set-xray-embed-collapsed-coerces
  (testing "set true / set false / coerce truthy"
    (is (true?  (rf.story.ui.state.transitions/xray-embed-collapsed?
                  (rf.story.ui.state.transitions/set-xray-embed-collapsed {} true))))
    (is (false? (rf.story.ui.state.transitions/xray-embed-collapsed?
                  (rf.story.ui.state.transitions/set-xray-embed-collapsed {:xray-embed-collapsed? true} false))))
    (is (true?  (rf.story.ui.state.transitions/xray-embed-collapsed?
                  (rf.story.ui.state.transitions/set-xray-embed-collapsed {} "truthy"))))))
