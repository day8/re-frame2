(ns re-frame.hicasso.error-shape-cljs-test
  "THE REFUSAL SHAPE, ASSERTED AS A MAP (rf2-hic-007).

  Spec SN §3.6 is one sentence — *every refusal has a stable error id,
  source coordinate, view, frame where relevant, tree path or host-prop
  position, offending value, expected shape, and actionable recovery* —
  and until this bead the package satisfied about half of it, in six
  copies.

  ## Why nothing here asserts `(thrown? …)`

  Because `(thrown? …)` is not a witness, and this file is the one place
  where that matters most. rf2-hic-011 ran a NAMED sabotage against its
  own guard, the reads still threw — from a different layer, with a
  different error id — and the assertion stayed green. A refusal's whole
  subject is its IDENTITY, so every row below asserts the ex-data as a
  MAP: the id a tool branches on, the fn that raised it, the reason, the
  recovery, and the ambient pair the constructor supplied.

  ## Three refusals, chosen for their ORIGINS rather than their ids

  The shape's two ambient fields come from wherever the runtime is when a
  refusal is minted, so the interesting axis is not which complaint fired
  but which extent it fired in. There are exactly three, and one row
  covers each:

  1. **Inside a boundary body.** The bulk of the package's refusals. The
     ambient origin is the rendering view, and the coordinate is the one
     `defview` captured — so the refusal names a file and a line the
     author can open, for a complaint raised four call frames below the
     boundary in the codec's hiccup walk.
  2. **Inside a declaration.** `defhost`'s own refusals fire during the
     `def`, at namespace load, with no render anywhere. The extent the
     macro opens is what puts the offending declaration's coordinate on
     them.
  3. **Outside both.** A read from an event handler, a timer, a callback,
     a top-level form. There IS no view, and the refusal says so by
     carrying neither field rather than by carrying two nils.

  ## And the guard, which is the reason the other three can be short

  A test can only assert about refusals somebody remembered to enumerate,
  which is precisely how one shape became six copies. The constructor
  refuses to mint an incomplete refusal, so the completeness property
  holds for the refusals nobody has written a row for yet —
  [[an-incomplete-refusal-is-itself-refused]] is the witness for that, and
  it is the permanent form of the sabotage rf2-hic-007 ran once by hand."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.adapter.uix :as uix-adapter]
            [re-frame.core :as rf]
            [re-frame.hicasso :as h]
            [re-frame.hicasso.impl.codec :as codec]
            [re-frame.hicasso.impl.collector :as collector]
            [re-frame.hicasso.impl.error :as error]
            [re-frame.hicasso.impl.mount :as mount]
            [re-frame.test-support :as test-support]
            ["react-dom/server" :as react-dom-server]))

(def ^:private frame-id ::error-shape)

(rf/reg-sub :error-shape/anything (fn [db _] (:anything db)))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture
    {:adapter       uix-adapter/adapter
     :ambient-frame nil
     :init-fn       (fn [] (collector/reset-runtime!))}))

;; ---------------------------------------------------------------------------
;; The consumer's source file — declared through the public door, so the
;; coordinates below are the macro's own and not a fixture's
;; ---------------------------------------------------------------------------

(h/defview refusing-row
  "A boundary whose body writes a plain function in head position — the
  ordinary authoring mistake HD-016 makes loud. The refusal is raised by
  `codec/vec->element`, which knows nothing about this view; everything
  the assertion reads about WHERE comes from the ambient origin."
  [_]
  [:li [(fn a-plain-function [] [:span "not a head"])]])

(h/defhost date-picker
  "A well-formed crossing, declared so the DECLARATION channel has a real
  macro-captured coordinate to assert on rather than a fabricated one."
  (fn DatePicker [_props] nil)
  {:callbacks {:on-change :event}})

;; ---------------------------------------------------------------------------
;; Harness
;; ---------------------------------------------------------------------------

(defn- render!
  "Server-render `hiccup` under a live frame. The server renderer runs
  bodies for real, which is what makes a body-scoped refusal reachable
  without a DOM."
  [hiccup]
  (set! (.-IS_REACT_ACT_ENVIRONMENT js/globalThis) false)
  (rf/make-frame {:id frame-id})
  (react-dom-server/renderToString
    (mount/provider frame-id (codec/root-element frame-id hiccup))))

(defn- refusal
  "Run `f`, and answer the ex-data of the refusal it raised — or a marker
  map saying what happened instead, so a row that fails says which of the
  two ways it failed rather than throwing out of the assertion."
  [f]
  (try
    (f)
    {::outcome :returned-without-refusing}
    (catch :default e
      (or (ex-data e) {::outcome :threw-without-ex-data ::message (ex-message e)}))))

(defn- coordinate-of
  "The `:source` slot, normalised to the four keys the shape names. Kept
  separate from the identity assertion because the FILE is the one field
  whose value depends on where the checkout lives."
  [data]
  (let [{:keys [ns file line column]} (:source data)]
    {:ns ns :file? (string? file) :line? (pos-int? line) :column? (pos-int? column)}))

;; ---------------------------------------------------------------------------
;; 1. A refusal raised inside a boundary body
;; ---------------------------------------------------------------------------

