(ns re-frame.story-save-variant-cljs-test
  "CLJS-side tests for the save-current-canvas-state-as-variant flow.

  Runs under shadow's `:node-test` build (ns-regexp `cljs-test$`).
  The snippet generator and id derivation are pure `.cljc` with no
  reader conditional on their path; the JVM
  `re-frame.story-save-variant-test` covers them, and
  `re-frame.story.ui.save-variant-cljs-test` runs the generator on this
  lane. This ns keeps the args snapshot, the dialog state machine and
  the save trigger running under CLJS.

  Browser-only behaviour (Reagent ratom, modal dialog rendering) lives
  in the CLJS-only `re-frame.story.ui.save-variant` ns, which
  `re-frame.story.ui.save-variant-cljs-test` covers."
  (:require [cljs.test :refer-macros [deftest is use-fixtures]]
            [re-frame.story :as rf.story]
            [re-frame.story.save-variant :as rf.story.save-variant]
            [re-frame.story.ui.state :as rf.story.ui.state]))

;; ---- fixtures ------------------------------------------------------------

(defn reset-all! [f]
  (rf.story/clear-all!)
  (rf.story.ui.state/reset-shell-state!)
  (rf.story/install-canonical-vocabulary!)
  (rf.story.save-variant/set-open-dialog-fn! nil)
  (f))

(use-fixtures :each reset-all!)

;; ---- snapshot-args -------------------------------------------------------

(deftest snapshot-args-returns-resolved-args
  (rf.story/reg-variant :story.snap/v
    {:args {:label "hello" :n 1}
     :setup []})
  (let [snap (rf.story.save-variant/snapshot-args :story.snap/v)]
    (is (= "hello" (:label snap)))
    (is (= 1 (:n snap)))))

(deftest snapshot-args-includes-cell-overrides
  (rf.story/reg-variant :story.snap/v
    {:args   {:label "before" :keep "yes"}
     :setup []})
  (let [snap (rf.story.save-variant/snapshot-args
               :story.snap/v
               {:cell-overrides {:label "after"}})]
    (is (= "after" (:label snap)))
    (is (= "yes"   (:keep snap)))))

;; ---- dialog state machine -------------------------------------------------

(deftest open-builds-dialog-state
  (let [s (rf.story.save-variant/open rf.story.save-variant/initial-dialog-state
                             :story.x/y {:n 1} 1000)]
    (is (:open? s))
    (is (= :story.x/y (:source-id s)))
    (is (= {:n 1} (:args s)))))

(deftest close-returns-idle
  (let [opened (rf.story.save-variant/open rf.story.save-variant/initial-dialog-state
                                  :story.x/y {} 0)]
    (is (= rf.story.save-variant/initial-dialog-state
           (rf.story.save-variant/close opened)))))

;; ---- save-current-as-variant! --------------------------------------------

(deftest save-current-as-variant!-triggers-callback
  (rf.story/reg-variant :story.snap/v {:args {:n 7} :setup []})
  (rf.story.ui.state/swap-state! rf.story.ui.state/select-variant :story.snap/v)
  (let [captured (atom nil)]
    (rf.story.save-variant/set-open-dialog-fn!
      (fn [source-id args & _]
        (reset! captured {:source-id source-id :args args})))
    (let [result (rf.story.save-variant/save-current-as-variant!)]
      (is (some? @captured))
      (is (= :story.snap/v (:source-id @captured)))
      (is (= 7 (-> @captured :args :n)))
      (is (= :story.snap/v (:source-id result))))))
