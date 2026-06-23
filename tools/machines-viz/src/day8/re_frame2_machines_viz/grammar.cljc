(ns day8.re-frame2-machines-viz.grammar
  "Shared, PURE grammar walker + injective id codec for the three
  machines-viz emitters (chart / mermaid / SCXML) — rf2-b2ygd2.

  ## Why this ns exists

  The three emitters each project a re-frame2 machine definition (Spec
  005 §Transition table grammar) into a different surface — the chart's
  xyflow node/edge graph, a Mermaid `stateDiagram-v2` string, and a W3C
  SCXML document. To stay faithful to one another (001-Topology-Parity.md
  §3.1 G9 — 'faithful across all three emitters'), they MUST address every
  node by the SAME injective id and walk the transition grammar the SAME
  way. Pre-rf2-b2ygd2 the walker + codec were HAND-COPIED into all three
  emitters: `target-path?`, `parent-path`, `transition-candidates`,
  `history-node?`, `reenter?`, `escape-id-segment` (the
  correctness-critical injective scheme), the fn-tolerant name renderer,
  and `normalise-root-targets` each appeared three times. Three copies =
  three chances to drift; a drift in the escape codec silently re-wires
  every edge in ONE emitter relative to the others.

  This ns is the SINGLE SOURCE OF TRUTH. The three emitters route through
  it so the codec + walker can never desync.

  ## What stays SEPARATE (genuinely-distinct SCXML variants)

  Two SCXML walkers are NOT collapsed in here — they implement a
  deliberately different grammar and must stay in `scxml.cljc`:

  - `scxml/root-transition-candidates` — root-grammar-aware: a
    vector-of-VECTORS (`[[:a :x] [:b :y]]`) is a SINGLE multi-region
    target, NOT a candidate fork (the generic `transition-candidates`
    below would mis-split it).
  - the SCXML decode side (`unescape-id-segment`, `decode-target`, …) —
    the INVERSE codec, used only on import.

  Per `tools/machines-viz/spec/*`: the cross-emitter agreement +
  injective id codec contracts are described there; this ns is the pure
  internal implementation those contracts already assume."
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
  - a vector of specs — multiple candidates (first-match wins at runtime;
    every target-bearing branch surfaces);
  - anything else (e.g. an inline fn) — dropped (cannot statically
    resolve).

  The SINGLE shared walker the chart + mermaid + the per-state SCXML
  emitters all use. NOTE the root-parallel SCXML emitter uses its OWN
  `scxml/root-transition-candidates` instead — a vector-of-vectors there
  is one multi-region target, not a candidate fork."
  [spec]
  (cond
    (keyword? spec)     [{:target spec}]
    (target-path? spec) [{:target spec}]
    (map? spec)         [spec]
    (vector? spec)      (mapcat transition-candidates spec)
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
  (when states
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
        (:states def')  (update :states walk-states-timeouts)
        (:regions def') (update :regions
                                (fn [regions]
                                  (reduce-kv
                                    (fn [acc rn body]
                                      (assoc acc rn
                                             (cond-> (desugar-node-timeouts (dissoc body :states))
                                               (:states body)
                                               (assoc :states (walk-states-timeouts (:states body))))))
                                    {} regions)))))))

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

  Scheme: every char outside `[A-Za-z0-9]` becomes `_<2-hex>` (the
  underscore itself included → `_5f`), so the mapping is reversible and no
  two distinct chars share an encoding. Because the result contains no two
  consecutive underscores, the reserved markers the consumers layer on top
  — `_2f` (the hex of `/`) for a keyword's ns/name boundary, `__` between
  path segments (xyflow `node-id` / mermaid `sanitise-id`), `__`/`___`
  (SCXML ns-name / path separators) — can never arise from segment
  content. This is the SINGLE injective scheme all three emitters' id
  codecs build on, so they address every node identically."
  [s]
  (str/join
    (map (fn [ch]
           (if (re-matches #"[A-Za-z0-9]" (str ch))
             (str ch)
             (str "_" #?(:clj  (format "%02x" (int ch))
                         :cljs (let [h (.toString (.charCodeAt (str ch) 0) 16)]
                                 (if (= 1 (count h)) (str "0" h) h))))))
         s)))
