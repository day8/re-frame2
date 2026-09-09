(ns re-frame.hicasso.select-multiple-value-dom-cljs-test
  "`::h/value` ON A `<select multiple>` — the selection, not its first
  option.

  `HTMLSelectElement.value` is defined by HTML as the value of the FIRST
  selected option, and it answers that on a `multiple` select exactly as
  it does on a single one. So a reader written as `(.-value target)` is
  correct for every control but this one, where it silently returns a
  plausible answer that is not the selection: the user picks two options,
  the handler receives one, the controlled echo writes that one back and
  the second choice is discarded inside the turn.

  Silence is what makes it worth a row. There is no exception, no warning
  and no shape difference — `\"a\"` is what a single select would have
  answered too — so nothing downstream can tell a one-option selection
  from an under-read two-option one.

  ## Two witnesses, and why both

  - **the reader**, over a stand-in whose `.value` answers the first
    selected option because that is what HTML says it answers. The
    stand-in is the DOM CONTRACT written down, and it is what lets the
    row run on `:node-test` where there is no DOM at all.
  - **the control**, over a real `<select multiple>` in a real document.
    A stand-in can only be wrong the way its author expected; this one
    is the browser's own answer, and it is the row that would catch a
    reader that agreed with the stand-in and disagreed with HTML.

  Both assert the same sentence, and the second is skipped rather than
  faked where there is no document — the same shape
  `controlled_dom_cljs_test` uses for its caret rows."
  (:require [cljs.test :refer-macros [deftest is testing]]
            [re-frame.hicasso.impl.intent :as rf.hicasso.impl.intent]))

(defn- browser? []
  (and (exists? js/document) (some? js/document) (some? (.-body js/document))))

(defn- skip! [why]
  (is true (str "a real selection needs a real document — " why)))

;; ---------------------------------------------------------------------------
;; The stand-in — HTML's own rule for `.value`, written down
;; ---------------------------------------------------------------------------

(defn- ev
  "An event whose target is `target`."
  [target]
  #js {:target target})

(defn- select-stand-in
  "A `<select>` as HTML defines the two properties this reader can ask.

  `.value` is \"the value of the first option in the list of options in
  tree order that has its selectedness set to true\", or the empty string
  when none has — **and that definition does not change for a `multiple`
  select**, which is the whole defect. `.selectedOptions` is every such
  option, in tree order."
  [multiple? selected]
  #js {:multiple        multiple?
       :value           (or (first selected) "")
       :selectedOptions (apply array (map (fn [v] #js {:value v}) selected))})

;; ---------------------------------------------------------------------------
;; The real control
;; ---------------------------------------------------------------------------

(defn- select!
  "A real `<select>` in the document, with `selected` picked."
  [multiple? options selected]
  (let [n (js/document.createElement "select")]
    (set! (.-multiple n) multiple?)
    (doseq [v options]
      (let [o (js/document.createElement "option")]
        (set! (.-value o) v)
        (set! (.-textContent o) v)
        (.appendChild n o)))
    (.appendChild js/document.body n)
    (doseq [o (array-seq (.-options n))]
      (set! (.-selected o) (contains? (set selected) (.-value o))))
    n))

(defn- drop! [node] (.remove node) nil)

;; ---------------------------------------------------------------------------
;; The defect, and the sentence that replaces it
;; ---------------------------------------------------------------------------

(deftest the-value-marker-reads-the-whole-selection-of-a-multiple-select
  (testing "two options picked materialize as two values, in tree order"
    (let [target (select-stand-in true ["a" "c"])]
      (is (= [:tb/pick ["a" "c"]]
             (rf.hicasso.impl.intent/materialize [:tb/pick :re-frame.hicasso/value] (ev target)))
          (str "`.value` answers \"a\" for this target because HTML says the "
               "first selected option is the value; the SELECTION is both, and "
               "an intent that lowers to the first one discards the author's "
               "second choice with no signal anywhere"))))
  (testing "one option picked is still a vector — the shape follows the control"
    (let [target (select-stand-in true ["b"])]
      (is (= [:tb/pick ["b"]]
             (rf.hicasso.impl.intent/materialize [:tb/pick :re-frame.hicasso/value] (ev target)))
          "a one-option selection is a selection of one, not a bare value")))
  (testing "nothing picked is an empty selection, not the empty string"
    (let [target (select-stand-in true [])]
      (is (= [:tb/pick []]
             (rf.hicasso.impl.intent/materialize [:tb/pick :re-frame.hicasso/value] (ev target)))
          "`.value` would answer \"\" here, which is indistinguishable from a
           selected option whose value is empty"))))

(deftest a-single-select-is-untouched
  (testing "no :multiple means `.value`, exactly as before"
    (let [target (select-stand-in false ["a"])]
      (is (= [:tb/pick "a"]
             (rf.hicasso.impl.intent/materialize [:tb/pick :re-frame.hicasso/value] (ev target)))
          "the ordinary select is the overwhelming case and answers a string"))))

(deftest an-input-carrying-multiple-is-not-a-select
  (testing "`<input type=email multiple>` carries `.multiple` and has no
            selection. So does `<input type=file multiple>`, which is why
            `.multiple` alone would have been the wrong test — but the file
            input never reaches this branch at all now: it is refused one
            step earlier, and `file_input_value_dom_cljs_test` owns that
            row (rf2-lhsvs)."
    (let [target #js {:files nil :multiple true :value "a@b.com,c@d.com"}]
      (is (= [:tb/pick "a@b.com,c@d.com"]
             (rf.hicasso.impl.intent/materialize [:tb/pick :re-frame.hicasso/value] (ev target)))
          (str "`.multiple` alone is the WRONG test — it is an <input> "
               "attribute too. Only a <select> has `.selectedOptions`, and "
               "asking for it is what keeps every input on the fast path")))))

(deftest the-real-control-agrees-with-the-stand-in
  (if-not (browser?)
    (skip! "the reader rows above carry HTML's rule on :node-test")
    (testing "a mounted <select multiple> with two options picked"
      (let [n (select! true ["a" "b" "c"] ["a" "c"])]
        (try
          (is (= "a" (.-value n))
              (str "the browser's own answer, and the reason the naive reader "
                   "looks right: `.value` is a plausible non-empty string"))
          (is (= [:tb/pick ["a" "c"]]
                 (rf.hicasso.impl.intent/materialize [:tb/pick :re-frame.hicasso/value] (ev n)))
              "the selection the user actually made")
          (finally (drop! n)))))))
