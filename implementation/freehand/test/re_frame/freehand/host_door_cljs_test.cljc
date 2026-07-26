(ns re-frame.freehand.host-door-cljs-test
  "FH-REACT-006 / 007 / 008 — the inward React host door (D022).

  Three laws, one declaration surface, both hosts.

  ## What these suites are defending

  Before `v/defhost`, Freehand's vector-head classification already had
  three legal answers and one of them was *a declared host descriptor* —
  `:rf.error/view-bad-head` names it in the sentence it prints. And no
  public verb produced one. An adopter could read what a host boundary
  was, meet a diagnostic that told them to use one, and have no way to
  build it. The pilot recorded exactly that, by reaching past the door
  with a hand-written map carrying the reserved marker key.

  So the door's first law is not \"a host crosses\" — it is that there is
  exactly ONE way in. `FH-REACT-006` asserts the mint and the ABSENCE of
  every alternative D022 rejects by name, because a second spelling is a
  second set of laws to keep in step, and it is the failure this slice
  exists to avoid rather than one it might stumble into.

  `FH-REACT-007` pins the three disjoint planes at the call, and
  `FH-REACT-008` pins what a structural render may say and what a
  compiled build must refuse.

  Host-neutral throughout: everything asserted here is the COMMON
  declaration and the COMMON structural projection, so the same
  assertions run on the JVM and in ClojureScript. The registered React
  components are plain function components returning nil — nothing here
  mounts, and what is under test is the boundary rather than the
  library behind it."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [re-frame.freehand :as v]
            [re-frame.freehand.compiler.analyze :as ana]
            [re-frame.freehand.compiler.env :as cenv]
            [re-frame.freehand.conformance :as conf]
            [re-frame.freehand.descriptor :as descriptor]
            [re-frame.freehand.test :as t]))

(def react-006 (conf/fixture :FH-REACT-006))
(def react-007 (conf/fixture :FH-REACT-007))
(def react-008 (conf/fixture :FH-REACT-008))

;; ---------------------------------------------------------------------------
;; The declarations under test
;; ---------------------------------------------------------------------------

(v/defhost declared-host
  "The plain case: value props, no callbacks, no children, no server content."
  (fn [_props] nil)
  {:children :none
   :ssr      :client-only})

(v/defhost planes-host
  "One `:event` position and one `:handler` position — the two roles a
  declared host position may take, and the pair FH-REACT-007 splits."
  (fn [_props] nil)
  {:callbacks {:onSelect :event :onMeasure :handler}
   :children  :optional
   :ssr       :client-only})

(v/defhost fallback-host
  "A host whose declaration chooses a PORTABLE fallback rather than
  explicit no-server content."
  (fn [_props] nil)
  {:children :none
   :ssr      {:fallback [:div.chart-placeholder]}})

(v/defhost mapped-host
  "A host with the one whole-ordinary-props adapter. The adapter is the
  reason the structural tree records the AUTHORED props and reports the
  adapter's presence rather than its output."
  (fn [_props] nil)
  {:children  :none
   :ssr       :client-only
   :map-props (fn [p] (assoc p :prepared true))})

(v/defhost no-children-host
  (fn [_props] nil)
  {:children :none :ssr :client-only})

(v/defhost needs-children-host
  (fn [_props] nil)
  {:children :required :ssr :client-only})

(v/defview holder
  "An ordinary enclosing view — a host crosses inside a boundary like any
  other head."
  [{:keys [children]}]
  [:section.holder children])

(v/defview host-page
  "An INTERPRETED view whose body mounts a host. The host is invisible to a
  compiled root that mounts this view — the root sees a view head — so the
  crossing surfaces at RENDER, which is where `v/render-static` proves it."
  [_]
  [:main.page [fallback-host {}]])

;; ---------------------------------------------------------------------------
;; Fixture value construction
;; ---------------------------------------------------------------------------

(def ^:private event-carrier (v/event [x] [:chart/selected x]))
(def ^:private handler-carrier (v/handler [x] x))

