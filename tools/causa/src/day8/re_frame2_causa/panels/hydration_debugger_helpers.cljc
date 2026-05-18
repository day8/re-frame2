(ns day8.re-frame2-causa.panels.hydration-debugger-helpers
  "Pure-data helpers for Causa's Hydration Debugger panel
  (Phase 5, rf2-pzxsr).

  ## Why a separate `.cljc` ns

  The panel view in `hydration_debugger.cljs` builds a side-by-side
  hiccup tree; the *logic* — projecting hydration-mismatch trace
  events into a mismatch list, classifying the divergence kind into
  a one-line hypothesis, walking server / client render-trees and
  flagging divergent nodes, computing the hash-bisector path from
  root to first differing descendant — is pure data → data.

  Splitting the algebra into `.cljc` so it runs under the JVM test
  target (`clojure -M:test`) is required by
  `feedback_jvm_interop_must_work.md` and mirrors the convention
  established by `causality_graph_helpers.cljc` and
  `time_travel_helpers.cljc`.

  ## Data model (per spec/006-Hydration-Debugger.md §Substrate)

  Each `:rf.ssr/hydration-mismatch` trace event carries `:tags` with:

      {:path           [...]             ; hiccup-path to divergent node
       :server-tree    [...]             ; server's subtree at path
       :client-tree    [...]             ; client's subtree at path
       :server-hash    \"abc123\"          ; render-tree hash, server side
       :client-hash    \"def456\"          ; render-tree hash, client side
       :frame          :rf/default       ; the affected frame
       :view-id        cart-summary-view ; divergent view's registration
       :failing-id     :rf/hydrate}      ; body vs head (see Spec 011)

  Phase 5 of Spec 011 §Hydration-mismatch detection establishes the
  `:failing-id` discriminator: `:rf/hydrate` for body mismatch;
  `:rf.ssr/head-mismatch` for head mismatch. This helper ns treats
  both uniformly — the panel surfaces the discriminator alongside
  the path, but the diff algebra is identical.

  ## Divergence-kind classification (per spec §Cause hypothesis)

  Five divergence kinds, each with a one-line hypothesis hint:

    - `:different-text`  — text under same tag differs (state slice)
    - `:tag-differs`     — tag differs (conditional render branch)
    - `:attr-differs`    — attribute differs (sub feeding attribute)
    - `:children-missing-client` — children only on server (gating sub)
    - `:children-missing-server` — children only on client (js/window etc.)

  The hypothesis is **a hint, not an answer**; the panel surfaces it
  with the appropriate panel pivot link. The classification fn is
  pure → callers (tests + view) consume it without any reactive
  context.

  ## Phase 4 hiccup-engine adoption (rf2-1mcax)

  Per `tools/causa/spec/006-Hydration-Debugger.md` §Layout the panel
  renders the divergent subtree side-by-side. Phase 4 of rf2-abts7
  composes the Phase 3 hiccup-diff micro-engine
  (`day8.re-frame2-causa.diff.hiccup`) so each pane shows structural
  diff annotations rather than raw `pr-str` output. The annotated
  tree is computed **once** (the diff is symmetric) and the renderer
  consumes it with a `:perspective` flag that picks which side's
  value to surface and which `::op` semantics to flip.

  See `bisector-path-segments` for the human-readable first-divergent
  header and `first-divergent-header-text` for the title bar text."
  (:require [clojure.string :as str]))

;; ---- mismatch projection ------------------------------------------------

