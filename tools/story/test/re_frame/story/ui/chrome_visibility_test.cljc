(ns re-frame.story.ui.chrome-visibility-test
  "Chrome-visibility transitions and per-pane visibility resolution
  (embed > full-screen > per-pane), plus the Xray-embed collapse slot."
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
  (testing "an unset slot flips from its default"
    (is (true? (get-in (rf.story.ui.state.transitions/toggle-chrome-visibility {} :full-screen?)
                       [:chrome-visibility :full-screen?])))
    (is (false? (get-in (rf.story.ui.state.transitions/toggle-chrome-visibility {} :sidebar?)
                        [:chrome-visibility :sidebar?]))))
  (testing "a set slot flips from its value"
    (is (true? (get-in (-> {}
                           (rf.story.ui.state.transitions/toggle-chrome-visibility :rhs?)
                           (rf.story.ui.state.transitions/toggle-chrome-visibility :rhs?))
                       [:chrome-visibility :rhs?])))))

;; ---- chrome-pane-visible? — precedence -----------------------------------

;; `=` on a boolean `expected` holds the predicate to a real boolean.

(deftest chrome-pane-visible-truth-table
  (are [pane state expected] (= expected (rf.story.ui.state.transitions/chrome-pane-visible? pane state))
    ;; default state, an unknown pane kw included
    :sidebar {}                                          true
    :unknown {}                                          true
    ;; embed hides every chrome pane, over a per-pane true too
    :toolbar {:chrome-visibility {:embed? true :toolbar? true}} false
    ;; full-screen hides every chrome pane
    :rhs     {:chrome-visibility {:full-screen? true}}   false
    ;; per-pane false hides only that pane
    :sidebar {:chrome-visibility {:sidebar? false}}      false
    :rhs     {:chrome-visibility {:sidebar? false}}      true))

;; ---- Xray-embed collapse ------------------------------------------------
;;
;; The `:xray-embed-collapsed?` slot defers the Xray panel mount and its
;; diff compute until expanded. The render reads it as
;; `data-xray-embed-collapsed`, so an unset slot must read `false`, not nil.

(deftest xray-embed-collapsed-default-expanded
  (is (false? (rf.story.ui.state.transitions/xray-embed-collapsed? {})))
  (is (true?  (rf.story.ui.state.transitions/xray-embed-collapsed? {:xray-embed-collapsed? true}))))

(deftest toggle-xray-embed-collapsed-flip
  (testing "first toggle from default collapses (expanded → collapsed)"
    (is (true? (rf.story.ui.state.transitions/xray-embed-collapsed?
                 (rf.story.ui.state.transitions/toggle-xray-embed-collapsed {})))))
  (testing "double toggle round-trips back to expanded"
    (let [a (rf.story.ui.state.transitions/toggle-xray-embed-collapsed {})
          b (rf.story.ui.state.transitions/toggle-xray-embed-collapsed a)]
      (is (false? (rf.story.ui.state.transitions/xray-embed-collapsed? b))))))
