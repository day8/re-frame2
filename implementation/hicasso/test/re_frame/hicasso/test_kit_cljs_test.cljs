(ns re-frame.hicasso.test-kit-cljs-test
  "THE TEST KIT'S OWN WITNESSES — L1 and L2, positive and sabotaged
  (rf2-hic-020).

  `re-frame.hicasso.test` is an instrument, and an instrument is only
  worth the controls that can make it go red. Every claim below is
  written as a PAIR: the thing the kit answers, and a control that fails
  if the kit answered it by accident.

  ## The three shapes of control in this file

  1. **Discrimination.** A predicate is asserted true on the thing and
     FALSE on its nearest neighbour — `boundary?` on a `defview` var and
     on the plain function its body is, `callback?` on `h/hfn` and on an
     identically-written `fn`. A predicate only ever asserted true is a
     predicate that could be `(constantly true)`.
  2. **Both verbs, from one discriminator.** [[outcome]] reports
     `{:returned v}` or `{:refused <ex-data>}`, and the refusal rows
     assert the WHOLE ex-data map rather than `(is (thrown? …))`. A bare
     `thrown?` is green for a throw from any layer carrying any id, which
     is exactly how a sibling bead's sabotage once passed while throwing
     from the wrong place. [[the-refusal-witness-answers-both-ways]]
     drives the discriminator in both directions so the refusal rows are
     not a helper that only knows one verb.
  3. **The legal twin.** Every refusal row has a sibling that renders the
     SAME body legally — the fixture supplied, the head named — so the
     refusal is about the thing under test and not about a body that was
     broken anyway.

  ## What is asserted elsewhere

  [[re-frame.hicasso.test/canonical-dom]] takes a DOM node, so its
  witnesses are in `test-kit-dom-cljs-test` on the browser lane. This is
  the node lane: nothing here mounts, and nothing here needs a document."
  (:require [cljs.reader :as reader]
            [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [clojure.string :as str]
            [re-frame.adapter.uix :as uix-adapter]
            [re-frame.core :as rf]
            [re-frame.hicasso :as h]
            [re-frame.hicasso.impl.codec :as codec]
            [re-frame.hicasso.impl.collector :as collector]
            [re-frame.hicasso.impl.inventory :as inventory]
            [re-frame.hicasso.test :as ht]
            [re-frame.test-support :as test-support]
            ["react" :as react]))

(def ^:private frame-id ::test-kit)

(rf/reg-sub :tk/todo   (fn [db [_ id]] (get-in db [:todos id])))
(rf/reg-sub :tk/filter (fn [db _] (:filter db)))
(rf/reg-sub :tk/count  (fn [db _] (count (:todos db))))

(rf/reg-event :tk/seed   (fn [_ [_ db]] {:db db}))
(rf/reg-event :tk/toggle (fn [{:keys [db]} [_ id]]
                           {:db (update-in db [:todos id :done] not)}))

;; The node lane, plain-atom's reactivity gap and the `act` environment are
;; all as `smoke-cljs-test` and `read-extent-cljs-test` establish them: the
;; UIx adapter because plain-atom never notifies, no ambient frame because
;; this suite seats its own, and the collector emptied between rows.
(use-fixtures :each
  (test-support/make-reset-runtime-fixture
    {:adapter       uix-adapter/adapter
     :ambient-frame nil
     :init-fn       (fn []
                      (set! (.-IS_REACT_ACT_ENVIRONMENT js/globalThis) false)
                      (collector/reset-runtime!))}))

;; ---------------------------------------------------------------------------
;; The discriminator
;; ---------------------------------------------------------------------------

(defn- outcome
  "Run `thunk` and report WHICH of the two things happened,
  distinguishably: `{:returned v}` when it was allowed through,
  `{:refused <ex-data> :message …}` when it was refused.

  A map with two possible keys rather than a predicate, because the two
  failure modes a refusal witness actually has — the thunk never ran, and
  something other than the refusal threw — both look like success to a
  bare `thrown?`."
  [thunk]
  (try {:returned (thunk)}
       (catch :default e {:refused (ex-data e) :message (ex-message e)})))

(defn- refusal
  "The four keys a refusal's identity is asserted on: the stable id, the
  raising site, the actionable recovery, and whatever the row names as
  its subject. `:reason` is prose and deliberately not frozen."
  [outcome' extra-keys]
  (some-> (:refused outcome')
          (select-keys (into [:rf.error/id :where :recovery] extra-keys))))

(deftest the-refusal-witness-answers-both-ways
  (testing "the discriminator reports :returned for a render that is legal,
            so the :refused assertions below are not a helper that only
            knows one verb"
    (is (= {:returned {:rf.ui/tree-version 1 :tag :p}}
           (outcome #(ht/render [(fn [_] [:p])  {}])))))

  (testing "and :refused, with the id, for one that is not"
    (is (= {:rf.error/id :rf.error/hicasso-test-not-a-body
            :where       're-frame.hicasso.test
            :recovery    :pass-the-body-fn}
           (refusal (outcome #(ht/render [:not-a-body-fn 1])) [])))))

;; ---------------------------------------------------------------------------
;; L1 — the boundary ABI
;; ---------------------------------------------------------------------------

(defn- badge-component [^js props]
  (react/createElement "b" #js {"className" "badge"} (.-label props)))

(h/defhost badge badge-component {:ssr :render})

(defn- greeting-body [{:keys [who]}] [:p (str "hi " who)])
(h/defview greeting [props] (greeting-body props))

;; The pair the minted-head render rows below run. The helper is NOT named
;; `farewell-body`, and that is deliberate: `h/defview` names its emitted
;; body fn `<sym>-body`, and a named `fn` binds its own name in its body —
;; so `(h/defview farewell [p] (farewell-body p))` expands to
;; `(fn farewell-body [p] (farewell-body p))`, which recurses forever. That
;; is a defect in the public door macro rather than in this suite, and it is
;; filed as its own finding; `greeting` above carries it and is never run.
(defn- farewell-text [{:keys [who]}] [:p (str "bye " who)])
(h/defview farewell [props] (farewell-text props))

(def ^:private unretained-head
  "A boundary head carrying NO retained body — which is exactly what an
  `:advanced` + `goog.DEBUG=false` mint produces, because
  `collector/mint-view!` writes the body property inside
  `(when ^boolean js/goog.DEBUG …)` and nothing else writes it ever.

  Marked through the codec's own door, so what is under test is the
  runtime's notion of a boundary head rather than this file's."
  (doto (codec/mark-boundary! (fn [_] [:p "never runs at L2"]))
    (unchecked-set "displayName"
                   "re-frame.hicasso.test-kit-cljs-test/unretained-head")))

(deftest the-abi-predicates-discriminate-rather-than-restate-fn?
  (testing "a `defview` var is a boundary and its own body function is not —
            which is the whole discrimination, since both are functions"
    (is (true?  (ht/boundary? greeting)))
    (is (false? (ht/boundary? greeting-body)))
    (is (false? (ht/boundary? badge))))

  (testing "a `defhost` var is a crossing and the component it named is not"
    (is (true?  (ht/host? badge)))
    (is (false? (ht/host? badge-component)))
    (is (false? (ht/host? greeting))))

  (testing "`h/hfn` is the one callback form and an identically-written
            plain `fn` is not"
    (is (true?  (ht/callback? (h/hfn [e] [:tk/picked (.-value e)]))))
    (is (false? (ht/callback? (fn [e] [:tk/picked (.-value e)])))))

  (testing "the minted name is the one React DevTools and Spec 009's
            render measure are keyed on"
    (is (= "re-frame.hicasso.test-kit-cljs-test/greeting" (ht/view-name greeting)))
    (is (= "re-frame.hicasso.test-kit-cljs-test/badge" (ht/view-name badge)))
    (is (nil? (ht/view-name {:not "a minted value"}))))

  (testing "the declared server policy is read back off the crossing as data"
    (is (= :render (ht/host-policy badge))))

  (testing "and asking a NON-host for a policy refuses rather than
            answering nil — a nil here would read as :client-only's
            neighbour"
    (is (= {:rf.error/id :rf.error/hicasso-test-not-a-host
            :where       're-frame.hicasso.test
            :recovery    :pass-the-defhost-var}
           (refusal (outcome #(ht/host-policy greeting)) [])))))

;; ---------------------------------------------------------------------------
;; L1 — the codec, projected
;; ---------------------------------------------------------------------------

(deftest element-props-answers-the-slots-the-codec-emits
  (testing "the `.class#id` sugar folds by the codec's own two rules —
            an explicit id WINS over `#id`, the shorthand class is
            PREPENDED to a declared one"
    (is (= {"id" "main" "className" "wide tall"}
           (ht/element-props [:div#ignored.wide {:id "main" :class "tall"}]))))

  (testing "and the control: without the shorthand the same props emit the
            same slots MINUS the folded halves, so the row above is
            measuring the fold rather than the props"
    (is (= {"id" "main" "className" "tall"}
           (ht/element-props [:div {:id "main" :class "tall"}]))))

  (testing "canonical slot names, not the author's spelling"
    (is (= {"tabIndex" 0 "htmlFor" "x"}
           (ht/element-props [:label {:tab-index 0 :for "x"}]))))

  (testing "the `:&` remainder folds under the owned-literal law: a
            caller cannot reach a slot the element writes"
    (is (= {"className" "owned" "title" "from-caller"}
           (ht/element-props [:div {:class "owned"
                                    :&     {:class "hijacked" :title "from-caller"}}]))))

  (testing "a lowered handler records as 004B's opaque marker — the site's
            existence and spelling are the claim, its behaviour is L3"
    (is (= {"onClick" {:rf.ui/opaque :fn}}
           (ht/element-props [:button {:on-click [:tk/toggle 1]}]))))

  (testing "and a non-native form is refused rather than projected"
    (is (= {:rf.error/id :rf.error/hicasso-test-not-a-native-form
            :where       're-frame.hicasso.test
            :recovery    :pass-a-native-hiccup-form}
           (refusal (outcome #(ht/element-props [greeting {}])) [])))))

(deftest materialize-is-the-runtime-marker-law
  (testing "`::h/value` takes the target's value"
    (is (= [:tk/set-filter "done"]
           (ht/materialize [:tk/set-filter :re-frame.hicasso/value]
                           {:value "done"}))))

  (testing "`::h/checked` takes the target's checked state, and a marker
            that is not present is left alone — the substitution is a
            roster, not a positional rule"
    (is (= [:tk/set 7 true]
           (ht/materialize [:tk/set 7 :re-frame.hicasso/checked]
                           {:value "ignored" :checked true}))))

  (testing "an intent carrying no marker comes back identical, so a green
            equality above is not a green everything"
    (is (= [:tk/toggle 1] (ht/materialize [:tk/toggle 1] {:value "x"}))))

  (testing "and a non-vector refuses"
    (is (= {:rf.error/id :rf.error/hicasso-test-not-an-intent
            :where       're-frame.hicasso.test
            :recovery    :pass-an-intent-vector}
           (refusal (outcome #(ht/materialize {:not "an intent"} {})) [])))))

(deftest the-controlled-and-revision-laws-read-as-data
  (testing "a text input carrying a value and a change handler IS the
            controlled door — the runtime's own selection, not a
            re-derivation of it"
    (is (true? (ht/controlled? [:input {:type  "text"
                                        :value "milk"
                                        :on-change [:tk/edit]}]))))

  (testing "and the near neighbours are not: an uncontrolled input, and a
            div carrying the identical props"
    (is (false? (ht/controlled? [:input {:type "text"}])))
    (is (false? (ht/controlled? [:div {:value "milk" :on-change [:tk/edit]}]))))

  (testing "the revision trigger reads off the author's own attribute map"
    (is (= 7 (ht/revision [:input {:value "milk"
                                   :re-frame.hicasso/revision 7}])))
    (is (nil? (ht/revision [:input {:value "milk"}])))))

;; ---------------------------------------------------------------------------
;; L1 — intent capture at Spec 009's observation port
;; ---------------------------------------------------------------------------

(defn- seeded!
  []
  (rf/make-frame {:id frame-id})
  (rf/with-frame frame-id
    (rf/dispatch-sync [:tk/seed {:todos {1 {:text "milk" :done false}
                                         2 {:text "bread" :done true}}
                                 :filter :all}]))
  frame-id)

(deftest capture-intents-reads-the-public-events-port
  (seeded!)
  (testing "what the frame dispatched, in order, as the vectors themselves"
    (let [{:keys [intents value]}
          (ht/capture-intents frame-id
                              (fn []
                                (rf/with-frame frame-id
                                  (rf/dispatch-sync [:tk/toggle 1])
                                  (rf/dispatch-sync [:tk/toggle 2]))
                                :done))]
      (is (= [[:tk/toggle 1] [:tk/toggle 2]] intents))
      (is (= :done value))))

  (testing "and the empty case answers empty rather than never running —
            without this row an assertion of [] could be green for a
            capture that was never armed"
    (is (= {:value :nothing-dispatched :intents []}
           (ht/capture-intents frame-id (fn [] :nothing-dispatched)))))

  (testing "another frame's events are not this frame's capture"
    (rf/make-frame {:id ::other})
    (rf/with-frame ::other (rf/dispatch-sync [:tk/seed {:todos {}}]))
    (is (= [] (:intents (ht/capture-intents
                          frame-id
                          (fn [] (rf/with-frame ::other
                                   (rf/dispatch-sync [:tk/seed {:todos {}}])))))))))

;; ---------------------------------------------------------------------------
;; L2 — the semantic tree
;; ---------------------------------------------------------------------------

(defn- todo-row-body
  "A body written exactly as an author writes one: `h/sub` from the public
  door, hiccup out, no hooks and nothing React-shaped."
  [{:keys [id]}]
  (let [todo (h/sub [:tk/todo id])]
    [:li.row {:data-id id :on-click [:tk/toggle id]}
     [:span.text (:text todo)]
     (when (:done todo) [:span.done "✓"])]))

(deftest render-answers-the-versioned-004b-tree
  (let [tree (ht/render [todo-row-body {:id 1}]
                        {:reads {[:tk/todo 1] {:text "milk" :done false}}})]

    (testing "the root carries the version gate every consumer validates first"
      (is (= 1 (:rf.ui/tree-version tree)))
      (is (= 1 ht/tree-version)))

    (testing "the whole tree is plain serialisable data — no wrapper types,
              no metadata-carried contract"
      (is (= {:rf.ui/tree-version 1
              :tag      :li
              :attrs    {:data-id 1 :class "row"}
              :events   {:on-click [:tk/toggle 1]}
              :children [{:tag :span :attrs {:class "text"} :children ["milk"]}]}
             tree))
      (is (= tree (reader/read-string (pr-str tree)))))

    (testing "the projections read it"
      (is (= "milk" (ht/text tree)))
      (is (= :span (:tag (ht/find tree #(= "text" (:class (:attrs %)))))))
      (is (= 2 (count (ht/find-all tree map?))))
      (is (= [:tk/toggle 1] (:on-click (ht/attrs tree)))))

    (testing "and `attrs` MERGES events with attributes, which is the one
              attribute read — a keyword lookup on the node is a field miss"
      (is (= {:data-id 1 :class "row" :on-click [:tk/toggle 1]} (ht/attrs tree)))
      (is (nil? (:on-click tree)))))

  (testing "the branch not taken contributes no node, and the branch taken
            does — so the row above is reading the body's control flow
            rather than a fixed shape"
    (let [tree (ht/render [todo-row-body {:id 1}]
                          {:reads {[:tk/todo 1] {:text "milk" :done true}}})]
      (is (= "milk✓" (ht/text tree)))
      (is (some? (ht/find tree #(= "done" (:class (:attrs %)))))))))

(deftest the-tree-carries-intents-as-data
  (let [tree (ht/render [(fn [_]
                           [:ul
                            [:li {:on-click [:tk/toggle 1]} "one"]
                            [:li {:on-click [:tk/toggle 2]
                                  :on-key-down {"Enter"  [:tk/commit 2]
                                                "Escape" [:tk/cancel 2]}} "two"]
                            [:li {:on-click (h/hfn [_] [:tk/opaque])} "three"]])
                         {}])]
    (testing "every literal intent site, in document order, including both
              branches of a data key-map"
      (is (= [[:tk/toggle 1] [:tk/toggle 2] [:tk/commit 2] [:tk/cancel 2]]
             (ht/intents tree))))

    (testing "a callback site contributes nothing — it is exactly the site
              whose intent is not data — and records as the opaque marker"
      (is (= {:rf.ui/opaque :fn}
             (:on-click (ht/attrs (ht/find tree #(= "three" (ht/text %))))))))

    (testing "and the empty case answers empty, so an equality against a
              stated expectation cannot pass vacuously"
      (is (= [] (ht/intents (ht/render [(fn [_] [:p "no handlers here"]) {}])))))))

(deftest a-child-boundary-records-the-call-and-never-its-rendering
  (let [tree (ht/render [(fn [_] [:div [greeting {:key 9 :who "ada"} "extra"]]) {}])
        node (ht/find tree :view-id)]
    (testing "the node is the CALL: the view id, the props the call site
              passed, and the children it wrote"
      (is (= {:view-id  "re-frame.hicasso.test-kit-cljs-test/greeting"
              :key      9
              :props    {:who "ada"}
              :children ["extra"]}
             node)))

    (testing "and nothing of the child's own rendering is in it — the body
              did not run, so `text` answers the call site's children and
              the word the child would have rendered is absent"
      (is (= "extra" (ht/text node)))
      (is (nil? (re-find #"hi ada" (ht/text tree)))))))

;; ---------------------------------------------------------------------------
;; L2 — honest opacity. Every refusal, with its legal twin.
;; ---------------------------------------------------------------------------

(deftest a-minted-boundary-head-renders-the-body-the-dev-build-retained
  ;; The inversion of `a-minted-boundary-head-refuses-because-its-body-is-not-
  ;; retained`, under the rf2-kjf5 ruling: `mint-view!` now keeps the body ON
  ;; the head under one dev-only property, so L2 renders `[some-view …]`.
  ;; The refusal is not gone — the last row below is it, reached the one way
  ;; it can still be reached.
  (testing "a minted `h/defview` head renders, and answers the body's tree"
    (let [tree (ht/render [farewell {:who "ada"}])]
      (is (= :p (:tag tree)))
      (is (= "bye ada" (ht/text tree)))))

  (testing "and it is the SAME tree the body answers when named directly —
            which is what running a view AS WRITTEN has to mean, and rules
            out a second rendering path that merely agrees on the text"
    (is (= (ht/render [farewell-text {:who "ada"}])
           (ht/render [farewell {:who "ada"}]))))

  (testing "what it cost is ONE own property on the head: the head is still
            the function `defview` defined and still a boundary, so no memo
            object escaped as the public representation (rf2-2rtt6.52)"
    (is (fn? farewell))
    (is (true? (ht/boundary? farewell)))
    (is (fn? (codec/retained-body farewell))))

  (testing "the retention is DEV-ONLY, so the refusal is still live and still
            named: a boundary head with no retained body — an `:advanced` +
            `goog.DEBUG=false` mint — refuses with the id, the view and the
            L3 pointer it always carried"
    (let [o (outcome #(ht/render [unretained-head {:who "ada"}]))]
      (is (= {:rf.error/id :rf.error/hicasso-test-boundary-body-not-retained
              :where       're-frame.hicasso.test
              :recovery    :render-the-body-fn-or-mount-at-l3
              :view        "re-frame.hicasso.test-kit-cljs-test/unretained-head"}
             (refusal o [:view])))
      (is (re-find #"L3 owns React lifecycle" (:message o))
          "the message points up the ladder rather than restating the tier"))))

(deftest a-host-crossing-is-opaque-at-l2
  (testing "at the root"
    (is (= {:rf.error/id :rf.error/hicasso-test-host-is-opaque
            :where       're-frame.hicasso.test
            :recovery    :assert-it-at-l3
            :host        "re-frame.hicasso.test-kit-cljs-test/badge"}
           (refusal (outcome #(ht/render [badge {:label "x"}])) [:host]))))

  (testing "and inside a body's own tree, which is where a crossing
            actually appears"
    (is (= {:rf.error/id :rf.error/hicasso-test-host-is-opaque
            :where       're-frame.hicasso.test
            :recovery    :assert-it-at-l3
            :host        "re-frame.hicasso.test-kit-cljs-test/badge"}
           (refusal (outcome #(ht/render [(fn [_] [:div [badge {:label "x"}]]) {}]))
                    [:host]))))

  (testing "the legal twin: the same body WITHOUT the crossing renders, so
            the refusal is the crossing's and not the div's"
    (is (= {:rf.ui/tree-version 1 :tag :div}
           (ht/render [(fn [_] [:div]) {}])))))

(deftest raw-react-is-opaque-at-l2
  (testing "an element only React can interpret has no semantic form here"
    (is (= {:rf.error/id :rf.error/hicasso-test-react-is-opaque
            :where       're-frame.hicasso.test
            :recovery    :assert-it-at-l3}
           (refusal (outcome
                      #(ht/render [(fn [_]
                                     [:div (react/createElement "b" nil "raw")])
                                   {}]))
                    []))))

  (testing "and an unforced `delay` is refused rather than forced — forcing
            an author's explicit deferral would change what their program
            means, which is the runtime's own ruling at a crossing"
    (is (= :rf.error/hicasso-deferred-read-at-boundary
           (:rf.error/id
            (:refused (outcome #(ht/render [(fn [_] [:div (delay [:p])]) {}]))))))))

(deftest a-plain-function-in-head-position-refuses-as-the-runtime-does
  (testing "HD-016 makes it a loud error in Hicasso, so the kit refuses it
            too rather than teaching a spelling the runtime rejects"
    (is (= {:rf.error/id :rf.error/hicasso-test-plain-fn-head
            :where       're-frame.hicasso.test
            :recovery    :mint-the-boundary-or-render-it-as-the-root}
           (refusal (outcome #(ht/render [(fn [_] [:div [greeting-body {:who "x"}]])
                                          {}]))
                    []))))

  (testing "the legal twin: the same function IS the root form's head"
    (is (= "hi x" (ht/text (ht/render [greeting-body {:who "x"}]))))))

;; ---------------------------------------------------------------------------
;; L2 — the injected read fixtures
;; ---------------------------------------------------------------------------

(deftest a-read-no-fixture-answers-refuses-rather-than-resolving-to-nil
  (testing "the refusal names the query, so the message is actionable
            without a debugger"
    (let [o (outcome #(ht/render [todo-row-body {:id 3}] {:reads {}}))]
      (is (= {:rf.error/id :rf.error/hicasso-test-missing-read-fixture
              :where       're-frame.hicasso.test
              :recovery    :add-the-query-to-reads
              :phase       :after-body-run
              :missing     [[:tk/todo 3]]}
             (refusal o [:missing :phase])))))

  (testing "and the legal twin: the SAME body with the fixture supplied
            renders — so the refusal is about the fixture and not about a
            body that was broken anyway"
    (is (= "bread"
           (ht/text (ht/render [todo-row-body {:id 3}]
                               {:reads {[:tk/todo 3] {:text "bread"}}})))))

  (testing "a fixture supplied but NOT read is not an error — the read set
            is what the body did, not what the caller offered"
    (is (= "milk"
           (ht/text (ht/render [todo-row-body {:id 1}]
                               {:reads {[:tk/todo 1] {:text "milk"}
                                        [:tk/filter] :all}})))))

  (testing "and :reads itself is checked"
    (is (= {:rf.error/id :rf.error/hicasso-test-bad-reads
            :where       're-frame.hicasso.test
            :recovery    :pass-a-map-of-query-to-value}
           (refusal (outcome #(ht/render [greeting-body {}] {:reads [:not :a :map]}))
                    [])))))

(deftest the-read-resolver-is-discardable
  (testing "the fixture cells exist for the body run and are gone when it
            returns — nothing subscribed, nothing watched, nothing left to
            dispose"
    (let [before (count @collector/!cells)]
      (ht/render [todo-row-body {:id 1}] {:reads {[:tk/todo 1] {:text "milk"}}})
      (is (= before (count @collector/!cells)))))

  (testing "and the runtime's own retention tables are as they were — the
            render acquired no cell, took no reference and recorded no edge"
    (let [before (dissoc (inventory/residue) :entries)]
      (ht/render [todo-row-body {:id 1}] {:reads {[:tk/todo 1] {:text "milk"}}})
      (is (= before (dissoc (inventory/residue) :entries)))
      (is (= {:cells 0 :cell-refs 0 :boundaries 0 :edges 0}
             (dissoc (inventory/residue) :entries))
          "and the baseline itself is the empty one, so the equality above
           is not two identical wrong numbers")))

  (testing "the probe frame is minted per call, so two renders cannot see
            each other's fixtures"
    (is (= "milk" (ht/text (ht/render [todo-row-body {:id 1}]
                                      {:reads {[:tk/todo 1] {:text "milk"}}}))))
    (is (= :rf.error/hicasso-test-missing-read-fixture
           (:rf.error/id (:refused (outcome #(ht/render [todo-row-body {:id 1}]
                                                        {:reads {}}))))))))

(deftest the-body-runs-on-the-runtime-s-own-path
  (testing "the read extent (I7) is the real one: a read deferred past the
            body's synchronous extent refuses with the RUNTIME's id, which
            is what says this harness runs bodies rather than imitating one"
    (let [escaped (volatile! nil)
          o (outcome
              #(ht/render [(fn [_]
                             (vreset! escaped (fn [] (h/sub [:tk/filter])))
                             [:p])
                           {}]))]
      (is (map? (:returned o)) "the body itself is legal")
      (is (= {:rf.error/id :rf.error/hicasso-sub-outside-render
              :where       're-frame.hicasso.impl.collector/read-key!
              :recovery    :read-inside-a-boundary-body}
             (refusal (outcome @escaped) [])))))

  (testing "and the body-run counter moved, so the row above is not green
            for a body that never ran"
    (collector/reset-body-runs!)
    (ht/render [greeting-body {:who "ada"}])
    (is (= 1 (collector/body-runs)))))

;; ---------------------------------------------------------------------------
;; L2 — the projections' own refusals
;; ---------------------------------------------------------------------------

(deftest the-projections-fail-loud-on-a-broken-tree
  (testing "a map carrying two primaries is malformed rather than read as
            whichever the discrimination order reaches first"
    (is (= :rf.error/ui-tree-malformed
           (:rf.error/id
            (:refused (outcome #(ht/attrs {:tag :p :view-id "x"})))))))

  (testing "text content is not a node, at either projection"
    (is (= :rf.error/ui-tree-malformed
           (:rf.error/id (:refused (outcome #(ht/attrs "milk"))))))
    (is (= :rf.error/ui-tree-malformed
           (:rf.error/id (:refused (outcome #(ht/text "milk")))))))

  (testing "and nil threads through a missed traversal rather than throwing,
            so `(attrs (find …))` nil-puns"
    (is (nil? (ht/attrs nil)))
    (is (nil? (ht/text nil)))
    (is (nil? (ht/find {:rf.ui/tree-version 1 :tag :p} #(= :div (:tag %)))))))

;; ---------------------------------------------------------------------------
;; L0 — the ladder is data, and the refusals cite it
;; ---------------------------------------------------------------------------

(deftest the-ladder-is-the-single-source-of-the-tier-vocabulary
  (testing "five rows, in tier order, each naming what it proves and the
            mechanism it is written with"
    (is (= [:l0 :l1 :l2 :l3 :l4] (mapv :tier ht/ladder)))
    (is (every? (fn [row] (and (string? (:proves row)) (string? (:mechanism row))))
                ht/ladder)))

  (testing "this namespace ships L1 and L2 and says so, which is what makes
            a reader's `where do I write this` answerable from data"
    (is (= [:l1 :l2] (mapv :tier (filterv :here? ht/ladder)))))

  (testing "and an opacity refusal quotes the L3 row rather than restating
            it — so a tier description has one home"
    (let [l3  (first (filterv #(= :l3 (:tier %)) ht/ladder))
          msg (:message (outcome #(ht/render [badge {}])))]
      (is (str/includes? msg (:proves l3)))
      (is (str/includes? msg (:mechanism l3))))))
