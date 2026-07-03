(ns day8.re-frame2-xray.panels.app-db-diff-state
  "Current-state inspector sections for the app-db tab (rf2-okvit).

  The app-db tab is a CURRENT-STATE inspector — the re-frame-10x look —
  NOT a diff. This ns renders the section model
  `app-db-diff-helpers/current-state-sections` produces:

    - a TOP section — the app-db MINUS every reserved `:rf*` key (the
      user-domain app-db). ALWAYS renders, even when empty.
    - one section per POPULATED operator-facing runtime area (per the
      `runtime-areas` table — sourced from the runtime-db partition's
      `:rf.runtime/*` roots per EP-0001 rf2-vzld77 / rf2-tj6w9l, no
      longer app-db's retired `:rf/runtime` container). Map-of-instances
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
  The widget has ONE rendering path keyed on value (always) + before
  (optional) — CLJS-aware type detection, distinct bracket styling per
  collection kind, click-to-toggle expansion stored in re-frame app-db,
  first-class sentinel chrome (`:rf/redacted`, `:rf.size/large-elided`),
  and inline diff annotations when a `:before` pre-image is supplied
  (rf2-q3dzw phase 5, D5=a per rf2-sndui; rf2-e28r3 collapsed the
  former browse/diff modes into the single path). The legacy
  `edn-inspector.render` engine is gone.

  The widget has NO ⎘ copy affordance (deferred to follow-on beads —
  the popup phase, D6=a). The old EDN widget's copy gesture is
  unaffected on surfaces still wired to it (Trace, segment-inspector,
  Static panels) until subsequent phases migrate them.

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

  Each section carries a `:before` slice (from the event-bundle's `db-before`,
  threaded by `app-db-diff-helpers/current-state-sections`'s 2-arity).
  When a pre-image is present and differs, the value body passes
  `:before` to the edn-inspector widget — painting inline `← was X`
  annotations in place on changed nodes and force-expanding the
  ancestor chain so the operator never expands to find a change. When
  no pre-image is threaded (`no-diff` sentinel — LIVE at boot,
  1-arity model) `:before` is omitted and the same widget renders the
  value plainly. The keyword-accent is already orange (owned by
  rf2-ad7zx.3).

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

  ## Single render path — value + optional before

  The edn-inspector has ONE rendering path (rf2-e28r3): value (always)
  + before (optional). When `before` is the `h/no-diff` sentinel (no
  pre-image threaded — LIVE at boot / 1-arity model) the `:before` opt
  is omitted and the value renders plainly — a current-state tree with
  no diff annotations, no rail, no chip. When a real pre-image is
  present `:before` is passed and the SAME renderer paints inline
  `← was X` annotations, the R4 rail + R3 chip on change-bearing
  containers, and force-expands the ancestor chain over changed
  descendants (spec/021 §4.3 + §10.4). App-db's depth heuristic is
  depth-3-collapsed by default (§10.4). The keyword-accent is already
  orange (owned by rf2-ad7zx.3).

  rf2-227cz — a third `before` value, the `h/added` sentinel, marks a
  whole instance / singleton slice present in `:value` but ABSENT in the
  focused epoch's pre-image (it came into existence this epoch). For
  that case the body passes `:added? true` (NOT `:before`) so the
  edn-inspector's first-run path (rf2-kp7bw) synthesises the prior side
  as `engine/missing-sentinel` and washes the WHOLE subtree `:added`
  (green). Without this the freshly-created machine / spawn / route
  slice rendered identically to an unchanged one and the per-event diff
  was near-invisible — the focused epoch's actual change (a new instance
  appearing) carried no visual marker at all."
  [value before render-id title]
  (let [_node-key (str "app-db-state/" render-id)
        ;; rf2-pvsxs — stable `:site-id` so expansion overrides survive
        ;; a tab-switch round-trip. `render-id` already identifies the
        ;; logical surface (e.g. "top" for the user-domain section, an
        ;; area name for the per-:rf/* sections), so passing it AS the
        ;; site-id reuses the existing per-surface key without
        ;; introducing a new namespace.
        site-id [:rf.xray/app-db render-id]
        ;; rf2-227cz — three before-states: `no-diff` (render plain),
        ;; `added` (this whole slice is new this epoch → `:added? true`),
        ;; or a real pre-image (diff against it → `:before`).
        added?      (= h/added before)
        has-before? (and (not added?) (not= h/no-diff before))]
    ;; rf2-7sdja — App-DB does NOT use `:popup-affordance?` (Mike's
    ;; live-testing call 2026-05-26). The side panel has plenty of
    ;; horizontal room; the whole-tree inspector renders comfortably
    ;; in-place. Other panels (Handler / Trace / Machines / Reactive)
    ;; keep the affordance where the inline widget is genuinely
    ;; cramped.
    ;;
    ;; rf2-e28r3 — ONE `ei/edn-inspector` call. `:before` is threaded
    ;; ONLY when a real pre-image is present; its absence is the signal
    ;; to render plainly. `:card?` + `:zoomable?` are constant across
    ;; both cases (rf2-63ie5 gives each top-level mount discrete card
    ;; chrome; rf2-h71e0 makes App-DB the canonical zoom-into-node
    ;; consumer — double-click / Enter on a container re-roots the
    ;; inspector onto it, rf2-zl4rs). Zoom now applies in the single
    ;; full+diff renderer: when a before is present the widget re-roots
    ;; BOTH value and before along the zoom path, so the diff annotations
    ;; paint relative to the focused subtree.
    [ei/edn-inspector
     (f/display-value value)
     (cond-> {:panel-id :rf.xray/app-db
              :site-id  site-id
              :default-expanded-depth 3
              :card? true
              :zoomable? true
              :header title}
       has-before?
       (assoc :before (f/display-value before))
       ;; rf2-227cz — a wholly-new slice (absent in the focused epoch's
       ;; pre-image) opts into the inspector's first-run `:added?` path
       ;; so the entire subtree washes `:added` (green) rather than
       ;; rendering as plain unchanged state.
       added?
       (assoc :added? true))]))

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

(defn state-body
  "Render the full current-state inspector body for the section model
  `current-state-sections` produces.

  rf2-vv3m6 (2026-05-29) — the prior `[diff][full][full+diff]` mode
  toggle (rf2-yqjrd) is retired. FULL+DIFF is the single rendering: the
  section list renders with each section's `:before` threaded so inline
  diff annotations paint (spec/021 §4.3). The 2-arity opts map (`:mode`
  + `:diff-triples`) is gone — the flat-diff lens + the plain-browse
  lens both retired with the toggle.

  Pure hiccup; nil-safe (a nil model degrades to an empty TOP + no
  areas)."
  [{:keys [top areas] :as model}]
  (let [before-top (get model :before-top h/no-diff)]
    (into [:div {:data-testid "rf-xray-app-db-state"}
           (top-section top before-top)]
          (for [{:keys [area] :as area-entry} areas]
            (with-meta
              (area-section area-entry)
              {:key (pr-str area)})))))
