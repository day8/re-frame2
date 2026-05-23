(ns day8.re-frame2-causa.panels.reactive-panel-view
  "Root view for the Views panel (rf2-ad7zx.6 · Figma reconcile · prior
  beads: rf2-e33ad / rf2-8ve8z / rf2-wyvf2 / rf2-isun6 · chrome cleanup
  rf2-fhh34).

  Renders the reactive cascade as a left → right REACTIVE FLOW graph —
  an inline-SVG node-and-edge canvas (NOT the prior three stacked
  tables). Reconciled to `tools/causa/spec/021-Dynamic-Panel-Designs.md`
  §3.2 + `tools/causa/design-reference/components/ViewsPanel.tsx` (the
  later iteration, authoritative over the §3.1.1 table iteration).

  ## Shape (spec/021 §3.2)

  Four columns, left → right:

      app-db        a single source node at the far left
      Level-1 subs  extractors (read app-db) — plain fan-out from app-db
      Level-2+ subs derived (`:<-` composition; OPTIONAL layer)
      views         the right-most focus; each (rerendered)

  Node + edge encoding (colour/edge first per spec/022 — NOT glyphs):

  - **changed / recomputed** node → filled tint + accent border + bold
    label; outgoing edges SOLID accent arrows that PROPAGATE downstream.
  - **unchanged / short-circuited** node → transparent fill + dashed dim
    outline + dim label; edges DASHED grey + visually CUT (no arrowhead).
  - **view** node → success-tinted box labelled `(rerendered)`; carries
    its per-view `:triggered-by` cause + `:elapsed-ms` timing
    (rf2-8wrzz.1) as a sub-label.
  - **shared subscription** → a sub read by ≥2 views fans out to N view
    nodes; the node carries a `×N` annotation.

  Below the graph two list sections record the epoch's reactive
  teardown (Figma design):

  - **UNMOUNTED VIEWS** — views whose component unmounted this epoch.
  - **DESTROYED SUBSCRIPTIONS** — subs cleaned up when their last reader
    unmounted (data-availability honest: empty until the sub-dispose op
    lands — see reactive-panel-subs/destroyed-subscriptions).

  A legend closes the panel with three swatches: changed (propagates) ·
  no change (short-circuits) · unmounted / destroyed.

  ## Why pure-SVG, not xyflow

  The Machine panel's xyflow integration (spec/021 §6) is a separate,
  interactive, draggable surface. The reactive-flow graph is a static
  cause/timing snapshot per the spec + reference, so it is a CAUSA-NATIVE
  pure-SVG graph — geometry computed by the JVM-testable
  `reactive-flow-graph/layout`, rendered here as hiccup. Mirrors
  `chart/timing-waterfall`.

  ## Hover-highlight (rf2-e33ad / rf2-8l03l — preserved)

  Hovering a view NODE toggles the `.rf-causa-view-highlight` class onto
  the rendered view's root DOM node (matched by `data-rf-view` — the
  attribute the framework stamps per Spec 006). The class paints a
  translucent pink diagonal-stripe `background-image` (theme/global-
  styles) — background-only, NO border / outline / shadow that would
  perturb layout. Cleared on mouseleave.

  Pure hiccup — frame isolation via the enclosing
  `[rf/frame-provider {:frame :rf/causa}]` in the shell."
  (:require [clojure.string :as string]
            [re-frame.core :as rf]
            [day8.re-frame2-causa.panels.reactive-flow-graph :as graph]
            [day8.re-frame2-causa.theme.tokens :as tk
             :refer [tokens mono-stack sans-stack]]))

;; ---- section chrome -----------------------------------------------------

(def ^:private section-label-style
  "Uppercase muted caption preceding each section (REACTIVE FLOW /
  UNMOUNTED VIEWS / DESTROYED SUBSCRIPTIONS), echoing the Figma
  `devtools-caption uppercase tracking-wide` heading."
  {:padding        "0 0 8px 0"
   :font-family    sans-stack
   :font-size      "10px"
   :font-weight    600
   :letter-spacing "0.6px"
   :text-transform "uppercase"
   :color          (:text-tertiary tokens)})

