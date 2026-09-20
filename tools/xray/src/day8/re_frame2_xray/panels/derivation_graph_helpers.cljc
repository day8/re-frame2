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
  the frame's `project-egress` walker under the FRAME's own elision policy
  (per-frame, fail-closed when frameless), and identity-embedded resource
  scope/params are opaqued. Redaction MUST NOT lose graph STRUCTURE — a
  redacted param is still an edge; the node is still present + classified.
  The composer itself stays RAW-ON-BOX by design; this projection is the
  consuming tool's egress obligation, not the composer's.

  The redaction ALGORITHM itself is OWNED by the bundle-isolated core tooling
  ns `re-frame.derivation.egress` (rf2-mm3y49) — `redact-graph-for-egress`
  is a thin DELEGATE to `rf.derivation.egress/project-graph`, so this call site and the
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
            [re-frame.derivation.egress :as rf.derivation.egress]))

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

(def ^:private preview-print-level
  "`*print-level*` bound for the preview print. Every nesting level costs at
  least one opening delimiter, so a value nested deeper than a handful of
  levels cannot contribute anything readable inside `preview-limit`
  characters; past this depth the printer writes `#` instead of descending."
  10)

(defn- printed-as
  "A value whose PRINTED form is exactly the characters `s`.

  The carrier is a SYMBOL because a symbol's printed form IS its name,
  written out verbatim with no quoting and no escaping, on BOTH runtimes —
  Clojure prints one through `print-simple`, ClojureScript's `Symbol` writes
  its own `str` field. Nothing but the printer ever sees one: this ns is the
  only producer, `bound-long-strings` is private, and the value it returns
  goes straight into the `pr-str` in `bounded-pr-str`."
  [s]
  (symbol s))

(defn- print-child
  "`pr-str` an already-bounded child sitting at walk depth `d`, under exactly
  the depth budget the real printer would have had left for it there.

  `*print-level*` counts DOWN as the printer descends, so a value nested `d`
  levels deep is printed with `preview-print-level` minus `d` levels
  remaining — past which it writes `#`, which is what the printer would have
  written in that position anyway. `*print-length*` rides in unchanged from
  `bounded-pr-str`'s binding."
  [x d]
  (binding [*print-level* (- preview-print-level d)]
    (pr-str x)))

(defn- printed-type-prefix
  "The type tag the printer writes BEFORE a collection's opening `{`, or
  `\"\"` when it writes none.

  Only a RECORD has one, and records satisfy `map?` — so a record whose key
  had to be shortened takes the rendered window below, which opened with a
  hard-coded `{` and printed `{:body \"short\", ...` where the printer would
  have written `#my.ns.Rec{:body \"short\", ...`. That drops the type out of
  the operator's first `preview-limit` characters, which are the ones the
  panel shows (rf2-xpitj).

  ASKED OF THE PRINTER rather than reconstructed, because the tag is the
  printer's to spell and the two runtimes spell it from different places:
  Clojure writes `#` plus the HOST CLASS name (`print-method` for
  `IRecord`), while `defrecord` bakes a per-type `pr-open` of `#`, the
  namespace, `.` and the type name into the `IPrintWithWriter` it generates
  (`emit-defrecord` in `cljs/core.cljc`). Neither is portably reachable from
  the value itself.

  BOUNDED, which is the constraint that rules out simply printing the
  record and reading its opening characters: `*print-length*` 0 makes the
  printer emit its opening delimiter, its own `...` marker and its closing
  delimiter and NOTHING between them, on both runtimes — so not one entry
  is printed and no long string is ever materialised. `*print-level*` is
  released because nothing descends, and because a level already spent
  would make the printer write `#` in place of the delimiter we came for;
  `*print-meta*` is silenced so an ambient binding cannot put a metadata
  map's `{` in front of the collection's own."
  [v]
  (if-not (record? v)
    ""
    (let [s (binding [*print-length* 0
                      *print-level*  nil
                      *print-meta*   false]
              (pr-str v))
          i (str/index-of s "{")]
      (if i (subs s 0 i) ""))))

