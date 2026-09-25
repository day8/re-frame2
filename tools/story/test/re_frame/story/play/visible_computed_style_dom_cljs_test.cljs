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
  (:require [cljs.test :refer-macros [deftest is testing]]
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
  (if-not (browser?)
    (is true ":node-test: no DOM — the browser-test runner exercises these assertions")
    (let [root      (block! js/document.body "vis-root" "")
          shown     (block! root "vis-shown" "")
          hidden    (block! root "vis-hidden" "visibility: hidden;")
          collapsed (block! root "vis-collapsed" "visibility: collapse;")
          parent    (block! root "vis-parent" "visibility: hidden;")
          child     (block! parent "vis-child" "")
          none      (block! root "vis-none" "display: none;")]
      (try
        (testing "control: an element with a box and visible computed visibility is visible"
          (is (true? (rf.story.play.dom/visible? shown))))
        (testing "control: the visibility fixtures keep their layout box"
          (is (pos? (.-offsetWidth hidden)))
          (is (pos? (.-offsetWidth collapsed)))
          (is (pos? (.-offsetWidth child))))
        (testing "visibility: hidden reads as not visible"
          (is (false? (rf.story.play.dom/visible? hidden))))
        (testing "visibility: collapse reads as not visible"
          (is (false? (rf.story.play.dom/visible? collapsed))))
        (testing "a child inherits its parent's visibility: hidden"
          (is (false? (rf.story.play.dom/visible? child))))
        (testing "the layout-box rule still holds: display: none is not visible"
          (is (false? (rf.story.play.dom/visible? none))))
        (testing "the :assert-dom modes follow visible?"
          (is (false? (:passed? (rf.story.play.dom/assert-visible (sel "vis-hidden") :visible))))
          (is (true?  (:passed? (rf.story.play.dom/assert-visible (sel "vis-hidden") :hidden))))
          (is (true?  (:passed? (rf.story.play.dom/assert-visible (sel "vis-shown") :visible)))))
        (finally
          (detach! root))))))
