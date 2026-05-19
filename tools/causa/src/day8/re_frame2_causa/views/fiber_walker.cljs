(ns day8.re-frame2-causa.views.fiber-walker
  "Causa's runtime view-hierarchy reader (rf2-mxkq7).

  Walks React's Fiber tree starting from a host DOM node and produces
  a depth-first list of `{:view-id … :depth N :fiber-key K}` entries.
  The Views panel's Group-by-tree toggle consumes this to render
  parent ⊃ children indentation and parent-cascade roll-up
  ('parent X (47 descendants re-rendered)').

  ## Why Fiber

  Per `spec/View-Hierarchy-Capture.md` (LOCKED 2026-05-19 ~14:55
  AUSEST), Fiber's parent/child slots ARE the contract for all React
  substrates (Reagent / UIx / Helix all mount through React). The
  walker reads ONLY the structural slots (`return`, `child`, `sibling`,
  `elementType`); it deliberately does NOT read memo status, hook
  state, lane priority, or any per-component metadata — those stay
  rejected per the original Views Q1 decision.

  ## Production DCE

  Every public fn is `(when interop/debug-enabled? …)`-gated; the
  outer `goog.DEBUG` define collapses the bodies under `:advanced` so
  production bundles carry the wrapper signatures only. The Causa
  preload is itself `:devtools/preloads`-only, so the walker NS is not
  on the production classpath at all under the canonical Causa
  install — this gate is the second line of defence against an
  accidental `:require` from a host's non-dev entry point. See the
  bundle-isolation contract in `implementation/scripts/check-bundle-
  isolation.cjs`.

  ## React-version regression check

  Each React major bump (16→17→18→19→…) must run the smoke test in
  `views/fiber_walker_cljs_test.cljs` to confirm the walker still
  reads parent/child slots correctly. If a future React renames a
  slot or restructures the Fiber shape, fall back to the data-
  attribute tagging bead (rf2-01il5)."
  (:require [goog.object :as gobj]
            [re-frame.interop :as interop]))

;; ---- Fiber property discovery ------------------------------------------
;;
;; React attaches one of two random-suffixed properties to each host
;; DOM node it mounts:
;;
;;   React 16  → `__reactInternalInstance$<hash>`
;;   React 17+ → `__reactFiber$<hash>`
;;
;; The suffix is a per-renderer random string. We discover the property
;; by scanning the DOM node's own keys for either documented prefix.

(def ^:private ^:const fiber-key-prefix "__reactFiber$")
(def ^:private ^:const legacy-fiber-key-prefix "__reactInternalInstance$")

(defn- find-fiber-property
  "Return the property name that holds the Fiber on `dom-node`, or nil
  if React has not attached one (e.g. node created outside React)."
  [dom-node]
  (when dom-node
    (some (fn [k]
            (when (or (zero? (.indexOf k fiber-key-prefix))
                      (zero? (.indexOf k legacy-fiber-key-prefix)))
              k))
          (js-keys dom-node))))

(defn dom-node->fiber
  "Read the Fiber pointer off a DOM node. Returns nil when React has
  not mounted the node or when DCE elided the walker (production)."
  [dom-node]
  (when ^boolean interop/debug-enabled?
    (when-let [k (find-fiber-property dom-node)]
      (gobj/get dom-node k))))

;; ---- elementType → view-id resolution ---------------------------------
;;
;; The adapter (Reagent / UIx / Helix) tags a `reg-view`-registered fn
;; with the view-id keyword on a known property at registration time.
;; The walker reads it without dragging an adapter `:require` in.
;; When the property is absent (anonymous fn, plain host element,
;; fragment) the walker falls back to `:displayName` / `:name` / a
;; "<host>" label so the tree still renders.

(def ^:private ^:const view-id-prop "__rf2_view_id__")

(defn- element-type->view-id
  [element-type]
  (cond
    (nil? element-type) nil
    (or (string? element-type) (symbol? element-type)) (str element-type)
    :else
    (or (gobj/get element-type view-id-prop)
        (gobj/get element-type "displayName")
        (gobj/get element-type "name")
        "<host>")))

;; ---- the walk -----------------------------------------------------------

(defn walk-fiber
  "Depth-first walk of the Fiber subtree rooted at `root-fiber`.
  Returns a vector of `{:view-id … :depth N :fiber-key K}` in
  document order. Pure traversal; reads only `child`, `sibling`,
  `elementType`. Recursion depth bounded by component-tree depth —
  React tolerates 10k+; that's well inside the stack budget."
  [root-fiber]
  (when (and ^boolean interop/debug-enabled? root-fiber)
    (let [out (transient [])
          visit (fn visit [node depth]
                  (when node
                    (conj! out
                           {:view-id (element-type->view-id
                                       (gobj/get node "elementType"))
                            :depth   depth
                            :fiber-key (hash node)})
                    (when-let [c (gobj/get node "child")]
                      (visit c (inc depth)))
                    (when-let [s (gobj/get node "sibling")]
                      (visit s depth))))]
      (visit root-fiber 0)
      (persistent! out))))

(defn read-tree-from
  "Top-level entry: hand a DOM node, get the view-tree rooted at the
  Fiber that node belongs to. Returns nil under DCE (production) or
  when React has not mounted the node."
  [dom-node]
  (when ^boolean interop/debug-enabled?
    (when-let [fiber (dom-node->fiber dom-node)]
      (walk-fiber fiber))))
