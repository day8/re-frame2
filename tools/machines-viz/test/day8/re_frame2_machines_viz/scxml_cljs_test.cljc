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
  - Error cases throw `ex-info` carrying `:rf.error/id :scxml/*` (the
    canonical discriminator; the message is the human sentence + token)."
  (:require #?(:clj  [clojure.test :refer [deftest is testing]]
               :cljs [cljs.test    :refer-macros [deftest is testing]])
            [clojure.string :as str]
            [clojure.walk :as walk]
            ;; rf2-qy8p — the canonical recursive grammar gate. The import
            ;; regressions below assert the POSTCONDITION `scxml->spec` now
            ;; carries: a successful import is a definition every emitter,
            ;; the chart projector and `reg-machine` accept.
            [day8.re-frame2-machines-viz.grammar :as g]
            [day8.re-frame2-machines-viz.scxml :as scxml]))

(defn- deep-strings
  "Every string anywhere in `m` (deep walk)."
  [m]
  (let [acc (volatile! [])]
    (walk/postwalk (fn [x] (when (string? x) (vswap! acc conj x)) x) m)
    @acc))

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
                             :states  {:browsing {:on {:checkout :paying}}
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
  (testing "rf2-mnp93.1 / rf2-t69tdo — :auth/idle → id=\"auth-idle\" (the `-`
            ns/name marker; the keyword name/ns chars are hex-escaped so
            the codec is fully injective and xsd:ID-conformant). The `-`
            marker — which the escaper never emits (a literal `-` → `_2d`)
            — can never grow into a `___` path-run at the ns/name boundary,
            unlike the pre-fix `__` marker."
    (let [out (scxml/spec->scxml namespaced-machine)]
      (is (str/includes? out "initial=\"auth-idle\""))
      (is (str/includes? out "<state id=\"auth-idle\""))
      (is (str/includes? out "<state id=\"auth-loading\""))
      (is (str/includes? out "event=\"rf-load\""))
      ;; The old non-injective `.`-as-ns/name form must be gone.
      (is (not (str/includes? out "id=\"auth.idle\"")))
      ;; rf2-t69tdo — the ns/name boundary must NOT be a `__` run that could
      ;; collide with the `___` path separator.
      (is (not (str/includes? out "id=\"auth__idle\""))))))

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
      ;; rf2-0pp6as — a targeted internal-default transition now carries
      ;; the explicit `type="internal"` axis (so the export does not inherit
      ;; SCXML's EXTERNAL targeted-transition default). The `:always`
      ;; candidate is eventless (no `event=`) but is still a targeted
      ;; internal-default transition, so it carries `type="internal"`.
      (is (str/includes? out "<transition target=\"blocked\" type=\"internal\"/>"))
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

(deftest round-trip-non-latin1-ids
  (testing "rf2-qgtcvy — state ids above U+00FF (CJK) round-trip EXACTLY.
            The pre-fix `_<var-hex>` encode + `_XX` (2-hex) decode mis-read
            `:开始` (`_5f00_59cb`) as `:_00Ycb`; the fixed-width `_u<4-hex>`
            codec is now reversible across the whole code-unit range"
    (let [begin (keyword "开始")            ;; U+5F00 U+59CB
          done  (keyword "结束")            ;; U+7ED3 U+675F
          spec  {:initial begin
                 :states  {begin {:on {:go done}}
                           done  {}}}
          back  (-> spec scxml/spec->scxml scxml/scxml->spec)]
      (is (= spec back) "the whole CJK-id machine round-trips exactly")
      (is (= done (get-in back [:states begin :on :go]))
          "the CJK transition target survives")))
  (testing "a bare CJK id decodes back to the SAME keyword (not a vector /
            mis-split), since a plain name carries no `-` ns/name marker"
    (let [k    (keyword "结束")
          spec {:initial k :states {k {}}}
          back (-> spec scxml/spec->scxml scxml/scxml->spec)]
      (is (= spec back))
      (is (keyword? (:initial back)))
      (is (= "结束" (name (:initial back))))))
  (testing "rf2-t69tdo — a NAMESPACED CJK id (`:开始/名`) round-trips EXACTLY.
            #5762 (rf2-qgtcvy) had to scope its CJK round-trip down to PLAIN
            (non-namespaced) CJK because the pre-fix `__` ns/name marker +
            the escaped (leading `_u…`) name segment minted `___` at the
            boundary, so the namespaced keyword mis-decoded to a vector.
            With the `-` ns/name marker the namespaced case round-trips too."
    (let [k    (keyword "开始" "名")          ;; ns 开始 (U+5F00 U+59CB), name 名 (U+540D)
          spec {:initial k
                :states  {k {:on {(keyword "登录" "成功") :done}}
                          :done {:final? true}}}
          back (-> spec scxml/spec->scxml scxml/scxml->spec)]
      (is (= spec back) "the whole namespaced-CJK machine round-trips exactly")
      (is (keyword? (:initial back)) "the namespaced CJK id stays a keyword")
      (is (= "开始" (namespace (:initial back))) "the CJK namespace survives")
      (is (= "名"  (name (:initial back)))       "the CJK name survives")
      (is (keyword? (-> back :states (get k) :on keys first))
          "the namespaced-CJK EVENT key stays a keyword, not a mis-split vector"))))

(deftest round-trip-ns-name-boundary-escaped-leading-char
  (testing "rf2-t69tdo — a namespaced keyword whose NAME segment begins with
            an escaped char (leading `_…`) round-trips EXACTLY, not to a
            vector. Pre-fix the `<ns>` + `__` + `_<name-escape>` boundary
            minted `___` (the path separator), so `:a/-b` decoded to the
            vector `[:a :2db]` instead of the namespaced keyword."
    (doseq [k [:a/-b            ;; the bead's exact repro (name -b → _2db)
               :auth/-flag      ;; another leading-hyphen name
               :x/?ready        ;; leading `?` name (→ _3f)
               (keyword "登录" "成功")]] ;; namespaced CJK (leading _u…)
      (let [spec {:initial :s0
                  :states  {:s0 {:on {:go {:target :s1 :guard k}}}
                            :s1 {}}}
            back (-> spec scxml/spec->scxml scxml/scxml->spec)]
        (is (= spec back)
            (str "namespaced keyword with escaped-leading-char name round-trips: " k))
        (is (= k (get-in back [:states :s0 :on :go :guard]))
            (str "the guard keyword keeps its namespace + name exactly: " k))))
    ;; and as a STATE / target id (not just a guard): the id must NOT
    ;; mis-decode into a vector path.
    (let [spec {:initial :a/-b
                :states  {:a/-b {:on {:go :done}}
                          :done {:final? true}}}
          back (-> spec scxml/spec->scxml scxml/scxml->spec)]
      (is (= spec back) "an :a/-b STATE id round-trips exactly")
      (is (keyword? (:initial back)) "the state id stays a keyword, not a vector"))
    ;; and INSIDE a vector-path target: a namespaced segment whose name
    ;; leads with an escape must not blur the `___` path boundary.
    (let [spec {:initial :a
                :states  {:a       {:on {:go [:auth/-region :browsing]}}
                          :auth/-region {:initial :browsing
                                         :states  {:browsing {}}}}}
          back (-> spec scxml/spec->scxml scxml/scxml->spec)]
      (is (= spec back) "a vector path with a leading-escape-name namespaced segment round-trips")
      (is (= [:auth/-region :browsing] (get-in back [:states :a :on :go]))
          "the vector target stays a 2-segment vector, not mis-split"))))

