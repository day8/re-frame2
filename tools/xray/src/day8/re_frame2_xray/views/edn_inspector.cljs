(ns day8.re-frame2-xray.views.edn-inspector
  "Xray's first-class edn-inspector widget — roll-your-own CLJS-value
  renderer (rf2-oqa60 phase 1).

  ## What this is

  ONE renderer for every Xray surface that shows a CLJS value:
  App-db, Trace per-event payload, Sub value inspector, Machine
  snapshot drill-in. The widget produces pure hiccup, owns its
  expansion state in re-frame app-db, and reads colour through CSS
  token variables so light + dark themes resolve at paint time
  without a re-render.

  ## What replaces what

  - `views/edn-widget/widget` — superseded for current-state browse
    (phase 1 wires the App-DB panel here directly; phases 2-5 migrate
    the remaining call sites).
  - `theme/data-inspector` — sentinels (`:rf/redacted`, `:rf/large`)
    become first-class types INSIDE this widget; the chrome wrapper
    goes away (D3=a per rf2-sndui).
  - `edn-inspector/render` — diff renderer subsumed (phase 5; D5=a per
    rf2-sndui). The diff path is now an opt-in mode on this same
    widget — pass `:before` to render with gutter glyphs +
    `← changed from <prior>` annotations.
  - `binaryage/cljs-devtools` dep — dropped from the project; this
    widget renders CLJS values natively from CLJS itself (rf2-oqa60).

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
  - `:site-id`      (optional, rf2-pvsxs) — when supplied, becomes the
                    second component of the per-node expansion key
                    INSTEAD of the auto-generated mount-id. Lets the
                    same logical call site survive a panel-leave-and-
                    return round-trip (auto-mount-id changes on remount;
                    a stable site-id does not). Omit to keep the per-
                    call-site isolation default (two `[edn-inspector]`
                    mounts side-by-side stay independent).
  - `:default-expanded-depth` (optional, default 8) — rf2-kbdk8: now an
                    EXPAND CEILING rather than a trigger. The widget
                    NEVER auto-expands past this depth — deeper nodes
                    render as a `▸ {…N keys}` collapsed summary unless
                    the operator clicks. Under the width-aware
                    heuristic shallow nodes inline whenever their full
                    `pr-str` fits the measured column; the depth ceiling
                    only protects against pathological wide-and-deep
                    auto-expansion when measurements are unavailable.
                    Default raised from 2 → 8 to reflect the new
                    semantic; tests that explicitly pass
                    `:default-expanded-depth 2` (or any number) get the
                    legacy depth-driven behaviour unchanged when no
                    width measurement has arrived.
  - `:max-inline-width` (optional, default 60) — character budget for
                    the COLLAPSED-PREVIEW one-liner (`▸ {:a 1, :b 2,
                    …}` style); leaves the width-aware inline decision
                    to `available-width-px` (the measured column).
  - `:max-depth` (optional, default 16) — hard cap on recursion
                    depth; deeper levels render `{…}` collapsed.
  - `:before` (optional) — when supplied, the widget renders in DIFF
                    mode. The `value` arg is treated as the `after`
                    side; the supplied `:before` is the prior value.
                    Gutter glyphs + colours paint per node
                    (`+` added · `-` removed · `~` modified ·
                    `◴` children-changed); modified leaves get an
                    inline `← changed from <prior>` annotation;
                    ancestors of any changed descendant force open
                    regardless of the default-expand heuristic.
  - `:popup-affordance?` (optional, default false) — rf2-l4625; when
                    true the widget renders a top-right ↗ icon button
                    (rf2-7sdja — was ⊕; ↗ reads as 'open in new pane')
                    that dispatches
                    `[:rf.xray.edn-inspector-popup/open mount-id payload]`
                    against `:rf/xray` explicitly (popup state is
                    Xray-global, not per-frame — rf2-7sdja). The popup-
                    mount-id is derived from this widget's own mount-
                    id, so re-clicking raises the existing popup rather
                    than spawning a duplicate. Opt-in per call-site;
                    panels enable the affordance where the inline
                    widget is genuinely cramped (machine snapshots,
                    sub values, trace payloads). App-DB does NOT use
                    the affordance (rf2-7sdja — App-DB has plenty of
                    horizontal room; the popup would be unnecessary
                    affordance noise).
  - `:card?`         (optional, default false) — rf2-63ie5; when true
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
  - `:header`        (optional, default nil) — rf2-okq7p; opt-in
                    three-shade card chrome (outer `<section>` →
                    `<header>` ribbon → body sleeve), modelled on the
                    Machine panel's `focused-event-section`. `nil`
                    renders inline as today. A string renders a
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

  - `:zoomable?`     (optional, default false) — rf2-h71e0; when true
                    every container in the tree renders a `⊙` zoom-
                    affordance button next to the expand triangle.
                    Click dispatches
                    `[:rf.xray.edn-inspector/zoom-to panel-id mount-id
                    absolute-path]`, which stores the absolute path
                    under `:rf.xray.edn-inspector/zoom` keyed by
                    `[panel-id site-or-mount-id]`. The widget then
                    renders ONLY the subtree at that path, with a
                    breadcrumb row above the body showing the path
                    from the original root (each segment clickable for
                    one-tap zoom-up).
                    Esc (when the widget has focus AND a zoom is
                    active) pops one level off the zoom stack.
                    Composes with `:popup-affordance?` (both
                    affordances render side-by-side — they serve
                    different intents: zoom = focus here; popup = open
                    in new pane). Diff mode (`:before` present)
                    suppresses zoom resolution — the diff path's
                    force-expand-over-changed-descendants logic and
                    zoom's hide-everything-outside-the-subtree are
                    conflicting intents; operators view diffs over the
                    full value. Per-mount keying matches the
                    expansion-slot pattern; pass a stable `:site-id`
                    to survive a panel-leave-and-return round-trip.

  Drop the `:render-id` arg from the old facade — mount-id is now
  auto-generated internally per D4=a (rf2-sndui).

  ## Per-call-site isolation (rf2-sndui D4=a · rf2-pvsxs opt-out)

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

  Per rf2-79ojx the per-type tokens were renamed + the palette retuned
  to an editor-syntax-highlight scheme (One Dark / One Light). Five
  scalar types now span four hue families: keyword magenta, string
  green, number orange, boolean gold, nil grey."
  (:require [clojure.string :as str]
            [re-frame.core :as rf]
            [day8.re-frame2-xray.theme.tokens
             :refer [tokens mono-stack sans-stack]]
            [day8.re-frame2-xray.views.edn-inspector-protocol :as ddp]
            ;; rf2-x16b1 — load default IXrayEdnInspector formatters
            ;; for uuid + inst. Requiring for side-effect (extend-type).
            ;; Consumers that extend the same types win — `extend-type`
            ;; installs the most-recently-loaded impl.
            [day8.re-frame2-xray.views.edn-inspector-default-formatters]))

