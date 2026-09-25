(ns day8.re-frame2-machines-viz.engine-grammar-parity-test
  "ENGINE-GRAMMAR PARITY tests.

  machines-viz HAND-MIRRORS the runtime engine's machine-definition
  grammar walk + target resolution: `grammar/normalise-root-targets`,
  `grammar/reenter?`, `grammar/transition-candidates`, and the chart's
  `resolve-target-path` each re-implement, in plain JVM-portable data,
  what `re-frame.machines.parallel` / `re-frame.machines.transition` /
  `re-frame.machines.grammar` do at runtime. The mirror is BY-DESIGN —
  the viz tool is bundle-isolated from production
  (`check-bundle-isolation` pins that nothing under implementation/ may
  `:require` this jar, and this jar's SRC never `:require`s the engine)
  and `grammar.cljc` is deliberately dep-free (`clojure.string` only), so
  a shared `:require` — or a shared grammar-codec ns — would punch a hole
  through the very isolation boundary the sentinel exists to protect.

  Because the copies are kept in sync only by hand, a silent drift — the
  viz tool re-wiring an edge the engine resolves differently — is the
  risk. These tests make that drift LOUD: each feeds REPRESENTATIVE
  machine-defs through BOTH the machines-viz copy AND the engine fn and
  asserts EQUAL OUTPUT (structural / behavioural, NOT a source-text
  compare). If one fails, the viz grammar drifted from the engine — re-
  sync the copy, do NOT delete the test.

  This is a TEST, not the shipped tool: it MAY `:require` the engine
  (the `day8/re-frame2-machines` dep lives ONLY on the :test alias —
  tools/machines-viz/deps.edn). The engine-require here does NOT trip
  `check-bundle-isolation`: that gate greps the compiled
  examples/counter PRODUCTION BUNDLE, never the test classpath, and the
  shipped machines-viz jar (`:clein/build :src-dirs [\"src\"]`) carries
  no test deps.

  The engine targets `rf.machines.parallel/normalise-root-targets`,
  `rf.machines.transition/normalise-candidates`, and `rf.machines.transition/target-path` are
  PRIVATE (`defn-`); the tests reach them through their vars
  (`#'ns/fn`), which is the standard, drift-honest way to pin a private
  contract from outside its namespace."
  (:require #?(:clj  [clojure.test :refer [deftest is testing]]
               :cljs [cljs.test    :refer-macros [deftest is testing]])
            [day8.re-frame2-machines-viz.chart.layout :as layout]
            [day8.re-frame2-machines-viz.grammar :as g]
            [re-frame.machines.choice :as rf.machines.choice]
            [re-frame.machines.lifecycle-fx.validation :as rf.machines.lifecycle-fx.validation]
            [re-frame.machines.parallel :as rf.machines.parallel]
            [re-frame.machines.timeout :as rf.machines.timeout]
            [re-frame.machines.transition :as rf.machines.transition]))

;; ---------------------------------------------------------------------------
;; Private-var accessors for the engine fns the viz copies mirror.
;;
;; `rf.machines.parallel/normalise-root-targets`, `rf.machines.transition/normalise-candidates`,
;; and `rf.machines.transition/target-path` are `defn-` (engine-internal). Pinning a
;; private contract from outside its ns via its var is intentional here —
;; the whole point is to assert the public viz copy agrees with the
;; engine's internal resolver.

