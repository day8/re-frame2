(ns re-frame.story.ui.recorder-export-dialog-cljs-test
  "Tests for the recorder → :script export dialog UI (rf2-x9zsr).

  Two tiers:

  - **JVM + CLJS** — open/close transitions, snapshot independence
    (the export dialog stores its own captured snapshot at open
    time so a subsequent recorder reset cannot mutate the in-flight
    export). The build-export helper is pure data → data and
    JVM-runnable.

  - **CLJS-only** — render the dialog hiccup and probe for the
    expected affordances (snippet preview contains the captured
    events; auto-assert toggle, name + variant-id inputs, copy /
    replay / close buttons all surface)."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.story.recorder                    :as rf.story.recorder]
            [re-frame.story.recorder.play-export        :as rf.story.recorder.play-export]
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
  (testing "build-export yields {:spec :rendered} with both shapes well-formed"
    (let [{:keys [spec rendered]} (rf.story.recorder.play-export-events/build-export
                                    [[:counter/inc] [:counter/dec]]
                                    {:variant-id :story.x/recorded
                                     :extends    :story.x/source
                                     :name       "happy"})]
      (is (map? spec))
      (is (string? rendered))
      (is (= "happy" (:name spec)))
      (is (= [[:dispatch [:counter/inc]]
              [:dispatch [:counter/dec]]]
             (:script spec)))
      (is (str/includes? rendered ":story.x/recorded"))
      (is (str/includes? rendered ":story.x/source"))
      (is (str/includes? rendered ":counter/inc")))))

(deftest build-export-auto-assert-flows-through
  (testing "the auto-assert option produces trailing :assert-db steps"
    (let [{:keys [spec]} (rf.story.recorder.play-export-events/build-export
                           [[:counter/inc]]
                           {:variant-id :story.x/recorded
                            :auto-assert? true
                            :final-db {:n 1}
                            :seed-db  {:n 0}})
          tags (mapv first (:script spec))]
      (is (= [:dispatch :assert-db] tags)
          "dispatch first, :assert-db trails"))))

;; ---- CLJS-only: open / close transitions --------------------------------

#?(:cljs
   (deftest open-dialog-snapshots-input
     (testing "open-dialog stashes the captured events + source-id on the ratom"
       (rf.story.ui.recorder-export-dialog/open-dialog!
         {:source-id :story.a/source
          :events    [[:counter/inc] [:counter/dec]]
          :final-db  {:n 1}})
       (let [s @rf.story.ui.recorder-export-dialog/ui-dialog]
         (is (:open? s))
         (is (= :story.a/source (:source-id s)))
         (is (= [[:counter/inc] [:counter/dec]] (:events s)))
         (is (= {:n 1} (:final-db s)))
         (is (true? (:auto-assert? s))
             "auto-assert defaults ON per bead — user toggles off if too noisy")))))

#?(:cljs
   (deftest variant-id-derived-from-source-id
     (testing "the default :variant-id is derived from source-id's namespace"
       (rf.story.ui.recorder-export-dialog/open-dialog!
         {:source-id :story.counter/happy
          :events    [[:counter/inc]]})
       (is (= :story.counter/recorded-script
              (:variant-id @rf.story.ui.recorder-export-dialog/ui-dialog))))))

#?(:cljs
   (deftest open-dialog-snapshot-is-decoupled-from-source-vector
     (testing "the snapshot is a fresh vector, not a live ref"
       (let [events (vec [[:counter/inc]])]
         (rf.story.ui.recorder-export-dialog/open-dialog!
           {:source-id :story.x/y :events events})
         (is (= [[:counter/inc]] (:events @rf.story.ui.recorder-export-dialog/ui-dialog))
             "the dialog carries the snapshot independent of the source vector")))))

;; ---- CLJS-only: dialog rendering ----------------------------------------

#?(:cljs
   (deftest dialog-not-rendered-when-closed
     (testing "the dialog renders nil when :open? is false"
       (reset! rf.story.ui.recorder-export-dialog/ui-dialog rf.story.ui.recorder-export-dialog/initial-state)
       (is (nil? (rf.story.ui.recorder-export-dialog/export-dialog))))))

