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
  (:require [clojure.string :as string]
            [re-frame.core :as rf]
            [day8.re-frame2-causa.diff.annotated-tree :as at]
            ;; Phase 3 (rf2-i39w2) — hiccup-diff micro-engine renderer.
            ;; Additive dispatch: when this renderer encounters a
            ;; hiccup-diff op (`:element-changed`/`:element-moved`/
            ;; `:fn-ref-changed`) it delegates to the hiccup-specific
            ;; renderer; the generic ops still resolve here.
            [day8.re-frame2-causa.diff.hiccup-render :as hd-render]
            [day8.re-frame2-causa.panels.app-db-diff-format :as f]
            [day8.re-frame2-causa.panels.app-db-diff-helpers :as h]
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

(defn- subtree->after-value
  "Extract the 'most-useful' value at the section's path for the
  Copy-value affordance: prefer `:after` on a `:modified` leaf, then
  `:value` for `:added` / `:children` / `:same`, then fall back to
  `:value` on a `:removed` leaf (the removed value is the only one
  available for a deleted slice)."
  [subtree]
  (case (at/op-of subtree)
    :modified (:after subtree)
    (:added :children :same) (:value subtree)
    :removed  (:value subtree)
    (:value subtree)))

(defn- header-button
  "Per-button hiccup factory for the breadcrumb's affordance row. Uses
  the same chrome the slice-mini-panel used before rf2-gfxmk so the
  visual language stays consistent."
  [{:keys [testid label colour on-click]}]
  [:button {:data-testid testid
            :on-click    on-click
            :style       {:background    "transparent"
                          :color         (or colour (:text-secondary tokens))
                          :border        (str "1px solid "
                                              (:border-default tokens))
                          :padding       "1px 6px"
                          :border-radius "4px"
                          :cursor        "pointer"
                          :font-family   sans-stack
                          :font-size     "10px"}}
   label])

(defn- breadcrumb-segments
  "Render the section path as individual clickable segments (rf2-e9tb0).
  Each segment is independently clickable; a click opens the App-DB
  segment-inspector popup at the path-prefix up to and INCLUDING that
  segment. So clicking `:cart` in `[:cart :items 0 :price]` opens the
  popup at `[:cart]`; clicking `:price` opens it at the full leaf path.

  Hover styling — a dotted underline + pointer cursor — makes the
  segments discoverable as click targets without screaming through the
  diff body. The colour stays the same accent-violet that all path
  text uses, so the inline path still scans as a single phrase when
  the user isn't pointing at it."
  [section-path]
  (if (empty? section-path)
    [:span {:data-testid (str "rf-causa-diff-section-path-"
                              (pr-str section-path))
            :style       {:color (:accent-violet tokens)}}
     "(root)"]
    (into [:span {:data-testid (str "rf-causa-diff-section-path-"
                                    (pr-str section-path))
                  :style       {:color (:accent-violet tokens)
                                :display "inline-flex"
                                :flex-wrap "wrap"
                                :gap "2px"}}]
          (for [i (range (count section-path))]
            (let [seg        (nth section-path i)
                  prefix     (vec (take (inc i) section-path))
                  on-click   (fn [^js e]
                               (.preventDefault e)
                               (.stopPropagation e)
                               (rf/dispatch
                                 [:rf.causa/open-segment-inspector prefix]
                                 {:frame :rf/causa}))]
              ^{:key i}
              [:span {:data-testid (str "rf-causa-diff-breadcrumb-segment-"
                                        (pr-str section-path) "-" i)
                      :on-click    on-click
                      :title       (str "Inspect app-db at "
                                        (pr-str prefix))
                      :style       {:cursor          "pointer"
                                    :color           (:accent-violet tokens)
                                    :text-decoration "underline"
                                    :text-decoration-style "dotted"
                                    :text-underline-offset "2px"
                                    :text-decoration-color (:border-default tokens)}}
               (pr-str seg)])))))

;; ---- rf2-s8r6c — per-section origin tag chip ---------------------------
;;
;; Each section header carries an origin chip identifying the writer(s)
;; that mutated paths under this section:
;;
;;   `[fx :db]`        — the handler's `:db` effect (no flow covers it)
;;   `[flow :flow-id]` — a single flow's `:output` wrote here
;;   `[flow :a :b] + [fx :db]` — coalesced mixed section
;;
;; The chip is rendered next to the path breadcrumb at the LEFT end of
;; the header's affordance row, so it sits in the developer's eye-path
;; as they read "this section is the cart, written by …". Computation
;; is a pure helper (`app-db-diff-helpers/path-origin-tag`); the
;; renderer just turns the tag into hiccup.

(defn- origin-tag-label
  "Render an origin-tag map (the output of `path-origin-tag`) as a
  human-readable label string. Pure data → string. Used by tests as
  well as the chip renderer."
  [tag]
  (case (:kind tag)
    :fx    "[fx :db]"
    :flow  (str "[flow " (pr-str (:flow-id tag)) "]")
    :mixed (let [flow-part (str "[flow "
                                (string/join " " (map pr-str (:flow-ids tag)))
                                "]")]
             (if (:fx? tag)
               (str flow-part " + [fx :db]")
               flow-part))))

(defn- origin-tag-tone
  "Pick the chip's accent colour. Flow writes use `:accent-violet`
  (mirrors §8 FLOWS row colour in the Event lens); pure-fx writes use
  the muted `:text-tertiary` (handler `:db` is the ambient writer);
  mixed uses `:yellow` to flag the coalesced case."
  [tag]
  (case (:kind tag)
    :fx    (:text-tertiary tokens)
    :flow  (:accent-violet tokens)
    :mixed (:yellow tokens)))

(defn- origin-tag-tooltip
  [tag]
  (case (:kind tag)
    :fx    (str "Handler `:db` effect wrote here. "
                "(No flow's :output covers this section.)")
    :flow  (str "Flow " (pr-str (:flow-id tag))
                " wrote here via its :output. "
                "Per Spec 013, flows fire automatically after the handler's "
                "effects, writing their computed value to the registered "
                ":path.")
    :mixed (str "Coalesced section — multiple writers contributed: "
                (origin-tag-label tag) ". Each flow listed wrote via "
                "its :output; the [fx :db] tag (when present) indicates "
                "additional handler-effect writes inside this section.")))

(defn origin-tag-chip
  "Render the origin-tag chip for one section. Always returns a hiccup
  vector (the chip is mandatory per the rf2-s8r6c design — every diff
  row has an attributable writer).

  `section-path` is included in the `data-testid` so tests can target
  individual section chips."
  [section-path tag]
  (let [label   (origin-tag-label tag)
        colour  (origin-tag-tone tag)
        kind    (name (:kind tag))]
    [:span {:data-testid (str "rf-causa-diff-section-origin-"
                              (pr-str section-path))
            :data-origin kind
            :title       (origin-tag-tooltip tag)
            :style       {:display       "inline-flex"
                          :align-items   "center"
                          :gap           "4px"
                          :padding       "1px 6px"
                          :background    (:bg-2 tokens)
                          :border        (str "1px solid "
                                              (:border-subtle tokens))
                          :border-radius "3px"
                          :color         colour
                          :font-family   mono-stack
                          :font-size     "10px"
                          :font-weight   600
                          :user-select   "none"
                          :cursor        "help"}}
     label]))

(defn breadcrumb
  "Sticky path-header breadcrumb (per design §5.1) carrying the section
  path + the per-section affordance row (rf2-ykjl5):

      [:cart :items]  [flow :cart-total]  ⟨Show me when this changed⟩
                                          ⟨Copy path⟩ ⟨Copy value⟩
                                            ◴ 3 changes

  Path segments are individually clickable (rf2-e9tb0) — clicking any
  segment opens the App-DB segment-inspector popup at the path-prefix
  up to and including that segment. Discoverable via a dotted
  underline + pointer cursor on hover.

  The origin-tag chip (rf2-s8r6c) sits between the breadcrumb and the
  affordance row. It identifies the writer(s) that mutated paths under
  this section — `[fx :db]` for the handler's `:db` effect,
  `[flow :flow-id]` for a flow's `:output`, or mixed for coalesced
  sections.

  The Pin affordance is dropped (rf2-e9tb0 — pinned-watches replaced
  by on-demand segment inspection); the diff already identifies
  changes surgically, so pinning paths up-front is redundant.

  The container is `position: sticky; top: 0` so it stays pinned as
  the user scrolls through a long section body. Per the design's
  scroll-sticky-vs-click decision (§5.1 + §7.2 open question 5), v1
  uses scroll-sticky — the breadcrumb is the user's 'where am I'
  anchor in a long diff tree.

  `subtree-value` is the unwrapped 'after' (or fallback) value at the
  section's path, used as the payload for the Copy-value button.
  `origin-tag` is the writer-attribution map (output of
  `app-db-diff-helpers/path-origin-tag`); when nil the chip is
  omitted (older test callers that pass `[path child-summary]` or
  `[path child-summary subtree-value]` still work)."
  ([section-path child-summary]
   (breadcrumb section-path child-summary nil nil))
  ([section-path child-summary subtree-value]
   (breadcrumb section-path child-summary subtree-value nil))
  ([section-path child-summary subtree-value origin-tag]
   (let [{:keys [added removed modified children]
          :or   {added 0 removed 0 modified 0 children 0}} child-summary
         total-changes (+ added removed modified children)
         path-vec      (vec section-path)]
     [:header {:data-testid (str "rf-causa-diff-section-header-"
                                 (pr-str section-path))
               :style {:position       "sticky"
                       :top            0
                       :z-index        1
                       :display        "flex"
                       :align-items    "center"
                       :flex-wrap      "wrap"
                       :gap            "8px"
                       :padding        "6px 12px"
                       :background     (:bg-3 tokens)
                       :border-bottom  (str "1px solid "
                                            (:border-subtle tokens))
                       :font-family    mono-stack
                       :font-size      "12px"
                       :color          (:text-primary tokens)
                       :font-weight    600}}
      (breadcrumb-segments section-path)
      ;; rf2-s8r6c — origin-tag chip identifying the writer(s) for the
      ;; paths under this section. Always rendered when `origin-tag`
      ;; is supplied; older callers (tests) that omit it get no chip,
      ;; preserving the legacy hiccup shape.
      (when origin-tag
        (origin-tag-chip section-path origin-tag))
      ;; Affordance row — Show-me-when / Copy path / Copy value.
      ;; Pin was dropped under rf2-e9tb0 when pinned-watches was
      ;; superseded by the clickable-segment inspector popup.
      [:div {:data-testid (str "rf-causa-diff-section-affordances-"
                               (pr-str section-path))
             :style       {:display     "inline-flex"
                           :gap         "4px"
                           :align-items "center"}}
       (header-button
         {:testid   (str "rf-causa-diff-section-show-when-"
                         (pr-str section-path))
          :label    "Show me when this changed"
          :colour   (:magenta tokens)
          :on-click #(rf/dispatch [:rf.causa/focus-slice-path path-vec]
                                  {:frame :rf/causa})})
       (header-button
         {:testid   (str "rf-causa-diff-section-copy-path-"
                         (pr-str section-path))
          :label    "Copy path"
          :on-click #(rf/dispatch [:rf.causa/copy-path-to-clipboard path-vec]
                                  {:frame :rf/causa})})
       (header-button
         {:testid   (str "rf-causa-diff-section-copy-value-"
                         (pr-str section-path))
          :label    "Copy value"
          :on-click #(rf/dispatch [:rf.causa/copy-value-to-clipboard
                                   subtree-value]
                                  {:frame :rf/causa})})]
      (when (pos? total-changes)
        [:span {:style {:font-family sans-stack
                        :font-size "11px"
                        :color (:text-tertiary tokens)
                        :font-weight 400
                        :margin-left "auto"}}
         (str "◴ "
              total-changes
              (if (= total-changes 1) " change" " changes"))])])))

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

  Hiccup-engine ops (`:element-changed` / `:element-moved` /
  `:fn-ref-changed`, rf2-i39w2 Phase 3) carry the parallel `::hd/op`
  key — when a section subtree is a hiccup-diff (e.g. a future Views-
  panel section), this dispatch detects and delegates to the
  hiccup-specific renderer in `diff.hiccup-render`. Today the Views
  panel calls `hd-render/render-root` directly; this branch keeps the
  generic section pipeline forward-compatible.

  `:same` rendered via greyed-out inspector pass."
  [node section-path sub-path surface depth]
  (let [node-key (node-key-for surface section-path sub-path)
        op       (at/op-of node)
        hd-op    (get node :day8.re-frame2-causa.diff.hiccup/op)]
    (cond
      ;; Hiccup-engine op short-circuit — delegate. Path conjoins
      ;; section-path + sub-path to preserve the unique node-key contract.
      (#{:element-changed :element-moved :fn-ref-changed} hd-op)
      (hd-render/render-hiccup-annotated node surface
                                         (vec (concat section-path sub-path))
                                         depth)

      :else
      (case op
        :children (render-children-container node section-path sub-path
                                             surface depth)
        :modified (gutter :modified (render-modified-leaf node node-key))
        :added    (gutter :added    (render-added-leaf    node node-key))
        :removed  (gutter :removed  (render-removed-leaf  node node-key))
        :same     (render-same-inline node node-key)
        ;; Fallback — shouldn't happen for well-formed input.
        [:span {:style {:color (:red tokens)}}
         (str "unknown op: " (pr-str op))]))))

