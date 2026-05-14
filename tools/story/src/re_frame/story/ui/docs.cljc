(ns re-frame.story.ui.docs
  "The `:docs` mode pane — read-only AutoDocs-equivalent for one variant.
  Per rf2-rodx + spec/008.

  Replaces the `mode-tabs/docs-placeholder` stub that landed with the
  mode-tabs primitive (rf2-9hc8). The pane composes six sections, in
  the order Storybook 8's MDX/AutoDocs page presents them:

      ┌──────────────────────────────────────────────────────┐
      │  Header — variant id · parent story · tags chips      │
      ├──────────────────────────────────────────────────────┤
      │  Prose — markdown from a :prose workspace referencing │
      │           this variant (if any)                       │
      ├──────────────────────────────────────────────────────┤
      │  Args  — table of every resolved arg with default +  │
      │           :doc (from the variant's :argtypes entry)   │
      ├──────────────────────────────────────────────────────┤
      │  Decorators — ordered :hiccup / :frame-setup /        │
      │           :fx-override stack                          │
      ├──────────────────────────────────────────────────────┤
      │  Parameters — :modes / :substrates / :platforms       │
      │           (the variant-level metadata that isn't args  │
      │           or decorators or tags)                      │
      ├──────────────────────────────────────────────────────┤
      │  Tags — clickable chips that toggle the sidebar       │
      │           `:tag-filter` for the matching tag          │
      └──────────────────────────────────────────────────────┘

  Read-only — no editing. Args overrides live in the Canvas / Controls
  pane. Switching from `:docs` back to `:dev` (Canvas) restores any
  override the user had previously typed in the controls editor.

  ## Pure-data prose lookup

  Prose blocks come from `:layout :prose` workspaces — Story v1 has no
  per-variant `:prose` slot (a deliberate Stage-2 schema choice; prose
  belongs to a workspace, not a variant). For the docs view we walk
  every registered `:prose` workspace and collect the `:body` strings
  of any `:type :prose` content item whose neighbour `:type :variant`
  item references this variant. The walk is pure data → data and
  JVM-testable via `prose-for-variant`.

  ## Elision

  The whole namespace is CLJS-side rendering plus one `.cljc` pure
  helper (`prose-for-variant`) so JVM tests can cover the workspace
  walk. The shell's `main-pane` only reaches `docs-view` via the
  `(when config/enabled? ...)`-gated mount call, so production builds
  never invoke it — closure DCEs the lot."
  (:require [re-frame.story.predicates :as pred]
            [re-frame.story.registrar  :as registrar]
            #?@(:cljs [[re-frame.story.args :as args]
                       [re-frame.story.decorators :as decorators]
                       [re-frame.story.ui.state :as state]])))

;; ---- pure: prose lookup -------------------------------------------------

(defn prose-for-variant
  "Walk every registered `:layout :prose` workspace, find any whose
  `:content` references `variant-id`, and return a vector of
  `{:workspace-id ... :body ...}` entries for each prose block that
  sits in the same workspace.

  Pure data → data; JVM-testable. The walk is O(W·C) over the workspace
  count × content-item count — fine for dev-time use.

  A prose block belongs in the variant's docs view when its host
  workspace's `:content` list also references the variant somewhere.
  Storybook's MDX prose is per-component; re-frame2 collapses that to
  per-workspace, and a workspace that mentions the variant earns its
  prose."
  [variant-id]
  (let [workspaces (registrar/handlers :workspace)]
    (vec
      (for [[wid body] (sort-by key workspaces)
            :when     (and (= :prose (:layout body))
                           (some (fn [item]
                                   (and (= :variant (:type item))
                                        (= variant-id (:id item))))
                                 (:content body)))
            item      (:content body)
            :when     (= :prose (:type item))]
        {:workspace-id wid
         :body         (:body item)}))))

;; ---- pure: row composition -----------------------------------------------

