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
            #?@(:cljs [[reagent.core :as r]
                       [re-frame.story.args :as args]
                       [re-frame.story.decorators :as decorators]
                       [re-frame.story.ui.state :as state]])
            [re-frame.story.theme.typography :as typography :refer [sans-stack mono-stack]]
            [re-frame.story.theme.colors :as colors]))

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
  (let [workspaces (registrar/registrations :workspace)]
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
                      :background       (:bg-canvas colors/tokens)
                      :color            (:text-primary colors/tokens)
                      :font-family      sans-stack
                      :font-size        (:body typography/type-scale)
                      :line-height      "1.5"}
      :h1            {:font-family      mono-stack
                      :font-size        (:display typography/type-scale)
                      :font-weight      "bold"
                      :color            "white"
                      :margin           "0 0 4px 0"}
      :sub           {:color            (:text-secondary colors/tokens)
                      :font-family      mono-stack
                      :font-size        (:caption typography/type-scale)
                      :margin-bottom    "10px"}
      :section       {:margin-top       "24px"}
      :section-h     {:font-weight      "bold"
                      :color            (:text-secondary colors/tokens)
                      :text-transform   "uppercase"
                      :font-size        (:micro typography/type-scale)
                      :letter-spacing   "0.5px"
                      :margin-bottom    "8px"
                      :border-bottom    "1px solid #444"
                      :padding-bottom   "4px"}
      :doc-blurb     {:color            (:text-secondary colors/tokens)
                      :font-style       "italic"
                      :margin           "4px 0 12px 0"}
      :prose-block   {:padding          "12px 16px"
                      :margin-bottom    "8px"
                      :background       (:bg-2 colors/tokens)
                      :border-left      "3px solid #0e639c"
                      :color            (:text-primary colors/tokens)
                      :font-family      sans-stack
                      :white-space      "pre-wrap"}
      :prose-source  {:color            (:text-secondary colors/tokens)
                      :font-family      mono-stack
                      :font-size        (:micro typography/type-scale)
                      :margin-bottom    "4px"}
      :table         {:width            "100%"
                      :border-collapse  "collapse"
                      :font-family      mono-stack
                      :font-size        (:caption typography/type-scale)}
      :th            {:text-align       "left"
                      :padding          "6px 8px"
                      :background       (:bg-2 colors/tokens)
                      :color            (:text-secondary colors/tokens)
                      :border-bottom    "1px solid #444"
                      :text-transform   "uppercase"
                      :font-size        (:micro typography/type-scale)
                      :letter-spacing   "0.5px"}
      :td            {:padding          "6px 8px"
                      :border-bottom    "1px solid #2d2d30"
                      :color            (:text-primary colors/tokens)
                      :vertical-align   "top"}
      :td-key        {:color            (:info colors/tokens)
                      :white-space      "nowrap"}
      :td-value      {:color            (:tag-experimental-fg colors/tokens)
                      :font-family      mono-stack
                      :white-space      "pre-wrap"}
      :td-doc        {:color            (:text-secondary colors/tokens)
                      :font-style       "italic"}
      :section-h-row {:background       (:bg-3 colors/tokens)
                      :color            (:text-secondary colors/tokens)
                      :text-transform   "uppercase"
                      :font-size        (:micro typography/type-scale)
                      :letter-spacing   "0.5px"}
      :chip-row      {:display          "flex"
                      :flex-wrap        "wrap"
                      :gap              "6px"
                      :margin-top       "4px"}
      :chip          {:padding          "3px 9px"
                      :background       (:bg-3 colors/tokens)
                      :color            (:text-primary colors/tokens)
                      :border           "none"
                      :border-radius    "10px"
                      :cursor           "pointer"
                      :font-family      mono-stack
                      :font-size        (:micro typography/type-scale)
                      :user-select      "none"}
      :chip-active   {:background       (:accent-amber colors/tokens)
                      :color            "white"}
      :empty         {:color            (:text-tertiary colors/tokens)
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

;; ---- pure: TOC entries (rf2-8c7tk) --------------------------------------

(def docs-toc-entries
  "Canonical TOC entry table for the `:docs` mode pane (rf2-8c7tk).
  Pure data → data so JVM tests can assert the table shape. The
  header section renders as the variant's `<h1>` and is intentionally
  NOT in the TOC list — it sits beside that h1 and would self-reference."
  [{:id "docs-prose"      :label "Prose"      :level 2 :conditional? true}
   {:id "docs-args"       :label "Args"       :level 2}
   {:id "docs-decorators" :label "Decorators" :level 2}
   {:id "docs-parameters" :label "Parameters" :level 2}
   {:id "docs-tags"       :label "Tags"       :level 2}])

(defn visible-toc-entries
  "Pure data → data: prune conditional TOC entries that don't apply to
  this variant. The prose section is the only conditional one — we
  drop it when `(prose-for-variant variant-id)` returns empty."
  [variant-id]
  (let [prose? (seq (prose-for-variant variant-id))]
    (vec
      (remove (fn [{:keys [id conditional?]}]
                (and conditional?
                     (= id "docs-prose")
                     (not prose?)))
              docs-toc-entries))))

#?(:cljs
   (defn- toc-pane-styles []
     {:wrap        {:position "sticky"
                    :top "16px"
                    :max-height "calc(100vh - 64px)"
                    :overflow-y "auto"
                    :width "180px"
                    :flex-shrink "0"
                    :padding "12px 12px 12px 16px"
                    :margin-left "20px"
                    :border-left (str "1px solid " (:border-subtle colors/tokens))
                    :font-family sans-stack
                    :font-size (:nano typography/type-scale)
                    :color (:text-secondary colors/tokens)}
      :label      {:text-transform "uppercase"
                   :letter-spacing "0.08em"
                   :color (:text-tertiary colors/tokens)
                   :font-size (:nano typography/type-scale)
                   :margin-bottom "6px"}
      :list       {:list-style "none"
                   :margin 0
                   :padding 0
                   :display "flex"
                   :flex-direction "column"
                   :gap "2px"}
      :item       {:padding "3px 8px"
                   :border-radius "3px"
                   :cursor "pointer"
                   :background "transparent"
                   :border "none"
                   :text-align "left"
                   :color (:text-secondary colors/tokens)
                   :font-family sans-stack
                   :font-size (:caption typography/type-scale)}
      :item-active {:background (:accent-amber-soft colors/tokens)
                    :color (:accent-amber colors/tokens)}}))

