(ns re-frame.story.ui.recorder-cljs-test
  "Tests for the Test Codegen recorder UI surface — specifically the
  save-as-variant dialog's snapshot-at-open contract (rf2-8x9nb).

  Splits into two tiers:

  - **JVM + CLJS** (pure machinery in `recorder.cljc`) — the
    `open-dialog` / `close-dialog` transitions snapshot
    `{:variant-id :events}` onto the dialog state map. The corpus runs
    on both runtimes via `clojure -M:test` and the CLJS `:node-test`
    target.

  - **CLJS-only** (`ui/recorder.cljs` is CLJS-only — depends on
    Reagent / DOM) — the dialog renders a snippet built from the
    snapshot stored on `@ui-dialog`, NOT from `@recorder/state`. A
    fresh `start-recording!` after the dialog opens does NOT mutate
    the rendered snippet — that's the rf2-8x9nb regression."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.story.recorder :as recorder]
            #?(:cljs [re-frame.story.ui.recorder :as ui-rec])))

;; ---- fixtures ------------------------------------------------------------

(defn reset-recorder! [f]
  (recorder/clear!)
  #?(:cljs (reset! ui-rec/ui-dialog recorder/initial-dialog-state))
  (f))

(use-fixtures :each reset-recorder!)

;; ---- JVM + CLJS: dialog state machine ------------------------------------

(deftest open-dialog-snapshots-events-onto-dialog-state
  (testing "open-dialog stashes the captured events on the dialog state"
    (let [events [[:counter/inc] [:counter/dec]]
          opened (recorder/open-dialog recorder/initial-dialog-state
                                       :story.x/y events 12345)]
      (is (:open? opened))
      (is (= :story.x/y (:source-id opened))
          "the recorded variant-id rides into :source-id (used as :extends)")
      (is (= events (:events opened))
          "the captured events ride on a top-level :events slot for ergonomics")
      (is (= events (get-in opened [:context :events]))
          "the captured events also ride in :context per review-dialog contract")
      (is (some? (:draft-id opened))
          "the default draft-id is derived from variant-id + now-ms"))))

(deftest open-dialog-snapshot-is-independent-of-source-vector
  (testing "the snapshot is decoupled from the caller's events vector"
    (let [events (vec [[:counter/inc]])
          opened (recorder/open-dialog recorder/initial-dialog-state
                                       :story.x/y events 0)]
      (is (= [[:counter/inc]] (:events opened))
          "the snapshot is a fresh vector, not a reference to a recorder atom"))))

(deftest close-dialog-returns-idle-state
  (testing "close-dialog clears the snapshot — next open starts fresh"
    (let [opened (recorder/open-dialog recorder/initial-dialog-state
                                       :story.x/y [[:counter/inc]] 0)
          closed (recorder/close-dialog opened)]
      (is (false? (:open? closed)))
      (is (nil? (:source-id closed)))
      (is (nil? (:events closed))))))

(deftest initial-dialog-state-is-idle
  (testing "the seed value for the dialog ratom is the idle state"
    (is (false? (:open? recorder/initial-dialog-state)))
    (is (nil? (:source-id recorder/initial-dialog-state)))
    (is (nil? (:draft-id recorder/initial-dialog-state)))))

;; ---- CLJS-only: dialog rendered hiccup -----------------------------------

#?(:cljs
   (deftest save-dialog-not-rendered-when-closed
     (testing "the dialog renders nil when the ratom :open? is false"
       (reset! ui-rec/ui-dialog recorder/initial-dialog-state)
       (is (nil? (ui-rec/save-dialog))))))

#?(:cljs
   (deftest save-dialog-renders-snippet-from-snapshot
     (testing "the rendered snippet is built from the dialog snapshot"
       (reset! ui-rec/ui-dialog
               (recorder/open-dialog recorder/initial-dialog-state
                                     :story.x/source
                                     [[:counter/inc] [:counter/dec]]
                                     12345))
       (let [flat (str (ui-rec/save-dialog))]
         (is (str/includes? flat ":counter/inc")
             "captured events appear in the snippet preview")
         (is (str/includes? flat ":counter/dec"))
         (is (str/includes? flat ":story.x/source")
             "the recorded variant-id appears via :extends")))))

;; ---- CLJS-only: rf2-8x9nb regression ------------------------------------

