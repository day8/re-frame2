(ns re-frame.hicasso.examples.slice.a11y-focus-dom-cljs-test
  "L4 — WHAT THE BROWSER SAYS ABOUT THE SLICE'S ACCESSIBILITY
  (rf2-hic-043; specification §7's accessibility row).

  `re-frame.hicasso.examples.slice.a11y-cljs-test` asserts the structure:
  which role each element carries and which label names which control,
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

  ## The two directions every row is written for

  A focus assertion is trivially satisfiable in the wrong direction: a
  row that only ever asserts *this element can be focused* is green under
  a page where everything can be focused, which is its own defect. So the
  focusability rows come in pairs. `.discard` is disabled until the form
  is dirty, so it is **absent** from the pristine page's focus order and
  **present** after one keystroke; `.save` is disabled while a save is in
  flight, so it leaves the order and comes back. The order is asserted as
  a complete sequence rather than as a membership test, which is what
  makes an element joining it, leaving it or moving within it visible.

  ## Browser lane

  Every row needs a real document, a real React DOM and a real focus
  model. `:node-test` compiles this namespace too (`cljs-test$` matches
  `-dom-cljs-test`), and each row degrades there to a STATED skip rather
  than to a false green — the skip is honest, and it is not a
  measurement."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures async]]
            [re-frame.adapter.uix :as uix-adapter]
            [re-frame.core :as rf]
            [re-frame.hicasso.examples.slice.events :as events]
            [re-frame.hicasso.examples.slice.routes :as routes]
            [re-frame.hicasso.examples.slice.subs :as subs]
            [re-frame.hicasso.examples.slice.views :as views]
            [re-frame.hicasso.test.mounted :as hm]
            [re-frame.test-support :as test-support]))

;; ---------------------------------------------------------------------------
;; The lane
;; ---------------------------------------------------------------------------

(defn- browser? [] (exists? js/document))

(defn- skip! [why]
  (is true (str "a focus witness needs a real browser — " why)))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture
    {:adapter       uix-adapter/adapter
     :ambient-frame nil
     :async?        true
     :init-fn       (fn []
                      (set! (.-IS_REACT_ACT_ENVIRONMENT js/globalThis) false)
                      (routes/register!))}))

;; ---------------------------------------------------------------------------
;; Reading the page
;; ---------------------------------------------------------------------------

(defn- node [m sel] (.querySelector (:container m) sel))

(defn- at-article!
  [slug]
  (hm/mount! [views/app {}]
             {:initial-events [[::events/seed]
                               [:rf.route/navigate {:to routes/article
                                                    :params {:slug slug}}]]}))

(defn- finish [m done]
  (-> (hm/unmount! m) (hm/assert-clean!) (.then done)))

(defn- read-sub [m query-v] (rf/subscribe-once query-v {:frame (:frame m)}))

(defn- drained
  "Wait for `pred`, then flush React — the router-drain counterpart of
  `hm/settle!`, for the one row here whose reply arrives on a macrotask.
  `flow-dom-cljs-test`'s helper of the same name and for the same reason."
  [m pred label]
  (-> (test-support/poll-until pred {:label label})
      (.then (fn [_] (hm/settle! m)))))

(defn- finish-after
  "End the row when `p` settles, reporting a rejection as a failure
  rather than letting a deadline hang the run — and tearing the mount
  down either way, so a stuck row cannot make the next row's residue
  reading wrong."
  [p m done]
  (-> p
      (.catch (fn [e]
                (is false (str "the flow never settled: "
                               (or (ex-message e) (str e)) " "
                               (pr-str (ex-data e))))))
      (.then (fn [_] (finish m done)))))

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

(defn- type-into!
  "One real keystroke's worth of input on a controlled field: a write
  through the prototype's own value setter and a real `input` event, the
  same door `flow-dom-cljs-test` and `i18n-dom-cljs-test` use."
  [m sel s]
  (let [n (node m sel)
        d (js/Object.getOwnPropertyDescriptor js/HTMLInputElement.prototype "value")]
    (.call (.-set d) n s)
    (.dispatchEvent n (js/InputEvent. "input" #js {:bubbles true}))
    (hm/settle! m)))

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
            (is (= 1 (.-length (.-labels el))))
            (is (identical? (node m ".published") (aget (.-labels el) 0)))))

        (finish m done)))))

(deftest clicking-a-label-moves-focus-to-the-control-it-names
  (if-not (browser?)
    (skip! ":node-test has no focus model")
    (async done
      (let [m (at-article! "intents")]
        (testing "the behaviour a `for`/`id` pair BUYS, and the one that
                  goes quiet when the pair is broken: a label pointing at
                  the wrong id still renders, still reads correctly, and
                  simply stops doing this"
          (doseq [[label-sel control-sel] [["label[for='slice-title']" "#slice-title"]
                                           ["label[for='slice-body']" "#slice-body"]
                                           ["label[for='slice-locale']" "#slice-locale"]]]
            (some-> (.-activeElement js/document) (.blur))
            (.click (node m label-sel))
            (is (identical? (node m control-sel) (.-activeElement js/document))
                (str "clicking " label-sel " must focus " control-sel))))

        (finish m done)))))

