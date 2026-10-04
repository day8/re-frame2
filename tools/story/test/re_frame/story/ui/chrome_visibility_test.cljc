(ns re-frame.story.ui.chrome-visibility-test
  "JVM-portable regression net for the chrome-visibility transitions
  + per-pane visibility resolution.

  Surface covered:

  - `chrome-visibility`          — merge-over-defaults read helper
  - `toggle-chrome-visibility`   — boolean flip per slot
  - `chrome-pane-visible?`       — embed > full-screen > per-pane
                                    precedence

  Pure data → data; no DOM / Reagent dependency."
  (:require [clojure.test :refer [are deftest is testing]]
            [re-frame.story.ui.state.transitions :as rf.story.ui.state.transitions]))

;; ---- defaults + read -----------------------------------------------------

(deftest chrome-visibility-merge-read
  (testing "missing slot → fall back to defaults"
    (is (= rf.story.ui.state.transitions/chrome-visibility-defaults
           (rf.story.ui.state.transitions/chrome-visibility {}))))
  (testing "partial map → merge over defaults"
    (is (= (assoc rf.story.ui.state.transitions/chrome-visibility-defaults :full-screen? true)
           (rf.story.ui.state.transitions/chrome-visibility
             {:chrome-visibility {:full-screen? true}})))))

;; ---- toggle --------------------------------------------------------------

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

;; ---- chrome-pane-visible? — precedence -----------------------------------

;; One table. Each row is `[pane state expected]`; `=` on a boolean
;; `expected` holds the predicate to a real boolean.

(deftest chrome-pane-visible-truth-table
  (testing "default state: every pane visible, an unknown pane kw included"
    (are [pane state expected] (= expected (rf.story.ui.state.transitions/chrome-pane-visible? pane state))
      :sidebar {} true
      :rhs     {} true
      :toolbar {} true
      :unknown {} true))
  (testing "embed mode hides every chrome pane, over a per-pane true too"
    (are [pane state expected] (= expected (rf.story.ui.state.transitions/chrome-pane-visible? pane state))
      :sidebar {:chrome-visibility {:embed? true}}                           false
      :rhs     {:chrome-visibility {:embed? true}}                           false
      :toolbar {:chrome-visibility {:embed? true}}                           false
      :sidebar {:chrome-visibility {:embed? true :sidebar? true :rhs? true}} false
      :rhs     {:chrome-visibility {:embed? true :sidebar? true :rhs? true}} false))
  (testing "full-screen hides every chrome pane; with embed as well it
            stays hidden"
    (are [pane state expected] (= expected (rf.story.ui.state.transitions/chrome-pane-visible? pane state))
      :sidebar {:chrome-visibility {:full-screen? true}}              false
      :rhs     {:chrome-visibility {:full-screen? true}}              false
      :toolbar {:chrome-visibility {:full-screen? true}}              false
      :sidebar {:chrome-visibility {:embed? true :full-screen? true}} false))
  (testing "per-pane false hides only that pane"
    (are [pane state expected] (= expected (rf.story.ui.state.transitions/chrome-pane-visible? pane state))
      :sidebar {:chrome-visibility {:sidebar? false}} false
      :rhs     {:chrome-visibility {:sidebar? false}} true
      :toolbar {:chrome-visibility {:sidebar? false}} true)))

;; ---- Xray-embed collapse ------------------------------------------------
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