(deftest parse-attrs-accepts-single-quoted-attributes
  (testing "rf2-qgtcvy — `parse-attrs` matched only double-quoted values, so
            an imported `<state id='idle'>` keyed the state under nil. Every
            attribute in a single-quoted document must parse identically"
    (let [spec   {:initial :idle
                  :states  {:idle {:on {:go :done}}
                            :done {:final? true}}}
          xml    (scxml/spec->scxml spec)
          ;; the emitter escapes `"`/`'` INSIDE values (&quot;/&apos;), so raw
          ;; quotes are only attribute delimiters — swapping them wholesale
          ;; yields a valid single-quoted document.
          single (str/replace xml "\"" "'")]
      (is (str/includes? single "id='"))
      (is (not (str/includes? single "id=\"")))
      (is (= (scxml/scxml->spec xml) (scxml/scxml->spec single))
          "single-quoted attributes parse identically to double-quoted")
      (is (= spec (scxml/scxml->spec single))
          "and the single-quoted document round-trips to the original spec"))))

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
                 (scxml/spec->scxml {:type :parallel}))))
  (testing "rf2-bj3sxo — a malformed flat-ROOT `:on` (non-map fallback slot)
            is rejected as the clean :scxml/invalid-spec, NOT an uncaught host
            ISeq exception (the emit path first validates via
            grammar/valid-definition?)"
    (let [d (try (scxml/spec->scxml {:initial :a :states {:a {}} :on :retry}) nil
                 (catch #?(:clj clojure.lang.ExceptionInfo :cljs :default) e (ex-data e)))]
      (is (= :scxml/invalid-spec (:rf.error/id d))
          "the malformed root :on surfaces the documented invalid-spec outcome")
      ;; the value-free summary carries the canonical structural defect category
      (is (= :rf.error/machine-bad-on-clause
             (get-in d [:spec-summary :defect :category]))
          "the summary carries the canonical bad-on-clause defect category"))))

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
;; EP-0015 — error ex-data carries NO raw payload (rf2-8nzxib)
;;
;; A machine spec can carry a `:data` slot of live runtime values; the
;; thrown error must keep value-FREE diagnostics (category, key SET,
;; counts) — never the raw spec or input (Spec 015 §exception-path).

(deftest invalid-spec-error-omits-raw-spec
  (testing "spec->scxml invalid-spec ex-data carries a value-free summary, not the raw spec"
    (let [secret "patient-record-secret-42"
          ;; Invalid (no :initial/:states) but carries a secret :data slot.
          spec   {:type :machine :data {:diagnosis secret}}
          d      (try (scxml/spec->scxml spec) nil
                      (catch #?(:clj clojure.lang.ExceptionInfo :cljs :default) e (ex-data e)))]
      (is (= :scxml/invalid-spec (:rf.error/id d)) "category preserved")
      (is (not (contains? d :spec)) "no raw :spec slot")
      (is (some? (:spec-summary d)) "value-free summary present")
      (is (not (some #(str/includes? % secret) (deep-strings d)))
          "the secret must not survive anywhere in ex-data"))))

(deftest parallel-invalid-spec-error-omits-raw-spec
  (testing "parallel-without-regions invalid-spec keeps only a value-free summary"
    (let [secret "token-deadbeef"
          spec   {:type :parallel :data {:token secret}}   ;; no :regions
          d      (try (scxml/spec->scxml spec) nil
                      (catch #?(:clj clojure.lang.ExceptionInfo :cljs :default) e (ex-data e)))]
      (is (= :scxml/invalid-spec (:rf.error/id d)))
      (is (not (contains? d :spec)))
      (is (not (some #(str/includes? % secret) (deep-strings d)))
          "the secret must not survive anywhere in ex-data"))))

(deftest parse-error-omits-raw-input
  (testing "scxml->spec non-string input keeps only a value-free summary"
    (let [secret "session-id-cafef00d"
          d      (try (scxml/scxml->spec {:leak secret}) nil   ;; non-string
                      (catch #?(:clj clojure.lang.ExceptionInfo :cljs :default) e (ex-data e)))]
      (is (= :scxml/parse-error (:rf.error/id d)) "category preserved")
      (is (not (contains? d :input)) "no raw :input slot")
      (is (some? (:input-summary d)) "value-free summary present")
      (is (not (some #(str/includes? % secret) (deep-strings d)))
          "the secret must not survive anywhere in ex-data"))))

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
;; Error-final terminal KIND — EP-0011 reply-envelope completion status.
;;
;; Spec 005 §:final? lets a `:final?` leaf carry `:error? true` — an ERROR
;; terminal. This is not decorative: a child finishing via an `:error?` final
;; lowers to the uniform reply envelope as `:status :error` (vs a plain
;; `:final?` child's `:status :ok`) and routes the spawning parent's `:spawn`
;; `:on-error` instead of `:on-done`. The completion KIND the framework acts
;; on must therefore survive the text round-trip — collapsing it silently
;; turns an error completion into a success one.
;;
;; W3C SCXML's `<final>` has no first-class error-terminal concept, so — like
;; the action-name carrier (`data_rf_action`) — the bit rides a re-frame2-
;; specific `data_rf_error_final="true"` custom attribute, which ordinary
;; SCXML consumers ignore.

(def success-and-error-finals-machine
  "Two terminals of distinct KIND: a plain success final + an `:error?`
  error final. Mirrors the chart-projection fixture so the two surfaces
  cover the same terminal-kind distinction."
  {:initial :running
   :states  {:running {:on {:ok :ok :boom :boom}}
             :ok      {:final? true}
             :boom    {:final? true :error? true}}})

(deftest emit-error-final-carries-error-attribute
  (testing "an :error? final emits the re-frame2 carrier
            data_rf_error_final=\"true\" on its <final>; a success final
            does NOT (the bit is the EP-0011 completion status, not decor)"
    (let [out (scxml/spec->scxml success-and-error-finals-machine)]
      (is (str/includes? out "<final id=\"boom\" data_rf_error_final=\"true\"")
          "the error final carries the error-terminal carrier attribute")
      (is (str/includes? out "<final id=\"ok\"")
          "the success final still renders as a plain <final>")
      (is (not (str/includes? out "<final id=\"ok\" data_rf_error_final"))
          "the success final carries NO error-terminal attribute"))))

(deftest round-trip-error-final-preserves-status
  (testing "an :error? final round-trips its :error? bit (the parent's
            :on-error vs :on-done routing must not silently collapse to
            success); a plain :final? stays plain"
    (let [spec success-and-error-finals-machine
          back (-> spec scxml/spec->scxml scxml/scxml->spec)]
      (is (= spec back) "the error-final spec round-trips exactly")
      (is (true? (get-in back [:states :boom :error?]))
          "the error terminal reconstructs as :error? true")
      (is (true? (get-in back [:states :boom :final?]))
          "the error terminal is still :final?")
      (is (nil? (get-in back [:states :ok :error?]))
          "the success terminal carries no :error? bit"))))

;; ---------------------------------------------------------------------------
;; Parallel-ROOT :on / :after ancestor fallback (rf2-656ivk / rf2-m3otj2)
;;
;; A `:type :parallel` ROOT may declare its OWN `:on` (the ancestor fallback,
;; Spec 005 §Root parallel :on) and its OWN `:after` (the timer-driven analog,
;; §Root-level :after). These are DIRECT `<parallel>` children. Pre-fix the
;; SCXML emitter dropped them entirely (only `:on-done` survived); the import
;; recovered only `:on-done`. Both now emit + round-trip, including
;; MULTI-region targets (space-separated W3C `target` id lists) and
;; action-only (targetless) forms.

;; The CANONICAL shorthand for a sole target-only root transition is the bare
;; target vector (`[:a :two]` / `[[:a :x] [:b :y]]`) — the SAME canonicalisation
;; `consume-transitions`/`simplify` apply to every target-only candidate (the
;; `{:target …}` map form decodes back to the bare shorthand, asserted
;; separately below). The round-trip fixtures use the shorthand so the equality
;; is exact.

(def parallel-root-on-single-machine
  "A parallel root :on targeting ONE region — `[:a :two]` (shorthand)."
  {:type    :parallel
   :on      {:one [:a :two]}
   :regions {:a {:initial :one :states {:one {} :two {}}}
             :b {:initial :one :states {:one {} :two {}}}}})

(def parallel-root-on-multi-machine
  "A parallel root :on with MULTIPLE region-qualified targets (shorthand)."
  {:type    :parallel
   :on      {:advance [[:a :x] [:b :y]]}
   :regions {:a {:initial :one :states {:one {} :x {}}}
             :b {:initial :one :states {:one {} :y {}}}}})

(def parallel-root-after-single-machine
  "A parallel root :after targeting ONE region (shorthand)."
  {:type    :parallel
   :after   {500 [:a :two]}
   :regions {:a {:initial :one :states {:one {} :two {}}}
             :b {:initial :one :states {:one {} :two {}}}}})

(def parallel-root-after-multi-machine
  "A parallel root :after with MULTIPLE region-qualified targets (shorthand)."
  {:type    :parallel
   :after   {1000 [[:a :two] [:b :two]]}
   :regions {:a {:initial :one :states {:one {} :two {}}}
             :b {:initial :one :states {:one {} :two {}}}}})

(deftest emit-parallel-root-on-single-region-target
  (testing "rf2-656ivk — a parallel-root :on emits a direct <parallel>
            <transition> with the region-qualified target id"
    (let [out (scxml/spec->scxml parallel-root-on-single-machine)]
      (is (str/includes? out "event=\"one\"") "the root :on event")
      (is (str/includes? out "target=\"a___two\"")
          "the region-qualified target id (region :a substate :two)"))))

(deftest emit-parallel-root-on-multi-region-target-is-space-separated
  (testing "rf2-656ivk — a MULTI-region root :on emits a SPACE-SEPARATED
            target id list (W3C SCXML target grammar)"
    (let [out (scxml/spec->scxml parallel-root-on-multi-machine)]
      (is (str/includes? out "target=\"a___x b___y\"")
          "both region-qualified targets in one space-joined attribute"))))

(deftest emit-parallel-root-after-renders-after-event
  (testing "rf2-m3otj2 — a parallel-root :after emits an after.<delay> direct
            <parallel> transition with the region-qualified target"
    (let [out (scxml/spec->scxml parallel-root-after-single-machine)]
      (is (str/includes? out "event=\"after.500\"") "the delay rides after.<ms>")
      (is (str/includes? out "target=\"a___two\"")))))

(deftest round-trip-parallel-root-on-single
  (testing "rf2-656ivk — a single-region root :on round-trips exactly"
    (is (round-trips? parallel-root-on-single-machine))))

(deftest round-trip-parallel-root-on-multi
  (testing "rf2-656ivk — a multi-region root :on round-trips its
            region-qualified target grammar exactly"
    (is (round-trips? parallel-root-on-multi-machine))))

(deftest round-trip-parallel-root-after-single
  (testing "rf2-m3otj2 — a single-region root :after round-trips exactly"
    (is (round-trips? parallel-root-after-single-machine))))

(deftest round-trip-parallel-root-after-multi
  (testing "rf2-m3otj2 — a multi-region root :after round-trips exactly"
    (is (round-trips? parallel-root-after-multi-machine))))

(deftest round-trip-parallel-root-on-with-guard
  (testing "rf2-656ivk — a guarded root :on round-trips its guard via cond="
    (is (round-trips? {:type    :parallel
                       :on      {:fire {:target [[:a :two] [:b :two]] :guard :armed?}}
                       :regions {:a {:initial :one :states {:one {} :two {}}}
                                 :b {:initial :one :states {:one {} :two {}}}}}))))

(deftest round-trip-parallel-root-on-action-only
  (testing "rf2-656ivk — a TARGETLESS action-only root :on round-trips its
            completion topology (the action is named-lossy — survives only as
            a comment, so it returns the action-bearing internal form)"
    (let [spec {:type    :parallel
                :on      {:ping {:action :log-ping}}
                :regions {:a {:initial :one :states {:one {}}}
                          :b {:initial :one :states {:one {}}}}}
          back (-> spec scxml/spec->scxml scxml/scxml->spec)]
      (is (= spec back) "the action-only root :on round-trips (action name survives)")
      (is (= :log-ping (get-in back [:on :ping :action]))
          "the action name is recovered, NOT collapsed to a forbidden {} block"))))

(deftest round-trip-parallel-root-after-action-only
  (testing "rf2-m3otj2 — a TARGETLESS action-only root :after round-trips"
    (let [spec {:type    :parallel
                :after   {2000 {:action :timeout-log}}
                :regions {:a {:initial :one :states {:one {}}}
                          :b {:initial :one :states {:one {}}}}}
          back (-> spec scxml/spec->scxml scxml/scxml->spec)]
      (is (= spec back) "the action-only root :after round-trips")
      (is (= :timeout-log (get-in back [:after 2000 :action]))))))

(deftest round-trip-parallel-root-on-and-after-together
  (testing "rf2-656ivk / rf2-m3otj2 — a root :on AND a root :after on the same
            parallel machine both survive + round-trip independently"
    (is (round-trips? {:type    :parallel
                       :on      {:go [:a :two]}
                       :after   {1000 [[:a :two] [:b :two]]}
                       :regions {:a {:initial :one :states {:one {} :two {}}}
                                 :b {:initial :one :states {:one {} :two {}}}}}))))

(deftest parallel-root-on-map-form-canonicalises-to-shorthand
  (testing "rf2-656ivk — the `{:target …}` map form of a target-only root :on
            decodes back to the canonical bare-target shorthand (the SAME
            canonicalisation every target-only candidate gets)"
    (let [spec {:type    :parallel
                :on      {:one {:target [:a :two]}}
                :regions {:a {:initial :one :states {:one {} :two {}}}
                          :b {:initial :one :states {:one {} :two {}}}}}
          back (-> spec scxml/spec->scxml scxml/scxml->spec)]
      (is (= [:a :two] (get-in back [:on :one]))
          "the map form canonicalises to the bare target vector"))))

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

;; ---------------------------------------------------------------------------
;; rf2-mnp93.5 — an INTERNAL ACTION transition must NOT round-trip into a
;; Spec-005 FORBIDDEN BLOCK.
;;
;; `:on {:tick {:action :log}}` is an internal action transition: it RUNS an
;; action and leaves the config unchanged (Spec 005 §Transition slots — "omit
;; for internal"). Pre-fix, the action rode an XML COMMENT that the decoder's
;; `strip-comments` discarded BEFORE tokenizing, so the candidate decoded to
;; the EMPTY map `{}` — which Spec 005 §Forbidden transitions (L1335-1346)
;; defines as a FORBIDDEN BLOCK: an enabled internal no-op that CONSUMES the
;; event and shadows every coarser descriptor + ancestor (opts OUT of
;; inheritance). 'Run an action' decoding to 'block the event entirely' is a
;; SEMANTIC INVERSION, not lossy detail. The distinguishing shape feature is
;; the PRESENCE of `:action` (L1346: an action-bearing internal transition
;; halts the walk AND runs the action). The fix lifts the action comment into
;; the candidate so it round-trips as `{:action :log}` — a VALID internal
;; action transition.

(def internal-action-on-machine
  "An INTERNAL action `:on` transition (no `:target`)."
  {:initial :a
   :states  {:a {:on {:tick {:action :log}}}}})

(def internal-action-after-machine
  "An INTERNAL action `:after` transition (no `:target`)."
  {:initial :a
   :states  {:a {:after {1000 {:action :timeout-log}}}}})

(def internal-action-always-machine
  "An INTERNAL action `:always` transition (no `:target`)."
  {:initial :a
   :states  {:a {:always [{:action :poll}]}}})

(def internal-guarded-action-machine
  "An INTERNAL action `:on` transition with a guard (no `:target`)."
  {:initial :a
   :states  {:a {:on {:tick {:action :log :guard :ready?}}}}})

(def internal-ns-action-machine
  "An INTERNAL action `:on` transition whose action is NAMESPACED — the
  action codec must round-trip its namespace too."
  {:initial :a
   :states  {:a {:on {:tick {:action :log/append}}}}})

(deftest internal-action-transition-not-forbidden-block
  (testing "rf2-mnp93.5 — an internal action `:on` round-trips to a VALID
            internal action transition, NOT the `{}` forbidden block"
    (let [back (-> internal-action-on-machine scxml/spec->scxml scxml/scxml->spec)
          cand (get-in back [:states :a :on :tick])]
      (is (= {:action :log} cand)
          "the candidate keeps its :action (NOT the empty {} forbidden block)")
      (is (not= {} cand)
          "explicitly: it is NOT the Spec-005 forbidden block shape")
      (is (contains? cand :action)
          "the distinguishing :action key is present")
      (is (not (contains? cand :target))
          "still internal — no :target")
      (is (= internal-action-on-machine back)
          "exact value round-trip")))

  (testing "rf2-mnp93.5 — internal action `:after` round-trips faithfully"
    (let [back (-> internal-action-after-machine scxml/spec->scxml scxml/scxml->spec)]
      (is (= internal-action-after-machine back))
      (is (= {:action :timeout-log} (get-in back [:states :a :after 1000])))))

  (testing "rf2-mnp93.5 — internal action `:always` round-trips faithfully"
    (let [back (-> internal-action-always-machine scxml/spec->scxml scxml/scxml->spec)]
      (is (= internal-action-always-machine back))
      (is (= [{:action :poll}] (get-in back [:states :a :always])))))

  (testing "rf2-mnp93.5 — an internal action transition WITH a guard keeps
            both :action and :guard (still not a forbidden block)"
    (let [back (-> internal-guarded-action-machine scxml/spec->scxml scxml/scxml->spec)
          cand (get-in back [:states :a :on :tick])]
      (is (= {:action :log :guard :ready?} cand))
      (is (not= {} cand))))

  (testing "rf2-mnp93.5 — a NAMESPACED action round-trips its namespace"
    (let [back (-> internal-ns-action-machine scxml/spec->scxml scxml/scxml->spec)]
      (is (= internal-ns-action-machine back))
      (is (= :log/append (get-in back [:states :a :on :tick :action])))))

  (testing "rf2-mnp93.5 — a genuine FORBIDDEN block ({} — no action, no
            target) still round-trips to `{}` (the action lift-pass only
            recovers a PRESENT action; absence stays absent — no false
            action synthesised)"
    (let [spec {:initial :a :states {:a {:on {:tick {}}}}}
          back (-> spec scxml/spec->scxml scxml/scxml->spec)]
      (is (= {} (get-in back [:states :a :on :tick]))
          "an empty-map forbidden block survives as `{}`, not invented an action"))))

;; ---------------------------------------------------------------------------
;; rf2-m285a — `:type :history` pseudo-states export as W3C `<history>`
;; (NOT `<state>` / `<final>`), preserving the shallow / deep / default-target
;; semantics, and round-trip back to `:type :history` nodes.

(def shallow-history-machine
  "Compound with a SHALLOW `:type :history` pseudo-state targeted by an
  outer transition. The history node is NEVER occupiable — a transition
  to `:hist` resolves to the compound's recorded direct child at runtime.
  Uses the normalised `:deep? false` shape `rf.machines/machine-meta` returns (an
  absent `:deep?` reads as shallow per Spec 005; SCXML's `<history>`
  always carries an explicit `type`, so the decode is `:deep? false`)."
  {:initial :off
   :states  {:off    {:on {:resume [:player :hist]}}
             :player {:initial :stopped
                      :states  {:stopped {:on {:play :playing}}
                                :playing {:on {:stop :stopped}}
                                :hist    {:type :history :deep? false}}
                      :on      {:power-off :off}}}})

(def deep-history-machine
  "Compound with a DEEP history pseudo-state (`:deep? true`)."
  {:initial :off
   :states  {:off    {:on {:resume [:player :hist]}}
             :player {:initial :stopped
                      :states  {:stopped {:on {:play :playing}}
                                :playing {:on {:stop :stopped}}
                                :hist    {:type :history :deep? true}}
                      :on      {:power-off :off}}}})

(def default-target-history-machine
  "History pseudo-state with an explicit `:default-target` (a sibling — a
  direct child of the owning compound)."
  {:initial :off
   :states  {:off    {:on {:resume [:player :hist]}}
             :player {:initial :stopped
                      :states  {:stopped {:on {:play :playing}}
                                :playing {:on {:stop :stopped}}
                                :hist    {:type :history :deep? false
                                          :default-target :playing}}
                      :on      {:power-off :off}}}})

(deftest history-exports-as-w3c-history-element
  (testing "rf2-m285a — a SHALLOW history pseudo-state emits <history
            type=\"shallow\">, NOT a <state>/<final>"
    (let [xml (scxml/spec->scxml shallow-history-machine)]
      (is (str/includes? xml "<history ")
          "a <history> element is emitted")
      (is (str/includes? xml "type=\"shallow\"")
          "shallow history carries type=\"shallow\"")
      ;; The history node id is the qualified path player___hist; assert it
      ;; rides a <history>, never a <state>/<final> for that id.
      (is (not (re-find #"<state id=\"player___hist\"" xml))
          "the history node is NOT exported as an occupiable <state>")
      (is (not (re-find #"<final id=\"player___hist\"" xml))
          "the history node is NOT exported as a <final>")))

  (testing "rf2-m285a — a DEEP history pseudo-state emits type=\"deep\""
    (let [xml (scxml/spec->scxml deep-history-machine)]
      (is (str/includes? xml "<history "))
      (is (str/includes? xml "type=\"deep\""))))

  (testing "rf2-m285a — a history :default-target rides a default
            <transition target=...> inside the <history>"
    (let [xml (scxml/spec->scxml default-target-history-machine)]
      (is (str/includes? xml "<history "))
      ;; the default-target :playing is a sibling — qualified id player___playing
      (is (re-find #"<history[^>]*>\s*<transition target=\"player___playing\"/>" xml)
          "the default transition targets the qualified default-target leaf"))))

(deftest history-round-trips-to-type-history
  (testing "rf2-m285a — a SHALLOW history pseudo-state round-trips to
            `:type :history` (NOT an ordinary state)"
    (let [back (-> shallow-history-machine scxml/spec->scxml scxml/scxml->spec)
          hist (get-in back [:states :player :states :hist])]
      (is (= :history (:type hist))
          "the node decodes back to a :type :history pseudo-state")
      (is (= false (:deep? hist))
          "shallow ⇒ :deep? false")
      (is (= shallow-history-machine back)
          "exact value round-trip")))

  (testing "rf2-m285a — a DEEP history pseudo-state round-trips with :deep? true"
    (let [back (-> deep-history-machine scxml/spec->scxml scxml/scxml->spec)
          hist (get-in back [:states :player :states :hist])]
      (is (= :history (:type hist)))
      (is (= true (:deep? hist)))
      (is (= deep-history-machine back))))

  (testing "rf2-m285a — a :default-target round-trips to its relative
            (sibling-keyword) grammar form"
    (let [back (-> default-target-history-machine scxml/spec->scxml scxml/scxml->spec)
          hist (get-in back [:states :player :states :hist])]
      (is (= :history (:type hist)))
      (is (= :playing (:default-target hist))
          "the default-target decodes back to the sibling keyword")
      (is (= default-target-history-machine back))))

  (testing "rf2-m285a — an absent `:deep?` exports as shallow and decodes to
            the normalised `:deep? false` (Spec 005: absent ⇒ shallow)"
    (let [spec {:initial :off
                :states {:off    {:on {:resume [:player :hist]}}
                         :player {:initial :stopped
                                  :states  {:stopped {:on {:play :playing}}
                                            :playing {}
                                            :hist    {:type :history}}}}}
          xml  (scxml/spec->scxml spec)
          back (scxml/scxml->spec xml)
          hist (get-in back [:states :player :states :hist])]
      (is (str/includes? xml "type=\"shallow\"")
          "an absent :deep? exports as type=\"shallow\"")
      (is (= :history (:type hist)))
      (is (= false (:deep? hist))
          "decodes to the normalised :deep? false"))))

;; ---------------------------------------------------------------------------
;; rf2-m285a — SCXML export tolerates INLINE-FN guard / action refs
;; (lossy name/`fn` label), instead of crashing in keyword->id-string.

(deftest inline-fn-guard-action-does-not-crash
  (testing "rf2-m285a — a NAMED inline-fn guard exports as a lossy name
            label without throwing (chart/Mermaid already tolerate it)"
    (let [guard-fn (with-meta (fn [_] true) {:name 'ready?})
          spec     {:initial :a
                    :states  {:a {:on {:go {:target :b :guard guard-fn}}}
                              :b {}}}
          xml      (scxml/spec->scxml spec)]
      (is (string? xml) "export succeeds (no ClassCastException)")
      (is (str/includes? xml "cond=\"ready?\"")
          "the named fn surfaces its :name meta as the cond label")))

  (testing "rf2-m285a — an ANONYMOUS inline-fn guard exports as the stable
            `fn` fallback label without throwing"
    (let [spec {:initial :a
                :states  {:a {:on {:go {:target :b :guard (fn [_] true)}}}
                          :b {}}}
          xml  (scxml/spec->scxml spec)]
      (is (string? xml))
      (is (str/includes? xml "cond=\"fn\"")
          "an anonymous fn falls back to the opaque \"fn\" label")))

  (testing "rf2-m285a — an inline-fn ACTION exports as a lossy label
            (action comment) without throwing"
    (let [action-fn (with-meta (fn [_] {}) {:name 'log!})
          spec      {:initial :a
                     :states  {:a {:on {:tick {:action action-fn}}}
                               :b {}}}
          xml       (scxml/spec->scxml spec)]
      (is (string? xml))
      (is (str/includes? xml "<!-- action: log! -->")
          "the named fn action surfaces its name in the action comment")))

  (testing "rf2-m285a — guard + action BOTH inline fns on one transition
            export without throwing"
    (let [spec {:initial :a
                :states  {:a {:on {:go {:target :b
                                        :guard  (fn [_] true)
                                        :action (fn [_] {})}}}
                          :b {}}}
          xml  (scxml/spec->scxml spec)]
      (is (string? xml))
      (is (str/includes? xml "cond=\"fn\""))
      (is (str/includes? xml "<!-- action: fn -->")))))

;; ---------------------------------------------------------------------------
;; rf2-9dj21r — the EXTERNAL restart axis (`:reenter? true`) must be a
;; DISTINCT, lossless SCXML round-trip.
;;
;; Spec 005 §Self-transitions / XState v5: a TARGETED transition is INTERNAL
;; by default (its own :exit/:entry do NOT re-run); only `:reenter? true`
;; makes a self / ancestor / compound-declared-descendant target EXTERNAL —
;; re-running :exit+:entry and restarting the target's :after timers + :spawn
;; children. Pre-fix the SCXML emitter/importer ignored the axis entirely, so
;; `{:target :same-state}` and `{:target :same-state :reenter? true}` exported
;; + round-tripped IDENTICALLY, silently dropping `:reenter? true` (which
;; CHANGES runtime behaviour on import). We map the axis onto W3C SCXML's
;; native `<transition type="external">`.

(def reenter-self-machine
  "A self-target transition WITH the external-restart opt-in."
  {:initial :a
   :states  {:a {:on {:ping {:target :same-state :reenter? true}}}}})

(def internal-self-machine
  "The SAME self-target transition WITHOUT `:reenter?` — the internal
  default (the runtime-distinct counterpart of `reenter-self-machine`)."
  {:initial :a
   :states  {:a {:on {:ping {:target :same-state}}}}})

(def reenter-ancestor-machine
  "A compound-declared transition to a descendant WITH `:reenter? true`
  (restart the declaring compound, land on the named child)."
  {:initial :outer
   :states  {:outer {:initial :inner1
                     :on      {:restart {:target [:outer :inner2] :reenter? true}}
                     :states  {:inner1 {}
                               :inner2 {}}}}})

(deftest reenter-axis-scxml-round-trip
  (testing "rf2-9dj21r — a `:reenter? true` self-target emits SCXML
            `type=\"external\"` and round-trips losslessly"
    (let [xml  (scxml/spec->scxml reenter-self-machine)
          back (scxml/scxml->spec xml)]
      (is (str/includes? xml "type=\"external\"")
          "the external-restart axis emits the native SCXML type attr")
      (is (= reenter-self-machine back)
          "exact value round-trip — `:reenter? true` survives")
      (is (true? (get-in back [:states :a :on :ping :reenter?]))
          "the decoded candidate carries `:reenter? true`")))

  (testing "rf2-9dj21r / rf2-0pp6as — the internal-DEFAULT self-target emits
            the explicit `type=\"internal\"` (NEVER `type=\"external\"`) and
            round-trips WITHOUT `:reenter?`"
    (let [xml  (scxml/spec->scxml internal-self-machine)
          back (scxml/scxml->spec xml)
          cand (get-in back [:states :a :on :ping])]
      (is (not (str/includes? xml "type=\"external\""))
          "the internal default does NOT emit the external type attr")
      ;; rf2-0pp6as — the internal default is EXPLICIT: a target-bearing
      ;; transition without `:reenter?` emits `type="internal"` so the
      ;; export does not inherit SCXML's EXTERNAL targeted-transition default.
      (is (str/includes? xml "type=\"internal\"")
          "the internal default emits the explicit SCXML internal type axis")
      ;; A SOLE target-only candidate normalises to the bare-keyword
      ;; shorthand (`:same-state`) on import — the canonical, semantically
      ;; identical form. The point is it stays a TARGET-ONLY transition with
      ;; NO `:reenter?` synthesised (vs the map form the external one keeps).
      (is (= :same-state cand)
          "round-trips to the canonical target-only shorthand (no map)")
      (is (not (and (map? cand) (contains? cand :reenter?)))
          "no spurious `:reenter?` synthesised on the internal default")))

  (testing "rf2-9dj21r — the with/without-`:reenter?` SCXML exports DIFFER
            (the two runtime-distinct machines are no longer identical)"
    (is (not= (scxml/spec->scxml reenter-self-machine)
              (scxml/spec->scxml internal-self-machine))
        "external vs internal must produce DISTINCT SCXML"))

  (testing "rf2-9dj21r — a compound-declared `:reenter?` descendant target
            round-trips the axis losslessly"
    (let [xml  (scxml/spec->scxml reenter-ancestor-machine)
          back (scxml/scxml->spec xml)]
      (is (str/includes? xml "type=\"external\""))
      (is (= reenter-ancestor-machine back)
          "exact value round-trip preserves `:reenter? true`")))

  (testing "rf2-9dj21r — `:reenter?` is emitted ONLY with a target
            (a targetless action-only transition stays internal — no
            `type=\"external\"`, no `:reenter?` synthesised on import)"
    (let [spec {:initial :a
                :states  {:a {:on {:tick {:action :log}}}}}
          xml  (scxml/spec->scxml spec)
          back (scxml/scxml->spec xml)]
      (is (not (str/includes? xml "type=\"external\"")))
      (is (= spec back)))))

;; ---------------------------------------------------------------------------
;; rf2-0pp6as — SCXML self-target export must reference a DECLARED state id
;; (never the dangling `same_2dstate` phantom), and must be EXPLICIT about
;; the XState-v5-internal vs SCXML-external default-inversion. Pre-fix the
;; SCXML resolver had no `:same-state` arm, so the sentinel exported the
;; absolute path `[:same-state]` → `target="same_2dstate"` — a DANGLING id
;; the document never declares. The local `scxml->spec` decoded that phantom
;; back to `:same-state`, so the round-trip oracle FALSE-GREENED over invalid
;; W3C SCXML. These tests assert the EXTERNAL form (declared-id validity +
;; internal/external type axis), not just the local round-trip, so the suite
;; cannot pass by decoding an invalid export back into the original EDN.

(defn- declared-state-ids
  "Every state / final / parallel / history id declared in an SCXML
  document (the set of valid `target=` referents per xsd:ID uniqueness)."
  [xml]
  (->> (re-seq #"<(?:state|final|parallel|history)\b[^>]*\bid=\"([^\"]+)\"" xml)
       (map second)
       set))

(defn- transition-target-ids
  "Every nonempty `target=` id appearing on a `<transition>` in an SCXML
  document."
  [xml]
  (->> (re-seq #"<transition\b[^>]*\btarget=\"([^\"]+)\"" xml)
       (map second)
       (remove str/blank?)
       set))

(defn- assert-targets-declared
  "Validity guard (rf2-0pp6as): EVERY nonempty transition `target=` id must
  reference a state DECLARED in the same SCXML document. This is the guard
  the pre-fix dangling `same_2dstate` export would FAIL — the phantom id is
  not in `declared-state-ids`."
  [xml]
  (let [declared (declared-state-ids xml)]
    (doseq [t (transition-target-ids xml)]
      (is (contains? declared t)
          (str "transition target=\"" t "\" must reference a declared "
               "state id; declared = " (pr-str declared))))))

(deftest scxml-self-target-references-declared-id
  (testing "rf2-0pp6as — ATOMIC self-target (`:same-state`) exports the
            SOURCE state's real id, NEVER the dangling `same_2dstate`"
    (let [spec {:initial :a
                :states  {:a {:on {:ping {:target :same-state}}}}}
          xml  (scxml/spec->scxml spec)]
      (is (not (str/includes? xml "same_2dstate"))
          "the `:same-state` sentinel must NOT leak as a phantom target id")
      (is (str/includes? xml "<transition event=\"ping\" target=\"a\" type=\"internal\"/>")
          "self-target references the source state's own id, internal default")
      (assert-targets-declared xml)
      ;; supplement the external-form assertions with the local round-trip:
      ;; the canonical decode of a self-target is `:same-state`.
      (is (= :same-state (get-in (scxml/scxml->spec xml) [:states :a :on :ping]))
          "round-trips to the canonical `:same-state` self-target form")))

  (testing "rf2-0pp6as — a keyword target NAMING the state's own key is the
            SAME self-transition; it too references the declared id + decodes
            to the canonical `:same-state`"
    (let [spec {:initial :a
                :states  {:a {:on {:ping {:target :a}}}}}
          xml  (scxml/spec->scxml spec)]
      (is (not (str/includes? xml "same_2dstate")))
      (is (str/includes? xml "target=\"a\""))
      (assert-targets-declared xml)
      (is (= :same-state (get-in (scxml/scxml->spec xml) [:states :a :on :ping]))
          "own-keyword self-target canonicalises to `:same-state` (Spec 005)")))

  (testing "rf2-0pp6as — COMPOUND self/ancestor target (`:same-state` declared
            on a compound) references the compound's own declared id"
    (let [spec {:initial :outer
                :states  {:outer {:initial :inner1
                                  :on      {:reset {:target :same-state}}
                                  :states  {:inner1 {} :inner2 {}}}}}
          xml  (scxml/spec->scxml spec)]
      (is (not (str/includes? xml "same_2dstate")))
      (is (str/includes? xml "<transition event=\"reset\" target=\"outer\" type=\"internal\"/>")
          "the compound self-target references the compound's own id, internal default")
      (assert-targets-declared xml)
      (is (= :same-state (get-in (scxml/scxml->spec xml) [:states :outer :on :reset]))
          "round-trips to the canonical `:same-state` form")))

  (testing "rf2-0pp6as — COMPOUND-declared DESCENDANT target references the
            descendant's fully-qualified declared id with `type=\"internal\"`
            (the case where SCXML internal IS the exact equivalent)"
    (let [spec {:initial :outer
                :states  {:outer {:initial :inner1
                                  :on      {:go {:target [:outer :inner2]}}
                                  :states  {:inner1 {} :inner2 {}}}}}
          xml  (scxml/spec->scxml spec)
          back (scxml/scxml->spec xml)]
      (is (str/includes? xml "target=\"outer___inner2\" type=\"internal\"")
          "descendant target references the qualified declared id, internal default")
      (assert-targets-declared xml)
      (is (= [:outer :inner2] (get-in back [:states :outer :on :go]))
          "descendant target round-trips to its absolute vector path")))

  (testing "rf2-0pp6as — the EXTERNAL (`:reenter? true`) self-target also
            references the declared id and carries `type=\"external\"`"
    (let [spec {:initial :a
                :states  {:a {:on {:ping {:target :same-state :reenter? true}}}}}
          xml  (scxml/spec->scxml spec)]
      (is (not (str/includes? xml "same_2dstate")))
      (is (str/includes? xml "<transition event=\"ping\" target=\"a\" type=\"external\"/>")
          "the external self-target references the source id, type external")
      (assert-targets-declared xml)
      (is (= {:target :same-state :reenter? true}
             (get-in (scxml/scxml->spec xml) [:states :a :on :ping]))
          "exact round-trip preserves the external `:reenter?` axis")))

  (testing "rf2-0pp6as — internal vs external self-targets export to DISTINCT,
            DECLARED-id-valid SCXML (the two are runtime-distinct)"
    (let [internal {:initial :a :states {:a {:on {:ping {:target :same-state}}}}}
          external {:initial :a :states {:a {:on {:ping {:target :same-state :reenter? true}}}}}
          xi (scxml/spec->scxml internal)
          xe (scxml/spec->scxml external)]
      (is (not= xi xe) "internal vs external must produce DISTINCT SCXML")
      (is (str/includes? xi "type=\"internal\""))
      (is (str/includes? xe "type=\"external\""))
      (assert-targets-declared xi)
      (assert-targets-declared xe))))

(deftest scxml-all-fixtures-targets-declared
  (testing "rf2-0pp6as — the declared-target validity guard holds for EVERY
            non-parallel fixture's export (no dangling `target=` ids anywhere)"
    (doseq [spec [idle-loading-success-error
                  compound-machine
                  always-machine
                  reenter-self-machine
                  internal-self-machine
                  reenter-ancestor-machine]]
      (assert-targets-declared (scxml/spec->scxml spec)))))

;; ---- consumer-attachment :rf.cofx/requires — intentional omission ------
;;
;; rf2-skhlw2.1 — SCXML INTENTIONALLY omits EP-0017 consumer-attachment
;; `:rf.cofx/requires` (W3C SCXML has no attribute for it; a re-frame2 import
;; re-attaches it from its own registry, and the chart is the "which
;; transitions consume which facts" surface). Lock the omission + the bead's
;; negative regression (no `:rf.world/inputs` / `inject-cofx` vocabulary).

(def scxml-cofx-bearing-machine
  {:initial :idle
   :guards  {:within-window? {:rf.cofx/requires [:rf/time-ms]
                              :fn (fn [_] true)}}
   :actions {:schedule-retry {:rf.cofx/requires [:payment/retry-jitter-ms]
                             :fn (fn [_] nil)}}
   :states  {:idle {:on {:go {:target :busy
                              :guard  :within-window?
                              :action :schedule-retry}}}
             :busy {}}})

(deftest scxml-omits-cofx-requires-vocabulary
  (testing "rf2-skhlw2.1 — SCXML surfaces the guard `cond=` NAME + the action
            comment NAME but omits the consumer-attachment requires diet"
    (let [out (scxml/spec->scxml scxml-cofx-bearing-machine)]
      ;; the guard `cond=` + the action comment still render (XML-mangled
      ;; names — SCXML escapes `-` / `?` to `_2d` / `_3f`); a transition
      ;; carries both so the topology survives.
      (is (str/includes? out "cond="))
      (is (str/includes? out "action:"))
      ;; the requires diet + its cofx ids are NOT emitted
      (is (not (str/includes? out "rf.cofx")))
      (is (not (str/includes? out "requires")))
      (is (not (str/includes? out "time-ms")))
      (is (not (str/includes? out "retry-jitter-ms")))
      ;; the bead's negative regression: NO retired/foreign cofx vocabulary
      (is (not (str/includes? out "rf.world/inputs")))
      (is (not (str/includes? out "inject-cofx"))))))

;; ---------------------------------------------------------------------------
;; rf2-qy8p — SCXML executable / data-model content is IGNORED, never
;; reinterpreted as topology
;;
;; W3C SCXML §3.3 lets a conforming `<state>` carry `<onentry>`, `<onexit>`,
;; `<invoke>`, `<datamodel>` and friends. The re-frame2 importer models
;; TOPOLOGY only — `<state>` / `<final>` / `<history>` / `<transition>` — so
;; those bodies are LOSSY BY DESIGN, exactly as the ns docstring's
;; "Not supported" list says.
;;
;; What is NOT acceptable is inventing topology out of them. Pre-fix the
;; direct-child collector accepted EVERY non-transition tag, so an
;; `<onentry/>` became an id-less `:states` entry keyed by `nil`, and the
;; import returned a definition `grammar/valid-definition?` — and therefore
;; `reg-machine`, `MachineChart` and every sibling emitter — rejects. The
;; failure surfaced far downstream as a compound-state error naming a state
;; the programmer never authored.
;;
;; Two invariants are pinned here:
;;   1. only recognised topology tags are collected as child states;
;;   2. every SUCCESSFUL `scxml->spec` result passes the canonical recursive
;;      grammar gate (the postcondition the public docstring promises).

(def onentry-only-scxml
  "The minimal reproduction: a conforming `<state>` whose only child is an
  empty `<onentry/>`."
  (str "<scxml xmlns='http://www.w3.org/2005/07/scxml' version='1.0' initial='idle'>"
       "<state id='idle'><onentry/></state>"
       "</scxml>"))

(deftest import-ignores-empty-onentry-executable-content
  (testing "rf2-qy8p — an empty <onentry/> is ignored, not promoted to a nil-keyed state"
    (let [spec (scxml/scxml->spec onentry-only-scxml)]
      (is (= {:initial :idle :states {:idle {}}} spec)
          "the state imports bare; the executable body leaves no trace")
      (is (not (contains? (:states spec) nil))
          "no phantom nil-keyed state")
      (is (empty? (get-in spec [:states :idle :states]))
          "the leaf stays a leaf — <onentry> is not a child state")
      (is (g/valid-definition? spec)
          "a successful import passes the canonical recursive grammar gate")
      (is (nil? (g/definition-defect spec))))))

(def executable-and-datamodel-scxml
  "Every unsupported family the bead names — `<datamodel>`/`<data>` at the
  root, `<onentry>` with a `<log>` body, `<invoke>` with a `<param>`, and
  `<onexit>` with an `<assign>` — wrapped around otherwise supported states
  and transitions."
  (str "<scxml xmlns='http://www.w3.org/2005/07/scxml' version='1.0' initial='go'>"
       "<datamodel><data id='counter' expr='0'/></datamodel>"
       "<state id='go'>"
       "<onentry><log expr='entering'/></onentry>"
       "<invoke type='http://www.w3.org/TR/scxml/'><param name='p' expr='1'/></invoke>"
       "<transition event='next' target='wait'/>"
       "</state>"
       "<state id='wait'>"
       "<onexit><assign location='counter' expr='counter'/></onexit>"
       "<transition event='finish' target='done'/>"
       "</state>"
       "<final id='done'/>"
       "</scxml>"))

(deftest import-ignores-executable-and-datamodel-subtrees-wholesale
  (testing "rf2-qy8p — <datamodel>/<onentry>/<onexit>/<invoke> subtrees are dropped
            wholesale while the real ids and transitions import exactly"
    (let [spec (scxml/scxml->spec executable-and-datamodel-scxml)]
      (is (= {:initial :go
              :states  {:go   {:on {:next :wait}}
                        :wait {:on {:finish :done}}
                        :done {:final? true}}}
             spec)
          "topology is exact; every unsupported subtree is ignored")
      (is (= #{:go :wait :done} (set (keys (:states spec))))
          "no phantom state from <datamodel> at the root")
      (is (not (contains? (set (keys (:states spec))) nil)))
      ;; the unsupported subtrees' own attributes must not leak in as ids
      (is (not (contains? (:states spec) :counter))
          "<data id='counter'> is data-model, not a state")
      (is (g/valid-definition? spec)))))

(def nested-state-inside-unsupported-scxml
  "An unsupported element that CONTAINS a `<state>`. Skipping only the open
  tag would promote the nested element into the parent's `:states`; the whole
  subtree has to go."
  (str "<scxml xmlns='http://www.w3.org/2005/07/scxml' version='1.0' initial='a'>"
       "<state id='a'>"
       "<onentry><state id='sneaky'/><final id='sneakier'/></onentry>"
       "<transition event='go' target='b'/>"
       "</state>"
       "<state id='b'/>"
       "</scxml>"))

(deftest import-does-not-promote-a-state-nested-in-an-unsupported-element
  (testing "rf2-qy8p — only DIRECT recognised children are collected; an
            unsupported element is skipped together with its whole subtree"
    (let [spec (scxml/scxml->spec nested-state-inside-unsupported-scxml)]
      (is (= {:initial :a
              :states  {:a {:on {:go :b}}
                        :b {}}}
             spec))
      (is (empty? (get-in spec [:states :a :states]))
          "the <state> buried inside <onentry> is NOT promoted")
      (is (not (contains? (:states spec) :sneaky)))
      (is (not (contains? (:states spec) :sneakier)))
      (is (g/valid-definition? spec)))))

(deftest import-tolerates-unsupported-content-around-history-and-compounds
  (testing "rf2-qy8p — the allowlist keeps <history> and nested compounds; only
            the unsupported families are dropped"
    (let [spec (scxml/scxml->spec
                 (str "<scxml xmlns='http://www.w3.org/2005/07/scxml' version='1.0' initial='outer'>"
                      "<state id='outer' initial='outer___one'>"
                      "<onentry><log expr='x'/></onentry>"
                      "<history id='outer___hist' type='deep'>"
                      "<transition target='outer___one'/>"
                      "</history>"
                      "<state id='outer___one'>"
                      "<onexit/>"
                      "<transition event='next' target='outer___two'/>"
                      "</state>"
                      "<state id='outer___two'/>"
                      "</state>"
                      "</scxml>"))]
      (is (= {:initial :outer
              :states  {:outer {:initial :one
                                :states  {:hist {:type :history :deep? true
                                                 :default-target :one}
                                          :one  {:on {:next :two}}
                                          :two  {}}}}}
             spec))
      (is (g/valid-definition? spec)))))

;; ---------------------------------------------------------------------------
;; rf2-qy8p — the import POSTCONDITION: success implies a projectable
;; definition. Parser output that cannot be represented as a valid re-frame2
;; definition throws the documented `:scxml/invalid-spec`, value-free.

(def missing-initial-scxml
  "No root `initial` — the machine contract wants a keyword `:initial`.
  Pre-fix this returned `{:states {:a {}}}` silently, contradicting the
  public docstring's stated error boundary."
  (str "<scxml xmlns='http://www.w3.org/2005/07/scxml' version='1.0'>"
       "<state id='a'/></scxml>"))

(deftest import-throws-invalid-spec-rather-than-returning-a-malformed-definition
  (testing "rf2-qy8p — a document whose topology cannot be a valid re-frame2
            definition throws :scxml/invalid-spec instead of returning it"
    (is (thrown? #?(:clj clojure.lang.ExceptionInfo :cljs js/Error)
                 (scxml/scxml->spec missing-initial-scxml)))
    (let [d (try (scxml/scxml->spec missing-initial-scxml)
                 nil
                 (catch #?(:clj clojure.lang.ExceptionInfo :cljs :default) e (ex-data e)))]
      (is (= :scxml/invalid-spec (:rf.error/id d)) "the documented discriminator"))))

(deftest import-invalid-spec-error-is-value-free
  (testing "rf2-qy8p — the import-side invalid-spec ex-data carries only the
            shared value-free summary: no raw XML, no parsed definition"
    (let [secret "patientrecordsecret42"
          xml    (str "<scxml xmlns='http://www.w3.org/2005/07/scxml' version='1.0'>"
                      "<state id='" secret "'/></scxml>")
          d      (try (scxml/scxml->spec xml) nil
                      (catch #?(:clj clojure.lang.ExceptionInfo :cljs :default) e (ex-data e)))]
      (is (= :scxml/invalid-spec (:rf.error/id d)))
      (is (some? (:spec-summary d)) "value-free summary present")
      (is (not (contains? d :input)) "no raw :input slot")
      (is (not (contains? d :spec)) "no raw :spec slot")
      (is (not (some #(str/includes? % secret) (deep-strings d)))
          "the id must not survive anywhere in ex-data"))))

(deftest every-supported-fixture-imports-to-a-valid-definition
  (testing "rf2-qy8p — the postcondition holds across the whole supported
            round-trip corpus (the guard against a gate that rejects real imports)"
    (doseq [spec [idle-loading-success-error
                  compound-machine
                  namespaced-machine
                  guarded-machine
                  after-machine
                  always-machine
                  parallel-machine]]
      (let [imported (scxml/scxml->spec (scxml/spec->scxml spec))]
        (is (g/valid-definition? imported)
            (str "import of " (pr-str (or (:initial spec) (:type spec))) " must be projectable"))
        (is (= spec imported) "and the round-trip stays value-equal")))))
