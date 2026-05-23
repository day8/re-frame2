(ns day8.re-frame2-causa.panels.reactive-panel-view
  "Root view for the Views panel (rf2-e33ad / rf2-8ve8z · Mike-direction
  2026-05-21 · prior beads: rf2-wyvf2, rf2-isun6 · chrome cleanup
  rf2-fhh34).

  Renders the reactive cascade flowing toward the UI as THREE STACKED
  TABLES, top→bottom mirroring the cascade (rf2-8ve8z, phase-B of the
  Views-tab redesign; phase-A landed the substrate captures in
  rf2-9hoos):

      Level 1 Subscriptions   one row per Level 1 sub that ran this
                              cascade. Columns:
                                name | changed | read by | code
                              `changed` is the accent flag from the
                              `:value-changed?` projection; `read by`
                              (rf2-y23uw) lists the views that deref this
                              sub this cascade — the shared-subscription
                              edge from `:sub-readers`; `code` is a
                              jump-to-source [code] chip from the static
                              topology coord.

      Level 2+ Subscriptions  one row per composed sub that ran.
                              Columns:
                                name | changed | inputs | read by | code
                              `inputs` lists the input-sub names ONE PER
                              LINE (the static `:<-` chain from
                              `sub-topology`); `read by` is the shared-sub
                              edge as in Level 1.

      Views                   one row per view render / unmount this
                              cascade. Columns:
                                name | action | reason
                                      `name` is hoverable (reuses the
                                      pink diagonal-stripe DOM highlight,
                                      rf2-8l03l); `action` is mount /
                                      rerender / unmount; `reason` is the
                                      changed subs THIS view reads (a
                                      reactive render) or the literal
                                      `← parent re-render` (a structural
                                      render — UNNAMED, permanent).

  The Level 1 / Level 2+ split comes from
  `re-frame.subs.tooling/sub-topology` (`:inputs []` = Level 1); the
  view action + reason come from phase-A's `:mount?` / `:deref-subs`
  fields on `:rf.view/rendered` + the new `:rf.view/unmounted` op. All
  the data wiring lives in `reactive-panel-subs/project-record` — this
  ns is pure presentation over the projected `:rf.causa/reactive-data`
  shape.

  ## Hover-highlight (rf2-e33ad / rf2-8l03l)

  Hovering a Views-table NAME cell toggles the
  `.rf-causa-view-highlight` class (rf2-8l03l) onto the rendered view's
  root DOM node (matched by `data-rf-view` — the attribute the framework
  already stamps per Spec 006 §View tagging contract). The class
  (theme/global-styles) paints a translucent PINK DIAGONAL-STRIPE
  barber-pole via `background-image` — pink-on-fainter-pink so it reads
  on both light and dark app surfaces, translucent so the view's content
  shows through. The highlight is background-only — NO border / outline /
  shadow that would perturb layout. Cleared on mouseleave by removing
  the class (no residue).

  Pure hiccup — frame isolation via the enclosing
  `[rf/frame-provider {:frame :rf/causa}]` in the shell. Subs read on
  `:rf.causa/*` (panel state) and the dynamically-bound focus via the
  spine."
  (:require [clojure.string :as string]
            [re-frame.core :as rf]
            [day8.re-frame2-causa.theme.tokens
             :refer [tokens mono-stack sans-stack]]))

;; ---- truncation budget (rf2-8ve8z · Spec 009 cascade-cap posture) -------
;;
;; A full-page cascade can run hundreds of subs / fire hundreds of view
;; renders. The substrate already caps `:rf.view/rendered` emits at 100
;; per cascade (re-frame.views/view-rendered-cap). The panel mirrors
;; that ceiling for table rows and applies a tighter inline budget to
;; the per-row list columns (inputs / reason subs) so a wide composed
;; sub or a many-reason render stays a single legible row. Overflow is
;; surfaced honestly with a muted `+N more` affordance, never silently
;; dropped.

(def ^:private max-table-rows
  "Max rows rendered per table before a `+N more` overflow row. Mirrors
  the substrate's per-cascade view-render cap (100)."
  100)

