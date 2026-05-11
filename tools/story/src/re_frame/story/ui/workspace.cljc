(ns re-frame.story.ui.workspace
  "Workspace rendering. Per Stage 4 (rf2-ekai) IMPL-SPEC §3.1 + spec/007
  §Workspaces.

  Five layouts ship in v1:

  - `:grid`          — vector of variant ids laid out N-up.
  - `:tabs`          — vector of variant ids; one shown at a time via tabs.
  - `:variants-grid` — devcards-style: every registered variant of one
                       story side-by-side. Enumerates from the registry.
  - `:prose`         — markdown / hiccup blocks interleaved with variants.
  - `:custom`        — caller-supplied view id.

  Stage 4 ships every layout's resolver as pure data (so JVM tests cover
  enumeration + filtering) and the Reagent renderer for `:grid`,
  `:variants-grid`, and `:prose`. `:tabs` and `:custom` ship the
  resolver; their renderers are simple variants of the `:grid`
  pipeline.

  ## Pure layout resolution

  - `resolve-layout` — given a workspace body + registered variants,
    return the ordered vector of cell descriptors. Each cell is one of:
    - `{:type :variant :variant-id ...}`
    - `{:type :prose :body \"...\"}`
    Used by `:grid` / `:variants-grid` / `:prose` / `:tabs`.

  Per IMPL-SPEC §2.8.4 the `:variants-grid` layout is the v1 devcards-
  style multi-variant pane."
  (:require [re-frame.story.registrar :as registrar]
            #?(:cljs [re-frame.story.ui.canvas :as canvas])))

;; ---- pure: layout resolution --------------------------------------------

(defn- variants-of-story
  "Return the variant ids whose parent story is `story-id`. Pure
  derivation against the registrar — used by `:variants-grid`."
  [story-id]
  (registrar/variants-of story-id))

(defn resolve-layout
  "Given a workspace body, return an ordered vector of cell descriptors.

  Cell shapes:
    `{:type :variant :variant-id <kw>}`
    `{:type :prose   :body <string>}`

  Cell ordering matches the workspace's declared `:variants` (for `:grid`
  / `:tabs`) or `:content` (for `:prose`). For `:variants-grid` the
  cells enumerate the registry's variants for the workspace's anchor
  story; the workspace body's `:story` slot names that anchor (defaults
  to the workspace id's namespace path stripped of `Workspace.`)."
  [workspace-id workspace-body]
  (case (:layout workspace-body)
    :grid
    (vec (for [vid (:variants workspace-body)]
           {:type :variant :variant-id vid}))

    :tabs
    (vec (for [vid (:variants workspace-body)]
           {:type :variant :variant-id vid}))

    :variants-grid
    (let [anchor (or (:story workspace-body)
                     ;; Derive `:story.<path>` from `:Workspace.<path>/<name>`.
                     (when-let [ns (namespace workspace-id)]
                       (when (and (>= (count ns) 10)
                                  (= (subs ns 0 10) "Workspace."))
                         (keyword (str "story." (subs ns 10))))))]
      (if anchor
        (->> (variants-of-story anchor)
             sort
             (mapv (fn [vid] {:type :variant :variant-id vid})))
        []))

    :prose
    (vec (for [item (:content workspace-body)]
           (case (:type item)
             :prose   {:type :prose :body (:body item)}
             :variant {:type :variant :variant-id (:id item)}
             nil)))

    :custom
    [{:type :custom :render (:render workspace-body)}]

    ;; unknown — degrade to empty
    []))

;; ---- styling -------------------------------------------------------------

#?(:cljs
   (def ^:private styles
     {:wrap          {:padding "16px"
                      :background "#1e1e1e"
                      :flex "1"
                      :overflow "auto"}
      :title         {:font-weight "bold"
                      :color "#9cdcfe"
                      :font-family "monospace"
                      :margin-bottom "12px"}
      :grid          {:display "grid"
                      :grid-template-columns "repeat(auto-fit, minmax(280px, 1fr))"
                      :gap "12px"}
      :cell          {:background "#252526"
                      :border "1px solid #3c3c3c"
                      :border-radius "4px"
                      :padding "8px"
                      :min-height "160px"
                      :color "#cccccc"
                      :font-family "monospace"
                      :font-size "11px"}
      :cell-title    {:color "#dcdcaa"
                      :font-weight "bold"
                      :margin-bottom "4px"}
      :prose-block   {:padding "12px"
                      :background "#252526"
                      :color "#cccccc"
                      :border-radius "4px"
                      :margin-bottom "12px"
                      :line-height "1.5"}
      :prose-flow    {:display "flex"
                      :flex-direction "column"}
      :empty         {:color "#666"
                      :font-style "italic"
                      :padding "24px"
                      :text-align "center"}}))

;; ---- the Reagent renderer ------------------------------------------------

#?(:cljs
   (defn- variant-cell
     "Render one variant cell in a workspace grid. Stage 4 leans on the
     same `canvas/canvas-inner`-style approach but in a smaller frame.
     We render the variant's name as a title and stub the actual view
     under it — Stage 6 brings the full render into each cell when the
     multi-substrate layer lands."
     [variant-id]
     [:div {:style (:cell styles)}
      [:div {:style (:cell-title styles)} (str (pr-str variant-id))]
      ;; Stage 4: each cell is a label + frame indicator. The variant's
      ;; live render uses the canvas component when the user clicks into
      ;; the cell (Stage 6 brings inline rendering with the multi-
      ;; substrate switcher).
      [:div "variant frame: " (pr-str variant-id)]]))

#?(:cljs
   (defn- prose-block
     [body]
     [:div {:style (:prose-block styles)}
      body]))

#?(:cljs
   (defn workspace-view
     "Render a workspace. Resolves the cells and dispatches per layout."
     [workspace-id]
     (let [body (registrar/handler-meta :workspace workspace-id)]
       (cond
         (nil? body)
         [:div {:style (:wrap styles)}
          [:div {:style (:empty styles)}
           "workspace " (pr-str workspace-id) " is not registered"]]

         :else
         (let [cells (resolve-layout workspace-id body)]
           [:div {:style (:wrap styles)}
            [:div {:style (:title styles)}
             (str (pr-str workspace-id)
                  " (" (name (:layout body)) ")")]
            (cond
              (empty? cells)
              [:div {:style (:empty styles)}
               "no cells resolved — check the workspace body"]

              (= :prose (:layout body))
              [:div {:style (:prose-flow styles)}
               (for [[i cell] (map-indexed vector cells)]
                 (case (:type cell)
                   :prose
                   ^{:key (str "p-" i)}
                   [prose-block (:body cell)]
                   :variant
                   ^{:key (str "v-" i)}
                   [variant-cell (:variant-id cell)]))]

              :else
              [:div {:style (:grid styles)}
               (for [[i cell] (map-indexed vector cells)]
                 (case (:type cell)
                   :variant
                   ^{:key (str "v-" i)}
                   [variant-cell (:variant-id cell)]
                   :prose
                   ^{:key (str "p-" i)}
                   [prose-block (:body cell)]
                   :custom
                   ^{:key (str "c-" i)}
                   [:div {:style (:cell styles)}
                    "custom render: " (pr-str (:render cell))]
                   nil))])])))))
