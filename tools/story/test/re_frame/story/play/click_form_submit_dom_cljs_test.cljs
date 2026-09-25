(ns re-frame.story.play.click-form-submit-dom-cljs-test
  "A recorded form submission replays as a SUBMISSION.

  The recorder captures a submit no click represents as a `:dom/submit`
  entry, and the translator exports it as `[:click form-selector]`. A
  click event dispatched at a `<form>` element submits nothing, so the
  replay step submits the form with `requestSubmit()` instead: constraint
  validation runs, and the form's `submit` event fires, as they do for a
  user's submission.

  The step under test is the one the translator emits for a `:dom/submit`
  entry, run through `re-frame.story.play.dom/click!` — the call a `:click`
  step makes.

  `-dom-cljs-test` opts the file into `:browser-test`; `:node-test` loads it
  too and each test states its skip, as the sibling DOM suites do."
  (:require [cljs.test :refer-macros [deftest is testing]]
            [re-frame.story.play.dom :as rf.story.play.dom]
            [re-frame.story.recorder.play-export :as rf.story.recorder.play-export]))

(defn- browser? []
  (and (exists? js/document)
       (some? (.-createElement js/document))))

(defn- form!
  "Append a `<form>` carrying `data-test` `hook` and one text `<input>` to
  the body, counting its `submit` events. Each submit is prevented, as a
  variant's own handler prevents it, so the page stays put. Returns
  `{:form :input :submits}`."
  [hook]
  (let [form    (js/document.createElement "form")
        input   (js/document.createElement "input")
        submits (atom 0)]
    (.setAttribute form "data-test" hook)
    (.setAttribute input "type" "text")
    (.appendChild form input)
    (.addEventListener form "submit" (fn [e] (.preventDefault e) (swap! submits inc)))
    (.appendChild js/document.body form)
    {:form form :input input :submits submits}))

(defn- replay-step
  "The step the translator exports for a recorded submit of `selector`."
  [selector]
  (rf.story.recorder.play-export/entry->step
    {:kind :dom/submit :selector selector :t 0}))

(defn- detach! [n]
  (when (.-parentNode n) (.removeChild (.-parentNode n) n)))

(deftest a-recorded-submit-replays-as-a-submission
  (if-not (browser?)
    (is true ":node-test: no DOM — the browser-test runner exercises these assertions")
    (let [{:keys [form submits]} (form! "submit-replay")
          step                   (replay-step "[data-test=\"submit-replay\"]")]
      (try
        (testing "control: the exported step is a :click on the form's selector"
          (is (= [:click "[data-test=\"submit-replay\"]"] step)))
        (testing "replaying it fires the form's submit event once"
          (is (true? (rf.story.play.dom/click! (second step))))
          (is (= 1 @submits)))
        (finally
          (detach! form))))))

(deftest a-replayed-submit-runs-constraint-validation
  (if-not (browser?)
    (is true ":node-test: no DOM — the browser-test runner exercises these assertions")
    (let [{:keys [form input submits]} (form! "submit-validate")
          step                         (replay-step "[data-test=\"submit-validate\"]")]
      (try
        (.setAttribute input "required" "")
        (testing "an empty required field blocks the submission, as it blocks a user's"
          (is (true? (rf.story.play.dom/click! (second step))))
          (is (= 0 @submits)))
        (testing "once the field is filled, the same step submits"
          (set! (.-value input) "alice")
          (is (true? (rf.story.play.dom/click! (second step))))
          (is (= 1 @submits)))
        (finally
          (detach! form))))))