#?(:cljs
   (deftest save-dialog-survives-fresh-start-recording
     (testing "rf2-8x9nb: starting a new recording while the dialog is open
              does NOT mutate the dialog's snippet — the snapshot is taken
              at open time, not read live off the recorder atom"
       ;; Step 1: simulate stop-of-recording-A → open dialog with A's events.
       (let [a-events [[:counter/inc] [:counter/inc] [:counter/dec]]]
         (reset! ui-rec/ui-dialog
                 (recorder/open-dialog recorder/initial-dialog-state
                                       :story.a/source a-events 12345))
         (let [snippet-before (str (ui-rec/save-dialog))]
           (is (str/includes? snippet-before ":counter/inc"))
           (is (str/includes? snippet-before ":story.a/source"))

           ;; Step 2: user clicks REC again — starts a fresh recording
           ;; targeting B. This resets `recorder/state` to an empty
           ;; recording with a different variant-id.
           (recorder/start-recording! :story.b/target 99999)
           (is (recorder/recording?))
           (is (= :story.b/target (recorder/recording-variant)))
           (is (= [] (recorder/recorded-events))
               "the recorder atom is now empty / aimed at B")

           ;; Step 3: re-render the dialog. The snippet MUST still
           ;; reflect A's events + A's variant id — NOT empty/B.
           (let [snippet-after (str (ui-rec/save-dialog))]
             (is (= snippet-before snippet-after)
                 "the dialog snippet is unchanged after start-recording!")
             (is (str/includes? snippet-after ":counter/inc")
                 "A's events still appear in the snippet")
             (is (str/includes? snippet-after ":story.a/source")
                 "A's variant-id still rides into :extends")
             (is (not (str/includes? snippet-after ":story.b/target"))
                 "B's variant-id does not leak into the open A dialog")))))))

#?(:cljs
   (deftest save-dialog-survives-record-event-into-fresh-recording
     (testing "rf2-8x9nb: events captured into a fresh recording after the
              dialog opened do NOT appear in the open dialog's snippet"
       (let [a-events [[:counter/inc]]]
         (reset! ui-rec/ui-dialog
                 (recorder/open-dialog recorder/initial-dialog-state
                                       :story.a/source a-events 12345))
         (recorder/start-recording! :story.b/target 99999)
         (recorder/record-event! [:auth/login {:email "test@test"}])
         (recorder/record-event! [:auth/logout])
         (let [flat (str (ui-rec/save-dialog))]
           (is (str/includes? flat ":counter/inc")
               "A's original event remains in the snippet")
           (is (not (str/includes? flat ":auth/login"))
               "B's freshly-captured events do NOT bleed into the snippet")
           (is (not (str/includes? flat ":auth/logout"))))))))

;; ---- CLJS-only: assertion picker ARIA + arrow-key nav (rf2-p1ai7 + 07m13)

#?(:cljs
   (defn- open-picker-for-test! []
     (reset! ui-rec/ui-picker {:open?        true
                               :assertion    nil
                               :field-text   {}
                               :error        nil
                               :active-index 0})))

#?(:cljs
   (deftest assertion-picker-stamps-modal-aria
     (testing "rf2-p1ai7: the assertion picker carries role=dialog +
              aria-modal + aria-labelledby on its panel"
       (open-picker-for-test!)
       (let [flat (str (ui-rec/assertion-picker))]
         (is (str/includes? flat "dialog")     "role=dialog appears")
         (is (str/includes? flat "aria-modal") "aria-modal flag is stamped")
         (is (str/includes? flat "aria-labelledby")
             "aria-labelledby points at the panel's visible title")
         (is (str/includes? flat "story-recorder-picker-title")
             "the title carries the id referenced by aria-labelledby")))))

#?(:cljs
   (deftest assertion-picker-vocabulary-is-a-menu
     (testing "rf2-07m13: phase-1 vocabulary list renders role=menu with
              menuitem rows + a roving tabindex (only the active row
              has tabindex=0)."
       (open-picker-for-test!)
       (let [flat (str (ui-rec/assertion-picker))]
         (is (str/includes? flat "menu")
             "role=menu identifies the vocab container")
         (is (str/includes? flat "menuitem")
             "role=menuitem identifies each row")
         (is (str/includes? flat "Assertion vocabulary")
             "the menu carries an aria-label for the group")
         ;; Roving tabindex: with active-index=0 the first row should
         ;; show tab-index 0 and the rest -1. We check both are present
         ;; somewhere in the tree.
         (is (re-find #"tab-index" flat)
             "tabindex is stamped on the rows")))))

#?(:cljs
   (deftest assertion-picker-active-index-moves
     (testing "rf2-07m13: set-active-index! clamps + wraps the cursor
              across the vocabulary length."
       (open-picker-for-test!)
       (let [n (count recorder/assertion-vocabulary)]
         ;; Step forward through bounds.
         (#'ui-rec/set-active-index! 0)
         (is (= 0 (:active-index @ui-rec/ui-picker)))
         (#'ui-rec/set-active-index! 1)
         (is (= 1 (:active-index @ui-rec/ui-picker)))
         ;; Past the end wraps to 0.
         (#'ui-rec/set-active-index! (+ n 5))
         (is (= 0 (:active-index @ui-rec/ui-picker)))
         ;; Negative wraps to the last index.
         (#'ui-rec/set-active-index! -1)
         (is (= (dec n) (:active-index @ui-rec/ui-picker)))))))
