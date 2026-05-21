(ns day8.re-frame2-causa.panels.reactive-panel-view
  "Root view for the View panel (rf2-e33ad · Mike-direction 2026-05-21 ·
  prior bead: rf2-wyvf2).

  Renders the canonical sub-cascade + view-re-render visualisation as
  two bare-label pipeline sections, mirroring the rf2-n4ad0 Event
  panel rhythm (thin left rail + downward chevrons):

      Subs this cascade (count)   ONE table — one row per sub that RAN
                                  this cascade. Columns:
                                    sub-id | changed? | cascaded? | code
                                  `changed?` ✓ via `sub-changed?`,
                                  `cascaded?` ✓ via `sub-cascaded?`
                                  (the upstream `:cause-sub` rides as
                                  secondary text on the cascaded ✓).
                                  Each sub appears EXACTLY once — the
                                  changed/cascaded dimensions are
                                  columns, not separate lists
                                  (rf2-isun6 · replaces the prior three
                                  overlapping SUBS RAN / SUBS WHOSE
                                  VALUE CHANGED / SUBS THAT CASCADED
                                  sections that repeated each sub).
      Views re-rendered (count)   entries — named via reg-view :name
                                  slot (fallback: var name) +
                                  [code] chip + hover-highlight

  ## Hover-highlight (rf2-e33ad / rf2-8l03l)

  Hovering a view row toggles the `.rf-causa-view-highlight` class
  (rf2-8l03l) onto the rendered view's root DOM node (matched by
  `data-rf-view` — the attribute the framework already stamps per
  Spec 006 §View tagging contract). The class (theme/global-styles)
  paints a translucent PINK DIAGONAL-STRIPE barber-pole via
  `background-image` — pink-on-fainter-pink so it reads on both light
  and dark app surfaces, translucent so the view's content shows
  through. The highlight is background-only — NO border / outline /
  shadow that would perturb layout. Cleared on mouseleave by removing
  the class (no residue).

  Pure hiccup — frame isolation via the enclosing
  `[rf/frame-provider {:frame :rf/causa}]` in the shell. Subs read on
  `:rf.causa/*` (panel state) and the dynamically-bound focus via the
  spine."
  (:require [clojure.string :as string]
            [re-frame.core :as rf]
            [day8.re-frame2-causa.theme.tokens
             :as t
             :refer [tokens mono-stack sans-stack]]))

;; ---- styling primitives -------------------------------------------------

(def ^:private section-label-style
  "Bare body-text label per Mike-direction 2026-05-21. NOT a large h1/
  h2 heading; uppercase 11px sans-stack matches the rf2-n4ad0 Event
  panel section labels."
  {:padding       "8px 12px 4px 12px"
   :font-family   sans-stack
   :font-size     "11px"
   :font-weight   600
   :letter-spacing "0.6px"
   :text-transform "uppercase"
   :color         (:text-secondary tokens)})

(def ^:private row-style
  {:padding     "4px 12px 4px 24px"
   :font-family mono-stack
   :font-size   "12px"
   :color       (:text-primary tokens)
   :display     "flex"
   :gap         "8px"
   :align-items "center"})

(def ^:private dim-row-style
  (assoc row-style :color (:text-tertiary tokens)))

(def ^:private empty-row-style
  {:padding     "2px 12px 6px 24px"
   :color       (:text-tertiary tokens)
   :font-style  "italic"
   :font-family sans-stack
   :font-size   "11px"})

(def ^:private chevron-style
  {:padding-left "4px"
   :color        (:text-tertiary tokens)
   :font-family  mono-stack
   :font-size    "10px"
   :line-height  1
   :user-select  "none"
   :opacity      "0.6"})

;; ---- single subs-table styling (rf2-isun6) ----------------------------

(def ^:private subs-table-style
  {:width          "100%"
   :border-collapse "collapse"
   :margin         "2px 12px 4px 24px"
   :font-family    mono-stack
   :font-size      "12px"})

(def ^:private subs-th-style
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
   :white-space    "nowrap"})

(def ^:private subs-td-style
  {:padding     "3px 12px 3px 0"
   :color       (:text-primary tokens)
   :vertical-align "top"})

(def ^:private flag-yes-style
  "The ✓ cell when a dimension is set."
  {:color (:cyan tokens) :font-weight 600})

(def ^:private flag-no-style
  "The · placeholder when a dimension is unset — dim so the eye skips it."
  {:color (:text-tertiary tokens) :opacity "0.5"})

(def ^:private cause-sub-style
  "Secondary text rendered next to the cascaded ✓ — the upstream
  `:cause-sub` that triggered the cascade. Dim so the ✓ stays primary."
  {:color (:text-tertiary tokens)
   :font-size "10px"
   :margin-left "6px"})

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

;; ---- pipeline chrome ---------------------------------------------------

(defn- section-label
  "Bare label that precedes each pipeline section's body — matches the
  rf2-n4ad0 Event panel section-label primitive. testid:
  `rf-causa-reactive-section-<id>-label`."
  [id title]
  [:div {:data-testid (str "rf-causa-reactive-section-" id "-label")
         :style       section-label-style}
   title])