(deftest a-refusal-from-a-body-carries-the-rendering-view-and-its-coordinate
  (let [data (refusal #(render! [refusing-row {}]))]

    (testing "the identity is asserted whole — a refusal that threw from
              somewhere else, or with a neighbouring id, cannot satisfy this"
      (is (= {:rf.error/id :rf.error/hicasso-bad-head
              :where       'front.codec/vec->element
              :recovery    :call-it-or-make-it-a-view
              :view        "re-frame.hicasso.error-shape-cljs-test/refusing-row"}
             (select-keys data [:rf.error/id :where :recovery :view]))))

    (testing "the reason is a human sentence, and the offending value rides
              with it — the shape's `offending value` slot"
      (is (string? (:reason data)))
      (is (fn? (:head data)) "the head that was refused is the head that was written"))

    (testing "and the source coordinate is `defview`'s, captured at
              macro-expansion time and resolved back by name"
      (is (= {:ns 're-frame.hicasso.error-shape-cljs-test
              :file? true :line? true :column? true}
             (coordinate-of data))))

    (testing "the refusal is complete by the shape's own definition"
      (is (= [] (error/missing-fields (:rf.error/id data) (:where data)
                                      (:reason data) (:recovery data)))))))

;; ---------------------------------------------------------------------------
;; 2. A refusal raised inside a declaration
;; ---------------------------------------------------------------------------
;;
;; The refusals `mint-host!` raises abort a `def` at namespace load, so a
;; bad `defhost` cannot be written here — the file would not load to be
;; tested. The channel is therefore asserted in its two halves: the macro
;; really registers a coordinate for a declaration, and a refusal raised
;; inside a declaration extent really picks it up.

(deftest defhost-registers-its-macro-captured-coordinate
  (testing "the coordinate is keyed by the same `<ns>/<sym>` string the
            macro stamps as `displayName`, and it is the declaration's own"
    (let [coord (error/source-of "re-frame.hicasso.error-shape-cljs-test/date-picker")]
      (is (some? coord) "defhost registered a coordinate")
      (is (= 're-frame.hicasso.error-shape-cljs-test (:ns coord)))
      (is (string? (:file coord)))
      (is (pos-int? (:line coord)))
      (is (pos-int? (:column coord))))))

(deftest a-refusal-raised-inside-a-declaration-extent-carries-that-declaration
  (testing "this is the extent `defhost` opens around `mint-host!`, driven
            directly because a declaration that refuses cannot be written
            at the top of a file that must load"
    (error/declaring! "app.pickers/calendar" {:ns 'app.pickers :file "app/pickers.cljs"
                                              :line 12 :column 3})
    (let [data (refusal #(error/fail! :rf.error/hicasso-host-unknown-option
                                      'front.codec/mint-host!
                                      "defhost was given an option it does not know."
                                      :remove-the-unknown-option
                                      {:option :nope}))]
      (error/declared!)
      (is (= {:rf.error/id :rf.error/hicasso-host-unknown-option
              :where       'front.codec/mint-host!
              :recovery    :remove-the-unknown-option
              :view        "app.pickers/calendar"
              :source      {:ns 'app.pickers :file "app/pickers.cljs" :line 12 :column 3}
              :option      :nope}
             (dissoc data :reason)))))

  (testing "and the extent closes, so the next refusal is not attributed to it"
    (is (nil? (:view (refusal #(error/fail! :rf.error/hicasso-state-bad-option
                                            'front.state/reg-state
                                            "reg-state options must be a map."
                                            :pass-a-map-of-options
                                            {})))))))

;; ---------------------------------------------------------------------------
;; 3. A refusal raised outside both extents
;; ---------------------------------------------------------------------------

(deftest a-refusal-outside-every-extent-omits-the-ambient-pair
  (let [data (refusal #(h/sub [:error-shape/anything]))]

    (testing "the identity, whole"
      (is (= {:rf.error/id :rf.error/hicasso-sub-outside-render
              :where       're-frame.hicasso.impl.collector/read-key!
              :recovery    :read-inside-a-boundary-body
              :query-v     [:error-shape/anything]}
             (select-keys data [:rf.error/id :where :recovery :query-v]))))

    (testing "there is no view and no coordinate, and the refusal says that
              by ABSENCE — a nil `:view` would claim the read happened in a
              boundary the runtime could not name, which is a different and
              false statement"
      (is (not (contains? data :view)))
      (is (not (contains? data :source))))

    (testing "the four required fields are still all present — the ambient
              pair is situational, the quartet never is"
      (is (= [] (error/missing-fields (:rf.error/id data) (:where data)
                                      (:reason data) (:recovery data)))))))

;; ---------------------------------------------------------------------------
;; The guard — the permanent form of the recovery-field sabotage
;; ---------------------------------------------------------------------------

(deftest an-incomplete-refusal-is-itself-refused
  (testing "a refusal minted without its recovery does not reach a user: the
            constructor refuses it, naming the field that is missing and the
            refusal that tried to omit it"
    (let [data (refusal #(error/fail! :rf.error/hicasso-presence-child-unkeyed
                                      'front.presence/child-key
                                      "A presence child has no :key."
                                      nil
                                      {:child [:li]}))]
      (is (= {:rf.error/id :rf.error/hicasso-refusal-incomplete
              :where       're-frame.hicasso.impl.error/fail!
              :recovery    :give-the-refusal-every-required-field
              :refusal     :rf.error/hicasso-presence-child-unkeyed
              :missing     [:recovery]}
             (dissoc data :reason)))))

  (testing "and the guard reads the same rule the tests above assert with,
            rather than a second copy of it"
    (is (= [:rf.error/id :where :reason :recovery] error/required))
    (is (= [:rf.error/id :where :reason :recovery]
           (error/missing-fields nil nil "" nil))
        "every required field, reported in one pass")
    (is (= [] (error/missing-fields :rf.error/x 'a/b "why" :how)))))