(def ^:private max-inline-list
  "Max items rendered inline in a per-row list column (inputs / reason
  changed-subs) before a `+N more` suffix. Keeps a wide sub row
  legible — the full set is in the underlying data."
  6)

;; ---- styling primitives -------------------------------------------------

(def ^:private section-label-style
  "Bare title-case label preceding each table. NOT a large h1/h2 heading;
  12px sans-stack semibold (rf2-fhh34 chrome cleanup — the titles read as
  written: `Level 1 Subscriptions` / `Level 2+ Subscriptions` / `Views`,
  no uppercase transform, no count badge)."
  {:padding       "8px 12px 4px 12px"
   :font-family   sans-stack
   :font-size     "12px"
   :font-weight   600
   :color         (:text-secondary tokens)})

(def ^:private empty-row-style
  {:padding     "2px 12px 6px 24px"
   :color       (:text-tertiary tokens)
   :font-style  "italic"
   :font-family sans-stack
   :font-size   "11px"})

;; ---- table styling (rf2-8ve8z) -----------------------------------------

(def ^:private table-style
  {:width          "100%"
   :border-collapse "collapse"
   :margin         "2px 12px 4px 24px"
   :font-family    mono-stack
   :font-size      "12px"
   :table-layout   "auto"})

(def ^:private th-style
  "Column header cell. Muted sans label echoing the section-label
  primitive so the table reads as part of the pipeline rhythm."
  {:text-align     "left"
   :padding        "2px 12px 4px 0"
   :font-family    sans-stack
   :font-size      "10px"
   :font-weight    600
   :letter-spacing "0.4px"
   :text-transform "uppercase"
   :color          (:text-tertiary tokens)
   :white-space    "nowrap"
   :vertical-align "bottom"})

(def ^:private td-style
  {:padding        "3px 12px 3px 0"
   :color          (:text-primary tokens)
   :vertical-align "top"})

(def ^:private name-cell-style
  "The `name` column — the sub-id / view-id. Violet accent + semibold so
  the identifier leads the row."
  (assoc td-style :color (:accent tokens) :font-weight 600
                  :white-space "nowrap"))

(def ^:private changed-yes-style
  "The `changed` flag when the sub's value changed this cascade — a clear
  accent so the eye lands on it."
  {:color (:accent tokens) :font-weight 600})

(def ^:private changed-no-style
  "The `changed` placeholder when unchanged — muted so the eye skips it."
  {:color (:text-tertiary tokens) :opacity "0.5"})

(def ^:private overflow-style
  "Muted `+N more` affordance for honest truncation."
  {:color       (:text-tertiary tokens)
   :font-style  "italic"
   :font-family sans-stack
   :font-size   "10px"})

;; ---- action / reason styling (rf2-8ve8z) -------------------------------

(def ^:private action->token
  "Map a view action to its accent token. mount = green (a new thing
  appears), rerender = the mode `accent` (the standard change accent,
  shared with the sub `changed` flag — `changed` = mode accent per
  §021 §10.3 / spec/022), unmount = red (a thing goes away)."
  {:mount    :green
   :rerender :accent
   :unmount  :red})

(defn- action-style
  [action]
  {:color       (get tokens (get action->token action :text-secondary))
   :font-weight 600
   :font-family sans-stack
   :font-size   "10px"
   :letter-spacing "0.3px"
   :text-transform "uppercase"})

(def ^:private structural-reason-style
  "The literal `← parent re-render` text — muted so a structural render
  reads as quieter than a reactive one (which carries named subs)."
  {:color       (:text-tertiary tokens)
   :font-style  "italic"})

(def ^:private reactive-reason-sub-style
  "A changed-sub name in a reactive view's reason cell."
  {:color (:accent tokens)})

(def ^:private none-reason-style
  "The em-dash placeholder for an unmount row's empty reason."
  {:color (:text-tertiary tokens) :opacity "0.5"})

;; ---- pure formatters ---------------------------------------------------

(defn- format-id
  [id]
  (cond
    (nil? id)        ""
    (keyword? id)    (str id)
    :else            (pr-str id)))

