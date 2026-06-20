(ns day8.re-frame2-xray.panels.derivation-graph-helpers
  "Pure-data projection algebra for the Derivation-Graph panel (EP-0014
  prop-3, rf2-9ett2d) + the OFF-BOX EGRESS REDACTION call site (rf2-yjarv6).

  ## What this panel renders

  The composer `re-frame.derivation.graph` (EP-0014 slice-7) assembles the
  five algebra-view siblings (subs / flows / resources / routes / machines)
  into ONE `{:mode :nodes :edges}` `DerivationGraph` view — the unified
  derivation/process graph the EP names. Xray is the NAMED FIRST CONSUMER
  ([Derivations.md] §Graph inspection; [EP-0014 §Reference Implementation /
  Bead Plan] item 7). This panel is that consumption made real: a single
  graph view that answers \"where does this fact come from, when is it
  evaluated, where does it live, and who owns it?\" across all families at
  once — even though the underlying runtime mechanisms are subscription
  cache, flow registry, route slice, resource cache, and machine snapshots.

  These helpers are the pure-data projection layer (the view-side hiccup
  lives in `derivation_graph.cljs`): they classify each node by the TWO
  closed superkinds, group nodes by family, summarize value-bearing fields
  for on-box display, and — the rider task — project the graph through the
  frame's egress policy when a tool ships it OFF-BOX.

  ## The two superkinds are the contract (EP-0014 §Algebra Declaration Shape)

  `:kind` is one of exactly two closed superkinds — `:derivation` or
  `:process` (the graduated, closed `DerivationKind` enum). A tool MUST be
  able to classify EVERY node by reading `:kind` alone. The refined kinds
  (`:resource-process`, `:route-fact`, `:machine-process`,
  `:machine-selector`) ride the separate `:refinement` axis and are COLOUR,
  NOT CONTRACT — this panel colours by `:refinement` for legibility but
  groups + classifies by `:kind`. A node carrying an unknown future
  refinement still classifies correctly off its superkind.

  ## ON-BOX rendering is RAW (Security.md permits on-box)

  The panel renders in the developer's own browser, in the `:rf/xray`
  frame, against the developer's own app. On-box inspection sees raw values
  — that is the in-process truth (the TAIL-2 correctness ruling on
  rf2-6y7wnb: raw-on-box is correct-as-designed for read-only projections;
  the composer composes nodes verbatim by design and does NO redaction).
  So `summarize-graph` produces bounded, render-safe PREVIEWS purely for
  display ergonomics (a 4MB value would wreck the panel), NOT for privacy —
  it is a size/shape projection, not an egress boundary.

  ## OFF-BOX egress is REDACTED, per-frame, FAIL-CLOSED (rf2-yjarv6)

  `redact-graph-for-egress` is the EGRESS REDACTION CALL SITE the EP-0014
  tail-2 redaction ruling says is BORN HERE — the wire boundary where a
  tool ships the graph OFF the developer's box (an MCP surface streaming the
  graph to a remote agent, a serialized capture written to disk / posted to
  a service). Per [Derivations.md] §Redaction metadata and the EP-0014
  issue-1 disposition, the graph SHOULD be useful WITHOUT exposing sensitive
  raw values: each node's value-bearing summary fields are projected through
  the single shared `rf/elide-wire-value` walker
  ([009 §Privacy], [015-Data-Classification], [Managed-Effects §5]) under
  the FRAME's own elision policy (`re-frame.elision`, per-frame, fail-closed
  when frameless). Redaction MUST NOT lose graph STRUCTURE — a redacted
  param is still an edge; the node is still present + classified. The
  composer itself stays RAW-ON-BOX by design; this projection is the
  consuming tool's egress obligation, not the composer's.

  JVM-portable (`.cljc`) so the projection + redaction contracts are pinned
  by the JVM test corpus without a CLJS runtime."
  (:require [clojure.string :as str]
            [re-frame.core :as rf]
            ;; Xray is a bundle-isolated dev tool (nothing in implementation/
            ;; `:require`s here), so it may reach the core CEDN-1 identity
            ;; primitive directly to mint the STABLE opaque handles the live
            ;; resource scoped-key egress projection uses (rf2-k0meap.1).
            [re-frame.identity :as identity]))

;; ---------------------------------------------------------------------------
;; Superkind classification (the contract axis).
;; ---------------------------------------------------------------------------

