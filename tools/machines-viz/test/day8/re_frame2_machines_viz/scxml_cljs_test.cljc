(ns day8.re-frame2-machines-viz.scxml-cljs-test
  "Pure-data tests for SCXML import/export (rf2-6urjd · v1.1).

  Mirrors the structure of `mermaid_cljs_test.cljc`. Coverage:

  - `spec->scxml` emits a valid SCXML XML string for the supported
    grammar subset (flat, compound, parallel, namespaced ids, final
    states, guards, `:after`, `:always`).
  - `scxml->spec` parses our own output back to the original spec
    structure.
  - Round-trip property: `(= spec (-> spec spec->scxml scxml->spec))`
    holds for every supported fixture.
  - Error cases throw `ex-info` with a `:reason :scxml/*` key."
  (:require #?(:clj  [clojure.test :refer [deftest is testing]]
               :cljs [cljs.test    :refer-macros [deftest is testing]])
            [clojure.string :as str]
            [day8.re-frame2-machines-viz.scxml :as scxml]))

;; ---------------------------------------------------------------------------
;; Fixtures — small, hand-curated machine definitions per Spec 005
;; §Transition table grammar. Mirror the fixtures in
;; `mermaid_cljs_test.cljc` so the two emitters cover the same
;; topology surface.

(def idle-loading-success-error
  "The canonical small machine: idle → loading → success / error."
  {:initial :idle
   :states  {:idle    {:on {:start :loading}}
             :loading {:on {:ok :success :err :failed}}
             :success {:final? true}
             :failed  {:final? true}}})

(def compound-machine
  "A compound machine with one nested region."
  {:initial :unauth
   :states  {:unauth        {:on {:login :authenticated}}
             :authenticated {:initial :browsing
                             :states  {:browsing {:on {:checkout :paying}}
                                       :paying   {:on {:done :browsing}}}
                             :on      {:logout :unauth}}}})

(def namespaced-machine
  "Machine using namespaced and hyphenated ids — exercises the
  keyword<->id-string mapping."
  {:initial :auth/idle
   :states  {:auth/idle    {:on {:rf/load :auth/loading}}
             :auth/loading {:on {:done :auth/idle}}}})

(def guarded-machine
  "Machine with a guarded transition — exercises `cond=` on
  `<transition>`."
  {:initial :checking
   :states  {:checking {:on {:check {:target :ready :guard :ready?}}}
             :ready    {:final? true}}})

(def after-machine
  "Machine with an `:after` timer transition. `:after` is lossy at
  the SCXML level (no countdown ring vocabulary) — the timer
  survives as an `event=\"after.5000\"` transition."
  {:initial :loading
   :states  {:loading {:after {5000 :timeout}
                       :on    {:loaded :done}}
             :timeout {}
             :done    {}}})

(def always-machine
  "Machine with an `:always` (eventless) transition."
  {:initial :checking
   :states  {:checking {:always [{:target :ready :guard :ready?}
                                 {:target :blocked}]}
             :ready    {}
             :blocked  {}}})

(def machine-level-on-machine
  "rf2-ee38b.21 — a flat machine with a top-level (machine-level) :on
  fallback transition every state inherits (Spec 005 §top-level :on)."
  {:initial :a
   :on      {:logout :a}
   :states  {:a {:on {:go :b}}
             :b {}}})

(def parallel-machine
  "A `:type :parallel` machine with two regions."
  {:type    :parallel
   :regions {:data {:initial :nothing
                    :states  {:nothing {:on {:fetch :loading}}
                              :loading {}}}
             :form {:initial :neutral
                    :states  {:neutral {:on {:submit :correct}}
                              :correct {:final? true}}}}})

(def vector-path-target-machine
  "rf2-csq75 — a compound machine whose transition target is a VECTOR
  PATH `[:authenticated :browsing]` (a deep-target into a compound
  child). The encoder must distinguish this from the namespaced keyword
  `:authenticated/browsing`: pre-fix both dot-joined to
  `\"authenticated.browsing\"` and the decoder collapsed the vector to a
  namespaced keyword, silently changing machine semantics."
  {:initial :idle
   :states  {:idle          {:on {:login [:authenticated :browsing]}}
             :authenticated {:initial :browsing
                             :states  {:browsing {:on {:logout :idle}}
                                       :paying   {}}}}})

