(ns day8.re-frame2-causa.diff.hiccup-render
  "Renderer for the hiccup-tree-diff micro-engine
  (rf2-i39w2 Phase 3 of rf2-abts7).

  Design source-of-truth:
  `ai/findings/2026-05-18-difftastic-in-causa.md` §3.3 + §4.2 + §5.4.

  ## What this renders

  Input: an annotated hiccup node produced by
  `diff.hiccup/diff-hiccup-node` — either a scalar leaf
  (`:added`/`:removed`/`:modified`/`:same`) OR one of the three
  hiccup-specific shapes:

    - `:element-changed`  → render `[:div.row {…}]` header + attrs-diff
                            inline + recurse into children-diff
    - `:element-moved`    → render `↻` chip with from/to index +
                            optionally recurse into :inner-diff
    - `:fn-ref-changed`   → render distinct violet `(fn ref changed)`
                            chip (only emitted when toggle on)

  Output is a hiccup tree the caller drops into any DOM-ish surface
  (the Views panel drilldown, the hydration debugger pane, etc.).

  ## Colour tokens (§5.4)

  Element-moved + fn-ref-changed both use `:accent-violet` (distinct
  from `:modified`'s `:yellow`) so the eye reads 'this is something
  other than a plain modification'."
  (:require [day8.re-frame2-causa.diff.hiccup :as hd]
            [day8.re-frame2-causa.panels.app-db-diff-format :as f]
            [day8.re-frame2-causa.theme.data-inspector :as inspector]
            [day8.re-frame2-causa.theme.tokens
             :refer [tokens mono-stack sans-stack]]))

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
    [:span {:style {:color       (:accent-violet tokens)
                    :font-family mono-stack
                    :font-size   "11px"
                    :margin-right "6px"}}
     (str ":key " (pr-str (:key node)))]

    (some? (:index node))
    [:span {:style {:color       (:text-tertiary tokens)
                    :font-family mono-stack
                    :font-size   "11px"
                    :margin-right "6px"}}
     (str "[" (:index node) "]")]

    :else nil))

(defn- inspect-value
  [v node-key]
  (inspector/inspect (f/display-value v) node-key))

(defn- gutter
  "Coloured-glyph + left-border wrapper for an annotated row."
  [glyph tone body]
  [:div {:style {:display      "flex"
                 :align-items  "flex-start"
                 :gap          "4px"
                 :padding      "2px 0"
                 :padding-left "6px"
                 :border-left  (str "3px solid " tone)}}
   [:span {:style {:flex          "0 0 14px"
                   :color         tone
                   :font-family   mono-stack
                   :font-size     "12px"
                   :font-weight   700
                   :text-align    "center"
                   :user-select   "none"}}
    glyph]
   [:div {:style {:flex 1 :min-width 0}}
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
      [:div {:style {:display "flex" :gap "6px"
                     :color (:text-tertiary tokens)
                     :font-family mono-stack :font-size "12px"}}
       [:span {:style {:color (:accent-violet tokens)}} (pr-str k)]
       (inspect-value (:value attr-node) nkey)]

      :added
      (gutter "+" (:green tokens)
              [:div {:style {:display "flex" :gap "6px"}}
               [:span {:style {:color (:accent-violet tokens)
                               :font-family mono-stack
                               :font-size "12px"}}
                (pr-str k)]
               [:span {:style {:color (:green tokens)
                               :font-family mono-stack
                               :font-size "12px"}}
                (inspect-value (:value attr-node) nkey)]])

      :removed
      (gutter "-" (:red tokens)
              [:div {:style {:display "flex" :gap "6px"
                             :text-decoration "line-through"}}
               [:span {:style {:color (:accent-violet tokens)
                               :font-family mono-stack
                               :font-size "12px"}}
                (pr-str k)]
               [:span {:style {:color (:red tokens)
                               :font-family mono-stack
                               :font-size "12px"}}
                (inspect-value (:value attr-node) nkey)]])

      :modified
      (gutter "~" (:yellow tokens)
              [:div {:style {:display "flex" :flex-wrap "wrap" :gap "6px"
                             :align-items "baseline"}}
               [:span {:style {:color (:accent-violet tokens)
                               :font-family mono-stack
                               :font-size "12px"}}
                (pr-str k)]
               [:span {:style {:color (:text-tertiary tokens)
                               :font-family mono-stack
                               :font-size "12px"
                               :text-decoration "line-through"}}
                (inspect-value (:before attr-node) (str nkey "/before"))]
               [:span {:style {:color (:text-tertiary tokens)
                               :font-family mono-stack
                               :font-size "12px"}}
                "→"]
               [:span {:style {:color (:yellow tokens)
                               :font-family mono-stack
                               :font-size "12px"}}
                (inspect-value (:after attr-node) (str nkey "/after"))]])

      :fn-ref-changed
      (gutter "◴" (:accent-violet tokens)
              [:div {:style {:display "flex" :gap "6px"
                             :align-items "baseline"}}
               [:span {:style {:color (:accent-violet tokens)
                               :font-family mono-stack
                               :font-size "12px"}}
                (pr-str k)]
               [:span {:data-testid "rf-causa-diff-fn-ref-changed-chip"
                       :style {:color (:accent-violet tokens)
                               :font-family mono-stack
                               :font-size "11px"
                               :font-style "italic"}}
                "(fn ref changed)"]])

      ;; Fallback — unknown op (defensive).
      [:span {:style {:color (:red tokens)}}
       (str "unknown attr op: " (pr-str op))])))

