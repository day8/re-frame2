(ns re-frame.fresco.file-input-value-dom-cljs-test
  "A FILE INPUT HAS NO VALUE SURFACE.

  The guide rules it out in one sentence — chapter 04, supported controls:
  the File input row reads `no controlled value`, with `:on-change` and an
  `h/event` reading `.files` as the whole of its event form, `because the
  platform owns their value`. The same chapter states the posture the two
  rows below enforce: unsupported controlled shapes are REJECTED rather
  than approximated.

  `value` reaches a file input from two directions, and the two are
  reported differently.

  - **The prop.** `:value` on an `<input type=file>` makes it a controlled
    element as far as React is concerned, and React's `initInput` /
    `updateInput` both reach `element.value = …`. The platform refuses
    every assignment but the empty string on a file input, so the write
    throws `InvalidStateError` out of the commit. That engine exception is
    the report, and it is measured here on the engine so a future engine
    that accepted the write would go red.
  - **The marker.** `::h/value` would lower to `(.-value target)`, and
    `HTMLInputElement.value` is in FILENAME MODE on a file input: the
    literal fiction `C:\\fakepath\\` followed by the FIRST selected file's
    name — a plausible non-empty string naming one file out of however
    many were chosen, over a path nothing can open. No throw, no warning,
    no shape difference from an honest answer, so it is REFUSED with
    `:rf.error/fresco-file-input-value-marker` (its Spec 009 row).

  The marker reads a LIVE element on an event, where the platform has
  already resolved the type, and asks `.files` — a property of the
  resolved control rather than a string anyone spelled — so `:type
  \"FILE\"` needs no fold of its own.

  Runtime: `-dom-cljs-test`, so the rows run in both lanes. The stand-in
  rows carry the reader's rule under `:node-test` where there is no
  document; the real-control rows are skipped there rather than faked,
  the same shape `controlled_dom_cljs_test` uses for its caret rows."
  (:require [cljs.test :refer-macros [deftest is testing]]
            [re-frame.fresco.impl.codec :as rf.fresco.impl.codec]
            [re-frame.fresco.impl.intent :as rf.fresco.impl.intent]))

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
  arrives: a `DOMException` is the engine's report, an `ex-info` carrying
  an id is Fresco's refusal."
  [f]
  (try (f) nil (catch :default e e)))

(defn- id-of [e] (:rf.error/id (ex-data e)))

(defn- file-input!
  "A real `<input type=file>` in the document."
  []
  (let [n (js/document.createElement "input")]
    (.setAttribute n "type" "file")
    (.appendChild js/document.body n)
    n))

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
                  on a controlled :value, which Fresco leaves to it"
          (let [t (thrown-by #(set! (.-value n) "budget.csv"))]
            (is (some? t)
                "if this ever stops throwing, a controlled :value on a file
                 input has become legal and this file's premise is gone")
            (is (nil? (id-of t))
                "the engine's exception carries no :rf.error/id — it is the
                 platform's report, not Fresco's")))
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
    (is (nil? (thrown-by #(rf.fresco.impl.codec/as-element
                           [:input {:type :file :value ""
                                    :on-change noop-change}]))))
    (is (nil? (thrown-by #(rf.fresco.impl.codec/as-element
                           [:input {:type :file :on-change noop-change}]))))))

;; ---------------------------------------------------------------------------
;; 2 — THE MARKER
;; ---------------------------------------------------------------------------

(deftest the-value-marker-on-a-file-input-is-refused
  (testing "one file picked: `.value` is a plausible string that names a
           path nothing can open"
    (let [target (file-stand-in ["budget.csv"])]
      (is (= :rf.error/fresco-file-input-value-marker
             (id-of (thrown-by
                     #(rf.fresco.impl.intent/materialize [:app/upload :re-frame.fresco/value]
                                          (ev target)))))
          "the string the marker would otherwise lower to")))
  (testing "nothing picked is refused too — the control is the wrong one
           for this marker whatever it currently holds, and a refusal that
           waited for a selection would fire on the user's action rather
           than the author's mistake"
    (let [target (file-stand-in [])]
      (is (= :rf.error/fresco-file-input-value-marker
             (id-of (thrown-by
                     #(rf.fresco.impl.intent/materialize [:app/upload :re-frame.fresco/value]
                                          (ev target)))))))))

(deftest the-checked-marker-is-not-the-value-marker
  (testing "only `::h/value` reads a value; `::h/checked` never reaches
           this reader and is not refused by it"
    (let [target #js {:files (array) :checked true}]
      (is (= [:app/pick true]
             (rf.fresco.impl.intent/materialize [:app/pick :re-frame.fresco/checked]
                                 (ev target)))))))

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
          (is (= :rf.error/fresco-file-input-value-marker
                 (id-of (thrown-by
                         #(rf.fresco.impl.intent/materialize
                           [:app/upload :re-frame.fresco/value] (ev n)))))))
        (testing "and with a file selected, the engine's own answer is the
                 fakepath fiction the spec mandates"
          (if-not (select-file! n "budget.csv")
            (skip! "this engine declines a programmatic .files plant")
            (do
              (is (= "C:\\fakepath\\budget.csv" (.-value n))
                  "the string the marker would otherwise hand the author")
              (is (= 1 (.-length (.-files n)))
                  "while the answer they want is on `.files`, which is what
                   an h/event reads")
              (is (= :rf.error/fresco-file-input-value-marker
                     (id-of (thrown-by
                             #(rf.fresco.impl.intent/materialize
                               [:app/upload :re-frame.fresco/value]
                               (ev n)))))))))
        (finally (drop! n))))))