(defn superkind
  "The node's CLOSED superkind — `:derivation` | `:process` — read off
  `:kind` alone (EP-0014 §Algebra Declaration Shape: a tool MUST classify
  every node knowing only the two superkinds). Returns `:unknown` for a
  malformed node missing `:kind` so the panel degrades to an inert row
  rather than throwing."
  [node]
  (case (:kind node)
    :derivation :derivation
    :process    :process
    :unknown))

(defn process?    [node] (= :process    (superkind node)))
(defn derivation? [node] (= :derivation (superkind node)))

;; ---------------------------------------------------------------------------
;; Family grouping (the EDITORIAL axis — colour, not contract).
;;
;; The node id is family-tagged by the composer (`node-id`): `[:sub …]`,
;; `[:flow …]`, `[:resource …]`, `[:machine …]`, or the route fact id
;; `:rf/route` / `[:rf/route <route-id>]`. We read the tag to bucket nodes
;; into the five families for the grouped render — falling back to the
;; verbatim `:rf/family` tag the composer stamps on every node when the id
;; tag is ambiguous.
;; ---------------------------------------------------------------------------

(def families
  "Editorial render order — the canonical reading order of
  [Derivations.md] (subscriptions → flows → resources → routes →
  machines), mirroring `re-frame.derivation.graph/families`."
  [:subs :flows :resources :routes :machines])

(defn node-family
  "Bucket one node into a render family. Prefers the composer-stamped
  `:rf/family` tag (authoritative — set on every node in
  `family-static-nodes` / `family-live-nodes`); falls back to inferring
  from the node id tag for a hand-built fixture node that omits it."
  [node-id node]
  (or (:rf/family node)
      (cond
        (and (vector? node-id) (= :sub      (first node-id))) :subs
        (and (vector? node-id) (= :flow     (first node-id))) :flows
        (and (vector? node-id) (= :resource (first node-id))) :resources
        (and (vector? node-id) (= :machine  (first node-id))) :machines
        (= :rf/route node-id)                                 :routes
        (and (vector? node-id) (= :rf/route  (first node-id))) :routes
        :else :subs)))

(defn group-by-family
  "Partition the graph's `:nodes` map into `{family [[node-id node] …]}`,
  each family's entries sorted by a stable string key so the render order
  is deterministic across re-renders. Families with no nodes are absent
  from the result."
  [{:keys [nodes]}]
  (->> nodes
       (group-by (fn [[node-id node]] (node-family node-id node)))
       (reduce-kv
        (fn [acc family entries]
          (assoc acc family
                 (vec (sort-by (fn [[node-id _]] (pr-str node-id)) entries))))
        {})))

;; ---------------------------------------------------------------------------
;; Edge classification.
;; ---------------------------------------------------------------------------

(def edge-roles
  "The edge roles the composer emits (Derivations §Graph inspection):
  `:input` (a `[:sub q]` declared input), `:param` (a route-owned resource
  activation), `:selector` (a machine → its selector subscription). Drives
  the per-role legend + edge colour."
  [:input :param :selector])

(defn edges-by-role
  "Group the graph's `:edges` vector by `:role` → `[edge …]`. Roles absent
  from the graph are absent from the result."
  [{:keys [edges]}]
  (group-by :role edges))

(defn node-degree
  "Count `{:in n :out n}` undirected degree per node id across the edge
  set, for the summary header (\"42 nodes · 17 edges\" + the most-connected
  node). A node id absent from any edge has zero degree."
  [{:keys [nodes edges]}]
  (reduce
   (fn [acc {:keys [from to]}]
     (-> acc
         (update-in [from :out] (fnil inc 0))
         (update-in [to :in] (fnil inc 0))))
   (zipmap (keys nodes) (repeat {:in 0 :out 0}))
   edges))

;; ---------------------------------------------------------------------------
;; ON-BOX value summarization (size/shape projection — NOT an egress boundary).
;;
;; A render-safe preview of any value: type tag, bounded size, short printed
;; preview. This protects the PANEL from a 4MB value, not the user's privacy
;; — the on-box panel is entitled to the raw value (Security.md permits
;; on-box; the redacted `:rf/redacted` sentinel a frame-policy walk produced
;; off-box is itself just a keyword and previews cleanly through here).
;; ---------------------------------------------------------------------------

(def ^:private preview-limit 80)

