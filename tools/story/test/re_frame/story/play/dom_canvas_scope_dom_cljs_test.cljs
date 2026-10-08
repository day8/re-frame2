(ns re-frame.story.play.dom-canvas-scope-dom-cljs-test
  "The play runner's DOM steps resolve their selector under
  the Story canvas root, where the recorder captured it.

  The recorder listens on the canvas root only, and an element with no
  `data-test` / `id` / `aria-label` records the positional fallback
  `tag:nth-of-type(N)`. Resolved over the whole page, `input:nth-of-type(1)`
  is the first `<input>` in document order — Story's sidebar search box,
  which precedes the canvas — so a recorded \"type into the form, click
  Save\" would replay into Story's chrome and leave the variant's form
  untouched.

  The page here is that shape in plain DOM: a chrome strip carrying an
  input and a button, then a canvas frame (the shell's hook) carrying the
  variant's form. `the-hook-is-what-scopes` is the control: with the
  frame's hook removed the same selectors resolve document-wide again, so
  it is the canvas root, not the fixture, that keeps the steps in the form.

  The canvas is the FIRST scope, not the only one. A view can
  render outside it — a modal or popover portalled into `document.body` —
  so a selector that matches nothing under the canvas is retried against
  the document, unless it is positional. The last three tests pin the three
  edges: a stable hook reaches a portalled node; a positional selector with
  no canvas match stays unresolved rather than reaching Story's chrome; and
  a stable hook that matches in the canvas resolves there, though an
  identical one precedes it in the chrome.

  `-dom-cljs-test` opts the file into `:browser-test`; `:node-test` loads it
  too and each test states its skip, as the sibling DOM suites do."
  (:require [cljs.test :refer-macros [deftest is]]
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
  ;; a :type step types into the variant's input and a :click step clicks
  ;; the variant's button, not Story's
  (if-not (browser?)
    (is true ":node-test: no DOM — the browser-test runner exercises these assertions")
    (let [page (page!)]
      (try
        (is (= [true "bob" ""]
               [(rf.story.play.dom/type! "input:nth-of-type(1)" "bob")
                (.-value (:canvas-input page))
                (.-value (:chrome-input page))]))
        (is (= [true {:chrome 0 :canvas 1}]
               [(rf.story.play.dom/click! "button:nth-of-type(1)") @(:clicks page)]))
        (finally
          (teardown! page))))))

(deftest the-hook-is-what-scopes
  ;; control: with no canvas root the document is the scope, and the first
  ;; input in document order is not the form's
  (if-not (browser?)
    (is true ":node-test: no DOM — the browser-test runner exercises these assertions")
    (let [page (page!)]
      (try
        (.removeAttribute (:frame page) "data-test")
        (let [q (rf.story.play.dom/query "input:nth-of-type(1)")]
          (is (and (some? q) (not (identical? (:canvas-input page) q)))))
        (finally
          (teardown! page))))))

;; ---- the document fallback ----------------------------------------

(defn- button!
  "Append a `type=button` carrying `data-test` `hook` to `parent`, counting
  its clicks under `k` in `clicks`. Returns the button."
  [parent hook clicks k]
  (let [b (el! parent "button")]
    (.setAttribute b "type" "button")
    (.setAttribute b "data-test" hook)
    (.addEventListener b "click" #(swap! clicks update k (fnil inc 0)))
    b))

(defn- detach! [n]
  (when (.-parentNode n) (.removeChild (.-parentNode n) n)))

(deftest a-stable-selector-reaches-a-portalled-node
  ;; a stable hook the canvas does not match falls back to the document, so a
  ;; recorded :click on a modal's button replays in the shell
  (if-not (browser?)
    (is true ":node-test: no DOM — the browser-test runner exercises these assertions")
    (let [page   (page!)
          ;; The modal's own root, straight under `document.body`, as a
          ;; portalling dialog component renders it — outside the canvas.
          portal (el! js/document.body "div")
          clicks (atom {})
          ok     (button! portal "modal-ok" clicks :portal)]
      (try
        (is (= [ok] (rf.story.play.dom/query-all "[data-test=\"modal-ok\"]")))
        (is (= [true {:portal 1}]
               [(rf.story.play.dom/click! "[data-test=\"modal-ok\"]") @clicks]))
        (finally
          (detach! portal)
          (teardown! page))))))

(deftest a-positional-selector-never-leaves-the-canvas
  ;; A positional selector with no canvas match is not retried against the
  ;; document, so it cannot reach Story's chrome. The control: the same node
  ;; IS reachable by a stable hook, so the selector's kind is what stops it.
  (if-not (browser?)
    (is true ":node-test: no DOM — the browser-test runner exercises these assertions")
    (let [page (page!)
          ;; Story's chrome carries a textarea; the variant's form has none.
          area (el! (:chrome page) "textarea")]
      (try
        (is (= [[] false ""]
               [(rf.story.play.dom/query-all "textarea:nth-of-type(1)")
                (rf.story.play.dom/type! "textarea:nth-of-type(1)" "bob")
                (.-value area)]))
        (.setAttribute area "data-test" "chrome-note")
        (is (identical? area (rf.story.play.dom/query "[data-test=\"chrome-note\"]")))
        (finally
          (teardown! page))))))

(deftest a-stable-selector-prefers-the-canvas-copy
  ;; the fallback is for a canvas MISS only: a hook the canvas matches
  ;; resolves there, not to the chrome's identical one
  (if-not (browser?)
    (is true ":node-test: no DOM — the browser-test runner exercises these assertions")
    (let [page        (page!)
          clicks      (atom {})
          ;; The chrome's copy comes first in document order.
          _chrome     (button! (:chrome page) "save" clicks :chrome)
          canvas-save (button! (:frame page) "save" clicks :canvas)]
      (try
        (is (= [canvas-save] (rf.story.play.dom/query-all "[data-test=\"save\"]")))
        (is (= [true {:canvas 1}]
               [(rf.story.play.dom/click! "[data-test=\"save\"]") @clicks]))
        (finally
          (teardown! page))))))
