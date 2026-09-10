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
      `:title/flow`). Singleton slices (`:rf/route`,
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

  The renderer carries NO copy affordance (`spec/021-Dynamic-Panel-
  Designs.md` §10.5, the B.9 lock). The universal EDN-widget copy
  gesture that once rode the other surfaces was retired under
  rf2-6r9j.24.

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

;; ---- panel-instance identity (rf2-t3fz) ---------------------------------
;;
;; Every id below — the widget's `:mount-id`, the expansion/zoom
;; `:site-id` — names a LOGICAL SURFACE of the app-db panel, and is
;; deliberately stable so a tab-switch round-trip comes back to the same
;; expansion, the same zoom and the same measured column. That stability
;; is the whole point, and it is also why a surface name cannot double as
;; a LIVE-MOUNT identity: two `Panel`s on screen at once present the same
;; surface names.
;;
;; rf2-d2aj fixed HALF of that. The widget's per-mount store — the
;; ResizeObserver, the width debounce, the Editscript projection cache —
;; is keyed by `[frame-id mount-id]`, so two panels under two DIFFERENT
;; `frame-provider`s no longer collide. Two panels under ONE frame still
;; do, and nothing inside the widget can separate them: a Fresco boundary
;; is a real React function component with no per-instance storage its
;; body may use (hooks do not belong in a body, and `rf.fresco/reg-state`
;; consumes an instance key rather than minting one). rf2-d2aj's closing
;; ruling named that case the CALLER's to name, which is what this is.
;;
;; So `Panel` takes an optional `:instance-id` prop and it is threaded
;; down to here. When the caller names one it qualifies BOTH ids, and
;; that pair is deliberate rather than belt-and-braces: qualifying the
;; store key alone would leave both instances writing the SAME width
;; slot, which is keyed by the logical `mount-id` inside the frame — a
;; wrong number rather than a missing one. When no instance is named
;; every id is byte-for-byte what it was, so the single-mount callers
;; (the L4 tab, the standalone `mount-app-db-diff!` facade) are
;; untouched.
;;
;; `:site-id` is qualified too because a caller who has bothered to name
;; two instances wants them independent: unqualified, expanding a node in
;; one expands it in the other and zooming one zooms both. It stays
;; stable across THAT instance's remounts, which is all rf2-pvsxs asked
;; of it, because a caller-supplied instance id is an identity and not a
;; per-render nonce — see [[instance-token]] for what is refused to keep
;; it that way.

(defn instance-token
  "Normalise `Panel`'s optional `:instance-id` prop to the string that
  qualifies one instance's section ids, or nil when the caller named no
  instance (the single-mount default — every composed id is then exactly
  what it was before rf2-t3fz).

  A KEYWORD is accepted alongside a string, and its NAMESPACE is part of
  the name: `:left/panel` tokenises to `left/panel`. `(subs (str id) 1)`
  is the whole of it — the keyword minus its leading colon.

  ## PUBLIC, and called TWICE on the way in (rf2-4bsq)

  This is the panel family's ONE normaliser, and `app-db-diff`'s
  `Panel-bridge` calls it BEFORE handing the prop across `[:>]`. That is
  the fix for a real collision, not tidiness: Reagent 2.0.1's
  `convert-prop-value` converts a named value with `cljs.core/name`,
  which DROPS the namespace, so `:left/panel` and `:right/panel` both
  reached the boundary as `\"panel\"` and the two panels a caller had
  deliberately named apart shared one `:mount-id`, one width slot and one
  expansion/zoom `:site-id` — the same-frame collision rf2-t3fz exists to
  repair, restored by the crossing. Normalising first means a STRING
  crosses, which Reagent preserves intact.

  So the boundary's own call (from [[value-body]]) sees an
  already-normalised string on that path, which is exactly why this fn is
  IDEMPOTENT: a non-blank string answers itself. The two doors into
  `Panel` — a Fresco body handing over a keyword, a Reagent parent's
  `[:>]` — therefore compose ONE answer for one value, which is what the
  contract promises. (Fresco's `as-component` puts the underlying rule in
  terms: names round-trip across a crossing, values do not. Tokenising to
  the name here is following that rule rather than working around it.)

  Anything else is REFUSED rather than `str`-ed, and that is the point of
  the fn. The token has to be stable across the instance's renders; a
  value whose printed form is minted per render — a map, a JS object —
  would compose a fresh `:mount-id` and `:site-id` on every pass and
  silently throw away expansion, zoom and the measured column width each
  time. That is the same failure `edn-inspector-view`'s own `:mount-id`
  refusal exists to prevent, one level up. Refusing at the BRIDGE now
  means that throw names the caller's own value, before a crossing has
  had a chance to convert it into something else."
  [instance-id]
  (cond
    (nil? instance-id)     nil
    (keyword? instance-id) (subs (str instance-id) 1)
    (string? instance-id)  (when (seq instance-id) instance-id)
    :else
    (throw (ex-info
             (str "The app-db Panel's :instance-id must be a non-blank string "
                  "or a keyword naming ONE live mount of the panel; it was "
                  (pr-str instance-id) ". It is composed into the "
                  "edn-inspector's :mount-id and :site-id, so it must be "
                  "stable across that mount's renders — a value minted per "
                  "render would lose expansion, zoom and the measured column "
                  "width on every pass. Omit it entirely when only one "
                  "app-db Panel is on screen in this frame.")
             {:instance-id instance-id}))))

(defn- value-body
  "Render a current-state VALUE, with the inline `← changed` diff
  annotation when a pre-image is supplied (spec/021 §4.3). `render-id`
  keeps adjacent renders' testids independent across the panel.

  The 5-arity's `instance-id` is `Panel`'s optional per-instance name;
  see the block comment above for what it separates and why. The 4-arity
  is the single-mount call and composes exactly the ids it always did.

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
  ([value before render-id title]
   (value-body value before render-id title nil))
  ([value before render-id title instance-id]
  (let [;; rf2-t3fz — the caller's per-instance name, or nil for the
        ;; single-mount default. Normalised (and type-refused) ONCE here so
        ;; the two compositions below can never disagree about it.
        instance (instance-token instance-id)
        ;; rf2-k97c.3 — `:mount-id` is REQUIRED by the Fresco head and must
        ;; be a stable string: a boundary is a React function component with
        ;; no form-2 outer body, so an id minted in the body would be fresh
        ;; every render and the widget would lose its expansion state each
        ;; pass. `render-id` already names the logical surface, which is
        ;; exactly the stability wanted, and it is what `:site-id` below is
        ;; built from too. (This is the string the pre-migration
        ;; `_node-key` binding already spelled out and discarded.)
        ;;
        ;; rf2-d2aj — and a LOGICAL name is all it is. This string is not
        ;; unique to a mounted panel: the gallery renders twelve variants at
        ;; once and each embedded panel is another, so several live mounts
        ;; present `app-db-state/top` together. The widget is what keeps the
        ;; two identities apart — it qualifies this name by the frame the
        ;; mount renders under to key its per-mount store — so DO NOT make
        ;; this string unique per render to fix a lifecycle collision: it
        ;; would take `:site-id` with it and lose expansion and zoom on
        ;; every pass, which is the trade the widget's split exists to
        ;; avoid.
        ;;
        ;; rf2-t3fz — the frame qualifier does not reach two panels in ONE
        ;; frame, and the caller's `instance` is what does. It is a stable
        ;; NAME and not a per-render nonce (`instance-token` refuses the
        ;; shapes that would not be), so this stays exactly the stable
        ;; string the paragraph above requires — it now names one live
        ;; instance's surface rather than every instance's at once.
        mount-id  (if instance
                    (str "app-db-state/" instance "/" render-id)
                    (str "app-db-state/" render-id))
        ;; rf2-pvsxs — stable `:site-id` so expansion overrides survive
        ;; a tab-switch round-trip. `render-id` already identifies the
        ;; logical surface (e.g. "top" for the user-domain section, an
        ;; area name for the per-:rf/* sections), so passing it AS the
        ;; site-id reuses the existing per-surface key without
        ;; introducing a new namespace.
        ;;
        ;; rf2-t3fz — qualified by the same instance name when the caller
        ;; supplies one, so two named instances expand and zoom
        ;; independently. Unqualified it is unchanged, which is every
        ;; single-mount call site.
        site-id (if instance
                  [:rf.xray/app-db instance render-id]
                  [:rf.xray/app-db render-id])
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
    ;;
    ;; rf2-k97c.3 — `ei/edn-inspector-view`, the widget's FRESCO head,
    ;; rather than `ei/edn-inspector`, its Reagent one. Both render the
    ;; same `render-inspector` body over the same opts; only the OBSERVER
    ;; differs, which is the whole subject of this epic — the Fresco head
    ;; reads its three slots with `rf.fresco/sub` so the shipped collector
    ;; wires them, where the Reagent head derefs reactions the installed
    ;; adapter owns. A boundary head is legal in a boundary body (`[head
    ;; props]` is `defview`'s own mount spelling); what would be a loud
    ;; error is a PLAIN fn in head position.
    [ei/edn-inspector-view
     {:mount-id mount-id
      :value    (f/display-value value)
      :opts     (cond-> {:panel-id :rf.xray/app-db
                         :site-id  site-id
                         :default-expanded-depth 3
                         :card? true
                         :zoomable? true
                         :header title}
                  has-before?
                  (assoc :before (f/display-value before))
                  ;; rf2-227cz — a wholly-new slice (absent in the focused
                  ;; epoch's pre-image) opts into the inspector's first-run
                  ;; `:added?` path so the entire subtree washes `:added`
                  ;; (green) rather than rendering as plain unchanged state.
                  added?
                  (assoc :added? true))}])))

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
  an empty-state body.

  rf2-t3fz — the 3-arity carries `Panel`'s optional `:instance-id` down
  to the value body; see the block comment above [[instance-token]]."
  ([top] (top-section top h/no-diff))
  ([top before] (top-section top before nil))
  ([top before instance-id]
   (let [title  [:span "app-db"]
         empty? (and (map? top) (empty? top))]
     (section-shell
       {:testid       "rf-xray-app-db-state-top"
        :title        title
        :hide-header? (not empty?)
        :body         (if empty?
                        (empty-body "app-db has no user-domain keys yet.")
                        (value-body top before "top" title instance-id))}))))

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
  edn-inspector card's own header ribbon carries the title.

  rf2-t3fz — the 3-arity carries `Panel`'s optional `:instance-id`."
  ([area inst] (instance-section area inst nil))
  ([area {:keys [id value] :as inst} instance-id]
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
                                  title
                                  instance-id)}))))

(defn instances-area
  "Render a map-of-instances reserved area (`:rf/machines`,
  `:rf/spawned`). Fans out to one `instance-section` per id.

  rf2-jcdvo — empty registries are filtered at projection time
  (`current-state-sections` omits `:empty?` entries) so this fn is
  only invoked for populated registries; no empty-state branch.

  rf2-t3fz — the 2-arity carries `Panel`'s optional `:instance-id`."
  ([area-entry] (instances-area area-entry nil))
  ([{:keys [area instances]} instance-id]
  (into [:div {:data-testid (str "rf-xray-app-db-state-area-" (pr-str area))}]
        (for [{:keys [id] :as inst} instances]
          ;; rf2-k97c.3 — the sequence key rides on a keyed FRAGMENT rather
          ;; than on the returned vector's metadata. This subtree now renders
          ;; through Fresco's codec, whose head table reads a literal `:key`
          ;; in the attribute map and nothing else, so a `with-meta` key
          ;; silently degrades to index-based reconciliation. MEASURED, not
          ;; inferred: the #9578 merged-PR audit ran the same two families
          ;; through both renderers and read metadata keys `[k1 k2]` and
          ;; Reagent React keys `[k1 k2]` against Fresco React keys
          ;; `[nil nil]`, with a positive control moving the SAME values into
          ;; the attribute map recovering them (rf2-vw80 owns the surviving
          ;; instances in `cancellation_cascade.cljs`). `instance-section`
          ;; answers hiccup of no fixed shape, so there is no one attribute
          ;; map to write into; `[:<> …]` takes the key and adds no DOM node.
          [:<> {:key (pr-str id)}
           (instance-section area inst instance-id)]))))

(defn singleton-area
  "Render a singleton-slice reserved area (`:rf/route`,
  `:rf/pending-navigation`, `:rf/elision`) as ONE
  section.

  rf2-jcdvo — empty/absent slices are filtered at projection time
  (`current-state-sections` omits `:empty?` entries) so this fn is
  only invoked for populated slices; no empty-state branch. The
  section-shell H3 is suppressed — the edn-inspector card's own header
  ribbon carries the title.

  rf2-t3fz — the 2-arity carries `Panel`'s optional `:instance-id`."
  ([area-entry] (singleton-area area-entry nil))
  ([{:keys [area value] :as area-entry} instance-id]
   (let [title (area-label area)]
     (section-shell
       {:testid       (str "rf-xray-app-db-state-area-" (pr-str area))
        :title        title
        :hide-header? true
        :body         (value-body value (get area-entry :before h/no-diff)
                                  (pr-str area)
                                  title
                                  instance-id)}))))

(defn area-section
  "Dispatch one reserved-area section entry (from
  `current-state-sections`'s `:areas`) to the matching renderer based
  on its `:kind`. Only invoked for non-empty areas — empty entries are
  filtered at projection time (rf2-jcdvo).

  rf2-t3fz — the 2-arity carries `Panel`'s optional `:instance-id`."
  ([area-entry] (area-section area-entry nil))
  ([{:keys [kind] :as area-entry} instance-id]
   (case kind
     :instances (instances-area area-entry instance-id)
     :singleton (singleton-area area-entry instance-id)
     ;; Defensive — an unknown kind still renders the bare key so the
     ;; area never silently vanishes.
     (singleton-area area-entry instance-id))))

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
  areas).

  rf2-t3fz — the 2-arity takes `Panel`'s optional `:instance-id` and
  threads it to every section, which is what lets two `Panel`s under ONE
  `frame-provider` own separate edn-inspector lifecycles. The 1-arity is
  the single-mount call and composes exactly the ids it always did; see
  the block comment above `instance-token`."
  ([model] (state-body model nil))
  ([{:keys [top areas] :as model} instance-id]
   (let [before-top (get model :before-top h/no-diff)]
     (into [:div {:data-testid "rf-xray-app-db-state"}
            (top-section top before-top instance-id)]
           (for [{:keys [area] :as area-entry} areas]
             ;; rf2-k97c.3 — keyed fragment; see `instances-area` above.
             [:<> {:key (pr-str area)}
              (area-section area-entry instance-id)])))))
