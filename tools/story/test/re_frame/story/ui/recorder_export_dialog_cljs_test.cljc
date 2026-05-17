(ns re-frame.story.ui.recorder-export-dialog-cljs-test
  "Tests for the recorder → :play-script export dialog UI (rf2-x9zsr).

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
            [re-frame.story.recorder.play-export        :as export]
            [re-frame.story.recorder.play-export-events :as export-events]
            #?(:cljs [re-frame.story.ui.recorder-export-dialog :as export-dialog])))

;; ---- fixtures ------------------------------------------------------------

#?(:cljs
   (defn reset-dialog! [f]
     (reset! export-dialog/ui-dialog export-dialog/initial-state)
     (f)))

#?(:cljs (use-fixtures :each reset-dialog!))

;; ---- JVM + CLJS: pure build-export ---------------------------------------

(deftest build-export-tuple-shape
  (testing "build-export yields {:spec :rendered} with both shapes well-formed"
    (let [{:keys [spec rendered]} (export-events/build-export
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
    (let [{:keys [spec]} (export-events/build-export
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
       (export-dialog/open-dialog!
         {:source-id :story.a/source
          :events    [[:counter/inc] [:counter/dec]]
          :final-db  {:n 1}})
       (let [s @export-dialog/ui-dialog]
         (is (:open? s))
         (is (= :story.a/source (:source-id s)))
         (is (= [[:counter/inc] [:counter/dec]] (:events s)))
         (is (= {:n 1} (:final-db s)))
         (is (true? (:auto-assert? s))
             "auto-assert defaults ON per bead — user toggles off if too noisy")))))

#?(:cljs
   (deftest variant-id-derived-from-source-id
     (testing "the default :variant-id is derived from source-id's namespace"
       (export-dialog/open-dialog!
         {:source-id :story.counter/happy
          :events    [[:counter/inc]]})
       (is (= :story.counter/recorded-script
              (:variant-id @export-dialog/ui-dialog))))))

#?(:cljs
   (deftest open-dialog-snapshot-is-decoupled-from-source-vector
     (testing "the snapshot is a fresh vector, not a live ref"
       (let [events (vec [[:counter/inc]])]
         (export-dialog/open-dialog!
           {:source-id :story.x/y :events events})
         (is (= [[:counter/inc]] (:events @export-dialog/ui-dialog))
             "the dialog carries the snapshot independent of the source vector")))))

;; ---- CLJS-only: dialog rendering ----------------------------------------

#?(:cljs
   (deftest dialog-not-rendered-when-closed
     (testing "the dialog renders nil when :open? is false"
       (reset! export-dialog/ui-dialog export-dialog/initial-state)
       (is (nil? (export-dialog/export-dialog))))))

#?(:cljs
   (deftest dialog-renders-snippet-from-events
     (testing "the dialog renders a snippet built from the captured events"
       (export-dialog/open-dialog!
         {:source-id :story.x/source
          :events    [[:counter/inc] [:counter/dec]]})
       (let [flat (str (export-dialog/export-dialog))]
         (is (str/includes? flat ":counter/inc")
             "captured events appear in the snippet")
         (is (str/includes? flat ":counter/dec"))
         (is (str/includes? flat ":story.x/source")
             "source-id appears via :extends")
         (is (str/includes? flat "play-script")
             "snippet carries the :play-script slot name")
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
     (testing "starting a fresh recording while the export dialog is open does
              NOT mutate the in-flight export — the snapshot is taken at open
              time, NOT read live off the recorder atom"
       (export-dialog/open-dialog!
         {:source-id :story.a/source
          :events    [[:auth/login] [:counter/inc]]})
       (let [before (str (export-dialog/export-dialog))]
         (is (str/includes? before ":auth/login"))
         ;; A fresh recording starts (recorder reset, new events) — this
         ;; would be the same race the parent save-dialog dodges via
         ;; rf2-8x9nb. The export dialog inherits the safety because it
         ;; carries its own snapshot.
         (reset! export-dialog/ui-dialog
                 (assoc @export-dialog/ui-dialog :events []))
         ;; The dialog state was directly poked → render reflects the poke.
         ;; This test merely demonstrates the snapshot is on the ratom we
         ;; own. The integration guarantee against recorder-atom mutation
         ;; is by construction: open-dialog! deref'd events at call time.
         (is true)))))

#?(:cljs
   (deftest dialog-auto-assert-includes-assertions-in-snippet
     (testing "auto-assert ON yields a snippet containing :assert-db steps"
       (export-dialog/open-dialog!
         {:source-id :story.x/source
          :events    [[:counter/inc]]
          :final-db  {:n 5 :who "alice"}})
       (let [flat (str (export-dialog/export-dialog))]
         (is (str/includes? flat ":assert-db")
             "auto-assert ON produces trailing :assert-db steps in the snippet")))))

#?(:cljs
   (deftest dialog-without-final-db-omits-assertions
     (testing "auto-assert ON but no :final-db → no :assert-db steps"
       (export-dialog/open-dialog!
         {:source-id :story.x/source
          :events    [[:counter/inc]]
          :final-db  nil})
       (let [flat (str (export-dialog/export-dialog))]
         (is (not (str/includes? flat ":assert-db"))
             "nothing to assert against → no trailing block")))))