;; =========================================================================
;; expansion state — lives in :rf.xray.edn-inspector/expansion under :rf/xray
;; =========================================================================

(def expansion-slot
  "App-db slot holding the per-node expansion overrides. Public so
  the consuming panel's reset affordance can clear it.

  Distinct from the legacy `:rf.xray/edn-inspector-expansion` slot
  used by `edn-inspector/render` — keeping them separate lets the old
  engine and the new widget coexist during the phased rollout."
  :rf.xray.edn-inspector/expansion)

(defn expansion-key
  "Compose the per-node expansion key. Pure data, JVM-portable."
  [panel-id mount-id path]
  [panel-id mount-id (vec path)])

(rf/reg-sub expansion-slot
  (fn [db _] (get db expansion-slot)))

(rf/reg-event-db :rf.xray.edn-inspector/toggle-node
  (fn [db [_ panel-id mount-id path rendered-expanded?]]
    ;; rf2-y59tb — first click MUST invert the currently-visible state.
    ;;
    ;; The widget renders `default-expanded` paths (top-level nodes,
    ;; depth ≤ `default-expanded-depth`) open BEFORE the user clicks,
    ;; even though no override is stored. If the reducer flipped from
    ;; a hard-coded assumption (e.g. "first click opens") it would
    ;; emit the same state the user already sees — a silent no-op on
    ;; the first click.
    ;;
    ;; The dispatch payload now carries `rendered-expanded?` — the
    ;; value `resolve-expanded?` returned for this path on the last
    ;; render (i.e. what the user currently sees). When no override
    ;; is stored the reducer flips from that visible state; when an
    ;; override IS stored it flips the override (idempotent given a
    ;; consistent dispatcher).
    (let [k       (expansion-key panel-id mount-id path)
          current (get-in db [expansion-slot k])
          next?   (if (contains? current :expanded?)
                    (not (boolean (:expanded? current)))
                    (not (boolean rendered-expanded?)))]
      (assoc-in db [expansion-slot k] {:expanded? next?}))))

(rf/reg-event-db :rf.xray.edn-inspector/set-node
  (fn [db [_ panel-id mount-id path expanded?]]
    (assoc-in db [expansion-slot (expansion-key panel-id mount-id path)]
              {:expanded? (boolean expanded?)})))

(rf/reg-event-db :rf.xray.edn-inspector/reset-expansion
  (fn [db _]
    (dissoc db expansion-slot)))

;; =========================================================================
;; available-width capture — per-mount measurement for the width-aware
;; expansion heuristic (rf2-kbdk8)
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
;; back to the legacy strict inline-fit gate — the heuristic improvement
;; is additive and graceful.

(def widths-slot
  "App-db slot holding the per-mount measured container widths in CSS
  pixels (map of `mount-id` → integer). Public so tests + consuming
  panels can drive measurements deterministically without spinning up a
  real DOM."
  :rf.xray.edn-inspector/widths)

(rf/reg-sub widths-slot
  (fn [db _] (get db widths-slot)))

(rf/reg-event-db :rf.xray.edn-inspector/set-width
  (fn [db [_ mount-id width-px]]
    (if (and (string? mount-id) (number? width-px) (pos? width-px))
      (assoc-in db [widths-slot mount-id] (long width-px))
      db)))

(rf/reg-event-db :rf.xray.edn-inspector/clear-width
  (fn [db [_ mount-id]]
    (if (and (string? mount-id) (some-> db (get widths-slot) (contains? mount-id)))
      (update db widths-slot dissoc mount-id)
      db)))

;; =========================================================================
;; zoom-into-node + breadcrumb navigation (rf2-h71e0)
;; =========================================================================
;;
;; Zoom turns the inspector into a focused window onto an arbitrary subtree.
;; Operator clicks the `⊙` zoom affordance on any container; that node
;; becomes the root of the displayed tree. A breadcrumb trail at the top
;; shows the path from the original root; clicking any segment zooms back
;; to that level.
;;
;; State shape mirrors `expansion-slot` — keyed by `[panel-id site-or-
;; mount-id]` so two side-by-side mounts zoom independently and a stable
;; `:site-id` (rf2-pvsxs) preserves the zoomed view across a panel-
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

(rf/reg-event-db :rf.xray.edn-inspector/zoom-to
  ;; Sets the zoom path for `[panel-id mount-id]` to `path`. An empty /
  ;; nil path clears the zoom (renders the full tree).
  (fn [db [_ panel-id mount-id path]]
    (let [k (zoom-key panel-id mount-id)
          p (vec path)]
      (if (seq p)
        (assoc-in db [zoom-slot k] p)
        (update db zoom-slot dissoc k)))))

(rf/reg-event-db :rf.xray.edn-inspector/zoom-up
  ;; Pop one segment off the zoom path. No-op when no zoom is active.
  (fn [db [_ panel-id mount-id]]
    (let [k        (zoom-key panel-id mount-id)
          current  (get-in db [zoom-slot k])
          popped   (when (seq current) (vec (butlast current)))]
      (cond
        (nil? current)   db
        (empty? popped)  (update db zoom-slot dissoc k)
        :else            (assoc-in db [zoom-slot k] popped)))))

