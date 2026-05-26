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
             :style       {:padding "12px 12px 4px"}}
   (when-not hide-header?
     [:h3 {:style {:display         "flex"
                   :align-items     "center"
                   :gap             "8px"
                   :margin          "0 0 8px"
                   :font-family     sans-stack
                   :font-size       "11px"
                   :font-weight     600
                   :text-transform  "uppercase"
                   :letter-spacing  "0.5px"
                   :color           (:text-secondary tokens)}}
      [:span {:style {:overflow      "hidden"
                      :text-overflow "ellipsis"
                      :white-space   "nowrap"
                      :flex          1}}
       title]])
   [:div body]])

(defn- empty-body
  "Empty-state placeholder body for an absent / empty section. `label`
  is a short prose hint (e.g. \"no machines\")."
  [label]
  [:div {:style {:font-family sans-stack
                 :font-size   "12px"
                 :font-style  "italic"
                 :color       (:text-tertiary tokens)}}
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
  [:span {:style {:font-family mono-stack
                  :color       (:accent tokens)}}
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
               [:span {:style {:margin "0 6px" :color (:text-tertiary tokens)}}
                "›"]
               [:span {:style {:font-family mono-stack
                               :color       (:text-primary tokens)}}
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

(defn state-body
  "Render the full current-state inspector body for the section model
  `current-state-sections` produces: the TOP user-domain section
  followed by one section group per POPULATED reserved `:rf/*` area
  (in `:areas` order; rf2-jcdvo — empty areas are filtered at
  projection time so only data-bearing cards reach the renderer).
  Each section threads its `:before` pre-image so changed nodes carry
  the inline `← changed` annotation (spec/021 §4.3). Pure hiccup;
  nil-safe (a nil model degrades to an empty TOP + no areas)."
  [{:keys [top areas] :as model}]
  (into [:div {:data-testid "rf-xray-app-db-state"}
         (top-section top (get model :before-top h/no-diff))]
        (for [{:keys [area] :as area-entry} areas]
          (with-meta (area-section area-entry)
                     {:key (pr-str area)}))))
