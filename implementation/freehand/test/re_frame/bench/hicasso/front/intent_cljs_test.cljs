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
;; Submit auto-prevent, and the one reserved head that opts in elsewhere
;; (HD-026)
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

(deftest the-prevent-head-opts-in-and-dispatches-the-inner-intent
  (testing "the anchor-acting-as-a-button, which is the shape this exists for"
    (let [!seen      (recorder)
          !prevented (atom false)
          h (lowered (dispatching !seen) :on-click
                     [:re-frame.hicasso/prevent [:conduit/show-your-feed]])]
      (h (ev {:prevented !prevented}))
      (is (true? @!prevented) "the browser does not follow the href")
      (is (= [[:conduit/show-your-feed]] @!seen)
          "and what reaches dispatch is the INNER vector — ordinary data, with no
           decorator on it")))
  (testing "the decorator is unwrapped BEFORE marker analysis, so the markers
            compose inside a prevented intent"
    (let [!seen      (recorder)
          !prevented (atom false)
          h (lowered (dispatching !seen) :on-input
                     [:re-frame.hicasso/prevent
                      [:filter/set :re-frame.hicasso/value]])]
      (h (ev {:value "milk" :prevented !prevented}))
      (is (true? @!prevented))
      (is (= [[:filter/set "milk"]] @!seen))))
  (testing "the submit default still holds under the head, and is not doubled"
    (let [!seen      (recorder)
          !prevented (atom false)
          h (lowered (dispatching !seen) :on-submit
                     [:re-frame.hicasso/prevent [:todo/create]])]
      (h (ev {:prevented !prevented}))
      (is (true? @!prevented))
      (is (= [[:todo/create]] @!seen))))
  (testing "auto-prevent composes with the value marker, decorator or not"
    (let [!seen (recorder)
          !prevented (atom false)
          h (lowered (dispatching !seen) :on-submit
                     [:todo/create :re-frame.hicasso/value])]
      (h (ev {:value "milk" :prevented !prevented}))
      (is (true? @!prevented))
      (is (= [[:todo/create "milk"]] @!seen)))))

(deftest a-prevented-intent-is-assertable-by-equality
  ;; THE REASON THE SPELLING CHANGED. HD-021's headless door returns the tree
  ;; as data and sells itself on "intent vectors assertable by equality" —
  ;; and metadata does not participate in `=`, so the one axis the retired
  ;; spelling carried was the one axis a structural test could not see.
  (testing "the retired spelling, stated as the defect it was"
    (is (= [:conduit/show-your-feed]
           (with-meta [:conduit/show-your-feed] {:re-frame.hicasso/prevent? true}))
        "`=` could not tell a prevented intent from a plain one")
    (is (= (hash [:conduit/show-your-feed])
           (hash (with-meta [:conduit/show-your-feed] {:re-frame.hicasso/prevent? true})))
        "and neither could a hash-keyed lookup")
    (is (= "[:conduit/show-your-feed]"
           (pr-str (with-meta [:conduit/show-your-feed] {:re-frame.hicasso/prevent? true})))
        "nor a log line, nor a snapshot — metadata is omitted from printing"))
  (testing "the head, which every one of those instruments can see"
    (is (not= [:conduit/show-your-feed]
              [:re-frame.hicasso/prevent [:conduit/show-your-feed]]))
    (is (not= (hash [:conduit/show-your-feed])
              (hash [:re-frame.hicasso/prevent [:conduit/show-your-feed]])))
    (is (= "[:re-frame.hicasso/prevent [:conduit/show-your-feed]]"
           (pr-str [:re-frame.hicasso/prevent [:conduit/show-your-feed]]))))
  (testing "which is the property a structural test actually takes: two props
            maps that differ ONLY in whether the click prevents"
    (let [plain     {:href "#" :on-click [:conduit/show-your-feed]}
          prevented {:href "#" :on-click [:re-frame.hicasso/prevent
                                          [:conduit/show-your-feed]]}]
      (is (not= plain prevented))
      (is (= plain (update prevented :on-click #(nth % 1)))
          "and the difference is exactly the decorator, nothing else")))
  (testing "the predicate the classification uses is public, so a test can ask
            the same question the lowering asks"
    (is (true? (intent/prevent-head? [:re-frame.hicasso/prevent [:x]])))
    (is (false? (intent/prevent-head? [:conduit/show-your-feed])))
    (is (false? (intent/prevent-head?
                  (with-meta [:conduit/show-your-feed]
                             {:re-frame.hicasso/prevent? true})))
        "the retired metadata is no longer a spelling of anything")))

(deftest the-retired-metadata-spelling-does-nothing
  ;; Not a formality: an author porting old code must find out at the click,
  ;; not never. The metadata is inert, so the anchor navigates — which is
  ;; loud in the browser and is what the DOM witness asserts.
  (let [!seen      (recorder)
        !prevented (atom false)
        h (lowered (dispatching !seen) :on-click
                   (with-meta [:ping] {:re-frame.hicasso/prevent? true}))]
    (h (ev {:prevented !prevented}))
    (is (false? @!prevented))
    (is (= [[:ping]] @!seen))))

(deftest a-malformed-prevent-head-is-refused-loudly
  (let [d (dispatching (recorder))]
    (testing "wrong arity — a bare head"
      (is (thrown-with-msg? js/Error #"wraps EXACTLY ONE intent vector"
                            (lowered d :on-click [:re-frame.hicasso/prevent]))))
    (testing "wrong arity — two payloads, which is how an author would try to
              write a second decorator"
      (is (thrown-with-msg? js/Error #"carries 2 forms after the head"
                            (lowered d :on-click [:re-frame.hicasso/prevent
                                                  [:a] [:b]]))))
    (testing "a non-vector payload"
      (is (thrown-with-msg? js/Error #"not an intent vector"
                            (lowered d :on-click [:re-frame.hicasso/prevent
                                                  :conduit/show-your-feed]))))
    (testing "the empty vector, which names no event"
      (is (thrown-with-msg? js/Error #"names no event"
                            (lowered d :on-click [:re-frame.hicasso/prevent []]))))
    (testing "the decorator does not nest — the grammar is closed, so there is
              no open decorator language to grow"
      (is (thrown-with-msg? js/Error #"does not nest"
                            (lowered d :on-click
                                     [:re-frame.hicasso/prevent
                                      [:re-frame.hicasso/prevent [:ping]]]))))
    (testing "the diagnostic carries its id and NAMES THE POSITION"
      (try
        (lowered d :on-key-up [:re-frame.hicasso/prevent])
        (is false "should have thrown")
        (catch :default e
          (let [data (ex-data e)]
            (is (= :rf.error/hicasso-malformed-prevent (:rf.error/id data)))
            (is (= :on-key-up (:position data)))
            (is (= [:re-frame.hicasso/prevent] (:form data)))
            (is (re-find #":on-key-up" (ex-message e)))
            (is (re-find #"Write \[:re-frame.hicasso/prevent" (ex-message e))
                "and it shows the form to write")))))
    (testing "the refusal is taken at LOWERING time — before any event exists,
              so a malformed decorator cannot reach a user's click"
      (is (thrown? js/Error
                   (intent/with-frame d
                     (fn [] (intent/lower-props
                              {:on-click [:re-frame.hicasso/prevent :nope]}))))))
    (testing "and only at an event position: an intent travelling as data is
              not lowered, so it is not classified either"
      (is (= [:re-frame.hicasso/prevent :nope]
             (lowered d :data-intent [:re-frame.hicasso/prevent :nope]))))))

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
  (testing "the prevent head is accepted inside a branch — through the same
            classification, because a branch is lowered by the same code — and
            prevention is off by default in the branch that does not carry it"
    (let [!seen (recorder)
          !a (atom false)
          !b (atom false)
          h  (lowered (dispatching !seen) :on-key-down
                      {"Enter"  [:re-frame.hicasso/prevent [:commit]]
                       "Escape" [:cancel]})]
      (h (ev {:key "Enter" :prevented !a}))
      (h (ev {:key "Escape" :prevented !b}))
      (is (true? @!a))
      (is (false? @!b))
      (is (= [[:commit] [:cancel]] @!seen)
          "and both branches dispatch ordinary intents")))
  (testing "a malformed decorator in a branch is refused at lowering, naming
            the position"
    (is (thrown-with-msg? js/Error #"wraps EXACTLY ONE intent vector"
                          (lowered (dispatching (recorder)) :on-key-down
                                   {"Enter" [:re-frame.hicasso/prevent]}))))
  (testing "a marker composes inside a prevented branch"
    (let [!seen (recorder)
          !p    (atom false)
          h     (lowered (dispatching !seen) :on-key-down
                         {"Enter" [:re-frame.hicasso/prevent
                                   [:todo/commit :re-frame.hicasso/value]]})]
      (h (ev {:key "Enter" :value "milk" :prevented !p}))
      (is (true? @!p))
      (is (= [[:todo/commit "milk"]] @!seen))))
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

;; ---------------------------------------------------------------------------
;; ONE callback form, and the POSITION selects the contract
;; (HD-024, rf2-2rtt6.35)
;; ---------------------------------------------------------------------------
;;
;; One test per position, plus the row that deletes the predecessor's fifth
;; rule, plus the diagnostic that must name the POSITION rather than the form.

(deftest the-one-form-is-an-ordinary-function
  (testing "the deletion, stated as an assertion. The predecessor's roster
            carriers are marker OBJECTS, so the same value handed to a
            position the library does not walk is not callable and the author
            gets the engine's own TypeError naming nothing they wrote. There
            is nothing here that can fail to be callable."
    (let [f  (fn [_] :ran)
          cb (intent/callback f)]
      (is (identical? f cb) "`callback` marks and returns the SAME function")
      (is (fn? cb))
      (is (true? (intent/callback? cb)))
      (is (false? (intent/callback? (fn [_]))) "an ordinary fn is not the form")
      (is (false? (intent/callback? [:an :intent])))
      (is (false? (intent/callback? "on-click"))))))

(deftest at-an-event-position-a-returned-vector-is-dispatched
  (testing "the contract the predecessor spells `v/event`"
    (let [!seen (recorder)
          h     (lowered (dispatching !seen) :on-change
                         (intent/callback (fn [e] [:files/picked (.. e -target -value)])))]
      (h (ev {:value "a.png"}))
      (is (= [[:files/picked "a.png"]] @!seen))))
  (testing "and a return that is not a vector is ignored — which is the
            contract the predecessor needs a SECOND form (`v/handler`) for"
    (let [!seen (recorder)
          !ran  (atom 0)
          h     (lowered (dispatching !seen) :on-click
                         (intent/callback (fn [_] (swap! !ran inc) :not-an-intent)))]
      (h (ev {}))
      (is (= 1 @!ran) "the body ran")
      (is (= [] @!seen) "and nothing was dispatched")))
  (testing "nil is likewise ignored, so a conditional dispatch is written as
            an ordinary conditional returning nil"
    (let [!seen (recorder)
          h     (lowered (dispatching !seen) :on-click
                         (intent/callback (fn [_] nil)))]
      (h (ev {}))
      (is (= [] @!seen)))))

(deftest the-policy-defaults-belong-to-the-data-spelling-not-to-the-callback
  (testing "`:on-submit` auto-prevents an INTENT VECTOR because a vector never
            sees the event, so the runtime must decide for it. A callback IS
            handed the event, so the event is the callback's — the runtime
            does not reach in after the body has run to second-guess it.
            One rule: whoever holds the event owns it."
    (let [!seen (recorder)]
      (testing "the vector at :on-submit prevents, as it always has"
        (let [prevented (atom false)
              h (lowered (dispatching !seen) :on-submit [:signup/submit])]
          (h (ev {:prevented prevented}))
          (is (true? @prevented))
          (is (= [[:signup/submit]] @!seen))))
      (testing "the callback at the same position does not, and its returned
                intent still dispatches"
        (reset! !seen [])
        (let [prevented (atom false)
              h (lowered (dispatching !seen) :on-submit
                         (intent/callback (fn [_] [:signup/submit])))]
          (h (ev {:prevented prevented}))
          (is (false? @prevented) "the runtime left the event alone")
          (is (= [[:signup/submit]] @!seen)))))))

(deftest at-a-render-position-the-return-is-output-and-dispatching-is-a-loud-error
  (testing "the contract the predecessor spells `v/render-fn`. A slot or a
            foreign render prop is invoked DURING a render, so its return is
            markup — which is itself a vector, and is exactly why the shape
            of the value cannot select the contract and the position must."
    (let [!seen (recorder)
          h     (lowered (dispatching !seen) :row-renderer
                         (intent/callback (fn [row] [:li (:title row)])))]
      (is (= [:li "milk"] (h {:title "milk"}))
          "the hiccup came back to the caller and was NOT dispatched")
      (is (= [] @!seen))))
  (testing "and a dispatch from inside one is refused, naming the POSITION"
    (let [!seen (recorder)
          h     (lowered (dispatching !seen) :row-renderer
                         (intent/callback
                           (fn [_] ((intent/lower-prop :on-click [:oops]) (ev {})) [:li])))]
      (try
        (h {})
        (is false "should have thrown")
        (catch :default e
          (let [d (ex-data e)]
            (is (= :rf.error/hicasso-dispatch-in-render-position (:rf.error/id d)))
            (is (= :row-renderer (:position d))
                "the diagnostic names the POSITION — under one form, the form
                 is never the answer to the question of what went wrong")
            (is (re-find #":row-renderer" (ex-message e))))))
      (is (= [] @!seen) "and nothing reached the frame"))))

(deftest a-declaration-can-name-the-contract-instead-of-the-position
  (testing "the position table's second row. A `defhost` declaration carries
            `:event` or `:handler` per EXACT prop name and never infers it
            from an `on*` spelling, so the contract travels with the
            declaration rather than with the value."
    (let [!seen (recorder)
          cb    (intent/callback (fn [x] [:host/changed x]))]
      (testing ":event dispatches the return even though :onValueChange is
                not a position the attribute grammar would call an event"
        (let [h (intent/with-frame (dispatching !seen)
                                   (fn [] (intent/lower-declared-prop :onValueChange cb :event)))]
          (h 7)
          (is (= [[:host/changed 7]] @!seen))))
      (testing "and it forwards EVERY argument the invoker passes. A native
                DOM event position calls with one argument, but this is also
                the wrapper a declaration gives a foreign component's own
                live invoker — `(on-change value event)`, `(on-select item
                index)` — and the form's parameter vector is arbitrary by
                construction. A wrapper that accepted exactly `[e]` would
                silently drop everything after the first, or raise an arity
                error naming nothing the author wrote."
        (reset! !seen [])
        (let [pair (intent/callback (fn [value e] [:host/changed value (.-key e)]))
              h    (intent/with-frame (dispatching !seen)
                                      (fn [] (intent/lower-declared-prop :onValueChange pair :event)))]
          (h "typed" (ev {:key "Enter"}))
          (is (= [[:host/changed "typed" "Enter"]] @!seen))))
      (testing ":handler ignores the return, and the function passes through
                by identity so a library memoising on it is not defeated"
        (reset! !seen [])
        (let [h (intent/with-frame (dispatching !seen)
                                   (fn [] (intent/lower-declared-prop :onValueChange cb :handler)))]
          (is (identical? cb h))
          (is (= [:host/changed 7] (h 7)) "the caller sees the return")
          (is (= [] @!seen))))
      (testing "an unknown contract is a loud error rather than a guess"
        (try
          (intent/lower-declared-prop :onValueChange cb :whatever)
          (is false "should have thrown")
          (catch :default e
            (is (= :rf.error/hicasso-unknown-callback-contract
                   (:rf.error/id (ex-data e))))))))))

(deftest outside-every-walked-position-the-form-is-just-a-function
  (testing "the row that deletes the predecessor's FIFTH rule. There, the
            roster is site-owned and a carrier handed to a raw #js prop is a
            marker object, so a native call on it raises the host's own
            TypeError naming nothing the author wrote. Here the value was
            never anything but a function, so the position not being walked
            costs the contract and nothing else."
    (let [!ran     (atom 0)
          cb       (intent/callback (fn [x] (swap! !ran inc) [:would-have-dispatched x]))
          js-props #js {:onPing cb}]
      (is (fn? (.-onPing js-props)))
      (is (= [:would-have-dispatched 3] ((.-onPing js-props) 3))
          "a JavaScript library calling it natively gets a real call and a
           real return — the return is simply not an intent here, because
           this is not a position anything could have lowered")
      (is (= 1 @!ran)))))

(deftest an-intent-returned-with-no-frame-in-scope-names-the-position
  (testing "the form is legal with no frame — a callback that never returns
            an intent has nothing to dispatch. Returning one there is the
            loud error, and it names the position rather than the form."
    (let [h (intent/lower-prop :on-click (intent/callback (fn [_] [:too/late])))]
      (try
        (h (ev {}))
        (is false "should have thrown")
        (catch :default e
          (is (= :rf.error/hicasso-intent-outside-boundary (:rf.error/id (ex-data e))))
          (is (= :on-click (:position (ex-data e)))))))
    (testing "while the same callback returning nothing is fine"
      (let [!ran (atom 0)
            h    (intent/lower-prop :on-click (intent/callback (fn [_] (swap! !ran inc) nil)))]
        (h (ev {}))
        (is (= 1 @!ran))))))

(deftest ref-keeps-reacts-own-contract-and-is-not-lowered
  (testing ":ref is the one position whose contract is neither Hicasso's to
            select nor the same in both phases — React invokes it in the
            COMMIT phase with the node, and its return is the detach
            cleanup. Wrapping it would forbid a legitimate dispatch there and
            would change the identity React re-attaches on."
    (is (= :ref (intent/position-contract :ref)))
    (is (= :event (intent/position-contract :on-click)))
    (is (= :render (intent/position-contract :row-renderer)))
    (let [cb (intent/callback (fn [_node] :cleanup))]
      (is (identical? cb (intent/lower-prop :ref cb)))
      (is (identical? cb (intent/lower-prop "ref" cb))))))

(deftest an-ordinary-function-is-untouched-everywhere
  (testing "`raw-fn`'s identity passthrough is not v0 because it is already
            the default: an ordinary function is claimed by no position, and
            the codec hands functions to React by identity so `React.memo`
            and every downstream bail-out that compares handler identity keep
            working. One fewer form for nothing given up."
    (let [f (fn [_] :whatever)]
      (is (identical? f (intent/lower-prop :on-click f)))
      (is (identical? f (intent/lower-prop :row-renderer f)))
      (is (identical? f (intent/lower-prop :ref f))))))
