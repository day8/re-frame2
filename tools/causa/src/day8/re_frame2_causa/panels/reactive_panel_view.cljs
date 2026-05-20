(ns day8.re-frame2-causa.panels.reactive-panel-view
  "Root view for the View panel (rf2-e33ad · Mike-direction 2026-05-21 ·
  prior bead: rf2-wyvf2).

  Renders the canonical sub-cascade + view-re-render visualisation as
  four bare-label pipeline sections, mirroring the rf2-n4ad0 Event
  panel rhythm (thin left rail + downward chevrons):

      Subs ran (count)            entries with [code] chip
      Subs whose value changed    entries with [code] chip
      Subs that cascaded          entries
      Views re-rendered           entries — named via reg-view :name
                                  slot (fallback: var name) +
                                  [code] chip + hover-highlight

  ## Hover-highlight (rf2-e33ad)

  Hovering a view row stamps a subtle `:bg-3`-tinted highlight on the
  rendered view's root DOM node (matched by `data-rf-view` — the
  attribute the framework already stamps per Spec 006 §View tagging
  contract). The highlight is background-only — NO border / outline /
  shadow that would perturb layout. Cleared on mouseleave.

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

;; ---- hover-highlight (rf2-e33ad) ---------------------------------------
;;
;; Hover a view-row → stamp a subtle background-only highlight on the
;; rendered view's root DOM node (matched via the `data-rf-view`
;; attribute the framework already stamps per Spec 006). Cleared on
;; mouseleave.
;;
;; Why background-only: NO border / outline / shadow that would
;; perturb layout. Per Mike-direction 2026-05-21 the hover signal must
;; be subtle and must NOT shift surrounding pixels.

(def ^:private highlight-bg-color
  "The hover-highlight tint. `:bg-3` is the canonical subtle
  surface-3 token; light + dark themes both resolve through the
  CSS-variable layer (rf2-on4cm landed)."
  (:bg-3 tokens))

(defn- highlight-selector
  "Build the DOM selector for a view-id. Per Spec 006 the attribute
  value is `(str id)` — so `:rf.foo/bar` is stored as `:rf.foo/bar`."
  [view-id]
  (str "[data-rf-view='" (str view-id) "']"))

(defn- apply-highlight!
  "Stamp the highlight onto every DOM node matching `view-id`. Stashes
  the prior inline `background-color` in a custom data attribute so
  `clear-highlight!` can restore the value. CLJS-only side effect."
  [view-id]
  (when (and (exists? js/document) view-id)
    (let [nodes (.querySelectorAll js/document (highlight-selector view-id))]
      (.forEach nodes
                (fn [^js node]
                  (when-not (.getAttribute node "data-rf-causa-prior-bg")
                    (let [prior (or (.. node -style -backgroundColor) "")]
                      (.setAttribute node "data-rf-causa-prior-bg" prior)
                      (set! (.. node -style -backgroundColor)
                            highlight-bg-color)))))
      nil)))

(defn- clear-highlight!
  "Restore the prior inline `background-color` on every DOM node
  matching `view-id`. CLJS-only side effect."
  [view-id]
  (when (and (exists? js/document) view-id)
    (let [nodes (.querySelectorAll js/document (highlight-selector view-id))]
      (.forEach nodes
                (fn [^js node]
                  (let [prior (.getAttribute node "data-rf-causa-prior-bg")]
                    (when prior
                      (set! (.. node -style -backgroundColor) prior)
                      (.removeAttribute node "data-rf-causa-prior-bg")))))
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

;; ---- subs ran section -------------------------------------------------

(defn- sub-ran-row
  [payload]
  (let [sub-id (or (:sub-id payload) (:id payload))
        coord  (when sub-id (sub-coord sub-id))]
    [:div {:data-testid "rf-causa-reactive-sub-ran"
           :style row-style}
     [:span {:style {:color (:accent-violet tokens)
                     :font-weight 600}}
      (format-id sub-id)]
     (code-chip coord
                (str "rf-causa-reactive-sub-ran-code-"
                     (when sub-id (string/replace (str sub-id) #"[^a-zA-Z0-9_]" "_"))))]))

(defn- subs-ran-section
  [data]
  (let [subs-ran (:subs-ran data)
        n        (count subs-ran)]
    [:<>
     (section-label "subs-ran" (str "SUBS RAN (" n ")"))
     (if (seq subs-ran)
       (into [:div]
             (for [[i p] (map-indexed vector subs-ran)]
               (with-meta (sub-ran-row p) {:key i})))
       (empty-row "rf-causa-reactive-subs-ran-empty" "(none ran)"))]))

;; ---- subs whose value changed section --------------------------------

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

(defn- subs-changed-section
  [data]
  (let [subs-ran     (:subs-ran data)
        changed-subs (filterv sub-changed? subs-ran)
        n            (count changed-subs)]
    [:<>
     (section-label "subs-changed" (str "SUBS WHOSE VALUE CHANGED (" n ")"))
     (if (seq changed-subs)
       (into [:div]
             (for [[i p] (map-indexed vector changed-subs)]
               (with-meta (sub-ran-row p) {:key i})))
       (empty-row "rf-causa-reactive-subs-changed-empty" "(none changed)"))]))

;; ---- subs that cascaded section --------------------------------------

(defn- sub-cascaded?
  "True when a `:rf.sub/computed` payload represents a cascade (a sub
  that was triggered by another sub's value change rather than by an
  app-db write). The substrate stamps `:cause-sub` / `:cascade?` on
  the payload depending on version. Pure."
  [payload]
  (or (true? (:cascade? payload))
      (some? (:cause-sub payload))
      (= :sub-cascade (:reason payload))))

(defn- subs-cascaded-section
  [data]
  (let [subs-ran      (:subs-ran data)
        cascaded-subs (filterv sub-cascaded? subs-ran)
        n             (count cascaded-subs)]
    [:<>
     (section-label "subs-cascaded" (str "SUBS THAT CASCADED (" n ")"))
     (if (seq cascaded-subs)
       (into [:div]
             (for [[i p] (map-indexed vector cascaded-subs)]
               (with-meta (sub-ran-row p) {:key i})))
       (empty-row "rf-causa-reactive-subs-cascaded-empty" "(none cascaded)"))]))

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
         (subs-ran-section data)
         (pipeline-chevron "subs-ran")
         (subs-changed-section data)
         (pipeline-chevron "subs-changed")
         (subs-cascaded-section data)
         (pipeline-chevron "subs-cascaded")
         (views-rendered-section data)])]]))
