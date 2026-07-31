(ns re-frame.bench.hicasso.front.intent-cljs-test
  "INTENT LOWERING, tested against the surface authoring.md declares
  (rf2-2rtt6.8).

  Every test drives the lowered closure with a stand-in event rather than
  a real DOM one. That is not a shortcut: the closures take exactly three
  things off an event — `preventDefault`, `.-target`, and the key/
  composition signals — and a stand-in makes each of those observable,
  which a real browser event does not. The browser's own behaviour on the
  same closures is the arms' witness (validation.md's 100-cell grid), not
  this file's."
  (:require [cljs.test :refer-macros [deftest is testing]]
            [re-frame.bench.hicasso.front.intent :as intent]))

;; ---------------------------------------------------------------------------
;; A recording dispatch, and stand-in events
;; ---------------------------------------------------------------------------

(defn- recorder [] (atom []))

(defn- dispatching [!seen] (fn [event] (swap! !seen conj event) nil))

(defn- ev
  "A stand-in DOM event. `:value`/`:checked` become the target's fields;
  `:key`, `:isComposing` and `:keyCode` sit on the event itself; the
  `:prevented` atom records `preventDefault`."
  [{:keys [value checked key composing? key-code prevented]}]
  #js {:target        #js {:value value :checked checked}
       :key           key
       :isComposing   composing?
       :keyCode       key-code
       :preventDefault (fn [] (when prevented (reset! prevented true)) nil)})

(defn- lowered
  "Lower one prop with `dispatch` bound as the boundary's frame, and
  return the closure — deliberately *outside* the binding, so every test
  below exercises a callback the browser invokes after the render's
  dynamic extent has unwound."
  [dispatch k v]
  (intent/with-frame dispatch (fn [] (intent/lower-prop k v))))

;; ---------------------------------------------------------------------------
;; Event vectors
;; ---------------------------------------------------------------------------

(deftest a-vector-at-an-event-position-becomes-a-dispatching-closure
  (let [!seen (recorder)
        h     (lowered (dispatching !seen) :on-click [:todo/toggle 7])]
    (is (fn? h))
    (is (= [] @!seen) "lowering dispatches nothing by itself")
    (h (ev {}))
    (is (= [[:todo/toggle 7]] @!seen))
    (testing "the same closure is reusable and dispatches the same intent"
      (h (ev {}))
      (is (= [[:todo/toggle 7] [:todo/toggle 7]] @!seen)))))

