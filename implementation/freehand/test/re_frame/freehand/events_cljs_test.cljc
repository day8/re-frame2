(ns re-frame.freehand.events-cljs-test
  "FH-EVENT-001 … FH-EVENT-004 — intent is data.

  One user action yields exactly one semantic event vector or `nil`. The
  Freehand event site materializes the closed projection roster from the
  live callback payload, so general re-frame dispatch keeps no payload
  arity; the callback roster is closed; and every site owns one stable
  committed proxy.

  Every row runs on the JVM and in ClojureScript from one fixture
  apiece. The one host-shaped seam — reading the scalar payload off a
  live callback argument — is named rather than hidden: the cross-host
  rows supply the payload map directly through `events/payload-map`, and
  a ClojureScript-only suite proves `events/native-payload` reads a real
  event object through the same production path."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [re-frame.freehand :as v]
            [re-frame.freehand.analyze-accept-cljs-test :refer [mk-env]]
            [re-frame.freehand.behaviors :as behaviors]
            [re-frame.freehand.compiler.analyze :as ana]
            [re-frame.freehand.conformance :as conf]
            [re-frame.freehand.descriptor :as descriptor]
            [re-frame.freehand.events :as events]
            [re-frame.freehand.test :as t]
            [re-frame.router :as router]
            #?(:clj [re-frame.core])))

;; ---------------------------------------------------------------------------
;; Host-neutral arity introspection
;; ---------------------------------------------------------------------------

(defn- arity-set
  "The set of fixed arities `f` accepts. On the JVM that is the `invoke`
  methods a `defn` compiles to; in ClojureScript the per-arity
  properties a multi-arity `defn` publishes, falling back to the
  function's own `length` for a single-arity fn."
  [f]
  #?(:clj
     (->> (.getDeclaredMethods (class f))
          (filter #(= "invoke" (.getName ^java.lang.reflect.Method %)))
          (map #(alength (.getParameterTypes ^java.lang.reflect.Method %)))
          set)
     :cljs
     (let [declared (into #{}
                          (filter #(some? (unchecked-get f (str "cljs$core$IFn$_invoke$arity$" %))))
                          (range 0 8))]
       (if (seq declared) declared #{(.-length f)}))))

;; ---------------------------------------------------------------------------
;; Recording seams — the injected dispatcher a committed site targets
;; ---------------------------------------------------------------------------

(defn- recorder
  "An injected dispatch target plus the vector of events it received."
  []
  (let [seen (atom [])]
    {:dispatch #(swap! seen conj %) :seen seen}))

;; ---------------------------------------------------------------------------
;; FH-EVENT-001 — the projection roster and the one pure materializer
;; ---------------------------------------------------------------------------

(def event-001 (conf/fixture :FH-EVENT-001))

(deftest fh-event-001-the-projection-roster-is-closed-and-exactly-these-members
  (testing "Per FH-EVENT-001: the reserved roster is CLOSED, and its
            membership is the whole grammar — there is no escape hatch
            that reads an arbitrary host property, because a projected
            read is only assertable, printable and host-neutral while
            the set of markers is finite and named. Equality in BOTH
            directions is the claim: a member silently dropped and a
            member silently smuggled in are the same defect."
    (is (= (:roster event-001) v/projections)
        "the published roster is exactly the fixture's roster")
    (is (= #{:re-frame.freehand/value
             :re-frame.freehand/checked
             :re-frame.freehand/key
             :re-frame.freehand/scroll-top
             :re-frame.freehand/new-state}
           v/projections)
        "value, checked, key, scroll offset, and a top-layer new state")))

(deftest fh-event-001-the-roster-materializes-from-the-live-payload
  (testing "Per FH-EVENT-001: `::v/value`, `::v/checked`, `::v/key`,
            `::v/scroll-top` and `::v/new-state` are replaced in
            TOP-LEVEL argument positions from the live
            payload, every occurrence, and a marker nested in another
            value stays ordinary application data. The vector that
            reaches re-frame is plain."
    (is (seq (:materialized event-001)) "the fixture's materialized table loaded")
    (let [payload (:payload event-001)]
      (doseq [{:keys [event expect]} (:materialized event-001)]
        (is (= expect (v/materialize-event event payload))
            (str "materializes " (pr-str event)))))))

(deftest fh-event-001-an-unprojected-event-is-returned-identical
  (testing "Per FH-EVENT-001: an event carrying no marker is returned
            UNCHANGED — identical, not merely equal — so a site with no
            projection allocates nothing and its intent compares equal
            across renders."
    (let [event [:cart/add 42]]
      (is (identical? event (v/materialize-event event (:payload event-001)))))))

(deftest fh-event-001-the-materializer-projects-at-firing-time
  (testing "Per FH-EVENT-001: projection reads the payload the CALLBACK
            supplies, never a render-captured value. The same event
            vector materializes differently under two payloads, which is
            what makes the intent a value the view can hold."
    (let [event [:form/edit :email :re-frame.freehand/value]]
      (is (= [:form/edit :email "first"]
             (v/materialize-event event {:re-frame.freehand/value "first"})))
      (is (= [:form/edit :email "second"]
             (v/materialize-event event {:re-frame.freehand/value "second"}))))))

(deftest fh-event-001-malformed-intent-is-rejected
  (testing "Per FH-EVENT-001: position zero is an event ID and may not be
            a projection marker; a vector of event vectors is the
            multi-intent mistake; and a non-vector never reaches
            dispatch."
    (is (seq (:rejected event-001)))
    (doseq [{:keys [event error-id]} (:rejected event-001)]
      (is (= error-id (conf/caught-id #(v/materialize-event event (:payload event-001))))
          (str "rejects " (pr-str event))))))

(deftest fh-event-001-an-unavailable-payload-dispatches-nothing
  (testing "Per FH-EVENT-001: a site asking for a projection its callback
            does not carry is a TYPED error with no dispatch. A
            malformed event vector reaching a handler is worse than
            none, and a silent `nil` argument is the failure that costs
            an afternoon."
    (is (seq (:unavailable event-001)))
    (doseq [{:keys [event payload error-id]} (:unavailable event-001)]
      (is (= error-id (conf/caught-id #(v/materialize-event event payload)))
          (str "rejects " (pr-str event) " under " (pr-str payload))))))

;; ---------------------------------------------------------------------------
;; rf2-drpa3.162 — the one-reader/one-law joins, through the PUBLIC
;; materializer (both hosts)
;; ---------------------------------------------------------------------------

(deftest a-missing-projection-diagnoses-over-heterogeneous-markers
  (testing "Per rf2-drpa3.162: a payload's markers are heterogeneous by
            construction — a named member is a keyword, a general door is a
            vector — and those do not compare. Diagnosing a missing projection
            from such a payload must stay the TYPED
            :rf.error/view-missing-payload, reporting what IS available; a raw
            host comparison error escaping here would replace the one
            diagnostic an author can act on with one they cannot."
    (let [payload {[:re-frame.freehand/read :key] "K"
                   :re-frame.freehand/value       "V"}]
      (is (= :rf.error/view-missing-payload
             (conf/caught-id
              #(v/materialize-event [:probe [:re-frame.freehand/read :key]
                                     [:re-frame.freehand/read :missing]]
                                    payload)))
          "the missing door is a typed error, not a ClassCastException")
      (is (= :rf.error/view-missing-payload
             (conf/caught-id
              #(v/materialize-event [:probe :re-frame.freehand/checked] payload)))
          "and so is a missing NAMED member over the same mixed payload")
      ;; The available roster is still reported, and in a stable order — a
      ;; diagnostic that named nothing would be no better than the throw.
      (is (= [:re-frame.freehand/value [:re-frame.freehand/read :key]]
             (:available (conf/caught-data
                          #(v/materialize-event
                            [:probe [:re-frame.freehand/read :missing]]
                            payload))))
          "both marker shapes are reported, ordered by their printed form"))))

(deftest both-spellings-accept-and-refuse-the-same-domain
  (testing "Per rf2-drpa3.162: `::v/value` IS `[::v/read [:target :value]]`, so
            the two spellings are ONE law. A named marker that dispatched a host
            object or a collection while its own expansion refused one would
            make the roster a second mechanism wearing the door's name."
    (doseq [[label named door] [["value"     :re-frame.freehand/value
                                 [:re-frame.freehand/read [:target :value]]]
                                ["checked"   :re-frame.freehand/checked
                                 [:re-frame.freehand/read [:target :checked]]]
                                ["key"       :re-frame.freehand/key
                                 [:re-frame.freehand/read [:key]]]]]
      (testing label
        (is (= [:probe "scalar"]
               (v/materialize-event [:probe named] {named "scalar"})
               (v/materialize-event [:probe door] {door "scalar"}))
            "both spellings substitute a shallow scalar")
        (doseq [[shape v] [["a map"        {:host "object"}]
                           ["a vector"     [1 2]]
                           ["a nil"        nil]]]
          (is (= :rf.error/view-bad-event
                 (conf/caught-id #(v/materialize-event [:probe named] {named v})))
              (str "the named marker refuses " shape))
          (is (= :rf.error/view-bad-event
                 (conf/caught-id #(v/materialize-event [:probe door] {door v})))
              (str "and so does the door, identically, for " shape)))))))

(deftest fh-event-001-general-dispatch-gains-no-payload-arity
  (testing "Per FH-EVENT-001: projection belongs to the layer that
            understands UI callback payloads. General re-frame dispatch
            takes an event vector and an optional opts map — no third
            payload argument, in either entry point — so a projection
            keyword travelling in an ordinary domain event is never
            secretly interpreted."
    (let [expected (:general-dispatch-arities event-001)]
      (is (seq expected) "the fixture's arity table loaded")
      (is (= (get expected "re-frame.router/dispatch!")
             (arity-set router/dispatch!)))
      (is (= (get expected "re-frame.router/dispatch-sync!")
             (arity-set router/dispatch-sync!))))))

#?(:clj
   (deftest fh-event-001-the-published-dispatch-macros-declare-two-arglists
     (testing "Per FH-EVENT-001: the published `rf/dispatch` and
               `rf/dispatch-sync` macros declare exactly the two
               arglists. Macros expand on the JVM for BOTH compilation
               targets, so this pins the surface a ClojureScript caller
               sees as well."
       (let [expected (:general-dispatch-arglists event-001)]
         (is (seq expected))
         (doseq [[qualified arglists] expected]
           (let [[ns-part var-part] (str/split qualified #"/")
                 v (ns-resolve (symbol ns-part) (symbol var-part))]
             (is (some? v) (str qualified " is published"))
             (is (true? (boolean (:macro (meta v)))) (str qualified " is a macro"))
             (is (= arglists (vec (:arglists (meta v))))
                 (str qualified " declares exactly " (pr-str arglists)))))))))

;; ---------------------------------------------------------------------------
;; FH-EVENT-002 — one event or nil, and the closed listener options
;; ---------------------------------------------------------------------------

(def event-002 (conf/fixture :FH-EVENT-002))

(def ^:private plan-forms
  "The values the FH-EVENT-002 `:form` tokens name. Written here rather
  than in the fixture because several of them are functions, which EDN
  cannot carry."
  {:event-vector            [:cart/add 7]
   :options-plain           {:event [:article/save 3]}
   :options-prevent-default {:event [:article/save 3] :prevent-default true}
   :options-every-blocking-option {:event [:picker/move 1] :prevent-default true
                                   :stop-propagation true :once true :capture true}
   :options-every-passive-option  {:event [:picker/move 1] :stop-propagation true
                                   :once true :passive true :capture true}
   :options-passive-prevent-default {:event [:picker/move 1] :passive true
                                     :prevent-default true}
   :options-false-options   {:event [:article/save 3] :prevent-default false
                             :stop-propagation false :once false :passive false :capture false}
   :nil                     nil
   :options-unknown-key     {:event [:article/save 3] :prevent-defualt true}
   :options-no-event        {:prevent-default true}
   :options-event-not-vector {:event :article/save}
   :options-string-key      {"Enter" [:picker/accept]}
   :options-empty           {}
   :string                  "cart/add"
   :number                  42
   :keyword                 :cart/add
   :set                     #{:cart/add}})

(deftest fh-event-002-the-closed-options-roster-normalizes
  (testing "Per FH-EVENT-002: an event position normalizes into one site
            plan both emitters and the structural host read. A listener
            option that was authored `false` is simply absent from the
            plan, so the smallest plan that can compare equal is what an
            unadorned site yields."
    (is (seq (:plans event-002)))
    (doseq [{:keys [form plan]} (:plans event-002)]
      (is (contains? plan-forms form) (str "the suite carries a value for " form))
      (is (= plan (events/event-plan (get plan-forms form)))
          (str "plan for " form)))))

(deftest fh-event-002-an-unknown-listener-option-is-rejected
  (testing "Per FH-EVENT-002: the options roster is CLOSED. A typo'd
            option that silently did nothing would be an event site that
            looks correct and is not — the failure this reject exists to
            remove. A MAP at an event position is that options map and
            nothing else (D007, discharged DELETE), so a string key is an
            unknown option like any other and an empty map is an options
            map missing its `:event` — neither is re-read as a second
            grammar."
    (is (seq (:rejected-plans event-002)))
    (doseq [{:keys [form error-id]} (:rejected-plans event-002)]
      (is (contains? plan-forms form) (str "the suite carries a value for " form))
      (is (= error-id (conf/caught-id #(events/event-plan (get plan-forms form))))
          (str "rejects " form)))))

(deftest fh-event-002-a-string-keyed-map-on-a-key-listener-is-rejected
  (testing "Per FH-EVENT-002, at the SITE rather than the planner: the
            exact spelling the closed key-condition form used to claim —
            `{\"Enter\" [:picker/accept]}` on `:on-key-down` — is refused
            with the ordinary bad-event diagnostic and mints no proxy.
            NON-VACUOUS in the direction that matters: the reject is the
            unknown-option one, so the map is read as the options map it
            is and never silently reinterpreted or passed through."
    (let [owner (events/owner :app/picker)
          cand  (events/candidate owner)
          run   #(events/site cand :on-key-down {"Enter" [:picker/accept]}
                              events/payload-map
                              {:tag :input :controlled? false :slot "onKeyDown"})]
      (is (= :rf.error/view-bad-event (conf/caught-id run)))
      (is (= :use-the-closed-listener-options (:recovery (conf/caught-data run)))
          "the ordinary closed-roster recovery, not a key-condition one")
      (is (= ["Enter"] (:unknown-keys (conf/caught-data run)))
          "the string key is reported as the unknown OPTION it is"))))

(v/defview menu-with-a-key-condition-map [_]
  [:ul {:on-key-down {"Enter" [:menu/activate] "Escape" [:menu/close]}} [:li "Open"]])

(defn- structural-key-down-verdict
  "The STRUCTURAL render's verdict on the same non-roster map, taken through
  the public `t/render` a consumer's own structural test calls."
  []
  #(t/render [menu-with-a-key-condition-map {}]))

(deftest fh-event-002-the-structural-render-classifies-a-map-at-an-event-position
  (testing "Per FH-EVENT-002, whose modes are `common jvm browser`: the
            STRUCTURAL render reaches the same verdict as a committed site
            for the same authored map, because it consults the same
            `events/event-plan` roster rather than recording whatever it
            was handed.

            This is the tier a consumer TESTS on. It used to record a
            non-roster map verbatim while the mounted walk raised
            `:rf.error/view-bad-event` and the compiled build raised
            `:rf.ui.compile/bad-handler-options` for that same
            declaration — so a green `.cljc` structural suite certified a
            view neither shipping tier would accept, which is exactly how
            the deleted D007 key-condition map survived in the guide
            (rf2-5xjxj)."
    (let [run (structural-key-down-verdict)]
      (is (= :rf.error/view-bad-event (conf/caught-id run))
          "the structural render refuses it")
      (is (= :use-the-closed-listener-options (:recovery (conf/caught-data run)))
          "with the closed-roster recovery, not a structural one")
      (is (= ["Enter" "Escape"] (:unknown-keys (conf/caught-data run)))
          "naming the string keys as the unknown OPTIONS they are"))))

(deftest fh-event-002-the-structural-and-mounted-tiers-agree-on-one-map
  (testing "Per FH-EVENT-002: the same authored value gets the SAME verdict
            whichever tier reads it. Asserted as an EQUALITY between the two
            tiers rather than as two independent expectations, so the claim
            is agreement itself — a later change that moved one diagnostic
            without the other fails here even if both are individually
            defensible."
    (let [value      {"Enter" [:menu/activate] "Escape" [:menu/close]}
          mounted    #(events/site (events/candidate (events/owner :app/menu))
                                   :on-key-down value events/payload-map
                                   {:tag :ul :controlled? false :slot "onKeyDown"})
          structural (structural-key-down-verdict)]
      (is (= (conf/caught-id mounted) (conf/caught-id structural))
          "one error id across the two tiers")
      (is (= (:recovery (conf/caught-data mounted))
             (:recovery (conf/caught-data structural)))
          "one recovery across the two tiers")
      (is (= (:unknown-keys (conf/caught-data mounted))
             (:unknown-keys (conf/caught-data structural)))
          "and one report of what was wrong"))))

(defn- compiled-reject-id
  "The COMPILED tier's verdict on an authored body — the
  `:rf.ui.compile/error` id, or nil when it compiles. The analyzer is pure
  and its resolution injected, so this reads the same on both hosts."
  [form]
  (try
    (ana/analyze (mk-env) form)
    nil
    (catch #?(:clj clojure.lang.ExceptionInfo :cljs cljs.core/ExceptionInfo) ex
      (:rf.ui.compile/error (ex-data ex)))))

(deftest fh-event-002-all-three-tiers-refuse-one-contradictory-options-map
  (testing "Per FH-EVENT-002: `:passive` and `:prevent-default` are BOTH
            roster members and contradict each other — a passive listener
            promises the browser it will never call `preventDefault`, so one
            of the two options would silently do nothing. Membership is not
            the whole law, and the roster alone cannot see this.

            The compiled tier always refused it, saying the combination is a
            contradiction `in any stage`. The other two accepted it, so a
            structural test and a development mount certified a declaration
            a release build could not compile — the mirror image of
            rf2-5xjxj, and the same class (rf2-uvcm3). The verdict now lives
            in `events/options-plan`, the ONE classifier the mounted and
            structural tiers both ask, so no third opinion was added to fix
            a disagreement between two.

            Asserted across all three tiers in one test, because the claim
            IS the agreement: a later change that moved one verdict without
            the others fails here even if each is individually defensible."
    (let [value      {:event [:picker/move 1] :passive true :prevent-default true}
          plan       #(events/event-plan value)
          mounted    #(events/site (events/candidate (events/owner :app/picker))
                                   :on-wheel value events/payload-map
                                   {:tag :div :controlled? false :slot "onWheel"})
          structural #(t/render [:div {:on-wheel value}])]
      (is (= :rf.error/view-bad-event (conf/caught-id plan))
          "the canonical plan refuses it")
      (is (= :drop-passive-or-prevent-default (:recovery (conf/caught-data plan)))
          "naming the one fix — drop one of the two")
      (is (= (conf/caught-id plan) (conf/caught-id mounted))
          "one error id at a committed site")
      (is (= (conf/caught-id plan) (conf/caught-id structural))
          "and the same id through the structural render a consumer tests on")
      (is (= :rf.ui.compile/contradictory-handler-options
             (compiled-reject-id '[:div {:on-wheel {:event [:picker/move 1]
                                                    :passive true
                                                    :prevent-default true}}]))
          "the compiled tier refuses it at BUILD, under its own build-time id")
      (is (nil? (compiled-reject-id '[:div {:on-wheel {:event [:picker/move 1]
                                                       :passive true}}]))
          "NON-VACUITY: passive alone still compiles")
      (is (= conf/no-throw
             (conf/caught-id #(t/render [:div {:on-wheel {:event [:picker/move 1]
                                                          :passive true}}])))
          "and passive alone still renders"))))

(deftest fh-event-002-the-structural-render-still-records-a-roster-options-map
  (testing "NON-VACUITY for the two rows above: the structural render
            refuses a NON-ROSTER map and nothing more. A legal options map
            still renders, and still records VERBATIM — the authored map,
            not the normalized plan, which is the tree FH-STRUCT-002 pins
            and the value a promoted declaration is compared against."
    (let [options {:event [:cart/checkout] :prevent-default true}
          tree    (t/render [:form {:on-submit options}])]
      (is (= options (:on-submit (:events tree)))
          "the authored options map is recorded exactly as written")
      (is (= conf/no-throw
             (conf/caught-id #(t/render [:button {:on-click [:cart/open 7]}])))
          "and an ordinary event vector still renders"))))


(deftest fh-event-002-a-site-yields-one-event-or-nil
  (testing "Per FH-EVENT-002: a committed site's callback yields exactly
            one event vector or `nil`. `nil` dispatches NOTHING — that
            is how a callback declines without a second control channel
            — and this runs through the real committed proxy, not a
            direct call to the body."
    (is (seq (:outcomes event-002)))
    (doseq [{:keys [returns dispatched]} (:outcomes event-002)]
      (let [{:keys [dispatch seen]} (recorder)
            owner (events/owner :app/picker)
            cand  (events/candidate owner)
            proxy (events/site cand :on-click (v/event [_] returns) events/payload-map)]
        (events/commit! cand dispatch)
        (proxy {})
        (is (= dispatched @seen) (str "returning " (pr-str returns)))))))

(deftest fh-event-002-a-site-yielding-anything-else-is-rejected
  (testing "Per FH-EVENT-002: anything that is neither one event vector
            nor `nil` is a loud diagnostic. A vector of event vectors is
            named explicitly: multi-step work is one semantic event whose
            handler returns the effects, keeping one inspectable causal
            unit instead of a miniature dispatcher in the view."
    (is (seq (:rejected-outcomes event-002)))
    (doseq [{:keys [returns error-id]} (:rejected-outcomes event-002)]
      (let [{:keys [dispatch seen]} (recorder)
            owner (events/owner :app/picker)
            cand  (events/candidate owner)
            proxy (events/site cand :on-click (v/event [_] returns) events/payload-map)]
        (events/commit! cand dispatch)
        (is (= error-id (conf/caught-id #(proxy {}))) (str "rejects " (pr-str returns)))
        (is (= [] @seen) "and dispatches nothing")))))

(deftest fh-event-002-once-is-site-state-retained-across-commits
  (testing "Per FH-EVENT-002: `:once` retires the site's intent after one
            firing, and the consumed state survives re-render — it is
            state of the SITE, not of the render that published it."
    (let [{:keys [firings dispatched]} (:once event-002)
          {:keys [dispatch seen]}      (recorder)
          owner (events/owner :app/boot)
          value {:event [:boot/ready] :once true}]
      (is (pos? firings) "the fixture names a firing count")
      (dotimes [_ firings]
        (let [cand  (events/candidate owner)
              proxy (events/site cand :on-ready value events/payload-map)]
          (events/commit! cand dispatch)
          (proxy {})))
      (is (= dispatched @seen)))))

(deftest fh-event-002-options-run-their-mechanics-then-dispatch
  (testing "Per FH-EVENT-002: an options map states one intent plus
            shallow listener options; the selected browser mechanics run
            BEFORE dispatch. The structural host fires no native event,
            so it has no mechanics to run — the options still normalize
            and still ride the plan, which is what keeps one shape
            across hosts."
    (let [{:keys [dispatch seen]} (recorder)
          owner (events/owner :app/article)
          cand  (events/candidate owner)
          proxy (events/site cand :on-submit
                             {:event [:article/save 3] :prevent-default true}
                             events/payload-map)]
      (events/commit! cand dispatch)
      #?(:cljs (let [calls (atom [])
                     e     #js {:preventDefault  #(swap! calls conj :prevent-default)
                                :stopPropagation #(swap! calls conj :stop-propagation)}]
                 (proxy e)
                 (is (= [:prevent-default] @calls) "prevent-default ran, stop-propagation did not"))
         :clj  (proxy {}))
      (is (= [[:article/save 3]] @seen)))))

;; ---------------------------------------------------------------------------
;; FH-EVENT-003 — the closed callback roster
;; ---------------------------------------------------------------------------

(def event-003 (conf/fixture :FH-EVENT-003))

(defn- roster-forms
  "The values the FH-EVENT-003 `:form` tokens name. A thunk, because the
  fn-carrying forms must be freshly built per assertion."
  [calls]
  {:event-vector  [:cart/add 7]
   :event-options {:event [:cart/add 7] :prevent-default true}
   :v-event       (v/event [n] (swap! calls conj :v-event) [:roster/converted n])
   :v-handler     (v/handler [_] (swap! calls conj :v-handler) [:not/dispatched])
   :v-render-fn   (v/render-fn [_] [:span "row"])
   :v-raw-fn      (v/raw-fn (fn [_] (swap! calls conj :v-raw-fn)))
   :bare-fn       (fn [_] (swap! calls conj :bare-fn))
   :nil           nil
   :string        "on-click"
   :number        42
   :keyword       :on-click
   :set           #{[:cart/add 7]}})

(deftest fh-event-003-the-declared-roster-is-closed
  (testing "Per FH-EVENT-003: the roster of DECLARED callback forms is
            closed — `v/event`, `v/handler`, `v/render-fn`, `v/raw-fn`.
            There is no `v/dispatcher`: appending a raw callback argument
            to an intent vector is exactly how host objects get into
            event data, and `v/event` is the conversion seam that does
            not."
    (is (= (:declared-roles event-003) events/callback-roles))
    (doseq [role (:declared-roles event-003)]
      (is (contains? (:roles event-003) role)
          (str role " is a role classification answers with")))))

(deftest fh-event-003-classification-is-total-over-the-roster
  (testing "Per FH-EVENT-003: every value in an event position resolves
            to exactly one role, and the answer set is exactly the
            roster. A bare function stays legal at a native `:on-*` site
            because the site's own committed adapter owns its lifetime."
    (is (seq (:classified event-003)))
    (let [forms (roster-forms (atom []))]
      (doseq [{:keys [form role]} (:classified event-003)]
        (is (contains? forms form) (str "the suite carries a value for " form))
        (is (= role (:role (events/event-plan (get forms form))))
            (str form " classifies as " role))
        (when role
          (is (contains? (:roles event-003) role)
              (str role " is in the closed role set")))))))

(deftest fh-event-003-a-value-outside-the-roster-is-rejected
  (testing "Per FH-EVENT-003: a value outside the roster is rejected
            naming the legal forms. Guessing at an unknown callback
            shape is how a phase and identity contract becomes implicit,
            which is the mistake the roster exists to prevent."
    (is (seq (:rejected event-003)))
    (let [forms (roster-forms (atom []))]
      (doseq [{:keys [form error-id]} (:rejected event-003)]
        (is (contains? forms form) (str "the suite carries a value for " form))
        (is (= error-id (conf/caught-id #(events/event-plan (get forms form))))
            (str "rejects " form))))))

(deftest fh-event-003-render-fn-and-raw-fn-are-outside-the-proxy-scheme
  (testing "Per FH-EVENT-003: `v/render-fn` can run during an
            uncommitted candidate render and `v/raw-fn` exists precisely
            because the caller owns the identity, so neither takes a
            site-owned committed proxy. `v/raw-fn` hands back EXACTLY
            the supplied function."
    (is (= #{:render-fn :raw-fn} (:no-slot-roles event-003)))
    (let [owner (events/owner :app/list)
          cand  (events/candidate owner)
          f     (fn [_] :raw)
          rf    (v/render-fn [_] [:span "row"])]
      (is (identical? f (events/site cand :on-pick (v/raw-fn f) events/payload-map))
          "v/raw-fn is passed through with its exact identity")
      (is (identical? (events/callback-fn rf) (events/site cand :render-item rf events/payload-map))
          "v/render-fn is passed through, not proxied"))))

(deftest fh-event-003-each-fn-role-does-its-own-job-when-fired
  (testing "Per FH-EVENT-003: the roles differ in what firing MEANS. A
            `v/event` body's vector dispatches; a `v/handler`'s return is
            imperative and dropped; a bare function is invoked and
            dispatches nothing of its own."
    (is (seq (:invocations event-003)))
    (doseq [{:keys [form dispatched called]} (:invocations event-003)]
      (let [calls (atom [])
            forms (roster-forms calls)
            {:keys [dispatch seen]} (recorder)
            owner (events/owner :app/roster)
            cand  (events/candidate owner)
            proxy (events/site cand :on-pick (get forms form) events/payload-map)]
        (events/commit! cand dispatch)
        (proxy 1)
        (is (= dispatched @seen) (str form " dispatched"))
        (is (= called (count @calls)) (str form " invoked its body " called " time(s)"))))))

;; ---------------------------------------------------------------------------
;; rf2-drpa3.138 — a v/render-fn's carried arity is HONEST
;; ---------------------------------------------------------------------------

(deftest render-fn-fixed-arity-is-recorded-honestly
  (testing "rf2-drpa3.138. A v/render-fn carries its declared arity so v/slot
            enforces one host-independent contract. A FIXED parameter vector
            records exactly its positional count — the arity the generated
            function truly accepts — and v/slot honours it. (Host-neutral: the
            accepted forms build a real Callback in both modes.)"
    (is (= 0 (events/callback-arity (v/render-fn [] [:span "x"]))) "nullary → 0")
    (is (= 1 (events/callback-arity (v/render-fn [x] [:span x]))) "unary → 1")
    (is (= 2 (events/callback-arity (v/render-fn [x y] [:span x y]))) "two params → 2")
    (is (= 1 (events/callback-arity (v/render-fn [{:keys [a]}] [:span a])))
        "a destructured parameter is still ONE positional argument → 1")
    (let [rf (v/render-fn [x] [:span x])]
      (is (nil? (events/check-slot-arity! rf 1))
          "a one-argument v/slot call matches a one-parameter render-fn")
      (is (= :rf.error/ui-tree-malformed
             (conf/caught-id #(events/check-slot-arity! rf 2)))
          "and a two-argument call to it is the didactic arity mismatch"))))

#?(:clj
   (deftest a-variadic-render-fn-is-refused-on-the-interpreted-path
     (testing "rf2-drpa3.138. `[x & xs]` is NOT a fixed arity: the function
               accepts one-or-more arguments, so recording `(count params)` = 3
               is a FALSE arity — a valid one-argument v/slot call would then be
               refused as `expected 3, actual 1`. The interpreted authoring path
               refuses variadic & didactically, the SAME verdict the compiled
               analyzer gives (`:rf.ui.compile/bad-render-fn`, exercised in
               `analyze-reject-cljs-test`), rather than carrying a contract the
               function does not honour."
       ;; `v/render-fn` is a thin macro over `expand-callback` (freehand.cljc),
       ;; so its rejection IS this expansion refusing to build the callback —
       ;; tested directly because `macroexpand` wraps a macro's throw and hides
       ;; the diagnostic id under the wrapper's cause.
       (is (= :rf.error/view-bad-event
              (conf/caught-id
                #(events/expand-callback :render-fn 'v/render-fn '[x & xs] '([:span x xs]))))
           "a variadic render-fn is refused at authoring, not recorded as arity 3")
       (is (thrown-with-msg?
             clojure.lang.ExceptionInfo #"FIXED arg list"
             (events/expand-callback :render-fn 'v/render-fn '[x & xs] '([:span x xs])))
           "expand-callback refuses to build the callback, so the false arity-3
            record cannot silently return")
       (testing "the refusal is render-fn's alone — v/event and v/handler have
                 no slot-arity contract, so their variadic forms are untouched"
         (is (some? (events/expand-callback :handler 'v/handler '[x & xs] '([x xs])))
             "a variadic v/handler still expands")
         (is (some? (events/expand-callback :event 'v/event '[x & xs] '(nil)))
             "and so does a variadic v/event")))))

;; ---------------------------------------------------------------------------
;; rf2-yn5nj — an invoked carrier NAMES the fix
;; ---------------------------------------------------------------------------
;;
;; A roster carrier is deliberately not the function it wraps, so a foreign API
;; handed one fails at the call rather than silently doing the wrong thing. That
;; much was already true. What the failure SAID was the defect: the hosts answer
;; a direct call with `cb.call is not a function` / a raw `ClassCastException`,
;; naming neither the carrier nor what to write instead — and the one position
;; that produces it, a carrier authored into a foreign element's raw
;; `createElement` `#js` props, was PRESCRIBED by the guide for months precisely
;; because the failure was mute (rf2-c1vvn).

(defn- invoke-as-fn
  "Invoke `f` as a function with `args`, from Clojure on one host and
  ClojureScript on the other. The head is bound as a local so neither compiler
  can fold the call site into a compile-time diagnostic.

  What this is NOT is a NATIVE JavaScript call, and the distinction is the
  subject of `MERGED-PR AUDIT #7034`. On the JVM `clojure.lang.IFn` is the
  host's whole call protocol, so this call IS every caller there is. In
  ClojureScript it compiles to the carrier's `-invoke` arity or its `.call`
  shim — neither of which a library's `props.onPing(x)` goes through. That
  boundary has its own row, in
  `re-frame.freehand.pilot-react-interop-dom-cljs-test`, where a caller
  authored in JavaScript can express the call this one cannot."
  [f & args]
  (apply f args))

(deftest an-invoked-carrier-is-didactic-on-both-hosts
  (testing "rf2-yn5nj. Every declared role answers a direct call — from
            Clojure on the JVM, from ClojureScript in the browser — with the
            typed `:rf.error/view-bad-event`, and the message names the three
            things a raw host TypeError cannot: the roster form that was
            invoked, the position class that produces the mistake, and the
            recovery to write instead."
    (doseq [role events/callback-roles]
      (let [carrier (events/callback role (fn [_] :body) 1)]
        (is (events/callback? carrier) (str role " — the subject is a carrier"))
        (is (not (fn? carrier))
            (str role " — and is still NOT the bare function it wraps, on either host"))
        (is (= :rf.error/view-bad-event
               (conf/caught-id #(invoke-as-fn carrier :payload)))
            (str role " — invoking it raises the typed diagnostic"))
        (let [message (conf/caught-message #(invoke-as-fn carrier :payload))]
          (is (str/includes? message (str "v/" (name role)))
              (str role " — the message names the roster form that was invoked"))
          (is (str/includes? message "createElement")
              (str role " — and the position class Freehand does not walk"))
          (is (str/includes? message "capture-frame")
              (str role " — and the closure to write instead")))
        (let [data (conf/caught-data #(invoke-as-fn carrier :payload))]
          (is (= role (:role data))
              (str role " — a tool reads the role off the ex-data, not the prose"))
          (is (= :close-over-capture-frame-dispatch (:recovery data))
              (str role " — and the recovery is a machine-readable disposition"))
          (is (= (symbol "v" (name role)) (:where data))
              (str role " — and `where` greps to the authoring form")))))))

(deftest no-call-arity-escapes-the-carrier-diagnostic
  (testing "rf2-yn5nj. The carrier is wired to the WHOLE host call protocol,
            not the one arity a realistic mistake uses: a skipped arity would
            fall through to the host's own `AbstractMethodError` /
            `Invalid arity`, which is the mute message this removes,
            reintroduced somewhere else. `events/call-protocol` is the roster's
            one authority and `FH-CALL-001` pins its completeness for the
            descriptor that shares it; what is proven here is that the carrier
            reaches it at every kind of position in the roster — none, one, a
            few, and the last fixed arity the protocol declares."
    (let [carrier (events/callback :event (fn [_] nil) 1)]
      (doseq [n [0 1 2 3 20]]
        (is (= :rf.error/view-bad-event
               (conf/caught-id #(apply invoke-as-fn carrier (repeat n :x))))
            (str "arity " n " raises the didactic diagnostic")))
      (is (not= conf/no-throw
                (conf/caught-id #(apply invoke-as-fn carrier (repeat 40 :x))))
          "past the host's own call-protocol ceiling the call still cannot
           SUCCEED — only the message becomes the host's"))))

(deftest a-carrier-is-ifn-and-nothing-classifies-on-that
  (testing "rf2-yn5nj. Implementing the call protocol makes a carrier `ifn?`
            on both hosts — exactly as it does for a declared view — and that
            is a fact about callability, never a classification. Every reader
            that decides what a value IS asks a nominal predicate, and the two
            readers that did ask about callability name the carrier."
    (let [carrier (v/handler [_] :imperative)]
      (is (ifn? carrier)
          "the carrier implements the call protocol, so `ifn?` is TRUE")
      (is (= :handler (:role (events/event-plan carrier)))
          "an event position still classifies it by ROLE — `callback?` is asked
           before `fn?`, so the carrier never falls through to :bare-fn")
      (is (= :rf.error/view-bad-head
             (conf/caught-id #(descriptor/classify-head carrier)))
          "a head is still refused")
      (is (not (str/includes? (conf/caught-message #(descriptor/classify-head carrier))
                              "A plain function is never an internal vector head"))
          "and not as a plain function — that recovery is not the carrier's, so
           the head diagnostic does not offer it")
      (is (= :rf.error/behavior-bad-args
             (conf/caught-id
               #(behaviors/register! ::carrier-in-a-lifecycle-slot {:connect carrier})))
          "and a behavior lifecycle slot still refuses it AT REGISTRATION rather
           than deferring to the connect that would call it")
      (is (not (contains? (behaviors/registered-ids) ::carrier-in-a-lifecycle-slot))
          "non-vacuous: the refused registration installed nothing"))))

;; ---------------------------------------------------------------------------
;; FH-EVENT-004 — per-site committed slots and stable identity
;; ---------------------------------------------------------------------------

(def event-004 (conf/fixture :FH-EVENT-004))

(deftest fh-event-004-a-site-owns-one-stable-committed-proxy
  (testing "Per FH-EVENT-004: an unchanged site keeps the EXACT callback
            across every re-render — the identity law that stops a
            re-render from churning callback identity through React
            reconciliation — while a later commit replaces the body
            behind it. An abandoned render publishes nothing, a
            retarget moves the destination without touching one
            identity, and retirement leaves the proxy callable but
            inert."
    (is (seq (:script event-004)) "the fixture's script loaded")
    (let [site-a  (:site-a event-004)
          site-b  (:site-b event-004)
          primary (recorder)
          retargeted-to (recorder)
          owner   (events/owner :app/cart)
          proxies (atom {})]
      (doseq [{:keys [value commit retarget retire fire dispatched retargeted
                      proxy-identity lifecycle] :as step}
              (:script event-004)]
        (if retire
          (events/retire! owner)
          (let [cand (events/candidate owner)
                pa   (events/site cand site-a value events/payload-map)
                pb   (events/site cand site-b value events/payload-map)]
            (when (= :unchanged proxy-identity)
              (is (identical? (get @proxies site-a) pa)
                  (str (:step step) ": site-a keeps its exact proxy"))
              (is (identical? (get @proxies site-b) pb)
                  (str (:step step) ": site-b keeps its exact proxy")))
            (is (not (identical? pa pb))
                (str (:step step) ": equal values at two sites get distinct proxies"))
            (swap! proxies assoc site-a pa site-b pb)
            (when commit
              (events/commit! cand (:dispatch (if retarget retargeted-to primary))))))
        (doseq [s fire]
          ((get @proxies (if (= s :site-a) site-a site-b)) {}))
        (when dispatched
          (is (= dispatched @(:seen primary)) (str (:step step) ": primary target")))
        (when retargeted
          (is (= retargeted @(:seen retargeted-to)) (str (:step step) ": retargeted")))
        (when lifecycle
          (is (= lifecycle (events/lifecycle owner)) (str (:step step) ": lifecycle")))))))

(deftest fh-event-004-equal-values-at-two-sites-stay-independent
  (testing "Per FH-EVENT-004: two sites holding an EQUAL authored value
            get two distinct proxies, so their lifetimes, `:once` state
            and diagnostics never merge. Equality of intent is not
            identity of site."
    (is (true? (:equal-values-are-independent event-004)))
    (let [{:keys [dispatch seen]} (recorder)
          owner (events/owner :app/cart)
          cand  (events/candidate owner)
          value {:event [:boot/ready] :once true}
          pa    (events/site cand :a value events/payload-map)
          pb    (events/site cand :b value events/payload-map)]
      (events/commit! cand dispatch)
      (is (not (identical? pa pb)))
      (pa {}) (pa {}) (pb {}) (pb {})
      (is (= [[:boot/ready] [:boot/ready]] @seen)
          "each site consumed its OWN :once, so two sites fired once each"))))

(deftest fh-event-004-a-retired-proxy-is-inert
  (testing "Per FH-EVENT-004: a retired proxy stays callable — a foreign
            listener may already hold one — and dispatches nothing
            rather than firing into whatever owns the node now. Inert,
            not exploding: the callback belongs to a view that is gone."
    (let [{:keys [dispatch seen]} (recorder)
          owner (events/owner :app/cart)
          cand  (events/candidate owner)
          proxy (events/site cand :on-click [:cart/add 1] events/payload-map)]
      (events/commit! cand dispatch)
      (proxy {})
      (events/retire! owner)
      (is (= :retired (events/lifecycle owner)))
      (is (nil? (proxy {})) "the retired proxy answers nil")
      (is (= [[:cart/add 1]] @seen) "and dispatched nothing further"))))

(deftest fh-event-004-a-site-dropped-by-a-later-render-is-inert
  (testing "Per FH-EVENT-004: a site the selected render no longer
            carries is retired with that commit. Its proxy is inert even
            though the OWNER is still connected — retirement is per
            site, which is what makes a keyed list's departing row stop
            dispatching without tearing down its siblings."
      (let [{:keys [dispatch seen]} (recorder)
            owner (events/owner :app/list)
            c1    (events/candidate owner)
            gone  (events/site c1 :row-1 [:row/pick 1] events/payload-map)
            kept  (events/site c1 :row-2 [:row/pick 2] events/payload-map)]
        (events/commit! c1 dispatch)
        (let [c2 (events/candidate owner)]
          (events/site c2 :row-2 [:row/pick 2] events/payload-map)
          (events/commit! c2 dispatch))
        (gone {})
        (kept {})
        (is (= [[:row/pick 2]] @seen)))))

(deftest fh-event-004-a-never-selected-candidates-proxy-is-not-a-doorway
  (testing "Per FH-EVENT-004: a candidate that is never selected
            publishes nothing — its CALLBACK included. Two candidates
            for a fresh owner mint two provisional proxies for one key;
            committing the second must not turn the first into an
            active doorway onto the selected body. Resolving an
            invocation by owner plus site key alone is exactly what
            would."
    (let [{:keys [site-key never-selected]} (:incarnation event-004)
          {:keys [abandoned selected dispatched]} never-selected
          {:keys [dispatch seen]} (recorder)
          owner (events/owner :app/cart)
          c1    (events/candidate owner)
          p1    (events/site c1 site-key abandoned events/payload-map)
          c2    (events/candidate owner)
          p2    (events/site c2 site-key selected events/payload-map)]
      (is (not (identical? p1 p2))
          "two candidates for an uncommitted key mint two distinct incarnations")
      (events/commit! c2 dispatch)
      (p1 {})
      (is (= [] @seen) "the abandoned candidate's proxy dispatches nothing")
      (p2 {})
      (is (= dispatched @seen)
          "and only the selected candidate's own proxy is live"))))

(deftest fh-event-004-a-retired-proxy-does-not-fire-into-a-successor
  (testing "Per FH-EVENT-004: retirement is permanent for the exact
            proxy. A committed site removed by a later render is inert;
            re-adding the same key mints a NEW incarnation, and the
            retired proxy a foreign listener still holds must stay
            inert rather than fire into whatever owns the key now."
    (let [{:keys [site-key retired-key-reuse]} (:incarnation event-004)
          {first-value   :first
           readded-value :readded
           dispatched    :dispatched} retired-key-reuse
          {:keys [dispatch seen]} (recorder)
          owner   (events/owner :app/list)
          c1      (events/candidate owner)
          retired (events/site c1 site-key first-value events/payload-map)]
      (events/commit! c1 dispatch)
      (retired {})
      (events/commit! (events/candidate owner) dispatch)
      (retired {})
      (is (= 1 (count @seen)) "the dropped site's proxy is inert while the key is absent")
      (let [c3        (events/candidate owner)
            successor (events/site c3 site-key readded-value events/payload-map)]
        (events/commit! c3 dispatch)
        (is (not (identical? retired successor))
            "re-adding a retired key mints a new incarnation")
        (retired {})
        (is (= 1 (count @seen))
            "and the retired proxy stays inert rather than firing into its successor")
        (successor {})
        (is (= dispatched @seen)
            "while the newly committed proxy dispatches normally")))))

;; ---------------------------------------------------------------------------
;; The one host-shaped seam — reading a live native event
;; ---------------------------------------------------------------------------

#?(:cljs
   (deftest fh-event-001-native-payload-reads-the-live-event
     (testing "Per FH-EVENT-001 (browser host): the ClojureScript
               adapter reads the closed scalar roster off the live event
               object — and nothing else. No DOM node, synthetic event
               or other host object enters the intent vector; a member
               the event does not carry is ABSENT, so asking a click for
               `::v/key` is a typed error rather than a silent nil."
       (is (= {:re-frame.freehand/value "mike@example.com"}
              (events/native-payload #js {:target #js {:value "mike@example.com"}})))
       (is (= {:re-frame.freehand/checked true}
              (events/native-payload #js {:target #js {:checked true}})))
       (is (= {:re-frame.freehand/value "" :re-frame.freehand/key "Enter"}
              (events/native-payload #js {:key "Enter" :target #js {:value ""}})))
       (is (= {} (events/native-payload #js {:target #js {}})))
       ;; The two demonstrated-need members, read from the same live
       ;; object under the same presence law. The scroll offset comes off
       ;; the TARGET, the toggle's new state off the EVENT — each is the
       ;; one host property its marker is named for, and the VALUE is the
       ;; assertion: a reader that returned the wrong scalar would still
       ;; produce a well-formed payload map.
       (is (= {:re-frame.freehand/scroll-top 3200}
              (events/native-payload #js {:target #js {:scrollTop 3200}}))
           "the viewport's own scroll offset, not a nested handle to it")
       (is (= {:re-frame.freehand/new-state "closed"}
              (events/native-payload #js {:newState "closed"}))
           "the toggle report's new state, verbatim from the platform")
       (is (= {:re-frame.freehand/new-state "open"}
              (events/native-payload #js {:newState "open" :target #js {}}))
           "and 'open' is the other half of the same vocabulary")
       (is (= {:re-frame.freehand/value ""
               :re-frame.freehand/scroll-top 0}
              (events/native-payload #js {:target #js {:value "" :scrollTop 0}}))
           "a zero offset is PRESENT — the target has a scroll offset and
            it is zero, which is a fact rather than an absence"))))

#?(:cljs
   (deftest fh-event-001-a-committed-site-materializes-from-a-live-event
     (testing "Per FH-EVENT-001 (browser host): end to end through the
               production path — a committed site fires with a live
               event object, the default extractor reads it, and the
               materialized plain vector is what reaches dispatch."
       (let [{:keys [dispatch seen]} (recorder)
             owner (events/owner :app/form)
             cand  (events/candidate owner)
             proxy (events/site cand :on-input [:account/email-edited :re-frame.freehand/value])]
         (events/commit! cand dispatch)
         (proxy #js {:target #js {:value "mike@example.com"}})
         (is (= [[:account/email-edited "mike@example.com"]] @seen)))
       (let [{:keys [dispatch seen]} (recorder)
             owner (events/owner :app/form)
             cand  (events/candidate owner)
             proxy (events/site cand :on-key-down [:picker/key-pressed :re-frame.freehand/key])]
         (events/commit! cand dispatch)
         (proxy #js {:key "Escape" :target #js {}})
         (is (= [[:picker/key-pressed "Escape"]] @seen)))
       (let [{:keys [dispatch seen]} (recorder)
             owner (events/owner :app/form)
             cand  (events/candidate owner)
             proxy (events/site cand :on-click [:picker/key-pressed :re-frame.freehand/key])]
         (events/commit! cand dispatch)
         (is (= :rf.error/view-missing-payload
                (conf/caught-id #(proxy #js {:target #js {}})))
             "a click carries no key, so asking for one is a typed error")
         (is (= [] @seen) "and nothing is dispatched"))
       ;; The two demonstrated-need members, end to end. The intent is a
       ;; VECTOR at the site — which is the whole point of admitting
       ;; them — so what reaches dispatch is plain data carrying the
       ;; live scalar, not an opaque callback marker.
       (let [{:keys [dispatch seen]} (recorder)
             owner (events/owner :acme/table)
             cand  (events/candidate owner)
             proxy (events/site cand :on-scroll
                     [:acme.table/scrolled [:ledger :q3] :re-frame.freehand/scroll-top])]
         (events/commit! cand dispatch)
         (proxy #js {:target #js {:scrollTop 3200}})
         (is (= [[:acme.table/scrolled [:ledger :q3] 3200]] @seen)))
       (let [{:keys [dispatch seen]} (recorder)
             owner (events/owner :acme/menu)
             cand  (events/candidate owner)
             proxy (events/site cand :on-toggle
                     [:acme.menu/toggle-reported :format :re-frame.freehand/new-state])]
         (events/commit! cand dispatch)
         (proxy #js {:newState "open" :target #js {}})
         (proxy #js {:newState "closed" :target #js {}})
         (is (= [[:acme.menu/toggle-reported :format "open"]
                 [:acme.menu/toggle-reported :format "closed"]]
                @seen)
             "each report carries its OWN state, so the handler never has
              to infer a dismissal by counting")))))

#?(:cljs
   (deftest fh-read-door-reads-the-live-event-through-the-production-path
     (testing "Per rf2-drpa3.162 (browser host): the general
               `[::v/read <path>]` door reads a shallow scalar off the live
               event object end to end — a keyword path off the event, a
               vector path walked as a chain from it — and materializes it
               into the dispatched vector. No reader conditional, no host
               object; the SCALAR is what reaches the handler."
       (let [{:keys [dispatch seen]} (recorder)
             owner (events/owner :acme/table)
             cand  (events/candidate owner)
             proxy (events/site cand :on-scroll
                     [:acme.table/scrolled [:ledger :q3]
                      [:re-frame.freehand/read [:target :scrollTop]]])]
         (events/commit! cand dispatch)
         (proxy #js {:target #js {:scrollTop 3200}})
         (is (= [[:acme.table/scrolled [:ledger :q3] 3200]] @seen)
             "a vector path walks event.target.scrollTop off the live event"))
       (let [{:keys [dispatch seen]} (recorder)
             owner (events/owner :acme/menu)
             cand  (events/candidate owner)
             proxy (events/site cand :on-toggle
                     [:acme.menu/toggle-reported :format
                      [:re-frame.freehand/read :newState]])]
         (events/commit! cand dispatch)
         (proxy #js {:newState "open" :target #js {}})
         (is (= [[:acme.menu/toggle-reported :format "open"]] @seen)
             "a keyword path reads event.newState directly"))
       ;; A read that lands on a HOST OBJECT is refused end to end, in
       ;; favour of v/event — the browser proof of the scalar law.
       (let [{:keys [dispatch seen]} (recorder)
             owner (events/owner :acme/table)
             cand  (events/candidate owner)
             proxy (events/site cand :on-scroll
                     [:acme/read [:re-frame.freehand/read :target]])]
         (events/commit! cand dispatch)
         (is (= :rf.error/view-bad-event
                (conf/caught-id #(proxy #js {:target #js {:scrollTop 0}})))
             "reading the whole target — a host object — is refused")
         (is (= [] @seen) "and nothing is dispatched")))))

#?(:cljs
   (deftest fh-read-door-is-the-live-counterpart-of-the-named-sugar
     (testing "Per rf2-drpa3.162 (browser host): the named roster is SUGAR
               over the general door. Fired against the SAME live event, a
               named marker and its `[::v/read <path>]` spelling reach
               dispatch with the identical scalar — one reader, one law,
               two spellings — which is the whole claim that the members
               are sugar rather than a private mechanism the door lacks."
       (letfn [(fire-one [intent e]
                 (let [{:keys [dispatch seen]} (recorder)
                       owner (events/owner :probe/owner)
                       cand  (events/candidate owner)
                       proxy (events/site cand :probe/site intent)]
                   (events/commit! cand dispatch)
                   (proxy e)
                   @seen))]
         (is (= (fire-one [:x :re-frame.freehand/scroll-top]
                          #js {:target #js {:scrollTop 512}})
                (fire-one [:x [:re-frame.freehand/read [:target :scrollTop]]]
                          #js {:target #js {:scrollTop 512}})
                [[:x 512]])
             "::v/scroll-top is [::v/read [:target :scrollTop]]")
         (is (= (fire-one [:x :re-frame.freehand/new-state]
                          #js {:newState "open"})
                (fire-one [:x [:re-frame.freehand/read :newState]]
                          #js {:newState "open"})
                [[:x "open"]])
             "::v/new-state is [::v/read :newState]")
         (is (= (fire-one [:x :re-frame.freehand/value]
                          #js {:target #js {:value "hi"}})
                (fire-one [:x [:re-frame.freehand/read [:target :value]]]
                          #js {:target #js {:value "hi"}})
                [[:x "hi"]])
             "::v/value is [::v/read [:target :value]]")))))

#?(:cljs
   (deftest fh-read-door-in-a-v-event-result-reads-the-live-argument
     (testing "Per rf2-drpa3.162 (browser host): the public contract says EVERY
               path runs through one materializer — including the vector a
               `v/event` body RETURNS. A body that does its own work and then
               yields a vector carrying `[::v/read <path>]` has that door read
               off the live callback argument it was handed, exactly as the same
               vector written declaratively would. The payload is read for the
               vector about to be dispatched, whichever way the site produced
               it."
       (let [{:keys [dispatch seen]} (recorder)
             owner (events/owner :acme/table)
             cand  (events/candidate owner)
             proxy (events/site cand :on-scroll
                     (events/callback
                       :event
                       (fn [_e] [:acme.table/scrolled [:ledger :q3]
                                 [:re-frame.freehand/read [:target :scrollTop]]])
                       1))]
         (events/commit! cand dispatch)
         (proxy #js {:target #js {:scrollTop 3200}})
         (is (= [[:acme.table/scrolled [:ledger :q3] 3200]] @seen)
             "the returned vector's door reads the LIVE argument, not nil"))
       ;; A named member in a returned vector already worked (native-payload
       ;; supplies the roster); it is asserted beside the door so the two
       ;; spellings are shown to behave alike on this path too.
       (let [{:keys [dispatch seen]} (recorder)
             owner (events/owner :acme/table)
             cand  (events/candidate owner)
             proxy (events/site cand :on-scroll
                     (events/callback
                       :event
                       (fn [_e] [:acme.table/scrolled :re-frame.freehand/scroll-top])
                       1))]
         (events/commit! cand dispatch)
         (proxy #js {:target #js {:scrollTop 512}})
         (is (= [[:acme.table/scrolled 512]] @seen)
             "and so does a named member in the same position"))
       ;; The door's scalar law still binds on this path — a returned vector is
       ;; not a way around it.
       (let [{:keys [dispatch seen]} (recorder)
             owner (events/owner :acme/table)
             cand  (events/candidate owner)
             proxy (events/site cand :on-scroll
                     (events/callback
                       :event
                       (fn [_e] [:acme/read [:re-frame.freehand/read :target]])
                       1))]
         (events/commit! cand dispatch)
         (is (= :rf.error/view-bad-event
                (conf/caught-id #(proxy #js {:target #js {:scrollTop 0}})))
             "a returned door reading a host object is refused")
         (is (= [] @seen) "and nothing is dispatched")))))
