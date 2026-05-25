(ns day8.re-frame2-xray.panels.app-db-diff-state
  "Current-state inspector sections for the app-db tab (rf2-okvit).

  The app-db tab is a CURRENT-STATE inspector — the re-frame-10x look —
  NOT a diff. This ns renders the section model
  `app-db-diff-helpers/current-state-sections` produces:

    - a TOP section — the app-db MINUS every reserved `:rf/*` key (the
      user-domain app-db).
    - one section per reserved `:rf/*` area (per spec/Conventions.md
      §Reserved app-db keys). Map-of-instances areas (`:rf/machines`,
      `:rf/spawned`) FAN OUT to one named sub-section per instance —
      section title = the instance id (e.g. `:title/flow`). Singleton
      slices (`:rf/route`, `:rf/system-ids`, `:rf/pending-navigation`,
      `:rf/elision`) render as one section each.

  Every reserved area renders even when absent/empty — an empty-state
  placeholder — so the developer sees the full reserved inventory.

  ## Current-state render — first-class edn-inspector widget (rf2-oqa60)

  Values render through the first-class `views/edn-inspector` widget.
  The new widget owns the WHOLE contract — browse + diff + mini —
  CLJS-aware type detection, distinct bracket styling per collection
  kind, click-to-toggle expansion stored in re-frame app-db, first-
  class sentinel chrome (`:rf/redacted`, `:rf/large`). After phase 5
  (rf2-q3dzw, D5=a per rf2-sndui) diff also routes through this same
  widget via the `:before` opt — the legacy `edn-inspector.render`
  engine is gone.

  The widget has NO ⎘ copy affordance (deferred to follow-on beads —
  the popup phase, D6=a). The old EDN widget's copy gesture is
  unaffected on surfaces still wired to it (Trace, segment-inspector,
  Event lens, Static panels) until phases 2-4 migrate them.

  ## Flat-hairline layout (spec/021 §4.2-4.3, Figma · rf2-ad7zx.11)

  Reconciled to `tools/xray/design-reference/xray_devtools_reference.cljs`
  (the `app-db-panel` component):
  sections render FLAT — an uppercase caption label over the value body,
  with adjacent sections separated by a 1px hairline (`border-t`), NOT
  bordered cards. The old `:bg-3` card + radius + per-section header-rule
  chrome is gone; the panel reads as one continuous scroll keyed by
  caption labels.

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
  "A flat current-state section (spec/021 §4.2, Figma). `title` is hiccup
  (so callers can colour the reserved-area / instance-id label);
  `testid` hooks the section for tests; `body` is the section's content
  hiccup; `first?` suppresses the leading hairline on the panel's top
  section.

  Renders FLAT — an uppercase caption label over the value body, no card
  surface. Adjacent sections are separated by a 1px hairline drawn as a
  `border-top` on every section after the first (Figma's `border-t`
  dividers). Reuses Xray's token CSS variables so light/dark resolve."
  [{:keys [testid title body first?]}]
  [:section {:data-testid testid
             :style       (cond-> {:padding "12px 12px 4px"}
                            (not first?)
                            (assoc :border-top
                                   (str "1px solid " (:border-subtle tokens))))}
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
     title]]
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
  [value before render-id]
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
        :card? true}]
      [ei/edn-inspector
       (f/display-value value)
       {:panel-id :rf.xray/app-db
        :site-id  site-id
        :default-expanded-depth 3
        :before (f/display-value before)
        :card? true}])))

;; ---- top (user-domain) section ------------------------------------------

(defn top-section
  "The TOP section — the app-db MINUS every reserved `:rf/*` key (the
  user-domain app-db). Renders the whole user-domain value as a
  current-state / diff tree. Empty-state when the user-domain app-db is
  empty (e.g. boot value / reserved-keys-only db). `before` is the
  prior user-domain value (or `h/no-diff`); `first?` suppresses the
  panel-leading hairline."
  ([top] (top-section top h/no-diff true))
  ([top before first?]
   (section-shell
     {:testid "rf-xray-app-db-state-top"
      :first? first?
      :title  [:span "app-db"]
      :body   (if (and (map? top) (empty? top))
                (empty-body "app-db has no user-domain keys yet.")
                (value-body top before "top"))})))

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
  snapshot in place. Each instance section draws the leading hairline
  (it is never the panel's first section — the TOP precedes it)."
  [area {:keys [id value] :as inst}]
  (section-shell
    {:testid (str "rf-xray-app-db-state-instance-"
                  (pr-str area) "-" (pr-str id))
     :first? false
     :title  [:span
              (area-label area)
              [:span {:style {:margin "0 6px" :color (:text-tertiary tokens)}}
               "›"]
              [:span {:style {:font-family mono-stack
                              :color       (:text-primary tokens)}}
               (pr-str id)]]
     :body   (value-body value (get inst :before h/no-diff)
                         (str (pr-str area) "/" (pr-str id)))}))

(defn instances-area
  "Render a map-of-instances reserved area (`:rf/machines`,
  `:rf/spawned`). Fans out to one `instance-section` per id. When the
  registry is empty/absent, renders a single empty-state section so the
  area is still visible in the inventory."
  [{:keys [area empty? instances]}]
  (if empty?
    (section-shell
      {:testid (str "rf-xray-app-db-state-area-" (pr-str area))
       :first? false
       :title  (area-label area)
       :body   (empty-body
                 (case area
                   :rf/machines "No machines registered."
                   :rf/spawned  "No spawned actors."
                   "Empty."))})
    (into [:div {:data-testid (str "rf-xray-app-db-state-area-" (pr-str area))}]
          (for [{:keys [id] :as inst} instances]
            (with-meta (instance-section area inst)
                       {:key (pr-str id)})))))

(defn singleton-area
  "Render a singleton-slice reserved area (`:rf/route`,
  `:rf/system-ids`, `:rf/pending-navigation`, `:rf/elision`) as ONE
  section. The section title is the singular slice name (e.g. `route`
  for `:rf/route`). Empty/absent slices render the empty-state body;
  populated slices carry their `:before` pre-image for the inline diff
  annotation."
  [{:keys [area empty? value] :as area-entry}]
  (section-shell
    {:testid (str "rf-xray-app-db-state-area-" (pr-str area))
     :first? false
     :title  (area-label area)
     :body   (if empty?
               (empty-body
                 (case area
                   :rf/route              "No active route."
                   :rf/system-ids         "No system-id bindings."
                   :rf/pending-navigation "No navigation pending."
                   :rf/elision            "No elision declarations."
                   "Empty."))
               (value-body value (get area-entry :before h/no-diff)
                           (pr-str area)))}))

(defn area-section
  "Dispatch one reserved-area section entry (from
  `current-state-sections`'s `:areas`) to the matching renderer based
  on its `:kind`."
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
  followed by one section group per reserved `:rf/*` area (in
  `:areas` order). Adjacent sections are separated by 1px hairlines —
  the TOP is the panel's first section (no leading hairline); every
  reserved-area section after it draws the divider (spec/021 §4.2).
  Each section threads its `:before` pre-image so changed nodes carry
  the inline `← changed` annotation (spec/021 §4.3). Pure hiccup;
  nil-safe (a nil model degrades to an empty TOP + no areas)."
  [{:keys [top areas] :as model}]
  (into [:div {:data-testid "rf-xray-app-db-state"}
         (top-section top (get model :before-top h/no-diff) true)]
        (for [{:keys [area] :as area-entry} areas]
          (with-meta (area-section area-entry)
                     {:key (pr-str area)}))))