(def mismatch-operation
  "Per Spec 011 §Hydration-mismatch detection — the canonical
  operation keyword emitted on every hydration mismatch. Exposed as a
  Var so callers (the registry's filter, the tests, the MCP server)
  share the source of truth."
  :rf.ssr/hydration-mismatch)

(defn hydration-mismatch?
  "Predicate: is `ev` a hydration-mismatch trace event? Pure pred so
  the filter is JVM-runnable and the registry sub composes it
  trivially."
  [ev]
  (= mismatch-operation (:operation ev)))

(defn mismatches
  "Project a trace-event vector down to just the hydration mismatches,
  oldest first. Per Spec 011 §Hydration-mismatch detection each
  mismatch carries the canonical `:operation` + `:tags` payload; the
  helper is filter-only — the order from the trace buffer is preserved."
  [trace-events]
  (filterv hydration-mismatch? trace-events))

(defn mismatches-for-frame
  "Per-frame projection. Per spec §Frame awareness the Hydration panel
  shows mismatches for the active frame; switching the frame picker
  re-filters. Pure data so the filter axis stays JVM-testable."
  [trace-events frame-id]
  (if (nil? frame-id)
    (mismatches trace-events)
    (filterv (fn [ev]
               (and (hydration-mismatch? ev)
                    (= frame-id (get-in ev [:tags :frame]))))
             trace-events)))

(defn frames-with-mismatches
  "Set of frame-ids that have at least one hydration mismatch in the
  buffer. Drives the cross-frame swimlane indicator per spec §Frame
  awareness — 'cross-frame SSR (a server-rendered frame nested inside
  a client-rendered shell) renders both swimlanes'."
  [trace-events]
  (into #{}
        (comp (filter hydration-mismatch?)
              (keep (fn [ev] (get-in ev [:tags :frame]))))
        trace-events))

(defn select-mismatch
  "Return the mismatch trace event whose `:id` matches `mismatch-id`,
  or nil. The panel reads :id off the trace event (which gensyms its
  way through `re-frame.trace`); selection is by that id."
  [trace-events mismatch-id]
  (some (fn [ev]
          (when (and (hydration-mismatch? ev)
                     (= mismatch-id (:id ev)))
            ev))
        trace-events))

;; ---- divergence-kind classification (Cause hypothesis) ------------------

(defn- hiccup-vector?
  "Is `node` a hiccup vector (i.e. starts with a keyword tag)?

  Per spec/006-Hydration-Debugger.md §Substrate the server / client
  trees are hiccup. Hiccup vectors start with a keyword (the tag); a
  scalar (string / number / nil) is a text leaf; everything else
  (component fn, hiccup-as-seq) is treated as opaque-equal."
  [node]
  (and (vector? node) (keyword? (first node))))

(defn- tag-of
  "Tag keyword of a hiccup vector, or nil."
  [node]
  (when (hiccup-vector? node) (first node)))

(defn- attrs-of
  "Attribute map of a hiccup vector (the second slot if it's a map).
  Returns nil when no map is present (positional children-only form)."
  [node]
  (when (hiccup-vector? node)
    (let [s (second node)]
      (when (map? s) s))))

(defn- children-of
  "Children of a hiccup vector, skipping the attribute map when
  present."
  [node]
  (when (hiccup-vector? node)
    (let [tail (rest node)]
      (if (map? (first tail))
        (rest tail)
        tail))))

(defn- text-node?
  "Is `node` a text leaf (string / number / nil)?"
  [node]
  (or (string? node) (number? node) (nil? node)))

(defn classify-divergence
  "Classify the divergence between `server-tree` and `client-tree`
  into one of five kinds. Returns one of:

    :different-text          — text under same tag differs
    :tag-differs             — tag differs
    :attr-differs            — attributes differ; tag matches
    :children-missing-client — children present server, missing client
    :children-missing-server — children missing server, present client
    :unknown                 — none of the above (shape diff we don't
                               classify; safe-default for the view)

  Pure data → keyword. The view consumes the keyword to pick the
  hypothesis line; the MCP server exports the same keyword in
  programmatic flows."
  [server-tree client-tree]
  (cond
    ;; Both text scalars under the same enclosing tag (the *caller*
    ;; passed the subtree the runtime flagged — text vs text is the
    ;; canonical 'different-text' case).
    (and (text-node? server-tree) (text-node? client-tree))
    :different-text

    ;; One hiccup vector, one text leaf — tag-vs-text is a structural
    ;; flip. Treat as :tag-differs (conditional branch flipped).
    (and (hiccup-vector? server-tree) (text-node? client-tree))
    :tag-differs

    (and (text-node? server-tree) (hiccup-vector? client-tree))
    :tag-differs

    ;; Both hiccup vectors — compare tags, attrs, children.
    (and (hiccup-vector? server-tree) (hiccup-vector? client-tree))
    (let [stag    (tag-of server-tree)
          ctag    (tag-of client-tree)
          sattrs  (attrs-of server-tree)
          cattrs  (attrs-of client-tree)
          skids   (vec (children-of server-tree))
          ckids   (vec (children-of client-tree))]
      (cond
        (not= stag ctag)         :tag-differs
        (not= sattrs cattrs)     :attr-differs
        (and (seq skids) (empty? ckids)) :children-missing-client
        (and (empty? skids) (seq ckids)) :children-missing-server
        ;; Text-content under same tag with a single string child
        ;; whose value differs is the most common case in practice.
        (and (= 1 (count skids)) (= 1 (count ckids))
             (text-node? (first skids)) (text-node? (first ckids))
             (not= (first skids) (first ckids)))
        :different-text
        :else :unknown))

    :else :unknown))

