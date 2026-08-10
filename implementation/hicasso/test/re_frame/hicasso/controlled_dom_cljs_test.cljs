(ns re-frame.hicasso.controlled-dom-cljs-test
  "THE CONVERGE'S OWN MECHANISM — the trap it avoids, and the elements it
  refuses to touch (rf2-fki5d).

  `arm1_controlled_grid_dom_cljs_test` reads the behaviour through a
  hundred mounted boundaries and a real keystroke, which is where the
  claim belongs. This file reads the two pieces underneath it:

  - **the trap**, reproduced. [[re-frame.hicasso.impl.controlled/converge-to!]]
    is called twice with one argument different — the change handler's
    own closure value, then the per-instance record — and the first call
    wipes a keystroke the model took verbatim. The regression the naive
    form causes is measured here rather than asserted about elsewhere.
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
            [re-frame.hicasso.impl.codec :as codec]
            [re-frame.hicasso.impl.controlled :as controlled]))

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
          (is (= "abXcd" (controlled/last-rendered node))
              "and the commit moved the record with it")
          (testing "the naive form — the handler's own closure value"
            (.setSelectionRange node 3 3)
            (controlled/converge-to! node "abXcd" 3 "abcd")
            (is (= "abcd" (.-value node))
                "the accepted keystroke is GONE. This is the regression,
                 measured rather than assumed")
            (is (= [2 2] (caret node))
                "and the caret is two from the end of a string that no
                 longer contains what the user typed — the arithmetic is
                 not what is wrong here, the value it was given is"))
          (testing "the same call, one argument different — the record"
            (committed! node "abXcd")
            (.setSelectionRange node 3 3)
            (controlled/converge-to! node "abXcd" 3 (controlled/last-rendered node))
            (is (= {:value "abXcd" :caret [3 3]} (reading node))
                "nothing moved, which is the whole of what a keystroke
                 the model took verbatim is owed"))
          (finally (drop! node)))))))

