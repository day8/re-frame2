(ns day8.re-frame2-machines-viz.scxml-cljs-test
  "Pure-data tests for SCXML import/export.

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
            ;; The canonical recursive grammar gate. The import
            ;; tests below assert the POSTCONDITION `scxml->spec`
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

(def ^:private scxml-format-marker
  "The exact format comment `spec->scxml` writes after the
  prolog and `scxml->spec` requires. Spelled out literally so a change to the
  marker is a visible format change here too."
  "<!-- re-frame2 machines-viz SCXML v1 -->")

(defn- marked
  "`str` for a HAND-WRITTEN input, led by the format marker,
  so the import assertions below keep running against a marked export
  augmented with content the importer does not model (a marked file can be
  hand-edited)."
  [& parts]
  (apply str scxml-format-marker "\n" parts))

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
  "A flat machine with a top-level (machine-level) :on
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
  "A compound machine whose transition target is a VECTOR
  PATH `[:authenticated :browsing]` (a deep-target into a compound
  child). The encoder must distinguish this from the namespaced keyword
  `:authenticated/browsing`: dot-joining both to
  `\"authenticated.browsing\"` would let the decoder collapse the vector to
  a namespaced keyword, silently changing machine semantics."
  {:initial :idle
   :states  {:idle          {:on {:login [:authenticated :browsing]}}
             :authenticated {:initial :browsing
                             :states  {:browsing {:on {:checkout :paying}}
                                       :paying   {}}}}})

(def vector-path-namespaced-segment-machine
  "A vector-path target whose FIRST segment is itself a
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
  (testing "compound states emit nested <state> blocks with
            FULLY-QUALIFIED unique xsd:IDs (the path root→leaf, `___`-joined)
            so two same-named nested states never collide; `initial` and
            transition targets reference those same unique ids"
    (let [out (scxml/spec->scxml compound-machine)]
      (is (str/includes? out "<state id=\"authenticated\" initial=\"authenticated___browsing\""))
      (is (str/includes? out "<state id=\"authenticated___browsing\""))
      (is (str/includes? out "<state id=\"authenticated___paying\"")))))

(deftest emit-namespaced-ids-use-ns-name-marker
  (testing ":auth/idle → id=\"auth-idle\" (the `-`
            ns/name marker; the keyword name/ns chars are hex-escaped so
            the codec is fully injective and xsd:ID-conformant). The `-`
            marker — which the escaper never emits (a literal `-` → `_2d`)
            — can never grow into a `___` path-run at the ns/name boundary,
            as a `__` marker could."
    (let [out (scxml/spec->scxml namespaced-machine)]
      (is (str/includes? out "initial=\"auth-idle\""))
      (is (str/includes? out "<state id=\"auth-idle\""))
      (is (str/includes? out "<state id=\"auth-loading\""))
      (is (str/includes? out "event=\"rf-load\""))
      ;; The non-injective `.`-as-ns/name form must not appear.
      (is (not (str/includes? out "id=\"auth.idle\"")))
      ;; The ns/name boundary must NOT be a `__` run that could
      ;; collide with the `___` path separator.
      (is (not (str/includes? out "id=\"auth__idle\""))))))