(defn args-rows
  "Compose the rows of the args table.

  Each row is `{:key <arg-key> :value <default> :doc <string-or-nil>}`.
  The doc string comes from the variant's (or parent story's)
  `:argtypes` entry — Storybook's `argTypes.<key>.description` —
  resolved per the same merge precedence as `controls/resolve-argtypes`
  (variant beats story).

  Pure data → data so the JVM test fixture can cover the merge."
  [variant-id resolved-args]
  (let [vb       (registrar/handler-meta :variant variant-id)
        story-id (when (and (keyword? variant-id) (namespace variant-id))
                   (keyword (namespace variant-id)))
        sb       (when story-id (registrar/handler-meta :story story-id))
        atypes   (merge (:argtypes sb) (:argtypes vb))]
    (vec
      (for [[k v] (sort-by key (or resolved-args {}))]
        {:key   k
         :value v
         :doc   (let [entry (get atypes k)]
                  (cond
                    (map? entry)    (or (:doc entry) (:description entry))
                    (string? entry) entry
                    :else           nil))}))))

(defn decorator-rows
  "Compose the rows of the decorator-stack table from a
  `resolve-decorators` pack. Each row is

      {:section :hiccup|:frame-setup|:fx-override
       :id      <decorator-id>
       :doc     <:doc on body, or nil>}

  Stage 6's `:errors` slot is shown in a dedicated row group at the
  bottom (with `:section :error`)."
  [pack]
  (let [{:keys [hiccup frame-setup fx-override errors]} (or pack {})]
    (vec
      (concat
        (for [d hiccup]
          {:section :hiccup :id (:id d) :doc (:doc (:body d))})
        (for [d frame-setup]
          {:section :frame-setup :id (:id d) :doc (:doc (:body d))})
        (for [d fx-override]
          {:section :fx-override :id (:id d) :doc (:doc (:body d))})
        (for [e errors]
          {:section :error :id (:id e) :doc (str (:reason e))})))))

(defn parameter-rows
  "Compose the rows of the parameters table — the variant-level
  metadata that's neither args nor decorators nor tags. Per spec/008
  §Parameters this maps to `:modes` / `:substrates` / `:platforms` on
  the variant body, plus the story-level defaults.

  Each row is `{:key :modes|:substrates|:platforms
                 :value <set-or-vector-as-string>}`."
  [variant-id]
  (let [vb       (registrar/handler-meta :variant variant-id)
        story-id (when (and (keyword? variant-id) (namespace variant-id))
                   (keyword (namespace variant-id)))
        sb       (when story-id (registrar/handler-meta :story story-id))
        get-eff  (fn [k] (or (get vb k) (get sb k)))]
    (vec
      (keep
        (fn [k]
          (let [v (get-eff k)]
            (when (seq v) {:key k :value v})))
        [:modes :substrates :platforms]))))