(def ^:private fixture-values
  "EDN cannot carry a live carrier, a live descriptor or a function. This
  is the one place those names become values."
  {:fixture/declared-host        declared-host
   :fixture/planes-host          planes-host
   :fixture/client-only-host     declared-host
   :fixture/fallback-host        fallback-host
   :fixture/mapped-host          mapped-host
   :fixture/event-carrier        event-carrier
   :fixture/handler-carrier      handler-carrier
   :fixture/plain-function       (fn [_] nil)
   :fixture/not-a-map            "not-a-map"
   :fixture/client-only-host-id  :re-frame.freehand.host-door-cljs-test/declared-host
   :fixture/planes-host-id       :re-frame.freehand.host-door-cljs-test/planes-host
   :fixture/fallback-host-id     :re-frame.freehand.host-door-cljs-test/fallback-host
   :fixture/mapped-host-id       :re-frame.freehand.host-door-cljs-test/mapped-host})

(defn- v* [x] (get fixture-values x x))

(defn- props* [m] (if (map? m) (reduce-kv (fn [a k x] (assoc a k (v* x))) {} m) (v* m)))

(defn- host-node
  "Render one host crossing inside an ordinary view boundary and answer the
  host node."
  [form]
  (t/find (t/render [holder {} form]) #(contains? % :rf.ui/host)))

;; ===========================================================================
;; FH-REACT-006 — one door, and only one
;; ===========================================================================

(deftest fh-react-006-the-fixture-is-not-empty
  (testing "A table-driven law whose table failed to load passes
            vacuously. Pin the fixture's shape before trusting a row."
    (is (= "FH-REACT-006" (:fh/id react-006)))
    (is (seq (:absent react-006)))
    (is (= ["defhost"] (:present react-006)))
    (is (contains? fixture-values (get-in react-006 [:mints :head])))))

(deftest fh-react-006-defhost-mints-the-third-head
  (testing "Per FH-REACT-006: `v/defhost` produces the value the classifier
            already recognised and nothing else could build."
    (let [{:keys [head classifies predicate]} (:mints react-006)
          host (v* head)]
      (is (= predicate (descriptor/host-descriptor? host)))
      (is (= classifies (descriptor/classify-head host))))))

(deftest fh-react-006-the-descriptor-is-not-callable
  (testing "Per FH-REACT-006: a direct call RAISES. A map-shaped descriptor
            would answer the call as a lookup — nil, rendering nothing,
            saying nothing — at exactly the boundary a hand arriving from
            React is most likely to call by habit."
    (let [expect (get-in react-006 [:mints :direct-call :error-id])]
      (is (= expect (conf/caught-id #(declared-host {:selected 1}))))
      (is (str/includes? (conf/caught-message #(declared-host {}))
                         "mounted, never invoked")))))

(deftest fh-react-006-the-pre-door-value-is-an-ordinary-map
  (testing "Per FH-REACT-006: the marked map the pilot used to reach past
            the door with is now an ordinary map — the predicate is false
            and the head is an error. Nominality is what makes the
            classification total without duck-typing at all."
    (let [{:keys [value predicate error-id]} (:pre-door-value react-006)]
      (is (= predicate (descriptor/host-descriptor? value)))
      (is (= error-id (conf/caught-id #(descriptor/classify-head value)))))))

#?(:clj
   (deftest fh-react-006-defhost-is-the-only-host-verb-on-the-door
     (testing "Per FH-REACT-006: D022 rejects a runtime `v/host`
               constructor, a leaf/wrapper `:kind` split and `v/react-el`
               BY NAME — each is the same crossing under a second spelling.
               So the supported surface carries exactly one of them, and a
               second appearing here is the regression this asserts against.

               The `^:no-doc` expansion targets are the macro's own
               machinery and not authoring surface, the same carve-out
               `expand-defview` / `parse-defview-args` already sit in."
       (require 're-frame.freehand)
       (let [publics (set (map name (keys (ns-publics (find-ns 're-frame.freehand)))))
             doored  (into #{}
                           (comp (remove #{"expand-defhost" "parse-defhost-args"})
                                 (filter #(str/includes? % "host")))
                           publics)]
         (is (seq publics) "non-vacuous: the door publishes vars to examine")
         (is (= (set (:present react-006)) doored)
             (str "defhost is the sole host verb on the door; got " (pr-str (sort doored))))
         (doseq [absent (:absent react-006)]
           (is (not (contains? publics absent))
               (str absent " is rejected by name in D022 and must not appear")))))))

(deftest fh-react-006-children-and-ssr-are-required-with-no-default
  (testing "Per FH-REACT-006: every declaration states its children policy
            and its SSR policy. Freehand never runs the registered
            component on the JVM, so a default would be the substrate
            choosing a server behaviour silently."
    (is (= [:children :ssr] (:required-options react-006)))
    (is (= #{:callbacks :children :ssr :map-props :props}
           (set (:closed-options react-006))))
    (let [entry (descriptor/host-entry declared-host)]
      (is (contains? entry :children) "the live declaration carries what it must state")
      (is (contains? entry :ssr)))))

#?(:clj
   (deftest fh-react-006-a-malformed-declaration-refuses-at-expansion
     (testing "Per FH-REACT-006: the refusals are MACRO-EXPANSION refusals,
               so a bad declaration never becomes a head a build accepts and
               a render discovers. Driven through the expander rather than
               written as declarations in this file, because a declaration
               here would fail this namespace's own compile instead of an
               assertion inside it.

               JVM-only, and that is the whole statement rather than a gap:
               macro expansion happens on the JVM for BOTH compilation
               targets, so this IS the ClojureScript behaviour."
       (let [expand #(try (v/expand-defhost true {:line 1} "f.cljc" 'my.ns 'h %)
                          (catch Exception e (:rf.ui.compile/error (ex-data e))))
             id     (:declaration-error-id react-006)]
         (is (= id (expand ['C {:ssr :client-only}]))
             "no :children policy")
         (is (= id (expand ['C {:children :none}]))
             "no :ssr policy")
         (is (= id (expand ['C {:children :sometimes :ssr :client-only}]))
             "a children policy outside the closed roster")
         (is (= id (expand ['C {:children :none :ssr :maybe}]))
             "an SSR policy outside the closed roster")
         (is (= id (expand ['C {:children :none :ssr {:fallback [:div] :extra 1}}]))
             "an SSR map with a second key")
         (is (= id (expand ['C {:children :none :ssr :client-only :kind :leaf}]))
             "an option outside the closed roster — including a reserved one")
         (is (= id (expand ['C {:children :none :ssr :client-only
                                :callbacks {:onChange :render-fn}}]))
             "a role outside the two declarable ones")
         (is (= id (expand ['C {:children :none :ssr :client-only
                                :callbacks {:children :event}}]))
             "a callback position naming a reserved call-ABI slot")
         (is (= id (expand ['C]))
             "a declaration with no options map at all")
         (is (map? (:opts (v/parse-defhost-args ['C {:children :none :ssr :client-only}])))
             "control: the legal spelling parses, so the reds above are the mutation talking")
         (is (some? (expand ['C {:children :none :ssr :client-only}]))
             "control: the legal declaration expands")
         (is (not= id (expand ['C {:children :none :ssr :client-only}]))
             "and expanding it is not itself the refusal")))))

;; ===========================================================================
;; FH-REACT-007 — three disjoint planes
;; ===========================================================================

(deftest fh-react-007-the-fixture-is-not-empty
  (testing "Pin the table before trusting a row of it."
    (is (= "FH-REACT-007" (:fh/id react-007)))
    (is (<= 5 (count (:accepts react-007))))
    (is (<= 12 (count (:rejects react-007))))
    (is (seq (get-in react-007 [:prop-names :rejects])))))

(deftest fh-react-007-the-declaration-matches-the-fixture
  (testing "Per FH-REACT-007: the host under test declares the positions
            the table's rows are written against. Without this the rows
            below could be asserting against a different declaration."
    (let [entry (descriptor/host-entry planes-host)
          decl  (:declaration react-007)]
      (is (= (:callbacks decl) (:callbacks entry)))
      (is (= (:children decl) (:children entry)))
      (is (= (:ssr decl) (:ssr entry))))))

(deftest fh-react-007-the-planes-are-disjoint
  (testing "Per FH-REACT-007: a declared position leaves the ordinary
            plane, an undeclared `on*`-looking name stays ordinary DATA,
            and an unfilled position is legal."
    (doseq [{:keys [case props ordinary callbacks]} (:accepts react-007)]
      (let [call (descriptor/normalize-host-call planes-host [(props* props)])]
        (is (= ordinary (:props call)) case)
        (is (= (set callbacks) (set (keys (:callbacks call)))) case)))))

(deftest fh-react-007-guessing-is-refused
  (testing "Per FH-REACT-007: every row here is a way an implementation
            drifts back toward guessing — inferring a callback from a name,
            coercing a bare event vector at a foreign prop, accepting a
            wrong-role carrier because both are 'callbacks', forwarding a
            bare function because it looked callable."
    (doseq [{:keys [case props error-id]} (:rejects react-007)]
      (is (= error-id
             (conf/caught-id #(descriptor/normalize-host-call planes-host [(props* props)])))
          case))))

(deftest fh-react-007-a-bare-vector-refusal-names-the-recovery
  (testing "Per FH-REACT-007 and D022: the refusal explains WHY there is no
            implicit conversion — a foreign API may itself want a vector at
            that prop — and names the spelling that works."
    (let [m (conf/caught-message
              #(descriptor/normalize-host-call planes-host [{:onSelect [:chart/selected]}]))]
      (is (str/includes? m "not a bare event vector"))
      (is (str/includes? m "v/event")))))

(deftest fh-react-007-a-prop-name-is-exact
  (testing "Per FH-REACT-007 §prop-names: a host prop name is an unqualified
            keyword, because the crossing names the prop by `name` and every
            law at this boundary — `:children`, `:key`, each declared
            position, and what a `:map-props` adapter may not supply — is
            enforced BY that name. A key with a second spelling reaches React
            under the reserved name while passing the check that looked for
            the keyword, so exactness is what makes those checks total rather
            than a style rule about how to spell a map."
    (let [{:keys [accepts rejects]} (:prop-names react-007)]
      (is (= [] (descriptor/inexact-host-prop-names (zipmap accepts (repeat 1))))
          "an unqualified keyword has an exact crossing name")
      (is (= (vec (sort-by pr-str rejects))
             (descriptor/inexact-host-prop-names (zipmap rejects (repeat 1))))
          "and nothing else does — every rejected spelling is reported, not just the first")
      (is (= "onSelect" (descriptor/host-prop-name :onSelect))
          "the crossing projection is the bare name")
      (is (= (count accepts) (count (into #{} (map descriptor/host-prop-name) accepts)))
          "and it is injective over the names it accepts — which is the whole claim"))))

(deftest fh-react-007-a-second-spelling-is-refused-at-the-call
  (testing "Per FH-REACT-007: each of these shipped as a real bypass. The
            planes were split by comparing Clojure keys while the crossing
            collapsed them by name, so `\"children\"` walked past both the
            `:children` rejection and the `:none` policy, `\"onSelect\"` was
            treated as ordinary DATA and handed to React as the callback
            prop with none of D008's identity, and `\"key\"` keyed the inner
            element while the outer phase boundary stayed unkeyed."
    (doseq [[props case] [[{"children" ["nope"]} "\"children\""]
                          [{"key" "k"}           "\"key\""]
                          [{:x/key "k"}          ":x/key"]
                          [{"onSelect" [:chart/selected]} "\"onSelect\""]
                          [{:x/spec "bar"}       ":x/spec"]]]
      (is (= :rf.error/view-bad-props
             (conf/caught-id #(descriptor/normalize-host-call planes-host [props])))
          case))))

(deftest fh-react-007-the-inexact-refusal-names-every-offender-and-the-recovery
  (testing "Per FH-REACT-007 §prop-names: the refusal reports EVERY badly
            spelled key rather than the first one found, because a call that
            got the spelling wrong once usually got it wrong twice, and it
            names the recovery."
    (let [call #(descriptor/normalize-host-call
                  planes-host [{"spec" "bar" :x/rows 3 :variant :compact}])
          m    (conf/caught-message call)]
      (is (str/includes? m "\"spec\"") "the string key is named")
      (is (str/includes? m ":x/rows") "and so is the qualified one")
      (is (not (str/includes? m ":variant")) "the well-spelled key is not accused")
      (is (str/includes? m "unqualified keyword") "the law is stated")
      (is (= (get-in react-007 [:prop-names :recovery])
             (:recovery (conf/caught-data call)))
          "and the recovery is the fixture's"))))

(deftest fh-react-007-the-children-policy-is-the-common-law
  (testing "Per FH-REACT-007: a host's children policy is the same closed
            roster and the same diagnostic an internal boundary uses. One
            law, not a host-shaped copy of one."
    (is (= (get-in react-007 [:children-policy :none-with-children :error-id])
           (conf/caught-id #(t/render [holder {} [no-children-host {} [:span "no"]]]))))
    (is (= (get-in react-007 [:children-policy :required-without-any :error-id])
           (conf/caught-id #(t/render [holder {} [needs-children-host {}]]))))))

;; ===========================================================================
;; FH-REACT-008 — structure, SSR, and the compiled refusal
;; ===========================================================================

(deftest fh-react-008-the-fixture-is-not-empty
  (testing "Pin the table before trusting a row of it."
    (is (= "FH-REACT-008" (:fh/id react-008)))
    (is (= 5 (count (:projections react-008))))
    (is (every? #(contains? fixture-values (:host %)) (:projections react-008)))))

(deftest fh-react-008-the-structural-projection-is-honest
  (testing "Per FH-REACT-008: identity, the declared SSR policy, the
            AUTHORED ordinary props, a COUNT of the crossed children, and a
            `:children` slot carrying only what the server can honestly
            emit. Each row is a way the node could lie, closed."
    (doseq [{:keys [case host props children node]} (:projections react-008)]
      (let [kids (repeat (or children 0) [:span "kid"])
            form (into [(v* host) (props* props)] kids)]
        ;; The expected node names its host id symbolically, for the reason
        ;; the heads are named symbolically: a fixture is EDN, and the id is
        ;; derived from THIS namespace.
        (is (= (update node :rf.ui/host v*) (host-node form)) case)))))

(deftest fh-react-008-a-host-value-never-reaches-the-tree
  (testing "Per FH-REACT-008: the recorded props are the AUTHORED ones. The
            `:map-props` adapter's output — the whole reason an adapter
            exists — is absent, and its presence is reported instead, which
            states the loss without incurring it.

            Non-vacuity: the adapter really does change the map, so an
            implementation that recorded its output would produce a
            DIFFERENT node here rather than an equal one."
    (is (= {:data [1 2 3] :prepared true}
           ((:map-props (descriptor/host-entry mapped-host)) {:data [1 2 3]}))
        "the adapter is live and its output differs from its input")
    (let [n (host-node [mapped-host {:data [1 2 3]}])]
      (is (= {:data [1 2 3]} (:props n)) "the tree records the authored props")
      (is (true? (:rf.ui/host-map-props n)) "and reports that an adapter stands between"))))

(deftest fh-react-008-the-tree-round-trips
  (testing "Per FH-REACT-008: the structural tree prints and reads back
            EQUAL, on both hosts. That is the property a host value, a
            React element, a function or a third-party instance would
            break, and it is what makes the projection a serialisable
            statement rather than a live handle."
    (let [n (host-node [planes-host {:spec "bar" :onSelect event-carrier} [:span "kid"]])]
      (is (= n (#?(:clj read-string :cljs cljs.reader/read-string) (pr-str n)))))))

#?(:clj
   (deftest fh-react-008-render-static-refuses-the-crossing
     (testing "Per FH-REACT-008: the static-HTML fold owns no client, so no
               client ever replaces the host's SSR projection and the page
               would ship the stand-in as its final answer. Refused on the
               law `v/behavior` is refused on, with the same recovery.

               Proved through an INTERPRETED body, because that is the tier
               that reaches the render-time arm at all: a host lexically
               visible at the compiled root is refused earlier, at build,
               which is the sibling assertion below."
       (let [thrown (try (v/render-static [host-page {}]) nil
                         (catch Exception e e))
             data   (some-> thrown ex-data)]
         (is (some? thrown) "the fold refuses rather than emitting the stand-in")
         (is (= (get-in react-008 [:render-static :error-id]) (:rf.error/id data)))
         (is (= (get-in react-008 [:render-static :capability]) (:capability data)))
         (is (str/includes? (ex-message thrown) "v/defhost crossing"))
         (is (= :mount-in-the-browser-or-move-the-live-subtree-behind-client-only
                (:recovery data))
             "the recovery is the one every no-silent-elision arm names"))
       (testing "control: the same shape without the crossing folds cleanly, so
                 the refusal above is the host talking"
         (is (string? (v/render-static [holder {} [:div.plain "ok"]])))))))

;; ---------------------------------------------------------------------------
;; The compiled build refusal
;; ---------------------------------------------------------------------------
;;
;; Asserted against the ANALYZER rather than against a `{:compiled true}`
;; declaration in this file, for a mechanical reason: the refusal is a
;; macro-expansion throw, so a declaration written here would fail this
;; namespace's own compile rather than an assertion inside it.

(def ^:private host-resolver
  "The one head this table needs, carrying the metadata `v/defhost` really
  stamps. Read off the LIVE declaration rather than hand-written, so a
  change to what the expansion stamps reds this row instead of leaving it
  asserting against a fossil."
  (fn [sym]
    (when (= 'chart-host sym)
      {:fqn  'app.interop/chart-host
       :meta (meta #'declared-host)})))

(defn- compile-reject
  [form]
  (try (ana/analyze (cenv/make-env {:host :clj :ns-sym 'app.test :resolver host-resolver})
                    form)
       nil
       (catch #?(:clj clojure.lang.ExceptionInfo :cljs cljs.core/ExceptionInfo) ex
         {:id (:rf.ui.compile/error (ex-data ex)) :msg (ex-message ex)})))

(deftest fh-react-008-the-declaration-metadata-is-what-the-compiler-reads
  (testing "Non-vacuity, and the hazard this closes. The declaration is read
            by TWO mechanisms — the interpreted walk reads the descriptor
            VALUE in the Var, the compiler reads the Var's METADATA — and
            one expansion writes both, in one form. Assert they agree about
            the same declaration rather than trusting that they do."
    (let [m (meta #'declared-host)]
      (is (true? (:re-frame.freehand/host m))
          "the compile-time marker the classifier keys on")
      (is (cenv/declared-host? m))
      (is (= (:re-frame.freehand/host-id m)
             (:host-id (descriptor/host-entry declared-host)))
          "and the id the metadata carries is the id the runtime descriptor carries")
      (is (= (:re-frame.freehand/children-policy m)
             (:children (descriptor/host-entry declared-host)))
          "as is the children policy"))))

(deftest fh-react-008-a-compiled-parent-refuses-the-crossing-at-build
  (testing "Per FH-REACT-008: the compiled tier RECOGNIZES the head and
            refuses. Both wrong answers are worse than a refusal — emitting
            a foreign component call hands React a Freehand descriptor as an
            element type, and walking the subtree interpreted produces a
            manifest describing markup that is not what runs."
    (let [{:keys [id msg]} (compile-reject '[:div [chart-host {:spec "bar"}]])]
      (is (= (get-in react-008 [:compiled :error-id]) id))
      (doseq [named (get-in react-008 [:compiled :names])]
        (is (str/includes? msg named)
            (str "the refusal names " named)))
      (is (str/includes? msg (str (:host-id (descriptor/host-entry declared-host))))
          "and names WHICH host, so the author can navigate to it"))
    (is (nil? (compile-reject '[:div [:span "ordinary"]]))
        "control: an ordinary compiled body still analyzes, so the red above
         is the host head talking")))
