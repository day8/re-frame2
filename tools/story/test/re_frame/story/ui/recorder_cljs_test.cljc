(ns re-frame.story.ui.recorder-cljs-test
  "Tests for the Test Codegen recorder UI surface. The pure `open-dialog`
  transition runs on the JVM and on the CLJS node-test build; the rendered
  save dialog and the assertion picker are CLJS-only (`ui/recorder.cljs`)."
  (:require [clojure.string :as str]
            [clojure.test :refer [are deftest is use-fixtures]]
            [re-frame.story.recorder :as rf.story.recorder]
            #?(:cljs [re-frame.story.ui.recorder :as rf.story.ui.recorder])
            #?(:cljs [re-frame.story.ui.recorder-export-dialog
                      :as rf.story.ui.recorder-export-dialog])))

;; ---- fixtures ------------------------------------------------------------

(defn reset-recorder! [f]
  (rf.story.recorder/clear!)
  #?(:cljs (reset! rf.story.ui.recorder/ui-dialog rf.story.recorder/initial-dialog-state))
  (f))

(use-fixtures :each reset-recorder!)

;; ---- JVM + CLJS: dialog state machine ------------------------------------

(deftest open-dialog-snapshots-events-onto-dialog-state
  ;; the snapshot rides on the dialog state, not read live off the recorder;
  ;; the recorded variant-id becomes :source-id, used as :extends
  (let [events [[:counter/inc] [:counter/dec]]]
    (is (= {:open? true :source-id :story.x/y :events events}
           (select-keys (rf.story.recorder/open-dialog rf.story.recorder/initial-dialog-state
                                                       :story.x/y events nil 12345)
                        [:open? :source-id :events])))))

;; ---- CLJS-only: DOM interactions reach the PRIMARY snippet -------------
;;
;; The save dialog renders from the rich `:entries` stream. `:events` holds
;; dispatched events ONLY, so a snippet built from it would silently drop
;; every recorded click / type / submit, and its count would show a
;; recording of three canvas clicks as "0 captured events".

#?(:cljs
   (deftest save-dialog-primary-snippet-includes-dom-interactions
     ;; drive the real capture pipeline, not a hand-built snapshot
     (rf.story.recorder/start-recording! :story.login/form 0)
     (rf.story.recorder/record-event! [:counter/inc])                       ; → :events + :entries
     (rf.story.recorder/record-dom-event! [:dom/click "#submit" 10])        ; → :entries ONLY
     (rf.story.recorder/record-dom-event! [:dom/type "#email" "a@b.co" 20]) ; → :entries ONLY
     (let [{:keys [variant-id events entries]} (rf.story.recorder/stop-recording!)]
       (reset! rf.story.ui.recorder/ui-dialog
               (rf.story.recorder/open-dialog rf.story.recorder/initial-dialog-state
                                              variant-id events entries 0))
       ;; `(str hiccup)` escapes the snippet's inner double-quotes, so the
       ;; selector "#submit" appears as \"#submit\" in the flattened tree.
       (let [flat (str (rf.story.ui.recorder/save-dialog))]
         (is (str/includes? flat ":dispatch [:counter/inc]")
             "the dispatched event still appears as a :dispatch step")
         (is (str/includes? flat "[:click \\\"#submit\\\"]")
             "the recorded DOM click codegens a :click step")
         (is (str/includes? flat "[:type \\\"#email\\\" \\\"a@b.co\\\"]")
             "the recorded DOM type codegens a :type step")
         (is (str/includes? flat "3 recorded steps")
             "the hint count reflects the rich :entries, not :events")))))

;; ---- CLJS-only: the dialog snapshots at open -----------------------------

#?(:cljs
   (deftest save-dialog-survives-fresh-start-recording
     ;; starting a new recording while the dialog is open does NOT change its
     ;; snippet — the snapshot is taken at open, not read off the recorder
     (reset! rf.story.ui.recorder/ui-dialog
             (rf.story.recorder/open-dialog rf.story.recorder/initial-dialog-state
                                            :story.a/source
                                            [[:counter/inc] [:counter/inc] [:counter/dec]]
                                            nil 12345))
     (let [before (str (rf.story.ui.recorder/save-dialog))]
       (rf.story.recorder/start-recording! :story.b/target 99999)
       (let [after (str (rf.story.ui.recorder/save-dialog))]
         (is (= before after))
         (is (str/includes? after ":counter/inc"))
         (is (str/includes? after ":extends :story.a/source"))))))

;; ---- CLJS-only: the export hand-off carries the recording's seed ----------

#?(:cljs
   (deftest save-dialog-export-hands-off-the-recording-seed
     ;; 'export as :script' opens the export dialog with the app-db the
     ;; recording started from, so its auto-assert diffs against it
     (reset! rf.story.recorder/state
             (-> (rf.story.recorder/start rf.story.recorder/initial-state
                                          :story.a/source 0 {:n 0})
                 (rf.story.recorder/append [:counter/inc] 5)
                 rf.story.recorder/stop))
     (reset! rf.story.ui.recorder/ui-dialog
             (rf.story.recorder/open-dialog rf.story.recorder/initial-dialog-state
                                            :story.a/source [[:counter/inc]] nil 12345))
     (let [export (some (fn [n]
                          (when (and (vector? n) (map? (second n))
                                     (= "story-recorder-export" (:data-test (second n))))
                            n))
                        (tree-seq #(or (vector? %) (seq? %)) seq
                                  (rf.story.ui.recorder/save-dialog)))]
       ((:on-click (second export)) nil)
       (is (= {:n 0} (:seed-db @rf.story.ui.recorder-export-dialog/ui-dialog))))
     (reset! rf.story.ui.recorder-export-dialog/ui-dialog
             rf.story.ui.recorder-export-dialog/initial-state)))

;; ---- CLJS-only: assertion picker ARIA + arrow-key nav

#?(:cljs
   (defn- open-picker-for-test! []
     (reset! rf.story.ui.recorder/ui-picker {:open?        true
                                             :assertion    nil
                                             :field-text   {}
                                             :error        nil
                                             :active-index 0})))

#?(:cljs
   (deftest assertion-picker-stamps-modal-aria
     ;; a modal dialog named by its visible title, whose phase-1 vocabulary
     ;; is a WAI-ARIA menu with a roving tabindex
     (open-picker-for-test!)
     (let [flat (str (rf.story.ui.recorder/assertion-picker))]
       (is (str/includes? flat ":role \"dialog\""))
       (is (str/includes? flat "aria-modal"))
       (is (str/includes? flat "aria-labelledby"))
       (is (str/includes? flat "story-recorder-picker-title")
           "the title carries the id aria-labelledby references")
       (is (str/includes? flat ":role \"menu\""))
       (is (str/includes? flat "menuitem"))
       (is (str/includes? flat "Assertion vocabulary")
           "the menu carries an aria-label for the group")
       (is (re-find #"tab-index" flat)))))

#?(:cljs
   (deftest assertion-picker-active-index-moves
     ;; arrow-key navigation wraps at both ends of the vocabulary
     (open-picker-for-test!)
     (let [n (count rf.story.recorder/assertion-vocabulary)]
       (are [idx active] (= active (do (#'rf.story.ui.recorder/set-active-index! idx)
                                       (:active-index @rf.story.ui.recorder/ui-picker)))
         1       1
         (+ n 5) 0
         -1      (dec n)))))
