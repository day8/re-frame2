(ns re-frame.fresco.controlled-dom-cljs-test
  "THE CONVERGE'S OWN MECHANISM — the trap it avoids, and the elements it
  refuses to touch.

  `arm1_controlled_grid_dom_cljs_test` reads the behaviour through a
  hundred mounted boundaries and a real keystroke, which is where the
  claim belongs. This file reads the two pieces underneath it:

  - **the trap**, reproduced. [[re-frame.fresco.impl.controlled/converge-to!]]
    is called twice with one argument different — the change handler's
    own closure value, then the per-instance record — and the first call
    wipes a keystroke the model took verbatim. The loss the naive form
    causes is measured here rather than asserted about elsewhere.
  - **the install guards**, exercised through `codec/as-element`, because
    the condition is about the emitted element and the emitted element
    is what the codec builds. A guard that does not hold leaves the
    author's handler at the element BY IDENTITY, which is the strongest
    available statement of \"nothing was installed\".

  ## The stand-in for React

  A converge needs two things from the node: what the field shows, and
  what the element last rendered. React maintains the second as
  `node.defaultValue`, and the rows below set it by hand — which is
  legitimate precisely because it is an ordinary DOM property with an
  ordinary setter, and is the whole reason the mechanism needs nothing
  of its own to keep. **That React really does write it is a separate
  claim**, and it is asserted against a live React tree in
  `arm1_controlled_grid_dom_cljs_test/the-record-is-reacts-own-mirror-and-is-not-the-handlers-closure`.
  Setting it here would prove nothing about React; these rows prove what
  the converge does with a record, given one.

  Runtime: `-dom-cljs-test`, because a caret needs a real text field.
  The guard rows need no DOM and run on both targets; the rest degrade
  to a stated skip under `:node-test`."
  (:require [cljs.test :refer-macros [deftest is testing]]
            [re-frame.fresco.impl.codec :as rf.fresco.impl.codec]
            [re-frame.fresco.impl.controlled :as rf.fresco.impl.controlled]
            ["react-dom" :as react-dom]
            ["react-dom/client" :as react-dom-client]))

(defn- browser? []
  (and (exists? js/document) (some? js/document) (some? (.-body js/document))))

(defn- skip! [why]
  (is true (str "a caret in a real text field needs a real DOM — " why)))

;; ---------------------------------------------------------------------------
;; A field, and a keystroke, without React
;; ---------------------------------------------------------------------------

(defn- field!
  "A real `<input>` in the document, left as a React render that put
  `rendered` on it would leave it: the field shows it, and the node's
  own record of what was rendered holds it."
  ([rendered] (field! "input" rendered))
  ([tag rendered]
   (let [n (js/document.createElement tag)]
     (when (= "input" tag) (set! (.-type n) "text"))
     (.appendChild js/document.body n)
     (set! (.-defaultValue n) rendered)
     (set! (.-value n) rendered)
     n)))

(defn- drop! [node] (.remove node) nil)

(defn- typed!
  "What the browser has already done by the time any handler runs: the
  character is in the field and the caret is after it."
  [node text at]
  (let [v (.-value node)
        c (+ at (count text))]
    (set! (.-value node) (str (subs v 0 at) text (subs v at)))
    (.setSelectionRange node c c)
    node))

(defn- committed!
  "What React's commit leaves on the node when the model moved to
  `rendered`: the field is written only when it differs — and a write
  throws the cursor to the end — while the record follows either way."
  [node rendered]
  (when-not (= (.-value node) rendered)
    (set! (.-value node) rendered)
    (.setSelectionRange node (count rendered) (count rendered)))
  (set! (.-defaultValue node) rendered)
  node)

(defn- caret [node] [(.-selectionStart node) (.-selectionEnd node)])

(defn- reading [node] {:value (.-value node) :caret (caret node)})

;; ---------------------------------------------------------------------------
;; The trap — one argument different
;; ---------------------------------------------------------------------------