(deftest the-closure-closes-over-the-boundarys-frame
  (testing "two boundaries lower the same intent to two different frames"
    (let [!a (recorder)
          !b (recorder)
          ha (lowered (dispatching !a) :on-click [:ping])
          hb (lowered (dispatching !b) :on-click [:ping])]
      (ha (ev {}))
      (hb (ev {}))
      (is (= [[:ping]] @!a))
      (is (= [[:ping]] @!b))))
  (testing "and it still works with no ambient frame bound — the browser
            calls it long after the render unwound"
    (let [!seen (recorder)
          h     (lowered (dispatching !seen) :on-click [:ping])]
      (is (nil? intent/*dispatch*))
      (h (ev {}))
      (is (= [[:ping]] @!seen)))))

(deftest an-intent-lowered-outside-a-boundary-is-a-loud-error
  (is (nil? intent/*dispatch*))
  (is (thrown-with-msg? js/Error #"no ambient frame"
                        (intent/lower-prop :on-click [:ping])))
  (testing "the error carries its id"
    (try
      (intent/lower-prop :on-click [:ping])
      (is false "should have thrown")
      (catch :default e
        (is (= :rf.error/hicasso-intent-outside-boundary (:rf.error/id (ex-data e))))))))

(deftest non-event-positions-and-non-intent-values-pass-through-untouched
  (let [d (dispatching (recorder))]
    (testing "a vector at a non-event position is data, not an intent"
      (is (= [:a :b] (lowered d :data-path [:a :b]))))
    (testing "an ordinary function at an event position stays legal"
      (let [f (fn [_e] :called)]
        (is (identical? f (lowered d :on-click f)))))
    (testing "a string, a number and nil are left alone"
      (is (= "x" (lowered d :on-click "x")))
      (is (= 3 (lowered d :on-click 3)))
      (is (nil? (lowered d :on-click nil))))))

(deftest both-event-prop-spellings-are-recognised-and-nothing-else-is
  (is (true? (intent/event-prop? :on-click)))
  (is (true? (intent/event-prop? :on-key-down)))
  (is (true? (intent/event-prop? :onClick)))
  (is (false? (intent/event-prop? :online)) "a kebab-less word starting with `on`")
  (is (false? (intent/event-prop? :class)))
  (is (false? (intent/event-prop? :data-on-click)))
  (is (false? (intent/event-prop? nil))))

;; ---------------------------------------------------------------------------
;; The value placeholder and the one pure materializer
;; ---------------------------------------------------------------------------

(deftest the-value-marker-is-replaced-with-the-targets-value
  (let [!seen (recorder)
        h     (lowered (dispatching !seen) :on-input
                       [:todo.ui/edit 7 :re-frame.hicasso/value])]
    (h (ev {:value "milk"}))
    (h (ev {:value "bread"}))
    (is (= [[:todo.ui/edit 7 "milk"] [:todo.ui/edit 7 "bread"]] @!seen)
        "one intent, materialized fresh per event")))

(deftest the-checked-marker-is-replaced-with-the-targets-checked-state
  (let [!seen (recorder)
        h     (lowered (dispatching !seen) :on-change
                       [:todo/set-done 7 :re-frame.hicasso/checked])]
    (h (ev {:checked true}))
    (h (ev {:checked false}))
    (is (= [[:todo/set-done 7 true] [:todo/set-done 7 false]] @!seen))))

(deftest the-materializer-is-pure-and-positional
  (testing "several markers in one intent all materialize"
    (let [e (ev {:value "v" :checked true})]
      (is (= [:e "v" true :tail]
             (intent/materialize [:e :re-frame.hicasso/value :re-frame.hicasso/checked :tail] e)))))
  (testing "an intent with no marker comes back with the same elements"
    (let [e (ev {:value "v"})]
      (is (= [:e 1 "two"] (intent/materialize [:e 1 "two"] e)))))
  (testing "markers are substituted at the top level only — the documented shape"
    (let [e (ev {:value "v"})]
      (is (= [:e {:v :re-frame.hicasso/value}]
             (intent/materialize [:e {:v :re-frame.hicasso/value}] e)))))
  (testing "the static/dynamic split is decidable before any event exists"
    (is (true? (intent/markers? [:e :re-frame.hicasso/value])))
    (is (true? (intent/markers? [:e :re-frame.hicasso/checked])))
    (is (false? (intent/markers? [:e 1 "two"])))))

;; ---------------------------------------------------------------------------
;; Submit auto-prevent, and the one metadata mechanism that overrides it
;; ---------------------------------------------------------------------------

(deftest on-submit-auto-prevents
  (let [!seen (recorder)
        !prevented (atom false)
        h     (lowered (dispatching !seen) :on-submit [:todo/create])]
    (h (ev {:prevented !prevented}))
    (is (true? @!prevented) "the browser's navigation is prevented")
    (is (= [[:todo/create]] @!seen) "and the intent still dispatches")))

(deftest no-other-event-position-prevents-by-default
  (let [!prevented (atom false)]
    ((lowered (dispatching (recorder)) :on-click [:ping]) (ev {:prevented !prevented}))
    (is (false? @!prevented))))

(deftest prevent-metadata-overrides-the-positions-default-in-both-directions
  (testing "opting out of the submit default"
    (let [!seen (recorder)
          !prevented (atom false)
          h (lowered (dispatching !seen) :on-submit
                     (with-meta [:todo/create] {:re-frame.hicasso/prevent? false}))]
      (h (ev {:prevented !prevented}))
      (is (false? @!prevented))
      (is (= [[:todo/create]] @!seen))))
  (testing "opting in anywhere else"
    (let [!prevented (atom false)
          h (lowered (dispatching (recorder)) :on-click
                     (with-meta [:ping] {:re-frame.hicasso/prevent? true}))]
      (h (ev {:prevented !prevented}))
      (is (true? @!prevented))))
  (testing "auto-prevent composes with the value marker"
    (let [!seen (recorder)
          !prevented (atom false)
          h (lowered (dispatching !seen) :on-submit
                     [:todo/create :re-frame.hicasso/value])]
      (h (ev {:value "milk" :prevented !prevented}))
      (is (true? @!prevented))
      (is (= [[:todo/create "milk"]] @!seen)))))

;; ---------------------------------------------------------------------------
;; The composition-gated key-map
;; ---------------------------------------------------------------------------

(deftest a-map-at-an-event-position-becomes-a-key-map
  (let [!seen (recorder)
        h     (lowered (dispatching !seen) :on-key-down
                       {"Enter"  [:todo/commit 7]
                        "Escape" [:todo.ui/cancel 7]})]
    (h (ev {:key "Enter"}))
    (is (= [[:todo/commit 7]] @!seen))
    (h (ev {:key "Escape"}))
    (is (= [[:todo/commit 7] [:todo.ui/cancel 7]] @!seen))
    (testing "an unlisted key commits nothing"
      (h (ev {:key "a"}))
      (h (ev {:key "Tab"}))
      (is (= [[:todo/commit 7] [:todo.ui/cancel 7]] @!seen)))))

(deftest a-composing-enter-commits-nothing
  (testing "the isComposing signal"
    (let [!seen (recorder)
          h     (lowered (dispatching !seen) :on-key-down {"Enter" [:todo/commit 7]})]
      (h (ev {:key "Enter" :composing? true}))
      (is (= [] @!seen))
      (h (ev {:key "Enter" :composing? false}))
      (is (= [[:todo/commit 7]] @!seen) "and the same closure commits once composition ends")))
  (testing "the legacy keyCode-229 signal, which is all some IMEs send"
    (let [!seen (recorder)
          h     (lowered (dispatching !seen) :on-key-down {"Enter" [:todo/commit 7]})]
      (h (ev {:key "Enter" :key-code 229}))
      (is (= [] @!seen))
      (h (ev {:key "Enter" :key-code 13}))
      (is (= [[:todo/commit 7]] @!seen))))
  (testing "the gate is centralised over the whole map, not written per key"
    (let [!seen (recorder)
          h     (lowered (dispatching !seen) :on-key-down
                         {"Enter" [:commit] "Escape" [:cancel]})]
      (h (ev {:key "Escape" :composing? true}))
      (is (= [] @!seen)))))

(deftest key-map-branches-carry-the-full-intent-surface
  (testing "a marker inside a key-map branch materializes"
    (let [!seen (recorder)
          h     (lowered (dispatching !seen) :on-key-down
                         {"Enter" [:todo/commit :re-frame.hicasso/value]})]
      (h (ev {:key "Enter" :value "milk"}))
      (is (= [[:todo/commit "milk"]] @!seen))))
  (testing "prevent metadata works inside a branch, and is off by default"
    (let [!a (atom false)
          !b (atom false)
          h  (lowered (dispatching (recorder)) :on-key-down
                      {"Enter"  (with-meta [:commit] {:re-frame.hicasso/prevent? true})
                       "Escape" [:cancel]})]
      (h (ev {:key "Enter" :prevented !a}))
      (h (ev {:key "Escape" :prevented !b}))
      (is (true? @!a))
      (is (false? @!b))))
  (testing "an ordinary function is a legal branch"
    (let [!called (atom false)
          h (lowered (dispatching (recorder)) :on-key-down
                     {"Enter" (fn [_e] (reset! !called true))})]
      (h (ev {:key "Enter"}))
      (is (true? @!called)))))

(deftest the-composition-predicate-reads-both-signals
  (is (true? (intent/composing? (ev {:composing? true}))))
  (is (true? (intent/composing? (ev {:key-code 229}))))
  (is (false? (intent/composing? (ev {:key-code 13}))))
  (is (false? (intent/composing? (ev {})))))

;; ---------------------------------------------------------------------------
;; The whole-map door
;; ---------------------------------------------------------------------------

(deftest lower-props-walks-a-map-once-and-does-not-allocate-when-there-is-nothing-to-do
  (let [!seen (recorder)]
    (testing "a props map with nothing to lower comes back by identity"
      (let [props {:class "row" :data-index 3 :on-click (fn [_])}]
        (is (identical? props (intent/with-frame (dispatching !seen)
                                                 (fn [] (intent/lower-props props)))))))
    (testing "a props map with an intent comes back lowered, everything else intact"
      (let [props    {:class "row" :data-index 3 :on-click [:touch 3]}
            lowered' (intent/with-frame (dispatching !seen) (fn [] (intent/lower-props props)))]
        (is (= "row" (:class lowered')))
        (is (= 3 (:data-index lowered')))
        (is (fn? (:on-click lowered')))
        ((:on-click lowered') (ev {}))
        (is (= [[:touch 3]] @!seen))))))
