(ns re-frame.story.play.visible-computed-style-dom-cljs-test
  "`re-frame.story.play.dom/visible?` — the predicate behind
  `[:assert-dom sel :visible]` / `:hidden` — applies both rules a test
  author means by visible: the element has a layout box, and its computed
  `visibility` is neither `hidden` nor `collapse`.

  The two rules are independent. A `visibility: hidden` element keeps its
  layout box, so the box rule alone reads it as visible; a `display: none`
  element has no box whatever its `visibility`. Each fixture here breaks
  exactly one rule, beside a control that breaks neither.

  `-dom-cljs-test` opts the file into `:browser-test`; `:node-test` loads it
  too and each test states its skip, as the sibling DOM suites do."
  (:require [cljs.test :refer-macros [deftest is]]
            [re-frame.story.play.dom :as rf.story.play.dom]))

(defn- browser? []
  (and (exists? js/document)
       (some? (.-createElement js/document))))

(defn- block!
  "Append a 40x20 `div` carrying `data-test` `hook` to `parent`, with the
  inline `style` declarations appended after the size. Returns it."
  [parent hook style]
  (let [el (js/document.createElement "div")]
    (.setAttribute el "data-test" hook)
    (.setAttribute el "style" (str "width: 40px; height: 20px; " style))
    (.appendChild parent el)
    el))

(defn- sel [hook]
  (str "[data-test=\"" hook "\"]"))

(defn- detach! [n]
  (when (.-parentNode n) (.removeChild (.-parentNode n) n)))

(deftest visible-requires-a-box-and-a-visible-computed-visibility
  ;; The child inherits its parent's `visibility: hidden`, which only the
  ;; COMPUTED style carries; `display: none` breaks the box rule.
  (if-not (browser?)
    (is true ":node-test: no DOM — the browser-test runner exercises these assertions")
    (let [root   (block! js/document.body "vis-root" "")
          shown  (block! root "vis-shown" "")
          hidden (block! root "vis-hidden" "visibility: hidden;")
          parent (block! root "vis-parent" "visibility: hidden;")
          child  (block! parent "vis-child" "")
          none   (block! root "vis-none" "display: none;")]
      (try
        (is (= [true false false false]
               (mapv rf.story.play.dom/visible? [shown hidden child none])))
        (is (= [false true true]
               (mapv (comp :passed? #(apply rf.story.play.dom/assert-visible %))
                     [[(sel "vis-hidden") :visible]
                      [(sel "vis-hidden") :hidden]
                      [(sel "vis-shown") :visible]]))
            "the :assert-dom modes follow visible?")
        (finally
          (detach! root))))))