;; ---------------------------------------------------------------------------
;; Focus order, and the two controls whose place in it is state
;; ---------------------------------------------------------------------------

(def ^:private pristine-order
  "The article screen's focus order before anything is typed. `discard`
  is absent because it is disabled until the form is dirty — the whole
  point of the pair of rows below."
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

(deftest a-disabled-control-leaves-the-focus-order-and-comes-back
  (if-not (browser?)
    (skip! ":node-test has no focus model")
    (async done
      (let [m (at-article! "intents")]
        (testing "pristine: discard is disabled, and the engine will not
                  focus it — measured by asking, not by reading the
                  attribute back"
          (is (false? (focusable? (node m ".discard"))))
          (is (not (contains? (set (focus-order m)) "discard"))))

        (type-into! m "#slice-title" "Intents are data, still")

        (testing "one keystroke later the form is dirty, discard is
                  enabled, and it takes its place at the END of the order
                  — the same order otherwise, so what moved is exactly
                  the one control whose state changed"
          (is (true? (focusable? (node m ".discard"))))
          (is (= (conj pristine-order "discard") (focus-order m))))

        (finish m done)))))

(deftest the-save-button-leaves-the-focus-order-while-it-is-saving
  (if-not (browser?)
    (skip! ":node-test has no focus model")
    (async done
      (let [m (at-article! "intents")]
        (type-into! m "#slice-title" "A new title")
        (.click (node m ".save"))
        (hm/settle! m)

        (testing "the save is in flight, the button is disabled, and it is
                  out of the order — the other direction of the same
                  mechanism, on a different control and a different piece
                  of state, so neither row is a special case of the
                  other's markup"
          (is (true? (.-disabled (node m ".save"))))
          (is (false? (focusable? (node m ".save"))))
          (is (not (contains? (set (focus-order m)) "save"))))

        ;; The reply is a macrotask the mount must not be torn down under:
        ;; wait for the region to leave `:saving`, then assert the button
        ;; came BACK, which is what makes the row above about a transition
        ;; rather than about a button that is simply always disabled.
        (-> (drained m
                     #(not= :saving (:status (read-sub m [::subs/save-state "intents"])))
                     "the stand-in server's reply for intents")
            (.then (fn [_]
                     (is (false? (.-disabled (node m ".save"))))
                     (is (contains? (set (focus-order m)) "save"))))
            (finish-after m done))))))

;; ---------------------------------------------------------------------------
;; Focus across a re-render
;; ---------------------------------------------------------------------------

(deftest a-re-render-does-not-take-focus-away-from-the-user
  (if-not (browser?)
    (skip! ":node-test has no focus model")
    (async done
      (let [m (at-article! "intents")]
        (.focus (node m "#slice-title"))
        (let [field (node m "#slice-title")]
          (is (identical? field (.-activeElement js/document)))

          ;; The locale switch dispatched rather than clicked, deliberately:
          ;; clicking the `<select>` would move focus itself, and what is
          ;; under test is what the RE-RENDER does to focus, not what a
          ;; click does.
          (hm/dispatch-and-settle! m [::events/set-locale "fr"])

          (is (identical? field (.-activeElement js/document))
              "the SAME element still holds focus after every label on the
               page changed language. A substrate that rebuilt the subtree
               would leave `<body>` focused here and a user mid-sentence
               would be typing into nothing — this is the failure an i18n
               subsystem is bought to avoid, stated where a browser can
               see it")
          (is (= "Titre" (.-textContent (node m "label[for='slice-title']")))
              "and the re-render really did happen, so the row above is
               not green for want of anything occurring"))

        (finish m done)))))

;; ---------------------------------------------------------------------------
;; An alert must be announced without being intrusive
;; ---------------------------------------------------------------------------

(deftest an-alert-appearing-does-not-take-focus-from-the-field-it-is-about
  (if-not (browser?)
    (skip! ":node-test has no focus model")
    (async done
      (let [m (at-article! "intents")]
        ;; A blank title is refused LOCALLY, so the region appears on the
        ;; line after the dispatch with nothing in flight to wait for.
        (type-into! m "#slice-title" "   ")
        (let [field (node m "#slice-title")]
          (.focus field)
          (is (identical? field (.-activeElement js/document)))

          ;; Dispatched rather than clicked: clicking `.save` would move
          ;; focus to the button, and what is under test is what the
          ;; ALERT's arrival does to focus.
          (hm/dispatch-and-settle! m [::events/save {:slug "intents"}])

          (is (= "alert" (.getAttribute (node m ".save-problem") "role"))
              "the region really did arrive, so the row below is not green
               for want of anything happening")
          (is (identical? field (.-activeElement js/document))
              "and focus did not move. `role=\"alert\"` is announced BECAUSE
               it appeared — a live region that also stole focus would
               interrupt a user mid-sentence and drop them somewhere they
               did not ask to be, which is the failure that makes people
               turn assistive announcements off")
          (is (= "   " (.-value field))
              "the draft is still in the field it was typed into"))

        (finish m done)))))
