(ns re-frame.fresco.test-kit-cljs-test
  "THE TEST KIT'S OWN WITNESSES — L1 and L2, positive and sabotaged.

  `re-frame.fresco.test` is an instrument, and an instrument is only
  worth the controls that can make it go red. Every claim below is
  written as a PAIR: the thing the kit answers, and a control that fails
  if the kit answered it by accident.

  ## The two shapes of control in this file

  1. **Discrimination.** A predicate is asserted true on the thing and
     FALSE on its nearest neighbour — `boundary?` on a `defview` var and
     on the plain function its body is, `callback?` on `h/event` and on an
     identically-written `fn`. A predicate only ever asserted true is a
     predicate that could be `(constantly true)`.
  2. **Refusals as identity.** [[outcome]] reports `{:returned v}` or
     `{:refused <ex-data>}`, and the refusal rows assert the ex-data's
     identity rather than `(is (thrown? …))`: a bare `thrown?` is green
     for a throw from any layer carrying any id.

  ## What is asserted elsewhere

  [[re-frame.fresco.test/canonical-dom]] takes a DOM node, so its
  witnesses are in `test-kit-dom-cljs-test` on the browser lane. This is
  the node lane: nothing here mounts, and nothing here needs a document."
  (:require [cljs.reader :as reader]
            [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [clojure.string :as str]
            [re-frame.adapter.uix :as rf.adapter.uix]
            [re-frame.core :as rf]
            [re-frame.fresco :as rf.fresco]
            [re-frame.fresco.impl.codec :as rf.fresco.impl.codec]
            [re-frame.fresco.impl.collector :as rf.fresco.impl.collector]
            [re-frame.fresco.test.runtime :as rf.fresco.test.runtime]
            [re-frame.fresco.test :as rf.fresco.test]
            [re-frame.test-support :as rf.test-support]
            ["react" :as react]))

(def ^:private frame-id ::test-kit)

(rf/reg-sub :tk/todo   (fn [db [_ id]] (get-in db [:todos id])))
(rf/reg-sub :tk/filter (fn [db _] (:filter db)))


(rf/reg-event :tk/seed   (fn [_ [_ db]] {:db db}))
(rf/reg-event :tk/toggle (fn [{:keys [db]} [_ id]]
                           {:db (update-in db [:todos id :done] not)}))

;; The node lane, plain-atom's reactivity gap and the `act` environment are
;; all as `smoke-cljs-test` and `read-extent-cljs-test` establish them: the
;; UIx adapter because plain-atom never notifies, no ambient frame because
;; this suite seats its own, and the collector emptied between rows.
(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    {:adapter       rf.adapter.uix/adapter
     :ambient-frame nil
     :init-fn       (fn []
                      (set! (.-IS_REACT_ACT_ENVIRONMENT js/globalThis) false)
                      (rf.fresco.impl.collector/reset-runtime!))}))

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
  "The keys a refusal's identity is asserted on: the stable id, the
  raising site, and whatever the row names as its subject. `:reason` is
  prose and deliberately not frozen; `:recovery` is `:no-recovery` on
  every throw, so it discriminates nothing and is not selected."
  [outcome' extra-keys]
  (some-> (:refused outcome')
          (select-keys (into [:rf.error/id :where] extra-keys))))

;; ---------------------------------------------------------------------------
;; L1 — the boundary ABI
;; ---------------------------------------------------------------------------

(defn- badge-component [^js props]
  (react/createElement "b" #js {"className" "badge"} (.-label props)))

(rf.fresco/defhost badge badge-component {:server :render})

;; The `<name>-body` pair, which is the spelling this suite's own examples
;; teach. A named `fn` binds its own name inside its body, so an
;; `h/defview` that named its emitted body fn `<sym>-body` would expand this
;; declaration to `(fn greeting-body [p] (greeting-body p))` and recurse
;; until the stack overflowed. The expansion names nothing, so the only
;; `greeting-body` in scope is the author's;
;; `a-body-may-call-a-helper-named-after-its-view` below is what says so.
(defn- greeting-body [{:keys [who]}] [:p (str "hi " who)])
(rf.fresco/defview greeting [props] (greeting-body props))

;; The pair the minted-head render rows below run, spelled the OTHER way —
;; a helper whose name is not derived from the view's — so those rows keep
;; measuring the retention contract rather than doubling as the
;; helper-naming witness.
(defn- farewell-text [{:keys [who]}] [:p (str "bye " who)])
(rf.fresco/defview farewell [props] (farewell-text props))

(def ^:private unretained-head
  "A boundary head carrying NO retained body — which is exactly what an
  `:advanced` + `goog.DEBUG=false` mint produces, because
  `collector/mint-view!` writes the body property inside
  `(when ^boolean js/goog.DEBUG …)` and nothing else writes it ever.

  Marked through the codec's own door, so what is under test is the
  runtime's notion of a boundary head rather than this file's."
  (doto (rf.fresco.impl.codec/mark-boundary! (fn [_] [:p "never runs at L2"]))
    (unchecked-set "displayName"
                   "re-frame.fresco.test-kit-cljs-test/unretained-head")))

(deftest the-abi-predicates-discriminate-rather-than-restate-fn?
  (testing "a `defview` var is a boundary and its own body function is not —
            which is the whole discrimination, since both are functions"
    (is (true?  (rf.fresco.test/boundary? greeting)))
    (is (false? (rf.fresco.test/boundary? greeting-body)))
    (is (false? (rf.fresco.test/boundary? badge))))

  (testing "a `defhost` var is a crossing and the component it named is not"
    (is (true?  (rf.fresco.test/host? badge)))
    (is (false? (rf.fresco.test/host? badge-component)))
    (is (false? (rf.fresco.test/host? greeting))))

  (testing "`h/event` is the one callback form and an identically-written
            plain `fn` is not"
    (is (true?  (rf.fresco.test/callback? (rf.fresco/event [e] [:tk/picked (.-value e)]))))
    (is (false? (rf.fresco.test/callback? (fn [e] [:tk/picked (.-value e)])))))

  (testing "the minted name is the one React DevTools and Spec 009's
            render measure are keyed on"
    (is (= "re-frame.fresco.test-kit-cljs-test/greeting" (rf.fresco.test/view-name greeting)))
    (is (= "re-frame.fresco.test-kit-cljs-test/badge" (rf.fresco.test/view-name badge)))
    (is (nil? (rf.fresco.test/view-name {:not "a minted value"}))))

  (testing "the declared server policy is read back off the crossing as data"
    (is (= :render (rf.fresco.test/host-policy badge))))

  (testing "and asking a NON-host for a policy refuses rather than
            answering nil — a nil here would read as :client-only's
            neighbour"
    (is (= {:rf.error/id :rf.error/fresco-test-not-a-host
            :where       're-frame.fresco.test}
           (refusal (outcome #(rf.fresco.test/host-policy greeting)) [])))))

;; ---------------------------------------------------------------------------
;; L1 — the codec, projected
;; ---------------------------------------------------------------------------

(deftest element-props-answers-the-slots-the-codec-emits
  (testing "the `.class#id` sugar folds by the codec's own two rules —
            an explicit id WINS over `#id`, the shorthand class is
            PREPENDED to a declared one"
    (is (= {"id" "main" "className" "wide tall"}
           (rf.fresco.test/element-props [:div#ignored.wide {:id "main" :class "tall"}]))))


  (testing "canonical slot names, not the author's spelling"
    (is (= {"tabIndex" 0 "htmlFor" "x"}
           (rf.fresco.test/element-props [:label {:tab-index 0 :for "x"}]))))


  (testing "a lowered handler records as 004B's opaque marker — the site's
            existence and spelling are the claim, its behaviour is L3"
    (is (= {"onClick" {:rf.ui/opaque :fn}}
           (rf.fresco.test/element-props [:button {:on-click [:tk/toggle 1]}]))))

  (testing "and a non-native form is refused rather than projected"
    (is (= {:rf.error/id :rf.error/fresco-test-not-a-native-form
            :where       're-frame.fresco.test}
           (refusal (outcome #(rf.fresco.test/element-props [greeting {}])) [])))))

(deftest materialize-is-the-runtime-marker-law
  (testing "`::h/value` takes the target's value"
    (is (= [:tk/set-filter "done"]
           (rf.fresco.test/materialize [:tk/set-filter :re-frame.fresco/value]
                           {:value "done"}))))

  (testing "`::h/checked` takes the target's checked state, and a marker
            that is not present is left alone — the substitution is a
            roster, not a positional rule"
    (is (= [:tk/set 7 true]
           (rf.fresco.test/materialize [:tk/set 7 :re-frame.fresco/checked]
                           {:value "ignored" :checked true}))))

  (testing "an intent carrying no marker comes back identical, so a green
            equality above is not a green everything"
    (is (= [:tk/toggle 1] (rf.fresco.test/materialize [:tk/toggle 1] {:value "x"}))))

  (testing "and a non-vector refuses"
    (is (= {:rf.error/id :rf.error/fresco-test-not-an-intent
            :where       're-frame.fresco.test}
           (refusal (outcome #(rf.fresco.test/materialize {:not "an intent"} {})) [])))))

(deftest the-controlled-and-revision-laws-read-as-data
  (testing "a text input carrying a value and a change handler IS the
            controlled door — the runtime's own selection, not a
            re-derivation of it"
    (is (true? (rf.fresco.test/controlled? [:input {:type  "text"
                                        :value "milk"
                                        :on-change [:tk/edit]}]))))

  (testing "and the near neighbours are not: an uncontrolled input, and a
            div carrying the identical props"
    (is (false? (rf.fresco.test/controlled? [:input {:type "text"}])))
    (is (false? (rf.fresco.test/controlled? [:div {:value "milk" :on-change [:tk/edit]}]))))

  (testing "the revision trigger reads off the author's own attribute map"
    (is (= 7 (rf.fresco.test/revision [:input {:value "milk"
                                   :re-frame.fresco/revision 7}])))))

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
    (is (= {:value :done :intents [[:tk/toggle 1] [:tk/toggle 2]]}
           (rf.fresco.test/capture-intents frame-id
                               (fn []
                                 (rf/with-frame frame-id
                                   (rf/dispatch-sync [:tk/toggle 1])
                                   (rf/dispatch-sync [:tk/toggle 2]))
                                 :done)))))

  (testing "and the empty case answers empty rather than never running —
            without this row an assertion of [] could be green for a
            capture that was never armed"
    (is (= {:value :nothing-dispatched :intents []}
           (rf.fresco.test/capture-intents frame-id (fn [] :nothing-dispatched)))))

  (testing "another frame's events are not this frame's capture"
    (rf/make-frame {:id ::other})
    (rf/with-frame ::other (rf/dispatch-sync [:tk/seed {:todos {}}]))
    (is (= [] (:intents (rf.fresco.test/capture-intents
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
  (let [todo (rf.fresco/sub [:tk/todo id])]
    [:li.row {:data-id id :on-click [:tk/toggle id]}
     [:span.text (:text todo)]
     (when (:done todo) [:span.done "✓"])]))

(deftest render-answers-the-versioned-004b-tree
  (let [tree (rf.fresco.test/tree [todo-row-body {:id 1}]
                        {:subs {[:tk/todo 1] {:text "milk" :done false}}})]


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
      (is (= "milk" (rf.fresco.test/text tree)))
      (is (= :span (:tag (rf.fresco.test/find tree #(= "text" (:class (:attrs %)))))))
      (is (= 2 (count (rf.fresco.test/find-all tree map?)))))

    (testing "and `attrs` MERGES events with attributes, which is the one
              attribute read — a keyword lookup on the node is a field miss"
      (is (= {:data-id 1 :class "row" :on-click [:tk/toggle 1]} (rf.fresco.test/attrs tree)))))

  (testing "the branch not taken contributes no node, and the branch taken
            does — so the row above is reading the body's control flow
            rather than a fixed shape"
    (let [tree (rf.fresco.test/tree [todo-row-body {:id 1}]
                          {:subs {[:tk/todo 1] {:text "milk" :done true}}})]
      (is (= "milk✓" (rf.fresco.test/text tree))))))

(deftest the-tree-carries-intents-as-data
  (let [tree (rf.fresco.test/tree [(fn [_]
                           [:ul
                            [:li {:on-click [:tk/toggle 1]} "one"]
                            [:li {:on-click [:tk/toggle 2]
                                  :on-key-down {"Enter"  [:tk/commit 2]
                                                "Escape" [:tk/cancel 2]}} "two"]
                            [:li {:on-click (rf.fresco/event [_] [:tk/opaque])} "three"]])
                         {}])]
    (testing "every literal intent site, in document order, including both
              branches of a data key-map"
      (is (= [[:tk/toggle 1] [:tk/toggle 2] [:tk/commit 2] [:tk/cancel 2]]
             (rf.fresco.test/intents tree))))

    (testing "a callback site contributes nothing — it is exactly the site
              whose intent is not data — and records as the opaque marker"
      (is (= {:rf.ui/opaque :fn}
             (:on-click (rf.fresco.test/attrs (rf.fresco.test/find tree #(= "three" (rf.fresco.test/text %))))))))

    (testing "and the empty case answers empty, so an equality against a
              stated expectation cannot pass vacuously"
      (is (= [] (rf.fresco.test/intents (rf.fresco.test/tree [(fn [_] [:p "no handlers here"]) {}])))))))

(deftest a-child-boundary-records-the-call-and-never-its-rendering
  (let [tree (rf.fresco.test/tree [(fn [_] [:div [greeting {:key 9 :who "ada"} "extra"]]) {}])]
    (testing "the node is the CALL: the view id, the props the call site
              passed, and the children it wrote — the child's body did not run"
      (is (= {:rf.ui/tree-version 1
              :tag      :div
              :children [{:view-id  "re-frame.fresco.test-kit-cljs-test/greeting"
                          :key      9
                          :props    {:who "ada"}
                          :children ["extra"]}]}
             tree)))

    (testing "and `text` answers the call site's children, never the child's rendering"
      (is (= "extra" (rf.fresco.test/text tree))))))

;; ---------------------------------------------------------------------------
;; L2 — honest opacity. Every refusal, with its legal twin.
;; ---------------------------------------------------------------------------

(deftest a-minted-boundary-head-renders-the-body-the-dev-build-retained
  ;; `mint-view!` keeps the body ON the head under one dev-only property, so
  ;; L2 renders `[some-view …]`. The not-retained refusal is the last row
  ;; below, reached the one way it can be reached.
  (testing "a minted `h/defview` head renders the body AS WRITTEN"
    (is (= {:rf.ui/tree-version 1 :tag :p :children ["bye ada"]}
           (rf.fresco.test/tree [farewell {:who "ada"}]))))

  (testing "the retention is DEV-ONLY, so the refusal is live and named: a
            boundary head with no retained body — an `:advanced` +
            `goog.DEBUG=false` mint — refuses with the id and the view"
    (is (= {:rf.error/id :rf.error/fresco-test-boundary-body-not-retained
            :where       're-frame.fresco.test
            :view        "re-frame.fresco.test-kit-cljs-test/unretained-head"}
           (refusal (outcome #(rf.fresco.test/tree [unretained-head {:who "ada"}])) [:view])))))

(deftest a-body-may-call-a-helper-named-after-its-view
  ;; The helper-naming witness, at L2. `greeting`'s body calls
  ;; `greeting-body` — the extract-a-helper spelling this file's own examples
  ;; use — and a macro that NAMED its emitted fn would make that call resolve
  ;; to the emitted fn itself and recurse until the stack overflowed. Rendered
  ;; through the kit, the failure is a `Maximum call stack size exceeded`
  ;; naming no view, no id and no macro.
  (testing "the helper the body calls is the author's, so a view whose helper
            is named after it renders rather than recursing"
    (is (= {:rf.ui/tree-version 1 :tag :p :children ["hi ada"]}
           (rf.fresco.test/tree [greeting {:who "ada"}])))))

(deftest a-host-crossing-is-opaque-at-l2
  (testing "at the root"
    (is (= {:rf.error/id :rf.error/fresco-test-host-is-opaque
            :where       're-frame.fresco.test
            :host        "re-frame.fresco.test-kit-cljs-test/badge"}
           (refusal (outcome #(rf.fresco.test/tree [badge {:label "x"}])) [:host]))))

  (testing "and inside a body's own tree, which is where a crossing
            actually appears"
    (is (= {:rf.error/id :rf.error/fresco-test-host-is-opaque
            :where       're-frame.fresco.test
            :host        "re-frame.fresco.test-kit-cljs-test/badge"}
           (refusal (outcome #(rf.fresco.test/tree [(fn [_] [:div [badge {:label "x"}]]) {}]))
                    [:host])))))

(deftest raw-react-is-opaque-at-l2
  (testing "an element only React can interpret has no semantic form here"
    (is (= {:rf.error/id :rf.error/fresco-test-react-is-opaque
            :where       're-frame.fresco.test}
           (refusal (outcome
                      #(rf.fresco.test/tree [(fn [_]
                                     [:div (react/createElement "b" nil "raw")])
                                   {}]))
                    []))))
)

(deftest a-plain-function-in-head-position-refuses-as-the-runtime-does
  (testing "HD-016 makes it a loud error in Fresco, so the kit refuses it
            too rather than teaching a spelling the runtime rejects"
    (is (= {:rf.error/id :rf.error/fresco-test-plain-fn-head
            :where       're-frame.fresco.test}
           (refusal (outcome #(rf.fresco.test/tree [(fn [_] [:div [greeting-body {:who "x"}]])
                                          {}]))
                    [])))))

;; ---------------------------------------------------------------------------
;; L2 — the injected read fixtures
;; ---------------------------------------------------------------------------

(deftest a-read-no-fixture-answers-refuses-rather-than-resolving-to-nil
  (testing "the refusal names the query, so the message is actionable
            without a debugger"
    (let [o (outcome #(rf.fresco.test/tree [todo-row-body {:id 3}] {:subs {}}))]
      (is (= {:rf.error/id :rf.error/fresco-test-missing-read-fixture
              :where       're-frame.fresco.test
              :phase       :after-body-run
              :missing     [[:tk/todo 3]]}
             (refusal o [:missing :phase])))))


  (testing "a fixture supplied but NOT read is not an error — the read set
            is what the body did, not what the caller offered"
    (is (= "milk"
           (rf.fresco.test/text (rf.fresco.test/tree [todo-row-body {:id 1}]
                               {:subs {[:tk/todo 1] {:text "milk"}
                                        [:tk/filter] :all}})))))

  (testing "and :subs itself is checked"
    (is (= {:rf.error/id :rf.error/fresco-test-bad-reads
            :where       're-frame.fresco.test}
           (refusal (outcome #(rf.fresco.test/tree [greeting-body {}] {:subs [:not :a :map]}))
                    [])))))

(deftest the-option-roster-is-closed-so-a-key-nothing-reads-cannot-look-set
  ;; `{fixtures :subs}` reads one key off the options map and ignores every
  ;; other, so a roster check is the only thing that can tell an author
  ;; their option did nothing. The two rows below are the two ways that
  ;; silence is otherwise reachable, and neither is caught by the
  ;; missing-read detection above.
  (testing "a near-miss spelling of `:subs` on a body that reads NOTHING.
            `{:reads …}` supplies no fixtures and the body needs none, so
            without the roster check the render would succeed outright and
            the author's fixture map would never be read by anything"
    (is (= {:rf.error/id :rf.error/fresco-test-bad-option
            :where       're-frame.fresco.test
            :unknown     [:reads]}
           (refusal (outcome #(rf.fresco.test/tree [greeting-body {:who "ada"}]
                                       {:reads {[:tk/todo 1] {:text "milk"}}}))
                    [:unknown]))))

  (testing "and on a body that DOES read, where ignoring the option would
            name the wrong problem — a missing read fixture rather than the
            bad option"
    (is (= :rf.error/fresco-test-bad-option
           (:rf.error/id
            (:refused (outcome #(rf.fresco.test/tree [todo-row-body {:id 3}]
                                         {:reads {[:tk/todo 3] {:text "bread"}}})))))))

  (testing "an unrecognised key is refused whatever it is spelled, and the
            refusal NAMES what it did not recognise — sorted, so two typos
            read the same way twice"
    (is (= [:sbus :subz]
           (:unknown (:refused (outcome #(rf.fresco.test/tree [greeting-body {:who "ada"}]
                                                  {:subz {} :sbus {}})))))))

  (testing "options that are not a map at all"
    (is (= {:rf.error/id :rf.error/fresco-test-bad-option
            :where       're-frame.fresco.test
            :value       [:subs {}]}
           (refusal (outcome #(rf.fresco.test/tree [greeting-body {:who "ada"}] [:subs {}]))
                    [:value])))))

(deftest the-read-resolver-is-discardable

  (testing "and the runtime's own retention tables are as they were — the
            render acquired no cell, took no reference and recorded no edge"
    (rf.fresco.test/tree [todo-row-body {:id 1}] {:subs {[:tk/todo 1] {:text "milk"}}})
    (is (= {:cells 0 :cell-refs 0 :boundaries 0 :edges 0}
           (dissoc (rf.fresco.test.runtime/residue) :entries))))

  (testing "the probe frame is minted per call, so two renders cannot see
            each other's fixtures"
    (is (= "milk" (rf.fresco.test/text (rf.fresco.test/tree [todo-row-body {:id 1}]
                                      {:subs {[:tk/todo 1] {:text "milk"}}}))))
    (is (= :rf.error/fresco-test-missing-read-fixture
           (:rf.error/id (:refused (outcome #(rf.fresco.test/tree [todo-row-body {:id 1}]
                                                        {:subs {}}))))))))

(deftest the-body-runs-on-the-runtime-s-own-path
  (testing "the read extent (I7) is the real one: a read deferred past the
            body's synchronous extent refuses with the RUNTIME's id, which
            is what says this harness runs bodies rather than imitating one"
    (let [escaped (volatile! nil)]
      (rf.fresco.test/tree [(fn [_]
                              (vreset! escaped (fn [] (rf.fresco/sub [:tk/filter])))
                              [:p])
                            {}])
      (is (= {:rf.error/id :rf.error/fresco-sub-outside-render
              :where       're-frame.fresco.impl.collector/read-key!}
             (refusal (outcome @escaped) []))))))

;; ---------------------------------------------------------------------------
;; L2 — the projections' own refusals
;; ---------------------------------------------------------------------------

(deftest the-projections-fail-loud-on-a-broken-tree
  (testing "a map carrying two primaries is malformed rather than read as
            whichever the discrimination order reaches first"
    (is (= :rf.error/ui-tree-malformed
           (:rf.error/id
            (:refused (outcome #(rf.fresco.test/attrs {:tag :p :view-id "x"})))))))

  (testing "text content is not a node, at either projection"
    (is (= :rf.error/ui-tree-malformed
           (:rf.error/id (:refused (outcome #(rf.fresco.test/attrs "milk"))))))
    (is (= :rf.error/ui-tree-malformed
           (:rf.error/id (:refused (outcome #(rf.fresco.test/text "milk")))))))

  (testing "and nil threads through a missed traversal rather than throwing,
            so `(attrs (find …))` nil-puns"
    (is (nil? (rf.fresco.test/attrs nil)))
    (is (nil? (rf.fresco.test/text nil)))))

;; ---------------------------------------------------------------------------
;; L0 — the ladder is data, and the refusals cite it
;; ---------------------------------------------------------------------------

(deftest the-ladder-is-the-single-source-of-the-tier-vocabulary
  (testing "five rows, in tier order, each naming what it proves and the
            mechanism it is written with"
    (is (= [:l0 :l1 :l2 :l3 :l4] (mapv :tier rf.fresco.test/ladder))))

  (testing "this namespace ships L1 and L2 and says so, which is what makes
            a reader's `where do I write this` answerable from data"
    (is (= [:l1 :l2] (mapv :tier (filterv :here? rf.fresco.test/ladder)))))

  (testing "and an opacity refusal quotes the L3 row rather than restating
            it — so a tier description has one home"
    (let [l3  (first (filterv #(= :l3 (:tier %)) rf.fresco.test/ladder))
          msg (:message (outcome #(rf.fresco.test/tree [badge {}])))]
      (is (str/includes? msg (:proves l3)))
      (is (str/includes? msg (:mechanism l3))))))
