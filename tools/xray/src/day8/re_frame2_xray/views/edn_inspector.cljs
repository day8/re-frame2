(ns day8.re-frame2-xray.views.edn-inspector
  "Xray's first-class edn-inspector widget — roll-your-own CLJS-value
  renderer.

  ## What this is

  ONE renderer for every Xray surface that shows a CLJS value:
  App-db, Trace per-event payload, Sub value inspector, Machine
  snapshot drill-in. The widget produces pure hiccup, owns its
  expansion state in re-frame app-db, and reads colour through CSS
  token variables so light + dark themes resolve at paint time
  without a re-render.

  ## What this widget owns

  - Current-state browse for every call site (the App-DB panel and the
    rest mount this widget directly).
  - Sentinels (`:rf/redacted`, `:rf.size/large-elided`) as first-class
    types — chip chrome rendered inline, no separate wrapper.
  - Diff rendering as an opt-in mode on the same widget — pass `:before`
    to render with gutter glyphs + `← was <prior>` annotations.
  - Native CLJS-value rendering, classified from CLJS itself with no
    external devtools dependency.

  ## Public API

      [edn-inspector value]                ;; browse (no diff)
      [edn-inspector value opts]           ;; browse / diff
      [edn-inspector-diff before after]    ;; diff convenience
      [edn-inspector-diff before after opts]

      [mini value]              ;; one-line inline (no expansion)
      [mini value max-len]      ;; with width cap

  `opts` keys:

  - `:panel-id`     (optional, default `:rf.xray.edn-inspector/anon`)
                    distinguishes per-panel expansion state.
  - `:site-id`      (optional) — when supplied, becomes the
                    second component of the per-node expansion key
                    INSTEAD of the auto-generated mount-id. Lets the
                    same logical call site survive a panel-leave-and-
                    return round-trip (auto-mount-id changes on remount;
                    a stable site-id does not). Omit to keep the per-
                    call-site isolation default (two `[edn-inspector]`
                    mounts side-by-side stay independent).
  - `:default-expanded-depth` (optional, default 8) — an EXPAND
                    CEILING. The widget NEVER auto-expands past this
                    depth — deeper nodes render as a `▸ {…N keys}`
                    collapsed summary unless the operator clicks. Under
                    the width-aware heuristic shallow nodes inline
                    whenever their full `pr-str` fits the measured
                    column; the depth ceiling only protects against
                    pathological wide-and-deep auto-expansion when
                    measurements are unavailable. When no width
                    measurement has arrived the depth-driven path runs:
                    any explicit value (e.g.
                    `:default-expanded-depth 2`) drives that fallback.
  - `:max-inline-width` (optional, default 60) — character budget for
                    the COLLAPSED-PREVIEW one-liner (`▸ {:a 1, :b 2,
                    …}` style); leaves the width-aware inline decision
                    to `available-width-px` (the measured column).
  - `:max-depth` (optional, default 16) — hard cap on recursion
                    depth; deeper levels render `{…}` collapsed.
  - `:before` (optional) — the prior value to annotate against. The
                    widget has ONE rendering path keyed on value (always)
                    + before (optional): with a `:before` present the
                    `value` arg is the `after` side and the tree paints
                    inline diff annotations — gutter glyphs + colours per
                    node (`+` added · `-` removed · `~` modified ·
                    `◴` children-changed), modified leaves get an inline
                    `← was <prior>` annotation, the R4 op-coloured rail +
                    R3 `[N∆]` collapsed-change chip render on change-
                    bearing containers, and ancestors of any changed
                    descendant force open regardless of the default-
                    expand heuristic. With no `:before` (and no
                    `:added?`) the same renderer shows `value` plainly —
                    no annotations, no rail, no chip.
  - `:added?` (optional, default false) — FIRST-RUN signal: a
                    value that just came into existence (a sub's first
                    cache entry, an app-db key that just appeared) with
                    no prior value to diff against. Synthesises the
                    before side as `engine/missing-sentinel`, so the
                    whole tree classifies as `:added` (green wash + `+`
                    chrome over every descendant). An explicit `:before`
                    always wins; empty containers still read `:added`.
  - `:popup-affordance?` (optional, default false) — when true the
                    widget renders a top-right ↗ icon button (reads as
                    'open in new pane') that dispatches
                    `[:rf.xray.edn-inspector-popup/open mount-id payload]`
                    through the captured frame-aware dispatcher so the
                    popup-open write lands on the surrounding instance
                    frame (N shells stay isolated). The popup-mount-id
                    is derived from this widget's own mount-id, so
                    re-clicking raises the existing popup rather than
                    spawning a duplicate. Opt-in per call-site; panels
                    enable the affordance where the inline widget is
                    genuinely cramped (machine snapshots, sub values,
                    trace payloads). App-DB does NOT use the affordance
                    — it has plenty of horizontal room, so the popup
                    would be unnecessary affordance noise.
  - `:card?`         (optional, default false) — when true
                    the widget's outer container carries the inspector-
                    card chrome (background `:bg-1`, 1px `:border-
                    default` border, `8px` radius, `8px 10px` padding,
                    `8px` margin-bottom) so multiple top-level mounts
                    in the same panel read as DISTINCT cards rather
                    than blending into one continuous block. Theme-
                    aware via tokens — both light + dark resolve at
                    paint time. Opt-in per call-site: inline mounts
                    (table cells, popup contents that already carry
                    modal chrome, diff sub-renderers) leave it off;
                    panels with multiple top-level inspector mounts
                    (App-DB's TOP + per-`:rf/*` sections; Handler's
                    side-by-side event-detail mounts) opt in.
  - `:header`        (optional, default nil) — opt-in
                    three-shade card chrome (outer `<section>` →
                    `<header>` ribbon → body sleeve), modelled on the
                    Machine panel's `focused-event-section`. `nil`
                    renders inline. A string renders a
                    plain-label ribbon. Hiccup renders whatever the
                    consumer composes (label + code chip + per-
                    inspector affordances). The widget treats the
                    value as opaque hiccup — no parsing, no required
                    shape.

                    Aesthetic (light theme; dark mirrors via theme
                    tokens): outer `<section>` paints `:bg-2`
                    (#ffffff) with a `1px solid :border-default`
                    border + `4px` radius; the `<header>` ribbon
                    paints `:bg-3` (#e8e8e8) with `10px 12px`
                    padding + a `1px solid :border-subtle` bottom
                    rule; the body sleeve paints `:bg-1` (#f5f5f5)
                    with `12px` padding. The three-shade ramp reads
                    as a single card with a distinct label band
                    rather than one continuous block.

                    Composes with `:popup-affordance?` (the icon
                    sits at the section's top-right corner) and
                    with `:card?` (independent — `:header` provides
                    its own surface chrome, so `:card?` is usually
                    redundant when `:header` is present).

  - `:zoomable?`     (optional, default false) — when true every
                    non-root container becomes a zoom-in TARGET:
                    double-click (or Enter while the node is keyboard-
                    focused) re-roots the inspector onto that node. The
                    gesture lives on the container itself, which carries
                    `tab-index 0` + an `aria-label` so the keyboard +
                    screen-reader affordance is available. The gesture
                    dispatches
                    `[:rf.xray.edn-inspector/zoom-to panel-id mount-id
                    absolute-path]`, which stores the absolute path
                    under `:rf.xray.edn-inspector/zoom` keyed by
                    `[panel-id site-or-mount-id]`. The widget then
                    re-roots onto that subtree, with a breadcrumb row
                    above the body showing the path from the original
                    root (each segment clickable for one-tap zoom-up).
                    Esc (when the widget has focus AND a zoom is
                    active) pops one level off the zoom stack.
                    Zoom applies in the SINGLE full+diff renderer: the
                    re-root walks `value` always and `before` too when a
                    pre-image is present,
                    so the diff rail / chip / inline annotations paint
                    relative to the zoomed subtree. A zoom into a wholly
                    `:added` subtree re-roots both halves so the whole
                    subtree reads `:added`; a stale path falls back to
                    the full value. Composes with `:popup-affordance?`
                    (popup = open in new pane; zoom = focus here).
                    Per-mount keying matches the expansion-slot pattern;
                    pass a stable `:site-id` to survive a panel-leave-
                    and-return round-trip.

  There is no `:render-id` arg — the mount-id is auto-generated
  internally.

  ## Per-call-site isolation

  Each `[edn-inspector …]` mount auto-assigns a UUID `mount-id` on
  first render (captured in a form-2 outer `let`). Two mounts side-
  by-side in the same panel get independent expansion state. The
  expansion key is `[panel-id mount-id path]` by default.

  When the same logical call site needs to SURVIVE a remount (e.g.
  the App-DB tab unmounts on tab-switch and re-mounts on return),
  pass a stable `:site-id` in `opts`. The expansion key becomes
  `[panel-id site-id path]` — independent of remount cadence. The
  cost is opt-in: a consumer that passes the SAME `:site-id` from
  two mount sites would have them share expansion state. Per-call-
  site isolation is the default; persistence-across-unmount is opt-in.

  ## Why pure hiccup + no Reagent direct

  Substrate-agnostic — downstream adapters (Reagent, UIx, Helix) all
  see the same hiccup. The mount-id capture uses Reagent form-2
  semantics in `edn-inspector` itself (rf2 substrate is currently
  Reagent in tools); the rest of the renderer is pure data
  transformations + `rf/subscribe` reads.

  ## CSS token classes

  The widget paints via CSS-variable strings from `theme.tokens` — no
  per-theme code path. The class table maps each leaf type to its
  token; the active theme class scope on the shell root decides which
  hex resolves.

  | Leaf type        | Token              | Bracket style            |
  |------------------|--------------------|--------------------------|
  | nil              | `:syntax-nil`      | n/a                      |
  | boolean          | `:syntax-boolean`  | n/a                      |
  | integer / float  | `:syntax-number`   | n/a                      |
  | string           | `:syntax-string`   | n/a                      |
  | keyword          | `:syntax-keyword`  | n/a                      |
  | symbol           | `:syntax-symbol`   | n/a                      |
  | uuid / regex     | `:info`            | n/a                      |
  | inst / date      | `:info`            | n/a                      |
  | fn               | `:text-tertiary`   | (italic)                 |
  | :rf/redacted     | `:magenta`         | (chip)                   |
  | :rf.size/elided  | `:yellow`          | (chip)                   |
  | map              | `:text-tertiary`   | `{` `}`                  |
  | vector           | `:text-secondary`  | `[` `]`                  |
  | list / seq       | `:text-secondary`  | `(` `)`                  |
  | set              | `:text-secondary`  | `#{` `}`                 |
  | map-entry        | `:accent`          | `[` `]` (accent tone)    |
  | record           | `:info`            | `#user.Rec{` `}`         |
  | js object        | `:text-tertiary`   | `#object[` `]`           |

  The per-type tokens follow an editor-syntax-highlight scheme (One
  Dark / One Light). Five scalar types span four hue families: keyword
  magenta, string green, number orange, boolean gold, nil grey."
  (:require [clojure.string :as str]
            [re-frame.core :as rf]
            [day8.re-frame2-xray.theme.tokens
             :refer [tokens mono-stack sans-stack]]
            ;; Expansion-state registration lives in its own namespace
            ;; (the `expansion-slot` key, its reg-sub, the
            ;; toggle/set/reset events, `expansion-key`, `resolve-expanded?`).
            ;; Re-exported below so call sites + tests resolve against
            ;; this widget namespace.
            [day8.re-frame2-xray.views.edn-inspector-state :as state]
            [day8.re-frame2-xray.views.edn-inspector-protocol :as ddp]
            ;; Editscript-backed diff projection engine. Produces
            ;; `{:path-ops :container-ops :flat-rows
            ;; :wholly-changed-roots :shift-suffix :vector-removals}`
            ;; — the renderer chrome below consumes it via
            ;; `engine/op-at`, `engine/wholly-changed-ancestor`,
            ;; `engine/shifted-was-index`, etc.
            [day8.re-frame2-xray.diff.engine :as engine]
            ;; Load default IXrayEdnInspector formatters for uuid + inst.
            ;; Requiring for side-effect (extend-type). Consumers that
            ;; extend the same types win — `extend-type` installs the
            ;; most-recently-loaded impl.
            [day8.re-frame2-xray.views.edn-inspector-default-formatters]))

;; =========================================================================
;; expansion state — lives in `views/edn-inspector-state`.
;; The slot key, its reg-sub, the toggle/set/reset events, `expansion-key`,
;; and `resolve-expanded?` live there (it registers the global
;; event/sub ids). Re-exported here so call sites + tests resolve
;; `edn-inspector/{expansion-slot,expansion-key,resolve-expanded?}`.
;; The slot is :rf.xray.edn-inspector/expansion under the SURROUNDING
;; instance frame (the shell's frame-id; `:rf/xray` for the production
;; singleton) — per-frame so N shells keep independent expansion.
;; =========================================================================

(def expansion-slot
  "Re-export of `edn-inspector-state/expansion-slot` — the app-db slot
  holding the per-node expansion overrides. Public so the consuming
  panel's reset affordance can clear it."
  state/expansion-slot)

(def expansion-key
  "Re-export of `edn-inspector-state/expansion-key` — composes the
  per-node expansion key. Pure data, JVM-portable."
  state/expansion-key)

(def resolve-expanded?
  "Re-export of `edn-inspector-state/resolve-expanded?` — pure
  projection returning whether THIS node renders expanded; the operator's
  sticky override (if present) wins."
  state/resolve-expanded?)

;; =========================================================================
;; available-width capture — per-mount measurement for the width-aware
;; expansion heuristic
;; =========================================================================
;;
;; The widget measures its container's `clientWidth` via a `:ref` callback
;; and stores the result keyed by `mount-id` under this app-db slot. A
;; ResizeObserver, installed when the container element mounts, updates the
;; slot whenever the panel resizes — wider → more collections render inline;
;; narrower → more expand.
;;
;; Per-mount keying lets two sibling mounts in the same panel record
;; different widths without colliding (e.g. a multi-column comparison panel
;; where each column hosts its own inspector).
;;
;; When no measurement is yet present (first render before the ref fires,
;; or a programmatic test render that doesn't mount), the widget falls
;; back to the strict inline-fit gate — the heuristic improvement
;; is additive and graceful.

(def widths-slot
  "App-db slot holding the per-mount measured container widths in CSS
  pixels (map of `mount-id` → integer). Public so tests + consuming
  panels can drive measurements deterministically without spinning up a
  real DOM."
  :rf.xray.edn-inspector/widths)

(rf/reg-sub widths-slot
  (fn [db _] (get db widths-slot)))

(rf/reg-event :rf.xray.edn-inspector/set-width
  (fn [{:keys [db]} [_ mount-id width-px]]
    {:db (if (and (string? mount-id) (number? width-px) (pos? width-px))
      (assoc-in db [widths-slot mount-id] (long width-px))
      db)}))

(rf/reg-event :rf.xray.edn-inspector/clear-width
  (fn [{:keys [db]} [_ mount-id]]
    {:db (if (and (string? mount-id) (some-> db (get widths-slot) (contains? mount-id)))
      (update db widths-slot dissoc mount-id)
      db)}))

;; =========================================================================
;; zoom-into-node + breadcrumb navigation
;; =========================================================================
;;
;; Zoom turns the inspector into a focused window onto an arbitrary subtree.
;; Operator double-clicks any container (or presses Enter while it is
;; keyboard-focused); that node becomes the root of the displayed
;; tree. The gesture lives on the container itself. A breadcrumb trail at
;; the top shows the path from the original root; clicking any segment
;; zooms back to that level. Esc zooms out one level.
;;
;; State shape mirrors `expansion-slot` — keyed by `[panel-id site-or-
;; mount-id]` so two side-by-side mounts zoom independently and a stable
;; `:site-id` preserves the zoomed view across a panel-
;; leave-and-return round-trip:
;;
;;   {[:rf.xray/app-db [:rf.xray/app-db "top"]] [:cart :items]
;;    [:rf.xray/handler "mount-uuid-…"]         []}
;;
;; An entry with `nil` or `[]` value renders the full tree (no zoom). The
;; sub `:rf.xray.edn-inspector/zoom-path` reads the slot; events
;; `:zoom-to`, `:zoom-up`, `:zoom-reset` mutate it.

(def zoom-slot
  "App-db slot holding the per-mount zoom path overrides. Public so the
  consuming panel's reset affordance can clear it.

  Mirrors `expansion-slot`'s shape — a map keyed by `[panel-id site-or-
  mount-id]` with a path vector as the value (the path within the
  original value that becomes the current zoom root). Empty / missing
  entries render the full tree."
  :rf.xray.edn-inspector/zoom)

(defn zoom-key
  "Compose the per-mount zoom key — `[panel-id site-or-mount-id]`. Pure
  data, JVM-portable. Distinct from `expansion-key`'s three-element shape
  because zoom is per-mount (one zoom root), not per-node (one expansion
  override per path)."
  [panel-id mount-id]
  [panel-id mount-id])

(rf/reg-sub zoom-slot
  (fn [db _] (get db zoom-slot)))

(rf/reg-event :rf.xray.edn-inspector/zoom-to
  ;; Sets the zoom path for `[panel-id mount-id]` to `path`. An empty /
  ;; nil path clears the zoom (renders the full tree).
  (fn [{:keys [db]} [_ panel-id mount-id path]]
    {:db (let [k (zoom-key panel-id mount-id)
          p (vec path)]
      (if (seq p)
        (assoc-in db [zoom-slot k] p)
        (update db zoom-slot dissoc k)))}))

(rf/reg-event :rf.xray.edn-inspector/zoom-up
  ;; Pop one segment off the zoom path. No-op when no zoom is active.
  (fn [{:keys [db]} [_ panel-id mount-id]]
    {:db (let [k        (zoom-key panel-id mount-id)
          current  (get-in db [zoom-slot k])
          popped   (when (seq current) (vec (butlast current)))]
      (cond
        (nil? current)   db
        (empty? popped)  (update db zoom-slot dissoc k)
        :else            (assoc-in db [zoom-slot k] popped)))}))

(rf/reg-event :rf.xray.edn-inspector/zoom-reset
  ;; Clear the zoom for a specific mount. With no args (mount-unspecified)
  ;; clear the entire slot — used by the panel-level reset affordance.
  (fn [{:keys [db]} [_ panel-id mount-id]]
    {:db (cond
      (and panel-id mount-id)
      (update db zoom-slot dissoc (zoom-key panel-id mount-id))

      :else
      (dissoc db zoom-slot))}))

(defn resolve-zoom-path
  "Pure projection — given the per-render zoom map, return the zoom
  path for this mount (vector of segments) or `nil` for the default no-
  zoom state. Public so tests + render-time consumers can drive the
  decision deterministically."
  [zoom-map panel-id mount-id]
  (let [v (get zoom-map (zoom-key panel-id mount-id))]
    (when (seq v) (vec v))))

(defn resolve-zoom-into
  "Pure projection — given the original `value` and the zoom map for a
  mount, return the value the widget should display. Walks `get-in`
  along the stored zoom path; falls back to the original value when the
  path is empty / nil / no-longer-resolvable (a value mutated out from
  under a stale zoom). Public so callers can probe the resolution
  without re-deriving the walk."
  [value zoom-map panel-id mount-id]
  (let [path (resolve-zoom-path zoom-map panel-id mount-id)]
    (cond
      (nil? path)   value
      (empty? path) value
      :else
      (let [resolved (try (get-in value path ::no-resolve)
                          (catch :default _ ::no-resolve))]
        (if (= resolved ::no-resolve) value resolved)))))

;; =========================================================================
;; type classification — sentinels recognised as first-class types
;; =========================================================================

(defn redacted-sentinel?
  "`:rf/redacted` bare keyword — spec/015 primary opaque sentinel."
  [v]
  (= :rf/redacted v))

(defn large-sentinel?
  "`{:rf.size/large-elided <body>}` size-elision sentinel per Spec 015.
  `<body>` carries `:path :bytes :type :reason :hint :handle` (plus
  optional `:digest` when `:include-digests?` is set on the elision
  walk). Emitted by `implementation/core/src/re_frame/elision.cljc`."
  [v]
  (and (map? v)
       (= 1 (count v))
       (let [[k m] (first v)]
         (and (= :rf.size/large-elided k) (map? m)))))

(defn redacted+size-sentinel?
  "Combined `{:rf/redacted {:bytes N}}` — sensitive + size-aware."
  [v]
  (and (map? v)
       (= 1 (count v))
       (let [[k m] (first v)]
         (and (= :rf/redacted k) (map? m)))))

(defn map-entry?*
  "True for clojure.lang.MapEntry — distinct from a 2-vector.
  ClojureScript's `MapEntry` shows up as a 2-element vector with
  identical print but distinct nominal type. The `*` suffix avoids
  shadowing `cljs.core/map-entry?` for callers that `:refer` it."
  [v]
  (instance? cljs.core/MapEntry v))

(defn record?*
  "True for a defrecord instance. CLJS records carry the
  `cljs$lang$type` static field."
  [v]
  (and (map? v)
       (try (some? (.-cljs$lang$type v))
            (catch :default _ false))))

(defn collection-kind
  "Classify a value into one of #{:map :vector :list :set :map-entry
  :record :seq :scalar :sentinel-redacted :sentinel-large :nil
  :string :number :keyword :symbol :boolean :uuid :regex :fn :other}.

  Pure function; no rendering."
  [v]
  (cond
    (nil? v)                       :nil
    (redacted-sentinel? v)         :sentinel-redacted
    (large-sentinel? v)            :sentinel-large
    (redacted+size-sentinel? v)    :sentinel-redacted-size
    (boolean? v)                   :boolean
    (keyword? v)                   :keyword
    (symbol? v)                    :symbol
    (string? v)                    :string
    (number? v)                    :number
    (uuid? v)                      :uuid
    (regexp? v)                    :regex
    (fn? v)                        :fn
    (map-entry?* v)                 :map-entry
    (record?* v)                   :record
    (map? v)                       :map
    (vector? v)                    :vector
    (set? v)                       :set
    (list? v)                      :list
    (seq? v)                       :seq
    :else                          :other))

;; =========================================================================
;; diff mode — projection-aware chrome
;; =========================================================================
;;
;; Diff classification flows through `day8.re-frame2-xray.diff.engine`'s
;; pre-computed projection (`engine/project`) + the path-keyed lookup
;; helpers (`engine/op-at`, `engine/wholly-changed-ancestor`,
;; `engine/shifted-was-index`, `engine/type-change?`,
;; `engine/redaction-side`, `engine/change-count-at`).
;;
;; The `::missing` sentinel is re-exported below for the diff-aware
;; container-iteration logic (`children-of-pair`, `diff-pair-count`) —
;; those walkers thread `::missing` to represent slots that exist on one
;; side of the diff only. The CLASSIFIER side of that interaction (which
;; op to attach to the leaf) flows through the engine's projection map.

(def missing-sentinel
  "Marker for a `:before` / `:after` slot that does not exist in its
  side of the diff. The `children-of-pair` / `diff-pair-count` walkers
  thread this same `::missing` keyword (they live in THIS ns, so they
  don't need it to be public). It stays public only so the
  edn-inspector tests can assert the walker triples against the exact
  sentinel value — distinct from `engine/missing-sentinel`
  (`:day8.re-frame2-xray.diff.engine/missing`), which is the CLASSIFIER
  side's marker. Never collides with a real `nil` slot."
  ::missing)

;; ---- per-op token tables ------------------------------------------------
;;
;; These five `op-*` lookups carry the rendering chrome rules. They are
;; PURE — given an op keyword (one of `:added / :removed / :modified /
;; :same / :same-shifted / :children`) they return the glyph / token-
;; key / colour the renderer paints. The projection layer is upstream
;; of these — the renderer hands the engine an op tag, then this
;; table maps to chrome. R5-tinted (suppress glyph + stripe; retain
;; wash) is implemented at the call site in `gutter-row` by the caller
;; passing `:suppress-glyph?` / `:suppress-stripe?` flags.

(defn- op-glyph
  "Gutter glyph per op. `:same-shifted` shows NO glyph (per R6) — the
  `(was N)` suffix carries the identity signal alone."
  [op]
  (case op
    :added        "+"
    :removed      "-"
    :modified     "~"
    :children     "◴"
    :same-shifted " "
    :same         " "
    " "))

(defn- op-gutter-colour
  "Gutter glyph colour. Active ops paint `:diff-gutter` (reserved cyan-
  teal, distinct from every `:syntax-*` family); `:same` and
  `:same-shifted` paint `:text-tertiary`."
  [op]
  (case op
    (:added :removed :modified :children) (:diff-gutter tokens)
    (:text-tertiary tokens)))

(defn- op-wash-bg
  "Row-background wash CSS string. R5-tinted: descendants of a wholly-
  changed root inherit the wash for their parent op (so a scrolled-in
  view of a 20-leaf added shard still reads as green) but the caller
  decides via `:effective-op` what op the wash should reflect."
  [op]
  (case op
    :added    (:diff-added-wash tokens)
    :removed  (:diff-removed-wash tokens)
    :modified (:diff-modified-wash tokens)
    nil))

(defn- op-stripe-colour
  "2px left-edge stripe colour. Suppressed for `:children` (the change
  is below — the descendants carry the signal), `:same`, and
  `:same-shifted`."
  [op]
  (case op
    :added    (:diff-added-stripe tokens)
    :removed  (:diff-removed-stripe tokens)
    :modified (:diff-modified-stripe tokens)
    nil))

;; ---- gutter-row hoisted style maps --------------------------------------
;;
;; `gutter-row` fires once per change-bearing leaf in a diff render — a
;; 30-changed-leaf render allocates 90 fresh map literals at the call site without
;; hoisting. The base shapes are static; only `:border-left` colour +
;; `:background` wash overlay vary (5-value op enum) on the outer, and
;; `:color` varies on the glyph span. Mirrors the hoisting culture
;; established further down (body-grid-style, body-block-style,
;; key-cell-style, value-cell-style, triangle-style, r3-chip-style).

(def ^:private gutter-row-outer-base-style
  "Outer `<span>` style for `gutter-row` — the static skeleton.
  Dynamic overlays (per render):
   - `:border-left` colour reflects the active op's stripe (or transparent)
   - `:background` wash assoc'd when an op carries one."
  {:display      "inline-flex"
   :align-items  "baseline"
   :gap          "4px"
   :padding-left "6px"})

(def ^:private gutter-glyph-base-style
  "Glyph `<span>` style for `gutter-row` — static skeleton.
  Dynamic overlay: `:color` resolves to the per-op gutter colour
  (or `:text-tertiary` for suppressed / `:same` rows)."
  {:flex        "0 0 12px"
   :font-size   "11px"
   :font-weight 700
   :text-align  "center"
   :user-select "none"})

(def ^:private gutter-body-style
  "Body `<span>` style for `gutter-row` — fully static (no dynamic
  overlay)."
  {:flex 1 :min-width 0})

(defn- gutter-row
  "Wrap `body` with the diff row chrome: gutter glyph + low-opacity
  row-background wash + 2px left-edge stripe.

  ## What lives on what channel

  - **Gutter glyph colour** (`:diff-gutter`, reserved cyan-teal) —
    NEVER changes per op; carries 'this is a diff row' semantic.
    Distinct from every `:syntax-*` family so per-token text colour
    (`:syntax-number` orange, `:syntax-boolean` gold, `:syntax-
    keyword` magenta, etc.) stays UNCONFLATED with diff state.
  - **Gutter glyph shape** (`+ / - / ~ / ◴`) — per-op semantic.
  - **Row wash** (low-alpha background tint per op) — added=green,
    modified=amber, removed=red. ~10-12% opacity reads as
    environmental, not obscuring.
  - **Left-edge stripe** (2px saturated per-op accent) — reinforces
    the row signal at the column-1 anchor without competing with text
    colour.
  - **Per-token text colour** — preserved as the `:syntax-*` palette
    output; the diff path no longer overrides it.

  When the op is `:same` the wrapper is invisible (transparent stripe,
  no wash, blank glyph) so non-diff renders share the same hiccup
  shape as diff renders. `:children` op (ancestor of a changed
  descendant) gets the gutter glyph but NO wash + NO stripe — the
  changed descendants below carry the colour-coded signal.

  ## inline-flex, not flex

  Uses `display: inline-flex` so a diff'd scalar leaf composes inline
  with its preceding key inside a map-row grid. A block-level `<div>`
  would force the leaf onto its own line (the per-row flex container
  can't keep key+value on one line — `flex-wrap: wrap` pushes a wide
  block-div below the key, producing two-line rows).

  Returns an `[:span ...]` (display: inline-flex) so it nests inside a
  grid cell without breaking the row.

  ## R5-tinted suppression

  The optional `chrome-opts` map carries R5-tinted overrides:

  - `:suppress-glyph?` — paint a blank gutter cell (no `+ / − / ~`).
    Set by the renderer for descendants of a wholly-changed root.
  - `:suppress-stripe?` — paint a transparent left-edge stripe.
    Same R5 suppression rule.
  - `:wash-op` — paint the wash for THIS op even when the row's own
    op is `:same` or `:same-shifted` (so descendants of a wholly-
    new subtree still read as green).

  ## slot-vs-value anchoring

  - `:suppress-wash?` — paint NO wash on this gutter-row. Used when
    the slot-anchored chrome paints the wash on the WHOLE ROW (key +
    value grid cells) at the parent's row level (R2 added/removed
    map-key, R6 vector add/remove). Without suppression here, the
    inner wash overlaps the outer cell wash and the row reads as a
    double-painted band over the value half.

  When `chrome-opts` is omitted the chrome falls back to the per-op
  defaults (added=green, modified=amber, removed=red, same=invisible).
  All four optional keys default to nil, so call sites that pass no
  chrome-opts reach the same hiccup shape unchanged."
  ([op body] (gutter-row op body nil))
  ([op body {:keys [suppress-glyph? suppress-stripe? suppress-wash? wash-op]}]
   (let [active?       (and (not (#{:same :same-shifted} op))
                            (not suppress-stripe?))
         effective-wash-op (or wash-op
                               (when-not (#{:same :same-shifted} op) op))
         wash          (when-not suppress-wash?
                         (op-wash-bg effective-wash-op))
         stripe        (when-not suppress-stripe? (op-stripe-colour op))
         glyph         (if suppress-glyph? " " (op-glyph op))
         glyph-colour  (if suppress-glyph?
                         (:text-tertiary tokens)
                         (op-gutter-colour op))]
     ;; Hoisted style bases (see `gutter-row-*-style` defs above):
     ;; the static skeletons are ns-top defs; only the dynamic per-op
     ;; overlays (`:border-left` colour + optional `:background` wash on
     ;; the outer; `:color` on the glyph) are computed per render.
     [:span {:data-rf-diff-op (name op)
             :data-rf-diff-wash (when wash "1")
             :data-rf-diff-stripe (when stripe "1")
             :data-rf-diff-suppressed (when suppress-glyph? "1")
             :style (cond-> (assoc gutter-row-outer-base-style
                              :border-left (str "2px solid "
                                                (if (and active? stripe)
                                                  stripe
                                                  "transparent")))
                      wash (assoc :background wash))}
      [:span {:style (assoc gutter-glyph-base-style :color glyph-colour)}
       glyph]
      [:span {:style gutter-body-style} body]])))

(def ^:private change-annotation-style
  "Style for the inline `← was <prior>` chip rendered to the
  right of a diff'd leaf."
  {:margin-left "8px"
   :color       (:text-secondary tokens)
   :font-family sans-stack
   :font-size   "11px"
   :font-style  "italic"})

(defn- change-annotation
  "Inline `← was <prior>` chip rendered to the right of a
  diff'd leaf. Pure hiccup."
  [before]
  [:span {:data-rf-diff-annotation "1"
          :style change-annotation-style}
   (str "← was " (try (pr-str before)
                      (catch :default _ (str before))))])

;; =========================================================================
;; scalar rendering (no expansion)
;; =========================================================================

(defn- str-pad-quote
  "Render a string with surrounding quotes; preserve newlines as `\\n`
  for inline rendering."
  [s]
  (try
    (pr-str s)
    (catch :default _ (str "\"" s "\""))))

(defn- scalar->string
  "The bare printed STRING for a single non-collection value — the text
  each scalar leaf prints (`\"nil\"`, `\"#uuid \\\"…\\\"\"`, a
  quote-padded string, etc.). Pure; JVM-portable.

  ONE source of truth for the scalar literal forms so `render-scalar`
  (which wraps the string in a colour-coded span) and `inline-scalar-str`
  (which returns the bare string for collapsed previews) don't keep two
  copies of the same literal table. Returns `nil` for shapes whose text
  the caller renders specially — `:fn` (each caller prints a different
  arity of detail) and the sentinels (chip chrome vs. a one-word label)
  — so callers branch those cases themselves."
  [v]
  (case (collection-kind v)
    :nil      "nil"
    :boolean  (str v)
    :keyword  (str v)
    :symbol   (str v)
    :string   (str-pad-quote v)
    :number   (str v)
    :uuid     (str "#uuid \"" v "\"")
    :regex    (str v)
    nil))

(def ^:private token-style
  ;; Memoized — `tokens` is a static const map of CSS-variable strings
  ;; (e.g. `:syntax-keyword` resolves to `"var(--rf-xray-syntax-keyword)"`)
  ;; so the returned map is identity-stable per `token-key`; the cache
  ;; is bounded by the size of the syntax-token vocabulary (~10 keys).
  ;; Called 25+ times per dense-tree render — the memo elides the
  ;; per-call map allocation and `get` lookup.
  ;;
  ;; `:font-family` not set — inherits `mono-stack` from the widget's
  ;; outer wrapper (and from `mini`'s wrapping span when called from
  ;; there). Single-property style keeps the per-call hiccup small.
  (memoize
    (fn [token-key]
      {:color (get tokens token-key)})))

(defn render-scalar
  "Render a single non-collection value as `[:span ...]` hiccup. Pure
  function — no expansion state, no rf reads."
  [v]
  ;; The bare printed string for the simple scalar forms is shared with
  ;; `inline-scalar-str` via `scalar->string`; here each case wraps it in
  ;; its colour-coded `:data-rf-type` span. `:fn` + the sentinels render
  ;; specially (chip chrome / arity-detail), so they branch below.
  (case (collection-kind v)
    :nil      [:span {:data-rf-type "nil"
                      :style (token-style :syntax-nil)} (scalar->string v)]
    :boolean  [:span {:data-rf-type "boolean"
                      :style (token-style :syntax-boolean)} (scalar->string v)]
    :keyword  [:span {:data-rf-type "keyword"
                      :style (token-style :syntax-keyword)} (scalar->string v)]
    :symbol   [:span {:data-rf-type "symbol"
                      :style (token-style :syntax-symbol)} (scalar->string v)]
    :string   [:span {:data-rf-type "string"
                      :style (token-style :syntax-string)} (scalar->string v)]
    :number   [:span {:data-rf-type "number"
                      :style (token-style :syntax-number)} (scalar->string v)]
    :uuid     [:span {:data-rf-type "uuid"
                      :style (token-style :info)} (scalar->string v)]
    :regex    [:span {:data-rf-type "regex"
                      :style (token-style :info)} (scalar->string v)]
    :fn       [:span {:data-rf-type "fn"
                      :style (merge (token-style :text-tertiary)
                                    {:font-style "italic"})}
               (let [nm (try (some-> v meta :name str)
                             (catch :default _ nil))]
                 (if (and nm (seq nm)) (str "#fn[" nm "]") "#fn"))]
    :sentinel-redacted
    [:span {:data-rf-type "rf-redacted"
            :data-testid  "rf-xray-edn-inspector-redacted"
            :title        "Redacted — not revealable (spec/015)"
            :style {:display       "inline-flex"
                    :align-items   "center"
                    :gap           "4px"
                    :padding       "0 6px"
                    :border-radius "3px"
                    :background    "color-mix(in srgb, var(--rf-xray-magenta) 12%, transparent)"
                    :color         (:magenta tokens)
                    :font-size     "11px"
                    :font-style    "italic"
                    :text-transform "lowercase"
                    :letter-spacing "0.5px"
                    :user-select   "none"}}
     [:span {:style {:font-size "10px"}} "●"]
     "redacted"]
    :sentinel-redacted-size
    (let [{:keys [bytes]} (val (first v))]
      [:span {:data-rf-type "rf-redacted-size"
              :data-testid  "rf-xray-edn-inspector-redacted-size"
              :title        "Redacted with size — not revealable (spec/015)"
              :style {:display       "inline-flex"
                      :align-items   "center"
                      :gap           "4px"
                      :padding       "0 6px"
                      :border-radius "3px"
                      :background    "color-mix(in srgb, var(--rf-xray-magenta) 12%, transparent)"
                      :color         (:magenta tokens)
                      :font-size     "11px"
                      :font-style    "italic"
                      :user-select   "none"}}
       [:span {:style {:font-size "10px"}} "●"]
       "redacted"
       (when (and bytes (pos? bytes))
         [:span {:style {:color (:text-tertiary tokens)
                         :font-style "normal"
                         :margin-left "4px"}}
          (str "· " bytes " bytes")])])
    :sentinel-large
    ;; Body keys per Spec 015 §Wire elision:
    ;; `:path :bytes :type :reason :hint :handle`. `:type` is the
    ;; original-value type tag (`:map :vector :string …`); `:hint`
    ;; carries the schema-author's docstring; `:handle` is the
    ;; structured fetch handle for a future drill-in affordance.
    (let [{:keys [bytes type hint]} (val (first v))]
      [:span {:data-rf-type "rf-size-large-elided"
              :data-testid  "rf-xray-edn-inspector-large"
              :title        (cond-> "Large value elided (spec/015)"
                              type (str " · " (name type))
                              hint (str "\n" hint))
              :style {:display       "inline-flex"
                      :align-items   "center"
                      :gap           "4px"
                      :padding       "0 6px"
                      :border-radius "3px"
                      :background    "color-mix(in srgb, var(--rf-xray-yellow) 12%, transparent)"
                      :color         (:yellow tokens)
                      :font-size     "11px"
                      :user-select   "none"}}
       [:span {:style {:font-size "10px"}} "●"]
       "large"
       (when bytes
         [:span {:style {:color (:text-tertiary tokens) :margin-left "4px"}}
          (str "· " bytes " bytes")])])
    ;; Fallback for unknown shapes — pr-str.
    [:span {:data-rf-type "other"
            :style (token-style :text-primary)}
     (try (pr-str v) (catch :default _ (str v)))]))

;; =========================================================================
;; delimiters — bracket characters + style per collection kind
;; =========================================================================

(def delim
  "Per-collection-kind opener/closer/style key map. Bracket COLOUR
  differs per kind so map-entry vs 2-vector is visually distinct.

  - `:open`/`:close` are the bracket characters
  - `:tone-key` is the token key used to colour the brackets

  Public so tests can assert the bracket characters + tone-keys are
  stable across the test surface."
  {:map        {:open "{"  :close "}"  :tone-key :text-tertiary}
   :vector     {:open "["  :close "]"  :tone-key :text-secondary}
   :list       {:open "("  :close ")"  :tone-key :text-secondary}
   :set        {:open "#{" :close "}"  :tone-key :text-secondary}
   :seq        {:open "("  :close ")"  :tone-key :text-secondary}
   :map-entry  {:open "["  :close "]"  :tone-key :accent}
   :record     {:open "{"  :close "}"  :tone-key :info}})

(defn inline-separator
  "Inter-element separator STRING for an inline / collapsed render of a
  collection of `kind`, matching canonical EDN print spacing:

  - maps / records — `\", \"` between consecutive key/value PAIRS, e.g.
    `{:a 1, :b 2}` (the space WITHIN a pair is supplied separately by the
    key→value gap, not by this separator).
  - vectors / lists / sets / seqs — a single `\" \"` space, e.g.
    `[\"machine-epochs\" :machine-epochs/run-step 26 :rf/default]`,
    `#{:a :b}`, `(1 2 3)`.

  EDN treats commas as whitespace, but idiomatic print uses commas ONLY
  to separate map entries; sequential collections are space-separated.
  Pure; JVM-portable. Public so tests can pin the per-kind spacing."
  [kind]
  (if (#{:map :record} kind) ", " " "))

(defn- inline-separator-span
  "The inter-element separator rendered as a `:text-tertiary` hiccup span
  for inline collection renders. Shared by the two inline-fit render
  paths — `render-inline-recursive` (width-aware recursive) and
  `render-container`'s strict inline-fit branch. The separator STRING
  comes from `inline-separator` (canonical EDN spacing per kind)."
  [kind]
  [:span {:style (token-style :text-tertiary)} (inline-separator kind)])

(defn- record-tag
  "Render `#user.MyRec` prefix for a defrecord instance. CLJS records
  expose the constructor's name via `(.-name (type v))`."
  [v]
  (try
    (let [nm (.-name (type v))]
      (str "#" nm))
    (catch :default _ "#record")))

(defn- bracket
  "Single bracket character with the kind's delimiter colour. The
  `:edge` arg is `:open` or `:close`."
  [kind edge value]
  (let [{:keys [tone-key] :as d} (delim kind)
        ch (or (d edge) "?")
        text (cond
               (and (= kind :record) (= edge :open)) (str (record-tag value) ch)
               :else ch)]
    [:span {:style       {:color (get tokens tone-key)}
            :data-rf-bracket (name edge)}
     text]))

;; =========================================================================
;; inline preview — `▸ {:a 1, :b 2, …}` etc.
;; =========================================================================

(declare inline-preview-string)

(defn- inline-scalar-str
  "Compact one-line string preview of a single value. Containers
  render as their `{…N keys}` / `[…N items]` placeholder — we never
  recurse into a child container's contents from inside the preview
  (one-level only). That keeps a `:deeply {:nested {:secret 1}}`
  preview from leaking `:secret` into a collapsed-collection summary."
  [v]
  ;; Simple scalar forms (nil / boolean / keyword / symbol / string /
  ;; number / uuid / regex) share their bare printed string with
  ;; `render-scalar` via `scalar->string`; the `:fn` + sentinel + container
  ;; cases below are preview-specific (a one-word label, not chip chrome).
  (or
   (scalar->string v)
   (case (collection-kind v)
    :fn         "#fn"
    :sentinel-redacted        "redacted"
    :sentinel-redacted-size   "redacted"
    :sentinel-large           "large"
    (:map :vector :list :seq :set :map-entry :record)
    (let [{:keys [open close]} (delim (collection-kind v))
          n (try (count v) (catch :default _ 0))
          noun (case (collection-kind v)
                 :map " keys"
                 :record " keys"
                 " items")]
      (str open "…" n noun close))
    (try (pr-str v) (catch :default _ (str v))))))

(defn inline-preview-string
  "Build a one-line preview of a collection. Returns a string; not
  hiccup. Used for collapsed-collection summaries.

  Pure function; safe to call on the JVM (`pr-str` portable). Caller
  decides max-elements and max-chars.

  Strategy: try first N elements; if the joined string fits within
  `max-chars`, return `\"{ :a 1, :b 2, :c 3 }\"`; if some fit, return
  `\"{:a 1, :b 2, …}\"`; if none fit, return the fallback
  `\"{…3 keys}\"` / `\"[…5 items]\"`."
  [v max-elements max-chars]
  (let [kind (collection-kind v)
        {:keys [open close]} (delim kind)
        fallback-n  (cond
                      (= kind :map) (count v)
                      (coll? v)     (try (count v) (catch :default _ 0))
                      :else         0)
        fallback-noun (case kind
                        :map     " keys"
                        :set     " items"
                        " items")
        fallback    (str open "…" fallback-n fallback-noun close)
        ;; Take up to max-elements + 1 to detect "more remaining".
        head-seq    (try (cond
                           (map? v)        (take (inc max-elements) v)
                           (set? v)        (take (inc max-elements) v)
                           (sequential? v) (take (inc max-elements) v)
                           :else           [])
                         (catch :default _ []))
        head        (take max-elements head-seq)
        more?       (> (count head-seq) max-elements)
        ;; rf2-7hqwe — inter-element separator follows canonical EDN
        ;; spacing: `, ` between map/record entries, a single space
        ;; between sequential (vector / list / set / seq) elements. Was
        ;; a hardcoded `, ` for ALL kinds, which previewed `[a, b, c]`
        ;; rather than `[a b c]`.
        sep         (inline-separator kind)
        item-str    (fn [el]
                      (cond
                        (and (#{:map :record} kind)
                             (or (map-entry? el)
                                 (and (vector? el) (= 2 (count el)))))
                        (str (inline-scalar-str (first el))
                             " " (inline-scalar-str (second el)))
                        :else
                        (inline-scalar-str el)))
        joined      (str/join sep (map item-str head))
        with-more   (str joined (when more? (if (seq joined) (str sep "…") "…")))
        result      (str open with-more close)]
    (if (<= (count result) max-chars)
      result
      ;; Try one-element preview as a middle ground.
      (let [one  (when (seq head) (item-str (first head)))
            mid  (str open one (when (or more? (> (count head) 1)) (str sep "…")) close)]
        (if (and one (<= (count mid) max-chars))
          mid
          fallback)))))

;; =========================================================================
;; recursive renderer — produces hiccup
;; =========================================================================

(declare render-node)
;; `render-leaf-with-diff` uses `mini` for R7 type-change suffix
;; rendering; `mini` is defined later in the file (it's the public 1-arg
;; inline render). Forward declare so the compiler resolves.
(declare mini)

(defn- children-of
  "Return a seq of `[child-key child-value]` pairs for a collection.
  Returns `nil` for non-collections. `child-key` is the path segment
  to use; for sets it's the value itself."
  [v]
  (case (collection-kind v)
    :map       (try (seq v) (catch :default _ nil))
    :map-entry (list [0 (first v)] [1 (second v)])
    :record    (try (seq v) (catch :default _ nil))
    :vector    (map-indexed (fn [i x] [i x]) v)
    :list      (map-indexed (fn [i x] [i x]) v)
    :seq       (map-indexed (fn [i x] [i x]) v)
    :set       (map (fn [x] [x x]) v)
    nil))

(defn- container? [kind]
  (contains? #{:map :vector :list :set :seq :map-entry :record} kind))

(defn- container-family
  "Group `collection-kind` into the diff-equivalence family that the
  engine's empty-edge expansion treats as same-kind: maps/records,
  sets, and the sequential family (vector / list / seq) each form one
  family. Non-containers return nil. Pure."
  [kind]
  (cond
    (#{:map :record} kind)        :map
    (= :set kind)                 :set
    (#{:vector :list :seq} kind)  :seq
    :else                         nil))

(defn diff-emptied?
  "True when a slot's value went from a populated collection to the EMPTY
  collection of the SAME family — `#{:a}→#{}`, `{:k :v}→{}`, `[x]→[]`,
  `(x)→()` — with the KEY INTACT (the slot still exists in AFTER).

  This is the render-side discriminator for an emptied collection. The
  engine's R5 `mark-wholly-changed` legitimately promotes such an
  emptied set / map
  KEY to `:removed` (the opposite side is empty, so there is no surviving
  member to anchor a member-level diff at the container path — see
  `engine/mark-wholly-changed`). But an emptied collection is NOT a
  `dissoc`: its KEY survives with the value now the empty collection and
  the dropped member(s) struck-through INSIDE it. A `dissoc` removes the
  slot entirely (AFTER is `missing-sentinel`).

  So the renderer keys the distinction off the SLOT shape, not the
  projection op: a present (non-`missing`) empty AFTER container whose
  BEFORE was a non-empty container of the same family reads as
  member-level emptying (key intact); a `missing` AFTER reads as a real
  key removal (struck-through removed ghost). Type-agnostic across set /
  map / vector / list.

  Pure. `before` / `after` are the slot's pre-/post-images (either may be
  `missing-sentinel`)."
  [before after]
  (let [a-kind (collection-kind after)
        b-kind (collection-kind before)]
    (boolean
      (and (not= after missing-sentinel)
           (not= before missing-sentinel)
           (container? a-kind)
           (container? b-kind)
           (= (container-family a-kind) (container-family b-kind))
           ;; AFTER empty, BEFORE populated. `empty?` / `seq` are lazy-
           ;; seq safe (no full realisation of a list).
           (empty? after)
           (seq before)))))

(defn children-of-pair
  "Diff-aware children walk — return a seq of `[child-key after-value
  before-value]` triples covering the UNION of `before` + `after` so
  removed children (present in BEFORE, absent from AFTER) are visible
  to the diff renderer.

  Slots that don't exist in their side carry `::missing` (the same
  `missing-sentinel` `diff-op` consumes), so the recursive renderer
  routes through `render-leaf-with-diff`'s `:added` / `:removed` paths
  unchanged.

  Per collection kind:
  - **Map / record** — AFTER's keys in their natural order, then
    BEFORE-only keys appended at the end. Appended-at-end was picked
    over interleaved-at-original-position because CLJS hash-maps don't
    carry stable order across boundaries anyway (array-map vs hash-
    map crossover, `dissoc` rehashing); appended-at-end is simple,
    predictable, and reads as 'the post-image, then a deletions
    section' in the rendered tree.
  - **Vector / list / seq** — index-align up to the longer side's
    count; trailing BEFORE-only positions render as `:removed`.
  - **Set** — UNION of members, sorted by `pr-str` for stable render
    (sets have no natural ordering).
  - **Map-entry** — fixed two positions `[0 k] [1 v]`, with AFTER /
    BEFORE per-side values.

  When the BEFORE side's collection kind does not match AFTER's (e.g.
  AFTER is a map, BEFORE is a vector), the function falls back to
  AFTER's children only — the parent row's `:modified` classification
  carries the structural-change signal and `← was <prior>`
  renders the BEFORE side via `render-scalar`.

  Returns `nil` when `kind` is not a container kind.

  Public so tests + downstream renderers can probe the union without
  re-deriving the walk."
  [before after kind]
  (case kind
    (:map :record)
    (let [a (when (map? after) after)
          b (when (map? before) before)]
      (cond
        ;; Both maps: union of keys, AFTER-order then BEFORE-only.
        (and a b)
        (let [a-keys (vec (keys a))
              a-key-set (set a-keys)
              extra-keys (remove a-key-set (keys b))]
          (concat
            (for [k a-keys]
              [k (get a k) (get b k ::missing)])
            (for [k extra-keys]
              [k ::missing (get b k)])))
        ;; Only AFTER is a map (BEFORE missing / different kind): all-added.
        a
        (for [[k v] a]
          [k v ::missing])
        ;; Only BEFORE is a map (shouldn't normally happen — render-node
        ;; routes value=::missing through render-leaf-with-diff). Defensive.
        b
        (for [[k v] b]
          [k ::missing v])
        :else nil))

    (:vector :list :seq)
    (let [a-vec (when (sequential? after)  (vec after))
          b-vec (when (sequential? before) (vec before))]
      (cond
        (and a-vec b-vec)
        (let [n (max (count a-vec) (count b-vec))]
          (for [i (range n)]
            [i
             (if (< i (count a-vec)) (nth a-vec i) ::missing)
             (if (< i (count b-vec)) (nth b-vec i) ::missing)]))
        a-vec
        (map-indexed (fn [i x] [i x ::missing]) a-vec)
        b-vec
        (map-indexed (fn [i x] [i ::missing x]) b-vec)
        :else nil))

    :set
    (let [a (when (set? after)  after)
          b (when (set? before) before)]
      (cond
        (and a b)
        (let [members (vec (sort-by pr-str (into a b)))]
          (for [x members]
            [x
             (if (contains? a x) x ::missing)
             (if (contains? b x) x ::missing)]))
        a (for [x a] [x x ::missing])
        b (for [x b] [x ::missing x])
        :else nil))

    :map-entry
    (let [a (when (map-entry?* after)  after)
          b (when (map-entry?* before) before)]
      (cond
        (and a b)
        (list [0 (first a) (first b)]
              [1 (second a) (second b)])
        a (list [0 (first a) ::missing] [1 (second a) ::missing])
        b (list [0 ::missing (first b)] [1 ::missing (second b)])
        :else nil))

    nil))

(def ^:private removed-key-tag
  "Path-segment tag wrapping a vector removal's before-index, so a struck-
  through removed element gets a React `:key` distinct from the surviving
  element now occupying that integer slot. The removal's op is forced
  `:removed` by its `::missing` after-value (`render-leaf-with-diff`'s
  structural override), so this synthetic segment is never
  consulted for a projection op lookup — it exists purely for key + testid
  uniqueness."
  :rf.xray.edn-inspector/removed)

(defn sequential-diff-children
  "Projection-aware children walk for a vector / list / seq in diff mode.
  Returns a seq of `[child-key after-value before-value]`
  triples — the SAME shape `children-of-pair` returns — but built by
  CONSUMING the engine's `:vector-removals` + `:same-shifted` projection
  rather than index-aligning the raw before/after vectors.

  Why not `children-of-pair`: a vector `:-` removal has no stable after-
  side path (survivors shift up). `children-of-pair` pairs before-index
  `i` with after-index `i` position-by-position, so for a scattered /
  mid-vector removal it strikes a SURVIVING-SHIFTED element (the one that
  slid up into the vacated slot) and never surfaces the genuinely-removed
  member. Contiguous TAIL removals happen to line up under index
  alignment, so only mid / scattered removals mis-render — this walk
  fixes all of them uniformly.

  Reconstruction (pure, projection-driven):

  - Surviving elements come from the AFTER vector at their AFTER index,
    so the per-element op resolves correctly off the projection (`:same`
    / `:same-shifted` / `:modified`). Their `before` slot carries the
    element's PRIOR value (recovered via the survivor's before-index) —
    NOT `::missing`, because `render-leaf-with-diff` treats a `::missing`
    before slot as a structural `:added`, which would override
    the projection and paint a surviving element green.
  - Purely-added elements come from the AFTER vector at their AFTER index
    with a `::missing` before slot (correctly forcing `:added`).
  - Removed elements come from `:vector-removals` — each carries its true
    `:before-index` + `:before-value`. They render struck-through with
    the after-value `::missing` (forcing `:removed`).
  - Order: the removed elements are spliced back into BEFORE-order
    position relative to the survivors, so the rendered list reads as the
    before-sequence with deletions struck IN PLACE; purely-added elements
    append after the survivors/removals in after-order.

  Falls back to `children-of-pair` when no `projection` is supplied (the
  test / REPL path that drives `render-container` without a top-level
  projection compute) — same answer for the no-removal case, graceful for
  the rest. Pure; `parent-path` is this vector's absolute path.

  Public so tests can probe the reconstruction without re-deriving it."
  [before after kind parent-path projection]
  (let [a-vec (when (sequential? after)  (vec after))
        b-vec (when (sequential? before) (vec before))]
    (cond
      ;; No projection (test/REPL path) or one side absent / non-sequential
      ;; — defer to the index-aligning union walk. With both sides present
      ;; AND a projection we take the removal-aware reconstruction below.
      (not (and projection a-vec b-vec))
      (children-of-pair before after kind)

      :else
      (let [parent-path (vec parent-path)
            removals    (vec (engine/vector-removals-at projection parent-path))
            removed-bis (into #{} (map :before-index) removals)
            ;; Survivors in after-order = after elements whose op is NOT
            ;; `:added`. Each maps, in order, to a surviving before-index
            ;; (ascending) — Editscript preserves survivor relative order.
            added?      (fn [ai] (= :added (engine/op-at projection
                                                         (conj parent-path ai))))
            after-idxs  (range (count a-vec))
            survivor-ais (vec (remove added? after-idxs))
            added-ais    (vec (filter added? after-idxs))
            survivor-bis (vec (remove removed-bis (range (count b-vec))))
            ;; before-index → surviving after-index (1:1 in order).
            bi->ai      (zipmap survivor-bis survivor-ais)
            removal-by-bi (zipmap (map :before-index removals) removals)
            ;; Walk BEFORE order: removed elements struck in place, survivors
            ;; rendered at their AFTER index (projection chrome resolves).
            ;; A survivor's `before` slot carries its PRIOR value (`nth
            ;; b-vec bi`) — never `::missing`, which `render-leaf-with-diff`
            ;; would read as a structural `:added`.
            before-order
            (mapcat
              (fn [bi]
                (if (contains? removed-bis bi)
                  (let [{:keys [before-value]} (removal-by-bi bi)]
                    [[[removed-key-tag bi] ::missing before-value]])
                  (when-let [ai (bi->ai bi)]
                    [[ai (nth a-vec ai) (nth b-vec bi)]])))
              (range (count b-vec)))
            ;; Purely-added elements append after the before-ordered run;
            ;; `::missing` before slot forces the `:added` render path.
            added-rows
            (map (fn [ai] [ai (nth a-vec ai) ::missing]) added-ais)]
        (concat before-order added-rows)))))

(defn- diff-pair-count
  "Cheap union-aware child count for diff mode. Returns the number of
  rows the union walk will emit so the header logic (`empty?` /
  `expanded?` gating) reflects both sides. Falls back to AFTER's
  `child-count` when the kinds don't line up. Pure."
  [before after kind]
  (case kind
    (:map :record)
    (let [a (when (map? after) after)
          b (when (map? before) before)]
      (cond
        (and a b)
        (let [a-keys (set (keys a))
              extra  (count (remove a-keys (keys b)))]
          (+ (count a) extra))
        a (count a)
        b (count b)
        :else 0))
    (:vector :list :seq)
    (let [a (when (sequential? after)  after)
          b (when (sequential? before) before)]
      (cond
        (and a b) (max (count (vec a)) (count (vec b)))
        a (count (vec a))
        b (count (vec b))
        :else 0))
    :set
    (let [a (when (set? after)  after)
          b (when (set? before) before)]
      (cond
        (and a b) (count (into a b))
        a (count a)
        b (count b)
        :else 0))
    :map-entry 2
    0))

(defn- child-count
  "Count children safely (don't realise lazy infinite seqs)."
  [v kind]
  (case kind
    :map        (count v)
    :record     (count v)
    :vector     (count v)
    :set        (count v)
    :map-entry  2
    (:list :seq) (try (count (take 1001 v)) (catch :default _ 0))
    0))

(defn- key-segment
  "Render a single map/vector key/index segment. Returns hiccup.
  Used to label children inside a container."
  [k]
  (cond
    (keyword? k) [:span {:style (token-style :syntax-keyword)} (str k)]
    (string? k)  [:span {:style (token-style :syntax-string)} (str-pad-quote k)]
    (number? k)  [:span {:style (token-style :syntax-number)} (str k)]
    (symbol? k)  [:span {:style (token-style :syntax-symbol)} (str k)]
    (nil? k)     [:span {:style (token-style :syntax-nil)} "nil"]
    :else        [:span {:style (token-style :text-primary)}
                  (try (pr-str k) (catch :default _ (str k)))]))

;; =========================================================================
;; width-aware expansion heuristic
;; =========================================================================
;;
;; The heuristic renders inline FIRST when the value's estimated inline
;; width fits the available column (with a small safety margin); it falls
;; back to tree-expand only when the inline form would overflow. This
;; keeps short values from rendering as multi-row trees that consume far
;; more vertical real-estate than their inline equivalents.
;;
;; `default-expanded-depth` is a CEILING — never auto-expand past depth N
;; even when the inline form overflows; show a collapsed-summary instead.
;; The operator's sticky override and diff-mode's
;; force-open-over-changed-descendant rule still win.
;;
;; Width is estimated by `(* char-count mono-char-width-px)` — JetBrains
;; Mono / Source Code Pro at the inspector's 12px size carries an
;; ~7.2px-wide M-advance; rounding up to 7px gives a slightly conservative
;; estimate (real strings render a hair narrower, so the inline gate is
;; slightly stricter than the actual fit — never the reverse, which would
;; cause horizontal overflow).
;;
;; A `safety-margin-px` of 16px guards against edge-case wrap (a few extra
;; pixels for the closing bracket, gutter, scroll-bar reserve). For
;; example, the 81-char `[:ws/connection [:rf.machine.timer/after-elapsed
;; 2501 [:active :authenticating]]]` value at ~570px estimate fits
;; trivially in a 966px column and renders inline at one row.

(def mono-char-width-px
  "Monospace M-advance in CSS pixels at the inspector's 12px font size.
  JetBrains Mono / Source Code Pro / DejaVu Sans Mono all sit in the
  7.0-7.3px range; 7px is the rounded-up conservative pick so the inline
  fit-gate slightly under-estimates the available room, never over-
  estimates. Public so tests can pin the math without re-deriving it."
  7)

(def safety-margin-px
  "Pixel headroom reserved against the measured `available-width` before
  the inline gate fires. Covers the closing bracket, key-column gutter
  for nested rows, optional scrollbar reserve. 16px is the pre-alpha
  pick — tuned by eye against running panels (rf2-kbdk8). Public for the
  same reason as `mono-char-width-px`."
  16)

(def default-ceiling-depth
  "Replacement default for the `:default-expanded-depth` opt under the
  width-aware heuristic (rf2-kbdk8). The opt is now a CEILING beyond
  which the widget never auto-expands even if the inline form overflows
  — it shows a collapsed `▸ {…N keys}` summary instead. The old default
  of 2 functioned as a TRIGGER (expand the first two levels regardless
  of fit); the new default 8 lets the width heuristic do its job at
  shallow depths while still protecting against pathological deep
  auto-expansion in rare cases.

  Public so tests can assert the new default without re-deriving it."
  8)

(defn estimated-inline-px
  "Estimate the inline-rendered width of `value` in CSS pixels. Pure
  function — `pr-str` length × `mono-char-width-px`. Returns 0 when
  `pr-str` throws (cyclic value, broken pr-method). Public so tests
  + future width-aware callers can probe the estimate without re-
  deriving the math."
  [value]
  (try
    (* mono-char-width-px (count (pr-str value)))
    (catch :default _ 0)))

(defn would-fit-inline?
  "True when the estimated inline width of `value` fits the
  `available-width-px` with the safety margin. When `available-width-
  px` is `nil` / non-positive (no measurement yet), returns `false` so
  the widget falls back to the legacy strict inline-fit gate.

  Pure function — no DOM, no rf reads. Public so tests can drive the
  decision deterministically."
  [value available-width-px]
  (boolean
    (and (number? available-width-px)
         (pos? available-width-px)
         (<= (+ (estimated-inline-px value) safety-margin-px)
             available-width-px))))

(defn default-expanded?
  "Pure depth/size/width heuristic — collections render inline when
  their estimated inline form fits the available column width; deeper
  / wider collections expand to tree form. The operator's sticky
  override wins via `resolve-expanded?`.

  In diff mode, a container with a changed descendant force-expands
  regardless of depth — operator never has to drill to find the
  change (spec/021 §10.4).

  rf2-kbdk8: `default-expanded-depth` is the EXPAND CEILING — never
  auto-expand past depth N (show a collapsed summary instead). When no
  available-width is yet measured the legacy depth-driven path runs as
  the fallback so unit tests + first-paint behaviour stay deterministic.

  rf2-fqcdd — DIFF posture: when a pre-image is present (`:diff?`) the
  depth/width heuristics are SUPPRESSED for unchanged subtrees.
  Only two reasons to auto-expand a container:

    1. depth = 0 (always show the root's keys)
    2. `:has-changed-descendant?` true (an ancestor of a change)

  Otherwise collapse — the operator sees only the changed slices
  plus the root, no surrounding context noise. Sticky override via
  `resolve-expanded?` still wins for ad-hoc inspection."
  [{:keys [depth child-count default-expanded-depth available-width-px value
           has-changed-descendant? diff?]
    :or   {default-expanded-depth default-ceiling-depth}}]
  (cond
    has-changed-descendant?
    true

    ;; rf2-fqcdd — DIFF: collapse unchanged subtrees regardless of
    ;; depth/width. Root (depth 0) still expands so the operator sees
    ;; the top-level keys; everything below collapses unless an
    ;; ancestor of a change.
    diff?
    (zero? depth)

    ;; Width-aware path — once a measurement exists, width drives the
    ;; decision. Containers that fit inline DON'T auto-expand (caller's
    ;; `inline-fit?` gate picks up the slack and renders the inline form);
    ;; containers that DON'T fit auto-expand up to the ceiling, then show
    ;; a collapsed summary beyond.
    (and (number? available-width-px) (pos? available-width-px))
    (cond
      (would-fit-inline? value available-width-px) false
      (<= depth (dec default-expanded-depth))      true
      :else                                        false)

    ;; Legacy depth-driven fallback for the no-measurement path. Kept
    ;; deterministic so unit tests that drive `render-node` without a
    ;; mount measurement reproduce the historical behaviour.
    (<= depth (dec default-expanded-depth))
    true

    (= depth default-expanded-depth)
    (<= (or child-count 0) 10)

    :else
    false))

(defn- testid-for
  "Compose a stable data-testid for a node — `[panel-id mount-id path]`."
  [panel-id mount-id path]
  (str "rf-xray-edn-inspector-"
       (name (or panel-id :anon))
       "-" mount-id
       (when (seq path) (str "-" (str/join "/" (map pr-str path))))))

;; rf2-tzvk9 — triangle expand/collapse glyph carries an explicit
;; ≥24×24 click target. The padding inside the existing key-column
;; gutter grows the hit-box without shifting the surrounding layout.
;; Public via the value so tests can assert the computed-width
;; contract without re-deriving the magic numbers.
;;
;; rf2-4aiaq — glyph size bumped 14px → 22px. The 14px glyph cleared
;; the 24px hit-box but read as a hairline against the inspector
;; chrome; Mike's live A/B (pair-debug 2026-05-26) found 22-24px
;; "much more clickable, feels right" vs. 14-18px which still felt
;; understated. 22px keeps the glyph visually balanced against the
;; surrounding 12px scalar rows (~1.83× scale) without dominating
;; the row.
;;
;; Why these numbers — `inline-flex` + the padding/font-size below
;; resolves to approximately 38×30px in Chromium at the shell's
;; default 13px root font-size:
;;
;;   width  ≈ font-size(22) * glyph-advance(~0.7) + padding-l(4) +
;;            padding-r(4)                                ≈ 23.4px
;;   height ≈ font-size(22) * line-height(1)               ≈ 22px
;;            + padding-t(4) + padding-b(4)                ≈ 30px
;;
;; `min-width 24px` pins the floor in both axes even when the glyph's
;; intrinsic width (`▸` is narrower than `▾`) doesn't reach 24px on
;; its own. Both axes clear the 24px WCAG-2.2-flavour comfortable-
;; mouse-target threshold.

(def triangle-min-target-px
  "Minimum click-target width/height the triangle must register
  (rf2-tzvk9). The CLJS-unit-test surface asserts the padding +
  font-size combination resolves to at least this many CSS pixels
  along both axes.

  Public so external surfaces (regression tests, design audits) can
  read the contract without re-deriving the number."
  24)

(def triangle-style
  "Inline-style map applied to every expand/collapse triangle (`▸`
  / `▾`) the edn-inspector widget renders (rf2-tzvk9). One source of
  truth so every triangle gets the SAME hit-box — three call sites
  (depth-capped, expanded ▾, collapsed ▸) and one diff-mode variant
  share this.

  Layout rationale:

  - `inline-flex` + `align-items center` + `justify-content center`
    centres the glyph inside the padded box so the click target is
    visually balanced.
  - `min-width` + `min-height` pin the hit-box to ≥24px in both axes
    even if the glyph's natural metrics fall short (Chromium renders
    `▸` slightly narrower than `▾`).
  - No `padding` — the `min-width`/`min-height` 24px + inline-flex
    centring deliver the ≥24×24 hit-box without padding overhead.
    Mike's live call (pair-debug 2026-05-26): the prior 4px 8px
    padding read as wasted space around the glyph; the min-* sizing
    alone keeps the click target large while the visual footprint
    matches the glyph itself.
  - `font-size 22px` (rf2-4aiaq) overrides the widget's inherited
    12px so the glyph reads as the primary expand/collapse affordance
    — Mike's live A/B (pair-debug 2026-05-26) found the prior 14px
    glyph read as hairline against the inspector chrome; 22px lands
    in the operator-preferred 22-24px band where the triangle 'feels
    clickable'.
  - `line-height 1` collapses inline leading so the height comes
    purely from font + min-height, not from inherited 1.4 leading.

  The colour stays on the subdued `:text-secondary` token — the
  glyph's job is to be *findable*, not loud."
  {:cursor          "pointer"
   :user-select     "none"
   :display         "inline-flex"
   :align-items     "center"
   :justify-content "center"
   :min-width       (str triangle-min-target-px "px")
   :min-height      (str triangle-min-target-px "px")
   :font-size       "22px"
   :line-height     1
   :color           (:text-secondary tokens)})

;; ---- recurring style maps lifted to module-level defs --------------------
;;
;; The body wrappers + grid cells render once per expanded container — at
;; depth N with M children that's N*M map allocations per render.
;; Hoisting the static map out of the call site eliminates the per-call
;; rebuild and gives Reagent stable identity for the style attr (lets
;; React skip the inline-style diff when the value hasn't changed).
;; `tokens` reads resolve to CSS-variable strings (theme-aware at paint
;; time), so the captured value stays valid across theme switches.
;;
;; Hoisting neighbours in this file (search anchors):
;;   - `change-annotation-style`        — inline `← was <prior>` chip
;;   - `gutter-row-outer-base-style`    — rf2-7cddi diff-row outer skeleton
;;   - `gutter-glyph-base-style`        — rf2-7cddi diff-row glyph skeleton
;;   - `gutter-body-style`              — rf2-7cddi diff-row body
;;   - `triangle-style`                 — ▸/▾ click-target (rf2-tzvk9)
;;   - `body-grid-style` / `body-block-style` / `key-cell-style` / `value-cell-style`
;;   - `r3-chip-style`                  — R3 [N∆] collapsed-change alert chip

(def ^:private body-grid-style
  "Style for an expanded map / record / map-entry body — children laid
  out on a 2-column grid (key | value) so values column-align across
  rows of the same map."
  {:margin-left          "11px"
   :padding-left         "6px"
   :border-left          (str "1px solid " (:border-subtle tokens))
   :display              "grid"
   :grid-template-columns "max-content 1fr"
   :column-gap           "8px"
   :row-gap              "0"
   :align-items          "baseline"})

(def ^:private body-block-style
  "Style for an expanded sequential body (vector / list / set / seq) —
  block flow, no key column. Shared with the protocol-render body so
  the indent + guide line are consistent across both paths."
  {:margin-left  "11px"
   :padding-left "6px"
   :border-left  (str "1px solid " (:border-subtle tokens))})

(def ^:private key-cell-style
  "Style for a key cell inside `body-grid-style`. `nowrap` prevents a
  long namespaced key from wrapping inside the key column."
  {:white-space "nowrap"})

(def ^:private value-cell-style
  "Style for a value cell inside `body-grid-style`. `min-width 0` lets
  the `1fr` track honour wider intrinsic-content values without
  pushing past the grid's allotment."
  {:min-width 0})

(def ^:private r3-chip-style
  "R3 `[N∆]` collapsed-change chip — constant solid-orange block with
  white text. Per Mike pair-debug 2026-05-27: the chip is the
  operator's alert that the collapsed subtree contains change; it
  needs to read as alert-prominent, not as subtle per-op chrome. The
  former per-op colour (amber wash for mixed / green for all-added /
  red for all-removed) was clever but under-prominent — operators
  scanning collapsed nodes for hidden changes need the binary signal
  'is there change here?' first; the kind-of-change is secondary
  detail discoverable on expand.

  Position: rendered IMMEDIATELY after the triangle (leading edge),
  not after the collapsed summary, so the operator's eye catches
  alert + key together rather than discovering the alert after
  reading past the summary."
  {:padding       "1px 6px"
   :border-radius "8px"
   :font-family   sans-stack
   :font-size     "10px"
   :font-weight   700
   :line-height   1.2
   :background    (:diff-modified-stripe tokens)
   :color         (:white tokens)})

(defn- on-toggle
  "Build the click handler for a node's `▸`/`▾` glyph.
  Dispatches the toggle event via `dispatch-fn` — the lexically-
  injected frame-aware dispatcher that `reg-view` binds inside the
  widget's render body. The dispatcher inherits the surrounding
  frame from React context (rf2-y59tb), so the event lands on the
  same frame the widget is mounted under (`:rf/xray` in the App-DB
  panel, `:rf/default` in a standalone playground).

  The payload carries `rendered-expanded?` — the current visible
  state of this node — so the reducer can invert from what the user
  sees on the first click (rf2-y59tb Bug B). Without it, default-
  expanded paths would silently no-op on the first click."
  [dispatch-fn panel-id mount-id path rendered-expanded?]
  (fn [^js e]
    (when e
      (.preventDefault e)
      (.stopPropagation e))
    (dispatch-fn [:rf.xray.edn-inspector/toggle-node
                  panel-id mount-id path rendered-expanded?])))

(defn- swallow-dblclick
  "`:on-double-click` for the `▸`/`▾` toggle glyph (rf2-6nw3g). The
  triangle's `:on-click` already toggles + `stopPropagation`s each
  click, but a double-click on the glyph still emits a `dblclick` that
  would bubble to the enclosing zoomable container's `:on-double-click`
  zoom (`zoom-trigger-attrs`). Swallowing it here lets the triangle own
  its own gesture — zoom only fires on a double-click OUTSIDE the
  triangle — and `preventDefault` suppresses the native text-selection."
  [^js e]
  (when e
    (.preventDefault e)
    (.stopPropagation e)))

(defn- collapsed-summary
  "Right-of-triangle summary for a collapsed collection. Shows an
  inline preview if any first elements fit; falls back to the
  `{…N keys}` shape."
  [v kind]
  (let [preview (inline-preview-string v 3 60)]
    [:span {:style       {:color (get tokens (:tone-key (delim kind)))}
            :data-rf-preview "1"}
     preview]))

;; =========================================================================
;; recursive inline rendering — width-aware path (rf2-kbdk8)
;; =========================================================================
;;
;; When the value's estimated inline width fits the available column
;; (computed by `would-fit-inline?`), the widget renders the WHOLE thing
;; inline as a one-line hiccup span — including nested containers. The
;; legacy strict inline-fit gate (≤3 children + all-scalars) is kept as a
;; pre-measurement fallback so unit tests without a width measurement
;; reproduce the historical behaviour; once a measurement exists the
;; recursive renderer takes over and handles the deep-but-skinny case.
;;
;; Per-token syntax colour is preserved through the recursion — scalars
;; route through `render-scalar`; brackets pick up their kind's tone-key;
;; the comma separator stays on `:text-tertiary` so structure punctuation
;; reads as secondary chrome. No expansion state, no toggle — once the
;; whole tree fits inline the entire value is already visible; the
;; operator's `▸` / `▾` interaction lives on the surrounding container
;; widget (top-level mount), not on every nested child.

(declare render-inline-recursive)

;; rf2-zl4rs — forward declaration for the per-node zoom-trigger
;; attribute factory. Zoom-in is now a node-local gesture (double-click
;; / Enter) on the container itself rather than a separate `⊙` glyph
;; button; the factory composes the `:on-double-click` + `:on-key-down`
;; + a11y attrs that render-container merges onto each zoomable
;; container's outer div. Defined below alongside the breadcrumb
;; component.
(declare zoom-trigger-attrs)

(defn- render-inline-pair
  "Render a single map / record entry `[k v]` as a key+value sequence
  for the inline render path. Returns a seq of hiccup fragments (no
  enclosing span — caller weaves separators)."
  [k v]
  [(key-segment k)
   [:span {:style (token-style :text-tertiary)} " "]
   (render-inline-recursive v)])

(defn render-inline-recursive
  "Render `value` as a single-line inline hiccup `[:span ...]`. Handles
  nested containers by recursing — brackets, separators, and scalars
  all paint with their full syntax-palette colour.

  Pure function — no expansion state, no rf reads, no toggle. Safe to
  call recursively to any depth (the width gate in
  `render-container` ensures only width-fitting values reach this
  path, so the recursion is naturally bounded by the operator's
  measured column).

  Public for unit tests."
  [value]
  (let [kind (collection-kind value)]
    (cond
      (not (container? kind))
      (render-scalar value)

      :else
      (let [{:keys [open close tone-key]} (delim kind)
            open-bracket
            (cond->> open
              (= kind :record) (str (record-tag value)))
            bracket-span
            (fn [text]
              [:span {:style {:color (get tokens tone-key)}
                      :data-rf-bracket "1"}
               text])
            ;; rf2-7hqwe — inter-element separator follows canonical EDN
            ;; spacing (`, ` between map/record entries, a single space
            ;; between sequentials), via the shared `inline-separator-span`.
            sep       (inline-separator-span kind)
            labelled? (#{:map :record :map-entry} kind)
            pairs     (children-of value)
            item-children
            (apply concat
                   (map-indexed
                     (fn [i [k cv]]
                       (let [prefix (when (pos? i) [sep])
                             body   (if labelled?
                                      (render-inline-pair k cv)
                                      [(render-inline-recursive cv)])]
                         (concat prefix body)))
                     pairs))]
        (into [:span {:data-rf-inline    "1"
                      :data-rf-kind      (name kind)
                      :style {:white-space "nowrap"}}
               (bracket-span open-bracket)]
              (concat item-children [(bracket-span close)]))))))

(defn- classify-container-op
  "Classify this container's diff op (`:same` / `:added` / `:removed` /
  `:children`) for `render-container`.

  Extracted verbatim from `render-container`'s body for clarity
  (rf2-nk7w0) — the answer is a pure function of
  `(diff? before value kind path projection removed-ancestor?)`.

  Diff: classify this container's op via the rf2-n2jig
  Editscript-backed projection map. The projection is computed once at
  the top of `render-edn-inspector` and threaded down via `opts`; the
  per-path lookup is constant-time. When the projection is absent
  (non-diff mode), every path reads `:same` and the gutter rows
  collapse to a transparent left border.

  rf2-n2jig — tests that drive `render-node` directly without going
  through `render-edn-inspector` skip the top-level projection compute.
  We fall back to a lightweight local op classifier in that case so the
  ancestor-chain force-open + chrome wiring still functions (the engine
  is the canonical classifier, but a 6-line `(cond ...)` fallback is no
  shim — it's the same answer for the (before, value) pair, with the
  engine's superset of `:same-shifted` / R7 / R8 nuances unreachable
  from this call path)."
  [{:keys [diff? before value kind path projection removed-ancestor?]}]
  (cond
    ;; rf2-8pfkk — a removed-container ghost (and every container inside
    ;; it) is unconditionally `:removed`. This MUST precede the
    ;; projection lookup: the engine anchors a `dissoc`-to-`{}` on the
    ;; surviving parent and classifies the ghost's own path `:children`,
    ;; which would otherwise paint the deletion as a benign
    ;; descendant-change rail.
    removed-ancestor? :removed

    ;; rf2-0c6a3 — a collection emptied by member removal (`#{:a}→#{}`,
    ;; `{:k :v}→{}`, `[x]→[]`, `(x)→()`) keeps its KEY INTACT: the value
    ;; is now the empty collection with the dropped member(s) struck
    ;; INSIDE it. The engine's R5 `mark-wholly-changed` legitimately
    ;; promotes the emptied set / map container path to `:removed` (the
    ;; opposite side is empty — no surviving member to anchor a
    ;; member-level diff at the container path). Trusting that `:removed`
    ;; here would render the node like a `dissoc`-removed key (struck
    ;; whole, `−` glyph). We classify it `:children` instead so the node
    ;; stays key-intact + change-bearing (auto-expands, rail in the
    ;; modified hue) and the `children-of-pair` /
    ;; `sequential-diff-children` union walk surfaces the struck-through
    ;; removed member(s). Must precede the projection lookup; a real
    ;; ghost (handled above by `removed-ancestor?`) is unaffected because
    ;; its AFTER value is `missing-sentinel`, not an empty collection.
    (and diff? (diff-emptied? before value))
    :children

    (and diff? projection)
    (let [proj-op (engine/op-at projection path)]
      ;; rf2-8pfkk — STRUCTURAL difference wins over a `:same`
      ;; projection. A pure vector / list tail deletion routes through
      ;; the engine's off-path `:vector-removals` channel (it owns no
      ;; stable after-side path), so `op-at` reports `:same` for the
      ;; parent even though `before` ≠ `value`. Trusting that `:same`
      ;; collapsed the container and the dropped tail vanished. When the
      ;; two sides genuinely differ we promote to `:children` so the
      ;; container expands and the `children-of-pair` union walk
      ;; surfaces the struck-through removed indices.
      (if (and (= :same proj-op)
               (not= before missing-sentinel)
               (not= value missing-sentinel)
               (not= before value))
        :children
        proj-op))

    (and diff? (or (= before missing-sentinel)
                   (= value missing-sentinel)))
    (cond
      (= before missing-sentinel) :added
      :else                       :removed)

    (and diff? (not= before value))
    :children

    :else :same))

(defn- render-container-header
  "Render the header row of a container (the line bearing the toggle
  triangle + brackets / summary), exclusive of the removed-ghost
  wrapper.

  Extracted from `render-container` for clarity (rf2-nk7w0) — this is
  the `cond` over the five header variants (empty / depth-capped /
  inline-fit / expanded / collapsed-summary). Output is byte-identical
  to the inlined form; all state is threaded in."
  [{:keys [empty? depth-capped? inline-fit? expanded? width-fits?
           value kind panel-id mount-id path toggle-fn max-inline-width
           diff? projection op]}]
  (cond
    ;; Empty collection — show bracket pair flat, no toggle.
    empty?
    [:span {:style {:display "inline-flex" :align-items "baseline"}}
     (bracket kind :open value)
     (bracket kind :close value)]

    ;; Depth-capped — render the placeholder ellipsis, click expands one level.
    depth-capped?
    (cond-> [:span {:style {:display "inline-flex" :align-items "center" :gap "4px"}}
             [:span {:on-click   toggle-fn
                     :on-double-click swallow-dblclick
                     :role       "button"
                     :tabIndex   0
                     :aria-expanded false
                     :data-testid (str (testid-for panel-id mount-id path) "-toggle")
                     ;; rf2-tzvk9 — ≥24×24 click target via the shared
                     ;; `triangle-style` (padding + font-size + min-width/
                     ;; -height).
                     :style triangle-style}
              "▸"]]
      true        (conj (bracket kind :open value))
      true        (conj [:span {:style (token-style :text-tertiary)} "…"])
      true        (conj (bracket kind :close value)))

    ;; Small enough to render inline — open bracket + items + close
    ;; bracket on one row, NO toggle (already exposed). Maps /
    ;; records render `key value` pairs; sequentials render values
    ;; only; sets render values only (no labelled key).
    ;;
    ;; rf2-kbdk8 — when `width-fits?` fires (a measurement is in play
    ;; and the FULL pr-str fits the available column), defer to
    ;; `render-inline-recursive` which handles nested containers in
    ;; the same inline span. The legacy strict path (scalar-only
    ;; children) still feeds the pre-measurement fallback.
    inline-fit?
    (let [inline-render
          (if width-fits?
            (render-inline-recursive value)
            (let [labelled? (#{:map :record :map-entry} kind)
                  pairs     (children-of value)]
              (into [:span {:style {:display "inline-flex"
                                    :align-items "baseline"
                                    :flex-wrap "wrap"
                                    :gap "4px"}}
                     (bracket kind :open value)]
                    (concat
                      (apply concat
                             (map-indexed
                               (fn [i [k cv]]
                                 (let [;; rf2-7hqwe — EDN-correct separator
                                       ;; via the shared `inline-separator-span`
                                       ;; (`, ` map/record · single space seq).
                                       sep (when (pos? i)
                                             (inline-separator-span kind))
                                       ks  (when labelled? (key-segment k))
                                       sp  (when labelled?
                                             [:span {:style (token-style :text-tertiary)} " "])]
                                   (cond-> []
                                     sep (conj sep)
                                     ks  (conj ks)
                                     sp  (conj sp)
                                     true (conj (render-scalar cv)))))
                               pairs))
                      [(bracket kind :close value)]))))]
      ;; rf2-zl4rs — the inline-fit row has no separate zoom glyph;
      ;; the zoom gesture lives on the container's outer div (handlers
      ;; merged above), so the inline render passes through unwrapped.
      inline-render)

    ;; Default — toggle glyph + open bracket (when expanded) OR summary.
    expanded?
    (cond-> [:span {:style {:display "inline-flex" :align-items "center" :gap "4px"}}
             [:span {:on-click   toggle-fn
                     :on-double-click swallow-dblclick
                     :role       "button"
                     :tabIndex   0
                     :aria-expanded true
                     :data-testid (str (testid-for panel-id mount-id path) "-toggle")
                     ;; rf2-tzvk9 — ≥24×24 click target via the shared
                     ;; `triangle-style`.
                     :style triangle-style}
              "▾"]]
      true        (conj (bracket kind :open value)))

    :else
    (let [;; R3-revised (rf2-n2jig + Mike pair-debug 2026-05-27):
          ;; collapsed containers carrying ANY descendant change
          ;; show a `[N∆]` alert chip IMMEDIATELY AFTER THE TRIANGLE
          ;; — leading-edge position, solid orange + white text,
          ;; constant regardless of op. The triangle itself stays
          ;; default `:text-tertiary` (no colour swap) so the click
          ;; affordance is unmuddied. See `r3-chip-style` docstring
          ;; for the alert-vs-per-op-colour rationale.
          n-changes (when (and diff? projection)
                      (engine/change-count-at projection path))
          show-chip? (and (pos? (or n-changes 0))
                          (not (#{:added :removed} op)))]
      (cond-> [:span {:style {:display "inline-flex" :align-items "center" :gap "6px"}}
               [:span {:on-click   toggle-fn
                       :on-double-click swallow-dblclick
                       :role       "button"
                       :tabIndex   0
                       :aria-expanded false
                       :data-testid (str (testid-for panel-id mount-id path) "-toggle")
                       ;; rf2-tzvk9 — ≥24×24 click target via the shared
                       ;; `triangle-style`.
                       :style triangle-style}
                "▸"]]
        ;; Chip BEFORE summary so the alert reads at the leading
        ;; edge (Mike pair-debug 2026-05-27). Was previously
        ;; appended after `(collapsed-summary ...)`.
        show-chip?
        (conj [:span {:data-rf-diff-chip "1"
                      :data-rf-diff-chip-count (str n-changes)
                      :style r3-chip-style}
               (str n-changes "∆")])
        true        (conj (collapsed-summary value kind))))))

(defn- render-grid-child-row
  "Render one row of a labelled-key container body (map / record /
  map-entry) as a `[key-cell value-cell]` pair of grid children.

  Extracted from `render-container` for clarity (rf2-nk7w0). Output is
  byte-identical to the inlined `(fn [[k cv cb]] …)`; the parent's
  threaded state arrives via the opts map."
  [{:keys [k cv cb path depth diff? projection removed-ancestor?
           panel-id mount-id expansion-map dispatch-fn zoomable?
           zoom-path-prefix opts]}]
  (let [child-path (conj (vec path) k)
        ;; R2 — when the KEY itself is new/removed
        ;; (the parent map sees this k for the first
        ;; time or this k is gone) paint a `+` / `−`
        ;; glyph in column 1 of the KEY ROW. Distinct
        ;; from "an existing key whose value changed"
        ;; which paints on the value cell. Pulled off
        ;; the projection's op at the child path.
        ;; rf2-8pfkk — inside a removed ghost every
        ;; key is `:removed` (the projection's op at
        ;; the child path is not authoritative here —
        ;; see the `op` override above). Force the
        ;; removed key chrome + slot-anchored wash so
        ;; the whole row strikes through.
        ;; rf2-0c6a3 — an emptied-collection slot
        ;; (`:k #{:a}` → `:k #{}`) keeps its KEY INTACT:
        ;; the engine's R5 promotion classifies the
        ;; emptied set / map child path `:removed`, but
        ;; the slot SURVIVES (the after value is a
        ;; present empty collection, not `missing`). A
        ;; real `dissoc` has `cv` = `missing-sentinel`.
        ;; So we read the SLOT shape: an emptied slot is
        ;; `:children` (key intact, value changed; no `−`
        ;; glyph, no key strike — the dropped member is
        ;; struck INSIDE the value cell), distinct from a
        ;; removed key. Type-agnostic across set / map /
        ;; vector / list.
        child-op (cond
                   removed-ancestor?         :removed
                   (diff-emptied? cb cv)     :children
                   (and diff? projection)
                   (engine/op-at projection child-path)
                   :else                     nil)
        key-side-glyph (case child-op
                         :added   "+"
                         :removed "−"
                         nil)
        ;; rf2-zpeyv — slot-vs-value anchoring. When
        ;; the SLOT itself changes (key added /
        ;; removed) the chrome paints the WHOLE row
        ;; (key cell + value cell) in the per-op
        ;; wash, and `:removed` strike-through reaches
        ;; the key text. When only the VALUE inside
        ;; an existing slot changed (R1/R7/R8), the
        ;; chrome stays value-anchored (no key-cell
        ;; wash, no key strike).
        slot-anchored? (boolean (#{:added :removed} child-op))
        slot-wash (when slot-anchored?
                    (op-wash-bg child-op))
        value-node (render-node
                     {:value cv
                      :before cb
                      :diff? diff?
                      :projection projection
                      :panel-id panel-id
                      :mount-id mount-id
                      :path child-path
                      :depth (inc depth)
                      :expansion-map expansion-map
                      :dispatch-fn dispatch-fn
                      :zoomable? zoomable?
                      :zoom-path-prefix zoom-path-prefix
                      :opts opts
                      ;; Suppress the leaf's inner
                      ;; gutter-row wash — the slot
                      ;; row paints it on the value
                      ;; cell here. The gutter glyph
                      ;; + per-token text colour on
                      ;; the value still render.
                      :slot-anchored? slot-anchored?
                      ;; rf2-8pfkk — propagate the
                      ;; ghost so nested containers /
                      ;; leaves keep the `:removed` op.
                      :removed-ancestor? removed-ancestor?})]
    [(with-meta
       ;; Key cell — uses `div` so the grid baseline
       ;; aligns predictably across rows. `white-
       ;; space: nowrap` prevents long keys (e.g. a
       ;; deeply-namespaced `:rf.x.with.many.parts/k`)
       ;; from wrapping inside the key column.
       ;;
       ;; rf2-zpeyv — when slot-anchored, paint the
       ;; per-op wash across the KEY cell (whole-row
       ;; treatment) and strike the key text for
       ;; `:removed`. The wash on the sibling value
       ;; cell completes the row.
       [:div (cond-> {:data-rf-cell "key"
                      :style (cond-> key-cell-style
                               slot-wash
                               (assoc :background slot-wash)
                               (= child-op :removed)
                               (assoc :text-decoration "line-through"))}
               key-side-glyph
               (assoc :data-rf-key-glyph key-side-glyph)
               slot-anchored?
               (assoc :data-rf-row-anchor "slot"
                      :data-rf-row-wash "1"))
        ;; R2 key-side glyph — paint `+` / `−` in
        ;; column 1 of the key row when the KEY itself
        ;; is new/removed. The key text picks up the
        ;; per-op decoration (`:removed` gets strike-
        ;; through; `:added` paints the key bright).
        (when key-side-glyph
          [:span {:data-rf-key-glyph "1"
                  :style {:color       (:diff-gutter tokens)
                          :font-weight 700
                          :margin-right "4px"
                          :user-select  "none"}}
           key-side-glyph])
        (cond-> (key-segment k)
          (= child-op :removed)
          (->>
            (conj [:span {:style {:text-decoration "line-through"}}])))]
       {:key (str "k-" (pr-str k))})
     (with-meta
       ;; Value cell. rf2-zpeyv — when slot-anchored,
       ;; paint the per-op wash across the whole value
       ;; cell so it joins the key cell's wash into a
       ;; single banded row. The leaf's inner gutter-
       ;; row wash is suppressed via `:slot-anchored?`
       ;; on render-node (above) — no double-paint.
       [:div (cond-> {:data-rf-cell "value"
                      :style (cond-> value-cell-style
                               slot-wash
                               (assoc :background slot-wash))}
               slot-anchored?
               (assoc :data-rf-row-anchor "slot"
                      :data-rf-row-wash "1"))
        value-node]
       {:key (str "v-" (pr-str k))})]))

(defn- render-block-child
  "Render one child of a sequential container body (vector / list / set
  / seq) as a single bare `render-node` (no key column).

  Extracted from `render-container` for clarity (rf2-nk7w0). Output is
  byte-identical to the inlined `(fn [[k cv cb]] …)`."
  [{:keys [k cv cb path depth diff? projection removed-ancestor?
           panel-id mount-id expansion-map dispatch-fn zoomable?
           zoom-path-prefix opts]}]
  (let [child-path (conj (vec path) k)]
    (with-meta
      (render-node {:value cv
                    :before cb
                    :diff? diff?
                    :projection projection
                    :panel-id panel-id
                    :mount-id mount-id
                    :path child-path
                    :depth (inc depth)
                    :expansion-map expansion-map
                    :dispatch-fn dispatch-fn
                    :zoomable? zoomable?
                    :zoom-path-prefix zoom-path-prefix
                    :opts opts
                    ;; rf2-8pfkk — vector / set / list
                    ;; ghost members keep the `:removed`
                    ;; op down the subtree.
                    :removed-ancestor? removed-ancestor?})
      {:key (str "v-" (pr-str k))})))

(defn- render-container
  "Render a map / vector / list / set / record / map-entry container.

  Returns a hiccup node. Threads:
   - panel-id / mount-id — for testid + dispatch
   - path — vector of segments from root
   - depth — for the default-expand heuristic
   - expansion-map — snapshot from the expansion-slot subscription
   - opts — `:default-expanded-depth`, `:max-depth`, `:max-inline-width`
   - dispatch-fn — frame-aware dispatcher captured by the surrounding
                   `reg-view` body (rf2-y59tb); falls back to `rf/dispatch`
                   when called outside a registered view (test/REPL).
   - diff? / before — when diff? true the renderer paints gutter rows,
                      annotates changed leaves, and force-expands the
                      ancestor chain over any changed descendant.
   - zoomable? / zoom-path-prefix — when zoomable? true (rf2-h71e0,
                                    gesture reworked rf2-zl4rs), every
                                    non-root container becomes a zoom-in
                                    target: double-click (or Enter while
                                    focused) re-roots the inspector onto
                                    that node. There is no separate
                                    glyph button — the container's own
                                    outer div carries the gesture
                                    handlers + a11y. zoom-path-prefix is
                                    the absolute path of the CURRENT zoom
                                    root within the original value; the
                                    gesture dispatches with `(into
                                    zoom-path-prefix path)` so the
                                    zoom-slot stores the full path."
  [{:keys [value kind panel-id mount-id path depth expansion-map opts
           dispatch-fn diff? before zoomable? zoom-path-prefix projection
           removed-ancestor?]}]
  (let [{:keys [default-expanded-depth max-depth max-inline-width
                available-width-px]
         :or {default-expanded-depth default-ceiling-depth
              max-depth 16
              max-inline-width 60}} opts
        ;; rf2-zuh1e — in diff mode `cnt` reflects the UNION of BEFORE +
        ;; AFTER so an AFTER-side `{}` with a BEFORE side carrying keys
        ;; still expands + renders the removed rows. Outside diff mode
        ;; the original AFTER-only count drives the header.
        cnt           (if diff?
                        (diff-pair-count before value kind)
                        (child-count value kind))
        empty?        (zero? cnt)
        depth-capped? (>= depth max-depth)
        ;; rf2-nk7w0 — op classification extracted to
        ;; `classify-container-op` (the rf2-n2jig projection lookup +
        ;; the rf2-8pfkk / rf2-0c6a3 structural overrides). Same answer
        ;; for the (before, value, projection) tuple.
        op            (classify-container-op
                        {:diff?             diff?
                         :before            before
                         :value             value
                         :kind              kind
                         :path              path
                         :projection        projection
                         :removed-ancestor? removed-ancestor?})
        has-change?   (and diff? (not (#{:same :same-shifted} op)))
        ;; rf2-6q2tz — the R5 wholly-changed-ancestor lookup +
        ;; `inside-wholly?` derivation live in `render-leaf-with-diff`
        ;; (where R5 chrome-opts are actually gated on them). The
        ;; duplicate container-level bindings were dead code and have
        ;; been removed; the container has no R5-specific behaviour.
        ;; rf2-8pfkk — a removed-container ghost defaults to COLLAPSED:
        ;; the deletion reads as a single struck-through summary line
        ;; (`:shapes {…} (N keys)`), expandable on demand to walk the
        ;; ghost. We therefore do NOT let its `:removed` op drive the
        ;; force-open `has-changed-descendant?` rule — the change IS the
        ;; node itself, not a buried descendant, so the collapsed
        ;; summary already carries the full signal. The diff-mode
        ;; collapse rule (`(zero? depth)`) then keeps every nested ghost
        ;; level collapsed until the operator drills in.
        default?      (and (not depth-capped?)
                           (default-expanded?
                             {:depth                   depth
                              :child-count             cnt
                              :default-expanded-depth  default-expanded-depth
                              :has-changed-descendant? (and has-change?
                                                            (not removed-ancestor?))
                              :diff?                   diff?
                              :available-width-px      available-width-px
                              :value                   value}))
        expanded?     (and (not empty?)
                           (not depth-capped?)
                           (resolve-expanded? expansion-map panel-id mount-id path default?))
        ;; rf2-kbdk8 — width-aware inline-fit. When a measurement exists
        ;; and the whole value's pr-str fits the available column, render
        ;; the FULL tree inline (recursively). The legacy strict gate
        ;; (≤3 children + all-scalars) remains as the pre-measurement
        ;; fallback so unit tests + first-paint behaviour stay
        ;; deterministic.
        width-fits?   (and (not empty?)
                           (not has-change?)
                           (not depth-capped?)
                           (would-fit-inline? value available-width-px))
        legacy-inline? (and (not empty?)
                            (not has-change?)
                            (<= cnt 3)
                            (every? (fn [[_ cv]]
                                      (not (container? (collection-kind cv))))
                                    (children-of value))
                            (<= (count (inline-preview-string value 5 max-inline-width))
                                max-inline-width))
        ;; Sticky-override-aware: if the operator EXPLICITLY toggled this
        ;; node open (`:expanded? true`), respect that — the inline gate
        ;; only fires when the operator hasn't overridden.
        operator-expanded?
        (let [override (get expansion-map (expansion-key panel-id mount-id path))]
          (and (contains? override :expanded?) (boolean (:expanded? override))))
        inline-fit?   (and (not operator-expanded?)
                           (or width-fits? legacy-inline?))
        ;; `dispatch-fn` is supplied by the reg-view'd outer body so
        ;; the toggle dispatch carries the surrounding frame. Tests
        ;; that drive render-node directly without mounting fall back
        ;; to the global dispatcher's fn form (`rf/dispatch` is a
        ;; macro — use `rf/dispatch*` for HoF callers).
        dispatch-fn   (or dispatch-fn rf/dispatch*)
        ;; rendered-expanded? is the visible state at this node —
        ;; the depth-capped placeholder is always shown collapsed
        ;; (▸), and we use the computed `expanded?` for the rest.
        ;; Threading it into `on-toggle` lets the reducer invert from
        ;; the visible state on the first click.
        toggle-fn     (on-toggle dispatch-fn panel-id mount-id path
                                 (boolean (and expanded? (not depth-capped?))))
        ;; rf2-zuh1e — in diff mode the body walks the UNION of BEFORE +
        ;; AFTER children. Plain browse keeps the original AFTER-only
        ;; `children-of` walk. The pair-list shape differs (`[k v]` for
        ;; browse vs `[k v b]` for diff) so the downstream child-pair
        ;; construction below normalises into a uniform `[k v b]` triple
        ;; for the recursive render call.
        ;;
        ;; rf2-vu42n — vectors / lists / seqs consume the engine's off-path
        ;; `:vector-removals` + `:same-shifted` projection via
        ;; `sequential-diff-children` rather than index-aligning the raw
        ;; before/after vectors. Index alignment (the old `children-of-pair`
        ;; vector branch) mis-attributed the strike to a surviving-shifted
        ;; element and dropped the genuinely-removed one for scattered /
        ;; mid-vector removals; the projection-driven walk strikes the
        ;; actually-removed members in before-order, in place. Maps / sets /
        ;; records / map-entries keep the `children-of-pair` union walk
        ;; (their slots are key/member-addressed — no positional shift).
        children      (when (and (not empty?) (not depth-capped?) expanded? (not inline-fit?))
                        (cond
                          (and diff? (#{:vector :list :seq} kind))
                          (sequential-diff-children before value kind path projection)
                          diff?
                          (children-of-pair before value kind)
                          :else
                          (children-of value)))
        ;; rf2-zl4rs — zoom-in is a node-local gesture (double-click /
        ;; Enter) on every non-empty container at a NON-ROOT relative
        ;; path. The root (`[]`) skips the gesture because zooming into
        ;; the current zoom root is a no-op; empty containers have no
        ;; meaningful subtree to focus. The handlers dispatch with the
        ;; ABSOLUTE path = `(into zoom-path-prefix path)` so the zoom-slot
        ;; stores the full path from the original root. The attrs merge
        ;; onto the container's outer div below (nil when not zoomable).
        zoom-attrs    (when (and zoomable?
                                 (not empty?)
                                 (seq path))
                        (zoom-trigger-attrs
                          {:dispatch-fn   dispatch-fn
                           :panel-id      panel-id
                           :mount-id      mount-id
                           :absolute-path (into (vec zoom-path-prefix) path)}))]
    [:div (merge {:data-testid (testid-for panel-id mount-id path)
                  :data-rf-kind (name kind)
                  :data-rf-expanded (if expanded? "1" "0")
                  :data-rf-diff-op (when diff? (name op))
                  :style {:font-family mono-stack
                          :line-height 1.4}}
                 zoom-attrs)
     ;; ---- header row ---------------------------------------------------
     ;; rf2-8pfkk — a removed-container ghost paints the removed chrome
     ;; (red wash + 2px red stripe + `−` gutter glyph + strike-through)
     ;; at the HEADER level so the collapsed `:shapes {…} (N keys)` line
     ;; reads as a single struck-through deletion, while the triangle
     ;; stays clickable to walk the ghost. Reuses the same `:diff-
     ;; removed-*` tokens as the leaf-level `gutter-row :removed`.
     [:div {:data-rf-removed-ghost (when removed-ancestor? "1")
            :style (cond-> {:display "flex"
                            :align-items "baseline"
                            :gap "4px"
                            :flex-wrap "wrap"}
                     removed-ancestor?
                     (assoc :text-decoration "line-through"
                            :padding-left "6px"
                            :border-left (str "2px solid " (:diff-removed-stripe tokens))
                            :background (:diff-removed-wash tokens)))}
      (when removed-ancestor?
        [:span {:data-rf-diff-op "removed"
                :style (assoc gutter-glyph-base-style
                              :color (:diff-gutter tokens)
                              :text-decoration "none")}
         "−"])
      ;; rf2-nk7w0 — the five-variant header `cond` (empty / depth-capped
      ;; / inline-fit / expanded / collapsed-summary) extracted to
      ;; `render-container-header`. Output byte-identical.
      (render-container-header
        {:empty?          empty?
         :depth-capped?   depth-capped?
         :inline-fit?     inline-fit?
         :expanded?       expanded?
         :width-fits?     width-fits?
         :value           value
         :kind            kind
         :panel-id        panel-id
         :mount-id        mount-id
         :path            path
         :toggle-fn       toggle-fn
         :max-inline-width max-inline-width
         :diff?           diff?
         :projection      projection
         :op              op})]

     ;; ---- body — children rendered indented -----------------------------
     ;;
     ;; Diff threading: for each child we compute the matching `:before`
     ;; slice (or `::missing` if the slot didn't exist pre-diff). This
     ;; lets the child's recursion render its own gutter row / inline
     ;; `← was <prior>` annotation; the parent's `:children`
     ;; row supplies the ancestor-open + ◴ glyph context.
     ;;
     ;; rf2-1bra5 — map / record bodies use CSS Grid (max-content 1fr) so
     ;; values column-align across rows. Pre-fix each row was its own
     ;; flex container with key + value sized independently → ragged
     ;; value column. With grid every row's key sits in column 1 (sized
     ;; to the widest key in THIS map; nested maps compute their own
     ;; column-1 width independently) and every row's value sits at the
     ;; single column-2 left edge.
     ;;
     ;; Sequentials (vectors / lists / sets / seqs) keep the per-row
     ;; block flow — values are emitted bare (no key column), so a
     ;; grid template wouldn't add anything.
     (when (seq children)
       (let [labelled?    (#{:map :record :map-entry} kind)
             ;; rf2-zuh1e — `children` is already the diff-aware triple
             ;; `[k after-value before-value]` when `diff?` is true (from
             ;; `children-of-pair`); plain browse hands back the legacy
             ;; `[k v]` pair which we lift into a uniform triple with
             ;; `::missing` on the `before` slot so the downstream
             ;; `render-node` call site reads one shape.
             child-pairs
             (if diff?
               children
               (for [[k cv] children]
                 [k cv ::missing]))]
         (if labelled?
           ;; --- CSS Grid body for labelled-key kinds ---
           ;; Each row contributes key (col 1) + value (col 2) as direct
           ;; grid children. `column-gap` provides the key-to-value
           ;; spacing (8px = old per-row :gap "6px" rounded to a 4-step).
           ;; `row-gap 0` keeps rows tight against the canvas density.
           ;;
           ;; `align-items: baseline` aligns the key and value text
           ;; baselines per row — short keys with tall values (e.g. a
           ;; nested map's bracket) hang on the same baseline as the
           ;; key. Falls back gracefully when a value is a multi-line
           ;; container — the value cell grows down, the key stays
           ;; baseline-aligned to the value's first line.
           ;; rf2-726ol — `margin-left 16px` puts the 1px guide line at
           ;; the triangle's visual center (the 22px glyph renders the
           ;; triangle box ~24-26px wide via `triangle-style`'s
           ;; min-width; centre lands at ~12-16px from the row's left
           ;; edge). The closing brace below sits at the same
           ;; `padding-left 16px` — line + first-key column + closing-
           ;; brace column all converge on one vertical column, so the
           ;; tree reads as `▾ { │ keys │ }` recursively at every depth.
           ;; R4 rail (rf2-n2jig): when this container is change-
           ;; bearing, promote the body's left border to a 2px coloured
           ;; rail in the dominant-op hue. `has-change?` already implies
           ;; a pre-image is present (it's `(and diff? …)`); when there's
           ;; no diff, or no change, the subtle guide line stays.
           (into [:div (let [rail-stripe (when has-change?
                                           (op-stripe-colour op))]
                         {:data-testid (str (testid-for panel-id mount-id path) "-body")
                          :data-rf-body-layout "grid"
                          :data-rf-rail (when rail-stripe "1")
                          :style (cond-> body-grid-style
                                   rail-stripe
                                   (assoc :border-left (str "2px solid " rail-stripe)))})]
                 ;; rf2-nk7w0 — per-row key/value cell construction
                 ;; extracted to `render-grid-child-row`.
                 (mapcat
                   (fn [[k cv cb]]
                     (render-grid-child-row
                       {:k k :cv cv :cb cb
                        :path path :depth depth
                        :diff? diff? :projection projection
                        :removed-ancestor? removed-ancestor?
                        :panel-id panel-id :mount-id mount-id
                        :expansion-map expansion-map :dispatch-fn dispatch-fn
                        :zoomable? zoomable? :zoom-path-prefix zoom-path-prefix
                        :opts opts}))
                   child-pairs))
           ;; --- Block body for sequentials (vector / list / set / seq) ---
           ;; Each child renders bare (no key column). Per-row block flow
           ;; — each entry sits below the previous; nested containers
           ;; recurse with their own grid/block decision.
           ;;
           ;; rf2-726ol — same `margin-left 16px` as the grid body so
           ;; the vertical guide line sits at the triangle's visual
           ;; centre. Closing bracket below shares the same `16px`
           ;; padding-left.
           (into [:div (let [rail-stripe (when has-change?
                                           (op-stripe-colour op))]
                         {:data-testid (str (testid-for panel-id mount-id path) "-body")
                          :data-rf-body-layout "block"
                          :data-rf-rail (when rail-stripe "1")
                          :style (cond-> body-block-style
                                   rail-stripe
                                   (assoc :border-left (str "2px solid " rail-stripe)))})]
                 ;; rf2-nk7w0 — per-child render extracted to
                 ;; `render-block-child`.
                 (map
                   (fn [[k cv cb]]
                     (render-block-child
                       {:k k :cv cv :cb cb
                        :path path :depth depth
                        :diff? diff? :projection projection
                        :removed-ancestor? removed-ancestor?
                        :panel-id panel-id :mount-id mount-id
                        :expansion-map expansion-map :dispatch-fn dispatch-fn
                        :zoomable? zoomable? :zoom-path-prefix zoom-path-prefix
                        :opts opts}))
                   child-pairs)))))

     ;; ---- close bracket (only when expanded + body present) -------------
     ;; rf2-726ol — closing bracket sits at `padding-left 16px` so it
     ;; column-aligns with the vertical guide line above (the body div's
     ;; 16px margin + 1px border puts the line at x=16). The bracket
     ;; pair `▾ { … }` reads as a coherent vertical column at every
     ;; nesting depth.
     ;;
     ;; `data-rf-cell "close"` exposes the close-bracket cell for
     ;; testbed assertions (column-alignment regression tests probe
     ;; this attr).
     (when (and expanded? (not empty?) (not depth-capped?) (not inline-fit?))
       [:div {:data-rf-cell "close"
              :style {:padding-left "10px"
                      :color (get tokens (:tone-key (delim kind)))}}
        (let [{:keys [close]} (delim kind)] close)])]))

(defn- render-protocol-node
  "Render a value that satisfies `IXrayEdnInspector` via the consumer's
  protocol methods. Returns hiccup wrapping the consumer's header
  (and optional body) in the standard widget chrome — same testid
  + container-shape contract as the built-in renderer so panel
  layout doesn't shift between protocol + built-in nodes.

  Returns `nil` if the consumer's `-xray-render-header` returns
  `nil` — the caller treats that as a fall-through to the built-in
  renderer (header-nil means \"I don't actually want to customise
  this node\")."
  [{:keys [value panel-id mount-id path expansion-map] :as node-opts}]
  (let [proto-opts (assoc node-opts :node-opts node-opts)
        header     (ddp/xray-render-header value proto-opts)]
    (when (some? header)
      (let [body          (ddp/xray-render-body value proto-opts)
            default?      true
            expanded?     (and (some? body)
                               (resolve-expanded? expansion-map panel-id
                                                  mount-id path default?))]
        [:div {:data-testid      (testid-for panel-id mount-id path)
               :data-rf-kind     "protocol"
               :data-rf-protocol "1"
               :data-rf-expanded (if expanded? "1" "0")
               :style {:font-family mono-stack
                       :line-height 1.4}}
         [:div {:style {:display "flex"
                        :align-items "baseline"
                        :gap "4px"
                        :flex-wrap "wrap"}}
          header]
         (when (and expanded? (some? body))
           ;; rf2-726ol — protocol-node body shares the same alignment
           ;; rule as built-in container bodies: line at the triangle's
           ;; visual centre (~16px from row left), body content with a
           ;; 6px breath beyond the line.
           [:div {:data-testid (str (testid-for panel-id mount-id path) "-body")
                  :style body-block-style}
            body])]))))

(defn- render-leaf-with-diff
  "Render a scalar leaf, wrapped in the diff row chrome when `diff?`
  is truthy. Returns hiccup; the gutter-row wrapper paints wash +
  stripe + glyph at the row level while the inner `render-scalar`
  paints per-token syntax colour at the token level.

  rf2-awqts — per-token text colour is PRESERVED across all diff ops.
  Pre-fix `:added` overrode to `:green` text, `:removed` to `:red`,
  `:modified` to `:yellow` — those clashed with the Calva-aligned
  `:syntax-*` palette (numbers orange ≡ modified yellow, booleans
  gold ≡ modified yellow). Now the row chrome (wash + stripe + glyph)
  carries the diff signal; the scalar's `:syntax-*` colour reads
  type semantics unchanged.

  Op-specific text decorations (e.g. `:removed` strike-through, the
  `← was <prior>` chip on `:modified`) survive because they
  are non-colour signals — strike-through is a TEXT-DECORATION
  channel; the chip is a separate inline element.

  - `:added`        — render `value`,  gutter `+`, green wash + stripe
  - `:removed`      — render `before` (strike-through), gutter `−`,
                      red wash + stripe
  - `:modified`     — render `value` + `← was <prior>` chip,
                      gutter `~`, amber wash + stripe
  - `:same`         — render `value` (dimmed via `:text-tertiary` so the
                      eye lands on the changes)
  - `:same-shifted` — render `value` (no dimming) + `(was N)` muted
                      suffix per R6 vector shift-detection.

  ## rf2-n2jig — projection-aware

  When `:projection` is supplied (the diff render path), the
  `op` for this leaf is read off `engine/op-at projection path`.
  Otherwise the call falls back to the legacy (before, after) pair-
  based op classification via `engine/op-at` over a 1-shot projection
  computed at the leaf level — same answer, more allocation.

  R5-tinted: when the leaf sits inside a wholly-changed ancestor, the
  glyph + stripe are suppressed (only the wash + parent's marking
  carry the signal). Implemented via `gutter-row` chrome-opts.

  ## rf2-zpeyv — slot-anchored rendering

  When `:slot-anchored?` is true, the leaf's own wash is SUPPRESSED.
  The caller (the map-row grid renderer) has painted the per-op wash
  on the WHOLE row (key cell + value cell) so the slot-identity
  change (R2 key add/remove) reads as a single banded row. Without
  suppression, the inner wash overlaps the outer cell wash and the
  value half reads darker than the key half. Only the gutter glyph
  and per-token text colour remain on the leaf side."
  [{:keys [value before diff? projection path slot-anchored? removed-ancestor?]}]
  (if-not diff?
    (render-scalar value)
    (let [;; rf2-8pfkk — the STRUCTURAL sentinel is authoritative for
          ;; one-sided slots, OVERRIDING the projection. A slot whose
          ;; `value` is `::missing` does not exist in the after-tree —
          ;; that is the definition of a removal, full stop; symmetric
          ;; for `before` `::missing` (an addition). The projection's
          ;; per-path op CANNOT be trusted to agree: the engine anchors
          ;; a `dissoc`-to-`{}` as a `:children`/`:removed` op on the
          ;; surviving PARENT path and leaves the removed child slot
          ;; classified `:children` (it has its own `:container-ops`
          ;; entry for the ghost subtree). Without this override the
          ;; leaf fell through `case op`'s default branch and rendered
          ;; `(render-scalar ::missing)` — leaking the internal sentinel
          ;; keyword (`:day8…edn-inspector/missing`) into the output.
          ;; `removed-ancestor?` carries the same force down a removed
          ;; container ghost so every descendant reads `:removed` (the
          ;; symmetric of rf2-bufw2's `:added` inheritance).
          op (cond
               removed-ancestor?    :removed
               (= value ::missing)  :removed
               (= before ::missing) (if (= value ::missing) :same :added)
               projection           (engine/op-at projection (vec (or path [])))
               (= before value)     :same
               :else                :modified)
          ;; rf2-8pfkk — the value actually painted is always the
          ;; PRESENT side of the (before, value) pair. `::missing` is an
          ;; internal absence marker, not a value, so it must never be
          ;; handed to `render-scalar` (which would `pr-str` the sentinel
          ;; keyword into the output). For a removal `value` is missing →
          ;; paint `before`; for a ghost descendant `before` is missing →
          ;; paint `value`. A pair with BOTH sides missing is structurally
          ;; impossible (a slot exists in at least one side), so it falls
          ;; back to `nil` rather than ever surfacing the sentinel.
          present-value (cond
                          (not= value ::missing)  value
                          (not= before ::missing) before
                          :else                   nil)
          ;; R5-tinted: descendant of wholly-changed root?
          wholly-anc (when projection
                       (engine/wholly-changed-ancestor projection (vec (or path []))))
          inside-wholly? (and wholly-anc (not= wholly-anc (vec (or path []))))
          ;; R5: suppress glyph + stripe on descendants of a wholly-
          ;; changed root, but RETAIN the wash for partial-visibility.
          ;; R2-revised (rf2-zpeyv): `:slot-anchored?` from the map-row
          ;; caller adds wash suppression on top of the R5 rule — the
          ;; outer key+value cells paint the whole-row wash.
          chrome-opts (cond-> nil
                        inside-wholly?
                        (assoc :suppress-glyph?   true
                               :suppress-stripe?  true
                               :wash-op           (engine/op-at projection wholly-anc))
                        slot-anchored?
                        (assoc :suppress-wash? true))
          ;; R6: shifted-was-index for vector elements at a different
          ;; position than they were in the before-tree.
          was-index (when (= op :same-shifted)
                      (engine/shifted-was-index projection (vec (or path []))))
          ;; R7: type-change suffix uses the `mini` renderer.
          type-change? (when projection
                         (engine/type-change? projection (vec (or path []))))
          ;; R8: redaction-side dictates curated suffix.
          redaction-side (when projection
                           (engine/redaction-side projection (vec (or path []))))]
      (case op
        :added
        (gutter-row :added
                    [:span {:data-rf-diff-op "added"}
                     (render-scalar value)]
                    chrome-opts)
        :removed
        ;; The struck-through value is the PRESENT side: the BEFORE side
        ;; for an ordinary removal (`value` is `::missing`), or the VALUE
        ;; side when this leaf is a descendant of a removed container
        ;; ghost (rf2-8pfkk — the ghost is threaded as `value` with
        ;; `before` `::missing` so the union walk visits every removed
        ;; descendant). `present-value` is sentinel-free by construction.
        (gutter-row :removed
                    [:span {:data-rf-diff-op "removed"
                            :style {:text-decoration "line-through"}}
                     (render-scalar present-value)]
                    chrome-opts)
        :modified
        (gutter-row :modified
                    [:span {:data-rf-diff-op "modified"
                            :style {:display "inline-flex"
                                    :align-items "baseline"
                                    :flex-wrap "wrap"
                                    :gap "4px"}}
                     (render-scalar value)
                     ;; R8 curated suffix for one-sided redaction; R7
                     ;; mini-rendered suffix for type changes; R1
                     ;; default for plain scalar mods.
                     (cond
                       (= redaction-side :before)
                       [:span {:data-rf-diff-annotation "redacted-before"
                               :style change-annotation-style}
                        "← was redacted"]
                       (= redaction-side :after)
                       [:span {:data-rf-diff-annotation "redacted-after"
                               :style change-annotation-style}
                        "← now redacted"]
                       type-change?
                       [:span {:data-rf-diff-annotation "type-change"
                               :style change-annotation-style}
                        "← was "
                        [:span {:style {:font-family mono-stack}}
                         (let [prior (if projection
                                       (:before (engine/entry-at projection
                                                                  (vec (or path []))))
                                       before)]
                           ;; Use the mini renderer for compact prior;
                           ;; fall back to a type-summary when it
                           ;; would overflow.
                           [mini prior 40])]]
                       :else
                       (change-annotation
                         (if projection
                           (or (:before (engine/entry-at projection
                                                          (vec (or path []))))
                               before)
                           before)))]
                    chrome-opts)
        :same-shifted
        (gutter-row :same-shifted
                    [:span {:data-rf-diff-op "same-shifted"
                            :style {:display "inline-flex"
                                    :align-items "baseline"
                                    :gap "8px"}}
                     (render-scalar present-value)
                     ;; R6: `(was N)` muted suffix.
                     (when was-index
                       [:span {:data-rf-diff-was-index (str was-index)
                               :style {:color       (:text-tertiary tokens)
                                       :font-family sans-stack
                                       :font-size   "11px"
                                       :font-style  "italic"}}
                        (str "(was " was-index ")")])]
                    chrome-opts)
        :same
        (gutter-row :same
                    [:span {:data-rf-diff-op "same"
                            :style {:color (:text-tertiary tokens)}}
                     (render-scalar present-value)]
                    chrome-opts)
        ;; Default — paint as :same so unknown ops degrade gracefully.
        ;; `present-value` keeps the internal `::missing` sentinel out of
        ;; the output even if an unexpected op ever reaches here.
        (gutter-row :same
                    [:span {:data-rf-diff-op (name op)}
                     (render-scalar present-value)]
                    chrome-opts)))))

(defn render-node
  "Recursive entry. Picks container vs scalar; threads the
  expansion-map snapshot down. Returns hiccup. Pure projection of
  (value, expansion-map, opts) — no `rf/subscribe` calls in here.

  Consults `IXrayEdnInspector` at the head — if `value` satisfies the
  protocol AND the consumer's `-xray-render-header` returns non-nil
  hiccup, the protocol path wins. Otherwise falls through to the
  built-in container / scalar dispatch (phase 7 / rf2-0qrcr).

  Diff mode: when `:diff?` is true the renderer paints gutter rows +
  `← was <prior>` annotations; when `:before` is
  `::missing`, the node is rendered as `:added`; when `value` is
  `::missing`, as `:removed`.

  `:dispatch-fn` (optional) is the frame-aware dispatcher captured by
  the surrounding `reg-view` body (rf2-y59tb) so toggle clicks land on
  the same frame the widget is mounted under. Tests / programmatic
  callers that drive render-node without a mount can omit it — the
  container-renderer falls back to the global `rf/dispatch`.

  Public so unit tests can drive the renderer without mounting.

  ## rf2-zpeyv — slot-anchored threading

  `:slot-anchored?` is set by the map-row grid renderer when a CHILD
  slot's op is `:added` / `:removed`. It threads to
  `render-leaf-with-diff` so the leaf's inner gutter-row suppresses
  its own wash (the map row paints whole-row wash on the key + value
  cells). Container values inside an added/removed slot already
  render via the recursive `render-container` path; that path paints
  no leaf-wash itself, so the flag only matters when the value bottoms
  out at a scalar leaf.

  ## rf2-8pfkk — removed-container ghosts + `:removed-ancestor?`

  A removed slot whose prior value is a CONTAINER renders as a single
  collapsed struck-through ghost node (`:shapes {…} (N keys)`), reusing
  the ordinary `render-container` collapse / expand / elision machinery
  — the ghost is threaded as `value` (with `before` `::missing`) so the
  existing union walk visits every removed descendant. `:removed-
  ancestor?` is threaded down that ghost subtree so every descendant
  reads `:removed` regardless of what the projection says about the
  per-child path (the symmetric of rf2-bufw2's `:added` inheritance).
  Without the ghost path a deleted subtree either `pr-str`'d in full
  (unbounded verbosity) or leaked the `::missing` sentinel through the
  leaf renderer's projection-trusting op resolution."
  [{:keys [value before diff? projection panel-id mount-id path depth expansion-map
           dispatch-fn zoomable? zoom-path-prefix opts slot-anchored? removed-ancestor?]
    :or   {depth 0 path [] zoom-path-prefix []}}]
  (or
    ;; Protocol seam (rf2-0qrcr) — light-touch satisfies? gate; nil
    ;; result falls through to built-ins. Bound to the same testid
    ;; contract as the built-in renderer so panel chrome doesn't shift.
    (when (and (not= value ::missing)
               (ddp/satisfies-xray-edn-inspector? value))
      (render-protocol-node {:value value
                             :panel-id panel-id
                             :mount-id mount-id
                             :path path
                             :depth depth
                             :expansion-map expansion-map
                             :opts opts}))
    (cond
      ;; rf2-8pfkk — inside a removed container ghost. The `before` side
      ;; was collapsed to `::missing` when we re-rooted the ghost as
      ;; `value` (so `children-of-pair` enumerates the deleted subtree),
      ;; but every node here is REMOVED, not added. `removed-ancestor?`
      ;; takes precedence over the `before ::missing` → added rule below
      ;; and forces the whole subtree through the removed render paths.
      (and diff? removed-ancestor?)
      (let [kind (collection-kind value)]
        (if (container? kind)
          (render-container {:value value
                             :kind kind
                             :panel-id panel-id
                             :mount-id mount-id
                             :path path
                             :depth depth
                             :expansion-map expansion-map
                             :dispatch-fn dispatch-fn
                             :zoomable? zoomable?
                             :zoom-path-prefix zoom-path-prefix
                             :opts opts
                             :diff? true
                             :before ::missing
                             :projection projection
                             :removed-ancestor? true})
          (render-leaf-with-diff {:value value :before ::missing :diff? true
                                  :projection projection :path path
                                  :slot-anchored? slot-anchored?
                                  :removed-ancestor? true})))

      ;; Diff mode: removed slot — render the prior value struck-through
      ;; IN PLACE (the universal diff idiom). The `value` side is
      ;; `::missing`; `before` carries the slot being removed.
      ;;
      ;; rf2-8pfkk — a removed CONTAINER renders as a recursive ghost
      ;; (one collapsed struck-through node, expandable to walk the
      ;; deleted subtree) via `render-container`, NOT a flat
      ;; `render-scalar` pr-str. The ghost is threaded as `value` with
      ;; `before` `::missing` (so `children-of-pair` enumerates every
      ;; removed descendant) plus `:removed-ancestor? true` (so the
      ;; whole subtree inherits the `:removed` op via the branch above).
      ;; A removed SCALAR still paints the single struck-through row.
      (and diff? (= value ::missing))
      (let [before-kind (collection-kind before)]
        (if (container? before-kind)
          (render-container {:value before
                             :kind before-kind
                             :panel-id panel-id
                             :mount-id mount-id
                             :path path
                             :depth depth
                             :expansion-map expansion-map
                             :dispatch-fn dispatch-fn
                             :zoomable? zoomable?
                             :zoom-path-prefix zoom-path-prefix
                             :opts opts
                             :diff? true
                             :before ::missing
                             :projection projection
                             :removed-ancestor? true})
          (render-leaf-with-diff {:value ::missing :before before :diff? true
                                  :projection projection :path path
                                  :slot-anchored? slot-anchored?})))

      ;; Diff mode: added slot at a container → render the new
      ;; container in green via the normal recursive path with `op
      ;; :added` threaded; for a scalar leaf, paint the `+` row.
      (and diff? (= before ::missing))
      (let [kind (collection-kind value)]
        (if (container? kind)
          (render-container {:value value
                             :kind kind
                             :panel-id panel-id
                             :mount-id mount-id
                             :path path
                             :depth depth
                             :expansion-map expansion-map
                             :dispatch-fn dispatch-fn
                             :zoomable? zoomable?
                             :zoom-path-prefix zoom-path-prefix
                             :opts opts
                             :diff? true
                             :before ::missing
                             :projection projection})
          (render-leaf-with-diff {:value value :before ::missing :diff? true
                                  :projection projection :path path
                                  :slot-anchored? slot-anchored?})))

      :else
      (let [kind (collection-kind value)]
        (if (container? kind)
          (render-container {:value value
                             :kind kind
                             :panel-id panel-id
                             :mount-id mount-id
                             :path path
                             :depth depth
                             :expansion-map expansion-map
                             :dispatch-fn dispatch-fn
                             :zoomable? zoomable?
                             :zoom-path-prefix zoom-path-prefix
                             :opts opts
                             :diff? (boolean diff?)
                             :before before
                             :projection projection})
          (render-leaf-with-diff {:value value
                                  :before before
                                  :diff? (boolean diff?)
                                  :projection projection
                                  :path path
                                  :slot-anchored? slot-anchored?}))))))

;; =========================================================================
;; mount-id generator + public entry — edn-inspector (form-2 component)
;; =========================================================================

(defn- gen-mount-id
  "Generate a stable per-mount id. Form-2 closure captures it once,
  so re-renders preserve it; an actual unmount/remount allocates a
  fresh id (which is the contract — independent expansion per
  call-site mount)."
  []
  (str (random-uuid)))

;; =========================================================================
;; popup affordance — opt-in "open in popup" control (rf2-l4625)
;; =========================================================================
;;
;; When a `[edn-inspector value opts]` call site passes
;; `:popup-affordance? true` in `opts`, the widget renders a small icon
;; button positioned at the top-right of the container. Click dispatches
;; the popup's `:open` event with a stable popup-mount-id derived from
;; the edn-inspector's own mount-id — so re-clicking just raises the
;; existing popup (window-manager "raise" semantics matching
;; `edn-inspector-popup/push-entry`).
;;
;; Opt-in (not always-on): scalar / tiny-value mounts don't benefit from
;; a larger inspection surface. Panels enable the affordance where the
;; inline widget is genuinely cramped (machine snapshots, sub values,
;; trace payloads). Default off keeps simple call sites quiet.
;;
;; App-DB does NOT use the affordance (rf2-7sdja — Mike's call after
;; live testing 2026-05-26): the App-DB panel has plenty of horizontal
;; room; the side panel is wide enough for the whole tree without a
;; pop-out. Earlier framing of App-DB as "the canonical cramped in the
;; side panel case" was wrong.
;;
;; The affordance dispatches the OPEN event id literally — no `require`
;; on the popup ns from here, which would form a cycle (the popup ns
;; requires edn-inspector). The event id keyword is the public contract.

(def ^:private popup-affordance-button-style
  {:position      "absolute"
   :top           "2px"
   :right         "2px"
   :background    "transparent"
   :border        "none"
   :color         (:text-tertiary tokens)
   :font-size     "12px"
   :line-height   1
   :cursor        "pointer"
   :padding       "2px 6px"
   :border-radius "3px"
   ;; Subtle by default — hover bumps colour up to text-secondary via
   ;; the data-rf-affordance="popup" hook in the global stylesheet
   ;; (theme/global-styles.cljs reads the attribute selector). Keeping
   ;; the colour change off the inline style avoids paint thrash on
   ;; every render.
   :opacity       0.6})

;; =========================================================================
;; zoom trigger + breadcrumb (rf2-h71e0; gesture reworked rf2-zl4rs)
;; =========================================================================
;;
;; Two surfaces:
;;
;; 1. `zoom-trigger-attrs` — the gesture attrs render-container merges
;;    onto every non-root container's outer div when `:zoomable? true`.
;;    Double-click (or Enter while the node is keyboard-focused) re-roots
;;    the inspector onto that node. The dispatch carries the node's
;;    ABSOLUTE path from the original root (the renderer's per-node
;;    `:path` is RELATIVE to the current zoom root, so the caller composes
;;    `zoom-path-prefix` + `path` before passing it in). There is no
;;    separate `⊙` glyph button (rf2-zl4rs): the container itself is the
;;    target, with `tab-index 0` + an `aria-label` carrying the keyboard +
;;    screen-reader affordance the button used to provide.
;;
;; 2. `zoom-breadcrumbs` — segmented nav at the top of the inspector
;;    when the zoom path is non-empty. First segment is the `:header`
;;    hiccup (or generic "root" fallback); subsequent segments render
;;    one key/index per zoom-path level. Each segment dispatches
;;    `:zoom-to` with a TRUNCATED path so clicking segment N pops the
;;    zoom back to N levels deep.

(def ^:private breadcrumb-separator
  ;; `›` (single right-pointing angle) reads as nav direction — same
  ;; convention as file-browser breadcrumbs + IDE path bars. Distinct
  ;; from `→` (transition / arrow) and `>` (greater-than / blockquote).
  "›")

(defn zoom-trigger-attrs
  "Gesture attrs for a single zoomable container — merged onto the
  container's outer div by `render-container`. Returns a map carrying:

   - `:on-double-click` — double-click re-roots the inspector onto this
     node. `preventDefault` (suppresses the browser's native dblclick
     text-selection) + `stopPropagation` (so a dblclick deep in the tree
     zooms to the INNERMOST container, not an ancestor).
   - `:on-key-down` — Enter (no modifiers) on the focused node re-roots,
     same as the double-click; other keys pass through untouched so the
     surrounding spine bindings (j/k/L/G) and Esc-zoom-out keep working.
   - `:tab-index 0` + `:aria-label` — the node is keyboard-focusable and
     announces itself as a zoom target, preserving the a11y the removed
     `⊙` button provided. We deliberately do NOT set `role \"button\"`:
     the container already nests its own `role=\"button\"` expand
     triangle, and a button-inside-button role is an ARIA nesting
     violation. A focusable labelled region is the correct shape for a
     composite node whose double-click / Enter zooms in.
   - `:data-rf-zoom-target \"1\"` — DOM hook for tooling / tests.

  ## Capture-the-frame (rf2-r0o63 — supersedes the rf2-kcaiz pin)

  Both gestures dispatch through the lexically-captured `dispatch-fn` —
  the frame-bound `dispatch` the surrounding `reg-view` body injects
  (the macro expands it over a `capture-frame` capturing the render
  frame). The handle bound the instance frame synchronously during
  render, so the dispatch lands on the SURROUNDING instance frame even
  though the gesture fires later (after
  React's synthetic-event timing has popped the dynamic frame context).
  This is the same shape as `on-toggle` — the zoom slot is per-frame, so
  the write must land on the instance frame, NOT a `:rf/xray` literal
  (which would entrench the singleton: two shells on one page would both
  write the global zoom-slot and clobber each other).

  `dispatch-fn` falls back to `rf/dispatch*` when absent (pure-render
  tests that drive the gesture without a `reg-view` ancestor).

  Public so unit tests can drive the gesture without mounting."
  [{:keys [panel-id mount-id absolute-path dispatch-fn]}]
  (let [dispatch-fn (or dispatch-fn rf/dispatch*)
        path        (vec absolute-path)
        zoom!       (fn []
                      ;; rf2-r0o63 — dispatch through the captured
                      ;; frame-aware dispatcher so the zoom-slot write
                      ;; lands on the SURROUNDING instance frame. N shells
                      ;; stay isolated; no `:rf/xray` literal.
                      (dispatch-fn
                        [:rf.xray.edn-inspector/zoom-to
                         panel-id mount-id path]))]
    {:data-rf-zoom-target "1"
     :tab-index           0
     :aria-label          (str "Zoom into " (pr-str path))
     :title               "Double-click or press Enter to zoom into this node"
     :on-double-click     (fn [^js e]
                            (when e
                              ;; Suppress the native double-click text
                              ;; selection + stop the event reaching an
                              ;; ancestor container (innermost wins).
                              (.preventDefault e)
                              (.stopPropagation e))
                            (zoom!))
     :on-key-down         (fn [^js e]
                            (when (and e
                                       (= "Enter" (.-key e))
                                       (not (.-ctrlKey e))
                                       (not (.-metaKey e))
                                       (not (.-altKey e))
                                       (not (.-shiftKey e)))
                              (.preventDefault e)
                              (.stopPropagation e)
                              (zoom!)))}))

(defn- breadcrumb-segment-label
  "Render a single path-segment label using the syntax-palette colour
  that matches the segment's type. Reuses `key-segment` so coloured-
  keyword / coloured-int / coloured-string segments paint consistently
  with the renderer's leaf-key column.

  Returns hiccup `[:span ...]`."
  [seg]
  (key-segment seg))

(def ^:private breadcrumb-row-style
  {:display       "flex"
   :flex-wrap     "wrap"
   :align-items   "baseline"
   :gap           "4px"
   :padding       "4px 8px"
   :margin-bottom "6px"
   :font-family   mono-stack
   :font-size     "12px"
   :line-height   1.4
   :background    (:bg-2 tokens)
   :border        (str "1px solid " (:border-subtle tokens))
   :border-radius "4px"})

(def ^:private breadcrumb-segment-button-style
  {:background    "transparent"
   :border        "none"
   :cursor        "pointer"
   :padding       "2px 4px"
   :margin        "0"
   :border-radius "3px"
   :font-family   mono-stack
   :font-size     "12px"
   :line-height   1.4
   :color         (:text-primary tokens)})

(def ^:private breadcrumb-separator-style
  {:color       (:text-tertiary tokens)
   :font-size   "12px"
   :user-select "none"})

(defn zoom-breadcrumbs
  "Render the breadcrumb nav above the zoomed inspector body.

  - `panel-id` / `mount-id` — key the zoom slot the dispatch mutates.
  - `zoom-path` — the current absolute zoom path (vec of segments).
    Caller passes the resolved `[]`-or-nil for the no-zoom case; this
    fn renders nothing when the path is empty.
  - `home-label` — hiccup / string for the first (home) segment; the
    consumer's `:header` value if present, else a generic `\"root\"`.
  - `dispatch-fn` — frame-aware dispatcher (lexically captured by the
    surrounding `reg-view`).
  - `testid-prefix` — base for `data-testid` attrs on each segment.

  Each segment dispatches `:zoom-to` with a TRUNCATED prefix of the
  zoom path. Home segment dispatches with `[]` (clears zoom). Segment
  N dispatches with `(subvec zoom-path 0 (inc N))`.

  Public so unit tests can drive the breadcrumb without mounting the
  full widget."
  [{:keys [panel-id mount-id zoom-path home-label dispatch-fn testid-prefix]}]
  (when (seq zoom-path)
    (let [dispatch-fn   (or dispatch-fn rf/dispatch*)
          testid-prefix (or testid-prefix "rf-xray-edn-inspector-breadcrumbs")
          home-content  (cond
                          (nil? home-label)    "root"
                          (string? home-label) home-label
                          :else                home-label)
          on-click-to   (fn [next-path]
                          (fn [^js e]
                            (when e
                              (.preventDefault e)
                              (.stopPropagation e))
                            (dispatch-fn
                              [:rf.xray.edn-inspector/zoom-to
                               panel-id mount-id (vec next-path)])))
          home-button   [:button
                         {:data-testid (str testid-prefix "-home")
                          :data-rf-breadcrumb-segment "home"
                          :on-click    (on-click-to [])
                          :aria-label  "Zoom back to root"
                          :title       "Zoom back to root"
                          :style       breadcrumb-segment-button-style}
                         home-content]
          sep           [:span {:style breadcrumb-separator-style
                                :data-rf-breadcrumb-separator "1"
                                :aria-hidden true}
                         breadcrumb-separator]
          segment-buttons
          (map-indexed
            (fn [i seg]
              (let [next-path (subvec (vec zoom-path) 0 (inc i))]
                [:button
                 {:data-testid (str testid-prefix "-" i)
                  :data-rf-breadcrumb-segment (str i)
                  :on-click    (on-click-to next-path)
                  :aria-label  (str "Zoom to " (pr-str seg))
                  :title       (str "Zoom to " (pr-str seg))
                  :style       breadcrumb-segment-button-style}
                 (breadcrumb-segment-label seg)]))
            zoom-path)]
      (into [:div {:data-testid     testid-prefix
                   :data-rf-zoomed  "1"
                   :data-rf-zoom-depth (str (count zoom-path))
                   :role            "navigation"
                   :aria-label      "Zoom breadcrumbs"
                   :style           breadcrumb-row-style}
             home-button]
            (apply concat
                   (for [btn segment-buttons]
                     [sep btn]))))))

(defn popup-affordance-button
  "Render the 'open in popup' icon button. `dispatch-fn` is the
  lexically-captured frame-aware dispatcher; `popup-mount-id` is the
  stable id keyed to the data-display's own mount-id; `value` + `opts`
  are the popup's payload.

  ## Capture-the-frame (rf2-r0o63 — supersedes the rf2-7sdja pin)

  The popup OPEN event dispatches through the lexically-captured
  `dispatch-fn` — the frame-bound `dispatch` the surrounding `reg-view`
  body injects (the macro expands it over a `capture-frame` capturing the
  render frame). The handle bound the instance frame synchronously
  during render, so the popup-open write lands on the SURROUNDING
  instance frame even though the click fires after React's
  synthetic-event timing has popped the dynamic frame context.

  The popup stack-view (`edn-inspector-popup-stack` in `shell.cljs`) is
  mounted inside the shell's `[rf/frame-provider {:frame frame-id}]`, so
  it subscribes against the SAME instance frame the affordance
  dispatches into — the write and the read meet on the instance frame,
  not a global `:rf/xray` literal. This supersedes the rf2-7sdja fix,
  which pinned the popup dispatch to a bare `:rf/xray` literal: that
  worked for the single-instance shell but entrenched the singleton
  (two shells would share one global popup-stack and clobber each
  other). Capturing the dispatcher keeps N instances isolated.

  `dispatch-fn` falls back to `rf/dispatch*` when absent (pure-render
  tests that drive the button without a `reg-view` ancestor; some tests
  pass a stub to capture the event vector).

  Public so unit tests can drive the button without spinning up the
  router."
  [dispatch-fn popup-mount-id value opts]
  (let [dispatch-fn (or dispatch-fn rf/dispatch*)]
   [:button
    {:data-testid             (str "rf-xray-edn-inspector-popup-affordance-"
                                   popup-mount-id)
     :data-rf-affordance      "popup"
     :data-rf-popup-mount-id  popup-mount-id
     :aria-label              "Open in popup"
     :title                   "Open in popup"
     :on-click                (fn [^js e]
                                (when e
                                  (.preventDefault e)
                                  (.stopPropagation e))
                                ;; rf2-r0o63 — dispatch through the
                                ;; captured frame-aware dispatcher so
                                ;; the popup-open write lands on the
                                ;; SURROUNDING instance frame (captured
                                ;; at render time), matching where the
                                ;; instance's popup-stack-view reads.
                                (dispatch-fn
                                  [:rf.xray.edn-inspector-popup/open
                                   popup-mount-id
                                   {:value value
                                    :opts  (-> (or opts {})
                                               ;; Don't recurse the
                                               ;; affordance inside the
                                               ;; popup's embedded
                                               ;; edn-inspector.
                                               (assoc :popup-affordance? false))}]))
     :style                   popup-affordance-button-style}
    ;; ↗ (north-east arrow) reads as "open in new pane / navigate
    ;; outward" which matches the popup's window-manager semantics
    ;; better than ⊕ (which read as "expand" / "add"). Same aria-label
    ;; / title — the glyph swap is visual only.
    "↗"]))

(rf/reg-view edn-inspector
  "First-class edn-inspector widget — single source of truth for
  browse + diff + mini.

  Pass `[edn-inspector value]` or `[edn-inspector value opts]`. The
  `opts` map carries `:panel-id`, `:default-expanded-depth`,
  `:max-inline-width`, `:max-depth`, `:before` — see the ns
  docstring for the full key inventory.

  - Browse mode (default): no `:before` opt; the widget renders
    `value` with expand/collapse + sticky operator overrides.
  - Diff mode: pass `:before` in `opts` (or use the
    `edn-inspector-diff` 3-arg convenience). The widget renders
    `value` as the AFTER side with gutter glyphs +
    `← was <prior>` annotations, force-expands the
    ancestor chain over any changed descendant, and dims `:same`
    rows.

  Form-2 component: the outer body allocates a stable `mount-id`
  in closure; the inner fn subscribes to the expansion slot,
  threads the snapshot through the recursive renderer, and returns
  hiccup.

  Per D4=a (rf2-sndui) the public API does NOT take a `:render-id` —
  mount-id is generated internally. Two simultaneous mounts get
  independent expansion state via the auto-id.

  Per-call-site isolation is the key correctness property here: two
  `[edn-inspector value]` mounts in the same panel must NOT share
  expansion state. The form-2 closure delivers that — the outer body
  runs once per mount.

  ## rf2-y59tb — `reg-view`-registered so dispatch / subscribe inherit
  the surrounding frame

  Before this fix `edn-inspector` was a plain `defn`. Plain Reagent fns
  do not consult the `frame-provider` React context, so when the
  widget mounted under `:rf/xray` (App-DB panel) toggle dispatches and
  expansion-slot subscribes routed to `:rf/default` instead — the
  click landed in the wrong frame's app-db and the rendering sub
  never saw it. Same root cause as `ribbon-theme-toggle` (rf2-uu3lp).

  The `reg-view` registration makes the component
  `:contextType frame-context`-aware: dispatch / subscribe both
  resolve to whatever frame the enclosing `frame-provider` puts in
  scope. The lexical `dispatch` injected by the macro is threaded
  through `render-node` opts so callbacks deep in the recursion
  carry the same frame.

  Call sites continue to read `[ei/edn-inspector value opts]` — the
  macro defs the `edn-inspector` Var to the registered render fn, so
  the public API is unchanged. Call sites that omit `opts`
  (`[ei/edn-inspector value]`) flow the same way; Reagent passes the
  positional args from the hiccup vector to both the outer and inner
  fn, and the inner fn tolerates `opts=nil` via the destructure's
  `:or` defaults."
  [_value & _opts]
  (let [mount-id    (gen-mount-id)
        ;; Capture the frame-aware dispatcher lexically. The closure
        ;; binds to the surrounding frame the outer body runs under;
        ;; every callback the inner renderer creates (toggle handlers,
        ;; recursive render-node descents) threads this closure so the
        ;; dispatch carries the right frame even when fired long after
        ;; render unwinds.
        dispatch-fn dispatch
        ;; rf2-kbdk8 — width measurement state captured in the form-2
        ;; closure so the ResizeObserver + ref callback persist across
        ;; re-renders. `observer` holds the per-mount ResizeObserver
        ;; instance (or nil before/after the lifecycle); `last-width`
        ;; debounces redundant dispatches by remembering the last
        ;; measurement we wrote — `clientWidth` returns fractional
        ;; pixels rounded by the browser, so identical measurements
        ;; arrive verbatim and should not churn the app-db slot.
        observer    (atom nil)
        last-width  (atom nil)
        ;; rf2-4p1vl — per-mount projection cache. The Editscript-backed
        ;; `engine/project` walks the full `(before, after)` pair every
        ;; call; the inner render fn runs on EVERY render of the mount
        ;; (expansion toggle, ResizeObserver widths update, parent re-
        ;; render). Without this cache the diff render path re-walks the
        ;; same byte-identical inputs N times per epoch (the redundant
        ;; per-render Editscript walk an efficiency audit flagged).
        ;;
        ;; Atom holds `{:before <ref> :after <ref> :projection <map>}` or
        ;; nil. Cache hit when both refs match the previous call by
        ;; `identical?`; otherwise recompute and store. The cache is
        ;; closure-scoped so it lives exactly as long as the mount —
        ;; unmount frees the closure, no leak.
        ;;
        ;; Identity stability of the inputs is gated by
        ;; `f/display-value` preserving structural sharing (rf2-4spyl).
        projection-cache (atom nil)
        memoised-project
        (fn [before after]
          (let [cached @projection-cache]
            (if (and cached
                     (identical? before (:before cached))
                     (identical? after  (:after  cached)))
              (:projection cached)
              (let [p (engine/project before after)]
                (reset! projection-cache
                        {:before before :after after :projection p})
                p))))
        measure-and-dispatch
        (fn [^js el]
          (when el
            (let [w (.-clientWidth el)]
              (when (and (number? w) (pos? w) (not= @last-width w))
                (reset! last-width w)
                (dispatch-fn
                  [:rf.xray.edn-inspector/set-width mount-id w])))))
        container-ref
        (fn [^js el]
          (cond
            ;; Mount — measure once, install the observer.
            (and el (nil? @observer))
            (do
              (measure-and-dispatch el)
              (when (exists? js/ResizeObserver)
                (let [obs (js/ResizeObserver.
                            (fn [_entries]
                              (measure-and-dispatch el)))]
                  (.observe obs el)
                  (reset! observer obs))))

            ;; Unmount — tear down observer, clear the slot. The slot
            ;; clear matters because the same mount-id may not recur
            ;; (each fresh mount allocates a UUID); leaving stale entries
            ;; in app-db is a slow leak.
            (and (nil? el) @observer)
            (do
              (try (.disconnect ^js @observer)
                   (catch :default _ nil))
              (reset! observer nil)
              (reset! last-width nil)
              (dispatch-fn
                [:rf.xray.edn-inspector/clear-width mount-id]))))]
    (fn render-edn-inspector
      [value & rest-args]
      (let [opts          (first rest-args)
            {:keys [panel-id site-id default-expanded-depth max-inline-width
                    max-depth popup-affordance? card? header zoomable?
                    added?]
             :or   {panel-id :rf.xray.edn-inspector/anon
                    ;; rf2-kbdk8 — default raised from 2 → 8. Under the
                    ;; width-aware heuristic this opt is a CEILING (never
                    ;; auto-expand past depth N), not a TRIGGER. The
                    ;; legacy depth-driven path keeps the same number as
                    ;; the maximum auto-open depth so deep tests still
                    ;; reach their leaves before the measurement arrives.
                    default-expanded-depth default-ceiling-depth
                    max-inline-width 60
                    max-depth 16
                    popup-affordance? false
                    card? false
                    ;; rf2-h71e0 — `:zoomable?` is OPT-IN. When false
                    ;; (default) the widget renders exactly as before:
                    ;; no zoom affordance, no breadcrumb, full tree
                    ;; rendered from the original root.
                    zoomable? false}} opts
            ;; rf2-kp7bw — `:added?` is the FIRST-RUN signal: a value
            ;; that just came into existence (a sub's first cache entry,
            ;; an app-db key that just appeared). Without an explicit
            ;; `:before`, `:added? true` synthesises the diff's prior
            ;; side as `engine/missing-sentinel`, so the projection
            ;; classifies the WHOLE tree as `:added` (root op `:added`
            ;; → green wash + `+` chrome over every descendant). This is
            ;; the container-shaped parity for the scalar first-run
            ;; `:added` chrome the SUBSCRIPTIONS leaf branch paints at
            ;; the row level (rf2-fyd8u handled scalars only; containers
            ;; mounted plain on a first run because `before` was nil and
            ;; the inspector never entered diff mode). An explicit
            ;; `:before` always wins (an actual prior value is a real
            ;; diff, not a first run); empty containers still read
            ;; `:added` (the engine reports root `:added` for
            ;; `(missing-sentinel, {})`). See §10.0.13.
            before        (if (contains? opts :before)
                            (:before opts)
                            (when added? engine/missing-sentinel))
            diff?         (or (contains? opts :before) (boolean added?))
            ;; rf2-okq7p — `:header` opts the widget into the 3-shade
            ;; card chrome (outer SECTION + grey-on-grey HEADER ribbon
            ;; + body), modelled on the Machine panel's `focused-event-
            ;; section`. `nil` (default) renders inline as before;
            ;; string or hiccup wraps the render in the chrome with
            ;; the supplied content as the header ribbon. Composable —
            ;; the consumer panel decides whether each top-level
            ;; inspector mount earns a label. See §10.0.10.
            chromed?      (some? header)
            ;; rf2-pvsxs — `site-id` (when supplied) opt-out of the
            ;; per-call-site mount-id isolation. The expansion-key's
            ;; second slot reads `site-id` instead of the auto-mount-
            ;; id, so a panel that leaves AND returns to the same
            ;; surface (e.g. App-DB tab switching) finds its prior
            ;; overrides under a stable key. When omitted, behaviour
            ;; is unchanged — auto-mount-id keeps per-call-site
            ;; isolation for naive callers (two `[edn-inspector]`
            ;; mounts side-by-side toggle independently).
            ;;
            ;; The choice is per-consumer-panel: App-DB / Handler /
            ;; Trace pass a stable :site-id derived from their
            ;; cascade epoch + slot role; throw-away surfaces (popup
            ;; previews, table-cell minis) omit it.
            effective-id  (or site-id mount-id)
            ;; `subscribe` is the lexical frame-aware closure
            ;; injected by `reg-view` — reads the expansion-slot
            ;; from the surrounding frame's app-db (e.g. `:rf/xray`).
            expansion-map @(subscribe [expansion-slot])
            ;; rf2-h71e0 — zoom slot read alongside expansion. Nil/empty
            ;; for unzoomed mounts; non-empty vec for mounts with an
            ;; active zoom. The slot is per-frame (same `:rf/xray`
            ;; pattern as `expansion-slot`); the per-mount key is
            ;; `[panel-id effective-id]` (effective-id is `site-id` if
            ;; supplied, else the auto-mount-id).
            ;;
            ;; rf2-zl4rs — zoom now applies in the SINGLE full+diff
            ;; renderer (rf2-e28r3). When a zoom is active the inspector
            ;; re-roots `value` along the path ALWAYS, and re-roots
            ;; `before` the same way ONLY when a pre-image is present
            ;; (diff mode). The projection is recomputed over the
            ;; re-rooted pair below, so the diff rail / chip / inline
            ;; annotations paint relative to the zoomed subtree exactly
            ;; as they do at the root. A stale path (mutated out from
            ;; under the zoom) falls back to the full value via
            ;; `resolve-zoom-into`. (The earlier rf2-h71e0 design
            ;; suppressed zoom whenever a `before` was present; that
            ;; conflict is resolved by re-rooting both halves together.)
            zoom-map      (when zoomable? @(subscribe [zoom-slot]))
            zoom-path     (resolve-zoom-path zoom-map panel-id
                                             (or site-id mount-id))
            zoom-active?  (and zoomable? (seq zoom-path))
            displayed-value
            (if zoom-active?
              (resolve-zoom-into value zoom-map panel-id
                                 (or site-id mount-id))
              value)
            displayed-before
            (if (and diff? zoom-active?)
              (resolve-zoom-into before zoom-map panel-id
                                 (or site-id mount-id))
              before)
            ;; The effective zoom-path-prefix is `[]` when not zoomed;
            ;; otherwise the stored zoom-path. Threaded into every
            ;; container's render-context so the affordance button can
            ;; dispatch with the ABSOLUTE path (zoom path + per-node
            ;; relative path).
            zoom-path-prefix (if zoom-active? zoom-path [])
            ;; rf2-kbdk8 — read the measured container width keyed by
            ;; THIS mount's id. Nil on the first render (the ref hasn't
            ;; fired yet); subsequent renders see the width and the
            ;; width-aware heuristic kicks in. ResizeObserver keeps the
            ;; slot live across panel resizes.
            widths        @(subscribe [widths-slot])
            available-width-px (get widths mount-id)
            container-id  (str "rf-xray-edn-inspector-"
                               (name panel-id) "-" mount-id)
            ;; Stable popup-mount-id derived from THIS edn-inspector's
            ;; own mount-id so re-clicking the affordance "raises" the
            ;; existing popup rather than spawning a duplicate
            ;; (matches `edn-inspector-popup/push-entry` semantics).
            popup-mount-id (str "ddp-" mount-id)
            ;; rf2-okq7p — when `:header` is supplied we move the
            ;; measurement + popup-positioning context out to the
            ;; outer `<section>` so the body div stays a content
            ;; sleeve. The mount-id testid + ref still anchor at the
            ;; root of whichever shape renders (section in chromed
            ;; mode; div otherwise).
            ;; rf2-n2jig — compute the Editscript-backed projection
            ;; once at the top so every recursive render-node descends
            ;; with the same `{path → op}` table. Outside diff mode the
            ;; projection is nil and the renderer's path-keyed lookups
            ;; return `:same` for everything. Pure data — same value
            ;; key composability as `expansion-map`.
            ;;
            ;; rf2-4p1vl — the projection is computed via the per-mount
            ;; `memoised-project` closure (captured in the form-2 outer
            ;; body). Byte-identical `(displayed-before, displayed-
            ;; value)` pairs across renders short-circuit to the cached
            ;; result; only changed inputs trigger a fresh Editscript
            ;; walk. Mirrors the sub-cache layer that fronts the
            ;; `:diff` lens (rf2-yqjrd / engine/project in
            ;; epoch/projection.cljc) — identical (before, after)
            ;; inputs reuse the cached Editscript result rather than
            ;; recomputing the A* edit-script on every render.
            projection    (when diff?
                            (memoised-project displayed-before displayed-value))
            body-content  (render-node
                            {:value displayed-value
                             :before (if diff? displayed-before ::missing)
                             :diff? diff?
                             :projection projection
                             :panel-id panel-id
                             :mount-id effective-id
                             :path []
                             :depth 0
                             :expansion-map expansion-map
                             :dispatch-fn dispatch-fn
                             :zoomable? zoomable?
                             :zoom-path-prefix zoom-path-prefix
                             :opts {:default-expanded-depth default-expanded-depth
                                    :max-inline-width max-inline-width
                                    :max-depth max-depth
                                    :available-width-px available-width-px}})
            ;; rf2-h71e0 — breadcrumb row above the body, only when a
            ;; zoom is active. Home label uses the consumer's `:header`
            ;; if supplied (mirrors §10.0.10's "header is the natural
            ;; identity label"); otherwise falls back to the generic
            ;; "root" string.
            breadcrumbs   (when zoom-active?
                            (zoom-breadcrumbs
                              {:panel-id      panel-id
                               :mount-id      effective-id
                               :zoom-path     zoom-path
                               :home-label    (or header "root")
                               :dispatch-fn   dispatch-fn
                               :testid-prefix (str container-id "-breadcrumbs")}))
            ;; rf2-h71e0 — Esc handler for "zoom up one level". Active
            ;; only when the widget is zoomable AND currently zoomed.
            ;; Coordinates with the popup widget's Esc-closes-top
            ;; behaviour (rf2-7sdja): the popup's keydown handler lives
            ;; on its own backdrop + dialog and `stopPropagation`s, so
            ;; when a popup is open Esc closes the popup; subsequent
            ;; Esc presses (no popup open) reach this handler and zoom
            ;; up one level. Captures the surrounding-frame dispatcher
            ;; so the zoom-up event lands on the correct frame.
            on-keydown
            (when zoom-active?
              (fn [^js e]
                (when (and e (= "Escape" (.-key e)))
                  (.preventDefault e)
                  (.stopPropagation e)
                  (dispatch-fn
                    [:rf.xray.edn-inspector/zoom-up
                     panel-id effective-id]))))]
        (if chromed?
          ;; ── rf2-okq7p — three-shade card chrome (section + header
          ;; ribbon + body). Modelled on the Machine panel's
          ;; `focused-event-section`: outer `<section>` paints the
          ;; surface band (`:bg-2`, light: #ffffff), the `<header>`
          ;; ribbon sits one shade darker (`:bg-3`, light: #e8e8e8),
          ;; and the body sleeve sits one shade lighter (`:bg-1`,
          ;; light: #f5f5f5). The three-shade ramp reads as a card
          ;; with a distinct label band rather than one continuous
          ;; block — consumer panels mounting multiple inspectors
          ;; side-by-side (App-DB: counter db + machine db + routes;
          ;; Handler: event + before + after + fx + coeffects) can
          ;; label each mount without visual blending.
          ;;
          ;; The mount-id testid + ref + measurement plumbing migrate
          ;; out to the section so DOM-level consumers (tests, panel-
          ;; gallery selectors) keep their existing selectors working
          ;; against `data-testid container-id` regardless of which
          ;; shape rendered.
          [:section {:data-testid     container-id
                     :data-rf-mount-id mount-id
                     :data-rf-site-id  (when site-id (pr-str site-id))
                     :data-rf-mode    (if diff? "diff" "browse")
                     :data-rf-popup-affordance (when popup-affordance? "1")
                     :data-rf-card     (when card? "1")
                     :data-rf-header   "1"
                     :data-rf-zoomable (when zoomable? "1")
                     :data-rf-zoomed   (when zoom-active? "1")
                     :data-rf-zoom-path (when zoom-active? (pr-str zoom-path))
                     :data-rf-available-width-px (when available-width-px
                                                   (str available-width-px))
                     :ref             container-ref
                     :on-key-down     on-keydown
                     :tab-index       (when zoom-active? -1)
                     :style {:font-family    mono-stack
                             :font-size      "12px"
                             :color          (:text-primary tokens)
                             :line-height    1.4
                             :background-color (:bg-2 tokens)
                             :border           (str "1px solid "
                                                    (:border-default tokens))
                             :border-radius    "4px"
                             :overflow         "hidden"
                             :margin-bottom    "8px"
                             ;; Positioning context for the absolute-
                             ;; positioned affordance button — moved
                             ;; here so the affordance still sits at
                             ;; the section's top-right corner.
                             :position    (when popup-affordance? "relative")}}
           (when popup-affordance?
             (popup-affordance-button dispatch-fn popup-mount-id value opts))
           [:header {:data-testid (str container-id "-header")
                     :data-rf-header-role "ribbon"
                     :style {:padding       "10px 12px"
                             :background    (:bg-3 tokens)
                             :border-bottom (str "1px solid "
                                                 (:border-subtle tokens))}}
            header]
           [:div {:data-testid (str container-id "-body")
                  :data-rf-body-role "card-body"
                  :style {:padding       "12px"
                          :background    (:bg-1 tokens)}}
            ;; rf2-h71e0 — breadcrumb row above the rendered tree.
            ;; Returns nil when no zoom is active, so the chromed body
            ;; layout stays unchanged for unzoomed mounts.
            breadcrumbs
            body-content]]
          ;; ── default (no `:header`): flat single-div render, with
          ;; optional rf2-63ie5 `:card?` chrome on the outer container.
          [:div {:data-testid     container-id
                 :data-rf-mount-id mount-id
                 :data-rf-site-id  (when site-id (pr-str site-id))
                 :data-rf-mode    (if diff? "diff" "browse")
                 :data-rf-popup-affordance (when popup-affordance? "1")
                 :data-rf-card     (when card? "1")
                 :data-rf-zoomable (when zoomable? "1")
                 :data-rf-zoomed   (when zoom-active? "1")
                 :data-rf-zoom-path (when zoom-active? (pr-str zoom-path))
                 :data-rf-available-width-px (when available-width-px
                                               (str available-width-px))
                 ;; rf2-kbdk8 — `:ref` callback drives the measurement.
                 ;; Captured once in the form-2 closure; React calls
                 ;; the same fn on mount + unmount so the observer's
                 ;; lifecycle mirrors the widget's DOM lifecycle.
                 :ref             container-ref
                 :on-key-down     on-keydown
                 :tab-index       (when zoom-active? -1)
                 :style (cond-> {:font-family mono-stack
                                 :font-size   "12px"
                                 :color       (:text-primary tokens)
                                 :line-height 1.4
                                 ;; Position context for the absolute-
                                 ;; positioned affordance button.
                                 ;; Harmless when the affordance is
                                 ;; off — no descendant uses absolute
                                 ;; positioning otherwise.
                                 :position    (when popup-affordance? "relative")}
                          ;; rf2-63ie5 — inspector-card chrome on
                          ;; top-level mounts. Theme-aware via tokens
                          ;; so both light + dark resolve at paint
                          ;; time. The opt-in (`:card? true`) keeps
                          ;; inline / nested mounts unchromed; panels
                          ;; with multiple top-level inspector mounts
                          ;; (App-DB, Handler) pass `:card? true` so
                          ;; the eye reads each mount as a discrete
                          ;; card rather than one continuous block.
                          card? (assoc :background-color (:bg-1 tokens)
                                       :border           (str "1px solid "
                                                              (:border-default tokens))
                                       :border-radius    "8px"
                                       :padding          "8px 10px"
                                       :margin-bottom    "8px"))}
           (when popup-affordance?
             (popup-affordance-button dispatch-fn popup-mount-id value opts))
           ;; rf2-h71e0 — breadcrumb row leads the body when a zoom is
           ;; active. nil when not zoomed so the un-zoomed render is
           ;; unchanged.
           breadcrumbs
           body-content])))))

(defn edn-inspector-diff
  "Diff convenience — `[edn-inspector-diff before after]` or
  `[edn-inspector-diff before after opts]`. Equivalent to
  `[edn-inspector after (assoc opts :before before)]`. Use when the
  call site reads more naturally with both halves of the diff at the
  callsite head."
  ([before after] (edn-inspector-diff before after nil))
  ([before after opts]
   [edn-inspector after (assoc (or opts {}) :before before)]))

;; =========================================================================
;; mini — one-line inline rendering (D2=a: 2-arg overload, sentinel-aware)
;; =========================================================================

(defn mini
  "One-line inline rendering of `value`. No expansion, no toggle —
  used in chip rows, table cells, hover tooltips where a full tree
  would crowd the layout.

  Per D2=a (rf2-sndui) the 2-arg overload keeps a `max-len` cap and
  sentinels route through here too (no separate `inspect-inline`).

  Returns hiccup `[:span ...]` so callers embed inline."
  ([value] (mini value 80))
  ([value max-len]
   (let [kind (collection-kind value)
         pr-text (try (pr-str value) (catch :default _ (str value)))
         truncated (if (<= (count pr-text) max-len)
                     pr-text
                     (str (subs pr-text 0 max-len) "…"))]
     [:span {:data-testid "rf-xray-edn-inspector-mini"
             :data-rf-mini "1"
             :title pr-text
             :style {:font-family mono-stack
                     :font-size   "11px"
                     :white-space "nowrap"
                     :overflow    "hidden"
                     :text-overflow "ellipsis"
                     :max-width   "100%"
                     :display     "inline-block"
                     :vertical-align "bottom"}}
      (cond
        ;; Sentinels keep their chip chrome inline.
        (#{:sentinel-redacted :sentinel-redacted-size :sentinel-large} kind)
        (render-scalar value)

        ;; Scalar — colour-coded mini.
        (not (container? kind))
        (render-scalar value)

        :else
        ;; Container — one-line preview string in the kind's bracket colour.
        [:span {:style (token-style :text-secondary)
                :data-rf-mini-text truncated}
         (inline-preview-string value 3 max-len)])])))