(defn- view-display-name
  "Resolve a view's human-friendly name. Per Mike-direction 2026-05-21
  the `reg-view :name` slot wins; fall back to the registry id
  keyword's name segment (the var-name encoded as the kw). Returns
  the string the panel renders."
  [view-id meta]
  (let [registered-name (:name meta)]
    (cond
      (and (string? registered-name) (not (string/blank? registered-name)))
      registered-name

      (keyword? view-id)
      (str view-id)

      :else
      (pr-str view-id))))

(defn- id-slug
  "Stable testid suffix for a sub-id / view-id — kw/symbol punctuation
  flattened to `_` so the row + chip testids are CSS-selector safe."
  [id]
  (when id (string/replace (str id) #"[^a-zA-Z0-9_]" "_")))

;; ---- pipeline chrome ---------------------------------------------------

(defn- section-label
  "Bare label that precedes each table. testid:
  `rf-causa-reactive-section-<id>-label`."
  [id title]
  [:div {:data-testid (str "rf-causa-reactive-section-" id "-label")
         :style       section-label-style}
   title])

(defn- empty-row
  "Render a muted placeholder for an empty table. Empty states are
  ALWAYS visible so the pipeline rhythm holds (Mike-direction
  2026-05-21)."
  [testid label]
  [:div {:data-testid testid
         :style empty-row-style}
   label])

;; ---- [code] open-chip helpers -----------------------------------------

(defn- code-chip
  "Render an open-in-editor pill matching the Event panel's `coord-chip`
  shape — a clickable `ns:line` jump-to-source affordance. Dispatches
  `:rf.causa/open-in-editor`. Returns nil when there is no usable
  `:file`."
  [coord testid]
  (when (and (map? coord) (seq (:file coord)))
    (let [ns-seg (some-> (:ns coord) str)
          line   (:line coord)
          label  (cond
                   (and ns-seg line) (str ns-seg ":" line)
                   ns-seg            ns-seg
                   line              (str ":" line)
                   :else             "[code]")]
      [:button {:data-testid testid
                :title       (str "Open " (:file coord)
                                  (when line (str ":" line)))
                :on-click    (fn [e]
                               (.stopPropagation e)
                               (rf/dispatch [:rf.causa/open-in-editor
                                             {:source-coord coord}]
                                            {:frame :rf/causa}))
                :style       {:background  "transparent"
                              :color       (:accent tokens)
                              :border      (str "1px solid " (:border-default tokens))
                              :padding     "1px 6px"
                              :border-radius "3px"
                              :cursor      "pointer"
                              :font-family mono-stack
                              :font-size   "10px"
                              :white-space "nowrap"}}
       label])))

(defn- view-coord
  "Look up the source coord for a registered view id via the registry
  meta read. Returns the structured coord."
  [view-id]
  (let [meta (rf/handler-meta :view view-id)
        file (:file meta)]
    (when (string? file)
      {:file file :line (:line meta) :column (:column meta) :ns (:ns meta)})))

;; ---- hover-highlight (rf2-e33ad / rf2-8l03l) --------------------------
;;
;; Hover a Views-table NAME cell → stamp a distinctive background-only
;; highlight on the rendered view's root DOM node (matched via the
;; `data-rf-view` attribute the framework already stamps per Spec 006).
;; Cleared on mouseleave.
;;
;; Mechanism (rf2-8l03l): toggle the Causa-namespaced
;; `.rf-causa-view-highlight` class. The class rule (in
;; `theme/global-styles` `motion-css`) paints a TRANSLUCENT PINK
;; DIAGONAL-STRIPE barber-pole via `background-image` over the view's
;; own background — pink-on-fainter-pink so it reads on BOTH light and
;; dark app surfaces, translucent so the view's content shows through.
;; A class toggle is cleaner than the old inline stash/restore: the
;; `background-image` gradient layers OVER the view's own
;; `background-color` without destroying it, and `clear-highlight!`
;; simply removes the class to fully restore the node — no
;; `data-rf-causa-prior-bg` stash, no residue.
;;
;; Why background-only: NO border / outline / shadow that would
;; perturb layout. Per Mike-direction 2026-05-21 (rf2-e33ad) the hover
;; signal must NOT shift surrounding pixels — a `background-image`
;; paints inside the existing box with zero reflow.

(def ^:private highlight-class
  "Causa-namespaced class toggled onto the hovered view's `data-rf-view`
  node. The matching CSS rule (theme/global-styles) paints the pink
  diagonal-stripe `background-image`."
  "rf-causa-view-highlight")

(defn- highlight-selector
  "Build the DOM selector for a view-id. Per Spec 006 the attribute
  value is `(str id)` — so `:rf.foo/bar` is stored as `:rf.foo/bar`."
  [view-id]
  (str "[data-rf-view='" (str view-id) "']"))

(defn- apply-highlight!
  "Toggle the pink diagonal-stripe highlight class onto every DOM node
  matching `view-id`. CLJS-only side effect."
  [view-id]
  (when (and (exists? js/document) view-id)
    (let [nodes (.querySelectorAll js/document (highlight-selector view-id))]
      (.forEach nodes
                (fn [^js node]
                  (.add (.-classList node) highlight-class)))
      nil)))

(defn- clear-highlight!
  "Remove the highlight class from every DOM node matching `view-id`,
  fully restoring the node to its original look. CLJS-only side effect."
  [view-id]
  (when (and (exists? js/document) view-id)
    (let [nodes (.querySelectorAll js/document (highlight-selector view-id))]
      (.forEach nodes
                (fn [^js node]
                  (.remove (.-classList node) highlight-class)))
      nil)))

;; ---- shared cell renderers --------------------------------------------

(defn- changed-cell
  "The `changed` column — a clear accent ✓ when the sub's value changed
  this cascade; otherwise a muted · placeholder."
  [changed?]
  [:td {:style td-style}
   (if changed?
     [:span {:style changed-yes-style} "✓"]
     [:span {:style changed-no-style} "·"])])

(defn- code-cell
  "The `code` column — the jump-to-source chip, or a muted em-dash when
  the topology carries no source coord (nil-safe degrade)."
  [coord testid]
  [:td {:style td-style}
   (or (code-chip coord testid)
       [:span {:style changed-no-style} "—"])])

(defn- overflow-line
  "Render a `+N more` line when `total` exceeds `shown`. Returns nil
  otherwise."
  [shown total]
  (when (> total shown)
    [:div {:style overflow-style} (str "+" (- total shown) " more")]))

(defn- readers-cell
  "The `read by` column (rf2-y23uw) — the views that deref THIS sub this
  cascade (the shared-subscription edge), one per line, honestly
  truncated with `+N more`. A muted em-dash when no rendered view read
  the sub (e.g. an upstream-input sub a composed sub reads but no view
  derefs directly)."
  [readers]
  (let [n     (count readers)
        shown (take max-inline-list readers)]
    [:td {:style td-style}
     (if (seq readers)
       (into [:div {:style {:display "flex" :flex-direction "column"
                            :gap "1px"}}]
             (concat
               (for [[i v] (map-indexed vector shown)]
                 ^{:key i} [:span {:style {:color (:text-secondary tokens)}}
                            (format-id v)])
               [(overflow-line max-inline-list n)]))
       [:span {:style changed-no-style} "—"])]))

;; ---- table 1: Level 1 subs --------------------------------------------

(defn- level-1-row
  "One Level 1 sub row: name | changed | read by | code.

  testids:
    row       `rf-causa-reactive-l1-row-<slug>`
    code chip `rf-causa-reactive-l1-code-<slug>`"
  [{:keys [sub-id changed? coord readers]}]
  (let [slug (id-slug sub-id)]
    [:tr {:data-testid (str "rf-causa-reactive-l1-row-" slug)
          :style       {:border-top (str "1px solid " (:border-subtle tokens))}}
     [:td {:style name-cell-style} (format-id sub-id)]
     (changed-cell changed?)
     (readers-cell readers)
     (code-cell coord (str "rf-causa-reactive-l1-code-" slug))]))

(defn- level-1-section
  [data]
  (let [rows (:level-1-subs data)
        n    (count rows)]
    [:<>
     (section-label "l1" "Level 1 Subscriptions")
     (if (seq rows)
       [:table {:data-testid "rf-causa-reactive-l1-table"
                :style       table-style}
        [:thead
         [:tr
          [:th {:style th-style} "name"]
          [:th {:style th-style} "changed"]
          [:th {:style th-style} "read by"]
          [:th {:style th-style} "code"]]]
        (into [:tbody]
              (concat
                (for [[i r] (map-indexed vector (take max-table-rows rows))]
                  (with-meta (level-1-row r) {:key i}))
                (when (> n max-table-rows)
                  [[:tr {:data-testid "rf-causa-reactive-l1-overflow"}
                    [:td {:colSpan 4 :style (assoc td-style :padding-left "0")}
                     (overflow-line max-table-rows n)]]])))]
       (empty-row "rf-causa-reactive-l1-empty" "(no Level 1 subs ran)"))]))

;; ---- table 2: Level 2+ subs -------------------------------------------

(defn- inputs-cell
  "The `inputs` column — input-sub names ONE PER LINE, honestly
  truncated with `+N more`. Muted em-dash when there are no inputs
  (defensive — a Level 2+ row always has at least one)."
  [inputs]
  (let [n     (count inputs)
        shown (take max-inline-list inputs)]
    [:td {:style td-style}
     (if (seq inputs)
       (into [:div {:style {:display "flex" :flex-direction "column"
                            :gap "1px"}}]
             (concat
               (for [[i input] (map-indexed vector shown)]
                 ^{:key i} [:span {:style {:color (:text-secondary tokens)}}
                            (format-id input)])
               [(overflow-line max-inline-list n)]))
       [:span {:style changed-no-style} "—"])]))

(defn- level-2-row
  "One Level 2+ sub row: name | changed | inputs | read by | code.

  testids:
    row       `rf-causa-reactive-l2-row-<slug>`
    code chip `rf-causa-reactive-l2-code-<slug>`"
  [{:keys [sub-id changed? inputs coord readers]}]
  (let [slug (id-slug sub-id)]
    [:tr {:data-testid (str "rf-causa-reactive-l2-row-" slug)
          :style       {:border-top (str "1px solid " (:border-subtle tokens))}}
     [:td {:style name-cell-style} (format-id sub-id)]
     (changed-cell changed?)
     (inputs-cell inputs)
     (readers-cell readers)
     (code-cell coord (str "rf-causa-reactive-l2-code-" slug))]))

(defn- level-2-section
  [data]
  (let [rows (:level-2-subs data)
        n    (count rows)]
    [:<>
     (section-label "l2" "Level 2+ Subscriptions")
     (if (seq rows)
       [:table {:data-testid "rf-causa-reactive-l2-table"
                :style       table-style}
        [:thead
         [:tr
          [:th {:style th-style} "name"]
          [:th {:style th-style} "changed"]
          [:th {:style th-style} "inputs"]
          [:th {:style th-style} "read by"]
          [:th {:style th-style} "code"]]]
        (into [:tbody]
              (concat
                (for [[i r] (map-indexed vector (take max-table-rows rows))]
                  (with-meta (level-2-row r) {:key i}))
                (when (> n max-table-rows)
                  [[:tr {:data-testid "rf-causa-reactive-l2-overflow"}
                    [:td {:colSpan 5 :style (assoc td-style :padding-left "0")}
                     (overflow-line max-table-rows n)]]])))]
       (empty-row "rf-causa-reactive-l2-empty" "(no Level 2+ subs ran)"))]))

;; ---- table 3: Views ----------------------------------------------------

(defn- reason-cell
  "The `reason` column for a Views-table row.

    :reactive   → the changed subs THIS view reads (mode-accent names,
                  honestly truncated with `+N more`).
    :structural → the literal `← parent re-render` — UNNAMED. We never
                  name the parent (permanent, rf2-8ve8z).
    :none       → unmount row → a muted em-dash."
  [reason]
  (let [kind (:kind reason)]
    [:td {:style td-style}
     (case kind
       :reactive
       (let [subs  (:subs reason)
             n     (count subs)
             shown (take max-inline-list subs)]
         (into [:div {:style {:display "flex" :flex-direction "column"
                              :gap "1px"}}]
               (concat
                 (for [[i s] (map-indexed vector shown)]
                   ^{:key i} [:span {:style reactive-reason-sub-style}
                              (format-id s)])
                 [(overflow-line max-inline-list n)])))

       :structural
       [:span {:data-testid "rf-causa-reactive-view-reason-structural"
               :style structural-reason-style}
        "← parent re-render"]

       ;; :none / unknown
       [:span {:style none-reason-style} "—"])]))

(defn- view-row
  "One Views-table row: name | action | reason. Hover the NAME cell to
  highlight the rendered view's DOM (rf2-8l03l). Each render/unmount of
  a view yields one row.

  testid: `rf-causa-reactive-view-row-<slug>` (index-suffixed so repeat
  renders of the same view stay unique)."
  [idx {:keys [view-id action reason]}]
  (let [meta      (when view-id (rf/handler-meta :view view-id))
        disp-name (view-display-name view-id meta)
        slug      (id-slug view-id)
        on-enter  (fn [_e] (apply-highlight! view-id))
        on-leave  (fn [_e] (clear-highlight! view-id))]
    [:tr {:data-testid (str "rf-causa-reactive-view-row-" slug "-" idx)
          :data-rf-causa-view-id (str view-id)
          :style       {:border-top (str "1px solid " (:border-subtle tokens))}}
     [:td {:data-testid (str "rf-causa-reactive-view-name-" slug "-" idx)
           :on-mouse-enter on-enter
           :on-mouse-leave on-leave
           :style (assoc name-cell-style :cursor "default")}
      disp-name]
     [:td {:style td-style}
      [:span {:style (action-style action)} (name action)]]
     (reason-cell reason)]))

(defn- views-section
  [data]
  (let [rows (:view-rows data)
        n    (count rows)]
    [:<>
     (section-label "views" "Views")
     (if (seq rows)
       [:table {:data-testid "rf-causa-reactive-views-table"
                :style       table-style}
        [:thead
         [:tr
          [:th {:style th-style} "name"]
          [:th {:style th-style} "action"]
          [:th {:style th-style} "reason"]]]
        (into [:tbody]
              (concat
                (for [[i r] (map-indexed vector (take max-table-rows rows))]
                  (with-meta (view-row i r) {:key i}))
                (when (> n max-table-rows)
                  [[:tr {:data-testid "rf-causa-reactive-views-overflow"}
                    [:td {:colSpan 3 :style (assoc td-style :padding-left "0")}
                     (overflow-line max-table-rows n)]]])))]
       (empty-row "rf-causa-reactive-views-empty" "(no views rendered)"))]))

;; ---- empty state ------------------------------------------------------

(defn- empty-state
  [data]
  [:div {:data-testid "rf-causa-reactive-empty"
         :style {:padding "16px"
                 :color (:text-tertiary tokens)
                 :font-family sans-stack
                 :font-size "13px"}}
   (if (nil? (:current (:focus data)))
     [:p "No event focused."]
     [:p "Focused cascade has no reactive activity captured yet."])])

;; ---- panel root --------------------------------------------------------

(defn reactive-panel
  "Plain Reagent fn — invoked from `reactive-panel/Panel` (the public
  facade reg-view) via a function call so the React-context frame tier
  resolves to `:rf/causa` inside the leaf's subscribes.

  Renders the three stacked tables (rf2-8ve8z) top→bottom mirroring the
  reactive cascade: Level 1 subs → Level 2+ subs → Views."
  []
  (let [data @(rf/subscribe [:rf.causa/reactive-data])]
    [:section {:data-testid "rf-causa-reactive"
               :style {:height "100%"
                       :display "flex"
                       :flex-direction "column"
                       :background (:bg-2 tokens)
                       :color (:text-primary tokens)
                       :font-family sans-stack
                       :font-size "14px"}}
     [:div {:style {:flex 1 :overflow "auto"}}
      (cond
        (not (:has-cascade? data))
        (empty-state data)

        :else
        [:div {:data-testid "rf-causa-reactive-pipeline"
               :style {:padding-top   "8px"
                       :padding-bottom "8px"}}
         (level-1-section data)
         (level-2-section data)
         (views-section data)])]]))
