(ns day8.re-frame2-xray.panels.app-db-diff-state
  "Current-state inspector sections for the app-db tab (rf2-okvit).

  The app-db tab is a CURRENT-STATE inspector — the re-frame-10x look —
  NOT a diff. This ns renders the section model
  `app-db-diff-helpers/current-state-sections` produces:

    - a TOP section — the app-db MINUS every reserved `:rf/*` key (the
      user-domain app-db). ALWAYS renders, even when empty.
    - one section per POPULATED reserved `:rf/*` area (per
      spec/Conventions.md §Reserved app-db keys). Map-of-instances
      areas (`:rf/machines`, `:rf/spawned`) FAN OUT to one named
      sub-section per instance — section title = the instance id (e.g.
      `:title/flow`). Singleton slices (`:rf/route`, `:rf/system-ids`,
      `:rf/pending-navigation`, `:rf/elision`) render as one section
      each.

  rf2-jcdvo — empty / absent reserved areas are FILTERED at projection
  time (`current-state-sections` omits any `:empty?` entry). The
  operator sees only areas that actually carry state; the panel is no
  longer cluttered with six labelled 'No X' placeholder cards. As
  state accrues (e.g. operator triggers navigation that populates
  `:rf/pending-navigation`), the corresponding card appears
  automatically — visibility is data-driven.

  ## Current-state render — first-class edn-inspector widget (rf2-oqa60)

  Values render through the first-class `views/edn-inspector` widget.
  The new widget owns the WHOLE contract — browse + diff + mini —
  CLJS-aware type detection, distinct bracket styling per collection
  kind, click-to-toggle expansion stored in re-frame app-db, first-
  class sentinel chrome (`:rf/redacted`, `:rf.size/large-elided`).
  After phase 5
  (rf2-q3dzw, D5=a per rf2-sndui) diff also routes through this same
  widget via the `:before` opt — the legacy `edn-inspector.render`
  engine is gone.

  The widget has NO ⎘ copy affordance (deferred to follow-on beads —
  the popup phase, D6=a). The old EDN widget's copy gesture is
  unaffected on surfaces still wired to it (Trace, segment-inspector,
  Event lens, Static panels) until phases 2-4 migrate them.

  ## Inspector-card layout (rf2-63ie5 + rf2-okq7p, post-rf2-jcdvo)

  Each section's value body renders through the first-class edn-inspector
  widget with `:card? true` (rf2-63ie5) and a header ribbon (rf2-okq7p),
  giving each top-level mount discrete inspector-card chrome — border +
  radius + background + header. Adjacent cards self-separate via that
  chrome + the inter-card vertical gap; rf2-jcdvo dropped the redundant
  inter-section hairline that competed with the card borders for the
  eye's attention.

  Empty reserved-area sections are FILTERED at projection time
  (`current-state-sections` omits any `:empty?` entry). The operator
  sees only areas that actually carry state — no labelled 'No machines
  registered.' / 'No active route.' placeholder cards. The TOP
  user-domain section ALWAYS renders (it is the panel's anchor; an
  empty user-domain app-db is itself meaningful operator information).

  ## Inline `← changed` annotation (spec/021 §4.3)

  Each section carries a `:before` slice (from the cascade's `db-before`,
  threaded by `app-db-diff-helpers/current-state-sections`'s 2-arity).
  When a pre-image is present and differs, the value body routes
  through the edn-inspector widget's DIFF mode (rf2-q3dzw phase 5,
  D5=a) — passing `:before` paints inline `← changed from X`
  annotations in place on changed nodes and force-expands the
  ancestor chain so the operator never expands to find a change. When
  no pre-image is threaded (`no-diff` sentinel — LIVE at boot,
  1-arity model) the body falls back to plain BROWSE mode on the same
  widget. The keyword-accent is already orange (owned by rf2-ad7zx.3).

  Pure hiccup; reuses Xray's theme tokens so light/dark resolve."
  (:require [day8.re-frame2-xray.panels.app-db-diff-format :as f]
            [day8.re-frame2-xray.panels.app-db-diff-helpers :as h]
            [day8.re-frame2-xray.theme.tokens
             :refer [tokens mono-stack sans-stack]]
            ;; The first-class edn-inspector widget owns the WHOLE
            ;; contract — browse + diff — as a single source of truth
            ;; (rf2-q3dzw phase 5, D5=a per rf2-sndui).
            [day8.re-frame2-xray.views.edn-inspector :as ei]))

