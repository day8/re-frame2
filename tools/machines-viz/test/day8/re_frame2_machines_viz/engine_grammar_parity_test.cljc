(ns day8.re-frame2-machines-viz.engine-grammar-parity-test
  "ENGINE-GRAMMAR PARITY tests (rf2-ir5nah, Mike-ruled option (b)).

  machines-viz HAND-MIRRORS the runtime engine's machine-definition
  grammar walk + target resolution: `grammar/normalise-root-targets`,
  `grammar/reenter?`, `grammar/transition-candidates`, and the chart's
  `resolve-target-path` each re-implement, in plain JVM-portable data,
  what `re-frame.machines.parallel` / `re-frame.machines.transition` /
  `re-frame.machines.grammar` do at runtime. Mike ruled the mirror is
  BY-DESIGN — the viz tool is bundle-isolated from production
  (`check-bundle-isolation` pins that nothing under implementation/ may
  `:require` this jar, and this jar's SRC never `:require`s the engine)
  and `grammar.cljc` is deliberately dep-free (`clojure.string` only), so
  a shared `:require` would punch a hole through the very isolation
  boundary the sentinel exists to protect. A shared grammar-codec ns
  (option (a)) was REJECTED.

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

  The engine targets `parallel/normalise-root-targets`,
  `transition/normalise-candidates`, and `transition/target-path` are
  PRIVATE (`defn-`); the tests reach them through their vars
  (`#'ns/fn`), which is the standard, drift-honest way to pin a private
  contract from outside its namespace."
  (:require #?(:clj  [clojure.test :refer [deftest is testing]]
               :cljs [cljs.test    :refer-macros [deftest is testing]])
            [day8.re-frame2-machines-viz.chart.layout :as layout]
            [day8.re-frame2-machines-viz.grammar :as g]
            [re-frame.machines.choice :as choice]
            [re-frame.machines.lifecycle-fx.validation :as validation]
            [re-frame.machines.parallel :as parallel]
            [re-frame.machines.timeout :as timeout]
            [re-frame.machines.transition :as transition]))

;; ---------------------------------------------------------------------------
;; Private-var accessors for the engine fns the viz copies mirror.
;;
;; `parallel/normalise-root-targets`, `transition/normalise-candidates`,
;; and `transition/target-path` are `defn-` (engine-internal). Pinning a
;; private contract from outside its ns via its var is intentional here —
;; the whole point is to assert the public viz copy agrees with the
;; engine's internal resolver.

