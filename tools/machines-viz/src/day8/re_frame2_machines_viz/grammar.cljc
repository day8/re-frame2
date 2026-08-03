(ns day8.re-frame2-machines-viz.grammar
  "Shared grammar walker and injective id codec for the chart, Mermaid,
  and SCXML emitters.

  All three surfaces project the same machine-definition grammar, so they
  normalise transitions and address nodes through this namespace. The
  runtime machines artefact remains bundle-isolated; representative parity
  tests compare this tool-side mirror with the runtime grammar.

  SCXML keeps its root-transition candidate parser and inverse id decoder
  locally because those operations follow SCXML-specific grammar."
  (:require [clojure.string :as str]))

;; ---------------------------------------------------------------------------
;; Grammar walker — transition-spec normalisation
;; ---------------------------------------------------------------------------

(defn target-path?
  "True when `v` is a grammar VECTOR-PATH target (Spec 005): a non-empty
  vector of keywords."
  [v]
  (and (vector? v)
       (seq v)
       (every? keyword? v)))

(defn transition-candidates
  "Normalise a transition spec to candidate maps (Spec 005 §Transition
  table grammar). A transition spec may be:

  - a keyword — the target state;
  - a vector path of keywords — an absolute target path;
  - a map — `{:target ... :action ... :guard ...}` (no `:target` =
    internal / action-only);
  - `nil` — a FORBIDDEN transition (rf2-oy49f1). Spec 005 §Forbidden
    transitions (`spec/005-StateMachines.md` §Forbidden transitions)
    declares `{:on {:logout {}}}` and `{:on {:logout nil}}`
    RUNTIME-EQUIVALENT — both block parent-fallthrough for that event.
    `nil` normalises to the SAME single empty-map candidate `{}` as the
    empty-map spelling (`map? {}` already yields `[{}]` → `internal?`
    true → a blocking chip); pre-fix `nil` matched no cond arm and fell
    to `:else []` (ZERO candidates), silently dropping the block instead
    of rendering it.
  - a vector of specs — multiple candidates (first-match wins at runtime;
    every target-bearing branch surfaces);
  - anything else (e.g. an inline fn) — dropped (cannot statically
    resolve).

  The SINGLE shared walker the chart + mermaid + the per-state SCXML
  emitters all use. NOTE the root-parallel SCXML emitter uses its OWN
  `scxml/root-transition-candidates` instead — a vector-of-vectors there
  is one multi-region target, not a candidate fork (that walker already
  special-cases `(nil? spec) [{}]` per the same equivalence — this fix
  brings the SHARED walker into alignment with it)."
  [spec]
  (cond
    (keyword? spec)     [{:target spec}]
    (target-path? spec) [{:target spec}]
    (map? spec)         [spec]
    (vector? spec)      (mapcat transition-candidates spec)
    (nil? spec)         [{}]
    :else               []))

;; ---------------------------------------------------------------------------
;; `:timeout` / `:on-timeout` → `:after` desugar (EP-0029 A4)
;; ---------------------------------------------------------------------------
;;
;; A state (or a `:spawn` spec) may declare a `:timeout` duration + an
;; `:on-timeout` transition (EP-0029 A4). The runtime LOWERS this onto the
;; existing `:after` timer mechanism — `:timeout` is a distinct authoring
;; concept that desugars to an `:after` entry keyed by the resolved ms. The
;; three emitters render the lowered `:after` (clock glyph / `after / …`
;; label / SCXML `<transition>`), so they desugar at their ingestion
;; boundary via `desugar-timeouts` below.
;;
;; machines-viz is bundle-isolated from the runtime `machines` artefact
;; (its deps.edn deliberately drops that dependency for DCE), so the
;; duration resolver + desugar are re-stated here rather than required from
;; `re-frame.machines.timeout`. The SEMANTICS mirror that namespace exactly
;; (integer-ms OR ISO-8601 only — the XState "5s"/"10ms" shorthand is NOT a
;; valid duration); the runtime's `validate-timeouts!` already rejected a
;; bad duration at registration, so by the time a spec reaches an emitter
;; the duration resolves.

