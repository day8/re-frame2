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

(def parallel-machine
  "A `:type :parallel` machine with two regions."
  {:type    :parallel
   :regions {:data {:initial :nothing
                    :states  {:nothing {:on {:fetch :loading}}
                              :loading {}}}
             :form {:initial :neutral
                    :states  {:neutral {:on {:submit :correct}}
                              :correct {:final? true}}}}})

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
                         ["parallel-machine"            parallel-machine]]]
      (testing name
        (is (= spec (-> spec scxml/spec->scxml scxml/scxml->spec)))))))