(def engine-normalise-root-targets @#'parallel/normalise-root-targets)
(def engine-normalise-candidates   @#'transition/normalise-candidates)
(def engine-target-path            @#'transition/target-path)

;; ---------------------------------------------------------------------------
;; PARITY 1 — normalise-root-targets
;;
;; PARITY: mirror of re-frame.machines.parallel/normalise-root-targets —
;; if this fails, the viz grammar drifted from the engine; re-sync, do
;; not delete.
;;
;; grammar/normalise-root-targets (grammar.cljc) is byte-identical-logic
;; to the engine's parallel/normalise-root-targets: a parallel-ROOT
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
;; rf2-oy49f1 — `nil` USED to be listed here as a documented divergence
;; (viz → `[]` drop; engine → `[{}]` the forbidden-transition / internal
;; no-op form, rf2-16gxd). That was actually a BUG, not a deliberate
;; scope choice: Spec 005 §Forbidden transitions declares `nil` and `{}`
;; RUNTIME-EQUIVALENT, so the viz walker silently dropping a nil-spelled
;; forbidden transition (while rendering the `{}` spelling as a blocking
;; chip) made a reader believe an event was still inherited/reachable
;; when the engine actually blocks it. `grammar/transition-candidates`
;; now special-cases `(nil? spec) [{}]`, matching the engine — `nil` has
;; MOVED into `shared-grammar-spec-fixtures` below as a genuine parity
;; case, not a divergence.

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
   ;; rf2-oy49f1 — nil ≡ {} (Spec 005 §Forbidden transitions): a
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
   ;; XState shorthand → nil (operator-ruled divergence)
   "5s" "10ms" "2m"
   ;; degenerate / malformed ISO → nil
   "P" "PT" "PT0S" "P0D" "soon" ""
   ;; wrong types → nil
   nil [1000] :pt5s])

(deftest resolve-timeout-ms-parity
  (testing "viz grammar/resolve-timeout-ms agrees with the engine
            timeout/resolve-duration-ms on every duration shape"
    (doseq [d duration-fixtures]
      (is (= (timeout/resolve-duration-ms d)
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
            timeout/desugar-timeouts across every machine shape"
    (doseq [m timeout-machine-fixtures]
      (is (= (timeout/desugar-timeouts m)
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
            choice/desugar-choices across every machine shape"
    (doseq [m choice-machine-fixtures]
      (is (= (choice/desugar-choices m)
             (g/desugar-choices m))
          (str ":choice desugar drifted for " (pr-str m))))))

;; ---------------------------------------------------------------------------
;; PARITY 8 — definition VALIDATION (rf2-j538f7.18)
;;
;; PARITY: `grammar/valid-definition?` (via `definition-defect`) must give the
;; SAME accept/reject answer as the runtime
;; `re-frame.machines.lifecycle-fx.validation/validate-machine!` for the
;; PROJECTABLE structural grammar. If this fails, the viz recursive validator
;; drifted from the engine — re-sync, do not delete.
;;
;; The pre-fix shallow `valid-definition?` blessed every deeply-invalid
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

  The two-valued probe this replaced could not express that third outcome, and
  on CLJS actively HID it: its `(catch :default … false)` recorded a host crash
  as a clean `:reject`, so a definition that made the engine explode looked
  exactly like one it had validated and rejected (rf2-dhl4d)."
  [m]
  (try (validation/validate-machine! m) :accept
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
   :valid-spawn-all  {:initial :a :states {:a {:spawn-all {:children [{:id :c1 :machine-id :m}]
                                                           :on-child-done :cd :on-child-error :ce
                                                           :on-all-complete [:done]}
                                               :on {:go :b}} :b {}}}
   :valid-namespaced {:initial :a :states {:a {:my.app/note "x"}}}
   :valid-tags       {:initial :a :states {:a {:tags #{:busy}}}}
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
   :spawn-neither    {:initial :a :states {:a {:spawn {}}}}
   :spawn-both       {:initial :a :states {:a {:spawn {:machine-id :m :definition {:initial :x :states {:x {}}}}}}}
   :spawn-unknown    {:initial :a :states {:a {:spawn {:machine-id :m :bogus 1}}}}
   :par-nested       {:type :parallel :regions {:r {:initial :a :states {:a {:type :parallel :regions {:x {:initial :y :states {:y {}}}}}}}}}
   :par-no-init      {:type :parallel :regions {:r {:states {:a {}}}}}
   :par-mutex        {:type :parallel :initial :a :regions {:r {:initial :a :states {:a {}}}}}
   :par-empty        {:type :parallel :regions {}}
   ;; ---- non-Named KEYS (rf2-dhl4d / rf2-oztox) ----
   ;;
   ;; Every entry above spells its keys as keywords, so until now the corpus
   ;; could not see either side's treatment of a key that is not `Named` —
   ;; and both sides had the same defect there, a bare `(namespace k)` that
   ;; THREW rather than rejecting. The ratchet exists to stop the two drifting
   ;; and it could not fail on the one axis they were both wrong about. A
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
;; the same input agree, and `definition-validation-parity` passes them. That
;; is not a hypothetical: a bare `(namespace k)` on a non-`Named` key threw on
;; both sides at once, which is precisely why the divergence hid (rf2-dhl4d).
;; Neither side may answer `:host-throw` for anything in the corpus.

(deftest definition-validation-is-total
  (testing "neither validator answers a corpus definition with a host throw"
    (doseq [[label m] validation-parity-corpus]
      (is (not= :host-throw (engine-answer m))
          (str label ": the ENGINE threw a host exception where a structured "
               ":rf.error/machine-* rejection was the contract"))
      (is (not= :host-throw (viz-answer m))
          (str label ": the VIZ threw a host exception where a returned defect "
               "was the contract")))))

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

  (testing "viz-STRICTER root shape is a DIVERGENCE — the engine resolves a
            missing / late root :initial lazily at runtime; the viz REQUIRES a
            keyword root :initial + non-empty :states to have an initial-marker
            to project"
    (let [m {:states {:idle {}}}]
      (is (= :accept (engine-answer m)) "engine accepts a flat machine with no root :initial")
      (is (= :reject (viz-answer m))    "the viz rejects it (no initial to project)"))))