(defn- bound-long-strings
  "Bound the print INPUT: replace every long STRING the print walk can REACH
  with its first `preview-limit` characters (rf2-3hnvn).

  `*print-length*` / `*print-level*` bound the printer's BREADTH and DEPTH
  but neither reaches INSIDE a string, so bounding only a string at the ROOT
  left `{:body <500k-char string>}` handing the whole 500k to `pr-str` on
  every coalesced tick to keep 80 characters — the exact cost the bound
  exists to remove.

  This walk visits only what the printer will actually print: at most
  `preview-limit` entries per level, and no deeper than
  `preview-print-level`, past which the printer writes `#` and never
  descends. So it costs the same order as the print it is bounding, and it
  never realises more of a lazy seq than the print would.

  A bounded VALUE is `assoc`ed back under its own key or index, so the walk
  is type- and identity-preserving wherever it touches one: a record stays a
  record, a sorted map stays sorted, and a value with no long string in reach
  comes back `identical?` — the ordinary tick rebuilds nothing. (A SEQ is
  rebuilt lazily, which prints identically and realises nothing extra.)

  A bounded KEY or SET MEMBER is NEVER put back, because there is no way to
  put one back that preserves the print (rf2-kbo64). The `dissoc`/`assoc`
  and `disj`/`conj` that would do it MOVE the entry — on an array-map, to
  the end — so the preview stops opening where the real print opens; and two
  keys sharing a `preview-limit`-character prefix COLLAPSE into one, which
  SHRINKS the collection and pulls an entry the walk never visited inside
  the print window with its value still unbounded. Such a collection is left
  exactly as it is and the printer is handed the walked window ALREADY
  RENDERED, in the collection's own order, one printed entry per entry,
  behind the collection's own printed type tag — so a RECORD still opens
  with `#my.ns.Rec` rather than a bare `{` (rf2-xpitj, `printed-type-prefix`
  above). So the print is bounded without the caller's collection being
  rebuilt at all, and nothing outside the window can appear in it.

  Truncating to `preview-limit` SOURCE characters cannot change the
  preview: escapes only lengthen, so `preview-limit` source characters
  always print to at least `preview-limit` characters."
  [v depth]
  (let [d    (inc depth)
        walk #(bound-long-strings % d)]
    (cond
      (string? v)
      (if (> (count v) preview-limit) (subs v 0 preview-limit) v)

      ;; Past the depth bound the printer writes `#` without descending, so
      ;; nothing under here can be printed — and nothing under here is walked.
      (>= depth preview-print-level)
      v

      (map? v)
      (let [pairs (mapv (fn [[k vv]] [k vv (walk k) (walk vv)])
                        (take preview-limit v))]
        (if (some (fn [[k _ bk _]] (not (identical? bk k))) pairs)
          ;; A KEY had to be shortened, so the collection is left alone and
          ;; this window is rendered instead — see the docstring: putting a
          ;; shortened key back MOVES its entry and COLLAPSES colliding
          ;; keys, and a collapse drags an unwalked entry into the print
          ;; window with its value still unbounded (rf2-kbo64).
          (printed-as
            (str ;; A RECORD opens with its printed type tag, and the tag is
                 ;; the first thing the operator reads. Rendering the window
                 ;; under a bare `{` erased it (rf2-xpitj); the tag is read
                 ;; back from the printer without printing an entry.
                 (printed-type-prefix v)
                 "{"
                 (str/join ", " (map (fn [[_ _ bk bv]]
                                       (str (print-child bk d) " "
                                            (print-child bv d)))
                                     pairs))
                 ;; The printer's own marker for "more than `*print-length*`
                 ;; of them", so the render does not claim the map is
                 ;; smaller than it is.
                 (when (> (count v) preview-limit) ", ...")
                 "}"))
          ;; Only VALUES moved, so they go straight back under their own
          ;; keys: type, order and `identical?` all survive.
          (reduce (fn [acc [k vv _ bv]]
                    (if (identical? bv vv) acc (assoc acc k bv)))
                  v pairs)))

      (set? v)
      (let [members (vec (take preview-limit v))
            bounded (mapv walk members)]
        (if (every? true? (map identical? members bounded))
          v
          ;; A set member IS its own key, so there is no slot to put a
          ;; shortened one back into — `disj`/`conj` reorders and collapses
          ;; exactly as the map case does. Render the window in the set's
          ;; own order.
          (printed-as
            (str "#{"
                 (str/join " " (map #(print-child % d) bounded))
                 (when (> (count v) preview-limit) " ...")
                 "}"))))

      (vector? v)
      (reduce-kv (fn [acc i vv]
                   (let [bv (walk vv)]
                     (if (identical? bv vv) acc (assoc acc i bv))))
                 v (subvec v 0 (min preview-limit (count v))))

      (sequential? v)
      (map walk (take preview-limit v))

      :else v)))

(defn bounded-pr-str
  "`pr-str` with the WORK of the print bounded — its BREADTH, its DEPTH, and
  the length of every STRING it can reach (rf2-y8doi.25, rf2-3hnvn).

  The Graph tab re-summarises every cached value-bearing field on every
  coalesced tick, so the cost here is the SERIALISATION, not the 80
  characters kept from it: printing a 50k-element sub value in full and then
  calling `subs` on the result pays the whole price and throws almost all of
  it away. Bounding the print is what removes the walk; truncating after the
  fact removes nothing.

  `*print-length*` is `preview-limit` itself rather than a smaller number,
  so the kept characters are exactly the ones the unbounded print gave:
  every printed element costs at least a separator plus one character, so 80
  elements can never be exhausted inside an 80-character preview.

  STRINGS are bounded on the way IN by `bound-long-strings`, because
  `*print-length*` does not reach inside one. That bound reaches EVERY
  string the print walk can, not only a string at the ROOT — a root-only
  bound left an ordinary `{:body <large string>}` serialising in full
  (rf2-3hnvn), and the two are indistinguishable from the preview, which
  agrees to the character either way.

  Public because that is the only place the cost is observable: `summarize`
  keeps 80 characters, so its preview cannot tell a bounded print from an
  unbounded one, and the regression pins the LENGTH OF THIS RESULT."
  [v]
  (binding [*print-length* preview-limit
            *print-level*  preview-print-level]
    (pr-str (bound-long-strings v 0))))

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
  :preview :redacted?}`. `:preview` is the `bounded-pr-str` print truncated
  to `preview-limit`. `:redacted?` flags the `:rf/redacted` sentinel (so a
  value that arrived already redacted — e.g. a value the egress walk
  redacted, then fed back — renders muted). Pure size/shape projection: it
  does NOT consult any elision policy (that is `redact-graph-for-egress`'s
  job).

  `:size` is the element count for a COUNTED collection (every vector, map,
  set and list), and is ABSENT otherwise — notably for a lazy seq, where
  `count` is a full realisation of the very walk `bounded-pr-str` exists to
  avoid (rf2-y8doi.25). A sub body ending in `map` / `filter` returns one, so
  this is the ordinary case rather than an exotic one, and an unknown size is
  the honest answer: the alternative is to walk 50k elements per coalesced
  tick to put a number beside an 80-character preview."
  [v]
  (let [redacted? (= :rf/redacted v)
        printed   (bounded-pr-str v)
        truncated (if (> (count printed) preview-limit)
                    (str (subs printed 0 preview-limit) "…")
                    printed)]
    (cond-> {:type      (value-type v)
             :preview   truncated
             :redacted? redacted?}
      (counted? v) (assoc :size (count v)))))

;; The value-bearing summary fields a LIVE node may carry (the `:value` /
;; `:params` / `:query` / `:state` summaries [Derivations.md] §Redaction
;; metadata names as egress-bearing off-box). ON-BOX `summarize-node` bounds
;; them for display; OFF-BOX `redact-graph-for-egress` walks them through the
;; frame's elision policy. ONE source of truth: the list is owned by the
;; core egress algorithm ns (rf2-mm3y49) and aliased here for the on-box
;; summary path so the two never drift.
(def value-bearing-node-keys rf.derivation.egress/value-bearing-node-keys)

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
;; — but the projection itself is `rf.derivation.egress/project-graph`.
;; ===========================================================================

(def redact-graph-for-egress
  "Project a `DerivationGraph` through the observed FRAME's egress policy for
  the off-box wire boundary — a thin DELEGATE to the core-owned algorithm
  `re-frame.derivation.egress/project-graph` (rf2-mm3y49). See that ns for
  the full contract: per-frame `project-egress` value redaction; dead-frame
  fail-closed (never borrowing an ambient frame and shipping raw); stable
  opaque live-resource-identity handles across every identity position (node
  key, `:id`, `:output`, realized `:inputs`, `:work-ledger` work-id +
  `:resource/key`, `:host-transient`) and every edge endpoint; structure
  preservation (a redacted param is still an edge); and idempotence.
  `([graph frame-id] [graph frame-id opts])` — `opts` ride through to
  `project-egress`; the `:frame` opt is set from `frame-id`."
  rf.derivation.egress/project-graph)
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
