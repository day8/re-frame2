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
            [re-frame.core :as rf]))

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

  STRUCTURE IS PRESERVED (the headline guarantee): the node KEYS (the
  canonical family-tagged node ids) and the `:edges` vector ride through
  UNTOUCHED — a redacted param is still an `:input` / `:param` edge, the
  node is still present and still classified by its `:kind` superkind. Only
  the value-bearing leaf fields inside node bodies are redacted. The
  storage / evaluation / lifecycle classifications, `:source-form`,
  `:refinement`, `:inputs` topology, and `:output` address are structure,
  not values, and are never walked.

  `frame-id` is the frame whose elision policy governs egress (the observed
  app's frame — typically the graph's `:frame` for a live graph). `opts`
  (optional) ride through to `rf/elide-wire-value` (e.g.
  `:rf.size/threshold-bytes`); the `:frame` opt is set from `frame-id` and
  overrides any caller-supplied one (egress redacts under the OBSERVED
  frame's policy, never a borrowed one).

  FAIL-CLOSED on an UNREACHABLE frame: `rf/elide-wire-value` treats a
  carried `:frame` opt as a KNOWN frame even when that id names no live
  frame — applying an empty (identity) policy, which would ship the value
  RAW under no policy. Egress must not do that. So this projection first
  checks the named frame is LIVE (`rf/frame-ids`); when it is not (nil id,
  a destroyed / never-registered frame), it walks WITHOUT a `:frame` opt so
  `rf/elide-wire-value` takes its own frameless fail-closed branch and
  redacts the whole value to `:rf/redacted` rather than borrow another
  frame's marks. This is the silent-leak this contract abolishes — a graph
  egressing under no reachable policy redacts, never ships raw.

  Returns the graph with redacted node value fields; `:mode` / `:frame` /
  `:nodes` keys / `:edges` unchanged. Idempotent over `:rf/redacted`
  (re-walking an already-redacted value is a no-op — the sentinel is a
  non-matchable scalar)."
  ([graph frame-id] (redact-graph-for-egress graph frame-id nil))
  ([graph frame-id opts]
   ;; A reachable (live) frame governs egress under its own policy; an
   ;; unreachable / nil frame walks frameless so the walker fails closed.
   (let [reachable? (and (some? frame-id) (contains? (rf/frame-ids) frame-id))
         walk-opts  (if reachable? (assoc opts :frame frame-id) opts)
         redact-node
         (fn [node]
           (reduce
            (fn [n k]
              (if (contains? n k)
                (assoc n k (rf/elide-wire-value (get n k) walk-opts))
                n))
            node
            value-bearing-node-keys))]
     (update graph :nodes update-vals redact-node))))

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