(defn- pipeline-chevron
  "Small downward chevron `⋁` separating adjacent pipeline sections.
  Muted (`:text-tertiary`) so the chevron is rhythm not foreground."
  [from-id]
  [:div {:data-testid (str "rf-causa-reactive-chevron-" from-id)
         :aria-hidden "true"
         :style       chevron-style}
   "⋁"])

(defn- empty-row
  "Render a muted placeholder for an empty section. Per Mike-direction
  2026-05-21 empty states are ALWAYS visible so the pipeline rhythm
  holds."
  [testid label]
  [:div {:data-testid testid
         :style empty-row-style}
   label])

;; ---- [code] open-chip helpers -----------------------------------------

(defn- code-chip
  "Render an open-in-editor pill matching the Event panel's
  `coord-chip` shape. Dispatches `:rf.causa/open-in-editor`. Returns
  nil when there is no usable `:file`."
  [coord testid]
  (when (and (map? coord) (seq (:file coord)))
    [:button {:data-testid testid
              :on-click    (fn [e]
                             (.stopPropagation e)
                             (rf/dispatch [:rf.causa/open-in-editor
                                           {:source-coord coord}]
                                          {:frame :rf/causa}))
              :style       {:background  "transparent"
                            :color       (:cyan tokens)
                            :border      (str "1px solid " (:border-default tokens))
                            :padding     "1px 6px"
                            :border-radius "3px"
                            :margin-left "8px"
                            :cursor      "pointer"
                            :font-family mono-stack
                            :font-size   "10px"}}
     "[code]"]))

(defn- sub-coord
  "Look up the source coord for a registered sub id via the registry
  meta read. Returns the structured coord (`{:file :line ...}`) when
  the registration carries it. Pure-ish — calls into the registry."
  [sub-id]
  (let [meta (rf/handler-meta :sub sub-id)
        file (:file meta)]
    (when (string? file)
      {:file file :line (:line meta) :column (:column meta) :ns (:ns meta)})))

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
;; Hover a view-row → stamp a distinctive background-only highlight on
;; the rendered view's root DOM node (matched via the `data-rf-view`
;; attribute the framework already stamps per Spec 006). Cleared on
;; mouseleave.
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
  "Causa-namespaced class toggled onto the hovered view's
  `data-rf-view` node. The matching CSS rule (theme/global-styles)
  paints the pink diagonal-stripe `background-image`."
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

;; ---- header (outcome line; no h1) --------------------------------------

(defn- header-block
  "Compact metadata strip — replaces the large h1 heading per
  rf2-6xezz. Renders a single line with the frame + cascade counts so
  the operator has the rhythm without the heading."
  [data]
  (when (:has-cascade? data)
    [:header {:data-testid "rf-causa-reactive-header-meta"
              :style {:padding "8px 12px"
                      :border-bottom (str "1px solid " (:border-subtle tokens))
                      :background    (:bg-3 tokens)
                      :font-family   sans-stack
                      :font-size     "11px"
                      :color         (:text-tertiary tokens)}}
     (let [counts (:counts data)]
       (str "frame " (:frame data)
            " · " (or (:subs-ran counts) 0) " subs ran · "
            (or (:subs-skipped counts) 0) " skipped · "
            (or (:views-rendered counts) 0) " views rendered"))]))

;; ---- subs-this-cascade dimension predicates ---------------------------
;;
;; rf2-isun6: a sub that ran may ALSO have changed value and/or cascaded.
;; These two predicates no longer split the run-set into separate lists
;; — they drive the `changed?` / `cascaded?` columns of the single
;; subs table, so each sub appears exactly once.

(defn- sub-changed?
  "True when a `:rf.sub/computed` payload represents a value change
  rather than the (already-equal) re-evaluation. The substrate sets
  `:reason :input-changed` when the inputs differ; `:value-changed?`
  may also ride on the payload depending on substrate version. Pure."
  [payload]
  (let [reason (:reason payload)]
    (or (= :value-changed reason)
        (true? (:value-changed? payload))
        (and (contains? payload :prev-value)
             (not= (:prev-value payload) (:value payload))))))

(defn- sub-cascaded?
  "True when a `:rf.sub/computed` payload represents a cascade (a sub
  that was triggered by another sub's value change rather than by an
  app-db write). The substrate stamps `:cause-sub` / `:cascade?` on
  the payload depending on version. Pure."
  [payload]
  (or (true? (:cascade? payload))
      (some? (:cause-sub payload))
      (= :sub-cascade (:reason payload))))

;; ---- single subs-this-cascade table (rf2-isun6) -----------------------

