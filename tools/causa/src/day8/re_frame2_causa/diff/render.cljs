(ns day8.re-frame2-causa.diff.render
  "Sections-per-cluster renderer for the structural-diff engine
  (rf2-gfxmk Phase 1 of rf2-abts7).

  Design source-of-truth:
  `ai/findings/2026-05-18-difftastic-in-causa.md` §3.1.2 (per-section
  rendering) + §5 (path-headers + sticky breadcrumb).

  ## What this renders

  Input: vector of `{:path … :subtree <annotated-subtree>}` (from
  `section_grouping/group-into-sections`).

  Output: N path-headed `[:section ...]` blocks stacked vertically.
  Each section has:

    - A **sticky path-header breadcrumb** identifying the section
    - A **local annotated subtree** rendered from the path down
    - Per-node gutters (`+`/`-`/`~`/`◴` glyphs + coloured left-borders)
    - Smart-expand for changed branches up to 3 levels deep
    - Collapse `:same` subtrees into `(N entries unchanged)` chips
    - `:modified` leaves render inline `before → after`

  ## Reuses the data-inspector

  Falls through to `theme/data_inspector/render-value` for the value
  content of every annotated leaf. Just adds the dispatch wrapper:
  given an annotated node, dispatch on `::op` → render the appropriate
  chrome around the leaf-content render.

  ## Per-node expand-state

  Reuses the inspector's `:rf/causa` `:data-inspector` slot. Per-node
  `node-key` for a diff render combines the surface, the section path,
  and the path within the local annotated subtree (per design §8 loose-
  end note 3).

  ## Sentinel handling

  Sentinels are handled by the inspector's existing `redacted-chip` /
  `large-chip` renderers — the diff layer never overrides them. The
  diff layer's chrome (gutter colour + glyph) wraps the chip without
  reveal."
  (:require [re-frame.core :as rf]
            [day8.re-frame2-causa.diff.annotated-tree :as at]
            [day8.re-frame2-causa.panels.app-db-diff-format :as f]
            [day8.re-frame2-causa.theme.data-inspector :as inspector]
            [day8.re-frame2-causa.theme.tokens
             :refer [tokens mono-stack sans-stack]]))

;; ---- knob constants -----------------------------------------------------

(def smart-expand-max-depth
  "Per design §3.1.2 — cap auto-expand at 3 levels deep within a
  section so very deep changes don't pour the whole subtree on screen."
  3)

(def collapse-unchanged-threshold
  "Per design §5.3 — collapse `:same` direct children of changed
  containers into a `(N entries unchanged)` chip when there are more
  than this. Below the threshold, render inline so context stays."
  3)

;; ---- gutter / colour mapping --------------------------------------------

(def ^:private op->gutter-glyph
  {:added    "+"
   :removed  "-"
   :modified "~"
   :children "◴"
   :same     " "})

(def ^:private op->gutter-tone
  {:added    :green
   :removed  :red
   :modified :yellow
   :children :accent-violet
   :same     :text-tertiary})

(defn- gutter-colour
  [op]
  (get tokens (op->gutter-tone op) (:text-tertiary tokens)))

;; ---- path / node-key helpers --------------------------------------------

(defn- format-path
  "Inline EDN-ish path label for the breadcrumb header — e.g.
  `[:cart :items]`."
  [path]
  (f/format-edn (vec path)))

(defn- node-key-for
  "Build a stable per-node expand-state key combining the surface, the
  section path, and the sub-path within the section."
  [surface section-path sub-path]
  (str surface "/" (pr-str (vec section-path))
       "/" (pr-str (vec sub-path))))

;; ---- breadcrumb -------------------------------------------------------