(def hypothesis-text
  "Per spec/006-Hydration-Debugger.md §Cause hypothesis — the one-line
  hypothesis text by divergence-kind. Exposed as a map so callers
  (the view, the MCP server, tests) share the source of truth. The
  hypothesis is a hint, not an answer — paired in the view with a
  panel pivot link."
  {:different-text          "App-db state differs between server and client. Check the slice that feeds this view."
   :tag-differs             "View structure differs. Check conditional rendering — the server may have rendered a different branch."
   :attr-differs            "Attribute computed from differing inputs. Check the sub feeding the attribute."
   :children-missing-client "View's children render conditionally; the condition differs. Check the gating sub."
   :children-missing-server "View bails out on server (e.g., uses js/window). Move the dependency into a client-only effect."
   :unknown                 "Server and client trees diverge here. Open the divergent subtree to inspect."})

(defn hypothesis-for
  "Look up the hypothesis line for a divergence kind. Pure-data →
  string."
  [divergence-kind]
  (get hypothesis-text divergence-kind (get hypothesis-text :unknown)))

;; ---- bisector path ------------------------------------------------------

(defn- compute-hash
  "Per Spec 011 §Hydration-mismatch detection — the canonical render-
  tree hash is an FNV-1a 32-bit hash over a canonical EDN
  serialisation (depth-first traversal; attribute maps in sorted-key
  order; nil pruned). The helper here is a content-equality stand-in
  that mirrors the bisection property — same content → same hash;
  different content → different hash — without depending on the SSR
  artefact's hash module. The view consumes the bisector path; the
  panel doesn't need cryptographic strength.

  Pure data → string. Falls back to `str` for unprintable values."
  [node]
  (try
    ;; Use `pr-str` as a stable canonical form. Two structurally-equal
    ;; nodes produce the same string; different nodes produce
    ;; different strings. This mirrors the bisection property the
    ;; spec calls for without depending on a binary hash impl.
    (pr-str node)
    (catch #?(:clj Exception :cljs :default) _
      (str node))))

(defn nodes-equal?
  "Structural equality over two hiccup nodes. Mirrors the bisection
  property of the render-tree hash — same content → equal. Pure-data
  so callers can wire it into the bisector walk without depending on
  a hash module."
  [a b]
  (= (compute-hash a) (compute-hash b)))

(defn first-divergence-path
  "Walk `server-tree` and `client-tree` in parallel; return the path
  (vector of integer child-indices) from root to the first divergent
  node. Returns `[]` when the roots themselves diverge; returns nil
  when the trees are equal.

  Per spec §Render-tree hash bisector — every parent node has a hash
  that summarises its subtree; hashes match at common ancestors and
  diverge at the first differing descendant. This walker uses
  `nodes-equal?` as the hash stand-in and produces the same bisection
  path the spec's hash chips highlight.

  Pure data → path-or-nil. JVM-runnable."
  [server-tree client-tree]
  (cond
    (nodes-equal? server-tree client-tree)
    nil

    ;; Both hiccup vectors with same tag — descend into matching
    ;; children to find the first divergent slot.
    (and (hiccup-vector? server-tree)
         (hiccup-vector? client-tree)
         (= (tag-of server-tree) (tag-of client-tree)))
    (let [skids (vec (children-of server-tree))
          ckids (vec (children-of client-tree))
          n     (min (count skids) (count ckids))]
      (loop [i 0]
        (cond
          (>= i n)
          ;; All matching children matched up to `n` — one tree has
          ;; more. The divergence lands at index `n`.
          [n]

          (not (nodes-equal? (nth skids i) (nth ckids i)))
          ;; Descend into the divergent slot.
          (let [sub (first-divergence-path (nth skids i) (nth ckids i))]
            (if (nil? sub) [i] (into [i] sub)))

          :else
          (recur (inc i)))))

    :else
    ;; Roots differ in tag / type — divergence at root.
    []))

