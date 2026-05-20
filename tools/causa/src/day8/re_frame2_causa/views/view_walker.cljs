(ns day8.re-frame2-causa.views.view-walker
  "Fallback runtime view-hierarchy walker — `data-rf-view` attribute path
  (rf2-01il5).

  ## Status: FALLBACK SAFETY NET only

  The primary path for runtime view-hierarchy capture is the Fiber-walker
  (rf2-mxkq7) — see [View-Hierarchy-Capture.md](../../../../../spec/View-Hierarchy-Capture.md).
  This namespace ships the **data-attribute fallback**:

      - Each re-frame2 adapter (Reagent / UIx / Helix) tags the rendered
        root DOM element of every registered view with
        `data-rf-view=\"<id>\"`. The tagging is wired in at adapter
        ns-load via the source-coord wrapper (Spec 006 §View tagging
        contract).
      - This walker queries `document.querySelectorAll('[data-rf-view]')`
        and reconstructs parent ⊃ children purely from DOM containment.
        No React internals; no Fiber slot reads; no version-coupling.

  Activate this walker when:

      1. A future React-version regression breaks the Fiber-walker.
      2. A non-React substrate is ever wired in (none today — Reagent,
         UIx, and Helix all mount through React, so the data-attribute
         path is dormant under the canonical install).

  ## Fidelity gaps (documented edge cases)

  The fallback is a **lossy approximation** of the Fiber-walker. Per
  Spec 006 §View tagging contract §Documented edge cases:

      - Fragment root (`:<>`) — invisible to the walker (no DOM
        element to tag); its children become orphans of the next-up
        tagged ancestor.
      - Nil / conditional root — invisible on the render that returned
        nil; subsequent re-renders that emit a DOM element are tagged.
      - Component-returning-component head — outer view invisible; the
        inner reg-view'd component tags its own root.
      - Portals — DOM containment associates portal children with the
        portal target, not the portal-rendering component.
      - `display: none` subtrees — present in the walk; consumers may
        filter them out.

  ## Production DCE

  This namespace lives under `tools/causa/`, which is bundle-isolated
  from production bundles (per the bundle-isolation contract — see
  `implementation/scripts/check-bundle-isolation.cjs`). Every public
  fn in this file is also gated on `interop/debug-enabled?` as a
  second line of defence against accidental requires from a non-dev
  entry point — per Spec 006 §Production elision and Spec 009
  §Production builds.

  ## Output shape

  The walker produces a depth-first vector of:

      {:view-id   <keyword | string | nil>   ;; resolved from data-rf-view
       :depth     <non-negative integer>     ;; 0 = root tagged element
       :node-key  <integer>}                 ;; stable hash of the DOM node

  In document order — mirrors the Fiber-walker's output shape per
  [View-Hierarchy-Capture.md §Output shape](../../../../../spec/View-Hierarchy-Capture.md#output-shape)
  so consumer code is path-agnostic. `:node-key` substitutes for the
  Fiber-walker's `:fiber-key` — same role (stable React key for tree-row
  rendering across re-walks)."
  (:require [re-frame.interop :as interop]))

;; ---- attribute parsing -----------------------------------------------------

(defn parse-view-id
  "Parse a `data-rf-view` attribute value back into the registry id.

      \":rf.foo/bar\"  → :rf.foo/bar         ;; namespaced kw
      \":bare\"        → :bare               ;; unqualified kw (legal at registrar)
      \"raw-string\"   → \"raw-string\"      ;; non-kw id (unusual)

  Per Spec 006 §View tagging contract §Attribute value format and
  Spec-Schemas §`:rf/view-id-attr`."
  [s]
  (cond
    (or (nil? s) (not (string? s)))
    nil

    ;; Leading colon → keyword. Splits on the first `/` to recover ns/name.
    (and (pos? (count s)) (= ":" (subs s 0 1)))
    (let [body (subs s 1)
          slash (.indexOf body "/")]
      (if (neg? slash)
        (keyword body)
        (keyword (subs body 0 slash) (subs body (inc slash)))))

    :else
    s))

