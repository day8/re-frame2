(ns re-frame.hicasso.file-input-value-dom-cljs-test
  "A FILE INPUT HAS NO VALUE SURFACE (rf2-u2tza, rf2-lhsvs).

  The guide rules it out in one sentence — chapter 04, supported controls:
  the File input row reads `no controlled value`, with `:on-change` and an
  `h/event` reading `.files` as the whole of its event form, `because the
  platform owns their value`. The same chapter states the posture the two
  rows below enforce: unsupported controlled shapes are REJECTED rather
  than approximated. Both defects are that sentence not being kept.

  ## The two halves of the value surface, and how each failed

  `value` reaches a file input from two directions, and until these rows
  both directions were silent.

  - **The prop.** `:value` on an `<input type=file>` made it a controlled
    element as far as React is concerned, and React's `initInput` /
    `updateInput` both reach `element.value = …`. The platform refuses
    every assignment but the empty string on a file input, so the write
    throws `InvalidStateError` — an ENGINE exception naming no view, no
    framework and no recovery. React 19.2 carries no controlled-file-input
    warning of its own, so nothing upstream was ever going to say it.
  - **The marker.** `::h/value` lowered to `(.-value target)`, and
    `HTMLInputElement.value` is in FILENAME MODE on a file input: the
    literal fiction `C:\\fakepath\\` followed by the FIRST selected file's
    name. That is a plausible non-empty string that names one file out of
    however many were chosen, over a path nothing can open. No throw, no
    warning, no shape difference from an honest answer.

  Two ids rather than one, because they are two authoring mistakes with
  two fixes: the prop wants DELETING (the platform owns the selection),
  the marker wants REPLACING with an `h/event`. See the two Spec 009 rows.

  ## The empty string is not the defect — it is the reset idiom

  `:value \"\"` must keep working, and one row here says so. Read at
  source in react-dom@19.2.0, both assignment sites SKIP when the value
  already agrees with the element: `initInput` is
  `value === element.value || (element.value = value)` and `updateInput`
  is `element.value !== \"\" + getToStringValue(value) && (element.value =
  …)`. An empty file input answers `\"\"`, so nothing is assigned; one
  holding a file answers the fakepath, so the assignment DOES happen and
  is the empty string — which is the one write the platform accepts, and
  it clears the control. Refusing a non-NIL `:value` would have taken the
  only model-driven clear away from the author. The predicate is
  non-EMPTY.

  ## The spelling is the platform's, not the author's (rf2-h6qm7)

  The prop refusal shipped comparing the `type` prop exactly, and an HTML
  `type` is an enumerated attribute the platform matches ASCII
  case-insensitively. So `:type \"FILE\"` was a file input to the engine
  and not to the predicate: the refusal did not fire, React took the
  controlled path anyway, and the engine threw the very
  `InvalidStateError` the refusal exists to replace — one spelling in
  sixteen refused, fifteen through. Worse than no refusal, because a hole
  that shape is invisible from the outside.

  The two readers answer this differently and both answers are right. The
  PROP predicate reads the author's props object before React builds
  anything, so a spelling is all it has and it folds. The MARKER reads a
  LIVE element on an event, where the platform has already resolved the
  type, and asks `.files` — a property of the resolved control rather
  than a string anyone spelled. One row below measures each.

  ## The platform control stays

  The direct `node.value = …` rows are not redundant with the refusals.
  They are the reason the refusals exist, measured on the engine rather
  than assumed from React's source, and they are what would go red if a
  future engine ever accepted the write.

  Runtime: `-dom-cljs-test`, so the rows run in both lanes. The stand-in
  rows carry the reader's rule under `:node-test` where there is no
  document; the real-control rows are skipped there rather than faked,
  the same shape `controlled_dom_cljs_test` uses for its caret rows."
  (:require [cljs.test :refer-macros [deftest is testing]]
            [re-frame.hicasso.impl.codec :as codec]
            [re-frame.hicasso.impl.intent :as intent]
            ["react-dom" :as react-dom]
            ["react-dom/client" :as react-dom-client]))