(defn- section-label
  "Uppercase section caption. testid:
  `rf-causa-reactive-section-<id>-label`."
  [id title]
  [:div {:data-testid (str "rf-causa-reactive-section-" id "-label")
         :style       section-label-style}
   title])

;; ---- pure formatters ---------------------------------------------------

(defn- format-id
  [id]
  (cond
    (nil? id)     ""
    (keyword? id) (str id)
    :else         (pr-str id)))

(defn- view-display-name
  "Resolve a view's human-friendly name. The `reg-view :name` slot wins;
  fall back to the registry id keyword's name. Returns the string the
  panel renders."
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
  "Stable testid suffix — kw/symbol punctuation flattened to `_`."
  [id]
  (when id (string/replace (str id) #"[^a-zA-Z0-9_]" "_")))

(defn- elapsed-label
  "Format a render's `:elapsed-ms` for the view-node sub-label. Sub-ms
  rounds to one decimal; ≥1ms rounds to whole ms. nil → nil."
  [ms]
  (when (number? ms)
    (if (< ms 1)
      (str (/ (Math/round (* ms 10.0)) 10.0) "ms")
      (str (Math/round ms) "ms"))))

;; ---- hover-highlight (rf2-e33ad / rf2-8l03l) --------------------------
;;
;; Hover a view node → stamp the pink diagonal-stripe highlight class on
;; the rendered view's root DOM node (matched via `data-rf-view`).
;; Cleared on mouseleave. Background-only — no layout perturbation.

(def ^:private highlight-class "rf-causa-view-highlight")

(defn- highlight-selector
  "DOM selector for a view-id (Spec 006 stores `(str id)`)."
  [view-id]
  (str "[data-rf-view='" view-id "']"))

(defn- apply-highlight!
  [view-id]
  (when (and (exists? js/document) view-id)
    (let [nodes (.querySelectorAll js/document (highlight-selector view-id))]
      (.forEach nodes
                (fn [^js node] (.add (.-classList node) highlight-class)))
      nil)))

(defn- clear-highlight!
  [view-id]
  (when (and (exists? js/document) view-id)
    (let [nodes (.querySelectorAll js/document (highlight-selector view-id))]
      (.forEach nodes
                (fn [^js node] (.remove (.-classList node) highlight-class)))
      nil)))

;; ---- [code] open-chip --------------------------------------------------

(defn- open-source!
  "Dispatch the jump-to-source effect for a topology coord."
  [coord e]
  (when e (.stopPropagation e))
  (rf/dispatch [:rf.causa/open-in-editor {:source-coord coord}]
               {:frame :rf/causa}))

;; ---- SVG paint helpers -------------------------------------------------

(def ^:private arrow-id "rf-causa-reactive-arrow-changed")

(defn- arrow-defs
  "The single arrowhead marker — accent-coloured, used only by changed
  (propagating) edges. Unchanged edges are visually CUT (no arrowhead)."
  []
  [:defs
   [:marker {:id arrow-id :markerWidth 9 :markerHeight 7
             :refX 8 :refY 3.5 :orient "auto"}
    [:polygon {:points "0 0, 9 3.5, 0 7" :fill (:accent tokens)}]]])

(defn- edge
  "Render one cascade edge. Changed → solid accent line + arrowhead
  (propagates). Unchanged → dashed dim line, NO arrowhead (cut)."
  [{:keys [from-id to-id x1 y1 x2 y2 changed? kind]} i]
  ^{:key (str "edge-" kind "-" i)}
  [:line (cond-> {:data-testid (str "rf-causa-reactive-edge-" (name kind))
                  :data-edge-changed (str (boolean changed?))
                  :data-edge-from (str from-id)
                  :data-edge-to (str to-id)
                  :x1 x1 :y1 y1 :x2 x2 :y2 y2}
           changed?       (assoc :stroke (:accent tokens)
                                 :stroke-width 2
                                 :marker-end (str "url(#" arrow-id ")"))
           (not changed?) (assoc :stroke (:dim tokens)
                                 :stroke-width 1
                                 :stroke-dasharray "4,3"))])

(defn- appdb-node
  "The single app-db source node at the far left."
  [{:keys [x y w h]}]
  [:g {:data-testid "rf-causa-reactive-appdb-node"}
   [:rect {:x x :y y :width w :height h :rx 4
           :fill (:bg-3 tokens)
           :stroke (:border-default tokens) :stroke-width 1.5}]
   [:text {:x (+ x (/ w 2)) :y (+ y (/ h 2) 4)
           :text-anchor "middle" :fill (:text-primary tokens)
           :font-size 12 :font-family mono-stack}
    "app-db"]])

(defn- sub-node
  "Render a Level-1 / Level-2 sub node. Changed → filled tint + accent
  border + bold; unchanged → transparent + dashed dim outline + dim
  label. A shared sub (read by ≥2 views) carries a `×N` annotation.
  Clicking the node jumps to the sub's registration source."
  [{:keys [id slug label changed? shared-count coord x y w h kind]}]
  (let [click (when coord (fn [e] (open-source! coord e)))]
    [:g (cond-> {:data-testid (str "rf-causa-reactive-node-" (name kind) "-" slug)
                 :data-node-changed (str (boolean changed?))
                 :data-node-id (str id)}
          click (assoc :on-click click
                       :style {:cursor "pointer"}))
     [:rect (cond-> {:x x :y y :width w :height h :rx 4}
              changed?       (assoc :fill (tk/with-alpha :accent 12)
                                    :stroke (:accent tokens) :stroke-width 2)
              (not changed?) (assoc :fill "transparent"
                                    :stroke (:dim tokens) :stroke-width 1
                                    :stroke-dasharray "4,2"))]
     [:text {:x (+ x (/ w 2)) :y (+ y (/ h 2) 4)
             :text-anchor "middle"
             :fill (if changed? (:accent tokens) (:dim tokens))
             :font-size 11 :font-family mono-stack
             :font-weight (if changed? 600 400)}
      label]
     (when (and shared-count (> shared-count 1))
       [:text {:data-testid (str "rf-causa-reactive-shared-" slug)
               :x (+ x w -4) :y (- y 3)
               :text-anchor "end" :fill (:text-tertiary tokens)
               :font-size 9 :font-family sans-stack}
        (str "×" shared-count)])]))

(defn- view-node
  "Render a view node — the cascade leaf + focus. Success-tinted box
  labelled `(rerendered)` (or `(mounted)`); carries the per-view
  `:triggered-by` cause + `:elapsed-ms` timing as a sub-label
  (rf2-8wrzz.1). Hovering toggles the pink DOM highlight (rf2-8l03l)."
  [{:keys [id slug label action triggered-by elapsed-ms x y w h]}]
  (let [meta      (when id (rf/handler-meta :view id))
        disp-name (view-display-name id meta)
        coord     (when (string? (:file meta))
                    {:file (:file meta) :line (:line meta) :ns (:ns meta)})
        sub-label (str "(" (if (= :mount action) "mounted" "rerendered") ")")
        cause     (when triggered-by (str "← " (format-id triggered-by)))
        timing    (elapsed-label elapsed-ms)
        meta-line (->> [cause timing] (remove nil?) (string/join " · "))]
    [:g {:data-testid (str "rf-causa-reactive-view-node-" slug)
         :data-node-id (str id)
         :data-rf-causa-view-id (str id)
         :on-mouse-enter (fn [_e] (apply-highlight! id))
         :on-mouse-leave (fn [_e] (clear-highlight! id))
         :on-click       (when coord (fn [e] (open-source! coord e)))
         :style {:cursor (if coord "pointer" "default")}}
     [:rect {:x x :y y :width w :height h :rx 4
             :fill (tk/with-alpha :success 12)
             :stroke (:success tokens) :stroke-width 2}]
     ;; view name
     [:text {:x (+ x (/ w 2)) :y (+ y 16)
             :text-anchor "middle" :fill (:success tokens)
             :font-size 11 :font-family mono-stack :font-weight 600}
      (if (and (string? disp-name) (seq disp-name)) disp-name label)]
     ;; (rerendered) + cause/timing sub-label
     [:text {:data-testid (str "rf-causa-reactive-view-meta-" slug)
             :x (+ x (/ w 2)) :y (+ y 28)
             :text-anchor "middle" :fill (:text-tertiary tokens)
             :font-size 9 :font-family sans-stack}
      (if (seq meta-line) (str sub-label "  " meta-line) sub-label)]]))

;; ---- REACTIVE FLOW graph -----------------------------------------------

(defn- flow-graph
  "Render the left → right reactive-flow SVG canvas from the pure
  `graph/layout` geometry."
  [data]
  (let [g (graph/layout data)]
    (if (:empty? g)
      [:div {:data-testid "rf-causa-reactive-graph-empty"
             :style {:padding "8px 0 16px 0"
                     :color (:text-tertiary tokens)
                     :font-family sans-stack :font-size "12px"}}
       "No subs subscribed to changed paths · no views re-rendered."]
      [:div {:data-testid "rf-causa-reactive-graph-card"
             :style {:border (str "1px solid " (:border-default tokens))
                     :border-radius "8px"
                     :padding "16px"
                     :background (:bg-1 tokens)
                     :overflow-x "auto"}}
       [:svg {:data-testid "rf-causa-reactive-flow-svg"
              :width (:width g) :height (:height g)
              :viewBox (str "0 0 " (:width g) " " (:height g))
              :style {:display "block" :max-width "100%"}}
        (arrow-defs)
        ;; edges first so nodes paint over them
        (into [:g {:data-testid "rf-causa-reactive-edges"}]
              (map-indexed (fn [i e] (edge e i)) (:edges g)))
        (appdb-node (:appdb g))
        (into [:g {:data-testid "rf-causa-reactive-l1-nodes"}]
              (map (fn [n] ^{:key (:slug n)} [sub-node n]) (-> g :nodes :l1)))
        (into [:g {:data-testid "rf-causa-reactive-l2-nodes"}]
              (map (fn [n] ^{:key (:slug n)} [sub-node n]) (-> g :nodes :l2)))
        (into [:g {:data-testid "rf-causa-reactive-view-nodes"}]
              (map (fn [n] ^{:key (:slug n)} [view-node n]) (-> g :nodes :view)))]])))

;; ---- teardown list sections (UNMOUNTED VIEWS / DESTROYED SUBS) ---------

(def ^:private list-card-style
  {:border (str "1px solid " (:border-default tokens))
   :border-radius "8px"
   :background (:bg-1 tokens)
   :overflow "hidden"})

(defn- list-row
  "One teardown-list row: a small tinted swatch + identifier + a muted
  trailing tag, matching the Figma `divide-y` list rows."
  [{:keys [testid swatch-token primary tag]}]
  [:div {:data-testid testid
         :style {:display "flex" :align-items "center" :gap "12px"
                 :padding "8px 14px"
                 :border-top (str "1px solid " (:border-subtle tokens))
                 :font-family mono-stack :font-size "12px"}}
   [:span {:style {:width "14px" :height "14px" :border-radius "3px"
                   :flex "0 0 auto"
                   :background (tk/with-alpha swatch-token 20)}}]
   [:span {:style {:flex 1 :color (:text-primary tokens)}} primary]
   [:span {:style {:color (:text-tertiary tokens)
                   :font-family sans-stack :font-size "10px"}}
    tag]])

(defn- unmounted-views-section
  [data]
  (let [rows (:unmounted-views data)]
    [:section {:data-testid "rf-causa-reactive-unmounted-section"
               :style {:margin-top "24px"}}
     (section-label "unmounted" "Unmounted Views")
     (if (seq rows)
       (into [:div {:data-testid "rf-causa-reactive-unmounted-list"
                    :style list-card-style}]
             (for [{:keys [view-id]} rows
                   :let [meta (when view-id (rf/handler-meta :view view-id))
                         nm   (view-display-name view-id meta)]]
               ^{:key (str view-id)}
               [list-row {:testid (str "rf-causa-reactive-unmounted-row-"
                                       (id-slug view-id))
                          :swatch-token :error
                          :primary nm
                          :tag "unmounted"}]))
       [:div {:data-testid "rf-causa-reactive-unmounted-empty"
              :style {:padding "2px 0" :color (:text-tertiary tokens)
                      :font-style "italic" :font-family sans-stack
                      :font-size "11px"}}
        "(no views unmounted)"])]))

(defn- destroyed-subs-section
  [data]
  (let [rows (:destroyed-subs data)]
    [:section {:data-testid "rf-causa-reactive-destroyed-section"
               :style {:margin-top "24px"}}
     (section-label "destroyed" "Destroyed Subscriptions")
     (if (seq rows)
       (into [:div {:data-testid "rf-causa-reactive-destroyed-list"
                    :style list-card-style}]
             (for [{:keys [sub-id]} rows]
               ^{:key (str sub-id)}
               [list-row {:testid (str "rf-causa-reactive-destroyed-row-"
                                       (id-slug sub-id))
                          :swatch-token :dim
                          :primary (format-id sub-id)
                          :tag "no readers remaining"}]))
       [:div {:data-testid "rf-causa-reactive-destroyed-empty"
              :style {:padding "2px 0" :color (:text-tertiary tokens)
                      :font-style "italic" :font-family sans-stack
                      :font-size "11px"}}
        "(no subscriptions destroyed)"])
     [:p {:data-testid "rf-causa-reactive-destroyed-caption"
          :style {:margin "8px 0 0 0" :color (:text-tertiary tokens)
                  :font-family sans-stack :font-size "10px"}}
      "Subscriptions cleaned up when their last reader unmounted"]]))

;; ---- legend ------------------------------------------------------------

(defn- swatch
  [style label]
  [:span {:style {:display "inline-flex" :align-items "center" :gap "8px"}}
   [:span {:style (merge {:display "inline-block" :width "12px"
                          :height "12px" :border-radius "3px"} style)}]
   label])

(defn- legend
  []
  [:div {:data-testid "rf-causa-reactive-legend"
         :style {:margin-top "24px"
                 :color (:text-tertiary tokens)
                 :font-family sans-stack :font-size "10px"}}
   [:p {:style {:margin "0 0 6px 0"}}
    "Views (right) are the focus — each: re-rendered + why (reactive vs parent re-render)"]
   [:div {:style {:display "flex" :flex-wrap "wrap" :gap "16px"
                  :align-items "center"}}
    (swatch {:background (:accent tokens)} "changed (propagates downstream)")
    (swatch {:background "transparent" :border (str "1px dashed " (:dim tokens))}
            "no change (short-circuits)")
    (swatch {:background (tk/with-alpha :error 20)} "unmounted / destroyed")]])

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

  Renders the left → right REACTIVE FLOW graph (rf2-ad7zx.6) followed by
  the UNMOUNTED VIEWS + DESTROYED SUBSCRIPTIONS sections and the closing
  legend."
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
               :style {:padding "16px"}}
         [:section {:data-testid "rf-causa-reactive-flow-section"}
          (section-label "flow" "Reactive Flow")
          (flow-graph data)]
         (unmounted-views-section data)
         (destroyed-subs-section data)
         (legend)])]]))