(deftest emit-guards-render-as-cond-attribute
  (testing "guarded transitions render with cond= on
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
  (testing "a top-level (machine-level) :on fallback is
            emitted (as a documented <transition> under <scxml>) rather
            than silently dropped (the parser-side P2 mirror). W3C SCXML
            has no clean root-fallback slot so this can't round-trip,
            but the topology survives the export."
    (let [out (scxml/spec->scxml machine-level-on-machine)]
      (is (str/includes? out "machine-level")
          "a comment documents the inherited fallback")
      (is (str/includes? out "event=\"logout\"")
          "the machine-level :logout transition is emitted")
      ;; The per-state :go transition emits normally too.
      (is (str/includes? out "event=\"go\"")))))

(deftest emit-always-transitions-omit-event-attribute
  (testing ":always candidates render as eventless <transition>s
            (no event= attribute; target= + optional cond= only)"
    (let [out (scxml/spec->scxml always-machine)]
      ;; Both attribute orders are equally valid SCXML; assert
      ;; semantic content, not lexical order.
      (is (str/includes? out "target=\"ready\""))
      ;; Guard hex-escaped (`:ready?` → `ready_3f`).
      (is (str/includes? out "cond=\"ready_3f\""))
      ;; A targeted internal-default transition carries
      ;; the explicit `type="internal"` axis (so the export does not inherit
      ;; SCXML's EXTERNAL targeted-transition default). The `:always`
      ;; candidate is eventless (no `event=`) but is a targeted
      ;; internal-default transition too, so it carries `type="internal"`.
      (is (str/includes? out "<transition target=\"blocked\" type=\"internal\"/>"))
      ;; Eventless transitions must not carry event= — confirm by
      ;; searching for the malformed combination.
      (is (not (re-find #"event=\"\"" out))))))

(deftest emit-parallel-machine-uses-parallel-element
  (testing ":type :parallel emits a <parallel> wrapper with region children"
    (let [out (scxml/spec->scxml parallel-machine)]
      (is (str/includes? out "<parallel id=\"rf2_parallel_root\""))
      ;; Region children carry fully-qualified ids, so the
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

(deftest round-trip-iso-after-machine
  (testing "an ISO-8601 :after delay rides event=\"after.<duration>\" verbatim
            and imports as the same string"
    (let [spec {:initial :loading
                :states  {:loading {:after {"PT1S" :timeout "PT0.5S" :done}}
                          :timeout {}
                          :done    {}}}]
      (is (str/includes? (scxml/spec->scxml spec) "event=\"after.PT1S\""))
      (is (round-trips? spec)))))

(deftest round-trip-always-machine
  (testing ":always eventless transitions round-trip"
    (is (round-trips? always-machine))))

(deftest round-trip-parallel-machine
  (testing ":type :parallel + :regions round-trips through <parallel>"
    (is (round-trips? parallel-machine))))

(deftest round-trip-vector-path-target
  (testing "a vector-path transition target round-trips as
            the SAME vector, NOT collapsed to a namespaced keyword"
    ;; The target must come back a vector.
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
    (testing "the emitted SCXML uses `___` (not `:`)
              between vector segments (`:` is not a valid
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
  (testing "state ids above U+00FF (CJK) round-trip EXACTLY.
            A `_<var-hex>` encode + `_XX` (2-hex) decode would mis-read
            `:开始` (`_5f00_59cb`) as `:_00Ycb`; the fixed-width `_u<4-hex>`
            codec is reversible across the whole code-unit range"
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
  (testing "a NAMESPACED CJK id (`:开始/名`) round-trips EXACTLY.
            A `__` ns/name marker + the escaped (leading `_u…`) name
            segment would mint `___` at the boundary and mis-decode the
            namespaced keyword to a vector; with the `-` ns/name marker the
            namespaced case round-trips like the plain one."
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
  (testing "a namespaced keyword whose NAME segment begins with
            an escaped char (leading `_…`) round-trips EXACTLY, not to a
            vector. An `<ns>` + `__` + `_<name-escape>` boundary would
            mint `___` (the path separator) and decode `:a/-b` to the
            vector `[:a :2db]` instead of the namespaced keyword."
    (doseq [k [:a/-b            ;; name -b → _2db
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
  (testing "`parse-attrs` reads single-quoted values as well as
            double-quoted ones (reading only double-quoted would key an
            imported `<state id='idle'>` under nil). Every attribute in a
            single-quoted document must parse identically"
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
  (testing "a malformed flat-ROOT `:on` (non-map fallback slot)
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
    ;; Marked, so this reaches the root check rather
    ;; than stopping at the format gate (an unmarked input throws too, but
    ;; with :scxml/unsupported-format).
    (is (thrown? #?(:clj clojure.lang.ExceptionInfo :cljs js/Error)
                 (scxml/scxml->spec (marked "<not-scxml/>"))))
    (is (= :scxml/parse-error
           (try (scxml/scxml->spec (marked "<not-scxml/>")) nil
                (catch #?(:clj clojure.lang.ExceptionInfo :cljs :default) e
                  (:rf.error/id (ex-data e))))))))

;; ---------------------------------------------------------------------------
;; EP-0015 — error ex-data carries NO raw payload
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
;; bit-for-bit. Every fixture above round-trips; a fixture here that
;; doesn't names its loss explicitly, as the machine-level `:on` test
;; below does — the comment is the contract.

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
  (testing "a machine-level (top-level) :on fallback is
            EXPORTED (not silently dropped) but does NOT round-trip:
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
;; :on-done (XState onDone) — W3C SCXML done.state.<id>
;;
;; SCXML §3.7: reaching a `<final>` child generates `done.state.<id>` into
;; the internal queue, which an enclosing `<transition event="done.state.
;; <id>">` takes. The emitter projects the onDone transition as well as the
;; `<final>` child; projecting only the child would make the round-trip
;; silently lossy.

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
  (testing "a compound `:on-done` emits the W3C SCXML
            `<transition event=\"done.state.<compound-id>\" target=...>`
            INSIDE the compound's own <state> (SCXML §3.7)"
    (let [out (scxml/spec->scxml compound-on-done-machine)]
      (is (str/includes? out "event=\"done.state.flow\"")
          "the done.state.<compound> completion event")
      (is (str/includes? out "target=\"next\"")
          "advances to the sibling :next"))))

(deftest emit-parallel-on-done-renders-parallel-done-state
  (testing "a parallel-root `:on-done` emits
            `<transition event=\"done.state.rf2_parallel_root\">` inside
            the <parallel> element (the whole-parallel completion)"
    (let [out (scxml/spec->scxml parallel-on-done-machine)]
      (is (str/includes? out "event=\"done.state.rf2_parallel_root\"")
          "the whole-parallel done.state completion event"))))

(deftest round-trip-compound-on-done
  (testing "a compound `:on-done` (sibling target) round-trips
            through `done.state.<id>` back to `:on-done`"
    (let [spec compound-on-done-machine
          back (-> spec scxml/spec->scxml scxml/scxml->spec)]
      (is (= spec back) "the compound :on-done topology round-trips exactly")
      (is (= :next (get-in back [:states :flow :on-done]))
          ":on-done reconstructs on the compound node"))))

(deftest round-trip-parallel-on-done-topology
  (testing "a parallel-root `:on-done` round-trips its
            COMPLETION topology (the action is named-lossy like every
            action — survives only as a comment, so the spec carries the
            empty completion form `{}`)"
    (let [spec parallel-on-done-machine
          back (-> spec scxml/spec->scxml scxml/scxml->spec)]
      (is (= spec back) "the parallel completion topology round-trips")
      (is (contains? back :on-done) "the parallel-root :on-done survives import"))))

(deftest no-on-done-emits-no-done-state-transition
  (testing "a compound with no :on-done emits no done.state
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
          "the success final renders as a plain <final>")
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
;; Parallel-ROOT :on / :after ancestor fallback
;;
;; A `:type :parallel` ROOT may declare its OWN `:on` (the ancestor fallback,
;; Spec 005 §Root parallel :on) and its OWN `:after` (the timer-driven analog,
;; §Root-level :after). These are DIRECT `<parallel>` children, beside the
;; root `:on-done`. Both emit + round-trip, including MULTI-region targets
;; (space-separated W3C `target` id lists) and action-only (targetless)
;; forms.

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
  (testing "a parallel-root :on emits a direct <parallel>
            <transition> with the region-qualified target id"
    (let [out (scxml/spec->scxml parallel-root-on-single-machine)]
      (is (str/includes? out "event=\"one\"") "the root :on event")
      (is (str/includes? out "target=\"a___two\"")
          "the region-qualified target id (region :a substate :two)"))))

(deftest emit-parallel-root-on-multi-region-target-is-space-separated
  (testing "a MULTI-region root :on emits a SPACE-SEPARATED
            target id list (W3C SCXML target grammar)"
    (let [out (scxml/spec->scxml parallel-root-on-multi-machine)]
      (is (str/includes? out "target=\"a___x b___y\"")
          "both region-qualified targets in one space-joined attribute"))))

(deftest emit-parallel-root-after-renders-after-event
  (testing "a parallel-root :after emits an after.<delay> direct
            <parallel> transition with the region-qualified target"
    (let [out (scxml/spec->scxml parallel-root-after-single-machine)]
      (is (str/includes? out "event=\"after.500\"") "the delay rides after.<ms>")
      (is (str/includes? out "target=\"a___two\"")))))

(deftest round-trip-parallel-root-on-single
  (testing "a single-region root :on round-trips exactly"
    (is (round-trips? parallel-root-on-single-machine))))

(deftest round-trip-parallel-root-on-multi
  (testing "a multi-region root :on round-trips its
            region-qualified target grammar exactly"
    (is (round-trips? parallel-root-on-multi-machine))))

(deftest round-trip-parallel-root-after-single
  (testing "a single-region root :after round-trips exactly"
    (is (round-trips? parallel-root-after-single-machine))))

(deftest round-trip-parallel-root-after-multi
  (testing "a multi-region root :after round-trips exactly"
    (is (round-trips? parallel-root-after-multi-machine))))

(deftest round-trip-parallel-root-on-with-guard
  (testing "a guarded root :on round-trips its guard via cond="
    (is (round-trips? {:type    :parallel
                       :on      {:fire {:target [[:a :two] [:b :two]] :guard :armed?}}
                       :regions {:a {:initial :one :states {:one {} :two {}}}
                                 :b {:initial :one :states {:one {} :two {}}}}}))))

(deftest round-trip-parallel-root-on-action-only
  (testing "a TARGETLESS action-only root :on round-trips its
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
  (testing "a TARGETLESS action-only root :after round-trips"
    (let [spec {:type    :parallel
                :after   {2000 {:action :timeout-log}}
                :regions {:a {:initial :one :states {:one {}}}
                          :b {:initial :one :states {:one {}}}}}
          back (-> spec scxml/spec->scxml scxml/scxml->spec)]
      (is (= spec back) "the action-only root :after round-trips")
      (is (= :timeout-log (get-in back [:after 2000 :action]))))))

(deftest round-trip-parallel-root-on-and-after-together
  (testing "a root :on AND a root :after on the same
            parallel machine both survive + round-trip independently"
    (is (round-trips? {:type    :parallel
                       :on      {:go [:a :two]}
                       :after   {1000 [[:a :two] [:b :two]]}
                       :regions {:a {:initial :one :states {:one {} :two {}}}
                                 :b {:initial :one :states {:one {} :two {}}}}}))))

(deftest parallel-root-on-map-form-canonicalises-to-shorthand
  (testing "the `{:target …}` map form of a target-only root :on
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
;; Injective, xsd:ID-conformant id codec
;;
;; The codec hex-escapes every keyword ns/name char and uses the reserved
;; `-` (ns/name) and `___` (path) markers the escaper can provably never
;; emit, so the round-trip is EXACT for ANY keyword and every emitted id is
;; a valid xsd:ID. A `.`-as-ns/name separator would be NON-INJECTIVE — it
;; cannot recover a keyword whose namespace itself contains dots
;; (`:my.app.auth/login` → `"my.app.auth.login"` → `:my/app.auth.login`) —
;; and a `:`-as-path separator would emit ids that are not valid xsd:ID
;; (NCName) tokens.

(def multi-dot-ns-machine
  "A multi-segment-namespace keyword EVENT. A `.`-joined encoding
  would turn `:my.app.auth/login` into `\"my.app.auth.login\"` and
  decode it to the wrong `:my/app.auth.login` (ns boundary lost)."
  {:initial :idle
   :states  {:idle   {:on {:my.app.auth/login :active}}
             :active  {:final? true}}})

(def multi-dot-ns-state-machine
  "A multi-segment-namespace keyword STATE id."
  {:initial :rf.app/idle
   :states  {:rf.app/idle {:on {:go :done}}
             :done        {:final? true}}})

(def dotted-name-machine
  "A non-namespaced keyword whose NAME contains dots."
  {:initial :a.b.c
   :states  {:a.b.c {:on {:go :d}}
             :d     {}}})

(def multi-dot-guard-machine
  "A multi-segment-namespace GUARD keyword. A guard `cond=`
  decoder that did not split the namespace would lose it even on a
  single-dot `:auth/valid?`."
  {:initial :idle
   :states  {:idle {:on {:go {:target :a :guard :my.app.auth/valid?}}}
             :a    {}}})

(def single-ns-guard-machine
  "A single-dot namespaced guard: `:auth/valid?`."
  {:initial :idle
   :states  {:idle {:on {:go {:target :a :guard :auth/valid?}}}
             :a    {}}})

(def reserved-prefix-after-event-machine
  "A USER event named `:after.foo` must NOT be reclassified
  as an `:after` timer. The codec escapes the literal `.` so the encoded
  event never starts with the synthetic `after.` prefix."
  {:initial :idle
   :states  {:idle {:on {:after.foo :done}}
             :done {:final? true}}})

(def reserved-prefix-done-event-machine
  "A USER event named `:done.state.flow` must NOT be
  reclassified as `:on-done`."
  {:initial :idle
   :states  {:idle {:on {:done.state.flow :done}}
             :done {:final? true}}})

(def reserved-prefix-after-ns-machine
  "`after` is a plausible event NAMESPACE; `:after/foo`
  must stay an ordinary `:on` event."
  {:initial :idle
   :states  {:idle {:on {:after/foo :done}}
             :done {:final? true}}})

(def nested-same-name-machine
  "Two states share the local name `:idle` under different
  compound parents. Bare ids would emit `<state id=\"idle\">` twice
  (duplicate xsd:ID — invalid SCXML), and `:`-joined path targets are not
  valid xsd:IDs. The qualified-id codec emits unique ids and resolvable
  targets, and the round-trip is exact."
  {:initial :a
   :states  {:a {:initial :idle :states {:idle {:on {:go [:b :idle]}}}}
             :b {:initial :idle :states {:idle {:on {:back [:a :idle]}}}}}})

(def mixed-candidate-after-machine
  "An `:after` vector MIXING a candidate-map with a
  bare-target. A decoder that collapsed `{:target :b}` to `:b` would make
  the round-trip value-UNEQUAL to the literal input."
  {:initial :idle
   :states  {:idle {:after {1000 [{:target :a :guard :g1} {:target :b}]}}
             :a    {}
             :b    {}}})

(def mixed-candidate-on-machine
  "An `:on` vector mixing a candidate-map with a bare-target."
  {:initial :idle
   :states  {:idle {:on {:go [{:target :a :guard :g1} {:target :b}]}}
             :a    {}
             :b    {}}})

(deftest round-trip-multi-dot-ns-keyword
  (testing "a multi-segment-namespace keyword EVENT
            round-trips EXACTLY (the namespace boundary is recovered)"
    (is (round-trips? multi-dot-ns-machine))
    (let [back (-> multi-dot-ns-machine scxml/spec->scxml scxml/scxml->spec)]
      (is (= :my.app.auth/login
             (-> back :states :idle :on keys first))
          "the multi-dot namespace is recovered, not corrupted to :my/app.auth.login")))
  (testing "a multi-segment-namespace keyword STATE id
            round-trips exactly"
    (is (round-trips? multi-dot-ns-state-machine)))
  (testing "a non-namespaced keyword with dots in its NAME
            round-trips exactly (not split into a namespaced keyword)"
    (is (round-trips? dotted-name-machine))
    (let [back (-> dotted-name-machine scxml/spec->scxml scxml/scxml->spec)]
      (is (= :a.b.c (-> back :initial))
          ":a.b.c stays a single keyword, NOT :a/b.c"))))

(deftest injective-distinct-keywords-distinct-ids
  (testing "two keywords a `.`-joined codec would map to the
            SAME id (`:my.app.auth/login` and `:my/app.auth.login` both →
            \"my.app.auth.login\") map to DISTINCT ids AND each
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
  (testing "literal underscores in a keyword name never
            collide with the `__`/`___` reserved markers (escaped to _5f)"
    (doseq [k [:a__b :a___b :a_b]]
      (is (round-trips? {:initial :s0 :states {:s0 {:on {k :s1}} :s1 {}}})
          (str "round-trip exact for " k)))))

(deftest round-trip-guard-keyword-namespace
  (testing "a single-dot namespaced GUARD round-trips
            exactly (`:auth/valid?`)"
    (is (round-trips? single-ns-guard-machine))
    (let [back (-> single-ns-guard-machine scxml/spec->scxml scxml/scxml->spec)]
      (is (= :auth/valid? (get-in back [:states :idle :on :go :guard]))
          "the guard keeps its namespace, NOT collapsed to :auth.valid?")))
  (testing "a multi-segment-namespace guard round-trips exactly"
    (is (round-trips? multi-dot-guard-machine))))

(deftest round-trip-reserved-prefix-user-events
  (testing "a user event `:after.foo` stays an `:on` event,
            NOT reclassified as an `:after` timer"
    (is (round-trips? reserved-prefix-after-event-machine))
    (let [back (-> reserved-prefix-after-event-machine scxml/spec->scxml scxml/scxml->spec)]
      (is (contains? (get-in back [:states :idle :on]) :after.foo)
          ":after.foo survives as an :on event")
      (is (nil? (get-in back [:states :idle :after]))
          "no spurious :after timer is synthesised")))
  (testing "a user event `:done.state.flow` stays an `:on`
            event, NOT reclassified as `:on-done`"
    (is (round-trips? reserved-prefix-done-event-machine))
    (let [back (-> reserved-prefix-done-event-machine scxml/spec->scxml scxml/scxml->spec)]
      (is (contains? (get-in back [:states :idle :on]) :done.state.flow)
          ":done.state.flow survives as an :on event")
      (is (not (contains? (get-in back [:states :idle]) :on-done))
          "no spurious :on-done is synthesised")))
  (testing "`after` as an event NAMESPACE (`:after/foo`)
            stays an ordinary :on event"
    (is (round-trips? reserved-prefix-after-ns-machine))))

(deftest round-trip-nested-same-name-states-unique-ids
  (testing "two same-named nested states emit UNIQUE
            xsd:IDs (no duplicate `id=\"idle\"`) and the round-trip is exact"
    (is (round-trips? nested-same-name-machine))
    (let [out (scxml/spec->scxml nested-same-name-machine)]
      (is (not (str/includes? out "id=\"idle\""))
          "no bare duplicate `id=\"idle\"` — ids are path-qualified")
      (is (str/includes? out "id=\"a___idle\"") "the a-region idle is qualified")
      (is (str/includes? out "id=\"b___idle\"") "the b-region idle is qualified"))))

(deftest emitted-ids-are-xsd-id-conformant
  (testing "no emitted id / target / initial attribute
            contains a `:` (not a valid xsd:ID / NCName char). Vector-path
            segments join with the `___` marker instead."
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
  (testing "an `:after` vector mixing a candidate-map with a
            bare-target round-trips with VALUE EQUALITY (the bare-target
            map is not collapsed)"
    (is (round-trips? mixed-candidate-after-machine))
    (let [back (-> mixed-candidate-after-machine scxml/spec->scxml scxml/scxml->spec)]
      (is (= [{:target :a :guard :g1} {:target :b}]
             (get-in back [:states :idle :after 1000]))
          "{:target :b} survives as a map, not collapsed to :b")))
  (testing "the same holds for an `:on` mixed vector"
    (is (round-trips? mixed-candidate-on-machine)))
  (testing "a SOLE target-only candidate still collapses to
            the bare-keyword shorthand (the canonical `:on {:event :tgt}`)"
    (let [spec {:initial :idle :states {:idle {:on {:go :a}} :a {}}}
          back (-> spec scxml/spec->scxml scxml/scxml->spec)]
      (is (= :a (get-in back [:states :idle :on :go]))
          "a single target-only transition stays the bare keyword"))))

(deftest round-trip-property-mnp93-fixtures
  (testing "every codec-faithfulness fixture round-trips
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
;; An INTERNAL ACTION transition must NOT round-trip into a
;; Spec-005 FORBIDDEN BLOCK.
;;
;; `:on {:tick {:action :log}}` is an internal action transition: it RUNS an
;; action and leaves the config as it is (Spec 005 §Transition slots — "omit
;; for internal"). The action rides an XML COMMENT, which the decoder lifts
;; into the candidate so it round-trips as `{:action :log}` — a VALID internal
;; action transition. Discarding the comment BEFORE tokenizing would decode
;; the candidate to the EMPTY map `{}` — which Spec 005 §Forbidden transitions
;; (L1335-1346) defines as a FORBIDDEN BLOCK: an enabled internal no-op that
;; CONSUMES the event and shadows every coarser descriptor + ancestor (opts
;; OUT of inheritance). 'Run an action' decoding to 'block the event entirely'
;; is a SEMANTIC INVERSION, not lossy detail. The distinguishing shape feature
;; is the PRESENCE of `:action` (L1346: an action-bearing internal transition
;; halts the walk AND runs the action).

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
  (testing "an internal action `:on` round-trips to a VALID
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
          "internal — no :target")
      (is (= internal-action-on-machine back)
          "exact value round-trip")))

  (testing "internal action `:after` round-trips faithfully"
    (let [back (-> internal-action-after-machine scxml/spec->scxml scxml/scxml->spec)]
      (is (= internal-action-after-machine back))
      (is (= {:action :timeout-log} (get-in back [:states :a :after 1000])))))

  (testing "internal action `:always` round-trips faithfully"
    (let [back (-> internal-action-always-machine scxml/spec->scxml scxml/scxml->spec)]
      (is (= internal-action-always-machine back))
      (is (= [{:action :poll}] (get-in back [:states :a :always])))))

  (testing "an internal action transition WITH a guard keeps
            both :action and :guard (not a forbidden block either)"
    (let [back (-> internal-guarded-action-machine scxml/spec->scxml scxml/scxml->spec)
          cand (get-in back [:states :a :on :tick])]
      (is (= {:action :log :guard :ready?} cand))
      (is (not= {} cand))))

  (testing "a NAMESPACED action round-trips its namespace"
    (let [back (-> internal-ns-action-machine scxml/spec->scxml scxml/scxml->spec)]
      (is (= internal-ns-action-machine back))
      (is (= :log/append (get-in back [:states :a :on :tick :action])))))

  (testing "a genuine FORBIDDEN block ({} — no action, no
            target) still round-trips to `{}` (the action lift-pass only
            recovers a PRESENT action; absence stays absent — no false
            action synthesised)"
    (let [spec {:initial :a :states {:a {:on {:tick {}}}}}
          back (-> spec scxml/spec->scxml scxml/scxml->spec)]
      (is (= {} (get-in back [:states :a :on :tick]))
          "an empty-map forbidden block survives as `{}`, not invented an action"))))

;; ---------------------------------------------------------------------------
;; `:type :history` pseudo-states export as W3C `<history>`
;; (NOT `<state>` / `<final>`), preserving the shallow / deep / default-target
;; semantics, and round-trip back to `:type :history` nodes.

(def shallow-history-machine
  "Compound with a SHALLOW `:type :history` pseudo-state targeted by an
  outer transition. The history node is NEVER occupiable — a transition
  to `:hist` resolves to the compound's recorded direct child at runtime.
  Uses the normalised `:deep? false` shape the `:rf/machine` registrar projection returns (an
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
  (testing "a SHALLOW history pseudo-state emits <history
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

  (testing "a DEEP history pseudo-state emits type=\"deep\""
    (let [xml (scxml/spec->scxml deep-history-machine)]
      (is (str/includes? xml "<history "))
      (is (str/includes? xml "type=\"deep\""))))

  (testing "a history :default-target rides a default
            <transition target=...> inside the <history>"
    (let [xml (scxml/spec->scxml default-target-history-machine)]
      (is (str/includes? xml "<history "))
      ;; the default-target :playing is a sibling — qualified id player___playing
      (is (re-find #"<history[^>]*>\s*<transition target=\"player___playing\"/>" xml)
          "the default transition targets the qualified default-target leaf"))))

(deftest history-round-trips-to-type-history
  (testing "a SHALLOW history pseudo-state round-trips to
            `:type :history` (NOT an ordinary state)"
    (let [back (-> shallow-history-machine scxml/spec->scxml scxml/scxml->spec)
          hist (get-in back [:states :player :states :hist])]
      (is (= :history (:type hist))
          "the node decodes back to a :type :history pseudo-state")
      (is (= false (:deep? hist))
          "shallow ⇒ :deep? false")
      (is (= shallow-history-machine back)
          "exact value round-trip")))

  (testing "a DEEP history pseudo-state round-trips with :deep? true"
    (let [back (-> deep-history-machine scxml/spec->scxml scxml/scxml->spec)
          hist (get-in back [:states :player :states :hist])]
      (is (= :history (:type hist)))
      (is (= true (:deep? hist)))
      (is (= deep-history-machine back))))

  (testing "a :default-target round-trips to its relative
            (sibling-keyword) grammar form"
    (let [back (-> default-target-history-machine scxml/spec->scxml scxml/scxml->spec)
          hist (get-in back [:states :player :states :hist])]
      (is (= :history (:type hist)))
      (is (= :playing (:default-target hist))
          "the default-target decodes back to the sibling keyword")
      (is (= default-target-history-machine back))))

  (testing "an absent `:deep?` exports as shallow and decodes to
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
;; SCXML export tolerates INLINE-FN guard / action refs
;; (lossy name/`fn` label), instead of crashing in keyword->id-string.

(deftest inline-fn-guard-action-does-not-crash
  (testing "a NAMED inline-fn guard exports as a lossy name
            label without throwing (as chart/Mermaid tolerate it)"
    (let [guard-fn (with-meta (fn [_] true) {:name 'ready?})
          spec     {:initial :a
                    :states  {:a {:on {:go {:target :b :guard guard-fn}}}
                              :b {}}}
          xml      (scxml/spec->scxml spec)]
      (is (string? xml) "export succeeds (no ClassCastException)")
      (is (str/includes? xml "cond=\"ready?\"")
          "the named fn surfaces its :name meta as the cond label")))

  (testing "an ANONYMOUS inline-fn guard exports as the stable
            `fn` fallback label without throwing"
    (let [spec {:initial :a
                :states  {:a {:on {:go {:target :b :guard (fn [_] true)}}}
                          :b {}}}
          xml  (scxml/spec->scxml spec)]
      (is (string? xml))
      (is (str/includes? xml "cond=\"fn\"")
          "an anonymous fn falls back to the opaque \"fn\" label")))

  (testing "an inline-fn ACTION exports as a lossy label
            (action comment) without throwing"
    (let [action-fn (with-meta (fn [_] {}) {:name 'log!})
          spec      {:initial :a
                     :states  {:a {:on {:tick {:action action-fn}}}
                               :b {}}}
          xml       (scxml/spec->scxml spec)]
      (is (string? xml))
      (is (str/includes? xml "<!-- action: log! -->")
          "the named fn action surfaces its name in the action comment")))

  (testing "guard + action BOTH inline fns on one transition
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
;; The EXTERNAL restart axis (`:reenter? true`) must be a
;; DISTINCT, lossless SCXML round-trip.
;;
;; Spec 005 §Self-transitions / XState v5: a TARGETED transition is INTERNAL
;; by default (its own :exit/:entry do NOT re-run); only `:reenter? true`
;; makes a self / ancestor / compound-declared-descendant target EXTERNAL —
;; re-running :exit+:entry and restarting the target's :after timers + :spawn
;; children. The axis maps onto W3C SCXML's native
;; `<transition type="external">`; an emitter/importer that ignored it would
;; export + round-trip `{:target :same-state}` and
;; `{:target :same-state :reenter? true}` IDENTICALLY, silently dropping
;; `:reenter? true` (which CHANGES runtime behaviour on import).

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
  (testing "a `:reenter? true` self-target emits SCXML
            `type=\"external\"` and round-trips losslessly"
    (let [xml  (scxml/spec->scxml reenter-self-machine)
          back (scxml/scxml->spec xml)]
      (is (str/includes? xml "type=\"external\"")
          "the external-restart axis emits the native SCXML type attr")
      (is (= reenter-self-machine back)
          "exact value round-trip — `:reenter? true` survives")
      (is (true? (get-in back [:states :a :on :ping :reenter?]))
          "the decoded candidate carries `:reenter? true`")))

  (testing "the internal-DEFAULT self-target emits
            the explicit `type=\"internal\"` (NEVER `type=\"external\"`) and
            round-trips WITHOUT `:reenter?`"
    (let [xml  (scxml/spec->scxml internal-self-machine)
          back (scxml/scxml->spec xml)
          cand (get-in back [:states :a :on :ping])]
      (is (not (str/includes? xml "type=\"external\""))
          "the internal default does NOT emit the external type attr")
      ;; The internal default is EXPLICIT: a target-bearing
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

  (testing "the with/without-`:reenter?` SCXML exports DIFFER
            (the two runtime-distinct machines export differently)"
    (is (not= (scxml/spec->scxml reenter-self-machine)
              (scxml/spec->scxml internal-self-machine))
        "external vs internal must produce DISTINCT SCXML"))

  (testing "a compound-declared `:reenter?` descendant target
            round-trips the axis losslessly"
    (let [xml  (scxml/spec->scxml reenter-ancestor-machine)
          back (scxml/scxml->spec xml)]
      (is (str/includes? xml "type=\"external\""))
      (is (= reenter-ancestor-machine back)
          "exact value round-trip preserves `:reenter? true`")))

  (testing "`:reenter?` is emitted ONLY with a target
            (a targetless action-only transition stays internal — no
            `type=\"external\"`, no `:reenter?` synthesised on import)"
    (let [spec {:initial :a
                :states  {:a {:on {:tick {:action :log}}}}}
          xml  (scxml/spec->scxml spec)
          back (scxml/scxml->spec xml)]
      (is (not (str/includes? xml "type=\"external\"")))
      (is (= spec back)))))

;; ---------------------------------------------------------------------------
;; SCXML self-target export must reference a DECLARED state id
;; (never the dangling `same_2dstate` phantom), and must be EXPLICIT about
;; the XState-v5-internal vs SCXML-external default-inversion. Without a
;; `:same-state` arm in the SCXML resolver, the sentinel would export as the
;; absolute path `[:same-state]` → `target="same_2dstate"` — a DANGLING id
;; the document never declares — and the local `scxml->spec` would decode
;; that phantom back to `:same-state`, so a round-trip oracle alone would
;; FALSE-GREEN over invalid W3C SCXML. These tests assert the EXTERNAL form
;; (declared-id validity +
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
  document. A W3C `target` is a SPACE-SEPARATED id list
  (the parallel-root multi-region target), so each listed id counts."
  [xml]
  (->> (re-seq #"<transition\b[^>]*\btarget=\"([^\"]+)\"" xml)
       (map second)
       (mapcat #(str/split % #"\s+"))
       (remove str/blank?)
       set))

(defn- assert-targets-declared
  "Validity guard: EVERY nonempty transition `target=` id must
  reference a state DECLARED in the same SCXML document. A dangling
  `same_2dstate` export FAILS this guard — the phantom id is not in
  `declared-state-ids`."
  [xml]
  (let [declared (declared-state-ids xml)]
    (doseq [t (transition-target-ids xml)]
      (is (contains? declared t)
          (str "transition target=\"" t "\" must reference a declared "
               "state id; declared = " (pr-str declared))))))

(deftest scxml-self-target-references-declared-id
  (testing "ATOMIC self-target (`:same-state`) exports the
            SOURCE state's real id, NEVER the dangling `same_2dstate`"
    (let [spec {:initial :a
                :states  {:a {:on {:ping {:target :same-state}}}}}
          xml  (scxml/spec->scxml spec)]
      (is (not (str/includes? xml "same_2dstate"))
          "the `:same-state` sentinel must NOT leak as a phantom target id")
      (is (str/includes? xml "<transition event=\"ping\" target=\"a\" type=\"internal\"/>")
          "self-target references the source state's own id, internal default")
      (assert-targets-declared xml)
      ;; Supplement the external-form assertions with the local round-trip:
      ;; the canonical decode of a self-target is `:same-state`.
      (is (= :same-state (get-in (scxml/scxml->spec xml) [:states :a :on :ping]))
          "round-trips to the canonical `:same-state` self-target form")))

  (testing "a keyword target NAMING the state's own key is the
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

  (testing "COMPOUND self/ancestor target (`:same-state` declared
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

  (testing "COMPOUND-declared DESCENDANT target references the
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

  (testing "the EXTERNAL (`:reenter? true`) self-target also
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

  (testing "internal vs external self-targets export to DISTINCT,
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
  (testing "the declared-target validity guard holds for EVERY
            non-parallel fixture's export (no dangling `target=` ids anywhere)"
    (doseq [spec [idle-loading-success-error
                  compound-machine
                  always-machine
                  reenter-self-machine
                  internal-self-machine
                  reenter-ancestor-machine]]
      (assert-targets-declared (scxml/spec->scxml spec)))))

;; ---- targets declared INSIDE a parallel region ---------------------------
;;
;; Spec 005 §Cross-region coordination drives each region as a synthetic
;; single machine, so every target declared inside a region resolves
;; strictly WITHIN it: a region-local vector target is an in-region
;; absolute path, and a region body's own root `:on` keyword target names
;; one of that region's top-level states. The exporter qualifies every
;; region state id with its region (`<region>___<state>`), so the targets
;; must carry the same prefix. Resolved from the MACHINE root instead,
;; `[:c :d]` would emit the dangling `c___d`, the region-root `:reset :idle`
;; the dangling `idle`, and an in-region path whose head shadows a sibling
;; region's name would emit that sibling REGION's id — while the local round
;; trip stayed green, because `decode-target` would invert the same mistake.
;; That is why the declared-id guard and exact ids are asserted here rather
;; than only the round trip.

(def region-vector-target-machine
  "A region-local vector target into a compound."
  {:type    :parallel
   :regions {:r {:initial :a
                 :states  {:a {:on {:go [:c :d]}}
                           :c {:initial :d :states {:d {} :e {}}}}}
             :q {:initial :x :states {:x {}}}}})

(def region-root-on-machine
  "A region body's own root `:on` keyword target."
  {:type    :parallel
   :regions {:a {:initial :idle
                 :on      {:reset :idle}
                 :states  {:idle {:on {:go :b}} :b {}}}
             :b {:initial :x :states {:x {}}}}})

(def region-shadowing-target-machine
  "An in-region path whose head SHADOWS a sibling region's name (region `:r`
  declares a real compound `:q`, and `:q` is also a region with its own
  `:y`). Spec 005: it resolves normally, in-region."
  {:type    :parallel
   :regions {:r {:initial :a
                 :states  {:a {:on {:go [:q :y]}}
                           :q {:initial :y :states {:y {}}}}}
             :q {:initial :y :states {:y {}}}}})

(def region-history-vector-default-machine
  "A history pseudo-state inside a region whose `:default-target` is a
  vector (absolute from the region root), to a non-sibling leaf so the
  decode is the vector form."
  {:type    :parallel
   :regions {:r {:initial :c
                 :states  {:c {:initial :d
                               :states  {:d {}
                                         :f {:initial :g :states {:g {}}}
                                         :h {:type           :history
                                             :deep?          true
                                             :default-target [:c :f :g]}}}}}
             :q {:initial :x :states {:x {}}}}})

(deftest region-targets-are-region-scoped
  (testing "a region-local vector target carries the region
            prefix every region state id carries"
    (let [xml (scxml/spec->scxml region-vector-target-machine)]
      (is (str/includes? xml "<transition event=\"go\" target=\"r___c___d\" type=\"internal\"/>"))
      (assert-targets-declared xml)))
  (testing "a region body's own `:on` keyword target names a
            top-level state of THAT region"
    (let [xml (scxml/spec->scxml region-root-on-machine)]
      (is (str/includes? xml "<transition event=\"reset\" target=\"a___idle\" type=\"internal\"/>"))
      (is (str/includes? xml "<transition event=\"go\" target=\"a___b\" type=\"internal\"/>")
          "control: an in-region sibling keyword is region-scoped too")
      (assert-targets-declared xml)))
  (testing "a head that shadows a sibling region's name stays
            in-region rather than naming the sibling REGION (whose id IS
            declared, so only the exact id can see this one)"
    (let [xml (scxml/spec->scxml region-shadowing-target-machine)]
      (is (str/includes? xml "<transition event=\"go\" target=\"r___q___y\" type=\"internal\"/>"))
      (assert-targets-declared xml)))
  (testing "a vector history `:default-target` inside a region
            is region-scoped too"
    (let [xml (scxml/spec->scxml region-history-vector-default-machine)]
      (is (str/includes? xml "<transition target=\"r___c___f___g\"/>"))
      (assert-targets-declared xml))))

(deftest region-scoped-targets-round-trip
  (testing "`decode-target` strips the region prefix back off,
            so each region-scoped export round-trips exactly"
    (doseq [spec [region-vector-target-machine
                  region-root-on-machine
                  region-shadowing-target-machine
                  region-history-vector-default-machine]]
      (is (= spec (scxml/scxml->spec (scxml/spec->scxml spec)))
          (pr-str spec)))))

(deftest scxml-parallel-fixtures-targets-declared
  (testing "the declared-target guard holds for the PARALLEL
            fixtures too, root multi-region targets included"
    (doseq [spec [parallel-machine
                  parallel-on-done-machine
                  parallel-root-on-single-machine
                  parallel-root-on-multi-machine
                  parallel-root-after-single-machine
                  parallel-root-after-multi-machine
                  region-vector-target-machine
                  region-root-on-machine
                  region-shadowing-target-machine
                  region-history-vector-default-machine]]
      (assert-targets-declared (scxml/spec->scxml spec)))))

;; ---- consumer-attachment :rf.cofx/requires — intentional omission ------
;;
;; SCXML INTENTIONALLY omits EP-0017 consumer-attachment
;; `:rf.cofx/requires` (W3C SCXML has no attribute for it; a re-frame2 import
;; re-attaches it from its own registry, and the chart is the "which
;; transitions consume which facts" surface). Lock the omission + a negative
;; check (no `:rf.world/inputs` / `inject-cofx` vocabulary).

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
  (testing "SCXML surfaces the guard `cond=` NAME + the action
            comment NAME but omits the consumer-attachment requires diet"
    (let [out (scxml/spec->scxml scxml-cofx-bearing-machine)]
      ;; The guard `cond=` + the action comment render (XML-mangled
      ;; names — SCXML escapes `-` / `?` to `_2d` / `_3f`); a transition
      ;; carries both so the topology survives.
      (is (str/includes? out "cond="))
      (is (str/includes? out "action:"))
      ;; the requires diet + its cofx ids are NOT emitted
      (is (not (str/includes? out "rf.cofx")))
      (is (not (str/includes? out "requires")))
      (is (not (str/includes? out "time-ms")))
      (is (not (str/includes? out "retry-jitter-ms")))
      ;; NO foreign cofx vocabulary.
      (is (not (str/includes? out "rf.world/inputs")))
      (is (not (str/includes? out "inject-cofx"))))))

;; ---------------------------------------------------------------------------
;; SCXML executable / data-model content is IGNORED, never
;; reinterpreted as topology
;;
;; W3C SCXML §3.3 lets a conforming `<state>` carry `<onentry>`, `<onexit>`,
;; `<invoke>`, `<datamodel>` and friends. The re-frame2 importer models
;; TOPOLOGY only — `<state>` / `<final>` / `<history>` / `<transition>` — so
;; those bodies are LOSSY BY DESIGN, exactly as the ns docstring's
;; "Not supported" list says.
;;
;; What is NOT acceptable is inventing topology out of them. A direct-child
;; collector that accepted EVERY non-transition tag would turn an
;; `<onentry/>` into an id-less `:states` entry keyed by `nil`, and the
;; import would return a definition `grammar/valid-definition?` — and
;; therefore `reg-machine`, `MachineChart` and every sibling emitter —
;; rejects, surfacing far downstream as a compound-state error naming a
;; state the programmer never authored.
;;
;; Two invariants are pinned here:
;;   1. only recognised topology tags are collected as child states;
;;   2. every SUCCESSFUL `scxml->spec` result passes the canonical recursive
;;      grammar gate (the postcondition the public docstring promises).

(def onentry-only-scxml
  "The minimal case: a conforming `<state>` whose only child is an
  empty `<onentry/>`."
  (marked "<scxml xmlns='http://www.w3.org/2005/07/scxml' version='1.0' initial='idle'>"
       "<state id='idle'><onentry/></state>"
       "</scxml>"))

(deftest import-ignores-empty-onentry-executable-content
  (testing "an empty <onentry/> is ignored, not promoted to a nil-keyed state"
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
  "The unsupported families — `<datamodel>`/`<data>` at the
  root, `<onentry>` with a `<log>` body, `<invoke>` with a `<param>`, and
  `<onexit>` with an `<assign>` — wrapped around otherwise supported states
  and transitions."
  (marked "<scxml xmlns='http://www.w3.org/2005/07/scxml' version='1.0' initial='go'>"
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
  (testing "<datamodel>/<onentry>/<onexit>/<invoke> subtrees are dropped
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
  (marked "<scxml xmlns='http://www.w3.org/2005/07/scxml' version='1.0' initial='a'>"
       "<state id='a'>"
       "<onentry><state id='sneaky'/><final id='sneakier'/></onentry>"
       "<transition event='go' target='b'/>"
       "</state>"
       "<state id='b'/>"
       "</scxml>"))

(deftest import-does-not-promote-a-state-nested-in-an-unsupported-element
  (testing "only DIRECT recognised children are collected; an
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
  (testing "the allowlist keeps <history> and nested compounds; only
            the unsupported families are dropped"
    (let [spec (scxml/scxml->spec
                 (marked "<scxml xmlns='http://www.w3.org/2005/07/scxml' version='1.0' initial='outer'>"
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
;; The import POSTCONDITION: success implies a projectable
;; definition. Parser output that cannot be represented as a valid re-frame2
;; definition throws the documented `:scxml/invalid-spec`, value-free.

(def missing-initial-scxml
  "No root `initial` — the machine contract wants a keyword `:initial`.
  Returning `{:states {:a {}}}` silently would contradict the public
  docstring's stated error boundary."
  (marked "<scxml xmlns='http://www.w3.org/2005/07/scxml' version='1.0'>"
       "<state id='a'/></scxml>"))

(deftest import-throws-invalid-spec-rather-than-returning-a-malformed-definition
  (testing "a document whose topology cannot be a valid re-frame2
            definition throws :scxml/invalid-spec instead of returning it"
    (is (thrown? #?(:clj clojure.lang.ExceptionInfo :cljs js/Error)
                 (scxml/scxml->spec missing-initial-scxml)))
    (let [d (try (scxml/scxml->spec missing-initial-scxml)
                 nil
                 (catch #?(:clj clojure.lang.ExceptionInfo :cljs :default) e (ex-data e)))]
      (is (= :scxml/invalid-spec (:rf.error/id d)) "the documented discriminator"))))

(deftest import-invalid-spec-error-is-value-free
  (testing "the import-side invalid-spec ex-data carries only the
            shared value-free summary: no raw XML, no parsed definition"
    (let [secret "patientrecordsecret42"
          xml    (marked "<scxml xmlns='http://www.w3.org/2005/07/scxml' version='1.0'>"
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
  (testing "the postcondition holds across the whole supported
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

;; ---------------------------------------------------------------------------
;; "ignored wholesale" has to hold for the ROOT topology too.
;;
;; The allowlist above stops an unsupported subtree contributing a `:states`
;; entry. It says nothing about the earlier decision `scxml->spec` makes
;; BEFORE any collector runs: parallel-root or flat? A scan of EVERY token in
;; the root body for the first open `<parallel>` would let markup the importer
;; has already declared unreachable choose — and wholly define — the imported
;; machine.
;;
;; W3C SCXML §6.4 makes this reachable from a CONFORMING document: `<invoke>`
;; carries a whole nested `<scxml>` inline through `<content>`. That is a real
;; SCXML feature, so the answer is not to reject it; the answer is that the
;; outer parser must not read into it. Read that way, the inner document's
;; `<parallel>` would REPLACE the outer machine outright while
;; `grammar/valid-definition?` returned true — the substitute is itself
;; well-formed — so the importer would produce a confidently wrong machine
;; with no diagnostic anywhere.
;;
;; `<invoke>` is not special here, and the importer does not name it: a
;; nested `<parallel>` inside an ordinary `<state>` (unsupported by design —
;; see `topology-child-tags`) would reach the same selector by the same route.

(def invoked-inner-parallel
  "The payload document: a two-region parallel machine, emitted by our own
  exporter so the fixture cannot drift from the emitter."
  {:type    :parallel
   :regions {:left  {:initial :a :states {:a {}}}
             :right {:initial :b :states {:b {}}}}})

(def outer-flat-machine
  "The OUTER machine — the one an import of the assembled document must
  return, unchanged, whatever the invoked payload contains."
  {:initial :idle
   :states  {:idle {:on {:go :done}}
             :done {:final? true}}})

(defn- inline-invoked-scxml
  "Assemble the outer machine's SCXML with `inner-scxml` embedded verbatim in
  a W3C §6.4 `<invoke><content>` payload on `<state id='idle'>`. The inner
  document's XML declaration is dropped so the assembled document is
  well-formed."
  [inner-scxml]
  (marked "<scxml xmlns='http://www.w3.org/2005/07/scxml' version='1.0' initial='idle'>"
       "<state id='idle'>"
       "<transition event='go' target='done'/>"
       "<invoke type='http://www.w3.org/TR/scxml/'><content>"
       (str/replace inner-scxml #"(?s)^\s*<\?xml[^?]*\?>\s*" "")
       "</content></invoke>"
       "</state>"
       "<final id='done'/>"
       "</scxml>"))

(deftest import-does-not-adopt-a-parallel-root-from-an-invoked-document
  (testing "an inline <invoke><content><scxml> payload cannot
            replace the outer machine's topology"
    (let [spec (scxml/scxml->spec
                 (inline-invoked-scxml (scxml/spec->scxml invoked-inner-parallel)))]
      (is (= outer-flat-machine spec)
          "the OUTER flat machine imports exactly; the invoked payload is ignored wholesale")
      (is (nil? (:type spec))
          "the inner <parallel> must not make this a parallel machine")
      (is (nil? (:regions spec))
          "and must not contribute regions")
      (is (= #{:idle :done} (set (keys (:states spec))))
          "the outer states survive; :left/:right never appear")
      (is (g/valid-definition? spec)))))

(deftest import-does-not-adopt-a-parallel-root-nested-in-a-state
  (testing "the same holds for a <parallel> nested in an ordinary
            <state> (unsupported by design): <invoke> is not a special case"
    (let [spec (scxml/scxml->spec
                 (marked "<scxml xmlns='http://www.w3.org/2005/07/scxml' version='1.0' initial='idle'>"
                      "<state id='idle'>"
                      "<transition event='go' target='done'/>"
                      "<parallel id='nested'>"
                      "<state id='nested___left' initial='nested___left___a'>"
                      "<state id='nested___left___a'/></state>"
                      "<state id='nested___right' initial='nested___right___b'>"
                      "<state id='nested___right___b'/></state>"
                      "</parallel>"
                      "</state>"
                      "<final id='done'/>"
                      "</scxml>"))]
      (is (= outer-flat-machine spec)
          "the nested <parallel> is dropped with its subtree, not promoted to the root")
      (is (nil? (:type spec)))
      (is (g/valid-definition? spec)))))

(deftest import-still-reads-a-real-root-parallel-and-a-flat-invoked-payload
  (testing "the controls: a DIRECT root <parallel> still imports,
            and an invoked FLAT payload leaves the outer definition exact"
    ;; Control 1 — the supported location. A `<parallel>` that really is a
    ;; direct child of `<scxml>` must still select the parallel branch.
    (is (= parallel-machine
           (scxml/scxml->spec (scxml/spec->scxml parallel-machine)))
        "a genuine root <parallel> still round-trips value-equal")
    ;; Control 2 — the same invoked-payload shape carrying a FLAT document.
    ;; The payload carries no `<parallel>`, so the root selector cannot affect
    ;; it: that is what makes it a control on the subtree drop rather than on
    ;; the parallel selector.
    (is (= outer-flat-machine
           (scxml/scxml->spec
             (inline-invoked-scxml (scxml/spec->scxml idle-loading-success-error))))
        "an invoked flat payload contributes nothing either")))

;; ---------------------------------------------------------------------------
;; Import reads ONLY this library's own marked exports
;;
;; `scxml->spec` is round-trip-only. The export codec is
;; injective for our own output but mis-decodes foreign ids (`logged-out` →
;; `:logged/out`, `step_1a` → a control char) and a W3C space-separated event
;; list becomes ONE keyword, while `grammar/valid-definition?` still passes —
;; a confident, well-formed, WRONG machine. So `spec->scxml` writes a fixed
;; format comment right after the prolog, and `scxml->spec` refuses any
;; document without exactly that marker, BEFORE any export convention runs.

(def ^:private reviewer-foreign-scxml
  "A third-party document, unmarked."
  (str "<scxml xmlns=\"http://www.w3.org/2005/07/scxml\" version=\"1.0\" initial=\"logged-out\">"
       "<state id=\"logged-out\"><transition event=\"login-ok\" target=\"step_1a\"/></state>"
       "<state id=\"step_1a\"><transition event=\"logout session.expired\" target=\"logged-out\"/></state>"
       "</scxml>"))

(defn- thrown-scxml-id
  "The `:rf.error/id` a `scxml->spec` call throws, or `::returned` when it
  returns normally."
  [input]
  (try
    (scxml/scxml->spec input)
    ::returned
    (catch #?(:clj Exception :cljs :default) e
      (:rf.error/id (ex-data e)))))

(deftest import-refuses-an-unmarked-foreign-document
  (testing "a document spec->scxml did not write throws
            :scxml/unsupported-format instead of importing silently renamed"
    (is (= :scxml/unsupported-format (thrown-scxml-id reviewer-foreign-scxml)))
    (testing "the refusal is value-free: no raw XML and no document id rides the error"
      (let [e (try (scxml/scxml->spec reviewer-foreign-scxml) nil
                   (catch #?(:clj Exception :cljs :default) e e))
            strs (deep-strings (ex-data e))]
        (is (some? e))
        (is (= :re-export-from-the-source-definition (:recovery (ex-data e))))
        (is (not-any? #(str/includes? % "logged-out") strs)
            "no foreign id leaks into ex-data")
        (is (not-any? #(str/includes? % "<scxml") strs)
            "the raw document does not ride the error")))))

(deftest import-accepts-the-same-bytes-once-marked
  (testing "control on the SAME bytes: prepending the marker is
            the whole difference between refused and imported"
    (let [spec (scxml/scxml->spec (str scxml-format-marker "\n" reviewer-foreign-scxml))]
      (is (map? spec) "the marked document imports")
      (is (g/valid-definition? spec)))
    (testing "the marker may follow an XML prolog, with whitespace around it"
      (is (map? (scxml/scxml->spec
                  (str "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n  "
                       scxml-format-marker "\n\n" reviewer-foreign-scxml)))))))

(deftest import-refuses-a-different-marker-version
  (testing "only the EXACT marker is accepted; a v2 marker, or the
            marker anywhere but first, throws the same id"
    (is (= :scxml/unsupported-format
           (thrown-scxml-id (str "<!-- re-frame2 machines-viz SCXML v2 -->\n"
                                 reviewer-foreign-scxml))))
    (is (= :scxml/unsupported-format
           (thrown-scxml-id (str "<!-- a comment first -->\n" scxml-format-marker "\n"
                                 reviewer-foreign-scxml))))))

(deftest export-writes-the-marker-right-after-the-prolog
  (testing "every export's line after the prolog is the marker,
            flat and parallel alike, so our own exports keep round-tripping"
    (doseq [[label machine] [["flat" idle-loading-success-error]
                             ["parallel" parallel-machine]]]
      (let [lines (str/split-lines (scxml/spec->scxml machine))]
        (is (str/starts-with? (first lines) "<?xml ") label)
        (is (= scxml-format-marker (second lines)) label)
        (is (= machine (scxml/scxml->spec (scxml/spec->scxml machine))) label)))))
