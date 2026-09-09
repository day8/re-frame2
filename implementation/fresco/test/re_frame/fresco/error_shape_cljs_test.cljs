(ns re-frame.hicasso.error-shape-cljs-test
  "THE REFUSAL SHAPE, ASSERTED AS A MAP.

  `re-frame.hicasso.impl.error/fail!` is the one constructor every
  Hicasso refusal routes through, and this file is its one test: the
  canonical map core's builder produces (`:rf.error/id`, `:where`,
  `:reason`, `:recovery :no-recovery`), the bracketed-id message, the
  protected merge (`extra` cannot rewrite a canonical field or forge the
  ambient pair), and the ambient `:view` / `:source` stamp — present
  inside a boundary body and inside a declaration, absent outside both.

  Nothing here asserts `(thrown? …)`: a refusal's whole subject is its
  IDENTITY, and a `(thrown? …)` stays green for a throw from any layer
  with any id. Every row asserts the ex-data as a map."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.adapter.uix :as rf.adapter.uix]
            [re-frame.core :as rf]
            [re-frame.hicasso :as rf.hicasso]
            [re-frame.hicasso.impl.codec :as rf.hicasso.impl.codec]
            [re-frame.hicasso.impl.collector :as rf.hicasso.impl.collector]
            [re-frame.hicasso.impl.error :as rf.hicasso.impl.error]
            [re-frame.hicasso.impl.mount :as rf.hicasso.impl.mount]
            [re-frame.test-support :as rf.test-support]
            ["react-dom/server" :as react-dom-server]))

(def ^:private frame-id ::error-shape)

(rf/reg-sub :error-shape/anything (fn [db _] (:anything db)))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    {:adapter       rf.adapter.uix/adapter
     :ambient-frame nil
     :init-fn       (fn [] (rf.hicasso.impl.collector/reset-runtime!))}))

;; ---------------------------------------------------------------------------
;; The consumer's source file — declared through the public door, so the
;; coordinates below are the macro's own and not a fixture's
;; ---------------------------------------------------------------------------

(rf.hicasso/defview refusing-row
  "A boundary whose body writes a plain function in head position — the
  ordinary authoring mistake HD-016 makes loud. The refusal is raised by
  `codec/vec->element`, which knows nothing about this view; everything
  the assertion reads about WHERE comes from the ambient origin."
  [_]
  [:li [(fn a-plain-function [] [:span "not a head"])]])

(rf.hicasso/defhost date-picker
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
    (rf.hicasso.impl.mount/provider frame-id (rf.hicasso.impl.codec/root-element frame-id hiccup))))

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

(defn- message-of
  "The message of the refusal `f` raised, or nil."
  [f]
  (try (f) nil (catch :default e (ex-message e))))

(defn- coordinate-of
  "The `:source` slot, normalised to the four keys the shape names. Kept
  separate from the identity assertion because the FILE is the one field
  whose value depends on where the checkout lives."
  [data]
  (let [{:keys [ns file line column]} (:source data)]
    {:ns ns :file? (string? file) :line? (pos-int? line) :column? (pos-int? column)}))

;; ---------------------------------------------------------------------------
;; The constructor
;; ---------------------------------------------------------------------------

(deftest fail-builds-the-canonical-map-and-the-bracketed-id-message
  (let [throw! #(rf.hicasso.impl.error/fail! :rf.error/hicasso-state-bad-argument
                             're-frame.hicasso.impl.state/reg-state
                             "reg-state options must be a map."
                             {:options :not-a-map})]
    (testing "the ex-data is core's four slots over the class's own — asserted
              whole, so a slot the constructor stopped writing cannot hide"
      (is (= {:rf.error/id :rf.error/hicasso-state-bad-argument
              :where       're-frame.hicasso.impl.state/reg-state
              :reason      "reg-state options must be a map."
              :recovery    :no-recovery
              :options     :not-a-map}
             (refusal throw!))))
    (testing "and the message is the reason with the bracketed id appended —
              core's derivation, so message and discriminator cannot drift"
      (is (= "reg-state options must be a map. [:rf.error/hicasso-state-bad-argument]"
             (message-of throw!))))))

