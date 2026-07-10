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
  the frame's `elide-wire-value` walker under the FRAME's own elision policy
  (per-frame, fail-closed when frameless), and identity-embedded resource
  scope/params are opaqued. Redaction MUST NOT lose graph STRUCTURE — a
  redacted param is still an edge; the node is still present + classified.
  The composer itself stays RAW-ON-BOX by design; this projection is the
  consuming tool's egress obligation, not the composer's.

  The redaction ALGORITHM itself is OWNED by the bundle-isolated core tooling
  ns `re-frame.derivation.egress` (rf2-mm3y49) — `redact-graph-for-egress`
  is a thin DELEGATE to `egress/project-graph`, so this call site and the
  derivation-conformance suite share ONE implementation rather than drifting
  copies. This panel remains the named CALL SITE; the projection lives in
  core (built from `implementation/`-resident primitives only, so no
  `tools/` → `implementation/` dependency inversion).

  JVM-portable (`.cljc`) so the projection + redaction contracts are pinned
  by the JVM test corpus without a CLJS runtime."
  (:require [clojure.string :as str]
            ;; Off-box egress redaction is owned by the bundle-isolated core
            ;; tooling ns `re-frame.derivation.egress` (rf2-mm3y49); this panel
            ;; DELEGATES to it. Xray is a dev tool, so reaching an
            ;; implementation/ ns preserves the tools → implementation
            ;; dependency arrow (nothing in implementation/ requires Xray).
            [re-frame.derivation.egress :as egress]))

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

;; The value-bearing summary fields a LIVE node may carry (the `:value` /
;; `:params` / `:query` / `:state` summaries [Derivations.md] §Redaction
;; metadata names as egress-bearing off-box). ON-BOX `summarize-node` bounds
;; them for display; OFF-BOX `redact-graph-for-egress` walks them through the
;; frame's elision policy. ONE source of truth: the list is owned by the
;; core egress algorithm ns (rf2-mm3y49) and aliased here for the on-box
;; summary path so the two never drift.
(def value-bearing-node-keys egress/value-bearing-node-keys)

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
;; OFF-BOX EGRESS REDACTION (rf2-yjarv6, centralized rf2-mm3y49).
;;
;; The redaction ALGORITHM — scoped-key / work-id / host-transient /
;; resource-node / edge-endpoint / dead-frame fail-closed / opaque-handle /
;; idempotent whole-graph projection — is OWNED by the bundle-isolated core
;; tooling ns `re-frame.derivation.egress`. It previously existed as two
;; drifting copies (this call site + the derivation-conformance suite's
;; in-tree mirror); a fix had to land in both and Xray lagged. Both now
;; DELEGATE to the one owner (rf2-mm3y49). This panel is still the EGRESS
;; CALL SITE the EP-0014 tail-2 ruling names — the wire boundary where a tool
;; ships the graph OFF the developer's box (an MCP surface streaming to a
;; remote agent, a serialized capture written to disk / posted to a service)
;; — but the projection itself is `egress/project-graph`.
;; ===========================================================================

(def redact-graph-for-egress
  "Project a `DerivationGraph` through the observed FRAME's egress policy for
  the off-box wire boundary — a thin DELEGATE to the core-owned algorithm
  `re-frame.derivation.egress/project-graph` (rf2-mm3y49). See that ns for
  the full contract: per-frame `elide-wire-value` value redaction; dead-frame
  fail-closed (never borrowing an ambient frame and shipping raw); stable
  opaque live-resource-identity handles across every identity position (node
  key, `:id`, `:output`, realized `:inputs`, `:work-ledger` work-id +
  `:resource/key`, `:host-transient`) and every edge endpoint; structure
  preservation (a redacted param is still an edge); and idempotence.
  `([graph frame-id] [graph frame-id opts])` — `opts` ride through to
  `elide-wire-value`; the `:frame` opt is set from `frame-id`."
  egress/project-graph)
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