(defn- browser? []
  (and (exists? js/document) (some? js/document) (some? (.-body js/document))))

(defn- skip! [why]
  (is true (str "a real file input needs a real document — " why)))

(defn- noop-change [_e])

;; ---------------------------------------------------------------------------
;; The harness
;; ---------------------------------------------------------------------------

(defn- container! []
  (let [c (js/document.createElement "div")]
    (.appendChild js/document.body c)
    c))

(defn- drop-container! [c]
  (when-some [p (.-parentNode c)] (.removeChild p c))
  nil)

(defn- thrown-by
  "Run `f` and return whatever it threw, or nil. The THING, not its
  ex-data, because half the point of these rows is WHICH kind of object
  arrives: a `DOMException` from the engine is the defect, an `ex-info`
  carrying an id is the repair."
  [f]
  (try (f) nil (catch :default e e)))

(defn- id-of [e] (:rf.error/id (ex-data e)))

(defn- file-input!
  "A real `<input type=file>` in the document, at the given SPELLING of
  the type attribute — `setAttribute`, so the attribute keeps the
  author's case and only the IDL normalises it, which is the asymmetry
  rf2-h6qm7 turns on."
  ([] (file-input! "file"))
  ([spelling]
   (let [n (js/document.createElement "input")]
     (.setAttribute n "type" spelling)
     (.appendChild js/document.body n)
     n)))

(defn- drop! [n] (.remove n) nil)