(def ^:private iso-duration-re
  #"(?i)^P(?:(\d+)Y)?(?:(\d+)M)?(?:(\d+)W)?(?:(\d+)D)?(?:T(?:(\d+)H)?(?:(\d+)M)?(?:(\d+(?:\.\d+)?)S)?)?$")

(defn- pl [s] (if (nil? s) 0 #?(:clj (Long/parseLong s) :cljs (js/parseInt s 10))))
(defn- pd [s] (if (nil? s) 0.0 #?(:clj (Double/parseDouble s) :cljs (js/parseFloat s))))

(defn resolve-timeout-ms
  "Resolve a `:timeout` duration to a positive-integer ms (a positive
  integer literal, or an ISO-8601 duration string), or nil if it is not a
  valid duration. Mirrors `re-frame.machines.timeout/resolve-duration-ms`.
  Year / month use the fixed 365-day / 30-day convention."
  [duration]
  (cond
    (and (integer? duration) (pos? duration)) (long duration)
    (string? duration)
    (when-let [m (re-matches iso-duration-re duration)]
      (let [[_ y mo w d h mi sec] m]
        (when (some some? [y mo w d h mi sec])
          (let [ms (+ (* (pl y)  (* 365 24 60 60 1000))
                      (* (pl mo) (* 30 24 60 60 1000))
                      (* (pl w)  (* 7 24 60 60 1000))
                      (* (pl d)  (* 24 60 60 1000))
                      (* (pl h)  (* 60 60 1000))
                      (* (pl mi) (* 60 1000))
                      (Math/round (* (pd sec) 1000.0)))]
            (when (pos? ms) (long ms))))))
    :else nil))

(defn- desugar-node-timeouts
  "Desugar one state-node's state-level AND spawn-level `:timeout` /
  `:on-timeout` into `:after` entries on the node, dropping the timeout
  keys. A node with neither is returned unchanged."
  [node]
  (if-not (map? node)
    node
    (let [st-ms   (when (contains? node :timeout) (resolve-timeout-ms (:timeout node)))
          spawn   (:spawn node)
          sp-ms   (when (and (map? spawn) (contains? spawn :timeout))
                    (resolve-timeout-ms (:timeout spawn)))
          node    (cond-> (dissoc node :timeout :on-timeout)
                    st-ms (update :after #(merge {st-ms (:on-timeout node)} %)))]
      (cond-> node
        (map? spawn) (assoc :spawn (dissoc spawn :timeout :on-timeout))
        sp-ms        (update :after #(merge {sp-ms (:on-timeout spawn)} %))))))

(defn- walk-states-timeouts [states]
  ;; `map?`-guarded (not just truthy) so a MALFORMED non-map `:states` (a
  ;; pathological direct-host prop) degrades to nil rather than throwing in
  ;; `reduce-kv` — the recursive validator that desugars first must be able to
  ;; REJECT such input, not crash on it. Well-formed inputs are unchanged (the
  ;; desugar-parity corpus never carries a non-map `:states`).
  (when (map? states)
    (reduce-kv
      (fn [acc k node]
        (assoc acc k (let [n (desugar-node-timeouts node)]
                       (cond-> n (:states n) (update :states walk-states-timeouts)))))
      {} states)))

(defn desugar-timeouts
  "Rewrite a whole machine definition so every state-level / spawn-level /
  root-level `:timeout` / `:on-timeout` (EP-0029 A4) is lowered into the
  equivalent `:after` entry, leaving no `:timeout` keys for an emitter to
  see. Pure; idempotent; nil-safe (a non-map → unchanged). Handles flat /
  compound (`:states`), parallel (`:regions`), and the root node."
  [definition]
  (if-not (map? definition)
    definition
    (let [root' (desugar-node-timeouts (dissoc definition :states :regions))
          def'  (cond-> (dissoc definition :timeout :on-timeout)
                  (contains? root' :after) (assoc :after (:after root')))]
      (cond-> def'
        (:states def')        (update :states walk-states-timeouts)
        (map? (:regions def')) (update :regions
                                (fn [regions]
                                  (reduce-kv
                                    (fn [acc rn body]
                                      (assoc acc rn
                                             (cond-> (desugar-node-timeouts (dissoc body :states))
                                               (:states body)
                                               (assoc :states (walk-states-timeouts (:states body))))))
                                    {} regions)))))))

;; ---------------------------------------------------------------------------
;; `:type :choice` / `:choice` → `:always` desugar (EP-0029 A5)
;; ---------------------------------------------------------------------------
;;
;; A `:type :choice` transient / choice state (EP-0029 A5) routes immediately
;; on entry to the first guard-passing candidate. The runtime LOWERS this onto
;; the existing `:always` eventless-transition mechanism — `:choice` is a
;; distinct authoring concept that desugars to an ordinary state carrying the
;; same candidate vector under `:always`, dropping the `:type :choice` /
;; `:choice` keys. The three emitters render the lowered `:always` (the
;; candidate edges) so they desugar at their ingestion boundary via
;; `desugar-grammar` below, alongside the `:timeout` desugar.
;;
;; The SEMANTICS mirror `re-frame.machines.choice/desugar-choices` exactly;
;; machines-viz is bundle-isolated from the runtime `machines` artefact, so the
;; desugar is re-stated here rather than required. By the time a spec reaches
;; an emitter the runtime's `validate-machine!` already rejected a malformed
;; choice state at registration, so the desugar can assume a well-formed
;; candidate vector.

(defn- choice-node? [node]
  (and (map? node) (= :choice (:type node))))

(defn- desugar-node-choice
  "Desugar one state-node's `:type :choice` / `:choice` into an `:always`
  slot carrying the candidate vector, dropping the `:type` / `:choice`
  keys. A non-choice node is returned unchanged."
  [node]
  (if-not (choice-node? node)
    node
    (-> node (assoc :always (:choice node)) (dissoc :type :choice))))

(defn- walk-states-choice [states]
  ;; `map?`-guarded — see `walk-states-timeouts`.
  (when (map? states)
    (reduce-kv
      (fn [acc k node]
        (assoc acc k (let [n (desugar-node-choice node)]
                       (cond-> n (:states n) (update :states walk-states-choice)))))
      {} states)))

(defn desugar-choices
  "Rewrite a whole machine definition so every `:type :choice` transient
  state (EP-0029 A5) is lowered into an ordinary state carrying its
  `:choice` candidate vector under `:always`, leaving no `:type :choice`
  keys for an emitter to see. Pure; idempotent; nil-safe (a non-map →
  unchanged). Handles flat / compound (`:states`) and parallel (`:regions`)
  machines."
  [definition]
  (if-not (map? definition)
    definition
    (cond-> definition
      (:states definition)        (update :states walk-states-choice)
      (map? (:regions definition)) (update :regions
                                    (fn [regions]
                                      (reduce-kv
                                        (fn [acc rn body]
                                          (assoc acc rn
                                                 (cond-> (desugar-node-choice body)
                                                   (:states body)
                                                   (update :states walk-states-choice))))
                                        {} regions))))))

(defn desugar-grammar
  "Apply every named-intent grammar desugar an emitter must lower before
  walking a machine definition (EP-0029): `:timeout` / `:on-timeout` →
  `:after` (A4) and `:type :choice` / `:choice` → `:always` (A5). The
  single ingestion-boundary seam the three emitters share so a future
  desugar is added once, not three times. Pure; idempotent; nil-safe."
  [definition]
  (-> definition desugar-timeouts desugar-choices))

;; ---------------------------------------------------------------------------
;; Definition-shape validation — the SINGLE shape gate all three emitters share
;; ---------------------------------------------------------------------------
;;
;; rf2-egupfk — the AI-generate, Mermaid, and SCXML emitters each project a
;; machine definition onto a different surface, but they must agree on which
;; definitions are well-formed enough to project AT ALL. Pre-fix each emitter
;; hand-rolled its own `valid-state-tree?` / parallel check and the copies had
;; DRIFTED: SCXML accepted malformed parallel region bodies (regions with no
;; `:initial` / `:states`) that AI + Mermaid rejected, and AI required a
;; KEYWORD `:initial` per the machine contract (Spec 005 §Transition table
;; grammar — state ids are keywords) while Mermaid + SCXML accepted any truthy
;; `:initial`. Three copies = three chances to drift; a drift here means one
;; emitter renders a spec the others reject (or vice-versa), and the three
;; diagrams disagree on what the same input even IS.
;;
;; These are the ONE source of truth. Every emitter desugars (`desugar-grammar`)
;; then routes its shape check + value-free error summary through here, keeping
;; only its surface-specific error id / message. The STRICT reading wins — the
;; machine contract wants a keyword `:initial` and well-formed parallel regions
;; — so unifying tightens the lax emitters (SCXML) rather than loosening the
;; strict one (AI).
;;
;; Kept LOCAL to machines-viz (bundle-isolated tooling): these do NOT depend on
;; the runtime `machines` grammar (`re-frame.machines.validate`), they re-state
;; the minimum projectable shape the emitters need.

(defn valid-state-tree?
  "True when `definition` is a well-formed flat / compound state tree: a map
  carrying a KEYWORD `:initial` (a state id — Spec 005 §Transition table
  grammar declares state ids are keywords) and a non-empty `:states` map.
  The shared minimum-shape predicate the three emitters agree on."
  [definition]
  (and (map? definition)
       (keyword? (:initial definition))
       (map? (:states definition))
       (seq (:states definition))))

(defn parallel-definition?
  "True when `definition` is a `:type :parallel` root (Spec 005 §Parallel
  regions). nil-safe / non-map-safe."
  [definition]
  (and (map? definition)
       (= :parallel (:type definition))))

(declare definition-defect)

(defn valid-definition?
  "True when `definition` is a machine shape every emitter can project —
  RECURSIVELY (rf2-j538f7.18). Delegates to `definition-defect`: a definition
  is valid iff it carries no structural projectability defect.

  This is NO LONGER a shallow minimum-shape check. Pre-fix it validated only
  the ROOT (or parallel-region roots) as a `valid-state-tree?`, so it blessed
  structurally-invalid-but-shallowly-ok definitions — a nested compound
  missing `:initial`, a dangling transition target, an unknown bare node key —
  and every boundary that delegates here (share encode/decode, AI generation,
  Mermaid, SCXML, the chart projector) inherited the same false positive. It
  now walks root, parallel regions and every compound descendant, mirroring the
  runtime `re-frame.machines.lifecycle-fx.validation/validate-machine!` for the
  projectable structural invariants. See `definition-defect` for the full
  contract + the documented viz-vs-engine divergences."
  [definition]
  (nil? (definition-defect definition)))

(def summary-type-vocabulary
  "The CLOSED `:type` vocabulary `definition-summary` may emit — deliberately
  the set `re-frame.error/diag-value-summary` and
  `machines-viz.share/value-free-summary` already share (rf2-210uq /
  rf2-m46qv), so a tool reading a thrown `ex-data` from any of the three reads
  ONE diagnostic vocabulary."
  #{:map :vector :seq :set :keyword :symbol :string :number :boolean :nil
    :fn :scalar})

(defn- defect-summary
  "The value-free projection of a `definition-defect` (rf2-oztox).

  `definition-defect` is the diagnostic for a caller that ALREADY HOLDS the
  definition, so it names the offending material directly: a `:path` of state
  ids and, for key defects, the offending `:keys`. Both are read straight off
  the definition, so both are attacker-chosen in CONTENT and in SIZE when the
  definition is forged — which is exactly the case `definition-summary` exists
  to describe. This projection keeps the diagnosis and drops the material:

    {:category  <:rf.error/machine-*>   ;; closed — the literals in this file
     :slot      :on | :after | :always | :on-done   ;; closed, when present
     :depth     <int>      ;; how deep the defect sits, not WHERE
     :key-count <int>}     ;; how many keys offended, not WHICH

  Every slot is a member of a closed vocabulary or an integer, so nothing here
  is derived from the definition's content. `:depth` and `:key-count` are the
  parts of `:path` / `:keys` that survive that rule, and they are the useful
  parts: \"two unknown keys on a node three levels down\" is the diagnosis a
  reader acts on, and an integer cannot carry a fragment of a token."
  [defect]
  (let [{:keys [category path slot]} defect
        offending                    (get defect :keys)]
    (cond-> {:category category}
      slot          (assoc :slot slot)
      (some? path)  (assoc :depth (count path))
      (seq offending) (assoc :key-count (count offending)))))

(defn definition-summary
  "EP-0015 / Spec 015 §exception-path residual (rf2-8nzxib) — a value-FREE
  structural diagnostic for a rejected `definition`. The single summary the
  three emitters, the share boundary and the chart projector share so their
  rejection diagnostics agree (each stashes it under its own surface-specific
  key: `:spec-summary` / `:definition-summary` / `:definition-error`).

  Shape:

    {:type         <member of `summary-type-vocabulary`>
     :count        <int>   ;; counted collection / string
     :parallel     <bool>  ;; map only
     :state-count  <int>   ;; map with a map `:states`
     :region-count <int>   ;; map with a map `:regions`
     :defect       <`defect-summary`>}  ;; when the definition is rejected

  **Content-free BY CONSTRUCTION (rf2-oztox).** Every value this can carry is
  a member of a closed vocabulary, an integer, or a boolean, so no expression
  in the output is derived from the input's CONTENT and the serialized summary
  is a fixed size whatever arrives. That is a structural guarantee rather than
  a redaction-quality argument — there is no prefix to bound and no key set to
  cap — and it is the only claim worth making about a value this function is
  handed from a forged share URL (`share/decode-share-url` gates `…/chart`'s
  `:definition` through `valid-definition?`), from an LLM response
  (`ai_generate`), and from SCXML / Mermaid input.

  IT DID NOT HOLD BEFORE, on the two legs rf2-210uq removed from
  `re-frame.error/diag-value-summary` and rf2-m46qv removed from
  `share/value-free-summary`, reproduced here a third time:

  - `:keys` — every top-level key of the rejected definition, uncapped and
    unsanitised, riding into an ex-info that names itself value-free. A
    forged definition's key set is attacker-chosen in content (keys carry
    markup, control characters and secrets as readily as values do) and in
    size, so the summary grew with the forger's input without limit. Removing
    it also removes the `(sort-by str (keys definition))` that ran `str` over
    caller-supplied keys, where a key whose `toString` THREW replaced the
    documented failure with its own exception.
  - the `:else {:type :value}` tag, outside the closed vocabulary the other
    two summarisers now share.

  SIZE AND SHAPE ARE DELIBERATELY KEPT. `:count` / `:state-count` /
  `:region-count` / `:parallel` are what make the summary useful rather than
  merely safe — \"a 2000-key map where a machine definition was expected\", \"a
  parallel root with 3 regions\" is the diagnosis — and none of them can carry
  a fragment of a token. A lazy seq is NOT counted: realising it on the
  failure path is its own hazard.

  rf2-j538f7.18 — a rejected definition also carries `:defect`, whose
  `:category` is the engine's canonical `:rf.error/machine-*` id, so every
  surface that stashes this summary reports the CANONICAL defect while keeping
  its own surface-specific error id. See `defect-summary` for why it is a
  projection of `definition-defect` rather than the defect itself."
  [definition]
  (let [defect (definition-defect definition)
        base   (cond
                 (map? definition)
                 (cond-> {:type     :map
                          :count    (count definition)
                          :parallel (parallel-definition? definition)}
                   (map? (:states definition))  (assoc :state-count  (count (:states definition)))
                   (map? (:regions definition)) (assoc :region-count (count (:regions definition))))

                 (nil? definition)     {:type :nil}
                 (vector? definition)  {:type :vector :count (count definition)}
                 (set? definition)     {:type :set    :count (count definition)}
                 (string? definition)  {:type :string :count (count definition)}
                 (keyword? definition) {:type :keyword}
                 (symbol? definition)  {:type :symbol}
                 (boolean? definition) {:type :boolean}
                 (number? definition)  {:type :number}
                 (seq? definition)     {:type :seq}      ; NOT counted — see above
                 (fn? definition)      {:type :fn}
                 (seqable? definition) {:type :seq}
                 :else                 {:type :scalar})]
    (cond-> base
      defect (assoc :defect (defect-summary defect)))))

(defn parent-path
  "The parent path of `path` (its `pop`); `[]` for an empty/top-level
  path."
  [path]
  (if (seq path)
    (pop path)
    []))

(defn history-node?
  "rf2-m285a — true when a node under a compound's `:states` is a
  `:type :history` PSEUDO-STATE (Spec 005 §History states), not an
  ordinary occupiable substate. A history pseudo-state is NEVER active: a
  transition *to* it resolves to the compound's recorded / default leaf
  configuration. Every emitter paints it as a history marker (chart
  `history-marker` glyph, mermaid `H`/`H*` alias, SCXML `<history>`),
  never an ordinary state."
  [state-node]
  (and (map? state-node)
       (= :history (:type state-node))))

(defn reenter?
  "rf2-9dj21r — true when a transition candidate opts in to the EXTERNAL
  restart axis (`:reenter? true`). Spec 005 §Self-transitions + XState v5:
  a TARGETED transition is INTERNAL by default — its own `:exit`/`:entry`
  do not re-run — and only `:reenter? true` makes a self / proper-ancestor
  / compound-declared-descendant target EXTERNAL (re-run `:exit`+`:entry`,
  restart the target's `:after` timers + tear-down-and-respawn its
  `:spawn` children). Matches the engine's `(true? (:reenter?
  transition))` read (`re-frame.machines.transition`). Only a map
  candidate can carry it; a bare keyword / vector-path target never does.
  Carried onto every emitter's edge so a `:reenter? true` transition
  renders DISTINCTLY from its internal default (otherwise two
  runtime-distinct machines project IDENTICALLY)."
  [candidate]
  (and (map? candidate)
       (true? (:reenter? candidate))))

(defn normalise-root-targets
  "rf2-3v3gv1 / rf2-656ivk / rf2-m3otj2 — normalise a PARALLEL-ROOT `:on` /
  `:after` candidate's `:target` into a vector of region-qualified absolute
  targets `[[<region> & <in-region-path>] …]`, mirroring the runtime resolver
  (`re-frame.machines.parallel/normalise-root-targets`) so the projected /
  exported edges address the SAME regions the engine moves. Per the grammar
  (Spec 005 §Root parallel `:on` / §Root-level `:after`):

    - nil / absent → `[]` (TARGETLESS / action-only — runs `:action` / `:fx`,
      moves no region);
    - a vector of KEYWORDS (`[:a :two]`) → ONE region-qualified target (head =
      region name, rest = the in-region path); wrapped to `[[:a :two]]`;
    - a vector of VECTORS (`[[:a :x] [:b :y]]`) → MULTIPLE region-qualified
      targets, returned as-is.

  An empty vector return means action-only — the caller self-anchors a
  terminal/internal root chip rather than emitting a moved-region edge.

  Shared by the chart projector (`chart.layout`) and the SCXML emitter
  (`scxml`, as `root-region-qualified-targets`); the mermaid emitter
  consumes the per-candidate exploder form (`root-region-qualified-
  candidates`) which is built on the same vector-of-vectors test."
  [target]
  (cond
    (nil? target)                        []
    (and (vector? target)
         (every? vector? target))        (vec target)
    (vector? target)                     [target]
    :else                                []))

;; ---------------------------------------------------------------------------
;; RECURSIVE projectability validation — rf2-j538f7.18
;; ---------------------------------------------------------------------------
;;
;; `valid-definition?` / `definition-defect` recursively enforce the
;; runtime-relevant STRUCTURAL PROJECTABILITY contract, so every ingestion /
;; export boundary that delegates here (share encode/decode, AI generation,
;; Mermaid, SCXML, and the chart projector) gives the SAME accept/reject answer
;; the runtime machine contract would — no longer the pre-fix SHALLOW
;; minimum-shape check that blessed structurally-invalid-but-shallowly-ok
;; definitions (nested compounds missing `:initial`, dangling transition
;; targets, unknown bare node keys, …).
;;
;; The walker MIRRORS
;; `re-frame.machines.lifecycle-fx.validation/validate-machine!` for the
;; PROJECTABLE invariants. machines-viz stays bundle-isolated (this ns
;; `:require`s only `clojure.string`), so the engine grammar/target-resolution
;; is RE-STATED here, not required; a TEST-ONLY parity corpus pins the mirror
;; against the engine oracle (`engine-grammar-parity-test`).
;;
;; Enforced (recursively, over root + parallel regions + every compound
;; descendant):
;;   - keyword state/region ids + non-empty `:states` maps;
;;   - compound-`:initial` PRESENCE (`machine-compound-state-missing-initial`);
;;   - transition `:target` shape + resolution for `:on` / `:after` /
;;     `:always` / `:on-done` / `:spawn :on-error`
;;     (`machine-bad-target` / `machine-unresolved-target`);
;;   - unknown BARE node / spawn keys — NAMESPACED keys pass
;;     (`machine-unknown-node-key` / `machine-unknown-spawn-key`);
;;   - `:final?` shape (`machine-final-state-compound` / `-has-transitions` /
;;     `machine-output-key-without-final` / `machine-error-flag-without-final`);
;;   - `:tags` set-of-keywords (`machine-bad-tags`);
;;   - single `:spawn` XOR `:machine-id` / `:definition`
;;     (`machine-spawn-bad-shape`);
;;   - `:after` delay-key shape (`machine-bad-after-delay`);
;;   - history pseudo-state placement / closed key-set /
;;     at-most-one-per-compound / `:default-target` resolution
;;     (`machine-history-*`);
;;   - mutually exclusive `:type :parallel` shape + non-nested regions
;;     (`machine-parallel-bad-shape` / `-nested-not-supported`).
;;
;; The `:category` on each returned defect IS the engine's `:rf.error/machine-*`
;; id, so a surface can carry the CANONICAL defect category while keeping its
;; own surface-specific error id.
;;
;; DOCUMENTED viz-vs-engine divergences (out of scope — either runtime-WIRING
;; not projectable topology, viz-STRICTER by necessity, or bounded complexity;
;; pinned in the parity test's divergence set so a future change that
;; accidentally aligns/diverges them is caught):
;;   - guard / action / `:on-spawn` keyword REF resolution (needs the machine
;;     registry — runtime wiring, not projectable topology);
;;   - a non-parallel root `:after` (the engine rejects it as unschedulable;
;;     the viz still PROJECTS it as a machine-root anchor);
;;   - viz-STRICTER root shape: the viz REQUIRES a keyword root `:initial` +
;;     non-empty `:states` (and non-empty region `:states`) — it needs an
;;     initial-marker to project — whereas the engine resolves a missing / late
;;     `:initial` lazily at runtime;
;;   - the parallel-root region-qualified `:on` / `:after` / `:on-done` target
;;     grammar + full `:spawn-all` shape (bounded complexity — a VALID
;;     spawn-all still projects; a malformed one is simply not rejected here).

(def ^:private known-state-node-keys
  "Closed BARE key vocabulary a state-node may declare — bundle-isolated mirror
  of the engine's `validation/known-state-node-keys`. Namespaced keys are the
  open extension carve-out. `:type :history` / `:type :choice` pseudo-states
  carry their own key-sets and are skipped by the node-key check."
  #{:type :deep? :default-target :regions :region-order
    :initial :states :data :schemas :internal-events
    :guards :actions :on-spawn-actions
    :entry :exit
    :spawn :spawn-all
    :always :after :choice :timeout :on-timeout :on :on-done
    :tags :final? :output-key :error?
    :meta :source-coords :source-code})

(def ^:private known-machine-root-extra-keys
  "Keys legal ONLY on the machine ROOT beyond `known-state-node-keys` — mirror
  of the engine's `validation/known-machine-root-extra-keys`."
  #{:doc :sensitive :large :schema :raise-depth-limit :always-depth-limit})

(def ^:private known-spawn-spec-keys
  "Closed BARE key vocabulary a single `:spawn` spec may declare — mirror of the
  engine's `validation/known-spawn-spec-keys` (the retired `:timeout-ms` slot is
  excluded from the unknown-key scan so it never surfaces as an unknown key)."
  #{:machine-id :definition :data :id-prefix :on-spawn :on-done :on-error
    :start :fixed-actor-id :system-id :timeout :on-timeout
    :id :source-coords :source-code})

(def ^:private history-pseudo-keys
  "Closed key-set a `:type :history` pseudo-state may carry."
  #{:type :deep? :default-target})

(defn node-at
  "Descend a `:states` map down absolute `path`, returning the leaf node (or nil
  if `path` doesn't resolve). Bundle-isolated mirror of
  `re-frame.machines.grammar/node-at`; an empty path resolves to nil (the scope
  root is the `states` map itself, not a node)."
  [states path]
  (loop [m states p (vec path)]
    (cond
      (empty? p) nil
      :else (let [n (get m (first p))]
              (cond
                (nil? n)        nil
                (= 1 (count p)) n
                :else           (recur (:states n) (rest p)))))))

(defn transition-value-form
  "Classify a transition-table slot value into its grammar form — bundle-
  isolated mirror of `re-frame.machines.grammar/transition-value-form` (the
  recogniser the runtime target validator discriminates on)."
  [v]
  (cond
    (nil? v)     :nil
    (keyword? v) :keyword
    (and (vector? v) (seq v) (every? map? v)) :candidate-vector
    (vector? v)  :vec-target
    (map? v)     :map
    :else        :other))

(defn candidate-maps
  "Normalise a transition slot value to its candidate maps — bundle-isolated
  mirror of `re-frame.machines.grammar/candidate-maps` (`:nil` → `[{}]`,
  malformed → nil). Used to explode an `:always` slot the way the runtime
  target validator does. Distinct from the projector's `transition-candidates`
  (which mapcat-explodes a mixed vector) — this matches the engine's normaliser
  so the VALIDATOR agrees with `validate-machine!`."
  [v]
  (case (transition-value-form v)
    :nil              [{}]
    :keyword          [{:target v}]
    :candidate-vector v
    :vec-target       [{:target v}]
    :map              [v]
    :other            nil))

(defn candidate-targets
  "Normalise a transition slot value to the seq of `:target`s it declares, each
  tagged `:present?` (whether the `:target` KEY was present) — bundle-isolated
  mirror of `re-frame.machines.grammar/candidate-targets` (the shape the runtime
  target VALIDATOR consumes)."
  [v]
  (case (transition-value-form v)
    :keyword          [{:present? true :target v}]
    :candidate-vector (mapv (fn [m] {:present? (contains? m :target) :target (:target m)}) v)
    :vec-target       [{:present? true :target v}]
    :map              [{:present? (contains? v :target) :target (:target v)}]
    []))

(defn- compound?
  "A node is compound iff it declares a non-empty `:states` map."
  [node]
  (and (map? (:states node)) (seq (:states node))))

(defn- resolves-to-state?
  "True iff `target` resolves to a real node under `owning-path` within `scope`
  (a `:states` map). A keyword names a direct child of the owning compound; a
  vector is an absolute path from the (region) root. Mirror of the engine's
  `validation/resolves-to-state?`."
  [scope owning-path target]
  (cond
    (keyword? target) (some? (node-at scope (conj (vec owning-path) target)))
    (vector? target)  (and (seq target) (some? (node-at scope target)))
    :else             false))

(defn- unknown-bare-keys
  "The BARE keys of `m` not in `known` (a namespaced key is the open extension
  carve-out and never flagged).

  TOTAL over any key a forged definition can carry (rf2-oztox). The carve-out
  used to be a bare `(remove #(namespace %))`, and `namespace` THROWS on a key
  that is not `Named` — so `{:initial :a :states {:a {}} \"x\" 1}`, which a
  transit-decoded share payload carries as readily as a keyword-keyed one, threw
  a host `ClassCastException` out of `definition-defect`, and therefore out of
  `valid-definition?`, in place of the documented `:invalid-chart-state` /
  `:invalid-definition` rejection every boundary here promises. That is the
  rf2-210uq path-5 shape one level below the one the bead named: a hostile key
  destroying the failure it was supposed to produce.

  Testing `Named`-ness first also makes the answer the RIGHT one rather than
  merely non-throwing. A node key that is not a keyword is not a legal node key
  under any reading of the grammar, so it belongs in `offending` — the same
  `:rf.error/machine-unknown-node-key` a misspelled `:on-entry` earns."
  [m known]
  (->> (keys m)
       (remove #(and (or (keyword? %) (symbol? %)) (namespace %)))
       (remove known)
       vec))

;; ---- per-node defect checks (each returns a value-free defect map or nil) --

(defn- node-keys-defect [path node at-root?]
  (when (and (map? node)
             (not (history-node? node))
             (not (choice-node? node)))
    (let [known     (cond-> known-state-node-keys
                       at-root? (into known-machine-root-extra-keys))
          offending (unknown-bare-keys node known)]
      (when (seq offending)
        {:category :rf.error/machine-unknown-node-key :path (vec path) :keys offending}))))

(defn- tags-defect [path node]
  (when (contains? node :tags)
    (let [tags (:tags node)]
      (when-not (and (set? tags) (every? keyword? tags))
        {:category :rf.error/machine-bad-tags :path (vec path)}))))

(defn- compound-initial-defect [path node]
  (when (and (compound? node) (not (contains? node :initial)))
    {:category :rf.error/machine-compound-state-missing-initial :path (vec path)}))

(defn- final-state-defect [path node]
  (cond
    (true? (:final? node))
    (cond
      (or (contains? node :states) (contains? node :initial))
      {:category :rf.error/machine-final-state-compound :path (vec path)}
      (some #(contains? node %) [:on :always :after :spawn :spawn-all])
      {:category :rf.error/machine-final-state-has-transitions :path (vec path)})
    (contains? node :output-key)
    {:category :rf.error/machine-output-key-without-final :path (vec path)}
    (contains? node :error?)
    {:category :rf.error/machine-error-flag-without-final :path (vec path)}))

(defn- spawn-defect [path node]
  (let [spec (:spawn node)]
    (when (map? spec)
      (let [has-id?   (contains? spec :machine-id)
            has-def?  (contains? spec :definition)
            offending (vec (remove #{:timeout-ms}
                                   (unknown-bare-keys spec known-spawn-spec-keys)))]
        (cond
          (or (and has-id? has-def?) (and (not has-id?) (not has-def?)))
          {:category :rf.error/machine-spawn-bad-shape :path (vec path)}
          (seq offending)
          {:category :rf.error/machine-unknown-spawn-key :path (vec path) :keys offending})))))

(defn- valid-after-delay-key?
  "A static `:after` map KEY is well-formed iff a positive integer (literal ms),
  a non-empty vector (subscription vector), or a fn — mirror of the engine's
  `validation/valid-after-delay-key?`."
  [k]
  (boolean (or (and (integer? k) (pos? k)) (and (vector? k) (seq k)) (fn? k))))

(defn- after-delay-defect [path node]
  (some (fn [[k _]]
          (when-not (valid-after-delay-key? k)
            {:category :rf.error/machine-bad-after-delay :path (vec path)}))
        (:after node)))

(defn- always-entries
  "A node's `:always` normalised to candidate maps (absent / explicit-nil →
  [], mirroring the engine's `validation/always-entries`)."
  [node]
  (let [a (:always node)]
    (if (nil? a) [] (or (candidate-maps a) []))))

(defn- target-defect
  "Defect for one transition `:target` against `scope` (the region / machine
  `:states`) and `path` (the declaring node's absolute path). nil / `:same-state`
  is fine; an empty vector / non-keyword-non-vector is a malformed SHAPE; a
  keyword / vector that names no declared node is UNRESOLVED. Mirror of the
  engine's `validation/validate-target!`."
  [scope path slot target]
  (cond
    (nil? target)                       nil
    (= :same-state target)              nil
    (and (vector? target) (empty? target))
    {:category :rf.error/machine-bad-target :path (vec path) :slot slot}
    (or (keyword? target) (vector? target))
    (when-not (resolves-to-state? scope (vec (drop-last path)) target)
      {:category :rf.error/machine-unresolved-target :path (vec path) :slot slot})
    :else
    {:category :rf.error/machine-bad-target :path (vec path) :slot slot}))

(defn- transition-slot-shape-defect
  "The SHARED transition-SLOT shape rule (rf2-qgtcvy node scope / rf2-bj3sxo
  root scopes). A fallback-transition `:on` / `:after` slot present on `node`
  at `path` must be a MAP of clause → spec. A non-map (e.g. `{:on :retry}`
  from an LLM) would throw an uncaught ISeq exception the moment it is
  iterated, instead of the clean `:invalid-definition` the emit paths promise.
  Surface the runtime's own slot-specific defect category so the emit path
  rejects cleanly.

  Deliberately shared by EVERY scope that consumes the `:on` / `:after`
  fallback grammar — compound state node (`transition-target-defect`), flat
  root, region root, and parallel root (`flat-defect` / `region-defect` /
  `parallel-defect`) — so the four scopes cannot drift and one path cannot
  reintroduce an unchecked iteration (rf2-bj3sxo)."
  [path node]
  (or (when (and (contains? node :on) (not (map? (:on node))))
        {:category :rf.error/machine-bad-on-clause :path (vec path)})
      (when (and (contains? node :after) (not (map? (:after node))))
        {:category :rf.error/machine-bad-after-spec :path (vec path)})))

(defn- transition-target-defect [scope path node]
  (or
    (transition-slot-shape-defect path node)
    (let [check (fn [slot v]
                  (some (fn [{:keys [present? target]}]
                          (when present? (target-defect scope path slot target)))
                        (candidate-targets v)))]
      (or (some (fn [[_ v]] (check :on v)) (:on node))
          (some (fn [[_ v]] (check :after v)) (:after node))
          (some (fn [entry] (check :always entry)) (always-entries node))
          (when (contains? node :on-done) (check :on-done (:on-done node)))
          (when-let [oe (get-in node [:spawn :on-error])] (check :spawn/on-error oe))))))

(defn- node-defect
  "First defect on one MAP state-node at `path` within `scope` (its region /
  flat `:states`). History pseudo-states contribute no defect here (their own
  placement / key-set / default-target rules run in `history-scope-defect`; the
  node-key check already skips them)."
  [scope path node]
  ;; Check ORDER mirrors the engine's `validate-machine!` intra-node precedence
  ;; (keys / tags / spawn shape earliest; `:final?` shape BEFORE compound-
  ;; `:initial`; targets; delay keys), so when a single node carries more than
  ;; one defect the viz reports the SAME `:rf.error/machine-*` category the
  ;; runtime would.
  (or (node-keys-defect path node false)
      (tags-defect path node)
      (spawn-defect path node)
      (final-state-defect path node)
      (compound-initial-defect path node)
      (transition-target-defect scope path node)
      (after-delay-defect path node)))

(defn- walk-scope-nodes
  "`[abs-path node]` for every MAP node under `states`, recursing through
  `:states`. Paths are scope-relative (region names never enter a within-scope
  path)."
  [states]
  (letfn [(walk [path nodes]
            (mapcat (fn [[k n]]
                      (when (map? n)
                        (cons [(conj path k) n]
                              (when (map? (:states n))
                                (walk (conj path k) (:states n))))))
                    nodes))]
    (when (map? states) (walk [] states))))

;; ---- history-scope defects (mirror validation/validate-history-scope!) -----

(defn- history-nodes-with-path [states]
  (letfn [(walk [path nodes]
            (mapcat (fn [[k n]]
                      (when (map? n)
                        (let [p (conj path k)]
                          (concat (when (history-node? n) [[p n]])
                                  (when (map? (:states n)) (walk p (:states n)))))))
                    nodes))]
    (when (map? states) (walk [] states))))

(defn- history-node-defect [scope path node]
  (let [owning-path (vec (drop-last path))]
    (cond
      (empty? owning-path)
      {:category :rf.error/machine-history-misplaced :path (vec path)}
      (seq (remove history-pseudo-keys (keys node)))
      {:category :rf.error/machine-history-extra-keys :path (vec path)
       :keys (vec (remove history-pseudo-keys (keys node)))}
      (and (contains? node :default-target)
           (not (resolves-to-state? scope owning-path (:default-target node))))
      {:category :rf.error/machine-history-bad-default-target :path (vec path)})))

(defn- compound-states-pairs
  "`[compound-key states-map]` for the scope root (`:rf/root`) + every nested
  compound inside `states`. Used for the at-most-one-history-per-compound check."
  [states]
  (letfn [(walk [pairs nodes]
            (reduce (fn [acc [k n]]
                      (if (and (map? n) (map? (:states n)) (seq (:states n)))
                        (-> acc (conj [k (:states n)]) (walk (:states n)))
                        acc))
                    pairs nodes))]
    (walk [[:rf/root states]] states)))

(defn- history-scope-defect [scope]
  (or (some (fn [[path node]] (history-node-defect scope path node))
            (history-nodes-with-path scope))
      (some (fn [[_ states-map]]
              (let [hk (->> states-map (keep (fn [[k n]] (when (history-node? n) k))) vec)]
                (when (> (count hk) 1)
                  {:category :rf.error/machine-history-duplicate :path []})))
            (compound-states-pairs scope))))

;; ---- root / region / parallel shape ----------------------------------------

(defn- root-on-target-defect
  "The non-parallel root's OWN `:on` (the ancestor-fallback slot, decl-path
  `[]`) target resolution — mirror of the engine's root `:on` branch in
  `validate-transition-targets!`. Assumes `:on` is a MAP: its shape is
  guarded upstream in `flat-defect` by `transition-slot-shape-defect`, so a
  malformed non-map root `:on` is rejected cleanly BEFORE this iterates it
  (rf2-bj3sxo)."
  [scope d]
  (some (fn [[_ v]]
          (some (fn [{:keys [present? target]}]
                  (when present? (target-defect scope [] :on target)))
                (candidate-targets v)))
        (:on d)))

(defn- flat-defect [d]
  (cond
    (not (keyword? (:initial d)))
    {:category :rf.error/machine-missing-initial :path []}
    (not (and (map? (:states d)) (seq (:states d))))
    {:category :rf.error/machine-missing-states :path []}
    :else
    (let [scope (:states d)]
      (or (node-keys-defect [] d true)
          (tags-defect [] d)
          ;; rf2-bj3sxo — the flat ROOT's own `:on` / `:after` fallback slot
          ;; is validated for shape with the SAME rule as a state node's,
          ;; BEFORE `root-on-target-defect` iterates it. A malformed root
          ;; `:on` (`{… :on :retry}`) now returns the clean
          ;; `machine-bad-on-clause` defect instead of throwing an ISeq
          ;; exception out of `valid-definition?` / the emit paths.
          (transition-slot-shape-defect [] d)
          (some (fn [[path node]] (node-defect scope path node)) (walk-scope-nodes scope))
          (history-scope-defect scope)
          (root-on-target-defect scope d)))))

(defn- region-defect [region-name body]
  (cond
    (not (keyword? region-name))
    {:category :rf.error/machine-parallel-bad-shape :path [region-name]}
    (not (and (map? body) (seq body)))
    {:category :rf.error/machine-parallel-bad-shape :path [region-name]}
    (= :parallel (:type body))
    {:category :rf.error/machine-parallel-nested-not-supported :path [region-name]}
    (not (keyword? (:initial body)))
    {:category :rf.error/machine-parallel-bad-shape :path [region-name]}
    (not (and (map? (:states body)) (seq (:states body))))
    {:category :rf.error/machine-parallel-bad-shape :path [region-name]}
    :else
    (let [scope (:states body)]
      (or (node-keys-defect [region-name] body false)
          (tags-defect [region-name] body)
          ;; rf2-bj3sxo — the region ROOT's own `:on` / `:after` ancestor
          ;; fallback slot gets the same shape guard as every other scope.
          (transition-slot-shape-defect [region-name] body)
          (some (fn [[path node]]
                  (or (when (= :parallel (:type node))
                        {:category :rf.error/machine-parallel-nested-not-supported :path (vec path)})
                      (node-defect scope path node)))
                (walk-scope-nodes scope))
          (history-scope-defect scope)))))

(defn- parallel-defect [d]
  (let [regions (:regions d)]
    (cond
      (not (and (map? regions) (seq regions)))
      {:category :rf.error/machine-parallel-bad-shape :path []}
      (or (contains? d :initial) (contains? d :states))
      {:category :rf.error/machine-parallel-bad-shape :path []}
      :else
      (or (node-keys-defect [] d true)
          ;; rf2-bj3sxo — the parallel ROOT's own `:on` / `:after` ancestor
          ;; fallback slot gets the same shape guard as every other scope.
          (transition-slot-shape-defect [] d)
          (some (fn [[region-name body]] (region-defect region-name body)) regions)))))

(defn definition-defect
  "Return the FIRST structural projectability defect of `definition` as a
  value-FREE map `{:category <:rf.error/machine-*> :path <state-id vector> …}`
  (key/target defects add `:keys` / `:slot`), or nil when the definition is
  projectable. Desugars (`desugar-grammar` — EP-0029 A4/A5) FIRST so a
  `:timeout` / `:choice` authoring form is validated on its lowered shape, then
  recursively mirrors the runtime machine contract's projectable invariants —
  see the section comment above for the full enforced list + the documented
  viz-vs-engine divergences.

  Pure; nil-safe (a nil / non-map desugars unchanged → `machine-bad-definition`).
  The single source of truth `valid-definition?`, `definition-summary`, and every
  emitter / share / chart shape gate delegate to."
  [definition]
  (let [d (desugar-grammar definition)]
    (cond
      (not (map? d))           {:category :rf.error/machine-bad-definition :path []}
      (parallel-definition? d) (parallel-defect d)
      :else                    (flat-defect d))))

;; ---------------------------------------------------------------------------
;; Name rendering — fn-tolerant guard / action / event labels
;; ---------------------------------------------------------------------------

(defn name-of
  "Render a guard / action / entry / exit symbol-like value as a short
  string WITHOUT throwing on fn values. Keywords use `ns/name` when
  namespaced, plain `name` otherwise; non-keywords fall through to `str`.
  Inlined fn guards / actions surface their `:name` meta (named
  `(fn name [...] ...)` / `(defn ...)`) or `\"fn\"` when anonymous — so the
  label reads cleanly instead of `#object[Function]`.

  The shared fn-tolerant renderer (chart `name-of`, mermaid
  `keyword-label`/`label-value`, SCXML `ref->label` all collapse here). A
  `nil` ref renders `nil`."
  [v]
  (cond
    (nil? v)     nil
    (keyword? v) (if-let [n (namespace v)]
                   (str n "/" (name v))
                   (name v))
    (fn? v)      (or (some-> v meta :name str) "fn")
    :else        (str v)))

;; ---------------------------------------------------------------------------
;; Injective id codec — the correctness-critical scheme
;; ---------------------------------------------------------------------------

(defn escape-id-segment
  "Sanitise one path segment into an id-safe string INJECTIVELY — distinct
  inputs always yield distinct outputs.

  The naive `str/replace #\"[^a-zA-Z0-9_]\" \"_\"` collapses `:a/b`,
  `:a-b`, `:a_b` all to `\"a_b\"` (rf2-ee38b.21 P2 / rf2-mnp93.1/.6): a
  collision drops one node + makes every edge addressing either ambiguous.
  Hyphens are pervasive in re-frame keywords (`:logged-in`,
  `:rate-limited`), so the collision is reachable.

  Scheme: every char outside `[A-Za-z0-9]` becomes a FIXED-WIDTH,
  self-delimiting escape of its UTF-16 code unit —

    - `≤ U+00FF`  → `_<2-hex>`  (the underscore itself → `_5f`)
    - `> U+00FF`  → `_u<4-hex>` (CJK, emoji surrogate halves, …)

  Both forms are fixed-width, so the codec is REVERSIBLE across the whole
  code-unit range and no two distinct inputs share an encoding (rf2-qgtcvy:
  the pre-fix `_<var-hex>` was neither self-delimiting nor reversible above
  0xFF — `:开始` mis-decoded and `đ` collided with `\\u0011`+`\"1\"`). The `u`
  sentinel (not a hex digit) keeps the two widths unambiguous: `_2f` is
  always a 2-hex escape, `_u5f00` always a 4-hex one, so `_2f00` reads as
  `/`+`00`, never as one wide escape.

  Neither form ever mints two consecutive underscores (a `_` is always
  followed by a hex digit or `u`), and no `-` / `.` survives unescaped (a
  literal `-` → `_2d`, `.` → `_2e`), so the reserved markers the consumers
  layer on top — `_2f` (the hex of `/`) for a keyword's ns/name boundary,
  `__` between path segments (xyflow `node-id` / mermaid `sanitise-id`),
  `-` (SCXML ns-name) / `___` (SCXML path) separators — can never arise
  from segment content. This is the SINGLE injective scheme all three
  emitters' id codecs build on, so they address every node identically."
  [s]
  (str/join
    (map (fn [ch]
           (if (re-matches #"[A-Za-z0-9]" (str ch))
             (str ch)
             (let [cu #?(:clj  (int ch)
                         :cljs (.charCodeAt (str ch) 0))]
               (if (<= cu 0xff)
                 (str "_" #?(:clj  (format "%02x" cu)
                             :cljs (let [h (.toString cu 16)]
                                     (if (= 1 (count h)) (str "0" h) h))))
                 (str "_u" #?(:clj  (format "%04x" cu)
                              :cljs (let [h (.toString cu 16)]
                                      (str (subs "0000" (count h)) h))))))))
         s)))