(defn variant-tags
  "Return the sorted vector of tags on `variant-id`. Falls back to the
  parent story's `:tags` set if the variant body declares none. Used
  by both the docs header chips and the bottom-of-page tag picker."
  [variant-id]
  (let [vb       (registrar/handler-meta :variant variant-id)
        story-id (pred/parent-story-id variant-id)
        sb       (when story-id (registrar/handler-meta :story story-id))
        ts       (or (:tags vb) (:tags sb) #{})]
    (vec (sort ts))))

(def parent-story-id
  "Cheap parent-story derivation. Canonical definition lives in
  `re-frame.story.predicates`; aliased here so the docs header chips
  keep their `docs/parent-story-id` call shape."
  pred/parent-story-id)

;; ---- CLJS-side rendering -------------------------------------------------

#?(:cljs
   (def ^:private styles
     {:wrap          {:flex             "1"
                      :overflow         "auto"
                      :padding          "20px 28px"
                      :background       "#1e1e1e"
                      :color            "#cccccc"
                      :font-family      "system-ui, sans-serif"
                      :font-size        "13px"
                      :line-height      "1.5"}
      :h1            {:font-family      "monospace"
                      :font-size        "18px"
                      :font-weight      "bold"
                      :color            "white"
                      :margin           "0 0 4px 0"}
      :sub           {:color            "#b0b0b0"
                      :font-family      "monospace"
                      :font-size        "11px"
                      :margin-bottom    "10px"}
      :section       {:margin-top       "24px"}
      :section-h     {:font-weight      "bold"
                      :color            "#b0b0b0"
                      :text-transform   "uppercase"
                      :font-size        "10px"
                      :letter-spacing   "0.5px"
                      :margin-bottom    "8px"
                      :border-bottom    "1px solid #444"
                      :padding-bottom   "4px"}
      :doc-blurb     {:color            "#b0b0b0"
                      :font-style       "italic"
                      :margin           "4px 0 12px 0"}
      :prose-block   {:padding          "12px 16px"
                      :margin-bottom    "8px"
                      :background       "#252526"
                      :border-left      "3px solid #0e639c"
                      :color            "#dcdcdc"
                      :font-family      "system-ui, sans-serif"
                      :white-space      "pre-wrap"}
      :prose-source  {:color            "#b0b0b0"
                      :font-family      "monospace"
                      :font-size        "10px"
                      :margin-bottom    "4px"}
      :table         {:width            "100%"
                      :border-collapse  "collapse"
                      :font-family      "monospace"
                      :font-size        "11px"}
      :th            {:text-align       "left"
                      :padding          "6px 8px"
                      :background       "#2d2d30"
                      :color            "#b0b0b0"
                      :border-bottom    "1px solid #444"
                      :text-transform   "uppercase"
                      :font-size        "10px"
                      :letter-spacing   "0.5px"}
      :td            {:padding          "6px 8px"
                      :border-bottom    "1px solid #2d2d30"
                      :color            "#dcdcdc"
                      :vertical-align   "top"}
      :td-key        {:color            "#9cdcfe"
                      :white-space      "nowrap"}
      :td-value      {:color            "#ce9178"
                      :font-family      "monospace"
                      :white-space      "pre-wrap"}
      :td-doc        {:color            "#b0b0b0"
                      :font-style       "italic"}
      :section-h-row {:background       "#37373d"
                      :color            "#b0b0b0"
                      :text-transform   "uppercase"
                      :font-size        "10px"
                      :letter-spacing   "0.5px"}
      :chip-row      {:display          "flex"
                      :flex-wrap        "wrap"
                      :gap              "6px"
                      :margin-top       "4px"}
      :chip          {:padding          "3px 9px"
                      :background       "#37373d"
                      :color            "#cccccc"
                      :border           "none"
                      :border-radius    "10px"
                      :cursor           "pointer"
                      :font-family      "monospace"
                      :font-size        "10px"
                      :user-select      "none"}
      :chip-active   {:background       "#0e639c"
                      :color            "white"}
      :empty         {:color            "#9a9a9a"
                      :font-style       "italic"
                      :padding          "6px 0"}}))

#?(:cljs
   (defn- pretty-value
     "Render a default-arg value for the args table. Strings show
     quoted; everything else uses `pr-str` so keywords / maps / vectors
     are visibly distinguishable from strings."
     [v]
     (cond
       (nil? v)    "nil"
       (string? v) (pr-str v)
       :else       (pr-str v))))

