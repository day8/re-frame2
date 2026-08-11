(ns re-frame.hicasso.examples.editor.l2-cljs-test
  "L1 AND L2 — THE EDITOR'S BODIES AS SEMANTIC TREES, AND THE MARKER LAW
  AS A FUNCTION (rf2-hic-078).

  `ht/tree` runs one hook-free body under injected read fixtures and
  answers the Spec 004B structural tree it returned. No React, no
  element, no hook, no DOM — so what a row here proves is what a body
  MEANS. `flow-dom-cljs-test` is the other half and says so.

  ## The fixture map IS the read set, and that is the point

  `:subs` refuses a read no fixture answers. This file leans on that
  deliberately: the fixture map written for each body is that body's read
  set, spelled out, so **adding a read to a view reds this file** rather
  than passing quietly. A body's edge set is what decides when it
  re-renders, and on a controlled surface that is the whole
  per-keystroke budget. Two rows here are nothing but an argument about
  an EMPTY fixture map — [[the-form-body-reads-nothing]] and its grid
  counterpart — because a layout body that read one moving value would
  put itself on every keystroke's path.

  ## The marker section is the sharpest thing in this file

  rf2-hic-025's authoring report opens with `::h/value` and the canonical
  event-vector shape being silently incompatible. That report describes
  the collision; the rows under §THE MARKER LAW put both spellings
  through `ht/materialize`, which is
  `re-frame.hicasso.impl.intent/materialize` itself rather than a
  re-derivation of it — so what is asserted is the substitution the
  browser path runs, and the finding is CONFIRMED by measurement from a
  second application rather than repeated."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.adapter.uix :as uix-adapter]
            [re-frame.hicasso :as h]
            [re-frame.hicasso.examples.editor.events :as events]
            [re-frame.hicasso.examples.editor.subs :as subs]
            [re-frame.hicasso.examples.editor.views :as views]
            [re-frame.hicasso.test :as ht]
            [re-frame.test-support :as test-support]))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture
    {:adapter       uix-adapter/adapter
     :ambient-frame nil}))

