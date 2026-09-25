(ns day8.re-frame2-xray.panels.app-db-diff-format
  "Display-only formatting helpers for the App-DB Diff panel.")

(def display-large-string-threshold
  "Display-only ceiling for string values. Clipboard values use the
  original value; this only keeps visible hiccup bounded."
  1024)

(defn format-edn
  "Best-effort EDN-like format. Used for short list-row labels and path
  headings (the Trace panel's path label) where row density rules per
  `tools/xray/spec/018-Event-Spine.md` §5 — short labels stay one-line
  `pr-str` for scan-ability.

  L4 detail-tab VALUE displays go through
  `day8.re-frame2-xray.views.edn-widget/inspect` (one widget, many
  call sites) for the cljs-devtools-shaped renderer;
  only short labels / paths come through here."
  [v]
  (try
    (pr-str v)
    (catch :default _
      (str v))))

(defn- needs-elision?
  "Pre-scan `v` for any string leaf exceeding the display ceiling. When
  no leaf needs elision the whole tree can pass through `display-value`
  unchanged — preserving structural identity downstream.

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

;; Module-level cache for `display-value`. The recursive
;; rewrite allocates a fresh tree on every call, even when nothing
;; needs eliding; that would defeat the `(identical? before after)`
;; short-circuit in `engine/project` and force the diff
;; engine into a full A* walk on every render. Together with the
;; edn-inspector's projection memo this keeps renders O(1) rather than
;; O(tree-size) when inputs are stable.
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
  "Recursive rewrite kernel behind `display-value`. Only
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

  Two-stage optimisation. First pre-scans `v` for any large
  string; if none exists, returns `v` unchanged (preserves structural
  identity so downstream `identical?` short-circuits hold — notably
  `engine/project` and the edn-inspector's projection memo).
  When elision IS needed, consults a module-level WeakMap cache keyed
  by input identity; cache hit returns the prior rewritten output (also
  identity-stable). Cache miss does the full rewrite and stores it.
  WeakMap auto-evicts when the input is GC'd.

  Semantically equivalent to a plain recursive rewrite; the cache adds
  only a call-once-per-input guarantee."
  [v]
  (if-not (needs-elision? v)
    v
    (if (cache-key? v)
      (or (.get display-value-cache v)
          (let [rewritten (rewrite v)]
            (.set display-value-cache v rewritten)
            rewritten))
      (rewrite v))))