(deftest the-closure-value-wipes-a-keystroke-the-model-took-verbatim
  (testing "THE MEASURED TRAP, and the reason the converge reads a record
           off the node rather than closing over a value it already has.

           The field held `\"abcd\"`, the user typed `X` at 2, and the
           model TOOK it — so the element now renders `\"abXcd\"` and
           there is nothing to converge. The change handler's own
           closure, though, still carries the value from the render that
           MINTED it, and that render is one behind. Writing it back is
           not a smaller improvement than writing the record: it deletes
           the character the user just typed."
    (if-not (browser?)
      (skip! ":node-test has no DOM")
      (let [node (field! "abcd")]
        (try
          (typed! node "X" 2)
          (is (= {:value "abXcd" :caret [3 3]} (reading node))
              "the browser got there first, as it always does")
          (committed! node "abXcd")
          (is (= "abXcd" (rf.fresco.impl.controlled/last-rendered node))
              "and the commit moved the record with it")
          (testing "the naive form — the handler's own closure value"
            (.setSelectionRange node 3 3)
            (rf.fresco.impl.controlled/converge-to! node "abXcd" 3 "abcd")
            (is (= "abcd" (.-value node))
                "the accepted keystroke is GONE. This is the trap,
                 measured rather than assumed")
            (is (= [2 2] (caret node))
                "and the caret is two from the end of a string that no
                 longer contains what the user typed — the arithmetic is
                 not what is wrong here, the value it was given is"))
          (testing "the same call, one argument different — the record"
            (committed! node "abXcd")
            (.setSelectionRange node 3 3)
            (rf.fresco.impl.controlled/converge-to! node "abXcd" 3 (rf.fresco.impl.controlled/last-rendered node))
            (is (= {:value "abXcd" :caret [3 3]} (reading node))
                "nothing moved, which is the whole of what a keystroke
                 the model took verbatim is owed"))
          (finally (drop! node)))))))

;; ---------------------------------------------------------------------------
;; The caret comes back by offset from the END
;; ---------------------------------------------------------------------------

(deftest the-caret-is-restored-by-offset-from-the-end-of-the-string
  (testing "the half an absolute position gets wrong. A normalisation that
           CHANGES THE LENGTH moves every absolute offset in the string,
           and what survives it is the distance back from the end — which
           is the right side to measure from, because it is the side the
           user is still typing into."
    (if-not (browser?)
      (skip! ":node-test has no DOM")
      (let [node (field! "1,234")]
        (try
          (typed! node "5" 5)
          (is (= {:value "1,2345" :caret [6 6]} (reading node)))
          (committed! node "12,345")
          (rf.fresco.impl.controlled/converge-to! node "1,2345" 6 "12,345")
          (is (= {:value "12,345" :caret [6 6]} (reading node))
              "0 from the end of a six-character string and 0 from the
               end of a seven-character one are the same caret")
          (finally (drop! node))))))
  (testing "and a refusal mid-string, which is the row neither shipped
           implementation gets right"
    (if-not (browser?)
      (skip! ":node-test has no DOM")
      (let [node (field! "12345")]
        (try
          (typed! node "z" 2)
          (is (= {:value "12z345" :caret [3 3]} (reading node)))
          (rf.fresco.impl.controlled/converge-to! node "12z345" 3 (rf.fresco.impl.controlled/last-rendered node))
          (is (= {:value "12345" :caret [2 2]} (reading node))
              "3 from the end of \"12z345\" is 2 from the end of
               \"12345\" — the position before the refused character")
          (finally (drop! node)))))))

;; ---------------------------------------------------------------------------
;; What the codec installs
;; ---------------------------------------------------------------------------

(defn- slot [e name'] (aget (.-props e) name'))

(defn- fire!
  "Invoke the emitted handler the way React would, with `node` as the
  event target, and read the node afterwards — so a row says whether a
  converge ran without knowing how it was installed."
  [hiccup name' node]
  ((slot (rf.fresco.impl.codec/as-element hiccup) name') #js {:target node})
  (reading node))

(deftest a-controlled-field-converges-through-the-handler-the-codec-emitted
  (testing "the whole installation, read through the element the codec
           built: the author's handler still runs, and the field is
           converged against the record afterwards"
    (if-not (browser?)
      (skip! ":node-test has no DOM")
      (let [!ran (atom 0)
            node (field! "12345")]
        (try
          (typed! node "z" 2)
          (is (= {:value "12345" :caret [2 2]}
                 (fire! [:input {:value    "12345"
                                 :on-input (fn [_e] (swap! !ran inc))}]
                        "onInput" node))
              "the refused character is off the screen and the caret is at
               the position before it")
          (is (= 1 @!ran) "and the author's own handler ran, once")
          (finally (drop! node))))))
  (testing "`:on-change` is wrapped in preference to `:on-input`, because
           React's `SimpleEventPlugin` extracts `onInput` before
           `ChangeEventPlugin` extracts `onChange` — so the converge
           lands after every handler the element has rather than between
           them"
    (if-not (browser?)
      (skip! ":node-test has no DOM")
      (let [node (field! "12345")]
        (try
          (typed! node "z" 2)
          (let [e (rf.fresco.impl.codec/as-element [:input {:value     "12345"
                                             :on-input  (fn [_e])
                                             :on-change (fn [_e])}])]
            ((slot e "onInput") #js {:target node})
            (is (= {:value "12z345" :caret [3 3]} (reading node))
                "the earlier handler is the author's, untouched")
            ((slot e "onChange") #js {:target node})
            (is (= {:value "12345" :caret [2 2]} (reading node))
                "and the later one carries the converge"))
          (finally (drop! node))))))
  (testing "a <textarea> is a controlled text field too"
    (if-not (browser?)
      (skip! ":node-test has no DOM")
      (let [node (field! "textarea" "12345")]
        (try
          (typed! node "z" 2)
          (is (= {:value "12345" :caret [2 2]}
                 (fire! [:textarea {:value "12345" :on-input (fn [_e])}]
                        "onInput" node)))
          (finally (drop! node)))))))

;; ---------------------------------------------------------------------------
;; The composition carve-out, at the mechanism
;; ---------------------------------------------------------------------------
;;
;; Two halves, and these rows read the half that lives in the element
;; path: the converge declines to run when the change event arrived
;; mid-composition. The other half — the shadow that makes React's own
;; restore a no-op — needs React, and is read in
;; `arm1_controlled_grid_dom_cljs_test` §7; the exchange itself needs a
;; browser composition and is read by
;; `bench/fresco/src/re_frame/bench/fresco/ime_run.cjs`.

(deftest a-composing-change-event-is-the-one-argument-that-suppresses-the-converge
  (testing "the same element, the same refused keystroke, one property
           different on the event. Not composing, the refused character
           comes off the screen in-turn; composing, the field is left
           exactly as the IME left it and the model has still refused —
           which is the whole of the carve-out's first half"
    (if-not (browser?)
      (skip! ":node-test has no DOM")
      (let [!ran (atom 0)
            hiccup [:input {:value "12345" :on-input (fn [_e] (swap! !ran inc))}]
            handler (fn [] (slot (rf.fresco.impl.codec/as-element hiccup) "onInput"))]
        (testing "not composing — the converge runs"
          (let [node (field! "12345")]
            (try
              (typed! node "z" 2)
              ((handler) #js {:target node})
              (is (= {:value "12345" :caret [2 2]} (reading node)))
              (finally (drop! node)))))
        (testing "composing — it does not"
          (let [node (field! "12345")]
            (try
              (typed! node "z" 2)
              ((handler) #js {:target node :nativeEvent #js {:isComposing true}})
              (is (= {:value "12z345" :caret [3 3]} (reading node))
                  "the draft and the caret are untouched")
              (finally (drop! node)))))
        (is (= 2 @!ran)
            "and the author's handler ran BOTH times — the carve-out
             suppresses the converge, never the model")))))

(deftest the-element-type-does-not-move-when-the-input-type-does
  (testing "why the shadow's component is chosen without reading `:type`.
           A React element type that changed would REMOUNT the field —
           taking focus, selection and any composition with it — and a
           synchronous handler re-rendering the same `<input>` from `text`
           to `number` is a measured case, not a hypothetical
           (`arm1_controlled_grid_dom_cljs_test/a-type-change-inside-the-flush-leaves-the-converge-inert`
           asserts React kept the node). The converge's own guard still
           reads the type — one render later, where a wrong answer costs
           nothing"
    (let [f   (fn [_e])
          typ (fn [props] (.-type (rf.fresco.impl.codec/as-element [:input props])))
          controlled-as (fn [t] (typ (cond-> {:value "1" :on-input f}
                                       (some? t) (assoc :type t))))]
      (is (identical? (controlled-as "text") (controlled-as "number"))
          "one element type across the flip the row above measures")
      (is (identical? (controlled-as nil) (controlled-as "password")))
      (is (identical? (controlled-as "text") (controlled-as "checkbox")))
      (is (= "input" (typ {:on-input f}))
          "while an uncontrolled input is still emitted as the bare tag —
           the shadow has no controlled value to hold"))))

(deftest the-element-type-does-not-move-when-the-value-moves-between-nil-and-text
  (testing "the same law on the VALUE axis. An unset model
           reads nil — a draft nobody has typed, an entity with no title
           yet — and the first keystroke moves it to text; a commit that
           clears the draft moves it back. Both moves must keep the element
           type, or React replaces the node under the user's caret"
    (let [f   (fn [_e])
          typ (fn [tag v] (.-type (rf.fresco.impl.codec/as-element
                                   [tag {:value v :on-input f}])))]
      (is (identical? (typ :input nil) (typ :input "a")))
      (is (identical? (typ :textarea nil) (typ :textarea "a")))
      (is (identical? (typ :input nil) (typ :input "")))))
  (testing "because nil is the EMPTY field wherever the value is the
           field's content, so the element carries `\"\"` and React reads
           it as controlled. A type whose value is a token or a label keeps
           React's reading of nil — no attribute — and an unwritten
           `:value` stays unwritten"
    (let [value-of (fn [hiccup] (slot (rf.fresco.impl.codec/as-element hiccup) "value"))]
      (is (= "" (value-of [:input {:value nil :on-input (fn [_e])}])))
      (is (= "" (value-of [:textarea {:value nil :on-input (fn [_e])}])))
      (is (= "" (value-of [:input {:type "number" :value nil}]))
          "a content type with no caret is still content")
      (is (nil? (value-of [:input {:type "checkbox" :value nil}])))
      (is (nil? (value-of [:input {:type "SUBMIT" :value nil}]))
          "a shouted type is folded here as it is for the caret")
      (is (undefined? (value-of [:input {:on-input (fn [_e])}]))))))

(deftest a-field-whose-model-moves-between-nil-and-text-keeps-its-node-and-focus
  (testing "the row above read on a live React root, where a changed
           element type is a REMOUNT: the focused `<input>` deleted and a
           fresh, unfocused one mounted in its place. And the second half
           the element type alone cannot answer — a model cleared to nil
           must clear the box, where a field React had read as uncontrolled
           would keep the text it last showed"
    (if-not (browser?)
      (skip! "a remount and a focus need a real React commit")
      (let [c       (js/document.createElement "div")
            _       (.appendChild js/document.body c)
            root    (react-dom-client/createRoot c)
            render! (fn [v]
                      (react-dom/flushSync
                       #(.render root (rf.fresco.impl.codec/as-element
                                       [:input {:id "f" :value v :on-input (fn [_e])}]))))
            node    #(.querySelector c "#f")]
        (try
          (render! nil)
          (let [n (node)]
            (.focus n)
            (is (= "" (.-value n)) "an unset model shows the empty field")
            (render! "a")
            (is (identical? n (node)) "the first keystroke's model kept the node")
            (is (identical? n js/document.activeElement) "and the focus")
            (is (= "a" (.-value n)))
            (render! "abc")
            (render! nil)
            (is (identical? n (node)) "a commit clearing the model kept the node")
            (is (identical? n js/document.activeElement) "and the focus")
            (is (= "" (.-value n))
                "and the box shows the model — empty — rather than the text
                 it last showed"))
          (finally
            (react-dom/flushSync #(.unmount root))
            (.remove c)))))))

;; ---------------------------------------------------------------------------
;; What it leaves alone — every guard, by identity
;; ---------------------------------------------------------------------------

(deftest an-element-outside-the-guards-keeps-the-authors-handler-by-identity
  (testing "each guard is a condition on the RECORD being the value the
           element last rendered, so failing one means NO wrapper —
           never a wrapper that quietly does less. The codec passes a
           function to React by identity, so `identical?` is exactly the
           question \"was anything installed here?\""
    (let [f (fn [_e])
          emitted (fn [hiccup] (slot (rf.fresco.impl.codec/as-element hiccup) "onInput"))]
      (is (not (identical? f (emitted [:input {:value "x" :on-input f}])))
          "the control: a controlled text field IS wrapped")
      (is (identical? f (emitted [:input {:on-input f}]))
          "no `:value` — the element is uncontrolled, and React writes no
           record for it")
      (is (not (identical? f (emitted [:input {:value nil :on-input f}])))
          "while a nil `:value` is NOT that statement: an unset model is
           the EMPTY field, controlled and wrapped like any other value")
      (is (identical? f (emitted [:input {:value "x" :default-value "seed"
                                          :on-input f}]))
          "a `:default-value` of the author's — React honours it over the
           mirror on a <textarea>, and the guard is taken on both tags
           because one rule about the record is easier to keep true than
           two")
      (is (identical? f (emitted [:input {:type "number" :value "1" :on-input f}]))
          "a type with no text cursor: `setSelectionRange` does not apply
           to it, and `setDefaultValue` skips a focused number field —
           the same exclusion twice")
      (is (identical? f (emitted [:input {:type "checkbox" :value "1" :on-input f}])))
      (is (identical? f (emitted [:select {:value "x" :on-input f}]))
          "a <select> has neither a caret nor a mirror")
      (is (identical? f (emitted [:div {:value "x" :on-input f}]))
          "and a tag that is not a form control is not even asked"))))

;; ---------------------------------------------------------------------------
;; The type is the PLATFORM's spelling, not the author's
;; ---------------------------------------------------------------------------
;;
;; An HTML `type` is an enumerated attribute the platform matches ASCII
;; case-insensitively, and this predicate reads the author's PROPS object,
;; where a spelling is all there is. So the rows below are the second half
;; of the statement `file_input_value_dom_cljs_test` makes about the same
;; attribute: ONE reading of `type` in this namespace rather
;; than two, and the one the platform uses.
;;
;; The failure they guard is silent, which is why it is worth a section. A
;; shouted spelling that failed the guard would install no wrapper and leave
;; the field to React's own end-of-event restore: the value would still
;; converge, and the caret would go to the end of the control. No throw, no
;; id, no warning — a subtly worse cursor and nothing to attribute it to.

(deftest every-spelling-of-a-caret-type-is-the-same-control
  (let [f       (fn [_e])
        emitted (fn [hiccup] (slot (rf.fresco.impl.codec/as-element hiccup) "onInput"))
        wrapped? (fn [hiccup] (not (identical? f (emitted hiccup))))]
    (testing "every caret-bearing type, shouted — each of these IS a text
             entry control to the engine, and an exact comparison would let
             each walk past the guard"
      (doseq [t ["TEXT" "SEARCH" "URL" "TEL" "PASSWORD"]]
        (is (wrapped? [:input {:type t :value "x" :on-input f}])
            (str "type=" t))))
    (testing "while a type with NO caret stays refused at every spelling —
             the fold widens which spellings the predicate recognises, not
             which controls it accepts. `setSelectionRange` throws on these,
             so a fold that leaked one through would be worse than the hole
             it closes"
      (doseq [t ["NUMBER" "CHECKBOX"]]
        (is (not (wrapped? [:input {:type t :value "1" :on-input f}]))
            (str "type=" t))))
    (testing "a type that merely CONTAINS a caret type's letters is not one"
      (doseq [t ["textarea" "context"]]
        (is (not (wrapped? [:input {:type t :value "x" :on-input f}]))
            (str "type=" t))))
    (testing "and a non-string `:type`, which `convert-prop-value` leaves as
             it found it, is asked the question without being handed to a
             string method. This is the `string?` guard, and it is
             load-bearing: `(.toLowerCase 0)` is a TypeError, so a fold
             written without it turns a silent miss into a thrown render"
      (is (not (wrapped? [:input {:type 0 :value "x" :on-input f}]))))))

(deftest a-controlled-field-with-no-change-handler-has-nothing-wrapped
  (testing "there is no slot to install into, and the codec does not mint
           one — an element with no handler comes out with no handler"
    (let [e (rf.fresco.impl.codec/as-element [:input {:value "12345"}])]
      (is (nil? (slot e "onInput")))
      (is (nil? (slot e "onChange")))
      (is (= "12345" (slot e "value"))))))