(deftest extra-cannot-erase-or-replace-what-the-constructor-guarantees
  (testing "the four canonical fields and the two ambient ones belong to the
            CONSTRUCTOR: an `extra` that spells any of them loses, and the
            class's own slots ride alongside untouched"
    (rf.hicasso.impl.error/declaring! "app.pickers/calendar" {:ns 'app.pickers :file "app/pickers.cljs"
                                              :line 12 :column 3})
    (let [data (refusal #(rf.hicasso.impl.error/fail! :rf.error/hicasso-state-bad-argument
                                      're-frame.hicasso.impl.state/reg-state
                                      "reg-state options must be a map."
                                      {:rf.error/id :rf.error/hicasso-true-child
                                       :where       nil
                                       :reason      "replaced"
                                       :recovery    :something-else
                                       :view        "app.impostor/not-this-one"
                                       :source      {:ns 'app.impostor}
                                       :options     :not-a-map}))]
      (rf.hicasso.impl.error/declared!)
      (is (= {:rf.error/id :rf.error/hicasso-state-bad-argument
              :where       're-frame.hicasso.impl.state/reg-state
              :reason      "reg-state options must be a map."
              :recovery    :no-recovery
              :view        "app.pickers/calendar"
              :source      {:ns 'app.pickers :file "app/pickers.cljs" :line 12 :column 3}
              :options     :not-a-map}
             data)
          "the whole ex-data, so an overridden field cannot hide behind a
           select-keys that never looked at it"))))

(deftest a-forged-ambient-pair-loses-where-there-is-no-origin-to-overwrite-it
  ;; Overwriting only reaches a field the constructor has a value for, and
  ;; the ambient pair has one only while an origin names a view. The case
  ;; worth driving is the one where it does not — every event handler,
  ;; timer and callback, and every production build.
  (testing "outside every extent a forged `:view` and `:source` are DROPPED,
            not overwritten — a catch site reading `:view` gets absence
            rather than a file name the call site made up"
    (let [data (refusal #(rf.hicasso.impl.error/fail! :rf.error/hicasso-state-bad-argument
                                      're-frame.hicasso.impl.state/reg-state
                                      "reg-state options must be a map."
                                      {:view    "app.impostor/not-a-view"
                                       :source  {:ns 'app.impostor
                                                 :file "app/impostor.cljs"
                                                 :line 1 :column 1}
                                       :options :not-a-map}))]
      (is (= {:rf.error/id :rf.error/hicasso-state-bad-argument
              :where       're-frame.hicasso.impl.state/reg-state
              :reason      "reg-state options must be a map."
              :recovery    :no-recovery
              :options     :not-a-map}
             data)
          "the whole ex-data — the class's own slot rides through, the two
           ambient keys are gone, and nothing was left nil in their place")
      (is (not (contains? data :view)))
      (is (not (contains? data :source))))))

;; ---------------------------------------------------------------------------
;; The ambient pair, in each of the three extents a refusal can fire in
;; ---------------------------------------------------------------------------

(deftest a-refusal-from-a-body-carries-the-rendering-view-and-its-coordinate
  (let [data (refusal #(render! [refusing-row {}]))]

    (testing "the identity is asserted whole — a refusal that threw from
              somewhere else, or with a neighbouring id, cannot satisfy this"
      (is (= {:rf.error/id :rf.error/hicasso-bad-head
              :where       're-frame.hicasso.impl.codec/vec->element
              :recovery    :no-recovery
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
             (coordinate-of data))))))

;; The refusals `mint-host!` raises abort a `def` at namespace load, so a
;; bad `defhost` cannot be written at the top of this file — it would not
;; load to be tested. The declaration extent is therefore asserted in its
;; two halves: the macro really registers a coordinate, and a refusal
;; raised inside a declaration extent really picks it up.

(deftest defhost-registers-its-macro-captured-coordinate
  (testing "the coordinate is keyed by the same `<ns>/<sym>` string the
            macro stamps as `displayName`, and it is the declaration's own"
    (let [coord (rf.hicasso.impl.error/source-of "re-frame.hicasso.error-shape-cljs-test/date-picker")]
      (is (some? coord) "defhost registered a coordinate")
      (is (= 're-frame.hicasso.error-shape-cljs-test (:ns coord)))
      (is (string? (:file coord)))
      (is (pos-int? (:line coord)))
      (is (pos-int? (:column coord))))))

(deftest a-refusal-raised-inside-a-declaration-extent-carries-that-declaration
  (testing "this is the extent `defhost` opens around `mint-host!`, driven
            directly because a declaration that refuses cannot be written
            at the top of a file that must load"
    (rf.hicasso.impl.error/declaring! "app.pickers/calendar" {:ns 'app.pickers :file "app/pickers.cljs"
                                              :line 12 :column 3})
    (let [data (refusal #(rf.hicasso.impl.error/fail! :rf.error/hicasso-bad-host-declaration
                                      're-frame.hicasso.impl.codec/mint-host!
                                      "defhost was given an option it does not know."
                                      {:option :nope}))]
      (rf.hicasso.impl.error/declared!)
      (is (= {:rf.error/id :rf.error/hicasso-bad-host-declaration
              :where       're-frame.hicasso.impl.codec/mint-host!
              :recovery    :no-recovery
              :view        "app.pickers/calendar"
              :source      {:ns 'app.pickers :file "app/pickers.cljs" :line 12 :column 3}
              :option      :nope}
             (dissoc data :reason)))))

  (testing "and the extent closes, so the next refusal is not attributed to it"
    (is (nil? (:view (refusal #(rf.hicasso.impl.error/fail! :rf.error/hicasso-state-bad-argument
                                            're-frame.hicasso.impl.state/reg-state
                                            "reg-state options must be a map."
                                            {})))))))

(deftest a-declaration-whose-mint-refuses-closes-its-extent-anyway
  ;; Written through the real macro, and the only refusing declaration in
  ;; the package that CAN be: a `defhost` refusal aborts a `def` at
  ;; namespace load, so one at the top of this file would stop the file
  ;; loading. Inside a `deftest` the same expansion runs with a catch
  ;; around it — which is not a contrivance but the case itself. An HMR
  ;; runtime catches exactly this and leaves the mounted page rendering.
  (let [data (refusal (fn [] (rf.hicasso/defhost refusing-declaration
                               (fn Refusing [_props] nil)
                               {:not-an-option true})))]

    (testing "the refusal still names the declaration that is wrong — the
              extent closes AFTER `fail!` has built the ex-data, so nothing
              the `finally` does can reach a refusal already on its way out"
      (is (= {:rf.error/id :rf.error/hicasso-bad-host-declaration
              :where       're-frame.hicasso.impl.codec/mint-host!
              :view        "re-frame.hicasso.error-shape-cljs-test/refusing-declaration"
              :option      :not-an-option}
             (select-keys data [:rf.error/id :where :view :option])))
      (is (= 're-frame.hicasso.error-shape-cljs-test (:ns (:source data)))))

    (testing "and the extent is CLOSED, so a later refusal — from an event
              handler, a timer, any code with no boundary on the stack —
              does not inherit the dead declaration's `:view` and `:source`"
      (let [later (refusal #(rf.hicasso/sub [:error-shape/anything]))]
        (is (= :rf.error/hicasso-sub-outside-render (:rf.error/id later))
            "the later refusal is the ordinary outside-extent one")
        (is (not (contains? later :view)))
        (is (not (contains? later :source)))))))

(deftest a-refusal-outside-every-extent-omits-the-ambient-pair
  (let [data (refusal #(rf.hicasso/sub [:error-shape/anything]))]

    (testing "the identity, whole"
      (is (= {:rf.error/id :rf.error/hicasso-sub-outside-render
              :where       're-frame.hicasso.impl.collector/read-key!
              :recovery    :no-recovery
              :query-v     [:error-shape/anything]}
             (select-keys data [:rf.error/id :where :recovery :query-v]))))

    (testing "there is no view and no coordinate, and the refusal says that
              by ABSENCE — a nil `:view` would claim the read happened in a
              boundary the runtime could not name, which is a different and
              false statement"
      (is (not (contains? data :view)))
      (is (not (contains? data :source))))))