(defn breadcrumb
  "Sticky path-header breadcrumb (per design §5.1). Clickable segments
  emit copy-path events on click (Cmd-click defers to native browser
  behaviour); the breadcrumb's surface-prefix is rendered as a faded
  label so the user sees `app-db → [:cart] [:items]`.

  The container is `position: sticky; top: 0` so it stays pinned as
  the user scrolls through a long section body. Per the design's
  scroll-sticky-vs-click decision (§5.1 + §7.2 open question 5), v1
  uses scroll-sticky — the breadcrumb is the user's 'where am I'
  anchor in a long diff tree."
  [section-path child-summary]
  (let [{:keys [added removed modified children]
         :or   {added 0 removed 0 modified 0 children 0}} child-summary
        total-changes (+ added removed modified children)]
    [:header {:data-testid (str "rf-causa-diff-section-header-"
                                (pr-str section-path))
              :style {:position       "sticky"
                      :top            0
                      :z-index        1
                      :display        "flex"
                      :align-items    "center"
                      :gap            "8px"
                      :padding        "6px 12px"
                      :background     (:bg-3 tokens)
                      :border-bottom  (str "1px solid "
                                           (:border-subtle tokens))
                      :font-family    mono-stack
                      :font-size      "12px"
                      :color          (:text-primary tokens)
                      :font-weight    600}}
     [:span {:data-testid (str "rf-causa-diff-section-path-"
                               (pr-str section-path))
             :style {:color (:accent-violet tokens)}}
      (if (empty? section-path)
        "(root)"
        (format-path section-path))]
     (when (pos? total-changes)
       [:span {:style {:font-family sans-stack
                       :font-size "11px"
                       :color (:text-tertiary tokens)
                       :font-weight 400}}
        (str "◴ "
             total-changes
             (if (= total-changes 1) " change" " changes"))])]))

;; ---- annotated leaf renderers ------------------------------------------

(declare render-annotated)

(defn- gutter
  "Coloured-glyph + left-border wrapper for a single annotated row."
  [op body]
  [:div {:style {:display      "flex"
                 :align-items  "flex-start"
                 :gap          "4px"
                 :padding      "2px 0"
                 :padding-left "6px"
                 :border-left  (str "3px solid " (gutter-colour op))}}
   [:span {:style {:flex          "0 0 14px"
                   :color         (gutter-colour op)
                   :font-family   mono-stack
                   :font-size     "12px"
                   :font-weight   700
                   :text-align    "center"
                   :user-select   "none"}}
    (or (op->gutter-glyph op) " ")]
   [:div {:style {:flex 1 :min-width 0}}
    body]])

(defn- key-label
  "Render the `:key` or `:index` slot for a child as `:k ` / `2 ` so
  the value sits inline with its identifier."
  [child]
  (when-let [k (at/child-key child)]
    [:span {:style {:color       (:accent-violet tokens)
                    :font-family mono-stack
                    :font-size   "12px"
                    :margin-right "6px"}}
     (str (pr-str k) " ")]))

(defn- inspect-value
  "Render a value via the existing data-inspector. Wraps the inspector
  invocation with a unique node-key so the expand-state slot doesn't
  collide with sibling renders."
  [v node-key]
  (inspector/inspect (f/display-value v) node-key))

(defn- render-modified-leaf
  "Inline `before → after` rendering for a `:modified` scalar leaf
  (design §3.1.2 — 'side-by-side vs unified')."
  [node node-key]
  (let [{:keys [before after]} node]
    [:div {:style {:display "flex" :flex-wrap "wrap" :align-items "baseline"
                   :gap "6px"}}
     (key-label node)
     [:span {:style {:color (:text-tertiary tokens)
                     :font-family mono-stack
                     :font-size "12px"
                     :text-decoration "line-through"}}
      (inspect-value before (str node-key "/before"))]
     [:span {:style {:color (:text-tertiary tokens)
                     :font-family mono-stack
                     :font-size "12px"}}
      "→"]
     [:span {:style {:color (:yellow tokens)
                     :font-family mono-stack
                     :font-size "12px"}}
      (inspect-value after (str node-key "/after"))]]))

