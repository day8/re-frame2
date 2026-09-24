(ns re-frame.story.play.dom-canvas-scope-dom-cljs-test
  "rf2-3x7nj.30.5: the play runner's DOM steps resolve their selector under
  the Story canvas root, where the recorder captured it.

  The recorder listens on the canvas root only, and an element with no
  `data-test` / `id` / `aria-label` records the positional fallback
  `tag:nth-of-type(N)`. Resolved over the whole page, `input:nth-of-type(1)`
  is the first `<input>` in document order — Story's sidebar search box,
  which precedes the canvas — so a recorded \"type into the form, click
  Save\" replayed into Story's chrome and left the variant's form untouched.

  The page here is that shape in plain DOM: a chrome strip carrying an
  input and a button, then a canvas frame (the shell's hook) carrying the
  variant's form. `the-hook-is-what-scopes` is the control: with the
  frame's hook removed the same selectors resolve document-wide again, so
  it is the canvas root, not the fixture, that keeps the steps in the form.

  `-dom-cljs-test` opts the file into `:browser-test`; `:node-test` loads it
  too and each test states its skip, as the sibling DOM suites do."
  (:require [cljs.test :refer-macros [deftest is testing]]
            [re-frame.story.play.dom :as rf.story.play.dom]))

(defn- browser? []
  (and (exists? js/document)
       (some? (.-createElement js/document))))

(defn- el!
  "Create a `tag` element, append it to `parent`, and return it."
  [parent tag]
  (let [el (js/document.createElement tag)]
    (.appendChild parent el)
    el))

(defn- page!
  "Build the chrome-then-canvas page. Returns the nodes by role, with a
  click counter per button."
  []
  (let [chrome        (el! js/document.body "div")
        chrome-input  (el! chrome "input")
        chrome-button (el! chrome "button")
        frame         (el! js/document.body "div")
        form          (el! frame "form")
        canvas-input  (el! form "input")
        canvas-button (el! form "button")
        clicks        (atom {:chrome 0 :canvas 0})]
    (.setAttribute frame "data-test" "story-canvas-frame")
    (.setAttribute canvas-button "type" "button")
    (.setAttribute chrome-button "type" "button")
    (.addEventListener chrome-button "click" #(swap! clicks update :chrome inc))
    (.addEventListener canvas-button "click" #(swap! clicks update :canvas inc))
    {:chrome chrome :frame frame
     :chrome-input chrome-input :canvas-input canvas-input
     :clicks clicks}))

(defn- teardown! [{:keys [chrome frame]}]
  (doseq [n [chrome frame]]
    (when (.-parentNode n) (.removeChild (.-parentNode n) n))))

(deftest recorded-positional-steps-replay-inside-the-canvas
  (if-not (browser?)
    (is true ":node-test: no DOM — the browser-test runner exercises these assertions")
    (let [page (page!)]
      (try
        (testing "the positional selectors resolve to the canvas's elements"
          (is (identical? (:canvas-input page)
                          (rf.story.play.dom/query "input:nth-of-type(1)"))))
        (testing "a :type step types into the variant's input, not Story's"
          (is (true? (rf.story.play.dom/type! "input:nth-of-type(1)" "bob")))
          (is (= "bob" (.-value (:canvas-input page))))
          (is (= "" (.-value (:chrome-input page)))
              "the chrome input is untouched"))
        (testing "a :click step clicks the variant's button, not Story's"
          (is (true? (rf.story.play.dom/click! "button:nth-of-type(1)")))
          (is (= {:chrome 0 :canvas 1} @(:clicks page))))
        (finally
          (teardown! page))))))

(deftest the-hook-is-what-scopes
  (if-not (browser?)
    (is true ":node-test: no DOM — the browser-test runner exercises these assertions")
    (let [page (page!)]
      (try
        (.removeAttribute (:frame page) "data-test")
        (testing "control: with no canvas root the document is the scope, and
                  the first input in document order is not the form's"
          (is (some? (rf.story.play.dom/query "input:nth-of-type(1)")))
          (is (not (identical? (:canvas-input page)
                               (rf.story.play.dom/query "input:nth-of-type(1)")))))
        (finally
          (teardown! page))))))
