(ns day8.re-frame2-causa.panels.hydration-pane-render
  "Per-pane hiccup-diff renderer for Causa's Hydration Debugger panel
  (Phase 4, rf2-1mcax — completes the structural-diff epic rf2-abts7).

  ## What this does

  Wraps the Phase 3 hiccup-diff micro-engine + renderer
  (`day8.re-frame2-causa.diff.hiccup` + `.hiccup-render`) for use as
  per-pane annotation in the hydration side-by-side. The hydration
  side-by-side is symmetric — the same annotated tree underlies both
  panes — but each pane surfaces the diff from its own perspective:

    - **Server pane** — the SERVER side's value is canonical.
      `:added` (present-after-only / client-only) is suppressed to
      `[absent]` ghost rows. `:removed` (server-only) renders in red
      as 'present in server but not client' (the server pane's POV).

    - **Client pane** — mirror image. `:removed` (server-only) is
      suppressed; `:added` (client-only) renders in green.

  Shared elements / shared attrs render identically on both sides
  (the `:modified` / `:element-changed` paths surface the divergent
  attrs inline). The fn-prop opaque rule + the `:highlight-fn-ref-
  changes?` toggle from Phase 3 apply uniformly — the opts map
  passed in flows straight through `classify-prop`.

  ## Why a separate ns

  The generic `hiccup-render` is symmetric — it shows both sides of
  every diff (the cljs-devtools-style before/after). The hydration
  panel needs per-side rendering — each pane shows only its own side
  of the diff with the diverging slots highlighted. The translation
  is a thin pattern-match over the `::op` keys; bolting it onto the
  generic renderer would tax every other consumer (Views drilldown,
  app-db diff) with a perspective flag they don't need.

  ## Pure hiccup

  Per the Causa convention — the renderer returns hiccup, no Reagent /
  UIx / Helix references."
  (:require [day8.re-frame2-causa.diff.hiccup :as hd]
            [day8.re-frame2-causa.diff.hiccup-render :as hd-render]
            [day8.re-frame2-causa.panels.app-db-diff-format :as f]
            [day8.re-frame2-causa.theme.tokens
             :refer [tokens mono-stack sans-stack]]
            [day8.re-frame2-causa.views.edn-widget.widget :as edn]))

;; ---- node-key + small helpers ------------------------------------------

(defn- node-key-for
  "Stable per-node expand-state key combining surface + perspective +
  path. The perspective enters the key so server / client panes have
  independent expand state for the same path."
  [surface perspective path]
  (str surface "/hydration/" (name perspective) "/" (pr-str (vec path))))

(defn- inspect-value
  [v node-key]
  (edn/inspect (f/display-value v) node-key))

(defn- inspect-inline
  [v]
  (edn/inspect-inline (f/display-value v)))

;; ---- absent / present chips --------------------------------------------

(defn- absent-chip
  "Render the 'this side does not have this node' chip. Used when the
  pane's perspective is server and the engine emitted `:added`
  (client-only), or when the perspective is client and the engine
  emitted `:removed` (server-only)."
  [label]
  [:span {:data-testid "rf-causa-hydration-pane-absent"
          :style {:display       "inline-block"
                  :padding       "0 6px"
                  :margin        "1px 0"
                  :border        (str "1px dashed " (:border-default tokens))
                  :border-radius "2px"
                  :background    (:bg-3 tokens)
                  :color         (:text-tertiary tokens)
                  :font-family   mono-stack
                  :font-size     "11px"
                  :font-style    "italic"}}
   (str "absent on this side · was " label)])

(defn- present-chip
  "Render the 'present only on this side' annotation. The colour signals
  perspective — green when this pane has it and the other does not."
  [tone label value nkey]
  [:div {:data-testid "rf-causa-hydration-pane-only-here"
         :style {:display       "flex"
                 :align-items   "baseline"
                 :gap           "4px"
                 :padding       "2px 0"
                 :padding-left  "6px"
                 :border-left   (str "3px solid " tone)}}
   [:span {:style {:flex          "0 0 14px"
                   :color         tone
                   :font-family   mono-stack
                   :font-size     "12px"
                   :font-weight   700}}
    label]
   [:span {:style {:color (:text-primary tokens)
                   :font-family mono-stack
                   :font-size "12px"}}
    (inspect-value value nkey)]])

(defn- key-or-index-label
  "Same label as the generic hiccup-render — but kept local so we don't
  reach into private vars in `hiccup-render`."
  [node]
  (cond
    (some? (:key node))
    [:span {:style {:color (:accent-violet tokens)
                    :font-family mono-stack
                    :font-size "11px"
                    :margin-right "6px"}}
     (str ":key " (pr-str (:key node)))]

    (some? (:index node))
    [:span {:style {:color (:text-tertiary tokens)
                    :font-family mono-stack
                    :font-size "11px"
                    :margin-right "6px"}}
     (str "[" (:index node) "]")]

    :else nil))

;; ---- attrs renderer ----------------------------------------------------

(declare render-pane-node)

(defn- render-attr-row
  "Render one attr from the attrs-diff, perspective-aware. Same colour
  vocabulary as the generic hiccup renderer for `:modified` (both
  sides differ) + `:fn-ref-changed`; `:added` / `:removed` are flipped
  per perspective."
  [attr-node perspective parent-key]
  (let [op   (hd/op-of attr-node)
        k    (:key attr-node)
        nkey (str parent-key "/attr/" (pr-str k))]
    (case op
      :same
      [:div {:style {:display "flex" :gap "6px"
                     :color (:text-tertiary tokens)
                     :font-family mono-stack :font-size "12px"}}
       [:span {:style {:color (:accent-violet tokens)}} (pr-str k)]
       (inspect-value (:value attr-node) nkey)]

      ;; :added = attr present on CLIENT only. Server pane shows it as
      ;; 'absent on this side'; client pane shows it in green.
      :added
      (case perspective
        :server
        [:div {:data-testid (str "rf-causa-hydration-attr-server-absent-" (pr-str k))
               :style {:display "flex" :gap "6px"
                       :color (:text-tertiary tokens)
                       :font-family mono-stack :font-size "12px"
                       :opacity 0.6}}
         [:span {:style {:color (:accent-violet tokens)}} (pr-str k)]
         (absent-chip (pr-str (:value attr-node)))]

        :client
        [:div {:data-testid (str "rf-causa-hydration-attr-client-only-" (pr-str k))
               :style {:display "flex" :gap "6px"
                       :padding-left "6px"
                       :border-left (str "3px solid " (:green tokens))}}
         [:span {:style {:color (:accent-violet tokens)
                         :font-family mono-stack :font-size "12px"}}
          (pr-str k)]
         [:span {:style {:color (:green tokens)
                         :font-family mono-stack :font-size "12px"}}
          (inspect-value (:value attr-node) nkey)]])

      ;; :removed = attr present on SERVER only.
      :removed
      (case perspective
        :server
        [:div {:data-testid (str "rf-causa-hydration-attr-server-only-" (pr-str k))
               :style {:display "flex" :gap "6px"
                       :padding-left "6px"
                       :border-left (str "3px solid " (:red tokens))}}
         [:span {:style {:color (:accent-violet tokens)
                         :font-family mono-stack :font-size "12px"}}
          (pr-str k)]
         [:span {:style {:color (:red tokens)
                         :font-family mono-stack :font-size "12px"}}
          (inspect-value (:value attr-node) nkey)]]

        :client
        [:div {:data-testid (str "rf-causa-hydration-attr-client-absent-" (pr-str k))
               :style {:display "flex" :gap "6px"
                       :color (:text-tertiary tokens)
                       :font-family mono-stack :font-size "12px"
                       :opacity 0.6}}
         [:span {:style {:color (:accent-violet tokens)}} (pr-str k)]
         (absent-chip (pr-str (:value attr-node)))])

      :modified
      [:div {:data-testid (str "rf-causa-hydration-attr-modified-" (pr-str k))
             :style {:display "flex" :flex-wrap "wrap" :gap "6px"
                     :align-items "baseline"
                     :padding-left "6px"
                     :border-left (str "3px solid " (:yellow tokens))}}
       [:span {:style {:color (:accent-violet tokens)
                       :font-family mono-stack :font-size "12px"}}
        (pr-str k)]
       [:span {:style {:color (:yellow tokens)
                       :font-family mono-stack :font-size "12px"}}
        (inspect-value
          (case perspective
            :server (:before attr-node)
            :client (:after  attr-node))
          (str nkey "/" (name perspective)))]
       [:span {:style {:color (:text-tertiary tokens)
                       :font-family mono-stack :font-size "11px"
                       :font-style "italic"}}
        (case perspective
          :server (str "(client: " (pr-str (:after  attr-node)) ")")
          :client (str "(server: " (pr-str (:before attr-node)) ")"))]]

      :fn-ref-changed
      [:div {:data-testid (str "rf-causa-hydration-attr-fn-ref-changed-" (pr-str k))
             :style {:display "flex" :gap "6px"
                     :align-items "baseline"
                     :padding-left "6px"
                     :border-left (str "3px solid " (:accent-violet tokens))}}
       [:span {:style {:color (:accent-violet tokens)
                       :font-family mono-stack :font-size "12px"}}
        (pr-str k)]
       [:span {:style {:color (:accent-violet tokens)
                       :font-family mono-stack :font-size "11px"
                       :font-style "italic"}}
        "(fn ref changed)"]]

      ;; Defensive fallback.
      [:span {:style {:color (:red tokens)}}
       (str "unknown attr op: " (pr-str op))])))

(defn- render-attrs-diff
  "Render the per-attr rows for a pane. Pure-:same attrs collapse into
  nothing — same as the generic renderer."
  [attrs-diff perspective parent-key]
  (let [kids    (:children attrs-diff)
        changed (filter #(not= :same (hd/op-of %)) kids)]
    (when (seq changed)
      (into [:div {:data-testid (str "rf-causa-hydration-pane-attrs-" (name perspective))
                   :style {:padding-left "12px"
                           :margin "2px 0"}}]
            (for [c changed]
              ^{:key (pr-str (:key c))}
              (render-attr-row c perspective parent-key))))))

;; ---- children renderer -------------------------------------------------

(defn- render-children-diff
  [children-diff perspective surface parent-path depth]
  (when (seq children-diff)
    (into [:div {:data-testid (str "rf-causa-hydration-pane-children-" (name perspective))
                 :style {:padding-left "12px"
                         :border-left (str "1px solid " (:border-subtle tokens))
                         :margin "2px 0"}}]
          (map-indexed
            (fn [i c]
              (let [k    (or (:key c) (:index c) i)
                    path (conj parent-path k)]
                ^{:key (pr-str k)}
                (render-pane-node c perspective surface path (inc depth))))
            children-diff))))

;; ---- element header ----------------------------------------------------

(defn- element-header
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

;; ---- per-op renderers --------------------------------------------------

(defn- render-element-changed
  [node perspective surface path depth]
  (let [{:keys [tag attrs-diff children-diff]} node
        nkey         (node-key-for surface perspective path)
        attrs-count  (count (:children attrs-diff))
        kids-count   (count children-diff)
        any-changes? (or (some #(not= :same (hd/op-of %)) (:children attrs-diff))
                         (some #(not= :same (hd/op-of %)) children-diff))]
    [:div {:data-testid (str "rf-causa-hydration-pane-element-" nkey)
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
     (render-attrs-diff attrs-diff perspective nkey)
     (render-children-diff children-diff perspective surface path depth)]))

(defn- render-element-moved
  "Element moved (same key, different index). Same render on both sides
  — the move is symmetric."
  [node perspective surface path depth]
  (let [{:keys [value from-index to-index inner-diff]} node
        nkey (node-key-for surface perspective path)]
    [:div {:data-testid "rf-causa-hydration-pane-moved"
           :style {:display "flex"
                   :flex-direction "column"
                   :margin "2px 0"}}
     [:div {:style {:display "flex" :align-items "baseline" :gap "6px"
                    :padding-left "6px"
                    :border-left (str "3px solid " (:accent-violet tokens))
                    :font-family mono-stack :font-size "12px"}}
      (key-or-index-label node)
      [:span {:style {:color (:accent-violet tokens)
                      :font-weight 600}}
       "moved"]
      [:span {:style {:color (:text-tertiary tokens)
                      :font-size "11px"
                      :font-style "italic"}}
       (str "(was at index " from-index ", now at " to-index ")")]
      [:span {:style {:color (:text-secondary tokens)}}
       (inspect-value value nkey)]]
     (when inner-diff
       (render-pane-node inner-diff perspective surface
                         (conj path :inner) (inc depth)))]))

(defn- render-same-leaf
  [node perspective surface path]
  (let [nkey (node-key-for surface perspective path)]
    [:div {:style {:display "flex" :gap "6px"
                   :color (:text-tertiary tokens)
                   :font-family mono-stack :font-size "12px"}}
     (key-or-index-label node)
     (inspect-value (:value node) nkey)]))

(defn- render-added-leaf
  "`:added` = present-on-CLIENT-only. Server pane shows absent; client
  pane shows green."
  [node perspective surface path]
  (let [nkey (node-key-for surface perspective path)]
    (case perspective
      :server
      [:div {:data-testid "rf-causa-hydration-pane-server-absent"
             :style {:display "flex" :gap "6px"
                     :color (:text-tertiary tokens)
                     :font-family mono-stack :font-size "12px"
                     :opacity 0.6}}
       (key-or-index-label node)
       (absent-chip (pr-str (:value node)))]

      :client
      (present-chip (:green tokens) "+" (:value node) nkey))))

(defn- render-removed-leaf
  "`:removed` = present-on-SERVER-only. Server pane shows red; client
  pane shows absent."
  [node perspective surface path]
  (let [nkey (node-key-for surface perspective path)]
    (case perspective
      :server
      (present-chip (:red tokens) "-" (:value node) nkey)

      :client
      [:div {:data-testid "rf-causa-hydration-pane-client-absent"
             :style {:display "flex" :gap "6px"
                     :color (:text-tertiary tokens)
                     :font-family mono-stack :font-size "12px"
                     :opacity 0.6}}
       (key-or-index-label node)
       (absent-chip (pr-str (:value node)))])))

(defn- render-modified-leaf
  "`:modified` = both sides have a value; values differ. Show this
  side's value with the other side's value as a faint annotation."
  [node perspective surface path]
  (let [nkey (node-key-for surface perspective path)
        mine (case perspective
               :server (:before node)
               :client (:after  node))
        theirs (case perspective
                 :server (:after  node)
                 :client (:before node))]
    [:div {:data-testid (str "rf-causa-hydration-pane-modified-" (name perspective))
           :style {:display "flex" :flex-wrap "wrap" :gap "6px"
                   :align-items "baseline"
                   :padding-left "6px"
                   :border-left (str "3px solid " (:yellow tokens))}}
     (key-or-index-label node)
     [:span {:style {:color (:yellow tokens)
                     :font-family mono-stack
                     :font-size "12px"}}
      (inspect-value mine (str nkey "/mine"))]
     [:span {:style {:color (:text-tertiary tokens)
                     :font-family mono-stack
                     :font-size "11px"
                     :font-style "italic"}}
      (str "(" (case perspective :server "client" :client "server") ": "
           (pr-str theirs) ")")]]))

(defn- render-fn-ref-changed-leaf
  [node perspective _surface _path]
  [:div {:data-testid (str "rf-causa-hydration-pane-fn-ref-changed-" (name perspective))
         :style {:display "flex" :gap "6px"
                 :align-items "baseline"
                 :padding-left "6px"
                 :border-left (str "3px solid " (:accent-violet tokens))
                 :font-family mono-stack
                 :font-size "12px"
                 :color (:accent-violet tokens)
                 :font-style "italic"}}
   (key-or-index-label node)
   "(fn ref changed)"])

;; ---- public dispatch ---------------------------------------------------

(defn render-pane-node
  "Dispatch on the hiccup-diff `::op` and render the annotated node
  from `perspective`'s POV (`:server` or `:client`).

  Per Phase 4 (rf2-1mcax) — each pane shows the SAME annotated tree
  but flips `:added` ↔ `:removed` semantics based on perspective."
  [node perspective surface path depth]
  (let [op (hd/op-of node)]
    (case op
      :element-changed (render-element-changed node perspective surface path depth)
      :element-moved   (render-element-moved   node perspective surface path depth)
      :fn-ref-changed  (render-fn-ref-changed-leaf node perspective surface path)
      :same            (render-same-leaf       node perspective surface path)
      :added           (render-added-leaf      node perspective surface path)
      :removed         (render-removed-leaf    node perspective surface path)
      :modified        (render-modified-leaf   node perspective surface path)
      ;; Defensive fallback — shouldn't happen for well-formed input.
      [:span {:style {:color (:red tokens)}}
       (str "unknown hiccup op: " (pr-str op))])))

(defn render-pane
  "Top-level entry for a per-pane hydration render. Takes the annotated
  tree (already diffed), a `:server` / `:client` perspective, a stable
  `surface` string for expand state, and returns a hiccup tree the
  caller drops into its layout.

  When the diff returns `::op :same` (both sides identical at this
  subtree), renders a 'no structural difference' chip."
  [annotated perspective surface]
  [:div {:data-testid (str "rf-causa-hydration-pane-render-" (name perspective))
         :style {:font-family mono-stack
                 :font-size "12px"
                 :color (:text-primary tokens)
                 :line-height "1.5"
                 :padding "12px"}}
   (if (and (= :same (hd/op-of annotated))
            (not (hd/changed? annotated)))
     [:div {:data-testid (str "rf-causa-hydration-pane-no-diff-" (name perspective))
            :style {:color (:text-tertiary tokens)
                    :font-family sans-stack
                    :font-size "11px"
                    :font-style "italic"}}
      (str "No structural difference at this subtree from the "
           (name perspective) " perspective.")]
     (render-pane-node annotated perspective surface [] 0))])