#?(:cljs
   (defn- jump-to-anchor!
     [anchor-id]
     (when (and (exists? js/document) anchor-id)
       (when-let [el (.getElementById js/document anchor-id)]
         (try
           (.scrollIntoView el #js {:behavior "smooth" :block "start"})
           (catch :default _ nil))))))

#?(:cljs
   (defn- responsive-toc?
     "Spec rf2-8c7tk: TOC auto-hides below 1024px (more aggressive than
     Storybook's 1200px since our RHS is already present)."
     []
     (boolean
       (when (exists? js/window)
         (try (>= (.-innerWidth js/window) 1024)
              (catch :default _ false))))))

#?(:cljs
   (defn- toc-pane
     "Render the TOC pane at the docs page right edge. Tracks the
     currently-visible section via IntersectionObserver so the active
     entry highlights as the user scrolls."
     [variant-id]
     (let [active   (r/atom nil)
           observer (atom nil)]
       (r/create-class
         {:display-name "rf-story-docs-toc"
          :component-did-mount
          (fn [_]
            (when (and (exists? js/window) (.-IntersectionObserver js/window))
              (try
                (let [entries (visible-toc-entries variant-id)
                      io (js/IntersectionObserver.
                           (fn [entries-arr _obs]
                             (doseq [e (array-seq entries-arr)]
                               (when (.-isIntersecting e)
                                 (reset! active (.-id (.-target e))))))
                           #js {:rootMargin "-30% 0px -60% 0px"})]
                  (reset! observer io)
                  (doseq [{:keys [id]} entries]
                    (when-let [el (.getElementById js/document id)]
                      (.observe io el))))
                (catch :default _ nil))))
          :component-will-unmount
          (fn [_]
            (when-let [io @observer]
              (try (.disconnect io) (catch :default _ nil))
              (reset! observer nil)))
          :reagent-render
          (fn [variant-id]
            (let [s         (toc-pane-styles)
                  entries   (visible-toc-entries variant-id)
                  active-id @active]
              (when (and (responsive-toc?) (seq entries))
                [:nav {:style     (:wrap s)
                       :data-test "story-docs-toc"
                       :aria-label "Documentation table of contents"}
                 [:div {:style (:label s)} "Contents"]
                 [:ul {:style (:list s)}
                  (for [{:keys [id label]} entries]
                    ^{:key id}
                    [:li
                     [:button
                      {:style       (merge (:item s)
                                           (when (= id active-id)
                                             (:item-active s)))
                       :data-test       "story-docs-toc-item"
                       :data-toc-target id
                       :aria-current    (if (= id active-id) "location" "false")
                       :on-click        (fn [_] (jump-to-anchor! id))}
                      label]])]])))}))))

#?(:cljs
   (defn docs-view
     "Top-level `:docs` mode pane for `variant-id`.

     Renders the six sections (header / prose / args / decorators /
     parameters / tags) inside a flex layout alongside a sticky TOC
     pane on the right edge (rf2-8c7tk; auto-hides below 1024px)."
     [variant-id]
     (when variant-id
       [:div {:style     {:display "flex"
                          :flex "1"
                          :overflow "auto"
                          :background (:bg-canvas colors/tokens)}
              :data-test "story-docs-layout"}
        [:section {:style     (:wrap styles)
                   :data-test "story-docs-view"
                   :aria-label "Variant documentation"}
         [header variant-id]
         [:section {:id "docs-prose"} [prose-section variant-id]]
         [:section {:id "docs-args"} [args-section variant-id]]
         [:section {:id "docs-decorators"} [decorators-section variant-id]]
         [:section {:id "docs-parameters"} [parameters-section variant-id]]
         [:section {:id "docs-tags"} [tags-section variant-id]]]
        [toc-pane variant-id]])))