(defn- tagged [tree tag] (ht/find tree #(= tag (:tag %))))

(defn- field-tree
  "The title or slug field's tree, under the two reads it makes and no
  others."
  [field {:keys [value revision]}]
  (ht/tree [views/text-field {:field field :label "L"}]
           {:subs {[::subs/field field] value
                   [::subs/revision]    revision}}))

;; ---------------------------------------------------------------------------
;; THE MARKER LAW — rf2-hic-025 finding 1, confirmed from a second app
;; ---------------------------------------------------------------------------

(deftest the-positional-intent-substitutes-what-was-typed
  (is (= [::events/edit :title "typed"]
         (ht/materialize [::events/edit :title ::h/value] {:value "typed"}))
      "the spelling every controlled field in this application uses")
  (is (= [::events/set-published true]
         (ht/materialize [::events/set-published ::h/checked] {:checked true}))
      "and the checkbox's")
  (is (= [::events/edit 3 4 "7"]
         (ht/materialize [::events/edit 3 4 ::h/value] {:value "7"}))
      "the grid's three-argument shape substitutes the same way — the
       marker's position in the vector is immaterial, only its DEPTH is"))

(deftest the-canonical-payload-map-silently-swallows-the-marker
  ;; THE FINDING, as a measurement. `spec/Conventions.md` §Canonical
  ;; event-vector shape asks for `[<id> {<k> <v>}]` and the linter nudges
  ;; new code toward it. `materialize` maps over the intent's TOP LEVEL,
  ;; deliberately and for a stated cost reason, so the shape the
  ;; convention asks for cannot carry a marker.
  (let [written    [::events/edit {:field :title :value ::h/value}]
        dispatched (ht/materialize written {:value "typed"})]
    (is (= [::events/edit {:field :title :value :re-frame.hicasso/value}]
           dispatched)
        "NOT substituted, NOT refused, and not linted: the marker KEYWORD
         is what reaches the handler, lands in app-db and renders as text.
         The failure is silent at every layer — rf2-hic-025 finding 1,
         confirmed. This application therefore writes the positional
         shape at all five of its marker-carrying intents, and says so at
         `examples.editor.events`")
    (is (not= "typed" (get-in dispatched [1 :value]))
        "stated in the other direction, because this is the assertion a
         reader should be able to find: what the user typed is NOWHERE in
         the dispatched event"))

  (testing "the escape hatch works, and the report's costing of it holds"
    ;; The one callback form restores the payload map by reading the
    ;; event by hand. It is a real escape and it is not free: a closure
    ;; per field per render, which never compares equal
    ;; (`codec/boundary-props=` is conservative about functions), and the
    ;; return of the `.. e -target -value` that `::h/value` exists to
    ;; delete.
    ;;
    ;; SPELLED `h/hfn`, and that cost this witness a compile. The door's
    ;; own `as-element` docstring writes the escape as `(h/fn [i] …)` and
    ;; so does `impl.intent`'s; `h/fn` is the authoring surface the name
    ;; was chosen FOR and is not a var, so the published spelling does
    ;; not compile. What the compiler says is
    ;; `Use of undeclared Var re-frame.hicasso/fn` plus one
    ;; `Use of undeclared Var <your-ns>/e` per argument — a WARNING and
    ;; not an error, so the build completes and the page throws at
    ;; render. Reported by rf2-hic-078; the naming gap itself is
    ;; acknowledged in `h/defview`'s docstring and belongs to the bead
    ;; that owns naming.
    (let [cb (h/hfn [e] [::events/edit {:field :title
                                        :value (.. e -target -value)}])]
      (is (ht/callback? cb)
          "it is the one callback form, so a position expecting an intent
           vector will not silently take it as data")
      (is (= [::events/edit {:field :title :value "typed"}]
             (cb #js {"target" #js {"value" "typed"}}))
          "and it does produce the canonical shape — the choice is real,
           and it is between a linted shape with a closure and an
           unlinted shape without one"))))

;; ---------------------------------------------------------------------------
;; L1 — what the codec makes of the fields
;; ---------------------------------------------------------------------------

(deftest every-text-control-is-controlled-and-carries-a-revision
  (doseq [[label form] {"input"    [:input {:type "text"
                                            :value "v"
                                            ::h/revision 3
                                            :on-input [::events/edit :title ::h/value]}]
                        "textarea" [:textarea {:value "v"
                                               ::h/revision 3
                                               :on-input [::events/edit :body ::h/value]}]}]
    (is (true? (ht/controlled? form))
        (str "the " label " does not install the controlled shadow — the
              runtime's own decision, not a re-derivation of it"))
    (is (= 3 (ht/revision form))
        (str "the " label " does not carry the reset trigger")))

  (testing "the checkbox carries no revision, and could not"
    ;; MEASURED, and it corrects the guess this witness started from.
    ;; `ht/controlled?` asks whether the converge SHADOW stands in front
    ;; of the element, and for a value-less checkbox it does not: the
    ;; shadow exists for text convergence, so `install!`'s
    ;; `controlled-text-tag?` needs an `input`/`textarea` AND a non-nil
    ;; `:value` to re-baseline to. A checkbox written idiomatically has
    ;; `:checked` and no `:value`. React's own `checked` ownership is what
    ;; controls it, and there is no draft between the click and the
    ;; dispatch for a shadow to hold.
    (let [box [:input {:type "checkbox" :checked false
                       :on-change [::events/set-published ::h/checked]}]]
      (is (false? (ht/controlled? box))
          "a value-less checkbox is not a CONVERGING control, and this row
           says so rather than leaving `examples.editor.views` asserting
           it from taste")
      (is (nil? (ht/revision box)))
      (is (true? (contains? (ht/element-props box) "checked"))
          "`false` reaches the element as a slot — an owned `:checked`
           wins by PRESENCE, not truthiness, and a truthiness test in the
           codec would leave an unchecked box uncontrolled")))

  (testing "and the REFUSAL for one is not reachable from this tier"
    ;; A limit, stated where it was met. A `::h/revision` on a value-less
    ;; checkbox is refused with
    ;; `:rf.error/hicasso-revision-not-controlled` — but by
    ;; `controlled/install!` reading a private slot that
    ;; `codec/native-element` stashes while creating a real element, and
    ;; `ht/controlled?` builds its props with `convert-props`, which does
    ;; not. So L1 sees no revision to refuse and answers quietly.
    ;;
    ;; The consequence for a reader of this file: `ht/revision` reports
    ;; what the AUTHOR wrote and `ht/element-props` reports what the codec
    ;; EMITS, and no L1 door reports what the runtime would say about the
    ;; pair. The refusal is asserted in `flow-dom-cljs-test`, where an
    ;; element is really created.
    (is (false? (ht/controlled? [:input {:type "checkbox" :checked false
                                         ::h/revision 1
                                         :on-change [::events/set-published
                                                     ::h/checked]}]))
        "recorded as measured conduct and not as approval — a tier that
         answered `true` here would be worse, but a tier that answers at
         all is stating something the runtime does not agree with")))

(deftest the-revision-is-a-trigger-and-not-an-emitted-slot
  (let [form  [:input {:type "text" :value "v" ::h/revision 7}]
        slots (ht/element-props form)]
    (is (contains? slots "value"))
    (is (= 7 (ht/revision form))
        "the author's own attribute map carries it — `ht/revision` reads
         it pre-merge, which is where the codec reads it")
    (is (= #{"type" "value"} (set (keys slots)))
        "and the emitted slots are the two DOM ones. A reader of
         `text-field` meets three keys in one attribute map of which two
         are DOM and one is a runtime trigger, and the `::h/` namespace is
         the whole of the signal — recorded in `examples.editor.views` as
         the one place the door reads awkwardly.

         The stronger claim — that the trigger never reaches the WIRE —
         is the runtime's own and is banked in `revision-dom-cljs-test`;
         this row is only about what an author's map projects to.")))

;; ---------------------------------------------------------------------------
;; L2 — the bodies
;; ---------------------------------------------------------------------------

(deftest a-text-field-reads-its-own-address-and-the-counter
  (let [input (tagged (field-tree :title {:value "Title" :revision 0}) :input)
        attrs (ht/attrs input)]
    (is (= "Title" (:value attrs)))
    (is (= [::events/edit :title ::h/value] (:on-input attrs))
        "the intent is DATA — a vector `=` can compare, which is what
         makes two renders of one field's handler equal and what keeps a
         closure off the props map")
    (is (= "title" (:id attrs)))
    (is (= "title" (:for (ht/attrs (tagged (field-tree :title {:value "T" :revision 0})
                                           :label))))
        "and the label points at it, so the control has an accessible
         name and the package's own clj-kondo export stays quiet")))

(deftest the-slug-field-is-the-same-body-with-a-different-address
  ;; The fixture map is the proof: `[::subs/field :slug]` and nothing
  ;; else. A parametric subscription is keyed by its whole query vector,
  ;; so title and slug are separate cells with separate equality gates —
  ;; which is the property the per-keystroke budget rests on.
  (let [attrs (ht/attrs (tagged (field-tree :slug {:value "a-slug" :revision 0}) :input))]
    (is (= "a-slug" (:value attrs)))
    (is (= [::events/edit :slug ::h/value] (:on-input attrs)))))

(deftest the-checkbox-body-reads-one-address-and-writes-one-marker
  (let [tree  (ht/tree [views/published-box {:label "Published"}]
                       {:subs {[::subs/field :published?] true}})
        attrs (ht/attrs (tagged tree :input))]
    (is (true? (:checked attrs)))
    (is (= [::events/set-published ::h/checked] (:on-change attrs)))
    (is (= "checkbox" (:type attrs)))))

(deftest the-buttons-read-one-value-between-them
  (testing "clean: both disabled"
    (let [tree (ht/tree [views/buttons {}] {:subs {[::subs/dirty?] false}})
          btns (ht/find-all tree #(= :button (:tag %)))]
      (is (= 2 (count btns)))
      (is (= [true true] (mapv (comp :disabled ht/attrs) btns)))))

  (testing "dirty: both live, and the discard says nothing about which field"
    (let [tree (ht/tree [views/buttons {}] {:subs {[::subs/dirty?] true}})
          btns (ht/find-all tree #(= :button (:tag %)))]
      (is (= [false false] (mapv (comp :disabled ht/attrs) btns))
          "`false` and not nil — per 004B a false attribute is RECORDED
           and a nil one is dropped, so `(is (nil? …))` here would be
           green against a button that had no disabled slot at all")
      (is (= [[::events/save {:at "now"}] [::events/discard {}]]
             (mapv (comp :on-click ht/attrs) btns))
          "both carry the CANONICAL payload map, which they may because
           neither carries a marker — the contrast with the fields, in
           one tree"))))

(deftest the-readout-reads-only-committed-addresses
  ;; The fixture map is the assertion: four `::subs/committed` reads and
  ;; not one `::subs/field`. A keystroke moves the draft, so it does not
  ;; notify this body at all — which is what makes "echoes only committed
  ;; state" readable off the page rather than merely true.
  (let [tree (ht/tree [views/readout {}]
                      {:subs {[::subs/committed :title]      "T"
                              [::subs/committed :slug]       "s"
                              [::subs/committed :body]       "B"
                              [::subs/committed :published?] false}})]
    (is (= ["T" "s" "B" "false"]
           (mapv ht/text (ht/find-all tree #(= :dd (:tag %))))))))

(deftest the-form-body-reads-nothing
  ;; An EMPTY fixture map, and the kit refuses any read. This is the row
  ;; that keeps the parent off the typing path: every value on the page
  ;; comes from a child's own body, so this body runs at mount and then
  ;; only if its own props change.
  (let [tree (ht/tree [views/editor {}] {:subs {}})]
    (is (some? tree)
        "the form body made a read. Every value on this page belongs to a
         child's own body; a read here puts the form, and a props compare
         over six children, on every keystroke's path")

    (testing "and it calls the four controls with the props they need"
      (let [calls (ht/find-all tree #(some? (:view-id %)))]
        (is (= 6 (count calls))
            "four controls, the buttons and the readout — L2 records a
             child boundary as a CALL and does not run its body")
        (is (= [:title :slug nil nil nil nil]
               (mapv (comp :field ht/attrs) calls)))
        (is (= ["Title" "Slug" "Body" "Published" nil nil]
               (mapv (comp :label ht/attrs) calls))
            "the labels are ordinary data handed down, not a subsystem —
             §7's whole i18n answer at this size")))))

(deftest every-intent-the-form-offers-is-a-vector
  ;; `ht/intents` answers only handler sites holding a literal intent
  ;; VECTOR; a site carrying a function contributes nothing. So an empty
  ;; answer here would mean the fields had grown closures.
  (let [tree    (field-tree :title {:value "T" :revision 0})
        offered (ht/intents tree)]
    (is (= [[::events/edit :title ::h/value]] offered)
        "one site, one vector. This application needed `h/fn` nowhere —
         every intent said what it meant as data, which is rf2-hic-025's
         observation about `hfn` confirmed from a form of four controls")))