(defn- select-file!
  "Give `n` a selected file. `DataTransfer` is the only way to populate
  `.files` without a user gesture; answers true when the plant took, so a
  row can degrade to a stated skip rather than assert against an empty
  control."
  [n nm]
  (try
    (let [dt (js/DataTransfer.)]
      (.add (.-items dt) (js/File. #js ["x"] nm #js {:type "text/plain"}))
      (set! (.-files n) (.-files dt))
      (pos? (.-length (.-files n))))
    (catch :default _ false)))

;; ---------------------------------------------------------------------------
;; The stand-ins — what each control answers the two readers, written down
;; ---------------------------------------------------------------------------

(defn- ev [target] #js {:target target})

(defn- file-stand-in
  "An `<input type=file>` as the platform defines the properties these
  readers ask. `.files` is a `FileList` — non-null on this control and on
  NO other, which is what makes it the discriminator — and `.value` is
  the filename-mode fiction."
  [names]
  #js {:files (apply array (map (fn [nm] #js {:name nm}) names))
       :value (if-some [nm (first names)] (str "C:\\fakepath\\" nm) "")})

;; ---------------------------------------------------------------------------
;; 0 — THE PLATFORM CONTROL. Why a refusal, and not a repair
;; ---------------------------------------------------------------------------

(deftest the-platform-refuses-every-write-but-the-empty-string
  (if-not (browser?)
    (skip! "the engine's own answer is the whole of this row")
    (let [n (file-input!)]
      (try
        (testing "a non-empty assignment throws, and names nothing"
          (let [t (thrown-by #(set! (.-value n) "budget.csv"))]
            (is (some? t)
                "if this ever stops throwing, the prop refusal below is the
                 one to revisit first")
            (is (nil? (id-of t))
                (str "the engine's exception carries no :rf.error/id, no view "
                     "and no recovery — which is the failure mode the refusal "
                     "exists to replace"))))
        (testing "the empty string is accepted — it CLEARS the control"
          (is (nil? (thrown-by #(set! (.-value n) "")))
              "the one legal write, and therefore the one the refusal must
               not take away")
          (is (= "" (.-value n))))
        (finally (drop! n))))))

;; ---------------------------------------------------------------------------
;; 1 — THE PROP (rf2-u2tza)
;; ---------------------------------------------------------------------------

(deftest a-value-on-a-file-input-is-refused-at-the-source
  (testing "the refusal is minted where the element is, so it carries the
           view and the source coordinate — and so React never reaches the
           assignment that would throw"
    (doseq [[what hiccup]
            [["a bare :value"
              [:input {:type :file :value "budget.csv"}]]
             ["a :value with a change handler, the shape an author writes"
              [:input {:type :file :value "budget.csv" :on-change noop-change}]]
             ["a :multiple file input, which is the same control"
              [:input {:type :file :multiple true :value "budget.csv"
                       :on-change noop-change}]]
             ["a non-string :value, which React stringifies and writes"
              [:input {:type :file :value 0 :on-change noop-change}]]]]
      (is (= :rf.error/hicasso-file-input-value-prop
             (id-of (thrown-by #(codec/as-element hiccup))))
          what))))

(deftest the-empty-value-is-the-reset-idiom-and-is-permitted
  (testing "`:value \"\"` is how an author clears a file input from the
           model. React skips the assignment when the element already
           agrees, and performs it — legally — when it does not."
    (is (nil? (thrown-by #(codec/as-element
                           [:input {:type :file :value ""
                                    :on-change noop-change}])))
        "refusing a non-NIL value would have refused this one"))
  (testing "and a file input with no :value at all is the supported path"
    (is (nil? (thrown-by #(codec/as-element
                           [:input {:type :file :on-change noop-change}])))
        "uncontrolled, with the selection read off `.files` in an h/event")))

(deftest every-other-controlled-field-is-untouched
  (doseq [[what hiccup]
          [["a text input" [:input {:value "x" :on-input noop-change}]]
           ["a typed text input" [:input {:type :text :value "x"
                                          :on-input noop-change}]]
           ["a number input" [:input {:type :number :value "1"
                                      :on-input noop-change}]]
           ["a checkbox carrying a submission value"
            [:input {:type :checkbox :value "yes" :checked true
                     :on-change noop-change}]]
           ["a textarea" [:textarea {:value "x" :on-input noop-change}]]
           ["a select" [:select {:value "x" :on-change noop-change}]]]]
    (is (nil? (thrown-by #(codec/as-element hiccup))) what)))

(deftest mounting-a-valued-file-input-never-reaches-the-platform-write
  (testing "the repair measured where it was found: a real React commit
           over a real document. Before it, this mount threw the engine's
           `InvalidStateError` out of React's own `element.value =` — no
           id, no view, no recovery, and no way for the author to know
           which of their inputs it was about."
    (if-not (browser?)
      (skip! "React's assignment needs React's own commit")
      (let [c    (container!)
            root (react-dom-client/createRoot c)]
        (try
          (let [t (thrown-by
                   #(react-dom/flushSync
                     (fn []
                       (.render root (codec/as-element
                                      [:input {:type :file :value "budget.csv"
                                               :on-change noop-change}])))))]
            (is (= :rf.error/hicasso-file-input-value-prop (id-of t))
                "the refusal arrives instead of the engine exception")
            (is (not (instance? js/DOMException t))
                "and the engine exception does not arrive at all — the
                 element is refused before React can attempt the write"))
          (finally
            (thrown-by #(react-dom/flushSync (fn [] (.unmount root))))
            (drop-container! c)))))))

(deftest the-type-spelling-is-the-platforms-not-the-authors
  (testing "an HTML `type` is an enumerated attribute matched ASCII
           case-insensitively, so every spelling below IS a file input as
           far as the engine is concerned, and every one of them used to
           walk past a refusal that compared the string exactly
           (rf2-h6qm7). The keyword spellings are here because
           `convert-prop-value` hands React `(name kw)` unchanged, so the
           author's case survives into the props object either way."
    (doseq [[what hiccup]
            [["the shouted string, which is the spelling the audit found"
              [:input {:type "FILE" :value "budget.csv"
                       :on-change noop-change}]]
             ["the shouted keyword, the same attribute by the other door"
              [:input {:type :FILE :value "budget.csv"
                       :on-change noop-change}]]
             ["title case, which is what a form generator emits"
              [:input {:type "File" :value "budget.csv"
                       :on-change noop-change}]]
             ["and a mixed spelling, since the fold is total rather than a
               list of the plausible ones"
              [:input {:type "fIlE" :value "budget.csv"
                       :on-change noop-change}]]]]
      (is (= :rf.error/hicasso-file-input-value-prop
             (id-of (thrown-by #(codec/as-element hiccup))))
          what)))
  (testing "and the empty value stays the reset idiom at EVERY spelling —
           the fold widened which controls the predicate recognises, not
           which values it refuses"
    (is (nil? (thrown-by #(codec/as-element
                           [:input {:type "FILE" :value ""
                                    :on-change noop-change}])))))
  (testing "a type that merely CONTAINS the letters is not the control"
    (doseq [hiccup [[:input {:type "profile" :value "x" :on-change noop-change}]
                    [:input {:type "filename" :value "x" :on-change noop-change}]]]
      (is (nil? (thrown-by #(codec/as-element hiccup))))))
  (testing "and a non-string :type, which `convert-prop-value` leaves as a
           number, is asked the question without being handed to a string
           method"
    (is (nil? (thrown-by #(codec/as-element
                           [:input {:type 0 :value "x"
                                    :on-change noop-change}]))))))

(deftest mounting-a-shouted-file-input-never-reaches-the-platform-write
  (testing "the same measurement as the row above, at the shouted
           spelling — and the reason this one is worth its own mount.
           Before the fold the engine's `InvalidStateError` did not even
           arrive at the caller: React caught it inside its own commit
           and re-reported it as an UNCAUGHT page error, so the author
           had neither an id nor an exception to catch."
    (if-not (browser?)
      (skip! "React's assignment needs React's own commit")
      (let [c    (container!)
            root (react-dom-client/createRoot c)]
        (try
          (let [t (thrown-by
                   #(react-dom/flushSync
                     (fn []
                       (.render root (codec/as-element
                                      [:input {:type "FILE" :value "budget.csv"
                                               :on-change noop-change}])))))]
            (is (= :rf.error/hicasso-file-input-value-prop (id-of t))
                "the refusal arrives, and it arrives at the call site")
            (is (not (instance? js/DOMException t))))
          (finally
            (thrown-by #(react-dom/flushSync (fn [] (.unmount root))))
            (drop-container! c)))))))

;; ---------------------------------------------------------------------------
;; 2 — THE MARKER (rf2-lhsvs)
;; ---------------------------------------------------------------------------

(deftest the-value-marker-on-a-file-input-is-refused
  (testing "one file picked: `.value` is a plausible string that names a
           path nothing can open"
    (let [target (file-stand-in ["budget.csv"])]
      (is (= "C:\\fakepath\\budget.csv" (.-value target))
          "the stand-in is the platform's rule written down")
      (is (= :rf.error/hicasso-file-input-value-marker
             (id-of (thrown-by
                     #(intent/materialize [:app/upload :re-frame.hicasso/value]
                                          (ev target)))))
          "the marker used to lower to that string")))
  (testing "three files picked: it names the FIRST, and discards the rest"
    (let [target (file-stand-in ["a.csv" "b.csv" "c.csv"])]
      (is (= "C:\\fakepath\\a.csv" (.-value target)))
      (is (= :rf.error/hicasso-file-input-value-marker
             (id-of (thrown-by
                     #(intent/materialize [:app/upload :re-frame.hicasso/value]
                                          (ev target))))))))
  (testing "nothing picked is refused too — the control is the wrong one
           for this marker whatever it currently holds, and a refusal that
           waited for a selection would fire on the user's action rather
           than the author's mistake"
    (let [target (file-stand-in [])]
      (is (= "" (.-value target)))
      (is (= :rf.error/hicasso-file-input-value-marker
             (id-of (thrown-by
                     #(intent/materialize [:app/upload :re-frame.hicasso/value]
                                          (ev target)))))))))

(deftest the-checked-marker-is-not-the-value-marker
  (testing "only `::h/value` reads a value; `::h/checked` never reaches
           this reader and is not refused by it"
    (let [target #js {:files (array) :checked true}]
      (is (= [:app/pick true]
             (intent/materialize [:app/pick :re-frame.hicasso/checked]
                                 (ev target)))))))

(deftest the-marker-is-untouched-on-every-other-control
  (testing "`.files` is null on every input but the file one, and absent
           entirely off an input — so no other control pays more than the
           one property read"
    (doseq [[what target expected]
            [["a text input" #js {:files nil :value "typed"} "typed"]
             ["an <input type=email multiple>, which carries `.multiple`
               and has no selection"
              #js {:files nil :multiple true :value "a@b.com,c@d.com"}
              "a@b.com,c@d.com"]
             ["a <select multiple>, whose selection is a list"
              #js {:multiple true :value "a"
                   :selectedOptions (array #js {:value "a"} #js {:value "c"})}
              ["a" "c"]]
             ["a single <select>" #js {:value "urgent"} "urgent"]]]
      (is (= [:app/pick expected]
             (intent/materialize [:app/pick :re-frame.hicasso/value]
                                 (ev target)))
          what))))

(deftest the-real-file-input-agrees-with-the-stand-in
  (if-not (browser?)
    (skip! "the stand-in rows above carry the platform's rule on :node-test")
    (let [n (file-input!)]
      (try
        (testing "an empty file input still answers a non-null `.files`,
                 which is what lets the refusal fire on the author's
                 mistake rather than on the user's first selection"
          (is (some? (.-files n)))
          (is (= 0 (.-length (.-files n))))
          (is (= :rf.error/hicasso-file-input-value-marker
                 (id-of (thrown-by
                         #(intent/materialize
                           [:app/upload :re-frame.hicasso/value] (ev n)))))))
        (testing "and with a file selected, the engine's own answer is the
                 fakepath fiction the spec mandates"
          (if-not (select-file! n "budget.csv")
            (skip! "this engine declines a programmatic .files plant")
            (do
              (is (= "C:\\fakepath\\budget.csv" (.-value n))
                  "the string the marker used to hand the author")
              (is (= 1 (.-length (.-files n)))
                  "while the answer they wanted was here all along, on
                   `.files`, which is what an h/event reads")
              (is (= :rf.error/hicasso-file-input-value-marker
                     (id-of (thrown-by
                             #(intent/materialize
                               [:app/upload :re-frame.hicasso/value]
                               (ev n)))))))))
        (finally (drop! n))))))

(deftest the-marker-needed-no-fold-of-its-own
  (testing "the prop refusal had to be taught the platform's
           case-insensitive `type` matching (rf2-h6qm7); this one did not,
           and the difference is WHERE each looks. The prop predicate runs
           against the author's props object before React builds anything,
           so the author's spelling is all it has. The marker runs against
           a LIVE element on an event, by which time the platform has
           already resolved the type — and it asks `.files`, which is a
           property of the resolved control rather than a string anyone
           spelled. Two readers of one control, and only one of them can
           be fooled by shouting."
    (if-not (browser?)
      (skip! "only a live element can carry the platform's own normalisation")
      (let [n (file-input! "FILE")]
        (try
          (is (= "FILE" (.getAttribute n "type"))
              "the attribute keeps the author's case")
          (is (= "file" (.-type n))
              "and the IDL answers the platform's, which is the whole asymmetry")
          (is (some? (.-files n))
              "so the discriminator the marker reads is present, unshouted")
          (is (= :rf.error/hicasso-file-input-value-marker
                 (id-of (thrown-by
                         #(intent/materialize
                           [:app/upload :re-frame.hicasso/value] (ev n)))))
              "and the marker refusal fires at this spelling exactly as at
               the lowercase one — it always did")
          (finally (drop! n)))))))
