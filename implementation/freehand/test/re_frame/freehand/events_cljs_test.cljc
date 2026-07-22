(ns re-frame.freehand.events-cljs-test
  "FH-EVENT-001 … FH-EVENT-004 — intent is data.

  One user action yields exactly one semantic event vector or `nil`. The
  Freehand event site materializes the closed projection trio from the
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
;; FH-EVENT-001 — the projection trio and the one pure materializer
;; ---------------------------------------------------------------------------

(def event-001 (conf/fixture :FH-EVENT-001))

(deftest fh-event-001-the-trio-materializes-from-the-live-payload
  (testing "Per FH-EVENT-001: `::v/value`, `::v/checked` and `::v/key`
            are replaced in TOP-LEVEL argument positions from the live
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

;; ---------------------------------------------------------------------------
;; The one host-shaped seam — reading a live native event
;; ---------------------------------------------------------------------------

#?(:cljs
   (deftest fh-event-001-native-payload-reads-the-live-event
     (testing "Per FH-EVENT-001 (browser host): the ClojureScript
               adapter reads the closed scalar trio off the live event
               object — and nothing else. No DOM node, synthetic event
               or other host object enters the intent vector; a key the
               event does not carry is ABSENT, so asking a click for
               `::v/key` is a typed error rather than a silent nil."
       (is (= {:re-frame.freehand/value "mike@example.com"}
              (events/native-payload #js {:target #js {:value "mike@example.com"}})))
       (is (= {:re-frame.freehand/checked true}
              (events/native-payload #js {:target #js {:checked true}})))
       (is (= {:re-frame.freehand/value "" :re-frame.freehand/key "Enter"}
              (events/native-payload #js {:key "Enter" :target #js {:value ""}})))
       (is (= {} (events/native-payload #js {:target #js {}}))))))

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
         (is (= [] @seen) "and nothing is dispatched")))))