(def vector-path-namespaced-segment-machine
  "rf2-csq75 — a vector-path target whose FIRST segment is itself a
  namespaced keyword (`[:auth/region :browsing]`). Exercises the
  `.`-within-segment + `:`-between-segments codec: a namespaced segment
  inside a vector path must survive both separators independently."
  {:initial :a
   :states  {:a          {:on {:go [:auth/region :browsing]}}
             :auth/region {:initial :browsing
                           :states  {:browsing {}}}}})

;; ---------------------------------------------------------------------------
;; Emit shape

(deftest emit-starts-with-xml-prolog
  (testing "emitted SCXML always carries the <?xml ... ?> prolog"
    (let [out (scxml/spec->scxml idle-loading-success-error)]
      (is (str/starts-with? out "<?xml version=\"1.0\" encoding=\"UTF-8\"?>")))))

(deftest emit-includes-scxml-root-with-namespace
  (testing "emit produces a <scxml xmlns=...> root with the W3C ns"
    (let [out (scxml/spec->scxml idle-loading-success-error)]
      (is (str/includes? out "<scxml"))
      (is (str/includes? out "xmlns=\"http://www.w3.org/2005/07/scxml\""))
      (is (str/includes? out "version=\"1.0\""))
      (is (str/includes? out "</scxml>")))))

(deftest emit-flat-machine-includes-initial-and-final-states
  (testing "<scxml initial=...> and <final id=...> render"
    (let [out (scxml/spec->scxml idle-loading-success-error)]
      (is (str/includes? out "initial=\"idle\""))
      (is (str/includes? out "<state id=\"idle\""))
      (is (str/includes? out "<state id=\"loading\""))
      (is (str/includes? out "<final id=\"success\""))
      (is (str/includes? out "<final id=\"failed\"")))))

(deftest emit-renders-transition-events
  (testing "transitions render with event= and target= attrs"
    (let [out (scxml/spec->scxml idle-loading-success-error)]
      (is (str/includes? out "event=\"start\""))
      (is (str/includes? out "target=\"loading\""))
      (is (str/includes? out "event=\"ok\""))
      (is (str/includes? out "target=\"success\"")))))

(deftest emit-compound-machine-nests-states
  (testing "rf2-mnp93.7 — compound states emit nested <state> blocks with
            FULLY-QUALIFIED unique xsd:IDs (the path root→leaf, `___`-joined)
            so two same-named nested states never collide; `initial` and
            transition targets reference those same unique ids"
    (let [out (scxml/spec->scxml compound-machine)]
      (is (str/includes? out "<state id=\"authenticated\" initial=\"authenticated___browsing\""))
      (is (str/includes? out "<state id=\"authenticated___browsing\""))
      (is (str/includes? out "<state id=\"authenticated___paying\"")))))

(deftest emit-namespaced-ids-use-ns-name-marker
  (testing "rf2-mnp93.1 — :auth/idle → id=\"auth__idle\" (the `__`
            ns/name marker; the keyword name/ns chars are hex-escaped so
            the codec is fully injective and xsd:ID-conformant)"
    (let [out (scxml/spec->scxml namespaced-machine)]
      (is (str/includes? out "initial=\"auth__idle\""))
      (is (str/includes? out "<state id=\"auth__idle\""))
      (is (str/includes? out "<state id=\"auth__loading\""))
      (is (str/includes? out "event=\"rf__load\""))
      ;; The old non-injective `.`-as-ns/name form must be gone.
      (is (not (str/includes? out "id=\"auth.idle\""))))))

(deftest emit-guards-render-as-cond-attribute
  (testing "rf2-mnp93.1 — guarded transitions render with cond= on
            <transition>; the guard keyword is hex-escaped (`:ready?` →
            `ready_3f`) so the cond attribute is a valid xsd:ID-safe token"
    (let [out (scxml/spec->scxml guarded-machine)]
      (is (str/includes? out "cond=\"ready_3f\"")))))

(deftest emit-after-transitions-encode-delay-in-event-name
  (testing ":after {5000 :timeout} → event=\"after.5000\""
    (let [out (scxml/spec->scxml after-machine)]
      (is (str/includes? out "event=\"after.5000\""))
      (is (str/includes? out "target=\"timeout\"")))))

