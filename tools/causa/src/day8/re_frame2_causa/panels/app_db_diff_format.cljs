(ns day8.re-frame2-causa.panels.app-db-diff-format
  "Display-only formatting helpers for the App-DB Diff panel.")

(def op->border
  {:added    :green
   :modified :yellow
   :removed  :red})

(def op->label
  {:added    "(added)"
   :modified "(modified)"
   :removed  "(removed)"})

(def display-large-string-threshold
  "Display-only ceiling for string values. Clipboard values still use
  the original value; this only keeps visible hiccup bounded."
  1024)

(defn format-edn
  "Best-effort EDN-like format. Used by the App-DB Diff panel's
  list-row labels (reserved-row, pinned-row, focus-result-row, slice
  path heading) where row density rules per `tools/causa/spec/018-Event-Spine.md`
  §5 — short labels stay one-line `pr-str` for scan-ability.

  L4 detail-tab VALUE displays go through
  `day8.re-frame2-causa.views.edn-widget.widget/inspect` (rf2-8q4f4 —
  one widget, many call sites) for the cljs-devtools-shaped renderer;
  only short labels / paths come through here."
  [v]
  (try
    (pr-str v)
    (catch :default _
      (str v))))

(defn display-value
  "Return `v` with large string leaves replaced by a stable marker."
  [v]
  (cond
    (and (string? v) (> (count v) display-large-string-threshold))
    {:rf.size/large-elided {:chars (count v)}}

    (map? v)
    (into (empty v) (map (fn [[k value]] [k (display-value value)])) v)

    (vector? v)
    (mapv display-value v)

    (set? v)
    (into (empty v) (map display-value) v)

    (sequential? v)
    (doall (map display-value v))

    :else
    v))

(defn format-display-edn
  "Best-effort EDN-like format for visible values."
  [v]
  (format-edn (display-value v)))

(defn truncate
  "Truncate `s` to `n` chars, adding an ellipsis when needed."
  [s n]
  (let [s (str s)]
    (if (<= (count s) n)
      s
      (str (subs s 0 (max 0 (dec n))) "…"))))
