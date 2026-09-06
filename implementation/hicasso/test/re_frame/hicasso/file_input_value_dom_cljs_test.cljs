(ns re-frame.hicasso.file-input-value-dom-cljs-test
  "A FILE INPUT HAS NO VALUE SURFACE.

  The guide rules it out in one sentence — chapter 04, supported controls:
  the File input row reads `no controlled value`, with `:on-change` and an
  `h/event` reading `.files` as the whole of its event form, `because the
  platform owns their value`. The same chapter states the posture the two
  rows below enforce: unsupported controlled shapes are REJECTED rather
  than approximated. Both defects are that sentence not being kept.

  `value` reaches a file input from two directions, and the two are
  reported differently.

  - **The prop.** `:value` on an `<input type=file>` makes it a controlled
    element as far as React is concerned, and React's `initInput` /
    `updateInput` both reach `element.value = …`. The platform refuses
    every assignment but the empty string on a file input, so the write
    throws `InvalidStateError` out of the commit. That engine exception is
    the report, and it is measured here on the engine so a future engine
    that accepted the write would go red.
  - **The marker.** `::h/value` lowered to `(.-value target)`, and
    `HTMLInputElement.value` is in FILENAME MODE on a file input: the
    literal fiction `C:\\fakepath\\` followed by the FIRST selected file's
    name — a plausible non-empty string naming one file out of however
    many were chosen, over a path nothing can open. No throw, no warning,
    no shape difference from an honest answer, so it is REFUSED with
    `:rf.error/hicasso-file-input-value-marker` (its Spec 009 row).

  The marker reads a LIVE element on an event, where the platform has
  already resolved the type, and asks `.files` — a property of the
  resolved control rather than a string anyone spelled — so `:type
  \"FILE\"` needs no fold of its own.

  Runtime: `-dom-cljs-test`, so the rows run in both lanes. The stand-in
  rows carry the reader's rule under `:node-test` where there is no
  document; the real-control rows are skipped there rather than faked,
  the same shape `controlled_dom_cljs_test` uses for its caret rows."
  (:require [cljs.test :refer-macros [deftest is testing]]
            [re-frame.hicasso.impl.codec :as rf.hicasso.impl.codec]
            [re-frame.hicasso.impl.intent :as rf.hicasso.impl.intent]))

(defn- browser? []
  (and (exists? js/document) (some? js/document) (some? (.-body js/document))))

(defn- skip! [why]
  (is true (str "a real file input needs a real document — " why)))

(defn- noop-change [_e])

;; ---------------------------------------------------------------------------
;; The harness
;; ---------------------------------------------------------------------------

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
  the rows below turn on."
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
;; 0 — THE PLATFORM CONTROL. The engine's own report on the prop half
;; ---------------------------------------------------------------------------

(deftest the-platform-refuses-every-write-but-the-empty-string
  (if-not (browser?)
    (skip! "the engine's own answer is the whole of this row")
    (let [n (file-input!)]
      (try
        (testing "a non-empty assignment throws — the platform's own report
                  on a controlled :value, which Hicasso leaves to it"
          (let [t (thrown-by #(set! (.-value n) "budget.csv"))]
            (is (some? t)
                "if this ever stops throwing, a controlled :value on a file
                 input has become legal and this file's premise is gone")
            (is (nil? (id-of t))
                "the engine's exception carries no :rf.error/id — it is the
                 platform's report, not Hicasso's")))
        (testing "the empty string is accepted — it CLEARS the control"
          (is (nil? (thrown-by #(set! (.-value n) "")))
              "the one legal write: the reset idiom")
          (is (= "" (.-value n))))
        (finally (drop! n))))))

;; ---------------------------------------------------------------------------
;; 1 — THE PROP, at the codec: lowered like any other value
;; ---------------------------------------------------------------------------

(deftest a-file-input-lowers-like-any-other-control
  (testing "`:value \"\"` is how an author clears a file input from the
           model, and a file input with no :value at all is the supported
           path — uncontrolled, with the selection read off `.files` in an
           h/event. The codec lowers both without comment."
    (is (nil? (thrown-by #(rf.hicasso.impl.codec/as-element
                           [:input {:type :file :value ""
                                    :on-change noop-change}]))))
    (is (nil? (thrown-by #(rf.hicasso.impl.codec/as-element
                           [:input {:type :file :on-change noop-change}]))))))

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
    (is (nil? (thrown-by #(rf.hicasso.impl.codec/as-element hiccup))) what)))

;; ---------------------------------------------------------------------------
;; 2 — THE MARKER
;; ---------------------------------------------------------------------------

(deftest the-value-marker-on-a-file-input-is-refused
  (testing "one file picked: `.value` is a plausible string that names a
           path nothing can open"
    (let [target (file-stand-in ["budget.csv"])]
      (is (= "C:\\fakepath\\budget.csv" (.-value target))
          "the stand-in is the platform's rule written down")
      (is (= :rf.error/hicasso-file-input-value-marker
             (id-of (thrown-by
                     #(rf.hicasso.impl.intent/materialize [:app/upload :re-frame.hicasso/value]
                                          (ev target)))))
          "the marker used to lower to that string")))
  (testing "three files picked: it names the FIRST, and discards the rest"
    (let [target (file-stand-in ["a.csv" "b.csv" "c.csv"])]
      (is (= "C:\\fakepath\\a.csv" (.-value target)))
      (is (= :rf.error/hicasso-file-input-value-marker
             (id-of (thrown-by
                     #(rf.hicasso.impl.intent/materialize [:app/upload :re-frame.hicasso/value]
                                          (ev target))))))))
  (testing "nothing picked is refused too — the control is the wrong one
           for this marker whatever it currently holds, and a refusal that
           waited for a selection would fire on the user's action rather
           than the author's mistake"
    (let [target (file-stand-in [])]
      (is (= "" (.-value target)))
      (is (= :rf.error/hicasso-file-input-value-marker
             (id-of (thrown-by
                     #(rf.hicasso.impl.intent/materialize [:app/upload :re-frame.hicasso/value]
                                          (ev target)))))))))

(deftest the-checked-marker-is-not-the-value-marker
  (testing "only `::h/value` reads a value; `::h/checked` never reaches
           this reader and is not refused by it"
    (let [target #js {:files (array) :checked true}]
      (is (= [:app/pick true]
             (rf.hicasso.impl.intent/materialize [:app/pick :re-frame.hicasso/checked]
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
             (rf.hicasso.impl.intent/materialize [:app/pick :re-frame.hicasso/value]
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
                         #(rf.hicasso.impl.intent/materialize
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
                             #(rf.hicasso.impl.intent/materialize
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
                         #(rf.hicasso.impl.intent/materialize
                           [:app/upload :re-frame.hicasso/value] (ev n)))))
              "and the marker refusal fires at this spelling exactly as at
               the lowercase one — it always did")
          (finally (drop! n)))))))