(deftest emit-machine-level-on-not-dropped
  (testing "rf2-ee38b.21 — a top-level (machine-level) :on fallback is
            emitted (as a documented <transition> under <scxml>) rather
            than silently dropped (the parser-side P2 mirror). W3C SCXML
            has no clean root-fallback slot so this can't round-trip,
            but the topology survives the export."
    (let [out (scxml/spec->scxml machine-level-on-machine)]
      (is (str/includes? out "machine-level")
          "a comment documents the inherited fallback")
      (is (str/includes? out "event=\"logout\"")
          "the machine-level :logout transition is emitted")
      ;; the per-state :go transition still emits normally
      (is (str/includes? out "event=\"go\"")))))

(deftest emit-always-transitions-omit-event-attribute
  (testing ":always candidates render as eventless <transition>s
            (no event= attribute; target= + optional cond= only)"
    (let [out (scxml/spec->scxml always-machine)]
      ;; Both attribute orders are equally valid SCXML; assert
      ;; semantic content, not lexical order.
      (is (str/includes? out "target=\"ready\""))
      ;; rf2-mnp93.1 — guard hex-escaped (`:ready?` → `ready_3f`).
      (is (str/includes? out "cond=\"ready_3f\""))
      (is (str/includes? out "<transition target=\"blocked\"/>"))
      ;; Eventless transitions must not carry event= — confirm by
      ;; searching for the malformed combination.
      (is (not (re-find #"event=\"\"" out))))))

(deftest emit-parallel-machine-uses-parallel-element
  (testing ":type :parallel emits a <parallel> wrapper with region children"
    (let [out (scxml/spec->scxml parallel-machine)]
      (is (str/includes? out "<parallel id=\"rf2_parallel_root\""))
      ;; rf2-mnp93.7 — region children carry fully-qualified ids, so the
      ;; region's `initial` references the qualified child id.
      (is (str/includes? out "<state id=\"data\" initial=\"data___nothing\""))
      (is (str/includes? out "<state id=\"form\" initial=\"form___neutral\""))
      (is (str/includes? out "</parallel>")))))

;; ---------------------------------------------------------------------------
;; Round-trip property

(defn- round-trips? [spec]
  (= spec (-> spec scxml/spec->scxml scxml/scxml->spec)))

(deftest round-trip-flat-machine
  (testing "idle-loading-success-error round-trips through SCXML"
    (is (round-trips? idle-loading-success-error))))

(deftest round-trip-compound-machine
  (testing "compound machine with nested states round-trips"
    (is (round-trips? compound-machine))))

(deftest round-trip-namespaced-machine
  (testing "namespaced ids round-trip via dot-separation"
    (is (round-trips? namespaced-machine))))

(deftest round-trip-guarded-machine
  (testing "guards round-trip via cond= attribute"
    (is (round-trips? guarded-machine))))

(deftest round-trip-after-machine
  (testing ":after timers round-trip via event=\"after.<ms>\""
    (is (round-trips? after-machine))))

(deftest round-trip-always-machine
  (testing ":always eventless transitions round-trip"
    (is (round-trips? always-machine))))

(deftest round-trip-parallel-machine
  (testing ":type :parallel + :regions round-trips through <parallel>"
    (is (round-trips? parallel-machine))))

(deftest round-trip-vector-path-target
  (testing "rf2-csq75 — a vector-path transition target round-trips as
            the SAME vector, NOT collapsed to a namespaced keyword"
    ;; The exact repro from the bead: the target must come back a vector.
    (let [spec {:initial :idle
                :states  {:idle          {:on {:login [:authenticated :browsing]}}
                          :authenticated {:initial :browsing
                                          :states  {:browsing {}}}}}
          back (-> spec scxml/spec->scxml scxml/scxml->spec)]
      (is (= [:authenticated :browsing]
             (get-in back [:states :idle :on :login]))
          "vector target must NOT collapse to :authenticated/browsing")
      (is (vector? (get-in back [:states :idle :on :login]))
          "the decoded target is a vector, not a namespaced keyword")
      (is (= spec back) "the whole spec round-trips exactly"))
    ;; The colon path-separator must NOT collide with namespaced keywords:
    ;; a sibling :authenticated/browsing namespaced-keyword target stays a
    ;; keyword while the vector path stays a vector.
    (is (round-trips? vector-path-target-machine))
    (is (round-trips? vector-path-namespaced-segment-machine))
    (testing "rf2-mnp93.1/.7 — the emitted SCXML uses `___` (not `:`)
              between vector segments (the `:` of csq75 is not a valid
              xsd:ID char); the dot-joined ambiguous form never appears"
      (let [out (scxml/spec->scxml vector-path-target-machine)]
        (is (str/includes? out "target=\"authenticated___browsing\"")
            "vector path joins with the `___` path-segment separator")
        (is (not (str/includes? out "target=\"authenticated.browsing\""))
            "the dot-joined (ambiguous) form must NOT appear")
        (is (not (str/includes? out "authenticated:browsing"))
            "the `:` separator (not a valid xsd:ID char) must NOT appear")))
    (testing "a namespaced-keyword target stays a namespaced keyword
              (no false vector promotion)"
      (let [spec {:initial :a
                  :states  {:a {:on {:go :auth/login}}
                            :auth/login {}}}
            back (-> spec scxml/spec->scxml scxml/scxml->spec)]
        (is (= :auth/login (get-in back [:states :a :on :go]))
            "single namespaced keyword must NOT become [:auth :login]")
        (is (keyword? (get-in back [:states :a :on :go])))))))

;; ---------------------------------------------------------------------------
;; Error cases

(deftest spec->scxml-rejects-invalid-spec
  (testing "missing :initial throws :scxml/invalid-spec"
    (is (thrown? #?(:clj clojure.lang.ExceptionInfo :cljs js/Error)
                 (scxml/spec->scxml {:states {:idle {}}}))))
  (testing "missing :states throws :scxml/invalid-spec"
    (is (thrown? #?(:clj clojure.lang.ExceptionInfo :cljs js/Error)
                 (scxml/spec->scxml {:initial :idle}))))
  (testing "parallel without :regions throws"
    (is (thrown? #?(:clj clojure.lang.ExceptionInfo :cljs js/Error)
                 (scxml/spec->scxml {:type :parallel})))))

(deftest scxml->spec-rejects-non-string
  (testing "non-string input throws :scxml/parse-error"
    (is (thrown? #?(:clj clojure.lang.ExceptionInfo :cljs js/Error)
                 (scxml/scxml->spec nil)))
    (is (thrown? #?(:clj clojure.lang.ExceptionInfo :cljs js/Error)
                 (scxml/scxml->spec 42)))))

(deftest scxml->spec-rejects-missing-root
  (testing "input without <scxml> throws :scxml/parse-error"
    (is (thrown? #?(:clj clojure.lang.ExceptionInfo :cljs js/Error)
                 (scxml/scxml->spec "<not-scxml/>")))))

;; ---------------------------------------------------------------------------
;; Documentation of lossy features
;;
;; These tests don't assert error behaviour — they document, via
;; canonical fixtures, which features *don't* survive the round-trip
;; bit-for-bit. The current list is empty (every fixture above round-
;; trips) — the comment is the contract. If a future fixture is
;; added here that doesn't round-trip, name the loss explicitly.

(deftest round-trip-property-pinned-on-supported-subset
  (testing "every fixture in this ns round-trips through SCXML —
            extend with explicit-loss tests when adding lossy
            features (e.g. :spawn-all rows)"
    (doseq [[name spec] [["idle-loading-success-error" idle-loading-success-error]
                         ["compound-machine"            compound-machine]
                         ["namespaced-machine"          namespaced-machine]
                         ["guarded-machine"             guarded-machine]
                         ["after-machine"               after-machine]
                         ["always-machine"              always-machine]
                         ["parallel-machine"            parallel-machine]
                         ["vector-path-target-machine"  vector-path-target-machine]
                         ["vector-path-namespaced-segment-machine"
                          vector-path-namespaced-segment-machine]]]
      (testing name
        (is (= spec (-> spec scxml/spec->scxml scxml/scxml->spec)))))))

(deftest round-trip-machine-level-on-is-lossy
  (testing "rf2-ee38b.21 — a machine-level (top-level) :on fallback is
            EXPORTED (no longer silently dropped) but does NOT round-trip:
            W3C SCXML has no root-fallback-transition slot, and the
            import side drops root-level transitions. Naming the loss
            explicitly per this section's contract."
    (let [spec machine-level-on-machine
          back (-> spec scxml/spec->scxml scxml/scxml->spec)]
      (is (not= spec back) "the top-level :on does not survive the import")
      (is (nil? (:on back)) "the inherited fallback is lost on import")
      (is (= (:states spec) (:states back))
          "the per-state topology DOES round-trip"))))

;; ---------------------------------------------------------------------------
;; :on-done (XState onDone) — W3C SCXML done.state.<id> (rf2-41goo)
;;
;; SCXML §3.7: reaching a `<final>` child generates `done.state.<id>` into
;; the internal queue, which an enclosing `<transition event="done.state.
;; <id>">` takes. Pre-rf2-41goo the emitter projected the `<final>` child
;; but NOT the onDone transition — a silently lossy round-trip.

(def compound-on-done-machine
  "Spec 005 example: a compound `:flow` whose `:on-done` advances to the
  SIBLING `:next` when its `:final?` `:paid` is reached."
  {:initial :flow
   :states  {:flow {:initial :collecting
                    :on-done :next
                    :states  {:collecting {:on {:submit :submitting}}
                              :submitting {:on {:ok :paid}}
                              :paid       {:final? true}}}
             :next {:on {:reset :flow}}}})

(def parallel-on-done-machine
  "Spec 005 example: a parallel-root `:on-done` runs action-only (no
  :target). The action survives only as a comment (lossy, like every
  action), so it round-trips to `:on-done {}` (the empty completion
  spec) — the COMPLETION TOPOLOGY survives; the action is named-lossy."
  {:type    :parallel
   :on-done {}
   :regions {:fetch    {:initial :loading :states {:loading {:on {:loaded :done}} :done {:final? true}}}
             :validate {:initial :checking :states {:checking {:on {:ok :done}} :done {:final? true}}}}})

(deftest emit-compound-on-done-renders-done-state-transition
  (testing "rf2-41goo — a compound `:on-done` emits the W3C SCXML
            `<transition event=\"done.state.<compound-id>\" target=...>`
            INSIDE the compound's own <state> (SCXML §3.7)"
    (let [out (scxml/spec->scxml compound-on-done-machine)]
      (is (str/includes? out "event=\"done.state.flow\"")
          "the done.state.<compound> completion event")
      (is (str/includes? out "target=\"next\"")
          "advances to the sibling :next"))))

(deftest emit-parallel-on-done-renders-parallel-done-state
  (testing "rf2-41goo — a parallel-root `:on-done` emits
            `<transition event=\"done.state.rf2_parallel_root\">` inside
            the <parallel> element (the whole-parallel completion)"
    (let [out (scxml/spec->scxml parallel-on-done-machine)]
      (is (str/includes? out "event=\"done.state.rf2_parallel_root\"")
          "the whole-parallel done.state completion event"))))

(deftest round-trip-compound-on-done
  (testing "rf2-41goo — a compound `:on-done` (sibling target) round-trips
            through `done.state.<id>` back to `:on-done`"
    (let [spec compound-on-done-machine
          back (-> spec scxml/spec->scxml scxml/scxml->spec)]
      (is (= spec back) "the compound :on-done topology round-trips exactly")
      (is (= :next (get-in back [:states :flow :on-done]))
          ":on-done reconstructs on the compound node"))))

(deftest round-trip-parallel-on-done-topology
  (testing "rf2-41goo — a parallel-root `:on-done` round-trips its
            COMPLETION topology (the action is named-lossy like every
            action — survives only as a comment, so the spec carries the
            empty completion form `{}`)"
    (let [spec parallel-on-done-machine
          back (-> spec scxml/spec->scxml scxml/scxml->spec)]
      (is (= spec back) "the parallel completion topology round-trips")
      (is (contains? back :on-done) "the parallel-root :on-done survives import"))))

(deftest no-on-done-emits-no-done-state-transition
  (testing "rf2-41goo — a compound with no :on-done emits no done.state
            transition (no false-positive completion edge)"
    (let [out (scxml/spec->scxml compound-machine)]
      (is (not (str/includes? out "done.state"))))))

;; ---------------------------------------------------------------------------
;; Injective, xsd:ID-conformant id codec (rf2-mnp93.1/.2/.3/.7/.8)
;;
;; The pre-mnp93.1 codec was NON-INJECTIVE: the `.`-as-ns/name separator
;; could not recover a keyword whose namespace itself contained dots
;; (`:my.app.auth/login` → `"my.app.auth.login"` → `:my/app.auth.login`),
;; and the csq75 `:`-as-path separator emitted ids that are not valid
;; xsd:ID (NCName) tokens. The codec now hex-escapes every keyword
;; ns/name char and uses the reserved `__` (ns/name) and `___` (path)
;; markers the escaper can provably never emit, so the round-trip is
;; EXACT for ANY keyword and every emitted id is a valid xsd:ID.

(def multi-dot-ns-machine
  "rf2-mnp93.1 — the P1 repro: a multi-segment-namespace keyword EVENT.
  Pre-fix `:my.app.auth/login` encoded to `\"my.app.auth.login\"` and
  decoded to the wrong `:my/app.auth.login` (ns boundary lost)."
  {:initial :idle
   :states  {:idle   {:on {:my.app.auth/login :active}}
             :active  {:final? true}}})

(def multi-dot-ns-state-machine
  "rf2-mnp93.1 — a multi-segment-namespace keyword STATE id."
  {:initial :rf.app/idle
   :states  {:rf.app/idle {:on {:go :done}}
             :done        {:final? true}}})

(def dotted-name-machine
  "rf2-mnp93.1 — a non-namespaced keyword whose NAME contains dots."
  {:initial :a.b.c
   :states  {:a.b.c {:on {:go :d}}
             :d     {}}})

(def multi-dot-guard-machine
  "rf2-mnp93.2 — a multi-segment-namespace GUARD keyword. Pre-fix the
  guard `cond=` decoder never split the namespace at all, so even a
  single-dot `:auth/valid?` lost its namespace."
  {:initial :idle
   :states  {:idle {:on {:go {:target :a :guard :my.app.auth/valid?}}}
             :a    {}}})

(def single-ns-guard-machine
  "rf2-mnp93.2 — the bead's exact single-dot guard repro: `:auth/valid?`."
  {:initial :idle
   :states  {:idle {:on {:go {:target :a :guard :auth/valid?}}}
             :a    {}}})

(def reserved-prefix-after-event-machine
  "rf2-mnp93.3 — a USER event named `:after.foo` must NOT be reclassified
  as an `:after` timer. The codec escapes the literal `.` so the encoded
  event no longer starts with the synthetic `after.` prefix."
  {:initial :idle
   :states  {:idle {:on {:after.foo :done}}
             :done {:final? true}}})

(def reserved-prefix-done-event-machine
  "rf2-mnp93.3 — a USER event named `:done.state.flow` must NOT be
  reclassified as `:on-done`."
  {:initial :idle
   :states  {:idle {:on {:done.state.flow :done}}
             :done {:final? true}}})

(def reserved-prefix-after-ns-machine
  "rf2-mnp93.3 — `after` is a plausible event NAMESPACE; `:after/foo`
  must stay an ordinary `:on` event."
  {:initial :idle
   :states  {:idle {:on {:after/foo :done}}
             :done {:final? true}}})

(def nested-same-name-machine
  "rf2-mnp93.7 — two states share the local name `:idle` under different
  compound parents. Pre-fix both emitted a BARE `<state id=\"idle\">`
  (duplicate xsd:ID — invalid SCXML) and `:`-joined path targets (not a
  valid xsd:ID). The qualified-id codec emits unique ids and resolvable
  targets, and the round-trip stays exact."
  {:initial :a
   :states  {:a {:initial :idle :states {:idle {:on {:go [:b :idle]}}}}
             :b {:initial :idle :states {:idle {:on {:back [:a :idle]}}}}}})

(def mixed-candidate-after-machine
  "rf2-mnp93.8 — an `:after` vector MIXING a candidate-map with a
  bare-target. Pre-fix the decoder collapsed `{:target :b}` to `:b`, so
  the round-trip was value-UNEQUAL to the literal input."
  {:initial :idle
   :states  {:idle {:after {1000 [{:target :a :guard :g1} {:target :b}]}}
             :a    {}
             :b    {}}})

(def mixed-candidate-on-machine
  "rf2-mnp93.8 — an `:on` vector mixing a candidate-map with a bare-target."
  {:initial :idle
   :states  {:idle {:on {:go [{:target :a :guard :g1} {:target :b}]}}
             :a    {}
             :b    {}}})

(deftest round-trip-multi-dot-ns-keyword
  (testing "rf2-mnp93.1 — a multi-segment-namespace keyword EVENT
            round-trips EXACTLY (the P1 corruption is fixed)"
    (is (round-trips? multi-dot-ns-machine))
    (let [back (-> multi-dot-ns-machine scxml/spec->scxml scxml/scxml->spec)]
      (is (= :my.app.auth/login
             (-> back :states :idle :on keys first))
          "the multi-dot namespace is recovered, not corrupted to :my/app.auth.login")))
  (testing "rf2-mnp93.1 — a multi-segment-namespace keyword STATE id
            round-trips exactly"
    (is (round-trips? multi-dot-ns-state-machine)))
  (testing "rf2-mnp93.1 — a non-namespaced keyword with dots in its NAME
            round-trips exactly (not split into a namespaced keyword)"
    (is (round-trips? dotted-name-machine))
    (let [back (-> dotted-name-machine scxml/spec->scxml scxml/scxml->spec)]
      (is (= :a.b.c (-> back :initial))
          ":a.b.c stays a single keyword, NOT :a/b.c"))))

(deftest injective-distinct-keywords-distinct-ids
  (testing "rf2-mnp93.1 — two keywords the OLD codec mapped to the SAME
            id (`:my.app.auth/login` and `:my/app.auth.login` both →
            \"my.app.auth.login\") now map to DISTINCT ids AND each
            round-trips back to itself"
    (let [k1 :my.app.auth/login
          k2 :my/app.auth.login
          m  (fn [k] {:initial :s0 :states {:s0 {:on {k :s1}} :s1 {}}})
          id (fn [k] (->> (scxml/spec->scxml (m k))
                          (re-find #"event=\"([^\"]*)\"")
                          second))]
      (is (not= (id k1) (id k2)) "distinct keywords produce distinct ids")
      (is (round-trips? (m k1)))
      (is (round-trips? (m k2)))))
  (testing "rf2-mnp93.1 — literal underscores in a keyword name never
            collide with the `__`/`___` reserved markers (escaped to _5f)"
    (doseq [k [:a__b :a___b :a_b]]
      (is (round-trips? {:initial :s0 :states {:s0 {:on {k :s1}} :s1 {}}})
          (str "round-trip exact for " k)))))

(deftest round-trip-guard-keyword-namespace
  (testing "rf2-mnp93.2 — a single-dot namespaced GUARD round-trips
            exactly (the bead's exact repro: `:auth/valid?`)"
    (is (round-trips? single-ns-guard-machine))
    (let [back (-> single-ns-guard-machine scxml/spec->scxml scxml/scxml->spec)]
      (is (= :auth/valid? (get-in back [:states :idle :on :go :guard]))
          "the guard keeps its namespace, NOT collapsed to :auth.valid?")))
  (testing "rf2-mnp93.2 — a multi-segment-namespace guard round-trips exactly"
    (is (round-trips? multi-dot-guard-machine))))

(deftest round-trip-reserved-prefix-user-events
  (testing "rf2-mnp93.3 — a user event `:after.foo` stays an `:on` event,
            NOT reclassified as an `:after` timer"
    (is (round-trips? reserved-prefix-after-event-machine))
    (let [back (-> reserved-prefix-after-event-machine scxml/spec->scxml scxml/scxml->spec)]
      (is (contains? (get-in back [:states :idle :on]) :after.foo)
          ":after.foo survives as an :on event")
      (is (nil? (get-in back [:states :idle :after]))
          "no spurious :after timer is synthesised")))
  (testing "rf2-mnp93.3 — a user event `:done.state.flow` stays an `:on`
            event, NOT reclassified as `:on-done`"
    (is (round-trips? reserved-prefix-done-event-machine))
    (let [back (-> reserved-prefix-done-event-machine scxml/spec->scxml scxml/scxml->spec)]
      (is (contains? (get-in back [:states :idle :on]) :done.state.flow)
          ":done.state.flow survives as an :on event")
      (is (not (contains? (get-in back [:states :idle]) :on-done))
          "no spurious :on-done is synthesised")))
  (testing "rf2-mnp93.3 — `after` as an event NAMESPACE (`:after/foo`)
            stays an ordinary :on event"
    (is (round-trips? reserved-prefix-after-ns-machine))))

(deftest round-trip-nested-same-name-states-unique-ids
  (testing "rf2-mnp93.7 — two same-named nested states emit UNIQUE
            xsd:IDs (no duplicate `id=\"idle\"`) and the round-trip is exact"
    (is (round-trips? nested-same-name-machine))
    (let [out (scxml/spec->scxml nested-same-name-machine)]
      (is (not (str/includes? out "id=\"idle\""))
          "no bare duplicate `id=\"idle\"` — ids are path-qualified")
      (is (str/includes? out "id=\"a___idle\"") "the a-region idle is qualified")
      (is (str/includes? out "id=\"b___idle\"") "the b-region idle is qualified"))))

(deftest emitted-ids-are-xsd-id-conformant
  (testing "rf2-mnp93.7 — no emitted id / target / initial attribute
            contains a `:` (not a valid xsd:ID / NCName char). The csq75
            `:`-path separator is superseded by the `___` marker."
    (doseq [[label spec] [["nested-same-name" nested-same-name-machine]
                          ["vector-path"      vector-path-target-machine]
                          ["compound"         compound-machine]
                          ["namespaced"       namespaced-machine]
                          ["multi-dot-ns"     multi-dot-ns-machine]
                          ["parallel"         parallel-machine]]]
      (testing label
        (let [out (scxml/spec->scxml spec)]
          (is (nil? (re-find #"(?:id|target|initial)=\"[^\"]*:[^\"]*\"" out))
              "no id/target/initial attribute carries a `:`"))))))

(deftest round-trip-mixed-candidate-vectors
  (testing "rf2-mnp93.8 — an `:after` vector mixing a candidate-map with a
            bare-target round-trips with VALUE EQUALITY (the bare-target
            map is no longer collapsed)"
    (is (round-trips? mixed-candidate-after-machine))
    (let [back (-> mixed-candidate-after-machine scxml/spec->scxml scxml/scxml->spec)]
      (is (= [{:target :a :guard :g1} {:target :b}]
             (get-in back [:states :idle :after 1000]))
          "{:target :b} survives as a map, not collapsed to :b")))
  (testing "rf2-mnp93.8 — the same holds for an `:on` mixed vector"
    (is (round-trips? mixed-candidate-on-machine)))
  (testing "rf2-mnp93.8 — a SOLE target-only candidate still collapses to
            the bare-keyword shorthand (the canonical `:on {:event :tgt}`)"
    (let [spec {:initial :idle :states {:idle {:on {:go :a}} :a {}}}
          back (-> spec scxml/spec->scxml scxml/scxml->spec)]
      (is (= :a (get-in back [:states :idle :on :go]))
          "a single target-only transition stays the bare keyword"))))

(deftest round-trip-property-mnp93-fixtures
  (testing "rf2-mnp93 — every new codec-faithfulness fixture round-trips
            EXACTLY (encode→decode = original)"
    (doseq [[name spec] [["multi-dot-ns-machine"               multi-dot-ns-machine]
                         ["multi-dot-ns-state-machine"         multi-dot-ns-state-machine]
                         ["dotted-name-machine"                dotted-name-machine]
                         ["multi-dot-guard-machine"            multi-dot-guard-machine]
                         ["single-ns-guard-machine"            single-ns-guard-machine]
                         ["reserved-prefix-after-event-machine" reserved-prefix-after-event-machine]
                         ["reserved-prefix-done-event-machine"  reserved-prefix-done-event-machine]
                         ["reserved-prefix-after-ns-machine"    reserved-prefix-after-ns-machine]
                         ["nested-same-name-machine"           nested-same-name-machine]
                         ["mixed-candidate-after-machine"      mixed-candidate-after-machine]
                         ["mixed-candidate-on-machine"         mixed-candidate-on-machine]]]
      (testing name
        (is (= spec (-> spec scxml/spec->scxml scxml/scxml->spec)))))))