;; ---- style hoists (rf2-mndut) -------------------------------------------
;;
;; Every literal `:style {...}` map in the section / flat-diff renderers
;; below is hoisted to ns-top defs so React's reconciler sees stable
;; object identities across re-renders (follow-on to rf2-qx414 /
;; rf2-zlk6h / rf2-xjgdk / rf2-gjiog / rf2-alsnz). The flat-diff body
;; in particular renders N rows × 4-5 inline `:style {...}` maps per
;; row inside a render loop; a 20-row diff was minting 80-100 fresh
;; map allocations per render before this hoist. `tokens` values
;; resolve to `var(--rf-xray-*)` CSS strings at ns load so the light/
;; dark theme toggle continues to flip palette in lockstep without
;; re-evaluation (spec/007 §UX-IA).
;;
;; Per-call variation rides:
;;   - small `assoc` / `cond->` overlays on a shared base map
;;     (glyph-colour swaps), and
;;   - hoisted named variants where the variation is whole-map (added /
;;     removed / modified glyph rows).

;; -- section chrome --------------------------------------------------------

(def ^:private section-shell-style
  "Outer `[:section]` wrapper inside section-shell."
  {:padding "12px 12px 4px"})

(def ^:private section-shell-h3-style
  "H3 ribbon when section-shell renders its own header (rare;
  hide-header? is the common case post-rf2-okq7p)."
  {:display         "flex"
   :align-items     "center"
   :gap             "8px"
   :margin          "0 0 8px"
   :font-family     sans-stack
   :font-size       "11px"
   :font-weight     600
   :text-transform  "uppercase"
   :letter-spacing  "0.5px"
   :color           (:text-secondary tokens)})

(def ^:private section-shell-h3-title-style
  "Title span inside section-shell-h3 — ellipsises on overflow."
  {:overflow      "hidden"
   :text-overflow "ellipsis"
   :white-space   "nowrap"
   :flex          1})

(def ^:private empty-body-style
  "Empty-state placeholder body style (italic muted prose)."
  {:font-family sans-stack
   :font-size   "12px"
   :font-style  "italic"
   :color       (:text-tertiary tokens)})

(def ^:private area-label-style
  "Reserved-area `:rf/*` keyword title in mode `accent` colour."
  {:font-family mono-stack
   :color       (:accent tokens)})

(def ^:private instance-section-separator-style
  "The `›` separator between area-label and instance-id in
  instance-section's title."
  {:margin "0 6px"
   :color  (:text-tertiary tokens)})

(def ^:private instance-section-id-style
  "Instance-id text inside instance-section's title."
  {:font-family mono-stack
   :color       (:text-primary tokens)})

;; -- flat-diff (`:diff` mode) chrome --------------------------------------

(def ^:private flat-diff-row-style
  "One change-list row inside flat-diff-body."
  {:display     "flex"
   :gap         "8px"
   :align-items "baseline"
   :font-family mono-stack
   :font-size   "12px"
   :padding     "2px 12px"
   :flex-wrap   "wrap"})

(def ^:private flat-diff-glyph-base-style
  "Per-row glyph span — only `:color` varies, applied via assoc."
  {:font-weight 700
   :min-width   "1ch"})

(def ^:private flat-diff-path-style
  "Path-prefix span — the `[:foo :bar]` text inside one diff row."
  {:color      (:text-secondary tokens)
   :word-break "break-word"})

(def ^:private flat-diff-modified-before-style
  "The `~`-modified row's BEFORE-value mini inspector — strikethrough
  tertiary text."
  {:color           (:text-tertiary tokens)
   :text-decoration "line-through"})

(def ^:private flat-diff-modified-arrow-style
  "The `→` separator between BEFORE and AFTER on `~`-modified rows."
  {:color (:text-tertiary tokens)})

