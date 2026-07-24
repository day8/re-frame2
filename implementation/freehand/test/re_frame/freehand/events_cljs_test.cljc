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
            [re-frame.freehand.conformance :as conf]
            [re-frame.freehand.events :as events]
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
   :options-every-option    {:event [:picker/move 1] :prevent-default true
                             :stop-propagation true :once true :passive true :capture true}
   :options-false-options   {:event [:article/save 3] :prevent-default false
                             :stop-propagation false :once false :passive false :capture false}
   :nil                     nil
   :options-unknown-key     {:event [:article/save 3] :prevent-defualt true}
   :options-no-event        {:prevent-default true}
   :options-event-not-vector {:event :article/save}
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
            remove."
    (is (seq (:rejected-plans event-002)))
    (doseq [{:keys [form error-id]} (:rejected-plans event-002)]
      (is (contains? plan-forms form) (str "the suite carries a value for " form))
      (is (= error-id (conf/caught-id #(events/event-plan (get plan-forms form))))
          (str "rejects " form)))))

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
;; FH-EVENT-005 — the closed key-condition event map
;; ---------------------------------------------------------------------------

(def event-005 (conf/fixture :FH-EVENT-005))

(defn- key-map-forms
  "The values the FH-EVENT-005 `:form` tokens name. A thunk, because the
  fn-carrying branches must be freshly built per assertion."
  [calls]
  {:one-key        {"Enter" [:picker/accept]}
   :many-keys      {"Enter"     {:event [:picker/accept] :prevent-default true}
                    "Escape"    [:picker/close]
                    "ArrowDown" {:event [:picker/move 1] :prevent-default true}}
   :nil-branch     {"Escape" nil}
   :mixed-map      {"Enter" [:picker/accept] :prevent-default true}
   :empty-map      {}
   :handler-branch {"Enter" (v/handler [_] (swap! calls conj :handler))}
   :bare-fn-branch {"Enter" (fn [_] (swap! calls conj :bare))}
   :nested-key-map {"Enter" {"Escape" [:x]}}})

(deftest fh-event-005-a-key-condition-map-classifies
  (testing "Per FH-EVENT-005: a key-condition map normalizes to a `:key-map`
            plan whose branches are each a classified DISPATCHING plan. It is a
            separate closed form from the options map — string keys, one level —
            and both emitters and the structural host read the one plan shape."
    (is (seq (:classify event-005)) "the fixture's classify table loaded")
    (let [forms (key-map-forms (atom []))]
      (doseq [{:keys [form plan]} (:classify event-005)]
        (is (contains? forms form) (str "the suite carries a value for " form))
        (is (= plan (events/event-plan (get forms form))) (str "plan for " form))))))

(deftest fh-event-005-a-v-event-branch-is-a-legal-intent
  (testing "Per FH-EVENT-005: a `v/event` branch is a legal per-key intent —
            it yields one event vector or nil like any other dispatching form."
    (let [plan (events/event-plan {"Enter" (v/event [_e] [:picker/typed])})]
      (is (= :key-map (:role plan)))
      (is (= :event (:role (get (:branches plan) "Enter")))))))