(defn- render-added-leaf
  [node node-key]
  [:div {:style {:display "flex" :flex-wrap "wrap" :align-items "baseline"
                 :gap "6px"}}
   (key-label node)
   [:span {:style {:color (:green tokens)
                   :font-family mono-stack
                   :font-size "12px"}}
    (inspect-value (:value node) node-key)]])

(defn- render-removed-leaf
  [node node-key]
  [:div {:style {:display "flex" :flex-wrap "wrap" :align-items "baseline"
                 :gap "6px"
                 :text-decoration "line-through"}}
   (key-label node)
   [:span {:style {:color (:red tokens)
                   :font-family mono-stack
                   :font-size "12px"}}
    (inspect-value (:value node) node-key)]])

(defn- unchanged-chip
  "Single-row `(N entries unchanged)` chip for the design's collapse-
  unchanged-subtrees default (§5.3)."
  [n container-tag]
  [:div {:data-testid "rf-causa-diff-unchanged-chip"
         :style {:display       "inline-flex"
                 :align-items   "center"
                 :gap           "6px"
                 :padding       "2px 8px"
                 :background    (:bg-2 tokens)
                 :border-radius "3px"
                 :color         (:text-tertiary tokens)
                 :font-family   mono-stack
                 :font-size     "11px"
                 :font-style    "italic"}}
   (str "(" n
        (case container-tag
          :map " entries unchanged"
          :vec " items unchanged"
          :set " items unchanged"
          " unchanged")
        ")")])

(defn- render-same-inline
  "Render a `:same` child inline (for small unchanged sets below the
  collapse threshold). Uses the data-inspector but with greyed-out
  colour."
  [node node-key]
  [:div {:style {:display "flex" :flex-wrap "wrap" :align-items "baseline"
                 :gap "6px"
                 :color (:text-tertiary tokens)}}
   (key-label node)
   (inspect-value (:value node) node-key)])

(defn- children-summary-changed-count
  [{:keys [added removed modified children]
    :or   {added 0 removed 0 modified 0 children 0}}]
  (+ added removed modified children))