(defn path-from-root-hash-chips
  "Build the hash-chip annotation for every parent node along the
  bisection path. Returns a vector of `{:path [...] :hash <string>}`
  entries — one per node from the root down to the first divergent
  node. The view renders these as faint chips on each parent.

  Pure data → chips. JVM-runnable."
  [tree divergence-path]
  (loop [acc      [{:path [] :hash (compute-hash tree)}]
         node     tree
         path     []
         remaining divergence-path]
    (if (empty? remaining)
      acc
      (let [idx       (first remaining)
            children  (vec (children-of node))
            sub       (when (and (vector? children) (< idx (count children)))
                        (nth children idx))
            new-path  (conj path idx)]
        (if (nil? sub)
          acc
          (recur (conj acc {:path new-path :hash (compute-hash sub)})
                 sub
                 new-path
                 (rest remaining)))))))

;; ---- mismatch detail projection -----------------------------------------

(defn mismatch-detail
  "Project a single mismatch trace event into the shape the view
  consumes. Returns a map with the trace event's own tags surfaced as
  top-level slots, plus a `:divergence-kind` keyword + `:hypothesis`
  line + the bisector path.

  Pure data → map. JVM-runnable so the classification + bisector pass
  is testable without booting a substrate."
  [mismatch-trace-event]
  (when (some? mismatch-trace-event)
    (let [{:keys [tags]}    mismatch-trace-event
          {:keys [path server-tree client-tree
                  server-hash client-hash frame
                  view-id failing-id first-diff-path]} tags
          kind             (classify-divergence server-tree client-tree)
          ;; Prefer the runtime-supplied :first-diff-path; fall back
          ;; to our own walker when absent.
          bisector-path    (or first-diff-path
                               (first-divergence-path server-tree client-tree)
                               [])
          server-chips     (path-from-root-hash-chips server-tree bisector-path)
          client-chips     (path-from-root-hash-chips client-tree bisector-path)]
      {:id              (:id mismatch-trace-event)
       :path            path
       :server-tree     server-tree
       :client-tree     client-tree
       :server-hash     server-hash
       :client-hash     client-hash
       :frame           frame
       :view-id         view-id
       :failing-id      failing-id
       :divergence-kind kind
       :hypothesis      (hypothesis-for kind)
       :bisector-path   bisector-path
       :server-chips    server-chips
       :client-chips    client-chips})))

;; ---- mismatch list (multi-mismatch case) --------------------------------

(defn mismatch-list-summary
  "Build the panel-top list of mismatches per spec §Multi-mismatch
  case. Each entry surfaces the path + a one-line summary of server
  vs client. Pure data → vector-of-maps."
  [trace-events frame-id]
  (mapv (fn [ev]
          (let [{:keys [tags]}             ev
                {:keys [path server-tree
                        client-tree view-id]} tags
                kind                       (classify-divergence
                                             server-tree client-tree)]
            {:id      (:id ev)
             :path    path
             :view-id view-id
             :divergence-kind kind
             :summary (str (pr-str (case kind
                                     :different-text          (first (children-of server-tree))
                                     :tag-differs             server-tree
                                     server-tree))
                           " vs "
                           (pr-str (case kind
                                     :different-text          (first (children-of client-tree))
                                     :tag-differs             client-tree
                                     client-tree)))}))
        (mismatches-for-frame trace-events frame-id)))

;; ---- re-rooting (hash-chip click → zoom into subtree) -------------------

(defn re-root
  "Re-root a tree at the node identified by `path` (a vector of
  integer child-indices). Returns the subtree, or the original tree
  when the path doesn't reach a node. Per spec §Render-tree hash
  bisector — click any hash chip → the panel re-roots at that node.

  Pure data → subtree. JVM-runnable."
  [tree path]
  (loop [node      tree
         remaining path]
    (if (empty? remaining)
      node
      (let [idx       (first remaining)
            children  (vec (children-of node))]
        (if (and (< idx (count children)))
          (recur (nth children idx) (rest remaining))
          node)))))

;; ---- Phase 4 (rf2-1mcax) — first-divergent header ---------------------