(def ^:private flat-diff-modified-after-style
  "The `~`-modified row's AFTER-value mini inspector."
  {:color (:success tokens)})

(def ^:private flat-diff-added-style
  "The `+`-added row's value mini inspector — success-coloured, flex 1."
  {:color     (:success tokens)
   :flex      1
   :min-width 0})

(def ^:private flat-diff-removed-style
  "The `−`-removed row's value mini inspector — strikethrough error."
  {:color           (:error tokens)
   :text-decoration "line-through"
   :flex            1
   :min-width       0})

(def ^:private flat-diff-section-style
  "Outer `[:section]` chrome for the flat-diff body."
  {:padding "12px 0 4px"})

(def ^:private flat-diff-header-style
  "H3 ribbon at the top of the flat-diff body."
  {:display         "flex"
   :align-items     "center"
   :gap             "8px"
   :margin          "0 12px 8px"
   :font-family     sans-stack
   :font-size       "11px"
   :font-weight     600
   :text-transform  "uppercase"
   :letter-spacing  "0.5px"
   :color           (:text-secondary tokens)})

(def ^:private flat-diff-summary-style
  "Right-side `N paths` / `— (no changes)` summary inside flat-diff
  header. Lighter weight + normal-case + normal letter-spacing so it
  reads as supporting metadata next to the uppercase `diff` title."
  {:color           (:text-tertiary tokens)
   :font-weight     400
   :text-transform  "none"
   :letter-spacing  "normal"})

;; ---- shared section chrome ----------------------------------------------

(defn- section-shell
  "A current-state section wrapper. `title` is hiccup (so callers can
  colour the reserved-area / instance-id label); `testid` hooks the
  section for tests; `body` is the section's content hiccup;
  `hide-header?` suppresses the section-shell H3 (used when the body's
  own card chrome carries its own header ribbon — the common case post-
  rf2-okq7p).

  rf2-jcdvo dropped the inter-section hairline divider — each card's
  own border + the inter-card vertical gap is sufficient visual
  separation; the divider was redundant chrome that competed with the
  card borders for the eye's attention."
  [{:keys [testid title body hide-header?]}]
  [:section {:data-testid testid
             :style       section-shell-style}
   (when-not hide-header?
     [:h3 {:style section-shell-h3-style}
      [:span {:style section-shell-h3-title-style}
       title]])
   [:div body]])