(def engine-normalise-root-targets @#'rf.machines.parallel/normalise-root-targets)
(def engine-normalise-candidates   @#'rf.machines.transition/normalise-candidates)
(def engine-target-path            @#'rf.machines.transition/target-path)

;; ---------------------------------------------------------------------------
;; PARITY 1 — normalise-root-targets
;;
;; PARITY: mirror of re-frame.machines.parallel/normalise-root-targets —
;; if this fails, the viz grammar drifted from the engine; re-sync, do
;; not delete.
;;
;; grammar/normalise-root-targets (grammar.cljc) is byte-identical-logic
;; to the engine's rf.machines.parallel/normalise-root-targets: a parallel-ROOT
;; `:on` / `:after` candidate's `:target` is normalised into a vector of
;; region-qualified absolute targets `[[<region> & <in-region-path>] …]`.
;; Both projected/exported edges (viz) and the regions the engine moves
;; must address the SAME regions, so the two normalisers must agree on
;; every shape of the grammar.

(def ^:private root-target-fixtures
  "Representative parallel-root `:target` shapes spanning every arm of
  the shared `cond`."
  [;; nil / absent → [] (targetless / action-only)
   nil
   ;; a vector of KEYWORDS → ONE region-qualified target, wrapped
   [:a :two]
   [:region :nested :leaf]
   ;; a single region head with no in-region path
   [:r]
   ;; a vector of VECTORS → MULTIPLE region-qualified targets, as-is
   [[:a :x] [:b :y]]
   [[:r1 :s] [:r2 :t] [:r3 :u]]
   ;; a non-vector (e.g. a keyword) → [] (the :else arm)
   :not-a-vector
   ;; the empty vector — degenerate, both treat it via the (every? ...)
   ;; arm: (every? vector? []) is true, so → (vec [])
   []])

(deftest normalise-root-targets-parity
  (testing "viz grammar/normalise-root-targets agrees with the engine resolver"
    (doseq [target root-target-fixtures]
      (is (= (engine-normalise-root-targets target)
             (g/normalise-root-targets target))
          (str "root-target normalisation drifted for " (pr-str target))))))

;; ---------------------------------------------------------------------------
;; PARITY 2 — reenter?
;;
;; PARITY: mirror of re-frame.machines.transition's
;; `(true? (:reenter? transition))` read — if this fails, the viz grammar
;; drifted from the engine; re-sync, do not delete.
;;
;; The engine classifies a transition candidate as an EXTERNAL self /
;; ancestor / compound-declared-descendant restart iff the candidate map
;; opts in with `:reenter? true`, read as `(true? (:reenter? transition))`
;; (re-frame.machines.transition). grammar/reenter? mirrors this exactly
;; for a candidate MAP, adding a `(map? candidate)` guard so it is total
;; over non-map candidates (a bare keyword / vector-path target — which
;; can never carry `:reenter?` — yields false). The viz edge renders a
;; `:reenter? true` transition DISTINCTLY from its internal default, so it
;; must agree with the engine on which candidates are external.

(def ^:private reenter-candidate-fixtures
  "Representative candidate MAPS — the only shape the engine's
  `(true? (:reenter? transition))` read sees (it operates on a selected
  candidate map)."
  [{:target :a :reenter? true}
   {:target :a :reenter? false}
   {:target :a}                       ;; :reenter? absent → false
   {:target :a :reenter? nil}         ;; explicit nil → false (true? nil)
   {:target :a :reenter? :truthy}     ;; truthy-but-not-true → false (true? only)
   {:action :log}                     ;; internal candidate, no :reenter?
   {}])                               ;; empty candidate

(deftest reenter?-parity
  (testing "viz grammar/reenter? agrees with the engine's (true? (:reenter? transition)) read"
    (doseq [candidate reenter-candidate-fixtures]
      (is (= (true? (:reenter? candidate))
             (g/reenter? candidate))
          (str ":reenter? classification drifted for " (pr-str candidate)))))

  (testing "the viz (map? candidate) guard makes reenter? total over non-map candidates"
    ;; The engine only ever reads :reenter? off a selected candidate MAP;
    ;; the viz copy is additionally total over the bare keyword / vector-
    ;; path target forms a candidate can take before normalisation, which
    ;; never carry :reenter?. So these must all be false — none is an
    ;; external restart.
    (is (false? (g/reenter? :a)))
    (is (false? (g/reenter? [:a :b])))
    (is (false? (g/reenter? nil)))))

;; ---------------------------------------------------------------------------
;; PARITY 3 — transition-candidates
;;
;; PARITY: mirror of re-frame.machines.transition/normalise-candidates
;; (the engine's transition-grammar normalisation) — if this fails, the
;; viz grammar drifted from the engine; re-sync, do not delete.
;;
;; Both normalise a transition spec into a vector of candidate MAPS. They
;; agree across the REPRESENTATIVE grammar a machine-def actually carries:
;; a keyword sibling target, an absolute keyword vector-path target, a
;; vector of candidate maps (first-guard-pass wins), and a single
;; transition map.
;;
;; SCOPE NOTE (deliberate, documented divergence — NOT drift): the two
;; differ ONLY on degenerate / non-grammar shapes the viz walker handles
;; more permissively than the runtime normaliser:
;;   - a MIXED vector ([:a [:x :y]] — not all maps, not all keywords):
;;     viz mapcat-explodes it into per-element candidates; the engine
;;     treats any non-all-maps vector as a single absolute vec-target.
;;   - a malformed value (e.g. 42): viz → [] (drop); engine → throws.
;; These are out of scope for THIS pair (the engine surfaces them through
;; registration validation, not the projector). The equality assertion is
;; pinned to the shared grammar so a drift WITHIN that region stays loud;
;; the divergent shapes are asserted separately below so a future change
;; that accidentally ALIGNS or further DIVERGES them is also caught.
;;
;; `nil` is NOT a divergence: Spec 005 §Forbidden transitions declares
;; `nil` and `{}` RUNTIME-EQUIVALENT, so a viz walker silently dropping a
;; nil-spelled forbidden transition (while rendering the `{}` spelling as a
;; blocking chip) would make a reader believe an event was inherited /
;; reachable when the engine actually blocks it. `grammar/transition-candidates`
;; special-cases `(nil? spec) [{}]`, matching the engine, and `nil` sits in
;; `shared-grammar-spec-fixtures` below as a genuine parity case.

(def ^:private shared-grammar-spec-fixtures
  "Transition specs on which the viz walker and the engine normaliser
  MUST agree — the representative grammar a machine-def carries."
  [;; a keyword sibling target
   :authenticated
   ;; an absolute keyword vector-path target
   [:outer :inner :leaf]
   ;; a vector of candidate maps (first-guard-pass wins)
   [{:guard :g1 :target :a} {:target :b :action :log}]
   [{:target :a} {:target :b} {:target :c}]
   ;; a single transition map (targeted)
   {:target :a :guard :g}
   ;; a single transition map (internal / action-only)
   {:action :log}
   ;; nil ≡ {} (Spec 005 §Forbidden transitions): a
   ;; forbidden transition spelled nil normalises identically to the
   ;; empty-map spelling on BOTH sides.
   nil])

(deftest transition-candidates-parity
  (testing "viz grammar/transition-candidates agrees with the engine normaliser across the shared grammar"
    (doseq [spec shared-grammar-spec-fixtures]
      (is (= (engine-normalise-candidates spec :rf.error/test-bad-value)
             (g/transition-candidates spec))
          (str "candidate normalisation drifted for " (pr-str spec))))))

(deftest transition-candidates-documented-divergence
  (testing "the deliberate viz-vs-engine divergences on degenerate shapes are unchanged"
    ;; If any of these EQUALITIES start failing, the divergence shifted —
    ;; re-read the scope note above and confirm the change is intentional
    ;; (a viz walker that newly drops/explodes a shape differently from
    ;; the documented contract is itself a drift signal).
    (testing "mixed vector: viz mapcat-explodes, engine treats as one vec-target"
      (is (= [{:target :a} {:target [:x :y]}]
             (g/transition-candidates [:a [:x :y]])))
      (is (= [{:target [:a [:x :y]]}]
             (engine-normalise-candidates [:a [:x :y]] :rf.error/test-bad-value))))))

;; ---------------------------------------------------------------------------
;; PARITY 4 — resolve-target-path (chart/layout) vs target-path
;;
;; PARITY: mirror of re-frame.machines.transition/target-path — if this
;; fails, the viz grammar drifted from the engine; re-sync, do not delete.
;;
;; The chart's resolve-target-path computes the absolute target path of a
;; transition relative to its declaring (source) path, exactly as the
;; engine's target-path does at runtime: `:same-state` → the declaring
;; state's own path; a keyword → sibling at the declaring level; a
;; vector → absolute. The chart's edges must land on the SAME node the
;; engine transitions to. resolve-target-path is `defn-` in chart/layout;
;; the test reaches it through its var.

(def viz-resolve-target-path @#'layout/resolve-target-path)

(def ^:private target-path-fixtures
  "[<decl/source-path> <target>] pairs spanning the shared arms:
  `:same-state`, keyword sibling, absolute keyword vector-path, and nil
  (internal — both return nil)."
  [[[:idle]              :same-state]
   [[:outer :inner]      :same-state]
   [[:idle]              :running]            ;; keyword sibling at top level
   [[:outer :inner]      :sibling]            ;; keyword sibling, nested
   [[:outer :inner :a]   :b]                  ;; keyword sibling, deeper
   [[:a]                 [:other :leaf]]      ;; absolute keyword vector-path
   [[:outer :inner]      [:x :y :z]]          ;; absolute keyword vector-path
   [[:idle]              nil]                 ;; internal (no :target) → nil
   [[:outer :inner]      nil]])

(deftest resolve-target-path-parity
  (testing "viz chart resolve-target-path agrees with the engine target-path"
    (doseq [[decl-path target] target-path-fixtures]
      (is (= (engine-target-path decl-path target)
             (viz-resolve-target-path decl-path target))
          (str "target-path resolution drifted for decl-path "
               (pr-str decl-path) " target " (pr-str target))))))

;; ---------------------------------------------------------------------------
;; PARITY 5 — resolve-timeout-ms vs resolve-duration-ms (EP-0029 A4)
;;
;; PARITY: mirror of re-frame.machines.timeout/resolve-duration-ms — if this
;; fails, the viz duration resolver drifted from the engine; re-sync, do not
;; delete.
;;
;; grammar/resolve-timeout-ms (grammar.cljc) re-states, bundle-isolated from
;; the runtime `machines` artefact, the engine's `:timeout` duration
;; grammar: a POSITIVE INTEGER literal ms, OR an ISO-8601 duration STRING
;; (fixed 365-day / 30-day year/month convention, fractional seconds rounded
;; to the nearest ms), with EVERYTHING else — the XState "5s"/"10ms"
;; shorthand, a non-positive/non-integer number, a bare "P", a fn/vector/nil
;; — resolving to nil. The chart / mermaid / SCXML emitters render the ms
;; the resolver produces (`after / <ms>` label, clock glyph, SCXML delay),
;; so a drift in the arithmetic re-times every rendered timeout relative to
;; what the engine actually fires.

(def ^:private duration-fixtures
  "Representative :timeout durations spanning every arm: positive-integer
  literal, each ISO-8601 component, combined components, fractional
  seconds, case-insensitivity, and every reject-to-nil shape (XState
  shorthand, non-positive/non-integer, degenerate ISO, wrong type)."
  [;; positive-integer literal ms
   5000 1 999999
   ;; ISO-8601 single components
   "PT5S" "PT2M" "PT1H" "P1D" "P1W" "P1M" "P1Y"
   ;; combined
   "PT1H30M" "P1DT1H1M1S"
   ;; fractional seconds (round to nearest ms)
   "PT0.5S" "PT1.5S" "PT0.25S" "PT0.001S"
   ;; case-insensitive
   "pt5s" "p1d"
   ;; non-positive / non-integer numbers → nil
   0 -5 1.5
   ;; XState shorthand → nil (a documented divergence from XState)
   "5s" "10ms" "2m"
   ;; degenerate / malformed ISO → nil
   "P" "PT" "PT0S" "P0D" "soon" ""
   ;; wrong types → nil
   nil [1000] :pt5s])

(deftest resolve-timeout-ms-parity
  (testing "viz grammar/resolve-timeout-ms agrees with the engine
            rf.machines.timeout/resolve-duration-ms on every duration shape"
    (doseq [d duration-fixtures]
      (is (= (rf.machines.timeout/resolve-duration-ms d)
             (g/resolve-timeout-ms d))
          (str "timeout duration resolution drifted for " (pr-str d))))))

;; ---------------------------------------------------------------------------
;; PARITY 6 — desugar-timeouts (EP-0029 A4)
;;
;; PARITY: mirror of re-frame.machines.timeout/desugar-timeouts — if this
;; fails, the viz `:timeout` → `:after` lowering drifted from the engine;
;; re-sync, do not delete.
;;
;; project-definition (layout.cljc) calls g/desugar-grammar — which applies
;; g/desugar-timeouts — as the shared ingestion boundary for all three
;; emitters, so the emitters render the SAME lowered `:after` table the
;; engine drives. Both lower state-level, spawn-level, root-level, nested-
;; compound, and parallel-region `:timeout` / `:on-timeout` into the
;; equivalent `:after` entry; the two must produce EQUAL machine-defs so a
;; rendered timer lands on the delay the engine actually fires. (The engine
;; short-circuits a timeout-free machine to the identical object; the viz
;; rebuilds it value-equal — the `=` assertion covers both.)

(def ^:private timeout-machine-fixtures
  "Representative machine-defs spanning every desugar arm."
  [;; state-level timeout coexisting with :on
   {:initial :a
    :states  {:a {:timeout 1000 :on-timeout :b :on {:x :c}} :b {} :c {}}}
   ;; ISO-8601 state timeout
   {:initial :a :states {:a {:timeout "PT2S" :on-timeout :done} :done {}}}
   ;; synthetic entry merges into an existing :after (collision → explicit wins)
   {:initial :a
    :states  {:a {:after {2000 :explicit} :timeout "PT2S" :on-timeout :from-timeout}}}
   ;; spawn-level timeout anchored on the state :after
   {:initial :a
    :states  {:a {:spawn {:machine :child :timeout 500 :on-timeout :fallback}
                  :on    {:x :b}}
              :b {}}}
   ;; root-level (whole-machine) timeout
   {:initial :a :timeout 3000 :on-timeout :expired :states {:a {}}}
   ;; the root :spawn's own timeout, lowered onto the root :after
   {:type    :parallel
    :spawn   {:machine-id :child :timeout 500 :on-timeout {:target [:r1 :y]}}
    :regions {:r1 {:initial :x :states {:x {} :y {}}}}}
   ;; nested compound
   {:initial :outer
    :states  {:outer {:initial :inner
                      :states  {:inner {:timeout 1000 :on-timeout :done} :done {}}}}}
   ;; parallel regions
   {:type    :parallel
    :regions {:r1 {:initial :x :states {:x {:timeout 1000 :on-timeout :y} :y {}}}
              :r2 {:initial :p :states {:p {} :q {}}}}}
   ;; timeout-free control (engine returns unchanged; viz rebuilds value-equal)
   {:initial :a :states {:a {:on {:x :b}} :b {:after {500 :a}}}}])

(deftest desugar-timeouts-parity
  (testing "viz grammar/desugar-timeouts agrees with the engine
            rf.machines.timeout/desugar-timeouts across every machine shape"
    (doseq [m timeout-machine-fixtures]
      (is (= (rf.machines.timeout/desugar-timeouts m)
             (g/desugar-timeouts m))
          (str ":timeout desugar drifted for " (pr-str m))))))

;; ---------------------------------------------------------------------------
;; PARITY 7 — desugar-choices (EP-0029 A5)
;;
;; PARITY: mirror of re-frame.machines.choice/desugar-choices — if this
;; fails, the viz `:type :choice` → `:always` lowering drifted from the
;; engine; re-sync, do not delete.
;;
;; g/desugar-grammar also applies g/desugar-choices at the shared ingestion
;; boundary, so the emitters render the same lowered `:always` candidate
;; vector the engine drives. Both lower a flat, nested-compound, and
;; region-nested `:type :choice` state into an ordinary state carrying its
;; `:choice` candidate vector under `:always`; the two must agree.

(def ^:private choice-machine-fixtures
  "Representative machine-defs spanning every choice-desugar arm."
  [;; flat choice state with guarded candidates
   {:initial :gate
    :states  {:gate {:type :choice :choice [{:guard :g1 :target :a} {:target :b}]}
              :a {} :b {}}}
   ;; nested-compound + region-nested choice states together
   {:initial :outer
    :states  {:outer {:initial :gate
                      :states  {:gate {:type :choice :choice [{:target :a}]} :a {}}}}
    :regions {:r1 {:initial :rgate
                   :states  {:rgate {:type :choice :choice [{:target :a}]} :a {}}}}}
   ;; choice-free control (engine returns unchanged; viz rebuilds value-equal)
   {:initial :a :states {:a {:always [{:target :b}]} :b {}}}])

(deftest desugar-choices-parity
  (testing "viz grammar/desugar-choices agrees with the engine
            rf.machines.choice/desugar-choices across every machine shape"
    (doseq [m choice-machine-fixtures]
      (is (= (rf.machines.choice/desugar-choices m)
             (g/desugar-choices m))
          (str ":choice desugar drifted for " (pr-str m))))))

;; ---------------------------------------------------------------------------
;; PARITY 8 — definition VALIDATION
;;
;; PARITY: `grammar/valid-definition?` (via `definition-defect`) must give the
;; SAME accept/reject answer as the runtime
;; `re-frame.machines.lifecycle-fx.validation/validate-machine!` for the
;; PROJECTABLE structural grammar. If this fails, the viz recursive validator
;; drifted from the engine — re-sync, do not delete.
;;
;; A shallow `valid-definition?` would bless every deeply-invalid
;; definition (nested compound missing `:initial`, dangling target, unknown
;; bare node key, …). The recursive walker mirrors the engine so a definition
;; the runtime rejects at `reg-machine` is rejected at EVERY viz ingestion /
;; export boundary too.
;;
;; The corpus below is CURATED to the SHARED structural grammar — it excludes
;; the documented viz-vs-engine divergences (guard/action ref resolution,
;; non-parallel root `:after`, viz-stricter root shape), which are pinned
;; SEPARATELY in `definition-validation-documented-divergences` so a change
;; that accidentally aligns / diverges them is also caught.

(defn- engine-answer
  "The runtime `validate-machine!`'s answer for `m`, on a THREE-valued scale:

    :accept      — no error;
    :reject      — the documented structured rejection (an `ex-info` carrying
                   an `:rf.error/id`);
    :host-throw  — anything else. A cast that failed, a protocol miss, a
                   `toString` that refused. Neither answer, and not a thing
                   either side is permitted to do.

  A two-valued probe cannot express that third outcome, and on CLJS actively
  HIDES it: a `(catch :default … false)` records a host crash as a clean
  `:reject`, so a definition that made the engine explode would look exactly
  like one it had validated and rejected."
  [m]
  (try (rf.machines.lifecycle-fx.validation/validate-machine! m) :accept
       (catch #?(:clj Throwable :cljs :default) t
         (if (:rf.error/id (ex-data t)) :reject :host-throw))))

(defn- viz-answer
  "`grammar/definition-defect`'s answer for `m`, on the same three-valued
  scale. The viz reports a defect by RETURNING one rather than throwing, so any
  throw at all is a `:host-throw`."
  [m]
  (try (if (nil? (g/definition-defect m)) :accept :reject)
       (catch #?(:clj Throwable :cljs :default) _t :host-throw)))

(def ^:private validation-parity-corpus
  "Representative definitions spanning the SHARED structural grammar — both the
  viz validator and the engine must agree on each. Half are projectable (both
  ACCEPT), half are structurally malformed (both REJECT)."
  {;; ---- valid (both accept) ----
   :valid-flat       {:initial :idle :states {:idle {:on {:go :done}} :done {:final? true}}}
   :valid-compound   {:initial :o :states {:o {:initial :i :states {:i {:on {:up :sib}} :sib {}}} :top {}}}
   :valid-vec-target {:initial :o :states {:o {:initial :i :states {:i {:on {:esc [:top]}}}} :top {}}}
   :valid-parallel   {:type :parallel :regions {:r1 {:initial :a :states {:a {:on {:x :b}} :b {}}}
                                                :r2 {:initial :p :states {:p {}}}}}
   :valid-history    {:initial :o :states {:o {:initial :s :states {:s {:on {:g :s2}} :s2 {} :h {:type :history :deep? true}}}}}
   :valid-timeout    {:initial :a :states {:a {:timeout 1000 :on-timeout :b} :b {}}}
   :valid-choice     {:initial :g :states {:g {:type :choice :choice [{:target :a} {:target :b}]} :a {} :b {}}}
   :valid-spawn      {:initial :a :states {:a {:spawn {:machine-id :child} :on {:go :b}} :b {}}}
   ;; An inline :definition is addressed by :id-prefix or
   ;; :fixed-actor-id (the controls for `:spawn-inline-unaddressed` below).
   :valid-spawn-inline-prefix {:initial :a :states {:a {:spawn {:definition {:initial :x :states {:x {}}}
                                                                 :id-prefix  :kid}}}}
   :valid-spawn-inline-fixed  {:initial :a :states {:a {:spawn {:definition     {:initial :x :states {:x {}}}
                                                                 :fixed-actor-id :kid-1}}}}
   :valid-spawn-all  {:initial :a :states {:a {:spawn-all {:children [{:id :c1 :machine-id :m}]
                                                           :on-all-complete [:done]}
                                               :on {:go :b}} :b {}}}
   ;; A single spawn may carry a namespaced extension key and the source
   ;; metadata the `reg-machine` macro stamps.
   :valid-spawn-namespaced-source-meta {:initial :a :states {:a {:spawn {:machine-id    :m
                                                                          :my.app/note   "x"
                                                                          :source-coords {:line 1 :column 1}
                                                                          :source-code   "(reg-machine …)"}}}}
   :valid-namespaced {:initial :a :states {:a {:my.app/note "x"}}}
   :valid-tags       {:initial :a :states {:a {:tags #{:busy}}}}
   ;; A transition map may carry the closed transition keys, a namespaced
   ;; extension, and the source metadata the `reg-machine` macro stamps.
   :valid-transition-keys        {:initial :a :states {:a {:on {:go {:target :b :reenter? true :meta {:note "x"}}}} :b {}}}
   :valid-transition-namespaced  {:initial :a :states {:a {:on {:go {:target :b :my.app/note "x"}}} :b {}}}
   :valid-transition-source-meta {:initial :a :states {:a {:on {:go {:target :b :source-coords {:line 1 :column 1}
                                                                     :source-code "(reg-machine …)"}}}
                                                       :b {}}}
   ;; The machine's own blocks are legal on the root, flat or parallel.
   :valid-root-only-keys          {:initial :a :data {:n 0} :guards {} :actions {} :internal-events #{:tick}
                                   :states {:a {:on {:tick :a}}}}
   :valid-parallel-root-only-keys {:type :parallel :region-order [:r] :data {:n 0}
                                   :regions {:r {:initial :a :states {:a {}}}}}
   ;; The root runs its `:entry` at birth and its `:exit` at teardown, and
   ;; joins its `:tags` to the tag union; a parallel root also schedules its
   ;; own `:after`.
   :valid-root-entry-exit        {:initial :a :entry :hello :exit :bye
                                  :actions {:hello (fn [_ctx] nil) :bye (fn [_ctx] nil)}
                                  :states  {:a {}}}
   :valid-root-tags              {:initial :a :tags #{:busy} :states {:a {}}}
   :valid-parallel-root-lifecycle {:type    :parallel :entry (fn [_ctx] nil) :exit (fn [_ctx] nil)
                                   :tags    #{:busy}
                                   :regions {:r {:initial :a :states {:a {}}}}}
   :valid-parallel-root-after    {:type    :parallel :after {1000 {:target [:r :b]}}
                                  :regions {:r {:initial :a :states {:a {} :b {}}}}}
   ;; An `:after` delay key may be an ISO-8601 duration, as a `:timeout` may.
   :valid-after-iso      {:initial :a :states {:a {:after {"PT1S" :b}} :b {}}}
   :valid-after-iso-frac {:initial :a :states {:a {:after {"PT0.5S" :b}} :b {}}}
   ;; A spawn deadline is the spawn-level `:timeout` / `:on-timeout`.
   :valid-spawn-timeout  {:initial :a :states {:a {:spawn {:machine-id :m :timeout 500 :on-timeout :b}} :b {}}}
   :valid-timeout-iso    {:initial :a :states {:a {:timeout "PT2S" :on-timeout :b} :b {}}}
   ;; `:on-done` completes a compound, a parallel region or the parallel root.
   :valid-compound-on-done      {:initial :o :states {:o {:initial :i :on-done :b
                                                          :states  {:i {:on {:f :fin}} :fin {:final? true}}}
                                                      :b {}}}
   :valid-region-on-done        {:type :parallel :regions {:r {:initial :a :on-done :a :states {:a {}}}}}
   ;; A region's `:on-done` resolves within its region, so a keyword naming
   ;; the region's own state is valid even beside a sibling region of the
   ;; same name.
   :valid-region-on-done-vector {:type :parallel :regions {:r {:initial :a :on-done [:p :q]
                                                                :states  {:a {} :p {:initial :q :states {:q {}}}}}}}
   :valid-region-on-done-shadowing {:type    :parallel
                                    :regions {:a {:initial :a1 :on-done :b :states {:a1 {} :b {}}}
                                              :b {:initial :b1 :states {:b1 {}}}}}
   ;; A region body runs its `:entry` / `:exit` and joins its `:tags`.
   :valid-region-lifecycle      {:type    :parallel
                                 :regions {:r {:initial :a :entry (fn [_ctx] nil) :exit (fn [_ctx] nil)
                                               :tags #{:busy} :states {:a {}}}}}
   ;; A region body's `:after` that declares no delay schedules nothing.
   :valid-region-empty-after    {:type :parallel :regions {:r {:initial :a :after {} :states {:a {}}}}}
   ;; A choice state inside a region is an ordinary choice state.
   :valid-region-nested-choice  {:type    :parallel
                                 :regions {:r {:initial :g :states {:g {:type :choice :choice [{:target :a}]}
                                                                    :a {}}}}}
   :valid-parallel-root-on-done {:type    :parallel :actions {:announce (fn [_ctx] nil)} :on-done {:action :announce}
                                 :regions {:r {:initial :a :states {:a {:final? true}}}}}
   ;; A single spawn's `:on-done` is a fn folding `:data`, or a transition.
   :valid-spawn-on-done-fn      {:initial :a :states {:a {:spawn {:machine-id :m :on-done (fn [{:keys [data]}] data)}} :b {}}}
   :valid-spawn-on-done-keyword {:initial :a :states {:a {:spawn {:machine-id :m :on-done :b}} :b {}}}
   :valid-spawn-on-done-path    {:initial :a :states {:a {:spawn {:machine-id :m :on-done [:b]}} :b {}}}
   :valid-spawn-on-done-map     {:initial :a :states {:a {:spawn {:machine-id :m :on-done {:target :b :reenter? true}}} :b {}}}
   :valid-spawn-on-done-cands   {:initial :a :states {:a {:spawn {:machine-id :m :on-done [{:target :b} {:target :c}]}} :b {} :c {}}}
   ;; A single spawn's `:on-error` is a transition.
   :valid-spawn-on-error-keyword {:initial :a :states {:a {:spawn {:machine-id :m :on-error :b}} :b {}}}
   :valid-spawn-on-error-map     {:initial :a :states {:a {:spawn {:machine-id :m :on-error {:target :b}}} :b {}}}
   ;; ---- invalid (both reject) ----
   :nested-no-init   {:initial :outer :states {:outer {:states {:inner {}}}}}
   :unresolved-kw    {:initial :idle :states {:idle {:on {:go :missing}}}}
   :unresolved-vec   {:initial :a :states {:a {:on {:go [:nope]}}}}
   :bad-target-map   {:initial :a :states {:a {:on {:go {:target 42}}}}}
   :empty-vec-target {:initial :a :states {:a {:on {:go []}}}}
   :unknown-node-key {:initial :idle :states {:idle {:on-entry :oops}}}
   :hist-misplaced   {:initial :a :states {:a {} :hist {:type :history}}}
   :hist-extra-keys  {:initial :o :states {:o {:initial :s :states {:s {} :h {:type :history :on {:x :s}}}}}}
   :hist-duplicate   {:initial :o :states {:o {:initial :s :states {:s {} :h1 {:type :history} :h2 {:type :history}}}}}
   :hist-bad-default {:initial :o :states {:o {:initial :s :states {:s {} :h {:type :history :default-target :nope}}}}}
   :final-compound   {:initial :a :states {:a {:final? true :states {:b {}}}}}
   :final-trans      {:initial :a :states {:a {:final? true :on {:x :b}} :b {}}}
   :output-no-final  {:initial :a :states {:a {:output-key :foo}}}
   :error-no-final   {:initial :a :states {:a {:error? true}}}
   :bad-tags         {:initial :a :states {:a {:tags [:x]}}}
   :bad-after-delay  {:initial :a :states {:a {:after {0 :b}} :b {}}}
   ;; The XState shorthand and a zero-length ISO duration are not delays.
   :after-shorthand  {:initial :a :states {:a {:after {"5s" :b}} :b {}}}
   :after-iso-zero   {:initial :a :states {:a {:after {"PT0S" :b}} :b {}}}
   :spawn-neither    {:initial :a :states {:a {:spawn {}}}}
   :spawn-both       {:initial :a :states {:a {:spawn {:machine-id :m :definition {:initial :x :states {:x {}}}}}}}
   :spawn-unknown    {:initial :a :states {:a {:spawn {:machine-id :m :bogus 1}}}}
   ;; The engine refuses an UNADDRESSED inline :definition (neither
   ;; :id-prefix nor :fixed-actor-id); the viz must too.
   :spawn-inline-unaddressed {:initial :a :states {:a {:spawn {:definition {:initial :x :states {:x {}}}}}}}
   ;; A state spawns ONE child: a vector of specs, or any other non-map, is
   ;; refused. N children is `:spawn-all`.
   :spawn-vector     {:initial :a :states {:a {:spawn [{:machine-id :child}]}}}
   :spawn-keyword    {:initial :a :states {:a {:spawn :child}}}
   ;; A bare `:id` addresses a `:spawn-all` child, never a single spawn.
   :spawn-bare-id    {:initial :a :states {:a {:spawn {:machine-id :child :id :x}}}}
   :par-nested       {:type :parallel :regions {:r {:initial :a :states {:a {:type :parallel :regions {:x {:initial :y :states {:y {}}}}}}}}}
   :par-no-init      {:type :parallel :regions {:r {:states {:a {}}}}}
   :par-mutex        {:type :parallel :initial :a :regions {:r {:initial :a :states {:a {}}}}}
   :par-empty        {:type :parallel :regions {}}
   ;; ---- unknown BARE transition-map keys, in every slot and scope ----
   :transition-unknown-key         {:initial :a :states {:a {:on {:go {:target :b :targt :c}}} :b {} :c {}}}
   :transition-xstate-cond         {:initial :a :states {:a {:on {:go {:target :b :cond :ok?}}} :b {}}}
   :transition-unknown-always      {:initial :a :states {:a {:always [{:target :b :bogus 1}]} :b {}}}
   :transition-unknown-on-timeout  {:initial :a :states {:a {:timeout 1000 :on-timeout {:target :b :bogus 1}} :b {}}}
   :transition-unknown-root-on     {:initial :a :on {:x {:target :a :bogus 1}} :states {:a {}}}
   :transition-unknown-region-root {:type :parallel :regions {:r {:initial :a :on {:x {:target :a :bogus 1}} :states {:a {}}}}}
   ;; ---- root-only keys below the root ----
   :nested-root-only-data         {:initial :a :states {:a {:data {:n 0}}}}
   :nested-root-only-guards       {:initial :o :states {:o {:initial :i :states {:i {:guards {}}}}}}
   :nested-root-only-region-order {:initial :a :states {:a {:region-order [:x]}}}
   :region-root-only-data         {:type :parallel :regions {:r {:initial :a :data {:n 0} :states {:a {}}}}}
   ;; ---- `:timeout-ms` is not a spawn key ----
   :spawn-timeout-ms     {:initial :a :states {:a {:spawn {:machine-id :m :timeout-ms 500}}}}
   :spawn-all-timeout-ms {:initial :a :states {:a {:spawn-all {:children [{:id :c1 :machine-id :m}]
                                                               :on-all-complete [:done]
                                                               :timeout-ms 500}}}}
   ;; ---- a `:timeout` the desugar cannot lower without dropping it ----
   :timeout-shorthand          {:initial :a :states {:a {:timeout "5s" :on-timeout :b} :b {}}}
   :spawn-timeout-shorthand    {:initial :a :states {:a {:spawn {:machine-id :m :timeout "5s" :on-timeout :b}} :b {}}}
   :region-timeout-shorthand   {:type :parallel :regions {:r {:initial :a :timeout "5s" :on-timeout :a :states {:a {}}}}}
   :timeout-without-on-timeout {:initial :a :states {:a {:timeout 1000} :b {}}}
   :on-timeout-without-timeout {:initial :a :states {:a {:on-timeout :b} :b {}}}
   :timeout-after-collision    {:initial :a :states {:a {:after {2000 :b} :timeout "PT2S" :on-timeout :b} :b {}}}
   ;; ---- `:on-done` on a LEAF, which has no children to complete ----
   :leaf-on-done          {:initial :a :states {:a {:on-done :b} :b {}}}
   :leaf-on-done-final    {:initial :a :states {:a {:final? true :on-done :b} :b {}}}
   :leaf-on-done-spawning {:initial :a :states {:a {:spawn {:machine-id :m} :on-done :b} :b {}}}
   :leaf-on-done-timeout  {:initial :a :states {:a {:timeout 1000 :on-timeout :b :on-done :b} :b {}}}
   :leaf-on-done-region   {:type :parallel :regions {:r {:initial :a :states {:a {:on-done :b} :b {}}}}}
   ;; ---- a choice state is a leaf, held to the state-node key vocabulary ----
   :choice-on-done     {:initial :g :states {:g {:type :choice :choice [{:target :a}] :on-done :a} :a {}}}
   :choice-unknown-key {:initial :g :states {:g {:type :choice :choice [{:target :a}] :bogus 1} :a {}}}
   ;; ---- a transition-shaped `:spawn :on-done` that does not resolve ----
   :spawn-on-done-unresolved     {:initial :a :states {:a {:spawn {:machine-id :m :on-done :nope}} :b {}}}
   :spawn-on-done-unresolved-map {:initial :a :states {:a {:spawn {:machine-id :m :on-done {:target [:nope]}}} :b {}}}
   :spawn-on-done-bad-target     {:initial :a :states {:a {:spawn {:machine-id :m :on-done {:target 42}}} :b {}}}
   :spawn-on-done-unknown-key    {:initial :a :states {:a {:spawn {:machine-id :m :on-done {:target :b :cond :ok?}}} :b {}}}
   ;; ---- a `:spawn :on-error` / `:on-done` value that is neither a transition
   ;; nor, for `:on-done`, a fn ----
   :spawn-on-error-nil          {:initial :a :states {:a {:spawn {:machine-id :m :on-error nil}} :b {}}}
   :spawn-on-error-number       {:initial :a :states {:a {:spawn {:machine-id :m :on-error 42}} :b {}}}
   :spawn-on-error-string       {:initial :a :states {:a {:spawn {:machine-id :m :on-error "b"}} :b {}}}
   :spawn-on-error-fn           {:initial :a :states {:a {:spawn {:machine-id :m :on-error (fn [_ctx] nil)}} :b {}}}
   :spawn-on-error-empty-vector {:initial :a :states {:a {:spawn {:machine-id :m :on-error []}} :b {}}}
   :spawn-on-done-nil           {:initial :a :states {:a {:spawn {:machine-id :m :on-done nil}} :b {}}}
   :spawn-on-done-number        {:initial :a :states {:a {:spawn {:machine-id :m :on-done 42}} :b {}}}
   :spawn-on-done-string        {:initial :a :states {:a {:spawn {:machine-id :m :on-done "b"}} :b {}}}
   :spawn-on-done-empty-vector  {:initial :a :states {:a {:spawn {:machine-id :m :on-done []}} :b {}}}
   ;; ---- a machine-root key the runtime never reads on the root ----
   :root-final          {:initial :a :final? true :states {:a {}}}
   :root-flat-on-done   {:initial :a :on-done :a :states {:a {}}}
   :root-two-slots      {:initial :a :always {:target :a} :final? true :states {:a {}}}
   ;; ---- the machine root's own `:spawn`, held to a state's spawn grammar ----
   :root-spawn                    {:initial :a :spawn {:machine-id :m} :states {:a {}}}
   :root-spawn-completions        {:initial :a :spawn {:machine-id :m :on-done :b :on-error [:b]} :states {:a {} :b {}}}
   :parallel-root-spawn           {:type :parallel :spawn {:machine-id :m} :regions {:r {:initial :a :states {:a {}}}}}
   :parallel-root-spawn-on-error  {:type :parallel :spawn {:machine-id :m :on-error {:target [:r :b]}}
                                   :regions {:r {:initial :a :states {:a {} :b {}}}}}
   :parallel-root-spawn-timeout   {:type :parallel :spawn {:machine-id :m :timeout 1000 :on-timeout {:target [:r :b]}}
                                   :regions {:r {:initial :a :states {:a {} :b {}}}}}
   :root-spawn-vector             {:initial :a :spawn [{:machine-id :m}] :states {:a {}}}
   :parallel-root-spawn-bare-id   {:type :parallel :spawn {:machine-id :m :id :x} :regions {:r {:initial :a :states {:a {}}}}}
   :root-spawn-on-error-number    {:initial :a :spawn {:machine-id :m :on-error 42} :states {:a {}}}
   :parallel-root-spawn-on-done-number {:type :parallel :spawn {:machine-id :m :on-done 42}
                                        :regions {:r {:initial :a :states {:a {}}}}}
   :root-spawn-on-done-unresolved {:initial :a :spawn {:machine-id :m :on-done :nope} :states {:a {}}}
   :root-spawn-timeout-ms         {:initial :a :spawn {:machine-id :m :timeout-ms 1000} :states {:a {}}}
   ;; A parallel root's `:tags` is a set of keywords, as a flat root's is.
   :parallel-root-bad-tags {:type :parallel :tags [:busy] :regions {:r {:initial :a :states {:a {}}}}}
   ;; ---- a region body's own `:on-done` / `:on` resolve within its region ----
   :region-on-done-sibling        {:type    :parallel
                                   :regions {:a {:initial :x :on-done :b :states {:x {} :done {:final? true}}}
                                             :b {:initial :y :states {:y {}}}}}
   :region-on-done-sibling-vector {:type    :parallel
                                   :regions {:a {:initial :x :on-done [:b :y] :states {:x {} :done {:final? true}}}
                                             :b {:initial :y :states {:y {}}}}}
   :region-on-done-missing        {:type :parallel :regions {:r {:initial :a :on-done :nowhere
                                                                  :states  {:a {} :done {:final? true}}}}}
   :region-on-done-bad-target     {:type :parallel :regions {:r {:initial :a :on-done {:target 42}
                                                                  :states  {:a {} :done {:final? true}}}}}
   :region-root-on-sibling        {:type    :parallel
                                   :regions {:a {:initial :x :on {:go :b} :states {:x {}}}
                                             :b {:initial :y :states {:y {}}}}}
   ;; ---- a region-body key the runtime never reads on a region body ----
   :region-body-final     {:type :parallel :regions {:r {:initial :a :final? true :states {:a {}}}}}
   :region-body-spawn     {:type :parallel :regions {:r {:initial :a :spawn {:machine-id :m} :states {:a {}}}}}
   :region-body-two-slots {:type :parallel :regions {:r {:initial :a :final? true :spawn {:machine-id :m}
                                                         :states  {:a {}}}}}
   ;; ---- a region body's own `:after`, and a region body as a choice state ----
   :region-body-after              {:type :parallel :regions {:r {:initial :a :after {1000 :a} :states {:a {}}}}}
   :region-body-timeout            {:type :parallel :regions {:r {:initial :a :timeout 1000 :on-timeout :a
                                                                  :states  {:a {}}}}}
   :region-body-after-and-final    {:type :parallel :regions {:r {:initial :a :after {1000 :a} :final? true
                                                                  :states  {:a {}}}}}
   :region-body-choice-without-type {:type :parallel :regions {:r {:initial :a :choice [{:target :a}]
                                                                   :states  {:a {}}}}}
   :region-body-choice-missing     {:type :parallel :regions {:r {:initial :a :type :choice :states {:a {}}}}}
   :region-body-choice             {:type :parallel :regions {:r {:initial :a :type :choice :choice [{:target :a}]
                                                                  :states  {:a {}}}}}
   :region-body-choice-malformed   {:type :parallel :regions {:r {:initial :a :type :choice :choice :a
                                                                  :states  {:a {}}}}}
   :region-body-choice-no-default  {:type :parallel :regions {:r {:type :choice :choice [{:guard :g :target :a}]}}}
   :region-body-choice-self-loop   {:type :parallel :regions {:r {:type :choice :choice [{:target :r}]}}}
   ;; ---- non-Named KEYS ----
   ;;
   ;; Every entry above spells its keys as keywords, so without these rows the
   ;; corpus could not see either side's treatment of a key that is not
   ;; `Named` — where a bare `(namespace k)` on both sides would THROW rather
   ;; than reject, and a ratchet built to stop the two drifting could not fail
   ;; on the one axis they were both wrong about. A
   ;; definition merged from config, decoded from transit, or read off a share
   ;; URL carries a string key as readily as a hand-written map carries a
   ;; keyword, so this is corpus, not exotica. Both sides must REJECT.
   :root-string-key  {:initial :a :states {:a {}} "x" 1}
   :root-number-key  {:initial :a :states {:a {}} 7 1}
   :node-string-key  {:initial :a :states {:a {"x" 1}}}
   :node-vector-key  {:initial :a :states {:a {[1 2] 1}}}
   :nested-string-key {:initial :o :states {:o {:initial :i :states {:i {"x" 1}}}}}})

(deftest definition-validation-parity
  (testing "the viz recursive validator accepts/rejects EXACTLY what the engine
            validate-machine! does across the shared structural grammar"
    (doseq [[label m] validation-parity-corpus]
      (is (= (engine-answer m) (viz-answer m))
          (str label ": viz validator drifted from the engine "
               "(engine " (engine-answer m) ", viz " (viz-answer m) ")")))))

;; Agreement alone is not the contract — two validators that BOTH explode on
;; the same input agree, and `definition-validation-parity` passes them: a bare
;; `(namespace k)` on a non-`Named` key would throw on both sides at once and
;; hide the divergence exactly that way. Neither side may answer `:host-throw`
;; for anything in the corpus.

(deftest definition-validation-is-total
  (testing "neither validator answers a corpus definition with a host throw"
    (doseq [[label m] validation-parity-corpus]
      (is (not= :host-throw (engine-answer m))
          (str label ": the ENGINE threw a host exception where a structured "
               ":rf.error/machine-* rejection was the contract"))
      (is (not= :host-throw (viz-answer m))
          (str label ": the VIZ threw a host exception where a returned defect "
               "was the contract")))))

;; Every ingestion boundary — the share decoder, Mermaid, SCXML, AI generation
;; and the chart projector — runs `desugar-grammar` BEFORE it validates, so the
;; answer those boundaries give is the answer on the LOWERED definition. A
;; desugar that dropped what it could not lower would turn a refusal into an
;; acceptance there, while `definition-validation-parity` above, which validates
;; the raw definition, stayed green.

(deftest definition-validation-survives-the-boundary-desugar
  (testing "the viz gives the engine's answer on the desugared definition every
            boundary validates"
    (doseq [[label m] validation-parity-corpus]
      (is (= (engine-answer m) (viz-answer (g/desugar-grammar m)))
          (str label ": the desugar changed the viz answer (engine "
               (engine-answer m) ", viz after desugar "
               (viz-answer (g/desugar-grammar m)) ")")))))

;; The rows above compare accept / reject. A spawn refusal also carries the
;; engine's own CATEGORY, which every export surface reports in its value-free
;; summary, so these rows pin the category against the engine's: on the raw
;; definition, and on the desugared one every boundary validates.

(defn- engine-category
  "The `:rf.error/id` `validate-machine!` refuses `m` with, or nil."
  [m]
  (try (rf.machines.lifecycle-fx.validation/validate-machine! m) nil
       (catch #?(:clj Throwable :cljs :default) t (:rf.error/id (ex-data t)))))

(def ^:private spawn-refusal-rows
  "Corpus labels → the category the engine refuses each with."
  {:spawn-vector  :rf.error/machine-spawn-bad-shape
   :spawn-keyword :rf.error/machine-spawn-bad-shape
   :spawn-bare-id :rf.error/machine-unknown-spawn-key})

(deftest spawn-refusal-category-parity
  (testing "the viz refuses a non-map :spawn and a single-spawn :id with the
            engine's own category"
    (doseq [[label category] spawn-refusal-rows
            :let [m (get validation-parity-corpus label)]]
      (is (= category (engine-category m))
          (str label ": the engine's category"))
      (is (= category (:category (g/definition-defect m)))
          (str label ": the viz category"))
      (is (= category (:category (g/definition-defect (g/desugar-grammar m))))
          (str label ": the viz category after the boundary desugar")))))

(def ^:private on-done-refusal-rows
  "Corpus labels → the category the engine refuses each `:on-done` with."
  {:leaf-on-done                  :rf.error/machine-unknown-node-key
   :leaf-on-done-final            :rf.error/machine-unknown-node-key
   :leaf-on-done-spawning         :rf.error/machine-unknown-node-key
   :leaf-on-done-timeout          :rf.error/machine-unknown-node-key
   :leaf-on-done-region           :rf.error/machine-unknown-node-key
   :spawn-on-done-unresolved      :rf.error/machine-unresolved-target
   :spawn-on-done-unresolved-map  :rf.error/machine-unresolved-target
   :spawn-on-done-bad-target      :rf.error/machine-bad-target
   :spawn-on-done-unknown-key     :rf.error/machine-unknown-node-key})

(deftest on-done-refusal-category-parity
  (testing "the viz refuses a leaf's :on-done, and a transition-shaped
            :spawn :on-done that does not resolve, with the engine's own
            category"
    (doseq [[label category] on-done-refusal-rows
            :let [m (get validation-parity-corpus label)]]
      (is (= category (engine-category m))
          (str label ": the engine's category"))
      (is (= category (:category (g/definition-defect m)))
          (str label ": the viz category"))
      (is (= category (:category (g/definition-defect (g/desugar-grammar m))))
          (str label ": the viz category after the boundary desugar")))))

(def ^:private choice-refusal-rows
  "Corpus labels → the category the engine refuses each choice-state key with."
  {:choice-on-done     :rf.error/machine-unknown-node-key
   :choice-unknown-key :rf.error/machine-unknown-node-key})

(deftest choice-refusal-category-parity
  (testing "the viz refuses a choice state's :on-done and an unknown bare key
            on a choice state with the engine's own category"
    (doseq [[label category] choice-refusal-rows
            :let [m (get validation-parity-corpus label)]]
      (is (= category (engine-category m))
          (str label ": the engine's category"))
      (is (= category (:category (g/definition-defect m)))
          (str label ": the viz category"))
      (is (= category (:category (g/definition-defect (g/desugar-grammar m))))
          (str label ": the viz category after the boundary desugar")))))

(def ^:private spawn-completion-refusal-rows
  "Corpus labels → the category the engine refuses each `:spawn :on-error` /
  `:spawn :on-done` value with."
  {:spawn-on-error-nil          :rf.error/machine-bad-on-error-clause
   :spawn-on-error-number       :rf.error/machine-bad-on-error-clause
   :spawn-on-error-string       :rf.error/machine-bad-on-error-clause
   :spawn-on-error-fn           :rf.error/machine-bad-on-error-clause
   :spawn-on-error-empty-vector :rf.error/machine-bad-on-error-clause
   :spawn-on-done-nil           :rf.error/machine-bad-on-done-clause
   :spawn-on-done-number        :rf.error/machine-bad-on-done-clause
   :spawn-on-done-string        :rf.error/machine-bad-on-done-clause
   :spawn-on-done-empty-vector  :rf.error/machine-bad-on-done-clause})

(deftest spawn-completion-refusal-category-parity
  (testing "the viz refuses a :spawn :on-error / :on-done value that is not a
            transition (or, for :on-done, a fn) with the engine's own category"
    (doseq [[label category] spawn-completion-refusal-rows
            :let [m (get validation-parity-corpus label)]]
      (is (= category (engine-category m))
          (str label ": the engine's category"))
      (is (= category (:category (g/definition-defect m)))
          (str label ": the viz category"))
      (is (= category (:category (g/definition-defect (g/desugar-grammar m))))
          (str label ": the viz category after the boundary desugar")))))

(def ^:private root-refusal-rows
  "Corpus labels → the category the engine refuses each machine root with."
  {:root-final             :rf.error/machine-root-slot-not-supported
   :root-flat-on-done      :rf.error/machine-root-slot-not-supported
   :root-two-slots         :rf.error/machine-root-slot-not-supported
   :parallel-root-bad-tags :rf.error/machine-bad-tags})

(deftest root-refusal-category-parity
  (testing "the viz refuses a machine root with the engine's own category"
    (doseq [[label category] root-refusal-rows
            :let [m (get validation-parity-corpus label)]]
      (is (= category (engine-category m))
          (str label ": the engine's category"))
      (is (= category (:category (g/definition-defect m)))
          (str label ": the viz category"))
      (is (= category (:category (g/definition-defect (g/desugar-grammar m))))
          (str label ": the viz category after the boundary desugar")))))

;; A machine root's `:spawn` registers, and a malformed one is refused with the
;; category a state's `:spawn` would be.

(def ^:private root-spawn-accept-rows
  [:root-spawn :root-spawn-completions :parallel-root-spawn
   :parallel-root-spawn-on-error :parallel-root-spawn-timeout])

(def ^:private root-spawn-refusal-rows
  "Corpus labels → the category the engine refuses each root `:spawn` with."
  {:root-spawn-vector                  :rf.error/machine-spawn-bad-shape
   :parallel-root-spawn-bare-id        :rf.error/machine-unknown-spawn-key
   :root-spawn-on-error-number         :rf.error/machine-bad-on-error-clause
   :parallel-root-spawn-on-done-number :rf.error/machine-bad-on-done-clause
   :root-spawn-on-done-unresolved      :rf.error/machine-unresolved-target
   :root-spawn-timeout-ms              :rf.error/spawn-timeout-ms-removed})

(deftest root-spawn-parity
  (testing "a well-formed root :spawn registers on both sides, flat and parallel"
    (doseq [label root-spawn-accept-rows
            :let [m (get validation-parity-corpus label)]]
      (is (= :accept (engine-answer m)) (str label ": the engine accepts"))
      (is (= :accept (viz-answer m)) (str label ": the viz accepts"))
      (is (= :accept (viz-answer (g/desugar-grammar m)))
          (str label ": the viz accepts after the boundary desugar"))))
  (testing "a malformed root :spawn is refused with the engine's own category"
    (doseq [[label category] root-spawn-refusal-rows
            :let [m (get validation-parity-corpus label)]]
      (is (= category (engine-category m))
          (str label ": the engine's category"))
      (is (= category (:category (g/definition-defect m)))
          (str label ": the viz category"))
      (is (= category (:category (g/definition-defect (g/desugar-grammar m))))
          (str label ": the viz category after the boundary desugar")))))

;; The refused root keys are read off the engine rather than listed here, so a
;; key the engine starts refusing on the root is a red row until the viz
;; refuses it too.

(def ^:private engine-root-unread-keys      @#'rf.machines.lifecycle-fx.validation/root-unread-keys)
(def ^:private engine-flat-root-unread-keys @#'rf.machines.lifecycle-fx.validation/flat-root-unread-keys)

(defn- engine-offending-keys
  "The `:offending-keys` `validate-machine!` names when it refuses `m`, or nil."
  [m]
  (try (rf.machines.lifecycle-fx.validation/validate-machine! m) nil
       (catch #?(:clj Throwable :cljs :default) t (:offending-keys (ex-data t)))))

(defn- flat-root-with [k] {:initial :a k nil :states {:a {}}})
(defn- parallel-root-with [k] {:type :parallel k nil :regions {:r {:initial :a :states {:a {}}}}})

(deftest root-slot-refusal-parity
  (testing "every key the engine refuses on a root, the viz refuses on that
            root with the engine's category, naming the same keys"
    (doseq [[root-kind k m] (concat
                              (for [k (sort (into engine-root-unread-keys engine-flat-root-unread-keys))]
                                [:flat k (flat-root-with k)])
                              (for [k (sort engine-root-unread-keys)]
                                [:parallel k (parallel-root-with k)])
                              [[:flat :two-keys (get validation-parity-corpus :root-two-slots)]])]
      (is (= :rf.error/machine-root-slot-not-supported (engine-category m))
          (str root-kind " root " k ": the engine's category"))
      (is (= :rf.error/machine-root-slot-not-supported (:category (g/definition-defect m)))
          (str root-kind " root " k ": the viz category"))
      (is (= :rf.error/machine-root-slot-not-supported
             (:category (g/definition-defect (g/desugar-grammar m))))
          (str root-kind " root " k ": the viz category after the boundary desugar"))
      (is (= (engine-offending-keys m) (:keys (g/definition-defect m)))
          (str root-kind " root " k ": the viz names the engine's offending keys"))))

  (testing "a parallel root reads the keys only a flat root refuses"
    (doseq [k (sort engine-flat-root-unread-keys)
            :let [m (parallel-root-with k)]]
      (is (= :accept (engine-answer m)) (str "parallel root " k ": the engine accepts"))
      (is (= :accept (viz-answer m))    (str "parallel root " k ": the viz accepts")))))

;; A region body follows the machine root's rule: it refuses the keys the
;; runtime never reads there, read off the engine so a key it starts refusing
;; is a red row until the viz refuses it too. Its own `:on` and `:on-done`
;; resolve within the region.

(def ^:private engine-region-unread-keys @#'rf.machines.lifecycle-fx.validation/region-unread-keys)

(defn- engine-refusal
  "The ex-data `validate-machine!` refuses `m` with, or nil."
  [m]
  (try (rf.machines.lifecycle-fx.validation/validate-machine! m) nil
       (catch #?(:clj Throwable :cljs :default) t (ex-data t))))

(defn- region-body-with [k] {:type :parallel :regions {:r {:initial :a k nil :states {:a {}}}}})

(deftest region-slot-refusal-parity
  (testing "every key the engine refuses on a region body, the viz refuses there
            with the engine's category, naming the same keys under the same path"
    (doseq [[k m] (concat (for [k (sort engine-region-unread-keys)]
                            [k (region-body-with k)])
                          [[:two-keys (get validation-parity-corpus :region-body-two-slots)]])
            :let [refusal (engine-refusal m)
                  defect  (g/definition-defect m)]]
      (is (= :rf.error/machine-root-slot-not-supported (:rf.error/id refusal))
          (str "region body " k ": the engine's category"))
      (is (= :rf.error/machine-root-slot-not-supported (:category defect))
          (str "region body " k ": the viz category"))
      (is (= :rf.error/machine-root-slot-not-supported
             (:category (g/definition-defect (g/desugar-grammar m))))
          (str "region body " k ": the viz category after the boundary desugar"))
      (is (= (:offending-keys refusal) (:keys defect))
          (str "region body " k ": the viz names the engine's offending keys"))
      (is (= (:path refusal) (:path defect))
          (str "region body " k ": the viz names the engine's path")))))

(def ^:private region-refusal-rows
  "Corpus labels → the category the engine refuses each region body with."
  {:region-on-done-sibling        :rf.error/machine-unresolved-target
   :region-on-done-sibling-vector :rf.error/machine-unresolved-target
   :region-on-done-missing        :rf.error/machine-unresolved-target
   :region-on-done-bad-target     :rf.error/machine-bad-target
   :region-root-on-sibling        :rf.error/machine-unresolved-target
   :region-body-final             :rf.error/machine-root-slot-not-supported
   :region-body-spawn             :rf.error/machine-root-slot-not-supported})

(deftest region-refusal-category-parity
  (testing "an in-region :on-done and a region body's lifecycle register on
            both sides"
    (doseq [label [:valid-region-on-done :valid-region-on-done-vector
                   :valid-region-on-done-shadowing :valid-region-lifecycle]
            :let [m (get validation-parity-corpus label)]]
      (is (= :accept (engine-answer m)) (str label ": the engine accepts"))
      (is (= :accept (viz-answer m)) (str label ": the viz accepts"))))
  (testing "the viz refuses a region body with the engine's own category and slot"
    (doseq [[label category] region-refusal-rows
            :let [m (get validation-parity-corpus label)]]
      (is (= category (engine-category m))
          (str label ": the engine's category"))
      (is (= category (:category (g/definition-defect m)))
          (str label ": the viz category"))
      (is (= category (:category (g/definition-defect (g/desugar-grammar m))))
          (str label ": the viz category after the boundary desugar"))
      (is (= (:slot (engine-refusal m)) (:slot (g/definition-defect m)))
          (str label ": the viz names the engine's slot")))))

;; A region body's own `:after` never fires, and a region body is never a
;; choice state, so the engine refuses both. It names the region under
;; `:region` / `:state` rather than a `:path`; the viz names the same region at
;; the region-body path its unread-key refusal carries.

(def ^:private region-body-after-choice-rows
  "Corpus labels → the category the engine refuses each region body with."
  {:region-body-after               :rf.error/machine-non-parallel-root-after-not-supported
   :region-body-timeout             :rf.error/machine-non-parallel-root-after-not-supported
   :region-body-after-and-final     :rf.error/machine-non-parallel-root-after-not-supported
   :region-body-choice-without-type :rf.error/machine-choice-without-type
   :region-body-choice-missing      :rf.error/machine-choice-missing-choice
   :region-body-choice              :rf.error/machine-choice-extra-keys
   :region-body-choice-malformed    :rf.error/machine-bad-choice
   :region-body-choice-no-default   :rf.error/machine-choice-no-default
   :region-body-choice-self-loop    :rf.error/machine-choice-self-loop})

(deftest region-body-after-and-choice-refusal-parity
  (testing "an ordinary region, a region body's empty :after, the parallel root's
            own :after and a choice state inside a region register on both sides"
    (doseq [label [:valid-parallel :valid-region-empty-after
                   :valid-parallel-root-after :valid-region-nested-choice]
            :let [m (get validation-parity-corpus label)]]
      (is (= :accept (engine-answer m)) (str label ": the engine accepts"))
      (is (= :accept (viz-answer m)) (str label ": the viz accepts"))))
  (testing "the viz refuses a region body's :after and :choice with the engine's
            own category, naming the region the engine names"
    (doseq [[label category] region-body-after-choice-rows
            :let [m       (get validation-parity-corpus label)
                  refusal (engine-refusal m)]]
      (is (= category (:rf.error/id refusal))
          (str label ": the engine's category"))
      (is (= category (:category (g/definition-defect m)))
          (str label ": the viz category"))
      (is (= category (:category (g/definition-defect (g/desugar-grammar m))))
          (str label ": the viz category after the boundary desugar"))
      (is (= [:regions (or (:region refusal) (:state refusal))] (:path (g/definition-defect m)))
          (str label ": the viz names the engine's region")))))

(deftest definition-validation-documented-divergences
  (testing "guard / action keyword REF resolution is a DIVERGENCE — the engine
            rejects a dangling guard ref (runtime wiring); the viz accepts it
            (projectable topology, no registry to resolve against)"
    (let [m {:initial :a :states {:a {:on {:go {:target :b :guard :missing?}}} :b {}}}]
      (is (= :reject (engine-answer m)) "engine rejects the dangling guard ref")
      (is (= :accept (viz-answer m))    "the viz projects it — refs are runtime wiring, not topology")))

  (testing "a NON-parallel root :after is a DIVERGENCE — the engine rejects it
            (unschedulable at registration); the viz projects it as a
            machine-root anchor"
    (let [m {:initial :a :after {1000 :b} :states {:a {} :b {}}}]
      (is (= :reject (engine-answer m)) "engine rejects a flat-root :after")
      (is (= :accept (viz-answer m))    "the viz projects it as a root anchor")))

  (testing "a parallel root :spawn's region-qualified target grammar is a
            DIVERGENCE — the engine rejects a bare-keyword target; the viz
            does not validate the parallel-root target grammar"
    (let [m {:type :parallel :spawn {:machine-id :m :on-error :b}
             :regions {:r {:initial :a :states {:a {} :b {}}}}}]
      (is (= :reject (engine-answer m)) "engine rejects the unqualified target")
      (is (= :accept (viz-answer m))    "the viz accepts it")))

  (testing "viz-STRICTER root shape is a DIVERGENCE — the engine resolves a
            missing / late root :initial lazily at runtime; the viz REQUIRES a
            keyword root :initial + non-empty :states to have an initial-marker
            to project"
    (let [m {:states {:idle {}}}]
      (is (= :accept (engine-answer m)) "engine accepts a flat machine with no root :initial")
      (is (= :reject (viz-answer m))    "the viz rejects it (no initial to project)"))))