(defn- value-type [v]
  (cond
    (map? v)        :map
    (vector? v)     :vector
    (set? v)        :set
    (sequential? v) :seq
    (string? v)     :string
    (keyword? v)    :keyword
    (nil? v)        :nil
    :else           :scalar))

(defn summarize
  "A bounded, render-safe summary of `v` for ON-BOX display: `{:type :size
  :preview :redacted?}`. `:size` is the element count for collections, nil
  otherwise. `:preview` is the printed value truncated to `preview-limit`.
  `:redacted?` flags the `:rf/redacted` sentinel (so a value that arrived
  already redacted — e.g. a value the egress walk redacted, then fed back —
  renders muted). Pure size/shape projection: it does NOT consult any
  elision policy (that is `redact-graph-for-egress`'s job)."
  [v]
  (let [redacted? (= :rf/redacted v)
        printed   (pr-str v)
        truncated (if (> (count printed) preview-limit)
                    (str (subs printed 0 preview-limit) "…")
                    printed)]
    (cond-> {:type      (value-type v)
             :preview   truncated
             :redacted? redacted?}
      (coll? v) (assoc :size (count v)))))

;; The value-bearing summary fields a LIVE node may carry — the value
;; summaries [Derivations.md] §Redaction metadata names as egress-bearing
;; once shipped off-box: a sub-cache entry's `:value`, a route slice's
;; `:params` / `:query`, a machine instance's `:state` summary, a resource
;; entry's `:work-ledger` summary. (Each is the in-process value summary the
;; sibling lives surface; the composer carries them verbatim.) Node IDs,
;; edges, and the storage/evaluation/lifecycle classifications are STRUCTURE
;; and are never touched.
(def value-bearing-node-keys
  [:value :params :query :state])

(defn summarize-node
  "Attach an ON-BOX `:summaries {<k> <summary>}` map to a node for any
  value-bearing key it carries — the bounded previews the panel renders.
  Leaves the node otherwise untouched (its `:kind` / `:refinement` /
  `:inputs` / `:output` / classifications ride through verbatim)."
  [node]
  (let [present (filter #(contains? node %) value-bearing-node-keys)]
    (if (seq present)
      (assoc node :summaries
             (into {} (map (fn [k] [k (summarize (get node k))])) present))
      node)))

(defn summarize-graph
  "Map `summarize-node` over the graph's `:nodes`, returning the graph with
  each node carrying its ON-BOX `:summaries`. The on-box display projection
  — raw-permitting, size-bounding. NOT the egress boundary."
  [graph]
  (update graph :nodes update-vals summarize-node))

;; ===========================================================================
;; OFF-BOX EGRESS REDACTION (rf2-yjarv6) — the call site the EP-0014 tail-2
;; redaction ruling says is BORN HERE.
;; ===========================================================================

;; ---------------------------------------------------------------------------
;; LIVE RESOURCE IDENTITY redaction (rf2-k0meap.1).
;;
;; The `rf/elide-wire-value` value-field walk above redacts a sensitive
;; VALUE sitting at a node's `:value` / `:params` / `:query` / `:state`
;; summary — it matches by the FRAME's declared `:sensitive` `:app-db`
;; PATH. But a LIVE resource node carries its sensitive scope/params in a
;; place that walk can NEVER reach: the concrete SCOPED KEY
;; `[cache-scope resource-id canonical-params]` is the node's IDENTITY —
;; it is the node KEY (`[:resource <scoped-key>]`), the node's `:id`, it is
;; embedded in the `:output` runtime path, in the `:work-ledger :record
;; :resource/key`, and a route-owned activation surfaces it as the `:to`
;; end of a `:param` edge. These are STRUCTURE positions, not value-bearing
;; leaves — and the param/scope they carry are exactly the "sensitive
;; params/scopes" [Derivations.md] §Redaction metadata names as
;; egress-bearing.
;;
;; A value-path walk is structurally blind to identity-embedded secrets, so
;; egress must project the scoped-key's secret-bearing components into
;; STABLE OPAQUE HANDLES: the same scoped key always maps to the same
;; projected key (graph CONNECTIVITY survives — a redacted param is still an
;; edge), but the raw scope/params never cross the wire. We mint the handle
;; from the core CEDN-1 identity primitive (`identity/canonical-bytes`) so
;; it is deterministic for a given value; a value outside the CEDN-1 domain
;; (or any error) FAILS CLOSED to the `:rf/redacted` sentinel rather than
;; risk shipping a host-stringified secret. The middle `resource-id` (a
;; registration keyword, never sensitive) is PRESERVED so a tool still sees
;; WHICH resource the node is.

(defn scoped-resource-key?
  "True when `v` is a live resource SCOPED KEY — a 3-tuple
  `[cache-scope resource-id canonical-params]` (the resource's concrete
  live fact identity, [Derivations.md] §Fact identity): the MIDDLE element
  is the registration `resource-id` keyword and the LAST is the
  canonical-params MAP (resource params are validated by a `[:map …]`
  `:params-schema`, so the concrete params are always a map). Requiring the
  params map is what keeps this from misfiring on an unrelated 3-vector —
  e.g. the `:output` runtime path `[:rf.runtime/resources :entries …]` has
  a non-map last element. A static resource node's `:id` is a bare keyword
  (not this shape), so only LIVE resource identities match. Already-projected
  handles re-match (the projected scoped key keeps the 3-tuple-with-map-tail
  shape), so re-projection is well-defined."
  [v]
  (and (vector? v)
       (= 3 (count v))
       (keyword? (nth v 1))
       (map? (nth v 2))))

(defn- already-projected?
  "True when `v` is ALREADY an egress-projected handle — an opaque
  `[:rf.resource/opaque <digest>]` token or the `:rf/redacted` fail-closed
  sentinel. Re-projecting such a value MUST be the identity (idempotence): a
  forwarder pipeline may run egress more than once (re-egress on re-render /
  re-subscribe / cascade), and hashing an already-projected handle would mint
  a NEW, different handle — silently changing the live node's identity across
  the boundary and breaking stable graph connectivity."
  [v]
  (or (= v :rf/redacted)
      (and (vector? v)
           (= 2 (count v))
           (= :rf.resource/opaque (nth v 0)))))

(defn- opaque-handle
  "A STABLE, ONE-WAY opaque handle for one secret-bearing scoped-key
  component. Deterministic from the value (same value ⇒ same handle, so
  graph connectivity survives) but IRREVERSIBLE — it is a HASH of the value's
  CEDN-1 canonical token, NOT the token itself (the token PRESERVES the raw
  value, which would defeat redaction). FAILS CLOSED to the `:rf/redacted`
  sentinel for any value outside the CEDN-1 identity domain or on any error
  (never host-stringify a secret onto the wire). IDEMPOTENT: an already-
  projected `[:rf.resource/opaque …]` handle / `:rf/redacted` sentinel is
  returned UNCHANGED (hashing it again would mint a fresh, DIFFERENT handle
  and silently change the projected identity on a second egress pass)."
  [v]
  (if (already-projected? v)
    v
    (try
      ;; `canonical-bytes` is the deterministic CEDN-1 token (stable for a
      ;; given value, cross-spelling-invariant); `hash` makes it one-way so the
      ;; raw scope/params cannot be read back off the wire. Render unsigned hex
      ;; so the handle is a compact, non-reversible, value-stable token.
      (let [token  (identity/canonical-bytes v)
            digest #?(:clj  (Integer/toHexString (hash token))
                      :cljs (.toString (bit-and (hash token) 0xffffffff) 16))]
        [:rf.resource/opaque digest])
      (catch #?(:clj Throwable :cljs :default) _
        :rf/redacted))))

(defn- project-scoped-key
  "Project a live resource scoped key `[scope resource-id params]` into its
  egress form `[<scope-handle> resource-id <params-handle>]` — the scope and
  params replaced by stable opaque handles, the registration `resource-id`
  preserved. Idempotent: a component already shaped `[:rf.resource/opaque …]`
  / `:rf/redacted` re-projects to itself (the handle of a handle is stable,
  and `:rf/redacted` is a non-secret scalar)."
  [[scope resource-id params]]
  [(opaque-handle scope) resource-id (opaque-handle params)])

(defn- redact-resource-identity-in-path
  "Replace any live resource scoped key embedded in `path` (e.g. the
  `:output` runtime path `[:runtime [:rf.runtime/resources :entries
  <scoped-key>]]`) with its projected form, so the secret-bearing scoped key
  never egresses through a structure position. Walks the path vector and
  projects any element that IS a scoped key."
  [path]
  (if (sequential? path)
    (mapv (fn [el]
            (cond
              (scoped-resource-key? el) (project-scoped-key el)
              (sequential? el)          (redact-resource-identity-in-path el)
              :else                     el))
          path)
    path))

(defn- redact-resource-inputs
  "Project the live resource node's realized `:inputs`
  `[[:scope <scope>] [:param <params>]]` — opaque the `[:scope …]` /
  `[:param …]` payloads (the realized scope + params edges carry the same
  sensitive identity). Other input shapes ride through untouched."
  [inputs]
  (if (sequential? inputs)
    (mapv (fn [in]
            (if (and (vector? in) (= 2 (count in))
                     (#{:scope :param} (first in)))
              [(first in) (opaque-handle (second in))]
              in))
          inputs)
    inputs))

(def ^:private dead-frame-sentinel
  "A frame id that can NEVER resolve to a live frame, used to FAIL CLOSED a
  nil / unreachable egress frame WITHOUT borrowing the ambient scope.

  `rf/elide-wire-value` resolves its governing frame as `(or (:frame opts)
  (frame/resolve-current-frame))` — so a nil `:frame` opt (or no `:frame` at
  all) falls through to the AMBIENT dynamically-bound frame, applying that
  frame's (possibly empty) policy and shipping value-bearing fields RAW. An
  unreachable but NON-nil `:frame` opt instead takes the unresolvable-frame
  fail-closed branch (`frame/frame` returns nil ⇒ whole value redacted to
  `:rf/redacted`). We therefore stamp this sentinel as the `:frame` opt when
  no live governing frame is known, so the walker fails closed on its own
  dead-frame branch rather than resolving an ambient frame. The keyword is
  namespaced into this panel so it can never collide with a real frame id."
  ::no-egress-frame)

(defn- redact-resource-node-identity
  "Project ONE live resource node's secret-bearing identity fields:
  `:id` (the scoped key), `:output` (the scoped key embedded in the runtime
  path), the realized `:inputs` `[:scope …]` / `[:param …]` payloads, and
  `:work-ledger :record :resource/key` (the scoped key on the work-ledger
  summary). Structure / classification fields are untouched."
  [node]
  (cond-> node
    (scoped-resource-key? (:id node))
    (update :id project-scoped-key)

    (contains? node :output)
    (update :output redact-resource-identity-in-path)

    (contains? node :inputs)
    (update :inputs redact-resource-inputs)

    (scoped-resource-key? (get-in node [:work-ledger :record :resource/key]))
    (update-in [:work-ledger :record :resource/key] project-scoped-key)))

(defn- resource-node-key?
  "True when `node-key` is a LIVE resource node id `[:resource <scoped-key>]`
  whose scoped key embeds a secret-bearing scope/params. A static resource
  node is `[:resource <bare-keyword>]`, which does NOT match."
  [node-key]
  (and (vector? node-key)
       (= :resource (first node-key))
       (scoped-resource-key? (second node-key))))

(defn- project-resource-node-key
  "Project a live resource node KEY `[:resource <scoped-key>]` to
  `[:resource <projected-scoped-key>]`; other node keys ride through."
  [node-key]
  (if (resource-node-key? node-key)
    [:resource (project-scoped-key (second node-key))]
    node-key))

(defn- redact-resource-edge-endpoints
  "Remap any `:from` / `:to` edge endpoint that names a live resource node
  key to the projected key, so an edge naming a redacted resource node still
  CONNECTS to it (structure preserved — the same projection applied to keys
  applies to endpoints, so the remap stays consistent)."
  [edges]
  (mapv (fn [edge]
          (cond-> edge
            (resource-node-key? (:from edge)) (update :from project-resource-node-key)
            (resource-node-key? (:to edge))   (update :to project-resource-node-key)))
        edges))

(defn redact-graph-for-egress
  "Project a `DerivationGraph` through the FRAME's egress policy for the
  wire boundary where a tool ships the graph OFF-BOX (rf2-yjarv6;
  [Derivations.md] §Redaction metadata; EP-0014 issue-1 disposition).

  This is the egress redaction CALL SITE the tail-2 ruling locates in the
  consuming tool — NOT in the registrar-derived composer (which composes
  nodes verbatim, raw-on-box by design). Each node's value-bearing summary
  field (`:value` / `:params` / `:query` / `:state` —
  `value-bearing-node-keys`) is walked through the single shared
  `rf/elide-wire-value` walker under the named `frame-id`'s own elision
  policy:

  - **per-frame** — the policy is the FRAME's declared `:sensitive` /
    `:large` `:app-db` classifications (`re-frame.elision`, sourced from
    `reg-frame`), passed explicitly as the `:frame` opt so the walk applies
    THAT frame's policy regardless of any ambient scope;
  - **fail-closed** — `rf/elide-wire-value` redacts the whole value to the
    `:rf/redacted` sentinel when no frame is reachable (frameless egress
    under no `:rf.size/include-sensitive?` opt-out); a sensitive-declared
    value is replaced by the sentinel; a large-declared value by the
    `:rf.size/large-elided` marker.

  STRUCTURE IS PRESERVED (the headline guarantee): a redacted value / param
  is still an `:input` / `:param` edge, the node is still present and still
  classified by its `:kind` superkind. The storage / evaluation / lifecycle
  classifications, `:source-form`, and `:refinement` are structure, not
  values, and are never touched.

  LIVE RESOURCE IDENTITY redaction (rf2-k0meap.1). A live resource node
  carries its sensitive scope/params NOT in a value-bearing field but in its
  IDENTITY — the concrete scoped key `[cache-scope resource-id
  canonical-params]` that is the node KEY (`[:resource <scoped-key>]`), the
  node's `:id`, and is embedded in the `:output` runtime path, the realized
  `:inputs` `[:scope …]` / `[:param …]` edges, and the `:work-ledger :record
  :resource/key`. The `rf/elide-wire-value` value-path walk is structurally
  BLIND to these identity-embedded secrets. So this projection ALSO replaces
  each scoped key's secret-bearing scope + params with STABLE OPAQUE HANDLES
  (`identity/canonical-bytes`-derived, fail-closed to `:rf/redacted` outside
  the CEDN-1 domain), preserving the registration `resource-id` so a tool
  still sees WHICH resource the node is. The SAME projection is applied to
  the `:nodes` keys AND every edge endpoint that names a resource node, so
  the remap stays CONSISTENT — a redacted resource node is still a node, and
  the edges naming it still connect (connectivity survives, the raw
  scope/params never cross the wire).

  `frame-id` is the frame whose elision policy governs egress (the observed
  app's frame — typically the graph's `:frame` for a live graph). `opts`
  (optional) ride through to `rf/elide-wire-value` (e.g.
  `:rf.size/threshold-bytes`); the `:frame` opt is set from `frame-id` and
  overrides any caller-supplied one (egress redacts under the OBSERVED
  frame's policy, never a borrowed one).

  FAIL-CLOSED on a nil / UNREACHABLE frame: `rf/elide-wire-value` resolves
  its governing frame as `(or (:frame opts) (frame/resolve-current-frame))`,
  so a nil `:frame` opt (or no `:frame` at all) falls through to the AMBIENT
  dynamically-bound frame and would ship value-bearing fields RAW under that
  borrowed frame's (possibly empty) policy. Egress must NOT borrow an ambient
  frame. So this projection first checks the named frame is LIVE
  (`rf/frame-ids`); when it is not (nil id, a destroyed / never-registered
  frame), it stamps a DEAD-FRAME SENTINEL as the `:frame` opt — a non-nil id
  that can never resolve to a live frame — so `rf/elide-wire-value` takes its
  unresolvable-frame fail-closed branch (`frame/frame` returns nil for it)
  and redacts the whole value to `:rf/redacted` rather than borrow the
  ambient frame's marks. This is the silent-leak this contract abolishes — a
  graph egressing under no reachable policy redacts, never ships raw, even
  when an ambient frame is dynamically bound (rf2-udkj69).

  Returns the graph with redacted node value fields + projected live
  resource identities; `:mode` / `:frame` unchanged. IDEMPOTENT — a value may
  egress more than once (re-egress on re-render / re-subscribe / a forwarder
  cascade), and re-projecting an already-projected graph is the IDENTITY:
  `redact-graph-for-egress` ∘ `redact-graph-for-egress` == `redact-graph-for-
  egress` (rf2-g197ep). `rf/elide-wire-value` is a no-op over an already-
  `:rf/redacted` value (the sentinel is a non-matchable scalar), and the
  opaque resource handle is idempotent at the source: `opaque-handle` returns
  an already-`[:rf.resource/opaque …]` handle / `:rf/redacted` sentinel
  UNCHANGED rather than re-hash it into a fresh, different handle. (Pinned by
  `live-resource-identity-projection-is-idempotent` here and the cross-family
  `g-graph-egress-is-idempotent` in derivation-conformance.)"
  ([graph frame-id] (redact-graph-for-egress graph frame-id nil))
  ([graph frame-id opts]
   ;; A reachable (live) frame governs egress under its own policy; a nil /
   ;; unreachable frame stamps the dead-frame sentinel as the `:frame` opt so
   ;; `rf/elide-wire-value` takes its unresolvable-frame FAIL-CLOSED branch
   ;; (whole value ⇒ `:rf/redacted`). We must NOT leave the `:frame` opt
   ;; absent / nil here: that frameless path lets the walker fall through to
   ;; the AMBIENT dynamically-bound frame (`frame/resolve-current-frame`) and
   ;; ship value-bearing fields RAW under that frame's policy — the exact
   ;; ambient-borrow leak this contract abolishes (rf2-udkj69).
   (let [reachable? (and (some? frame-id) (contains? (rf/frame-ids) frame-id))
         walk-opts  (assoc opts :frame (if reachable? frame-id dead-frame-sentinel))
         redact-node
         (fn [node]
           (-> (reduce
                (fn [n k]
                  (if (contains? n k)
                    (assoc n k (rf/elide-wire-value (get n k) walk-opts))
                    n))
                node
                value-bearing-node-keys)
               ;; the live resource scoped-key identity walk (rf2-k0meap.1) —
               ;; the secrets the value-path walk above cannot reach.
               redact-resource-node-identity))]
     (-> graph
         ;; remap node KEYS so a live resource scoped key no longer carries
         ;; raw scope/params in the node id (and the edge endpoints below
         ;; stay consistent with the remapped keys).
         (update :nodes (fn [nodes]
                          (into {}
                                (map (fn [[k node]]
                                       [(project-resource-node-key k)
                                        (redact-node node)]))
                                nodes)))
         (update :edges (fn [edges]
                          (redact-resource-edge-endpoints (or edges []))))))))

;; ---------------------------------------------------------------------------
;; Header summary (counts + family/role tallies for the panel header).
;; ---------------------------------------------------------------------------

(defn graph-summary
  "A compact header summary of the graph: total node / edge counts, the
  per-superkind tally (`{:derivation n :process n}`), the per-family node
  tally, and the per-role edge tally. Pure data; the view renders it as the
  panel's count strip."
  [{:keys [mode nodes edges] :as graph}]
  {:mode          mode
   :node-count    (count nodes)
   :edge-count    (count edges)
   :by-superkind  (frequencies (map (fn [[_ node]] (superkind node)) nodes))
   :by-family     (update-vals (group-by-family graph) count)
   :by-role       (update-vals (edges-by-role graph) count)})

(defn empty-graph?
  "True when the graph has no nodes — the panel renders its silent state
  (host registered nothing in any family, or a production build DCE'd the
  live projections)."
  [{:keys [nodes]}]
  (empty? nodes))

(defn redacted?
  "True when `v` is the `:rf/redacted` sensitive-egress sentinel
  (`re-frame.privacy/redacted-sentinel`) — the shape a sensitive-declared
  value (or a frameless fail-closed walk) produced at egress."
  [v]
  (= :rf/redacted v))

(defn large-elided?
  "True when `v` is the `:rf.size/large-elided` size-elision marker
  (`re-frame.elision/marker?`) — the shape a large-declared value produced
  at egress: structure-preserving (path / bytes / type / handle), value
  withheld."
  [v]
  (and (map? v) (contains? v :rf.size/large-elided)))

;; ---------------------------------------------------------------------------
;; Display labels.
;; ---------------------------------------------------------------------------

(defn node-label
  "A short human label for a node id — the family tag stripped to its
  fact identity for the row heading. `[:sub :cart/total]` → `:cart/total`;
  `[:resource <scoped-key>]` → the printed scoped key; `:rf/route` → the
  route fact id."
  [node-id]
  (cond
    (and (vector? node-id) (= 2 (count node-id))) (pr-str (second node-id))
    :else                                         (pr-str node-id)))

(defn family-label
  "Human label for a render family."
  [family]
  (case family
    :subs      "Subscriptions"
    :flows     "Flows"
    :resources "Resources"
    :routes    "Routes"
    :machines  "Machines"
    (str/capitalize (name family))))
