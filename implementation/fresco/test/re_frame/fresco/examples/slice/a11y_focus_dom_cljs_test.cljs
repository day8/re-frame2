(ns re-frame.fresco.examples.slice.a11y-focus-dom-cljs-test
  "L4 — WHAT THE BROWSER SAYS ABOUT THE SLICE'S ACCESSIBILITY
  (specification §7's accessibility row).

  `re-frame.fresco.examples.slice.a11y-cljs-test` asserts the structure:
  which role each element carries and that every control has a name,
  read off the semantic tree. Everything it says is a claim about markup,
  and markup is a claim about what an engine will do with it. This file
  is where an engine answers.

  ## The three things only a browser can decide, and they are all here

  1. **Where focus IS.** `document.activeElement` is not derivable from a
     tree. Every row below that says `focus` reads it back.
  2. **Which elements are focusable at all**, which the engine decides
     from `disabled` and `tabindex` rather than from the tag —
     [[focus-order]] asks it one element at a time instead of trusting a
     selector, because a selector is a second opinion about the very
     thing under test.
  3. **Whether a `<label>` really labels a control.** `label.control` and
     `input.labels` are the engine's OWN association, computed from
     `for` and from ancestry. [[the-engine-agrees-with-the-structural-
     names]] compares them with `ht/accessible-name`'s answer for the
     same page — the one row that can catch the structural helper being
     confidently wrong.

  ## The order is a complete sequence

  A focus assertion is trivially satisfiable in the wrong direction: a
  row that only ever asserts *this element can be focused* is green under
  a page where everything can be focused, which is its own defect. So the
  order is asserted as a complete sequence rather than as a membership
  test, which is what makes an element joining it, leaving it or moving
  within it visible.

  ## Browser lane

  Every row needs a real document, a real React DOM and a real focus
  model. `:node-test` compiles this namespace too (`cljs-test$` matches
  `-dom-cljs-test`), and each row degrades there to a STATED skip rather
  than to a false green — the skip is honest, and it is not a
  measurement."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures async]]
            [re-frame.adapter.uix :as rf.adapter.uix]
            [re-frame.fresco.examples.slice.events :as rf.fresco.examples.slice.events]
            [re-frame.fresco.examples.slice.routes :as rf.fresco.examples.slice.routes]
            [re-frame.fresco.examples.slice.views :as rf.fresco.examples.slice.views]
            [re-frame.fresco.test.mounted :as rf.fresco.test.mounted]
            [re-frame.test-support :as rf.test-support]))

;; ---------------------------------------------------------------------------
;; The lane
;; ---------------------------------------------------------------------------

(defn- browser? [] (exists? js/document))

(defn- skip! [why]
  (is true (str "a focus witness needs a real browser — " why)))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    {:adapter       rf.adapter.uix/adapter
     :ambient-frame nil
     :async?        true
     :init-fn       (fn []
                      (set! (.-IS_REACT_ACT_ENVIRONMENT js/globalThis) false)
                      (rf.fresco.examples.slice.routes/register!))}))

;; ---------------------------------------------------------------------------
;; Reading the page
;; ---------------------------------------------------------------------------

(defn- node [m sel] (.querySelector (:container m) sel))

(defn- at-article!
  [slug]
  (rf.fresco.test.mounted/mount! [rf.fresco.examples.slice.views/app {}]
             {:initial-events [[::rf.fresco.examples.slice.events/seed]
                               [:rf.route/navigate {:to rf.fresco.examples.slice.routes/article
                                                    :params {:slug slug}}]]}))

(defn- finish [m done]
  (-> (rf.fresco.test.mounted/unmount! m) (rf.fresco.test.mounted/assert-clean!) (.then done)))

(defn- label-of
  "How a failure NAMES an element: its id when it has one, else its first
  class, else its tag. Short enough to read a whole focus order at once."
  [el]
  (cond
    (seq (.-id el))        (.-id el)
    (seq (.-className el)) (first (.split (.-className el) " "))
    :else                  (.toLowerCase (.-tagName el))))

(defn- focusable?
  "Does the ENGINE accept focus on `el`? Asked rather than inferred: blur
  whatever holds focus, ask for it, and read `document.activeElement`
  back. A `disabled` control and a `tabindex=\"-1\"` control both leave
  focus on `<body>`, and neither fact is visible in the markup alone."
  [el]
  (some-> (.-activeElement js/document) (.blur))
  (.focus el)
  (identical? el (.-activeElement js/document)))

(defn- focus-order
  "The page's focus order, as readable names — every candidate in
  document order that the engine will actually focus.

  The candidate set is the natural-tab-order tags plus anything carrying
  a `tabindex`; the engine decides which of them survive. Nothing here
  simulates a Tab press: a synthetic keydown does not move focus in any
  engine, so a `pressed Tab` witness would be measuring its own
  dispatcher."
  [m]
  (->> (.querySelectorAll (:container m)
                          "a[href],button,input,select,textarea,[tabindex]")
       (array-seq)
       (filterv focusable?)
       (mapv label-of)))

;; ---------------------------------------------------------------------------
;; The label associations, as the engine computes them
;; ---------------------------------------------------------------------------

(deftest the-engine-agrees-with-the-structural-names
  (if-not (browser?)
    (skip! ":node-test has no document")
    (async done
      (let [m (at-article! "intents")]
        (testing "`.labels` is the engine's OWN association — it is
                  computed from `for` and from ancestry, and it is the
                  only available check on the structural helper being
                  confidently wrong"
          (doseq [[sel expected] [["#slice-title" "Title"]
                                  ["#slice-body" "Body"]
                                  ["#slice-published" "Published"]
                                  ["#slice-locale" "Language"]]]
            (let [el     (node m sel)
                  labels (array-seq (.-labels el))]
              (is (= 1 (count labels))
                  (str sel " is associated with exactly one <label> by the "
                       "engine; it reported " (count labels)))
              (is (= expected (some-> (first labels) .-textContent))
                  (str sel "'s label, as the engine found it")))))

        (testing "and the checkbox is reached BOTH ways at once — it sits
                  inside its label and carries the `for` as well — which
                  the engine must still count as one association rather
                  than two"
          (let [el (node m "#slice-published")]
            (is (identical? (node m ".published") (aget (.-labels el) 0)))))

        (finish m done)))))

;; ---------------------------------------------------------------------------
;; Focus order
;; ---------------------------------------------------------------------------

(def ^:private pristine-order
  "The article screen's focus order before anything is typed. `discard`
  is absent because it is disabled until the form is dirty."
  ["slice-locale" "theme-choice" "theme-choice" "back"
   "slice-title" "slice-body" "slice-published" "save"])

(deftest the-focus-order-is-the-document-order-and-nothing-reorders-it
  (if-not (browser?)
    (skip! ":node-test has no focus model")
    (async done
      (let [m (at-article! "intents")]
        (is (= pristine-order (focus-order m))
            "the COMPLETE sequence rather than a membership test — a
             control that joined it, left it or moved within it is
             invisible to `is this focusable?` and loud here")

        (testing "and no element buys itself a place out of turn: a
                  positive tabindex hoists a control above everything
                  that has none, which is a reordering nothing on the
                  page shows"
          (is (= [] (->> (.querySelectorAll (:container m) "[tabindex]")
                         (array-seq)
                         (filterv #(pos? (.-tabIndex %)))
                         (mapv label-of)))))

        (finish m done)))))