#?(:cljs
   (defn- header
     "Render the variant header — id, parent story, tags as chips that
     forward-link to the sidebar tag filter."
     [variant-id]
     (let [shell      @state/shell-state-atom
           tag-filter (or (:tag-filter shell) #{})
           tags       (variant-tags variant-id)
           vb         (registrar/handler-meta :variant variant-id)
           story-id   (parent-story-id variant-id)
           sb         (when story-id (registrar/handler-meta :story story-id))
           vdoc       (:doc vb)
           sdoc       (:doc sb)]
       [:div
        [:h1 {:style (:h1 styles)}
         (str variant-id)]
        [:div {:style (:sub styles)
               :data-test "story-docs-parent-story"}
         (if story-id
           (str "parent story: " story-id)
           "no parent story registered")]
        (when (seq tags)
          [:div {:style     (:chip-row styles)
                 :data-test "story-docs-header-tags"}
           (for [tag tags]
             ^{:key tag}
             [:button
              {:style    (merge (:chip styles)
                                (when (contains? tag-filter tag)
                                  (:chip-active styles)))
               :data-test       "story-docs-tag-chip"
               :data-docs-tag   (name tag)
               :aria-pressed    (if (contains? tag-filter tag) "true" "false")
               :title           (str "Toggle tag filter for " tag)
               :on-click
               (fn [_]
                 (state/swap-state! state/toggle-tag-filter tag))}
              (str tag)])])
        (when (or (seq vdoc) (seq sdoc))
          [:div {:style     (:doc-blurb styles)
                 :data-test "story-docs-doc-blurb"}
           (or vdoc sdoc)])])))

#?(:cljs
   (defn- prose-section
     "Render the prose blocks pulled from `:prose` workspaces that
     reference this variant. Renders nothing when no prose is found —
     deliberately not a 'no prose' placeholder, the page reads cleaner
     without it (the args table is the primary docs surface)."
     [variant-id]
     (let [entries (prose-for-variant variant-id)]
       (when (seq entries)
         [:div {:style     (:section styles)
                :data-test "story-docs-prose-section"}
          [:div {:style (:section-h styles)} "Prose"]
          (for [[i {:keys [workspace-id body]}] (map-indexed vector entries)]
            ^{:key i}
            [:div {:data-test "story-docs-prose-block"}
             [:div {:style (:prose-source styles)}
              (str "from " workspace-id)]
             [:div {:style (:prose-block styles)} body]])]))))

#?(:cljs
   (defn- args-section
     "Args table — every resolved arg with default + :doc (when an
     `:argtypes` entry exists)."
     [variant-id]
     (let [shell    @state/shell-state-atom
           eff-args (args/resolve-args
                      variant-id
                      {:active-modes (:active-modes shell)
                       ;; :docs is read-only — overrides are deliberately
                       ;; left out so the table reflects the variant's
                       ;; declared shape, not the user's transient edits.
                       :cell-overrides nil})
           rows     (args-rows variant-id eff-args)]
       [:div {:style     (:section styles)
              :data-test "story-docs-args-section"}
        [:div {:style (:section-h styles)} "Args"]
        (if (empty? rows)
          [:div {:style (:empty styles)} "no args resolved on this variant"]
          [:table {:style     (:table styles)
                   :data-test "story-docs-args-table"}
           [:thead
            [:tr
             [:th {:style (:th styles)} "key"]
             [:th {:style (:th styles)} "default"]
             [:th {:style (:th styles)} "doc"]]]
           [:tbody
            (for [{:keys [key value doc]} rows]
              ^{:key key}
              [:tr {:data-test "story-docs-args-row"
                    :data-arg-key (str key)}
               [:td {:style (merge (:td styles) (:td-key styles))}
                (str key)]
               [:td {:style (merge (:td styles) (:td-value styles))}
                (pretty-value value)]
               [:td {:style (merge (:td styles) (:td-doc styles))}
                (or doc "—")]])]])])))

#?(:cljs
   (defn- decorators-section
     "Decorator stack — three groups (hiccup / frame-setup / fx-override)
     in apply-order. Story-level decorators come first per
     `resolve-decorators` semantics."
     [variant-id]
     (let [pack (decorators/resolve-decorators variant-id)
           rows (decorator-rows pack)]
       [:div {:style     (:section styles)
              :data-test "story-docs-decorators-section"}
        [:div {:style (:section-h styles)} "Decorators"]
        (if (empty? rows)
          [:div {:style (:empty styles)}
           "no decorators on this variant"]
          [:table {:style     (:table styles)
                   :data-test "story-docs-decorators-table"}
           [:thead
            [:tr
             [:th {:style (:th styles)} "kind"]
             [:th {:style (:th styles)} "id"]
             [:th {:style (:th styles)} "doc"]]]
           [:tbody
            (for [[i {:keys [section id doc]}] (map-indexed vector rows)]
              ^{:key i}
              [:tr {:data-test     "story-docs-decorator-row"
                    :data-section  (name section)}
               [:td {:style (merge (:td styles) (:td-key styles))}
                (name section)]
               [:td {:style (merge (:td styles) (:td-value styles))}
                (str id)]
               [:td {:style (merge (:td styles) (:td-doc styles))}
                (or doc "—")]])]])])))