#?(:cljs
   (deftest dialog-renders-snippet-from-events
     (testing "the dialog renders a snippet built from the captured events"
       (rf.story.ui.recorder-export-dialog/open-dialog!
         {:source-id :story.x/source
          :events    [[:counter/inc] [:counter/dec]]})
       (let [flat (str (rf.story.ui.recorder-export-dialog/export-dialog))]
         (is (str/includes? flat ":counter/inc")
             "captured events appear in the snippet")
         (is (str/includes? flat ":counter/dec"))
         (is (str/includes? flat ":story.x/source")
             "source-id appears via :extends")
         (is (str/includes? flat ":script")
             "snippet carries the public :script slot name (rf2-7mj4z)")
         (is (not (str/includes? flat ":play-script"))
             "snippet no longer emits the transitional :play-script slot")
         (is (str/includes? flat "story-recorder-export-snippet")
             ":data-test for the snippet pre tag")
         (is (str/includes? flat "story-recorder-export-copy")
             "copy button rendered")
         (is (str/includes? flat "story-recorder-export-replay")
             "replay button rendered")
         (is (str/includes? flat "story-recorder-export-close")
             "close button rendered")
         (is (str/includes? flat "story-recorder-export-auto-assert-checkbox")
             "auto-assert checkbox rendered")
         (is (str/includes? flat "story-recorder-export-name-input")
             "name input rendered")
         (is (str/includes? flat "story-recorder-export-variant-id-input")
             "variant-id input rendered")))))

#?(:cljs
   (deftest dialog-survives-fresh-recording
     (testing "the export dialog holds its OWN snapshot of the captured
              events, taken when the export-open handler runs; mutating the
              recorder afterwards (a fresh recording + a new keystroke, then
              a discard) does NOT change the in-flight export. Drives the REAL
              call-site (open-from-recorder-dialog!) with the recorder's live
              capture, then churns the recorder — exercising the
              snapshot-independence the test name promises, NOT a self-poke of
              the dialog's own ratom (rf2-x76af2.20)."
       ;; Seed the recorder with a completed capture.
       (rf.story.recorder/clear!)
       (rf.story.recorder/start-recording! :story.a/source)
       (rf.story.recorder/record-event! [:auth/login])
       (rf.story.recorder/record-event! [:counter/inc])
       (is (seq (rf.story.recorder/recorded-events))
           "sanity: the recorder captured the seeded events")
       ;; Drive the export-open handler EXACTLY as the toolbar :on-export
       ;; closure does — deref the recorder AT click time and pass the
       ;; snapshot. :source-id nil keeps the frame-db snapshot out of scope.
       (rf.story.ui.recorder-export-dialog/open-from-recorder-dialog!
         {:events    (rf.story.recorder/recorded-events)
          :entries   (rf.story.recorder/recorded-entries)
          :source-id nil})
       (let [snap-events  (:events  @rf.story.ui.recorder-export-dialog/ui-dialog)
             snap-entries (:entries @rf.story.ui.recorder-export-dialog/ui-dialog)]
         (is (str/includes? (str (rf.story.ui.recorder-export-dialog/export-dialog)) ":auth/login")
             "the export dialog snapshotted the captured events at open time")
         ;; MUTATE THE RECORDER: a fresh recording resets the atom, a new
         ;; keystroke lands, then a discard — the exact churn the snapshot
         ;; must survive. Were any link reading the recorder live, the
         ;; already-open export would change.
         (rf.story.recorder/start-recording! :story.b/other)
         (rf.story.recorder/record-event! [:counter/dec])
         (rf.story.recorder/clear!)
         (is (= snap-events (:events @rf.story.ui.recorder-export-dialog/ui-dialog))
             "recorder churn did not change the export dialog :events snapshot")
         (is (= snap-entries (:entries @rf.story.ui.recorder-export-dialog/ui-dialog))
             "recorder churn did not change the export dialog :entries snapshot")
         (let [after (str (rf.story.ui.recorder-export-dialog/export-dialog))]
           (is (str/includes? after ":auth/login")
               "the rendered export still carries the originally-captured events")
           (is (not (str/includes? after ":counter/dec"))
               "the post-open recorder keystroke never leaked into the export")))
       ;; Leave the recorder idle for the next test.
       (rf.story.recorder/clear!))))

#?(:cljs
   (deftest dialog-auto-assert-includes-assertions-in-snippet
     (testing "auto-assert ON yields a snippet containing :assert-db steps"
       (rf.story.ui.recorder-export-dialog/open-dialog!
         {:source-id :story.x/source
          :events    [[:counter/inc]]
          :final-db  {:n 5 :who "alice"}})
       (let [flat (str (rf.story.ui.recorder-export-dialog/export-dialog))]
         (is (str/includes? flat ":assert-db")
             "auto-assert ON produces trailing :assert-db steps in the snippet")))))

#?(:cljs
   (deftest dialog-without-final-db-omits-assertions
     (testing "auto-assert ON but no :final-db → no :assert-db steps"
       (rf.story.ui.recorder-export-dialog/open-dialog!
         {:source-id :story.x/source
          :events    [[:counter/inc]]
          :final-db  nil})
       (let [flat (str (rf.story.ui.recorder-export-dialog/export-dialog))]
         (is (not (str/includes? flat ":assert-db"))
             "nothing to assert against → no trailing block")))))
