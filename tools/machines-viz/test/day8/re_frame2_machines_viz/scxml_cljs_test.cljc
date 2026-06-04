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
  (testing "compound states emit nested <state> blocks with initial="
    (let [out (scxml/spec->scxml compound-machine)]
      (is (str/includes? out "<state id=\"authenticated\" initial=\"browsing\""))
      (is (str/includes? out "<state id=\"browsing\""))
      (is (str/includes? out "<state id=\"paying\"")))))

(deftest emit-namespaced-ids-use-dot-separator
  (testing ":auth/idle → id=\"auth.idle\""
    (let [out (scxml/spec->scxml namespaced-machine)]
      (is (str/includes? out "initial=\"auth.idle\""))
      (is (str/includes? out "<state id=\"auth.idle\""))
      (is (str/includes? out "<state id=\"auth.loading\""))
      (is (str/includes? out "event=\"rf.load\"")))))

(deftest emit-guards-render-as-cond-attribute
  (testing "guarded transitions render with cond= on <transition>"
    (let [out (scxml/spec->scxml guarded-machine)]
      (is (str/includes? out "cond=\"ready?\"")))))

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
      (is (str/includes? out "cond=\"ready?\""))
      (is (str/includes? out "<transition target=\"blocked\"/>"))
      ;; Eventless transitions must not carry event= — confirm by
      ;; searching for the malformed combination.
      (is (not (re-find #"event=\"\"" out))))))

(deftest emit-parallel-machine-uses-parallel-element
  (testing ":type :parallel emits a <parallel> wrapper with region children"
    (let [out (scxml/spec->scxml parallel-machine)]
      (is (str/includes? out "<parallel id=\"rf2_parallel_root\""))
      (is (str/includes? out "<state id=\"data\" initial=\"nothing\""))
      (is (str/includes? out "<state id=\"form\" initial=\"neutral\""))
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
    (testing "the emitted SCXML uses `:` (not `.`) between vector segments"
      (let [out (scxml/spec->scxml vector-path-target-machine)]
        (is (str/includes? out "target=\"authenticated:browsing\"")
            "vector path joins with the `:` path-segment separator")
        (is (not (str/includes? out "target=\"authenticated.browsing\""))
            "the dot-joined (ambiguous) form must NOT appear")))
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