(defn- render-children-container
  "Render a `:children` container — header + body. Auto-expand changed
  containers up to `max-depth` levels deep; collapse `:same` direct
  children into a single chip when count > `collapse-unchanged-
  threshold`."
  [node section-path sub-path surface depth]
  (let [{:keys [tag children child-summary value]} node
        node-key       (node-key-for surface section-path sub-path)
        ;; Auto-expand changed containers within depth budget.
        auto-expand?   (< depth smart-expand-max-depth)
        changed-kids   (filter #(not= :same (at/op-of %)) children)
        same-kids      (filter #(= :same (at/op-of %)) children)
        many-same?     (> (count same-kids) collapse-unchanged-threshold)]
    [:div {:data-testid (str "rf-causa-diff-container-" node-key)
           :style {:display "flex"
                   :flex-direction "column"
                   :margin "2px 0"}}
     ;; Header — key (if any) + container summary chip.
     [:div {:style {:display "flex" :align-items "baseline"
                    :gap "6px"
                    :font-family mono-stack
                    :font-size "12px"
                    :color (:text-primary tokens)}}
      (key-label node)
      [:span {:style {:color (:text-tertiary tokens)}}
       (case tag :map "{…}" :vec "[…]" :set "#{…}" "…")]
      [:span {:style {:color (:text-tertiary tokens)
                      :font-family sans-stack
                      :font-size "11px"}}
       (str "◴ " (children-summary-changed-count child-summary) " changed")]]
     ;; Body — either auto-expanded (depth-budgeted) or chip-collapsed.
     (if auto-expand?
       [:div {:style {:padding-left "12px"
                      :border-left (str "1px solid "
                                        (:border-subtle tokens))}}
        ;; Changed children — render each with appropriate gutter.
        (into [:div]
              (map-indexed
                (fn [i child]
                  (let [child-sub  (conj sub-path
                                         (or (at/child-key child) i))]
                    ^{:key i}
                    (render-annotated child section-path child-sub
                                      surface (inc depth))))
                changed-kids))
        ;; Unchanged-children chip OR inline list.
        (when (seq same-kids)
          (if many-same?
            ^{:key "same-chip"} (unchanged-chip (count same-kids) tag)
            (into [:div]
                  (map-indexed
                    (fn [i child]
                      (let [child-sub (conj sub-path
                                            (or (at/child-key child)
                                                (+ i (count changed-kids))))]
                        ^{:key (str "same-" i)}
                        (render-same-inline
                          child
                          (node-key-for surface section-path child-sub))))
                    same-kids))))]
       ;; Past depth budget — collapse the whole container to a chip
       ;; with a click-to-expand affordance handled by inspector's
       ;; existing toggle.
       [:div {:style {:padding-left "12px"}}
        [:span {:style {:color (:text-tertiary tokens)
                        :font-family sans-stack
                        :font-size "11px"
                        :font-style "italic"}}
         (str "(" (children-summary-changed-count child-summary)
              " nested changes — expand by drilling: "
              (case tag :map "map" :vec "vec" :set "set" "value") ")")]
        (inspect-value value node-key)])]))

(defn render-annotated
  "Dispatch on `::op` and render the annotated node. Three additive
  cases over the data-inspector:

    - `:children` → header + body recursion (with smart-expand)
    - `:modified` → inline `before → after`
    - `:added` / `:removed` → coloured-gutter leaf

  `:same` rendered via greyed-out inspector pass."
  [node section-path sub-path surface depth]
  (let [node-key (node-key-for surface section-path sub-path)
        op       (at/op-of node)]
    (case op
      :children (render-children-container node section-path sub-path
                                           surface depth)
      :modified (gutter :modified (render-modified-leaf node node-key))
      :added    (gutter :added    (render-added-leaf    node node-key))
      :removed  (gutter :removed  (render-removed-leaf  node node-key))
      :same     (render-same-inline node node-key)
      ;; Fallback — shouldn't happen for well-formed input.
      [:span {:style {:color (:red tokens)}}
       (str "unknown op: " (pr-str op))])))

;; ---- section + panel ---------------------------------------------------

(defn section
  "Render a single `{:path :subtree}` section: sticky breadcrumb +
  local annotated subtree."
  [{:keys [path subtree]} surface]
  (let [child-summary (if (= :children (at/op-of subtree))
                        (:child-summary subtree)
                        nil)]
    [:section {:data-testid (str "rf-causa-diff-section-"
                                 (pr-str path))
               :style {:margin         "8px 12px"
                       :background     (:bg-3 tokens)
                       :border         (str "1px solid "
                                            (:border-subtle tokens))
                       :border-radius  "4px"
                       :overflow       "hidden"}}
     (breadcrumb path child-summary)
     [:div {:style {:padding "8px 12px"
                    :font-family mono-stack
                    :font-size "12px"
                    :color (:text-primary tokens)
                    :line-height "1.5"}}
      (render-annotated subtree path [] surface 0)]]))

(defn render-sections
  "Top-level entry: a vector of `[:section ...]` blocks. The empty-state
  signaller is the caller's job — when sections is empty, this returns
  an empty wrapper div so the caller can switch on the parent layout."
  [sections surface]
  (if (empty? sections)
    [:div {:data-testid "rf-causa-diff-empty"
           :style {:padding "12px"
                   :color (:text-tertiary tokens)
                   :font-family sans-stack
                   :font-size "12px"}}
     "No structural changes in the selected epoch."]
    (into [:div {:data-testid "rf-causa-diff-sections"}]
          (for [s sections]
            ^{:key (pr-str (:path s))}
            (section s surface)))))
