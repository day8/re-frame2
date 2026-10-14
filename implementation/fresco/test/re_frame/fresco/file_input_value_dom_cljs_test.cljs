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
    the report.
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
  document; the real-control row is skipped there rather than faked,
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

(deftest the-real-file-input-agrees-with-the-stand-in
  (if-not (browser?)
    (skip! "the stand-in rows above carry the platform's rule on :node-test")
    (let [n (file-input!)]
      (try
        (testing "an empty REAL file input already answers a non-null
                 `.files`, so the refusal fires on the author's mistake rather
                 than on the user's first selection"
          (is (= :rf.error/fresco-file-input-value-marker
                 (id-of (thrown-by
                         #(rf.fresco.impl.intent/materialize
                           [:app/upload :re-frame.fresco/value] (ev n)))))))
        (finally (drop! n))))))