(deftest neither-the-field-nor-what-the-handler-saw-can-tell-the-two-apart
  (testing "why the record is not optional. `(= (.-value node) dom-value)`
           reads TRUE on a refusal and TRUE on a keystroke taken
           verbatim — the same reading, two opposite obligations — so a
           converge written against it either wipes accepted keystrokes
           or leaves refused ones on the screen. Both fields below are in
           that state at the same instant; only the record separates
           them."
    (if-not (browser?)
      (skip! ":node-test has no DOM")
      (let [refused  (field! "12")
            accepted (field! "abcd")]
        (try
          (typed! refused "a" 2)             ; the model does not move
          (typed! accepted "X" 2)
          (committed! accepted "abXcd")      ; the model took it
          (is (= "12a" (.-value refused))
              "the field still shows what was typed — nothing re-rendered")
          (is (= "abXcd" (.-value accepted))
              "and so does this one — because that IS what was rendered")
          (is (not= (controlled/last-rendered refused) (.-value refused))
              "the record is the only thing that tells them apart")
          (is (= (controlled/last-rendered accepted) (.-value accepted)))
          (finally (drop! refused) (drop! accepted)))))))

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
          (controlled/converge-to! node "1,2345" 6 "12,345")
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
          (controlled/converge-to! node "12z345" 3 (controlled/last-rendered node))
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
  ((slot (codec/as-element hiccup) name') #js {:target node})
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
          (let [e (codec/as-element [:input {:value     "12345"
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
;; The composition carve-out, at the mechanism (rf2-digtt)
;; ---------------------------------------------------------------------------
;;
;; Two halves, and these rows read the half that lives in the element
;; path: the converge declines to run when the change event arrived
;; mid-composition. The other half — the shadow that makes React's own
;; restore a no-op — needs React, and is read in
;; `arm1_controlled_grid_dom_cljs_test` §7; the exchange itself needs a
;; browser composition and is read by `bench/hicasso/ime_run.cjs`.

(deftest a-composing-change-event-is-the-one-argument-that-suppresses-the-converge
  (testing "the same element, the same refused keystroke, one property
           different on the event. Not composing, the refused character
           comes off the screen in-turn as it always did; composing, the
           field is left exactly as the IME left it and the model has
           still refused — which is the whole of the carve-out's first
           half"
    (if-not (browser?)
      (skip! ":node-test has no DOM")
      (let [!ran (atom 0)
            hiccup [:input {:value "12345" :on-input (fn [_e] (swap! !ran inc))}]
            handler (fn [] (slot (codec/as-element hiccup) "onInput"))]
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

(deftest the-composition-reading-is-taken-off-the-native-event
  (testing "React hands a handler a synthetic event, so a gate reading
           `isComposing` off it would be dead. A synthetic target from a
           node-side row carries no native event at all, which is why
           every row written before the carve-out reads as it did"
    (is (false? (controlled/composing-input? #js {:target #js {}})))
    (is (false? (controlled/composing-input? #js {})))
    (is (false? (controlled/composing-input? #js {:nativeEvent #js {}})))
    (is (false? (controlled/composing-input? #js {:nativeEvent #js {:isComposing false}})))
    (is (true? (controlled/composing-input? #js {:nativeEvent #js {:isComposing true}})))))

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
          typ (fn [props] (.-type (codec/as-element [:input props])))
          controlled-as (fn [t] (typ (cond-> {:value "1" :on-input f}
                                       (some? t) (assoc :type t))))]
      (is (identical? (controlled-as "text") (controlled-as "number"))
          "one element type across the flip the row above measures")
      (is (identical? (controlled-as nil) (controlled-as "password")))
      (is (identical? (controlled-as "text") (controlled-as "checkbox")))
      (is (= "input" (typ {:on-input f}))
          "while an uncontrolled input is still emitted as the bare tag —
           the shadow has no controlled value to hold"))))

(deftest the-tag-an-emitted-element-renders-is-readable-without-knowing-this-namespace
  (testing "a controlled field's element type is the shadow's component,
           so an element-tree reader asks for the TAG rather than the
           type. Everything else answers itself"
    (let [f (fn [_e])
          tag-of (fn [hiccup] (controlled/element-tag (codec/as-element hiccup)))]
      (is (= "input" (tag-of [:input {:value "x" :on-input f}])))
      (is (= "textarea" (tag-of [:textarea {:value "x" :on-input f}])))
      (is (= "input" (tag-of [:input {:on-input f}]))
          "an uncontrolled input is emitted as the tag, and answers it")
      (is (= "div" (tag-of [:div {}])))
      (is (= "select" (tag-of [:select {:value "x" :on-input f}]))))))

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
          emitted (fn [hiccup] (slot (codec/as-element hiccup) "onInput"))]
      (is (not (identical? f (emitted [:input {:value "x" :on-input f}])))
          "the control: a controlled text field IS wrapped")
      (is (identical? f (emitted [:input {:on-input f}]))
          "no `:value` — the element is uncontrolled, and React writes no
           record for it")
      (is (identical? f (emitted [:input {:value nil :on-input f}]))
          "a nil `:value` is the same statement")
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
          "and a tag that is not a form control is not even asked")
      (testing "while every applicable input type is"
        (doseq [t ["text" "search" "url" "tel" "password"]]
          (is (not (identical? f (emitted [:input {:type t :value "x" :on-input f}])))
              (str "type=" t))))
      (testing "as is an input with no `:type` at all, which is text"
        (is (not (identical? f (emitted [:input {:value "x" :on-input f}]))))))))

(deftest a-controlled-field-with-no-change-handler-has-nothing-wrapped
  (testing "there is no slot to install into, and the codec does not mint
           one — an element with no handler comes out with no handler"
    (let [e (codec/as-element [:input {:value "12345"}])]
      (is (nil? (slot e "onInput")))
      (is (nil? (slot e "onChange")))
      (is (= "12345" (slot e "value"))))))

(deftest a-target-that-is-not-a-form-control-is-left-alone
  (testing "the reading that makes this inert rather than defensive: a
           caret that is not a number is not a text-entry control. Every
           node-side codec row invokes an emitted handler with a
           synthetic `#js {:target #js {…}}`, and none of them may throw
           or flush anything."
    (let [!seen (atom [])
          e     (codec/as-element
                 [:input {:value    "12345"
                          :on-input (fn [ev] (swap! !seen conj (.. ev -target -value)))}])]
      ((slot e "onInput") #js {:target #js {:value "typed"}})
      (is (= ["typed"] @!seen) "the author's handler ran and saw the event")
      (is (nil? (controlled/last-rendered #js {:value "typed"}))
          "and there was no record to converge to")))
  (testing "an event with no target at all"
    (let [!ran (atom 0)
          e    (codec/as-element [:input {:value "x" :on-input (fn [_e] (swap! !ran inc))}])]
      ((slot e "onInput") #js {})
      (is (= 1 @!ran)))))