#?(:cljs
   (defn- parameters-section
     "Parameters — `:modes` / `:substrates` / `:platforms` on the
     variant body, falling back to the parent story. Per spec/008
     §Parameters re-frame2 collapses Storybook's free-form
     `:parameters` map into these three explicit slots."
     [variant-id]
     (let [rows (parameter-rows variant-id)]
       [:div {:style     (:section styles)
              :data-test "story-docs-parameters-section"}
        [:div {:style (:section-h styles)} "Parameters"]
        (if (empty? rows)
          [:div {:style (:empty styles)}
           "no :modes / :substrates / :platforms declared"]
          [:table {:style     (:table styles)
                   :data-test "story-docs-parameters-table"}
           [:thead
            [:tr
             [:th {:style (:th styles)} "key"]
             [:th {:style (:th styles)} "value"]]]
           [:tbody
            (for [{:keys [key value]} rows]
              ^{:key key}
              [:tr {:data-test "story-docs-parameter-row"
                    :data-param-key (name key)}
               [:td {:style (merge (:td styles) (:td-key styles))}
                (str key)]
               [:td {:style (merge (:td styles) (:td-value styles))}
                (pr-str value)]])]])])))

#?(:cljs
   (defn- tags-section
     "Bottom tag picker — clickable chips that forward-link into the
     sidebar tag filter. Re-uses the controls-panel chip style so the
     surface feels familiar; selecting a chip dispatches
     `state/toggle-tag-filter` and the sidebar re-renders with the
     constrained tree."
     [variant-id]
     (let [shell      @state/shell-state-atom
           tag-filter (or (:tag-filter shell) #{})
           tags       (variant-tags variant-id)]
       [:div {:style     (:section styles)
              :data-test "story-docs-tags-section"}
        [:div {:style (:section-h styles)} "Tags"]
        (if (empty? tags)
          [:div {:style (:empty styles)}
           "no tags on this variant"]
          [:div {:style     (:chip-row styles)
                 :data-test "story-docs-tags-row"}
           (for [tag tags]
             ^{:key tag}
             [:button
              {:style          (merge (:chip styles)
                                      (when (contains? tag-filter tag)
                                        (:chip-active styles)))
               :data-test      "story-docs-tag-link"
               :data-docs-tag  (name tag)
               :aria-pressed   (if (contains? tag-filter tag) "true" "false")
               :title          (str "Toggle tag filter for " tag)
               :on-click
               (fn [_]
                 (state/swap-state! state/toggle-tag-filter tag))}
              (str tag)])])])))

#?(:cljs
   (defn docs-view
     "Top-level `:docs` mode pane for `variant-id`.

     Renders the six sections (header / prose / args / decorators /
     parameters / tags) inside a single scrollable container styled
     to match the render-shell chrome (rf2-2uwv contrast palette).

     Per spec/008 the pane is read-only — no inputs. All editing
     happens in the `:dev` (Canvas) mode's controls panel."
     [variant-id]
     (when variant-id
       [:section {:style     (:wrap styles)
                  :data-test "story-docs-view"
                  :aria-label "Variant documentation"}
        [header variant-id]
        [prose-section variant-id]
        [args-section variant-id]
        [decorators-section variant-id]
        [parameters-section variant-id]
        [tags-section variant-id]])))