(rf/reg-event-db :rf.xray.edn-inspector/zoom-reset
  ;; Clear the zoom for a specific mount. With no args (mount-unspecified)
  ;; clear the entire slot — used by the panel-level reset affordance.
  (fn [db [_ panel-id mount-id]]
    (cond
      (and panel-id mount-id)
      (update db zoom-slot dissoc (zoom-key panel-id mount-id))

      :else
      (dissoc db zoom-slot))))

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

(defn resolve-expanded?
  "Pure projection — given the per-render expansion map, the path,
  and the default-heuristic result, return whether THIS node renders
  expanded. The operator's sticky override (if present) wins."
  [expansion-map panel-id mount-id path default?]
  (let [k        (expansion-key panel-id mount-id path)
        override (get expansion-map k)]
    (if (contains? override :expanded?)
      (boolean (:expanded? override))
      (boolean default?))))

;; =========================================================================
;; type classification — sentinels recognised as first-class types
;; =========================================================================

(defn redacted-sentinel?
  "`:rf/redacted` bare keyword — spec/015 primary opaque sentinel."
  [v]
  (= :rf/redacted v))

(defn large-sentinel?
  "`{:rf/large {:bytes N :head s}}` size-elision sentinel."
  [v]
  (and (map? v)
       (= 1 (count v))
       (let [[k m] (first v)]
         (and (= :rf/large k) (map? m)))))

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
;; diff mode — pure helpers (op classification, gutter mapping)
;; =========================================================================
;;
;; Phase 5 (rf2-q3dzw, D5=a per rf2-sndui) subsumes the legacy
;; `edn-inspector.render` diff engine into this widget. Diff is an
;; opt-in MODE on the same renderer: when the caller passes `:before`
;; the widget paints gutter glyphs + `← changed from <prior>`
;; annotations in place, force-expands the ancestor chain over any
;; changed descendant, and dims `:same` rows so the eye lands on the
;; change.

(def missing-sentinel
  "Marker for a `:before` / `:after` slot that does not exist in its
  side of the diff (e.g. an added key has `::missing` for `:before`).
  Distinct from `nil` (which is a real CLJS value). Public so tests
  can assert the diff-op classification against it."
  ::missing)

(defn diff-op
  "Classify a (before, after) pair into a diff op keyword:

    :added    — before is `::missing`, after exists
    :removed  — before exists, after is `::missing`
    :modified — both exist and differ (leaf-level)
    :same     — both exist and are equal

  Pure data; JVM-portable."
  [before after]
  (cond
    (= before ::missing) (if (= after ::missing) :same :added)
    (= after  ::missing) :removed
    (= before after)     :same
    :else                :modified))

(declare changed-descendant?*)

(defn changed-descendant?
  "True when at least one descendant in (before, after) differs.
  Walks maps + sequentials + sets; pure. Returns true for primitive
  mismatches at the root too — so a caller can use this to drive
  ancestor-open.

  Always returns a primitive boolean (never nil) so callers can
  dispatch on `true?` / `false?` without nil-coercion."
  [before after]
  (boolean (changed-descendant?* before after)))

(defn- changed-descendant?*
  [before after]
  (cond
    ;; Either side missing → caller treats this as added / removed,
    ;; which is itself a change.
    (or (= before ::missing) (= after ::missing))
    (not (and (= before ::missing) (= after ::missing)))

    (and (map? before) (map? after))
    (or (not= (set (keys before)) (set (keys after)))
        (some (fn [k] (changed-descendant?* (get before k ::missing)
                                            (get after  k ::missing)))
              (into (set (keys before)) (keys after))))

    (and (sequential? before) (sequential? after))
    (let [a-vec (vec after)
          b-vec (vec before)
          n     (max (count a-vec) (count b-vec))]
      (or (not= (count a-vec) (count b-vec))
          (boolean
            (some true?
                  (for [i (range n)]
                    (changed-descendant?*
                      (if (< i (count b-vec)) (nth b-vec i) ::missing)
                      (if (< i (count a-vec)) (nth a-vec i) ::missing)))))))

    (and (set? before) (set? after))
    (not= before after)

    :else (not= before after)))

(def op->gutter-glyph
  "Per-op gutter glyph (§10.3 cascade-gutter mapping). Public so tests
  can assert the mapping without re-deriving."
  {:added    "+"
   :removed  "-"
   :modified "~"
   :children "◴"
   :same     " "})

(def op->gutter-tone-key
  "Per-op token-key for the gutter GLYPH colour.

  rf2-awqts — every diff-active op now reads the SAME reserved
  `:diff-gutter` token (cyan-teal in dark / darker-teal in light); the
  glyph carries the per-op shape (`+ / - / ~ / ◴`) and the row wash
  carries the per-op hue. Pre-fix the glyph colour mapped per-op to
  `:green` / `:red` / `:yellow` / `:accent`, which collided with the
  Calva-aligned `:syntax-*` palette (numbers orange ≡ modified yellow,
  booleans gold ≡ modified yellow, etc.) — the operator could not
  distinguish 'this is a number' from 'this is modified'. The reserved
  diff-gutter hue sits outside every `:syntax-*` family by design.

  `:same` keeps `:text-tertiary` so the transparent-border non-diff
  shape composes unchanged."
  {:added    :diff-gutter
   :removed  :diff-gutter
   :modified :diff-gutter
   :children :diff-gutter
   :same     :text-tertiary})

(def op->row-wash-key
  "Per-op token-key for the row-background wash. rf2-awqts —
  GitHub-style low-opacity tinge across the whole diff row, so the eye
  reads diff state at row level while per-token text colour reads type
  semantics. `:same` and `:children` get NO wash (`nil`) — the row sits
  flush with the canvas. Public so tests can assert the mapping
  without re-deriving."
  {:added    :diff-added-wash
   :removed  :diff-removed-wash
   :modified :diff-modified-wash
   :children nil
   :same     nil})

(def op->row-stripe-key
  "Per-op token-key for the 2px left-edge stripe. Reinforces the row
  wash at the column-1 anchor — same hue family, more saturated.
  `:same` and `:children` produce a transparent stripe."
  {:added    :diff-added-stripe
   :removed  :diff-removed-stripe
   :modified :diff-modified-stripe
   :children nil
   :same     nil})