(defn- empty-body
  "Empty-state placeholder body for an absent / empty section. `label`
  is a short prose hint (e.g. \"no machines\")."
  [label]
  [:div {:style empty-body-style}
   label])

(defn- value-body
  "Render a current-state VALUE, with the inline `← changed` diff
  annotation when a pre-image is supplied (spec/021 §4.3). `render-id`
  keeps adjacent renders' testids independent across the panel.

  `f/display-value` runs first so giant string leaves collapse to the
  `:rf.size/large-elided` display marker before rendering — the same
  display-side bound the old slice renderer applied. This keeps a 20 KiB
  payload from flooding the inspector (and from leaking the raw bytes
  into the rendered text).

  ## Diff vs current-state routing

  When `before` is the `h/no-diff` sentinel (no pre-image threaded —
  LIVE at boot / 1-arity model) the value renders in BROWSE mode via
  the first-class edn-inspector widget — a plain current-state tree.

  When a real pre-image is present the value renders in DIFF mode via
  the SAME widget (rf2-q3dzw phase 5), passing `:before` so the
  widget paints inline `← changed from X` annotations and force-
  expands the ancestor chain over changed descendants (spec/021 §4.3 +
  §10.4). App-db's depth heuristic is depth-3-collapsed by default
  (§10.4). The keyword-accent is already orange (owned by
  rf2-ad7zx.3)."
  [value before render-id title]
  (let [_node-key (str "app-db-state/" render-id)
        ;; rf2-pvsxs — stable `:site-id` so expansion overrides survive
        ;; a tab-switch round-trip. `render-id` already identifies the
        ;; logical surface (e.g. "top" for the user-domain section, an
        ;; area name for the per-:rf/* sections), so passing it AS the
        ;; site-id reuses the existing per-surface key without
        ;; introducing a new namespace.
        site-id [:rf.xray/app-db render-id]]
    ;; rf2-7sdja — App-DB does NOT use `:popup-affordance?` (Mike's
    ;; live-testing call 2026-05-26). The side panel has plenty of
    ;; horizontal room; the whole-tree inspector renders comfortably
    ;; in-place. Other panels (Handler / Trace / Machines / Reactive)
    ;; keep the affordance where the inline widget is genuinely
    ;; cramped.
    (if (= h/no-diff before)
      [ei/edn-inspector
       (f/display-value value)
       {:panel-id :rf.xray/app-db
        :site-id  site-id
        :default-expanded-depth 3
        ;; rf2-63ie5 — App-DB renders the user-domain TOP + every
        ;; reserved `:rf/*` area as top-level mounts in the same panel.
        ;; Without card chrome the mounts blend into one continuous
        ;; block; the opt-in chrome gives each mount a distinct card
        ;; affordance so the operator sees them as discrete inspector
        ;; cards.
        :card? true
        ;; rf2-h71e0 — App-DB is the canonical zoom-into-node consumer.
        ;; Dense top-level trees benefit hugely from focusing on a
        ;; single subtree; the breadcrumb row keeps the operator's
        ;; bearings. Diff mode (`before` present) suppresses zoom
        ;; resolution because diff's force-expand-over-changes logic
        ;; and zoom's hide-everything-outside-the-subtree conflict.
        :zoomable? true
        :header title}]
      [ei/edn-inspector
       (f/display-value value)
       {:panel-id :rf.xray/app-db
        :site-id  site-id
        :default-expanded-depth 3
        :before (f/display-value before)
        :card? true
        ;; rf2-h71e0 — zoomable opt is preserved here for symmetry;
        ;; the widget self-suppresses zoom resolution in diff mode so
        ;; the affordance + breadcrumb stay off until the operator
        ;; returns to current-state (non-diff) browse.
        :zoomable? true
        :header title}])))

;; ---- top (user-domain) section ------------------------------------------

(defn top-section
  "The TOP section — the app-db MINUS every reserved `:rf/*` key (the
  user-domain app-db). Renders the whole user-domain value as a
  current-state / diff tree. Empty-state when the user-domain app-db is
  empty (e.g. boot value / reserved-keys-only db). `before` is the
  prior user-domain value (or `h/no-diff`).

  rf2-jcdvo — the TOP section ALWAYS renders, even when empty (it is
  the panel's anchor; an empty user-domain app-db is itself meaningful
  operator information). Empty reserved-area sections are filtered at
  projection time and never reach the renderer; only the TOP carries
  an empty-state body."
  ([top] (top-section top h/no-diff))
  ([top before]
   (let [title  [:span "app-db"]
         empty? (and (map? top) (empty? top))]
     (section-shell
       {:testid       "rf-xray-app-db-state-top"
        :title        title
        :hide-header? (not empty?)
        :body         (if empty?
                        (empty-body "app-db has no user-domain keys yet.")
                        (value-body top before "top" title))}))))

;; ---- reserved-area sections ---------------------------------------------

(defn- area-label
  "Render a reserved-area section title — the bare `:rf/*` key in the
  mode `accent` keyword colour."
  [area]
  [:span {:style area-label-style}
   (pr-str area)])

(defn instance-section
  "One fan-out sub-section for a single instance of a map-of-instances
  reserved area — section title = the instance id. Used per machine
  (`:rf/machines`) and per parent (`:rf/spawned`). The instance carries
  its own `:before` pre-image so the body diff-annotates the changed
  snapshot in place. The section-shell H3 is suppressed — the
  edn-inspector card's own header ribbon carries the title."
  [area {:keys [id value] :as inst}]
  (let [title [:span
               (area-label area)
               [:span {:style instance-section-separator-style}
                "›"]
               [:span {:style instance-section-id-style}
                (pr-str id)]]]
    (section-shell
      {:testid       (str "rf-xray-app-db-state-instance-"
                          (pr-str area) "-" (pr-str id))
       :title        title
       :hide-header? true
       :body         (value-body value (get inst :before h/no-diff)
                                 (str (pr-str area) "/" (pr-str id))
                                 title)})))

(defn instances-area
  "Render a map-of-instances reserved area (`:rf/machines`,
  `:rf/spawned`). Fans out to one `instance-section` per id.

  rf2-jcdvo — empty registries are filtered at projection time
  (`current-state-sections` omits `:empty?` entries) so this fn is
  only invoked for populated registries; no empty-state branch."
  [{:keys [area instances]}]
  (into [:div {:data-testid (str "rf-xray-app-db-state-area-" (pr-str area))}]
        (for [{:keys [id] :as inst} instances]
          (with-meta (instance-section area inst)
                     {:key (pr-str id)}))))

(defn singleton-area
  "Render a singleton-slice reserved area (`:rf/route`,
  `:rf/system-ids`, `:rf/pending-navigation`, `:rf/elision`) as ONE
  section.

  rf2-jcdvo — empty/absent slices are filtered at projection time
  (`current-state-sections` omits `:empty?` entries) so this fn is
  only invoked for populated slices; no empty-state branch. The
  section-shell H3 is suppressed — the edn-inspector card's own header
  ribbon carries the title."
  [{:keys [area value] :as area-entry}]
  (let [title (area-label area)]
    (section-shell
      {:testid       (str "rf-xray-app-db-state-area-" (pr-str area))
       :title        title
       :hide-header? true
       :body         (value-body value (get area-entry :before h/no-diff)
                                 (pr-str area)
                                 title)})))

(defn area-section
  "Dispatch one reserved-area section entry (from
  `current-state-sections`'s `:areas`) to the matching renderer based
  on its `:kind`. Only invoked for non-empty areas — empty entries are
  filtered at projection time (rf2-jcdvo)."
  [{:keys [kind] :as area-entry}]
  (case kind
    :instances (instances-area area-entry)
    :singleton (singleton-area area-entry)
    ;; Defensive — an unknown kind still renders the bare key so the
    ;; area never silently vanishes.
    (singleton-area area-entry)))

;; ---- panel body ----------------------------------------------------------

;; ---- flat-diff lens (rf2-yqjrd `:diff` mode) ----------------------------
;;
;; When the panel's mode toggle is `:diff` the body renders ONE flat path-
;; prefixed change list across the entire app-db (not per-section). The
;; rows are sourced from `:rf.xray/selected-epoch-diff` (the same triples
;; the Epoch HANDLER step's `:diff` view + MCP exporter consume) so the
;; App-DB panel's pure-diff lens reads identically to every other Xray
;; surface's diff lens.

(defn- flat-diff-glyph
  "Render the leading glyph for a `[path before after change-kind]`
  triple — `+` added · `−` removed · `~` modified. Mirrors the
  glyph palette the Epoch HANDLER `:diff` rows use so the two
  surfaces share visual grammar."
  [change-kind]
  (case change-kind
    :added    "+"
    :removed  "−"
    :modified "~"
    "~"))

(defn- flat-diff-glyph-colour
  [glyph]
  (case glyph
    "+" (:success tokens)
    "−" (:error tokens)
    (:warning tokens)))

(defn- flat-diff-line
  "Render one `[path before after change-kind]` triple. Same shape as
  the Epoch HANDLER `:diff` row but scoped to the App-DB panel testid
  prefix."
  [[path before after change-kind] idx]
  (let [glyph        (flat-diff-glyph change-kind)
        glyph-colour (flat-diff-glyph-colour glyph)]
    [:div {:key (str "app-db-diff-row-" idx)
           :data-testid (str "rf-xray-app-db-diff-row-" idx)
           :style flat-diff-row-style}
     [:span {:style (assoc flat-diff-glyph-base-style :color glyph-colour)}
      glyph]
     [:span {:style flat-diff-path-style}
      (pr-str path)]
     (when (= "~" glyph)
       [:<>
        [:span {:style flat-diff-modified-before-style}
         [ei/mini before 40]]
        [:span {:style flat-diff-modified-arrow-style} "→"]
        [:span {:style flat-diff-modified-after-style}
         [ei/mini after 40]]])
     (when (= "+" glyph)
       [:span {:style flat-diff-added-style}
        [ei/mini after 80]])
     (when (= "−" glyph)
       [:span {:style flat-diff-removed-style}
        [ei/mini before 80]])]))

(defn- flat-diff-body
  "Render the `:diff` mode body — header + one row per change. When
  `diff-triples` is empty an italic `(no changes)` placeholder
  appears in place of rows."
  [diff-triples]
  (let [empty? (empty? diff-triples)]
    [:section {:data-testid "rf-xray-app-db-diff-flat"
               :data-empty (str empty?)
               :style flat-diff-section-style}
     [:h3 {:style flat-diff-header-style}
      [:span "diff"]
      [:span {:style flat-diff-summary-style}
       (if empty?
         "— (no changes)"
         (str (count diff-triples) " path"
              (when (not= 1 (count diff-triples)) "s")))]]
     (when-not empty?
       (into [:div]
             (map-indexed (fn [i row] (flat-diff-line row i))
                          diff-triples)))]))

;; ---- panel body ----------------------------------------------------------

(defn- sections-body
  "Render the section list for the `:full` / `:full+diff` modes. When
  `diff?` is true each section threads its `:before` pre-image so
  changed nodes carry inline `← changed` annotations. When false
  every section renders as plain current-state browse (no `:before`
  threaded; the renderer falls back to the `h/no-diff` sentinel)."
  [{:keys [top areas] :as model} diff?]
  (let [before-top (if diff? (get model :before-top h/no-diff) h/no-diff)]
    (into [:div {:data-testid "rf-xray-app-db-state"
                 :data-mode-diff (str (boolean diff?))}
           (top-section top before-top)]
          (for [{:keys [area] :as area-entry} areas]
            (with-meta
              (area-section
                (if diff? area-entry (dissoc area-entry :before
                                             :instances)))
              {:key (pr-str area)})))))

(defn- strip-instance-befores
  "When the mode is `:full` (no diff) strip every `:before` from the
  per-instance entries of a map-of-instances area so the renderer's
  diff branch never triggers. Singleton areas drop their top-level
  `:before` via `dissoc` in `sections-body`; map-of-instances areas
  need this deeper walk because the `:before` lives one level down."
  [areas]
  (mapv
    (fn [{:keys [kind instances] :as entry}]
      (if (and (= kind :instances) (seq instances))
        (assoc entry :instances
               (mapv (fn [inst] (dissoc inst :before)) instances))
        entry))
    areas))

(defn state-body
  "Render the full current-state inspector body for the section model
  `current-state-sections` produces.

  rf2-yqjrd — accepts an opts map with `:mode` (`:diff` / `:full` /
  `:full+diff`) and `:diff-triples` (the flat-row source for the
  `:diff` lens). When opts are omitted (1-arity legacy call) the
  body renders in the prior `:full+diff` shape — section list with
  `:before` threaded — so any direct caller that pre-dates the
  toggle keeps its behaviour. Production callers (the Panel view
  above) always pass the mode.

  Mode behaviour:

  - `:diff`      — flat path-prefixed change list (one row per
                   change). Section list suppressed; the operator
                   sees only what changed.
  - `:full`      — section list rendered with no `:before` threaded
                   (plain browse, no inline diff annotations on any
                   section).
  - `:full+diff` — section list rendered with each section's
                   `:before` threaded so inline diff annotations
                   paint (spec/021 §4.3 default).

  Pure hiccup; nil-safe (a nil model degrades to an empty TOP + no
  areas)."
  ([model] (state-body model nil))
  ([{:keys [top areas] :as model} {:keys [mode diff-triples]
                                   :or   {mode :full+diff}}]
   (case mode
     :diff
     (flat-diff-body (or diff-triples []))

     :full
     (sections-body
       (assoc model :areas (strip-instance-befores (or areas [])))
       false)

     ;; :full+diff (default) — existing inline-diff behaviour.
     (sections-body model true))))