(defn- render-attrs-diff
  "Render an attrs-diff node — render only non-`:same` rows by
  default; collapse pure-:same attrs into nothing (the attrs map
  rendering on the element header carries the same-value info)."
  [attrs-diff parent-key]
  (let [kids   (:children attrs-diff)
        changed (filter #(not= :same (hd/op-of %)) kids)]
    (when (seq changed)
      (into [:div {:data-testid "rf-causa-diff-hiccup-attrs"
                   :style {:padding-left "12px"
                           :margin "2px 0"}}]
            (for [c changed]
              ^{:key (pr-str (:key c))}
              (render-attr-row c parent-key))))))

;; ---- children diff rendering ------------------------------------------

(defn- render-children-diff
  "Render a vector of children-diff nodes. Recurses for elements;
  shows scalar diffs inline."
  [children-diff surface parent-path depth]
  (when (seq children-diff)
    (into [:div {:data-testid "rf-causa-diff-hiccup-children"
                 :style {:padding-left "12px"
                         :border-left (str "1px solid "
                                           (:border-subtle tokens))
                         :margin "2px 0"}}]
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
  [:div {:style {:display "flex" :align-items "baseline" :gap "6px"
                 :font-family mono-stack :font-size "12px"
                 :color (:text-primary tokens)}}
   (when node-label node-label)
   [:span {:style {:color (:text-tertiary tokens)}}
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
    [:div {:data-testid (str "rf-causa-diff-hiccup-element-" nkey)
           :style {:display "flex"
                   :flex-direction "column"
                   :margin "2px 0"}}
     [:div {:style {:display "flex" :align-items "baseline" :gap "6px"
                    :font-family mono-stack :font-size "12px"
                    :color (:text-primary tokens)}}
      (key-or-index-label node)
      (element-header tag attrs-count kids-count nil)
      (when any-changes?
        [:span {:style {:color (:text-tertiary tokens)
                        :font-family sans-stack
                        :font-size "11px"}}
         "◴ changed"])]
     (render-attrs-diff attrs-diff nkey)
     (render-children-diff children-diff surface path depth)]))

;; ---- element-moved ----------------------------------------------------

(defn- render-element-moved
  [node surface path depth]
  (let [{:keys [value key from-index to-index inner-diff]} node
        nkey (node-key-for surface path)]
    [:div {:data-testid "rf-causa-diff-hiccup-moved"
           :style {:display "flex"
                   :flex-direction "column"
                   :margin "2px 0"}}
     (gutter "↻" (:accent-violet tokens)
             [:div {:style {:display "flex" :flex-wrap "wrap" :gap "6px"
                            :align-items "baseline"
                            :font-family mono-stack :font-size "12px"}}
              (key-or-index-label node)
              [:span {:style {:color (:accent-violet tokens)
                              :font-weight 600}}
               (str "moved")]
              [:span {:style {:color (:text-tertiary tokens)
                              :font-size "11px"
                              :font-style "italic"}}
               (str "(was at index " from-index ", now at " to-index ")")]
              [:span {:style {:color (:text-secondary tokens)}}
               (inspect-value value nkey)]])
     (when inner-diff
       (render-hiccup-annotated inner-diff surface
                                (conj path :inner) (inc depth)))]))

;; ---- scalar dispatch (re-uses existing colour palette) ----------------

(defn- render-same-leaf
  [node nkey]
  [:div {:style {:display "flex" :gap "6px"
                 :color (:text-tertiary tokens)
                 :font-family mono-stack :font-size "12px"}}
   (key-or-index-label node)
   (inspect-value (:value node) nkey)])

(defn- render-added-leaf
  [node nkey]
  (gutter "+" (:green tokens)
          [:div {:style {:display "flex" :gap "6px"}}
           (key-or-index-label node)
           [:span {:style {:color (:green tokens)
                           :font-family mono-stack
                           :font-size "12px"}}
            (inspect-value (:value node) nkey)]]))

(defn- render-removed-leaf
  [node nkey]
  (gutter "-" (:red tokens)
          [:div {:style {:display "flex" :gap "6px"
                         :text-decoration "line-through"}}
           (key-or-index-label node)
           [:span {:style {:color (:red tokens)
                           :font-family mono-stack
                           :font-size "12px"}}
            (inspect-value (:value node) nkey)]]))

(defn- render-modified-leaf
  [node nkey]
  (gutter "~" (:yellow tokens)
          [:div {:style {:display "flex" :flex-wrap "wrap" :gap "6px"
                         :align-items "baseline"}}
           (key-or-index-label node)
           [:span {:style {:color (:text-tertiary tokens)
                           :font-family mono-stack
                           :font-size "12px"
                           :text-decoration "line-through"}}
            (inspect-value (:before node) (str nkey "/before"))]
           [:span {:style {:color (:text-tertiary tokens)
                           :font-family mono-stack
                           :font-size "12px"}}
            "→"]
           [:span {:style {:color (:yellow tokens)
                           :font-family mono-stack
                           :font-size "12px"}}
            (inspect-value (:after node) (str nkey "/after"))]]))

(defn- render-fn-ref-changed-leaf
  [node nkey]
  (gutter "◴" (:accent-violet tokens)
          [:div {:data-testid "rf-causa-diff-fn-ref-changed-chip"
                 :style {:display "flex" :gap "6px"
                         :align-items "baseline"
                         :font-family mono-stack
                         :font-size "12px"
                         :color (:accent-violet tokens)
                         :font-style "italic"}}
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
    `:fn-ref-changed`  — render distinct violet `(fn ref changed)`
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
      [:span {:style {:color (:red tokens)}}
       (str "unknown hiccup op: " (pr-str op))])))

(defn render-root
  "Top-level entry for a hiccup-diff render. Wraps the dispatch in a
  testable container with a stable `data-testid`."
  ([annotated surface]
   (render-root annotated surface []))
  ([annotated surface path]
   [:div {:data-testid "rf-causa-diff-hiccup-root"
          :style {:font-family mono-stack
                  :font-size "12px"
                  :color (:text-primary tokens)
                  :line-height "1.5"}}
    (render-hiccup-annotated annotated surface path 0)]))