(defn- gutter-colour
  "Resolve the gutter GLYPH colour for an op via the token table.
  rf2-awqts — every active op reads `:diff-gutter`; `:same` reads
  `:text-tertiary`."
  [op]
  (get tokens (op->gutter-tone-key op) (:text-tertiary tokens)))

(defn- row-wash-bg
  "Resolve the per-op row-background wash CSS string (or `nil` when no
  wash applies). rf2-awqts."
  [op]
  (when-let [k (get op->row-wash-key op)]
    (get tokens k)))

(defn- row-stripe-colour
  "Resolve the per-op 2px-stripe CSS string (or `nil` when no stripe
  applies). rf2-awqts."
  [op]
  (when-let [k (get op->row-stripe-key op)]
    (get tokens k)))

(defn- container-op
  "Classify a container's diff op given (before, after). Returns
  `:added`/`:removed`/`:children`/`:same` (containers never report
  `:modified` directly — a per-leaf modification surfaces as
  `:modified` on the leaf row, with `:children` on the ancestor)."
  [before after]
  (cond
    (= before ::missing)                       :added
    (= after  ::missing)                       :removed
    (changed-descendant? before after)         :children
    :else                                      :same))

(defn- gutter-row
  "Wrap `body` with the diff row chrome: gutter glyph + low-opacity
  row-background wash + 2px left-edge stripe (rf2-awqts).

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

  ## rf2-1bra5 — inline-flex, not flex

  Switched from `display: flex` (block-level) to `inline-flex` so a
  diff'd scalar leaf composes inline with its preceding key inside a
  map-row grid. Pre-fix the block-level `<div>` forced the leaf onto
  its own line (the per-row flex container couldn't keep the key+value
  on one line — `flex-wrap: wrap` pushed the wide block-div below the
  key, producing the `:show-parity?` / `:threshold` two-line rows Mike
  measured at ~28.79px against the inline ~17.79px sibling rows).

  Returns an `[:span ...]` (display: inline-flex) so it nests inside a
  grid cell without breaking the row."
  [op body]
  (let [active? (not= :same op)
        wash    (row-wash-bg op)
        stripe  (row-stripe-colour op)]
    [:span {:data-rf-diff-op (name op)
            :data-rf-diff-wash (when wash "1")
            :data-rf-diff-stripe (when stripe "1")
            :style (cond-> {:display      "inline-flex"
                            :align-items  "baseline"
                            :gap          "4px"
                            :padding-left "6px"
                            ;; rf2-awqts — stripe colour comes from
                            ;; `:diff-*-stripe`; on `:same` / `:children`
                            ;; the border stays transparent so the row
                            ;; chrome aligns column-wise without paint.
                            :border-left  (str "2px solid "
                                               (if (and active? stripe)
                                                 stripe
                                                 "transparent"))}
                     ;; rf2-awqts — row wash applied as background on
                     ;; the wrapping span so the tint extends behind
                     ;; both gutter glyph + value. Opacity baked into
                     ;; the token's rgba() string (~10-12%) so the
                     ;; wash reads as environmental, not obscuring.
                     wash (assoc :background wash))}
     [:span {:style {:flex          "0 0 12px"
                     :color         (gutter-colour op)
                     :font-size     "11px"
                     :font-weight   700
                     :text-align    "center"
                     :user-select   "none"}}
      (op->gutter-glyph op)]
     [:span {:style {:flex 1 :min-width 0}} body]]))

(def ^:private change-annotation-style
  "Style for the inline `← changed from <prior>` chip rendered to the
  right of a diff'd leaf."
  {:margin-left "8px"
   :color       (:text-secondary tokens)
   :font-family sans-stack
   :font-size   "11px"
   :font-style  "italic"})

