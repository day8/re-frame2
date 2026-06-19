(ns day8.re-frame2-xray.panels.app-db-diff-format
  "Display-only formatting helpers for the App-DB Diff panel.")

(def display-large-string-threshold
  "Display-only ceiling for string values. Clipboard values still use
  the original value; this only keeps visible hiccup bounded."
  1024)

(defn format-edn
  "Best-effort EDN-like format. Used by the App-DB Diff panel's
  list-row labels (reserved-row, pinned-row, focus-result-row, slice
  path heading) where row density rules per `tools/xray/spec/018-Event-Spine.md`
  §5 — short labels stay one-line `pr-str` for scan-ability.

  L4 detail-tab VALUE displays go through
  `day8.re-frame2-xray.views.edn-widget/inspect` (rf2-8q4f4 —
  one widget, many call sites) for the cljs-devtools-shaped renderer;
  only short labels / paths come through here."
  [v]
  (try
    (pr-str v)
    (catch :default _
      (str v))))

(defn- needs-elision?
  "Pre-scan `v` for any string leaf exceeding the display ceiling. When
  no leaf needs elision the whole tree can pass through `display-value`
  unchanged — preserving structural identity downstream (rf2-4spyl).

  Short-circuits on the first hit: any large string anywhere in the
  subtree returns `true` immediately."
  [v]
  (cond
    (string? v)
    (> (count v) display-large-string-threshold)

    (map? v)
    (some (fn [[_ value]] (needs-elision? value)) v)

    (or (vector? v) (set? v) (sequential? v))
    (some needs-elision? v)

    :else
    false))

;; rf2-4spyl — module-level cache for `display-value`. The recursive
;; rewrite allocates a fresh tree on every call, even when nothing
;; needs eliding; that defeats the `(identical? before after)` short-
;; circuit in `engine/project` (engine.cljc:515) and forces the diff
;; engine into a full A* walk on every render. Combined with the audit's
;; H1 finding (rf2-4p1vl) this is the load-bearing pair that drops mode-
;; 3 renders from O(tree-size) to O(1) when inputs are stable.
;;
;; The fast path (`needs-elision?` → false) returns the input as-is and
;; preserves identity natively — no cache lookup needed. The slow path
;; (large strings present) caches the rewritten output keyed by the
;; INPUT identity in a JS WeakMap; subsequent calls with the same input
;; return the stable rewritten output, keeping identity downstream.
;;
;; WeakMap auto-evicts when the input is GC'd — no manual invalidation
;; needed. Only collections (map / vector / set / seq) can be WeakMap
;; keys in JS; scalars (string / number / nil / etc.) are not keyable
;; but the recursion only hits them as leaves where the rewrite is
;; either trivial (return `v`) or atomic (the elision marker), so a
;; cache there would add overhead without payoff.
(def ^:private display-value-cache
  (js/WeakMap.))

(defn- cache-key?
  "Predicate for WeakMap-keyable values — collections only. Other
  shapes (scalars, the elision marker output) skip the cache."
  [v]
  (or (map? v) (vector? v) (set? v) (and (sequential? v) (not (string? v)))))

(defn- rewrite
  "Recursive rewrite kernel — the original `display-value` logic. Only
  invoked on the slow path (when `needs-elision?` returned true)."
  [v]
  (cond
    (and (string? v) (> (count v) display-large-string-threshold))
    {:rf.size/large-elided {:chars (count v)}}

    (map? v)
    (into (empty v) (map (fn [[k value]] [k (rewrite value)])) v)

    (vector? v)
    (mapv rewrite v)

    (set? v)
    (into (empty v) (map rewrite) v)

    (sequential? v)
    (doall (map rewrite v))

    :else
    v))

(defn display-value
  "Return `v` with large string leaves replaced by a stable marker.

  rf2-4spyl — two-stage optimisation. First pre-scans `v` for any large
  string; if none exists, returns `v` unchanged (preserves structural
  identity so downstream `identical?` short-circuits hold — notably
  `engine/project` and the edn-inspector's projection memo, rf2-4p1vl).
  When elision IS needed, consults a module-level WeakMap cache keyed
  by input identity; cache hit returns the prior rewritten output (also
  identity-stable). Cache miss does the full rewrite and stores it.
  WeakMap auto-evicts when the input is GC'd.

  Semantically equivalent to the prior implementation; the rewrite
  kernel is unchanged. Only the call-once-per-input guarantee is new."
  [v]
  (if-not (needs-elision? v)
    v
    (if (cache-key? v)
      (or (.get display-value-cache v)
          (let [rewritten (rewrite v)]
            (.set display-value-cache v rewritten)
            rewritten))
      (rewrite v))))
