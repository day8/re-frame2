(ns re-frame.story.ui.sidebar
  "Story-tree sidebar. Per Stage 4 (rf2-ekai) IMPL-SPEC §4.

  Lays out the registered stories and their variants as a tree, with
  inline tag filtering. Each variant is a clickable row that updates
  the shell state's `:selected-variant`. Workspaces register below the
  story tree.

  ## Layout

      ┌──────────────────────┐
      │ filter: [tag chips ] │
      ├──────────────────────┤
      │ ▾ :story.counter     │
      │   ◦ /default         │   ← variant rows
      │   ◦ /at-five         │
      │ ▾ :story.login       │
      │   ◦ /empty           │
      ├──────────────────────┤
      │ Workspaces           │
      │ ◦ :Workspace.X/y     │
      └──────────────────────┘

  Tag filter: every distinct tag registered on a variant becomes a
  toggle. Selecting tags constrains the tree to variants whose `:tags`
  intersects the active set; empty set means 'show all'."
  (:require [re-frame.story.ui.state :as state]))

;; ---- styling -------------------------------------------------------------

(def ^:private styles
  {:wrap         {:width "260px"
                  :background "#252526"
                  :color "#cccccc"
                  :font-family "monospace"
                  :font-size "12px"
                  :border-right "1px solid #444"
                  :overflow "auto"
                  :padding "8px 0"}
   :header       {:padding "8px 12px"
                  :font-weight "bold"
                  :color "#9cdcfe"
                  :border-bottom "1px solid #333"
                  :margin-bottom "4px"}
   :section      {:padding "12px 0 4px 12px"
                  :font-weight "bold"
                  :color "#888"
                  :text-transform "uppercase"
                  :font-size "10px"
                  :letter-spacing "0.5px"}
   :tag-row      {:display "flex"
                  :flex-wrap "wrap"
                  :gap "4px"
                  :padding "8px 12px"
                  :border-bottom "1px solid #333"}
   :tag          {:padding "2px 6px"
                  :background "#37373d"
                  :color "#cccccc"
                  :border-radius "10px"
                  :cursor "pointer"
                  :font-size "10px"
                  :user-select "none"}
   :tag-active   {:background "#0e639c"
                  :color "white"}
   :story-row    {:padding "4px 12px"
                  :color "#dcdcaa"
                  :font-weight "bold"
                  :cursor "default"}
   :variant-row  {:padding "2px 12px 2px 24px"
                  :cursor "pointer"
                  :color "#cccccc"}
   :variant-row-active {:background "#094771" :color "white"}
   :empty        {:color "#666"
                  :font-style "italic"
                  :padding "8px 12px"}})

;; ---- pure: collect tags from registered variants ------------------------

(defn collect-tags
  "Return the sorted set of tags present across the registered variants.
  Pure data → data; JVM-testable."
  [variants]
  (->> (vals variants)
       (mapcat (fn [body] (or (:tags body) #{})))
       set
       sort
       vec))

;; ---- components ----------------------------------------------------------

(defn- tag-filter-row
  [variants tag-filter]
  (let [all-tags (collect-tags variants)]
    [:div {:style (:tag-row styles)}
     (if (empty? all-tags)
       [:span {:style (:empty styles)} "no tags"]
       (for [tag all-tags]
         ^{:key tag}
         [:span {:style    (merge (:tag styles)
                                  (when (contains? tag-filter tag)
                                    (:tag-active styles)))
                 :on-click (fn [_] (state/swap-state!
                                     state/toggle-tag-filter tag))}
          (str tag)]))]))

(defn- variant-row
  [variant-id selected?]
  [:div {:style    (merge (:variant-row styles)
                          (when selected? (:variant-row-active styles)))
         :on-click (fn [_] (state/swap-state!
                             state/select-variant variant-id))}
   (str "/" (name variant-id))])

(defn- story-block
  "Render one story header + its variants. `entry` shape is
  `{:story-id ... :variants [[variant-id body] ...]}` (the shape
  produced by `state/group-variants-by-story`)."
  [{:keys [story-id variants]} selected-variant]
  [:div
   [:div {:style (:story-row styles)}
    (str (or story-id "(no story)"))]
   (for [[vid _body] variants]
     ^{:key vid}
     [variant-row vid (= vid selected-variant)])])

(defn- workspace-row
  [workspace-id selected?]
  [:div {:style    (merge (:variant-row styles)
                          (when selected? (:variant-row-active styles)))
         :on-click (fn [_]
                     (state/swap-state!
                       (fn [s] (-> s
                                   (state/select-workspace workspace-id)
                                   (state/select-variant nil)))))}
   (str workspace-id)])

(defn sidebar
  "Top-level sidebar component. Reads the registry snapshot + shell
  state, builds the filtered tree, and renders."
  []
  (let [shell        @state/shell-state-atom
        registry     (state/registry-snapshot)
        tag-filter   (:tag-filter shell)
        sel-variant  (:selected-variant shell)
        sel-ws       (:selected-workspace shell)
        visible      (state/filter-variants (:variants registry) tag-filter)
        grouped      (state/group-variants-by-story visible)
        workspaces   (:workspaces registry)]
    [:div {:style (:wrap styles)}
     [:div {:style (:header styles)} "Stories"]
     [tag-filter-row (:variants registry) tag-filter]
     (if (empty? grouped)
       [:div {:style (:empty styles)}
        (if (empty? (:variants registry))
          "no variants registered"
          "no variants match the active tag filter")]
       (for [{:keys [story-id] :as entry} grouped]
         ^{:key (or story-id :nostory)}
         [story-block entry sel-variant]))
     (when (seq workspaces)
       [:div
        [:div {:style (:section styles)} "Workspaces"]
        (for [[wid _body] (sort-by key workspaces)]
          ^{:key wid}
          [workspace-row wid (= wid sel-ws)])])]))