;; ---- DOM walk --------------------------------------------------------------
;;
;; Build the parent ⊃ children tree from DOM containment.
;;
;; Algorithm: collect every `[data-rf-view]` element in document order.
;; For each tagged element, walk up its DOM `parentNode` chain looking
;; for the nearest ancestor that is ALSO tagged — that ancestor is its
;; logical parent. Elements with no tagged ancestor are roots (depth 0).
;;
;; `Element.contains` is documented across IE6+ and every modern engine
;; (per MDN, baseline-2003) so the containment check is portable. The
;; `parentNode` walk is O(depth) per tagged element; total O(N * D) for
;; N tagged elements at depth D. In practice the tagged-element count is
;; bounded by the registered-views count and D is the DOM depth — well
;; under 1ms for the dashboards we ship.
;;
;; The walk also tolerates a tagged element with NO `data-rf-view`
;; attribute on its DOM node — this can happen during a re-render where
;; the adapter wrapper's annotation hasn't landed yet. Those elements
;; are skipped (not rooted, not parented).

(defn- node-key
  "Stable integer key for a DOM node. We use `hash` on the underlying
  JS object — `hash` is identity-stable for the JS object's lifetime,
  so two consecutive walks of the same DOM tree produce the same
  `:node-key` value per node. React consumes the key for tree-row
  identity."
  [node]
  (hash node))

(defn- nearest-tagged-ancestor
  "Return the nearest ancestor of `node` that carries a `data-rf-view`
  attribute, or nil if none. The walk excludes `node` itself."
  [node]
  (loop [n (.-parentNode node)]
    (cond
      (nil? n)                                  nil
      (and (some? (.-getAttribute n))
           (some? (.getAttribute n "data-rf-view"))) n
      :else                                     (recur (.-parentNode n)))))

(defn- compute-depth
  "Depth = number of tagged ancestors. 0 for roots (no tagged
  ancestor). Computed by chasing `nearest-tagged-ancestor`
  iteratively — O(D) per node where D is the tagged-depth (typically
  ≤ 10). The memoisation across one walk via the `depths` JS Map cuts
  repeat work down each shared ancestor chain: if an ancestor is
  already in the map, we add its cached depth + 1 and stop."
  [node depths]
  (loop [n     (nearest-tagged-ancestor node)
         steps 0]
    (cond
      (nil? n)                  steps
      (.has depths n)           (+ steps 1 (.get depths n))
      :else                     (recur (nearest-tagged-ancestor n)
                                       (inc steps)))))

(defn walk!
  "Walk the document for `[data-rf-view]`-tagged elements and return a
  depth-first vector of `{:view-id … :depth … :node-key …}` maps in
  document order. Per Spec 006 §View tagging contract §Walker
  contract (fallback path).

  Production-elision contract per Spec 009: the body is gated on
  `interop/debug-enabled?` — under `:advanced` + `goog.DEBUG=false`
  the entire walk DCEs and the fn returns nil. Bundle-isolation
  (the `tools/causa/` artefact is forbidden from production bundles)
  is the first line of defence; this gate is the second.

  Accepts an optional `root` element — defaults to `js/document.body`.
  Passing a sub-tree root scopes the walk to that subtree's contained
  tagged elements."
  ([] (walk! (when (exists? js/document) (.-body js/document))))
  ([root]
   (when (and interop/debug-enabled? (some? root))
     (let [matched (.querySelectorAll root "[data-rf-view]")
           n       (.-length matched)
           ;; depths is a JS Map keyed by DOM node → integer depth.
           ;; Built up as we walk so each node's depth derivation can
           ;; reuse its ancestors' cached results. A JS Map matches
           ;; identity-keyed-by-DOM-node lookup more naturally than a
           ;; CLJS map and stays O(1) per read.
           depths  (js/Map.)]
       (loop [i   0
              acc (transient [])]
         (if (< i n)
           (let [node    (aget matched i)
                 attr    (.getAttribute node "data-rf-view")
                 view-id (parse-view-id attr)
                 depth   (compute-depth node depths)]
             (.set depths node depth)
             (recur (inc i)
                    (conj! acc {:view-id  view-id
                                :depth    depth
                                :node-key (node-key node)})))
           (persistent! acc)))))))

;; ---- raw helper for tooling ------------------------------------------------

(defn tagged-elements
  "Return the JS NodeList of `[data-rf-view]`-tagged elements under
  `root`. Test-facing — `walk!` already does this internally but
  consumers (e.g. element-resolvers for click-to-source) need the
  raw nodes too. Defaults to `js/document.body`.

  Same `interop/debug-enabled?` gate as `walk!`."
  ([] (tagged-elements (when (exists? js/document) (.-body js/document))))
  ([root]
   (when (and interop/debug-enabled? (some? root))
     (.querySelectorAll root "[data-rf-view]"))))