(defn- sub-id-slug
  "Stable testid suffix for a sub-id — kw/symbol punctuation flattened
  to `_` so the row + code-chip testids are CSS-selector safe."
  [sub-id]
  (when sub-id (string/replace (str sub-id) #"[^a-zA-Z0-9_]" "_")))

(defn- flag-cell
  "A ✓ / · column cell. Renders the cyan ✓ when `on?`; otherwise a dim
  placeholder. `secondary` (optional) rides as muted text after the ✓
  (used to surface the upstream `:cause-sub` on the cascaded column)."
  [on? secondary]
  [:td {:style subs-td-style}
   (if on?
     [:span
      [:span {:style flag-yes-style} "✓"]
      (when (seq secondary)
        [:span {:style cause-sub-style} secondary])]
     [:span {:style flag-no-style} "·"])])

(defn- subs-table-row
  "One row per sub that ran this cascade. `changed?` / `cascaded?`
  columns carry the dimensions; the sub appears exactly once.

  testids:
    row       `rf-causa-reactive-sub-row-<slug>`
    code chip `rf-causa-reactive-sub-code-<slug>`"
  [payload]
  (let [sub-id   (or (:sub-id payload) (:id payload))
        slug     (sub-id-slug sub-id)
        coord    (when sub-id (sub-coord sub-id))
        changed? (sub-changed? payload)
        cause    (when (sub-cascaded? payload)
                   (let [c (:cause-sub payload)]
                     (when c (str "← " (format-id c)))))]
    [:tr {:data-testid (str "rf-causa-reactive-sub-row-" slug)
          :style       {:border-top (str "1px solid " (:border-subtle tokens))}}
     [:td {:style (assoc subs-td-style :color (:accent-violet tokens)
                         :font-weight 600)}
      (format-id sub-id)]
     (flag-cell changed? nil)
     (flag-cell (sub-cascaded? payload) cause)
     [:td {:style subs-td-style}
      (code-chip coord (str "rf-causa-reactive-sub-code-" slug))]]))

(defn- subs-table-section
  "ONE 'SUBS THIS CASCADE' table replacing the prior three overlapping
  SUBS RAN / SUBS WHOSE VALUE CHANGED / SUBS THAT CASCADED sections.
  One row per sub that RAN (the union); `changed?` / `cascaded?` are
  columns, so each sub appears exactly once (rf2-isun6)."
  [data]
  (let [subs-ran (:subs-ran data)
        n        (count subs-ran)]
    [:<>
     (section-label "subs" (str "SUBS THIS CASCADE (" n ")"))
     (if (seq subs-ran)
       [:table {:data-testid "rf-causa-reactive-subs-table"
                :style       subs-table-style}
        [:thead
         [:tr
          [:th {:style subs-th-style} "sub-id"]
          [:th {:style subs-th-style} "changed?"]
          [:th {:style subs-th-style} "cascaded?"]
          [:th {:style subs-th-style} "code"]]]
        (into [:tbody]
              (for [[i p] (map-indexed vector subs-ran)]
                (with-meta (subs-table-row p) {:key i})))]
       (empty-row "rf-causa-reactive-subs-empty" "(no subs ran)"))]))

;; ---- views re-rendered section ---------------------------------------

(defn- view-rendered-row
  "Single view-rendered row. Hover triggers a subtle background-only
  highlight on the rendered view's root DOM (matched via
  `data-rf-view`). Click [code] opens the registered source. Per
  Mike-direction 2026-05-21."
  [payload]
  (let [view-id    (or (:view-id payload) (:id payload))
        meta       (when view-id (rf/handler-meta :view view-id))
        coord      (when view-id (view-coord view-id))
        disp-name  (view-display-name view-id meta)
        on-enter   (fn [_e] (apply-highlight! view-id))
        on-leave   (fn [_e] (clear-highlight! view-id))]
    [:div {:data-testid (str "rf-causa-reactive-view-rendered")
           :data-rf-causa-view-id (str view-id)
           :on-mouse-enter on-enter
           :on-mouse-leave on-leave
           :style (assoc row-style :cursor "default")}
     [:span {:style {:color (:accent-violet tokens)
                     :font-weight 600}}
      disp-name]
     (code-chip coord
                (str "rf-causa-reactive-view-code-"
                     (when view-id (string/replace (str view-id)
                                                    #"[^a-zA-Z0-9_]"
                                                    "_"))))]))

(defn- views-rendered-section
  [data]
  (let [views (:views-rendered data)
        n     (count views)]
    [:<>
     (section-label "views-rendered" (str "VIEWS RE-RENDERED (" n ")"))
     (if (seq views)
       (into [:div]
             (for [[i v] (map-indexed vector views)]
               (with-meta (view-rendered-row v) {:key i})))
       (empty-row "rf-causa-reactive-views-empty" "(none re-rendered)"))]))

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
  resolves to `:rf/causa` inside the leaf's subscribes."
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
     (header-block data)
     [:div {:style {:flex 1 :overflow "auto"}}
      (cond
        (not (:has-cascade? data))
        (empty-state data)

        :else
        [:div {:data-testid "rf-causa-reactive-pipeline"
               :style {:border-left   (str "1px solid " (:border-subtle tokens))
                       :margin-left   "16px"
                       :padding-left  "12px"
                       :padding-top   "8px"
                       :padding-bottom "8px"}}
         (subs-table-section data)
         (pipeline-chevron "subs")
         (views-rendered-section data)])]]))
