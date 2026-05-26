(ns day8.re-frame2-xray.diff.hiccup-render
  "Renderer for the hiccup-tree-diff micro-engine
  (rf2-i39w2 Phase 3 of rf2-abts7).

  Design source-of-truth:
  `ai/findings/2026-05-18-difftastic-in-xray.md` §3.3 + §4.2 + §5.4.

  ## What this renders

  Input: an annotated hiccup node produced by
  `diff.hiccup/diff-hiccup-node` — either a scalar leaf
  (`:added`/`:removed`/`:modified`/`:same`) OR one of the three
  hiccup-specific shapes:

    - `:element-changed`  → render `[:div.row {…}]` header + attrs-diff
                            inline + recurse into children-diff
    - `:element-moved`    → render `↻` chip with from/to index +
                            optionally recurse into :inner-diff
    - `:fn-ref-changed`   → render distinct mode-accent `(fn ref
                            changed)` chip (only emitted when toggle on)

  Output is a hiccup tree the caller drops into any DOM-ish surface
  (the Views panel drilldown, the hydration debugger pane, etc.).

  ## Colour tokens (§5.4)

  Element-moved + fn-ref-changed both use the mode `:accent` (distinct
  from `:modified`'s `:yellow`) so the eye reads 'this is something
  other than a plain modification'."
  (:require [clojure.string :as string]
            [day8.re-frame2-xray.diff.hiccup :as hd]
            [day8.re-frame2-xray.panels.app-db-diff-format :as f]
            ;; rf2-q3dzw phase 5 — leaf values route through the
            ;; first-class edn-inspector widget. The legacy
            ;; `theme.data-inspector` ns is deleted.
            [day8.re-frame2-xray.views.edn-inspector :as ei]
            [day8.re-frame2-xray.theme.tokens
             :refer [tokens mono-stack sans-stack]]))

;; ---- hoisted row-level styles (rf2-xjgdk · audit F5) -------------------
;;
;; Hiccup-diff renderers walk per element + per attr + per child;
;; allocating fresh `:style` maps per node makes deep trees expensive.
;; The maps below capture the static shape once; op-keyed colour
;; variation is applied as a tiny `assoc`-overlay where it varies.

(def ^:private key-label-keyed-style
  {:color        (:accent tokens)
   :font-family  mono-stack
   :font-size    "11px"
   :margin-right "6px"})

(def ^:private key-label-indexed-style
  {:color        (:text-tertiary tokens)
   :font-family  mono-stack
   :font-size    "11px"
   :margin-right "6px"})

(def ^:private gutter-row-style-base
  "Outer gutter row — only the `:border-left` varies per call."
  {:display      "flex"
   :align-items  "flex-start"
   :gap          "4px"
   :padding      "2px 0"
   :padding-left "6px"})

(def ^:private gutter-glyph-style-base
  "Per-row glyph span — only `:color` varies."
  {:flex        "0 0 14px"
   :font-family mono-stack
   :font-size   "12px"
   :font-weight 700
   :text-align  "center"
   :user-select "none"})

(def ^:private gutter-body-style
  {:flex 1 :min-width 0})

;; --- attr-row shapes ---------------------------------------------------

(def ^:private attr-same-row-style
  {:display     "flex"
   :gap         "6px"
   :color       (:text-tertiary tokens)
   :font-family mono-stack
   :font-size   "12px"})

(def ^:private attr-key-accent-style
  {:color (:accent tokens)})

(def ^:private attr-key-mono-style
  {:color       (:accent tokens)
   :font-family mono-stack
   :font-size   "12px"})

(def ^:private attr-row-flex-style
  {:display "flex" :gap "6px"})

(def ^:private attr-row-flex-strike-style
  {:display "flex" :gap "6px" :text-decoration "line-through"})

(def ^:private attr-row-flex-wrap-style
  {:display "flex" :flex-wrap "wrap" :gap "6px" :align-items "baseline"})

(def ^:private attr-value-added-style
  {:color       (:green tokens)
   :font-family mono-stack
   :font-size   "12px"})

(def ^:private attr-value-removed-style
  {:color       (:red tokens)
   :font-family mono-stack
   :font-size   "12px"})

(def ^:private attr-value-before-strike-style
  {:color           (:text-tertiary tokens)
   :font-family     mono-stack
   :font-size       "12px"
   :text-decoration "line-through"})

(def ^:private attr-arrow-style
  {:color       (:text-tertiary tokens)
   :font-family mono-stack
   :font-size   "12px"})

(def ^:private attr-value-modified-after-style
  {:color       (:yellow tokens)
   :font-family mono-stack
   :font-size   "12px"})

(def ^:private fn-ref-changed-chip-style
  {:color       (:accent tokens)
   :font-family mono-stack
   :font-size   "11px"
   :font-style  "italic"})

(def ^:private unknown-op-style
  {:color (:red tokens)})

(def ^:private attrs-container-style
  {:padding-left "12px"
   :margin       "2px 0"})

(def ^:private children-container-style
  {:padding-left "12px"
   :border-left  (str "1px solid " (:border-subtle tokens))
   :margin       "2px 0"})

;; --- element header + outer element-changed row -----------------------

(def ^:private element-row-style
  {:display        "flex"
   :flex-direction "column"
   :margin         "2px 0"})

(def ^:private element-header-flex-style
  {:display     "flex"
   :align-items "baseline"
   :gap         "6px"
   :font-family mono-stack
   :font-size   "12px"
   :color       (:text-primary tokens)})

(def ^:private element-tag-style
  {:color (:text-tertiary tokens)})

(def ^:private element-changed-marker-style
  {:color       (:text-tertiary tokens)
   :font-family sans-stack
   :font-size   "11px"})

;; --- element-moved row -------------------------------------------------

(def ^:private moved-row-style
  {:display        "flex"
   :flex-direction "column"
   :margin         "2px 0"})

(def ^:private moved-body-style
  {:display     "flex"
   :flex-wrap   "wrap"
   :gap         "6px"
   :align-items "baseline"
   :font-family mono-stack
   :font-size   "12px"})

(def ^:private moved-label-style
  {:color       (:accent tokens)
   :font-weight 600})

(def ^:private moved-meta-style
  {:color      (:text-tertiary tokens)
   :font-size  "11px"
   :font-style "italic"})

(def ^:private moved-value-style
  {:color (:text-secondary tokens)})

;; --- scalar leaf rows --------------------------------------------------

(def ^:private leaf-same-row-style
  {:display     "flex"
   :gap         "6px"
   :color       (:text-tertiary tokens)
   :font-family mono-stack
   :font-size   "12px"})

(def ^:private leaf-row-flex-style
  {:display "flex" :gap "6px"})

(def ^:private leaf-row-flex-strike-style
  {:display "flex" :gap "6px" :text-decoration "line-through"})

(def ^:private leaf-row-flex-wrap-style
  {:display "flex" :flex-wrap "wrap" :gap "6px" :align-items "baseline"})

(def ^:private leaf-value-added-style
  {:color       (:green tokens)
   :font-family mono-stack
   :font-size   "12px"})

(def ^:private leaf-value-removed-style
  {:color       (:red tokens)
   :font-family mono-stack
   :font-size   "12px"})

(def ^:private leaf-value-before-strike-style
  {:color           (:text-tertiary tokens)
   :font-family     mono-stack
   :font-size       "12px"
   :text-decoration "line-through"})

(def ^:private leaf-arrow-style
  {:color       (:text-tertiary tokens)
   :font-family mono-stack
   :font-size   "12px"})

(def ^:private leaf-value-modified-after-style
  {:color       (:yellow tokens)
   :font-family mono-stack
   :font-size   "12px"})

(def ^:private leaf-fn-ref-changed-row-style
  {:display     "flex"
   :gap         "6px"
   :align-items "baseline"
   :font-family mono-stack
   :font-size   "12px"
   :color       (:accent tokens)
   :font-style  "italic"})

;; --- root wrapper ------------------------------------------------------

(def ^:private root-wrapper-style
  {:font-family mono-stack
   :font-size   "12px"
   :color       (:text-primary tokens)
   :line-height "1.5"})

;; ---- node-key helper ---------------------------------------------------

(defn- node-key-for
  "Stable per-node expand-state key combining the surface + path."
  [surface path]
  (str surface "/hiccup" "/" (pr-str (vec path))))

(defn- key-or-index-label
  "Inline `[index]` or `{:key k}` label that prefixes a per-child diff
  row. The renderer reads `:index` and `:key` slots set by the engine."
  [node]
  (cond
    (some? (:key node))
    [:span {:style key-label-keyed-style}
     (str ":key " (pr-str (:key node)))]

    (some? (:index node))
    [:span {:style key-label-indexed-style}
     (str "[" (:index node) "]")]

    :else nil))

(defn- inspect-value
  [v node-key]
  (let [pid (keyword "rf.xray.diff-section"
                     (-> node-key
                         (string/replace #"[^A-Za-z0-9._-]+" "_")))]
    [ei/edn-inspector (f/display-value v)
     {:panel-id pid
      :default-expanded-depth 1}]))

(defn- gutter
  "Coloured-glyph + left-border wrapper for an annotated row. The two
  per-op-variant pixels (`:border-left` tone, glyph `:color`) ride on
  hoisted base styles."
  [glyph tone body]
  [:div {:style (assoc gutter-row-style-base
                       :border-left (str "3px solid " tone))}
   [:span {:style (assoc gutter-glyph-style-base :color tone)}
    glyph]
   [:div {:style gutter-body-style}
    body]])

;; ---- annotated-attrs rendering -----------------------------------------

(declare render-hiccup-annotated)

(defn- render-attr-row
  "Render one attr from the attrs-diff. The attr key prefixes the
  value rendering; ops dispatch to coloured-gutter cells."
  [attr-node parent-key]
  (let [op    (hd/op-of attr-node)
        k     (:key attr-node)
        nkey  (str parent-key "/attr/" (pr-str k))]
    (case op
      :same
      [:div {:style attr-same-row-style}
       [:span {:style attr-key-accent-style} (pr-str k)]
       (inspect-value (:value attr-node) nkey)]

      :added
      (gutter "+" (:green tokens)
              [:div {:style attr-row-flex-style}
               [:span {:style attr-key-mono-style}
                (pr-str k)]
               [:span {:style attr-value-added-style}
                (inspect-value (:value attr-node) nkey)]])

      :removed
      (gutter "-" (:red tokens)
              [:div {:style attr-row-flex-strike-style}
               [:span {:style attr-key-mono-style}
                (pr-str k)]
               [:span {:style attr-value-removed-style}
                (inspect-value (:value attr-node) nkey)]])

      :modified
      (gutter "~" (:yellow tokens)
              [:div {:style attr-row-flex-wrap-style}
               [:span {:style attr-key-mono-style}
                (pr-str k)]
               [:span {:style attr-value-before-strike-style}
                (inspect-value (:before attr-node) (str nkey "/before"))]
               [:span {:style attr-arrow-style}
                "→"]
               [:span {:style attr-value-modified-after-style}
                (inspect-value (:after attr-node) (str nkey "/after"))]])

      :fn-ref-changed
      (gutter "◴" (:accent tokens)
              [:div {:style attr-row-flex-wrap-style}
               [:span {:style attr-key-mono-style}
                (pr-str k)]
               [:span {:data-testid "rf-xray-diff-fn-ref-changed-chip"
                       :style fn-ref-changed-chip-style}
                "(fn ref changed)"]])

      ;; Fallback — unknown op (defensive).
      [:span {:style unknown-op-style}
       (str "unknown attr op: " (pr-str op))])))

(defn- render-attrs-diff
  "Render an attrs-diff node — render only non-`:same` rows by
  default; collapse pure-:same attrs into nothing (the attrs map
  rendering on the element header carries the same-value info)."
  [attrs-diff parent-key]
  (let [kids   (:children attrs-diff)
        changed (filter #(not= :same (hd/op-of %)) kids)]
    (when (seq changed)
      (into [:div {:data-testid "rf-xray-diff-hiccup-attrs"
                   :style attrs-container-style}]
            (for [c changed]
              ^{:key (pr-str (:key c))}
              (render-attr-row c parent-key))))))

;; ---- children diff rendering ------------------------------------------

(defn- render-children-diff
  "Render a vector of children-diff nodes. Recurses for elements;
  shows scalar diffs inline."
  [children-diff surface parent-path depth]
  (when (seq children-diff)
    (into [:div {:data-testid "rf-xray-diff-hiccup-children"
                 :style children-container-style}]
          (map-indexed
            (fn [i c]
              (let [k    (or (:key c) (:index c) i)
                    path (conj parent-path k)]
                ^{:key (pr-str k)}
                (render-hiccup-annotated c surface path (inc depth))))
            children-diff))))

;; ---- element header (the `[:div.row {…}]` line) ------------------------

(defn- element-header
  "Render `[tag attrs-summary …]` as the per-element row label. The
  attrs detail rolls out below."
  [tag attrs-count children-count node-label]
  [:div {:style element-header-flex-style}
   (when node-label node-label)
   [:span {:style element-tag-style}
    (str "[" (pr-str tag)
         (when (pos? attrs-count) " {…}")
         (when (pos? children-count) " …")
         "]")]])

;; ---- element-changed --------------------------------------------------

(defn- render-element-changed
  [node surface path depth]
  (let [{:keys [tag attrs-diff children-diff]} node
        nkey         (node-key-for surface path)
        attrs-count  (count (:children attrs-diff))
        kids-count   (count children-diff)
        any-changes? (or (some #(not= :same (hd/op-of %)) (:children attrs-diff))
                         (some #(not= :same (hd/op-of %)) children-diff))]
    [:div {:data-testid (str "rf-xray-diff-hiccup-element-" nkey)
           :style element-row-style}
     [:div {:style element-header-flex-style}
      (key-or-index-label node)
      (element-header tag attrs-count kids-count nil)
      (when any-changes?
        [:span {:style element-changed-marker-style}
         "◴ changed"])]
     (render-attrs-diff attrs-diff nkey)
     (render-children-diff children-diff surface path depth)]))

;; ---- element-moved ----------------------------------------------------

(defn- render-element-moved
  [node surface path depth]
  (let [{:keys [value key from-index to-index inner-diff]} node
        nkey (node-key-for surface path)]
    [:div {:data-testid "rf-xray-diff-hiccup-moved"
           :style moved-row-style}
     (gutter "↻" (:accent tokens)
             [:div {:style moved-body-style}
              (key-or-index-label node)
              [:span {:style moved-label-style}
               (str "moved")]
              [:span {:style moved-meta-style}
               (str "(was at index " from-index ", now at " to-index ")")]
              [:span {:style moved-value-style}
               (inspect-value value nkey)]])
     (when inner-diff
       (render-hiccup-annotated inner-diff surface
                                (conj path :inner) (inc depth)))]))

;; ---- scalar dispatch (re-uses existing colour palette) ----------------

(defn- render-same-leaf
  [node nkey]
  [:div {:style leaf-same-row-style}
   (key-or-index-label node)
   (inspect-value (:value node) nkey)])

(defn- render-added-leaf
  [node nkey]
  (gutter "+" (:green tokens)
          [:div {:style leaf-row-flex-style}
           (key-or-index-label node)
           [:span {:style leaf-value-added-style}
            (inspect-value (:value node) nkey)]]))

(defn- render-removed-leaf
  [node nkey]
  (gutter "-" (:red tokens)
          [:div {:style leaf-row-flex-strike-style}
           (key-or-index-label node)
           [:span {:style leaf-value-removed-style}
            (inspect-value (:value node) nkey)]]))

(defn- render-modified-leaf
  [node nkey]
  (gutter "~" (:yellow tokens)
          [:div {:style leaf-row-flex-wrap-style}
           (key-or-index-label node)
           [:span {:style leaf-value-before-strike-style}
            (inspect-value (:before node) (str nkey "/before"))]
           [:span {:style leaf-arrow-style}
            "→"]
           [:span {:style leaf-value-modified-after-style}
            (inspect-value (:after node) (str nkey "/after"))]]))

(defn- render-fn-ref-changed-leaf
  [node nkey]
  (gutter "◴" (:accent tokens)
          [:div {:data-testid "rf-xray-diff-fn-ref-changed-chip"
                 :style leaf-fn-ref-changed-row-style}
           (key-or-index-label node)
           "(fn ref changed)"]))

;; ---- public dispatch ---------------------------------------------------

(defn render-hiccup-annotated
  "Dispatch on the hiccup-diff `::op` and render the annotated node.

  Three hiccup-specific cases:

    `:element-changed` — render element header + attrs-diff +
                         children-diff (recursive)
    `:element-moved`   — render `↻` chip with from/to index, optional
                         inner-diff
    `:fn-ref-changed`  — render distinct mode-accent `(fn ref changed)`
                         chip (only emitted when toggle on)

  Plus the generic scalar cases (`:same` / `:added` / `:removed` /
  `:modified`)."
  [node surface path depth]
  (let [op   (hd/op-of node)
        nkey (node-key-for surface path)]
    (case op
      :element-changed (render-element-changed node surface path depth)
      :element-moved   (render-element-moved   node surface path depth)
      :fn-ref-changed  (render-fn-ref-changed-leaf node nkey)
      :same            (render-same-leaf     node nkey)
      :added           (render-added-leaf    node nkey)
      :removed         (render-removed-leaf  node nkey)
      :modified        (render-modified-leaf node nkey)
      ;; Fallback — shouldn't happen for well-formed input.
      [:span {:style unknown-op-style}
       (str "unknown hiccup op: " (pr-str op))])))

(defn render-root
  "Top-level entry for a hiccup-diff render. Wraps the dispatch in a
  testable container with a stable `data-testid`."
  ([annotated surface]
   (render-root annotated surface []))
  ([annotated surface path]
   [:div {:data-testid "rf-xray-diff-hiccup-root"
          :style root-wrapper-style}
    (render-hiccup-annotated annotated surface path 0)]))