;; ---- section + panel ---------------------------------------------------

(defn section
  "Render a single `{:path :subtree}` section: sticky breadcrumb +
  local annotated subtree.

  The breadcrumb carries the per-section affordances (Pin /
  Show-me-when / Copy-path / Copy-value, rf2-ykjl5). The Copy-value
  payload is the subtree's after-value (or fallback) extracted via
  `subtree->after-value`.

  `origin-tag` (optional) is the writer-attribution map (output of
  `app-db-diff-helpers/path-origin-tag`) the breadcrumb renders as a
  chip. When omitted (older callers, test rigs), no chip renders.

  ## rf2-5kfxe.2 — diff-flash motion

  When `flash?` is truthy the section wrapper carries an `:animation`
  prop that drives the `rf-causa-diff-flash` keyframes (yellow wash
  decaying to transparent over 400ms). The animation auto-plays on
  every React mount of this element. The caller (`render-sections`)
  arranges to key the section by `epoch-id` so a new cascade lands as
  a fresh React mount and the wash retriggers.

  Duration is `calc(400ms * var(--rf-causa-motion-scale, 1))` so the
  reduced-motion seam (rf2-5kfxe.5) collapses the wash to a 0ms hold
  — the visual signal disappears entirely under
  `prefers-reduced-motion: reduce`. The `:animation-fill-mode forwards`
  pins the end state (transparent) so the row settles at `bg-3` once
  the wash decays."
  ([s surface] (section s surface nil false))
  ([s surface origin-tag] (section s surface origin-tag false))
  ([{:keys [path subtree]} surface origin-tag flash?]
   (let [child-summary (if (= :children (at/op-of subtree))
                         (:child-summary subtree)
                         nil)
         after-value   (subtree->after-value subtree)
         base-style    {:margin         "8px 12px"
                        :background     (:bg-3 tokens)
                        :border         (str "1px solid "
                                             (:border-subtle tokens))
                        :border-radius  "4px"
                        :overflow       "hidden"}
         flash-style   (when flash?
                         ;; The `--rf-causa-motion-scale` custom prop
                         ;; (rf2-5kfxe.5 lands in a later commit and
                         ;; sets it to 0 under reduced-motion). Default
                         ;; via the `var(..., 1)` fallback keeps the
                         ;; wash running even before that seam is
                         ;; wired.
                         {:animation
                          (str "rf-causa-diff-flash "
                               "calc(400ms * var(--rf-causa-motion-scale, 1)) "
                               "ease-out forwards")})]
     [:section {:data-testid (str "rf-causa-diff-section-"
                                  (pr-str path))
                :style (merge base-style flash-style)}
      (breadcrumb path child-summary after-value origin-tag)
      [:div {:style {:padding "8px 12px"
                     :font-family mono-stack
                     :font-size "12px"
                     :color (:text-primary tokens)
                     :line-height "1.5"}}
       (render-annotated subtree path [] surface 0)]])))