(deftest fh-event-005-a-malformed-key-map-is-rejected
  (testing "Per FH-EVENT-005: a map mixing exact-key strings with listener
            options, an empty map, and a branch that is not itself one intent
            (a `v/handler`, a bare function, a NESTED key map) are each a loud
            reject — the boundaries the one-level exact-key form must not blur."
    (is (seq (:rejected event-005)))
    (let [forms (key-map-forms (atom []))]
      (doseq [{:keys [form error-id]} (:rejected event-005)]
        (is (contains? forms form) (str "the suite carries a value for " form))
        (is (= error-id (conf/caught-id #(events/event-plan (get forms form))))
            (str "rejects " form))))))

(deftest fh-event-005-a-branch-may-not-carry-a-whole-listener-option
  (testing "Per FH-EVENT-005: a key branch carries its intent plus the two
            PRE-DISPATCH mechanics, and nothing else. `:once`, `:capture` and
            `:passive` are whole-listener facts — the first retires the site,
            the other two decide native attachment — and all three are settled
            before a keystroke is read, so a branch naming one is a typed
            reject rather than a branch that accepts the option and then
            discards it."
    (is (seq (:branch-options event-005)) "the fixture's branch-option table loaded")
    (doseq [{:keys [option accepted error-id]} (:branch-options event-005)]
      (let [value {"Enter" {:event [:picker/accept] option true}}
            run   #(events/event-plan value)]
        (if accepted
          (is (= {:role :event-options :event [:picker/accept] option true}
                 (get (:branches (run)) "Enter"))
              (str option " is a pre-dispatch mechanic a branch may carry"))
          (is (= error-id (conf/caught-id run))
              (str option " is a whole-listener fact a branch may not carry")))))))

(defn- keystroke
  "One keystroke argument for `k`, in the shape THIS host's `key-facts` seam
  reads — a real event object in the browser, the structural payload map on the
  JVM. The seam is named rather than hidden, exactly as `default-payload` is,
  so one body of test code proves the law on both hosts."
  [k]
  #?(:cljs #js {:key k}
     :clj  {:re-frame.freehand/key k}))

(deftest fh-event-005-a-key-map-site-fires-once-per-keystroke
  (testing "Per FH-EVENT-005: the counterexample the branch roster exists to
            make unwritable. A committed key-map site invoked twice with the
            same key dispatches its intent BOTH times — one firing per
            keystroke — so a branch that could carry `:once` would be reading
            back a promise the site never keeps."
    (let [{:keys [branch key invocations dispatched]} (:repeated-key event-005)
          {:keys [dispatch seen]} (recorder)
          owner (events/owner :app/picker)
          cand  (events/candidate owner)
          proxy (events/site cand :on-key-down {key branch} events/default-payload
                             {:tag :div :slot "onKeyDown"})]
      (events/commit! cand dispatch)
      (dotimes [_ invocations]
        (proxy (keystroke key)))
      (is (= dispatched @seen)
          "every keystroke fires — the site retires nothing per branch")
      (is (= :rf.error/view-bad-event
             (conf/caught-id #(events/event-plan {key (assoc branch :once true)})))
          "and asking for :once on that branch is refused rather than ignored"))))

(deftest fh-event-005-a-key-map-is-legal-only-on-a-key-listener
  (testing "Per FH-EVENT-005: a key-condition map selects an intent by
            KeyboardEvent.key, so it is legal only on `:on-key-down` /
            `:on-key-up`; on any other listener slot it is a typed authoring
            error rather than a site that silently never fires."
    (is (seq (:site-legality event-005)))
    (doseq [{:keys [slot accepted error-id]} (:site-legality event-005)]
      (let [owner   (events/owner :app/picker)
            cand    (events/candidate owner)
            element {:tag :div :slot slot}
            run     #(events/site cand :on-key {"Enter" [:picker/accept]}
                                  events/payload-map element)]
        (if accepted
          (is (fn? (run)) (str slot " accepts a key-condition map"))
          (is (= error-id (conf/caught-id run)) (str slot " rejects a key-condition map")))))))

(deftest fh-event-005-selection-is-exact-key-one-level
  (testing "Per FH-EVENT-005: selection is one level and by EXACT equality —
            the branch whose key equals the keystroke fires, a missing key is a
            no-op, and a chord modifier (Ctrl/Alt/Meta) or an in-flight IME
            composition matches nothing. This is acceptance 1/2/3 as a pure,
            cross-host law over the selection facts."
    (is (seq (:selections event-005)))
    (let [plan (:selection-plan event-005)]
      (doseq [{:keys [facts selected]} (:selections event-005)]
        (is (= (when-not (= :none selected) selected)
               (events/select-branch plan facts))
            (str "facts " (pr-str facts)))))))

;; ---------------------------------------------------------------------------
;; FH-EVENT-005 — end to end through a committed proxy, both hosts
;; ---------------------------------------------------------------------------

#?(:clj
   (deftest fh-event-005-a-committed-key-map-selects-and-dispatches-jvm
     (testing "Per FH-EVENT-005 (structural host): a committed key-map site
               fires exactly the mapped intent and only that one; a
               non-matching key and a chord modifier dispatch nothing; and a
               selected branch materializes `::v/key` from the same payload the
               site supplies. The structural host reads the selection facts off
               the plain payload map — one body, the same law as the browser."
       (let [{:keys [dispatch seen]} (recorder)
             owner (events/owner :app/picker)
             cand  (events/candidate owner)
             value {"Enter"     {:event [:picker/accept] :prevent-default true}
                    "Escape"    [:picker/close]
                    "ArrowDown" [:picker/moved :re-frame.freehand/key]}
             proxy (events/site cand :on-key-down value events/payload-map
                                {:tag :div :slot "onKeyDown"})]
         (events/commit! cand dispatch)
         (proxy {:re-frame.freehand/key "Enter"})
         (is (= [[:picker/accept]] @seen) "the one mapped intent")
         (proxy {:re-frame.freehand/key "Tab"})
         (is (= [[:picker/accept]] @seen) "a non-matching key dispatches nothing")
         (proxy {:re-frame.freehand/key "Enter" :chord? true})
         (is (= [[:picker/accept]] @seen) "a chord modifier matches nothing")
         (proxy {:re-frame.freehand/key "ArrowDown"})
         (is (= [[:picker/accept] [:picker/moved "ArrowDown"]] @seen)
             "the selected branch projects ::v/key from the payload")))))

#?(:cljs
   (deftest fh-event-005-a-real-key-selects-and-dispatches-one-intent
     (testing "Per FH-EVENT-005 (browser host): a live KeyboardEvent for a
               mapped key dispatches exactly the mapped intent and only that
               one, running the selected branch's pre-dispatch mechanics first;
               a non-matching key dispatches nothing; and a chord modifier or an
               in-flight composition matches nothing — the boundary the closed
               form must not blur, proven against real event objects."
     (let [value {"Enter"  {:event [:picker/accept] :prevent-default true}
                  "Escape" [:picker/close]
                  "ArrowDown" [:picker/moved :re-frame.freehand/key]}
           fire  (fn [e]
                   (let [{:keys [dispatch seen]} (recorder)
                         owner (events/owner :app/picker)
                         cand  (events/candidate owner)
                         proxy (events/site cand :on-key-down value
                                            events/default-payload
                                            {:tag :div :slot "onKeyDown"})]
                     (events/commit! cand dispatch)
                     (proxy e)
                     @seen))]
       (let [calls (atom [])
             e     #js {:key "Enter"
                       :preventDefault  #(swap! calls conj :prevent-default)
                       :stopPropagation #(swap! calls conj :stop-propagation)}
             {:keys [dispatch seen]} (recorder)
             owner (events/owner :app/picker)
             cand  (events/candidate owner)
             proxy (events/site cand :on-key-down value events/default-payload
                                {:tag :div :slot "onKeyDown"})]
         (events/commit! cand dispatch)
         (proxy e)
         (is (= [[:picker/accept]] @seen) "the mapped intent, and only that one")
         (is (= [:prevent-default] @calls)
             "the selected branch's mechanics ran before dispatch, and only those"))
       (is (= [] (fire #js {:key "Tab"})) "an unmapped key dispatches nothing")
       (is (= [] (fire #js {:key "Enter" :ctrlKey true}))
           "Ctrl+Enter matches nothing — modifier chords are v/event's job")
       (is (= [] (fire #js {:key "Enter" :metaKey true})) "Meta+Enter matches nothing")
       (is (= [] (fire #js {:key "Enter" :isComposing true}))
           "a composing keystroke matches nothing")
       (is (= [[:picker/moved "ArrowDown"]] (fire #js {:key "ArrowDown"}))
           "a branch projects ::v/key off the live event")))))

#?(:cljs
   (deftest fh-event-005-key-facts-reads-the-live-event
     (testing "Per FH-EVENT-005 (browser host): the one host seam reads the key
               name, the composition flag, and a Ctrl/Alt/Meta chord off a live
               KeyboardEvent — Shift is NOT a chord, since it is already baked
               into `KeyboardEvent.key`."
       (is (= {:key "Enter" :composing? false :chord? false}
              (events/key-facts #js {:key "Enter"})))
       (is (= {:key "?" :composing? false :chord? false}
              (events/key-facts #js {:key "?" :shiftKey true}))
           "Shift is not a chord — a shifted key still matches its exact key")
       (is (:chord? (events/key-facts #js {:key "Enter" :ctrlKey true})))
       (is (:chord? (events/key-facts #js {:key "Enter" :altKey true})))
       (is (:chord? (events/key-facts #js {:key "Enter" :metaKey true})))
       (is (:composing? (events/key-facts #js {:key "a" :isComposing true}))))))

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
