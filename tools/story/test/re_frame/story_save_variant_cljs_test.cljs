(ns re-frame.story-save-variant-cljs-test
  "CLJS test keeping the save-variant trigger, and the args snapshot it
  takes, running on this lane. The `.cljc` generator, id derivation and dialog state machine
  are covered on the JVM by `re-frame.story-save-variant-test`; the Reagent
  dialog by `re-frame.story.ui.save-variant-cljs-test`."
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

;; ---- save-current-as-variant! --------------------------------------------

(deftest save-current-as-variant!-triggers-callback
  (rf.story/reg-variant :story.snap/v {:args {:n 7} :setup []})
  (rf.story.ui.state/swap-state! rf.story.ui.state/select-variant :story.snap/v)
  (let [captured (atom nil)]
    (rf.story.save-variant/set-open-dialog-fn!
      (fn [source-id args & _]
        (reset! captured {:source-id source-id :args args})))
    (let [result (rf.story.save-variant/save-current-as-variant!)]
      (is (= [:story.snap/v 7 :story.snap/v]
             [(:source-id @captured) (-> @captured :args :n) (:source-id result)])))))