(defn- node-display-tag
  "Render a node as a short readable tag for the bisector header. Hiccup
  vectors collapse to `:tag`; text leaves render via `pr-str` (truncated
  to keep the header on one line). Pure data → string."
  [node]
  (cond
    (hiccup-vector? node)
    (let [tag    (tag-of node)
          attrs  (attrs-of node)
          id     (when attrs (get attrs :id))
          cls    (when attrs (get attrs :class))]
      (cond-> (str tag)
        id  (str "#" id)
        cls (str (if (string? cls) (str "." (str/replace cls " " "."))
                                   (str "." cls)))))

    (text-node? node)
    (let [s (pr-str node)]
      (if (> (count s) 24) (str (subs s 0 21) "...") s))

    :else
    (let [s (pr-str node)]
      (if (> (count s) 24) (str (subs s 0 21) "...") s))))

(defn bisector-path-segments
  "Walk a tree along the bisector path (vector of integer child indices)
  and return a vector of short readable segments, one per node from the
  root down to the first divergent node. Used to render the
  'first divergent' header per Phase 4.

  Example:

      (bisector-path-segments
        [:div [:section.cart [:p \"3 items\"]]]
        [0 0])
      ;; => [\":div\" \":section.cart\" \":p\"]

  Pure data → vector of strings. JVM-runnable."
  [tree bisector-path]
  (loop [segments [(node-display-tag tree)]
         node     tree
         remaining bisector-path]
    (if (empty? remaining)
      segments
      (let [idx      (first remaining)
            children (vec (children-of node))
            sub      (when (< idx (count children))
                       (nth children idx))]
        (if (nil? sub)
          segments
          (recur (conj segments (node-display-tag sub))
                 sub
                 (rest remaining)))))))

(defn first-divergent-header-text
  "Render the first-divergent path as a single-line header string for
  the panel's drilldown header (per Phase 4). Joins segments with `>`.

      \":div > :section.cart > :p\"

  Returns `(root)` when the bisector path is empty (root divergence)."
  [tree bisector-path]
  (let [segs (bisector-path-segments tree bisector-path)]
    (if (= 1 (count segs))
      (first segs)
      (str/join " > " segs))))

;; ---- Phase 4 (rf2-1mcax) — hiccup-engine pane diff -------------------

(defn pane-diff-input
  "Return a map suitable for per-pane rendering by the Phase 4 pane
  renderer in `hydration_debugger.cljs`:

      {:server-tree   <original>
       :client-tree   <original>
       :bisector-path <path>}

  The hiccup-engine diff is computed lazily inside the renderer (it
  requires the runtime `:diff-opts` map fed from the settings sub).
  This helper exists so the projection is JVM-testable shape-wise
  without depending on the engine ns from the .cljc target."
  [{:keys [server-tree client-tree bisector-path] :as _detail}]
  {:server-tree   server-tree
   :client-tree   client-tree
   :bisector-path (or bisector-path [])})

;; ---- source-coord resolution (Lock #11 fallback) ------------------------

(defn source-coord-for-mismatch
  "Resolve a mismatch's source-coord per spec/006-Hydration-Debugger.md
  §Source-coord drilldown. Priority order:

    1. The divergent view's `:view-id` registration coord (when the
       runtime stamped a `:source-coord` map on the trace event's
       :tags).
    2. The dispatch / `:rf/hydrate` cofx's handler-coord (per Lock #11
       in DESIGN-RATIONALE.md — `(?)` annotation surfaced).
    3. nil when neither is available.

  Returns a map `{:coord <string-or-nil> :annotation <:exact | :fallback | nil>}`
  so the view can render the `(?)` annotation per spec.

  Pure data → map. JVM-runnable."
  [mismatch-trace-event]
  (let [{:keys [tags]} mismatch-trace-event
        view-coord     (get-in tags [:source-coord])
        handler-coord  (get-in tags [:handler-source-coord])]
    (cond
      view-coord    {:coord      (let [{:keys [file line]} view-coord]
                                   (cond-> file
                                     line (str ":" line)))
                     :annotation :exact}
      handler-coord {:coord      (let [{:keys [file line]} handler-coord]
                                   (cond-> file
                                     line (str ":" line)))
                     :annotation :fallback}
      :else         {:coord nil :annotation nil})))
