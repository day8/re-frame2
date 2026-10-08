(ns re-frame.story.ui.recorder-export-dialog-cljs-test
  "Tests for the recorder → :script export dialog UI. `build-export` is pure
  and runs on the JVM and on the CLJS node-test build; the dialog ratom and
  its rendered hiccup are CLJS-only."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is use-fixtures]]
            [re-frame.story.recorder                    :as rf.story.recorder]
            [re-frame.story.recorder.play-export-events :as rf.story.recorder.play-export-events]
            #?(:cljs [re-frame.story.ui.recorder-export-dialog :as rf.story.ui.recorder-export-dialog])))

;; ---- fixtures ------------------------------------------------------------

#?(:cljs
   (defn reset-dialog! [f]
     (reset! rf.story.ui.recorder-export-dialog/ui-dialog rf.story.ui.recorder-export-dialog/initial-state)
     (f)))

#?(:cljs (use-fixtures :each reset-dialog!))

;; ---- JVM + CLJS: pure build-export ---------------------------------------

(deftest build-export-tuple-shape
  (let [{:keys [spec rendered]} (rf.story.recorder.play-export-events/build-export
                                  [[:counter/inc] [:counter/dec]]
                                  {:variant-id :story.x/recorded
                                   :extends    :story.x/source
                                   :name       "happy"})]
    (is (= "happy" (:name spec)))
    (is (= [[:dispatch [:counter/inc]]
            [:dispatch [:counter/dec]]]
           (:script spec)))
    (is (str/includes? rendered ":story.x/recorded"))
    (is (str/includes? rendered ":story.x/source"))
    (is (str/includes? rendered ":counter/inc"))))

;; ---- CLJS-only: open ------------------------------------------------------

#?(:cljs
   (deftest variant-id-derived-from-source-id
     (rf.story.ui.recorder-export-dialog/open-dialog!
       {:source-id :story.counter/happy
        :events    [[:counter/inc]]})
     (is (= :story.counter/recorded-script
            (:variant-id @rf.story.ui.recorder-export-dialog/ui-dialog)))))

;; ---- CLJS-only: dialog rendering ----------------------------------------

#?(:cljs
   (deftest dialog-renders-snippet-from-events
     ;; The title prints `:script` and the hint prints the source-id, so the
     ;; claims read the snippet `<pre>`, not the whole dialog.
     (rf.story.ui.recorder-export-dialog/open-dialog!
       {:source-id :story.x/source
        :events    [[:counter/inc] [:counter/dec]]})
     (let [snippet (str (some (fn [node]
                                (when (and (vector? node)
                                           (= :pre (first node))
                                           (= "story-recorder-export-snippet"
                                              (:data-test (second node))))
                                  (nth node 2)))
                              (tree-seq coll? seq (rf.story.ui.recorder-export-dialog/export-dialog))))]
       (is (str/includes? snippet ":counter/inc")
           "captured events appear in the snippet")
       (is (str/includes? snippet ":story.x/source")
           "source-id appears via :extends")
       (is (str/includes? snippet ":script")
           "snippet carries the public :script slot name"))))

#?(:cljs
   (deftest dialog-survives-fresh-recording
     ;; The export dialog holds its OWN snapshot, taken when the export-open
     ;; handler runs: recorder churn afterwards (a fresh recording, a new
     ;; keystroke, a discard) leaves the in-flight export unchanged.
     (rf.story.recorder/start-recording! :story.a/source)
     (rf.story.recorder/record-event! [:auth/login])
     (rf.story.recorder/record-event! [:counter/inc])
     ;; exactly as the save dialog's :on-export does; :source-id nil keeps
     ;; the frame-db snapshot out of scope
     (rf.story.ui.recorder-export-dialog/open-from-recorder-dialog!
       {:events    (rf.story.recorder/recorded-events)
        :entries   (rf.story.recorder/recorded-entries)
        :source-id nil})
     (rf.story.recorder/start-recording! :story.b/other)
     (rf.story.recorder/record-event! [:counter/dec])
     (rf.story.recorder/clear!)
     (is (str/includes? (str (rf.story.ui.recorder-export-dialog/export-dialog)) ":auth/login")
         "the rendered export still carries the originally-captured events")))

#?(:cljs
   (deftest dialog-auto-assert-diffs-against-the-recording-seed
     ;; given the recording's seed db, the default auto-assert pins only
     ;; what the recording changed
     (rf.story.ui.recorder-export-dialog/open-dialog!
       {:source-id :story.x/source
        :events    [[:counter/inc]]
        :seed-db   {:static-a 1 :static-b 2 :n 0}
        :final-db  {:static-a 1 :static-b 2 :n 1}})
     (let [flat (str (rf.story.ui.recorder-export-dialog/export-dialog))]
       (is (str/includes? flat "[:assert-db [:n] 1]")
           "the changed path is asserted")
       (is (not (str/includes? flat ":static-"))
           "the unchanged static keys are not"))))