(defn render-sections
  "Top-level entry: a vector of `[:section ...]` blocks. The empty-state
  signaller is the caller's job — when sections is empty, this returns
  an empty wrapper div so the caller can switch on the parent layout.

  `opts` (optional) supplies the inputs the rf2-s8r6c origin-tag chip
  computation needs + the rf2-5kfxe.2 diff-flash motion input:

      {:flow-writes  [{:flow-id <kw> :write-path <vec>} ...]
       :diff-triples [{:op … :path … :before … :after …} ...]
       :epoch-id     <id>   ; rf2-5kfxe.2 — drives the per-section
                           ;   React key so a fresh epoch lands as a
                           ;   re-mount and the diff-flash keyframes
                           ;   auto-play on every changed section.
                           ;   Omit for legacy callers / test rigs.
      }

  When `opts` is omitted (older callers, test rigs), no origin chip
  renders and no flash plays — the legacy hiccup shape is preserved."
  ([sections surface] (render-sections sections surface nil))
  ([sections surface {:keys [flow-writes diff-triples epoch-id] :as _opts}]
   (if (empty? sections)
     [:div {:data-testid "rf-causa-diff-empty"
            :style {:padding "12px"
                    :color (:text-tertiary tokens)
                    :font-family sans-stack
                    :font-size "12px"}}
      "No structural changes in the selected epoch."]
     (let [origin-for (fn [section-path]
                        (when (or (seq flow-writes) (seq diff-triples))
                          (h/path-origin-tag section-path
                                             (or flow-writes [])
                                             (or diff-triples []))))
           ;; rf2-5kfxe.2 — the flash plays whenever an epoch-id is
           ;; supplied (the caller is the live panel). Test rigs that
           ;; omit `:epoch-id` get the legacy no-flash render so the
           ;; hiccup-walking tests stay deterministic.
           flash?     (some? epoch-id)]
       (into [:div {:data-testid "rf-causa-diff-sections"}]
             (for [s sections]
               ;; React key combines epoch-id + section path so a new
               ;; epoch lands as a *fresh* element per section — React
               ;; unmounts the previous wrapper and mounts a new one,
               ;; which restarts the CSS `:animation` from frame 0.
               ;; Without the epoch-id in the key React would reuse
               ;; the same DOM node and the animation would never
               ;; replay.
               ;;
               ;; Per rf2-ppzid: `^{:key …}` reader meta on a *list*
               ;; form (the `(section ...)` call) is dropped when the
               ;; call returns its fresh vector — Reagent's
               ;; `get-react-key` only reads `:key` meta from vectors.
               ;; `with-meta` on the returned vector is the correct
               ;; surface.
               (with-meta
                 (section s surface (origin-for (:path s)) flash?)
                 {:key (str (when epoch-id (str epoch-id "/"))
                            (pr-str (:path s)))})))))))