(defn- change-annotation
  "Inline `← changed from <prior>` chip rendered to the right of a
  diff'd leaf. Pure hiccup."
  [before]
  [:span {:data-rf-diff-annotation "1"
          :style change-annotation-style}
   (str "← changed from " (try (pr-str before)
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
  (case (collection-kind v)
    :nil      [:span {:data-rf-type "nil"
                      :style (token-style :syntax-nil)} "nil"]
    :boolean  [:span {:data-rf-type "boolean"
                      :style (token-style :syntax-boolean)} (str v)]
    :keyword  [:span {:data-rf-type "keyword"
                      :style (token-style :syntax-keyword)} (str v)]
    :symbol   [:span {:data-rf-type "symbol"
                      :style (token-style :syntax-symbol)} (str v)]
    :string   [:span {:data-rf-type "string"
                      :style (token-style :syntax-string)} (str-pad-quote v)]
    :number   [:span {:data-rf-type "number"
                      :style (token-style :syntax-number)} (str v)]
    :uuid     [:span {:data-rf-type "uuid"
                      :style (token-style :info)} (str "#uuid \"" v "\"")]
    :regex    [:span {:data-rf-type "regex"
                      :style (token-style :info)} (str v)]
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
    (let [{:keys [bytes head]} (val (first v))]
      [:span {:data-rf-type "rf-large"
              :data-testid  "rf-xray-edn-inspector-large"
              :title        (when head (str "Head preview: " head))
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
  (case (collection-kind v)
    :nil        "nil"
    :boolean    (str v)
    :keyword    (str v)
    :symbol     (str v)
    :string     (str-pad-quote v)
    :number     (str v)
    :uuid       (str "#uuid \"" v "\"")
    :regex      (str v)
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
    (try (pr-str v) (catch :default _ (str v)))))

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
        item-str    (fn [el]
                      (cond
                        (and (= kind :map)
                             (or (map-entry? el)
                                 (and (vector? el) (= 2 (count el)))))
                        (str (inline-scalar-str (first el))
                             " " (inline-scalar-str (second el)))
                        :else
                        (inline-scalar-str el)))
        joined      (str/join ", " (map item-str head))
        with-more   (str joined (when more? (if (seq joined) ", …" "…")))
        result      (str open with-more close)]
    (if (<= (count result) max-chars)
      result
      ;; Try one-element preview as a middle ground.
      (let [one  (when (seq head) (item-str (first head)))
            mid  (str open one (when (or more? (> (count head) 1)) ", …") close)]
        (if (and one (<= (count mid) max-chars))
          mid
          fallback)))))

;; =========================================================================
;; recursive renderer — produces hiccup
;; =========================================================================

(declare render-node)

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
;; width-aware expansion heuristic (rf2-kbdk8)
;; =========================================================================
;;
;; The closed-renderer's auto-expansion was depth-driven — any container
;; within `default-expanded-depth` opened automatically regardless of how
;; trivially its inline form would fit the available column width. Result:
;; short values rendered as 8-9 row trees that consumed ~9× the vertical
;; real-estate of their inline equivalents.
;;
;; The new heuristic flips the test: render inline FIRST when the value's
;; estimated inline width fits the available column (with a small safety
;; margin); only fall back to tree-expand when the inline form would
;; overflow. `default-expanded-depth` is repurposed as a CEILING — never
;; auto-expand past depth N even when the inline form overflows; show a
;; collapsed-summary instead. The operator's sticky override and diff-
;; mode's force-open-over-changed-descendant rule still win.
;;
;; Width is estimated by `(* char-count mono-char-width-px)` — JetBrains
;; Mono / Source Code Pro at the inspector's 12px size carries an
;; ~7.2px-wide M-advance; rounding up to 7px gives a slightly conservative
;; estimate (real strings render a hair narrower, so the inline gate is
;; slightly stricter than the actual fit — never the reverse, which would
;; cause horizontal overflow).
;;
;; A `safety-margin-px` of 16px guards against edge-case wrap (a few extra
;; pixels for the closing bracket, gutter, scroll-bar reserve). Tested live
;; against the Handler panel dispatch-section mount (rf2-kbdk8 bead body):
;; the 81-char `[:ws/connection [:rf.machine.timer/after-elapsed 2501
;; [:active :authenticating]]]` value at ~570px estimate fits trivially in
;; the 966px column and now renders inline at one row (was 148px / 8-9 rows).

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
  the fallback so unit tests + first-paint behaviour stay deterministic."
  [{:keys [depth child-count default-expanded-depth available-width-px value
           has-changed-descendant?]
    :or   {default-expanded-depth default-ceiling-depth}}]
  (cond
    has-changed-descendant?
    true

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

;; rf2-h71e0 — forward declaration for the zoom affordance button
;; rendered inside `render-container`'s header row. The definition
;; lives further down alongside the breadcrumb component + popup
;; affordance (all three are top-level chrome surfaces).
(declare zoom-affordance-button)

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
            sep
            [:span {:style (token-style :text-tertiary)} ", "]
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
   - zoomable? / zoom-path-prefix — when zoomable? true (rf2-h71e0), the
                                    container renders a `⊙` zoom-in
                                    affordance next to the expand
                                    triangle. zoom-path-prefix is the
                                    absolute path of the CURRENT zoom
                                    root within the original value; the
                                    affordance dispatches with `(into
                                    zoom-path-prefix path)` so the
                                    zoom-slot stores the full path."
  [{:keys [value kind panel-id mount-id path depth expansion-map opts
           dispatch-fn diff? before zoomable? zoom-path-prefix]}]
  (let [{:keys [default-expanded-depth max-depth max-inline-width
                available-width-px]
         :or {default-expanded-depth default-ceiling-depth
              max-depth 16
              max-inline-width 60}} opts
        cnt           (child-count value kind)
        empty?        (zero? cnt)
        depth-capped? (>= depth max-depth)
        ;; Diff: classify this container's op vs `before`. Only
        ;; meaningful when diff? is true; otherwise everything is
        ;; `:same` and the gutter rows collapse to a transparent
        ;; left border.
        op            (if diff? (container-op before value) :same)
        has-change?   (and diff? (not= op :same))
        default?      (and (not depth-capped?)
                           (default-expanded?
                             {:depth                   depth
                              :child-count             cnt
                              :default-expanded-depth  default-expanded-depth
                              :has-changed-descendant? has-change?
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
        children      (when (and (not empty?) (not depth-capped?) expanded? (not inline-fit?))
                        (children-of value))
        ;; rf2-h71e0 — zoom affordance is rendered next to the expand
        ;; triangle on every non-empty container at a NON-ROOT relative
        ;; path. The root (`[]`) skips the affordance because zooming
        ;; into the current zoom root is a no-op. The button dispatches
        ;; with the ABSOLUTE path = `(into zoom-path-prefix path)` so
        ;; the zoom-slot stores the full path from the original root.
        zoom-button   (when (and zoomable?
                                 (not empty?)
                                 (seq path))
                        (zoom-affordance-button
                          {:dispatch-fn   dispatch-fn
                           :panel-id      panel-id
                           :mount-id      mount-id
                           :absolute-path (into (vec zoom-path-prefix) path)
                           :testid        (str (testid-for panel-id mount-id path)
                                               "-zoom-affordance")}))]
    [:div {:data-testid (testid-for panel-id mount-id path)
           :data-rf-kind (name kind)
           :data-rf-expanded (if expanded? "1" "0")
           :data-rf-diff-op (when diff? (name op))
           :style {:font-family mono-stack
                   :line-height 1.4}}
     ;; ---- header row ---------------------------------------------------
     [:div {:style {:display "flex"
                    :align-items "baseline"
                    :gap "4px"
                    :flex-wrap "wrap"}}
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
                         :role       "button"
                         :tabindex   0
                         :aria-expanded false
                         :data-testid (str (testid-for panel-id mount-id path) "-toggle")
                         ;; rf2-tzvk9 — ≥24×24 click target via the shared
                         ;; `triangle-style` (padding + font-size + min-width/
                         ;; -height).
                         :style triangle-style}
                  "▸"]]
          zoom-button (conj zoom-button)
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
                                     (let [sep (when (pos? i)
                                                 [:span {:style (token-style :text-tertiary)} ", "])
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
          (if zoom-button
            ;; rf2-h71e0 — wrap the inline render in a flex span so the
            ;; zoom button sits next to (and aligned with) the inline
            ;; content. The inline-fit path has no toggle triangle, so
            ;; the affordance leads.
            [:span {:style {:display "inline-flex"
                            :align-items "baseline"
                            :gap "4px"}}
             zoom-button
             inline-render]
            inline-render))

        ;; Default — toggle glyph + open bracket (when expanded) OR summary.
        expanded?
        (cond-> [:span {:style {:display "inline-flex" :align-items "center" :gap "4px"}}
                 [:span {:on-click   toggle-fn
                         :role       "button"
                         :tabindex   0
                         :aria-expanded true
                         :data-testid (str (testid-for panel-id mount-id path) "-toggle")
                         ;; rf2-tzvk9 — ≥24×24 click target via the shared
                         ;; `triangle-style`.
                         :style triangle-style}
                  "▾"]]
          zoom-button (conj zoom-button)
          true        (conj (bracket kind :open value)))

        :else
        (cond-> [:span {:style {:display "inline-flex" :align-items "center" :gap "6px"}}
                 [:span {:on-click   toggle-fn
                         :role       "button"
                         :tabindex   0
                         :aria-expanded false
                         :data-testid (str (testid-for panel-id mount-id path) "-toggle")
                         ;; rf2-tzvk9 — ≥24×24 click target via the shared
                         ;; `triangle-style`.
                         :style triangle-style}
                  "▸"]]
          zoom-button (conj zoom-button)
          true        (conj (collapsed-summary value kind))))]

     ;; ---- body — children rendered indented -----------------------------
     ;;
     ;; Diff threading: for each child we compute the matching `:before`
     ;; slice (or `::missing` if the slot didn't exist pre-diff). This
     ;; lets the child's recursion render its own gutter row / inline
     ;; `← changed from <prior>` annotation; the parent's `:children`
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
             child-pairs
             (cond
               (and diff? (= kind :map) (map? before))
               ;; Map: walk union of keys; carry both v and b per key.
               (let [all-keys (vec (into (set (keys value)) (keys before)))]
                 (for [k all-keys]
                   [k (get value k ::missing) (get before k ::missing)]))

               (and diff? (#{:vector :list :seq} kind) (sequential? before))
               ;; Sequential: index-align; treat extra trailing items.
               (let [a-vec (vec value)
                     b-vec (vec before)
                     n     (max (count a-vec) (count b-vec))]
                 (for [i (range n)]
                   [i
                    (if (< i (count a-vec)) (nth a-vec i) ::missing)
                    (if (< i (count b-vec)) (nth b-vec i) ::missing)]))

               :else
               ;; Non-diff path, or diff with no comparable structure
               ;; on the `before` side — render the present children as-is.
               (for [[k cv] children]
                 [k cv (if diff? ::missing ::missing)]))]
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
           (into [:div {:data-testid (str (testid-for panel-id mount-id path) "-body")
                        :data-rf-body-layout "grid"
                        :style body-grid-style}]
                 (mapcat
                   (fn [[k cv cb]]
                     (let [child-path (conj (vec path) k)
                           value-node (render-node
                                        {:value cv
                                         :before cb
                                         :diff? diff?
                                         :panel-id panel-id
                                         :mount-id mount-id
                                         :path child-path
                                         :depth (inc depth)
                                         :expansion-map expansion-map
                                         :dispatch-fn dispatch-fn
                                         :zoomable? zoomable?
                                         :zoom-path-prefix zoom-path-prefix
                                         :opts opts})]
                       [(with-meta
                          ;; Key cell — uses `div` so the grid baseline
                          ;; aligns predictably across rows. `white-
                          ;; space: nowrap` prevents long keys (e.g. a
                          ;; deeply-namespaced `:rf.x.with.many.parts/k`)
                          ;; from wrapping inside the key column.
                          [:div {:data-rf-cell "key"
                                 :style key-cell-style}
                           (key-segment k)]
                          {:key (str "k-" (pr-str k))})
                        (with-meta
                          [:div {:data-rf-cell "value"
                                 :style value-cell-style}
                           value-node]
                          {:key (str "v-" (pr-str k))})]))
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
           (into [:div {:data-testid (str (testid-for panel-id mount-id path) "-body")
                        :data-rf-body-layout "block"
                        :style body-block-style}]
                 (map
                   (fn [[k cv cb]]
                     (let [child-path (conj (vec path) k)]
                       (with-meta
                         (render-node {:value cv
                                       :before cb
                                       :diff? diff?
                                       :panel-id panel-id
                                       :mount-id mount-id
                                       :path child-path
                                       :depth (inc depth)
                                       :expansion-map expansion-map
                                       :dispatch-fn dispatch-fn
                                       :zoomable? zoomable?
                                       :zoom-path-prefix zoom-path-prefix
                                       :opts opts})
                         {:key (str "v-" (pr-str k))})))
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
  `← changed from <prior>` chip on `:modified`) survive because they
  are non-colour signals — strike-through is a TEXT-DECORATION
  channel; the chip is a separate inline element.

  - `:added`    — render `value`,  gutter `+`, green wash + stripe
  - `:removed`  — render `before` (strike-through), gutter `-`,
                  red wash + stripe
  - `:modified` — render `value` + `← changed from <prior>` chip,
                  gutter `~`, amber wash + stripe
  - `:same`     — render `value` (dimmed via `:text-tertiary` so the
                  eye lands on the changes)"
  [{:keys [value before diff?]}]
  (if-not diff?
    (render-scalar value)
    (let [op (diff-op before value)]
      (case op
        :added
        (gutter-row :added
                    [:span {:data-rf-diff-op "added"}
                     ;; rf2-awqts — render-scalar paints per-token
                     ;; syntax colour; wash + stripe carry the diff
                     ;; signal at the row level.
                     (render-scalar value)])
        :removed
        (gutter-row :removed
                    [:span {:data-rf-diff-op "removed"
                            ;; rf2-awqts — strike-through is a
                            ;; non-colour signal that survives the
                            ;; move to row chrome; keep it so a
                            ;; removed leaf still reads as deleted at
                            ;; the glyph level.
                            :style {:text-decoration "line-through"}}
                     (render-scalar before)])
        :modified
        (gutter-row :modified
                    [:span {:data-rf-diff-op "modified"
                            :style {:display "inline-flex"
                                    :align-items "baseline"
                                    :flex-wrap "wrap"
                                    :gap "4px"}}
                     ;; rf2-awqts — drop the per-leaf colour override;
                     ;; render-scalar emits the value with its
                     ;; `:syntax-*` token colour intact.
                     (render-scalar value)
                     (change-annotation before)])
        :same
        (gutter-row :same
                    [:span {:data-rf-diff-op "same"
                            ;; rf2-awqts — `:same` keeps the dim
                            ;; `:text-tertiary` override on purpose:
                            ;; in a diff context the unchanged rows
                            ;; SHOULD recede so the eye lands on the
                            ;; changes. This is the only op where the
                            ;; diff path still tints text colour, and
                            ;; the choice is dimming-not-replacing.
                            :style {:color (:text-tertiary tokens)}}
                     (render-scalar value)])))))

(defn render-node
  "Recursive entry. Picks container vs scalar; threads the
  expansion-map snapshot down. Returns hiccup. Pure projection of
  (value, expansion-map, opts) — no `rf/subscribe` calls in here.

  Consults `IXrayEdnInspector` at the head — if `value` satisfies the
  protocol AND the consumer's `-xray-render-header` returns non-nil
  hiccup, the protocol path wins. Otherwise falls through to the
  built-in container / scalar dispatch (phase 7 / rf2-0qrcr).

  Diff mode: when `:diff?` is true the renderer paints gutter rows +
  `← changed from <prior>` annotations; when `:before` is
  `::missing`, the node is rendered as `:added`; when `value` is
  `::missing`, as `:removed`.

  `:dispatch-fn` (optional) is the frame-aware dispatcher captured by
  the surrounding `reg-view` body (rf2-y59tb) so toggle clicks land on
  the same frame the widget is mounted under. Tests / programmatic
  callers that drive render-node without a mount can omit it — the
  container-renderer falls back to the global `rf/dispatch`.

  Public so unit tests can drive the renderer without mounting."
  [{:keys [value before diff? panel-id mount-id path depth expansion-map
           dispatch-fn zoomable? zoom-path-prefix opts]
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
      ;; Diff mode: removed slot — render the prior value struck-through.
      ;; The `value` side is `::missing` but `before` carries the slot
      ;; that's being removed. Always a leaf-shaped row (containers
      ;; collapse to one removed line — operator follows the gutter, not
      ;; the structure, for deletions).
      (and diff? (= value ::missing))
      (render-leaf-with-diff {:value ::missing :before before :diff? true})

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
                             :before ::missing})
          (render-leaf-with-diff {:value value :before ::missing :diff? true})))

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
                             :before before})
          (render-leaf-with-diff {:value value
                                  :before before
                                  :diff? (boolean diff?)}))))))

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
;; zoom affordance + breadcrumb (rf2-h71e0)
;; =========================================================================
;;
;; Two surfaces:
;;
;; 1. `zoom-affordance-button` — inline `⊙` button rendered next to the
;;    expand triangle on every container when `:zoomable? true`. Click
;;    dispatches `:zoom-to` with the node's ABSOLUTE path from the
;;    original root (the renderer's per-node `:path` is RELATIVE to the
;;    current zoom root, so the dispatch composes `zoom-path-prefix` +
;;    `path` before storing).
;;
;; 2. `zoom-breadcrumbs` — segmented nav at the top of the inspector
;;    when the zoom path is non-empty. First segment is the `:header`
;;    hiccup (or generic "root" fallback); subsequent segments render
;;    one key/index per zoom-path level. Each segment dispatches
;;    `:zoom-to` with a TRUNCATED path so clicking segment N pops the
;;    zoom back to N levels deep.

(def ^:private zoom-affordance-glyph
  ;; `⊙` (circled-dot) reads as "focus / aim cursor at this node" — the
  ;; mental model the bead body identified (Chrome devtools' object
  ;; inspector + nav, IDE nav-to-symbol). Visually distinct from the
  ;; popup affordance (`↗` — "open in new pane"), the expand triangles
  ;; (`▸`/`▾` — toggle), and the breadcrumb separator (`›`). Single
  ;; codepoint, theme-token coloured.
  "⊙")

(def ^:private breadcrumb-separator
  ;; `›` (single right-pointing angle) reads as nav direction — same
  ;; convention as file-browser breadcrumbs + IDE path bars. Distinct
  ;; from `→` (transition / arrow) and `>` (greater-than / blockquote).
  "›")

(def ^:private zoom-affordance-button-style
  {:background    "transparent"
   :border        "none"
   :color         (:text-tertiary tokens)
   :font-size     "13px"
   :line-height   1
   :cursor        "pointer"
   :padding       "0 4px"
   :margin        "0"
   :border-radius "3px"
   ;; Subtle by default — matches the popup affordance's resting opacity
   ;; so the operator's eye reads "secondary affordance" at both glyphs.
   ;; Theme-aware via the token resolution.
   :opacity       0.55
   :display       "inline-flex"
   :align-items   "center"
   :user-select   "none"})

(defn zoom-affordance-button
  "Inline `⊙` zoom-in button. Renders next to the expand triangle on
  containers when `:zoomable? true`. Click dispatches `:zoom-to` with
  the absolute path from the original root (composed by the caller as
  `(into zoom-path-prefix path)`).

  `dispatch-fn` is the lexically-captured frame-aware dispatcher (so the
  event lands on the same frame the widget is mounted under). `panel-id`
  + `mount-id` (or `site-id`) key the zoom slot. `absolute-path` is the
  vec the reducer stores verbatim.

  Public so unit tests can drive the button without mounting."
  [{:keys [dispatch-fn panel-id mount-id absolute-path testid]}]
  (let [dispatch-fn (or dispatch-fn rf/dispatch*)]
    [:button
     {:data-testid        testid
      :data-rf-affordance "zoom"
      :aria-label         "Zoom into this node"
      :title              "Zoom into this node"
      :on-click           (fn [^js e]
                            (when e
                              (.preventDefault e)
                              (.stopPropagation e))
                            (dispatch-fn
                              [:rf.xray.edn-inspector/zoom-to
                               panel-id mount-id (vec absolute-path)]))
      :style              zoom-affordance-button-style}
     zoom-affordance-glyph]))

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
  lexically-captured frame-aware dispatcher reserved for the
  PER-FRAME paths (legacy parameter — see dispatch-asymmetry note
  below); `popup-mount-id` is the stable id keyed to the data-
  display's own mount-id; `value` + `opts` are the popup's payload.

  ## Dispatch asymmetry (rf2-7sdja)

  The popup OPEN event dispatches against `:rf/xray` EXPLICITLY via
  the established `(rf/dispatch event {:frame :rf/xray})` pattern
  (matches `settings/view.cljs` + `app_db_segment_inspector.cljs`).
  Other edn-inspector dispatches (`:rf.xray.edn-inspector/toggle-node`)
  use the lexically-captured `dispatch-fn` because expansion state is
  per-frame — the widget can be mounted in any frame and the toggle
  dispatch must land in the SURROUNDING frame's app-db so the
  expansion-slot subscription sees the write.

  Popup state is different: the popup stack-view (`edn-inspector-popup-
  stack` in `shell.cljs`) is mounted inside `[rf/frame-provider
  {:frame :rf/xray}]` and subscribes ONLY against `:rf/xray`'s
  app-db. If a popup-open dispatch leaks to `:rf/default` (or any
  non-Xray frame), the mutation lands on the wrong app-db and the
  stack-view never renders the popup — the canonical bug Mike
  reproduced live (pair-debug 2026-05-26). The fix pins the popup
  dispatch to `:rf/xray` REGARDLESS of where the widget mounts:
  expansion state stays per-frame; popup state stays Xray-global.

  The `dispatch-fn` parameter is retained as a no-op for back-compat
  with the existing test surface (some tests pass a stub to capture
  the event vector). Production callers should pass `nil` —
  `popup-affordance-button` ignores it and dispatches directly.

  Public so unit tests can drive the button without spinning up the
  router."
  [_dispatch-fn popup-mount-id value opts]
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
                               ;; rf2-7sdja — popup state is Xray-
                               ;; global. Pin the dispatch frame to
                               ;; `:rf/xray` regardless of the
                               ;; surrounding mount frame so the
                               ;; popup-stack-view (which only
                               ;; subscribes against `:rf/xray`)
                               ;; sees the write.
                               (rf/dispatch
                                 [:rf.xray.edn-inspector-popup/open
                                  popup-mount-id
                                  {:value value
                                   :opts  (-> (or opts {})
                                              ;; Don't recurse the
                                              ;; affordance inside the
                                              ;; popup's embedded
                                              ;; edn-inspector.
                                              (assoc :popup-affordance? false))}]
                                 {:frame :rf/xray}))
    :style                   popup-affordance-button-style}
   ;; rf2-7sdja — ↗ (north-east arrow) reads as "open in new pane /
   ;; navigate outward" which matches the popup's window-manager
   ;; semantics better than ⊕ (which read as "expand" / "add"). Same
   ;; aria-label / title — the glyph swap is visual only.
   "↗"])

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
    `← changed from <prior>` annotations, force-expands the
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
                    max-depth before popup-affordance? card? header zoomable?]
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
            diff?         (contains? opts :before)
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
            ;; supplied, else the auto-mount-id). Diff mode ALSO ignores
            ;; zoom (`zoom-active?` short-circuits when `diff?` is true)
            ;; because the diff path's force-expand-over-changed-
            ;; descendants logic + zoom's hide-everything-outside the
            ;; subtree are conflicting intents. Operators view diffs
            ;; over the FULL value; non-diff browse can zoom.
            zoom-map      (when zoomable? @(subscribe [zoom-slot]))
            zoom-path     (resolve-zoom-path zoom-map panel-id
                                             (or site-id mount-id))
            zoom-active?  (and zoomable? (not diff?) (seq zoom-path))
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
            body-content  (render-node
                            {:value displayed-value
                             :before (if diff? displayed-before ::missing)
                             :diff? diff?
                             :panel-id panel-id
                             ;; rf2-pvsxs — `mount-id` slot in
                             ;; render-node's key carries the
                             ;; EFFECTIVE id (site-id if supplied;
                             ;; auto-mount-id otherwise). Callbacks
                             ;; deep in the recursion thread the same
                             ;; value, so toggle dispatches store +
                             ;; read overrides under the persistence-
                             ;; friendly key.
                             :mount-id effective-id
                             :path []
                             :depth 0
                             :expansion-map expansion-map
                             :dispatch-fn dispatch-fn
                             ;; rf2-h71e0 — `:zoomable?` enables the
                             ;; per-container `⊙` affordance during the
                             ;; recursive walk. `:zoom-path-prefix` is
                             ;; the absolute path of the displayed-
                             ;; subtree's root within the original
                             ;; value; per-container affordances
                             ;; compose this prefix with their relative
                             ;; `:path` to produce the absolute path
                             ;; the dispatched event stores.
                             :zoomable? zoomable?
                             :zoom-path-prefix zoom-path-prefix
                             :opts {:default-expanded-depth default-expanded-depth
                                    :max-inline-width max-inline-width
                                    :max-depth max-depth
                                    ;; rf2-kbdk8 — threaded so every
                                    ;; recursive render-node decides
                                    ;; expansion against the same
                                    ;; available column width. Nil
                                    ;; until the ref measures, after
                                    ;; which ResizeObserver keeps it
                                    ;; live.
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
                     :data-rf-popup-affordance? (when popup-affordance? "1")
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
                 :data-rf-popup-affordance? (when popup-affordance? "1")
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
